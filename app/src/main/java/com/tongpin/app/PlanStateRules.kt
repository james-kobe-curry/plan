package com.tongpin.app

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

fun isPlanPaused(plan: Plan): Boolean = plan.pauses.lastOrNull()?.endDate == null && plan.pauses.isNotEmpty()
fun isPlanExpired(plan: Plan, date: LocalDate): Boolean = !plan.archived && plan.dueDate?.let { date > LocalDate.parse(it) } == true

/** Counts long date ranges without iterating every day, excluding pauses and explicit rest days. */
fun countExecutionDays(start: LocalDate, end: LocalDate, weekdays: Set<Int>, pauses: List<PlanPause> = emptyList(), skips: List<PlanSkip> = emptyList()): Long {
    fun count(from: LocalDate, through: LocalDate): Long {
        if (through < from) return 0
        val span = ChronoUnit.DAYS.between(from, through) + 1
        return span / 7 * weekdays.size + (0 until (span % 7).toInt()).count { from.plusDays(it.toLong()).dayOfWeek.value in weekdays }
    }
    if (end < start) return 0
    val paused = pauses.sumOf { pause ->
        val from = maxOf(start, LocalDate.parse(pause.startDate))
        val through = minOf(end, pause.endDate?.let { LocalDate.parse(it).minusDays(1) } ?: end)
        count(from, through)
    }
    val skipped = skips.map { LocalDate.parse(it.date) }.distinct().count { date ->
        date in start..end && date.dayOfWeek.value in weekdays && pauses.none {
            date >= LocalDate.parse(it.startDate) && (it.endDate == null || date < LocalDate.parse(it.endDate))
        }
    }
    return (count(start, end) - paused - skipped).coerceAtLeast(0)
}

fun skipPlanDay(data: AppData, id: String, date: LocalDate, reason: String): AppData {
    val plan = requireNotNull(data.plans.find { it.id == id }) { "找不到这个计划" }
    require(!plan.archived && isScheduled(plan, date)) { "这一天没有可跳过的安排" }
    require(amountFor(data, id, date) == 0) { "今天已有打卡，请先撤销打卡再跳过" }
    val skip = PlanSkip(date.toString(), reason.trim())
    return data.copy(plans = data.plans.map { if (it.id == id) it.copy(skips = it.skips + skip) else it }).also(DomainValidation::data)
}

fun undoPlanSkip(data: AppData, id: String, date: LocalDate): AppData =
    data.copy(plans = data.plans.map { if (it.id == id) it.copy(skips = it.skips.filterNot { skip -> skip.date == date.toString() }) else it })
        .also(DomainValidation::data)

fun pausePlan(data: AppData, id: String, today: LocalDate): AppData {
    val plan = requireNotNull(data.plans.find { it.id == id }) { "找不到这个计划" }
    require(!plan.archived && !plan.recordsOnly) { "只能暂停进行中的计划" }
    require(!isPlanExpired(plan, today)) { "请先延长已到期计划的截止日期" }
    if (isPlanPaused(plan)) return data
    // Keep today's progress editable. An already logged day is never removed from history.
    val start = maxOf(LocalDate.parse(plan.startDate), if (amountFor(data, id, today) > 0) today.plusDays(1) else today)
    return data.copy(plans = data.plans.map { if (it.id == id) it.copy(pauses = it.pauses + PlanPause(start.toString())) else it })
        .also(DomainValidation::data)
}

fun resumePlan(data: AppData, id: String, today: LocalDate): AppData {
    val plan = requireNotNull(data.plans.find { it.id == id }) { "找不到这个计划" }
    require(!plan.archived && !plan.recordsOnly) { "只能恢复进行中的计划" }
    val open = plan.pauses.lastOrNull()?.takeIf { it.endDate == null } ?: return data
    val closed = plan.pauses.dropLast(1) + if (today > LocalDate.parse(open.startDate)) listOf(open.copy(endDate = today.toString())) else emptyList()
    return data.copy(plans = data.plans.map { if (it.id == id) it.copy(pauses = closed) else it }).also(DomainValidation::data)
}

fun restoreArchivedPlan(data: AppData, id: String, start: LocalDate, today: LocalDate = LocalDate.now()): AppData {
    val plan = requireNotNull(data.plans.find { it.id == id }) { "找不到这个计划" }
    require(plan.archived && !plan.recordsOnly) { "请选择归档任务" }
    val versions = data.plans.filter { it.seriesId == plan.seriesId && !it.recordsOnly }
    require(versions.none { !it.archived }) { "这个任务已经恢复，请查看进行中的计划" }
    require(start >= today) { "恢复日期不能早于今天" }
    val lastEnd = versions.mapNotNull { it.endDate?.takeIf { end -> end > it.startDate } }.maxOrNull()
    require(lastEnd == null || start.toString() >= lastEnd) { "恢复日期不能与之前的安排重叠，请选择 $lastEnd 或之后" }
    val ids = versions.map { it.id }.toSet()
    val lastLogged = data.checkIns.filter { it.planId in ids }.maxOfOrNull { it.date }
    require(lastLogged == null || start.toString() > lastLogged) { "恢复日期应晚于最近打卡日期 $lastLogged" }
    val replacement = plan.copy(id = UUID.randomUUID().toString(), startDate = start.toString(), archived = false, superseded = false,
        endDate = null, pauses = emptyList(), skips = emptyList(), dueDate = plan.dueDate?.takeIf { it >= start.toString() })
    return data.copy(plans = data.plans + replacement).also(DomainValidation::data)
}

fun setPlanPinned(data: AppData, id: String, pinned: Boolean): AppData {
    val plan = requireNotNull(data.plans.find { it.id == id }) { "找不到这个计划" }
    return data.copy(plans = data.plans.map { if (it.seriesId == plan.seriesId) it.copy(pinned = pinned) else it })
        .also(DomainValidation::data)
}

/** Keep a task's place when a revision appends its current version to the stored list. */
fun plansInDisplayOrder(data: AppData, plans: List<Plan>): List<Plan> {
    val firstSeriesPosition = mutableMapOf<String, Int>()
    data.plans.forEachIndexed { index, plan -> firstSeriesPosition.putIfAbsent(plan.seriesId, index) }
    return plans.sortedWith(compareByDescending<Plan> { it.pinned }
        .thenBy { it.sortOrder }
        .thenBy { firstSeriesPosition[it.seriesId] ?: Int.MAX_VALUE })
}

fun scheduledPlansOrdered(data: AppData, date: LocalDate): List<Plan> =
    plansInDisplayOrder(data, effectivePlansForDate(data, date).filter { isScheduled(it, date) })

/** Focus keeps its existing eligibility rules, including paused and future tasks. */
fun focusPlansOrdered(data: AppData): List<Plan> =
    plansInDisplayOrder(data, data.plans.filter { !it.archived && !it.recordsOnly }
        .groupBy { it.seriesId }.values.map { it.last() })

/** Move within the visible group; pinning and filtering never move unrelated tasks accidentally. */
fun movePlan(data: AppData, id: String, direction: Int, visibleIds: List<String>): AppData {
    require(direction == -1 || direction == 1) { "调整方向无效" }
    require(visibleIds.size == visibleIds.toSet().size) { "任务列表顺序无效" }
    val all = data.plans.associateBy { it.id }
    require(visibleIds.all { it in all }) { "找不到待排序的任务" }
    val index = visibleIds.indexOf(id)
    require(index >= 0) { "任务不在当前列表中" }
    val next = index + direction
    if (next !in visibleIds.indices) return data
    require(all.getValue(id).pinned == all.getValue(visibleIds[next]).pinned) { "置顶任务只能在置顶区域内调整" }
    val ordered = visibleIds.toMutableList().also { java.util.Collections.swap(it, index, next) }
    val series = ordered.map { all.getValue(it).seriesId }
    require(series.toSet().size == series.size) { "同一任务不能重复排序" }
    // Normalize every series once so new orders never collide with untouched zero-valued legacy rows.
    val base = plansInDisplayOrder(data, data.plans).map { it.seriesId }.distinct().toMutableList()
    val positions = base.indices.filter { base[it] in series }
    positions.forEachIndexed { i, position -> base[position] = series[i] }
    val order = base.withIndex().associate { it.value to it.index }
    return data.copy(plans = data.plans.map { it.copy(sortOrder = order.getValue(it.seriesId)) }).also(DomainValidation::data)
}
