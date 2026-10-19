package com.twentyone.rhythm

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.net.*
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class WakeRecoveryTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val c=instrument.targetContext
    private val device=UiDevice.getInstance(instrument)
    private val s by lazy { Store(c) }
    companion object {
        private var installed=false
        private var code=204
        private var onConfirm:(()->Unit)?=null
        private var body=JSONObject()
        private var bearer:String?=null
        private const val qr="rhythm://emergency/00000000-0000-0000-0000-000000000001/test"
        private fun mock() {
            if(installed)return
            URL.setURLStreamHandlerFactory { protocol -> if(protocol!="https")null else object:URLStreamHandler() {
                override fun openConnection(url:URL):URLConnection=object:HttpsURLConnection(url) {
                    private val out=ByteArrayOutputStream()
                    override fun connect() {};override fun disconnect() {};override fun usingProxy()=false
                    override fun getCipherSuite()="test";override fun getLocalCertificates():Array<Certificate>?=null
                    override fun getServerCertificates():Array<Certificate> = emptyArray()
                    override fun getPeerPrincipal():Principal?=null;override fun getLocalPrincipal():Principal?=null
                    override fun getOutputStream():OutputStream=out
                    override fun getResponseCode():Int {
                        body=JSONObject(out.toString("UTF-8"));bearer=getRequestProperty("Authorization")
                        if(url.path.endsWith("/confirm"))onConfirm?.invoke()
                        return if(url.path.endsWith("/request"))200 else code
                    }
                    override fun getInputStream():InputStream=ByteArrayInputStream(if(url.path.endsWith("/request")) "{\"challengeId\":\"ticket\",\"expiresAt\":\"2026-10-19T12:00:00Z\"}".toByteArray() else byteArrayOf())
                    override fun getErrorStream():InputStream=ByteArrayInputStream("{\"message\":\"二维码无效或过期\"}".toByteArray())
                }
            } }
            installed=true
        }
    }
    @Before fun setup() {
        Assume.assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"));mock();code=204;onConfirm=null
        s.prefs.edit().clear().commit()
        val write=ProjectAccount::class.java.getDeclaredMethod("write",Context::class.java,String::class.java,JSONObject::class.java).apply {isAccessible=true}
        write.invoke(ProjectAccount,c,"session",JSONObject().put("email","recovery@example.test").put("userId",java.util.UUID.randomUUID().toString()).put("expiresAt",System.currentTimeMillis()+3600_000).put("accessToken","test-token"))
        s.wake=WakeSession(java.time.LocalDate.now().toString(),WakePhase.RINGING,System.currentTimeMillis()+60_000,1,id=java.util.UUID.randomUUID().toString())
    }
    @After fun cleanup() {instrument.runOnMainSync {Alarms.stop(c,"测试结束")};File(c.noBackupFilesDir,"account-session.json").delete();device.pressHome()}
    @Test fun mailRequestContainsOnlyDeviceAndSessionAndDoesNotStopAlarm()= runBlocking {
        assertEquals("ticket",ProjectAccount.requestWakeRecovery(c).id)
        assertEquals(s.wake.id,body.getString("sessionId"));assertEquals(ProjectAccount.device(c),body.getString("deviceId"))
        assertFalse(body.has("email"));assertFalse(body.has("qr"));assertEquals("Bearer test-token",bearer);assertTrue(s.wake.active)
    }
    @Test fun validEmailQrStopsOnlyCurrentTaskWithoutRecordingWakeCompletion()= runBlocking {
        assertTrue(Alarms.emergencyStop(c,qr));assertEquals(WakePhase.STOPPED,s.wake.phase)
        assertEquals(0L,s.log(s.wake.day).verifiedAt);assertTrue(s.events().any {it.detail.contains("邮箱")})
    }
    @Test fun rejectedQrAndAResponseForAnOldTaskCannotStopRinging()= runBlocking {
        code=401
        assertTrue(runCatching {Alarms.emergencyStop(c,qr)}.isFailure);assertTrue(s.wake.ringing)
        assertTrue(runCatching {Alarms.emergencyStop(c,"rhythm://wake/ordinary")}.isFailure);assertTrue(s.wake.ringing)
        code=204;onConfirm={s.wake=s.wake.copy(id=java.util.UUID.randomUUID().toString())}
        assertFalse(Alarms.emergencyStop(c,qr));assertTrue(s.wake.ringing)
    }
    @Test fun emergencyDialogOffersEmailScanAndImageWithoutAWaitBypass() {
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")
        c.startActivity(Intent(c,WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val label="紧急停止本次验证"
        if(device.wait(Until.findObject(By.text(label)),3000)==null) UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(label)
        device.findObject(By.text(label)).click()
        assertTrue(device.wait(Until.hasObject(By.text("邮箱紧急验证")),5000))
        assertTrue(device.hasObject(By.text("发送紧急验证二维码到邮箱")));assertTrue(device.hasObject(By.text("扫描邮件二维码")))
        assertTrue(device.hasObject(By.text("选择邮件二维码图片")));assertFalse(device.hasObject(By.desc("持续长按 5 秒确认退出")))
        device.takeScreenshot(File(c.getExternalFilesDir(null),"emergency-email-signed-in-0.12.5.png"))
        assertTrue(s.wake.active)
    }
    @Test fun savedEmailPngDecodesAndInvalidImageCannotVerify() {
        val image=File(c.getExternalFilesDir("updates"),"test-mail-qr.png")
        val bitmap=qrBitmap(qr)
        image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        val uri=androidx.core.content.FileProvider.getUriForFile(c,c.packageName+".updates",image)
        assertEquals(qr,readRecoveryQr(c,uri))
        image.writeText("not an image")
        assertTrue(runCatching {readRecoveryQr(c,uri)}.isFailure);assertTrue(s.wake.ringing);image.delete()
    }
}
