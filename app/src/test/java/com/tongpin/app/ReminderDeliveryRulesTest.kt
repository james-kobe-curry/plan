package com.tongpin.app

import org.junit.Assert.*
import org.junit.Test

class ReminderDeliveryRulesTest {
    @Test fun returningToASoundKeepsTheSameChannelAndSystemPreferences() {
        val first = ReminderDeliveryRules.channelId("chime", true, true)
        val second = ReminderDeliveryRules.channelId("bell", true, true)
        assertNotEquals(first, second)
        assertEquals(first, ReminderDeliveryRules.channelId("chime", true, true))
        assertNotEquals(first, ReminderDeliveryRules.channelId("chime", true, false))
    }

    @Test fun silentChannelsAreSharedAcrossSoundsAndSeparateFromAudibleChannels() {
        assertEquals(ReminderDeliveryRules.channelId("chime", false, true), ReminderDeliveryRules.channelId("bell", false, true))
        assertNotEquals(ReminderDeliveryRules.channelId("chime", false, true), ReminderDeliveryRules.channelId("chime", true, true))
        assertNotEquals(ReminderDeliveryRules.channelId("chime", false, true), ReminderDeliveryRules.channelId("chime", false, false))
    }

    @Test fun idsCannotConfuseUserAudioWithBuiltinOrSilentChannels() {
        val ids = listOf("chime", "CHIME", "silent", "../chime", "custom:清晨铃", "custom/清晨铃")
            .map { ReminderDeliveryRules.channelId(it, true, true) }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("[a-z0-9_]+")) && it.length < 100 })
    }

    @Test fun audibleDefaultsHaveNoProblemMessage() {
        assertTrue(ReminderDeliveryRules.problems(ReminderDeliveryState()).isEmpty())
    }

    @Test fun disabledCompletionAndNotificationPermissionAreVisible() {
        val problems = ReminderDeliveryRules.problems(ReminderDeliveryState(enabled = false, notificationsAllowed = false))
        assertTrue(problems.any { it.contains("完成提醒") })
        assertTrue(problems.any { it.contains("允许 plan") })
    }

    @Test fun systemChannelBlockCannotBeMistakenForAnAudioFileFailure() {
        val problems = ReminderDeliveryRules.problems(ReminderDeliveryState(channelImportance = 0, channelHasSound = false))
        assertTrue(problems.any { it.contains("系统关闭") })
        assertFalse(problems.any { it.contains("选择声音") })
    }

    @Test fun quietImportanceAndMissingSoundExplainHowToRepairTheActualChannel() {
        assertTrue(ReminderDeliveryRules.problems(ReminderDeliveryState(channelImportance = 2)).any { it.contains("设为静默") })
        assertTrue(ReminderDeliveryRules.problems(ReminderDeliveryState(channelHasSound = false)).any { it.contains("选择声音") })
    }

    @Test fun userSoundOverrideIsDisclosedWithoutReplacingIt() {
        assertTrue(ReminderDeliveryRules.problems(ReminderDeliveryState(channelSoundChanged = true)).any { it.contains("系统已更改") })
    }

    @Test fun ringerVolumeAndDndAreDiagnosedIndependently() {
        val problems = ReminderDeliveryRules.problems(ReminderDeliveryState(ringerSilenced = true, notificationVolume = 0, doNotDisturb = true))
        assertEquals(3, problems.size)
        assertTrue(problems.any { it.contains("静音或振动") })
        assertTrue(problems.any { it.contains("音量为零") })
        assertTrue(problems.any { it.contains("勿扰") })
    }

    @Test fun soundDisabledDoesNotAskUserToIncreaseVolume() {
        val problems = ReminderDeliveryRules.problems(ReminderDeliveryState(sound = false, channelHasSound = false, ringerSilenced = true, notificationVolume = 0))
        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("打开“提示声音”"))
    }

    @Test fun unavailableSystemReadingsDoNotInventAProblem() {
        assertTrue(ReminderDeliveryRules.problems(ReminderDeliveryState(channelImportance = null, channelHasSound = null, notificationVolume = null)).isEmpty())
    }
}
