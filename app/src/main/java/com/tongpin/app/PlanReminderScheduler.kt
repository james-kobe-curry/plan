package com.tongpin.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** All task reminders share one persisted alarm and one short-lived worker queue. */
object PlanReminderScheduler {
    internal const val FIRE = "com.tongpin.app.PLAN_REMINDER"
    internal const val SNOOZE = "com.tongpin.app.PLAN_REMINDER_SNOOZE"
    internal const val DISMISS = "com.tongpin.app.PLAN_REMINDER_DISMISS"
    private const val CHANNEL = "plan_task_reminders_v1"
    private const val TAG = "plan-task:"
    private const val NOTIFICATION_ID = 9320
    private const val ALARM_ID = 9310
    private val executor = Executors.newSingleThreadExecutor { run -> Thread(run, "plan-reminders").apply { isDaemon = true } }
    private val queued = AtomicBoolean(false)
    private val epoch = AtomicLong(0)

    fun reschedule(context: Context) {
        val app = context.applicationContext
        val revision = epoch.get()
        if (queued.compareAndSet(false, true)) executor.execute {
            queued.set(false)
            runCatching { reconcile(app, null, revision) }.onFailure { Log.w("PlanTasks", "任务提醒暂未重新安排", it) }
        }
    }

    /** Immediately invalidates in-flight delivery; restoration can then acquire the data lock. */
    fun cancelAll(context: Context) {
        val app = context.applicationContext
        val revision = epoch.incrementAndGet()
        cancelAlarm(app); cancelVisible(app)
        executor.execute { if (revision == epoch.get()) {
            cancelAlarm(app); cancelVisible(app)
            preferences(app).edit().remove("alarm_at").remove("alarm_token").commit()
        } }
    }

    fun resetAfterRestore(context: Context) {
        val app = context.applicationContext
        val revision = epoch.incrementAndGet()
        cancelAlarm(app); cancelVisible(app)
        executor.execute {
            if (revision == epoch.get()) runCatching {
                cancelAlarm(app); cancelVisible(app)
                check(preferences(app).edit().clear().commit()) { "提醒状态暂未重置" }
                reconcile(app, null, revision)
            }.onFailure { Log.w("PlanTasks", "恢复后的任务提醒暂未安排", it) }
        }
    }

    internal fun receive(context: Context, intent: Intent, expired: AtomicBoolean, finished: () -> Unit) {
        val app = context.applicationContext
        val revision = epoch.get()
        executor.execute {
            try { if (!expired.get()) reconcile(app, intent, revision, expired) }
            catch (failure: Exception) { Log.w("PlanTasks", "任务提醒处理暂未完成", failure) }
            finally { finished() }
        }
    }

    /** A notification action remains available if its receiver times out before the worker runs. */
    internal fun rememberAction(context: Context, intent: Intent) {
        if (intent.action != SNOOZE && intent.action != DISMISS) return
        val event = eventFrom(intent) ?: return
        val key = "action:${event.seriesId}@${event.date}@${event.at}"
        val value = JSONObject().put("action", intent.action).put("planId", event.planId).put("seriesId", event.seriesId)
            .put("date", event.date).put("deliveryAt", event.at).toString()
        // apply updates the in-process map synchronously and queues its tiny disk write;
        // the receiver owns goAsync while the data worker validates the action.
        preferences(context).edit().putString(key, value).apply()
    }

    /** Keep one recovery alarm while a receiver works, including process death during decoding. */
    internal fun armReceiverRetry(context: Context) {
        if (blocked(context)) { cancelAlarm(context); return }
        runCatching {
            val prefs = preferences(context)
            val operation = alarmIntent(context, prefs.getString("alarm_token", "retry").orEmpty(), prefs.getLong("alarm_at", 0))
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 90_000L, operation)
        }.onFailure { Log.w("PlanTasks", "无法安排提醒重试", it) }
    }

    fun canScheduleExact(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun notificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun status(context: Context): List<String> {
        ensureChannel(context)
        val result = mutableListOf<String>()
        preferences(context).getString("last_error", null)?.let { result += it }
        if (!notificationsAllowed(context)) result += "尚未允许通知，任务提醒暂时无法显示。"
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) result += "系统已关闭任务提醒频道，请在提醒设置中开启。"
        else if (channel != null && (channel.importance < NotificationManager.IMPORTANCE_DEFAULT || channel.sound == null))
            result += "任务提醒已设为静默，将按系统设置显示。"
        if (!canScheduleExact(context)) result += "当前为普通提醒，省电或休眠时可能延迟；可在系统中允许准时提醒。"
        return result
    }

    fun openSettings(context: Context) {
        ensureChannel(context)
        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openExactSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= 31) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun reconcile(context: Context, trigger: Intent?, revision: Long, expired: AtomicBoolean = AtomicBoolean(false)) {
        fun valid() = revision == epoch.get() && !expired.get() && !blocked(context)
        if (!valid()) { if (blocked(context)) { cancelAlarm(context); cancelVisible(context) }; return }
        AppStore(context).exclusive {
            if (!valid()) return@exclusive
            val data = try { AppStore(context).load() } catch (failure: Exception) {
                // The store preserves an unreadable file on each failed load. Do not
                // repeat that indefinitely from a background retry alarm.
                cancelAlarm(context); cancelVisible(context)
                preferences(context).edit().remove("alarm_at").remove("alarm_token")
                    .putString("last_error", "计划数据暂时无法读取，任务提醒已暂停；恢复数据后会重新安排。").commit()
                throw failure
            }
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val prefs = preferences(context)
            val days = readDays(context).filterValues { it.date >= today.minusDays(30).toString() && it.date <= today.plusDays(1).toString() }.toMutableMap()
            val configurations = readConfigurations(context)
            val active = PlanReminderRules.configurationPlans(data, today)
            val plansById = data.plans.associateBy { it.id }
            val nextConfigurations = active.associate { it.seriesId to PlanReminderRules.configuration(it) }
            active.forEach { plan ->
                val key = PlanReminderRules.key(plan.seriesId, today.toString())
                val state = PlanReminderRules.configuredState(data, plan, days[key],
                    !PlanReminderRules.configurationMatches(configurations[plan.seriesId], plan), now, zone)
                if (state != null) days[key] = state
            }
            if (!valid()) return@exclusive
            val pendingActions = prefs.all.filter { it.key.startsWith("action:") && it.value is String }
            for ((_, stored) in pendingActions) {
                val action = runCatching {
                    val row = JSONObject(stored as String)
                    Intent().setAction(row.getString("action")).putExtra("planId", row.getString("planId"))
                        .putExtra("seriesId", row.getString("seriesId")).putExtra("date", row.getString("date"))
                        .putExtra("deliveryAt", row.getLong("deliveryAt"))
                }.getOrNull()
                if (action?.action !in setOf(SNOOZE, DISMISS)) continue
                eventFrom(action!!)?.let { event ->
                    val key = PlanReminderRules.key(event.seriesId, event.date)
                    PlanReminderRules.action(data, days[key], event, now, zone, action.action == SNOOZE)?.let { days[key] = it }
                }
            }
            // The day state and action removals share one commit, so a crash cannot
            // consume an action without retaining its snooze/dismiss result.
            if (!valid()) return@exclusive
            saveDays(context, days, nextConfigurations, pendingActions)
            cancelInvalidVisible(context, data, days, today)
            ensureChannel(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!notificationsAllowed(context) || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
                saveDays(context, days, nextConfigurations)
                cancelAlarm(context); cancelVisible(context)
                prefs.edit().remove("alarm_at").remove("alarm_token").commit()
                return@exclusive
            }
            val storedAt = prefs.getLong("alarm_at", 0)
            val storedToken = prefs.getString("alarm_token", null)
            val clockChanged = trigger?.action in setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
            val pendingToday = storedAt > 0 && Instant.ofEpochMilli(storedAt).atZone(zone).toLocalDate() == today
            val minimum = if (!clockChanged && pendingToday && storedAt <= now) storedAt else now
            val firing = trigger?.action == FIRE && storedToken != null && trigger.getStringExtra("token") == storedToken &&
                trigger.getLongExtra("scheduledAt", 0) == storedAt && storedAt <= now && pendingToday
            if (firing) {
                val due = PlanReminderRules.candidates(data, days, now, zone, minimum).filter { it.at <= now }
                for (event in due) {
                    if (!valid()) return@exclusive
                    val plan = plansById[event.planId] ?: continue
                    if (!PlanReminderRules.eligible(data, plan, today)) continue
                    val key = PlanReminderRules.key(event.seriesId, event.date)
                    days[key] = PlanReminderDay(event.seriesId, event.date, handled = true, deliveredAt = event.at,
                        deliveryConfiguration = PlanReminderRules.configuration(plan))
                    // Persist before submitting so a restarted receiver cannot replay the same day.
                    saveDays(context, days, nextConfigurations)
                    // If the disk commit crossed the receiver's soft deadline,
                    // complete this one short binder submission before leaving.
                    // Its handled marker is already durable; skipping the submit
                    // would otherwise lose the reminder on the recovery alarm.
                    if (revision != epoch.get() || blocked(context)) return@exclusive
                    runCatching { manager.notify(TAG + event.seriesId, NOTIFICATION_ID, notification(context, data, plan, event)) }
                        .onFailure { Log.w("PlanTasks", "任务提醒暂未显示", it) }
                    if (revision != epoch.get()) { manager.cancel(TAG + event.seriesId, NOTIFICATION_ID); return@exclusive }
                    if (expired.get()) return@exclusive
                }
            }
            if (!valid()) return@exclusive
            saveDays(context, days, nextConfigurations)
            val upcoming = PlanReminderRules.candidates(data, days, now, zone, if (firing) now else minimum).firstOrNull()
            if (upcoming == null) {
                cancelAlarm(context); prefs.edit().remove("alarm_at").remove("alarm_token").commit()
            } else {
                val token = UUID.randomUUID().toString()
                check(prefs.edit().putString("alarm_token", token).putLong("alarm_at", upcoming.at).commit()) { "提醒安排未能保存" }
                if (valid()) { schedule(context, token, upcoming.at); if (revision != epoch.get()) cancelAlarm(context) }
                else if (revision == epoch.get() && !blocked(context)) armReceiverRetry(context)
                else cancelAlarm(context)
            }
        }
    }

    private fun notification(context: Context, data: AppData, plan: Plan, event: PlanReminderEvent): android.app.Notification {
        val open = PendingIntent.getActivity(context, 9321, Intent(context, MainActivity::class.java)
            .setData(Uri.parse("plan://today/${Uri.encode(event.seriesId)}"))
            .putExtra("open_today", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val subtitle = if (plan.tracking == TrackingMode.TASK) "今天还未完成，按自己的节奏开始。" else
            "已完成 ${formatQuantity(amountFor(data, plan.id, LocalDate.parse(event.date)), plan)} / ${formatQuantity(plan.target, plan)} ${plan.unit}"
        val builder = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_focus_notification)
            .setContentTitle(plan.title).setContentText(subtitle).setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setAutoCancel(true).setOnlyAlertOnce(true).setWhen(event.at)
            .setContentIntent(open).setDeleteIntent(actionIntent(context, DISMISS, event))
            .setTimeoutAfter((LocalDate.parse(event.date).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1))
            .addExtras(Bundle().apply { putString("plan_reminder_date", event.date) })
        val snoozeDate = Instant.ofEpochMilli(System.currentTimeMillis() + 15 * 60_000L).atZone(ZoneId.systemDefault()).toLocalDate()
        if (snoozeDate.toString() == event.date) builder.addAction(0, "稍后 15 分钟", actionIntent(context, SNOOZE, event))
        return builder.addAction(0, "今天不再提醒", actionIntent(context, DISMISS, event)).build()
    }

    private fun actionIntent(context: Context, action: String, event: PlanReminderEvent): PendingIntent =
        PendingIntent.getBroadcast(context, 9322, Intent(context, PlanReminderReceiver::class.java).setAction(action)
            .setData(Uri.parse("plan://task-reminder/${Uri.encode(event.seriesId)}/${event.date}/${event.at}/${Uri.encode(action)}"))
            .putExtra("planId", event.planId).putExtra("seriesId", event.seriesId).putExtra("date", event.date).putExtra("deliveryAt", event.at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun eventFrom(intent: Intent): PlanReminderEvent? = runCatching {
        val plan = requireNotNull(intent.getStringExtra("planId")); DomainValidation.id(plan)
        val series = requireNotNull(intent.getStringExtra("seriesId")); DomainValidation.id(series)
        val date = requireNotNull(intent.getStringExtra("date")); DomainValidation.date(date)
        val at = intent.getLongExtra("deliveryAt", 0); require(at > 0)
        PlanReminderEvent(plan, series, date, at)
    }.getOrNull()

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "计划提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "在任务指定时间提醒尚未完成的安排"
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true)
            })
    }

    private fun schedule(context: Context, token: String, at: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val operation = alarmIntent(context, token, at)
        val fireAt = maxOf(at, System.currentTimeMillis() + 750L)
        try {
            if (canScheduleExact(context)) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, operation)
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, operation)
        } catch (_: SecurityException) { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, operation) }
    }

    private fun alarmIntent(context: Context, token: String, at: Long): PendingIntent = PendingIntent.getBroadcast(context, ALARM_ID,
        Intent(context, PlanReminderReceiver::class.java).setAction(FIRE).setData(Uri.parse("plan://task-reminders/next"))
            .putExtra("token", token).putExtra("scheduledAt", at), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun cancelAlarm(context: Context) { runCatching { context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, "", 0)) } }
    private fun cancelVisible(context: Context) { runCatching {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag?.startsWith(TAG) == true }.forEach { manager.cancel(it.tag, it.id) }
    } }
    private fun cancelInvalidVisible(context: Context, data: AppData, days: Map<String, PlanReminderDay>, today: LocalDate) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val current = PlanReminderRules.configurationPlans(data, today).associateBy { it.seriesId }
        manager.activeNotifications.filter { it.tag?.startsWith(TAG) == true }.forEach { notice ->
            val series = notice.tag?.removePrefix(TAG) ?: return@forEach
            val day = days[PlanReminderRules.key(series, today.toString())]
            val plan = current[series]
            if (notice.notification.extras.getString("plan_reminder_date") != today.toString() || day?.dismissed == true || day?.snoozeAt != null ||
                plan == null || !PlanReminderRules.eligible(data, plan, today) ||
                day != null && !PlanReminderRules.acceptsDeliveryConfiguration(day, plan)) manager.cancel(notice.tag, notice.id)
        }
    }

    private fun blocked(context: Context) = PlanRecovery.failure != null || File(context.filesDir, "complete-restore").exists()
    private fun preferences(context: Context) = context.getSharedPreferences("plan_task_reminders", Context.MODE_PRIVATE)
    private fun readConfigurations(context: Context): Map<String, String> = runCatching {
        val json = JSONObject(preferences(context).getString("configurations", "{}").orEmpty())
        json.keys().asSequence().associateWith { json.getString(it) }
    }.getOrDefault(emptyMap())

    private fun readDays(context: Context): Map<String, PlanReminderDay> = runCatching {
        val json = JSONArray(preferences(context).getString("days", "[]").orEmpty())
        require(json.length() <= 6400)
        (0 until json.length()).map { index ->
            val row = json.getJSONObject(index)
            val series = row.getString("series").also(DomainValidation::id)
            val date = row.getString("date").also { DomainValidation.date(it) }
            PlanReminderDay(series, date, row.getBoolean("handled"), row.getBoolean("dismissed"),
                if (row.isNull("snooze")) null else row.getLong("snooze").takeIf { it > 0 },
                if (row.isNull("delivered")) null else row.getLong("delivered").takeIf { it > 0 },
                if (row.isNull("deliveryConfiguration")) null else row.getString("deliveryConfiguration"))
        }.associateBy { PlanReminderRules.key(it.seriesId, it.date) }
    }.getOrDefault(emptyMap())

    private fun saveDays(context: Context, days: Map<String, PlanReminderDay>, configurations: Map<String, String>, consumed: Map<String, *> = emptyMap<String, String>()) {
        val json = JSONArray()
        days.values.sortedBy { it.date }.takeLast(6400).forEach { state -> json.put(JSONObject()
            .put("series", state.seriesId).put("date", state.date).put("handled", state.handled).put("dismissed", state.dismissed)
            .put("snooze", state.snoozeAt ?: JSONObject.NULL).put("delivered", state.deliveredAt ?: JSONObject.NULL)
            .put("deliveryConfiguration", state.deliveryConfiguration ?: JSONObject.NULL)) }
        val config = JSONObject().apply { configurations.forEach { (key, value) -> put(key, value) } }
        val prefs = preferences(context)
        val edit = prefs.edit().putString("days", json.toString()).putString("configurations", config.toString()).remove("last_error")
        consumed.forEach { (key, value) -> if (prefs.getString(key, null) == value) edit.remove(key) }
        check(edit.commit()) { "提醒状态未能保存" }
    }
}
