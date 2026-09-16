package com.tongpin.app

import java.time.LocalDate
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class RecordsOverviewTest {
    private val today = LocalDate.of(2026, 9, 16)
    private val firstDay = today.minusDays(6)
    private fun plan(id: String) = Plan(id = id, title = id, category = Category.STUDY,
        target = 1, unit = "次", tracking = TrackingMode.TASK, startDate = firstDay.toString())

    @Test fun emptyHistoryStillProvidesSevenOrderedDaysWithoutInventedProgress() {
        val result = recordsOverview(AppData(), today)
        assertEquals((6 downTo 0).map { today.minusDays(it.toLong()) }, result.days.map { it.date })
        assertTrue(result.days.all { it.completed == 0 && it.scheduled == 0 })
        assertEquals(0, result.checkInDays)
        assertEquals(0L, result.focusSeconds)
        assertTrue(result.recent.isEmpty())
    }

    @Test fun scheduleCountsMatchExistingRulesIncludingHistoryPausesSkipsAndRecordOnlyPlans() {
        val ordinary = plan("ordinary")
        val oldVersion = plan("old").copy(seriesId = ordinary.seriesId, archived = true,
            endDate = firstDay.plusDays(3).toString(), superseded = true)
        val paused = plan("paused").copy(pauses = listOf(PlanPause(firstDay.plusDays(1).toString(), firstDay.plusDays(3).toString())))
        val skipped = plan("skipped").copy(skips = listOf(PlanSkip(today.toString())))
        val due = plan("due").copy(dueDate = today.minusDays(1).toString(), weekdays = setOf(1, 3, 5))
        val detached = plan("recordOnly").copy(recordsOnly = true)
        val legacyArchive = plan("legacyArchive").copy(archived = true)
        val plans = listOf(ordinary, oldVersion, paused, skipped, due, detached, legacyArchive)
        val entries = plans.flatMapIndexed { i, p ->
            (0L..6L).filter { isScheduled(p, firstDay.plusDays(it)) }.map { day ->
                CheckIn(p.id, firstDay.plusDays(day).toString(), if ((day + i) % 2L == 0L) 1 else 0, updatedAt = day)
            }
        } + CheckIn(detached.id, today.minusYears(1).toString(), 1, "保留的历史", 1)
        val data = AppData(plans, entries, listOf(FocusRecord("a", seconds = 180), FocusRecord("b", seconds = 75)))
        val result = recordsOverview(data, today)
        result.days.forEach { assertEquals(dayProgress(data, it.date), it.completed to it.scheduled) }
        assertEquals(data.checkIns.filter { it.amount > 0 }.map { it.date }.distinct().size, result.checkInDays)
        assertEquals(255L, result.focusSeconds)
        assertEquals(result.days.sumOf { it.completed }, result.completed)
        assertEquals(result.days.sumOf { it.scheduled }, result.scheduled)
        // Ordinary and its overlapping old version count once, alongside paused and skipped.
        assertEquals(3, result.days.first().scheduled)
    }

    @Test fun recentRecordsKeepStableHistoryOrderingAndUseTheOriginalPlanAndEditRules() {
        val active = plan("a")
        val archive = plan("b").copy(archived = true, endDate = today.minusDays(1).toString())
        val future = today.plusDays(1).toString()
        val entries = listOf(
            CheckIn(active.id, today.toString(), 1, "同时间第一条", 20),
            CheckIn(active.id, today.toString(), 1, "同时间第二条", 20),
            CheckIn(archive.id, today.toString(), 1, "已结束", 30),
            CheckIn("missing", future, 1, "未知计划", 1),
            CheckIn(active.id, future, 1, "未来记录不可编辑", 2),
            CheckIn(active.id, today.minusDays(1).toString(), 1, "更早", 100),
            CheckIn(active.id, today.toString(), 0, "零值不展示", 999),
        )
        val result = recordsOverview(AppData(plans = listOf(active, archive), checkIns = entries), today)
        assertEquals(entries.filter { it.amount > 0 }.sortedWith(historyCheckInOrder).take(5), result.recent.map { it.entry })
        assertEquals(listOf("同时间第一条", "同时间第二条"), result.recent.filter { it.date == today && it.plan == active }.map { it.entry.note })
        assertTrue(result.recent.filter { it.date == today && it.plan == active }.all { it.canEdit })
        assertTrue(result.recent.filter { it.date > today || it.plan == archive || it.plan == null }.none { it.canEdit })
        assertNull(result.recent.single { it.entry.planId == "missing" }.plan)
    }

    @Test fun indexedAmountsKeepFirstMatchingCheckInAndLongFocusTotals() {
        val p = plan("p")
        val entries = listOf(CheckIn(p.id, today.toString(), 0), CheckIn(p.id, today.toString(), 1))
        val data = AppData(plans = listOf(p), checkIns = entries,
            focusRecords = List(3) { FocusRecord(it.toString(), seconds = Int.MAX_VALUE) })
        val result = recordsOverview(data, today)
        assertEquals(dayProgress(data, today), result.days.last().completed to result.days.last().scheduled)
        assertEquals(0, result.days.last().completed)
        assertEquals(1, result.checkInDays)
        assertEquals(Int.MAX_VALUE.toLong() * 3, result.focusSeconds)
    }

    @Test fun oneHundredThousandRecordsProduceCorrectTotalsAndMostRecentFive() {
        val start = today.minusDays(499)
        val plans = (0 until 200).map { plan("p$it").copy(startDate = start.toString()) }
        val entries = (0 until 100_000).map { i ->
            CheckIn(plans[i % 200].id, start.plusDays((i / 200).toLong()).toString(),
                if (i % 3 == 0) 0 else 1, updatedAt = i.toLong())
        }.shuffled(Random(42))
        val data = AppData(plans = plans, checkIns = entries)
        val result = recordsOverview(data, today)
        assertEquals(500, result.checkInDays)
        assertEquals(7 * 200, result.scheduled)
        result.days.forEach { day ->
            val offset = java.time.temporal.ChronoUnit.DAYS.between(start, day.date).toInt() * 200
            assertEquals((offset until offset + 200).count { it % 3 != 0 }, day.completed)
        }
        assertEquals(entries.filter { it.amount > 0 }.sortedWith(historyCheckInOrder).take(5), result.recent.map { it.entry })
        assertTrue(result.recent.all { it.canEdit })
    }
}
