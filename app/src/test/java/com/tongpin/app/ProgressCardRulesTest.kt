package com.tongpin.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ProgressCardRulesTest {
    private val date = LocalDate.of(2026, 9, 2)
    private fun plan(id: String = "study", title: String = "秘密学习计划") = Plan(id = id, title = title,
        category = Category.STUDY, target = 1, unit = "次", tracking = TrackingMode.TASK,
        startDate = "2026-08-01")
    private fun model(data: AppData, options: ProgressCardOptions = ProgressCardOptions()) = ProgressCardRules.make(data, date, options, ZoneId.of("UTC"))

    @Test fun defaultDetailedNamesVisibleNotesAbsent() {
        val value = model(AppData(plans = listOf(plan()), checkIns = listOf(CheckIn("study", "$date", 1, "绝密心得"))))
        assertTrue(value.detailed)
        assertEquals("秘密学习计划", value.rows.single().title)
        assertFalse(value.notesRequested)
        assertTrue(value.notes.isEmpty())
        assertFalse(value.toString().contains("绝密心得"))
    }

    @Test fun hiddenNamesAndNotesNeverEnterRendererOrExportMetadata() {
        val value = model(AppData(plans = listOf(plan()), checkIns = listOf(CheckIn("study", "$date", 1, "绝密心得"))),
            ProgressCardOptions(showTitles = false, showNotes = false))
        assertEquals("任务 1", value.rows.single().title)
        listOf(value.toString(), value.filePrefix, value.description, value.title).forEach { output ->
            assertFalse(output.contains("秘密学习计划"))
            assertFalse(output.contains("绝密心得"))
        }
    }

    @Test fun namesRemainHiddenWhenVisibleNotesMentionThem() {
        val value = model(AppData(plans = listOf(plan(), plan("fitness", "秘密锻炼计划")),
            checkIns = listOf(CheckIn("study", "$date", 1, "秘密学习计划完成后开始秘密锻炼计划"))),
            ProgressCardOptions(showTitles = false, showNotes = true))
        assertFalse(value.toString().contains("秘密学习计划"))
        assertFalse(value.toString().contains("秘密锻炼计划"))
        assertEquals("该任务完成后开始该任务", value.notes.single().text)
        assertEquals("$date", value.notes.single().heading)
    }

    @Test fun simpleContainsOnlySummaryAndNoPrivateDetailsEvenIfTogglesAreOn() {
        val value = model(AppData(plans = listOf(plan()), checkIns = listOf(CheckIn("study", "$date", 1, "绝密心得"))),
            ProgressCardOptions(style = ProgressCardStyle.SIMPLE, showTitles = true, showNotes = true))
        assertFalse(value.detailed)
        assertTrue(value.rows.isEmpty())
        assertTrue(value.notes.isEmpty())
        assertEquals(0, value.extraRows)
        assertEquals(1, value.completed)
        assertEquals(1, value.scheduled)
        assertFalse(value.toString().contains("秘密学习计划"))
        assertFalse(value.toString().contains("绝密心得"))
    }

    @Test fun emptyDataKeepsSevenDatesAndZeroSummary() {
        val value = model(AppData(), ProgressCardOptions(weekly = true, showNotes = true))
        assertEquals(7, value.days.size)
        assertEquals(0, value.completed)
        assertEquals(0, value.scheduled)
        assertEquals(0L, value.focusSeconds)
        assertTrue(value.rows.isEmpty())
        assertTrue(value.notes.isEmpty())
        assertTrue(value.notesRequested)
    }

    @Test fun maximumEightRowsDoesNotTruncateTotals() {
        val plans = (1..11).map { plan("p$it", "任务名称$it") }
        val entries = plans.map { CheckIn(it.id, "$date", 1) }
        val value = model(AppData(plans = plans, checkIns = entries))
        assertEquals(8, value.rows.size)
        assertEquals(3, value.extraRows)
        assertEquals(11, value.completed)
        assertEquals(11, value.scheduled)
        assertFalse(value.toString().contains("任务名称9"))
    }

    @Test fun noteLimitUsesRecentEntriesAndCountsRemainder() {
        val plans = (1..10).map { plan("p$it", "学习$it") }
        val entries = plans.mapIndexed { index, p -> CheckIn(p.id, "$date", 1, "心得$index", updatedAt = index.toLong()) }
        val value = model(AppData(plans = plans, checkIns = entries), ProgressCardOptions(showNotes = true))
        assertEquals(8, value.notes.size)
        assertEquals(2, value.extraNotes)
        assertEquals("心得9", value.notes.first().text)
        assertEquals("心得2", value.notes.last().text)
    }

    @Test fun notesSortByRecordDateBeforeEditTime() {
        val value = model(AppData(plans = listOf(plan()), checkIns = listOf(
            CheckIn("study", "$date", 1, "今天", updatedAt = 1),
            CheckIn("study", "${date.minusDays(1)}", 1, "昨天", updatedAt = 999),
        )), ProgressCardOptions(weekly = true, showNotes = true))
        assertEquals(listOf("今天", "昨天"), value.notes.map { it.text })
    }

    @Test fun longChineseAndEmojiAreBoundedWithoutBrokenSurrogates() {
        val title = "学习😀".repeat(20)
        val note = "回顾😀".repeat(100)
        val value = model(AppData(plans = listOf(plan(title = title)), checkIns = listOf(CheckIn("study", "$date", 1, note))),
            ProgressCardOptions(showNotes = true))
        assertEquals(31, value.rows.single().title.codePointCount(0, value.rows.single().title.length))
        assertEquals(101, value.notes.single().text.codePointCount(0, value.notes.single().text.length))
        assertTrue(value.rows.single().title.endsWith("…"))
        assertTrue(value.notes.single().text.endsWith("…"))
        DomainValidation.text(value.rows.single().title, "名称", 80)
        DomainValidation.text(value.notes.single().text, "心得", 1000)
    }

    @Test fun notesNormalizeWhitespaceAndOmitBlankEntries() {
        val value = model(AppData(plans = listOf(plan(), plan("other", "其他")), checkIns = listOf(
            CheckIn("study", "$date", 1, " 一行\n第二行\t末尾 "),
            CheckIn("other", "$date", 1, "\n\t  "),
        )), ProgressCardOptions(showNotes = true))
        assertEquals("一行 第二行 末尾", value.notes.single().text)
    }

    @Test fun weeklyRangeCrossesMonthAndPreservesDailyCompletion() {
        val value = model(AppData(plans = listOf(plan()), checkIns = listOf(
            CheckIn("study", "2026-08-31", 1), CheckIn("study", "2026-09-02", 1))), ProgressCardOptions(weekly = true))
        assertEquals(LocalDate.of(2026, 8, 27), value.start)
        assertEquals(date, value.end)
        assertEquals(7, value.scheduled)
        assertEquals(2, value.completed)
        assertEquals(listOf(0, 0, 0, 0, 1, 0, 1), value.days.map { it.completed })
        assertEquals("2/7 天", value.rows.single().status)
        assertEquals("2026-08-27 至 2026-09-02 的 plan 进度卡", value.description)
    }

    @Test fun skippedAndPausedDaysDoNotEnterDenominatorOrNotes() {
        val p = plan().copy(skips = listOf(PlanSkip("2026-08-31")), pauses = listOf(PlanPause("2026-08-28", "2026-08-30")))
        val value = model(AppData(plans = listOf(p), checkIns = listOf(
            CheckIn("study", "2026-08-31", 1, "跳过时的隐藏备注"),
            CheckIn("study", "2026-08-28", 1, "暂停时的隐藏备注"),
            CheckIn("study", "$date", 1, "当天心得"))), ProgressCardOptions(weekly = true, showNotes = true))
        assertEquals(4, value.scheduled)
        assertEquals(1, value.completed)
        assertEquals(listOf("当天心得"), value.notes.map { it.text })
    }

    @Test fun archivedVersionStillAppearsForItsScheduledPast() {
        val p = plan().copy(archived = true, endDate = "2026-09-01")
        val value = model(AppData(plans = listOf(p), checkIns = listOf(CheckIn("study", "2026-08-31", 1))), ProgressCardOptions(weekly = true))
        assertEquals(5, value.scheduled)
        assertEquals(1, value.completed)
        assertEquals("1/5 天", value.rows.single().status)
    }

    @Test fun quantityTotalsKeepDecimalScaleAndDoNotRound() {
        val p = plan().copy(tracking = TrackingMode.QUANTITY, unit = "公里", target = 150, scale = 2)
        val data = AppData(plans = listOf(p), checkIns = listOf(CheckIn("study", "$date", 175), CheckIn("study", "2026-09-01", 120)))
        assertEquals("1.75 / 1.5 公里", model(data).rows.single().detail)
        assertEquals("累计 2.95 公里 · 完成 1/7 天", model(data, ProgressCardOptions(weekly = true)).rows.single().detail)
    }

    @Test fun focusUsesInclusiveCalendarDatesInProvidedZone() {
        val zone = ZoneId.of("Asia/Shanghai")
        fun timestamp(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val data = AppData(focusRecords = listOf(
            FocusRecord(seconds = 1800, completedAt = timestamp(date)),
            FocusRecord(seconds = 600, completedAt = timestamp(date.plusDays(1)) - 1),
            FocusRecord(seconds = 900, completedAt = timestamp(date.plusDays(1))),
            FocusRecord(seconds = 900, completedAt = timestamp(date) - 1),
        ))
        assertEquals(2400L, ProgressCardRules.make(data, date, ProgressCardOptions(), zone).focusSeconds)
    }

    @Test fun recordsOutsideSelectedRangeNeverAppear() {
        val value = model(AppData(plans = listOf(plan()), checkIns = listOf(
            CheckIn("study", "2026-09-01", 1, "旧心得"), CheckIn("study", "2026-09-03", 1, "新心得"))), ProgressCardOptions(showNotes = true))
        assertEquals(0, value.completed)
        assertTrue(value.notes.isEmpty())
    }

    @Test fun exportMetadataUsesDatesAndCardKindOnly() {
        val value = model(AppData(nickname = "私人昵称", plans = listOf(plan())), ProgressCardOptions(showNotes = true))
        assertEquals("plan-daily-2026-09-02", value.filePrefix)
        assertEquals("2026-09-02 的 plan 进度卡", value.description)
        assertEquals("每日进度卡", value.title)
        assertFalse(value.filePrefix.contains("私人昵称"))
        assertFalse(value.description.contains("秘密"))
    }

    @Test fun changedNicknameIsKeptForHeader() {
        assertEquals("新昵称", model(AppData(nickname = "新昵称")).nickname)
    }

    @Test fun dailyNoteCanBeSharedWithoutAnyTaskOrCheckIn() {
        val value = model(AppData(dailyNotes = listOf(DailyNote("$date", "今天给自己留一点时间", 100))), ProgressCardOptions(showNotes = true))
        assertEquals(listOf(ProgressCardNote("$date · 每日心得", "今天给自己留一点时间")), value.notes)
        assertTrue(value.rows.isEmpty())
        assertEquals(0, value.completed)
        assertEquals(0, value.scheduled)
        assertEquals(0L, value.focusSeconds)
        assertTrue(value.notesRequested)
    }

    @Test fun dailyNotesUseTheSelectedDateAndInclusiveSevenDayRange() {
        val data = AppData(dailyNotes = listOf(
            DailyNote("${date.minusDays(7)}", "范围外的过去", 100),
            DailyNote("${date.minusDays(6)}", "范围的第一天", 200),
            DailyNote("${date.minusDays(1)}", "昨天的记录", 300),
            DailyNote("$date", "当天的记录", 400),
            DailyNote("${date.plusDays(1)}", "范围外的未来", 500),
        ))
        assertEquals(listOf("当天的记录"), model(data, ProgressCardOptions(showNotes = true)).notes.map { it.text })
        val weekly = model(data, ProgressCardOptions(weekly = true, showNotes = true))
        assertEquals(listOf("当天的记录", "昨天的记录", "范围的第一天"), weekly.notes.map { it.text })
        assertFalse(weekly.toString().contains("范围外"))
    }

    @Test fun dailyAndTaskNotesShareDateThenEditTimeOrdering() {
        val value = model(AppData(plans = listOf(plan()),
            checkIns = listOf(CheckIn("study", "$date", 1, "当天较早的任务心得", 100),
                CheckIn("study", "${date.minusDays(1)}", 1, "昨天后来修改的任务心得", 900)),
            dailyNotes = listOf(DailyNote("$date", "当天较晚的每日心得", 200),
                DailyNote("${date.minusDays(1)}", "昨天较早的每日心得", 300))),
            ProgressCardOptions(weekly = true, showNotes = true))
        assertEquals(listOf("当天较晚的每日心得", "当天较早的任务心得", "昨天后来修改的任务心得", "昨天较早的每日心得"), value.notes.map { it.text })
    }

    @Test fun dailyAndTaskNotesUseOneEightItemLimitAndRemainderCount() {
        val plans = (1..9).map { plan("p$it", "任务$it") }
        val entries = plans.mapIndexed { index, p -> CheckIn(p.id, "$date", 1, "任务心得$index", index.toLong()) }
        val value = model(AppData(plans = plans, checkIns = entries,
            dailyNotes = listOf(DailyNote("$date", "当天日记", 100))), ProgressCardOptions(showNotes = true))
        assertEquals(8, value.notes.size)
        assertEquals(2, value.extraNotes)
        assertEquals("当天日记", value.notes.first().text)
        assertEquals("任务心得2", value.notes.last().text)
        assertFalse(value.toString().contains("任务心得1"))
        assertEquals(9, value.completed)
        assertEquals(9, value.scheduled)
    }

    @Test fun dailyNotesNeverLeakIntoDefaultHiddenOrSimpleCards() {
        val secret = "私人的每日心情"
        val data = AppData(plans = listOf(plan()), dailyNotes = listOf(DailyNote("$date", secret, 100)))
        val choices = listOf(ProgressCardOptions(), ProgressCardOptions(showNotes = false),
            ProgressCardOptions(weekly = true, showNotes = false),
            ProgressCardOptions(style = ProgressCardStyle.SIMPLE, showNotes = true),
            ProgressCardOptions(weekly = true, style = ProgressCardStyle.SIMPLE, showNotes = true))
        choices.forEach { options ->
            val value = model(data, options)
            assertFalse(value.notesRequested)
            assertTrue(value.notes.isEmpty())
            assertEquals(0, value.extraNotes)
            listOf(value.toString(), value.filePrefix, value.description, value.title).forEach { output -> assertFalse(output.contains(secret)) }
        }
    }

    @Test fun hidingTaskNamesAlsoRedactsDailyNotesBeforeTruncation() {
        val value = model(AppData(plans = listOf(plan(), plan("other", "秘密锻炼计划")),
            dailyNotes = listOf(DailyNote("$date", "秘密学习计划以后，开始秘密锻炼计划。" + "继续😀".repeat(100), 100))),
            ProgressCardOptions(showTitles = false, showNotes = true))
        assertFalse(value.toString().contains("秘密学习计划"))
        assertFalse(value.toString().contains("秘密锻炼计划"))
        assertEquals("$date · 每日心得", value.notes.single().heading)
        assertTrue(value.notes.single().text.startsWith("该任务以后，开始该任务。"))
        assertEquals(101, value.notes.single().text.codePointCount(0, value.notes.single().text.length))
        DomainValidation.text(value.notes.single().text, "心得", 1000)
    }

    @Test fun dailyNotesDoNotChangeCompletionRowsFocusOrDailyTotals() {
        val original = AppData(plans = listOf(plan(), plan("other", "另一项")),
            checkIns = listOf(CheckIn("study", "$date", 1)),
            focusRecords = listOf(FocusRecord("focus", "study", 600, date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())))
        val enriched = original.copy(dailyNotes = listOf(DailyNote("$date", "这是独立的日记", 100)))
        val options = ProgressCardOptions(weekly = true, showNotes = true)
        val before = model(original, options)
        val after = model(enriched, options)
        assertEquals(before.days, after.days)
        assertEquals(before.completed, after.completed)
        assertEquals(before.scheduled, after.scheduled)
        assertEquals(before.focusSeconds, after.focusSeconds)
        assertEquals(before.rows, after.rows)
        assertEquals(before.extraRows, after.extraRows)
        assertEquals(1, after.notes.size)
    }

    @Test fun dailyNoteOnAnUnscheduledDayIsStillIncludedAndWhitespaceIsNormalized() {
        val p = plan().copy(skips = listOf(PlanSkip("$date")))
        val value = model(AppData(plans = listOf(p), dailyNotes = listOf(DailyNote("$date", " 一行\n第二行\t末尾 ", 100))),
            ProgressCardOptions(showNotes = true))
        assertEquals("一行 第二行 末尾", value.notes.single().text)
        assertEquals(0, value.scheduled)
        assertEquals(0, value.completed)
    }
}
