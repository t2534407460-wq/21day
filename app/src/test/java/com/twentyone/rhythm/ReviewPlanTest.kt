package com.twentyone.rhythm

import org.junit.Test
import org.junit.Assert.*
import java.time.*

class ReviewPlanTest {
    private val start=LocalDate.of(2026,10,3)
    private fun habit()=Habit(name="阅读",start=start,mode=HabitMode.AT_LEAST,unit="分钟",rules=listOf(HabitRule(start,target=20)))
    @Test fun sundayMidnightReviewsThePreviousSevenDays() {
        val specs=Reviews.specs(listOf(habit()),Plan(start),start,true,true)
        val first=specs.first { it.kind=="week" }
        assertEquals(LocalDateTime.parse("2026-10-04T00:00"),first.due)
        assertEquals(LocalDate.parse("2026-09-27"),first.from);assertEquals(start,first.to)
        assertEquals(LocalDate.parse("2026-10-11"),Reviews.sundayAfter(LocalDate.parse("2026-10-04")))
    }
    @Test fun eachCycleHasItsOwnIdAndCompletesAfterDay21() {
        val h=habit();val other=habit()
        val specs=Reviews.specs(listOf(h,other),null,start,false,true)
        assertEquals(2,specs.size);assertEquals(2,specs.map { it.id }.distinct().size)
        assertEquals(h.start.plusDays(21).atStartOfDay(),specs[0].due)
        assertTrue(Reviews.specs(listOf(h.copy(archivedOn=start.plusDays(5))),null,start,false,true).isEmpty())
    }
    @Test fun factsUseHistoricalGoalsAndExcludeNotes() {
        val h=habit().copy(rules=listOf(HabitRule(start,target=20),HabitRule(start.plusDays(1),target=10)))
        val spec=ReviewSpec("test","cycle",h.id,h.name,start,h.end)
        val facts=Reviews.habitFacts(h,listOf(HabitEntry(h.id,start,15,"private-note"),HabitEntry(h.id,start.plusDays(1),15)),spec)
        assertTrue(facts.contains("达标 1"));assertTrue(facts.contains("至少 20 分钟"));assertTrue(facts.contains("至少 10 分钟"))
        assertFalse(facts.contains("private-note"));assertTrue(facts.contains("未记录 19"))
        assertTrue(Reviews.prompt(spec,facts).length<12000)
    }
    @Test fun reminderUsesNewTimeAndRejectsStaleOrLateCallbacks() {
        val plan=Plan(start);val old=Rules(bed=60);val changed=old.copy(bed=120)
        val edited=LocalDateTime.parse("2026-10-03T23:10")
        assertNull(Schedule.night(plan,old,edited))
        assertEquals(LocalDateTime.parse("2026-10-04T01:45"),Schedule.nextWindDown(plan,changed,edited))
        assertNull(Schedule.windDownBedtime(plan,changed,LocalDateTime.parse("2026-10-03T23:45")))
        assertNull(Schedule.windDownBedtime(plan,changed,LocalDateTime.parse("2026-10-04T00:45")))
        assertEquals(LocalDateTime.parse("2026-10-04T02:00"),Schedule.windDownBedtime(plan,changed,LocalDateTime.parse("2026-10-04T01:45")))
        assertNull(Schedule.windDownBedtime(plan,changed,LocalDateTime.parse("2026-10-04T02:00")))
    }
    @Test fun midnightWeekendExceptionsAndPlanBoundaryStillApply() {
        val plan=Plan(start);val midnight=Rules(bed=0)
        assertEquals(start.plusDays(1).atStartOfDay(),Schedule.windDownBedtime(plan,midnight,start.atTime(23,45)))
        assertNull(Schedule.windDownBedtime(plan,midnight,start.plusDays(21).atTime(23,45)))
        val weekend=Rules(bed=120,weekends=true,weekendBed=0)
        assertEquals("法定假期 · 休息日安排",weekend.bedtimeSource(start))
        assertEquals(start.plusDays(1).atStartOfDay(),Schedule.windDownBedtime(plan,weekend,start.atTime(23,45)))
        assertNull(Schedule.windDownBedtime(plan,weekend,start.minusDays(1).atTime(23,45)))
        val special=weekend.copy(exceptions=mapOf(start.toString() to DaySchedule(90,420)))
        assertEquals("特殊日期安排",special.bedtimeSource(start))
        assertEquals(start.plusDays(1).atTime(1,30),Schedule.windDownBedtime(plan,special,start.plusDays(1).atTime(1,15)))
    }
}
