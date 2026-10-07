package com.twentyone.rhythm

import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.*
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate

class ReviewNavigationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val c=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    @Before fun setup() {
        Assume.assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");device.pressHome()
        Store(c).prefs.edit().clear().commit()
        ReviewStore(c).prefs.edit().clear().commit()
        ReviewStore(c).settings.edit().clear().putBoolean("weekly",false).putBoolean("cycle",false).commit()
        CloudCoach.removeKey(c)
    }
    @After fun cleanup() { device.pressHome();device.setOrientationNatural();device.unfreezeRotation() }
    private fun tap(text:String) {
        val node=device.wait(Until.findObject(By.text(text)),5000)
        assertNotNull(text,node);node.click();device.waitForIdle(2000)
    }
    private fun launch() {
        c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        tap("21 天")
    }
    @Test fun reusedEmbeddedReviewFollowsParentSelectionInsteadOfRememberedWeek() {
        val start=LocalDate.now().minusDays(24)
        val h=Habit(name="阅读",start=start,rules=listOf(HabitRule(start)))
        HabitStore(c).save(h,start)
        val store=ReviewStore(c);store.settings.edit().putString("since",start.toString()).commit()
        val specs=store.specs(c)
        store.save(specs.last { it.kind=="week" }.id,ReviewReport("done",answer="周报告原文"))
        store.save(specs.first { it.kind=="cycle" }.id,ReviewReport("done",answer="周期报告原文"))
        launch()
        // Reuse the embedded content while its parent changes selection. A saved local
        // week must never override the currently selected cycle in the parent.
        var selected by mutableStateOf("week")
        instrumentation.runOnMainSync {
            val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first() as ComponentActivity
            activity.setContent { RhythmTheme { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row {
                    TextButton(onClick={selected="week"}) { Text("切换周总结") }
                    TextButton(onClick={selected="cycle"}) { Text("切换21天总结") }
                }
                Text("当前选择：$selected")
                ReviewScreen(activity,initialTab=selected,showNavigation=false)
            } } }
        }
        assertTrue(device.wait(Until.hasObject(By.text("周报告原文")),5000))
        tap("切换21天总结")
        assertTrue(device.wait(Until.hasObject(By.text("当前选择：cycle")),3000))
        device.takeScreenshot(java.io.File(c.getExternalFilesDir(null),"review-parent-cycle.png"))
        assertTrue("父页签已是cycle，正文必须跟随",device.wait(Until.hasObject(By.text("周期报告原文")),3000))
        assertFalse(device.hasObject(By.text("周报告原文")))
        tap("切换周总结");assertTrue(device.wait(Until.hasObject(By.text("周报告原文")),3000))
    }
    @Test fun unfinishedCyclesSwitchRepeatedlyWithoutLosingContentOrNavigation() {
        val today=LocalDate.now();val start=today.minusDays(3)
        Store(c).createPlan(Plan(start))
        val habits=listOf("阅读","运动","工作").map { name ->
            Habit(name=name,start=start,mode=HabitMode.AT_LEAST,unit="分钟",rules=listOf(HabitRule(start,target=20)))
        }
        habits.forEach { HabitStore(c).save(it,start);HabitStore(c).record(HabitEntry(it.id,today,10)) }
        val before=Store(c).export();launch()
        repeat(4) {
            tap("周总结");assertTrue(device.wait(Until.hasObject(By.text("这一周的回顾")),3000))
            tap("21天总结");assertTrue(device.wait(Until.hasObject(By.text("这一轮的回顾")),3000))
            tap("阅读");tap("运动")
            assertTrue(device.wait(Until.hasObject(By.text("运动")),3000))
            tap("运动");tap("阅读")
        }
        tap("作息进度");assertTrue(device.wait(Until.hasObject(By.text("把早晚，连成习惯。")),3000))
        tap("21天总结");tap("习惯");tap("21 天")
        assertTrue(device.wait(Until.hasObject(By.text("这一轮的回顾")),3000))
        assertEquals(before,Store(c).export())
    }
    @Test fun completedLongCycleScrollAndReturnToWeeklyRemainResponsive() {
        val start=LocalDate.now().minusDays(24)
        val h=Habit(name="阅读",start=start,rules=listOf(HabitRule(start)))
        HabitStore(c).save(h,start)
        val store=ReviewStore(c);store.settings.edit().putString("since",start.toString()).commit()
        val specs=store.specs(c)
        store.save(specs.last { it.kind=="week" }.id,ReviewReport("done",answer="周总结保留"))
        store.save(specs.first { it.kind=="cycle" }.id,ReviewReport("done",answer=(1..80).joinToString("\n") { "第${it}段：按本期真实记录回顾进度，未记录不等于零。" }+"\n报告结尾"))
        launch();tap("21天总结")
        assertTrue(device.wait(Until.hasObject(By.text("这一轮的回顾")),3000))
        device.swipe(device.displayWidth/2,device.displayHeight*3/4,device.displayWidth/2,device.displayHeight/3,20)
        tap("周总结");assertTrue(device.wait(Until.hasObject(By.text("周总结保留")),3000))
        tap("21天总结");tap("作息进度");tap("21天总结")
        tap("记录助手");assertNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")),5000))
    }
}
