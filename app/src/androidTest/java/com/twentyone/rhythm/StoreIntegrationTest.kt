package com.twentyone.rhythm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class StoreIntegrationTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store
    @Before fun prepare(){store=Store(context);store.prefs.edit().clear().commit()}
    @After fun cleanup(){Alarms.stop(context,"自动测试结束");store.prefs.edit().clear().commit()}
    @Test fun logsAndVerificationPointSurviveRecreation() {
        val token=store.qrToken
        store.saveLog(DayLog("2026-10-03","部分完成","23:30","07:10","一般","手机","明天提前收尾"))
        val other=Store(context)
        assertEquals(token,other.qrToken)
        assertEquals("手机",other.log("2026-10-03").reason)
    }
    @Test fun activeWakeDefersRuleChanges() {
        store.wake=WakeSession(LocalDate.now().toString(),WakePhase.WALK,System.currentTimeMillis()+60000,1)
        val original=store.rules
        assertTrue(store.saveRules(original.copy(secondCheck=false)))
        assertTrue(store.rules.secondCheck)
        assertTrue(store.pendingAt>System.currentTimeMillis())
    }
    @Test fun backupRestoresRecordsButNotSecretsOrActiveSessions() {
        store.createPlan(Plan(LocalDate.now().plusDays(2)))
        store.saveLog(DayLog(LocalDate.now().toString(),"完成"))
        val backup=store.export()
        assertFalse(backup.contains("qr_token"));assertFalse(backup.contains("nfc_id"))
        store.saveLog(DayLog(LocalDate.now().toString(),"未完成"));store.import(backup)
        assertEquals("完成",store.log(LocalDate.now().toString()).status)
    }
    @Test fun invalidBackupDoesNotOverwriteExistingData() {
        store.createPlan(Plan(LocalDate.now().plusDays(2)))
        val before=store.export()
        try { store.import(org.json.JSONObject(before).put("schema",999).toString());fail("should reject") }catch(_:IllegalArgumentException){}
        assertEquals(before,store.export())
    }
    @Test fun threePassesPerCalendarDaySurviveRecreation() {
        val now=LocalDateTime.parse("2026-10-03T23:00:00")
        store.createPlan(Plan(now.toLocalDate()))
        store.rules=Rules(blocked=setOf("video","game"),passMinutes=10,passWaitSeconds=180)
        repeat(3) { index ->
            val at=now.plusMinutes(index*5L)
            assertTrue(Store(context).grantPass(if(index==1)"game" else "video",at))
            assertEquals(at.epoch()+300_000,Store(context).passUntil)
            assertEquals(2-index,Store(context).passesRemaining(now.toLocalDate()))
            assertFalse("A duplicate tap must not consume another pass",Store(context).grantPass("video",at))
        }
        assertFalse(store.grantPass("video",now.plusMinutes(15)))
        assertEquals(3,store.events().size)
        assertEquals(3,store.passesRemaining(now.toLocalDate().plusDays(1)))
        assertTrue(store.grantPass("video",now.plusHours(1)))
        assertEquals(2,store.passesRemaining(now.toLocalDate().plusDays(1)))
    }
    @Test fun legacyPassCountsOnActualCalendarDayAndOldRulesRemainReadable() {
        val now=LocalDateTime.parse("2026-10-04T02:00:00")
        store.createPlan(Plan(now.toLocalDate().minusDays(1)))
        store.rules=Rules(blocked=setOf("video"),passMinutes=8,passWaitSeconds=120)
        store.prefs.edit().putString("pass_night","2026-10-03").putLong("pass_until",now.epoch()+480_000).apply()
        assertEquals(2,Store(context).passesRemaining(now.toLocalDate()))
        assertFalse(store.grantPass("video",now))
        assertTrue(store.grantPass("video",now.plusMinutes(8)))
        assertEquals(1,store.passesRemaining(now.toLocalDate()))
        assertEquals(now.plusMinutes(13).epoch(),store.passUntil)
        assertEquals(8,store.rules.passMinutes);assertEquals(120,store.rules.passWaitSeconds)
        assertEquals("runtime",SyncCodec.scope("rhythm","pass_day"))
        assertEquals("runtime",SyncCodec.scope("rhythm","pass_count"))
    }
    @Test fun midnightResetsQuotaWithoutExtendingAnActivePass() {
        val now=LocalDateTime.parse("2026-10-03T23:58:00")
        store.createPlan(Plan(now.toLocalDate()));store.rules=Rules(blocked=setOf("video"))
        assertTrue(store.grantPass("video",now))
        assertEquals(3,store.passesRemaining(now.toLocalDate().plusDays(1)))
        assertFalse(store.grantPass("video",now.plusMinutes(2)))
        assertEquals(now.plusMinutes(5).epoch(),store.passUntil)
        assertTrue(store.grantPass("video",now.plusMinutes(5)))
        assertEquals(2,store.passesRemaining(now.toLocalDate().plusDays(1)))
    }
    @Test fun passesRequireAnActiveRestrictionAndCannotRace() {
        val now=LocalDateTime.parse("2026-10-03T23:00:00")
        store.createPlan(Plan(now.toLocalDate()));store.rules=Rules(blocked=setOf("video","phone"),allowed=setOf("phone"))
        assertFalse(store.grantPass("video",now.minusMinutes(1)))
        assertFalse(store.grantPass("phone",now));assertFalse(store.grantPass("other",now))
        val results=java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val threads=(1..6).map { Thread { results.add(Store(context).grantPass("video",now)) }.apply { start() } }
        threads.forEach { it.join() }
        assertEquals(1,results.count { it });assertEquals(2,store.passesRemaining(now.toLocalDate()))
    }
    @Test fun testVerificationDoesNotCreateDailyAchievement() {
        store.rules=store.rules.copy(secondCheck=false)
        store.wake=WakeSession(LocalDate.now().toString(),WakePhase.WALK,System.currentTimeMillis()+60000,1,test=true)
        assertTrue(Alarms.verify(context))
        assertEquals(WakePhase.COMPLETE,store.wake.phase)
        assertTrue(store.logs().isEmpty())
    }
    @Test fun sequentialChoicesPreserveOtherFieldsAndLatestVerification() {
        val day=LocalDate.now().toString()
        store.saveLog(DayLog(day,"完成","23:10","07:00","不错","工作","保留备注"))
        store.updateLog(day){it.copy(status="部分完成")}
        store.saveLog(store.log(day).copy(verifiedAt=1234,rise="07:05"))
        store.updateLog(day){it.copy(energy="一般")}
        val result=Store(context).log(day)
        assertEquals("部分完成",result.status);assertEquals("一般",result.energy)
        assertEquals(1234L,result.verifiedAt);assertEquals("07:05",result.rise)
        assertEquals("23:10",result.bed);assertEquals("工作",result.reason);assertEquals("保留备注",result.note)
        store.updateLog(day){it.copy(status="未记录")}
        assertEquals("未记录",store.log(day).status);assertEquals(1234L,store.log(day).verifiedAt)
    }
}
