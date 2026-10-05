package com.twentyone.rhythm

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object SyncJournal {
    private val action=ThreadLocal<String>()
    fun <T> command(kind:String,block:()->T):T { check(action.get()==null);action.set(kind);try{return block()}finally{action.remove()} }
    fun create(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_local(entity_type TEXT NOT NULL,entity_id TEXT NOT NULL,data TEXT,PRIMARY KEY(entity_type,entity_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_remote(entity_type TEXT NOT NULL,entity_id TEXT NOT NULL,revision INTEGER NOT NULL,data TEXT,PRIMARY KEY(entity_type,entity_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_outbox(sequence INTEGER PRIMARY KEY AUTOINCREMENT,operation_id TEXT NOT NULL UNIQUE,entity_type TEXT NOT NULL,entity_id TEXT NOT NULL,request TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'queued',message TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS sync_outbox_entity ON sync_outbox(entity_type,entity_id,sequence)")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_conflict_history(id INTEGER PRIMARY KEY AUTOINCREMENT,entity_type TEXT,entity_id TEXT,request TEXT,remote TEXT,resolution TEXT,at INTEGER)")
    }
    fun capture(db:SQLiteDatabase,device:String) {
        val before=linkedMapOf<String,SyncItem>()
        db.rawQuery("SELECT entity_type,entity_id,data FROM sync_local WHERE data IS NOT NULL",null).use { c -> while(c.moveToNext())SyncItem(c.getString(0),c.getString(1),JSONObject(c.getString(2))).let { before[it.key]=it } }
        val after=SyncCodec.project(db,device)
        (before.keys+after.keys).forEach { key ->
            val b=before[key];val a=after[key];if(SyncCodec.same(b?.data,a?.data))return@forEach
            val item=a ?: SyncItem(b!!.type,b.id,null)
            if(a==null && (item.type !in SyncCodec.types || item.type=="ui_layouts" && b?.data?.optString("device")!=device || item.type in setOf("preferences","reviews") && b?.data?.optString("namespace") !in SyncCodec.namespaces))return@forEach
            // Local history is capped for display; trimming old chat/events is not an explicit cloud deletion.
            if(a==null && item.type in setOf("sleep_events","chats"))return@forEach
            val revision=revision(db,item.type,item.id)
            val op=JSONObject().put("operationId",UUID.randomUUID().toString()).put("entityType",item.type).put("entityId",item.id)
                .put("baseRevision",revision).put("schemaVersion",1).put("data",item.data ?: JSONObject.NULL).put("deleted",item.data==null)
                .put("baseline",b?.data ?: JSONObject.NULL).put("kind",if(item.type=="habits")action.get() ?: "replace" else "replace")
            enqueue(db,op);local(db,item)
        }
    }
    private fun enqueue(db:SQLiteDatabase,op:JSONObject) {
        db.execSQL("INSERT INTO sync_outbox(operation_id,entity_type,entity_id,request) VALUES(?,?,?,?)",arrayOf(op.getString("operationId"),op.getString("entityType"),op.getString("entityId"),op.toString()))
    }
    private fun local(db:SQLiteDatabase,item:SyncItem) {
        db.execSQL("INSERT INTO sync_local(entity_type,entity_id,data) VALUES(?,?,?) ON CONFLICT(entity_type,entity_id) DO UPDATE SET data=excluded.data",arrayOf(item.type,item.id,item.data?.toString()))
    }
    internal fun sleepLocked(db:SQLiteDatabase,now:java.time.LocalDateTime=java.time.LocalDateTime.now()):Boolean {
        fun read(key:String)=db.rawQuery("SELECT value FROM preferences WHERE namespace='rhythm' AND key=?",arrayOf(key)).use { if(it.moveToFirst())it.getString(0) else null }
        val wake=JSONObject(read("wake") ?: "{}")
        if(wake.optString("phase") in setOf("RINGING","WALK","SECOND_WAIT","SECOND_READY"))return true
        val raw=read("plan") ?: return false;val p=JSONObject(raw)
        val plan=Plan(java.time.LocalDate.parse(p.getString("start")),p.optString("action"),p.optString("goal"))
        return Schedule.night(plan,Store.decodeRules(JSONObject(read("rules") ?: "{}")),now)!=null
    }
    private fun applyRemote(db:SQLiteDatabase,item:SyncItem) {
        if(item.type=="sleep_plans" && sleepLocked(db))metadata(db,"deferred_sleep",JSONObject().put("data",item.data ?: JSONObject.NULL).toString())
        else { local(db,item);if(item.type=="sleep_plans")db.execSQL("DELETE FROM sync_metadata WHERE key='deferred_sleep'") }
    }
    fun applyDeferred(db:SQLiteDatabase,device:String):Map<String,List<String>> = transaction(db) {
        val deferred=metadata(db,"deferred_sleep")
        if(deferred!=null && !sleepLocked(db) && !hasPending(db,"sleep_plans","main")) {
            applyRemote(db,SyncItem("sleep_plans","main",JSONObject(deferred).optJSONObject("data")))
            SyncCodec.apply(db,device)
        }else emptyMap()
    }
    private fun revision(db:SQLiteDatabase,type:String,id:String)=db.rawQuery("SELECT revision FROM sync_remote WHERE entity_type=? AND entity_id=?",arrayOf(type,id)).use { if(it.moveToFirst())it.getLong(0) else 0L }
    private fun remote(db:SQLiteDatabase,item:SyncItem,revision:Long) {
        db.execSQL("INSERT INTO sync_remote(entity_type,entity_id,revision,data) VALUES(?,?,?,?) ON CONFLICT(entity_type,entity_id) DO UPDATE SET revision=excluded.revision,data=excluded.data WHERE excluded.revision>=sync_remote.revision",arrayOf(item.type,item.id,revision,item.data?.toString()))
    }
    fun pending(db:SQLiteDatabase)=db.rawQuery("SELECT count(*) FROM sync_outbox",null).use { it.moveToFirst();it.getInt(0) }
    fun conflicts(db:SQLiteDatabase)=db.rawQuery("SELECT count(*) FROM sync_outbox WHERE state='blocked'",null).use { it.moveToFirst();it.getInt(0) }
    fun next(db:SQLiteDatabase):JSONObject?=db.rawQuery("""
        SELECT a.request FROM sync_outbox a WHERE a.state='queued' AND NOT EXISTS
        (SELECT 1 FROM sync_outbox b WHERE b.entity_type=a.entity_type AND b.entity_id=a.entity_id AND b.sequence<a.sequence)
        ORDER BY a.sequence LIMIT 1
    """.trimIndent(),null).use { if(it.moveToFirst())JSONObject(it.getString(0)) else null }
    private fun hasPending(db:SQLiteDatabase,type:String,id:String)=db.rawQuery("SELECT 1 FROM sync_outbox WHERE entity_type=? AND entity_id=? LIMIT 1",arrayOf(type,id)).use { it.moveToFirst() }
    fun removeNoChanges(db:SQLiteDatabase,device:String):Map<String,List<String>> = transaction(db) {
        val empty=mutableListOf<JSONObject>()
        db.rawQuery("SELECT request FROM sync_outbox",null).use { rows -> while(rows.moveToNext()) {
            val op=JSONObject(rows.getString(0));val baseline=op.optJSONObject("baseline")
            if(!op.getBoolean("deleted") && baseline!=null && SyncCodec.same(baseline,op.optJSONObject("data")))empty.add(op)
        } }
        empty.forEach { op ->
            // Old clients emitted no-op count commands around real check-ins. Keep the evidence before removing the blocker.
            db.execSQL("INSERT INTO sync_conflict_history(entity_type,entity_id,request,remote,resolution,at) SELECT entity_type,entity_id,request,NULL,'no_change',? FROM sync_outbox WHERE operation_id=?",arrayOf(System.currentTimeMillis(),op.getString("operationId")))
            db.execSQL("DELETE FROM sync_outbox WHERE operation_id=?",arrayOf(op.getString("operationId")))
        }
        empty.map {it.getString("entityType") to it.getString("entityId")}.distinct().forEach { (type,id) ->
            if(!hasPending(db,type,id))db.rawQuery("SELECT data FROM sync_remote WHERE entity_type=? AND entity_id=?",arrayOf(type,id)).use { row ->
                if(row.moveToFirst())applyRemote(db,SyncItem(type,id,if(row.isNull(0))null else JSONObject(row.getString(0))))
            }
        }
        if(empty.isEmpty())emptyMap()else SyncCodec.apply(db,device)
    }
    fun acknowledge(db:SQLiteDatabase,request:JSONObject,result:JSONObject,device:String):Map<String,List<String>> = transaction(db) {
        require(result.getString("operationId")==request.getString("operationId"))
        val status=result.getString("status");val type=request.getString("entityType");val id=request.getString("entityId")
        val revision=result.getLong("revision");require(revision>=0)
        if(status=="applied") {
            require(revision>0)
            val data=if(request.getBoolean("deleted"))null else result.optJSONObject("current") ?: request.getJSONObject("data")
            val item=SyncItem(type,id,data);remote(db,item,revision)
            db.execSQL("DELETE FROM sync_outbox WHERE operation_id=?",arrayOf(request.getString("operationId")))
            if(!hasPending(db,type,id)) {
                // A newer pull may already have overtaken a delayed idempotency receipt.
                db.rawQuery("SELECT data FROM sync_remote WHERE entity_type=? AND entity_id=?",arrayOf(type,id)).use { c -> c.moveToFirst();applyRemote(db,SyncItem(type,id,if(c.isNull(0))null else JSONObject(c.getString(0)))) }
            }
        } else {
            require(status in setOf("conflict","deleted","invalid","unsupported"))
            if(status in setOf("conflict","deleted"))remote(db,SyncItem(type,id,result.optJSONObject("current")),revision)
            db.execSQL("UPDATE sync_outbox SET state='blocked',message=? WHERE operation_id=?",arrayOf(status,request.getString("operationId")))
        }
        SyncCodec.apply(db,device)
    }
    fun pull(db:SQLiteDatabase,page:JSONObject,device:String):Map<String,List<String>> = transaction(db) {
        val cursor=metadata(db,"cursor")?.toLong() ?: 0L
        val next=page.getLong("cursor");val high=page.getLong("highWatermark");require(next>=cursor && high>=next)
        val changes=page.getJSONArray("changes");var previous=cursor
        for(i in 0 until changes.length()) {
            val change=changes.getJSONObject(i);val sequence=change.getLong("sequence");require(sequence>previous && sequence<=next);previous=sequence
            val e=change.getJSONObject("entity");require(e.getInt("schemaVersion")==1 && e.getLong("revision")==sequence)
            val item=SyncItem(e.getString("entityType"),e.getString("entityId"),if(e.getBoolean("deleted"))null else e.getJSONObject("data"))
            require(item.type.length in 1..80 && item.id.length in 1..200)
            if(sequence>=revision(db,item.type,item.id)) {
                remote(db,item,sequence)
                if(!hasPending(db,item.type,item.id))applyRemote(db,item)
            }
        }
        require(previous==next)
        val changed=SyncCodec.apply(db,device);metadata(db,"cursor",next.toString());changed
    }
    fun conflictItems(db:SQLiteDatabase):List<JSONObject> {
        val items=mutableListOf<JSONObject>()
        db.rawQuery("SELECT a.entity_type,a.entity_id,a.message,l.data,r.data,r.revision FROM sync_outbox a LEFT JOIN sync_local l USING(entity_type,entity_id) LEFT JOIN sync_remote r USING(entity_type,entity_id) WHERE a.state='blocked'",null).use { c -> while(c.moveToNext()) {
            items.add(JSONObject().put("type",c.getString(0)).put("id",c.getString(1)).put("reason",c.getString(2)).put("local",if(c.isNull(3))JSONObject.NULL else JSONObject(c.getString(3))).put("remote",if(c.isNull(4))JSONObject.NULL else JSONObject(c.getString(4))).put("revision",c.getLong(5)))
        } };return items
    }
    fun resolve(db:SQLiteDatabase,type:String,id:String,useLocal:Boolean,device:String):Map<String,List<String>> = transaction(db) {
        val conflict=conflictItems(db).first { it.getString("type")==type && it.getString("id")==id }
        if(useLocal)require(!conflict.isNull("remote")) { "云端已删除，不能自动恢复旧实体。请保留本机记录并核对。" }
        db.execSQL("INSERT INTO sync_conflict_history(entity_type,entity_id,request,remote,resolution,at) SELECT entity_type,entity_id,request,?,?,? FROM sync_outbox WHERE entity_type=? AND entity_id=?",arrayOf(conflict.optJSONObject("remote")?.toString(),if(useLocal)"local" else "remote",System.currentTimeMillis(),type,id))
        db.execSQL("DELETE FROM sync_outbox WHERE entity_type=? AND entity_id=?",arrayOf(type,id))
        if(useLocal)enqueue(db,JSONObject().put("operationId",UUID.randomUUID().toString()).put("entityType",type).put("entityId",id).put("baseRevision",conflict.getLong("revision")).put("schemaVersion",1).put("data",conflict.optJSONObject("local") ?: JSONObject.NULL).put("baseline",conflict.optJSONObject("remote") ?: JSONObject.NULL).put("deleted",conflict.isNull("local")).put("kind","replace"))
        else applyRemote(db,SyncItem(type,id,conflict.optJSONObject("remote")))
        SyncCodec.apply(db,device)
    }
    fun metadata(db:SQLiteDatabase,key:String):String?=db.rawQuery("SELECT value FROM sync_metadata WHERE key=?",arrayOf(key)).use { if(it.moveToFirst())it.getString(0) else null }
    fun metadata(db:SQLiteDatabase,key:String,value:String) { db.execSQL("INSERT INTO sync_metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",arrayOf(key,value)) }
    fun <T> transaction(db:SQLiteDatabase,block:()->T):T { db.beginTransaction();try { val result=block();db.setTransactionSuccessful();return result }finally{db.endTransaction()} }
}
