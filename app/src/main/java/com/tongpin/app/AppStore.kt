package com.tongpin.app

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppStoreSnapshot(val revision: Long, val result: Result<AppData>, val canUndoRestore: Boolean)

/** All app instances share a path lock; update is an atomic read/modify/write transaction. */
class AppStore internal constructor(private val directory: File) {
    constructor(context: Context) : this(context.applicationContext.filesDir)

    private val file = File(directory, "tongpin-data.json")
    private val previous = File(directory, "tongpin-before-restore.json")
    private val shared = states.computeIfAbsent(file.canonicalPath) { SharedState() }
    private val lock = shared.lock
    /** All Activities and background stores for this directory observe the same committed version. */
    val revision: StateFlow<Long> get() = shared.revision

    fun load(): AppData = synchronized(lock) { loadLocked() }

    /** The result and its revision must be read together; callers can discard superseded IO results. */
    fun loadSnapshot(): AppStoreSnapshot = synchronized(lock) {
        AppStoreSnapshot(shared.revision.value, runCatching { loadLocked() }, hasPreviousBackup())
    }

    /** Full ZIP transactions replace the file directly while holding exclusive(), then signal here. */
    internal fun notifyExternalChange() = synchronized(lock) { committed() }

    private fun committed() { shared.revision.value = shared.revision.value + 1L }

    /** The complete restore shares the data lock with timer writes. */
    internal fun <T> exclusive(block: () -> T): T = synchronized(lock) { block() }

    fun save(data: AppData) = synchronized(lock) {
        writeAtomic(file, DataCodec.encode(data).toByteArray(Charsets.UTF_8))
        committed()
    }

    /** Use this for background updates so a timer cannot overwrite a simultaneous edit. */
    fun update(transform: (AppData) -> AppData): AppData = synchronized(lock) {
        val current = loadLocked()
        transform(current).also {
            if (it != current) { writeAtomic(file, DataCodec.encode(it).toByteArray(Charsets.UTF_8)); committed() }
        }
    }

    /** Preserve a validated snapshot before replacing data with an imported backup. */
    fun restore(data: AppData) = synchronized(lock) {
        val incoming = DataCodec.encode(data).toByteArray(Charsets.UTF_8)
        val current = try {
            DataCodec.encode(loadLocked()).toByteArray(Charsets.UTF_8)
        } catch (error: IOException) {
            // A valid backup can also rescue a damaged current file. Preserve the raw
            // bytes before replacing it, and retain any existing valid safety copy.
            val source = if (file.exists()) file else File(file.path + ".bak")
            if (!source.exists()) throw error
            try {
                source.copyTo(File(directory, "tongpin-unreadable-${UUID.randomUUID()}.json"), overwrite = false)
            } catch (preserveError: Exception) {
                throw IOException("恢复前无法保留原文件，当前记录未被替换", preserveError)
            }
            null
        }
        if (current != null) writeAtomic(previous, current)
        writeAtomic(file, incoming)
        committed()
    }

    /** Merge imports under the same lock as timer writes and their pre-import safety copy. */
    fun restore(transform: (AppData) -> AppData): AppData = synchronized(lock) {
        val replacement = transform(loadLocked())
        restore(replacement)
        replacement
    }

    fun hasPreviousBackup(): Boolean = synchronized(lock) {
        previous.exists() || File(previous.path + ".bak").exists()
    }

    /** Recovery is reversible: the replaced data becomes the next safety copy. */
    fun recoverPrevious(): AppData = synchronized(lock) {
        require(hasPreviousBackup()) { "暂无可恢复的安全副本" }
        val recovered = readData(previous)
        restore(recovered)
        recovered
    }

    private fun loadLocked(): AppData {
        if (!file.exists() && !File(file.path + ".bak").exists()) return AppData()
        try {
            return readData(file)
        } catch (error: Exception) {
            val backup = File(directory, "tongpin-unreadable-${System.currentTimeMillis()}.json")
            val preserved = try {
                if (file.exists()) file.copyTo(backup, overwrite = false) else null
            } catch (_: Exception) { null }
            val detail = if (preserved != null) "原文件已保留为 ${backup.name}" else "原文件未被覆盖，请先保留应用数据"
            throw IOException("无法读取本地记录：${error.message ?: "文件格式无效"}。$detail。", error)
        }
    }

    private fun readData(source: File): AppData {
        // Android AtomicFile used .bak in earlier releases. Recover it before reading.
        val interruptedBackup = File(source.path + ".bak")
        if (interruptedBackup.exists()) {
            Files.move(interruptedBackup.toPath(), source.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val bytes = source.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= DataCodec.MAX_BYTES) { "记录文件不能超过 32 MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        return DataCodec.decode(text)
    }

    /** fsync the complete temporary file before replacing the committed snapshot. */
    private fun writeAtomic(target: File, bytes: ByteArray) {
        val temporary = File(target.path + ".new")
        val interruptedBackup = File(target.path + ".bak")
        try {
            require(directory.isDirectory || directory.mkdirs()) { "无法创建保存目录" }
            // Finish rollback of an interrupted older write before touching the target.
            if (interruptedBackup.exists()) {
                Files.move(interruptedBackup.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                // The fallback retains the previous file until the replacement is committed.
                if (target.exists()) Files.move(target.toPath(), interruptedBackup.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Files.deleteIfExists(interruptedBackup.toPath())
            }
        } catch (error: Exception) {
            try {
                Files.deleteIfExists(temporary.toPath())
                if (interruptedBackup.exists()) Files.move(interruptedBackup.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (recoveryError: Exception) { error.addSuppressed(recoveryError) }
            throw IOException("保存失败，之前的记录仍已保留：${error.message ?: "存储不可用"}", error)
        }
    }

    private class SharedState { val lock = Any(); val revision = MutableStateFlow(0L) }
    private companion object { val states = ConcurrentHashMap<String, SharedState>() }
}
