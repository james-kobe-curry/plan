package com.tongpin.app

import org.junit.Assert.*
import org.junit.Test

class PersonalizationTest {
    @Test fun missingLegacyPreferencesKeepTasksAndTheOriginalProgressModule() {
        val value = readHomePreferences(null, null, null, null, null)
        assertEquals(HomePreferences(), value)
        assertTrue(value.shows(HomeModule.TASKS))
        assertTrue(value.shows(HomeModule.PROGRESS))
        assertFalse(value.shows(HomeModule.RECENT))
    }

    @Test fun togglingOptionalModulesNeverHidesTasks() {
        for (progress in listOf(false, true)) for (recent in listOf(false, true)) {
            val value = HomePreferences(showProgress = progress, showRecent = recent)
            assertTrue(value.shows(HomeModule.TASKS))
            assertEquals(progress, value.shows(HomeModule.PROGRESS))
            assertEquals(recent, value.shows(HomeModule.RECENT))
        }
    }

    @Test fun reorderingPreservesEveryModuleAndOtherPreferences() {
        val value = HomePreferences(showRecent = true, density = HomeDensity.COMPACT, startPage = 2)
        val moved = value.move(HomeModule.RECENT, -1).move(HomeModule.RECENT, -1)
        assertEquals(listOf(HomeModule.RECENT, HomeModule.PROGRESS, HomeModule.TASKS), moved.order)
        assertEquals(value.copy(order = moved.order), moved)
        assertSame(moved, moved.move(HomeModule.RECENT, -1))
        assertSame(moved, moved.move(HomeModule.TASKS, 1))
        assertSame(moved, moved.move(HomeModule.TASKS, -2))
    }

    @Test fun malformedOrderCannotRemoveOrDuplicateTasks() {
        listOf(null, "", "PROGRESS,RECENT", "PROGRESS,TASKS,TASKS", "TASKS,RECENT,UNKNOWN", "TASKS,RECENT, PROGRESS", "X".repeat(65)).forEach {
            assertNull(parseHomeOrder(it))
        }
        assertThrows(IllegalArgumentException::class.java) { HomePreferences(order = listOf(HomeModule.RECENT)) }
        assertThrows(IllegalArgumentException::class.java) { HomePreferences(order = listOf(HomeModule.TASKS, HomeModule.TASKS, HomeModule.RECENT)) }
    }

    @Test fun eachOrderRoundTripsAndMissingValuesRecoverIndependently() {
        val options = HomeModule.entries
        options.forEach { first -> options.filter { it != first }.forEach { second ->
            val order = listOf(first, second, options.first { it != first && it != second })
            assertEquals(order, parseHomeOrder(order.joinToString(",") { it.name }))
        } }
        val read = readHomePreferences("invalid", false, true, "COMPACT", 3)
        assertEquals(HomePreferences(showProgress = false, showRecent = true, density = HomeDensity.COMPACT, startPage = 3), read)
        assertEquals(HomePreferences(), readHomePreferences(null, null, null, "wrong", 10))
    }

    @Test fun invalidStartPagesAreRejected() {
        listOf(-1, 4, Int.MAX_VALUE).forEach { page ->
            assertThrows(IllegalArgumentException::class.java) { HomePreferences(startPage = page) }
        }
    }

    @Test fun importedImageHeadersAreBoundedBeforeBitmapAllocation() {
        assertTrue(acceptableAvatarDimensions(12_000, 8_000, false))
        assertTrue(acceptableAvatarDimensions(32_768, 1, false))
        assertFalse(acceptableAvatarDimensions(12_000, 12_000, false))
        assertFalse(acceptableAvatarDimensions(32_769, 1, false))
        assertFalse(acceptableAvatarDimensions(Int.MAX_VALUE, Int.MAX_VALUE, false))
        assertFalse(acceptableAvatarDimensions(-1, 100, false))
        assertFalse(acceptableAvatarDimensions(100, 0, false))
    }

    @Test fun storedAvatarMustBeASmallThumbnailEvenForValidLargeImageHeaders() {
        assertTrue(acceptableAvatarDimensions(AVATAR_EDGE, AVATAR_EDGE, true))
        assertTrue(acceptableAvatarDimensions(512, 512, true))
        assertFalse(acceptableAvatarDimensions(513, 512, true))
        assertFalse(acceptableAvatarDimensions(1, 513, true))
        assertFalse(acceptableAvatarDimensions(12_000, 8_000, true))
    }

    @Test fun recentEntriesKeepDateOrderAndIgnoreZeroProgress() {
        val newest = CheckIn("new", "2026-09-16", 1, updatedAt = 20)
        val sameDate = CheckIn("same", "2026-09-16", 1, updatedAt = 10)
        val previous = CheckIn("previous", "2026-09-15", 1, updatedAt = 1)
        val oldEdited = CheckIn("old", "2026-01-01", 1, updatedAt = 999)
        val zero = CheckIn("zero", "2026-09-17", 0, updatedAt = 30)
        assertEquals(listOf(newest, sameDate, previous), homeRecentEntries(listOf(oldEdited, previous, sameDate, zero, newest)))
        assertTrue(homeRecentEntries(listOf(zero)).isEmpty())
    }
}
