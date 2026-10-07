package com.twentyone.rhythm

import android.app.NotificationManager
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.time.*

class HabitFlowTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val c=instrument.targetContext
    private val device=UiDevice.getInstance(instrument)
    private val s=Store(c);private val hs=HabitStore(c);private val today=LocalDate.now()
    @Before fun setup() { device.wakeUp();device.pressHome();s.prefs.edit().clear().commit();c.getSystemService(NotificationManager::class.java).cancelAll() }
    @After fun cleanup() { device.pressHome();s.prefs.edit().clear().commit();HabitReminders.schedule(c);c.getSystemService(NotificationManager::class.java).cancelAll() }
    private fun h(start:LocalDate=today)=Habit(name="阅读",start=start,mode=HabitMode.AT_LEAST,unit="分钟",rules=listOf(HabitRule(start,target=20)))
    private fun launch() { c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK));assertTrue(device.wait(Until.hasObject(By.text("习惯")),10000));assertTrue(device.findObject(UiSelector().text("习惯")).click());device.waitForIdle() }
    private fun tap(text:String) { if(!device.hasObject(By.text(text))) runCatching { UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text)) };assertNotNull(text,device.wait(Until.findObject(By.text(text)),7000));device.findObject(By.text(text)).click();device.waitForIdle() }
    @Test fun habitsWorkWithoutSleepPlanAndActualEntryCanBeEdited() {
        launch();tap("添加习惯");tap("阅读");tap("保存习惯")
        assertNull(s.plan);assertEquals(1,hs.habits().size)
        tap("手动填写今日总量");device.wait(Until.findObject(By.clazz("android.widget.EditText")),7000).text="25"
        tap("保存打卡")
        val until=System.currentTimeMillis()+5000
        while(hs.entries().isEmpty() && System.currentTimeMillis()<until)Thread.sleep(50)
        assertEquals(25,hs.entries().single().value)
        tap("修改今日记录");device.wait(Until.findObject(By.clazz("android.widget.EditText")),7000).text="10";tap("保存打卡")
        assertEquals(1,hs.entries().size);assertEquals(10,hs.entries().single().value)
        device.takeScreenshot(java.io.File(c.getExternalFilesDir(null),"0.7.0-habit-detail.png"))
        launch();assertEquals(1,hs.entries().size);assertTrue(s.logs().isEmpty());assertEquals(WakePhase.IDLE,s.wake.phase)
    }
    @Test fun recordsAreUpsertsAndFutureOrRestDaysAreRejected() {
        val h=h();hs.save(h);hs.record(HabitEntry(h.id,today,20));hs.record(HabitEntry(h.id,today,30,"调整总量"))
        assertEquals(1,hs.entries().size);assertEquals(30,HabitStore(c).entries().single().value)
        assertTrue(runCatching { hs.record(HabitEntry(h.id,today.plusDays(1),20)) }.isFailure)
        assertTrue(runCatching { hs.record(HabitEntry(h.id,today,-1)) }.isFailure)
        val other=h().copy(name="运动",rules=listOf(HabitRule(today,days=setOf(today.plusDays(1).dayOfWeek.value))))
        hs.save(other);assertTrue(runCatching { hs.record(HabitEntry(other.id,today,1)) }.isFailure)
        hs.removeEntry(h.id,today);assertTrue(hs.entries().isEmpty())
    }
    @Test fun editsApplyTomorrowAndInvalidChangesPreserveEverything() {
        val start=today.minusDays(2);val h=h(start);hs.save(h,start);hs.record(HabitEntry(h.id,start,15),today)
        hs.save(h.copy(rules=h.rules+HabitRule(today.plusDays(1),target=10)))
        assertEquals(20,hs.habits().single().rule(today).target);assertEquals(10,hs.habits().single().rule(today.plusDays(1)).target)
        val before=s.export();assertTrue(runCatching { hs.save(h.copy(rules=listOf(HabitRule(start,target=10)))) }.isFailure);assertEquals(before,s.export())
        hs.save(hs.habits().single().copy(archivedOn=today));assertEquals(1,hs.entries().size)
        assertTrue(runCatching { hs.record(HabitEntry(h.id,today,0)) }.isFailure)
    }
    @Test fun backupRoundTripsWithoutSleepAndOldBackupPreservesHabits() {
        val h=h();hs.save(h);hs.record(HabitEntry(h.id,today,0,"真实零值"));val exported=s.export()
        hs.removeEntry(h.id,today);s.import(exported);assertNull(s.plan);assertEquals(0,hs.entries().single().value)
        val old=JSONObject(exported).put("schema",1);old.remove("habits")
        s.import(old.toString());assertEquals(h,hs.habits().single());assertEquals("真实零值",hs.entries().single().note)
        val bad=JSONObject(exported);bad.getJSONObject("habits").getJSONArray("entries").getJSONObject(0).put("habitId","unknown")
        val before=s.export();assertTrue(runCatching { s.import(bad.toString()) }.isFailure);assertEquals(before,s.export())
    }
    @Test fun remindersAreIndependentDoNotCompleteHabitsAndAreDeduplicated() {
        device.executeShellCommand("pm grant ${c.packageName} android.permission.POST_NOTIFICATIONS")
        val now=LocalDateTime.now().withSecond(0).withNano(0);val first=h().copy(rules=listOf(HabitRule(today,target=20,reminder=now.hour*60+now.minute)))
        val second=first.copy(id=java.util.UUID.randomUUID().toString(),name="戒烟",mode=HabitMode.AT_MOST,unit="支",input=HabitInput.COUNT,smoking=true,rules=listOf(first.rules[0].copy(target=0)))
        hs.save(first);hs.save(second);hs.record(HabitEntry(second.id,today,0))
        HabitReminders.deliver(c,now.epoch(),now.plusSeconds(2))
        val nm=c.getSystemService(NotificationManager::class.java)
        assertEquals(1,nm.activeNotifications.count { it.tag==first.id });assertEquals(1,hs.entries().size)
        nm.cancelAll();HabitReminders.deliver(c,now.epoch(),now.plusSeconds(3));assertTrue(nm.activeNotifications.isEmpty())
        assertTrue(s.logs().isEmpty());assertEquals(WakePhase.IDLE,s.wake.phase)
    }
    @Test fun realAlarmFiresAndNotificationOpensHabits() {
        device.executeShellCommand("pm grant ${c.packageName} android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("appops set ${c.packageName} SCHEDULE_EXACT_ALARM allow")
        val due=LocalDateTime.now().plusMinutes(1).withSecond(0).withNano(0)
        Assume.assumeTrue(due.toLocalDate()==today)
        val h=h().copy(rules=listOf(HabitRule(today,target=20,reminder=due.hour*60+due.minute)))
        hs.save(h);HabitReminders.schedule(c)
        assertEquals(due.epoch(),s.prefs.getLong("habit_alarm_at",0))
        device.pressHome()
        val nm=c.getSystemService(NotificationManager::class.java)
        val until=System.currentTimeMillis()+75000
        while(nm.activeNotifications.none { it.tag==h.id } && System.currentTimeMillis()<until) Thread.sleep(500)
        val notice=nm.activeNotifications.firstOrNull { it.tag==h.id };assertNotNull("真实 AlarmManager 提醒",notice)
        assertTrue(hs.entries().isEmpty());device.openNotification()
        val notification=device.wait(Until.findObject(By.text("阅读 · 记录今天")),5000);assertNotNull(notification);notification.click()
        assertTrue(device.wait(Until.hasObject(By.text("把想做的，变成日常。")),7000))
        assertTrue(s.prefs.getStringSet("habit_reminded",emptySet())!!.contains(HabitAnalysis.key(h.id,today)))
        assertTrue(s.prefs.getLong("habit_alarm_at",0)>due.epoch())
    }
}
