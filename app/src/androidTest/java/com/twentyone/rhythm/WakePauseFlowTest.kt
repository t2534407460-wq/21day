package com.twentyone.rhythm

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Run on a disposable emulator set to a workday morning before 08:30. */
class WakePauseFlowTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val c=instrument.targetContext
    private val s=Store(c)
    private val today=LocalDate.now()
    private val device=UiDevice.getInstance(instrument)
    private val notifications=c.getSystemService(NotificationManager::class.java)

    @Before fun setup() {
        Assume.assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        Assume.assumeTrue(!RestCalendar.isRest(today) && LocalDateTime.now().isBefore(today.atTime(8,30)))
        assertTrue(Alarms.exactAllowed(c))
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")
        s.prefs.edit().clear().commit()
        s.createPlan(Plan(today.minusDays(1)))
        s.rules=Rules(wake=510,weekends=true,weekendBed=120,weekendWake=600)
        Notifications.create(c);notifications.cancelAll()
    }

    @After fun cleanup() {
        if(Build.HARDWARE !in listOf("ranchu","goldfish")) return
        instrument.runOnMainSync {
            Alarms.stop(c,"测试结束")
            listOf(Alarms.WAKE,Alarms.CHECK,Alarms.PREP,Alarms.BEDTIME_LOCK).forEach { Alarms.cancel(c,it) }
        }
        notifications.cancelAll();s.prefs.edit().clear().commit();device.wakeUp();device.pressHome()
    }

    @Test fun settingsSavesPauseAndUndoRestoresTheOriginalAlarm() {
        c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        tap("设置")
        tap("作息计划")
        tap("暂停一天起床闹钟")
        val pictures=File(c.getExternalFilesDir(null),"wake-pause-qa").apply { mkdirs() }
        tap("暂停日期")
        tap("确定日期")
        assertTrue(device.wait(Until.hasObject(By.text("确认暂停")),5000))
        SystemClock.sleep(450) // Let the date picker finish dismissing before taking a screenshot.
        device.takeScreenshot(File(pictures,"confirm.png"))
        tap("确认暂停")
        val status="${today.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))}已暂停"
        assertTrue(device.wait(Until.hasObject(By.text(status)),5000))
        assertEquals(today,Store(c).wakePauseDate)
        val tomorrowWake=Schedule.nextWake(s.plan!!,s.rules,today.plusDays(1).atStartOfDay())!!
        assertEquals(tomorrowWake.epoch(),s.prefs.getLong("scheduled_wake",0))
        assertEquals("runtime",SyncCodec.scope("rhythm",WAKE_PAUSE_DATE))
        SystemClock.sleep(450) // Capture the saved state after the dialog transition.
        device.takeScreenshot(File(pictures,"scheduled.png"))

        device.pressHome()
        c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        tap("设置")
        tap("作息计划")
        assertTrue(device.wait(Until.hasObject(By.text(status)),5000))
        tap("暂停一天起床闹钟")
        tap("取消已安排的暂停")
        assertTrue(device.wait(Until.gone(By.text(status)),5000))
        assertNull(s.wakePauseDate)
        assertEquals(today.atTime(8,30).epoch(),s.prefs.getLong("scheduled_wake",0))
    }

    @Test fun queuedAlarmOnPausedWorkdayDoesNotRingOrCreateARecord() {
        // Start the app once so the system can deliver alarms after a clean app install.
        c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("今天")),8000))
        // Seed the saved value directly so this delivery test can also run against the previous APK.
        s.prefs.edit().putString("wake_pause_date",today.toString()).apply()
        val original=DayLog(today.minusDays(1).toString(),rise="08:30",verifiedAt=123)
        s.saveLog(original)
        device.pressHome();device.sleep()
        val oldAlarm=System.currentTimeMillis()+1500
        Alarms.set(c,Alarms.WAKE,oldAlarm)
        val deadline=SystemClock.elapsedRealtime()+20000
        while(s.prefs.getLong("scheduled_wake",0) in listOf(0L,oldAlarm) && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50)
        assertNotEquals("The old AlarmManager callback must be delivered",oldAlarm,s.prefs.getLong("scheduled_wake",0))
        assertFalse("The paused day must not start a formal wake session",s.wake.active)
        assertFalse(device.isScreenOn)
        assertFalse(notifications.activeNotifications.any { it.id==21 })
        assertEquals(listOf(original),s.logs())
        val next=generateSequence(today.plusDays(1)) { it.plusDays(1) }.first { !RestCalendar.isRest(it) }
        assertEquals(next.atTime(8,30).epoch(),s.prefs.getLong("scheduled_wake",0))
    }

    @Test fun startedVerificationCannotBePausedAndEarlierVerificationSurvives() {
        val waiting=WakeSession(today.toString(),WakePhase.SECOND_WAIT,System.currentTimeMillis()+300_000,1,firstAt=123)
        instrument.runOnMainSync {
            s.wake=waiting
            assertFalse(Alarms.setWakePause(c,today))
            assertNull(s.wakePauseDate)
            val earlier=waiting.copy(day=today.minusDays(1).toString())
            s.wake=earlier
            assertTrue(Alarms.setWakePause(c,today))
            assertEquals(earlier,s.wake)
            Alarms.start(c)
            assertEquals(earlier,s.wake)
        }
    }

    private fun tap(text:String) {
        assertTrue("Missing: $text",device.wait(Until.hasObject(By.text(text)),8000))
        device.findObject(By.text(text)).click()
    }
}
