package com.tongpin.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** A short, visible notification player for devices whose SystemUI cannot read our sound URI. */
class ReminderSoundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notifications: FocusNotifications
    private lateinit var diagnostics: ReminderDiagnostics
    private lateinit var audio: AudioManager
    private var event: ReminderEvent? = null
    private var channelId: String? = null
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false
    private var finishing = false
    private var activeStartId = 0

    override fun onCreate() {
        super.onCreate()
        notifications = FocusNotifications(this)
        diagnostics = ReminderDiagnostics(this)
        audio = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Even an ignored duplicate is a newer Android service start. The eventual
        // stopSelf must acknowledge it, otherwise an idle service can remain alive.
        activeStartId = startId
        if (PlanRecovery.failure != null) {
            runCatching { promote(7405, notifications.stoppedSoundNotification()) }
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId); return START_NOT_STICKY
        }
        val source = intent ?: run { stopSelf(startId); return START_NOT_STICKY }
        val token = source.getStringExtra("token")
        if (token.isNullOrEmpty()) { stopSelf(startId); return START_NOT_STICKY }
        val incoming = ReminderEvent(token, source.getBooleanExtra("test", false),
            source.getLongExtra("at", System.currentTimeMillis()), source.getIntExtra("seconds", 0))
        if (event?.token == token && !finishing) return START_NOT_STICKY
        // A queued start can arrive after Stop. Promote a silent placeholder then remove it.
        if (!ReminderPlaybackControl.current(token)) {
            if (event == null) {
                runCatching { promote(7405, notifications.stoppedSoundNotification()) }
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        event?.let { diagnostics.add(it.token, "声音已停止：开始新的提醒") }
        releasePlayer()
        if (event != null) stopForeground(STOP_FOREGROUND_DETACH)
        event = incoming; finishing = false; started = false
        val prefs = getSharedPreferences("reminder_player", Context.MODE_PRIVATE)
        if (prefs.getString("last_event", "") == token) {
            // Check before promoting the completion/test notification: a new
            // start for a finished token must not vibrate a second time either.
            runCatching { promote(7405, notifications.stoppedSoundNotification()) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            finishing = true
            diagnostics.add(token, "已处理过的提醒，不重复播放或振动")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Reserve the event before any notification or fallback is submitted.
        // A repeated start must not produce a second vibration after a failure.
        prefs.edit().putString("last_event", token).apply()
        val plan = try { notifications.playbackPlan() } catch (failure: Exception) {
            runCatching { promote(7405, notifications.stoppedSoundNotification()) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            notifications.fallbackToSystem(incoming, "兼容播放准备失败：${failure.javaClass.simpleName}")
            stopSelf(startId); return START_NOT_STICKY
        }
        channelId = plan.channelId
        try {
            promote(incoming.notificationId, notifications.eventNotification(incoming, plan.channelId, playing = plan.uri != null))
            diagnostics.add(token, "通知已提交系统，兼容前台服务已启动")
        } catch (failure: Exception) {
            notifications.fallbackToSystem(incoming, "兼容前台服务受限：${failure.javaClass.simpleName}")
            stopSelf(startId); return START_NOT_STICKY
        }
        if (plan.uri == null) {
            diagnostics.add(token, plan.reason ?: "交由系统通知处理")
            finish("兼容播放未启动")
            return START_NOT_STICKY
        }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "plan:reminder").apply {
            setReferenceCounted(false)
            runCatching { acquire(70_000L) }
        }
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val next = try { MediaPlayer() } catch (failure: Exception) {
            fail("播放器创建失败：${failure.javaClass.simpleName}"); return START_NOT_STICKY
        }
        player = next
        try {
            next.setAudioAttributes(attributes)
            next.isLooping = false
            next.setDataSource(this, plan.uri)
            next.setOnPreparedListener {
                if (player !== next || !ReminderPlaybackControl.current(token)) return@setOnPreparedListener
                val recheck = runCatching { notifications.playbackPlan() }.getOrNull()
                if (recheck?.uri != plan.uri || recheck?.channelId != plan.channelId) {
                    finish(recheck?.reason ?: "设置已改变，声音已停止")
                    return@setOnPreparedListener
                }
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                        if (change < 0 && player === next) finish("声音已停止：音频焦点转移")
                    }, handler).build()
                focus = request
                try {
                    if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        finish("系统暂未允许音频焦点，未播放声音")
                    } else if (!ReminderPlaybackControl.current(token)) {
                        finish("声音已停止")
                    } else {
                        next.start(); started = true
                        diagnostics.add(token, "兼容播放器已开始（通知音量）")
                        handler.postDelayed({ if (player === next) finish("声音已停止：达到 60 秒上限") }, 60_000L)
                    }
                } catch (failure: Exception) { fail("播放启动失败：${failure.javaClass.simpleName}") }
            }
            next.setOnCompletionListener { if (player === next) finish("提示音播放结束") }
            next.setOnErrorListener { _, what, extra ->
                if (player === next) fail("音频解码失败（$what / $extra）")
                true
            }
            next.prepareAsync()
            handler.postDelayed({ if (player === next && !started) fail("音频准备超时") }, 10_000L)
            handler.postDelayed({ if (player === next) finish("声音已停止：服务达到时限") }, 65_000L)
            val guard = object : Runnable {
                override fun run() {
                    if (player !== next) return
                    if (!ReminderPlaybackControl.current(token)) { finish("声音已停止"); return }
                    val recheck = runCatching { notifications.playbackPlan() }.getOrNull()
                    if (recheck?.uri != plan.uri || recheck?.channelId != plan.channelId) {
                        finish(recheck?.reason ?: "系统状态已改变，声音已停止"); return
                    }
                    handler.postDelayed(this, 1000L)
                }
            }
            handler.postDelayed(guard, 1000L)
        } catch (failure: Exception) { fail("音频读取失败：${failure.javaClass.simpleName}") }
        return START_NOT_STICKY
    }

    private fun promote(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(id, notification)
    }

    private fun fail(reason: String) {
        val current = event ?: return
        val mayFallback = !started && ReminderPlaybackControl.current(current.token)
        finish(reason)
        if (mayFallback) notifications.fallbackToSystem(current, reason)
    }

    private fun finish(reason: String) {
        if (finishing) return
        finishing = true
        event?.let { diagnostics.add(it.token, reason) }
        releasePlayer()
        stopForeground(STOP_FOREGROUND_DETACH)
        val current = event
        val channel = channelId
        if (current != null && channel != null && ReminderPlaybackControl.current(current.token))
            notifications.finishPlayingNotification(current, channel)
        stopSelf(activeStartId)
    }

    private fun releasePlayer() {
        handler.removeCallbacksAndMessages(null)
        val old = player; player = null
        runCatching { old?.release() }
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }; focus = null
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }; wakeLock = null
    }

    override fun onDestroy() {
        if (!finishing) event?.let { diagnostics.add(it.token, "声音已停止") }
        releasePlayer()
        stopForeground(STOP_FOREGROUND_DETACH)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
