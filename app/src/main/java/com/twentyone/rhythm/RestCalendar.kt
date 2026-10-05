package com.twentyone.rhythm

import java.time.DayOfWeek
import java.time.LocalDate

/** Mainland China 2026, 国办发明电〔2025〕7号. Other years use weekends only. */
object RestCalendar {
    private val holidays=listOf("01-01" to "01-03","02-15" to "02-23","04-04" to "04-06",
        "05-01" to "05-05","06-19" to "06-21","09-25" to "09-27","10-01" to "10-07")
        .map { (start,end)->LocalDate.parse("2026-$start") to LocalDate.parse("2026-$end") }
    private val workdays=setOf("01-04","02-14","02-28","05-09","09-20","10-10").map { LocalDate.parse("2026-$it") }.toSet()
    fun holiday(date:LocalDate)=holidays.any { (start,end)->date>=start && date<=end }
    fun isRest(date:LocalDate)=date !in workdays && (holiday(date) || date.dayOfWeek in setOf(DayOfWeek.SATURDAY,DayOfWeek.SUNDAY))
}
