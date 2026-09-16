package com.tongpin.app

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class QuickUndoTest {
    private val day = LocalDate.of(2026, 9, 14)
    private val task = Plan(id = "read", title = "阅读", category = Category.STUDY,
        target = 10, unit = "页", startDate = day.toString())
    private val initial = AppData(plans = listOf(task))
    private fun entry(amount: Int = 3, note: String = "已读三页", time: Long = 1234L) =
        CheckIn(task.id, day.toString(), amount, note, time)
    private fun checked(data: AppData) = data.also(DomainValidation::data)
    private fun checkUndo(before: AppData, after: AppData) = requireNotNull(checkInUndo(before, after, task.id, day))
    private fun skipUndo(before: AppData, after: AppData) = requireNotNull(skipUndo(before, after, task.id, day))
    private fun pauseUndo(before: AppData, after: AppData) = requireNotNull(pauseUndo(before, after, task.id))
    private fun conflict(block: () -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java, block)
        assertTrue(error.message.orEmpty().contains("无法撤销"))
    }

    @Test fun undoNewCheckInRemovesOnlyTheNewEntry() {
        val after = setCheckIn(initial, task.id, day, 3, "完成第一步")
        assertEquals(initial, applyQuickUndo(after, checkUndo(initial, after)))
    }

    @Test fun undoAccumulatedProgressRestoresExactNoteAndTimestamp() {
        val before = checked(initial.copy(checkIns = listOf(entry())))
        val submission = CheckInSubmission("add-pages", CheckInAmountMode.ADD, 5, "又读了五页",
            draftPlanFingerprint(task), draftCheckInFingerprint(before, task.id, day))
        val after = applyCheckInSubmission(before, task.id, day, submission)
        assertEquals(8, after.checkIns.single().amount)
        assertEquals(before, applyQuickUndo(after, checkUndo(before, after)))
    }

    @Test fun undoCheckInRemovalRestoresOriginalEntryAtItsPosition() {
        val next = entry().copy(date = day.plusDays(1).toString(), updatedAt = 2000)
        val before = checked(initial.copy(checkIns = listOf(entry(), next)))
        val after = setCheckIn(before, task.id, day, 0)
        assertEquals(before, applyQuickUndo(after, checkUndo(before, after)))
    }

    @Test fun ordinaryTaskCompletionCanBeUndone() {
        val before = initial.copy(plans = listOf(task.copy(tracking = TrackingMode.TASK, target = 1, unit = "次")))
        val after = setCheckIn(before, task.id, day, 1)
        assertEquals(before, applyQuickUndo(after, checkUndo(before, after)))
    }

    @Test fun undoPreservesNewerUnrelatedRecordsProfileAndDailyNotes() {
        val after = setCheckIn(initial, task.id, day, 3)
        val undo = checkUndo(initial, after)
        val other = task.copy(id = "other", seriesId = "other", title = "其他任务")
        val later = checked(after.copy(plans = after.plans + other,
            checkIns = after.checkIns + CheckIn(other.id, day.toString(), 2, "保留", 3000) +
                entry(4).copy(date = day.plusDays(1).toString()),
            focusRecords = listOf(FocusRecord("focus", task.id, 600, 2000)),
            nickname = "新昵称", profile = PersonalProfile(motto = "新的签名"), collapseCompleted = true,
            dailyNotes = listOf(DailyNote(day.toString(), "今天的心得", 4000))))
        val restored = applyQuickUndo(later, undo)
        assertEquals(later.copy(checkIns = later.checkIns.drop(1)), restored)
    }

    @Test fun pinningAndReorderingDoNotPreventCheckInUndo() {
        val after = setCheckIn(initial, task.id, day, 3)
        val later = after.copy(plans = listOf(task.copy(pinned = true, sortOrder = 7)))
        assertEquals(later.copy(checkIns = emptyList()), applyQuickUndo(later, checkUndo(initial, after)))
    }

    @Test fun laterEditOfTheSameCheckInCannotBeOverwritten() {
        val after = setCheckIn(initial, task.id, day, 3)
        val later = after.copy(checkIns = listOf(after.checkIns.single().copy(note = "之后编辑过", updatedAt = 3000)))
        conflict { applyQuickUndo(later, checkUndo(initial, after)) }
    }

    @Test fun laterCheckInWithOnlyChangedTimestampStillConflicts() {
        val after = initial.copy(checkIns = listOf(entry()))
        val later = after.copy(checkIns = listOf(entry(time = 9999)))
        conflict { applyQuickUndo(later, checkUndo(initial, after)) }
    }

    @Test fun undoingCheckInTwiceIsRejected() {
        val after = setCheckIn(initial, task.id, day, 3)
        val undo = checkUndo(initial, after)
        conflict { applyQuickUndo(applyQuickUndo(after, undo), undo) }
    }

    @Test fun removedTaskCannotBeResurrectedByUndo() {
        val after = setCheckIn(initial, task.id, day, 3)
        conflict { applyQuickUndo(AppData(), checkUndo(initial, after)) }
    }

    @Test fun archivedTaskCannotBeChangedByEarlierUndo() {
        val after = setCheckIn(initial, task.id, day, 3)
        conflict { applyQuickUndo(archivePlan(after, task.id, day), checkUndo(initial, after)) }
    }

    @Test fun revisedOrRenamedTaskRejectsEarlierUndo() {
        val after = setCheckIn(initial, task.id, day, 3)
        listOf(task.copy(title = "新名称"), task.copy(target = 20)).forEach { proposal ->
            conflict { applyQuickUndo(revisePlan(after, task.id, proposal, day), checkUndo(initial, after)) }
        }
    }

    @Test fun restoredHistoricalSeriesRejectsEarlierUndoEvenIfOriginalVersionIsUnchanged() {
        val archived = task.copy(archived = true, endDate = day.plusDays(1).toString())
        val before = initial.copy(plans = listOf(archived))
        val after = setCheckIn(before, task.id, day, 3)
        val restored = restoreArchivedPlan(after, task.id, day.plusDays(2), day)
        conflict { applyQuickUndo(restored, checkUndo(before, after)) }
    }

    @Test fun otherSkippedDatesArePreservedDuringCheckInUndo() {
        val after = setCheckIn(initial, task.id, day, 3)
        val later = skipPlanDay(after, task.id, day.plusDays(1), "休息")
        assertEquals(later.copy(checkIns = emptyList()), applyQuickUndo(later, checkUndo(initial, after)))
    }

    @Test fun undoRemovedCheckInCannotOverwriteALaterSkip() {
        val before = initial.copy(checkIns = listOf(entry()))
        val after = setCheckIn(before, task.id, day, 0)
        val later = skipPlanDay(after, task.id, day, "已改变安排")
        conflict { applyQuickUndo(later, checkUndo(before, after)) }
    }

    @Test fun undoPauseRemovesTheNewOpenPauseAndKeepsPastPauses() {
        val oldPause = PlanPause(day.minusDays(3).toString(), day.minusDays(1).toString())
        val before = initial.copy(plans = listOf(task.copy(startDate = day.minusDays(7).toString(), pauses = listOf(oldPause))))
        val after = pausePlan(before, task.id, day)
        assertEquals(before, applyQuickUndo(after, pauseUndo(before, after)))
    }

    @Test fun undoPauseAfterProgressRestoresTomorrowWithoutTouchingToday() {
        val before = initial.copy(checkIns = listOf(entry()))
        val after = pausePlan(before, task.id, day)
        assertEquals(day.plusDays(1).toString(), after.plans.single().pauses.single().startDate)
        val later = setCheckIn(after, task.id, day, 8, "暂停后又读了几页")
        val undone = applyQuickUndo(later, pauseUndo(before, after))
        assertTrue(undone.plans.single().pauses.isEmpty())
        assertEquals(later.checkIns, undone.checkIns)
        assertEquals(0 to 1, dayProgress(undone, day.plusDays(1)))
    }

    @Test fun undoPauseAcrossDaysDoesNotManufacturePauseHistory() {
        val after = pausePlan(initial, task.id, day)
        val later = after.copy(dailyNotes = listOf(DailyNote(day.plusDays(2).toString(), "两天后", 9999)))
        val undone = applyQuickUndo(later, pauseUndo(initial, after))
        assertTrue(undone.plans.single().pauses.isEmpty())
        assertEquals(later.dailyNotes, undone.dailyNotes)
        assertEquals(0 to 1, dayProgress(undone, day.plusDays(1)))
    }

    @Test fun resumedPauseCannotBeUndoneUsingOldOperation() {
        val after = pausePlan(initial, task.id, day)
        conflict { applyQuickUndo(resumePlan(after, task.id, day.plusDays(2)), pauseUndo(initial, after)) }
    }

    @Test fun undoPauseRetainsNewerSkipsAndSortOrder() {
        val before = initial.copy(checkIns = listOf(entry()))
        val after = pausePlan(before, task.id, day)
        val later = after.copy(plans = after.plans.map { it.copy(pinned = true, sortOrder = 8,
            skips = listOf(PlanSkip(day.plusDays(3).toString(), "后来的安排"))) })
        val restored = applyQuickUndo(later, pauseUndo(before, after))
        assertEquals(later.plans.single().copy(pauses = emptyList()), restored.plans.single())
        assertEquals(later.checkIns, restored.checkIns)
    }

    @Test fun undoSkipOnlyRestoresThatDateAndPreservesOtherDates() {
        val after = skipPlanDay(initial, task.id, day, "休息")
        val later = skipPlanDay(after, task.id, day.plusDays(1), "另一天休息")
        val undone = applyQuickUndo(later, skipUndo(initial, after))
        assertEquals(listOf(PlanSkip(day.plusDays(1).toString(), "另一天休息")), undone.plans.single().skips)
        assertEquals(0 to 1, dayProgress(undone, day))
        assertEquals(0 to 0, dayProgress(undone, day.plusDays(1)))
    }

    @Test fun undoSkipPreservesNewerRecordsAndPersonalData() {
        val after = skipPlanDay(initial, task.id, day, "休息")
        val later = checked(setCheckIn(after, task.id, day.plusDays(1), 5, "另一天的打卡").copy(
            nickname = "新昵称", profile = PersonalProfile(motto = "新签名"),
            focusRecords = listOf(FocusRecord("new-focus", task.id, 900, 5678)),
            dailyNotes = listOf(DailyNote(day.toString(), "休息日心得", 6000))))
        val undone = applyQuickUndo(later, skipUndo(initial, after))
        assertEquals(later.copy(plans = listOf(task)), undone)
    }

    @Test fun undoSkipRemovalRestoresItsReasonAndPosition() {
        val before = skipPlanDay(skipPlanDay(initial, task.id, day, "原来的原因"), task.id, day.plusDays(1), "另一天")
        val after = undoPlanSkip(before, task.id, day)
        assertEquals(before, applyQuickUndo(after, skipUndo(before, after)))
    }

    @Test fun editedOrRemovedSkipCannotBeOverwritten() {
        val after = skipPlanDay(initial, task.id, day, "休息")
        val edited = after.copy(plans = listOf(after.plans.single().copy(skips = listOf(PlanSkip(day.toString(), "新原因")))))
        conflict { applyQuickUndo(edited, skipUndo(initial, after)) }
        conflict { applyQuickUndo(undoPlanSkip(after, task.id, day), skipUndo(initial, after)) }
    }

    @Test fun skipAndPauseUndoRejectArchiveOrRevision() {
        val skipped = skipPlanDay(initial, task.id, day, "休息")
        val paused = pausePlan(initial, task.id, day)
        listOf(skipped to skipUndo(initial, skipped), paused to pauseUndo(initial, paused)).forEach { (after, undo) ->
            conflict { applyQuickUndo(archivePlan(after, task.id, day), undo) }
            conflict { applyQuickUndo(revisePlan(after, task.id, task.copy(target = 20), day), undo) }
        }
    }

    @Test fun undoSkipRemovalCannotConflictWithANewerSameDayCheckIn() {
        val before = skipPlanDay(initial, task.id, day, "休息")
        val after = undoPlanSkip(before, task.id, day)
        val later = setCheckIn(after, task.id, day, 3)
        conflict { applyQuickUndo(later, skipUndo(before, after)) }
    }

    @Test fun restoredDataStillPassesFullDomainValidation() {
        val after = setCheckIn(initial, task.id, day, 3)
        val malformed = after.copy(dailyNotes = listOf(DailyNote(day.toString(), "", 1234)))
        assertThrows(IllegalArgumentException::class.java) { applyQuickUndo(malformed, checkUndo(initial, after)) }
    }

    @Test fun unchangedTargetProducesNoUndoEvenWhenOtherDataChanged() {
        val later = initial.copy(nickname = "另一个昵称", dailyNotes = listOf(DailyNote(day.toString(), "写了心得", 1)))
        assertNull(checkInUndo(initial, later, task.id, day))
        assertNull(pauseUndo(initial, later, task.id))
        assertNull(skipUndo(initial, later, task.id, day))
        val paused = pausePlan(initial, task.id, day)
        assertNull(pauseUndo(paused, pausePlan(paused, task.id, day), task.id))
    }
}
