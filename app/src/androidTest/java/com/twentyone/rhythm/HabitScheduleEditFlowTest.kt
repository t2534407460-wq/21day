package com.twentyone.rhythm

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import java.time.*

class HabitScheduleEditFlowTest {
    private val c=InstrumentationRegistry.getInstrumentation().targetContext
    private val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val store=HabitStore(c);private val s=Store(c)
    private val today=LocalDate.now()
    @Before fun setup() {
        Assume.assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");device.pressHome()
        s.prefs.edit().clear().commit()
        ReviewStore(c).settings.edit().putBoolean("weekly",false).putBoolean("cycle",false).commit()
        c.getSystemService(NotificationManager::class.java).cancelAll()
    }
    @After fun cleanup() { device.pressHome();s.prefs.edit().clear().commit();HabitReminders.schedule(c);c.getSystemService(NotificationManager::class.java).cancelAll() }
    private fun seed(minute:Int=360):Habit {
        val start=today.minusDays(2)
        return Habit(name="运动",start=start,mode=HabitMode.AT_LEAST,unit="分钟",rules=listOf(HabitRule(start,setOf(1,3,5),30,minute))).also { store.save(it,start) }
    }
    @Test fun saveTodayScheduleReplacesOldAlarmAndRejectsLateOldCallback() {
        val now=LocalDateTime.now();Assume.assumeTrue(now.hour<23)
        val first=now.plusMinutes(1).withSecond(0).withNano(0);val next=first.plusMinutes(1)
        val h=seed(first.hour*60+first.minute).let { it.copy(rules=listOf(it.rules.single().copy(days=(1..7).toSet()))) }
        // Seed the original schedule, as if created two days ago.
        s.prefs.edit().clear().commit();store.save(h,h.start);HabitReminders.schedule(c)
        assertEquals(first.epoch(),s.prefs.getLong("habit_alarm_at",0))
        val changed=h.copy(rules=h.rules+HabitRule(today,(1..7).toSet(),30,next.hour*60+next.minute))
        store.save(changed);HabitReminders.schedule(c)
        assertEquals(next.epoch(),s.prefs.getLong("habit_alarm_at",0))
        device.executeShellCommand("pm grant ${c.packageName} android.permission.POST_NOTIFICATIONS")
        HabitReminders.deliver(c,first.epoch(),first.plusSeconds(1))
        val nm=c.getSystemService(NotificationManager::class.java)
        assertTrue(nm.activeNotifications.none { it.tag==h.id })
        HabitReminders.deliver(c,next.epoch(),next.plusSeconds(1))
        assertEquals(1,nm.activeNotifications.count { it.tag==h.id });assertTrue(store.entries().isEmpty())
        HabitReminders.schedule(c)
        assertTrue(s.prefs.getLong("habit_alarm_at",0)>next.epoch())
    }
    @Test fun uiEditsMondayWednesdayFridaySixToDailyEightAndReopensWithNewSchedule() {
        seed()
        c.startActivity(Intent(c,MainActivity::class.java).setAction("habits").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        fun tap(text:String) {
            if(!device.wait(Until.hasObject(By.text(text)),1500))runCatching { UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(text) }
            val node=device.wait(Until.findObject(By.text(text)),7000);assertNotNull(text,node);node.click();device.waitForIdle()
        }
        tap("运动");tap("调整计划")
        assertTrue(device.wait(Until.hasObject(By.text("调整习惯计划")),5000))
        listOf("二","四","六","日").forEach { day ->
            val desc="周${day}未选"
            if(!device.wait(Until.hasObject(By.desc(desc)),1000))runCatching { UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().description(desc)) }
            val node=device.wait(Until.findObject(By.desc(desc)),5000)
            if(node==null)device.dumpWindowHierarchy(java.io.File(c.getExternalFilesDir(null),"0.12.2-edit-missing.xml"))
            assertNotNull(desc,node);node.click();device.waitForIdle()
        }
        tap("打卡提醒时间")
        device.dumpWindowHierarchy(java.io.File(c.getExternalFilesDir(null),"0.12.2-time-picker.xml"))
        val hour=device.wait(Until.findObject(By.descStartsWith("8 ")),5000)
        assertNotNull("8点的时钟选项",hour);hour.click();device.waitForIdle()
        tap("确定时间");tap("保存习惯")
        val deadline=System.currentTimeMillis()+5000
        while(store.habits().single().rule(today).reminder!=480 && System.currentTimeMillis()<deadline)Thread.sleep(50)
        assertEquals((1..7).toSet(),store.habits().single().rule(today).days)
        assertEquals(480,store.habits().single().rule(today).reminder)
        assertTrue(device.wait(Until.hasObject(By.text("每天 · 08:00 提醒")),5000))
        device.takeScreenshot(java.io.File(c.getExternalFilesDir(null),"0.12.2-habit-schedule.png"))
        tap("调整计划");assertTrue(device.wait(Until.hasObject(By.desc("周二已选")),5000))
        tap("取消")
        assertEquals(480,store.habits().single().rule(today.plusDays(1)).reminder)
    }
    @Test fun removingTodayKeepsExistingEntryAndTimerAndBackupReadable() {
        val h=seed().let { it.copy(rules=listOf(it.rules.single().copy(days=(1..7).toSet()))) }
        s.prefs.edit().clear().commit();store.save(h,h.start)
        store.record(HabitEntry(h.id,today,5));store.startTimer(h.id)
        val days=(1..7).toSet()-today.dayOfWeek.value
        val changed=h.revised(h.name,days,20,480,today,true)
        store.save(changed)
        assertEquals(5,store.entries().single().value);assertTrue(store.running(h.id)>0)
        assertTrue(HabitStore.decode(org.json.JSONObject(s.export()).getJSONObject("habits")).first.single().scheduled(today))
        val before=s.export()
        assertTrue(runCatching { store.save(h.revised(h.name,days,20,480,today,false)) }.isFailure)
        assertEquals(before,s.export())
        store.finishTimer(h.id,System.currentTimeMillis()+60_000)
        assertEquals(6,store.entries().single().value)
    }
}
