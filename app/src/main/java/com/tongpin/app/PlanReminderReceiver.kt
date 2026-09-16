package com.tongpin.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** Bounds receiver ownership; a single recovery alarm survives process death or slow storage. */
class PlanReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (PlanRecovery.failure != null) { PlanReminderScheduler.cancelAll(context); return }
        val action = intent.action ?: return
        if (action !in setOf(PlanReminderScheduler.FIRE, PlanReminderScheduler.SNOOZE, PlanReminderScheduler.DISMISS,
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        PlanReminderScheduler.rememberAction(context, intent)
        PlanReminderScheduler.armReceiverRetry(context)
        val finished = AtomicBoolean(false)
        val expired = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val deadline = Runnable { expired.set(true); if (finished.compareAndSet(false, true)) pending.finish() }
        handler.postDelayed(deadline, 8_000L)
        PlanReminderScheduler.receive(context, Intent(intent), expired) {
            handler.removeCallbacks(deadline)
            if (finished.compareAndSet(false, true)) pending.finish()
        }
    }
}
