package com.twentyone.rhythm

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class SyncStatus(val enabled:Boolean,val pending:Int,val conflicts:Int,val lastSuccess:Long,val message:String)

object ProjectSync {
    private const val endpoint="https://zhuisu.leadjet.com.cn/21day-api/v1/sync/"
    private const val jobId=2105
    private val mutex=Mutex()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val requests=Channel<Context>(Channel.CONFLATED)
    init { scope.launch {
        for(context in requests) {
            delay(250)
            // Coalesce triggers, not the durable operations: every check-in keeps its own operationId.
            while(requests.tryReceive().isSuccess) { }
            try { run(context) }catch(e:CancellationException){throw e}catch(_:Exception){ /* The durable queue and fallback job retain retries. */ }
        }
    } }
    fun observeConnectivity(c:Context) {
        val context=c.applicationContext
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object:ConnectivityManager.NetworkCallback() {
            private var available:Network?=null
            override fun onCapabilitiesChanged(network:Network,capabilities:NetworkCapabilities) {
                if(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    if(available!=network){available=network;schedule(context)}
                }else if(available==network)available=null
            }
            override fun onLost(network:Network) { if(available==network)available=null }
        })
    }
    private fun database(c:Context)=PreferenceDatabase(c.applicationContext,ProjectPreferences.databaseName)
    internal suspend fun request(c:Context,path:String,body:JSONArray?=null):Any=withContext(Dispatchers.IO) {
        val connection=URL(endpoint+path).openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects=false;connection.connectTimeout=15000;connection.readTimeout=30000
        connection.setRequestProperty("Authorization","Bearer "+ProjectAccount.accessToken(c))
        connection.setRequestProperty("Accept","application/json")
        try {
            if(body!=null) { connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
            val status=connection.responseCode
            check(status !in 300..399) { "同步地址发生变化，请稍后重试。" }
            check(status in 200..299) { if(status in setOf(401,403))"登录已失效或无同步权限，请重新登录。" else "同步服务暂不可用（$status），本机记录已保留。" }
            val text=connection.inputStream.use { input -> val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;check(out.size()+n<=8*1024*1024){"同步响应过大，请稍后重试。"};out.write(buffer,0,n)};out.toString("UTF-8") }
            if(body!=null)JSONArray(text) else JSONObject(text)
        } finally { connection.disconnect() }
    }
    private fun prepare(c:Context) {
        SyncCodec.namespaces.forEach { ProjectPreferences.get(c,it) }
        database(c).use { helper ->
            val changed=SyncJournal.transaction(helper.writableDatabase) {
                val device=ProjectAccount.device(c)
                val repaired=SyncJournal.removeNoChanges(helper.writableDatabase,device)
                SyncJournal.capture(helper.writableDatabase,device);repaired
            }
            ProjectPreferences.changed(c,changed)
        }
    }
    fun status(c:Context):SyncStatus=database(c).use { helper -> val db=helper.readableDatabase
        SyncStatus(SyncJournal.metadata(db,"enabled")=="true",SyncJournal.pending(db),SyncJournal.conflicts(db),SyncJournal.metadata(db,"last_success")?.toLongOrNull() ?: 0L,SyncJournal.metadata(db,"message") ?: "尚未开启同步")
    }
    suspend fun preflight(c:Context):String=withContext(Dispatchers.IO) {
        check(ProjectAccount.userId(c)!=null){"请先登录。"};prepare(c)
        val remote=request(c,"pull?after=0&maximum=1") as JSONObject
        "本机有 ${status(c).pending} 项待同步变更。"+(if(remote.getLong("highWatermark")>0)"云端已有记录，将逐项合并；同一内容的不同修改会保留为冲突。" else "云端尚无记录。")+"\n习惯、作息历史、聊天、复盘及普通设置将绑定当前账号。设备权限需重新授权，模型密钥和验证点暂留本机。"
    }
    suspend fun enable(c:Context)=withContext(Dispatchers.IO) { mutex.withLock {
        val user=ProjectAccount.userId(c) ?: error("请先登录。")
        prepare(c);database(c).use { helper -> SyncJournal.transaction(helper.writableDatabase) {
            val owner=SyncJournal.metadata(helper.writableDatabase,"owner")
            check(owner==null || owner==user){"同步数据属于其他账号。"}
            SyncJournal.metadata(helper.writableDatabase,"owner",user);SyncJournal.metadata(helper.writableDatabase,"enabled","true")
        } };schedule(c)
    } }
    suspend fun disable(c:Context)=withContext(Dispatchers.IO) { mutex.withLock {
        database(c).use { SyncJournal.metadata(it.writableDatabase,"enabled","false") }
        c.getSystemService(JobScheduler::class.java).cancel(jobId)
    } }
    suspend fun run(c:Context):SyncStatus=withContext(Dispatchers.IO) { mutex.withLock {
        val user=ProjectAccount.userId(c) ?: return@withLock status(c)
        if(!status(c).enabled)return@withLock status(c)
        prepare(c)
        database(c).use { helper ->
            val db=helper.writableDatabase;val device=ProjectAccount.device(c)
            fun checkOwner() { check(ProjectAccount.userId(c)==user && SyncJournal.metadata(db,"owner")==user){"账号已退出，同步已停止。"} }
            suspend fun pull() {
                do {
                    checkOwner();val cursor=SyncJournal.metadata(db,"cursor")?.toLong() ?: 0L
                    val page=request(c,"pull?after=$cursor&maximum=100") as JSONObject;checkOwner()
                    ProjectPreferences.changed(c,SyncJournal.pull(db,page,device))
                }while(page.getLong("cursor")<page.getLong("highWatermark"))
            }
            try {
                ProjectPreferences.changed(c,SyncJournal.applyDeferred(db,device))
                pull()
                var sent=0
                while(sent<200) {
                    currentCoroutineContext().ensureActive();checkOwner()
                    val op=SyncJournal.next(db) ?: break
                    val result=request(c,"push",JSONArray().put(op)) as JSONArray;checkOwner();require(result.length()==1)
                    ProjectPreferences.changed(c,SyncJournal.acknowledge(db,op,result.getJSONObject(0),device));sent++
                }
                pull()
                val pending=SyncJournal.pending(db);val conflicts=SyncJournal.conflicts(db)
                SyncJournal.metadata(db,"message",if(conflicts>0)"有 $conflicts 项冲突，请在下方选择保留的版本。" else if(pending>0)"本机已保存，剩余 $pending 项待同步。" else if(SyncJournal.metadata(db,"deferred_sleep")!=null)"记录已同步，作息调整将在当前夜间限制或起床验证结束后应用。" else "已同步")
                if(pending==0)SyncJournal.metadata(db,"last_success",System.currentTimeMillis().toString())
                if(SyncJournal.next(db)!=null)scheduleBackground(c)
            }catch(e:CancellationException){throw e}catch(e:Exception) {
                SyncJournal.metadata(db,"message",if(e is java.io.IOException)"网络不可用，本机变更已保留，联网后重试。" else e.message?.take(160) ?: "同步未完成，本机记录已保留。")
                throw e
            }
        };status(c)
    } }
    fun conflicts(c:Context)=database(c).use { SyncJournal.conflictItems(it.readableDatabase) }
    internal fun timerClaimPending(c:Context,id:String)=database(c).use { helper -> helper.readableDatabase.rawQuery("SELECT 1 FROM sync_outbox WHERE entity_type='habits' AND entity_id=? AND json_extract(request,'$.kind')='timer_takeover' LIMIT 1",arrayOf(id)).use { it.moveToFirst() } }
    suspend fun resolve(c:Context,type:String,id:String,useLocal:Boolean)=withContext(Dispatchers.IO) { mutex.withLock {
        database(c).use { ProjectPreferences.changed(c,SyncJournal.resolve(it.writableDatabase,type,id,useLocal,ProjectAccount.device(c))) };schedule(c)
    } }
    fun schedule(c:Context) {
        if(runCatching { ProjectAccount.userId(c)==null || !status(c).enabled }.getOrDefault(true))return
        scheduleBackground(c)
        requests.trySend(c.applicationContext)
    }
    private fun scheduleBackground(c:Context) {
        val scheduler=c.getSystemService(JobScheduler::class.java)
        if(scheduler.getPendingJob(jobId)==null)scheduler.schedule(JobInfo.Builder(jobId,ComponentName(c,ProjectSyncJob::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(2000).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).setPersisted(true).build())
    }
}

class ProjectSyncJob:JobService() {
    private var work:Job?=null
    override fun onStartJob(params:JobParameters):Boolean {
        work=CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            val retry=try { ProjectSync.run(applicationContext).let { ProjectAccount.userId(applicationContext)!=null && it.enabled && it.pending>it.conflicts } }catch(e:CancellationException){throw e}catch(e:Exception){ProjectAccount.userId(applicationContext)!=null}
            jobFinished(params,retry)
        };return true
    }
    override fun onStopJob(params:JobParameters):Boolean { work?.cancel();return true }
}
