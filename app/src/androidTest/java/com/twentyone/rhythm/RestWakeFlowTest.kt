package com.twentyone.rhythm

import android.app.NotificationManager
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import java.time.*

/** Run on a disposable emulator whose current date is a rest day, e.g. 2026-10-04. */
class RestWakeFlowTest {
    private val instrument=InstrumentationRegistry.getInstrumentation();private val c=instrument.targetContext
    private val s=Store(c);private val today=LocalDate.now();private val device=UiDevice.getInstance(instrument)
    private val notifications=c.getSystemService(NotificationManager::class.java)
    @Before fun setup() {
        Assume.assumeTrue("Rest-day device date required",RestCalendar.isRest(today))
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")
        s.prefs.edit().clear().commit();s.createPlan(Plan(today.minusDays(1)))
        s.rules=Rules(bed=0,wake=510,weekends=true,weekendBed=120,weekendWake=600)
        Notifications.create(c);notifications.cancelAll()
    }
    @After fun cleanup() {
        instrument.runOnMainSync { Alarms.stop(c,"测试结束");listOf(Alarms.WAKE,Alarms.CHECK,Alarms.PREP,Alarms.BEDTIME_LOCK).forEach { Alarms.cancel(c,it) } }
        notifications.cancelAll();s.prefs.edit().clear().commit();device.pressHome()
    }
    private fun seed(phase:WakePhase=WakePhase.RINGING) {
        s.wake=WakeSession(today.toString(),phase,System.currentTimeMillis()-1,1,firstAt=if(phase==WakePhase.SECOND_WAIT || phase==WakePhase.SECOND_READY) 123 else 0)
    }
    @Test fun rescheduleCancelsExistingRestWakeAndCheck() {
        assertTrue(Alarms.exactAllowed(c));seed()
        Alarms.set(c,Alarms.WAKE,System.currentTimeMillis()-1000)
        instrument.runOnMainSync { Alarms.schedule(c) }
        assertFalse(s.wake.active)
        val next=Instant.ofEpochMilli(s.prefs.getLong("scheduled_wake",0)).atZone(ZoneId.systemDefault()).toLocalDateTime()
        assertEquals(Schedule.nextWake(s.plan!!,s.rules,LocalDateTime.now()),next)
        assertFalse(RestCalendar.isRest(next.toLocalDate()))
        assertEquals(0L,s.log(today.toString()).verifiedAt)
    }
    @Test fun staleWakeAndRetryCallbacksCannotStartFormalWake() {
        instrument.runOnMainSync { AlarmReceiver().onReceive(c,Intent(Alarms.WAKE)) }
        assertFalse("Old WAKE callback must not start rest-day verification",s.wake.active)
        seed(WakePhase.SECOND_WAIT)
        instrument.runOnMainSync { AlarmReceiver().onReceive(c,Intent(Alarms.CHECK)) }
        assertFalse("Old second-check callback must be released",s.wake.active)
        assertTrue(s.logs().isEmpty())
    }
    @Test fun deliveredLegacyWakeAlarmLeavesScreenAndRecordsUntouched() {
        assertTrue(Alarms.exactAllowed(c));device.pressHome();device.sleep()
        val at=System.currentTimeMillis()+1500
        Alarms.set(c,Alarms.WAKE,at)
        val end=System.currentTimeMillis()+8000
        while(s.prefs.getLong("scheduled_wake",0)==at && System.currentTimeMillis()<end) Thread.sleep(100)
        assertNotEquals("Old alarm must actually be delivered",at,s.prefs.getLong("scheduled_wake",0))
        assertFalse(s.wake.active);assertFalse(device.isScreenOn)
        assertFalse(notifications.activeNotifications.any { it.id==21 });assertTrue(s.logs().isEmpty())
        device.wakeUp()
    }
    @Test fun existingPhasesCannotRequireOrRecordVerification() {
        val old=DayLog(today.minusDays(1).toString(),verifiedAt=123,rise="08:30",note="保留历史")
        s.saveLog(old)
        for(phase in listOf(WakePhase.RINGING,WakePhase.WALK,WakePhase.SECOND_WAIT,WakePhase.SECOND_READY)) {
            seed(phase)
            instrument.runOnMainSync { assertFalse(Alarms.verify(c));Alarms.begin(c);Alarms.ring(c) }
            assertFalse(s.wake.active);assertEquals(old,s.logs().single())
        }
        instrument.runOnMainSync { Alarms.start(c) };assertFalse(s.wake.active)
    }
    @Test fun oldServiceAndScreenAreReleasedWithoutAlarm() {
        c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("今天")),8000));seed()
        instrument.runOnMainSync { ContextCompat.startForegroundService(c,Intent(c,WakeAlarmService::class.java)) }
        val end=System.currentTimeMillis()+4000
        while((s.wake.active || notifications.activeNotifications.any { it.id==21 }) && System.currentTimeMillis()<end) Thread.sleep(50)
        assertFalse(s.wake.active)
        assertFalse(notifications.activeNotifications.any { it.id==21 })
        seed();c.startActivity(Intent(c,WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("今天")),5000));Thread.sleep(500)
        assertFalse(s.wake.active);assertFalse(device.hasObject(By.text("开始起床 · 安静验证 3 分钟")))
    }
    @Test fun explicitTrialStillWorksAndWritesNoFormalRecord() {
        c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("今天")),8000))
        instrument.runOnMainSync { Alarms.start(c,true) }
        val end=System.currentTimeMillis()+4000
        while(notifications.activeNotifications.none { it.id==21 } && System.currentTimeMillis()<end) Thread.sleep(50)
        assertTrue(notifications.activeNotifications.any { it.id==21 })
        instrument.runOnMainSync {
            assertTrue(s.wake.active);assertTrue(s.wake.test)
            Alarms.begin(c);assertEquals(WakePhase.WALK,s.wake.phase)
            assertTrue(Alarms.verify(c));assertEquals(WakePhase.SECOND_WAIT,s.wake.phase)
            s.wake=s.wake.copy(dueAt=System.currentTimeMillis()-1)
            assertTrue(Alarms.verify(c));assertEquals(WakePhase.COMPLETE,s.wake.phase)
        }
        assertTrue(s.logs().isEmpty())
    }
}
