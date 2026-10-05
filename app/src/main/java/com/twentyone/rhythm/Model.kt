package com.twentyone.rhythm

import java.time.*

data class DaySchedule(val bed: Int, val wake: Int)
data class Rules(
    val bed: Int = 23 * 60, val wake: Int = 7 * 60, val weekends: Boolean = false,
    val weekendBed: Int = 2 * 60, val weekendWake: Int = 10 * 60,
    val blocked: Set<String> = emptySet(), val allowed: Set<String> = emptySet(),
    val secondCheck: Boolean = true, val secondDelay: Int = 5,
    // Retained for older backups/sync clients; runtime passes are fixed by Store's policy.
    val passMinutes: Int = 5, val passWaitSeconds: Int = 60,
    val exceptions: Map<String, DaySchedule> = emptyMap()
) {
    fun bedtime(date: LocalDate) = exceptions[date.toString()]?.bed ?: if (weekends && RestCalendar.isRest(date.plusDays(1))) weekendBed else bed
    fun bedtimeSource(date: LocalDate) = if(exceptions.containsKey(date.toString())) "特殊日期安排" else if(weekends && RestCalendar.isRest(date.plusDays(1))) {
        if(RestCalendar.holiday(date.plusDays(1))) "法定假期 · 休息日安排" else "休息日安排"
    } else "普通作息"
    fun wakeTime(date: LocalDate) = exceptions[date.toString()]?.wake ?: if (weekends && RestCalendar.isRest(date)) weekendWake else wake
    fun valid() = listOf(bed, wake, weekendBed, weekendWake).all { it in 0..1439 } &&
        bed != wake && weekendBed != weekendWake && secondDelay in 1..15 && passMinutes in 1..10 && passWaitSeconds in 30..180 &&
        exceptions.size<=90 && exceptions.all { (d,t)->runCatching { LocalDate.parse(d) }.isSuccess && t.bed in 0..1439 && t.wake in 0..1439 && t.bed!=t.wake }
}
data class Plan(val start: LocalDate = LocalDate.now(), val action: String = "洗漱后，把手机放到固定充电处", val goal: String = "规律睡觉，清醒地开始每一天") {
    fun day(date: LocalDate) = java.time.temporal.ChronoUnit.DAYS.between(start, date).toInt() + 1
    fun contains(date: LocalDate) = day(date) in 1..21
}
data class NightWindow(val date: LocalDate, val start: LocalDateTime, val end: LocalDateTime)
object Schedule {
    // A rules refresh at the wake boundary must not replace an undelivered alarm with tomorrow's.
    fun preserveDueWake(scheduled: Long, now: Long, handled: Boolean) = !handled && scheduled>0 && now>=scheduled && now-scheduled<=120_000
    fun night(plan: Plan, rules: Rules, now: LocalDateTime): NightWindow? {
        for (date in listOf(now.toLocalDate().minusDays(1), now.toLocalDate())) {
            if (!plan.contains(date)) continue
            val start = BedtimeSchedule.bedtime(date,rules)
            val end = BedtimeSchedule.closes(date,rules)
            if (!now.isBefore(start) && now.isBefore(end)) return NightWindow(date, start, end)
        }
        return null
    }
    fun nextWake(plan: Plan, rules: Rules, now: LocalDateTime): LocalDateTime? =
        (0L..21L).map { plan.start.plusDays(it) }.filterNot { RestCalendar.isRest(it) }.map { it.atStartOfDay().plusMinutes(rules.wakeTime(it).toLong()) }.firstOrNull { it.isAfter(now) }
    fun nextWindDown(plan: Plan, rules: Rules, now: LocalDateTime): LocalDateTime? =
        (0L..20L).map { BedtimeSchedule.bedtime(plan.start.plusDays(it),rules).minusMinutes(15) }.firstOrNull { it.isAfter(now) }
    // Recheck the effective rules at delivery; an old queued callback is not proof that a reminder is due.
    fun windDownBedtime(plan: Plan, rules: Rules, now: LocalDateTime): LocalDateTime? =
        (0L..20L).map { plan.start.plusDays(it) }
            .map { BedtimeSchedule.bedtime(it,rules) }
            .firstOrNull { !now.isBefore(it.minusMinutes(15)) && now.isBefore(it) }
    fun blocks(packageName: String, rules: Rules, night: NightWindow?, passPackage: String, passUntil: Long, now: Long) =
        night != null && packageName in rules.blocked && packageName !in rules.allowed &&
            !(packageName == passPackage && now < passUntil)
}
enum class WakePhase { IDLE, RINGING, WALK, SECOND_WAIT, SECOND_READY, COMPLETE, STOPPED, TIMED_OUT }
data class WakeSession(
    val day: String = "", val phase: WakePhase = WakePhase.IDLE, val dueAt: Long = 0,
    val attempts: Int = 0, val firstAt: Long = 0, val completedAt: Long = 0,
    val test: Boolean = false
) {
    val active get() = phase in setOf(WakePhase.RINGING, WakePhase.WALK, WakePhase.SECOND_WAIT, WakePhase.SECOND_READY)
    fun begin(now: Long): WakeSession = if (phase in setOf(WakePhase.RINGING, WakePhase.SECOND_READY)) copy(phase = WakePhase.WALK, dueAt = now + 180_000) else this
    fun verify(now: Long, rules: Rules): WakeSession {
        if (!active || (phase == WakePhase.SECOND_WAIT && now < dueAt)) return this
        return if (firstAt == 0L && rules.secondCheck) copy(phase = WakePhase.SECOND_WAIT, firstAt = now, dueAt = now + rules.secondDelay * 60_000L)
        else copy(phase = WakePhase.COMPLETE, firstAt = if (firstAt == 0L) now else firstAt, completedAt = now, dueAt = 0)
    }
    fun retry(now: Long): WakeSession {
        if (!active) return this
        if (phase == WakePhase.SECOND_WAIT && now >= dueAt) return copy(phase = WakePhase.SECOND_READY, dueAt = now + 60_000)
        return if (attempts >= 3) copy(phase = WakePhase.TIMED_OUT, dueAt = 0)
        else copy(phase = if (firstAt > 0) WakePhase.SECOND_READY else WakePhase.RINGING, attempts = attempts + 1, dueAt = now + 60_000)
    }
}
data class DayLog(val day: String, val status: String = "未记录", val bed: String = "", val rise: String = "", val energy: String = "", val reason: String = "", val note: String = "", val verifiedAt: Long = 0,
    val sleepiness: String = "", val bedMood: String = "", val bedReason: String = "", val bedtimeCheckedAt: Long = 0)
data class EventLog(val time: Long, val kind: String, val detail: String)
fun timeText(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
fun parseTime(text: String): Int? = runCatching { LocalTime.parse(text.trim()).let { it.hour * 60 + it.minute } }.getOrNull()
fun LocalDateTime.epoch() = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
