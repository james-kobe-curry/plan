package com.tongpin.app

import org.junit.Assert.*
import org.junit.Test

class FocusPreferencesTest {
    @Test fun newInstallRetainsTheFourOriginalShortcuts() {
        val preferences = FocusPreferences()
        assertEquals(listOf(15, 25, 45, 60), preferences.favorites)
        assertEquals(25, preferences.lastMinutes)
    }

    @Test fun favoriteBoundariesAreUsableAndOrdered() {
        val preferences = FocusPreferences().withFavorite(MAX_FOCUS_MINUTES).withFavorite(1)
        assertEquals(listOf(1, 15, 25, 45, 60, MAX_FOCUS_MINUTES), preferences.favorites)
        assertEquals(25, preferences.lastMinutes)
    }

    @Test fun duplicateFavoritesDoNotGrowTheListEvenWhenFull() {
        val full = FocusPreferences().withFavorite(40).withFavorite(50).withFavorite(90).withFavorite(120)
        assertEquals(MAX_FOCUS_FAVORITES, full.favorites.size)
        assertSame(full, full.withFavorite(25))
        assertRejected { full.withFavorite(5) }
        assertEquals(MAX_FOCUS_FAVORITES, full.favorites.size)
    }

    @Test fun removingOneFavoriteFreesASlotAndPreservesMemory() {
        val full = FocusPreferences(lastMinutes = 70).withFavorite(40).withFavorite(50).withFavorite(90).withFavorite(120)
        val next = full.withoutFavorite(15).withFavorite(20)
        assertEquals(MAX_FOCUS_FAVORITES, next.favorites.size)
        assertFalse(15 in next.favorites)
        assertTrue(20 in next.favorites)
        assertEquals(70, next.lastMinutes)
        assertTrue(15 in full.favorites)
    }

    @Test fun atLeastOneFavoriteMustRemain() {
        val one = FocusPreferences(favorites = listOf(40))
        assertRejected { one.withoutFavorite(40) }
        assertSame(one, one.withoutFavorite(25))
        assertRejected { FocusPreferences(favorites = emptyList()) }
    }

    @Test fun invalidAndDuplicatePreferenceValuesAreRejected() {
        listOf(0, -1, MAX_FOCUS_MINUTES + 1, Int.MAX_VALUE).forEach { minutes ->
            assertRejected { FocusPreferences().withFavorite(minutes) }
            assertRejected { FocusPreferences().withManualMinutes(minutes) }
            assertRejected { FocusPreferences(favorites = listOf(minutes)) }
        }
        assertRejected { FocusPreferences(favorites = listOf(25, 25)) }
        assertRejected { FocusPreferences(favorites = (1..9).toList()) }
    }

    @Test fun encodedFavoritesAreCanonicalAndRoundTrip() {
        assertEquals("1,15,25,1440", encodeFocusFavorites(listOf(1440, 25, 1, 15)))
        val custom = listOf(1, 10, 20, 30, 40, 60, 90, 1440)
        assertEquals(custom, parseFocusFavorites(encodeFocusFavorites(custom)))
        assertEquals(listOf(15, 25), parseFocusFavorites("25,15"))
    }

    @Test fun corruptedFavoriteStringsNeverCreateInvalidShortcuts() {
        listOf(null, "", ",", "15,", ",15", "15,,25", "15,15", "0", "1441", "-1", "25.0", "1e2",
            "15, 25", "２５", "1,2,3,4,5,6,7,8,9", "9".repeat(200)).forEach { encoded ->
            assertNull("Should reject $encoded", parseFocusFavorites(encoded))
        }
    }

    @Test fun missingAndDamagedSettingsFallBackIndependently() {
        assertEquals(FocusPreferences(), readFocusPreferences(null, null))
        assertEquals(FocusPreferences(lastMinutes = 70), readFocusPreferences("15,15", 70))
        assertEquals(FocusPreferences(favorites = listOf(40, 90)), readFocusPreferences("40,90", -5))
    }

    @Test fun resettingFavoritesPreservesLastManualDuration() {
        val preferences = FocusPreferences(favorites = listOf(40, 50, 90), lastMinutes = 100).withDefaultFavorites()
        assertEquals(DEFAULT_FOCUS_FAVORITES, preferences.favorites)
        assertEquals(100, preferences.lastMinutes)
    }

    @Test fun freeFocusAndNonTimeTasksUseManualMemory() {
        val preferences = FocusPreferences(lastMinutes = 50)
        assertEquals(50, focusMinutesForPlan(null, preferences).minutes)
        assertEquals(50, focusMinutesForPlan(task(), preferences).minutes)
        assertEquals(50, focusMinutesForPlan(task().copy(tracking = TrackingMode.QUANTITY, target = 30, unit = "页"), preferences).minutes)
        assertEquals(50, focusMinutesForPlan(task().copy(unit = "分钟"), preferences).minutes)
    }

    @Test fun linkedTimeTargetsNeverOverwriteManualMemory() {
        val preferences = FocusPreferences(lastMinutes = 50)
        val timed = task().copy(tracking = TrackingMode.QUANTITY, target = 150, unit = "小时", scale = 2)
        assertEquals(90, focusMinutesForPlan(timed, preferences).minutes)
        assertEquals(50, preferences.lastMinutes)
        assertEquals(50, focusMinutesForPlan(null, preferences).minutes)
        assertEquals(50, focusMinutesForPlan(task(), preferences).minutes)
    }

    @Test fun manualChoiceDoesNotAddAFavoriteImplicitly() {
        val preferences = FocusPreferences().withManualMinutes(70)
        assertEquals(70, preferences.lastMinutes)
        assertEquals(DEFAULT_FOCUS_FAVORITES, preferences.favorites)
        assertEquals(70, focusMinutesForPlan(null, preferences).minutes)
    }

    @Test fun invalidLinkedTimeFallsBackToMemoryAndRetainsGuidance() {
        val preferences = FocusPreferences(lastMinutes = 45)
        val tooLong = task().copy(tracking = TrackingMode.QUANTITY, unit = "小时", target = 25)
        val selected = focusMinutesForPlan(tooLong, preferences)
        assertEquals(45, selected.minutes)
        assertTrue(selected.message?.contains("自定义") == true)
        assertEquals(45, preferences.lastMinutes)
    }

    private fun task() = Plan(title = "学习", category = Category.STUDY, target = 1, unit = "次", tracking = TrackingMode.TASK)

    private fun assertRejected(operation: () -> Unit) {
        try {
            operation()
            fail("Expected invalid preferences to be rejected")
        } catch (_: IllegalArgumentException) {
            // The caller can present the rule's message without mutating the prior preference value.
        }
    }
}
