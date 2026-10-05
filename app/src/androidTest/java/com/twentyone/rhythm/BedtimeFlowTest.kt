package com.twentyone.rhythm

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.app.NotificationManager
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class BedtimeFlowTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val c=instrument.targetContext
    private val s=Store(c)
    private val date=LocalDate.parse("2026-10-03")
    @Before fun setup() { s.prefs.edit().clear().commit();s.createPlan(Plan(date)) }
    @After fun cleanup() { instrument.runOnMainSync { Alarms.stop(c,"测试结束");Alarms.cancel(c,Alarms.BEDTIME_LOCK);Alarms.cancel(c,Alarms.WAKE);Alarms.cancel(c,Alarms.PREP) };s.prefs.edit().clear().commit() }
    private fun answers(day:LocalDate)=DayLog(day.toString(),sleepiness="有点困",bedMood="平静",bedReason="工作")
    @Test fun bedtimeRequiresAllAnswersAndConfirmationAndPreservesOtherRecords() {
        val day=date.toString();val now=date.atTime(22,50)
        s.saveLog(DayLog(day,status="完成",verifiedAt=100,energy="疲惫",note="历史备注"))
        assertTrue(runCatching { s.completeBedtime(day,now) }.isFailure)
        s.updateLog(day) { it.copy(sleepiness="有点困",bedMood="平静",bedReason="工作") }
        assertEquals(0L,s.log(day).bedtimeCheckedAt)
        s.completeBedtime(day,now);s.completeBedtime(day,now.plusMinutes(2))
        val result=s.log(day)
        assertEquals(now.epoch(),result.bedtimeCheckedAt);assertEquals(100L,result.verifiedAt)
        assertEquals("疲惫",result.energy);assertEquals("历史备注",result.note)
        assertTrue(runCatching { s.completeBedtime(day,date.plusDays(1).atTime(7,0)) }.isFailure)
    }
    @Test fun afterMidnightSavesThePreviousEveningIncludingFinalNight() {
        val last=date.plusDays(20);s.saveLog(answers(last))
        s.completeBedtime(last.toString(),last.plusDays(1).atTime(0,15))
        assertTrue(BedtimeSchedule.checked(s.log(last.toString())))
        assertEquals(0L,s.log(last.plusDays(1).toString()).bedtimeCheckedAt)
    }
    @Test fun oldAndNewBackupsRetainTheirMeaning() {
        s.createPlan(Plan(LocalDate.now().plusDays(5)))
        s.saveLog(answers(date).copy(bedtimeCheckedAt=date.atTime(23,0).epoch(),energy="一般",verifiedAt=123))
        val backup=s.export();s.import(backup)
        assertEquals(answers(date).copy(bedtimeCheckedAt=date.atTime(23,0).epoch(),energy="一般",verifiedAt=123),s.log(date.toString()))
        val old=JSONObject(backup);val oldLog=old.getJSONObject("logs").getJSONObject(date.toString())
        listOf("sleepiness","bedMood","bedReason","bedtimeCheckedAt").forEach { oldLog.remove(it) }
        s.import(old.toString());assertEquals("一般",s.log(date.toString()).energy);assertFalse(BedtimeSchedule.checked(s.log(date.toString())))
        val invalid=JSONObject(backup);invalid.getJSONObject("logs").getJSONObject(date.toString()).remove("bedMood")
        val before=s.export();assertTrue(runCatching { s.import(invalid.toString()) }.isFailure);assertEquals(before,s.export())
    }
    @Test fun morningIsSavedOnlyAfterFinalRealVerificationAndOnlyOnce() {
        val day=LocalDate.now().toString();s.saveLog(answers(LocalDate.now()).copy(note="保留"))
        s.wake=WakeSession(day,WakePhase.WALK,System.currentTimeMillis()+60000,1)
        instrument.runOnMainSync { assertTrue(Alarms.verify(c)) }
        assertEquals(WakePhase.SECOND_WAIT,s.wake.phase);assertEquals(0L,s.log(day).verifiedAt)
        instrument.runOnMainSync { assertFalse(Alarms.verify(c)) }
        s.wake=s.wake.copy(dueAt=System.currentTimeMillis()-1)
        instrument.runOnMainSync { assertTrue(Alarms.verify(c)) }
        val result=s.log(day);assertTrue(result.verifiedAt>0);assertTrue(result.rise.isNotBlank())
        assertEquals("有点困",result.sleepiness);assertEquals("保留",result.note)
        instrument.runOnMainSync { assertFalse(Alarms.verify(c)) };assertEquals(result,s.log(day))
    }
    @Test fun conversationalDraftsCannotCompleteEitherCheckIn() {
        val day=date.toString();s.saveLog(answers(date))
        val original=s.log(day)
        s.applyCoachDrafts(listOf(CoachDraft(day,bed="23:00",rise="07:00")),mapOf(day to original))
        assertEquals(0L,s.log(day).verifiedAt);assertEquals(0L,s.log(day).bedtimeCheckedAt)
        assertEquals("有点困",s.log(day).sleepiness)
    }
    @Test fun completedBedtimeDoesNotReceiveAnotherCheckInReminder() {
        val bedtime=LocalDateTime.now().plusMinutes(15);val today=BedtimeReminders.evening(bedtime);s.createPlan(Plan(today))
        s.rules=Rules(bed=bedtime.hour*60+bedtime.minute)
        s.saveLog(answers(today).copy(bedtimeCheckedAt=System.currentTimeMillis()))
        val manager=c.getSystemService(NotificationManager::class.java);manager.cancel(22)
        AlarmReceiver().onReceive(c,Intent().setAction(Alarms.PREP))
        assertTrue(manager.activeNotifications.none { it.id==22 })
        s.updateLog(today.toString()) { it.copy(bedtimeCheckedAt=0) }
        AlarmReceiver().onReceive(c,Intent().setAction(Alarms.PREP))
        assertTrue(manager.activeNotifications.any { it.id==22 })
        manager.cancel(22)
    }
}
