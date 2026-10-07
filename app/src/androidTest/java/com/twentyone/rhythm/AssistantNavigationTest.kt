package com.twentyone.rhythm

import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate

class AssistantNavigationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val c=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    @Before fun setup() {
        Assume.assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")
        Store(c).prefs.edit().clear().commit()
        ProjectPreferences.get(c,"coach_chat").edit().clear().commit()
        ProjectPreferences.get(c,"coach_voice").edit().clear().commit()
        ReviewStore(c).prefs.edit().clear().commit()
        ReviewStore(c).settings.edit().clear().putBoolean("weekly",false).putBoolean("cycle",false).commit()
        CloudCoach.removeKey(c)
    }
    private fun launch() {
        c.startActivity(Intent(c,CoachActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")),10000))
    }
    private fun tap(text:String) { device.wait(Until.findObject(By.text(text)),5000).click();device.waitForIdle();instrumentation.uiAutomation.clearCache() }
    private fun capture(name:String) { device.takeScreenshot(java.io.File(c.getExternalFilesDir(null),"0.12.3-$name.png")) }
    @Test fun oldDisabledPreferenceNeverOffersSystemVoice() {
        ProjectPreferences.get(c,"coach_voice").edit().putBoolean("keyboard",false).commit()
        launch()
        assertFalse("Turning the old switch off must not offer the broken system route",device.hasObject(By.desc("语音输入")))
        assertEquals(c.packageName,device.currentPackageName)
        assertNotNull(device.findObject(By.clazz("android.widget.EditText")))
        capture("voice-disabled")
    }
    @Test fun voiceOffStaysOffAcrossTabsAndSettingsWhileInputSurvives() {
        launch()
        device.findObject(By.desc("助手设置")).click()
        device.wait(Until.findObject(By.desc("启用语音输入")),5000).click();tap("完成")
        assertFalse(device.hasObject(By.desc("语音输入")))
        device.findObject(By.clazz("android.widget.EditText")).text="尚未发送的文字"
        device.waitForIdle()
        if(device.executeShellCommand("dumpsys input_method").contains("mInputShown=true"))device.pressBack()
        tap("21 天");tap("周总结");assertTrue(device.hasObject(By.text("周总结")))
        tap("记录助手")
        assertTrue(device.hasObject(By.text("尚未发送的文字")));assertFalse(device.hasObject(By.desc("语音输入")))
        assertTrue(CoachChat.load(c).isEmpty())
        device.findObject(By.desc("助手设置")).click();tap("API 与自动复盘设置")
        assertTrue(device.wait(Until.hasObject(By.text("DeepSeek 连接")),5000))
        tap("记录助手");assertFalse(device.hasObject(By.desc("语音输入")))
        try {
            device.setOrientationLeft();device.waitForIdle()
            val input=device.wait(Until.findObject(By.clazz("android.widget.EditText")),7000)
            capture("landscape-before-keyboard");assertNotNull(input)
            input.click();device.waitForIdle()
            assertEquals("尚未发送的文字",device.findObject(By.clazz("android.widget.EditText")).text)
            assertTrue(device.findObject(By.desc("发送消息")).visibleBounds.height()>0)
            capture("landscape-keyboard")
        } finally { device.pressHome();device.setOrientationNatural();device.unfreezeRotation() }
    }
    @Test fun changesOnlyShowsSelectedWeeklyOrCycleSummaryWithoutSleepPlan() {
        val start=LocalDate.now().minusDays(24)
        val h=Habit(name="阅读",start=start,rules=listOf(HabitRule(start)))
        HabitStore(c).save(h,start)
        val store=ReviewStore(c);store.settings.edit().putString("since",start.toString()).commit()
        val specs=store.specs(c);val week=specs.last { it.kind=="week" }
        store.save(week.id,ReviewReport("done",answer="这一周已记录阅读"))
        store.save(specs.first { it.kind=="cycle" }.id,ReviewReport("done",answer="这一轮21天阅读总结"))
        launch();tap("21 天");assertFalse(device.hasObject(By.desc("变化")));tap("周总结")
        assertTrue(device.hasObject(By.text("这一周已记录阅读")))
        assertFalse(device.hasObject(By.text("打开记录助手")))
        assertFalse(device.hasObject(By.text("最近的作息")))
        assertFalse(device.hasObject(By.text("例外也值得被看见")))
        capture("weekly")
        tap("21天总结");assertTrue(device.hasObject(By.text("这一轮21天阅读总结")))
        assertFalse(device.hasObject(By.text("这一周已记录阅读")));capture("cycle")
        tap("作息进度");assertTrue(device.hasObject(By.text("设置我的 21 天")))
        tap("21天总结");assertTrue(device.hasObject(By.text("这一轮21天阅读总结")))
        tap("记录助手");assertNotNull(device.findObject(By.clazz("android.widget.EditText")))
        assertTrue(device.hasObject(By.text("今天")));capture("assistant")
    }
    @Test fun journeyKeepsSelectedWeekAcrossSummariesAndRotation() {
        Store(c).createPlan(Plan(LocalDate.now()))
        val before=Store(c).export()
        launch();tap("21 天");tap("第 3 周")
        assertTrue(device.hasObject(By.descStartsWith("第 21 天，")))
        tap("周总结");tap("作息进度")
        assertTrue(device.wait(Until.hasObject(By.descStartsWith("第 21 天，")),5000))
        tap("21天总结")
        try {
            device.setOrientationLeft();device.waitForIdle()
            assertTrue(device.wait(Until.hasObject(By.text("还没有21天总结")),5000))
            capture("merged-landscape")
        } finally { device.setOrientationNatural();device.unfreezeRotation() }
        tap("习惯");tap("21 天")
        assertTrue(device.wait(Until.hasObject(By.text("还没有21天总结")),5000))
        tap("作息进度");capture("merged-progress")
        assertTrue(device.hasObject(By.descStartsWith("第 21 天，")))
        assertEquals(before,Store(c).export())
    }

}
