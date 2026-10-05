package com.twentyone.rhythm

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ModelTest {
    private val plan = Plan(LocalDate.of(2026,10,3))
    private val rules = Rules(blocked=setOf("video"), allowed=setOf("phone"))
    @Test fun overnightBoundaries() {
        assertNull(Schedule.night(plan,rules,LocalDateTime.parse("2026-10-03T22:59:59")))
        assertNotNull(Schedule.night(plan,rules,LocalDateTime.parse("2026-10-03T23:00:00")))
        assertEquals("2026-10-03", Schedule.night(plan,rules,LocalDateTime.parse("2026-10-04T06:59:59"))!!.date.toString())
        assertNull(Schedule.night(plan,rules,LocalDateTime.parse("2026-10-04T07:00:00")))
    }
    @Test fun planDoesNotStartYesterdayAndEndsAfterLastNight() {
        assertNull(Schedule.night(plan,rules,LocalDateTime.parse("2026-10-03T01:00:00")))
        assertNotNull(Schedule.night(plan,rules,LocalDateTime.parse("2026-10-24T06:00:00")))
        assertNull(Schedule.night(plan,rules,LocalDateTime.parse("2026-10-24T23:00:00")))
        assertNull(Schedule.nextWake(plan,rules,LocalDateTime.parse("2026-10-24T08:00:00")))
    }
    @Test fun weekendUsesWakeDaysAndNightStarts() {
        val r=rules.copy(weekends=true,weekendBed=30,weekendWake=540)
        val n=Schedule.night(plan,r,LocalDateTime.parse("2026-10-04T08:00:00"))!!
        assertEquals(LocalDateTime.parse("2026-10-04T09:00:00"), n.end)
    }
    @Test fun passExpiresAndDoesNotUnlockOtherApps() {
        val n=Schedule.night(plan,rules,LocalDateTime.parse("2026-10-03T23:30:00"))
        assertFalse(Schedule.blocks("video",rules,n,"video",1000,999))
        assertTrue(Schedule.blocks("video",rules,n,"video",1000,1000))
        assertTrue(Schedule.blocks("video",rules,n,"other",1000,999))
        assertTrue(Schedule.blocks("video",rules,n,"",0,999))
        assertFalse(Schedule.blocks("video",rules.copy(allowed=setOf("video")),n,"",0,999))
    }
    @Test fun secondCheckCannotBeSkippedByRepeatedScan() {
        val first=WakeSession(phase=WakePhase.RINGING,attempts=1).verify(1000,rules)
        assertEquals(WakePhase.SECOND_WAIT,first.phase)
        assertEquals(first,first.verify(2000,rules))
        assertEquals(WakePhase.COMPLETE,first.verify(301000,rules).phase)
    }
    @Test fun oneCheckModeAndStoppedSession() {
        assertEquals(WakePhase.COMPLETE,WakeSession(phase=WakePhase.WALK).verify(1000,rules.copy(secondCheck=false)).phase)
        assertEquals(WakePhase.STOPPED,WakeSession(phase=WakePhase.STOPPED).verify(1000,rules).phase)
    }
    @Test fun remindersHaveAnUpperLimitAndDoNotRestartFinishedSession() {
        var w=WakeSession(phase=WakePhase.RINGING,attempts=1)
        repeat(3) { w=w.retry(1000L+it) }
        assertEquals(WakePhase.TIMED_OUT,w.phase)
        assertEquals(w,w.retry(9000))
    }
    @Test fun invalidTimesAreRejected() {
        assertNull(parseTime("25:00")); assertNull(parseTime("abc")); assertEquals(420,parseTime("07:00"))
        assertFalse(rules.copy(bed=420).valid()); assertFalse(rules.copy(passMinutes=0).valid())
        assertEquals(21,plan.day(plan.start.plusDays(20)))
    }
    @Test fun quietWalkCannotBeExtendedByRepeatedBegin() {
        val first=WakeSession(phase=WakePhase.SECOND_READY,firstAt=100).begin(1000)
        assertEquals(WakePhase.WALK,first.phase)
        assertEquals(first,first.begin(3000))
    }
    @Test fun rulesRefreshPreservesWakeAtDeliveryBoundaryOnly() {
        assertTrue(Schedule.preserveDueWake(1000,1000,false))
        assertTrue(Schedule.preserveDueWake(1000,1100,false))
        assertFalse(Schedule.preserveDueWake(1000,1100,true))
        assertFalse(Schedule.preserveDueWake(1000,200000,false))
        assertFalse(Schedule.preserveDueWake(1000,999,false))
        assertFalse(Schedule.preserveDueWake(0,1000,false))
    }
}
