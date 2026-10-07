package com.twentyone.rhythm

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate

class CoachChatTest {
    private val c=InstrumentationRegistry.getInstrumentation().targetContext
    private fun awaitVoicePreference(expected:Boolean) {
        val deadline=android.os.SystemClock.uptimeMillis()+5000
        while(ProjectPreferences.get(c,"coach_voice").getBoolean("enabled",!expected)!=expected && android.os.SystemClock.uptimeMillis()<deadline) Thread.sleep(50)
        assertEquals(expected,ProjectPreferences.get(c,"coach_voice").getBoolean("enabled",!expected))
    }
    @Before fun setup() { ProjectPreferences.get(c,"coach_voice").edit().clear().commit() }
    @After fun cleanup() {
        ProjectPreferences.get(c,"coach_chat").edit().clear().commit()
        ProjectPreferences.get(c,"coach_voice").edit().clear().commit()
    }
    @Test fun boundedConversationUsesRecentTurnsAndExcludesDraftsAndOldNotes() {
        val facts=CoachAnalysis.facts(listOf(DayLog(LocalDate.now().toString(),note="private-note")),LocalDate.now(),7)
        val history=(0..19).map { CoachMessage(if(it%2==0) "assistant" else "user","message-$it") }+
            CoachMessage("user","draft-private",context=false)
        val request=CoachChat.requestMessages(history,facts)
        assertEquals(12,request.length()) // system + 11 messages after dropping initial assistant
        assertEquals("user",request.getJSONObject(1).getString("role"))
        assertEquals("message-19",request.getJSONObject(request.length()-1).getString("content"))
        assertFalse(request.toString().contains("private-note"));assertFalse(request.toString().contains("draft-private"))
        assertFalse(request.toString().contains("message-0"))
    }
    @Test fun conversationPersistsSummaryProvenanceAndDoesNotTouchDailyBackup() {
        val before=Store(c).export()
        val messages=(0..64).map { CoachMessage("user","turn $it") }+
            CoachMessage("assistant","summary",days=7,facts="original facts")
        CoachChat.save(c,messages)
        val saved=CoachChat.load(c);assertEquals(60,saved.size);assertEquals(messages.last(),saved.last())
        assertEquals(before,Store(c).export())
    }
    @Test fun defaultVoiceOpensKeyboardWithoutExternalEngineAndKeepsExistingText() {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val before=Store(c).export()
        c.startActivity(Intent(c,CoachActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val editor=device.wait(Until.findObject(By.clazz("android.widget.EditText")),10000)
        editor.click();editor.text="原来的想法";Thread.sleep(300)
        if(device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) device.pressBack()
        device.waitForIdle();device.findObject(By.desc("语音输入")).click();device.waitForIdle()
        assertEquals(c.packageName,device.currentPackageName)
        assertTrue(device.executeShellCommand("dumpsys input_method").contains("mInputShown=true"))
        assertTrue(device.hasObject(By.text("原来的想法")))
        assertTrue(device.wait(Until.hasObject(By.text("请点键盘上的麦克风开始说话；识别后可修改，再点发送。")),5000))
        assertTrue(CoachChat.voiceEnabled(c))
        assertEquals(before,Store(c).export());assertTrue(CoachChat.load(c).isEmpty())
        device.takeScreenshot(java.io.File(c.filesDir,"0.6.1-keyboard-voice.png"));device.pressHome()
    }
    @Test fun preferredVoiceRouteCanBeChangedAndIsKeptAfterReopening() {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun launch() {
            c.startActivity(Intent(c,CoachActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            device.wait(Until.findObject(By.desc("助手设置")),10000).click()
        }
        launch()
        val option=device.wait(Until.findObject(By.desc("启用语音输入")),5000)
        assertTrue(option.isChecked);option.click();device.waitForIdle()
        awaitVoicePreference(false)
        device.pressBack();device.pressBack();launch()
        val restored=device.wait(Until.findObject(By.desc("启用语音输入")),5000)
        assertFalse(restored.isChecked);restored.click();device.waitForIdle()
        awaitVoicePreference(true);device.pressHome()
    }
}
