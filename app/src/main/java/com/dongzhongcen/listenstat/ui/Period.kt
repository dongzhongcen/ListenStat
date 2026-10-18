package com.dongzhongcen.listenstat.ui

import java.time.LocalDate

enum class Range { DAY, MONTH, YEAR }

/** A selectable statistics period: the day / month / year containing [anchor]. */
data class Period(val range: Range, val anchor: LocalDate) {

    /** dayKey prefix used by the DAO's LIKE queries (yyyy-MM-dd / yyyy-MM / yyyy). */
    val prefix: String
        get() = when (range) {
            Range.DAY -> "%04d-%02d-%02d".format(anchor.year, anchor.monthValue, anchor.dayOfMonth)
            Range.MONTH -> "%04d-%02d".format(anchor.year, anchor.monthValue)
            Range.YEAR -> "%04d".format(anchor.year)
        }

    val start: LocalDate
        get() = when (range) {
            Range.DAY -> anchor
            Range.MONTH -> anchor.withDayOfMonth(1)
            Range.YEAR -> anchor.withDayOfYear(1)
        }

    val endExclusive: LocalDate
        get() = when (range) {
            Range.DAY -> start.plusDays(1)
            Range.MONTH -> start.plusMonths(1)
            Range.YEAR -> start.plusYears(1)
        }

    operator fun contains(day: LocalDate): Boolean = !day.isBefore(start) && day.isBefore(endExclusive)

    fun shift(n: Long): Period = copy(
        anchor = when (range) {
            Range.DAY -> anchor.plusDays(n)
            Range.MONTH -> anchor.plusMonths(n)
            Range.YEAR -> anchor.plusYears(n)
        }
    )

    /** Next period would start after [today] (no future navigation). */
    fun isLatest(today: LocalDate): Boolean = shift(1).start.isAfter(today)

    fun withRange(r: Range): Period = copy(range = r)
}
