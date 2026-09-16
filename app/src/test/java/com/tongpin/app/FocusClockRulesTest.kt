package com.tongpin.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusClockRulesTest {
    private fun seconds(wall: Long, elapsed: Long, boot: Int = 7, deadline: Long = 160_000L): Int =
        FocusClockRules.remaining(true, 60, 60, 1_060_000, deadline, 7, boot, wall, elapsed)

    @Test fun changingCivilClockDoesNotChangeRunningTimer() {
        assertEquals(30, seconds(1_030_000, 130_000))
        assertEquals(30, seconds(99_030_000, 130_000))
        assertEquals(30, seconds(10, 130_000))
    }

    @Test fun processRestartOnSameBootUsesPersistedMonotonicDeadline() {
        assertEquals(1, seconds(1_059_999, 159_001))
        assertEquals(0, seconds(1_060_000, 160_000))
        assertEquals(0, seconds(1_070_000, 170_000))
    }

    @Test fun deviceRestartUsesWallDeadlineAndClampsRemaining() {
        assertEquals(20, seconds(1_040_000, 10_000, boot = 8))
        assertEquals(0, seconds(1_100_000, 10_000, boot = 8))
        assertEquals(60, seconds(1, 10_000, boot = 8))
    }

    @Test fun legacyClockWithoutElapsedDeadlineUsesWallTime() {
        assertEquals(15, seconds(1_045_000, 10_000, deadline = 0))
    }

    @Test fun pausedClockIgnoresTimeChanges() {
        assertEquals(12, FocusClockRules.remaining(false, 12, 60, 0, 0, 1, 2, 999_999, 999_999))
    }

    @Test fun delayedDeliveryRecordsActualCompletionTime() {
        assertEquals(1_000_000L, FocusClockRules.completedAt(60_000, 70_000, 1_010_000))
        assertEquals(2_000_000L, FocusClockRules.completedAt(60_000, 70_000, 2_010_000))
    }
}
