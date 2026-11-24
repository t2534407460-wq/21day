package com.twentyone.rhythm

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class RestWakeTest {
    private val p=Plan(LocalDate.parse("2026-10-01"))
    private val r=Rules(bed=0,wake=510,weekends=true,weekendBed=120,weekendWake=600)
    @Test fun nationalDaySkipsAllSevenMornings() {
        for(day in 1..7) assertEquals(LocalDateTime.parse("2026-10-08T08:30"),Schedule.nextWake(p,r,LocalDate.of(2026,10,day).atStartOfDay()))
    }
    @Test fun ordinaryWeekendSkipsToMonday() {
        assertEquals(LocalDateTime.parse("2026-10-19T08:30"),Schedule.nextWake(p,r,LocalDateTime.parse("2026-10-17T00:00")))
    }
    @Test fun makeupSaturdayStillWakesAndUsesSpecialTime() {
        val rules=r.copy(exceptions=mapOf("2026-10-10" to DaySchedule(0,420)))
        assertEquals(LocalDateTime.parse("2026-10-10T07:00"),Schedule.nextWake(p,rules,LocalDateTime.parse("2026-10-09T12:00")))
    }
    @Test fun restMorningIsNotForcedByTimeOverrideOrSharedTimes() {
        val rules=r.copy(weekends=false,exceptions=mapOf("2026-10-04" to DaySchedule(0,360)))
        assertEquals(360,rules.wakeTime(LocalDate.parse("2026-10-04")))
        assertEquals(LocalDateTime.parse("2026-10-08T08:30"),Schedule.nextWake(p,rules,LocalDateTime.parse("2026-10-04T01:00")))
    }
    @Test fun alarmContinuesBeyondFirstCycleAndBedtimeIsUnchanged() {
        val plan=Plan(LocalDate.parse("2026-09-13"))
        assertEquals(LocalDateTime.parse("2026-10-08T08:30"),Schedule.nextWake(plan,r,LocalDateTime.parse("2026-10-01T00:00")))
        assertEquals(LocalDateTime.parse("2026-10-04T01:45"),Schedule.nextWindDown(p,r,LocalDateTime.parse("2026-10-04T01:00")))
        assertEquals(LocalDateTime.parse("2026-10-04T10:00"),BedtimeSchedule.morning(LocalDate.parse("2026-10-03"),r))
    }
    @Test fun pauseSkipsOnlyItsDateAndStillFollowsTheWorkdayCalendar() {
        val friday=LocalDate.parse("2026-10-09")
        val saturday=friday.plusDays(1) // 调休上班
        val now=friday.atStartOfDay()
        val rules=r.copy(exceptions=mapOf(saturday.toString() to DaySchedule(0,420)))
        assertEquals(saturday.atTime(7,0),Schedule.nextWake(p,rules,now,friday))
        assertEquals(friday.atTime(8,30),Schedule.nextWake(p,rules,now,saturday))
        assertEquals(friday.plusDays(3).atTime(8,30),Schedule.nextWake(p,rules,saturday.atStartOfDay(),saturday))
        assertEquals(friday.atTime(8,30),Schedule.nextWake(p,rules,now,friday.minusDays(1)))
        assertEquals(Schedule.night(p,rules,friday.atTime(1,0))?.end,friday.atTime(8,30))
    }
    @Test fun pauseDateMustHaveAnUpcomingFormalWake() {
        val saturday=LocalDate.parse("2026-10-10")
        val wake=saturday.atTime(7,0)
        val rules=r.copy(exceptions=mapOf(saturday.toString() to DaySchedule(0,420)))
        assertTrue(Schedule.canPauseWake(p,rules,saturday,wake.minusSeconds(1)))
        assertFalse(Schedule.canPauseWake(p,rules,saturday,wake))
        assertFalse(Schedule.canPauseWake(p,rules,saturday.plusDays(1),wake))
        assertFalse(Schedule.canPauseWake(p.copy(start=saturday.plusDays(1)),rules,saturday,wake.minusSeconds(1)))
    }
}
