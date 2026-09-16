package com.tongpin.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Cross-feature regressions discovered while auditing the complete 1.12 feature set. */
class FocusPersonalAuditTest {
    private val day = LocalDate.of(2026, 9, 16)
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun task(id: String = "study") = Plan(
        id = id, title = "学习任务", category = Category.STUDY, target = 1, unit = "次",
        tracking = TrackingMode.TASK, startDate = "2026-09-01",
    )

    @Test fun completedFocusDoesNotCountAsTaskCheckInOrSuppressTaskReminder() {
        val plan = task().copy(reminderTime = "20:00")
        val now = day.atTime(19, 0).atZone(zone).toInstant().toEpochMilli()
        val data = AppData(plans = listOf(plan), focusRecords = listOf(FocusRecord("focus", plan.id, 1800, now)))
        val card = ProgressCardRules.make(data, day, ProgressCardOptions(), zone)
        assertEquals(1800L, card.focusSeconds)
        assertEquals(0, card.completed)
        assertEquals(1, card.scheduled)
        assertEquals("未打卡", card.rows.single().status)
        assertEquals(day.toString(), PlanReminderRules.candidates(data, emptyMap(), now, zone).single().date)
        assertTrue(data.checkIns.isEmpty())
    }

    @Test fun archivingATaskPreservesItsFocusInDailyCardWithoutOfferingArchivedTask() {
        val plan = task()
        val now = day.atTime(19, 0).atZone(zone).toInstant().toEpochMilli()
        val original = AppData(plans = listOf(plan), focusRecords = listOf(FocusRecord("focus", plan.id, 2700, now)))
        val archived = archivePlan(original, plan.id, day)
        assertTrue(focusPlansOrdered(archived).isEmpty())
        assertEquals(original.focusRecords, archived.focusRecords)
        assertEquals(2700L, ProgressCardRules.make(archived, day, ProgressCardOptions(), zone).focusSeconds)
    }

    @Test fun nextRevisionUsesNewTimeTargetWhilePreservingOldFocusAndManualMemory() {
        val old = task().copy(tracking = TrackingMode.QUANTITY, target = 45, unit = "分钟")
        val now = day.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val original = AppData(plans = listOf(old), checkIns = listOf(CheckIn(old.id, day.toString(), 10)),
            focusRecords = listOf(FocusRecord("focus", old.id, 600, now)))
        val updated = revisePlan(original, old.id, old.copy(target = 150, unit = "小时", scale = 2), day)
        val selected = focusPlansOrdered(updated).single()
        val preferences = FocusPreferences(lastMinutes = 70)
        assertEquals(day.plusDays(1).toString(), selected.startDate)
        assertEquals(90, focusMinutesForPlan(selected, preferences).minutes)
        assertEquals(70, focusMinutesForPlan(null, preferences).minutes)
        assertEquals(original.focusRecords, updated.focusRecords)
        assertEquals(600L, ProgressCardRules.make(updated, day, ProgressCardOptions(), zone).focusSeconds)
    }

    @Test fun removingCustomCategoryPreservesFocusSelectionOrderAndCardStatistics() {
        val category = CustomCategory("language", "外语")
        val first = task("first").copy(customCategoryId = category.id, pinned = true)
        val second = task("second").copy(title = "运动任务", category = Category.FITNESS, customCategoryId = category.id)
        val original = AppData(plans = listOf(second, first), categories = listOf(category),
            checkIns = listOf(CheckIn(first.id, day.toString(), 1)))
        val updated = deleteCustomCategory(original, category.id)
        assertEquals(listOf(first.id, second.id), focusPlansOrdered(updated).map { it.id })
        assertEquals(original.checkIns, updated.checkIns)
        assertEquals("学习", planCategoryName(updated.plans.first { it.id == first.id }, updated))
        assertEquals("健身", planCategoryName(updated.plans.first { it.id == second.id }, updated))
        assertEquals(ProgressCardRules.make(original, day, ProgressCardOptions(), zone),
            ProgressCardRules.make(updated, day, ProgressCardOptions(), zone))
    }

    @Test fun hiddenTitlesRedactCurrentAndArchivedRevisionNamesInDailyNote() {
        val old = task().copy(title = "旧版秘密名称", archived = true, endDate = day.toString(), superseded = true)
        val current = old.copy(id = "new", title = "新版秘密名称", archived = false, startDate = day.toString(), endDate = null, superseded = false)
        val data = AppData(plans = listOf(old, current),
            dailyNotes = listOf(DailyNote(day.toString(), "旧版秘密名称完成后，改为新版秘密名称", 1)))
        val card = ProgressCardRules.make(data, day, ProgressCardOptions(showTitles = false, showNotes = true), zone)
        assertEquals("任务 1", card.rows.single().title)
        assertEquals("该任务完成后，改为该任务", card.notes.single().text)
        assertFalse(card.toString().contains(old.title))
        assertFalse(card.toString().contains(current.title))
        assertEquals("旧版秘密名称完成后，改为新版秘密名称", data.dailyNotes.single().text)
    }

    @Test fun autumnClockOverlapIncludesBothFocusSessionsInOneLocalDay() {
        val ny = ZoneId.of("America/New_York")
        val date = LocalDate.of(2026, 11, 1)
        fun stamp(value: String) = Instant.parse(value).toEpochMilli()
        val data = AppData(focusRecords = listOf(
            FocusRecord("earlier", seconds = 1800, completedAt = stamp("2026-11-01T05:30:00Z")),
            FocusRecord("later", seconds = 2700, completedAt = stamp("2026-11-01T06:30:00Z")),
            FocusRecord("next-day", seconds = 900, completedAt = stamp("2026-11-02T05:00:00Z")),
        ))
        assertEquals(4500L, ProgressCardRules.make(data, date, ProgressCardOptions(), ny).focusSeconds)
        assertEquals(900L, ProgressCardRules.make(data, date.plusDays(1), ProgressCardOptions(), ny).focusSeconds)
    }

    @Test fun leapDayWeeklyCardIncludesEveryCalendarDateWithoutSpillingNotes() {
        val end = LocalDate.of(2028, 3, 1)
        val data = AppData(dailyNotes = listOf(
            DailyNote("2028-02-24", "第一天", 1),
            DailyNote("2028-02-29", "闰日", 2),
            DailyNote("2028-03-01", "最后一天", 3),
            DailyNote("2028-02-23", "范围外", 4),
        ))
        val card = ProgressCardRules.make(data, end, ProgressCardOptions(weekly = true, showNotes = true), zone)
        assertEquals((0L..6L).map { LocalDate.of(2028, 2, 24).plusDays(it) }, card.days.map { it.date })
        assertEquals(listOf("最后一天", "闰日", "第一天"), card.notes.map { it.text })
        assertFalse(card.toString().contains("范围外"))
    }
}
