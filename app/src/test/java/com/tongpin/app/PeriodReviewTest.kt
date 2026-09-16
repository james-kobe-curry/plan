package com.tongpin.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class PeriodReviewTest {
    private val today = LocalDate.parse("2026-09-18")
    private val week = reviewPeriod(ReviewPeriodKind.WEEK, today, today)
    private val plan = Plan(id = "read", title = "阅读", category = Category.STUDY, target = 2, unit = "页",
        startDate = "2026-09-14", weekdays = (1..5).toSet())

    @Test fun weeksBeginMondayAcrossTheYearBoundaryAndDoNotGoToTheFuture() {
        val sunday = LocalDate.parse("2023-01-01")
        val period = reviewPeriod(ReviewPeriodKind.WEEK, sunday, sunday)
        assertEquals(LocalDate.parse("2022-12-26"), period.start)
        assertEquals(sunday, period.end)
        assertEquals(sunday, period.through)
        assertNull(period.next(sunday))
        assertEquals(LocalDate.parse("2022-12-19"), period.previous(sunday)!!.start)
        assertEquals(period, reviewPeriod(ReviewPeriodKind.WEEK, sunday.plusYears(1), sunday))
    }

    @Test fun monthRangeHandlesLeapDayAndCurrentPeriodStopsAtToday() {
        val feb = reviewPeriod(ReviewPeriodKind.MONTH, LocalDate.parse("2024-02-02"), today)
        assertEquals(LocalDate.parse("2024-02-29"), feb.end)
        assertEquals(feb.end, feb.through)
        assertEquals(LocalDate.parse("2024-03-01"), feb.next(today)!!.start)
        val current = reviewPeriod(ReviewPeriodKind.MONTH, today, today)
        assertEquals(today, current.through)
        assertTrue(current.isPartial)
        assertNull(current.next(today))
    }

    @Test fun completedUnfinishedSkippedAndPausedHaveDisjointCounts() {
        val task = plan.copy(pauses = listOf(PlanPause("2026-09-16", "2026-09-18")),
            skips = listOf(PlanSkip("2026-09-15", "休息")))
        val data = AppData(plans = listOf(task), checkIns = listOf(
            CheckIn(task.id, "2026-09-14", 2), CheckIn(task.id, "2026-09-18", 1)))
        val review = PeriodReviewIndex(data).summarize(week)
        assertEquals(ReviewCounts(completed = 1, unfinished = 1, skipped = 1, paused = 2, partial = 1), review.counts)
        assertEquals(2, review.counts.expected)
        assertEquals(50, review.counts.completionPercent)
        assertEquals(2, review.checkInDays)
    }

    @Test fun futureDaysNeverAppearAsUnfinishedInCurrentWeek() {
        val wednesday = LocalDate.parse("2026-09-16")
        val period = reviewPeriod(ReviewPeriodKind.WEEK, wednesday, wednesday)
        val data = AppData(plans = listOf(plan.copy(weekdays = (1..7).toSet())))
        assertEquals(3, PeriodReviewIndex(data).summarize(period).counts.unfinished)
    }

    @Test fun pastPlanVersionsRespectExclusiveEndDatesAndTheirOwnTargets() {
        val old = plan.copy(id = "old", archived = true, endDate = "2026-09-16", target = 5, seriesId = "series")
        val revised = plan.copy(id = "new", startDate = "2026-09-16", target = 1, seriesId = "series")
        val data = AppData(plans = listOf(old, revised), checkIns = listOf(
            CheckIn("old", "2026-09-14", 4), CheckIn("old", "2026-09-15", 5),
            CheckIn("new", "2026-09-16", 1)))
        val result = PeriodReviewIndex(data).summarize(week)
        assertEquals(5, result.counts.expected)
        assertEquals(2, result.counts.completed)
        assertEquals(1, result.counts.partial)
        assertEquals(0, result.additionalHistoryRecords)
    }

    @Test fun overlappingImportedVersionsDoNotDoubleCountOneTaskOnOneDay() {
        val old = plan.copy(id = "old", archived = true, endDate = "2026-09-19", seriesId = "series")
        val revised = plan.copy(id = "new", startDate = "2026-09-16", seriesId = "series")
        val data = AppData(plans = listOf(old, revised), checkIns = listOf(
            CheckIn("old", "2026-09-16", 2), CheckIn("new", "2026-09-16", 1)))
        val result = PeriodReviewIndex(data).summarize(week)
        assertEquals(5, result.counts.expected)
        assertEquals(0, result.counts.completed)
        assertEquals(1, result.counts.partial)
        assertEquals(1, result.additionalHistoryRecords)
    }

    @Test fun recordsOnlyAndLegacyOutOfScheduleEntriesRemainVisibleWithoutInventingExpectations() {
        val historical = plan.copy(id = "history", seriesId = "history", recordsOnly = true)
        val closed = plan.copy(id = "closed", seriesId = "closed", archived = true, endDate = null)
        val data = AppData(plans = listOf(historical, closed), checkIns = listOf(
            CheckIn("history", "2026-09-15", 2, "导入的心得"), CheckIn("closed", "2026-09-16", 1, "旧任务记录")))
        val result = PeriodReviewIndex(data).summarize(week)
        assertEquals(0, result.counts.expected)
        assertNull(result.counts.completionPercent)
        assertEquals(2, result.checkInRecords)
        assertEquals(2, result.additionalHistoryRecords)
        assertEquals(2, result.notes.size)
        assertFalse(result.isEmpty)
    }

    @Test fun dueDateIsInclusiveAndRestoredPlansDoNotFillTheGap() {
        val old = plan.copy(id = "old", archived = true, endDate = "2026-09-16", seriesId = "series")
        val restored = plan.copy(id = "restore", startDate = "2026-09-18", dueDate = "2026-09-18", seriesId = "series")
        val period = reviewPeriod(ReviewPeriodKind.MONTH, today, LocalDate.parse("2026-09-30"))
        assertEquals(3, PeriodReviewIndex(AppData(plans = listOf(old, restored))).summarize(period).counts.expected)
    }

    @Test fun onlyOriginallyScheduledDaysCountAsPausedAndSkipWithinPauseDoesNotDoubleCount() {
        val data = AppData(plans = listOf(plan.copy(pauses = listOf(PlanPause("2026-09-15")),
            skips = listOf(PlanSkip("2026-09-16")))))
        val fullWeek = reviewPeriod(ReviewPeriodKind.WEEK, today, LocalDate.parse("2026-09-20"))
        val result = PeriodReviewIndex(data).summarize(fullWeek)
        assertEquals(1, result.counts.expected)
        assertEquals(4, result.counts.paused)
        assertEquals(0, result.counts.skipped)
    }

    @Test fun focusUsesLocalCompletionDateAndTotalsIncludeFreeFocusExactlyOnce() {
        val fitness = plan.copy(id = "fitness", seriesId = "fitness", category = Category.FITNESS)
        val old = plan.copy(id = "old", seriesId = "old", recordsOnly = true, category = Category.LIFE)
        val data = AppData(plans = listOf(plan, fitness, old), focusRecords = listOf(
            FocusRecord("before", plan.id, 60, Instant.parse("2026-09-13T15:59:59Z").toEpochMilli()),
            FocusRecord("study", plan.id, 1500, Instant.parse("2026-09-13T16:00:00Z").toEpochMilli()),
            FocusRecord("fitness", fitness.id, 900, Instant.parse("2026-09-15T04:00:00Z").toEpochMilli()),
            FocusRecord("life", old.id, 61, Instant.parse("2026-09-16T04:00:00Z").toEpochMilli()),
            FocusRecord("free", null, 300, Instant.parse("2026-09-17T04:00:00Z").toEpochMilli()),
            FocusRecord("future", plan.id, 60, Instant.parse("2026-09-18T16:00:00Z").toEpochMilli())))
        val result = PeriodReviewIndex(data, ZoneId.of("Asia/Shanghai")).summarize(week)
        assertEquals(4, result.focusSessions)
        assertEquals(2761L, result.focusSeconds)
        assertEquals(1, result.freeFocusSessions)
        assertEquals(300L, result.freeFocusSeconds)
        assertEquals(listOf(1500L, 900L, 61L), result.categories.map { it.focusSeconds })
        assertEquals(result.focusSeconds, result.categories.sumOf { it.focusSeconds } + result.freeFocusSeconds)
    }

    @Test fun notesKeepTheirOriginalTextAndSortWithinDayByLatestUpdate() {
        val second = plan.copy(id = "second", seriesId = "second", category = Category.FITNESS)
        val text = "第一段\n\n" + "很长的心得。".repeat(300)
        val data = AppData(plans = listOf(plan, second), checkIns = listOf(
            CheckIn(plan.id, "2026-09-16", 1, text, 10), CheckIn(second.id, "2026-09-16", 2, "最近写的", 20),
            CheckIn(plan.id, "2026-09-15", 1, "   ", 30), CheckIn(plan.id, "2026-09-01", 1, "其他阶段", 40)))
        val result = PeriodReviewIndex(data).summarize(week)
        assertEquals(listOf("second", "read"), result.notes.map { it.entry.planId })
        assertEquals(text, result.notes.last().entry.note)
        assertEquals(2, result.checkInDays)
    }

    @Test fun ordinaryValidSchedulesMatchExistingDailyProgressCounts() {
        val study = plan.copy(pauses = listOf(PlanPause("2026-09-16", "2026-09-18")))
        val fitness = plan.copy(id = "fitness", seriesId = "fitness", category = Category.FITNESS, weekdays = setOf(1, 3, 5),
            skips = listOf(PlanSkip("2026-09-18")))
        val data = AppData(plans = listOf(study, fitness), checkIns = listOf(
            CheckIn(study.id, "2026-09-14", 2), CheckIn(fitness.id, "2026-09-16", 2)))
        val result = PeriodReviewIndex(data).summarize(week)
        val daily = (0L..4L).map { dayProgress(data, week.start.plusDays(it)) }
        assertEquals(daily.sumOf { it.first }, result.counts.completed)
        assertEquals(daily.sumOf { it.second }, result.counts.expected)
        assertEquals(result.counts.expected, result.categories.sumOf { it.counts.expected })
    }

    @Test fun emptyPeriodHasNoInventedPercentage() {
        val result = PeriodReviewIndex(AppData()).summarize(week)
        assertTrue(result.isEmpty)
        assertNull(result.counts.completionPercent)
        assertEquals(0L, result.focusSeconds)
    }
}
