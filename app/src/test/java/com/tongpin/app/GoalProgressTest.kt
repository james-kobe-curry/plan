package com.tongpin.app

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class GoalProgressTest {
    private val original = Plan(id = "hours-v1", title = "学习", category = Category.STUDY, target = 2, unit = "小时", seriesId = "study", startDate = "2026-09-01")
    private val current = original.copy(id = "hours-v2", target = 150, scale = 2, totalTarget = 500, startDate = "2026-09-15")

    @Test fun historicalIntegersAndCurrentHundredthsAccumulateInRealUnits() {
        val old = original.copy(archived = true, endDate = current.startDate)
        val data = AppData(plans = listOf(old, current), checkIns = listOf(
            CheckIn(old.id, "2026-09-14", 2), CheckIn(current.id, "2026-09-15", 150),
        ))
        DomainValidation.data(data)
        val progress = totalGoalProgress(data, current)
        assertEquals("3.5", progress.first)
        assertEquals(0.7f, progress.second, 0.000001f)
    }

    @Test fun differentUnitsAndUnrelatedTasksDoNotChangeTheTotal() {
        val minutes = original.copy(id = "minutes-v1", target = 30, unit = "分钟", archived = true, endDate = current.startDate)
        val unrelated = current.copy(id = "other-task", seriesId = "other")
        val data = AppData(plans = listOf(minutes, current, unrelated), checkIns = listOf(
            CheckIn(minutes.id, "2026-09-14", 60), CheckIn(unrelated.id, "2026-09-15", 900),
            CheckIn(current.id, "2026-09-15", 150),
        ))
        val progress = totalGoalProgress(data, current)
        assertEquals("1.5", progress.first)
        assertEquals(0.3f, progress.second, 0.000001f)
    }

    @Test fun renamingATaskKeepsItsSeriesAndWhitespaceCaseDoNotSplitTheUnit() {
        val old = original.copy(unit = " HOUR ", archived = true, endDate = current.startDate)
        val renamed = current.copy(title = "备考英语", unit = "hour")
        val data = AppData(plans = listOf(old, renamed), checkIns = listOf(
            CheckIn(old.id, "2026-09-14", 2), CheckIn(renamed.id, "2026-09-15", 150),
        ))
        assertEquals("3.5", totalGoalProgress(data, renamed).first)
    }

    @Test fun simpleTaskCompletionsAreNotAddedToAQuantityGoalWithTheSameUnit() {
        val task = original.copy(id = "task-v1", target = 1, unit = "次", tracking = TrackingMode.TASK, archived = true, endDate = current.startDate)
        val quantities = current.copy(target = 2, unit = "次", scale = 0, totalTarget = 10)
        val data = AppData(plans = listOf(task, quantities), checkIns = listOf(
            CheckIn(task.id, "2026-09-14", 1), CheckIn(quantities.id, "2026-09-15", 3),
        ))
        DomainValidation.data(data)
        assertEquals("3", totalGoalProgress(data, quantities).first)
        assertEquals(0.3f, totalGoalProgress(data, quantities).second, 0.000001f)
    }

    @Test fun totalsRemainExactWhileProgressIsCappedAtCompletion() {
        val goal = current.copy(totalTarget = 150)
        val data = AppData(plans = listOf(goal), checkIns = listOf(
            CheckIn(goal.id, "2026-09-15", 125), CheckIn(goal.id, "2026-09-16", 25),
        ))
        assertEquals("1.5" to 1f, totalGoalProgress(data, goal))
        val surpassed = data.copy(checkIns = data.checkIns + CheckIn(goal.id, "2026-09-17", 1))
        assertEquals("1.51" to 1f, totalGoalProgress(surpassed, goal))
        assertEquals("0" to 0f, totalGoalProgress(data.copy(checkIns = emptyList()), goal))
    }

    @Test fun aTaskWithoutTotalGoalUsesItsDailyTargetAsTheFallback() {
        val goal = current.copy(totalTarget = null)
        val data = AppData(plans = listOf(goal), checkIns = listOf(CheckIn(goal.id, "2026-09-15", 75)))
        assertEquals("0.75" to 0.5f, totalGoalProgress(data, goal))
    }

    @Test fun yearsOfQuantitiesCannotOverflowAnIntegerSum() {
        val goal = original.copy(unit = "页", target = 100_000, totalTarget = 1_000_000, startDate = "2020-01-01")
        val data = AppData(plans = listOf(goal), checkIns = (0 until 3000).map {
            CheckIn(goal.id, LocalDate.of(2020, 1, 1).plusDays(it.toLong()).toString(), 1_000_000)
        })
        DomainValidation.data(data)
        assertEquals("3000000000" to 1f, totalGoalProgress(data, goal))
    }
}
