package com.twentyone.rhythm

import android.app.job.*
import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.*

data class ReviewReport(val status:String,val facts:String="",val answer:String="",val message:String="",val at:Long=System.currentTimeMillis())
class ReviewStore(private val c:Context) {
    val prefs=ProjectPreferences.get(c,"habit_reviews")
    val settings=ProjectPreferences.get(c,"review_settings")
    fun get(id:String):ReviewReport?=prefs.getString(id,null)?.let { raw -> JSONObject(raw).let { ReviewReport(it.getString("status"),it.optString("facts"),it.optString("answer"),it.optString("message"),it.getLong("at")) } }
    fun save(id:String,r:ReviewReport) { prefs.edit().putString(id,JSONObject().put("status",r.status).put("facts",r.facts).put("answer",r.answer).put("message",r.message).put("at",r.at).put("generationDevice",ProjectAccount.device(c)).toString()).apply() }
    fun remoteInFlight(id:String):Boolean=prefs.getString(id,null)?.let { raw -> JSONObject(raw).let { it.optString("status") in setOf("running","waiting") && it.optString("generationDevice").let { device->device.isNotBlank() && device!=ProjectAccount.device(c) } } } ?: false
    fun specs(c:Context):List<ReviewSpec> {
        val since=settings.getString("since",null) ?: LocalDate.now().toString().also { settings.edit().putString("since",it).apply() }
        return Reviews.specs(HabitStore(c).habits(),Store(c).plan,LocalDate.parse(since),true,true)
    }
}
object ReviewRunner {
    private val mutex=Mutex()
    suspend fun generate(c:Context,spec:ReviewSpec,retry:Boolean=false)=mutex.withLock {
        require(!spec.due.isAfter(LocalDateTime.now())) { "这个周期尚未结束" }
        val store=ReviewStore(c);val old=store.get(spec.id)
        if(store.remoteInFlight(spec.id))return@withLock
        if(old?.status=="done" || (old?.status=="failed" && !retry)) return@withLock
        val habits=HabitStore(c);val h=habits.habits().find { it.id==spec.subject }
        val facts=if(h!=null) Reviews.habitFacts(h,habits.entries(),spec)
            else Store(c).let { CoachAnalysis.facts(it.logs(),spec.to,7,it.plan).text }
        store.save(spec.id,ReviewReport("running",facts))
        try {
            val answer=CoachInference.generate(c,Reviews.prompt(spec,facts))
            currentCoroutineContext().ensureActive()
            store.save(spec.id,ReviewReport("done",facts,answer))
        } catch(e:TimeoutCancellationException) {
            store.save(spec.id,ReviewReport("failed",facts,message="等待 DeepSeek 超时，请在看板重试"))
        } catch(e:CancellationException) {
            store.save(spec.id,ReviewReport("waiting",facts,message="生成中断，等待系统重新安排"));throw e
        } catch(e:Exception) {
            store.save(spec.id,ReviewReport("failed",facts,message=e.message?.take(200) ?: "生成失败，请重试"))
        }
    }
}
object ReviewScheduler {
    const val JOB=310
    fun schedule(c:Context) {
        val store=ReviewStore(c);val now=LocalDateTime.now()
        val scheduler=c.getSystemService(JobScheduler::class.java)
        val eligible=store.specs(c).filter { store.settings.getBoolean(if(it.kind=="week") "weekly" else "cycle",true) }
        val old=scheduler.getPendingJob(JOB)
        if(CloudCoach.configured(c) && eligible.any { it.id==old?.extras?.getString("id") && store.get(it.id)?.let { r->r.status=="running" && System.currentTimeMillis()-r.at<600_000 }==true }) return
        val next=eligible.firstOrNull { spec ->
            val report=store.get(spec.id)
            !store.remoteInFlight(spec.id) && (report==null || report.status=="waiting" || (report.status=="running" && System.currentTimeMillis()-report.at>600_000))
        }
        if(next==null || !CloudCoach.configured(c)) { scheduler.cancel(JOB);return }
        val due=maxOf(next.due.epoch(),now.epoch()+1000)
        if(old?.extras?.getString("id")==next.id && old.extras.getLong("due")==next.due.epoch()) return
        scheduler.schedule(JobInfo.Builder(JOB,ComponentName(c,ReviewJobService::class.java))
            .setMinimumLatency((due-System.currentTimeMillis()).coerceAtLeast(0)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setExtras(PersistableBundle().apply { putString("id",next.id);putLong("due",next.due.epoch()) }).build())
    }
}
class ReviewJobService:JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var work:Job?=null
    override fun onStartJob(params:JobParameters):Boolean {
        val store=ReviewStore(this)
        val spec=store.specs(this).find { it.id==params.extras.getString("id") && !it.due.isAfter(LocalDateTime.now()) && store.settings.getBoolean(if(it.kind=="week") "weekly" else "cycle",true) }
        if(spec==null || !CloudCoach.configured(this) || store.remoteInFlight(spec.id)) return false
        work=scope.launch {
            try { ReviewRunner.generate(this@ReviewJobService,spec) }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { store.save(spec.id,ReviewReport("failed",message=e.message?.take(200) ?: "生成失败，请重试")) }
            finally {
                if(currentCoroutineContext().isActive) {
                    jobFinished(params,false)
                    Handler(Looper.getMainLooper()).post { ReviewScheduler.schedule(applicationContext) }
                }
            }
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean { work?.cancel();return true }
    override fun onDestroy() { scope.cancel();super.onDestroy() }
}
