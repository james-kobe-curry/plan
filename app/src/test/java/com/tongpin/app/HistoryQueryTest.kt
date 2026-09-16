package com.tongpin.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HistoryQueryTest {
    private val today = LocalDate.parse("2026-09-16")
    private val old = Plan(id = "old", title = "英语单词", category = Category.STUDY, target = 20, unit = "个",
        startDate = "2026-08-01", archived = true, endDate = "2026-09-01", seriesId = "english")
    private val current = old.copy(id = "current", title = "English 阅读", archived = false,
        startDate = "2026-09-01", endDate = null)
    private val run = Plan(id = "run", title = "跑步", category = Category.FITNESS, target = 1, unit = "次",
        tracking = TrackingMode.TASK, startDate = "2026-09-01")
    private val custom = HistoryDateRange(today.minusDays(5), today.minusDays(2))

    @Test fun checkInSearchMatchesTitleOrNoteIgnoringCaseAndWhitespace() {
        val data = AppData(plans = listOf(current, run), checkIns = listOf(
            CheckIn(current.id, "2026-09-15", 1, "完成阅读", 1),
            CheckIn(run.id, "2026-09-16", 1, "听 English 播客", 2),
            CheckIn(run.id, "2026-09-14", 1, "天气凉爽", 3),
        ))
        assertEquals(listOf("2026-09-16", "2026-09-15"),
            HistoryQuery(text = "  ENGLISH  ").checkIns(data).map { it.date })
        assertEquals(listOf("2026-09-14"), HistoryQuery(text = "凉爽").checkIns(data).map { it.date })
    }

    @Test fun seriesFilterKeepsHistoricalTaskVersionsAndCombinesWithKeywordAndDate() {
        val data = AppData(plans = listOf(old, current, run), checkIns = listOf(
            CheckIn(old.id, "2026-08-31", 20, "复习", 1),
            CheckIn(current.id, "2026-09-01", 20, "复习", 2),
            CheckIn(run.id, "2026-09-01", 1, "复习播客", 3),
            CheckIn(current.id, "2026-09-02", 20, "新内容", 4),
        ))
        val query = HistoryQuery("english", "复习", HistoryDateRange(LocalDate.parse("2026-08-31"), LocalDate.parse("2026-09-01")))
        assertEquals(listOf("current", "old"), query.checkIns(data).map { it.planId })
    }

    @Test fun recentCheckInsSortByDateThenMostRecentUpdate() {
        val entries = listOf(CheckIn("a", "2026-09-16", 1, updatedAt = 5),
            CheckIn("b", "2026-09-15", 1, updatedAt = 20),
            CheckIn("c", "2026-09-16", 1, updatedAt = 15))
        assertEquals(listOf("c", "a", "b"), entries.sortedWith(historyCheckInOrder).map { it.planId })
    }

    @Test fun emptyQueryIncludesAllPositiveCheckInsAndNoUnresolvedTasks() {
        val data = AppData(plans = listOf(current), checkIns = listOf(
            CheckIn(current.id, "2026-09-16", 1), CheckIn(current.id, "2026-09-15", 0),
            CheckIn("missing", "2026-09-14", 1),
        ))
        assertEquals(listOf("2026-09-16"), HistoryQuery(text = "  ").checkIns(data).map { it.date })
    }

    @Test fun customRangeIncludesBothBoundaries() {
        assertTrue(custom.contains(today.minusDays(5)))
        assertTrue(custom.contains(today.minusDays(2)))
        assertFalse(custom.contains(today.minusDays(6)))
        assertFalse(custom.contains(today.minusDays(1)))
        assertEquals(custom, historyDateRange(HistoryRange.CUSTOM, today, custom))
        assertNull(historyDateRange(HistoryRange.ALL, today, custom))
    }

    @Test(expected = IllegalArgumentException::class)
    fun reversedCustomRangeIsRejected() {
        HistoryDateRange(today, today.minusDays(1))
    }

    @Test fun thisWeekUsesMondayAndStopsAtTodayAcrossMonthAndYearBoundaries() {
        val sunday = LocalDate.parse("2023-01-01")
        assertEquals(HistoryDateRange(LocalDate.parse("2022-12-26"), sunday),
            historyDateRange(HistoryRange.THIS_WEEK, sunday, custom))
        val monday = sunday.plusDays(1)
        assertEquals(HistoryDateRange(monday, monday), historyDateRange(HistoryRange.THIS_WEEK, monday, custom))
    }

    @Test fun thisMonthIncludesLeapDayAndStopsAtToday() {
        val leapDay = LocalDate.parse("2024-02-29")
        assertEquals(HistoryDateRange(LocalDate.parse("2024-02-01"), leapDay),
            historyDateRange(HistoryRange.THIS_MONTH, leapDay, custom))
    }

    @Test fun focusFilterUsesLocalCompletionDayAtMidnight() {
        val china = ZoneId.of("Asia/Shanghai")
        val before = FocusRecord("before", current.id, 60, Instant.parse("2026-09-15T15:59:59Z").toEpochMilli())
        val start = FocusRecord("start", current.id, 60, Instant.parse("2026-09-15T16:00:00Z").toEpochMilli())
        val end = FocusRecord("end", current.id, 60, Instant.parse("2026-09-16T15:59:59Z").toEpochMilli())
        val after = FocusRecord("after", current.id, 60, Instant.parse("2026-09-16T16:00:00Z").toEpochMilli())
        val data = AppData(plans = listOf(current), focusRecords = listOf(before, start, end, after))
        assertEquals(listOf("end", "start"), HistoryQuery(range = HistoryDateRange(today, today))
            .focusRecords(data, china).map { it.id })
        assertEquals(today, focusRecordDate(start, china))
        assertEquals(today.minusDays(1), focusRecordDate(start, ZoneId.of("UTC")))
    }

    @Test fun focusSearchIncludesFreeFocusAndKeepsTaskHistory() {
        val data = AppData(plans = listOf(old, current), focusRecords = listOf(
            FocusRecord("f-old", old.id, 600, 10), FocusRecord("f-new", current.id, 1200, 30),
            FocusRecord("f-free", null, 300, 20),
        ))
        assertEquals(listOf("f-new", "f-old"), HistoryQuery(seriesId = "english").focusRecords(data).map { it.id })
        assertEquals(listOf("f-free"), HistoryQuery(text = " 自由 ").focusRecords(data).map { it.id })
        assertEquals(listOf("f-new"), HistoryQuery("english", "english").focusRecords(data).map { it.id })
        assertEquals(listOf("f-new", "f-free", "f-old"), HistoryQuery().focusRecords(data).map { it.id })
    }

    @Test fun focusDurationDoesNotRoundSmallOrPartialMinutesAway() {
        assertEquals("0 秒", historyFocusDuration(0))
        assertEquals("59 秒", historyFocusDuration(59))
        assertEquals("25 分钟", historyFocusDuration(1500))
        assertEquals("25 分 5 秒", historyFocusDuration(1505))
    }
}
