package com.tongpin.app

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** A durable rollback journal covers app data, every snapshot, all sounds and app preferences. */
internal class CompleteBackupTransaction(private val directory: File) {
    private val transaction = File(directory, "complete-restore")

    fun restore(source: File, oldPreferences: Map<String, Any?>, newPreferences: Map<String, Any?>,
        writePreferences: (Map<String, Any?>) -> Unit, afterMove: (String) -> Unit = {}, autoSnapshotSource: File? = null) {
        CompleteBackupArchive.validatePreferences(oldPreferences)
        CompleteBackupArchive.validatePreferences(newPreferences)
        recover(writePreferences)
        require(!transaction.exists() && transaction.mkdirs()) { "无法创建恢复安全副本" }
        try {
            val stage = File(transaction, "new").apply { mkdirs() }
            File(source, "current.json").copyTo(File(stage, "tongpin-data.json"))
            listOf("snapshots" to "snapshots", "sounds" to "reminder_sounds", "auto_snapshots" to "auto_snapshots").forEach { (from, to) ->
                val original = if (from == "auto_snapshots" && autoSnapshotSource != null) autoSnapshotSource else File(source, from)
                val target = File(stage, to)
                if (original.isDirectory) copyTree(original, target) else check(target.mkdirs())
            }
            // Files copied above must reach storage before a durable journal allows replacement.
            stage.walkTopDown().filter { it.isFile }.forEach { file -> FileOutputStream(file, true).use { it.fd.sync() } }
            val original = targets.filter { File(directory, it).exists() }
            val journal = linkedMapOf("version" to 1, "original" to original, "preferences" to oldPreferences)
            syncText(File(transaction, "journal.json"), DataCodec.writeJsonObject(journal))
            val old = File(transaction, "old").apply { check(mkdirs()) }
            targets.forEach { name ->
                val current = File(directory, name)
                if (current.exists()) Files.move(current.toPath(), File(old, name).toPath())
                afterMove("old:$name")
                val replacement = File(stage, name)
                if (replacement.exists()) Files.move(replacement.toPath(), current.toPath())
                afterMove("new:$name")
            }
            writePreferences(newPreferences)
            afterMove("preferences")
            syncText(File(transaction, "committed"), "PLAN-COMMITTED-1")
        } catch (failure: Exception) {
            try { recover(writePreferences) } catch (recovery: Exception) { failure.addSuppressed(recovery)
                throw IOException("恢复未完成，安全副本已保留。请重新打开应用以恢复原数据。", failure) }
            throw IOException("恢复失败，原有计划、存档、提示音与设置已还原。${failure.message.orEmpty()}", failure)
        }
        // A failed cleanup after the commit must never be reported as a failed restore.
        runCatching { retireTransaction() }
    }

    fun recover(writePreferences: (Map<String, Any?>) -> Unit) {
        // Retired journals are never replayed. A crash during their deletion is harmless.
        directory.listFiles().orEmpty().filter { it.name.startsWith("complete-restore-cleanup-") }.forEach {
            runCatching { removeTree(it) }
        }
        if (!transaction.exists()) return
        require(!Files.isSymbolicLink(transaction.toPath()) && transaction.isDirectory) { "恢复安全副本路径无效" }
        val committed = File(transaction, "committed")
        if (committed.isFile && committed.readText() == "PLAN-COMMITTED-1") { retireTransaction(); return }
        val journal = File(transaction, "journal.json")
        if (!journal.exists()) { retireTransaction(); return }
        val content = journal.inputStream().use { DataCodec.readJsonObject(CompleteBackupArchive.readUtf8(it, 8192)) }
        require(content.keys == setOf("version", "original", "preferences") && content["version"] == 1L) { "恢复安全副本信息无效" }
        val originals = (content["original"] as? List<*>)?.map {
            (it as? String)?.takeIf(targets::contains) ?: throw IllegalArgumentException("恢复文件路径无效")
        } ?: throw IllegalArgumentException("恢复文件清单无效")
        require(originals.size == originals.toSet().size) { "恢复文件清单无效" }
        @Suppress("UNCHECKED_CAST")
        val preferences = CompleteBackupArchive.validatePreferences(content["preferences"] as? Map<String, Any?>
            ?: throw IllegalArgumentException("恢复偏好无效"))
        val old = File(transaction, "old")
        targets.forEach { name ->
            val saved = File(old, name); val current = File(directory, name)
            if (saved.exists()) {
                removeTree(current)
                // Copy, rather than consume, the safety copy: another crash during rollback
                // leaves all originals available for the next recovery attempt.
                if (saved.isDirectory) copyTree(saved, current) else saved.copyTo(current)
                if (current.isDirectory) current.walkTopDown().filter { it.isFile }.forEach(::syncFile)
                else syncFile(current)
            } else if (name !in originals) removeTree(current)
        }
        writePreferences(preferences)
        // Mark rollback itself as finished before retiring its safety copy. Deleting the
        // directory in-place could leave a partial old/ that a future launch replays.
        syncText(File(transaction, "committed"), "PLAN-COMMITTED-1")
        retireTransaction()
    }

    private fun retireTransaction() {
        val retired = File(directory, "complete-restore-cleanup-${UUID.randomUUID()}")
        Files.move(transaction.toPath(), retired.toPath())
        runCatching { removeTree(retired) }
    }

    companion object {
        private val targets = listOf("tongpin-data.json", "tongpin-data.json.bak",
            "tongpin-before-restore.json", "tongpin-before-restore.json.bak", "snapshots", "reminder_sounds", "auto_snapshots")
        internal fun copyTree(source: File, target: File) {
            require(!Files.isSymbolicLink(source.toPath())) { "备份目录路径无效" }
            require(target.isDirectory || target.mkdirs()) { "无法复制恢复文件" }
            source.listFiles().orEmpty().forEach { file ->
                require(!Files.isSymbolicLink(file.toPath())) { "备份文件路径无效" }
                val next = File(target, file.name)
                if (file.isDirectory) copyTree(file, next) else file.copyTo(next)
            }
        }
        private fun syncText(file: File, text: String) {
            val pending = File(file.path + ".new")
            FileOutputStream(pending).use { output -> output.write(text.toByteArray(Charsets.UTF_8)); output.fd.sync() }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        private fun syncFile(file: File) { FileOutputStream(file, true).use { it.fd.sync() } }
        private fun removeTree(file: File) {
            if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return
            if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) file.listFiles().orEmpty().forEach(::removeTree)
            check(file.delete()) { "无法整理恢复安全副本" }
        }
    }
}
