package com.blainemiller.scripturealone.data.plans

import java.time.LocalDate

/** A reader's place in one plan, ported from `PlanProgress` (Plans/PlanProgress.swift). */
data class PlanProgress(
    val planId: String,
    /** "yyyy-MM-dd" */
    val startDate: String,
    val followsCalendar: Boolean = false,
    val completed: Set<Int> = emptySet(),
    val reminderHour: Int? = null,
    val reminderMinute: Int? = null,
)

sealed interface PlanToday {
    data class NotStarted(val starts: String) : PlanToday
    data class Reading(val day: PlanDay) : PlanToday
    data class Rest(val next: PlanDay?) : PlanToday
    data object Finished : PlanToday
}

/**
 * An annual plan starts over each January: this progress begun on January 1 of [now]'s year with nothing read, when
 * it was begun in an earlier year. Null when there is nothing to roll over.
 */
fun PlanProgress.rolledOver(plan: ReadingPlan, now: LocalDate): PlanProgress? {
    if (!plan.annual) return null
    val started = startDate.take(4).toIntOrNull() ?: return null
    return if (started < now.year) copy(startDate = "%04d-01-01".format(now.year), completed = emptySet()) else null
}

/** Dates and tallies for a plan, pure: the clock is an argument so every rule is testable on a fixed date. */
class PlanSchedule(val plan: ReadingPlan, val progress: PlanProgress) {
    private val follows get() = progress.followsCalendar && plan.isDated

    private fun monthDay(date: LocalDate) = "%02d-%02d".format(date.monthValue, date.dayOfMonth)

    // An annual plan's days are "MM-DD" in the year of the start date; February 29 has no date in a common year.
    private fun annualDate(monthDay: String): LocalDate? {
        val year = start?.year ?: return null
        val (m, d) = monthDay.split("-").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return null
        return runCatching { LocalDate.of(year, m, d) }.getOrNull()
    }
    private val start: LocalDate? get() = runCatching { LocalDate.parse(progress.startDate) }.getOrNull()

    /** 1 = Sunday … 7 = Saturday, as `Calendar.weekday` numbers them on iOS. */
    private fun weekday(date: LocalDate) = date.dayOfWeek.value % 7 + 1
    private fun isRest(date: LocalDate) = weekday(date) in plan.skipWeekdays

    fun dateForDay(index: Int): LocalDate? {
        val day = plan.days.getOrNull(index) ?: return null
        if (plan.annual) return day.date?.let(::annualDate)
        if (follows) return day.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        var cursor = start ?: return null
        var remaining = index
        repeat(plan.days.size * 7 + 14) {
            if (!isRest(cursor)) {
                if (remaining == 0) return cursor
                remaining--
            }
            cursor = cursor.plusDays(1)
        }
        return null
    }

    fun dayIndex(on: LocalDate): Int? {
        val from = start ?: return null
        if (on < from) return null
        if (plan.annual) return plan.days.firstOrNull { it.date == monthDay(on) }?.index
        if (follows) return plan.days.firstOrNull { it.date == on.toString() }?.index
        if (isRest(on)) return null
        var count = 0
        var cursor = from
        while (cursor < on) {
            if (!isRest(cursor)) count++
            cursor = cursor.plusDays(1)
            if (count >= plan.days.size) return null
        }
        return if (count < plan.days.size) count else null
    }

    fun today(now: LocalDate): PlanToday {
        val from = start
        if (from == null || plan.days.isEmpty()) return PlanToday.Finished
        if (now < from) return PlanToday.NotStarted(progress.startDate)
        dayIndex(now)?.let { return PlanToday.Reading(plan.days[it]) }
        val last = dateForDay(plan.days.size - 1)
        if (last != null && now > last) return PlanToday.Finished
        return PlanToday.Rest(plan.days.firstOrNull { dateForDay(it.index)?.let { d -> d > now } == true })
    }

    /** Days whose date has passed without being read, oldest first. */
    fun missedDays(now: LocalDate): List<PlanDay> = if (plan.annual) emptyList() else plan.days.filter { d ->
        val when_ = dateForDay(d.index)
        d.index !in progress.completed && when_ != null && when_ < now && (start?.let { when_ >= it } ?: true)
    }

    val completedCount: Int get() = progress.completed.count { it in plan.days.indices }
    val fraction: Double get() = if (plan.days.isEmpty()) 0.0 else completedCount.toDouble() / plan.days.size

    /** Consecutive scheduled days read, ending today's — or yesterday's while today's is still open. */
    fun streak(now: LocalDate): Int {
        var scheduled = plan.days.filter { d -> dateForDay(d.index)?.let { it <= now } == true }.map { it.index }
        val last = scheduled.lastOrNull()
        if (last != null && last !in progress.completed && dateForDay(last) == now) scheduled = scheduled.dropLast(1)
        var count = 0
        for (index in scheduled.asReversed()) {
            if (index !in progress.completed) break
            count++
        }
        return count
    }

    /** What "play today's reading" reads: today's day, else the oldest missed, else the next to come. */
    fun reading(now: LocalDate): PlanDay? = when (val t = today(now)) {
        is PlanToday.Reading -> t.day
        is PlanToday.Rest -> missedDays(now).firstOrNull() ?: t.next
        is PlanToday.NotStarted -> plan.days.firstOrNull()
        PlanToday.Finished -> missedDays(now).firstOrNull()
    }
}
