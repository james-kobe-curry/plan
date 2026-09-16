package com.tongpin.app

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Only the most recent delivery; never records task titles, notes, filenames or audio contents. */
internal class ReminderDiagnostics(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("reminder_diagnostics", Context.MODE_PRIVATE)
    fun begin(token: String, test: Boolean) = synchronized(lock) {
        prefs.edit().putString("token", token).putString("type", if (test) "测试提醒" else "专注结束")
            .putLong("at", System.currentTimeMillis()).putString("steps", "").apply()
    }
    fun add(token: String, step: String) = synchronized(lock) {
        if (prefs.getString("token", "") != token) return@synchronized
        val stamp = time(System.currentTimeMillis(), "HH:mm:ss")
        val lines = (prefs.getString("steps", "").orEmpty().lines().filter { it.isNotBlank() } + "$stamp $step").takeLast(12)
        prefs.edit().putString("steps", lines.joinToString("\n")).apply()
    }
    fun latest(): String = synchronized(lock) {
        val at = prefs.getLong("at", 0)
        if (at == 0L) "还没有测试或专注结束的提醒记录。" else
            "最近触发：${prefs.getString("type", "提醒")} · ${time(at, "yyyy-MM-dd HH:mm:ss")}\n${prefs.getString("steps", "")}" 
    }
    private fun time(at: Long, pattern: String) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern))
    companion object { private val lock = Any() }
}
