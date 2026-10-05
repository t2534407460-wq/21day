package com.twentyone.rhythm

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class HabitTest {
    private val start=LocalDate.of(2026,10,5)
    private fun habit(mode:HabitMode=HabitMode.AT_LEAST,target:Int=20,days:Set<Int> = (1..7).toSet())=Habit(name="阅读",start=start,mode=mode,rules=listOf(HabitRule(start,days,target)))
    @Test fun cycleAndSelectedWeekdaysAreBounded() {
        val h=habit(days=setOf(1,3,5));assertTrue(h.valid());assertEquals(9,h.dates().count { h.scheduled(it) })
        assertFalse(h.scheduled(start.minusDays(1)));assertTrue(h.scheduled(start));assertFalse(h.scheduled(start.plusDays(1)))
        assertFalse(h.scheduled(start.plusDays(21)));assertEquals(21,h.day(h.end))
    }
    @Test fun unrecordedIsNotZeroOrReachedForAbstinence() {
        val h=habit(HabitMode.AT_MOST,0)
        val empty=HabitAnalysis.stats(h,emptyList(),start.plusDays(2));assertEquals(0,empty.reached);assertNull(empty.average);assertEquals(2,empty.missed)
        val s=HabitAnalysis.stats(h,listOf(HabitEntry(h.id,start,0),HabitEntry(h.id,start.plusDays(1),3)),start.plusDays(2))
        assertEquals(1,s.reached);assertEquals(2,s.recorded);assertEquals(1.5,s.average!!,0.001);assertEquals(0,s.missed)
    }
    @Test fun zeroReadingAndExplicitIncompleteAreValidRecordsNotMissing() {
        val h=habit();val s=HabitAnalysis.stats(h,listOf(HabitEntry(h.id,start,0)),start)
        assertEquals(1,s.recorded);assertEquals(0,s.reached);assertEquals(0.0,s.average!!,0.001)
        val check=habit(HabitMode.CHECK,1);assertFalse(check.reached(start,0));assertTrue(check.reached(start,1))
    }
    @Test fun revisedTargetsAndFrequencyDoNotRewriteEarlierDates() {
        val h=habit().copy(rules=listOf(HabitRule(start,target=20),HabitRule(start.plusDays(7),days=setOf(1),target=10)))
        assertTrue(h.valid());assertEquals(20,h.rule(start).target);assertEquals(10,h.rule(start.plusDays(7)).target)
        val entries=listOf(HabitEntry(h.id,start,15),HabitEntry(h.id,start.plusDays(7),15))
        assertEquals(1,HabitAnalysis.stats(h,entries,start.plusDays(8)).reached)
        assertFalse(h.scheduled(start.plusDays(8)))
    }
    @Test fun reminderSkipsRecordedSentRestArchivedAndEnded() {
        val h=habit(days=setOf(1,3,5));val first=start.atTime(21,0)
        assertEquals(first,HabitAnalysis.nextReminder(listOf(h),emptyList(),emptySet(),start.atTime(20,0)))
        val expected=start.plusDays(2).atTime(21,0)
        assertEquals(expected,HabitAnalysis.nextReminder(listOf(h),listOf(HabitEntry(h.id,start,0)),emptySet(),start.atTime(20,0)))
        assertEquals(expected,HabitAnalysis.nextReminder(listOf(h),emptyList(),setOf(HabitAnalysis.key(h.id,start)),start.atTime(20,0)))
        assertNull(HabitAnalysis.nextReminder(listOf(h.copy(archivedOn=start)),emptyList(),emptySet(),start.atTime(20,0)))
        assertNull(HabitAnalysis.nextReminder(listOf(h),emptyList(),emptySet(),h.end.plusDays(1).atStartOfDay()))
    }
    @Test fun archiveStopsCountingSubsequentDates() {
        val h=habit().copy(archivedOn=start.plusDays(2));assertEquals(3,HabitAnalysis.stats(h,emptyList(),start.plusDays(8)).scheduled)
    }
    @Test fun invalidRulesAndDuplicateRevisionsAreRejected() {
        val h=habit();assertFalse(h.copy(rules=listOf(HabitRule(start,emptySet()))).valid())
        assertFalse(h.copy(rules=listOf(HabitRule(start,target=-1))).valid());assertFalse(habit(HabitMode.AT_LEAST,0).valid())
        assertFalse(h.copy(rules=h.rules+h.rules).valid());assertFalse(h.copy(id="invalid").valid())
        assertFalse(h.copy(rules=listOf(HabitRule(start,reminder=1440))).valid())
    }
}
