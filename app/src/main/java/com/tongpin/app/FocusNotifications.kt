package com.tongpin.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID

data class FocusReminderSettings(
    val enabled: Boolean = true, val sound: Boolean = true, val vibrate: Boolean = true,
    val soundId: String = ReminderSoundLibrary.defaultId,
    val compatibilitySound: Boolean = false,
)

internal data class ReminderEvent(val token: String, val test: Boolean, val at: Long, val seconds: Int) {
    val notificationId: Int get() = if (test) FocusNotifications.TEST_ID else FocusNotifications.COMPLETION_ID
}

internal data class ReminderPlaybackPlan(val channelId: String, val uri: Uri?, val reason: String?)

class FocusNotifications(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("focus_reminders", Context.MODE_PRIVATE)
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val sounds = ReminderSoundLibrary(app)
    private val diagnostics = ReminderDiagnostics(app)

    fun settings() = FocusReminderSettings(
        prefs.getBoolean("enabled", true), prefs.getBoolean("sound", true), prefs.getBoolean("vibrate", true),
        prefs.getString("sound_id", ReminderSoundLibrary.defaultId) ?: ReminderSoundLibrary.defaultId,
        prefs.getBoolean("compatible_sound", ReminderCompatibility.defaultEnabled(Build.MANUFACTURER, Build.BRAND)),
    )

    fun updateSettings(settings: FocusReminderSettings) {
        val previous = this.settings()
        val normalized = settings.copy(soundId = sounds.find(settings.soundId)?.id ?: ReminderSoundLibrary.defaultId)
        try {
            check(writeSettings(normalized)) { "提醒设置未能保存，请检查可用存储空间后重试。" }
        } catch (failure: Exception) {
            // SharedPreferences can update its in-memory map even when the disk commit
            // fails. Restore that map so this process does not pretend the save worked.
            runCatching { check(writeSettings(previous)) }.onFailure {
                diagnosticLog("提醒设置保存失败，恢复原设置时也未能写入文件", it)
            }
            throw IllegalStateException("提醒设置未能保存，请检查可用存储空间后重试。", failure)
        }
        ensureChannels()
        stopReminderSound()
    }

    private fun writeSettings(settings: FocusReminderSettings): Boolean = prefs.edit()
        .putBoolean("enabled", settings.enabled).putBoolean("sound", settings.sound)
        .putBoolean("vibrate", settings.vibrate).putString("sound_id", settings.soundId)
        .putBoolean("compatible_sound", settings.compatibilitySound).commit()

    fun currentSound(): ReminderSound = sounds.find(settings().soundId)
        ?: sounds.find(ReminderSoundLibrary.defaultId)
        ?: throw IllegalStateException("暂时无法读取提示音，请重新打开提醒设置")

    fun notificationsAllowed(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(app).areNotificationsEnabled()

    fun canScheduleExactAlarms(): Boolean = Build.VERSION.SDK_INT < 31 || app.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) app.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openNotificationSettings() {
        app.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openCompletionSettings() {
        val choice = settings()
        val delivery = prepareCompletion(choice)
        val standard = manager.getNotificationChannel(delivery.channelId)
        val channelId = if (choice.compatibilitySound && standard?.importance != 0 &&
            (!choice.sound || (standard?.importance ?: 0) >= 3 && standard?.sound != null && standard.sound == delivery.soundUri)) ensureCompatibilityChannel(choice)
            else delivery.channelId
        app.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openSoundSettings() {
        app.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Inspect the actual system channel, since its settings override the in-app switches. */
    fun soundStatus(): List<String> {
        val choice = settings()
        val details = mutableListOf<String>()
        val delivery = runCatching { prepareCompletion(choice) }.getOrElse {
            diagnosticLog("暂时无法准备完成提醒，请重新打开提醒设置。", it)
            details.add("暂时无法准备完成提醒，请重新打开提醒设置。")
            null
        }
        val channel = delivery?.let { runCatching { manager.getNotificationChannel(it.channelId) }.getOrNull() }
        delivery?.problem?.let(details::add)
        // A user can replace our built-in sound with the system's default URI, whose
        // actual value may be 'None'. Do not replace their channel to work around it.
        if (choice.enabled && choice.sound && channel?.sound != null) {
            val defaultType = RingtoneManager.getDefaultType(channel.sound)
            if (defaultType != -1 && runCatching {
                    RingtoneManager.getActualDefaultRingtoneUri(app, defaultType) == null
                }.getOrDefault(false)) {
                details.add("当前系统默认提示音设为无声，请在“当前提醒设置”中选择声音。")
            }
        }
        val audio = app.getSystemService(AudioManager::class.java)
        details.addAll(ReminderDeliveryRules.problems(ReminderDeliveryState(
            enabled = choice.enabled,
            sound = choice.sound,
            notificationsAllowed = notificationsAllowed(),
            channelImportance = channel?.importance,
            channelHasSound = channel?.let { it.sound != null },
            channelSoundChanged = channel != null && channel.sound != delivery?.soundUri,
            ringerSilenced = runCatching { audio.ringerMode != AudioManager.RINGER_MODE_NORMAL }.getOrDefault(false),
            notificationVolume = runCatching { audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) }.getOrNull(),
            doNotDisturb = runCatching {
                manager.currentInterruptionFilter in setOf(NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                    NotificationManager.INTERRUPTION_FILTER_NONE, NotificationManager.INTERRUPTION_FILTER_ALARMS)
            }.getOrDefault(false),
        )))
        if (choice.compatibilitySound) {
            val compatible = runCatching { manager.getNotificationChannel(ensureCompatibilityChannel(choice)) }.getOrNull()
            if (compatible == null || compatible.importance < NotificationManager.IMPORTANCE_DEFAULT)
                details.add("兼容提醒已被系统关闭或设为静默，请在“当前提醒设置”中允许提醒。")
            else if (compatible.sound != null)
                details.add("系统为兼容提醒指定了声音，将由系统播放，避免重复响铃。")
        }
        return details.distinct()
    }

    /** Real channel delivery, without adding a focus completion or changing its notification. */
    @Suppress("MissingPermission")
    fun showTest() {
        val event = ReminderEvent(UUID.randomUUID().toString(), true, System.currentTimeMillis(), 0)
        diagnostics.begin(event.token, true)
        try {
            check(settings().enabled) { "请先打开“完成提醒”，再测试提醒。" }
            check(notificationsAllowed()) { "请先允许 plan 发送通知，再测试提醒。" }
            dispatch(event)
        } catch (error: Exception) {
            diagnostics.add(event.token, "测试提醒未提交：${error.javaClass.simpleName}")
            diagnosticLog("测试提醒发送失败", error)
            throw IllegalStateException(if (!settings().enabled) "请先打开“完成提醒”，再测试提醒。"
                else if (!notificationsAllowed()) "请先允许 plan 发送通知，再测试提醒。"
                else "测试提醒未能发送，请检查系统通知设置。", error)
        }
    }

    /** Only delete the exact custom sound channels requested by the user. */
    fun deleteSoundChannels(soundId: String) {
        if (!SoundRules.isCustomId(soundId)) return
        for (vibrate in listOf(false, true)) {
            runCatching { manager.deleteNotificationChannel(ReminderDeliveryRules.channelId(soundId, true, vibrate)) }
                .onFailure { diagnosticLog("无法清理已删除提示音的提醒设置", it) }
        }
    }

    internal fun ensureChannels() {
        runCatching {
            ensureRunningChannel()
            prepareCompletion(settings())
        }.onFailure { diagnosticLog("暂时无法更新提醒设置", it) }
    }

    private fun ensureRunningChannel() {
        if (manager.getNotificationChannel(RUNNING_CHANNEL) != null) return
        manager.createNotificationChannel(NotificationChannel(RUNNING_CHANNEL, "专注计时", NotificationManager.IMPORTANCE_LOW).apply {
            description = "显示剩余时间，并暂停、继续或结束本次专注"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }

    private data class CompletionDelivery(val channelId: String, val soundUri: Uri?, val problem: String?)

    private fun prepareCompletion(choice: FocusReminderSettings): CompletionDelivery {
        var problem: String? = null
        var sound: ReminderSound? = null
        var uri: Uri? = null
        if (choice.sound) {
            sound = runCatching { sounds.find(choice.soundId) }.getOrNull()
            if (sound == null) problem = "所选音频已不可用，已改用清晨铃；可重新导入或选择提示音。"
            if (sound != null) {
                val selected = sound
                uri = runCatching { sounds.uri(selected) }.onFailure {
                    diagnosticLog("所选提示音读取失败，将使用清晨铃", it)
                }.getOrNull()
            }
            if (uri == null) {
                if (sound != null) problem = "所选音频暂时无法读取，已改用清晨铃；可重新导入或选择提示音。"
                sound = runCatching { sounds.find(ReminderSoundLibrary.defaultId) }.getOrNull()
                uri = sound?.let { fallback -> runCatching { sounds.uri(fallback) }.onFailure {
                    diagnosticLog("默认提示音读取失败", it)
                }.getOrNull() }
            }
            if (uri == null) problem = "暂时无法读取提示音，本次将保留通知与振动；请重新选择提示音。"
        }
        val hasSound = choice.sound && uri != null
        val id = ReminderDeliveryRules.channelId(sound?.id ?: ReminderSoundLibrary.defaultId, hasSound, choice.vibrate)
        // Keep the existing channel, including any system mute/importance chosen by the user.
        if (manager.getNotificationChannel(id) == null) {
            val name = if (hasSound) "专注完成 · ${sound?.name ?: "提示音"}${if (choice.vibrate) "与振动" else ""}"
                else if (choice.vibrate) "专注完成 · 振动" else "专注完成 · 静音"
            manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "专注时长结束时提醒"
                setSound(uri, notificationAudioAttributes())
                enableVibration(choice.vibrate)
                if (choice.vibrate) vibrationPattern = longArrayOf(0, 300, 180, 300)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        // A read-only grant also covers OEM players that do not inherit AOSP's channel grant.
        run {
            val actualSound = manager.getNotificationChannel(id)?.sound
            if (actualSound?.scheme == "content" && actualSound.authority == "${app.packageName}.files") {
                runCatching { app.grantUriPermission("com.android.systemui", actualSound, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    .onFailure { diagnosticLog("无法向系统授权读取提示音", it) }
            }
        }
        return CompletionDelivery(id, uri, problem)
    }

    internal fun timerNotification(state: ClockState, remaining: Int): Notification {
        // Frequent countdown updates must not read/copy the audio library.
        ensureRunningChannel()
        val time = String.format(Locale.ROOT, "%02d:%02d", remaining / 60, remaining % 60)
        val builder = NotificationCompat.Builder(app, RUNNING_CHANNEL)
            .setSmallIcon(R.drawable.ic_focus_notification)
            .setContentTitle(if (state.running) "专注进行中" else "专注已暂停")
            .setContentText(if (state.running) "本次 ${state.minutes} 分钟" else "剩余 $time")
            .setContentIntent(openApp())
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true).setSilent(true).setOngoing(state.running)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (state.running) "暂停" else "继续", action(if (state.running) FocusActionReceiver.PAUSE else FocusActionReceiver.RESUME, state.id))
            .addAction(0, "结束", action(FocusActionReceiver.STOP, state.id))
        if (state.running) builder.setWhen(System.currentTimeMillis() + remaining * 1000L)
            .setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        else builder.setShowWhen(false)
        return builder.build()
    }

    internal fun stoppedSoundNotification(): Notification {
        ensureRunningChannel()
        return NotificationCompat.Builder(app, RUNNING_CHANNEL).setSmallIcon(R.drawable.ic_focus_notification)
            .setContentTitle("提醒已结束").setSilent(true).setOnlyAlertOnce(true).build()
    }

    internal fun showTimer(state: ClockState) {
        runCatching {
            if (notificationsAllowed()) notifySafely(TIMER_ID, timerNotification(state, FocusClock(app).remaining(state)))
        }.onFailure { diagnosticLog("专注计时通知暂时无法显示", it) }
    }

    internal fun showCompletion(completion: FocusCompletion) {
        // The completed state and pending record were committed by Clock.tick beforehand.
        // Audio/notification failures must not interrupt acknowledgement of that record.
        runCatching {
            dispatch(ReminderEvent(completion.id, false, completion.completedAt, completion.seconds))
        }.onFailure {
            diagnostics.add(completion.id, "完成提醒准备失败：${it.javaClass.simpleName}")
            diagnosticLog("专注已完成，但系统暂时无法显示提醒", it)
        }
    }

    /** Both test and completion enter the same channel, gate checks and service path. */
    private fun dispatch(event: ReminderEvent) {
        diagnostics.begin(event.token, event.test)
        val choice = settings()
        if (!choice.enabled || !notificationsAllowed()) {
            stopSound()
            diagnostics.add(event.token, if (!choice.enabled) "未提交通知：完成提醒已关闭" else "未提交通知：系统通知权限未允许")
            return
        }
        val plan = playbackPlan()
        plan.reason?.let { diagnostics.add(event.token, it) }
        if (plan.uri == null) {
            stopSound()
            postEvent(event, plan.channelId)
            return
        }
        ReminderPlaybackControl.activate(event.token)
        try {
            ContextCompat.startForegroundService(app, Intent(app, ReminderSoundService::class.java)
                .putExtra("token", event.token).putExtra("test", event.test)
                .putExtra("at", event.at).putExtra("seconds", event.seconds))
            diagnostics.add(event.token, "已请求兼容播放，等待播放器启动")
        } catch (failure: Exception) {
            ReminderPlaybackControl.stop()
            fallbackToSystem(event, "兼容服务未能启动：${failure.javaClass.simpleName}")
        }
    }

    /** A null URI deliberately delegates to the system; never defeat its mute choices. */
    internal fun playbackPlan(): ReminderPlaybackPlan {
        val choice = settings()
        val normal = prepareCompletion(choice)
        if (!choice.compatibilitySound || !choice.sound) return ReminderPlaybackPlan(normal.channelId, null,
            if (choice.sound) "使用系统通知声音" else "提示声音已关闭")
        val standard = manager.getNotificationChannel(normal.channelId)
        val compatibleId = ensureCompatibilityChannel(choice)
        val compatible = manager.getNotificationChannel(compatibleId)
        val audio = app.getSystemService(AudioManager::class.java)
        val state = ReminderCompatibilityState(
            enabled = choice.enabled, sound = choice.sound, notificationsAllowed = notificationsAllowed(),
            standardImportance = standard?.importance, standardHasSound = standard?.sound != null,
            ringerSilenced = runCatching { audio.ringerMode != AudioManager.RINGER_MODE_NORMAL }.getOrDefault(true),
            notificationVolume = runCatching { audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) }.getOrNull(),
            doNotDisturb = runCatching { manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL }.getOrDefault(true),
            compatibilityImportance = compatible?.importance,
        )
        val blocked = ReminderCompatibility.blockedReason(state)
        if (blocked != null) {
            val id = if (compatible == null || compatible.importance < NotificationManager.IMPORTANCE_DEFAULT) compatibleId else normal.channelId
            return ReminderPlaybackPlan(id, null, "未进行兼容播放：$blocked")
        }
        if (standard?.sound != normal.soundUri) return ReminderPlaybackPlan(normal.channelId, null,
            "系统已为原提醒频道更换声音，交由系统播放")
        if (compatible?.sound != null) return ReminderPlaybackPlan(compatibleId, null,
            "系统已为兼容提醒指定声音，交由系统播放")
        if (normal.soundUri == null) return ReminderPlaybackPlan(normal.channelId, null, "音频暂时无法读取，保留通知")
        return ReminderPlaybackPlan(compatibleId, normal.soundUri, normal.problem)
    }

    internal fun fallbackToSystem(event: ReminderEvent, reason: String) {
        diagnostics.add(event.token, "$reason；尝试系统通知声音")
        runCatching {
            if (settings().enabled && notificationsAllowed()) {
                // Recheck a mute/settings change that may have happened while
                // the service was preparing. A blocked compatibility channel
                // must not fall through to a different audible channel.
                val current = playbackPlan()
                val channel = if (current.uri == null) current.channelId else prepareCompletion(settings()).channelId
                postEvent(event, channel)
            }
        }.onFailure { diagnostics.add(event.token, "系统通知回退失败：${it.javaClass.simpleName}") }
    }

    @Suppress("MissingPermission")
    private fun postEvent(event: ReminderEvent, channelId: String) {
        try {
            manager.cancel(event.notificationId)
            manager.notify(event.notificationId, eventNotification(event, channelId))
            diagnostics.add(event.token, "通知已提交系统（是否可听见仍取决于系统设置）")
        } catch (failure: Exception) {
            diagnostics.add(event.token, "通知提交失败：${failure.javaClass.simpleName}")
            throw failure
        }
    }

    internal fun eventNotification(event: ReminderEvent, channelId: String, playing: Boolean = false): Notification {
        val builder = completionBuilder(channelId)
            .setContentTitle(if (event.test) "这是一次测试提醒" else "本次专注已完成")
            .setContentText(if (event.test) "专注结束时会使用相同的声音与振动设置" else "已完成 ${event.seconds / 60} 分钟，轻点返回 plan")
            // A new compatibility event can reuse the still-visible test ID.
            // Its initial foreground post may vibrate; the final UI-only update
            // uses playing=false and must remain quiet.
            .setWhen(event.at).setOnlyAlertOnce(!playing)
        if (event.test) builder.setTimeoutAfter(70_000L)
        if (playing) builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "停止声音", PendingIntent.getBroadcast(app, 7410,
                Intent(app, FocusActionReceiver::class.java).setAction(FocusActionReceiver.STOP_SOUND),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        return builder.build()
    }

    @Suppress("MissingPermission")
    internal fun finishPlayingNotification(event: ReminderEvent, channelId: String) {
        runCatching { manager.notify(event.notificationId, eventNotification(event, channelId)) }
    }

    private fun ensureCompatibilityChannel(choice: FocusReminderSettings): String {
        val id = "focus_compatible_v1_v${if (choice.vibrate) 1 else 0}"
        if (manager.getNotificationChannel(id) == null) manager.createNotificationChannel(
            NotificationChannel(id, "专注完成 · 兼容播放${if (choice.vibrate) "与振动" else ""}", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "配合应用内的兼容播放显示完成提醒"
                setSound(null, null); enableVibration(choice.vibrate)
                if (choice.vibrate) vibrationPattern = longArrayOf(0, 300, 180, 300)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        return id
    }

    fun stopSound() {
        ReminderPlaybackControl.stop()
        runCatching { app.stopService(Intent(app, ReminderSoundService::class.java)) }
        // The system player owns standard-channel sounds. Stopping only our
        // compatibility service cannot stop a long custom tone playing there.
        runCatching { manager.cancel(TEST_ID) }
        runCatching { manager.cancel(COMPLETION_ID) }
    }
    fun stopReminderSound() = stopSound()

    fun diagnosticReport(): String {
        val choice = settings()
        val audio = app.getSystemService(AudioManager::class.java)
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "未知"
        val normal = runCatching { prepareCompletion(choice) }.getOrNull()
        val standard = normal?.let { manager.getNotificationChannel(it.channelId) }
        val compatible = runCatching { manager.getNotificationChannel(ensureCompatibilityChannel(choice)) }.getOrNull()
        return buildString {
            appendLine("plan 提醒诊断")
            appendLine("设备：${Build.MANUFACTURER} / ${Build.BRAND} ${Build.MODEL}")
            appendLine("Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）· plan $version")
            appendLine("完成提醒：${choice.enabled} · 提示声音：${choice.sound} · 兼容播放：${choice.compatibilitySound}")
            appendLine("通知允许：${notificationsAllowed()}")
            appendLine("响铃模式：${runCatching { audio.ringerMode }.getOrNull()}（2 为响铃）")
            appendLine("通知音量：${runCatching { audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) }.getOrNull()} / ${runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION) }.getOrNull()}")
            appendLine("勿扰过滤：${runCatching { manager.currentInterruptionFilter }.getOrNull()}（1 为允许全部）")
            appendLine("原频道：级别 ${standard?.importance} · 有声音 ${standard?.sound != null}")
            appendLine("兼容频道：级别 ${compatible?.importance} · 系统指定声音 ${compatible?.sound != null}")
            appendLine("频道级别 0 为关闭，1–2 为静默；兼容频道默认无系统声音，由应用播放。")
            appendLine()
            appendLine(diagnostics.latest())
            appendLine()
            append("只记录本机提醒状态，不包含任务、心得或音频内容。播放器已开始不代表手机一定发出了可听见的声音。")
        }
    }

    private fun completionBuilder(channelId: String) = NotificationCompat.Builder(app, channelId)
        .setSmallIcon(R.drawable.ic_focus_notification)
        .setContentIntent(openApp()).setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    internal fun clearTimer() { runCatching { manager.cancel(TIMER_ID) }.onFailure { diagnosticLog("无法清理专注计时通知", it) } }
    internal fun clearCompletion() { stopReminderSound();runCatching { manager.cancel(COMPLETION_ID) }.onFailure { diagnosticLog("无法清理完成提醒", it) } }

    @Suppress("MissingPermission")
    private fun notifySafely(id: Int, notification: Notification) {
        runCatching { manager.notify(id, notification) }.onFailure { diagnosticLog("系统拒绝显示提醒", it) }
    }

    private fun diagnosticLog(message: String, error: Throwable) { Log.w("PlanReminders", message, error) }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(app, 7400,
        Intent(app, MainActivity::class.java).putExtra("open_focus", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun action(action: String, sessionId: String): PendingIntent = PendingIntent.getBroadcast(app, action.hashCode(),
        Intent(app, FocusActionReceiver::class.java).setAction(action)
            .setData(Uri.parse("plan://focus/${Uri.encode(sessionId)}/${Uri.encode(action)}"))
            .putExtra(FocusActionReceiver.SESSION_ID, sessionId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notificationAudioAttributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    companion object {
        internal const val TIMER_ID = 7401
        internal const val COMPLETION_ID = 7402
        internal const val TEST_ID = 7404
        private const val RUNNING_CHANNEL = "focus_timer"
    }
}

