package com.tongpin.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class ReviewPeriodKind { WEEK, MONTH }

data class ReviewPeriod(
    val kind: ReviewPeriodKind,
    val start: LocalDate,
    val end: LocalDate,
    val through: LocalDate,
) {
    init {
        require(!end.isBefore(start) && through in start..end) { "复盘日期范围无效" }
        require(end.toEpochDay() - start.toEpochDay() <= 30) { "复盘范围不能超过一个月" }
    }
    val isPartial: Boolean get() = through < end
    fun previous(today: LocalDate): ReviewPeriod? = if (start.year == 1 && start.dayOfYear == 1) null
        else reviewPeriod(kind, start.minusDays(1), today)
    fun next(today: LocalDate): ReviewPeriod? = if (end >= today) null else reviewPeriod(kind, end.plusDays(1), today)
}

/** Weeks begin Monday; future portions of the current period are not expectations yet. */
fun reviewPeriod(kind: ReviewPeriodKind, anchor: LocalDate, today: LocalDate): ReviewPeriod {
    val date = minOf(anchor, today)
    val start = when (kind) {
        ReviewPeriodKind.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        ReviewPeriodKind.MONTH -> date.withDayOfMonth(1)
    }
    val end = if (kind == ReviewPeriodKind.WEEK) start.plusDays(6) else start.withDayOfMonth(start.lengthOfMonth())
    return ReviewPeriod(kind, start, end, minOf(end, today))
}

data class ReviewCounts(val completed: Int = 0, val unfinished: Int = 0, val skipped: Int = 0,
    val paused: Int = 0, val partial: Int = 0) {
    val expected: Int get() = completed + unfinished
    val completionPercent: Int? get() = if (expected == 0) null else (completed.toLong() * 100 / expected).toInt()
}

data class CategoryReview(val category: Category, val counts: ReviewCounts,
    val records: Int, val focusSessions: Int, val focusSeconds: Long, val customCategory: CustomCategory? = null) {
    val key: String get() = customCategory?.let { "custom:${it.id}" } ?: "builtin:${category.name}"
    val label: String get() = customCategory?.name ?: builtinCategoryName(category)
}

data class ReviewNote(val plan: Plan?, val entry: CheckIn, val categoryLabel: String? = null)

data class PeriodReview(
    val period: ReviewPeriod,
    val counts: ReviewCounts,
    val checkInRecords: Int,
    val checkInDays: Int,
    val additionalHistoryRecords: Int,
    val focusSessions: Int,
    val focusSeconds: Long,
    val freeFocusSessions: Int,
    val freeFocusSeconds: Long,
    val categories: List<CategoryReview>,
    val notes: List<ReviewNote>,
) {
    val isEmpty: Boolean get() = counts.expected + counts.skipped + counts.paused == 0 &&
        checkInRecords == 0 && focusSessions == 0
}

/** Index once per data revision; range changes never scan all records for every plan/day. */
class PeriodReviewIndex(data: AppData, zone: ZoneId = ZoneId.systemDefault()) {
    private data class EntryKey(val planId: String, val date: String)
    private data class DatedFocus(val record: FocusRecord, val day: LocalDate)
    private data class PreparedPlan(val plan: Plan, val start: Long, val end: Long,
        val pausedFrom: LongArray, val pausedUntil: LongArray, val skipped: Set<Long>) {
        fun paused(day: Long): Boolean {
            val found = pausedFrom.binarySearch(day)
            val index = if (found >= 0) found else -found - 2
            return index >= 0 && day < pausedUntil[index]
        }
    }
    private class Counts {
        var completed = 0; var unfinished = 0; var skipped = 0; var paused = 0; var partial = 0
        fun snapshot() = ReviewCounts(completed, unfinished, skipped, paused, partial)
    }
    private val plansById = data.plans.associateBy { it.id }
    private val categoryDefinitions = data.categories
    private val categoryKeys = Category.entries.map { "builtin:${it.name}" } + data.categories.filter { category -> data.plans.any { it.customCategoryId == category.id } }.map { "custom:${it.id}" }
    private val checkIns = data.checkIns.filter { it.amount > 0 }
    private val entriesByKey = checkIns.associateBy { EntryKey(it.planId, it.date) }
    private val focus = data.focusRecords.filter { it.seconds > 0 }.map { DatedFocus(it, focusRecordDate(it, zone)) }
    private val prepared = data.plans.filter { !it.recordsOnly && !(it.archived && it.endDate == null) }.map { plan ->
        val pauses = plan.pauses.sortedBy { it.startDate }
        PreparedPlan(plan, LocalDate.parse(plan.startDate).toEpochDay(),
            minOf(plan.endDate?.let { LocalDate.parse(it).toEpochDay() - 1 } ?: Long.MAX_VALUE,
                plan.dueDate?.let { LocalDate.parse(it).toEpochDay() } ?: Long.MAX_VALUE),
            pauses.map { LocalDate.parse(it.startDate).toEpochDay() }.toLongArray(),
            pauses.map { it.endDate?.let { end -> LocalDate.parse(end).toEpochDay() } ?: Long.MAX_VALUE }.toLongArray(),
            plan.skips.mapTo(hashSetOf()) { LocalDate.parse(it.date).toEpochDay() })
    }

    fun summarize(period: ReviewPeriod): PeriodReview {
        val from = period.start.toString()
        val through = period.through.toString()
        val counts = categoryKeys.associateWith { Counts() }
        val matched = hashSetOf<EntryKey>()
        val candidates = prepared.filter { it.start <= period.through.toEpochDay() && it.end >= period.start.toEpochDay() }
        var date = period.start
        while (date <= period.through) {
            val epoch = date.toEpochDay()
            val dayText = date.toString()
            val active = hashMapOf<String, PreparedPlan>()
            for (version in candidates) {
                if (epoch !in version.start..version.end || date.dayOfWeek.value !in version.plan.weekdays) continue
                val existing = active[version.plan.seriesId]
                // Valid revisions have disjoint dates. Keep one latest effective version if an
                // imported legacy file contains overlapping versions of the same task.
                if (existing == null || isNewerPlanVersion(version.plan, existing.plan)) active[version.plan.seriesId] = version
            }
            for (version in active.values) {
                val count = counts.getValue(planCategoryKey(version.plan))
                when {
                    version.paused(epoch) -> count.paused++
                    epoch in version.skipped -> count.skipped++
                    else -> {
                        val key = EntryKey(version.plan.id, dayText)
                        val entry = entriesByKey[key]
                        if (entry != null) matched.add(key)
                        if ((entry?.amount ?: 0) >= version.plan.target) count.completed++ else {
                            count.unfinished++
                            if (entry != null) count.partial++
                        }
                    }
                }
            }
            date = date.plusDays(1)
        }
        val periodEntries = checkIns.filter { it.date in from..through }
        val periodFocus = focus.filter { it.day >= period.start && it.day <= period.through }.map { it.record }
        val categoryRecords = periodEntries.groupingBy { plansById[it.planId]?.let(::planCategoryKey) }.eachCount()
        val categoryFocus = periodFocus.groupBy { plansById[it.planId]?.let(::planCategoryKey) }
        val categorySummaries = categoryKeys.map { key ->
            val sessions = categoryFocus[key].orEmpty()
            val custom = categoryDefinitions.firstOrNull { "custom:${it.id}" == key }
            val builtin = if (custom == null) Category.valueOf(key.removePrefix("builtin:")) else Category.STUDY
            CategoryReview(builtin, counts.getValue(key).snapshot(), categoryRecords[key] ?: 0,
                sessions.size, sessions.sumOf { it.seconds.toLong() }, custom)
        }
        val free = categoryFocus[null].orEmpty()
        return PeriodReview(period,
            ReviewCounts(counts.values.sumOf { it.completed }, counts.values.sumOf { it.unfinished },
                counts.values.sumOf { it.skipped }, counts.values.sumOf { it.paused }, counts.values.sumOf { it.partial }),
            periodEntries.size, periodEntries.map { it.date }.distinct().size,
            periodEntries.count { EntryKey(it.planId, it.date) !in matched },
            periodFocus.size, periodFocus.sumOf { it.seconds.toLong() }, free.size, free.sumOf { it.seconds.toLong() },
            categorySummaries,
            periodEntries.filter { it.note.isNotBlank() }.sortedWith(historyCheckInOrder).map { entry ->
                val plan = plansById[entry.planId]
                ReviewNote(plan, entry, plan?.let { categoryDefinitions.firstOrNull { c -> c.id == it.customCategoryId }?.name ?: builtinCategoryName(it.category) })
            })
    }

}
