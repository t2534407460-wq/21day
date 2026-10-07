package com.twentyone.rhythm

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.net.*
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue

// Fake HTTP responses only: these tests never contact or charge a DeepSeek account.
@RunWith(AndroidJUnit4::class)
class CloudCoachTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val c=instrumentation.targetContext
    private val store=Store(c)
    private val device=UiDevice.getInstance(instrumentation)
    private val key="sk-test-only-not-a-real-key"
    private val keyFile get()=File(c.noBackupFilesDir,"deepseek-key.json")
    companion object {
        private val responses=ConcurrentLinkedQueue<FakeConnection>()
        @BeforeClass @JvmStatic fun interceptOfficialEndpoint() {
            URL.setURLStreamHandlerFactory { protocol ->
                if(protocol!="https") null else object:URLStreamHandler() {
                    override fun openConnection(url:URL):URLConnection {
                        check(url.toString()==CloudCoach.endpoint) { "Unexpected URL in cloud tests" }
                        return responses.poll() ?: error("No mock response queued")
                    }
                }
            }
        }
    }
    @Before fun setup() {
        CloudCoach.removeKey(c);store.prefs.edit().clear().commit()
        c.getSharedPreferences("cloud_coach",0).edit().clear().commit()
        ProjectPreferences.get(c,"coach_reports").edit().clear().commit()
        ProjectPreferences.get(c,"coach_chat").edit().clear().commit()
        responses.clear()
    }
    @After fun cleanup() {
        device.pressHome();CloudCoach.removeKey(c);responses.clear()
        store.prefs.edit().clear().commit()
        ProjectPreferences.get(c,"coach_chat").edit().clear().commit()
    }
    private fun response(text:String,finish:String="stop")=JSONObject().put("choices",JSONArray().put(
        JSONObject().put("finish_reason",finish).put("message",JSONObject().put("role","assistant").put("content",text)))).toString()
    @Test fun keyIsEncryptedAtomicReplaceableAndExcludedFromExport() {
        store.createPlan(Plan());val before=store.export()
        CloudCoach.saveKey(c,key)
        assertTrue(CloudCoach.configured(c));assertEquals(key,CloudCoach.readKey(c))
        val first=keyFile.readText();assertFalse(first.contains(key));assertFalse(store.export().contains(key))
        CloudCoach.saveKey(c,key);assertNotEquals(first,keyFile.readText())
        assertTrue(runCatching { CloudCoach.saveKey(c,"bad key\n") }.isFailure)
        assertEquals(key,CloudCoach.readKey(c))
        keyFile.writeText("corrupt")
        assertTrue(runCatching { CloudCoach.readKey(c) }.exceptionOrNull()?.message?.contains("重新填写")==true)
        CloudCoach.saveKey(c,key);CloudCoach.removeKey(c)
        assertFalse(CloudCoach.configured(c));assertEquals(before,store.export())
    }
    @Test fun oldModelAndCacheCleanupIsIdempotentAndKeepsRecordsReportsAndKey() {
        val model=File(c.noBackupFilesDir,"coach/qwen25.litertlm").apply { parentFile!!.mkdirs();writeText("old test model") }
        val cache=File(c.noBackupFilesDir,"coach/runtime/ready").apply { parentFile!!.mkdirs();writeText("old test cache") }
        val download=File(c.getExternalFilesDir("coach"),"model.download").apply { writeText("partial") }
        c.getSharedPreferences("coach_model",0).edit().putString("hash","old").commit()
        ProjectPreferences.get(c,"coach_reports").edit().putString("last_7","old report").commit()
        store.saveLog(DayLog(LocalDate.now().toString(),"完成",verifiedAt=123));val before=store.export()
        CloudCoach.saveKey(c,key);CloudCoach.clearLegacyModel(c);CloudCoach.clearLegacyModel(c)
        assertFalse(model.exists());assertFalse(cache.exists());assertFalse(download.exists())
        assertTrue(c.getSharedPreferences("coach_model",0).all.isEmpty())
        assertEquals("old report",ProjectPreferences.get(c,"coach_reports").getString("last_7",null))
        assertEquals(before,store.export());assertEquals(key,CloudCoach.readKey(c))
    }
    @Test fun requestUsesFixedModelAndAuthHeaderAndJsonModeWithoutSendingOldNotes()=runBlocking {
        val day=LocalDate.now()
        val facts=CoachAnalysis.facts(listOf(DayLog(day.toString(),rise="07:20",note="private-note-should-not-upload")),day,7)
        val connection=FakeConnection(response("观察：有起床记录。建议：按时补记。缺失：没有睡眠数据。"))
        val result=CoachInference.request(connection,key,CoachAnalysis.summaryPrompt(facts))
        assertTrue(result.contains("观察"));assertEquals("POST",connection.requestMethod)
        assertEquals("Bearer $key",connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects);assertTrue(connection.closed)
        val body=JSONObject(connection.sent.toString("UTF-8"))
        assertEquals("deepseek-flash",body.getString("model"));assertFalse(body.getBoolean("stream"))
        assertEquals("disabled",body.getJSONObject("thinking").getString("type"))
        assertEquals(1024,body.getInt("max_tokens"));assertEquals(1,body.getJSONArray("messages").length())
        assertFalse(body.toString().contains(key));assertFalse(body.toString().contains("private-note-should-not-upload"))
        val json=FakeConnection(response("{\"changes\":[]}"))
        CoachInference.request(json,key,CoachDrafts.prompt("今天疲惫",day),true)
        assertEquals("json_object",JSONObject(json.sent.toString("UTF-8")).getJSONObject("response_format").getString("type"))
    }
    @Test fun authBalanceRateLimitRedirectAndServerErrorsAreSafeAndDoNotChangeLogs()=runBlocking {
        val before=store.export()
        mapOf(401 to "密钥",403 to "密钥",402 to "余额",429 to "频繁",503 to "不可用",302 to "重定向",422 to "422").forEach { (code,word)->
            val connection=FakeConnection("secret-server-body-$key",code)
            val error=runCatching { CoachInference.request(connection,key,"test") }.exceptionOrNull()
            assertTrue(error?.message?.contains(word)==true);assertFalse(error!!.message!!.contains(key))
            assertTrue(connection.closed);assertFalse(connection.bodyRead)
        }
        assertEquals(before,store.export())
    }
    @Test fun partialMalformedEmptyOrOversizeResponsesAreRejected()=runBlocking {
        val invalid=listOf(response("partial","length"),response("filtered","content_filter"),"not json",response(""),response("x".repeat(8001)),"x".repeat(131073))
        invalid.forEach { body ->
            val connection=FakeConnection(body)
            assertTrue(runCatching { CoachInference.request(connection,key,"test") }.isFailure)
            assertTrue(connection.closed)
        }
        assertTrue(store.logs().isEmpty())
    }
    @Test fun cancellingInFlightRequestDisconnectsWithoutReturningAnAnswer()=runBlocking {
        val connection=FakeConnection(response("late answer"),wait=true)
        var answer:String?=null
        val job=launch { answer=CoachInference.request(connection,key,"test") }
        withContext(Dispatchers.IO) { assertTrue(connection.readStarted.await(5,TimeUnit.SECONDS)) }
        job.cancel();withTimeout(3000) { job.join() }
        assertTrue(connection.closed);assertNull(answer)
    }
    @Test fun deadlineDisconnectsAndIoErrorsDoNotExposeNetworkDetails()=runBlocking {
        val waiting=FakeConnection(response("late answer"),wait=true)
        assertTrue(runCatching { withTimeout(200) { CoachInference.request(waiting,key,"test") } }.exceptionOrNull() is TimeoutCancellationException)
        assertTrue(waiting.closed)
        val broken=object:HttpURLConnection(URL(CloudCoach.endpoint)) {
            override fun connect() {}
            override fun usingProxy()=false
            override fun disconnect() {}
            override fun getOutputStream():OutputStream=throw IOException("sensitive network detail $key")
        }
        val error=runCatching { CoachInference.request(broken,key,"test") }.exceptionOrNull()
        assertTrue(error?.message?.contains("检查网络")==true);assertFalse(error!!.message!!.contains(key))
    }
    @Test fun uiSummaryPersistsProvenanceAndMarksChangedFactsWithoutUploadingNotes() {
        CloudCoach.saveKey(c,key);val day=LocalDate.now().toString()
        store.saveLog(DayLog(day,rise="07:20",note="private-note"))
        val habit=Habit(name="控烟",start=LocalDate.now(),mode=HabitMode.AT_MOST,unit="支",rules=listOf(HabitRule(LocalDate.now(),target=10)))
        HabitStore(c).save(habit);HabitStore(c).count(habit.id);store.event("临时放行","抖音 · 5 分钟")
        val connection=FakeConnection(response("观察：记录了起床时间。建议：明天继续补记。缺失：没有睡眠记录。"));responses.add(connection)
        c.startActivity(Intent(c,CoachActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("从记录里，看见每天。")),15000))
        tap("近7天复盘")
        assertTrue(device.wait(Until.hasObject(By.textContains("观察：记录了起床时间")),5000))
        val report=JSONObject(ProjectPreferences.get(c,"coach_reports").getString("last_7","{}")!!)
        assertEquals("DeepSeek / deepseek-flash",report.getString("source"))
        assertFalse(report.has("health"));assertFalse(connection.sent.toString("UTF-8").contains("private-note"))
        assertTrue(connection.sent.toString("UTF-8").contains("控烟"));assertTrue(connection.sent.toString("UTF-8").contains("次数打卡"));assertTrue(connection.sent.toString("UTF-8").contains("抖音 · 5 分钟"))
        store.updateLog(day) { it.copy(rise="08:00") }
        device.waitForIdle();instrumentation.uiAutomation.clearCache()
        assertVisible("记录或日期已有变化，以下是之前的总结，请重新生成。","summary-stale")
        device.takeScreenshot(File(c.filesDir,"cloud-coach-summary.png"))
    }
    @Test fun crossDayCloudExtractionKeepsDatesAndDoesNotWriteBeforeConfirmation()=runBlocking {
        CloudCoach.saveKey(c,key);val day=LocalDate.now()
        responses.add(FakeConnection(response("""{"changes":[{"day":"${day.minusDays(1)}","bed":"23:30"}]}""")))
        responses.add(FakeConnection(response("""{"changes":[{"day":"$day","rise":"07:10"}]}""")))
        val drafts=CoachDrafts.extract(c,"昨天23:30上床，今天07:10起床",day,day)
        assertEquals(listOf(day.minusDays(1).toString(),day.toString()),drafts.map { it.day })
        assertEquals("23:30",drafts[0].bed);assertEquals("07:10",drafts[1].rise);assertTrue(store.logs().isEmpty())
        // A cause from another date must not validate an invented field on this date.
        responses.add(FakeConnection(response("""{"changes":[{"day":"${day.minusDays(1)}","rise":"07:00","reason":"手机"}]}""")))
        assertTrue(runCatching { CoachDrafts.extract(c,"昨天07:00起床，今天刷手机",day,day) }.isFailure)
    }
    @Test fun uiSavesKeyReviewsDraftAndStopsReceivingOnBackground() {
        store.createPlan(Plan())
        val day=LocalDate.now().toString()
        val before=DayLog(day,"部分完成",rise="06:30",note="保留内容",verifiedAt=456);store.saveLog(before)
        c.startActivity(Intent(c,CoachActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("从记录里，看见每天。")),15000))
        device.findObject(By.desc("助手设置")).click();device.waitForIdle()
        tap("API 与自动复盘设置")
        assertTrue(device.wait(Until.hasObject(By.text("DeepSeek API Key")),7000))
        fillInput(key);tap("保存 API Key");tap("知道了")
        tap("记录助手")
        assertEquals(key,CloudCoach.readKey(c))
        assertVisible("DeepSeek · 回顾作息、习惯和操作","return-from-settings")
        assertEquals(key,CloudCoach.readKey(c));assertTrue(responses.isEmpty())
        responses.add(FakeConnection(response("""{"changes":[{"day":"$day","rise":"07:35","energy":"一般"}]}""")))
        tap("补记");fillInput("今天07:35起床，精神一般");send("发送补记")
        assertTrue(device.wait(Until.hasObject(By.text("确认保存补记")),10000))
        assertEquals(before,store.log(day));device.takeScreenshot(File(c.filesDir,"cloud-coach-review.png"))
        tap("21 天");tap("周总结");tap("记录助手")
        assertTrue(device.wait(Until.hasObject(By.text("确认保存补记")),5000))
        assertEquals(before,store.log(day))
        tap("确认保存补记")
        assertVisible("补记已保存，可以在21天页面查看对应日期。","draft-saved")
        assertEquals("07:35",store.log(day).rise);assertEquals(456L,store.log(day).verifiedAt)
        assertEquals("部分完成",store.log(day).status);assertEquals("保留内容",store.log(day).note)
        val waiting=FakeConnection(response("late answer"),wait=true);responses.add(waiting)
        tap("补记");fillInput("今天08:00起床");send("发送补记")
        assertTrue(waiting.readStarted.await(5,TimeUnit.SECONDS));device.pressHome()
        assertTrue(waiting.released.await(5,TimeUnit.SECONDS));assertEquals("07:35",store.log(day).rise)
    }
    private fun assertVisible(text:String,name:String) {
        val visible=device.wait(Until.hasObject(By.text(text)),5000)
        if(!visible) { device.takeScreenshot(File(c.filesDir,"$name.png"));device.dumpWindowHierarchy(File(c.filesDir,"$name.xml")) }
        assertTrue(text,visible)
    }
    private fun tap(text:String) {
        val target=device.wait(Until.findObject(By.text(text)),7000);assertNotNull(text,target);target.click();device.waitForIdle()
    }
    private fun fillInput(value:String) {
        val editor=device.wait(Until.findObject(By.clazz("android.widget.EditText")),5000)
        assertNotNull(editor);editor.click();device.waitForIdle();editor.text=value
        Thread.sleep(300)
        if(device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) device.pressBack()
        device.waitForIdle()
    }
    private fun send(description:String="发送消息") {
        val target=device.wait(Until.findObject(By.desc(description)),5000);assertNotNull(target);target.click();device.waitForIdle()
    }
    private fun launchChat() {
        CloudCoach.saveKey(c,key)
        c.startActivity(Intent(c,CoachActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("记录助手")),10000))
    }
    @Test fun chatFollowsUpWithRealContextPersistsAndCannotWriteDailyLogs() {
        store.saveLog(DayLog(LocalDate.now().toString(),rise="07:20",note="private-existing-note"))
        val before=store.export();launchChat()
        responses.add(FakeConnection(response("可以从睡前半小时收起手机开始。")))
        fillInput("我总是在睡前刷手机，有什么办法？");send()
        assertTrue(device.wait(Until.hasObject(By.text("可以从睡前半小时收起手机开始。")),7000))
        val follow=FakeConnection(response("可以先设一个很小的收尾动作，比如洗漱。"));responses.add(follow)
        fillInput("如果我还是忍不住呢？");send()
        assertTrue(device.wait(Until.hasObject(By.text("可以先设一个很小的收尾动作，比如洗漱。")),7000))
        val body=JSONObject(follow.sent.toString("UTF-8"));val turns=body.getJSONArray("messages")
        assertEquals(4,turns.length());assertEquals("system",turns.getJSONObject(0).getString("role"))
        assertTrue(turns.getJSONObject(0).getString("content").contains("07:20"))
        assertEquals("assistant",turns.getJSONObject(2).getString("role"))
        assertTrue(turns.getJSONObject(2).getString("content").contains("收起手机"))
        assertFalse(body.toString().contains("private-existing-note"));assertEquals(before,store.export())
        assertEquals(4,CoachChat.load(c).size)
        device.takeScreenshot(File(c.filesDir,"0.6.0-chat-conversation.png"))
        device.pressBack();launchChat()
        assertTrue(device.wait(Until.hasObject(By.text("可以先设一个很小的收尾动作，比如洗漱。")),7000))
        assertEquals(4,CoachChat.load(c).size)
    }
    @Test fun failedChatRetryDoesNotDuplicateUserMessageAndLateResponseIsCancelled() {
        launchChat();responses.add(FakeConnection("error",429))
        fillInput("今天很累");send()
        assertTrue(device.wait(Until.hasObject(By.text("重试")),7000));assertEquals(1,CoachChat.load(c).size)
        responses.add(FakeConnection(response("今天先把要做的事减一点。")));tap("重试")
        assertTrue(device.wait(Until.hasObject(By.text("今天先把要做的事减一点。")),7000));assertEquals(2,CoachChat.load(c).size)
        val waiting=FakeConnection(response("late answer"),wait=true);responses.add(waiting)
        fillInput("具体怎么做？");send();assertTrue(waiting.readStarted.await(5,TimeUnit.SECONDS))
        tap("停止生成");assertTrue(waiting.released.await(5,TimeUnit.SECONDS))
        assertEquals(3,CoachChat.load(c).size);assertFalse(CoachChat.load(c).any { it.text=="late answer" })
        assertTrue(store.logs().isEmpty())
    }
    private class FakeConnection(private val body:String,private val code:Int=200,private val wait:Boolean=false):HttpURLConnection(URL("https://api.deepseek.com/chat/completions")) {
        val sent=ByteArrayOutputStream();@Volatile var closed=false;var bodyRead=false
        val readStarted=CountDownLatch(1);val released=CountDownLatch(1)
        override fun connect() {}
        override fun usingProxy()=false
        override fun disconnect() { closed=true;released.countDown() }
        override fun getOutputStream():OutputStream=sent
        override fun getResponseCode()=code
        override fun getInputStream():InputStream {
            bodyRead=true;readStarted.countDown()
            if(wait) { check(released.await(10,TimeUnit.SECONDS));throw IOException("closed") }
            return ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        }
    }
}
