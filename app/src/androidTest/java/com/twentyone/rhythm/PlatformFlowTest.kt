package com.twentyone.rhythm

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.Configurator
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

/** Requires the documented permissions on a disposable emulator, never a personal phone. */
@RunWith(AndroidJUnit4::class)
class PlatformFlowTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val automation get()=instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val context=instrumentation.targetContext
    private val device by lazy {
        Configurator.getInstance().uiAutomationFlags=android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        UiDevice.getInstance(instrumentation)
    }
    private lateinit var store:Store
    @Before fun setup(){store=Store(context);store.prefs.edit().clear().commit();device.wakeUp()}
    @After fun cleanup(){instrumentation.runOnMainSync { Alarms.stop(context,"平台自动测试结束");Alarms.cancel(context,Alarms.WAKE);Alarms.cancel(context,Alarms.PREP) };store.prefs.edit().clear().commit();device.pressHome()}
    @Test fun generatedQrDecodesToRegisteredPoint() {
        val bitmap=qrBitmap(store.qrToken);val pixels=IntArray(bitmap.width*bitmap.height)
        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val result=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels))))
        assertEquals(store.qrToken,result.text)
    }
    @Test fun scheduledAlarmRingsAndOpensVerificationFromLockScreen() {
        assertTrue("Grant exact alarm permission before this test",Alarms.exactAllowed(context))
        store.createPlan(Plan(LocalDate.now()))
        device.pressHome();device.sleep()
        Alarms.set(context,Alarms.WAKE,System.currentTimeMillis()+2500)
        val shown=device.wait(Until.hasObject(By.text("开始起床 · 安静验证 3 分钟")),20000)
        if(!shown) {
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"alarm-failure.png"))
            val hierarchy=java.io.ByteArrayOutputStream();device.dumpWindowHierarchy(hierarchy)
            fail("Alarm activity missing; phase=${store.wake.phase}, screenOn=${device.isScreenOn}; UI=$hierarchy")
        }
        assertTrue(store.wake.active)
        device.findObject(By.text("开始起床 · 安静验证 3 分钟")).click()
        assertTrue(device.wait(Until.gone(By.text("开始起床 · 安静验证 3 分钟")),3000))
        assertEquals(WakePhase.WALK,store.wake.phase)
        instrumentVerify()
        assertEquals(WakePhase.SECOND_WAIT,store.wake.phase)
        assertFalse(Alarms.verify(context))
        store.wake=store.wake.copy(dueAt=System.currentTimeMillis()-1)
        instrumentVerify()
        assertTrue(device.wait(Until.hasObject(By.text("新的一天，\n从这里开始。")),5000))
        assertEquals(WakePhase.COMPLETE,store.wake.phase)
        assertTrue(store.log(store.wake.day).verifiedAt>0)
    }
    private fun instrumentVerify(){instrumentation.runOnMainSync { assertTrue(Alarms.verify(context)) }}
    @Test fun selectedAppHasThreeImmediatePassesAndNoEmergencyBypass() {
        // Instrumentation restarts the app process; reconnect the service in this disposable emulator.
        automation.executeShellCommand("settings put secure enabled_accessibility_services null").close()
        android.os.SystemClock.sleep(200)
        automation.executeShellCommand("settings put secure enabled_accessibility_services com.twentyone.rhythm/com.twentyone.rhythm.NightAccessibilityService").close()
        val deadline=System.currentTimeMillis()+5000
        while(NightAccessibilityService.instance==null && System.currentTimeMillis()<deadline) android.os.SystemClock.sleep(100)
        val now=LocalDateTime.now();val minute=now.hour*60+now.minute
        val target="com.android.chrome"
        store.createPlan(Plan(LocalDate.now().minusDays(1)))
        store.rules=Rules(bed=(minute+1438)%1440,wake=(minute+20)%1440,blocked=setOf(target))
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)),5000)
        assertNotNull("Enable app accessibility service before this test",NightAccessibilityService.instance)
        device.pressHome()
        val launch=context.packageManager.getLaunchIntentForPackage(target)!!
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        store.prefs.edit().putString("emergency_night",Schedule.night(store.plan!!,store.rules,LocalDateTime.now())!!.date.toString()).apply()
        val directory=java.io.File(context.getExternalFilesDir(null),"usability-qa").apply { mkdirs() }
        repeat(3) { index ->
            assertTrue("Selected app should be intercepted",device.wait(Until.hasObject(By.text("临时使用 5 分钟")),5000))
            assertTrue(device.hasObject(By.text("今日剩余 ${3-index} / 3 次")))
            assertFalse(device.hasObject(By.textContains("紧急结束")))
            if(index==0)device.takeScreenshot(java.io.File(directory,"restriction.png"))
            device.findObject(By.text("临时使用 5 分钟")).click()
            assertTrue(device.wait(Until.gone(By.text("临时使用 5 分钟")),3000))
            assertEquals(2-index,Store(context).passesRemaining())
            assertTrue(store.passUntil-System.currentTimeMillis() in 290_000..300_000)
            // Move only the stored deadline to exercise the actual service expiry tick.
            store.prefs.edit().putLong("pass_until",System.currentTimeMillis()-1).apply()
        }
        assertTrue(device.wait(Until.hasObject(By.text("今日剩余 0 / 3 次")),5000))
        assertFalse(device.hasObject(By.text("临时使用 5 分钟")))
        assertFalse(device.hasObject(By.textContains("紧急结束")))
        device.takeScreenshot(java.io.File(directory,"restriction-exhausted.png"))
        device.findObject(By.text("回到桌面")).click()
        assertTrue(device.wait(Until.gone(By.text("今日剩余 0 / 3 次")),3000))
        val font=device.executeShellCommand("settings get system font_scale").trim()
        try {
            device.executeShellCommand("settings put system font_scale 1.3")
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.text("设置")),5000))
            android.os.SystemClock.sleep(1000);device.setOrientationLeft()
            val rotationDeadline=System.currentTimeMillis()+5000
            while(device.displayWidth<device.displayHeight && System.currentTimeMillis()<rotationDeadline) android.os.SystemClock.sleep(100)
            assertTrue("Landscape must actually be active",device.displayWidth>device.displayHeight)
            device.waitForIdle();android.os.SystemClock.sleep(700)
            instrumentation.runOnMainSync { NightAccessibilityService.instance!!.showBlock("",true) }
            assertTrue(device.wait(Until.hasObject(By.text("廿一 / 效果预览")),5000))
            android.os.SystemClock.sleep(500);device.waitForIdle();automation.clearCache()
            assertFalse(device.hasObject(By.text("临时使用 5 分钟")))
            device.takeScreenshot(java.io.File(directory,"restriction-landscape-before-scroll.png"))
            repeat(5) {
                if(!device.hasObject(By.text("结束预览"))) {
                    device.swipe(device.displayWidth/2,device.displayHeight*4/5,device.displayWidth/2,device.displayHeight/4,30)
                    device.waitForIdle();automation.clearCache()
                }
            }
            automation.clearCache()
            assertTrue(device.wait(Until.hasObject(By.text("结束预览")),3000))
            device.takeScreenshot(java.io.File(directory,"restriction-landscape-large-font.png"))
            device.findObject(By.text("结束预览")).click()
            assertTrue(device.wait(Until.gone(By.text("结束预览")),3000))
            assertEquals(0,store.passesRemaining())
            instrumentation.runOnMainSync { NightAccessibilityService.instance!!.showBlock("",true);NightAccessibilityService.instance!!.onInterrupt() }
        } finally { device.executeShellCommand("settings put system font_scale $font");device.setOrientationNatural();device.unfreezeRotation() }
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("Interrupted preview must not disable blocking",device.wait(Until.hasObject(By.text("今日剩余 0 / 3 次")),5000))
    }
}
