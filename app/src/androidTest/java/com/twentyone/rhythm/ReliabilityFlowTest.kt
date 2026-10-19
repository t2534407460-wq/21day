package com.twentyone.rhythm

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReliabilityFlowTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val context=instrument.targetContext
    private val device=UiDevice.getInstance(instrument)
    private val store by lazy { Store(context) }
    @Before fun setup() {
        Assume.assumeTrue(android.os.Build.HARDWARE in listOf("ranchu","goldfish"))
        store.prefs.edit().clear().commit();store.createPlan(Plan());device.wakeUp()
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("今天")),15000))
    }
    @After fun cleanup() {
        instrument.runOnMainSync { Alarms.stop(context,"可靠性自动测试结束");Alarms.cancel(context,Alarms.WAKE);Alarms.cancel(context,Alarms.PREP) }
        store.prefs.edit().clear().commit();device.pressHome()
    }
    private fun openAlarm() {
        instrument.runOnMainSync { Alarms.start(context) }
        context.startActivity(Intent(context,WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("扫描洗漱区的起床码")),10000))
    }
    private fun tapStop(text:String) {
        var button=device.wait(Until.findObject(By.text(text)),2000)
        if(button==null) {
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text))
            button=device.wait(Until.findObject(By.text(text)),3000)
        }
        assertNotNull(text,button);button.click()
    }
    @Test fun hiddenTaskSurvivesHomeAndReopen() {
        device.pressHome();device.waitForIdle()
        val tasks=context.getSystemService(ActivityManager::class.java).appTasks
        assertTrue(tasks.isNotEmpty())
        assertTrue("App task must be excluded from Recents",tasks.all{it.taskInfo.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0})
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("今天")),5000))
        assertNotNull(store.plan)
        store.hideFromRecents=false;RecentTasks.apply(context)
        assertTrue(tasks.all{it.taskInfo.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS == 0})
        device.pressHome()
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("今天")),5000))
        assertTrue(context.getSystemService(ActivityManager::class.java).appTasks.all{it.taskInfo.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS == 0})
        store.hideFromRecents=true;RecentTasks.apply(context)
        openAlarm()
        assertTrue(context.getSystemService(ActivityManager::class.java).appTasks.all{it.taskInfo.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0})
    }
    @Test fun formalAlarmCannotStopWithOneTap() {
        openAlarm()
        val actionTitles=context.getSystemService(NotificationManager::class.java).activeNotifications.flatMap{it.notification.actions?.map{a->a.title.toString()}.orEmpty()}
        assertFalse("Formal notification must not contain an immediate stop",actionTitles.contains("紧急停止"))
        tapStop("紧急停止本次验证");device.waitForIdle()
        assertTrue("One tap must keep verification active",store.wake.active)
        assertTrue(device.hasObject(By.text("邮箱紧急验证")))
        assertEquals(0L,store.log(store.wake.day).verifiedAt)
        assertFalse(device.hasObject(By.desc("持续长按 5 秒确认退出")))
        device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"emergency-email-0.12.5.png"))
        tapStop("返回起床验证");assertTrue(store.wake.ringing)
    }
    @Test fun legacyNotificationActionCannotStopFormalAlarm() {
        openAlarm()
        context.startService(Intent(context,WakeAlarmService::class.java).setAction("stop"))
        device.waitForIdle()
        assertTrue("An old notification action must not bypass confirmation",store.wake.active)
    }
    @Test fun alarmRetryDoesNotDismissEmergencyConfirmation() {
        openAlarm();tapStop("紧急停止本次验证")
        assertTrue(device.wait(Until.hasObject(By.text("邮箱紧急验证")),5000))
        val due=System.currentTimeMillis()+2000
        store.wake=store.wake.copy(dueAt=due)
        Alarms.set(context,Alarms.CHECK,due)
        val deadline=SystemClock.elapsedRealtime()+8000
        while(store.wake.attempts==1 && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100)
        assertEquals(2,store.wake.attempts)
        assertTrue(store.wake.active)
        assertTrue(device.hasObject(By.text("邮箱紧急验证")))
    }
    @Test fun formalAlarmKeepsForegroundBeyondOneMinuteAndThreeAttempts() {
        openAlarm()
        instrument.runOnMainSync { Alarms.begin(context) }
        assertEquals(WakePhase.RINGING,store.wake.phase)
        // The retry is deliberately far away: the service must remain audible on its own.
        store.wake=store.wake.copy(dueAt=System.currentTimeMillis()+180_000)
        Alarms.set(context,Alarms.CHECK,store.wake.dueAt)
        device.pressHome();SystemClock.sleep(66_000)
        assertTrue(store.wake.ringing)
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id==21 })
        repeat(5) {
            store.wake=store.wake.copy(dueAt=System.currentTimeMillis()-1)
            instrument.runOnMainSync { AlarmReceiver().onReceive(context,Intent(Alarms.CHECK)) }
        }
        assertTrue(store.wake.ringing);assertTrue(store.wake.attempts>3)
        assertEquals(0L,store.log(store.wake.day).verifiedAt)
    }
    @Test fun trialAlarmCanStillEndImmediately() {
        instrument.runOnMainSync { Alarms.start(context,true) }
        context.startActivity(Intent(context,WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("结束试用")),5000))
        tapStop("结束试用")
        assertTrue(device.wait(Until.hasObject(By.text("本次验证已停止。")),5000))
        assertEquals(WakePhase.STOPPED,store.wake.phase)
        assertTrue(store.events().isEmpty());assertTrue(store.logs().isEmpty())
    }
}
