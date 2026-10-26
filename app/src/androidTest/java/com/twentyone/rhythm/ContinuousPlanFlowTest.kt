package com.twentyone.rhythm

import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.time.temporal.ChronoUnit

/** Synthetic fixtures on a disposable emulator; never run against a personal phone. */
class ContinuousPlanFlowTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val c=instrument.targetContext
    private val s=Store(c)
    private val manager=c.getSystemService(NotificationManager::class.java)
    private val device by lazy { UiDevice.getInstance(instrument) }
    @Before fun setup() {
        check(Build.HARDWARE in listOf("ranchu","goldfish"))
        Configurator.getInstance().uiAutomationFlags=android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        s.prefs.edit().clear().commit();Notifications.create(c);manager.cancelAll();device.wakeUp()
    }
    @After fun cleanup() {
        instrument.runOnMainSync { Alarms.stop(c,"连续周期测试结束");listOf(Alarms.WAKE,Alarms.CHECK,Alarms.PREP,Alarms.BEDTIME_LOCK).forEach { Alarms.cancel(c,it) } }
        manager.cancelAll();s.prefs.edit().clear().commit();device.pressHome()
    }
    @Test fun oldPlanActuallyRingsAndHabitRemindsAfterDay42() {
        Assume.assumeFalse(RestCalendar.isRest(LocalDate.now()))
        val at=LocalDateTime.now().plusMinutes(1).truncatedTo(ChronoUnit.MINUTES)
        Assume.assumeTrue(at.toLocalDate()==LocalDate.now())
        val start=LocalDate.now().minusDays(42)
        s.createPlan(Plan(start));s.rules=Rules(bed=23*60,wake=at.hour*60+at.minute,secondCheck=false)
        val h=Habit(name="连续42天提醒",start=start,mode=HabitMode.AT_LEAST,rules=listOf(HabitRule(start,target=1,reminder=at.hour*60+at.minute)),input=HabitInput.COUNT)
        s.prefs.edit().putString("habits",HabitStore.encode(listOf(h),emptyList()).toString()).commit()
        instrument.runOnMainSync { Alarms.schedule(c) }
        assertEquals(at.epoch(),s.prefs.getLong("scheduled_wake",0))
        device.pressHome();device.sleep()
        assertTrue(device.wait(Until.hasObject(By.text("扫描洗漱区的起床码")),75000))
        assertEquals(LocalDate.now().toString(),s.wake.day);assertEquals(WakePhase.RINGING,s.wake.phase)
        val until=System.currentTimeMillis()+5000
        while(manager.activeNotifications.none { it.id==211 } && System.currentTimeMillis()<until) android.os.SystemClock.sleep(100)
        assertTrue(manager.activeNotifications.any { it.id==211 && it.tag==h.id })
        assertTrue(s.prefs.getStringSet("habit_reminded",emptySet())!!.contains(HabitAnalysis.key(h.id,LocalDate.now())))
        instrument.runOnMainSync { assertTrue(Alarms.verify(c)) }
        assertTrue(s.log(LocalDate.now().toString()).verifiedAt>0)
        val store=HabitStore(c);store.count(h.id);assertEquals(1,store.entries().single().value)
        assertTrue(HabitCards.available(c).any { it.id==h.id })
    }
    @Test fun longHistoryCanBeEditedRecordedBackedUpAndArchived() {
        val today=LocalDate.now();val start=today.minusDays(45)
        val h=Habit(name="长期计次",start=start,mode=HabitMode.AT_LEAST,rules=listOf(HabitRule(start,target=1)),input=HabitInput.COUNT)
        val entries=(0L..44L).map { HabitEntry(h.id,start.plusDays(it),1) }
        s.prefs.edit().putString("habits",HabitStore.encode(listOf(h),entries).toString()).commit()
        val store=HabitStore(c);store.save(h.revised("长期计次",(1..7).toSet(),2,600,today))
        store.count(h.id);assertEquals(46,store.entries().size)
        val backup=s.export();s.import(backup);assertEquals(46,store.entries().size)
        val saved=store.habits().single();assertEquals(1,saved.rule(today).target);assertEquals(2,saved.rule(today.plusDays(1)).target)
        store.save(saved.copy(archivedOn=today));assertTrue(HabitCards.available(c).isEmpty());assertEquals(46,store.entries().size)
    }
    @Test fun bedtimeNotificationsContinueForAnOldPlan() {
        val day=LocalDate.now();s.createPlan(Plan(day.minusDays(42)));s.rules=Rules(bed=0,wake=540)
        BedtimeReminders.preparation(c,day.atTime(23,45));assertTrue(manager.activeNotifications.any { it.id==22 })
        BedtimeReminders.due(c,day.plusDays(1).atStartOfDay());assertTrue(manager.activeNotifications.any { it.id==26 })
        BedtimeReminders.due(c,day.plusDays(1).atTime(0,5));assertEquals(1,s.events().count { it.kind=="睡前到点提醒" })
    }
    @Test fun progressAndHabitPagesShowCurrentAndPreviousCycles() {
        val today=LocalDate.now();val start=today.minusDays(42)
        s.createPlan(Plan(start));s.rules=Rules(bed=23*60,wake=540)
        val h=Habit(name="跨周期习惯",start=start,mode=HabitMode.AT_LEAST,rules=listOf(HabitRule(start,target=1)),input=HabitInput.COUNT)
        s.prefs.edit().putString("habits",HabitStore.encode(listOf(h),emptyList()).toString()).commit()
        c.startActivity(android.content.Intent(c,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("21 天")),10000));device.findObject(By.text("21 天")).click()
        assertTrue(device.wait(Until.hasObject(By.text("第 3 期")),5000));device.findObject(By.text("上一周期")).click()
        assertTrue(device.wait(Until.hasObject(By.text("第 2 期")),3000));device.findObject(By.text("下一周期")).click()
        assertTrue(device.wait(Until.hasObject(By.text("第 3 期")),3000))
        device.takeScreenshot(java.io.File(c.getExternalFilesDir(null),"continuous-sleep-cycle.png"))
        device.findObject(By.text("习惯")).click();assertTrue(device.wait(Until.hasObject(By.text(h.name)),5000));device.findObject(By.text(h.name)).click()
        assertTrue(device.wait(Until.hasObject(By.text("全部习惯")),5000));device.waitForIdle(2000)
        assertTrue(device.wait(Until.hasObject(By.text("第 3 期")),5000));device.findObject(By.text("上一周期")).click()
        assertTrue(device.wait(Until.hasObject(By.text("第 2 期")),3000))
        device.takeScreenshot(java.io.File(c.getExternalFilesDir(null),"continuous-habit-cycle.png"))
    }
}
