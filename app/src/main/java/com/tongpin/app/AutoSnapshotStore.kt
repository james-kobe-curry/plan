package com.tongpin.app

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.util.UUID

fun hasBackupContent(data: AppData): Boolean = data.plans.isNotEmpty() || data.checkIns.isNotEmpty() ||
    data.focusRecords.isNotEmpty() || data.dailyNotes.isNotEmpty() || data.categories.isNotEmpty() || data.profile != PersonalProfile()

/** Separate seven-day safety history; all operations share the current-data transaction lock. */
class AutoSnapshotStore internal constructor(private val files: File) {
    constructor(context: Context): this(context.applicationContext.filesDir)
    private val dataStore = AppStore(files)
    private val directory = File(files, DIRECTORY)
    private val snapshots = SnapshotStore(directory)

    /** Reads current data under the same lock used by full restore and background timer writes. */
    fun checkpoint(day: LocalDate = LocalDate.now(), now: Long = System.currentTimeMillis()): SnapshotInfo? =
        dataStore.exclusive { checkpointLocked(dataStore.load(), day, now) }

    /** The supplied state is only a fast empty-data hint; authoritative data is read under lock. */
    fun checkpoint(data: AppData, day: LocalDate = LocalDate.now(), now: Long = System.currentTimeMillis()): SnapshotInfo? =
        if (!hasBackupContent(data)) null else checkpoint(day, now)

    private fun checkpointLocked(data: AppData, day: LocalDate, now: Long): SnapshotInfo? {
        if (!hasBackupContent(data)) return null
        val date = checkedDay(day)
        val existing = snapshots.list()
        val id = identity(date)
        if (existing.any { it.id == id }) { prune(); return null }
        val retained = existing.mapNotNull(::dateOf).sortedDescending().take(MAX_SNAPSHOTS)
        if (retained.size == MAX_SNAPSHOTS && date < retained.last()) return null
        val made = snapshots.createIfAbsent(id, name(date), data, now)
        prune()
        return made
    }

    fun list(): List<SnapshotInfo> = dataStore.exclusive {
        prune()
        snapshots.list().sortedWith(compareByDescending<SnapshotInfo> { dateOf(it) ?: LocalDate.MIN }.thenByDescending { it.createdAt })
    }
    fun read(id: String): AppData = dataStore.exclusive { snapshots.read(id) }
    fun delete(id: String) = dataStore.exclusive { snapshots.delete(id) }

    /** Called only while AppStore.exclusive is held; preserves the local pre-restore daily copy. */
    internal fun stageForRestore(incoming: File?, destination: File, day: LocalDate = LocalDate.now()) = dataStore.exclusive {
        val today = identity(checkedDay(day))
        destination.mkdirs()
        val source = incoming ?: directory
        if (source.isDirectory) CompleteBackupTransaction.copyTree(source, destination)
        val current = snapshots.list().firstOrNull { it.id == today }
        if (current != null) {
            require(!current.corrupt) { "今天的自动存档暂时无法读取，请先处理这份存档再恢复" }
            snapshots.read(today)
            File(directory, "$today.snapshot").copyTo(File(destination, "$today.snapshot"), overwrite = true)
        }
        trimDirectory(destination, current?.id)
    }

    private fun prune() { trimDirectory(directory) }

    companion object {
        const val MAX_SNAPSHOTS = 7
        const val DIRECTORY = "auto_snapshots"
        internal fun identity(day: LocalDate): String = UUID.nameUUIDFromBytes("plan-auto-${checkedDay(day)}".toByteArray(Charsets.UTF_8)).toString()
        internal fun name(day: LocalDate): String = "${checkedDay(day)} 自动存档"
        internal fun dateOf(info: SnapshotInfo): LocalDate? = runCatching {
            require(info.name.endsWith(" 自动存档"))
            val day = checkedDay(LocalDate.parse(info.name.removeSuffix(" 自动存档")))
            require(info.id == identity(day)); day
        }.getOrNull()
        internal fun validateDirectory(directory: File): List<SnapshotInfo> {
            val list = SnapshotStore(directory).list()
            require(list.size <= MAX_SNAPSHOTS && list.none { it.corrupt || dateOf(it) == null }) { "备份包含无效的自动存档" }
            return list
        }
        internal fun trimDirectory(directory: File, preserveId: String? = null) {
            val store = SnapshotStore(directory)
            val ordered = store.list().sortedWith(compareByDescending<SnapshotInfo> { dateOf(it) ?: LocalDate.MIN }.thenByDescending { it.createdAt })
            val protected = ordered.firstOrNull { it.id == preserveId }
            val keep = (listOfNotNull(protected) + ordered.filterNot { it.id == protected?.id }).take(MAX_SNAPSHOTS).map { it.id }.toSet()
            ordered.filterNot { it.id in keep }.forEach { store.delete(it.id) }
        }
        private fun checkedDay(day: LocalDate): LocalDate = day.also { require(it.year in 1..9999) { "自动存档日期无效" } }
    }
}
