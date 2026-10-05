package com.twentyone.rhythm

import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class InteropSettingsTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val c=instrument.targetContext
    private val device=UiDevice.getInstance(instrument)
    private fun refresh(){device.waitForIdle();instrument.uiAutomation.clearCache()}
    private fun click(text:String){refresh();assertTrue(device.wait(Until.hasObject(By.text(text)),8000));device.findObject(UiSelector().text(text)).click();refresh()}
    @Test fun keyIsProtectedAndOpeningAccountDoesNotEnableSync() {
        Assume.assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        Assume.assumeFalse(ProjectSync.status(c).enabled)
        val file=File(c.noBackupFilesDir,"account-session.json");val old=file.takeIf{it.exists()}?.readBytes()
        val oldKey=ProjectAccount.knowledgeKey(c)
        val before=Store(c).export()
        val key="synthetic-knowledge-read-key-for-ui-test-0001"
        val directory=File(c.getExternalFilesDir(null),"interop-qa");directory.mkdirs()
        try {
            ProjectAccount.saveKnowledgeKey(c,key)
            assertEquals(key,ProjectAccount.knowledgeKey(c))
            assertFalse(File(c.noBackupFilesDir,"account-knowledge.json").readText().contains(key))
            assertFalse(Store(c).export().contains(key))
            val write=ProjectAccount::class.java.getDeclaredMethod("write",android.content.Context::class.java,String::class.java,JSONObject::class.java).apply{isAccessible=true}
            write.invoke(ProjectAccount,c,"session",JSONObject().put("userId",UUID.randomUUID().toString()).put("email","ui@example.invalid").put("accessToken","synthetic-never-sent").put("refreshToken","synthetic-never-sent").put("expiresAt",System.currentTimeMillis()+3600000))
            c.startActivity(c.packageManager.getLaunchIntentForPackage(c.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            click("设置");click("三端互通")
            assertTrue(device.wait(Until.hasObject(By.text("替换知识库密钥")),5000))
            device.dumpWindowHierarchy(File(directory,"knowledge.xml"));assertTrue(File(directory,"knowledge.xml").readText().contains("password=\"true\""))
            device.takeScreenshot(File(directory,"knowledge.png"))
            device.pressBack();refresh();click("账号")
            val scroll=UiScrollable(UiSelector().scrollable(true))
            scroll.scrollTextIntoView("检查并开启同步");refresh()
            assertTrue(device.hasObject(By.text("检查并开启同步")))
            device.takeScreenshot(File(directory,"sync-settings.png"))
            assertFalse(ProjectSync.status(c).enabled)
            assertEquals(before,Store(c).export())
        }finally{ProjectAccount.saveKnowledgeKey(c,oldKey);if(old==null)file.delete()else file.writeBytes(old)}
    }
}
