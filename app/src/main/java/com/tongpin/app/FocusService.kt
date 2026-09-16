package com.tongpin.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/** Visible, user-started countdown with notification controls; alarms deliver completion in sleep. */
class FocusService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clock: FocusClock
    private lateinit var notifications: FocusNotifications
    private val update = object : Runnable {
        override fun run() {
            val state = try { clock.tick() } catch (failure: Exception) {
                // Keep the existing foreground timer. A failed completion must not
                // disappear or produce a notification until its event is durable.
                Log.w("PlanFocus", "专注状态暂未保存，将稍后重试", failure)
                runCatching { FocusAlarm.scheduleRetry(this@FocusService, clock.load()) }
                handler.postDelayed(this, FocusAlarm.RETRY_DELAY_MS)
                return
            }
            if (!state.running) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (state.id.isNotEmpty() && !state.completed) notifications.showTimer(state)
                stopSelf()
                return
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        clock = FocusClock(this)
        notifications = FocusNotifications(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (PlanRecovery.failure != null) {
            runCatching {
                val notification = notifications.stoppedSoundNotification()
                if (Build.VERSION.SDK_INT >= 34) startForeground(FocusNotifications.TIMER_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(FocusNotifications.TIMER_ID, notification)
            }
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId); return START_NOT_STICKY
        }
        val state = try { clock.load() } catch (failure: Exception) {
            Log.w("PlanFocus", "暂时无法读取专注状态", failure)
            // A foreground-service start still needs a timely promotion even when
            // loading fails, before its placeholder is removed and the service stops.
            runCatching {
                val notification = notifications.stoppedSoundNotification()
                if (Build.VERSION.SDK_INT >= 34) startForeground(FocusNotifications.TIMER_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(FocusNotifications.TIMER_ID, notification)
            }
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!state.running) { stopSelf(); return START_NOT_STICKY }
        // Keep the alarm even if promoting this service is rejected by the system.
        FocusAlarm.schedule(this, state)
        val notification = notifications.timerNotification(state, clock.remaining(state))
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(FocusNotifications.TIMER_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(FocusNotifications.TIMER_ID, notification)
        } catch (_: SecurityException) {
            stopSelf(startId)
            return START_NOT_STICKY
        } catch (_: IllegalStateException) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        handler.removeCallbacks(update)
        handler.post(update)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(update)
        // Pausing stops the service; keep a normal notification so Continue remains available.
        if (PlanRecovery.failure == null) {
            runCatching {
                val state = clock.load()
                if (!state.running && state.id.isNotEmpty() && !state.completed) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    notifications.showTimer(state)
                }
            }.onFailure { Log.w("PlanFocus", "暂时无法更新专注通知", it) }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

internal object FocusRuntime {
    fun sync(context: Context, state: ClockState) {
        val app = context.applicationContext
        if (state.running) {
            FocusAlarm.schedule(app, state)
            // Permission/background restrictions must never discard a persisted timer.
            try { ContextCompat.startForegroundService(app, Intent(app, FocusService::class.java)) }
            catch (_: IllegalStateException) { }
            catch (_: SecurityException) { }
        } else {
            FocusAlarm.cancel(app)
            app.stopService(Intent(app, FocusService::class.java))
            val notifications = FocusNotifications(app)
            if (state.id.isNotEmpty() && !state.completed && state.remaining > 0) notifications.showTimer(state)
            else notifications.clearTimer()
        }
    }
}

internal object FocusAlarm {
    internal const val RETRY_DELAY_MS = 30_000L
    private fun pending(context: Context, sessionId: String = ""): PendingIntent = PendingIntent.getBroadcast(context, 7403,
        Intent(context, FocusActionReceiver::class.java).setAction(FocusActionReceiver.COMPLETE)
            .putExtra(FocusActionReceiver.SESSION_ID, sessionId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(context: Context, state: ClockState) {
        if (!state.running) return
        scheduleAt(context, state.id, state.elapsedDeadline.coerceAtLeast(SystemClock.elapsedRealtime()))
    }

    /** Retry the persisted session, without changing its actual completion deadline. */
    fun scheduleRetry(context: Context, state: ClockState) {
        if (!state.running) return
        scheduleAt(context, state.id, SystemClock.elapsedRealtime() + RETRY_DELAY_MS)
    }

    private fun scheduleAt(context: Context, sessionId: String, deadline: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val delivery = pending(context, sessionId)
        try {
            if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, delivery)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, delivery)
            }
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, delivery)
        }
    }

    fun cancel(context: Context) { context.getSystemService(AlarmManager::class.java).cancel(pending(context)) }
}
