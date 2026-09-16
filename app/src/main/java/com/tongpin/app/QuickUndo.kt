package com.tongpin.app

import java.time.LocalDate

/** A short-lived inverse of one successful operation, never a whole-data snapshot. */
sealed class QuickUndo

private data class UndoPlanGuard(val seriesId: String, val plans: Map<String, Plan>)
private data class CheckInChange(
    val guard: UndoPlanGuard,
    val planId: String,
    val date: String,
    val before: CheckIn?,
    val expected: CheckIn?,
    val previousIndex: Int,
    val expectedSkip: PlanSkip?,
) : QuickUndo()
private data class PauseChange(
    val guard: UndoPlanGuard,
    val planId: String,
    val before: List<PlanPause>,
) : QuickUndo()
private data class SkipChange(
    val guard: UndoPlanGuard,
    val planId: String,
    val date: String,
    val before: PlanSkip?,
    val expected: PlanSkip?,
    val previousIndex: Int,
    val expectedEntry: CheckIn?,
) : QuickUndo()

/** Ordering and other skipped dates do not change the meaning of the saved operation. */
private fun Plan.undoStructure() = copy(pinned = false, sortOrder = 0, skips = emptyList())

private fun undoPlan(data: AppData, planId: String): Plan =
    requireNotNull(data.plans.find { it.id == planId }) { "这个任务已被删除，无法撤销" }

private fun undoGuard(data: AppData, plan: Plan) = UndoPlanGuard(
    plan.seriesId,
    data.plans.filter { it.seriesId == plan.seriesId }.associate { it.id to it.undoStructure() },
)

private fun checkedUndoPlan(current: AppData, guard: UndoPlanGuard, planId: String): Plan {
    val plan = undoPlan(current, planId)
    val currentVersions = current.plans.filter { it.seriesId == guard.seriesId }
        .associate { it.id to it.undoStructure() }
    require(plan.seriesId == guard.seriesId && currentVersions == guard.plans) {
        "这个任务的设置或状态已改变，无法撤销之前的操作"
    }
    return plan
}

fun checkInUndo(before: AppData, after: AppData, planId: String, date: LocalDate): QuickUndo? {
    val day = date.toString()
    val old = before.checkIns.find { it.planId == planId && it.date == day }
    val saved = after.checkIns.find { it.planId == planId && it.date == day }
    if (old == saved) return null
    val oldPlan = undoPlan(before, planId)
    val plan = undoPlan(after, planId)
    require(oldPlan.undoStructure() == plan.undoStructure()) { "任务在打卡时已发生变化，无法提供撤销" }
    return CheckInChange(undoGuard(after, plan), planId, day, old, saved,
        before.checkIns.indexOfFirst { it.planId == planId && it.date == day },
        plan.skips.find { it.date == day })
}

fun pauseUndo(before: AppData, after: AppData, planId: String): QuickUndo? {
    val old = undoPlan(before, planId)
    val plan = undoPlan(after, planId)
    if (old.pauses == plan.pauses) return null
    require(plan.pauses.size == old.pauses.size + 1 && plan.pauses.dropLast(1) == old.pauses &&
        plan.pauses.last().endDate == null && plan.copy(pauses = old.pauses).undoStructure() == old.undoStructure()) {
        "暂停状态已发生变化，无法提供撤销"
    }
    return PauseChange(undoGuard(after, plan), planId, old.pauses.toList())
}

fun skipUndo(before: AppData, after: AppData, planId: String, date: LocalDate): QuickUndo? {
    val oldPlan = undoPlan(before, planId)
    val plan = undoPlan(after, planId)
    val day = date.toString()
    val old = oldPlan.skips.find { it.date == day }
    val saved = plan.skips.find { it.date == day }
    if (old == saved) return null
    require(oldPlan.undoStructure() == plan.undoStructure()) { "任务在跳过时已发生变化，无法提供撤销" }
    return SkipChange(undoGuard(after, plan), planId, day, old, saved,
        oldPlan.skips.indexOfFirst { it.date == day },
        after.checkIns.find { it.planId == planId && it.date == day })
}

/** Reject a stale inverse instead of erasing a newer edit, import, or restored task version. */
fun applyQuickUndo(current: AppData, undo: QuickUndo): AppData {
    val result = when (undo) {
        is CheckInChange -> {
            val plan = checkedUndoPlan(current, undo.guard, undo.planId)
            val actual = current.checkIns.find { it.planId == undo.planId && it.date == undo.date }
            require(actual == undo.expected && plan.skips.find { it.date == undo.date } == undo.expectedSkip) {
                "这一天的打卡或跳过状态已改变，无法撤销之前的操作"
            }
            val entries = current.checkIns.filterNot { it.planId == undo.planId && it.date == undo.date }.toMutableList()
            undo.before?.let { entries.add(undo.previousIndex.coerceIn(0, entries.size), it) }
            current.copy(checkIns = entries)
        }
        is PauseChange -> {
            checkedUndoPlan(current, undo.guard, undo.planId)
            current.copy(plans = current.plans.map {
                if (it.id == undo.planId) it.copy(pauses = undo.before) else it
            })
        }
        is SkipChange -> {
            val plan = checkedUndoPlan(current, undo.guard, undo.planId)
            val entry = current.checkIns.find { it.planId == undo.planId && it.date == undo.date }
            require(plan.skips.find { it.date == undo.date } == undo.expected && entry == undo.expectedEntry) {
                "这一天的打卡或跳过状态已改变，无法撤销之前的操作"
            }
            val skips = plan.skips.filterNot { it.date == undo.date }.toMutableList()
            undo.before?.let { skips.add(undo.previousIndex.coerceIn(0, skips.size), it) }
            current.copy(plans = current.plans.map {
                if (it.id == undo.planId) it.copy(skips = skips) else it
            })
        }
    }
    return result.also(DomainValidation::data)
}
