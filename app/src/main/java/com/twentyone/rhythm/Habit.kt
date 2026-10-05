package com.twentyone.rhythm

import java.time.*
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class HabitMode(val label:String) { CHECK("完成打卡"), AT_LEAST("至少达到"), AT_MOST("不超过") }
enum class HabitInput(val label:String) { MANUAL("填写总量"), TIMER("时段计时"), COUNT("次数打卡"), DAILY("每日确认") }
enum class HabitVisual { SMOKING, EXERCISE, READING, WORK }
fun legacyHabitVisual(name:String,smoking:Boolean)=when { smoking->HabitVisual.SMOKING;name=="运动"->HabitVisual.EXERCISE;name=="阅读"->HabitVisual.READING;else->HabitVisual.WORK }
fun legacyHabitInput(name:String,mode:HabitMode,unit:String)=when {
    name in setOf("阅读","运动") && unit=="分钟" && mode==HabitMode.AT_LEAST -> HabitInput.TIMER
    name in setOf("戒烟","控烟") && unit=="支" && mode==HabitMode.AT_MOST -> HabitInput.COUNT
    mode==HabitMode.CHECK -> HabitInput.DAILY
    else -> HabitInput.MANUAL
}
data class HabitRule(val from:LocalDate,val days:Set<Int> = (1..7).toSet(),val target:Int=1,val reminder:Int?=21*60) {
    fun valid(mode:HabitMode)=days.isNotEmpty() && days.all { it in 1..7 } && target in 0..100000 &&
        (mode==HabitMode.AT_MOST || target>0) && (mode!=HabitMode.CHECK || target==1) && (reminder==null || reminder in 0..1439)
}
data class Habit(val id:String=UUID.randomUUID().toString(),val name:String,val start:LocalDate=LocalDate.now(),
    val mode:HabitMode=HabitMode.CHECK,val unit:String="次",val rules:List<HabitRule>,val archivedOn:LocalDate?=null,
    val input:HabitInput=legacyHabitInput(name,mode,unit),val smoking:Boolean=name in setOf("戒烟","控烟"),
    val visual:HabitVisual=legacyHabitVisual(name,smoking)) {
    val archived get()=archivedOn!=null
    val end get()=start.plusDays(20)
    fun day(date:LocalDate)=ChronoUnit.DAYS.between(start,date).toInt()+1
    fun rule(date:LocalDate)=rules.lastOrNull { !it.from.isAfter(date) } ?: rules.first()
    fun scheduled(date:LocalDate)=date>=start && date<=end && (archivedOn==null || date<=archivedOn) && date.dayOfWeek.value in rule(date).days
    fun dates()=(0L..20L).map { start.plusDays(it) }
    fun reached(date:LocalDate,value:Int)=when(mode) { HabitMode.CHECK->value==1;HabitMode.AT_LEAST->value>=rule(date).target;HabitMode.AT_MOST->value<=rule(date).target }
    fun goal(date:LocalDate)=when(mode) { HabitMode.CHECK->"完成一次";HabitMode.AT_LEAST->"至少 ${rule(date).target} $unit";HabitMode.AT_MOST->"不超过 ${rule(date).target} $unit" }
    fun valid()=runCatching { UUID.fromString(id); true }.getOrDefault(false) && name.isNotBlank() && name.length<=40 && unit.isNotBlank() && unit.length<=8 &&
        rules.isNotEmpty() && rules.size<=21 && rules.first().from==start && rules.map { it.from }==rules.map { it.from }.distinct().sorted() &&
        rules.all { it.from in start..end && it.valid(mode) } &&
        (input!=HabitInput.TIMER || (unit=="分钟" && mode==HabitMode.AT_LEAST)) &&
        (input!=HabitInput.COUNT || mode!=HabitMode.CHECK) && (!smoking || unit=="支") &&
        (input!=HabitInput.DAILY || mode==HabitMode.CHECK || (smoking && mode==HabitMode.AT_MOST && rules.all { it.target==0 }))
}
data class HabitSession(val start:Long,val end:Long) { val seconds get()=(end-start)/1000 }
data class HabitEntry(val habitId:String,val date:LocalDate,val value:Int,val note:String="",val recordedAt:Long=System.currentTimeMillis(),
    val sessions:List<HabitSession> = emptyList(),val remainderSeconds:Int=0)
data class HabitStats(val scheduled:Int,val recorded:Int,val reached:Int,val missed:Int,val average:Double?)
object HabitAnalysis {
    fun stats(h:Habit,entries:List<HabitEntry>,today:LocalDate,from:LocalDate=h.start,to:LocalDate=h.end):HabitStats {
        val dates=h.dates().filter { h.scheduled(it) && it<=today && it in from..to }
        val recorded=entries.filter { it.habitId==h.id && it.date in dates }
        return HabitStats(dates.size,recorded.size,recorded.count { h.reached(it.date,it.value) },dates.count { it<today && recorded.none { e->e.date==it } },
            recorded.takeIf { it.isNotEmpty() }?.map { it.value }?.average())
    }
    // One reminder for an unrecorded scheduled day. Missing entries never imply a zero value.
    fun nextReminder(habits:List<Habit>,entries:List<HabitEntry>,sent:Set<String>,now:LocalDateTime):LocalDateTime? = habits.filter { !it.archived }.flatMap { h ->
        h.dates().mapNotNull { date ->
            val minute=h.rule(date).reminder
            if(!h.scheduled(date) || minute==null || entries.any { it.habitId==h.id && it.date==date } || key(h.id,date) in sent) null
            else date.atStartOfDay().plusMinutes(minute.toLong()).takeIf { it.isAfter(now) }
        }
    }.minOrNull()
    fun key(id:String,date:LocalDate)="$id/$date"
    fun advice(h:Habit,entries:List<HabitEntry>,today:LocalDate):String {
        val s=stats(h,entries,today)
        return when {
            s.recorded<3->"先留下 3 次真实记录，再决定是否调整目标。未记录的日期不会按零计算。"
            s.missed>=3->"有 ${s.missed} 个过去的计划日未记录。先检查提醒时间是否方便，或减少每周执行的天数。"
            s.reached*2<s.recorded->if(h.mode==HabitMode.AT_MOST) "多数已记录日超过了自定上限。回看备注中的诱因，选择一个能执行的应对动作。" else "多数已记录日还没达到目标。可缩小单次目标，先让开始这件事更容易。"
            else->"已有 ${s.reached} 次达到当日目标。可以先保持当前安排，再结合每周趋势做小幅调整。"
        }
    }
}
