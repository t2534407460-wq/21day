package com.twentyone.rhythm

import android.os.Build
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.time.*

class RecordHistoryIntegrationTest {
    private val c=InstrumentationRegistry.getInstrumentation().targetContext
    private val s=Store(c);private val hs=HabitStore(c)
    @Before fun setup() {
        Assume.assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        s.prefs.edit().clear().commit()
    }
    @After fun cleanup(){s.prefs.edit().clear().commit()}
    @Test fun countTimerDailyAndCorrectionRemainInPersistentHistoryAndAssistantInput() {
        val day=LocalDate.now();val at=day.atTime(16,0).epoch()
        val count=Habit(name="控烟",mode=HabitMode.AT_MOST,unit="支",rules=listOf(HabitRule(day,target=10)))
        val timer=Habit(name="阅读",mode=HabitMode.AT_LEAST,unit="分钟",rules=listOf(HabitRule(day,target=20)))
        val daily=Habit(name="每日整理",rules=listOf(HabitRule(day)))
        hs.save(count);hs.save(timer);hs.save(daily)
        hs.count(count.id);hs.count(count.id);hs.record(HabitEntry(count.id,day,1))
        hs.startTimer(timer.id,at);hs.finishTimer(timer.id,at+65000)
        hs.confirmDay(daily.id)
        val events=Store(c).events()
        assertEquals(2,events.count { it.kind=="次数打卡" })
        assertEquals(1,events.count { it.kind=="更正习惯记录" })
        assertEquals(1,events.count { it.kind=="每日确认" })
        assertTrue(events.any { it.kind=="开始计时" });assertTrue(events.any { it.kind=="结束计时" })
        assertEquals(1,hs.entries().first { it.habitId==count.id }.value)
        assertEquals(5,hs.entries().first { it.habitId==timer.id }.remainderSeconds)
        val facts=RecordHistory.facts(s.logs(),s.plan,hs.habits(),hs.entries(),events,day,1)
        val request=CoachChat.requestMessages(listOf(CoachMessage("user","回顾我的操作")),facts).toString()
        assertTrue(request.contains("控烟"));assertTrue(request.contains("阅读"));assertTrue(request.contains("每日整理"));assertTrue(request.contains("结束计时"))
        assertTrue(request.contains("记录助手"))
    }
    @Test fun eventsBeyond500PersistExportAndRestoreWithoutChangingLegacyStatuses() {
        val array=JSONArray()
        repeat(500) { array.put(JSONObject().put("time",it+1).put("kind","旧操作").put("detail","第 $it 次")) }
        s.prefs.edit().putString("events",array.toString()).commit()
        s.event("临时放行","抖音 · 5 分钟")
        assertEquals(501,Store(c).events().size)
        s.createPlan(Plan(LocalDate.now().plusDays(2)))
        s.saveLog(DayLog(LocalDate.now().toString(),status="完成",note="旧备注"))
        val backup=s.export();s.prefs.edit().putString("events","[]").commit();s.import(backup)
        assertEquals(502,s.events().size);assertEquals("旧操作",s.events().first().kind)
        assertEquals("完成",s.log(LocalDate.now().toString()).status)
        val before=s.export()
        assertTrue(runCatching { s.import(JSONObject(before).put("events",JSONObject()).toString()) }.isFailure)
        assertEquals(before,s.export())
    }
    @Test fun liveStatusUsesNextMorningAndRestRulesAfterRecreation() {
        val day=LocalDate.parse("2026-10-09");s.createPlan(Plan(day))
        s.saveLog(DayLog(day.toString(),status="完成",sleepiness="很困了",bedMood="放松",bedReason="无",bedtimeCheckedAt=100,verifiedAt=101))
        assertEquals("部分完成",Store(c).nightStatus(day))
        s.saveLog(DayLog(day.plusDays(1).toString(),verifiedAt=200))
        assertEquals("已完成",Store(c).nightStatus(day))
        s.updateLog(day.toString()) { it.copy(bedtimeCheckedAt=0) }
        assertEquals("部分完成",Store(c).nightStatus(day))
        val rest=LocalDate.parse("2026-10-06")
        s.saveLog(DayLog(rest.toString(),sleepiness="很困了",bedMood="放松",bedReason="无",bedtimeCheckedAt=100))
        assertEquals("已完成",Store(c).nightStatus(rest))
    }
    @Test fun historyIsReadableWithoutKeyAndCanSelectChatScope() {
        val day=LocalDate.now();val old=day.minusDays(20)
        s.createPlan(Plan(old))
        s.saveLog(DayLog(old.toString(),sleepiness="很困了",bedMood="放松",bedReason="无",bedtimeCheckedAt=old.atTime(23,0).epoch()))
        s.event("临时放行","抖音 · 5 分钟")
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        c.startActivity(Intent(c,CoachActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("记录助手")),10000))
        device.findObject(By.desc("历史记录")).click()
        assertTrue(device.wait(Until.hasObject(By.text("历史记录")),5000))
        assertTrue(device.hasObject(By.textContains("抖音 · 5 分钟")))
        device.findObject(By.text("选择聊天日期范围")).click()
        assertTrue(device.wait(Until.hasObject(By.text("开始日期")),5000))
        device.findObject(By.text("按此范围聊天")).click()
        assertTrue(device.wait(Until.gone(By.text("历史记录")),5000))
        assertTrue(device.hasObject(By.textContains("聊天现在使用")))
        device.takeScreenshot(java.io.File(c.getExternalFilesDir(null),"record-assistant.png"))
        device.pressHome()
    }
}
