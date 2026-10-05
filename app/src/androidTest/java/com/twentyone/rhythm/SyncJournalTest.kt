package com.twentyone.rhythm

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import java.util.UUID

class SyncJournalTest {
    private val c=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var helper:PreferenceDatabase
    private lateinit var db:android.database.sqlite.SQLiteDatabase
    private lateinit var name:String
    private val device=UUID.randomUUID().toString()
    private val habit=Habit(name="并发计次",mode=HabitMode.AT_LEAST,rules=listOf(HabitRule(LocalDate.now())),input=HabitInput.COUNT)
    @Before fun setup() {
        Assume.assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        name="qa-sync-${UUID.randomUUID()}.db";helper=PreferenceDatabase(c,name);db=helper.writableDatabase
    }
    @After fun cleanup() { if(::helper.isInitialized){helper.close();c.deleteDatabase(name)} }
    private fun write(key:String,value:String) { db.execSQL("INSERT INTO preferences(namespace,key,type,value) VALUES('rhythm',?,'string',?) ON CONFLICT(namespace,key) DO UPDATE SET value=excluded.value",arrayOf(key,value)) }
    private fun capture(entries:List<HabitEntry> = emptyList())=SyncJournal.transaction(db) { write("habits",HabitStore.encode(listOf(habit),entries).toString());SyncJournal.capture(db,device) }
    private fun result(op:JSONObject,revision:Long,data:JSONObject?=op.optJSONObject("data"),status:String="applied")=JSONObject().put("operationId",op.getString("operationId")).put("status",status).put("revision",revision).put("current",data ?: JSONObject.NULL)
    private fun entity(type:String,id:String,data:JSONObject,rev:Long)=JSONObject().put("sequence",rev).put("entity",JSONObject().put("entityType",type).put("entityId",id).put("revision",rev).put("schemaVersion",1).put("data",data).put("deleted",false))
    private fun page(vararg changes:JSONObject)=JSONObject().put("cursor",changes.last().getLong("sequence")).put("highWatermark",changes.last().getLong("sequence")).put("changes",JSONArray(changes.toList()))
    @Test fun responseAfterNewLocalEditDoesNotOverwriteNewValueAndRetryKeepsOperationId() {
        capture();val first=SyncJournal.next(db)!!;assertEquals(first.toString(),SyncJournal.next(db)!!.toString())
        val entry=HabitEntry(habit.id,LocalDate.now(),1);SyncJournal.command("count"){capture(listOf(entry))}
        SyncJournal.acknowledge(db,first,result(first,1),device)
        assertEquals(1,HabitStore.decode(JSONObject(db.rawQuery("SELECT value FROM preferences WHERE key='habits'",null).use {it.moveToFirst();it.getString(0)})).second.single().value)
        val next=SyncJournal.next(db)!!;assertEquals("count",next.getString("kind"));assertNotEquals(first.getString("operationId"),next.getString("operationId"))
        val canonical=JSONObject(next.getJSONObject("data").toString());canonical.getJSONObject("entries").getJSONObject(LocalDate.now().toString()).put("value",2)
        SyncJournal.acknowledge(db,next,result(next,2,canonical),device)
        assertEquals(0,SyncJournal.pending(db))
        assertEquals(2,HabitStore.decode(JSONObject(db.rawQuery("SELECT value FROM preferences WHERE key='habits'",null).use {it.moveToFirst();it.getString(0)})).second.single().value)
    }
    @Test fun datesCompareLikeTheirSavedJsonAndReadingDoesNotCreateOperations() {
        val value=JSONObject().put("date",LocalDate.now()).put("nested",JSONArray().put(JSONObject().put("from",LocalDate.now())))
        assertTrue(SyncCodec.same(value,JSONObject(value.toString())))
        capture();val first=SyncJournal.next(db)!!;SyncJournal.acknowledge(db,first,result(first,1),device)
        repeat(3) {SyncJournal.capture(db,device)}
        assertEquals("Reading the same habit must not create empty updates",0,SyncJournal.pending(db))
    }
    @Test fun oldEmptyCountBlockerIsArchivedWithoutDroppingRealCheckIn() {
        capture();val create=SyncJournal.next(db)!!;SyncJournal.acknowledge(db,create,result(create,1),device)
        val noChange=JSONObject(create.toString()).put("operationId",UUID.randomUUID().toString()).put("kind","count").put("baseline",create.getJSONObject("data"))
        db.execSQL("INSERT INTO sync_outbox(operation_id,entity_type,entity_id,request,state,message) VALUES(?,?,?,?, 'blocked','invalid')",arrayOf(noChange.getString("operationId"),"habits",habit.id,noChange.toString()))
        SyncJournal.command("count") {capture(listOf(HabitEntry(habit.id,LocalDate.now(),1)))}
        assertNull(SyncJournal.next(db))
        SyncJournal.removeNoChanges(db,device)
        assertEquals(1,SyncJournal.pending(db));assertEquals(0,SyncJournal.conflicts(db))
        val real=SyncJournal.next(db)!!;assertEquals("count",real.getString("kind"))
        assertEquals(1,real.getJSONObject("data").getJSONObject("entries").getJSONObject(LocalDate.now().toString()).getInt("value"))
        assertEquals(1,db.rawQuery("SELECT count(*) FROM sync_conflict_history WHERE resolution='no_change'",null).use{it.moveToFirst();it.getInt(0)})
        SyncJournal.acknowledge(db,real,result(real,2),device);SyncJournal.capture(db,device);assertEquals(0,SyncJournal.pending(db))
    }
    @Test fun pullAndCursorRollbackTogetherWhenOneEntityIsInvalid() {
        val good=JSONObject().put("namespace","coach_voice").put("key","keyboard").put("type","boolean").put("value","false")
        val bad=JSONObject().put("plan",JSONObject().put("id",habit.id))
        try { SyncJournal.pull(db,page(entity("preferences","coach_voice:keyboard",good,1),entity("habits",habit.id,bad,2)),device);fail("Invalid page must roll back") }catch(expected:Exception) { }
        assertNull(SyncJournal.metadata(db,"cursor"))
        assertEquals(0,db.rawQuery("SELECT count(*) FROM sync_remote",null).use {it.moveToFirst();it.getInt(0)})
        assertEquals(0,db.rawQuery("SELECT count(*) FROM preferences",null).use {it.moveToFirst();it.getInt(0)})
    }
    @Test fun remoteDeletionIsHeldUntilExplicitConflictChoiceAndOriginalIsRetained() {
        capture();val op=SyncJournal.next(db)!!
        SyncJournal.acknowledge(db,op,result(op,2,null,"deleted"),device)
        assertEquals(1,SyncJournal.conflicts(db));assertNull(SyncJournal.next(db))
        try {SyncJournal.resolve(db,"habits",habit.id,true,device);fail("Deleted entities must not resurrect")}catch(expected:IllegalArgumentException){}
        SyncJournal.resolve(db,"habits",habit.id,false,device)
        assertEquals(0,SyncJournal.pending(db));assertEquals(1,db.rawQuery("SELECT count(*) FROM sync_conflict_history",null).use {it.moveToFirst();it.getInt(0)})
        assertTrue(HabitStore.decode(JSONObject(db.rawQuery("SELECT value FROM preferences WHERE key='habits'",null).use {it.moveToFirst();it.getString(0)})).first.isEmpty())
    }
    @Test fun projectionKeepsFullTimerChatReportAndExcludesSecretsAndRuntime() {
        val timerHabit=habit.copy(unit="分钟",input=HabitInput.TIMER)
        write("habits",HabitStore.encode(listOf(timerHabit),emptyList()).toString())
        write("habit_timers",JSONObject().put(habit.id,JSONObject().put("at",123456L).put("day",LocalDate.now())).toString())
        write("qr_token","secret-qr");write("nfc_id","secret-nfc");write("wake","{\"phase\":\"SCAN\"}")
        write("pass_day","2026-10-05");db.execSQL("INSERT INTO preferences VALUES('rhythm','pass_count','int','2')")
        db.execSQL("INSERT INTO preferences VALUES('coach_chat','messages','string',?)",arrayOf(JSONArray().put(JSONObject().put("id","m1").put("at",1).put("role","user").put("text","旧聊天")).toString()))
        db.execSQL("INSERT INTO preferences VALUES('habit_reviews','review1','string','旧报告')")
        val data=SyncCodec.project(db,device)
        assertEquals(123456L,data["habits/${habit.id}"]!!.data!!.getJSONObject("timer").getLong("at"))
        assertEquals(device,data["habits/${habit.id}"]!!.data!!.getJSONObject("timer").getString("device"))
        assertEquals("旧聊天",data["chats/m1"]!!.data!!.getString("text"));assertTrue(data.containsKey("reviews/habit_reviews:review1"))
        assertFalse(data.values.any { it.data.toString().contains("secret-") || it.data.toString().contains("SCAN") })
        assertFalse(data.containsKey("preferences/rhythm:pass_day"));assertFalse(data.containsKey("preferences/rhythm:pass_count"))
    }
    @Test fun sourceChangesAndQueueAreAtomicIfProjectionCannotBeStored() {
        db.execSQL("CREATE TRIGGER qa_sync_failure BEFORE INSERT ON sync_outbox BEGIN SELECT RAISE(ABORT,'synthetic outbox failure'); END")
        try {capture();fail("Queue must commit with data")}catch(expected:android.database.sqlite.SQLiteException){}
        assertEquals(0,db.rawQuery("SELECT count(*) FROM preferences",null).use {it.moveToFirst();it.getInt(0)})
        assertEquals(0,SyncJournal.pending(db))
    }
    @Test fun foreignDeviceLayoutsAndFutureFieldsAreNeverDeletedByLocalProjection() {
        val data=JSONObject().put("namespace","rhythm").put("key","cards_widget_99").put("type","set").put("value","[]").put("device",UUID.randomUUID().toString())
        val future=JSONObject().put("text","future entity")
        SyncJournal.pull(db,page(entity("ui_layouts","another-device:cards",data,1),entity("future_type","new",future,2)),device)
        capture();val requests=mutableListOf<String>();db.rawQuery("SELECT request FROM sync_outbox",null).use{while(it.moveToNext())requests.add(it.getString(0))}
        assertFalse(requests.any {it.contains("another-device:cards") || it.contains("future_type")})
        val op=SyncJournal.next(db)!!;val canonical=JSONObject(op.getJSONObject("data").toString()).put("future",true);canonical.getJSONObject("plan").put("futurePlanField","keep")
        SyncJournal.acknowledge(db,op,result(op,3,canonical),device)
        capture(listOf(HabitEntry(habit.id,LocalDate.now(),2)))
        val updated=SyncJournal.next(db)!!.getJSONObject("data");assertTrue(updated.getBoolean("future"));assertEquals("keep",updated.getJSONObject("plan").getString("futurePlanField"))
    }
    @Test fun remoteSleepRulesWaitForLocalVerificationToFinish() {
        write("rules",Store.encodeRules(Rules()).toString());write("wake","{\"phase\":\"WALK\"}")
        SyncJournal.capture(db,device);val op=SyncJournal.next(db)!!;SyncJournal.acknowledge(db,op,result(op,1),device)
        val updated=JSONObject(op.getJSONObject("data").toString());updated.put("rules",SyncCodec.typed(PreferenceValue("string",Store.encodeRules(Rules(bed=120)).toString())))
        SyncJournal.pull(db,page(entity("sleep_plans","main",updated,2)),device)
        assertTrue(SyncJournal.sleepLocked(db));assertNotNull(SyncJournal.metadata(db,"deferred_sleep"))
        assertEquals(Rules().bed,db.rawQuery("SELECT value FROM preferences WHERE key='rules'",null).use{it.moveToFirst();Store.decodeRules(JSONObject(it.getString(0))).bed})
        write("wake","{\"phase\":\"COMPLETE\"}");SyncJournal.applyDeferred(db,device)
        assertNull(SyncJournal.metadata(db,"deferred_sleep"));assertEquals(120,db.rawQuery("SELECT value FROM preferences WHERE key='rules'",null).use{it.moveToFirst();Store.decodeRules(JSONObject(it.getString(0))).bed})
    }
    @Test fun remoteRunningReviewKeepsOriginAndUnrelatedLocalNamespaceIsUntouched() {
        db.execSQL("INSERT INTO preferences VALUES('unrelated','keep','string','local')")
        val report=JSONObject().put("status","running").put("at",1)
        val data=JSONObject().put("namespace","habit_reviews").put("key","old-review").put("type","string").put("value",report.toString())
        SyncJournal.pull(db,page(entity("reviews","habit_reviews:old-review",data,1)),device)
        assertEquals("legacy-remote",db.rawQuery("SELECT value FROM preferences WHERE namespace='habit_reviews'",null).use{it.moveToFirst();JSONObject(it.getString(0)).getString("generationDevice")})
        assertEquals("local",db.rawQuery("SELECT value FROM preferences WHERE namespace='unrelated'",null).use{it.moveToFirst();it.getString(0)})
        assertFalse(SyncCodec.project(db,device).values.any{it.data?.optString("namespace")=="unrelated"})
    }
}
