package com.tongpin.app

import java.security.MessageDigest

/** A channel keeps the user's system choices when the same sound is selected again. */
internal object ReminderDeliveryRules {
    private const val CHANNEL_PREFIX = "focus_done_v3"

    fun channelId(soundId: String, sound: Boolean, vibrate: Boolean): String {
        val key = if (sound) MessageDigest.getInstance("SHA-256").digest(soundId.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) } else "silent"
        return "${CHANNEL_PREFIX}_${key}_v${if (vibrate) 1 else 0}"
    }

    fun problems(state: ReminderDeliveryState): List<String> = buildList {
        if (!state.enabled) add("打开“完成提醒”后，专注结束时才会提醒。")
        if (!state.notificationsAllowed) add("请在系统设置中允许 plan 发送通知。")
        if (state.channelImportance == 0) {
            add("当前完成提醒已被系统关闭，请打开“当前提醒设置”允许通知。")
        }
        if (!state.sound) add("打开“提示声音”后，完成提醒才会播放声音。")
        if (state.enabled && state.sound) {
            when {
                state.channelImportance != null && state.channelImportance in 1..2 ->
                    add("当前提醒方式在系统中设为静默，请打开“当前提醒设置”允许声音。")
                state.channelHasSound == false && state.channelImportance != 0 ->
                    add("当前提醒方式的提示音已被系统关闭，请打开“当前提醒设置”选择声音。")
                state.channelSoundChanged ->
                    add("系统已更改当前提示音，可在“当前提醒设置”中查看或调整。")
            }
            if (state.ringerSilenced) add("手机处于静音或振动模式，请在系统声音设置中切换为响铃。")
            if (state.notificationVolume == 0) add("通知音量为零，请在系统声音设置中调高通知音量。")
        }
        if (state.enabled && state.doNotDisturb) add("勿扰模式可能限制声音与振动，可按需要在系统设置中调整。")
    }
}

internal data class ReminderDeliveryState(
    val enabled: Boolean = true,
    val sound: Boolean = true,
    val notificationsAllowed: Boolean = true,
    val channelImportance: Int? = 4,
    val channelHasSound: Boolean? = true,
    val channelSoundChanged: Boolean = false,
    val ringerSilenced: Boolean = false,
    val notificationVolume: Int? = 5,
    val doNotDisturb: Boolean = false,
)
