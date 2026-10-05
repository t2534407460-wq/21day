package com.twentyone.rhythm

import android.app.job.JobScheduler
import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.net.*
import java.security.cert.Certificate
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/** Opt-in disposable emulator + the real local HTTP/PG fixture. No production account or endpoint is contacted. */
class AutomaticSyncTest {
    private val c=InstrumentationRegistry.getInstrumentation().targetContext
    private val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var helper:PreferenceDatabase
    private val upgrade get()=InstrumentationRegistry.getArguments().getString("autoUpgrade")
    companion object {
        @Volatile private var owner=""
        @Volatile private var offline=false
        private val requests=AtomicInteger()
        @BeforeClass @JvmStatic fun intercept() {
            Assume.assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish") && InstrumentationRegistry.getArguments().getString("syncHttpFixture")=="true")
            URL.setURLStreamHandlerFactory { protocol -> if(protocol!="https")null else object:URLStreamHandler() {
                override fun openConnection(url:URL):URLConnection {
                    if(!url.toString().startsWith("https://zhuisu.leadjet.com.cn/21day-api/v1/sync/"))throw IOException("Unrelated endpoint disabled in QA")
                    return LocalConnection(url,owner)
                }
            } }
        }
        private fun http(path:String,body:String?=null,asOwner:String=owner):String {
            Socket("127.0.0.1",18769).use { socket ->
                socket.soTimeout=10000;val bytes=(body ?: "").toByteArray(Charsets.UTF_8)
                val head="${if(body==null)"GET" else "POST"} $path HTTP/1.1\r\nHost: localhost\r\nX-QA-Owner: $asOwner\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(head.toByteArray(Charsets.US_ASCII));socket.getOutputStream().write(bytes);socket.getOutputStream().flush()
                val response=socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                check(response.startsWith("HTTP/1.1 200")){response.take(120)}
                return response.substringAfter("\r\n\r\n")
            }
        }
        private class LocalConnection(url:URL,private val account:String):HttpsURLConnection(url) {
            private val sent=ByteArrayOutputStream();private var reply:String?=null
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy()=false
            override fun getCipherSuite()="QA"
            override fun getLocalCertificates():Array<Certificate>?=null
            override fun getServerCertificates():Array<Certificate> = emptyArray()
            override fun getOutputStream():OutputStream=sent
            override fun getResponseCode():Int {
                requests.incrementAndGet();if(offline)throw IOException("Synthetic offline")
                reply=http(url.file,if(doOutput)sent.toString("UTF-8")else null,account);return 200
            }
            override fun getInputStream():InputStream=ByteArrayInputStream(reply!!.toByteArray(Charsets.UTF_8))
        }
    }
    @Before fun setup() {
        device.pressHome();runBlocking { ProjectSync.disable(c) };offline=false
        helper=PreferenceDatabase(c,ProjectPreferences.databaseName)
        if(upgrade=="verify") { owner=ProjectAccount.userId(c)!!;return }
        SyncCodec.namespaces.forEach { ProjectPreferences.get(c,it).edit().clear().commit() }
        val db=helper.writableDatabase
        SyncJournal.transaction(db) {
            listOf("sync_local","sync_remote","sync_outbox","sync_conflict_history","sync_metadata").forEach { db.execSQL("DELETE FROM $it") }
        }
        owner=UUID.randomUUID().toString();requests.set(0)
        val write=ProjectAccount::class.java.getDeclaredMethod("write",android.content.Context::class.java,String::class.java,JSONObject::class.java).apply{isAccessible=true}
        write.invoke(ProjectAccount,c,"session",JSONObject().put("userId",owner).put("email","automatic@example.test").put("accessToken","synthetic-local-only").put("expiresAt",System.currentTimeMillis()+3600000))
        Store(c).migrateRestSchedule()
        CloudCoach.removeKey(c)
    }
    @After fun cleanup() {
        device.pressHome();runBlocking { ProjectSync.disable(c) };offline=false
        if(upgrade!="seed")File(c.noBackupFilesDir,"account-session.json").delete();helper.close()
    }
    private fun waitUntil(message:String,condition:()->Boolean) {
        val until=System.currentTimeMillis()+5000
        while(!condition() && System.currentTimeMillis()<until)Thread.sleep(80)
        assertTrue("$message; ${ProjectSync.status(c)}; requests=${requests.get()}; conflicts=${ProjectSync.conflicts(c)}",condition())
    }
    private fun seed():Habit {
        val h=Habit(name="控烟",start=LocalDate.now(),mode=HabitMode.AT_MOST,unit="支",input=HabitInput.COUNT,smoking=true,rules=listOf(HabitRule(LocalDate.now(),target=10)))
        HabitStore(c).save(h)
        runBlocking { ProjectSync.enable(c);ProjectSync.run(c) }
        c.getSystemService(JobScheduler::class.java).cancel(2105)
        return h
    }
    private fun card(id:String):JSONObject=JSONObject(http("/21day-api/v1/habits/cards?timeZoneId=Asia%2FShanghai")).getJSONArray("cards").let { a -> (0 until a.length()).map(a::getJSONObject).first {it.getString("sourceId")==id} }
    private fun cloudCount(id:String)=card(id).getJSONObject("data").getJSONObject("entries").optJSONObject(LocalDate.now().toString())?.getInt("value") ?: 0
    private fun localCount(id:String)=HabitStore(c).entries().firstOrNull {it.habitId==id && it.date==LocalDate.now()}?.value ?: 0
    private fun remoteCount(id:String) {
        val old=card(id);val baseline=old.getJSONObject("data");val data=JSONObject(baseline.toString());val day=LocalDate.now().toString()
        val e=data.getJSONObject("entries").optJSONObject(day) ?: HabitStore.encode(emptyList(),listOf(HabitEntry(id,LocalDate.now(),0))).getJSONArray("entries").getJSONObject(0)
        e.put("value",e.getInt("value")+1).put("recordedAt",System.currentTimeMillis());data.getJSONObject("entries").put(day,e)
        val op=JSONObject().put("operationId",UUID.randomUUID().toString()).put("entityType","habits").put("entityId",id).put("schemaVersion",1).put("baseRevision",old.getLong("sourceRevision")).put("baseline",baseline).put("data",data).put("deleted",false).put("kind","count")
        assertEquals("applied",JSONArray(http("/21day-api/v1/sync/push",JSONArray().put(op).toString())).getJSONObject(0).getString("status"))
    }
    @Test fun checkInUploadsImmediatelyWithoutManualSync() {
        val h=seed();HabitStore(c).count(h.id)
        // Immediate delivery must not depend on Android deciding when to run the fallback job.
        c.getSystemService(JobScheduler::class.java).cancel(2105)
        waitUntil("Saved check-in should upload without manual sync") { cloudCount(h.id)==1 }
        repeat(4){HabitStore(c).count(h.id)}
        waitUntil("Rapid check-ins must all arrive exactly once") { cloudCount(h.id)==5 && localCount(h.id)==5 && ProjectSync.status(c).pending==0 }
        assertEquals(0,ProjectSync.status(c).conflicts)
    }
    @Test fun returningToForegroundPullsLatestCloudCount() {
        val h=seed()
        val launch=Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(launch);assertTrue(device.wait(Until.hasObject(By.text("设置")),5000))
        waitUntil("Initial foreground sync must finish"){ProjectSync.status(c).pending==0}
        device.pressHome();remoteCount(h.id)
        assertEquals(0,localCount(h.id));c.startActivity(launch)
        waitUntil("Reopening must pull current cloud records immediately"){localCount(h.id)==1}
        assertEquals(0,ProjectSync.status(c).conflicts)
    }
    @Test fun offlineCheckInsSurviveAndPausedSyncDoesNotUpload() {
        val h=seed();offline=true
        HabitStore(c).count(h.id);HabitStore(c).count(h.id)
        Thread.sleep(700);assertEquals(2,localCount(h.id));assertEquals(0,cloudCount(h.id));assertTrue(ProjectSync.status(c).pending>=2)
        offline=false;remoteCount(h.id);ProjectSync.schedule(c)
        waitUntil("Offline count events should merge with another device without choosing a version") {cloudCount(h.id)==3 && localCount(h.id)==3 && ProjectSync.status(c).pending==0}
        assertEquals(0,ProjectSync.status(c).conflicts)
        runBlocking {ProjectSync.disable(c)};val calls=requests.get();HabitStore(c).count(h.id);ProjectSync.schedule(c)
        Thread.sleep(700);assertEquals(calls,requests.get());assertEquals(3,cloudCount(h.id));assertEquals(4,localCount(h.id))
    }
    @Test fun oldBlockedCountRecoversAfterCoverUpgrade() {
        Assume.assumeTrue(upgrade in setOf("seed","verify"))
        val fixture=File(c.filesDir,"0.11.2-blocked-count-id")
        if(upgrade=="seed") {
            val h=seed();HabitStore(c).count(h.id)
            runBlocking {ProjectSync.run(c)}
            assertEquals(1,ProjectSync.status(c).conflicts);assertEquals(0,cloudCount(h.id));assertEquals(1,localCount(h.id))
            fixture.writeText(h.id)
        } else {
            val id=fixture.readText();assertEquals(1,localCount(id));assertEquals(1,ProjectSync.status(c).conflicts)
            runBlocking {ProjectSync.enable(c)}
            waitUntil("Existing 0.11.1 blocker must recover without a version choice") {cloudCount(id)==1 && localCount(id)==1 && ProjectSync.status(c).pending==0}
            assertEquals(0,ProjectSync.status(c).conflicts)
            assertTrue(helper.readableDatabase.rawQuery("SELECT count(*) FROM sync_conflict_history WHERE resolution='no_change'",null).use{it.moveToFirst();it.getInt(0)}>=1)
        }
    }
}
