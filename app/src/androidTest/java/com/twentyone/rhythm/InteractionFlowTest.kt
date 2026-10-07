package com.twentyone.rhythm

import android.content.Intent
import android.graphics.Point
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@RunWith(AndroidJUnit4::class)
class InteractionFlowTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val context=instrument.targetContext
    private val device=UiDevice.getInstance(instrument)
    private lateinit var store:Store
    @Before fun setup() {
        store=Store(context);store.prefs.edit().clear().commit()
        store.createPlan(Plan());launch()
    }
    @After fun cleanup(){device.pressHome();store.prefs.edit().clear().commit()}
    private fun launch(){context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK));assertTrue(device.wait(Until.hasObject(By.text("今天")),15000))}
    private fun tap(text:String){
        if(!device.hasObject(By.text(text))) runCatching { UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text)) }
        val found=device.wait(Until.hasObject(By.text(text)),7000)
        if(!found) { device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"interaction-missing.png"));device.dumpWindowHierarchy(java.io.File(context.getExternalFilesDir(null),"interaction-missing.xml")) }
        assertTrue(text,found);device.waitForIdle()
        val bounds=device.findObject(UiSelector().text(text)).visibleBounds
        assertFalse("按钮须在可见区域：$text",bounds.isEmpty);assertTrue(device.click(bounds.centerX(),bounds.centerY()));device.waitForIdle()
    }
    private fun orbCenter():Point { val bounds=device.findObject(UiSelector().description("选择 圆球")).visibleBounds;return Point(bounds.centerX(),bounds.centerY()) }
    private fun sphere():Point {
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("开始睡前打卡"))
        tap("开始睡前打卡")
        val orb=device.wait(Until.findObject(By.desc("选择 圆球")),7000)
        assertNotNull("Sleepiness orb should be ready",orb)
        return orbCenter()
    }
    private fun gesture(start:Point,dx:Float,dy:Float,backToCentre:Boolean=false) {
        val began=SystemClock.uptimeMillis()
        fun event(action:Int,x:Float,y:Float) {
            val event=MotionEvent.obtain(began,SystemClock.uptimeMillis(),action,x,y,0).apply{source=InputDevice.SOURCE_TOUCHSCREEN}
            instrument.uiAutomation.injectInputEvent(event,true);event.recycle()
        }
        event(MotionEvent.ACTION_DOWN,start.x.toFloat(),start.y.toFloat())
        SystemClock.sleep(650)
        repeat(15){i->event(MotionEvent.ACTION_MOVE,start.x+dx*(i+1)/15,start.y+dy*(i+1)/15);SystemClock.sleep(16)}
        if(backToCentre){event(MotionEvent.ACTION_MOVE,start.x.toFloat(),start.y.toFloat());SystemClock.sleep(80)}
        event(MotionEvent.ACTION_UP,start.x+(if(backToCentre)0f else dx),start.y+(if(backToCentre)0f else dy))
        device.waitForIdle()
    }
    @Test fun bedtimeOrbsSaveDraftsButRequireFinalConfirmation() {
        val point=sphere();val density=context.resources.displayMetrics.density
        gesture(point,0f,-108*density)
        assertTrue(device.wait(Until.hasObject(By.text("睡前，心情怎么样？")),7000))
        assertEquals("很困了",store.log(LocalDate.now().toString()).sleepiness)
        assertEquals(0L,store.log(LocalDate.now().toString()).bedtimeCheckedAt)
        val mood=orbCenter()
        gesture(mood,0f,-108*density)
        assertTrue(device.wait(Until.hasObject(By.text("有什么影响了作息？")),7000))
        assertEquals("放松",store.log(LocalDate.now().toString()).bedMood)
        tap("也可以点选");tap("手机")
        assertEquals("手机",store.log(LocalDate.now().toString()).bedReason)
        assertEquals(0L,store.log(LocalDate.now().toString()).bedtimeCheckedAt)
        if(!device.hasObject(By.text("完成睡前打卡"))) UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("完成睡前打卡"))
        tap("完成睡前打卡");tap("知道了")
        assertTrue(BedtimeSchedule.checked(store.log(LocalDate.now().toString())))
        assertEquals(0L,store.log(LocalDate.now().toString()).verifiedAt)
        assertTrue(device.wait(Until.hasObject(By.text("今天")),7000))
    }
    @Test fun tapHoldAndReturnToCentreDoNotRecord() {
        val point=sphere()
        device.click(point.x,point.y);device.waitForIdle()
        assertTrue(store.logs().isEmpty())
        gesture(point,0f,0f)
        assertTrue(store.logs().isEmpty())
        gesture(point,100*context.resources.displayMetrics.density,0f,true)
        assertTrue(store.logs().isEmpty())
    }
    @Test fun timePickerCancellationAndBackKeepTask() {
        store.hideFromRecents=false;RecentTasks.apply(context)
        tap("设置");tap("作息计划");tap("我的作息时间")
        tap("夜间限制开始")
        assertTrue(device.wait(Until.hasObject(By.text("确定时间")),5000))
        tap("取消");tap("取消")
        assertEquals(1380,store.rules.bed)
        device.pressBack();device.waitForIdle();device.pressBack();device.waitForIdle()
        assertFalse(device.hasObject(By.text("按自己的节奏来。")))
        val tasks=context.getSystemService(android.app.ActivityManager::class.java).appTasks
        assertTrue(tasks.any{it.taskInfo.baseIntent.component?.packageName==context.packageName})
        assertTrue(tasks.none{it.taskInfo.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0})
    }
    @Test fun datePickerUsesCalendarAndCancellationPreservesDate() {
        store.prefs.edit().clear().commit()
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        tap("设置我的 21 天");tap("开始日期")
        assertTrue(device.wait(Until.hasObject(By.text("确定日期")),5000))
        assertFalse(device.hasObject(By.clazz("android.widget.EditText")))
        tap("取消");tap("保存计划")
        assertEquals(LocalDate.now(),store.plan?.start)
    }
    @Test fun journeyPairsFinalNightWithFollowingMorningAndKeepsItReadOnly() {
        val last=LocalDate.now();val morning=last.plusDays(1)
        store.createPlan(Plan(last.minusDays(20)))
        store.saveLog(DayLog(last.toString(),sleepiness="有点困",bedMood="平静",bedReason="无",bedtimeCheckedAt=LocalDateTime.now().epoch()))
        store.saveLog(DayLog(morning.toString(),rise="07:01",verifiedAt=morning.atTime(7,1).epoch()))
        val before=store.logs();launch();tap("21 天");tap("第 3 周")
        device.wait(Until.findObject(By.descStartsWith("第 21 天，")),5000).click()
        val heading="醒后 · ${morning.format(DateTimeFormatter.ofPattern("MM月dd日"))}"
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(heading))
        assertTrue(device.wait(Until.hasObject(By.text(heading)),5000))
        assertTrue(device.wait(Until.hasObject(By.text("已验证 · ${stamp(morning.atTime(7,1).epoch())}")),5000))
        assertFalse(device.hasObject(By.text("早上，精神怎么样？")))
        assertEquals(before,store.logs())
    }
}
