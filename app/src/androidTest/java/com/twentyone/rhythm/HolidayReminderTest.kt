package com.twentyone.rhythm

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.time.*

class HolidayReminderTest {
    private val c=InstrumentationRegistry.getInstrumentation().targetContext
    private val s=Store(c);private val manager=c.getSystemService(NotificationManager::class.java)
    private val day=LocalDate.parse("2026-10-03")
    @Before fun setup() { s.prefs.edit().clear().commit();s.createPlan(Plan(day));s.rules=Rules(bed=0,wake=510,weekends=true,weekendBed=120,weekendWake=600);Notifications.create(c);manager.cancelAll() }
    @After fun cleanup() { listOf(Alarms.PREP,Alarms.BEDTIME_LOCK,Alarms.WAKE).forEach { Alarms.cancel(c,it) };manager.cancelAll();s.prefs.edit().clear().commit() }
    @Test fun holidayPrepAt145AndDueAt2AreIndependentAndNotRepeated() {
        BedtimeReminders.preparation(c,day.plusDays(1).atTime(1,44));assertTrue(manager.activeNotifications.isEmpty())
        BedtimeReminders.preparation(c,day.plusDays(1).atTime(1,45));assertTrue(manager.activeNotifications.any { it.id==22 })
        BedtimeReminders.due(c,day.plusDays(1).atTime(1,59));assertFalse(manager.activeNotifications.any { it.id==26 })
        BedtimeReminders.due(c,day.plusDays(1).atTime(2,0));assertTrue(manager.activeNotifications.any { it.id==26 })
        BedtimeReminders.due(c,day.plusDays(1).atTime(2,5));assertEquals(1,s.events().count { it.kind=="睡前到点提醒" })
    }
    @Test fun beforeMidnightCompletionSuppressesBothRemindersAfterMidnight() {
        s.saveLog(DayLog(day.toString(),sleepiness="很困了",bedMood="平静",bedReason="无"));s.completeBedtime(day.toString(),day.atTime(23,58))
        BedtimeReminders.preparation(c,day.plusDays(1).atTime(1,45));BedtimeReminders.due(c,day.plusDays(1).atTime(2,5))
        assertTrue(manager.activeNotifications.isEmpty());assertTrue(s.events().isEmpty())
    }
    @Test fun lateStartCatchesUpOnlyCurrentNightAndNotAfterWake() {
        BedtimeReminders.due(c,day.plusDays(1).atTime(2,5));assertEquals(1,s.events().size)
        manager.cancelAll();BedtimeReminders.due(c,day.plusDays(1).atTime(10,0));assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun restMigrationPreservesWorkdaysHistoryAndPendingActivation() {
        s.rules=Rules(bed=0,wake=510,blocked=setOf("video"),exceptions=mapOf("2026-10-08" to DaySchedule(1380,420)))
        val log=DayLog(day.toString(),note="keep",verifiedAt=123);s.saveLog(log)
        val at=day.plusDays(1).atTime(10,0).epoch()
        s.prefs.edit().putString("pending_rules",Store.encodeRules(s.rules.copy(bed=30)).toString()).putLong("pending_at",at).commit()
        s.migrateRestSchedule();assertEquals(0,s.rules.bed);assertEquals(510,s.rules.wake);assertEquals(120,s.rules.weekendBed);assertEquals(600,s.rules.weekendWake)
        assertTrue(s.rules.weekends);assertEquals(at,s.pendingAt);assertEquals(30,s.editableRules.bed);assertEquals(1,s.rules.exceptions.size);assertEquals(log,s.log(day.toString()))
        s.rules=s.rules.copy(weekends=false,weekendBed=60);s.migrateRestSchedule();assertFalse(s.rules.weekends);assertEquals(60,s.rules.weekendBed)
    }
}
