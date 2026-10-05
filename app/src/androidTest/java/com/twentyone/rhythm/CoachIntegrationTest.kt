package com.twentyone.rhythm

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class CoachIntegrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val today=LocalDate.now()
    private val store=Store(context)
    @Before fun setup(){store.prefs.edit().clear().commit()}
    @After fun cleanup(){UiDevice.getInstance(instrumentation).pressHome();store.prefs.edit().clear().commit()}
    @Test fun confirmPreservesStatusVerificationAndAppendsNotes() {
        val original=DayLog(today.toString(),"完成","23:00","07:00","不错","无","原备注",1234)
        store.saveLog(original)
        val drafts=CoachDrafts.parse("""{"changes":[{"day":"$today","rise":"07:20","note":"有点疲惫"}],"question":""}""",today)
        store.applyCoachDrafts(drafts,mapOf(original.day to original))
        val result=store.log(original.day)
        assertEquals("07:20",result.rise);assertEquals("23:00",result.bed)
        assertEquals("完成",result.status);assertEquals(1234L,result.verifiedAt)
        assertEquals("原备注\n补记：有点疲惫",result.note)
    }
    @Test fun stalePreviewCannotOverwriteNewWakeTimeAndWritesAreAtomic() {
        val yesterday=today.minusDays(1).toString();val day=today.toString()
        val old=DayLog(day,rise="07:00");store.saveLog(old)
        val drafts=listOf(CoachDraft(yesterday,bed="23:20"),CoachDraft(day,rise="07:30"))
        store.updateLog(day){it.copy(rise="07:05",verifiedAt=999)}
        try { store.applyCoachDrafts(drafts,mapOf(day to old,yesterday to DayLog(yesterday)));fail() }catch(_:IllegalArgumentException){}
        assertEquals("",store.log(yesterday).bed);assertEquals("07:05",store.log(day).rise)
        assertEquals(999L,store.log(day).verifiedAt)
    }
    @Test fun unrelatedConcurrentVerificationIsKept() {
        val original=DayLog(today.toString());store.saveLog(original)
        store.updateLog(original.day){it.copy(verifiedAt=888,rise="07:00")}
        store.applyCoachDrafts(listOf(CoachDraft(original.day,energy="疲惫")),mapOf(original.day to original))
        assertEquals(888L,store.log(original.day).verifiedAt);assertEquals("07:00",store.log(original.day).rise)
    }
    @Test fun invalidModelOutputCannotWriteFutureDatesOrProtectedFields() {
        val invalid=listOf(
            """{"changes":[{"day":"${today.plusDays(1)}","rise":"07:00"}]}""",
            """{"changes":[{"day":"$today","verifiedAt":100}]}""",
            """{"changes":[{"day":"$today","bed":"29:00"}]}""",
            """{"changes":[{"day":"$today","status":"完成"}]}""",
            """{"changes":[{"day":"$today","energy":"诊断"}]}""",
            """{"changes":[],"question":"哪一天？"}""",
            """{"changes":[{"day":"$today","rise":"07:00"},{"day":"$today","rise":"08:00"}]}"""
        )
        invalid.forEach { answer -> assertTrue(runCatching { CoachDrafts.parse(answer,today) }.isFailure) }
        assertTrue(store.logs().isEmpty())
    }
    @Test fun crossedDatesAreReviewedSeparatelyAndBackupStillWorks() {
        val yesterday=today.minusDays(1).toString();val day=today.toString()
        val drafts=CoachDrafts.parse("""{"changes":[{"day":"$yesterday","bed":"23:40"},{"day":"$day","rise":"07:10"}]}""",today)
        assertEquals(2,drafts.size)
        store.createPlan(Plan(today.plusDays(2)))
        store.applyCoachDrafts(drafts,drafts.associate { it.day to store.log(it.day) })
        val backup=store.export();store.prefs.edit().remove("logs").commit();store.import(backup)
        assertEquals("23:40",store.log(yesterday).bed);assertEquals("07:10",store.log(day).rise)
        assertFalse(backup.contains("coach_model"))
    }
    @Test fun assistantPageWorksWithoutKeyAndWithoutMutatingRecords() {
        CloudCoach.removeKey(context)
        store.createPlan(Plan());val before=store.export()
        context.startActivity(Intent(context,CoachActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val device=UiDevice.getInstance(instrumentation)
        assertTrue(device.wait(Until.hasObject(By.text("从今晚的小事聊起。")),15000))
        device.findObject(By.text("今日总结")).click()
        assertTrue(device.wait(Until.hasObject(By.text("助手设置")),5000))
        assertTrue(device.hasObject(By.text("API 与自动复盘设置")))
        assertEquals(before,store.export())
        device.pressBack()
    }
    @Test fun missingKeyFailsWithoutTouchingRecords()=runBlocking {
        CloudCoach.removeKey(context)
        val before=store.export()
        val result=runCatching { CoachInference.generate(context,"test") }
        assertTrue(result.isFailure);assertEquals(before,store.export())
    }
    @Test fun inventedFieldsAreRejectedAgainstTheOriginalSentence() {
        val answer="""{"changes":[{"day":"$today","bed":"07:20","rise":"07:20","reason":"手机"}]}"""
        assertTrue(runCatching { CoachDrafts.parse(answer,today,"今天07:20起床") }.isFailure)
        val note="""{"changes":[{"day":"$today","note":"自创内容"}]}"""
        assertTrue(runCatching { CoachDrafts.parse(note,today,"今天疲惫") }.isFailure)
    }
    @Test fun datesAreResolvedBeforeTheModelAndAmbiguousNightsAreRejected() {
        val inputs=CoachDrafts.split("昨天23:30上床，今天07:10起床，精神疲惫",today,today)
        assertEquals(listOf(today.minusDays(1),today),inputs.map { it.day })
        assertEquals("23:30上床",inputs[0].text);assertEquals("07:10起床，精神疲惫",inputs[1].text)
        assertEquals(listOf(CoachDrafts.Input(today.minusDays(1),"我23:30上床")),CoachDrafts.split("我昨天23:30上床",today,today))
        assertTrue(runCatching { CoachDrafts.split("昨晚一点上床",today,today) }.isFailure)
        assertTrue(runCatching { CoachDrafts.split("明天07:00起床",today,today) }.isFailure)
        assertTrue(runCatching { CoachDrafts.split("上周五07:00起床",today,today) }.isFailure)
    }
}
