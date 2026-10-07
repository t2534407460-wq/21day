package com.twentyone.rhythm

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class NightCompletionTest {
    private fun bed(date:LocalDate)=DayLog(date.toString(),status="未完成",sleepiness="有点困",bedMood="平静",bedReason="无",bedtimeCheckedAt=100)
    @Test fun workdayRequiresBothChecksIncludingMakeupSaturday() {
        val date=LocalDate.parse("2026-10-09") // next morning is the 10/10 makeup workday
        val empty=DayLog(date.toString(),status="完成");val wake=DayLog(date.plusDays(1).toString(),verifiedAt=200)
        assertEquals("未完成",BedtimeSchedule.status(date,empty,wake.copy(verifiedAt=0)))
        assertEquals("部分完成",BedtimeSchedule.status(date,bed(date),wake.copy(verifiedAt=0)))
        assertEquals("部分完成",BedtimeSchedule.status(date,empty,wake))
        assertEquals("已完成",BedtimeSchedule.status(date,bed(date),wake))
    }
    @Test fun restDayNeedsOnlyConfirmedBedtimeAndNeverManualStatus() {
        val date=LocalDate.parse("2026-10-06") // next morning is the last national holiday
        val wake=DayLog(date.plusDays(1).toString(),verifiedAt=200)
        assertEquals("已完成",BedtimeSchedule.status(date,bed(date),wake.copy(verifiedAt=0)))
        assertEquals("未完成",BedtimeSchedule.status(date,bed(date).copy(bedtimeCheckedAt=0,status="完成"),wake))
        assertEquals("未完成",BedtimeSchedule.status(date,bed(date).copy(bedMood=""),wake))
    }
    @Test fun verificationFromTheSameCalendarDayCannotCompleteTheNextMorning() {
        val date=LocalDate.parse("2026-10-08")
        val logs=listOf(bed(date).copy(verifiedAt=200))
        val facts=CoachAnalysis.facts(logs,date,1,Plan(date))
        assertTrue(facts.text.contains("$date 晚 → ${date.plusDays(1)} 早：部分完成"))
    }
}
