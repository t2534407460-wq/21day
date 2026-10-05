package com.twentyone.rhythm

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.net.Socket
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Opt-in real HTTP/PostgreSQL source-command test; only the loopback QA host accepts this synthetic identity. */
class SyncRoundTripTest {
    private val c=InstrumentationRegistry.getInstrumentation().targetContext
    private val owner=UUID.randomUUID().toString()
    private val day=LocalDate.now()
    private val deviceA=UUID.randomUUID().toString()
    private val deviceB=UUID.randomUUID().toString()
    private lateinit var a:PreferenceDatabase
    private lateinit var b:PreferenceDatabase
    private lateinit var names:List<String>
    @Before fun prepare() {
        Assume.assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish") && InstrumentationRegistry.getArguments().getString("syncHttpFixture")=="true")
        names=listOf("qa-a-${UUID.randomUUID()}.db","qa-b-${UUID.randomUUID()}.db");a=PreferenceDatabase(c,names[0]);b=PreferenceDatabase(c,names[1])
    }
    @After fun cleanup(){if(::a.isInitialized){a.close();b.close();names.forEach(c::deleteDatabase)}}
    private fun http(path:String,body:Any?=null,asOwner:String=owner):String {
        // Production always uses HTTPS. Raw loopback transport is confined to the test APK.
        Socket("127.0.0.1",18769).use { socket ->
            socket.soTimeout=20000;val bytes=(body?.toString() ?: "").toByteArray(Charsets.UTF_8)
            val header="${if(body==null)"GET" else "POST"} $path HTTP/1.1\r\nHost: localhost\r\nX-QA-Owner: $asOwner\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(header.toByteArray(Charsets.US_ASCII));socket.getOutputStream().write(bytes);socket.getOutputStream().flush()
            val response=socket.getInputStream().readBytes().toString(Charsets.UTF_8);assertTrue(response.take(120),response.startsWith("HTTP/1.1 200"));return response.substringAfter("\r\n\r\n")
        }
    }
    private fun capture(helper:PreferenceDatabase,h:Habit,entries:List<HabitEntry>,device:String,kind:String="replace") {
        SyncJournal.command(kind){SyncJournal.transaction(helper.writableDatabase){
            helper.writableDatabase.execSQL("INSERT INTO preferences(namespace,key,type,value) VALUES('rhythm','habits','string',?) ON CONFLICT(namespace,key) DO UPDATE SET value=excluded.value",arrayOf(HabitStore.encode(listOf(h),entries).toString()))
            SyncJournal.capture(helper.writableDatabase,device)
        }}
    }
    private fun send(helper:PreferenceDatabase,device:String):JSONObject {
        val op=SyncJournal.next(helper.writableDatabase)!!
        val reply=JSONArray(http("/21day-api/v1/sync/push",JSONArray().put(op))).getJSONObject(0)
        SyncJournal.acknowledge(helper.writableDatabase,op,reply,device);return op
    }
    private fun pull(helper:PreferenceDatabase,device:String) {
        val cursor=SyncJournal.metadata(helper.readableDatabase,"cursor") ?: "0"
        val page=JSONObject(http("/21day-api/v1/sync/pull?after=$cursor&maximum=100"));SyncJournal.pull(helper.writableDatabase,page,device)
    }
    private fun value(helper:PreferenceDatabase)=helper.readableDatabase.rawQuery("SELECT value FROM preferences WHERE key='habits' AND namespace='rhythm'",null).use {it.moveToFirst();HabitStore.decode(JSONObject(it.getString(0))).second.single().value}
    @Test fun twoPhonesAndDesktopUseSameRecordAcrossReplayCorrectionAndConflict() {
        val h=Habit(name="真实协议合成计次",mode=HabitMode.AT_LEAST,input=HabitInput.COUNT,rules=listOf(HabitRule(day)))
        capture(a,h,emptyList(),deviceA);send(a,deviceA);pull(b,deviceB)
        capture(a,h,listOf(HabitEntry(h.id,day,1)),deviceA,"count");capture(b,h,listOf(HabitEntry(h.id,day,1)),deviceB,"count")
        send(a,deviceA);val replay=send(b,deviceB);http("/21day-api/v1/sync/push",JSONArray().put(replay));pull(a,deviceA)
        assertEquals(2,value(a));assertEquals(2,value(b))
        val path="/21day-api/v1/habits/cards?timeZoneId="+java.net.URLEncoder.encode(ZoneId.systemDefault().id,"UTF-8")
        val card=JSONObject(http(path)).getJSONArray("cards").getJSONObject(0)
        assertEquals("21day",card.getString("sourceProject"));assertEquals(h.id,card.getString("sourceId"))
        val baseline=card.getJSONObject("data");val data=JSONObject(baseline.toString());data.getJSONObject("entries").getJSONObject(day.toString()).put("value",20).put("recordedAt",System.currentTimeMillis())
        val op=JSONObject().put("operationId",UUID.randomUUID().toString()).put("entityType","habits").put("entityId",h.id).put("baseRevision",card.getLong("sourceRevision")).put("schemaVersion",1).put("data",data).put("baseline",baseline).put("kind","replace").put("deleted",false)
        val command=JSONObject().put("operation",op).put("action","set_total").put("day",day.toString()).put("timeZoneId",ZoneId.systemDefault().id).put("deviceId",UUID.randomUUID().toString())
        assertEquals("applied",JSONObject(http("/21day-api/v1/habits/commands",command)).getString("status"));http("/21day-api/v1/habits/commands",command)
        pull(a,deviceA);assertEquals(20,value(a))
        capture(b,h,listOf(HabitEntry(h.id,day,5)),deviceB);send(b,deviceB);pull(b,deviceB)
        assertEquals(5,value(b));assertEquals(1,SyncJournal.conflicts(b.readableDatabase))
        SyncJournal.resolve(b.writableDatabase,"habits",h.id,false,deviceB);assertEquals(20,value(b))
        assertEquals(0,JSONObject(http(path,asOwner=UUID.randomUUID().toString())).getJSONArray("cards").length())
    }
}
