package com.twentyone.rhythm

import java.time.*
import java.time.format.DateTimeFormatter

data class HistoryRecord(val day:LocalDate,val time:Long,val kind:String,val detail:String)
object RecordHistory {
    fun records(logs:List<DayLog>,habits:List<Habit>,entries:List<HabitEntry>,events:List<EventLog>,plan:Plan?=null,today:LocalDate=LocalDate.now()):List<HistoryRecord> {
        val result=mutableListOf<HistoryRecord>()
        val dates=(logs.map { LocalDate.parse(it.day) }+(plan?.let { p -> (0L..20L).map { p.start.plusDays(it) } } ?: emptyList())).distinct().filter { it<=today }
        dates.forEach { date ->
            result+=HistoryRecord(date,0,"作息完成度","$date 晚 → ${date.plusDays(1)} 早 · "+BedtimeSchedule.status(date,logs.find { it.day==date.toString() } ?: DayLog(date.toString()),logs.find { it.day==date.plusDays(1).toString() } ?: DayLog(date.plusDays(1).toString())))
        }
        logs.forEach { log ->
            val date=LocalDate.parse(log.day)
            if(BedtimeSchedule.checked(log)) result+=HistoryRecord(date,log.bedtimeCheckedAt,"睡前记录","困意 ${log.sleepiness} · 心情 ${log.bedMood} · 影响 ${log.bedReason}")
            if(log.verifiedAt>0) result+=HistoryRecord(date,log.verifiedAt,"醒后验证","全部验证已完成 · 起床 ${log.rise.ifEmpty { "—" }}")
            if(listOf(log.bed,log.rise,log.energy,log.reason,log.note).any(String::isNotBlank)) result+=HistoryRecord(date,0,"作息自述","上床 ${log.bed.ifEmpty { "—" }} · 起床 ${log.rise.ifEmpty { "—" }} · 精神 ${log.energy.ifEmpty { "—" }} · 影响 ${log.reason.ifEmpty { "—" }}")
        }
        entries.forEach { entry ->
            val habit=habits.find { it.id==entry.habitId } ?: return@forEach
            result+=HistoryRecord(entry.date,entry.recordedAt,"习惯日总量","${habit.name} · ${entry.value} ${habit.unit}"+(if(entry.remainderSeconds>0) " ${entry.remainderSeconds}秒" else "")+" · ${if(habit.reached(entry.date,entry.value)) "达标" else "未达标"} · 目标 ${habit.goal(entry.date)}")
            entry.sessions.forEach { session -> result+=HistoryRecord(entry.date,session.end,"习惯时段","${habit.name} · ${time(session.start)} → ${time(session.end)} · ${session.seconds}秒") }
        }
        events.forEach { event -> result+=HistoryRecord(Instant.ofEpochMilli(event.time).atZone(ZoneId.systemDefault()).toLocalDate(),event.time,event.kind,event.detail) }
        return result.sortedWith(compareByDescending<HistoryRecord> { it.day }.thenByDescending { it.time })
    }
    fun time(at:Long)=Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    fun facts(logs:List<DayLog>,plan:Plan?,habits:List<Habit>,entries:List<HabitEntry>,events:List<EventLog>,today:LocalDate,days:Int):CoachFacts {
        val sleep=CoachAnalysis.facts(logs,today,days,plan)
        val history=records(logs,habits,entries,events,plan,today).filter { it.day in sleep.from..today }
        val selected=history.take(60)
        val text=buildString {
            appendLine(sleep.text)
            appendLine("习惯和操作历史：${history.size} 条；日总量是当前累计值，操作是变化过程，不可再次相加。")
            val relevant=habits.filter { it.start<=today && it.end>=sleep.from }
            relevant.take(30).forEach { h ->
                val stats=HabitAnalysis.stats(h,entries,today,sleep.from,today)
                val own=entries.filter { it.habitId==h.id && it.date in sleep.from..today }
                appendLine("习惯 ${h.name}：${h.input.label}，${h.start} 至 ${h.end}，${if(h.archived) "已归档" else "进行中"}；所选范围计划 ${stats.scheduled} 天，记录 ${stats.recorded} 天，达标 ${stats.reached} 天，实际累计 ${own.sumOf { it.value.toLong() }} ${h.unit}，余秒累计 ${own.sumOf { it.remainderSeconds }}。")
            }
            if(relevant.size>30) appendLine("习惯摘要仅发送前30个，其余未发送。")
            selected.forEach { row -> appendLine("${row.day}"+(if(row.time>0) " (${time(row.time)})" else "")+" · ${row.kind}：${row.detail.take(240)}") }
            if(selected.size<history.size) appendLine("本次只发送最新 ${selected.size} 条明细；更早记录仍在本机历史中，未发送部分不能据此判断。")
        }.trim()
        val recorded=(0 until days).count { offset -> val date=sleep.from.plusDays(offset.toLong());history.any { it.day==date && it.kind!="作息完成度" } }
        return sleep.copy(recorded=recorded,text=if(text.length<=16000) text else text.take(15500)+"\n本次输入达到长度限制；未发送部分不能据此判断，请缩小历史日期范围。")
    }
}
