package com.twentyone.rhythm

import android.app.NotificationManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class BedtimeReminderTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val c=instrument.targetContext
    private val s=Store(c)
    private val manager=c.getSystemService(NotificationManager::class.java)
    @Before fun setup() {
        s.prefs.edit().clear().commit();s.createPlan(Plan(LocalDate.now().minusDays(1)))
        Notifications.create(c);manager.cancelAll()
    }
    @After fun cleanup() {
        instrument.runOnMainSync {
            Alarms.stop(c,"测试结束")
            listOf(Alarms.WAKE,Alarms.PREP,Alarms.BEDTIME_LOCK,Alarms.CHECK).forEach { Alarms.cancel(c,it) }
        }
        manager.cancelAll();s.prefs.edit().clear().commit()
    }
    private fun rulesAfter(minutes:Long):Rules {
        val bed=LocalDateTime.now().plusMinutes(minutes).toLocalTime()
        return Rules(bed=bed.hour*60+bed.minute,wake=(bed.hour*60+bed.minute+120)%1440)
    }
    @Test fun oldCallbackCannotNotifyBeforeTheNewReminderWindow() {
        s.rules=rulesAfter(15)
        assertFalse(s.saveRules(rulesAfter(75)))
        Alarms.schedule(c)
        AlarmReceiver().onReceive(c,Intent().setAction(Alarms.PREP))
        assertTrue("旧回调不能提前发出睡前提醒",manager.activeNotifications.none { it.id==22 })
    }
    @Test fun changingBedtimeRemovesTheAlreadyDisplayedOldReminder() {
        s.rules=rulesAfter(15)
        AlarmReceiver().onReceive(c,Intent().setAction(Alarms.PREP))
        assertTrue(manager.activeNotifications.any { it.id==22 })
        assertFalse(s.saveRules(rulesAfter(75)))
        Alarms.schedule(c)
        assertTrue("调整时间后不能保留过时的睡前通知",manager.activeNotifications.none { it.id==22 })
    }
    @Test fun duePendingRulesAreAppliedBeforeReminderDelivery() {
        s.rules=rulesAfter(15);s.wake=WakeSession(LocalDate.now().toString(),WakePhase.WALK)
        assertTrue(s.saveRules(rulesAfter(75)))
        s.prefs.edit().putLong("pending_at",System.currentTimeMillis()-1).commit()
        AlarmReceiver().onReceive(c,Intent().setAction(Alarms.PREP))
        assertEquals(0L,s.pendingAt);assertTrue(manager.activeNotifications.none { it.id==22 })
    }
    @Test fun reschedulingDoesNotRemoveOtherPlanNotifications() {
        Notifications.info(c,"起床尚未确认","保留其他消息")
        s.rules=rulesAfter(75);Alarms.schedule(c)
        assertTrue(manager.activeNotifications.any { it.id==22 })
    }
    @Test fun realAlarmUsesTheChangedBedtimeAndOldAlarmIsCancelled() {
        assertTrue(Alarms.exactAllowed(c))
        val now=LocalDateTime.now()
        val due=now.plusMinutes(if(now.second>50) 2 else 1).withSecond(0).withNano(0)
        val bedtime=due.plusMinutes(15)
        s.rules=rulesAfter(15);Alarms.set(c,Alarms.PREP,System.currentTimeMillis()+2000)
        assertFalse(s.saveRules(Rules(bed=bedtime.hour*60+bedtime.minute,wake=(bedtime.hour*60+bedtime.minute+120)%1440)))
        Alarms.schedule(c);Thread.sleep(3000)
        assertTrue("旧定时不应发送通知",manager.activeNotifications.none { it.id==22 })
        val until=due.epoch()+15000
        while(manager.activeNotifications.none { it.id==22 } && System.currentTimeMillis()<until) Thread.sleep(200)
        val notice=manager.activeNotifications.firstOrNull { it.id==22 };assertNotNull("新定时应通过 AlarmManager 真实触发",notice)
        assertEquals(bedtime.epoch(),notice!!.notification.extras.getLong(Notifications.BEDTIME_AT))
        assertTrue(notice.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains(timeText(s.rules.bed)))
    }
}
