package com.twentyone.rhythm

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class HolidayNightTest {
    private val p=Plan(LocalDate.parse("2026-09-25"))
    private val r=Rules(bed=0,wake=510,weekends=true,weekendBed=120,weekendWake=600)
    @Test fun nationalDayUsesRestScheduleIncludingWeekdays() {
        val evening=LocalDate.parse("2026-10-04")
        assertEquals(120,r.bedtime(evening))
        assertEquals(600,r.wakeTime(evening.plusDays(1)))
        assertEquals(LocalDateTime.parse("2026-10-05T02:00"),BedtimeSchedule.bedtime(evening,r))
    }
    @Test fun octoberFourthAtTwoBelongsToThirdEvening() {
        val now=LocalDateTime.parse("2026-10-04T02:05")
        val evening=LocalDate.parse("2026-10-03")
        assertEquals(evening,BedtimeSchedule.recordDay(p,r,now))
        assertEquals(evening,Schedule.night(p,r,now)!!.date)
        assertEquals(now.toLocalDate().atTime(10,0),Schedule.night(p,r,now)!!.end)
        assertEquals(now.toLocalDate().atTime(2,0),BedtimeSchedule.deadline(evening,r))
    }
    @Test fun earlyCheckInAndPreparationUseTheSameNight() {
        val now=LocalDateTime.parse("2026-10-04T01:50")
        assertEquals(LocalDate.parse("2026-10-03"),BedtimeSchedule.recordDay(p,r,now))
        assertEquals(now.toLocalDate().atTime(2,0),Schedule.windDownBedtime(p,r,now))
        assertNull(Schedule.night(p,r,now))
    }
    @Test fun makeupSaturdayAndHolidayEndUseWorkSchedule() {
        assertEquals(0,r.bedtime(LocalDate.parse("2026-10-09")))
        assertEquals(510,r.wakeTime(LocalDate.parse("2026-10-10")))
        assertEquals(0,r.bedtime(LocalDate.parse("2026-10-07")))
        assertEquals(510,r.wakeTime(LocalDate.parse("2026-10-08")))
    }
    @Test fun explicitDatesAndDisabledRestRulesKeepPriority() {
        val date=LocalDate.parse("2026-10-04")
        assertEquals(90,r.copy(exceptions=mapOf(date.toString() to DaySchedule(90,660))).bedtime(date))
        assertEquals(0,r.copy(weekends=false).bedtime(date))
        assertEquals(510,r.copy(weekends=false).wakeTime(date))
    }
}
