package com.twentyone.rhythm

import java.time.*

object BedtimeSchedule {
    val sleepiness=listOf("很困了","有点困","还不困")
    val moods=listOf("放松","平静","有压力")
    val reasons=listOf("无","工作","手机","社交","没有困意","其他")
    fun answered(log:DayLog)=log.sleepiness in sleepiness && log.bedMood in moods && log.bedReason in reasons
    fun checked(log:DayLog)=log.bedtimeCheckedAt>0 && answered(log)
    fun status(date:LocalDate,evening:DayLog,morning:DayLog):String {
        val bed=checked(evening);val wake=morning.verifiedAt>0
        return if(RestCalendar.isRest(date.plusDays(1))) {
            if(bed) "已完成" else "未完成"
        } else when { bed && wake->"已完成";bed || wake->"部分完成";else->"未完成" }
    }
    // A plan day is an evening followed by its morning; 00:00–11:59 belongs to the next calendar day.
    fun bedtime(date:LocalDate,rules:Rules)=date.plusDays(if(rules.bedtime(date)<12*60) 1 else 0).atStartOfDay().plusMinutes(rules.bedtime(date).toLong())
    fun deadline(date:LocalDate,rules:Rules)=bedtime(date,rules)
    fun morning(date:LocalDate,rules:Rules)=date.plusDays(1).atStartOfDay().plusMinutes(rules.wakeTime(date.plusDays(1)).toLong())
    fun closes(date:LocalDate,rules:Rules)=minOf(morning(date,rules),bedtime(date.plusDays(1),rules))
    // After midnight, finish the previous evening until its planned morning begins.
    fun recordDay(plan:Plan,rules:Rules,now:LocalDateTime):LocalDate? {
        val previous=now.toLocalDate().minusDays(1)
        if(plan.contains(previous) && now.isBefore(closes(previous,rules))) return previous
        return now.toLocalDate().takeIf { plan.contains(it) }
    }
    fun nextLockDay(plan:Plan,rules:Rules,logs:List<DayLog>,locked:Set<String>,now:LocalDateTime):LocalDate? =
        (0L..20L).map { plan.start.plusDays(it) }.firstOrNull { date ->
            val log=logs.firstOrNull { it.day==date.toString() } ?: DayLog(date.toString())
            date.toString() !in locked && !checked(log) && deadline(date,rules).isBefore(closes(date,rules)) && now.isBefore(closes(date,rules))
        }
}
