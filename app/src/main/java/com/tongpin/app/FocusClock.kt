package com.tongpin.app

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ClockState(
    val id: String = "", val planId: String? = null, val minutes: Int = 25,
    val remaining: Int = 1500, val deadline: Long = 0, val running: Boolean = false,
    val completed: Boolean = false, val elapsedDeadline: Long = 0, val bootCount: Int = -1,
)

data class FocusCompletion(val id: String, val planId: String?, val seconds: Int, val completedAt: Long)

/** Activity, service and receivers share one process and serialize timer/queue edits. */
class FocusClock(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("focus_clock", Context.MODE_PRIVATE)

    fun load(): ClockState = synchronized(lock) { readReconciled() }

    /** Saves configuration or an explicit reset. Change running timers with start/pause. */
    fun save(state: ClockState, clearPending: Boolean = false) {
        synchronized(lock) {
            commitPreferences { editor ->
                writeState(editor, state).also { if (clearPending) it.remove("pending_completions") }
            }
        }
        FocusRuntime.sync(app, state)
    }

    fun start(state: ClockState): ClockState {
        val next = synchronized(lock) {
            val current = readReconciled()
            val base = if (state.id.isNotEmpty() && current.id == state.id) current else state
            if (base.running) return@synchronized base
            val seconds = if (base.completed || base.remaining <= 0) base.minutes * 60 else base.remaining
            base.copy(
                id = if (base.id.isEmpty() || base.completed) UUID.randomUUID().toString() else base.id,
                remaining = seconds, deadline = System.currentTimeMillis() + seconds * 1000L,
                elapsedDeadline = SystemClock.elapsedRealtime() + seconds * 1000L,
                bootCount = currentBootCount(), running = true, completed = false,
            ).also { state -> commitPreferences { writeState(it, state) } }
        }
        FocusNotifications(app).clearCompletion()
        FocusRuntime.sync(app, next)
        return next
    }

    fun pause(state: ClockState): ClockState {
        // A notification action can race with reaching zero; complete before trying to pause.
        val current = tick()
        if (state.id != current.id || !current.running) return current
        val next = synchronized(lock) {
            val latest = readReconciled()
            val seconds = remaining(latest)
            if (latest.id != current.id || !latest.running || seconds == 0) latest else latest.copy(
                remaining = seconds, running = false, elapsedDeadline = 0,
            ).also { state -> commitPreferences { writeState(it, state) } }
        }
        if (next.running && remaining(next) == 0) return tick()
        FocusRuntime.sync(app, next)
        return next
    }

    fun remaining(state: ClockState): Int = FocusClockRules.remaining(
        running = state.running, storedRemaining = state.remaining, maximum = state.minutes * 60,
        wallDeadline = state.deadline, elapsedDeadline = state.elapsedDeadline,
        savedBoot = state.bootCount, currentBoot = currentBootCount(),
        nowWall = System.currentTimeMillis(), nowElapsed = SystemClock.elapsedRealtime(),
    )

    /** Reads fresh state and atomically commits a completion event exactly once. */
    fun tick(): ClockState {
        var completion: FocusCompletion? = null
        val next = synchronized(lock) {
            val current = readReconciled()
            if (!current.running || remaining(current) > 0) return@synchronized current
            val completedAt = FocusClockRules.completedAt(
                current.elapsedDeadline, SystemClock.elapsedRealtime(), System.currentTimeMillis(),
            )
            val event = FocusCompletion(current.id, current.planId, current.minutes * 60, completedAt)
            val pending = readCompletions().toMutableList()
            if (pending.none { it.id == event.id }) pending.add(event)
            current.copy(remaining = 0, running = false, completed = true, deadline = completedAt).also {
                val finished = it
                commitPreferences { editor ->
                    writeState(editor, finished).putString("pending_completions", encodeCompletions(pending))
                }
                completion = event
            }
        }
        completion?.let {
            FocusRuntime.sync(app, next)
            FocusNotifications(app).showCompletion(it)
        }
        return next
    }

    /** Call once on entry/resume, or after a device restart, to restore background work. */
    fun restore() { FocusRuntime.sync(app, tick()) }

    fun pendingCompletions(): List<FocusCompletion> = synchronized(lock) { readCompletions() }

    /** Only acknowledge after the FocusRecord is durably saved (or deduplicated by id). */
    fun acknowledgeCompletion(id: String) {
        synchronized(lock) {
            commitPreferences { it.putString("pending_completions", encodeCompletions(readCompletions().filterNot { event -> event.id == id })) }
        }
    }

    fun clearPendingCompletions() {
        synchronized(lock) { commitPreferences { it.remove("pending_completions") } }
    }

    private fun currentBootCount(): Int = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun readReconciled(): ClockState {
        val current = ClockState(
            id = prefs.getString("id", "") ?: "", planId = prefs.getString("plan", null),
            minutes = prefs.getInt("minutes", 25).coerceIn(1, 1440),
            remaining = prefs.getInt("remaining", 1500), deadline = prefs.getLong("deadline", 0),
            running = prefs.getBoolean("running", false), completed = prefs.getBoolean("completed", false),
            elapsedDeadline = prefs.getLong("elapsed_deadline", 0), bootCount = prefs.getInt("boot_count", -1),
        )
        val boot = currentBootCount()
        if (!current.running || (current.elapsedDeadline > 0 && current.bootCount == boot)) {
            reconcileFallback = null
            return current
        }
        // elapsedRealtime resets on reboot. Only this migration/reboot path uses civil time.
        val nowElapsed = SystemClock.elapsedRealtime()
        val cached = reconcileFallback?.takeIf { it.original == current && it.reconciled.bootCount == boot }
        val remainingMillis = (current.deadline - System.currentTimeMillis()).coerceAtMost(current.minutes * 60_000L)
        val reconciled = cached?.reconciled ?: current.copy(elapsedDeadline = nowElapsed + remainingMillis, bootCount = boot)
        if (cached != null && nowElapsed < cached.retryAt) return reconciled
        try {
            commitPreferences { writeState(it, reconciled) }
            reconcileFallback = null
        } catch (failure: FocusPersistenceException) {
            // This is a derived clock anchor, not a user edit. Keep counting with the
            // same monotonic anchor and retry its cache write at most every 30 seconds.
            reconcileFallback = ReconcileFallback(current, reconciled, nowElapsed + 30_000L)
            Log.w("PlanFocus", "计时锚点暂未保存，继续使用当前计时", failure)
        }
        return reconciled
    }

    /** Call under lock; a false commit may already have changed Android's memory map. */
    private fun commitPreferences(change: (SharedPreferences.Editor) -> SharedPreferences.Editor) {
        val previous = prefs.all.toMap()
        persistFocusChange(previous, commit = { change(prefs.edit()).commit() }, restore = { values ->
            val editor = prefs.edit().clear()
            values.forEach { (key, value) ->
                when (value) {
                    null -> editor.remove(key)
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> error("专注设置包含无法还原的类型")
                }
            }
            editor.commit()
        })
    }

    private fun writeState(editor: SharedPreferences.Editor, state: ClockState): SharedPreferences.Editor = editor
        .putString("id", state.id).putString("plan", state.planId).putInt("minutes", state.minutes)
        .putInt("remaining", state.remaining).putLong("deadline", state.deadline)
        .putBoolean("running", state.running).putBoolean("completed", state.completed)
        .putLong("elapsed_deadline", state.elapsedDeadline).putInt("boot_count", state.bootCount)

    private fun readCompletions(): List<FocusCompletion> {
        val json = JSONArray(prefs.getString("pending_completions", "[]") ?: "[]")
        return List(json.length()) { index ->
            val item = json.getJSONObject(index)
            FocusCompletion(item.getString("id"), if (item.isNull("planId")) null else item.getString("planId"), item.getInt("seconds"), item.getLong("completedAt"))
        }
    }

    private fun encodeCompletions(items: List<FocusCompletion>): String = JSONArray().apply {
        items.forEach { item -> put(JSONObject().put("id", item.id).put("planId", item.planId ?: JSONObject.NULL)
            .put("seconds", item.seconds).put("completedAt", item.completedAt)) }
    }.toString()

    private data class ReconcileFallback(val original: ClockState, val reconciled: ClockState, val retryAt: Long)
    companion object {
        private val lock = Any()
        private var reconcileFallback: ReconcileFallback? = null
    }
}
