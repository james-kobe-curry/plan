package com.tongpin.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

enum class HistoryRange { ALL, THIS_WEEK, THIS_MONTH, CUSTOM }

/** Both date boundaries are inclusive, including the local day of a focus completion. */
data class HistoryDateRange(val from: LocalDate, val through: LocalDate) {
    init { require(!from.isAfter(through)) { "开始日期不能晚于结束日期" } }
    fun contains(date: LocalDate): Boolean = !date.isBefore(from) && !date.isAfter(through)
}

fun historyDateRange(preset: HistoryRange, today: LocalDate, custom: HistoryDateRange): HistoryDateRange? = when (preset) {
    HistoryRange.ALL -> null
    HistoryRange.THIS_WEEK -> HistoryDateRange(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today)
    HistoryRange.THIS_MONTH -> HistoryDateRange(today.withDayOfMonth(1), today)
    HistoryRange.CUSTOM -> custom
}

val historyCheckInOrder: Comparator<CheckIn> = compareByDescending<CheckIn> { it.date }
    .thenByDescending { it.updatedAt }.thenBy { it.planId }

/** A task brought forward or restored may have a later-dated, archived historical version. */
fun historyTaskRepresentatives(data: AppData): List<Plan> = data.plans.groupBy { it.seriesId }.values.map { versions ->
    // Imported record metadata remains searchable but must not replace a real task's status.
    val candidates = versions.filterNot { it.recordsOnly }.ifEmpty { versions }
    candidates.lastOrNull { !it.archived }
        ?: candidates.maxWith(compareBy<Plan> { it.endDate ?: it.startDate }.thenBy { it.startDate })
}.sortedWith(compareBy<Plan> { it.archived }.thenBy { it.title })

data class HistoryQuery(
    val seriesId: String? = null,
    val text: String = "",
    val range: HistoryDateRange? = null,
) {
    fun checkIns(data: AppData): List<CheckIn> {
        val plans = data.plans.associateBy { it.id }
        val needle = text.trim()
        return data.checkIns.filter { entry ->
            val plan = plans[entry.planId]
            entry.amount > 0 && plan != null && (seriesId == null || plan.seriesId == seriesId) &&
                (range == null || range.contains(LocalDate.parse(entry.date))) &&
                (needle.isEmpty() || plan.title.contains(needle, ignoreCase = true) || entry.note.contains(needle, ignoreCase = true))
        }.sortedWith(historyCheckInOrder)
    }

    fun focusRecords(data: AppData, zone: ZoneId = ZoneId.systemDefault()): List<FocusRecord> {
        val plans = data.plans.associateBy { it.id }
        val needle = text.trim()
        return data.focusRecords.filter { record ->
            val plan = plans[record.planId]
            val title = focusRecordTitle(record, plans)
            record.seconds > 0 && (seriesId == null || plan?.seriesId == seriesId) &&
                (range == null || range.contains(focusRecordDate(record, zone))) &&
                (needle.isEmpty() || title.contains(needle, ignoreCase = true))
        }.sortedWith(compareByDescending<FocusRecord> { it.completedAt }.thenBy { it.id })
    }
}

fun focusRecordDate(record: FocusRecord, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(record.completedAt).atZone(zone).toLocalDate()

fun focusRecordTitle(record: FocusRecord, plans: Map<String, Plan>): String =
    if (record.planId == null) "自由专注" else plans[record.planId]?.title ?: "历史任务"

fun historyFocusDuration(seconds: Long): String = when {
    seconds < 60 -> "$seconds 秒"
    seconds % 60L == 0L -> "${seconds / 60} 分钟"
    else -> "${seconds / 60} 分 ${seconds % 60} 秒"
}
