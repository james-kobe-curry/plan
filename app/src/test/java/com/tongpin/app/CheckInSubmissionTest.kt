package com.tongpin.app

import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CheckInSubmissionTest {
    private val day = LocalDate.of(2026, 9, 16)
    private val plan = Plan(id = "reading", title = "阅读", category = Category.STUDY, target = 20, unit = "页", startDate = "2026-09-01")
    private fun data(plan: Plan = this.plan, amount: Int = 10, note: String = "原心得") = AppData(plans = listOf(plan),
        checkIns = if (amount == 0) emptyList() else listOf(CheckIn(plan.id, day.toString(), amount, note, 1000L)))
    private fun request(data: AppData, amount: Int, mode: CheckInAmountMode = CheckInAmountMode.ADD, note: String = data.checkIns.firstOrNull()?.note ?: "") =
        CheckInSubmission(UUID.randomUUID().toString(), mode, amount, note, draftPlanFingerprint(data.plans.single()), draftCheckInFingerprint(data, plan.id, day))
    private fun apply(data: AppData, request: CheckInSubmission) = applyCheckInSubmission(data, plan.id, day, request)
    private fun rejects(block: () -> Unit) { try { block(); fail("Must reject") } catch (_: IllegalArgumentException) { } }

    @Test fun tenPlusFiveIsFifteenAndRetainsTheExistingNote() {
        val before = data()
        val after = apply(before, request(before, 5))
        assertEquals(15, amountFor(after, plan.id, day)); assertEquals("原心得", after.checkIns.single().note)
    }

    @Test fun decimalIncrementUsesExactHundredths() {
        val running = plan.copy(unit = "公里", scale = 2, target = 500)
        val before = data(running, 125)
        val increment = requireNotNull(parseQuantity("0.35", running.unit, running.scale))
        val after = apply(before, request(before, increment))
        assertEquals("1.6", formatQuantity(after.checkIns.single().amount, running))
        assertNull(parseQuantity("0.351", running.unit, running.scale))
    }

    @Test fun totalModeReplacesInsteadOfAddingAndZeroExplicitlyUndoesEntry() {
        val before = data()
        assertEquals(5, apply(before, request(before, 5, CheckInAmountMode.TOTAL)).checkIns.single().amount)
        assertTrue(apply(before, request(before, 0, CheckInAmountMode.TOTAL)).checkIns.isEmpty())
    }

    @Test fun aRepeatedCommittedIncrementIsRejectedInsteadOfAddedAgain() {
        val before = data()
        val pending = request(before, 5)
        val committed = apply(before, pending)
        rejects { apply(committed, pending) }
        assertEquals(15, committed.checkIns.single().amount)
    }

    @Test fun concurrentEntryChangesCannotBeOverwrittenByAnOldTotalOrNote() {
        val before = data()
        val changed = before.copy(checkIns = listOf(before.checkIns.single().copy(note = "另一个界面的心得", updatedAt = 1001)))
        rejects { apply(changed, request(before, 5)) }
        rejects { apply(changed, request(before, 12, CheckInAmountMode.TOTAL, "旧界面修改")) }
        assertEquals("另一个界面的心得", changed.checkIns.single().note)
    }

    @Test fun changedUnitsAndMissingPlansRejectAStaleDraft() {
        val before = data()
        rejects { apply(before.copy(plans = listOf(plan.copy(unit = "题"))), request(before, 5)) }
        rejects { apply(before.copy(plans = emptyList()), request(before, 5)) }
    }

    @Test fun exactMaximumIsAllowedButIncrementOverflowAndNegativeAreRejected() {
        val before = data(amount = DomainValidation.maxAmount(0) - 1)
        assertEquals(DomainValidation.maxAmount(0), apply(before, request(before, 1)).checkIns.single().amount)
        rejects { apply(before, request(before, 2)) }
        rejects { apply(before, request(before, Int.MAX_VALUE)) }
        rejects { apply(before, request(before, -1)) }
        rejects { apply(before, request(before, 0)) }
    }

    @Test fun decimalMaximumUsesStoredScaleWithoutIntegerOverflow() {
        val running = plan.copy(unit = "公里", scale = 2, target = 100)
        val before = data(running, DomainValidation.maxAmount(2))
        rejects { apply(before, request(before, 1)) }
        assertEquals(DomainValidation.maxAmount(2), apply(before, request(before, DomainValidation.maxAmount(2), CheckInAmountMode.TOTAL)).checkIns.single().amount)
    }

    @Test fun normalTasksRemainCompletionOrUndoOnly() {
        val simple = plan.copy(target = 1, unit = "次", tracking = TrackingMode.TASK)
        val before = data(simple, 0)
        val completed = apply(before, request(before, 1, CheckInAmountMode.TOTAL, "已完成"))
        assertEquals(1, completed.checkIns.single().amount)
        assertTrue(apply(completed, request(completed, 0, CheckInAmountMode.TOTAL)).checkIns.isEmpty())
        rejects { apply(before, request(before, 1)) }
        rejects { apply(before, request(before, 2, CheckInAmountMode.TOTAL)) }
    }

    @Test fun editedNoteIsSavedTogetherWithIncrement() {
        val before = data()
        val after = apply(before, request(before, 5, note = "原心得\n新体会"))
        assertEquals(15, after.checkIns.single().amount)
        assertEquals("原心得\n新体会", after.checkIns.single().note)
    }

    @Test fun disabledScheduleStillRejectsAnOtherwiseMatchingSubmission() {
        val before = data(plan.copy(skips = listOf(PlanSkip(day.toString()))))
        rejects { apply(before, request(before, 5)) }
    }

    @Test fun separateModeFieldsRoundTripWithoutUsingTheTotalAsAnIncrement() {
        val fields = mapOf("mode" to "TOTAL", "addAmount" to "5", "totalAmount" to "10", "checked" to "true", "note" to "心得", "operationId" to "operation")
        val draft = CheckInDraftFields.read(fields)
        assertEquals(CheckInAmountMode.TOTAL, draft.mode); assertEquals("5", draft.addAmount); assertEquals("10", draft.totalAmount)
        assertEquals("心得", draft.note)
        rejects { CheckInDraftFields.read(fields + ("mode" to "BROKEN")) }
    }
}
