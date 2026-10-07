package com.twentyone.rhythm

import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate

/** Synthetic state only; never run on a personal phone. */
class UsabilityFlowTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private val directory by lazy { java.io.File(context.getExternalFilesDir(null),"usability-qa").apply { mkdirs() } }
    @Before fun setup() {
        Assume.assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")
        Store(context).prefs.edit().clear().commit()
    }
    private fun launch() {
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("设置")),10000))
    }
    private fun tap(label:String) {
        device.waitForIdle();instrumentation.uiAutomation.clearCache()
        var attempts=0
        while(!device.hasObject(By.text(label)) && attempts++<12) {
            device.swipe(device.displayWidth/2,device.displayHeight*3/4,device.displayWidth/2,device.displayHeight/3,30)
            device.waitForIdle();instrumentation.uiAutomation.clearCache()
        }
        instrumentation.uiAutomation.clearCache()
        val node=device.wait(Until.findObject(By.text(label)),5000);assertNotNull(label,node)
        node.click();android.os.SystemClock.sleep(200);device.waitForIdle();instrumentation.uiAutomation.clearCache()
    }
    private fun capture(name:String) { device.takeScreenshot(java.io.File(directory,"$name.png"));device.dumpWindowHierarchy(java.io.File(directory,"$name.xml")) }
    @Test fun accountShowsOnePrimaryFlowAndClearRecoveryLinks() {
        launch();tap("设置");tap("账号")
        assertTrue(device.wait(Until.hasObject(By.text("欢迎回来")),3000))
        assertTrue(device.hasObject(By.text("忘记密码？")))
        assertTrue(device.hasObject(By.text("注册账号")))
        assertFalse(device.hasObject(By.text("注册或重置时至少 9 个字符，包含字母、数字和符号")))
        tap("注册账号")
        assertTrue(device.hasObject(By.text("创建账号")))
        device.pressBack();device.waitForIdle();instrumentation.uiAutomation.clearCache()
        assertTrue(device.wait(Until.hasObject(By.text("欢迎回来")),3000))
    }
    @Test fun permissionShortcutGoesToTheRelevantSettings() {
        HabitStore(context).save(Habit(name="阅读",start=LocalDate.now(),rules=listOf(HabitRule(LocalDate.now(),reminder=1200))))
        assertFalse("Revoke POST_NOTIFICATIONS before starting this isolated test",context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled())
        launch();tap("习惯");capture("permission-shortcut");tap("开启通知，接收习惯提醒 →")
        assertTrue(device.wait(Until.hasObject(By.text("权限与可靠性")),3000))
    }
    @Test fun allSettingsSectionsCanBeEnteredAndReturnedWithoutChangingRecords() {
        Store(context).createPlan(Plan(LocalDate.now()))
        Store(context).saveLog(DayLog(LocalDate.now().toString(),note="保留原记录"))
        val before=Store(context).export()
        launch();tap("设置")
        val sections=listOf("账号" to "欢迎回来","时屿连接" to "时屿关联事项","作息计划" to "作息与约束","习惯与复盘" to "管理习惯计划","AI 助手" to "DeepSeek 连接","权限与后台" to "权限与可靠性","起床验证" to "我的起床二维码","数据与更新" to "检查更新")
        sections.forEachIndexed { index,(category,content) ->
            tap(category)
            assertTrue(category,device.wait(Until.hasObject(By.text(content)),5000))
            capture("settings-$index")
            if(category=="时屿连接") {
                tap("前往登录")
                assertTrue(device.wait(Until.hasObject(By.text("欢迎回来")),5000))
            }
            if(category=="习惯与复盘") {
                tap("复盘看板")
                assertTrue(device.wait(Until.hasObject(By.text("周总结")),5000))
                tap("21天总结");capture("review-21-days");tap("周总结");capture("review-weekly")
                device.pressBack();device.waitForIdle();instrumentation.uiAutomation.clearCache()
                assertTrue(device.wait(Until.hasObject(By.text(content)),5000))
            }
            device.pressBack();device.waitForIdle();instrumentation.uiAutomation.clearCache()
            assertTrue("Back from $category",device.wait(Until.hasObject(By.text("邮箱登录、注册与同步")),5000))
        }
        assertEquals(before,Store(context).export())
    }
    @Test fun mainTabsAndCancelledHabitActionsPreserveData() {
        val store=Store(context);store.createPlan(Plan(LocalDate.now()));store.saveLog(DayLog(LocalDate.now().toString(),note="保留原记录"))
        val habit=Habit(name="阅读",start=LocalDate.now(),mode=HabitMode.AT_LEAST,unit="分钟",rules=listOf(HabitRule(LocalDate.now(),target=20)),input=HabitInput.TIMER)
        HabitStore(context).save(habit)
        val before=store.export()
        launch();capture("today")
        tap("21 天");tap("第 3 周");capture("journey");tap("第 1 周")
        tap("习惯");tap("阅读");capture("habit-detail")
        tap("归档此计划");assertTrue(device.hasObject(By.text("归档阅读？")));tap("取消")
        device.pressBack();device.waitForIdle();instrumentation.uiAutomation.clearCache()
        tap("添加习惯");tap("取消")
        tap("21 天");tap("周总结");capture("trends");tap("记录助手")
        assertTrue(device.wait(Until.hasObject(By.text("记录助手")),3000))
        capture("coach");device.pressBack();device.waitForIdle()
        assertEquals(before,store.export())
    }
}
