package com.twentyone.rhythm

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

/** Run separately, after activating BedtimeAdminReceiver on the disposable emulator only. */
@RunWith(AndroidJUnit4::class)
class BedtimeLockTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val c=instrument.targetContext
    private val device=UiDevice.getInstance(instrument)
    private val s=Store(c)
    private lateinit var day:LocalDate
    @Before fun setup() {
        assertTrue("Activate the lock admin on the disposable emulator",BedtimeLock.available(c))
        assertTrue(Alarms.exactAllowed(c));s.prefs.edit().clear().commit()
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");device.pressHome()
        val now=LocalDateTime.now();val bed=now.minusMinutes(61)
        day=BedtimeReminders.evening(bed);s.createPlan(Plan(day.minusDays(20)))
        s.rules=Rules(bed=bed.hour*60+bed.minute,wake=(now.hour*60+now.minute+120)%1440)
    }
    @After fun cleanup() {
        Alarms.cancel(c,Alarms.BEDTIME_LOCK);Alarms.cancel(c,Alarms.WAKE);Alarms.cancel(c,Alarms.PREP)
        s.prefs.edit().clear().commit();device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");device.pressHome()
    }
    @Test fun overdueAlarmLocksTheRealScreenOnceAndReschedulingDoesNotLockAgain() {
        // Use a boot broadcast path to exercise recovery, rather than directly calling lockNow.
        RescheduleReceiver().onReceive(c,Intent(Intent.ACTION_BOOT_COMPLETED))
        val limit=SystemClock.elapsedRealtime()+15000
        while((device.isScreenOn || s.events().none { it.kind=="睡前超时锁屏" }) && SystemClock.elapsedRealtime()<limit) SystemClock.sleep(100)
        assertFalse("The system screen should be off after the overdue alarm",device.isScreenOn)
        assertEquals(1,s.events().count { it.kind=="睡前超时锁屏" })
        assertTrue(s.prefs.getStringSet("bedtime_locked_nights",emptySet())!!.contains(day.toString()))
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")
        BedtimeLock.enforce(c);BedtimeLock.schedule(c);SystemClock.sleep(2000)
        assertTrue(device.isScreenOn);assertEquals(1,s.events().count { it.kind=="睡前超时锁屏" })
        assertEquals(0L,s.log(day.toString()).bedtimeCheckedAt)
    }
    @Test fun completingBedtimeCancelsAnArmedLockAndStaleBroadcastIsHarmless() {
        val now=LocalDateTime.now()
        s.saveLog(DayLog(day.toString(),sleepiness="很困了",bedMood="平静",bedReason="无"))
        Alarms.set(c,Alarms.BEDTIME_LOCK,System.currentTimeMillis()+2000)
        s.completeBedtime(day.toString(),now);BedtimeLock.schedule(c)
        AlarmReceiver().onReceive(c,Intent().setAction(Alarms.BEDTIME_LOCK))
        SystemClock.sleep(3000)
        assertTrue(device.isScreenOn);assertTrue(s.events().none { it.kind=="睡前超时锁屏" })
        assertTrue(BedtimeSchedule.checked(s.log(day.toString())))
    }
    @Test fun partialAnswersAndOldActionCompletionDoNotAvoidLock() {
        s.saveLog(DayLog(day.toString(),status="完成",sleepiness="很困了",bedMood="平静",bedReason="无"))
        BedtimeLock.enforce(c)
        val limit=SystemClock.elapsedRealtime()+4000
        while(device.isScreenOn && SystemClock.elapsedRealtime()<limit) SystemClock.sleep(100)
        assertFalse(device.isScreenOn);assertEquals(0L,s.log(day.toString()).bedtimeCheckedAt)
    }
    @Test fun beforeBedtimeAndAfterTheMorningNeverLock() {
        val now=LocalDateTime.now();val bedtime=now.plusMinutes(1)
        s.createPlan(Plan(BedtimeReminders.evening(bedtime)));s.rules=Rules(bed=bedtime.hour*60+bedtime.minute)
        BedtimeLock.enforce(c);assertTrue(device.isScreenOn)
        s.createPlan(Plan(now.toLocalDate().minusDays(22)))
        BedtimeLock.enforce(c);assertTrue(device.isScreenOn);assertTrue(s.events().isEmpty())
    }
    @Test fun scheduledBedtimeNotifiesAndLocksAtTheMinuteWithoutAnHourDelay() {
        val now=LocalDateTime.now();val due=now.plusMinutes(if(now.second>52) 2 else 1).withSecond(0).withNano(0)
        val evening=BedtimeReminders.evening(due)
        s.createPlan(Plan(evening));s.rules=Rules(bed=due.hour*60+due.minute,wake=(due.hour*60+due.minute+120)%1440)
        Alarms.schedule(c);assertTrue(device.isScreenOn)
        val until=due.epoch()+10000
        while(device.isScreenOn && System.currentTimeMillis()<until) SystemClock.sleep(100)
        assertFalse("到点即锁屏，无一小时宽限",device.isScreenOn)
        assertTrue(s.prefs.getStringSet("bedtime_locked_nights",emptySet())!!.contains(evening.toString()))
        assertTrue(c.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.id==26 })
        val locked=s.events().single { it.kind=="睡前超时锁屏" }
        assertTrue("不能早锁",locked.time>=due.epoch());assertTrue("实际定时触发",locked.time-due.epoch()<10000)
    }
    companion object {
        @JvmStatic @AfterClass fun releaseTestAdmin() {
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            c.getSystemService(android.app.admin.DevicePolicyManager::class.java).removeActiveAdmin(android.content.ComponentName(c,BedtimeAdminReceiver::class.java))
        }
    }
}
