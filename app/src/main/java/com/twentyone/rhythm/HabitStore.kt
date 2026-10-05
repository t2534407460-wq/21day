package com.twentyone.rhythm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.*

class HabitStore(private val context:Context) {
    private val prefs=Store(context).prefs
    private val device=ProjectAccount.device(context)
    fun habits()=decode(JSONObject(prefs.getString("habits","{\"plans\":[],\"entries\":[]}")!!)).first
    fun entries()=decode(JSONObject(prefs.getString("habits","{\"plans\":[],\"entries\":[]}")!!)).second
    fun save(h:Habit,today:LocalDate=LocalDate.now()):Unit=ProjectPreferences.atomic(context) {
        require(h.valid()) { "请填写名称、有效目标和执行日期" }
        val all=habits();val old=all.find { it.id==h.id }
        require(all.size<200 || old!=null) { "最多保存 200 轮计划，请先导出备份" }
        if(old==null) require(h.start>=today && !h.archived && h.rules.size==1) { "新计划请从今天或之后开始" }
        else {
            require(!old.archived || h==old) { "已归档计划请开始新一轮" }
            require(h.archivedOn==null || h.archivedOn==today) { "归档日期应为今天" }
            require(h.start==old.start && h.mode==old.mode && h.unit==old.unit && h.input==old.input && h.smoking==old.smoking) { "计量方式和开始日期在本轮中保持不变" }
            require(h.archivedOn==null || running(h.id)==0L) { "请先结束或取消本次计时，再归档" }
            require(old.dates().filter { it<=today }.all { old.rule(it)==h.rule(it) }) { "调整只能从明天起生效" }
        }
        write(if(old==null) all+h else all.map { if(it.id==h.id) h else it },entries())
    }
    fun record(entry:HabitEntry,today:LocalDate=LocalDate.now()):Unit=ProjectPreferences.atomic(context) {
        val all=habits();val h=all.first { it.id==entry.habitId }
        require(!h.archived && h.scheduled(entry.date) && entry.date<=today) { "只能记录本轮已到日期的计划日" }
        require(validEntry(h,entry)) { "请填写有效数值，备注最多 300 字" }
        val list=entries().filterNot { it.habitId==entry.habitId && it.date==entry.date }+entry
        write(all,list)
    }
    fun removeEntry(id:String,date:LocalDate)=ProjectPreferences.atomic(context) { write(habits(),entries().filterNot { it.habitId==id && it.date==date }) }
    private fun timers()=JSONObject(prefs.getString("habit_timers","{}")!!)
    fun running(id:String)=timers().optJSONObject(id)?.optLong("at") ?: 0L
    fun otherDevice(id:String)=timers().optJSONObject(id)?.optString("device")?.let { it.isNotBlank() && it!=device } ?: false
    fun takeOverTimer(id:String):Unit=ProjectPreferences.atomic(context) {
        require(ProjectAccount.userId(context)!=null && ProjectSync.status(context).enabled){"请先登录并开启同步，再接管另一台设备的计时。"}
        val active=timers();val timer=active.getJSONObject(id);require(otherDevice(id)){"该计时已由本机负责。"}
        timer.put("device",device)
        SyncJournal.command("timer_takeover"){prefs.edit().putString("habit_timers",active.toString()).apply()}
    }
    private fun requireTimerOwner(id:String) {
        require(!otherDevice(id)){"计时由另一台设备负责，请先在打卡抽屉中接管。"}
        require(!ProjectSync.timerClaimPending(context,id)){"计时接管等待云端确认，请联网同步后再结束或取消。"}
    }
    fun startTimer(id:String,now:Long=System.currentTimeMillis()):Unit=ProjectPreferences.atomic(context) {
        val h=habits().first { it.id==id };val day=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        require(h.input==HabitInput.TIMER && !h.archived && h.scheduled(day)) { "今天不在这个习惯的执行日期内" }
        require(running(id)==0L) { "这次计时已经开始" }
        prefs.edit().putString("habit_timers",timers().put(id,JSONObject().put("at",now).put("day",day).put("id",java.util.UUID.randomUUID().toString()).put("device",device)).toString()).apply()
    }
    fun cancelTimer(id:String)=ProjectPreferences.atomic(context) { requireTimerOwner(id);val active=timers();active.remove(id);prefs.edit().putString("habit_timers",active.toString()).apply() }
    fun finishTimer(id:String,now:Long=System.currentTimeMillis()):Unit=ProjectPreferences.atomic(context) {
        requireTimerOwner(id)
        val start=running(id);require(start>0 && now>=start) { "计时状态或设备时间有变化，请取消本次计时后手动补记" }
        val h=habits().first { it.id==id };val day=LocalDate.parse(timers().getJSONObject(id).getString("day"))
        require(h.input==HabitInput.TIMER && !h.archived && h.scheduled(day)) { "这次计时已不属于当前计划，请取消后核对" }
        val list=entries();val old=list.find { it.habitId==id && it.date==day };val session=HabitSession(start,now)
        val seconds=(old?.value ?: 0)*60L+(old?.remainderSeconds ?: 0)+session.seconds
        require(seconds/60<=100000 && (old?.sessions?.size ?: 0)<200) { "记录过多，请取消本次计时并核对总量" }
        val entry=HabitEntry(id,day,(seconds/60).toInt(),old?.note ?: "",now,(old?.sessions ?: emptyList())+session,(seconds%60).toInt())
        val active=timers();active.remove(id)
        SyncJournal.command("timer_finish") { prefs.edit().putString("habits",encode(habits(),list.filterNot { it.habitId==id && it.date==day }+entry).toString()).putString("habit_timers",active.toString()).apply() }
    }
    fun count(id:String,today:LocalDate=LocalDate.now()):Unit=ProjectPreferences.atomic(context) {
        val h=habits().first { it.id==id };require(h.input==HabitInput.COUNT) { "这个习惯不是次数打卡" }
        val old=entries().find { it.habitId==id && it.date==today }
        SyncJournal.command("count") { record(HabitEntry(id,today,(old?.value ?: 0)+1,old?.note ?: ""),today) }
    }
    fun confirmDay(id:String,today:LocalDate=LocalDate.now()):Unit=ProjectPreferences.atomic(context) {
        val h=habits().first { it.id==id };require(h.input==HabitInput.DAILY) { "这个习惯不是每日确认" }
        val old=entries().find { it.habitId==id && it.date==today }
        record(HabitEntry(id,today,if(h.smoking) 0 else 1,old?.note ?: ""),today)
    }
    private fun write(all:List<Habit>,entries:List<HabitEntry>) { prefs.edit().putString("habits",encode(all,entries).toString()).apply() }
    companion object {
        private fun validEntry(h:Habit,e:HabitEntry)=h.scheduled(e.date) && e.value in 0..100000 && (h.mode!=HabitMode.CHECK || e.value in 0..1) && e.note.length<=300 && e.recordedAt>0 &&
            e.remainderSeconds in 0..59 && e.sessions.size<=200 && (h.input==HabitInput.TIMER || (e.sessions.isEmpty() && e.remainderSeconds==0)) && e.sessions.all { it.start>0 && it.end>=it.start }
        fun encode(all:List<Habit>,entries:List<HabitEntry>)=JSONObject().put("plans",JSONArray().also { a -> all.forEach { h ->
            a.put(JSONObject().put("id",h.id).put("name",h.name).put("start",h.start).put("mode",h.mode.name).put("unit",h.unit).put("archivedOn",h.archivedOn ?: JSONObject.NULL).put("input",h.input.name).put("smoking",h.smoking).put("visual",h.visual.name)
                .put("rules",JSONArray().also { rules -> h.rules.forEach { r -> rules.put(JSONObject().put("from",r.from).put("days",JSONArray(r.days.sorted())).put("target",r.target).put("reminder",r.reminder ?: JSONObject.NULL)) } }))
        } }).put("entries",JSONArray().also { a -> entries.forEach { e -> a.put(JSONObject().put("habitId",e.habitId).put("date",e.date).put("value",e.value).put("note",e.note).put("recordedAt",e.recordedAt).put("remainderSeconds",e.remainderSeconds)
            .put("sessions",JSONArray().also { sessions -> e.sessions.forEach { sessions.put(JSONObject().put("start",it.start).put("end",it.end)) } })) } })
        fun decode(root:JSONObject):Pair<List<Habit>,List<HabitEntry>> {
            val plans=root.getJSONArray("plans");val records=root.getJSONArray("entries")
            require(plans.length()<=200 && records.length()<=4200) { "习惯备份条目过多" }
            val all=(0 until plans.length()).map { i -> val j=plans.getJSONObject(i);val r=j.getJSONArray("rules")
                Habit(j.getString("id"),j.getString("name"),LocalDate.parse(j.getString("start")),HabitMode.valueOf(j.getString("mode")),j.getString("unit"),
                    (0 until r.length()).map { k -> val rule=r.getJSONObject(k);val d=rule.getJSONArray("days")
                        HabitRule(LocalDate.parse(rule.getString("from")),(0 until d.length()).map { d.getInt(it) }.toSet(),rule.getInt("target"),if(rule.isNull("reminder")) null else rule.getInt("reminder")) },if(j.isNull("archivedOn")) null else LocalDate.parse(j.getString("archivedOn")),
                    if(j.has("input")) HabitInput.valueOf(j.getString("input")) else legacyHabitInput(j.getString("name"),HabitMode.valueOf(j.getString("mode")),j.getString("unit")),j.optBoolean("smoking",j.getString("name") in setOf("戒烟","控烟")),
                    if(j.has("visual")) HabitVisual.valueOf(j.getString("visual")) else legacyHabitVisual(j.getString("name"),j.optBoolean("smoking",j.getString("name") in setOf("戒烟","控烟"))))
            }
            require(all.all { it.valid() } && all.map { it.id }.distinct().size==all.size) { "习惯计划格式不正确" }
            val entries=(0 until records.length()).map { i -> val j=records.getJSONObject(i)
                val sessions=j.optJSONArray("sessions") ?: JSONArray()
                HabitEntry(j.getString("habitId"),LocalDate.parse(j.getString("date")),j.getInt("value"),j.getString("note"),j.getLong("recordedAt"),
                    (0 until sessions.length()).map { k -> sessions.getJSONObject(k).let { HabitSession(it.getLong("start"),it.getLong("end")) } },j.optInt("remainderSeconds"))
            }
            require(entries.map { HabitAnalysis.key(it.habitId,it.date) }.distinct().size==entries.size && entries.all { e -> all.find { it.id==e.habitId }?.let { validEntry(it,e) }==true }) { "习惯打卡格式不正确" }
            return all to entries
        }
    }
}
