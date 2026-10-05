package com.twentyone.rhythm

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class BedtimeScheduleTest {
    private val day=LocalDate.parse("2026-10-03")
    private val plan=Plan(day)
    private val rules=Rules()
    @Test fun midnightBelongsToPreviousEveningUntilPlannedWake() {
        assertEquals(day,BedtimeSchedule.recordDay(plan,rules,day.plusDays(1).atTime(0,30)))
        assertEquals(day,BedtimeSchedule.recordDay(plan,rules,day.plusDays(1).atTime(6,59,59)))
        assertEquals(day.plusDays(1),BedtimeSchedule.recordDay(plan,rules,day.plusDays(1).atTime(7,0)))
        assertEquals(day.atTime(23,0),BedtimeSchedule.deadline(day,rules))
    }
    @Test fun finalEveningIncludesTheTwentySecondMorning() {
        val last=day.plusDays(20)
        assertEquals(last,BedtimeSchedule.recordDay(plan,rules,last.plusDays(1).atTime(6,30)))
        assertNull(BedtimeSchedule.recordDay(plan,rules,last.plusDays(1).atTime(7,0)))
        assertNull(BedtimeSchedule.recordDay(plan,rules,day.minusDays(1).atTime(22,0)))
    }
    @Test fun exceptionsAndWeekendUseTheCorrectCalendarDates() {
        val special=rules.copy(weekends=true,weekendBed=22*60,weekendWake=9*60,exceptions=mapOf(day.toString() to DaySchedule(23*60+40,8*60),day.plusDays(1).toString() to DaySchedule(23*60,10*60)))
        assertEquals(day.atTime(23,40),BedtimeSchedule.deadline(day,special))
        assertEquals(day.plusDays(1).atTime(10,0),BedtimeSchedule.morning(day,special))
        assertEquals(day.atTime(22,0),BedtimeSchedule.deadline(day,rules.copy(weekends=true,weekendBed=22*60)))
    }
    @Test fun morningBedtimeStillBelongsToPreviousPlanEvening() {
        val daytime=Rules(bed=7*60,wake=10*60)
        val now=day.plusDays(1).atTime(8,30)
        assertEquals(day,BedtimeSchedule.recordDay(plan,daytime,now))
        assertEquals(day,BedtimeSchedule.nextLockDay(plan,daytime,emptyList(),emptySet(),now))
    }
    @Test fun oldActionOrPartialAnswersCannotBypassBedtimeCompletion() {
        val old=DayLog(day.toString(),status="完成",verifiedAt=123)
        assertFalse(BedtimeSchedule.checked(old))
        val answers=old.copy(sleepiness="很困了",bedMood="平静",bedReason="无")
        assertFalse(BedtimeSchedule.checked(answers))
        assertTrue(BedtimeSchedule.checked(answers.copy(bedtimeCheckedAt=456)))
        assertFalse(BedtimeSchedule.checked(answers.copy(bedtimeCheckedAt=456,bedMood="")))
    }
    @Test fun completedAndAlreadyLockedNightsAreSkippedAndStaleNightsExpire() {
        val checked=DayLog(day.toString(),sleepiness="很困了",bedMood="平静",bedReason="无",bedtimeCheckedAt=123)
        assertEquals(day.plusDays(1),BedtimeSchedule.nextLockDay(plan,rules,listOf(checked),emptySet(),day.atTime(22,0)))
        assertEquals(day.plusDays(1),BedtimeSchedule.nextLockDay(plan,rules,emptyList(),setOf(day.toString()),day.atTime(22,0)))
        assertEquals(day,BedtimeSchedule.nextLockDay(plan,rules,emptyList(),emptySet(),day.plusDays(1).atTime(6,59)))
        assertEquals(day.plusDays(1),BedtimeSchedule.nextLockDay(plan,rules,emptyList(),emptySet(),day.plusDays(1).atTime(7,0)))
        assertNull(BedtimeSchedule.nextLockDay(plan,rules,emptyList(),emptySet(),day.plusDays(21).atTime(7,0)))
    }
}
