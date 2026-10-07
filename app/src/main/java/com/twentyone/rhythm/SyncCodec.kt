package com.twentyone.rhythm

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class SyncItem(val type:String,val id:String,val data:JSONObject?) {
    val key get()="$type/$id"
}

/** A habit round is an aggregate: its rules, 21 daily totals and active timer commit together. */
internal object SyncCodec {
    val namespaces=listOf("rhythm","coach_chat","coach_voice","coach_reports","habit_reviews","review_settings")
    val types=setOf("habits","sleep_plans","sleep_logs","sleep_events","preferences","ui_layouts","chats","reviews")
    private val sleepKeys=setOf("plan","rules","pending_at","pending_rules","rest_schedule_2026")
    private val runtime=setOf("wake","emergency_night","pass_package","pass_night","pass_day","pass_count","pass_until","scheduled_wake","habit_alarm_at","habit_reminded","bedtime_at","bedtime_locked_nights")
    fun scope(namespace:String,key:String):String {
        if(namespace!="rhythm")return "project"
        if(key in setOf("qr_token","nfc_id"))return "secret"
        if(key in runtime || key.startsWith("bedtime_sent_") || key.startsWith("pass_request_") || key.startsWith("widget_confirm_") || key.startsWith("widget_arm_"))return "runtime"
        if(key.startsWith("widget_") || key.startsWith("cards_widget_"))return "device"
        return "project"
    }
    fun project(db:SQLiteDatabase,device:String):Map<String,SyncItem> {
        val all=linkedMapOf<String,MutableMap<String,PreferenceValue>>()
        db.rawQuery("SELECT namespace,key,type,value FROM preferences",null).use { c -> while(c.moveToNext())all.getOrPut(c.getString(0)){linkedMapOf()}[c.getString(1)]=PreferenceValue(c.getString(2),c.getString(3)) }
        val result=linkedMapOf<String,SyncItem>()
        val stored=linkedMapOf<String,JSONObject>()
        db.rawQuery("SELECT entity_type,entity_id,data FROM sync_local WHERE data IS NOT NULL",null).use { c -> while(c.moveToNext())stored[c.getString(0)+"/"+c.getString(1)]=JSONObject(c.getString(2)) }
        fun add(type:String,id:String,data:JSONObject) {
            val old=stored["$type/$id"]
            val preserved=if(old==null || type=="sleep_plans")data else JSONObject(old.toString()).apply {
                data.keys().forEach { key -> put(key,data.get(key)) }
                if(type=="habits") {
                    put("plan",overlay(old.optJSONObject("plan"),data.getJSONObject("plan")).apply {
                        val previous=old.optJSONObject("plan")?.optJSONArray("rules")
                        val rules=data.getJSONObject("plan").getJSONArray("rules")
                        put("rules",JSONArray().apply { for(i in 0 until rules.length()) { val r=rules.getJSONObject(i);val before=previous?.let { a -> (0 until a.length()).map {a.getJSONObject(it)}.find {it.optString("from")==r.optString("from")} };put(overlay(before,r)) } })
                    })
                    put("entries",JSONObject().apply { data.getJSONObject("entries").let { entries -> entries.keys().forEach { day -> put(day,overlay(old.optJSONObject("entries")?.optJSONObject(day),entries.getJSONObject(day))) } } })
                }
            }
            val item=SyncItem(type,id,preserved);result[item.key]=item
        }
        val rhythm=all["rhythm"] ?: emptyMap()
        val habits=JSONObject(rhythm["habits"]?.value ?: "{\"plans\":[],\"entries\":[]}")
        val (plans,entries)=HabitStore.decode(habits)
        val timers=JSONObject(rhythm["habit_timers"]?.value ?: "{}")
        val encoded=HabitStore.encode(plans,entries)
        plans.forEachIndexed { index,h ->
            val records=JSONObject();val recordsArray=encoded.getJSONArray("entries")
            for(i in 0 until recordsArray.length()) { val e=recordsArray.getJSONObject(i);if(e.getString("habitId")==h.id)records.put(e.getString("date"),e) }
            val timer=timers.optJSONObject(h.id)?.let { JSONObject(it.toString()).apply {
                if(!has("id"))put("id",UUID.nameUUIDFromBytes("$device/${h.id}/${getLong("at")}".toByteArray()).toString())
                if(!has("device"))put("device",device)
            } }
            add("habits",h.id,JSONObject().put("plan",encoded.getJSONArray("plans").getJSONObject(index)).put("entries",records).put("timer",timer ?: JSONObject.NULL))
        }
        val sleep=JSONObject()
        sleepKeys.forEach { key -> rhythm[key]?.let { sleep.put(key,typed(it)) } }
        if(sleep.length()>0)add("sleep_plans","main",sleep)
        JSONObject(rhythm["logs"]?.value ?: "{}").let { logs -> logs.keys().forEach { day -> add("sleep_logs",day,logs.getJSONObject(day)) } }
        JSONArray(rhythm["events"]?.value ?: "[]").let { events -> for(i in 0 until events.length()) {
            val e=events.getJSONObject(i);add("sleep_events",UUID.nameUUIDFromBytes(canonical(e).toByteArray()).toString(),e)
        } }
        JSONArray(all["coach_chat"]?.get("messages")?.value ?: "[]").let { messages -> for(i in 0 until messages.length()) { val m=messages.getJSONObject(i);add("chats",m.getString("id"),m) } }
        all.filterKeys { it in namespaces }.forEach { (namespace,values) -> values.forEach valueLoop@{ (key,value) ->
            if(namespace=="rhythm" && (key in sleepKeys || key in setOf("habits","habit_timers","logs","events")) || namespace=="coach_chat" && key=="messages")return@valueLoop
            val scope=scope(namespace,key);if(scope in setOf("secret","runtime"))return@valueLoop
            val type=when { scope=="device"->"ui_layouts";namespace in setOf("habit_reviews","coach_reports")->"reviews";else->"preferences" }
            val id=if(scope=="device")"$device:$namespace:$key" else "$namespace:$key"
            val projected=typed(value)
            if(namespace=="habit_reviews" && value.type=="string") {
                val report=runCatching{JSONObject(value.value)}.getOrNull()
                if(report!=null && report.optString("status") in setOf("running","waiting") && !report.has("generationDevice"))projected.put("value",report.put("generationDevice",device).toString())
            }
            add(type,id,projected.put("namespace",namespace).put("key",key).apply { if(scope=="device")put("device",device) })
        } }
        return result
    }
    fun typed(v:PreferenceValue)=JSONObject().put("type",v.type).put("value",v.value)
    private fun overlay(old:JSONObject?,value:JSONObject)=JSONObject(old?.toString() ?: "{}").apply {value.keys().forEach {put(it,value.get(it))}}
    fun value(j:JSONObject)=PreferenceValue(j.getString("type"),j.getString("value")).also { it.decoded() }
    fun canonical(value:Any?):String=when(value) {
        null,JSONObject.NULL->"null"
        is JSONObject->value.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonical(value.get(it))}
        is JSONArray->(0 until value.length()).joinToString(",","[","]"){canonical(value.get(it))}
        is String->JSONObject.quote(value)
        is Number,is Boolean->value.toString()
        // JSONObject serializes LocalDate and other non-JSON values as quoted strings too.
        else->JSONObject.quote(value.toString())
    }
    fun same(a:JSONObject?,b:JSONObject?)=canonical(a)==canonical(b)

    fun apply(db:SQLiteDatabase,device:String):Map<String,List<String>> {
        val items=mutableListOf<SyncItem>()
        db.rawQuery("SELECT entity_type,entity_id,data FROM sync_local WHERE data IS NOT NULL",null).use { c -> while(c.moveToNext())items.add(SyncItem(c.getString(0),c.getString(1),JSONObject(c.getString(2)))) }
        val desired=linkedMapOf<Pair<String,String>,PreferenceValue>()
        fun set(namespace:String,key:String,value:Any) { desired[namespace to key]=PreferenceValue.encode(value) }
        val plans=JSONArray();val entries=JSONArray();val timers=JSONObject();val logs=JSONObject();val events=mutableListOf<JSONObject>();val chats=mutableListOf<JSONObject>()
        items.forEach { item -> val j=item.data!!;when(item.type) {
            "habits" -> { plans.put(j.getJSONObject("plan"));j.getJSONObject("entries").let { es -> es.keys().forEach { entries.put(es.getJSONObject(it)) } };j.optJSONObject("timer")?.let { timers.put(item.id,it) } }
            "sleep_plans" -> j.keys().forEach { key -> require(key in sleepKeys);desired["rhythm" to key]=value(j.getJSONObject(key)) }
            "sleep_logs" -> { require(Store.decodeLog(j).day==item.id);logs.put(item.id,j) }
            "sleep_events" -> events.add(j)
            "chats" -> { require(j.getString("role") in setOf("user","assistant"));chats.add(j) }
            "preferences","reviews","ui_layouts" -> {
                val namespace=j.getString("namespace");val key=j.getString("key")
                require(scope(namespace,key) !in setOf("secret","runtime"))
                if(namespace !in namespaces)return@forEach
                if(namespace=="rhythm")require(key !in sleepKeys && key !in setOf("habits","habit_timers","logs","events"))
                if(namespace=="coach_chat")require(key!="messages")
                if(item.type!="ui_layouts" || j.optString("device")==device) {
                    var decoded=value(j)
                    if(namespace=="habit_reviews" && decoded.type=="string") {
                        val report=runCatching{JSONObject(decoded.value)}.getOrNull()
                        if(report!=null && report.optString("status") in setOf("running","waiting") && !report.has("generationDevice"))decoded=PreferenceValue("string",report.put("generationDevice","legacy-remote").toString())
                    }
                    desired[namespace to key]=decoded
                }
            }
        } }
        val habits=JSONObject().put("plans",plans).put("entries",entries);HabitStore.decode(habits)
        desired["rhythm" to "rules"]?.let { require(Store.decodeRules(JSONObject(it.value)).valid()) }
        set("rhythm","habits",habits.toString());set("rhythm","habit_timers",timers.toString());set("rhythm","logs",logs.toString())
        set("rhythm","events",JSONArray(events.sortedBy { it.getLong("time") }).toString())
        set("coach_chat","messages",JSONArray(chats.sortedWith(compareBy<JSONObject>{it.getLong("at")}.thenBy{it.getString("id")} ).takeLast(60)).toString())
        val changes=linkedMapOf<String,MutableList<String>>()
        val existing=linkedMapOf<Pair<String,String>,PreferenceValue>()
        db.rawQuery("SELECT namespace,key,type,value FROM preferences",null).use { c -> while(c.moveToNext())existing[c.getString(0) to c.getString(1)]=PreferenceValue(c.getString(2),c.getString(3)) }
        (existing.keys+desired.keys).forEach { key ->
            if(key.first !in namespaces)return@forEach
            if(scope(key.first,key.second) in setOf("secret","runtime"))return@forEach
            val value=desired[key]
            if(existing[key]!=value) {
                if(value==null)db.execSQL("DELETE FROM preferences WHERE namespace=? AND key=?",arrayOf(key.first,key.second))
                else db.execSQL("INSERT INTO preferences(namespace,key,type,value) VALUES(?,?,?,?) ON CONFLICT(namespace,key) DO UPDATE SET type=excluded.type,value=excluded.value",arrayOf(key.first,key.second,value.type,value.value))
                changes.getOrPut(key.first){mutableListOf()}.add(key.second)
            }
        }
        return changes
    }
}
