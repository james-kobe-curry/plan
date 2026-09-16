package com.tongpin.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper

/** Short, user-initiated preview. Completion alerts use the notification channel instead. */
class ReminderPreview(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var stopped: (() -> Unit)? = null

    fun play(uri: Uri, onStopped: () -> Unit, onError: (String) -> Unit) {
        stop()
        val next = MediaPlayer()
        player = next
        stopped = onStopped
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        try {
            next.setAudioAttributes(attributes)
            next.setDataSource(app, uri)
            next.setOnPreparedListener {
                if (player !== next) return@setOnPreparedListener
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
                        if (change < 0 && player === next) stop()
                    }.build()
                focus = request
                if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    stop(); onError("暂时无法试听，请稍后重试")
                } else try {
                    next.start()
                    handler.postDelayed({ if (player === next) stop() }, 10_000L)
                } catch (_: Exception) { stop(); onError("无法播放这个提示音，请重新选择") }
            }
            next.setOnCompletionListener { if (player === next) stop() }
            next.setOnErrorListener { _, _, _ ->
                if (player === next) { stop(); onError("无法播放这个提示音，请重新选择") }
                true
            }
            next.prepareAsync()
        } catch (_: Exception) { stop(); onError("无法读取提示音，请重新选择或导入") }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        val old = player
        player = null
        runCatching { old?.release() }
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        val callback = stopped
        stopped = null
        callback?.invoke()
    }
}
