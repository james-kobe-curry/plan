package com.tongpin.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PlanRevisionReminderTest {
    private val today = LocalDate.parse("2026-09-14")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val task = Plan(id = "study", title = "阅读", category = Category.STUDY,
        target = 60, unit = "分钟", startDate = today.minusDays(7).toString(), reminderTime = "20:00")
    private fun partial(plan: Plan = task) = AppData(plans = listOf(plan),
        checkIns = listOf(CheckIn(plan.id, today.toString(), 20, "先完成了一部分", 100)))
    private fun at(day: LocalDate = today, hour: Int = 15) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun unchangedSavePreservesTheOriginalDataAndDoesNotCreateVersions() {
        val original = partial(task.copy(pinned = true, sortOrder = 4,
            pauses = listOf(PlanPause(today.minusDays(6).toString(), today.minusDays(5).toString())),
            skips = listOf(PlanSkip(today.minusDays(4).toString()))))
        // PlanEditor does not return the operational pause/skip/order fields.
        val proposal = task.copy()
        assertSame(original, revisePlan(original, task.id, proposal, today))
    }

    @Test fun renamingAndChangingCategoryKeepTodaysPartialProgressAndReminder() {
        val original = partial()
        val changed = revisePlan(original, task.id, task.copy(title = "精读", category = Category.LIFE), today)
        assertEquals(1, changed.plans.size)
        assertEquals(task.id, changed.plans.single().id)
        assertEquals("精读", changed.plans.single().title)
        assertEquals(Category.LIFE, changed.plans.single().category)
        assertEquals(original.checkIns, changed.checkIns)
        val reminder = PlanReminderRules.candidates(changed, emptyMap(), at(), zone).single()
        assertEquals(today.toString(), reminder.date)
        assertEquals(at(hour = 20), reminder.at)
    }

    @Test fun reminderTimeChangesApplyTodayWithoutMovingTheTaskOrItsRecords() {
        val original = partial()
        val changed = revisePlan(original, task.id, task.copy(reminderTime = "21:00"), today)
        assertEquals(original.checkIns, changed.checkIns)
        assertEquals(task.startDate, changed.plans.single().startDate)
        assertEquals(task.id, changed.plans.single().id)
        assertEquals(at(hour = 21), PlanReminderRules.candidates(changed, emptyMap(), at(), zone).single().at)
    }

    @Test fun deadlineTodayDoesNotPreventRenamingOrChangingReminderTime() {
        val ending = task.copy(dueDate = today.toString())
        val original = partial(ending)
        val changed = revisePlan(original, task.id, ending.copy(title = "考试复习", reminderTime = "21:00"), today)
        assertEquals(1, changed.plans.size)
        assertEquals(today.toString(), changed.plans.single().dueDate)
        assertEquals(original.checkIns, changed.checkIns)
        assertEquals(at(hour = 21), PlanReminderRules.candidates(changed, emptyMap(), at(), zone).single().at)
    }

    @Test fun metadataProposalCannotOverwriteFreshPauseSkipAndOrderControls() {
        val current = task.copy(pinned = true, sortOrder = 7,
            pauses = listOf(PlanPause(today.plusDays(1).toString())),
            skips = listOf(PlanSkip(today.plusDays(3).toString(), "休息")))
        val changed = revisePlan(partial(current), task.id, task.copy(title = "新名称"), today).plans.single()
        assertEquals(current.pauses, changed.pauses)
        assertEquals(current.skips, changed.skips)
        assertEquals(current.pinned, changed.pinned)
        assertEquals(current.sortOrder, changed.sortOrder)
    }

    @Test fun quantityRevisionKeepsTodaysOldMeasurementAndReminderBeforeSuccessorStarts() {
        val original = partial()
        val changed = revisePlan(original, task.id, task.copy(target = 30, unit = "页"), today)
        val old = changed.plans.single { it.id == task.id }
        val next = changed.plans.single { !it.archived }
        assertTrue(old.superseded)
        assertEquals(60, old.target)
        assertEquals("分钟", old.unit)
        assertEquals(today.plusDays(1).toString(), next.startDate)
        assertEquals(original.checkIns, changed.checkIns)
        val currentEvents = PlanReminderRules.candidates(changed, emptyMap(), at(), zone)
        assertEquals(1, currentEvents.size)
        assertEquals(task.id, currentEvents.single().planId)
        assertEquals(today.toString(), currentEvents.single().date)
        val future = PlanReminderRules.candidates(changed, emptyMap(), at(today.plusDays(1)), zone).single()
        assertEquals(next.id, future.planId)
        assertEquals(today.plusDays(1).toString(), future.date)
    }

    @Test fun editingFutureSuccessorMetadataAlsoUpdatesTheStillEffectiveDay() {
        val first = revisePlan(partial(), task.id, task.copy(target = 30, unit = "页"), today)
        val successor = first.plans.single { !it.archived }
        val changed = revisePlan(first, successor.id, successor.copy(title = "精读", category = Category.LIFE, reminderTime = "21:00"), today)
        assertEquals(2, changed.plans.size)
        assertTrue(changed.plans.all { it.title == "精读" && it.category == Category.LIFE && it.reminderTime == "21:00" })
        assertEquals("分钟", changed.plans.single { it.id == task.id }.unit)
        assertEquals("页", changed.plans.single { !it.archived }.unit)
        assertEquals(at(hour = 21), PlanReminderRules.candidates(changed, emptyMap(), at(), zone).single().at)
        val disabled = revisePlan(changed, successor.id, changed.plans.single { !it.archived }.copy(reminderTime = null), today)
        assertTrue(PlanReminderRules.candidates(disabled, emptyMap(), at(), zone).isEmpty())
    }

    @Test fun repeatedFutureEditsCannotOverlapTheAlreadyLoggedDay() {
        val first = revisePlan(partial(), task.id, task.copy(target = 70), today)
        val successor = first.plans.single { !it.archived }
        val changed = revisePlan(first, successor.id, successor.copy(target = 80, startDate = today.toString()), today)
        assertEquals(today.plusDays(1).toString(), changed.plans.single { !it.archived }.startDate)
        assertEquals(listOf(task.id), changed.plans.filter { isScheduled(it, today) }.map { it.id })
        assertEquals(task.id, PlanReminderRules.candidates(changed, emptyMap(), at(), zone).single().planId)
    }

    @Test fun manualArchiveStopsRelayAndFutureRestoreDoesNotReviveToday() {
        val changed = revisePlan(partial(), task.id, task.copy(target = 70), today)
        val successor = changed.plans.single { !it.archived }
        val archived = archivePlan(changed, successor.id, today)
        assertTrue(PlanReminderRules.candidates(archived, emptyMap(), at(), zone).isEmpty())
        assertFalse(archived.plans.single { it.id == task.id }.superseded)
        val restored = restoreArchivedPlan(archived, successor.id, today.plusDays(1), today)
        val next = PlanReminderRules.candidates(restored, emptyMap(), at(), zone).single()
        assertEquals(today.plusDays(1).toString(), next.date)
        assertFalse(restored.plans.single { !it.archived }.superseded)
    }

    @Test fun futurePauseHonorsItsDateAndSuppressesTheSuccessorsNextDay() {
        val changed = revisePlan(partial(), task.id, task.copy(target = 70), today)
        val paused = pausePlan(changed, changed.plans.single { !it.archived }.id, today)
        assertEquals(today.toString(), PlanReminderRules.candidates(paused, emptyMap(), at(), zone).single().date)
        assertTrue(PlanReminderRules.candidates(paused, emptyMap(), at(today.plusDays(1)), zone).isEmpty())
    }

    @Test fun completedOldDayAndDaysWithoutArrangementsDoNotGetRelayReminders() {
        val changed = revisePlan(partial(), task.id, task.copy(target = 70), today)
        val completed = setCheckIn(changed, task.id, today, task.target)
        assertEquals(today.plusDays(1).toString(), PlanReminderRules.candidates(completed, emptyMap(), at(), zone).single().date)
        val legacy = AppData(plans = listOf(task.copy(archived = true, endDate = today.plusDays(1).toString()),
            task.copy(id = "next", startDate = today.plusDays(1).toString())))
        assertEquals(today.plusDays(1).toString(), PlanReminderRules.candidates(legacy, emptyMap(), at(), zone).single().date)
    }

    @Test fun restartPreservesRelayAndMidnightDoesNotLookLikeALateReminderEdit() {
        val changed = revisePlan(partial(), task.id, task.copy(target = 70), today)
        val restarted = DataCodec.decode(DataCodec.encode(changed))
        assertEquals(changed, restarted)
        assertEquals(task.id, PlanReminderRules.candidates(restarted, emptyMap(), at(), zone).single().planId)
        val current = PlanReminderRules.configurationPlans(restarted, today).single()
        val savedConfiguration = PlanReminderRules.configuration(current)
        val tomorrow = today.plusDays(1)
        val next = PlanReminderRules.configurationPlans(restarted, tomorrow).single()
        assertTrue(PlanReminderRules.configurationMatches(savedConfiguration, next))
        val state = PlanReminderRules.configuredState(restarted, next, null,
            !PlanReminderRules.configurationMatches(savedConfiguration, next), at(tomorrow, 21), zone)
        assertNull(state)
        val delayed = PlanReminderRules.candidates(restarted, emptyMap(), at(tomorrow, 21), zone, at(tomorrow, 20)).single()
        assertEquals(at(tomorrow, 20), delayed.at)
        assertEquals(next.id, delayed.planId)
    }

    @Test fun unchangedReminderKeepsSnoozeAcrossRenameAndStructuralRevision() {
        val time = at(hour = 20)
        val event = PlanReminderEvent(task.id, task.seriesId, today.toString(), time)
        val delivered = PlanReminderDay(task.seriesId, today.toString(), handled = true, deliveredAt = time,
            deliveryConfiguration = PlanReminderRules.configuration(task))
        val renamed = revisePlan(partial(), task.id, task.copy(title = "精读"), today)
        val revised = revisePlan(renamed, task.id, renamed.plans.single().copy(target = 70), today)
        val current = PlanReminderRules.configurationPlans(revised, today).single()
        assertEquals(PlanReminderRules.configuration(task), PlanReminderRules.configuration(current))
        val snoozed = PlanReminderRules.action(revised, delivered, event, time + 60_000, zone, true)!!
        val afterRestart = DataCodec.decode(DataCodec.encode(revised))
        val next = PlanReminderRules.candidates(afterRestart,
            mapOf(PlanReminderRules.key(task.seriesId, today.toString()) to snoozed), time + 60_000, zone).single()
        assertTrue(next.snoozed)
        assertEquals(time + 16 * 60_000, next.at)
        assertEquals(task.id, next.planId)
    }

    @Test fun timeEditClearsSnoozeAndRejectsOldActionsWithoutRepeatingRegularReminder() {
        val time = at(hour = 20)
        val original = partial()
        val event = PlanReminderEvent(task.id, task.seriesId, today.toString(), time)
        val delivered = PlanReminderDay(task.seriesId, today.toString(), handled = true, deliveredAt = time,
            snoozeAt = time + 15 * 60_000, deliveryConfiguration = PlanReminderRules.configuration(task))
        val changed = revisePlan(original, task.id, task.copy(reminderTime = "21:00"), today)
        val current = changed.plans.single()
        val state = PlanReminderRules.configuredState(changed, current, delivered, true, time + 60_000, zone)!!
        assertNull(state.snoozeAt)
        assertTrue(state.handled)
        assertNull(PlanReminderRules.action(changed, state, event, time + 60_000, zone, true))
        assertNull(PlanReminderRules.action(changed, state, event, time + 60_000, zone, false))
        assertEquals(today.plusDays(1).toString(), PlanReminderRules.candidates(changed,
            mapOf(PlanReminderRules.key(task.seriesId, today.toString()) to state), time + 60_000, zone).single().date)
        val legacy = PlanReminderRules.configuredState(changed, current, delivered.copy(deliveryConfiguration = null), true, time + 60_000, zone)!!
        assertNull(PlanReminderRules.action(changed, legacy, event, time + 60_000, zone, true))
    }

    @Test fun manualArchiveRejectsAnOldNotificationActionForTheRelay() {
        val revised = revisePlan(partial(), task.id, task.copy(target = 70), today)
        val archived = archivePlan(revised, revised.plans.single { !it.archived }.id, today)
        val time = at(hour = 20)
        val delivered = PlanReminderDay(task.seriesId, today.toString(), handled = true, deliveredAt = time)
        val event = PlanReminderEvent(task.id, task.seriesId, today.toString(), time)
        assertNull(PlanReminderRules.action(archived, delivered, event, time + 60_000, zone, true))
    }

    @Test fun olderDataDoesNotGuessWhetherAnArchiveWasManual() {
        val changed = revisePlan(partial(), task.id, task.copy(target = 70), today)
        val root = DataCodec.readJsonObject(DataCodec.encode(changed)).toMutableMap()
        root["version"] = 5; root.remove("categories"); root.remove("profile"); root.remove("dailyNotes")
        @Suppress("UNCHECKED_CAST")
        root["plans"] = (root["plans"] as List<Map<String, Any?>>).map { it - "superseded" - "customCategoryId" - "iconId" }
        val migrated = DataCodec.decode(DataCodec.writeJsonObject(root))
        assertTrue(migrated.plans.none { it.superseded })
        assertEquals(today.plusDays(1).toString(), PlanReminderRules.candidates(migrated, emptyMap(), at(), zone).single().date)
    }

    @Test fun allTransferScopesRetainTheExplicitHistoricalReason() {
        val changed = revisePlan(partial(), task.id, task.copy(target = 70), today)
        TransferScope.entries.forEach { scope ->
            val decoded = TransferCodec.decode(TransferCodec.encode(changed, scope)).data
            assertTrue(decoded.plans.single { it.id == task.id }.superseded)
        }
        val imported = mergeTransfer(AppData(), TransferCodec.decode(TransferCodec.encode(changed, TransferScope.RECORDS)), TransferScope.RECORDS).result
        assertTrue(imported.plans.single().recordsOnly)
        assertTrue(PlanReminderRules.candidates(imported, emptyMap(), at(), zone).isEmpty())
        assertTrue(DataCodec.decodePlans(DataCodec.encodePlans(changed.plans)).none { it.superseded })
    }

    @Test fun overlappingImportedActiveVersionsStillProduceOnlyOneSeriesCandidate() {
        val other = task.copy(id = "other", startDate = today.toString())
        val events = PlanReminderRules.candidates(AppData(plans = listOf(task, other)), emptyMap(), at(), zone)
        assertEquals(1, events.size)
        assertEquals(other.id, events.single().planId)
    }
}
