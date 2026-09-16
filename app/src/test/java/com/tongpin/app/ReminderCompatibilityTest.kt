package com.tongpin.app

import org.junit.Assert.*
import org.junit.Test

class ReminderCompatibilityTest {
    @Test fun xiaomiFamilyDefaultsAreCaseInsensitiveAndDoNotMatchOtherBrands() {
        assertTrue(ReminderCompatibility.defaultEnabled("Xiaomi", "other"))
        assertTrue(ReminderCompatibility.defaultEnabled("other", " REDMI "))
        assertTrue(ReminderCompatibility.defaultEnabled("other", "POCO"))
        assertFalse(ReminderCompatibility.defaultEnabled("Google", "google"))
        assertFalse(ReminderCompatibility.defaultEnabled("not-xiaomi", "xiaomi-like"))
    }
    @Test fun normalNotificationSettingsPermitCompatibilityPlayback() {
        assertNull(ReminderCompatibility.blockedReason(ReminderCompatibilityState()))
    }
    @Test fun everyUserMuteControlBlocksCompatibilityPlayback() {
        val states = listOf(ReminderCompatibilityState(enabled = false), ReminderCompatibilityState(sound = false),
            ReminderCompatibilityState(notificationsAllowed = false), ReminderCompatibilityState(standardImportance = 0),
            ReminderCompatibilityState(standardImportance = 2), ReminderCompatibilityState(standardHasSound = false),
            ReminderCompatibilityState(ringerSilenced = true), ReminderCompatibilityState(notificationVolume = 0),
            ReminderCompatibilityState(doNotDisturb = true), ReminderCompatibilityState(compatibilityImportance = 0),
            ReminderCompatibilityState(compatibilityImportance = 2))
        states.forEach { assertNotNull("Must not bypass mute: $it", ReminderCompatibility.blockedReason(it)) }
    }
    @Test fun unknownSystemStateDoesNotRiskBypassingMute() {
        assertNotNull(ReminderCompatibility.blockedReason(ReminderCompatibilityState(standardImportance = null)))
        assertNotNull(ReminderCompatibility.blockedReason(ReminderCompatibilityState(notificationVolume = null)))
        assertNotNull(ReminderCompatibility.blockedReason(ReminderCompatibilityState(compatibilityImportance = null)))
    }
    @Test fun stopAndNewEventInvalidateOlderPendingPlayback() {
        ReminderPlaybackControl.activate("first")
        assertTrue(ReminderPlaybackControl.current("first"))
        ReminderPlaybackControl.activate("second")
        assertFalse(ReminderPlaybackControl.current("first"))
        ReminderPlaybackControl.stop()
        assertFalse(ReminderPlaybackControl.current("second"))
    }
}
