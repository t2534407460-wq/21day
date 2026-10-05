package com.twentyone.rhythm

import android.content.Context
import android.os.Bundle
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object BedtimeReminders {
    fun evening(at:LocalDateTime)=at.toLocalDate().minusDays(if(at.hour<12) 1 else 0)
    private fun sent(s:Store,kind:String)=s.prefs.getStringSet("bedtime_sent_$kind",emptySet())!!.toSet()
    private fun token(day:LocalDate,r:Rules)="$day/${BedtimeSchedule.bedtime(day,r).epoch()}"
    private fun mark(s:Store,kind:String,day:LocalDate) {
        val keep=sent(s,kind).filter { it.substringBefore('/')>=day.minusDays(30).toString() }
        s.prefs.edit().putStringSet("bedtime_sent_$kind",(keep+token(day,s.rules)).toSet()).apply()
    }
    fun preparation(c:Context,now:LocalDateTime=LocalDateTime.now()) {
        val s=Store(c);val p=s.plan ?: return
        val at=Schedule.windDownBedtime(p,s.rules,now)
        val day=at?.let(::evening)
        val pending=at?.takeUnless { BedtimeSchedule.checked(s.log(day.toString())) }
        Notifications.clearStaleBedtime(c,pending)
        if(pending==null || token(day!!,s.rules) in sent(s,"prep")) return
        val format=DateTimeFormatter.ofPattern("M月d日 HH:mm")
        Notifications.info(c,Notifications.BEDTIME_TITLE,"${at.format(format)} 准备休息（${s.rules.bedtimeSource(day)}）。请完成睡前打卡；到点仍未完成时，在已授权的情况下锁屏一次。",extras=Bundle().apply { putLong(Notifications.BEDTIME_AT,at.epoch()) },bedtime=true)
        mark(s,"prep",day)
    }
    fun nextDay(s:Store,now:LocalDateTime):LocalDate? {
        val p=s.plan ?: return null
        return (0L..20L).map { p.start.plusDays(it) }.firstOrNull { day ->
            !BedtimeSchedule.checked(s.log(day.toString())) && token(day,s.rules) !in sent(s,"due") &&
                BedtimeSchedule.bedtime(day,s.rules)<BedtimeSchedule.closes(day,s.rules) && now<BedtimeSchedule.closes(day,s.rules)
        }
    }
    fun due(c:Context,now:LocalDateTime=LocalDateTime.now()) {
        val s=Store(c);val day=nextDay(s,now) ?: return
        val at=BedtimeSchedule.bedtime(day,s.rules)
        if(now<at) return
        Notifications.info(c,"到睡觉时间了 · 睡前打卡","计划 ${at.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))} 休息。请留下困意、心情和影响原因。"+
            if(BedtimeLock.available(c)) "未打卡，将执行一次系统锁屏；解锁后可补记。" else "系统锁屏尚未授权，请在权限与后台中开启。",26,bedtime=true)
        mark(s,"due",day)
        s.event("睡前到点提醒","$day 晚间，计划 ${at}；已检查睡前打卡")
    }
}
