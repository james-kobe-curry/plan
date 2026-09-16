package com.tongpin.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

data class BackupReminderStatus(val due: Boolean, val firstDataAt: Long?, val lastExportAt: Long?, val snoozedUntil: Long?)

/** Operational reminder dates belong to this installation, never to an imported backup. */
class BackupReminderStore internal constructor(directory: File) {
    constructor(context: Context): this(context.applicationContext.filesDir)
    private val file = File(directory, "backup-reminder.json")
    private val lock = locks.getOrPut(file.canonicalPath) { Any() }

    fun observe(data: AppData, now: Long = System.currentTimeMillis()): BackupReminderStatus = synchronized(lock) {
        val timestamp = checkedTime(now)
        var state = read()
        val meaningful = hasBackupContent(data)
        if (meaningful && state.first == null) { state = state.copy(first = timestamp); write(state) }
        status(state, timestamp, meaningful)
    }

    fun defer(days: Int = 1, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        require(days == 1 || days == 7) { "提醒延后天数无效" }
        val timestamp = checkedTime(now)
        write(read().copy(snooze = (timestamp + days * DAY).coerceAtMost(MAX_TIME)))
    }

    /** Call only after the complete ZIP output stream closed successfully. */
    fun markExported(now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val timestamp = checkedTime(now)
        val old = read()
        write(State(old.first, timestamp, null))
    }

    private fun status(state: State, now: Long, meaningful: Boolean): BackupReminderStatus {
        val base = maxOf(state.first ?: now, state.exported ?: 0L)
        val due = meaningful && state.first != null && now >= base && now - base >= 7 * DAY && now >= (state.snooze ?: 0L)
        return BackupReminderStatus(due, state.first, state.exported, state.snooze)
    }

    private fun read(): State {
        if (!file.exists()) return State()
        return try {
            val values = file.inputStream().use { DataCodec.readJsonObject(CompleteBackupArchive.readUtf8(it, 2048)) }
            require(values.keys == setOf("version", "firstDataAt", "lastExportAt", "snoozedUntil") && values["version"] == 1L) { "备份提醒信息无效" }
            fun time(key: String) = values[key]?.let { checkedTime(it as? Long ?: throw IllegalArgumentException("备份提醒日期无效")) }
            State(time("firstDataAt"), time("lastExportAt"), time("snoozedUntil"))
        } catch (failure: Exception) {
            // Reminder metadata cannot block access to real plans; retain damaged bytes once.
            val preserved = File(file.path + ".unreadable")
            if (!preserved.exists()) runCatching { file.copyTo(preserved) }
            State()
        }
    }
    private fun write(state: State) {
        val parent = requireNotNull(file.parentFile)
        require(parent.isDirectory || parent.mkdirs()) { "无法保存备份提醒设置" }
        val pending = File(file.path + ".new")
        val text = DataCodec.writeJsonObject(linkedMapOf("version" to 1, "firstDataAt" to state.first,
            "lastExportAt" to state.exported, "snoozedUntil" to state.snooze))
        try {
            FileOutputStream(pending).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
    }
    private data class State(val first: Long? = null, val exported: Long? = null, val snooze: Long? = null)
    companion object {
        internal const val DAY = 86_400_000L
        private const val MAX_TIME = 253_402_300_799_999L
        private val locks = ConcurrentHashMap<String, Any>()
        private fun checkedTime(value: Long): Long = value.also { require(it in 1..MAX_TIME) { "备份提醒日期无效" } }
    }
}
