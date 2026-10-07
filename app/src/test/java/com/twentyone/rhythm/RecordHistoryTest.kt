package com.twentyone.rhythm

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RecordHistoryTest {
    private val day=LocalDate.parse("2026-10-07")
    private val habit=Habit(name="控烟",start=day,mode=HabitMode.AT_MOST,unit="支",rules=listOf(HabitRule(day,target=10)))
    @Test fun historicalTotalsSessionsAndTemporaryPassesReachAssistantWithoutNotes() {
        val at=day.atTime(21,0).epoch()
        val facts=RecordHistory.facts(listOf(DayLog(day.toString(),note="private-note")),null,listOf(habit),
            listOf(HabitEntry(habit.id,day,3,note="private-habit-note",recordedAt=at)),listOf(EventLog(at,"临时放行","抖音 · 5 分钟 · 今日第 1 次")),day,1)
        assertTrue(facts.text.contains("控烟 · 3 支"));assertTrue(facts.text.contains("临时放行"));assertTrue(facts.text.contains("实际累计 3 支"))
        assertFalse(facts.text.contains("private-note"));assertFalse(facts.text.contains("private-habit-note"))
        assertEquals(1,facts.recorded)
    }
    @Test fun scopeOmitsOtherDatesAndLimitsInputButKeepsCompleteLocalHistory() {
        val at=day.atTime(21,0).epoch()
        val events=(0..510).map { EventLog(at+it,"次数打卡","控烟第 $it 次") }+EventLog(day.plusDays(1).atStartOfDay().epoch(),"未来操作","不应发送")
        assertEquals(512,RecordHistory.records(emptyList(),emptyList(),emptyList(),events).size)
        val facts=RecordHistory.facts(emptyList(),null,emptyList(),emptyList(),events,day,1)
        assertTrue(facts.text.contains("最新 60 条明细"));assertFalse(facts.text.contains("未来操作"))
        assertTrue(facts.text.length<=16000)
    }
    @Test fun planIncludesMissingNightsWithoutInventingRecordsOrFutureDays() {
        val records=RecordHistory.records(emptyList(),emptyList(),emptyList(),emptyList(),Plan(day),day)
        assertEquals(1,records.size);assertTrue(records.single().detail.contains("未完成"))
        assertEquals(0,RecordHistory.facts(emptyList(),Plan(day),emptyList(),emptyList(),emptyList(),day,1).recorded)
    }
    @Test fun olderScopeKeepsCompletionAfterStartingANewPlan() {
        val old=LocalDate.parse("2026-09-15")
        val logs=listOf(DayLog(old.toString(),sleepiness="很困了",bedMood="放松",bedReason="无",bedtimeCheckedAt=100),DayLog(old.plusDays(1).toString(),verifiedAt=200))
        val facts=RecordHistory.facts(logs,Plan(day),emptyList(),emptyList(),emptyList(),old,1)
        assertTrue(facts.text.contains("$old 晚 → ${old.plusDays(1)} 早：已完成"))
        assertTrue(RecordHistory.records(logs,emptyList(),emptyList(),emptyList(),Plan(day),day).any { it.day==old && it.kind=="作息完成度" && it.detail.contains("已完成") })
    }
}
