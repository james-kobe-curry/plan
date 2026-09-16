package com.tongpin.app

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class PreparedCompleteBackup internal constructor(internal val content: CompleteBackupContent,
    internal val archive: File? = null) {
    val summary: CompleteBackupSummary get() = content.summary
    private val lifetime = Any()
    private var readers = 0
    private var closed = false
    fun close() {
        val clean = synchronized(lifetime) { closed = true; readers == 0 }
        if (clean) content.directory.parentFile?.deleteRecursively()
    }
    internal fun <T> access(block: () -> T): T {
        synchronized(lifetime) { check(!closed) { "备份预览已关闭，请重新选择文件" }; readers++ }
        try { return block() } finally {
            val clean = synchronized(lifetime) { readers--; closed && readers == 0 }
            if (clean) content.directory.parentFile?.deleteRecursively()
        }
    }
}

class CompleteBackupRecoveryRequired(message: String, cause: Throwable): IOException(message, cause)

/** SAF streams only; private recovery and audio directories are never exposed as a share root. */
class CompleteBackupStore(context: Context) {
    private val app = context.applicationContext
    private val files = app.filesDir
    private val prefs = app.getSharedPreferences("focus_reminders", Context.MODE_PRIVATE)
    private val uiPrefs = app.getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
    private val sounds = ReminderSoundLibrary(app)
    private val data = AppStore(app)

    fun recoverInterrupted() = synchronized(lock) {
        data.exclusive {
            val pending = File(files, "complete-restore").exists()
            CompleteBackupTransaction(files).recover(::writePreferences)
            if (pending) data.notifyExternalChange()
        }
    }

    fun prepareExport(): PreparedCompleteBackup = synchronized(lock) {
        recoverInterrupted()
        val work = workspace()
        val source = File(work, "content").apply { check(mkdirs()) }
        try {
            data.exclusive {
                File(source, "current.json").writeText(DataCodec.encode(data.load()), Charsets.UTF_8)
                val snapshotStore = SnapshotStore(app)
                val snapshotList = snapshotStore.list()
                require(snapshotList.none { it.corrupt }) { "有存档暂时无法读取，请先在“我的存档”中处理后再打包" }
                snapshotList.forEach { info ->
                    snapshotStore.read(info.id)
                    copy(File(files, "snapshots/${info.id}.snapshot"), File(source, "snapshots/${info.id}.snapshot"))
                }
                val autoStore = AutoSnapshotStore(app)
                autoStore.list().forEach { info ->
                    require(!info.corrupt) { "有自动存档暂时无法读取，请先在“自动存档”中处理后再打包" }
                    autoStore.read(info.id)
                    copy(File(files, "auto_snapshots/${info.id}.snapshot"), File(source, "auto_snapshots/${info.id}.snapshot"))
                }
                val custom = sounds.list().filter { it.custom }
                val storedIds = File(files, "reminder_sounds").listFiles().orEmpty().mapNotNull { file ->
                    file.name.removeSuffix(".bak").takeIf { it.endsWith(".json") }?.removeSuffix(".json")?.takeIf(SoundRules::isCustomId)
                }.toSet()
                require(storedIds == custom.map { it.id }.toSet()) { "有自定义提示音文件不完整，请重新导入该音频后再打包" }
                custom.forEach { sound ->
                    val metadata = File(files, "reminder_sounds/${sound.id}.json")
                    val text = AtomicFile(metadata).openRead().use { CompleteBackupArchive.readUtf8(it, 4096) }
                    val details = DataCodec.readJsonObject(text)
                    val extension = details["extension"] as? String ?: throw IllegalArgumentException("提示音格式无效")
                    require(SoundRules.isStorageExtension(extension)) { "提示音格式无效" }
                    copy(File(files, "reminder_sounds/${sound.id}.$extension"), File(source, "sounds/${sound.id}.$extension"))
                    File(source, "sounds/${sound.id}.json").writeText(text, Charsets.UTF_8)
                }
                val preferences = readPreferences().toMutableMap()
                if (preferences["sound_id"] !in (setOf("chime", "wood", "double", "soft") + custom.map { it.id })) preferences["sound_id"] = "chime"
                File(source, "settings.json").writeText(DataCodec.writeJsonObject(preferences), Charsets.UTF_8)
            }
            val content = CompleteBackupArchive.validate(source, System.currentTimeMillis(), sounds::validateStoredAudio)
            val archive = File(work, "complete.plan.zip")
            FileOutputStream(archive).use { CompleteBackupArchive.write(source, it, content.summary.createdAt) }
            PreparedCompleteBackup(content, archive)
        } catch (error: Exception) { work.deleteRecursively(); throw error }
    }

    fun export(prepared: PreparedCompleteBackup, destination: Uri): Boolean = prepared.access {
        val archive = requireNotNull(prepared.archive) { "请重新准备备份" }
        app.contentResolver.openOutputStream(destination, "wt")?.use { output ->
            archive.inputStream().use { CompleteBackupArchive.copyBounded(it, output, CompleteBackupArchive.MAX_TOTAL_BYTES + 1024 * 1024) }
            output.flush()
        } ?: throw IllegalArgumentException("无法写入所选位置，请换一个文件夹")
        // A reminder-state write failure must not misreport a successfully saved ZIP as lost.
        runCatching { BackupReminderStore(app).markExported() }.isSuccess
    }

    fun prepareImport(source: Uri): PreparedCompleteBackup {
        val work = workspace()
        try {
            val stream = app.contentResolver.openInputStream(source) ?: throw IllegalArgumentException("无法打开这个文件")
            val content = stream.use { CompleteBackupArchive.read(it, File(work, "content"), sounds::validateStoredAudio) }
            return PreparedCompleteBackup(content)
        } catch (error: Exception) { work.deleteRecursively(); throw error }
    }

    fun restore(prepared: PreparedCompleteBackup): AppData = prepared.access { synchronized(lock) {
        data.exclusive {
            recoverInterrupted()
            // Recheck the private staging data before committing, even after preview.
            val content = CompleteBackupArchive.validate(prepared.content.directory, prepared.summary.createdAt, sounds::validateStoredAudio, prepared.content.formatVersion)
            val autoStore = AutoSnapshotStore(app)
            // The pre-restore daily safety copy is never overwritten by restored data.
            val readableCurrent = try { data.load() } catch (_: IOException) { null }
            if (readableCurrent != null) autoStore.checkpoint(readableCurrent)
            val restoreAuto = File(prepared.content.directory.parentFile, "restore-auto-${UUID.randomUUID()}")
            try {
                autoStore.stageForRestore(if (content.formatVersion >= 2) File(content.directory, AutoSnapshotStore.DIRECTORY) else null,
                    restoreAuto)
                CompleteBackupTransaction(files).restore(content.directory, readPreferences(), content.preferences, ::writePreferences,
                    autoSnapshotSource = restoreAuto)
            } catch (failure: Exception) {
                if (File(files, "complete-restore/journal.json").exists() && !File(files, "complete-restore/committed").exists()) {
                    throw CompleteBackupRecoveryRequired("恢复尚未完成，安全副本已保留。请重试数据恢复后再使用应用。", failure)
                }
                throw failure
            } finally { restoreAuto.deleteRecursively() }
            data.load().also { data.notifyExternalChange() }
        }
    } }

    private fun readPreferences(): Map<String, Any?> {
        val focus = FocusPreferencesStore(app).load()
        val home = HomePreferencesStore(app).load()
        val choice = FocusNotifications(app).settings()
        return linkedMapOf(
        "enabled" to choice.enabled, "sound" to choice.sound, "vibrate" to choice.vibrate,
        "sound_id" to choice.soundId, "compatible_sound" to choice.compatibilitySound,
        "theme_mode" to AppearanceStore(app).mode().name,
        "theme_color" to AppearanceStore(app).color().name,
        "theme_style" to AppearanceStore(app).style().name,
        "focus_favorites" to encodeFocusFavorites(focus.favorites),
        "focus_last_minutes" to focus.lastMinutes.toLong(),
        "home_order" to home.order.joinToString(",") { it.name },
        "home_show_progress" to home.showProgress, "home_show_recent" to home.showRecent,
        "home_density" to home.density.name, "start_page" to home.startPage.toLong(),
    ) }

    private fun writePreferences(values: Map<String, Any?>) {
        CompleteBackupArchive.validatePreferences(values)
        val editor = prefs.edit()
        CompleteBackupArchive.preferenceKeys.filterNot { it in CompleteBackupArchive.uiPreferenceKeys }.forEach { editor.remove(it) }
        values.filterKeys { it !in CompleteBackupArchive.uiPreferenceKeys }.forEach { (key, value) -> when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
        } }
        check(editor.commit()) { "提醒偏好未能保存" }
        // Appearance and focus choices move together in one atomic preference file write.
        // CompleteBackupTransaction rolls both preference files back on failure or restart.
        check(uiPrefs.edit()
            .putString("theme_mode", values["theme_mode"] as? String ?: "SYSTEM")
            .putString("theme_color", values["theme_color"] as? String ?: "GREEN")
            .putString("theme_style", values["theme_style"] as? String ?: "SOFT")
            .putString("focus_favorites", values["focus_favorites"] as? String ?: "15,25,45,60")
            .putInt("focus_last_minutes", (values["focus_last_minutes"] as? Number)?.toInt() ?: 25)
            .putString("home_order", values["home_order"] as? String ?: "PROGRESS,TASKS,RECENT")
            .putBoolean("home_show_progress", values["home_show_progress"] as? Boolean ?: true)
            .putBoolean("home_show_recent", values["home_show_recent"] as? Boolean ?: false)
            .putString("home_density", values["home_density"] as? String ?: "COMFORTABLE")
            .putInt("start_page", (values["start_page"] as? Number)?.toInt() ?: 0)
            .commit()) { "外观、首页与专注设置未能保存" }
    }

    private fun workspace(): File {
        val root = File(app.cacheDir, "complete-backups")
        require(root.isDirectory || root.mkdirs()) { "无法准备备份，请检查可用存储空间" }
        // Abandoned picker/validation sessions are temporary and never contain the only original.
        root.listFiles().orEmpty().filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }.forEach { it.deleteRecursively() }
        return File(root, UUID.randomUUID().toString()).apply { check(mkdirs()) { "无法准备备份" } }
    }

    private fun copy(source: File, target: File) {
        require(!java.nio.file.Files.isSymbolicLink(source.toPath())) { "备份来源路径无效" }
        require(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) { "无法整理备份内容" }
        require(source.isFile && source.length() <= DataCodec.MAX_BYTES + 512L) { "备份来源文件不可用" }
        source.copyTo(target)
    }

    companion object { private val lock = Any() }
}
