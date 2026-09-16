package com.tongpin.app

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PlanOrderingTest {
    private val monday = LocalDate.of(2026, 9, 14)

    private fun task(id: String, category: Category = Category.STUDY) = Plan(
        id = id, title = id, category = category, target = 10, unit = "页", startDate = monday.toString(),
    )

    private fun assertCommonOrder(data: AppData, date: LocalDate = monday) {
        val home = scheduledPlansOrdered(data, date).map { it.seriesId }
        val library = libraryTasks(data).map { it.seriesId }
        val focus = focusPlansOrdered(data).map { it.seriesId }
        val common = home.toSet().intersect(library.toSet()).intersect(focus.toSet())
        assertEquals(home.filter { it in common }, library.filter { it in common })
        assertEquals(home.filter { it in common }, focus.filter { it in common })
    }

    @Test fun legacyZeroOrdersKeepTheirPlaceAfterARevisionIsAppended() {
        val first = task("first")
        val initial = AppData(plans = listOf(first, task("second"), task("third")))
        val revised = revisePlan(initial, first.id, first.copy(target = 20), monday)

        assertNotEquals(first.id, revised.plans.last().id)
        assertEquals(listOf("first", "second", "third"), scheduledPlansOrdered(revised, monday).map { it.seriesId })
        assertEquals(revised.plans.last().id, libraryTasks(revised).first().id)
        assertCommonOrder(revised)
        // Merely presenting an ordered view never rewrites the stored version sequence or metadata.
        assertEquals(listOf("first", "second", "third", "first"), revised.plans.map { it.seriesId })
        assertTrue(revised.plans.all { it.sortOrder == 0 })
    }

    @Test fun historicalAndCurrentVersionsKeepTheSameRelativePlace() {
        val first = task("first")
        val logged = setCheckIn(AppData(plans = listOf(first, task("second"))), first.id, monday, 3, "Already read")
        val revised = revisePlan(logged, first.id, first.copy(target = 20), monday)

        assertEquals(first.id, scheduledPlansOrdered(revised, monday).first().id)
        assertNotEquals(first.id, libraryTasks(revised).first().id)
        assertCommonOrder(revised, monday)
        assertCommonOrder(revised, monday.plusDays(1))
        assertEquals(logged.checkIns, revised.checkIns)
    }

    @Test fun pinsAndExplicitOrderApplyAcrossAllThreeLists() {
        val data = AppData(plans = listOf(
            task("a").copy(sortOrder = 5), task("b").copy(sortOrder = 2),
            task("c").copy(pinned = true, sortOrder = 9), task("d").copy(pinned = true, sortOrder = 1),
        ))

        assertEquals(listOf("d", "c", "b", "a"), scheduledPlansOrdered(data, monday).map { it.id })
        assertEquals(listOf("d", "c", "b", "a"), libraryTasks(data).map { it.id })
        assertEquals(listOf("d", "c", "b", "a"), focusPlansOrdered(data).map { it.id })
        assertCommonOrder(data)
    }

    @Test fun sortingKeepsEachScreensExistingEligibilityRules() {
        val data = AppData(plans = listOf(
            task("active"),
            task("paused").copy(pauses = listOf(PlanPause(monday.toString()))),
            task("skipped").copy(skips = listOf(PlanSkip(monday.toString()))),
            task("future").copy(startDate = monday.plusDays(1).toString()),
            task("expired").copy(startDate = monday.minusDays(5).toString(), dueDate = monday.minusDays(1).toString()),
            task("archived").copy(archived = true, endDate = monday.toString()),
            task("recordOnly").copy(recordsOnly = true),
        ))
        DomainValidation.data(data)

        assertEquals(listOf("active"), scheduledPlansOrdered(data, monday).map { it.id })
        assertEquals(listOf("active", "paused", "skipped", "future", "expired"), focusPlansOrdered(data).map { it.id })
        assertEquals(listOf("active", "paused", "skipped", "future", "expired", "archived"), libraryTasks(data).map { it.id })
        assertCommonOrder(data)
    }

    @Test fun filteredMoveKeepsOtherCategoriesInTheirExistingSlots() {
        val data = AppData(plans = listOf(task("a"), task("fitness", Category.FITNESS), task("b")))
        val visible = scheduledPlansOrdered(data, monday).filter { it.category == Category.STUDY }.map { it.id }
        val moved = movePlan(data, "b", -1, visible)

        assertEquals(listOf("b", "fitness", "a"), scheduledPlansOrdered(moved, monday).map { it.id })
        assertEquals(1, focusPlansOrdered(moved).indexOfFirst { it.id == "fitness" })
        assertCommonOrder(moved)
    }

    @Test fun movingUnpinnedTasksNeverReordersHiddenPinnedTasks() {
        // A mixed visible list used to normalize raw storage order, moving b above the hidden pin d.
        val data = AppData(plans = listOf(
            task("a"), task("d", Category.FITNESS).copy(pinned = true),
            task("b").copy(pinned = true), task("c"),
        ))
        val visible = scheduledPlansOrdered(data, monday).filter { it.category == Category.STUDY }.map { it.id }
        assertEquals(listOf("b", "a", "c"), visible)
        val moved = movePlan(data, "c", -1, visible)

        assertEquals(listOf("d", "b", "c", "a"), scheduledPlansOrdered(moved, monday).map { it.id })
        assertCommonOrder(moved)
        assertThrows(IllegalArgumentException::class.java) { movePlan(data, "a", -1, visible) }
    }

    @Test fun movingARevisedTaskUpdatesItsEntireSeriesAndKeepsHistory() {
        val a = task("a")
        val initial = AppData(plans = listOf(a, task("b"), task("c")))
        val revised = revisePlan(initial, a.id, a.copy(target = 20), monday)
        val current = focusPlansOrdered(revised).first()
        val moved = movePlan(revised, current.id, 1, scheduledPlansOrdered(revised, monday).map { it.id })

        assertEquals(listOf("b", "a", "c"), focusPlansOrdered(moved).map { it.seriesId })
        assertEquals(1, moved.plans.filter { it.seriesId == "a" }.map { it.sortOrder }.distinct().size)
        assertEquals(revised.plans.map { it.copy(sortOrder = 0) }, moved.plans.map { it.copy(sortOrder = 0) })
        assertCommonOrder(moved)
    }

    @Test fun archivedLibraryStillShowsTheLatestVersionOnceAndRetainsTaskOrder() {
        val a = task("a")
        val initial = AppData(plans = listOf(a, task("b")))
        val revised = revisePlan(initial, a.id, a.copy(target = 20), monday.plusDays(1))
        val current = focusPlansOrdered(revised).first()
        val archived = archivePlan(revised, current.id, monday.plusDays(2))

        assertEquals(listOf("a", "b"), libraryTasks(archived).map { it.seriesId })
        assertEquals(current.id, libraryTasks(archived).first().id)
        assertEquals(listOf("b"), focusPlansOrdered(archived).map { it.id })
        assertCommonOrder(archived, monday)
    }
}
