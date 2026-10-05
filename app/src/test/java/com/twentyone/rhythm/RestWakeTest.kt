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
    @Test fun noAlarmBeyondPlanAndBedtimeIsUnchanged() {
        val plan=Plan(LocalDate.parse("2026-09-13"))
        assertNull(Schedule.nextWake(plan,r,LocalDateTime.parse("2026-10-01T00:00")))
        assertEquals(LocalDateTime.parse("2026-10-04T01:45"),Schedule.nextWindDown(p,r,LocalDateTime.parse("2026-10-04T01:00")))
        assertEquals(LocalDateTime.parse("2026-10-04T10:00"),BedtimeSchedule.morning(LocalDate.parse("2026-10-03"),r))
    }
}
