package com.tongpin.app

import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** Cross-feature audit cases: state transitions must agree with their history and summaries. */
class TaskHistoryAuditTest {
    private val monday = LocalDate.of(2026, 9, 14)
    private val reading = Plan(id = "reading", title = "阅读", category = Category.STUDY,
        target = 20, unit = "页", startDate = monday.toString())

    private fun assertDailyAndReviewAgree(data: AppData, through: LocalDate) {
        val period = reviewPeriod(ReviewPeriodKind.MONTH, through, through)
        val review = PeriodReviewIndex(data, ZoneId.of("Asia/Shanghai")).summarize(period)
        val days = generateSequence(period.start) { it.plusDays(1) }.takeWhile { it <= through }.toList()
        val completed = days.sumOf { dayProgress(data, it).first }
        val scheduled = days.sumOf { dayProgress(data, it).second }
        assertEquals("daily and period completion count", completed, review.counts.completed)
        assertEquals("daily and period scheduled count", scheduled, review.counts.expected)
        val overview = recordsOverview(data, through)
        overview.days.forEach { day ->
            assertEquals(dayProgress(data, day.date), day.completed to day.scheduled)
        }
    }

    @Test fun importingARevisedPlanIntoItsOlderCopyDoesNotCreateTwoCurrentTasks() {
        val local = AppData(plans = listOf(reading))
        val revised = revisePlan(local, reading.id, reading.copy(target = 40), monday.plusDays(1))
        val bundle = TransferCodec.decode(TransferCodec.encode(revised, TransferScope.PLANS))
        val imported = mergeTransfer(local, bundle, TransferScope.PLANS).result

        assertEquals("keep the complete local series when a revision conflicts", local.plans, imported.plans)
        assertEquals("one visible arrangement per logical task after importing a newer revision",
            1, scheduledPlansOrdered(imported, monday.plusDays(1)).size)
        assertEquals(1, focusPlansOrdered(imported).size)
        assertEquals(1, libraryTasks(imported).count { !it.archived })
        assertDailyAndReviewAgree(imported, monday.plusDays(3))
    }

    @Test fun revisionArchiveRestoreAndPauseKeepHistoryAndSummariesAligned() {
        var data = AppData(plans = listOf(reading))
        data = logged(data, reading.id, monday, 20, "第一天", 100L)
        data = logged(data, reading.id, monday.plusDays(1), 5, "少读几页", 200L)
        data = revisePlan(data, reading.id, reading.copy(target = 10), monday.plusDays(1))
        val revised = data.plans.single { !it.archived }
        data = skipPlanDay(data, revised.id, monday.plusDays(2), "休息")
        data = pausePlan(data, revised.id, monday.plusDays(3))
        data = resumePlan(data, revised.id, monday.plusDays(5))
        data = logged(data, revised.id, monday.plusDays(5), 10, "重新开始", 300L)
        val preserved = data.checkIns
        data = archivePlan(data, revised.id, monday.plusDays(5))
        data = restoreArchivedPlan(data, revised.id, monday.plusDays(8), monday.plusDays(6))
        val restored = data.plans.single { !it.archived }
        data = logged(data, restored.id, monday.plusDays(8), 7, "继续积累", 400L)
        data = setDailyNote(data, monday.plusDays(8), "任务之外的收获", 500L)

        assertEquals(preserved, data.checkIns.take(preserved.size))
        assertEquals("42", totalGoalProgress(data, restored).first)
        assertEquals(0 to 0, dayProgress(data, monday.plusDays(2)))
        assertEquals(0 to 0, dayProgress(data, monday.plusDays(4)))
        assertEquals(0 to 0, dayProgress(data, monday.plusDays(7)))
        assertEquals(data, DataCodec.decode(DataCodec.encode(data)))
        assertDailyAndReviewAgree(data, monday.plusDays(10))
    }

    @Test fun weekdayCountingMatchesCalendarEnumerationAcrossLeapAndYearBoundaries() {
        val random = Random(91212)
        repeat(120) {
            val start = LocalDate.of(2023, 12, 1).plusDays(random.nextLong(0, 800))
            val end = start.plusDays(random.nextLong(0, 500))
            val weekdays = (1..7).filter { random.nextBoolean() }.toSet().ifEmpty { setOf(7) }
            val pauses = listOf(
                PlanPause(start.plusDays(3).toString(), start.plusDays(11).toString()),
                PlanPause(start.plusDays(28).toString(), start.plusDays(50).toString()),
                PlanPause(start.plusDays(90).toString()),
            )
            val skips = listOf(1L, 5L, 15L, 29L, 51L, 90L, 91L).map { PlanSkip(start.plusDays(it).toString()) }
            val plan = reading.copy(startDate = start.toString(), weekdays = weekdays, pauses = pauses, skips = skips)
            val expected = generateSequence(start) { it.plusDays(1) }.takeWhile { it <= end }.count { isScheduled(plan, it) }
            assertEquals("$start to $end, weekdays=$weekdays", expected.toLong(), countExecutionDays(start, end, weekdays, pauses, skips))
        }
    }

    @Test fun removingAHistoricalCheckInCanBeUndoneWithoutDeletingANewerReflectionOrFocusSession() {
        val date = monday.plusDays(1)
        val checked = logged(AppData(plans = listOf(reading)), reading.id, date, 7, "原始心得", 123L)
        val archived = archivePlan(checked, reading.id, date.plusDays(1))
        val removed = setCheckIn(archived, reading.id, date, 0)
        val undo = requireNotNull(checkInUndo(archived, removed, reading.id, date))
        val changed = setDailyNote(removed, date, "独立每日心得", 456L).copy(
            focusRecords = listOf(FocusRecord("audit-focus", reading.id, 90, 789L)))
        val restored = applyQuickUndo(changed, undo)

        assertEquals(checked.checkIns, restored.checkIns)
        assertEquals(changed.dailyNotes, restored.dailyNotes)
        assertEquals(changed.focusRecords, restored.focusRecords)
        assertEquals(changed.plans, restored.plans)
        assertDailyAndReviewAgree(restored, date.plusDays(2))
    }

    @Test fun quantityUnitChangesNeverAddDifferentMeasurementsToTheSameTotalGoal() {
        val km = reading.copy(title = "跑步", category = Category.FITNESS, target = 250, unit = "公里", scale = 2, totalTarget = 1000)
        var data = logged(AppData(plans = listOf(km)), km.id, monday, 125, "慢跑", 100L)
        data = revisePlan(data, km.id, km.copy(target = 30, unit = "分钟", scale = 0, totalTarget = 300), monday)
        val minutes = data.plans.single { !it.archived }
        data = logged(data, minutes.id, monday.plusDays(1), 30, "训练时长", 200L)
        assertEquals("1.25", totalGoalProgress(data, data.plans.first()).first)
        assertEquals("30", totalGoalProgress(data, minutes).first)
        assertEquals(125, data.checkIns.first().amount)
        assertEquals("公里", data.plans.first().unit)
        assertDailyAndReviewAgree(data, monday.plusDays(2))
    }

    @Test fun aSkippedDayAndPauseDoNotHideItsIndependentDailyNote() {
        val noteDay = monday.plusDays(1)
        var data = setDailyNote(AppData(plans = listOf(reading)), noteDay, "休息也有收获", 100L)
        data = skipPlanDay(data, reading.id, noteDay, "休息")
        data = pausePlan(data, reading.id, monday.plusDays(2))
        assertEquals("休息也有收获", dailyNoteFor(data, noteDay)?.text)
        assertEquals(0 to 0, dayProgress(data, noteDay))
        val roundTrip = TransferCodec.decode(TransferCodec.encode(data, TransferScope.RECORDS)).data
        assertEquals(data.dailyNotes, roundTrip.dailyNotes)
        assertTrue(roundTrip.plans.isEmpty())
        assertTrue(roundTrip.checkIns.isEmpty())
    }

    @Test fun bringingAFutureTaskForwardKeepsItsHistoryFilterMarkedAsCurrent() {
        val future = reading.copy(startDate = monday.plusMonths(1).toString())
        val data = revisePlan(AppData(plans = listOf(future)), future.id,
            future.copy(startDate = monday.toString()), monday)
        val current = data.plans.single { !it.archived }
        val representative = historyTaskRepresentatives(data).single()

        assertEquals(current, representative)
        assertFalse(representative.archived)
        assertEquals(libraryTasks(data), historyTaskRepresentatives(data))
    }

    @Test fun restoringANeverStartedArchiveTodaySelectsTheRestoredVersionInHistory() {
        val future = reading.copy(startDate = monday.plusMonths(1).toString())
        val archived = archivePlan(AppData(plans = listOf(future)), future.id, monday)
        val restored = restoreArchivedPlan(archived, future.id, monday, monday)
        assertEquals(restored.plans.single { !it.archived }, historyTaskRepresentatives(restored).single())
    }

    @Test fun historyFilterKeepsRecordOnlySeriesButDoesNotLetMetadataChangeARealTasksStatus() {
        val archived = reading.copy(archived = true, endDate = monday.plusDays(1).toString())
        val alias = reading.copy(id = "record-alias", recordsOnly = true,
            originId = reading.id, startDate = monday.plusDays(20).toString())
        val standalone = alias.copy(id = "record-standalone", seriesId = "standalone", title = "历史跑步")
        val data = AppData(plans = listOf(archived, alias, standalone), checkIns = listOf(
            CheckIn(alias.id, monday.toString(), 3, "记录导入", 1L),
            CheckIn(standalone.id, monday.toString(), 4, "记录导入", 2L)))
        val representatives = historyTaskRepresentatives(data)

        assertEquals(2, representatives.size)
        assertEquals(archived, representatives.single { it.seriesId == reading.seriesId })
        assertEquals(standalone, representatives.single { it.seriesId == standalone.seriesId })
        assertEquals(listOf(alias.id), HistoryQuery(seriesId = reading.seriesId).checkIns(data).map { it.planId })
        assertEquals(listOf(standalone.id), HistoryQuery(seriesId = standalone.seriesId).checkIns(data).map { it.planId })
    }

    @Test fun completeRestoreWithOverlappingVersionsUsesOneArrangementAndRetainsEveryOriginalRecord() {
        val date = monday.plusDays(2)
        val old = reading
        val current = reading.copy(id = "reading-new", startDate = date.toString(), target = 40)
        val backup = AppData(plans = listOf(old, current), checkIns = listOf(
            CheckIn(old.id, date.toString(), 20, "原版本完成", 100L),
            CheckIn(current.id, date.toString(), 10, "新版本进度", 200L)))
        val restored = mergeTransfer(AppData(), TransferCodec.decode(TransferCodec.encode(backup, TransferScope.ALL)), TransferScope.ALL).result
        val before = DataCodec.encode(restored)

        assertEquals(listOf(current.id), effectivePlansForDate(restored, date).map { it.id })
        assertEquals(listOf(current.id), scheduledPlansOrdered(restored, date).map { it.id })
        assertEquals(listOf(current.id), focusPlansOrdered(restored).map { it.id })
        assertEquals(libraryTasks(restored).filter { !it.archived }, focusPlansOrdered(restored))
        assertEquals(0 to 1, dayProgress(restored, date))
        assertEquals(2, HistoryQuery(range = HistoryDateRange(date, date)).checkIns(restored).size)
        assertEquals(2, recordsOverview(restored, date).recent.size)
        val review = PeriodReviewIndex(restored).summarize(reviewPeriod(ReviewPeriodKind.WEEK, date, date))
        assertEquals(1, review.additionalHistoryRecords)
        assertEquals(1, review.counts.partial)
        val card = ProgressCardRules.make(restored, date, ProgressCardOptions())
        assertEquals(0 to 1, card.completed to card.scheduled)
        assertEquals(1, card.rows.size)
        assertDailyAndReviewAgree(restored, monday.plusDays(4))
        assertEquals("derived views never migrate or delete original data", before, DataCodec.encode(restored))
    }

    @Test fun aPausedLatestVersionCannotReviveAnOlderOverlappingArrangement() {
        val date = monday.plusDays(2)
        val current = reading.copy(id = "reading-new", startDate = date.toString(),
            pauses = listOf(PlanPause(date.toString())))
        val data = AppData(plans = listOf(reading, current))

        assertEquals(listOf(current.id), effectivePlansForDate(data, date).map { it.id })
        assertTrue(scheduledPlansOrdered(data, date).isEmpty())
        assertEquals(0 to 0, dayProgress(data, date))
        val review = PeriodReviewIndex(data).summarize(reviewPeriod(ReviewPeriodKind.WEEK, date, date))
        assertEquals(1, review.counts.paused)
        assertEquals(2, review.counts.expected)
        val card = ProgressCardRules.make(data, date, ProgressCardOptions())
        assertEquals(0, card.scheduled)
        assertTrue(card.rows.isEmpty())
        assertDailyAndReviewAgree(data, monday.plusDays(4))
    }

    @Test fun aSkippedLatestVersionCannotReviveAnOlderOverlappingArrangement() {
        val date = monday.plusDays(2)
        val current = reading.copy(id = "reading-new", startDate = date.toString(),
            skips = listOf(PlanSkip(date.toString(), "休息")))
        val data = AppData(plans = listOf(reading, current))

        assertEquals(listOf(current.id), effectivePlansForDate(data, date).map { it.id })
        assertTrue(scheduledPlansOrdered(data, date).isEmpty())
        assertEquals(0 to 0, dayProgress(data, date))
        val skips = effectivePlansForDate(data, date).filter { isArranged(it, date) && it.skips.any { skip -> skip.date == date.toString() } }
        assertEquals(listOf(current), skips)
        val review = PeriodReviewIndex(data).summarize(reviewPeriod(ReviewPeriodKind.WEEK, date, date))
        assertEquals(1, review.counts.skipped)
        assertEquals(2, review.counts.expected)
        assertEquals(0, ProgressCardRules.make(data, date, ProgressCardOptions()).scheduled)
        assertDailyAndReviewAgree(data, monday.plusDays(4))
    }

    @Test fun effectiveVersionTieBreakAndDateEligibilityAreIndependentOfImportedListOrder() {
        val old = reading.copy(archived = true, endDate = monday.plusDays(5).toString())
        val first = reading.copy(id = "active-a")
        val newest = reading.copy(id = "active-z", weekdays = setOf(1, 2), dueDate = monday.plusDays(1).toString())
        val detached = reading.copy(id = "metadata-z", recordsOnly = true)
        val future = reading.copy(id = "future-z", startDate = monday.plusDays(10).toString())
        listOf(listOf(old, first, newest, detached, future), listOf(future, detached, newest, first, old)).forEach { plans ->
            val data = AppData(plans = plans)
            assertEquals(listOf(newest.id), effectivePlansForDate(data, monday).map { it.id })
            assertEquals(listOf(newest.id), effectivePlansForDate(data, monday.plusDays(1)).map { it.id })
            assertEquals(listOf(first.id), effectivePlansForDate(data, monday.plusDays(2)).map { it.id })
            assertEquals(listOf(future.id), effectivePlansForDate(data, monday.plusDays(10)).map { it.id })
            assertDailyAndReviewAgree(data, monday.plusDays(12))
        }
    }

    private fun logged(data: AppData, id: String, date: LocalDate, amount: Int, note: String, timestamp: Long): AppData =
        com.tongpin.app.setCheckIn(data, id, date, amount, note).let { changed ->
            changed.copy(checkIns = changed.checkIns.map { if (it.planId == id && it.date == date.toString()) it.copy(updatedAt = timestamp) else it })
        }
}
