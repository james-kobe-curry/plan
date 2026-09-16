package com.tongpin.app

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/** Explicit, non-exported alarm/control receiver; no network or data-store writes. */
class FocusActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (PlanRecovery.failure != null) return
        if (intent.action == STOP_SOUND) { FocusNotifications(context).stopSound(); return }
        val clock = FocusClock(context)
        try { when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> clock.restore()
            Intent.ACTION_TIME_CHANGED -> {
                // Re-anchor only the wall-clock backup for reboot; elapsed time remains untouched.
                val state = clock.tick()
                if (state.running) clock.save(state.copy(deadline = System.currentTimeMillis() + clock.remaining(state) * 1000L))
            }
            else -> {
                val state = clock.tick()
                if (state.id != intent.getStringExtra(SESSION_ID)) return
                when (intent.action) {
                    COMPLETE -> if (state.running) FocusAlarm.schedule(context, state)
                    PAUSE -> clock.pause(state)
                    RESUME -> if (!state.running && !state.completed) clock.start(state)
                    STOP -> clock.save(ClockState(planId = state.planId, minutes = state.minutes, remaining = state.minutes * 60))
                }
            }
        } } catch (failure: Exception) {
            Log.w("PlanFocus", "专注操作暂未保存，保留原计时", failure)
            // Alarm delivery has consumed its PendingIntent. Re-arm a bounded retry
            // so a locked device can finish after storage becomes writable again.
            runCatching {
                val state = clock.load()
                if (state.running) FocusAlarm.scheduleRetry(context, state)
                else if (state.id.isNotEmpty() && !state.completed) FocusNotifications(context).showTimer(state)
            }.onFailure { Log.w("PlanFocus", "暂时无法安排专注重试", it) }
            if (intent.action in setOf(PAUSE, RESUME, STOP)) runCatching {
                Toast.makeText(context, "专注操作未能保存，请打开 plan 后重试。", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val SESSION_ID = "focus_session_id"
        const val COMPLETE = "com.tongpin.app.FOCUS_COMPLETE"
        const val PAUSE = "com.tongpin.app.FOCUS_PAUSE"
        const val RESUME = "com.tongpin.app.FOCUS_RESUME"
        const val STOP = "com.tongpin.app.FOCUS_STOP"
        const val STOP_SOUND = "com.tongpin.app.STOP_REMINDER_SOUND"
    }
}
