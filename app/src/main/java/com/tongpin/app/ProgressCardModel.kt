package com.tongpin.app

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ProgressCardStyle(val label: String) { SIMPLE("简洁"), DETAILED("详细") }

data class ProgressCardOptions(
    val weekly: Boolean = false,
    val style: ProgressCardStyle = ProgressCardStyle.DETAILED,
    val showTitles: Boolean = true,
    val showNotes: Boolean = false,
)

data class ProgressCardDay(val date: LocalDate, val completed: Int, val scheduled: Int)
data class ProgressCardRow(val title: String, val detail: String, val status: String, val ratio: Float, val done: Boolean)
data class ProgressCardNote(val heading: String, val text: String)

/** This is the entire renderer input. Hidden names and notes never enter this model. */
data class ProgressCardModel(
    val weekly: Boolean,
    val detailed: Boolean,
    val nickname: String,
    val start: LocalDate,
    val end: LocalDate,
    val days: List<ProgressCardDay>,
    val completed: Int,
    val scheduled: Int,
    val focusSeconds: Long,
    val rows: List<ProgressCardRow>,
    val extraRows: Int,
    val notes: List<ProgressCardNote>,
    val extraNotes: Int,
    val notesRequested: Boolean,
) {
    val title: String get() = if (weekly) "七日进度周报" else "每日进度卡"
    val filePrefix: String get() = "plan-${if (weekly) "weekly" else "daily"}-$end"
    val description: String get() = if (weekly) "$start 至 $end 的 plan 进度卡" else "$end 的 plan 进度卡"
}

object ProgressCardRules {
    const val MAX_ROWS = 8
    const val MAX_NOTES = 8
    const val MAX_TITLE_CODEPOINTS = 30
    const val MAX_NOTE_CODEPOINTS = 100
    const val DISPLAY_RULE = "详细卡最多展示 8 项任务；每日心得与任务心得合计展示最近 8 条。名称最多 30 字、每条心得最多 100 字，超出以省略号显示。全部安排仍计入统计，原记录保留完整。"

    private data class NoteEntry(val date: String, val updatedAt: Long, val key: String, val heading: String, val text: String)

    fun make(data: AppData, end: LocalDate, options: ProgressCardOptions, zone: ZoneId = ZoneId.systemDefault()): ProgressCardModel {
        val start = if (options.weekly) end.minusDays(6) else end
        val dates = (0L..if (options.weekly) 6L else 0L).map(start::plusDays)
        // Build one index instead of rescanning up to 100,000 records for every task/day.
        val entries = data.checkIns.asSequence().filter { it.date >= start.toString() && it.date <= end.toString() }
            .associateBy { it.planId to it.date }
        val scheduled = dates.associateWith { date -> effectivePlansForDate(data, date).filter { isScheduled(it, date) } }
        fun amount(plan: Plan, date: LocalDate) = entries[plan.id to date.toString()]?.amount ?: 0
        val days = dates.map { date ->
            val plans = scheduled.getValue(date)
            ProgressCardDay(date, plans.count { amount(it, date) >= it.target }, plans.size)
        }
        val detailed = options.style == ProgressCardStyle.DETAILED
        val planIds = scheduled.values.flatten().mapTo(hashSetOf()) { it.id }
        val plans = if (detailed) data.plans.filter { it.id in planIds } else emptyList()
        val rows = plans.take(MAX_ROWS).mapIndexed { index, plan ->
            val arranged = dates.filter { date -> scheduled.getValue(date).any { it.id == plan.id } }
            val doneDays = arranged.count { amount(plan, it) >= plan.target }
            val total = arranged.sumOf { amount(plan, it).toLong() }
            val done = doneDays == arranged.size
            val detail = if (options.weekly) {
                if (plan.tracking == TrackingMode.TASK) "完成 $doneDays/${arranged.size} 天"
                else "累计 ${quantity(total, plan)} ${plan.unit} · 完成 $doneDays/${arranged.size} 天"
            } else if (plan.tracking == TrackingMode.TASK) "" else "${quantity(total, plan)} / ${formatQuantity(plan.target, plan)} ${plan.unit}"
            ProgressCardRow(
                title = if (options.showTitles) shorten(plan.title, MAX_TITLE_CODEPOINTS) else "任务 ${index + 1}",
                detail = detail,
                status = if (options.weekly) "$doneDays/${arranged.size} 天" else when { done -> "已完成"; total > 0 -> "进行中"; else -> "未打卡" },
                ratio = if (options.weekly) doneDays.toFloat() / arranged.size else total.toFloat() / plan.target,
                done = done,
            )
        }
        val notesRequested = detailed && options.showNotes
        val plansById = if (notesRequested) data.plans.associateBy { it.id } else emptyMap()
        val notes = if (notesRequested) buildList {
            entries.values.forEach { entry ->
                val plan = plansById[entry.planId]
                if (entry.note.isNotBlank() && plan != null && isScheduled(plan, LocalDate.parse(entry.date))) {
                    add(NoteEntry(entry.date, entry.updatedAt, "task:${entry.planId}",
                        if (options.showTitles) "${entry.date} · ${shorten(plan.title, MAX_TITLE_CODEPOINTS)}" else entry.date,
                        entry.note))
                }
            }
            // Daily notes describe a date rather than a task; no scheduled task is required.
            data.dailyNotes.forEach { entry ->
                if (entry.date >= start.toString() && entry.date <= end.toString() && entry.text.isNotBlank()) {
                    add(NoteEntry(entry.date, entry.updatedAt, "daily:${entry.date}", "${entry.date} · 每日心得", entry.text))
                }
            }
        }.sortedWith(compareByDescending<NoteEntry> { it.date }.thenByDescending { it.updatedAt }.thenBy { it.key }) else emptyList()
        val hiddenTitles = if (options.showTitles || !notesRequested) emptyList()
            else data.plans.map { it.title }.distinct().sortedByDescending { it.length }
        val noteRows = notes.take(MAX_NOTES).map { entry ->
            val noteText = hiddenTitles.fold(entry.text) { text, title -> text.replace(title, "该任务") }
            ProgressCardNote(
                heading = entry.heading,
                text = shorten(noteText, MAX_NOTE_CODEPOINTS),
            )
        }
        val focus = data.focusRecords.sumOf { record ->
            val date = Instant.ofEpochMilli(record.completedAt).atZone(zone).toLocalDate()
            if (date in start..end) record.seconds.toLong().coerceAtLeast(0) else 0L
        }
        return ProgressCardModel(options.weekly, detailed, shorten(data.nickname, DomainValidation.MAX_NICKNAME_LENGTH), start, end, days, days.sumOf { it.completed }, days.sumOf { it.scheduled }, focus,
            rows, (plans.size - MAX_ROWS).coerceAtLeast(0), noteRows, (notes.size - MAX_NOTES).coerceAtLeast(0), notesRequested)
    }

    internal fun shorten(value: String, maxCodePoints: Int): String {
        val text = value.trim().replace(Regex("\\s+"), " ")
        return if (text.codePointCount(0, text.length) <= maxCodePoints) text
        else text.substring(0, text.offsetByCodePoints(0, maxCodePoints)) + "…"
    }

    private fun quantity(value: Long, plan: Plan) = BigDecimal.valueOf(value, plan.scale).stripTrailingZeros().toPlainString()
}
