package com.twentyone.rhythm

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.net.*
import java.util.concurrent.atomic.AtomicInteger

class AutomaticUpdateTest {
    private val c=InstrumentationRegistry.getInstrumentation().targetContext
    private val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val prefs=c.getSharedPreferences("updates",0)
    companion object {
        private val requests=AtomicInteger(0)
        @Volatile private var version="9.9.9"
        @Volatile private var status=200
        @Volatile private var responseDelay=0L
        @BeforeClass @JvmStatic fun mock() {
            URL.setURLStreamHandlerFactory { protocol -> if(protocol!="https") null else object:URLStreamHandler() {
                override fun openConnection(url:URL):URLConnection {
                    check(url.host=="api.github.com");requests.incrementAndGet()
                    return object:HttpURLConnection(url) {
                        override fun connect() {};override fun disconnect() {};override fun usingProxy()=false
                        override fun getResponseCode():Int { Thread.sleep(responseDelay);return status }
                        override fun getInputStream()=ByteArrayInputStream(JSONObject().put("tag_name","v$version").put("body","合成更新说明").put("assets",JSONArray().put(JSONObject().put("name","21day-$version-debug.apk").put("browser_download_url","https://github.com/${AppUpdates.REPOSITORY}/releases/download/v$version/21day-$version-debug.apk").put("size",100).put("digest","sha256:"+"a".repeat(64)))).toString().toByteArray())
                    }
                }
            } }
        }
    }
    @Before fun setup() { device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");device.pressHome();Thread.sleep(500);prefs.edit().clear().commit();requests.set(0);version="9.9.9";status=200;responseDelay=0;Store(c).prefs.edit().clear().commit() }
    @After fun cleanup() { device.pressHome();prefs.edit().clear().commit() }
    private fun awaitRequests(count:Int) { val end=System.currentTimeMillis()+8000;while(requests.get()<count && System.currentTimeMillis()<end) Thread.sleep(50);assertTrue("Expected $count checks, got ${requests.get()}",requests.get()>=count) }
    private fun prompt()=device.wait(Until.hasObject(By.text("发现新版本 9.9.9")),8000)
    @Test fun previousCheckDoesNotBlockOpeningPrompt() {
        prefs.edit().putLong("auto_checked_at",System.currentTimeMillis()).commit()
        launch();assertTrue(prompt())
    }
    private fun launch() { c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
    @Test fun newVersionPromptsAndDismissalDoesNotDownload() {
        launch();assertTrue(prompt());device.findObject(By.text("稍后")).click()
        assertFalse(prefs.contains("download_id"));assertTrue(device.hasObject(By.text("今日打卡")))
        device.pressHome();launch();assertTrue(prompt());awaitRequests(2);assertFalse(prefs.contains("download_id"))
        device.takeScreenshot(java.io.File(c.filesDir,"opening-update-prompt.png"))
    }
    @Test fun failedCheckRetriesOnNextOpening() {
        status=503;launch();awaitRequests(1);Thread.sleep(800);assertFalse(device.hasObject(By.textStartsWith("发现新版本")))
        device.pressHome();status=200;launch();assertTrue(prompt());awaitRequests(2)
    }
    @Test fun releasePublishedAfterEarlierCheckIsFoundOnResume() {
        version=BuildConfig.VERSION_NAME;launch();awaitRequests(1);Thread.sleep(800)
        device.pressHome();version="9.9.9"
        c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(prompt());awaitRequests(2)
    }
    @Test fun checkInterruptedByBackgroundRetriesWhenResumed() {
        responseDelay=1800;launch();awaitRequests(1);device.pressHome();Thread.sleep(2200)
        assertFalse(device.hasObject(By.textStartsWith("发现新版本")))
        responseDelay=0;c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(prompt());awaitRequests(2)
    }
    @Test fun noUpdateIsSilentAndManualCheckStillWorks() {
        version=BuildConfig.VERSION_NAME;launch();assertTrue(device.wait(Until.hasObject(By.text("今日打卡")),8000));device.waitForIdle()
        assertFalse(device.hasObject(By.textStartsWith("发现新版本")));assertFalse(device.hasObject(By.text("正在处理…")))
        device.findObject(By.text("设置")).click();assertTrue(device.wait(Until.hasObject(By.text("数据与更新")),5000));device.findObject(By.text("数据与更新")).click()
        assertTrue(device.wait(Until.hasObject(By.text("检查更新")),5000));device.findObject(By.text("检查更新")).click()
        assertTrue(device.wait(Until.hasObject(By.text("已经是最新版本 ${BuildConfig.VERSION_NAME}")),8000));assertEquals(2,requests.get())
    }
}
