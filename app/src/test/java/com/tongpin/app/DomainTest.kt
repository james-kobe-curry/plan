package com.tongpin.app

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    private val monday = LocalDate.of(2026, 9, 14)
    private val plan = Plan(id = "study-1", title = "阅读", category = Category.STUDY, target = 20, unit = "页", startDate = monday.toString())

    private fun rejects(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java, block)
    }

    @Test fun recurrenceUsesIsoWeekdaysAndIncludesStartDate() {
        val selected = plan.copy(weekdays = setOf(1, 3, 7))
        assertTrue(isScheduled(selected, monday))
        assertFalse(isScheduled(selected, monday.plusDays(1)))
        assertTrue(isScheduled(selected, monday.plusDays(2)))
        assertTrue(isScheduled(selected, monday.plusDays(6)))
        assertFalse(isScheduled(selected, monday.minusDays(7)))
    }

    @Test fun futurePlansDoNotChangePastProgress() {
        val data = AppData(plans = listOf(plan.copy(startDate = monday.plusDays(1).toString())))
        assertEquals(0 to 0, dayProgress(data, monday))
        assertEquals(0 to 1, dayProgress(data, monday.plusDays(1)))
    }

    @Test fun archivedPeriodKeepsPastProgressAndHasExclusiveEnd() {
        val archived = plan.copy(archived = true, endDate = monday.plusDays(2).toString())
        val data = AppData(plans = listOf(archived), checkIns = listOf(CheckIn(plan.id, monday.toString(), 20)))
        assertEquals(1 to 1, dayProgress(data, monday))
        assertEquals(0 to 1, dayProgress(data, monday.plusDays(1)))
        assertEquals(0 to 0, dayProgress(data, monday.plusDays(2)))
        assertFalse(isScheduled(plan.copy(archived = true), monday))
    }

    @Test fun replacingPlanPreservesItsHistoricalTarget() {
        val replacementDate = monday.plusDays(1)
        val data = AppData(
            plans = listOf(
                plan.copy(archived = true, endDate = replacementDate.toString()),
                plan.copy(id = "study-2", target = 40, startDate = replacementDate.toString()),
            ),
            checkIns = listOf(CheckIn(plan.id, monday.toString(), 20)),
        )
        assertEquals(1 to 1, dayProgress(data, monday))
        assertEquals(0 to 1, dayProgress(data, replacementDate))
    }

    @Test fun revisingUnitsPreservesTodayAndEarlierEntriesOnTheirOriginalPlan() {
        val today = monday.plusDays(1)
        val original = AppData(
            plans = listOf(plan),
            checkIns = listOf(
                CheckIn(plan.id, monday.toString(), 20, "昨天读了 20 页", 100L),
                CheckIn(plan.id, today.toString(), 12, "今天读了 12 页", 200L),
            ),
            focusRecords = listOf(FocusRecord("focus-1", plan.id, 1500, 300L)),
        )
        val revised = revisePlan(original, plan.id, plan.copy(title = "阅读时间", target = 10, unit = "分钟"), today)
        val replacement = revised.plans.single { !it.archived }
        val historical = revised.plans.single { it.id == plan.id }
        assertEquals(original.checkIns, revised.checkIns)
        assertEquals(original.focusRecords, revised.focusRecords)
        assertEquals("页", historical.unit)
        assertEquals(20, historical.target)
        assertEquals(today.plusDays(1).toString(), historical.endDate)
        assertEquals(historical.endDate, replacement.startDate)
        assertNotEquals(plan.id, replacement.id)
        assertEquals("分钟", replacement.unit)
        assertNull(replacement.endDate)
        assertFalse(replacement.archived)
        assertEquals(0, amountFor(revised, replacement.id, today))
        assertEquals(1 to 1, dayProgress(revised, monday))
        assertEquals(0 to 1, dayProgress(revised, today))
        assertEquals(0 to 1, dayProgress(revised, today.plusDays(1)))
    }

    @Test fun removingTodaysWeekdayAfterLoggingPreservesTodaysProgress() {
        val original = AppData(plans = listOf(plan), checkIns = listOf(CheckIn(plan.id, monday.toString(), 20)))
        val revised = revisePlan(original, plan.id, plan.copy(weekdays = setOf(3)), monday)
        assertEquals(1 to 1, dayProgress(revised, monday))
        assertEquals(0 to 0, dayProgress(revised, monday.plusDays(1)))
        assertEquals(0 to 1, dayProgress(revised, monday.plusDays(2)))
        assertEquals(monday.plusDays(1).toString(), revised.plans.single { !it.archived }.startDate)
    }

    @Test fun revisionWithoutTodaysEntryTakesEffectToday() {
        val today = monday.plusDays(1)
        val original = AppData(plans = listOf(plan), checkIns = listOf(CheckIn(plan.id, monday.toString(), 20)))
        val revised = revisePlan(original, plan.id, plan.copy(weekdays = setOf(3), target = 50), today)
        val replacement = revised.plans.single { !it.archived }
        assertEquals(today.toString(), replacement.startDate)
        assertEquals(today.toString(), revised.plans.single { it.archived }.endDate)
        assertEquals(original.checkIns, revised.checkIns)
        assertEquals(1 to 1, dayProgress(revised, monday))
        assertEquals(0 to 0, dayProgress(revised, today))
    }

    @Test fun revisingAndArchivingFuturePlansNeverEndsBeforeTheirStart() {
        val future = plan.copy(startDate = monday.plusDays(7).toString())
        val original = AppData(plans = listOf(future))
        val revised = revisePlan(original, future.id, future.copy(target = 30), monday)
        assertEquals(future.startDate, revised.plans.single { it.archived }.endDate)
        assertEquals(future.startDate, revised.plans.single { !it.archived }.startDate)
        assertEquals(0 to 0, dayProgress(revised, monday))
        assertEquals(0 to 1, dayProgress(revised, monday.plusDays(7)))
        val archived = archivePlan(original, future.id, monday)
        assertEquals(future.startDate, archived.plans.single().endDate)
        assertEquals(0 to 0, dayProgress(archived, monday.plusDays(7)))
        assertEquals(archived, DataCodec.decode(DataCodec.encode(archived)))
    }

    @Test fun archivePreservesLoggedTodayAndExcludesTomorrow() {
        val original = AppData(plans = listOf(plan), checkIns = listOf(CheckIn(plan.id, monday.toString(), 25)))
        val archived = archivePlan(original, plan.id, monday)
        assertEquals(original.checkIns, archived.checkIns)
        assertEquals(monday.plusDays(1).toString(), archived.plans.single().endDate)
        assertEquals(1 to 1, dayProgress(archived, monday))
        assertEquals(0 to 0, dayProgress(archived, monday.plusDays(1)))
        assertEquals(archived, archivePlan(archived, plan.id, monday.plusDays(2)))
    }

    @Test fun archiveWithoutTodaysEntryEndsTodayAndPreservesPastCompletion() {
        val original = AppData(plans = listOf(plan), checkIns = listOf(CheckIn(plan.id, monday.toString(), 20)))
        val archived = archivePlan(original, plan.id, monday.plusDays(1))
        assertEquals(monday.plusDays(1).toString(), archived.plans.single().endDate)
        assertEquals(1 to 1, dayProgress(archived, monday))
        assertEquals(0 to 0, dayProgress(archived, monday.plusDays(1)))
    }

    @Test fun planChangesRejectMissingPlansAndInvalidReplacements() {
        val original = AppData(plans = listOf(plan))
        rejects { revisePlan(original, "missing", plan, monday) }
        rejects { archivePlan(original, "missing", monday) }
        rejects { revisePlan(original, plan.id, plan.copy(unit = ""), monday) }
        rejects { revisePlan(archivePlan(original, plan.id, monday), plan.id, plan, monday) }
        assertFalse(original.plans.single().archived)
    }

    @Test fun emptyAndUnscheduledDaysHaveNoArtificialCompletion() {
        assertEquals(0 to 0, dayProgress(AppData(), monday))
        assertEquals(0 to 0, dayProgress(AppData(plans = listOf(plan.copy(weekdays = setOf(2)))), monday))
        assertEquals(0, amountFor(AppData(), plan.id, monday))
    }

    @Test fun checkInIsAnUpsertAndZeroRemovesIt() {
        var data = AppData(plans = listOf(plan))
        data = setCheckIn(data, plan.id, monday, 10, "第一阶段")
        assertEquals(0 to 1, dayProgress(data, monday))
        data = setCheckIn(data, plan.id, monday, 25, "完成")
        assertEquals(1, data.checkIns.size)
        assertEquals(25, amountFor(data, plan.id, monday))
        assertEquals("完成", data.checkIns.single().note)
        assertEquals(1 to 1, dayProgress(data, monday))
        data = setCheckIn(data, plan.id, monday, 0)
        assertTrue(data.checkIns.isEmpty())
        assertEquals(0 to 1, dayProgress(data, monday))
    }

    @Test fun changingOneEntryPreservesOtherDatesAndPlans() {
        val other = plan.copy(id = "other", seriesId = "other")
        var data = AppData(plans = listOf(plan, other))
        data = setCheckIn(data, plan.id, monday, 5)
        data = setCheckIn(data, plan.id, monday.plusDays(1), 10)
        data = setCheckIn(data, other.id, monday, 20)
        data = setCheckIn(data, plan.id, monday, 30)
        assertEquals(3, data.checkIns.size)
        assertEquals(10, amountFor(data, plan.id, monday.plusDays(1)))
        assertEquals(20, amountFor(data, other.id, monday))
        assertEquals(2 to 2, dayProgress(data, monday))
    }

    @Test fun invalidCheckInCannotEnterData() {
        val data = AppData(plans = listOf(plan))
        rejects { setCheckIn(data, "missing", monday, 1) }
        rejects { setCheckIn(data, plan.id, monday.minusDays(1), 1) }
        rejects { setCheckIn(data, plan.id, monday, -1) }
        rejects { setCheckIn(data, plan.id, monday, DomainValidation.MAX_AMOUNT + 1) }
        rejects { setCheckIn(data, plan.id, monday, 1, "x".repeat(DomainValidation.MAX_NOTE_LENGTH + 1)) }
    }

    @Test fun datesRequireRealCanonicalIsoDates() {
        assertEquals(LocalDate.of(2024, 2, 29), DomainValidation.date("2024-02-29"))
        listOf("2026-02-29", "2026-13-01", "2026-9-01", "0000-01-01", "2026-09-01T00:00:00", " 2026-09-01").forEach {
            rejects { DomainValidation.date(it) }
        }
    }

    @Test fun validatesPlanBoundsAndSchedules() {
        listOf(
            plan.copy(title = " "), plan.copy(title = "x".repeat(81)), plan.copy(unit = ""),
            plan.copy(target = 0), plan.copy(target = DomainValidation.MAX_TARGET + 1),
            plan.copy(weekdays = emptySet()), plan.copy(weekdays = setOf(0, 1)),
            plan.copy(id = "bad id"), plan.copy(endDate = monday.minusDays(1).toString()),
        ).forEach { invalid -> rejects { DomainValidation.plan(invalid) } }
        DomainValidation.plan(plan.copy(endDate = plan.startDate, archived = true))
    }

    @Test fun rejectsDuplicateIdsEntriesAndDanglingReferences() {
        rejects { DomainValidation.data(AppData(plans = listOf(plan, plan))) }
        val entry = CheckIn(plan.id, monday.toString(), 1)
        rejects { DomainValidation.data(AppData(plans = listOf(plan), checkIns = listOf(entry, entry))) }
        rejects { DomainValidation.data(AppData(checkIns = listOf(entry))) }
        val focus = FocusRecord(id = "focus-1", planId = plan.id, seconds = 60)
        rejects { DomainValidation.data(AppData(plans = listOf(plan), focusRecords = listOf(focus, focus))) }
        rejects { DomainValidation.data(AppData(focusRecords = listOf(focus))) }
        rejects { DomainValidation.data(AppData(focusRecords = listOf(focus.copy(planId = null, seconds = 0)))) }
    }

    @Test fun fullBackupRoundTripsUnicodeNotesAndAllHistory() {
        val original = AppData(
            plans = listOf(plan.copy(title = "阅读 📚 \"专注\"", archived = true, endDate = monday.plusDays(1).toString())),
            checkIns = listOf(CheckIn(plan.id, monday.toString(), 21, "章节一\n路径 C:\\书籍\t复盘", 100L)),
            focusRecords = listOf(FocusRecord("focus-1", plan.id, 1500, 200L), FocusRecord("focus-2", null, 60, 300L)),
            nickname = "小同学",
        )
        assertEquals(original, DataCodec.decode(DataCodec.encode(original)))
        assertEquals(AppData(), DataCodec.decode(DataCodec.encode(AppData())))
    }

    @Test fun planTransferStartsFreshAndDoesNotLeakLogs() {
        val archived = plan.copy(archived = true, endDate = monday.plusDays(1).toString())
        val encoded = DataCodec.encodePlans(listOf(archived))
        assertFalse(encoded.contains("checkIns"))
        assertFalse(encoded.contains("focusRecords"))
        val imported = DataCodec.decodePlans(encoded).single()
        assertNotEquals(plan.id, imported.id)
        assertEquals(plan.title, imported.title)
        assertEquals(plan.weekdays, imported.weekdays)
        assertEquals(LocalDate.now().toString(), imported.startDate)
        assertFalse(imported.archived)
        assertNull(imported.endDate)
        assertNotEquals(imported.id, DataCodec.decodePlans(encoded).single().id)
    }

    @Test fun importEnforcesFormatVersionAndFieldTypes() {
        val valid = DataCodec.encodePlans(listOf(plan))
        listOf(
            valid.replace("\"version\":8", "\"version\":9"),
            valid.replace("\"version\":8", "\"version\":\"3\""),
            valid.replace("\"target\":20", "\"target\":\"20\""),
            valid.replace("\"target\":20", "\"target\":20.0"),
            valid.replace("\"target\":20", "\"target\":0"),
            valid.replace("\"archived\":false", "\"archived\":\"false\""),
            valid.replace("\"STUDY\"", "\"UNKNOWN\""),
            valid.replace("\"endDate\":null", "\"endDate\":23"),
            valid.replace("\"endDate\":null", "\"extra\":null"),
            valid.replace("[1,2,3,4,5,6,7]", "[1,1]"),
        ).forEach { invalid -> rejects { DataCodec.decodePlans(invalid) } }
        rejects { DataCodec.decode(valid) }
        rejects { DataCodec.decodePlans(DataCodec.encode(AppData())) }
    }

    @Test fun parserRejectsDuplicateFieldsTrailingContentAndLenientJson() {
        val valid = DataCodec.encodePlans(listOf(plan))
        listOf(
            valid.replace("\"version\":8", "\"version\":3,\"version\":3"),
            valid + "{}", valid.dropLast(1) + ",}",
            valid.replace("\"version\"", "version"),
            valid.replace("\"version\"", "'version'"),
            valid.replace("\"version\":8", "\"version\":03"),
            valid.replace("\"version\":8", "\"version\":3e0"),
            valid.replace("阅读", "\\uD800"),
        ).forEach { invalid -> rejects { DataCodec.decodePlans(invalid) } }
    }

    @Test fun transferRejectsDuplicatePlanIdsBeforeAssigningNewIds() {
        val valid = DataCodec.encodePlans(listOf(plan, plan.copy(id = "study-2")))
        rejects { DataCodec.decodePlans(valid.replace("study-2", "study-1")) }
    }

    @Test fun invalidUnicodeCannotProduceAnUnreadableBackup() {
        rejects { DataCodec.encodePlans(listOf(plan.copy(title = "bad\uD800"))) }
        rejects { DataCodec.encode(AppData(nickname = "bad\uDC00")) }
    }

    @Test fun rejectsOversizedFilesAndExcessiveCounts() {
        rejects { DataCodec.decodePlans(" ".repeat(DataCodec.MAX_BYTES + 1)) }
        rejects { DataCodec.encodePlans((0..DomainValidation.MAX_PLANS).map { plan.copy(id = "plan-$it") }) }
        rejects { DataCodec.decodePlans("{\"format\":\"tongpin-plans\",\"version\":1,\"plans\":[]}") }
    }
}
