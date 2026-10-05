package com.twentyone.rhythm

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate

class CoachChatTest {
    private val c=InstrumentationRegistry.getInstrumentation().targetContext
    private fun awaitVoicePreference(expected:Boolean) {
        val deadline=android.os.SystemClock.uptimeMillis()+5000
        while(ProjectPreferences.get(c,"coach_voice").getBoolean("keyboard",!expected)!=expected && android.os.SystemClock.uptimeMillis()<deadline) Thread.sleep(50)
        assertEquals(expected,ProjectPreferences.get(c,"coach_voice").getBoolean("keyboard",!expected))
    }
    @Before fun setup() { ProjectPreferences.get(c,"coach_voice").edit().clear().commit() }
    @After fun cleanup() {
        ProjectPreferences.get(c,"coach_chat").edit().clear().commit()
        ProjectPreferences.get(c,"coach_voice").edit().clear().commit()
    }
    @Test fun voiceReturnsEditableTextAndCancelledOrEmptyInputKeepsDraft() {
        val intent=CoachChat.voiceIntent()
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH,intent.action)
        assertEquals("zh-CN",intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertNull(CoachChat.voiceText("原来的话",null,2000))
        assertNull(CoachChat.voiceText("原来的话",Intent(),2000))
        val result=Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS,arrayListOf("今天七点起床","其他候选"))
        assertEquals("原来的话 今天七点起床",CoachChat.voiceText("原来的话",result,2000))
        assertEquals(300,CoachChat.voiceText("字".repeat(295),result,300)!!.length)
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
    @Test fun voiceCancelKeepsComposerAndRotationKeepsInputReachable() {
        ProjectPreferences.get(c,"coach_voice").edit().putBoolean("keyboard",false).commit()
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val before=Store(c).export()
        c.startActivity(Intent(c,CoachActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val editor=device.wait(Until.findObject(By.clazz("android.widget.EditText")),10000)
        assertNotNull(editor);editor.click();editor.text="还没发送的想法";Thread.sleep(300)
        if(device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) device.pressBack()
        device.waitForIdle();device.findObject(By.desc("语音输入")).click();Thread.sleep(1200)
        if(device.currentPackageName!=c.packageName) device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.text("还没发送的想法")),7000))
        assertTrue(CoachChat.load(c).isEmpty());assertEquals(before,Store(c).export())
        device.wait(Until.findObject(By.text("改用输入法语音")),5000).click();device.waitForIdle()
        awaitVoicePreference(true)
        assertTrue(device.hasObject(By.text("还没发送的想法")))
        if(device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) device.pressBack()
        try {
            device.setOrientationLeft();device.waitForIdle()
            val input=device.wait(Until.findObject(By.clazz("android.widget.EditText")),7000)
            assertEquals("还没发送的想法",input.text)
            input.click();device.waitForIdle()
            val send=device.wait(Until.findObject(By.desc("发送消息")),5000)
            assertNotNull(send);assertTrue(send.visibleBounds.height()>0)
            assertTrue(device.findObject(By.clazz("android.widget.EditText")).visibleBounds.height()>=50*c.resources.displayMetrics.density)
            device.takeScreenshot(java.io.File(c.filesDir,"0.6.0-chat-landscape-keyboard.png"))
        } finally { device.pressHome();device.setOrientationNatural();device.unfreezeRotation() }
    }
    @Test fun systemVoiceErrorsAreNotReportedAsUserCancellation() {
        assertTrue(CoachChat.voiceStatus(RecognizerIntent.RESULT_CLIENT_ERROR,false).contains("无法处理请求（2）"))
        assertTrue(CoachChat.voiceStatus(RecognizerIntent.RESULT_NETWORK_ERROR,false).contains("网络"))
        assertTrue(CoachChat.voiceStatus(RecognizerIntent.RESULT_SERVER_ERROR,false).contains("服务端"))
        assertTrue(CoachChat.voiceStatus(RecognizerIntent.RESULT_AUDIO_ERROR,false).contains("声音"))
        assertTrue(CoachChat.voiceStatus(0,false).contains("原来的文字保留"))
        assertTrue(CoachChat.voiceStatus(-1,false).contains("没有返回文字"))
        assertTrue(CoachChat.voiceStatus(-1,true).contains("核对后点击发送"))
        assertTrue(CoachChat.voiceStatus(99,false).contains("（99）"))
        assertFalse(CoachChat.voiceStatus(2,false).contains("已取消"))
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
        assertTrue(ProjectPreferences.get(c,"coach_voice").getBoolean("keyboard",false))
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
        val option=device.wait(Until.findObject(By.desc("优先使用输入法语音")),5000)
        assertTrue(option.isChecked);option.click();device.waitForIdle()
        awaitVoicePreference(false)
        device.pressBack();device.pressBack();launch()
        val restored=device.wait(Until.findObject(By.desc("优先使用输入法语音")),5000)
        assertFalse(restored.isChecked);restored.click();device.waitForIdle()
        awaitVoicePreference(true);device.pressHome()
    }
}
