package com.twentyone.rhythm

import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*

class AccountSettingsTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private val directory by lazy { java.io.File(context.getExternalFilesDir(null),"account-qa").apply { mkdirs() } }
    @Before fun setup() {
        Assume.assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        java.io.File(context.noBackupFilesDir,"account-session.json").delete()
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")
    }
    @After fun cleanup() { java.io.File(context.noBackupFilesDir,"account-session.json").delete();device.pressHome() }
    private fun tap(label:String) {
        device.waitForIdle();instrumentation.uiAutomation.clearCache()
        if(!device.hasObject(By.text(label)))UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(label)
        instrumentation.uiAutomation.clearCache()
        val node=device.wait(Until.findObject(By.text(label)),5000);assertNotNull(label,node)
        node.click();android.os.SystemClock.sleep(200);device.waitForIdle();instrumentation.uiAutomation.clearCache()
    }
    private fun open() {
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("设置")),10000));tap("设置");tap("账号")
    }
    private fun screenshot(name:String) { device.takeScreenshot(java.io.File(directory,"$name.png"));device.dumpWindowHierarchy(java.io.File(directory,"$name.xml")) }
    @Test fun accountModesKeepBusinessDataAndPasswordFieldProtected() {
        open();assertTrue(device.wait(Until.hasObject(By.text("欢迎回来")),5000))
        val names=listOf("rhythm","coach_chat","habit_reviews","coach_voice")
        val before=names.associateWith { ProjectPreferences.get(context,it).all.toMap() }
        assertFalse(device.findObject(By.text("登录")).parent.isEnabled)
        val hierarchy=java.io.File(context.cacheDir,"account-fields.xml")
        device.dumpWindowHierarchy(hierarchy)
        assertTrue("Password must be hidden by default",hierarchy.readText().contains("password=\"true\""))
        screenshot("login")
        device.findObject(By.desc("显示密码")).click();device.waitForIdle();instrumentation.uiAutomation.clearCache()
        assertTrue(device.hasObject(By.desc("隐藏密码")))
        tap("注册账号")
        assertTrue(device.hasObject(By.text("创建账号")));assertTrue(device.hasObject(By.text("至少 9 个字符，包含字母、数字和符号。")))
        screenshot("register");tap("返回登录");tap("忘记密码？")
        assertTrue(device.hasObject(By.text("新密码")));screenshot("reset")
        device.pressBack();device.waitForIdle();instrumentation.uiAutomation.clearCache()
        assertTrue(device.hasObject(By.text("欢迎回来")))
        names.forEach { assertEquals("Account settings changed $it",before[it],ProjectPreferences.get(context,it).all.toMap()) }
    }
    @Test fun loggedInAccountDoesNotDisplayLoginFormAndLogoutCanBeCancelled() {
        kotlinx.coroutines.runBlocking { ProjectSync.disable(context) }
        val write=ProjectAccount::class.java.getDeclaredMethod("write",android.content.Context::class.java,String::class.java,org.json.JSONObject::class.java).apply { isAccessible=true }
        write.invoke(ProjectAccount,context,"session",org.json.JSONObject().put("email","ui-check@example.test").put("userId",java.util.UUID.randomUUID().toString()))
        open();assertTrue(device.wait(Until.hasObject(By.text("我的账号")),5000))
        assertTrue(device.hasObject(By.text("ui-check@example.test")))
        assertFalse(device.hasObject(By.text("登录")));assertFalse(device.hasObject(By.text("注册账号")))
        assertFalse(device.hasObject(By.clazz("android.widget.EditText")))
        screenshot("signed-in");tap("退出登录")
        assertTrue(device.wait(Until.hasObject(By.text("退出当前账号？")),5000));tap("取消")
        assertEquals("ui-check@example.test",ProjectAccount.email(context))
    }
    @Test fun invalidEmailIsCaughtLocallyAndKeyboardWorksInLandscape() {
        open();assertTrue(device.wait(Until.hasObject(By.text("欢迎回来")),5000))
        val fields=device.findObjects(By.clazz("android.widget.EditText"))
        assertEquals(2,fields.size)
        fields[0].text="invalid-email";android.os.SystemClock.sleep(200);instrumentation.uiAutomation.clearCache()
        device.findObjects(By.clazz("android.widget.EditText"))[1].text="UiTest123!"
        android.os.SystemClock.sleep(200);instrumentation.uiAutomation.clearCache()
        device.findObjects(By.clazz("android.widget.EditText"))[1].click()
        val keyboardDeadline=System.currentTimeMillis()+5000
        while(!device.executeShellCommand("dumpsys input_method").contains("mInputShown=true") && System.currentTimeMillis()<keyboardDeadline) android.os.SystemClock.sleep(100)
        assertTrue("Keyboard must actually be visible",device.executeShellCommand("dumpsys input_method").contains("mInputShown=true"))
        device.waitForIdle();android.os.SystemClock.sleep(500)
        screenshot("keyboard")
        device.pressBack();tap("登录")
        assertTrue(device.wait(Until.hasObject(By.text("请填写有效的邮箱地址。")),5000))
        val font=device.executeShellCommand("settings get system font_scale").trim()
        try {
            device.executeShellCommand("settings put system font_scale 1.3")
            android.os.SystemClock.sleep(1000);device.setOrientationLeft()
            val rotationDeadline=System.currentTimeMillis()+5000
            while(device.displayWidth<device.displayHeight && System.currentTimeMillis()<rotationDeadline) android.os.SystemClock.sleep(100)
            assertTrue("Landscape must actually be active",device.displayWidth>device.displayHeight)
            android.os.SystemClock.sleep(500);device.waitForIdle();instrumentation.uiAutomation.clearCache()
            tap("忘记密码？")
            assertTrue(device.wait(Until.hasObject(By.text("新密码")),5000))
            screenshot("landscape-large-font")
            tap("返回登录")
        } finally {device.executeShellCommand("settings put system font_scale $font");device.setOrientationNatural();device.unfreezeRotation()}
    }
}
