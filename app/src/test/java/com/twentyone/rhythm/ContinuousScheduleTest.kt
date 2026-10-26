package com.twentyone.rhythm

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ContinuousScheduleTest {
    private val start=LocalDate.parse("2026-10-03")
    private val plan=Plan(start)
    private val rules=Rules(bed=0,wake=540)
    @Test fun workdayWakeContinuesAfterFirstCycleAndAcrossYears() {
        assertEquals(LocalDateTime.parse("2026-10-26T09:00"),Schedule.nextWake(plan,rules,LocalDateTime.parse("2026-10-26T08:00")))
        assertEquals(LocalDateTime.parse("2027-10-04T09:00"),Schedule.nextWake(plan,rules,LocalDateTime.parse("2027-10-02T10:00")))
    }
    @Test fun bedtimeRecordingRestrictionAndLockContinueAfterFirstCycle() {
        val now=LocalDateTime.parse("2026-10-27T00:01")
        assertEquals(LocalDate.parse("2026-10-26"),Schedule.night(plan,rules,now)?.date)
        assertEquals(LocalDate.parse("2026-10-26"),BedtimeSchedule.recordDay(plan,rules,now))
        assertEquals(LocalDate.parse("2026-10-26"),BedtimeSchedule.nextLockDay(plan,rules,emptyList(),emptySet(),now))
        assertEquals(LocalDateTime.parse("2026-10-27T23:45"),Schedule.nextWindDown(plan,rules,now))
    }
    @Test fun habitRemainsScheduledAndRemindsAfterFirstCycle() {
        val h=Habit(name="阅读",mode=HabitMode.AT_LEAST,start=start,rules=listOf(HabitRule(start,days=setOf(1,3,5),target=20,reminder=540)))
        val day=LocalDate.parse("2026-10-26")
        assertTrue(h.scheduled(day));assertFalse(h.scheduled(day.plusDays(1)))
        assertEquals(day.atTime(9,0),HabitAnalysis.nextReminder(listOf(h),emptyList(),emptySet(),day.atTime(8,0)))
        assertNull(HabitAnalysis.nextReminder(listOf(h.copy(archivedOn=day)),emptyList(),emptySet(),day.atTime(8,0)))
    }
    @Test fun laterHabitRulesRemainValidAndHistoricalGoalsArePreserved() {
        val h=Habit(name="阅读",mode=HabitMode.AT_LEAST,start=start,rules=listOf(HabitRule(start,target=20)))
        val changed=h.revised("阅读",setOf(1,2,3,4,5,6,7),30,600,LocalDate.parse("2026-10-26"))
        assertTrue(changed.valid())
        assertEquals(20,changed.rule(LocalDate.parse("2026-10-26")).target)
        assertEquals(30,changed.rule(LocalDate.parse("2026-10-27")).target)
    }
    @Test fun everyCompletedCycleHasItsOwnHabitAndSleepSummary() {
        val origin=LocalDate.now().minusDays(65)
        val h=Habit(name="阅读",mode=HabitMode.AT_LEAST,start=origin,rules=listOf(HabitRule(origin,target=20)))
        val specs=Reviews.specs(listOf(h),Plan(origin),origin,true,true)
        val cycles=specs.filter { it.kind=="cycle" }
        assertTrue(cycles.count { it.subject==h.id }>=3)
        assertTrue(cycles.count { it.subject=="sleep:$origin" }>=3)
        assertEquals(1,cycles.count { it.id=="cycle:${h.id}" })
        assertEquals(cycles.size,cycles.map { it.id }.distinct().size)
    }
    @Test fun cycleStatsUseOnlySelectedPeriodAndRetainPastPeriods() {
        val h=Habit(name="阅读",mode=HabitMode.AT_LEAST,start=start,rules=listOf(HabitRule(start,target=20)))
        val entries=listOf(HabitEntry(h.id,start,25),HabitEntry(h.id,start.plusDays(21),30))
        val today=start.plusDays(23)
        assertEquals(1,HabitAnalysis.stats(h,entries,today).recorded)
        assertEquals(3,HabitAnalysis.stats(h,entries,today).scheduled)
        assertEquals(1,HabitAnalysis.stats(h,entries,today,start,start.plusDays(20)).recorded)
        val specs=Reviews.specs(listOf(h.copy(archivedOn=today)),null,start,false,true,today)
        assertEquals(1,specs.size);assertEquals("cycle:${h.id}",specs.single().id)
    }
    @Test fun sleepSummaryIncludesLastMorningWithoutSendingPrivateNotes() {
        val spec=ReviewSpec("sleep-cycle","cycle","sleep:$start","作息",start,start.plusDays(20))
        val last=spec.to
        val facts=Reviews.sleepFacts(listOf(DayLog(last.toString(),sleepiness="很困了",bedMood="平静",bedReason="无",bedtimeCheckedAt=1,note="private"),DayLog(last.plusDays(1).toString(),verifiedAt=2)),spec)
        assertTrue(facts.contains("$last 晚 → ${last.plusDays(1)} 早：已完成"));assertFalse(facts.contains("private"))
        assertEquals(last.plusDays(2).atStartOfDay(),spec.due)
    }
    @Test fun schedulesWithNoValidBedtimeWindowReturnWithoutSearchingForever() {
        assertNull(BedtimeSchedule.nextLockDay(plan,Rules(bed=600,wake=540),emptyList(),emptySet(),LocalDateTime.parse("2026-10-26T08:00")))
        val future=LocalDate.parse("2027-02-01")
        val special=Rules(bed=600,wake=540,exceptions=mapOf(future.toString() to DaySchedule(1380,540)))
        assertEquals(future,BedtimeSchedule.nextLockDay(plan,special,emptyList(),emptySet(),LocalDateTime.parse("2026-10-26T08:00")))
    }
}
