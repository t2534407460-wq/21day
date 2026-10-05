package com.twentyone.rhythm

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import java.time.*

class HabitInteractionTest {
    private val c=InstrumentationRegistry.getInstrumentation().targetContext
    private val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val s=Store(c);private val hs=HabitStore(c);private val today=LocalDate.now()
    private fun timer(start:LocalDate=today)=Habit(name="阅读",start=start,mode=HabitMode.AT_LEAST,unit="分钟",rules=listOf(HabitRule(start,target=20)),input=HabitInput.TIMER)
    private fun count()=Habit(name="控烟",mode=HabitMode.AT_MOST,unit="支",rules=listOf(HabitRule(today,target=10)),input=HabitInput.COUNT,smoking=true)
    @Before fun setup() { device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");device.pressHome();s.prefs.edit().clear().commit();ReviewStore(c).settings.edit().putBoolean("weekly",false).putBoolean("cycle",false).commit() }
    @After fun cleanup() { device.pressHome();s.prefs.edit().clear().commit();HabitReminders.schedule(c) }
    @Test fun timerRestoresAndAddsSubMinuteSessionsWithoutDoubleFinish() {
        val h=timer();hs.save(h);val start=today.atTime(12,0).epoch()
        hs.startTimer(h.id,start);assertEquals(start,HabitStore(c).running(h.id))
        assertTrue(runCatching { hs.startTimer(h.id,start+1000) }.isFailure)
        hs.finishTimer(h.id,start+35000);hs.startTimer(h.id,start+60000);hs.finishTimer(h.id,start+95000)
        val entry=hs.entries().single();assertEquals(1,entry.value);assertEquals(10,entry.remainderSeconds);assertEquals(2,entry.sessions.size)
        assertEquals(0L,hs.running(h.id));assertTrue(runCatching { hs.finishTimer(h.id,start+100000) }.isFailure)
        val backup=s.export();s.import(backup);assertEquals(entry,hs.entries().single())
    }
    @Test fun crossMidnightBelongsToStartDateAndBackupDoesNotRestartTimer() {
        val day=today.minusDays(1);val h=timer(day);hs.save(h,day);val start=day.atTime(23,58).epoch()
        hs.startTimer(h.id,start);hs.finishTimer(h.id,start+5*60_000)
        assertEquals(day,hs.entries().single().date);assertEquals(5,hs.entries().single().value)
        hs.startTimer(h.id,today.atTime(1,0).epoch());val backup=s.export();s.import(backup)
        assertEquals(0L,hs.running(h.id));assertEquals(5,hs.entries().single().value)
    }
    @Test fun countRecordsActualAboveLimitAndDailyZeroMustBeExplicit() {
        val h=count();val quit=h.copy(id=java.util.UUID.randomUUID().toString(),name="戒烟",input=HabitInput.DAILY,rules=listOf(HabitRule(today,target=0)))
        hs.save(h);hs.save(quit);repeat(11) { hs.count(h.id) }
        assertEquals(11,hs.entries().single().value);assertFalse(h.reached(today,11));assertEquals(1,hs.entries().size)
        hs.confirmDay(quit.id);hs.confirmDay(quit.id)
        assertEquals(2,hs.entries().size);assertEquals(0,hs.entries().first { it.habitId==quit.id }.value)
    }
    @Test fun archiveRequiresEndingTimerAndCancelKeepsCompletedRecords() {
        val h=timer();hs.save(h);hs.record(HabitEntry(h.id,today,7,"保留"));hs.startTimer(h.id)
        assertTrue(runCatching { hs.save(h.copy(archivedOn=today)) }.isFailure)
        hs.cancelTimer(h.id);hs.save(h.copy(archivedOn=today));assertEquals(7,hs.entries().single().value)
    }
    private fun launch() { c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK));assertTrue(device.wait(Until.hasObject(By.text("今日打卡")),8000)) }
    private fun freshButton(label:String):UiObject2? {
        val until=System.currentTimeMillis()+7000
        do {
            InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
            device.findObject(By.desc(label))?.let { return it }
            Thread.sleep(100)
        } while(System.currentTimeMillis()<until)
        return null
    }
    private fun hold(label:String,steps:Int=180) { val node=freshButton(label);assertNotNull(label,node);val b=node!!.visibleBounds;device.swipe(b.centerX(),b.centerY(),b.centerX(),b.centerY(),steps);device.waitForIdle() }
    @Test fun homeHoldIsOncePerPressAndSwipingDoesNotRecord() {
        val h=count();hs.save(h);hs.save(timer());launch();device.findObject(By.text("打卡抽屉")).click();assertTrue(device.wait(Until.hasObject(By.text("选择习惯")),5000))
        val b=device.wait(Until.findObject(By.desc("长按 +1 支")),7000).visibleBounds
        device.click(b.centerX(),b.centerY());assertTrue(hs.entries().isEmpty())
        hold("长按 +1 支",450);assertEquals(1,hs.entries().single().value)
        hold("长按 +1 支");assertEquals(2,hs.entries().single().value)
        val title=device.findObject(By.text("控烟")).visibleBounds
        device.swipe(device.displayWidth*85/100,title.centerY(),device.displayWidth*15/100,title.centerY(),35)
        // Wait for the 220 ms paper transition before refreshing the cached accessibility tree.
        Thread.sleep(500);device.waitForIdle();InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
        val turned=freshButton("长按开始")!=null
        if(!turned) { device.takeScreenshot(java.io.File(c.filesDir,"habit-swipe-failure.png"));device.dumpWindowHierarchy(java.io.File(c.filesDir,"habit-swipe-failure.xml")) }
        assertTrue(turned);assertEquals(1,hs.entries().size)
        hold("长按开始");assertTrue(hs.running(hs.habits()[1].id)>0)
        hold("长按结束");assertEquals(0L,hs.running(hs.habits()[1].id));assertEquals(2,hs.entries().size)
    }
    @Test fun settingsAreCategorizedAndApiIsAvailableThere() {
        launch();device.findObject(By.text("设置")).click()
        assertTrue(device.wait(Until.hasObject(By.text("AI 助手")),5000))
        assertFalse(device.hasObject(By.text("检查更新")))
        device.findObject(By.text("AI 助手")).click()
        assertTrue(device.wait(Until.hasObject(By.text("DeepSeek API Key")),5000) || device.hasObject(By.text("替换 API Key")))
        device.pressBack();assertTrue(device.wait(Until.hasObject(By.text("数据与更新")),5000));device.findObject(By.text("数据与更新")).click()
        assertTrue(device.wait(Until.hasObject(By.text("检查更新")),5000))
    }
    @Test fun editorCreatesExerciseAbstinenceControlAndCustomInputs() {
        fun tap(text:String) {
            if(!device.hasObject(By.text(text))) runCatching { UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text)) }
            val node=device.wait(Until.findObject(By.text(text)),7000);assertNotNull(text,node);node.click();device.waitForIdle()
        }
        fun editor(template:String) {
            launch();tap("习惯");tap("添加习惯");tap(template)
            if(template=="自定义") { device.wait(Until.findObject(By.clazz("android.widget.EditText")),5000).text="Custom ${hs.habits().size}";device.waitForIdle() }
        }
        editor("运动");tap("保存习惯")
        assertEquals(HabitInput.TIMER,hs.habits().last().input);assertEquals(setOf(1,3,5),hs.habits().last().rules.single().days)
        editor("戒烟");tap("保存习惯")
        assertEquals(HabitInput.DAILY,hs.habits().last().input);assertEquals(0,hs.habits().last().rules.single().target)
        editor("戒烟");tap("每日控量");tap("保存习惯")
        assertEquals(HabitInput.COUNT,hs.habits().last().input);assertEquals(10,hs.habits().last().rules.single().target)
        editor("自定义");tap("时段计时");tap("保存习惯")
        assertEquals(HabitInput.TIMER,hs.habits().last().input)
        editor("自定义");tap("保存习惯")
        assertEquals(HabitInput.COUNT,hs.habits().last().input);assertEquals(5,hs.habits().size)
    }
}
