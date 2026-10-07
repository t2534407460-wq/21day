package com.twentyone.rhythm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.util.UUID

class Store(private val context: Context) {
    val prefs = ProjectPreferences.get(context,"rhythm")
    var keepTaskOnBack: Boolean
        get() = prefs.getBoolean("keep_task_on_back",true)
        set(value) { prefs.edit().putBoolean("keep_task_on_back",value).apply() }
    var hideFromRecents: Boolean
        get() = prefs.getBoolean("hide_from_recents",true)
        set(value) { prefs.edit().putBoolean("hide_from_recents",value).apply() }
    private fun json(key: String) = runCatching { JSONObject(prefs.getString(key, "{}")!!) }.getOrDefault(JSONObject())
    private fun put(key: String, value: JSONObject) = prefs.edit().putString(key, value.toString()).apply()
    val plan: Plan? get() = if (!prefs.contains("plan")) null else json("plan").let { Plan(LocalDate.parse(it.getString("start")), it.optString("action"), it.optString("goal")) }
    var rules: Rules
        get() = decodeRules(json("rules"))
        set(value) = put("rules", encodeRules(value))
    val pendingAt get() = prefs.getLong("pending_at", 0)
    val editableRules get() = if(pendingAt>0) decodeRules(json("pending_rules")) else rules
    fun migrateRestSchedule():Unit=ProjectPreferences.atomic(context) {
        if(prefs.getBoolean("rest_schedule_2026",false)) return@atomic
        // User's 2026-10-04 requirement: rest days are 02:00–10:00. Preserve workdays, exceptions and pending timing.
        fun rest(r:Rules)=r.copy(weekends=true,weekendBed=120,weekendWake=600)
        val edit=prefs.edit().putBoolean("rest_schedule_2026",true)
        edit.putString("rules",encodeRules(rest(rules)).toString())
        if(pendingAt>0) edit.putString("pending_rules",encodeRules(rest(editableRules)).toString())
        edit.commit()
    }
    fun applyPending(now: Long = System.currentTimeMillis()):Unit=ProjectPreferences.atomic(context) {
        if (pendingAt in 1..now) { prefs.edit().putString("rules",encodeRules(decodeRules(json("pending_rules"))).toString()).remove("pending_at").remove("pending_rules").apply() }
    }
    fun saveRules(value: Rules): Boolean=ProjectPreferences.atomic(context) {
        require(value.valid())
        applyPending()
        val window = plan?.let { Schedule.night(it, rules, LocalDateTime.now()) }
        if (window != null || wake.active) {
            val applyAt = window?.end?.epoch() ?: LocalDate.now().plusDays(1).atStartOfDay().epoch()
            prefs.edit().putString("pending_rules",encodeRules(value).toString()).putLong("pending_at",applyAt).apply()
            event("调整规则", "当前夜间结束后生效"); true
        } else {
            val before=rules
            prefs.edit().putString("rules",encodeRules(value).toString()).remove("pending_at").remove("pending_rules").apply()
            if(before!=value) event("调整规则","睡前 ${timeText(value.bed)} · 起床 ${timeText(value.wake)} · 已生效")
            false
        }
    }
    fun createPlan(value: Plan) { put("plan", JSONObject().put("start", value.start).put("action", value.action).put("goal", value.goal)) }
    var wake: WakeSession
        get() = json("wake").let { WakeSession(it.optString("day"), runCatching { WakePhase.valueOf(it.optString("phase")) }.getOrDefault(WakePhase.IDLE), it.optLong("due"), it.optInt("attempts"), it.optLong("first"), it.optLong("complete"), it.optBoolean("test")) }
        set(v) = put("wake", JSONObject().put("day", v.day).put("phase", v.phase.name).put("due", v.dueAt).put("attempts", v.attempts).put("first", v.firstAt).put("complete", v.completedAt).put("test", v.test))
    val passPackage get() = prefs.getString("pass_package", "")!!
    val passUntil get() = prefs.getLong("pass_until", 0)
    fun passesRemaining(date: LocalDate = LocalDate.now()): Int {
        val used=if(prefs.contains("pass_day")) {
            if(prefs.getString("pass_day", "")==date.toString()) prefs.getInt("pass_count",0) else 0
        } else {
            // Legacy releases saved the night, but the expiry also lets us recover the actual calendar day.
            val started=passUntil-rules.passMinutes*60_000L
            if(passUntil>0 && Instant.ofEpochMilli(started).atZone(ZoneId.systemDefault()).toLocalDate()==date) 1 else 0
        }
        return (DAILY_PASSES-used).coerceIn(0,DAILY_PASSES)
    }
    fun grantPass(pkg: String, now: LocalDateTime = LocalDateTime.now()): Boolean=ProjectPreferences.atomic(context) {
        val remaining=passesRemaining(now.toLocalDate())
        val current=rules
        if(remaining==0 || passUntil>now.epoch() || pkg !in current.blocked || pkg in current.allowed ||
            plan?.let { Schedule.night(it,current,now) }==null) return@atomic false
        prefs.edit().putString("pass_package",pkg).putString("pass_day",now.toLocalDate().toString())
            .putInt("pass_count",DAILY_PASSES-remaining+1).putLong("pass_until",now.epoch()+PASS_MINUTES*60_000L).apply()
        val name=runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg,0)).toString() }.getOrDefault(pkg)
        event("临时放行", "$name · $PASS_MINUTES 分钟 · 今日第 ${DAILY_PASSES-remaining+1} 次",now.epoch())
        true
    }
    val qrToken: String get() = prefs.getString("qr_token", null) ?: ("rhythm://wake/" + UUID.randomUUID()).also { prefs.edit().putString("qr_token", it).apply() }
    var nfcId: String
        get() = prefs.getString("nfc_id", "")!!
        set(value) { prefs.edit().putString("nfc_id", value).apply() }
    fun logs(): List<DayLog> = json("logs").let { all -> all.keys().asSequence().map { key -> decodeLog(all.getJSONObject(key)) }.toList().sortedBy { it.day } }
    fun log(day: String) = logs().firstOrNull { it.day == day } ?: DayLog(day)
    fun nightStatus(date:LocalDate)=BedtimeSchedule.status(date,log(date.toString()),log(date.plusDays(1).toString()))
    fun saveLog(v: DayLog)=ProjectPreferences.atomic(context) { put("logs", json("logs").put(v.day, encodeLog(v))) }
    fun updateLog(day:String,change:(DayLog)->DayLog)=ProjectPreferences.atomic(context) { saveLog(change(log(day))) }
    fun completeBedtime(day:String,now:LocalDateTime=LocalDateTime.now()):Unit=ProjectPreferences.atomic(context) {
        val p=plan ?: error("请先设置计划")
        require(BedtimeSchedule.recordDay(p,rules,now).toString()==day) { "这个夜晚的打卡时间已结束，请返回查看当前记录" }
        updateLog(day) { latest ->
            require(BedtimeSchedule.answered(latest)) { "请先回答困意、心情和影响原因" }
            latest.copy(bedtimeCheckedAt=latest.bedtimeCheckedAt.takeIf { it>0 } ?: now.epoch())
        }
        event("睡前打卡", "$day 晚 · 困意 ${log(day).sleepiness} · 心情 ${log(day).bedMood} · 影响 ${log(day).bedReason}",now.epoch())
    }
    fun applyCoachDrafts(drafts:List<CoachDraft>,originals:Map<String,DayLog>):Unit=ProjectPreferences.atomic(context) {
        require(drafts.isNotEmpty() && drafts.map { it.day }.distinct().size==drafts.size)
        val all=json("logs")
        val merged=drafts.map { draft ->
            val latest=if(all.has(draft.day)) decodeLog(all.getJSONObject(draft.day)) else DayLog(draft.day)
            require(draft.matches(originals.getValue(draft.day),latest)) { "这几项记录刚刚发生了变化，请重新预览再保存" }
            draft.merge(latest).also { require(it.note.length<=5000) { "原备注已很长，请在记录中整理后再补记" } }
        }
        merged.forEach { all.put(it.day,encodeLog(it)) }
        put("logs",all)
        event("作息补记",drafts.joinToString("；") { "${it.day} · ${it.changes(originals.getValue(it.day)).joinToString { (label,value)->if(label=="追加备注") "追加备注" else "$label：$value" }}" })
    }
    fun events(): List<EventLog> = runCatching { JSONArray(prefs.getString("events", "[]")).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { e -> EventLog(e.getLong("time"), e.getString("kind"), e.getString("detail")) } } } }.getOrDefault(emptyList())
    fun event(kind: String, detail: String,at:Long=System.currentTimeMillis()):Unit=ProjectPreferences.atomic(context) {
        val list = events() + EventLog(at, kind, detail)
        prefs.edit().putString("events", JSONArray().also { a -> list.forEach { a.put(JSONObject().put("time", it.time).put("kind", it.kind).put("detail", it.detail)) } }.toString()).apply()
    }
    fun export(): String = JSONObject().put("schema", 2).put("plan", json("plan")).put("rules", encodeRules(rules)).put("logs", json("logs")).put("habits",JSONObject(prefs.getString("habits","{\"plans\":[],\"entries\":[]}")!!)).put("events", JSONArray(prefs.getString("events", "[]"))).toString(2)
    fun import(text: String):Unit=ProjectPreferences.atomic(context) {
        require(text.length < 2_000_000) { "备份文件过大" }
        require(plan?.let { Schedule.night(it, rules, LocalDateTime.now()) } == null && !wake.active) { "请在夜间限制和起床验证结束后恢复备份" }
        val root = JSONObject(text)
        require(root.getInt("schema") in 1..2) { "不支持的备份版本" }
        val p = root.getJSONObject("plan")
        val newPlan = if(p.length()==0) null else Plan(LocalDate.parse(p.getString("start")), p.getString("action").take(120), p.getString("goal").take(120))
        val habits=if(root.getInt("schema")==2) root.getJSONObject("habits").also { HabitStore.decode(it) } else null
        val r = decodeRules(root.getJSONObject("rules")); require(r.valid()) { "备份中的作息时间不正确" }
        val logs = root.getJSONObject("logs")
        val events=if(root.has("events")) root.getJSONArray("events") else null
        events?.let { list -> for(i in 0 until list.length()) {
            val e=list.getJSONObject(i);require(e.getLong("time")>=0);e.getString("kind");e.getString("detail")
        } }
        require(logs.length() <= 10000)
        logs.keys().forEach { key -> require(LocalDate.parse(key).toString() == key); val l = decodeLog(logs.getJSONObject(key)); require(l.day == key); require(l.status in listOf("完成", "部分完成", "未完成", "未记录")); require(l.bed.isEmpty() || parseTime(l.bed) != null); require(l.rise.isEmpty() || parseTime(l.rise) != null); require(l.sleepiness.isEmpty() || l.sleepiness in BedtimeSchedule.sleepiness); require(l.bedMood.isEmpty() || l.bedMood in BedtimeSchedule.moods); require(l.bedReason.isEmpty() || l.bedReason in BedtimeSchedule.reasons); require(l.bedtimeCheckedAt>=0 && (l.bedtimeCheckedAt==0L || BedtimeSchedule.answered(l))) }
        // Validate everything before committing; active restrictions, tokens and permissions are never imported.
        val edit = prefs.edit().putString("rules", encodeRules(r).toString()).putString("logs", logs.toString())
        if(events!=null) edit.putString("events",events.toString())
        if(newPlan==null) edit.remove("plan") else edit.putString("plan",p.put("action",newPlan.action).put("goal",newPlan.goal).toString())
        if(habits!=null) edit.putString("habits",habits.toString()).remove("habit_alarm_at").remove("habit_reminded").remove("habit_timers")
        edit.remove("pending_at").remove("pending_rules").remove("wake").apply()
        event("恢复备份", "已恢复计划与日常记录；权限和验证点仍使用本机设置")
    }
    companion object {
        const val DAILY_PASSES=3
        const val PASS_MINUTES=5
        private fun strings(j: JSONObject, key: String): Set<String> = j.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        fun encodeRules(r: Rules) = JSONObject().put("bed", r.bed).put("wake", r.wake).put("weekends", r.weekends).put("weekendBed", r.weekendBed).put("weekendWake", r.weekendWake).put("blocked", JSONArray(r.blocked.toList())).put("allowed", JSONArray(r.allowed.toList())).put("secondCheck", r.secondCheck).put("secondDelay", r.secondDelay).put("passMinutes", r.passMinutes).put("passWait", r.passWaitSeconds).put("exceptions",JSONObject().also { j->r.exceptions.forEach { (d,t)->j.put(d,JSONObject().put("bed",t.bed).put("wake",t.wake)) } })
        fun decodeRules(j: JSONObject): Rules {
            val exceptions=j.optJSONObject("exceptions")
            val days=exceptions?.keys()?.asSequence()?.associateWith { d->exceptions.getJSONObject(d).let { DaySchedule(it.getInt("bed"),it.getInt("wake")) } } ?: emptyMap()
            return Rules(j.optInt("bed",1380), j.optInt("wake",420), j.optBoolean("weekends"), j.optInt("weekendBed",1380), j.optInt("weekendWake",480), strings(j,"blocked"), strings(j,"allowed"), j.optBoolean("secondCheck",true), j.optInt("secondDelay",5), j.optInt("passMinutes",5), j.optInt("passWait",60),days)
        }
        fun encodeLog(v: DayLog) = JSONObject().put("day",v.day).put("status",v.status).put("bed",v.bed).put("rise",v.rise).put("energy",v.energy).put("reason",v.reason).put("note",v.note).put("verifiedAt",v.verifiedAt).put("sleepiness",v.sleepiness).put("bedMood",v.bedMood).put("bedReason",v.bedReason).put("bedtimeCheckedAt",v.bedtimeCheckedAt)
        fun decodeLog(j: JSONObject) = DayLog(j.getString("day"),j.optString("status","未记录"),j.optString("bed"),j.optString("rise"),j.optString("energy"),j.optString("reason"),j.optString("note"),j.optLong("verifiedAt"),j.optString("sleepiness"),j.optString("bedMood"),j.optString("bedReason"),j.optLong("bedtimeCheckedAt"))
    }
}
