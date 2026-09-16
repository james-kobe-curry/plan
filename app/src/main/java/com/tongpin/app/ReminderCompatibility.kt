package com.tongpin.app

import java.util.Locale

internal object ReminderCompatibility {
    fun defaultEnabled(manufacturer: String, brand: String): Boolean =
        listOf(manufacturer, brand).any { it.trim().lowercase(Locale.ROOT) in setOf("xiaomi", "redmi", "poco") }

    fun blockedReason(state: ReminderCompatibilityState): String? = when {
        !state.enabled -> "完成提醒已关闭"
        !state.sound -> "提示声音已关闭"
        !state.notificationsAllowed -> "系统通知权限未允许"
        state.standardImportance == null || state.standardImportance < 3 -> "原提醒频道已关闭或设为静默"
        !state.standardHasSound -> "原提醒频道的声音已被关闭"
        state.ringerSilenced -> "手机处于静音或振动模式"
        state.notificationVolume == null || state.notificationVolume <= 0 -> "通知音量为零或暂时无法读取"
        state.doNotDisturb -> "勿扰模式正在限制提醒"
        state.compatibilityImportance == null || state.compatibilityImportance < 3 -> "兼容提醒频道已关闭或设为静默"
        else -> null
    }
}

internal data class ReminderCompatibilityState(
    val enabled: Boolean = true, val sound: Boolean = true, val notificationsAllowed: Boolean = true,
    val standardImportance: Int? = 4, val standardHasSound: Boolean = true,
    val ringerSilenced: Boolean = false, val notificationVolume: Int? = 5,
    val doNotDisturb: Boolean = false, val compatibilityImportance: Int? = 4,
)

/** Invalidates pending preparation too, so Stop/new timers cannot race into a late sound. */
internal object ReminderPlaybackControl {
    private var token: String? = null
    @Synchronized fun activate(event: String) { token = event }
    @Synchronized fun current(event: String): Boolean = token == event
    @Synchronized fun stop() { token = null }
}
