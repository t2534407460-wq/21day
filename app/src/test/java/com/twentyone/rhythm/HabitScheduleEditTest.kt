package com.twentyone.rhythm

import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate

class HabitScheduleEditTest {
    private val start=LocalDate.of(2026,10,5)
    private val today=start.plusDays(2)
    private val old=Habit(name="运动",start=start,mode=HabitMode.AT_LEAST,unit="分钟",rules=listOf(HabitRule(start,setOf(1,3,5),30,360)))
    @Test fun weekdaysAndTimeChangeTogetherTodayWithoutRewritingPastTargets() {
        val changed=old.revised("运动",(1..7).toSet(),20,480,today)
        assertTrue(changed.valid())
        assertEquals(old.rule(start),changed.rule(start))
        assertEquals((1..7).toSet(),changed.rule(today).days);assertEquals(480,changed.rule(today).reminder)
        assertEquals(30,changed.rule(today).target);assertEquals(20,changed.rule(today.plusDays(1)).target)
        assertEquals(today.atTime(8,0),HabitAnalysis.nextReminder(listOf(changed),emptyList(),emptySet(),today.atTime(5,0)))
        assertEquals(today.plusDays(1).atTime(8,0),HabitAnalysis.nextReminder(listOf(changed),emptyList(),emptySet(),today.atTime(9,0)))
    }
    @Test fun repeatedEditsReplacePendingSettingsAndDoNotRevertTomorrow() {
        val pending=old.copy(rules=old.rules+HabitRule(today.plusDays(1),(1..7).toSet(),20,480))
        val changed=pending.revised("运动",setOf(2,4),10,600,today)
        assertTrue(changed.valid());assertFalse(changed.scheduled(today))
        assertEquals(setOf(2,4),changed.rule(today.plusDays(1)).days)
        assertEquals(600,changed.rule(today.plusDays(1)).reminder)
        assertEquals(10,changed.rule(today.plusDays(1)).target)
    }
    @Test fun recordedOrRunningTodayRemainsScheduledButFutureUsesNewDays() {
        val changed=old.revised("运动",setOf(2,4),20,480,today,true)
        assertTrue(changed.scheduled(today));assertEquals(30,changed.rule(today).target)
        assertEquals(setOf(2,4),changed.rule(today.plusDays(1)).days)
        assertFalse(changed.scheduled(today.plusDays(7)))
    }
    @Test fun futurePlanKeepsItsStartAndDisablingReminderCancelsAllFutureReminders() {
        val changed=old.revised("运动",(1..7).toSet(),20,null,start.minusDays(2))
        assertTrue(changed.valid());assertEquals(1,changed.rules.size);assertEquals(start,changed.rules.single().from)
        assertEquals(20,changed.rule(start).target)
        assertNull(HabitAnalysis.nextReminder(listOf(changed),emptyList(),emptySet(),start.minusDays(1).atStartOfDay()))
    }
}
