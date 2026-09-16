package com.tongpin.app

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class SnapshotInfo(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val plans: Int,
    val archived: Int,
    val checkIns: Int,
    val focusRecords: Int,
    val corrupt: Boolean = false,
    val error: String? = null,
    val recordsOnly: Int = 0,
    /** Derived from the checksummed payload; the original binary header remains compatible. */
    val dailyNotes: Int = 0,
)

/** Named copies are separate from AppStore; metadata and complete data commit as one file. */
class SnapshotStore internal constructor(directory: File) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "snapshots"))

    private val root = directory.canonicalFile
    private val lock = locks.getOrPut(root.path) { Any() }

    /** A damaged file remains visible and deletable without preventing other copies from loading. */
    fun list(): List<SnapshotInfo> = synchronized(lock) {
        snapshotIds().map { id ->
            var known: SnapshotInfo? = null
            try {
                readStored(id) { known = it }.info
            } catch (error: Exception) {
                val modified = runCatching {
                    Files.getLastModifiedTime(File(root, "$id$EXTENSION").toPath(), LinkOption.NOFOLLOW_LINKS).toMillis()
                }.getOrDefault(0L).coerceIn(0L, MAX_TIMESTAMP)
                (known ?: SnapshotInfo(id, "无法读取的存档", modified, modified, 0, 0, 0, 0)).copy(
                    corrupt = true, error = error.message ?: "存档文件损坏",
                )
            }
        }.sortedWith(compareByDescending<SnapshotInfo> { it.updatedAt }.thenByDescending { it.createdAt }.thenBy { it.id })
    }

    fun create(name: String, data: AppData): SnapshotInfo = synchronized(lock) {
        val cleanName = checkedName(name)
        val existing = snapshotIds().toSet()
        require(existing.size < MAX_SNAPSHOTS) { "最多保存 $MAX_SNAPSHOTS 份存档，请先删除不再需要的存档" }
        val payload = DataCodec.encode(data).toByteArray(Charsets.UTF_8)
        val timestamp = System.currentTimeMillis().coerceIn(1L, MAX_TIMESTAMP)
        var id: String
        do { id = UUID.randomUUID().toString() } while (id in existing)
        val info = infoFor(id, cleanName, timestamp, timestamp, data)
        writeAtomic(fileFor(id), info, payload)
        info
    }

    /** Daily checkpoints have a date-derived identity and must never overwrite that day. */
    internal fun createIfAbsent(id: String, name: String, data: AppData, timestamp: Long): SnapshotInfo? = synchronized(lock) {
        fileFor(id)
        if (id in snapshotIds()) return@synchronized null
        require(snapshotIds().size < MAX_SNAPSHOTS) { "存档数量已达上限" }
        val cleanName = checkedName(name)
        require(timestamp in 1L..MAX_TIMESTAMP) { "存档日期无效" }
        val payload = DataCodec.encode(data).toByteArray(Charsets.UTF_8)
        infoFor(id, cleanName, timestamp, timestamp, data).also { writeAtomic(fileFor(id), it, payload) }
    }

    fun rename(id: String, name: String): SnapshotInfo = synchronized(lock) {
        val cleanName = checkedName(name)
        val current = readStored(id)
        val info = current.info.copy(name = cleanName, updatedAt = nextTimestamp(current.info))
        writeAtomic(fileFor(id), info, current.payload)
        info
    }

    fun overwrite(id: String, data: AppData): SnapshotInfo = synchronized(lock) {
        // Read first: an unreadable copy must not be silently replaced by another data set.
        val current = readStored(id)
        val payload = DataCodec.encode(data).toByteArray(Charsets.UTF_8)
        val info = infoFor(id, current.info.name, current.info.createdAt, nextTimestamp(current.info), data)
        writeAtomic(fileFor(id), info, payload)
        info
    }

    fun read(id: String): AppData = synchronized(lock) { readStored(id).data }

    fun delete(id: String) = synchronized(lock) {
        val target = fileFor(id)
        val backup = File(target.path + ".bak")
        // Restoring an interrupted replacement before deletion prevents the backup resurrecting it.
        recoverInterrupted(target)
        if (!Files.deleteIfExists(target.toPath())) throw IOException("找不到这份存档")
        if (backup.exists()) throw IOException("删除未完成，请重试")
        Unit
    }

    private fun readStored(id: String, onHeader: (SnapshotInfo) -> Unit = {}): StoredSnapshot {
        val target = fileFor(id)
        recoverInterrupted(target)
        try {
            require(target.isFile) { "找不到这份存档" }
            require(target.length() <= DataCodec.MAX_BYTES.toLong() + MAX_HEADER_BYTES) { "存档文件超过支持的大小" }
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(target.inputStream().buffered(), digest).use { hashed ->
                val input = DataInputStream(hashed)
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                require(magic.contentEquals(MAGIC)) { "存档文件格式无效" }
                val version = input.readInt()
                require(version in 1..VERSION) { "不支持此存档版本" }
                val storedId = readText(input, 36)
                require(storedId == id) { "存档标识不匹配" }
                val name = readText(input, MAX_NAME_BYTES)
                require(checkedName(name) == name) { "存档名称格式无效" }
                val info = SnapshotInfo(id, name, input.readLong(), input.readLong(),
                    input.readInt(), input.readInt(), input.readInt(), input.readInt(),
                    recordsOnly = if (version >= 2) input.readInt() else 0)
                require(info.createdAt in 1L..MAX_TIMESTAMP && info.updatedAt in info.createdAt..MAX_TIMESTAMP) { "存档日期无效" }
                // The original envelope counted record-only metadata as active/archived plans.
                val activeLimit = if (version == 1) DomainValidation.MAX_PLAN_VERSIONS else DomainValidation.MAX_PLANS
                require(info.plans in 0..activeLimit && info.archived in 0..DomainValidation.MAX_PLAN_VERSIONS &&
                    info.recordsOnly in 0..DomainValidation.MAX_PLAN_VERSIONS &&
                    info.plans.toLong() + info.archived + info.recordsOnly <= DomainValidation.MAX_PLAN_VERSIONS &&
                    info.checkIns in 0..DomainValidation.MAX_CHECK_INS && info.focusRecords in 0..DomainValidation.MAX_FOCUS_RECORDS) {
                    "存档摘要数量无效"
                }
                onHeader(info)
                val length = input.readInt()
                require(length in 1..DataCodec.MAX_BYTES) { "存档数据超过支持的大小" }
                val payload = ByteArray(length).also(input::readFully)
                hashed.on(false)
                val checksum = ByteArray(32).also(input::readFully)
                require(MessageDigest.isEqual(digest.digest(), checksum)) { "存档内容校验失败，原文件已保留" }
                require(input.read() == -1) { "存档末尾包含无效内容" }
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString()
                val data = DataCodec.decode(text)
                val summary = infoFor(id, name, info.createdAt, info.updatedAt, data)
                val expected = if (version == 1) summary.copy(
                    plans = data.plans.count { !it.archived }, archived = data.plans.count { it.archived }, recordsOnly = 0,
                ) else summary
                require(expected.copy(dailyNotes = 0) == info) { "存档摘要与记录不一致" }
                return StoredSnapshot(summary, payload, data)
            }
        } catch (error: Exception) {
            throw IOException("无法读取存档：${error.message ?: "文件不完整或已损坏"}", error)
        }
    }

    private fun writeEnvelope(stream: FileOutputStream, info: SnapshotInfo, payload: ByteArray) {
        require(payload.size in 1..DataCodec.MAX_BYTES) { "存档数据不能超过 32 MB" }
        val digest = MessageDigest.getInstance("SHA-256")
        val hashed = DigestOutputStream(stream, digest)
        val output = DataOutputStream(hashed)
        output.write(MAGIC)
        output.writeInt(VERSION)
        writeText(output, info.id)
        writeText(output, info.name)
        output.writeLong(info.createdAt)
        output.writeLong(info.updatedAt)
        output.writeInt(info.plans)
        output.writeInt(info.archived)
        output.writeInt(info.checkIns)
        output.writeInt(info.focusRecords)
        output.writeInt(info.recordsOnly)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
        hashed.on(false)
        output.write(digest.digest())
        output.flush()
    }

    private fun snapshotIds(): List<String> {
        if (!root.exists()) return emptyList()
        require(root.isDirectory) { "存档目录不可用" }
        val files = root.listFiles() ?: throw IOException("无法读取存档目录")
        return files.mapNotNull { file ->
            val name = file.name.removeSuffix(".bak")
            if (!name.endsWith(EXTENSION)) null else name.removeSuffix(EXTENSION).takeIf(ID_PATTERN::matches)
        }.distinct()
    }

    private fun fileFor(id: String): File {
        require(ID_PATTERN.matches(id)) { "存档标识无效" }
        return File(root, "$id$EXTENSION").also { target ->
            require(target.parentFile?.canonicalFile == root) { "存档路径无效" }
            require(!Files.isSymbolicLink(target.toPath())) { "存档路径无效" }
        }
    }

    private fun recoverInterrupted(target: File) {
        val backup = File(target.path + ".bak")
        require(!Files.isSymbolicLink(backup.toPath())) { "存档恢复路径无效" }
        if (backup.exists()) {
            require(backup.isFile) { "存档恢复文件不可用" }
            Files.move(backup.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** An atomic rename is preferred; fallback keeps the previous complete file until commit. */
    private fun writeAtomic(target: File, info: SnapshotInfo, payload: ByteArray) {
        val temporary = File(target.path + ".new")
        val backup = File(target.path + ".bak")
        try {
            require(root.isDirectory || root.mkdirs()) { "无法创建存档目录" }
            require(!Files.isSymbolicLink(temporary.toPath())) { "存档临时路径无效" }
            recoverInterrupted(target)
            FileOutputStream(temporary).use { output ->
                writeEnvelope(output, info, payload)
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                if (target.exists()) Files.move(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Files.deleteIfExists(backup.toPath())
            }
        } catch (error: Exception) {
            try {
                // Never follow a leftover temporary symlink during cleanup.
                if (temporary.isFile || Files.isSymbolicLink(temporary.toPath())) Files.deleteIfExists(temporary.toPath())
                if (backup.isFile && !Files.isSymbolicLink(backup.toPath())) {
                    Files.move(backup.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (recoveryError: Exception) { error.addSuppressed(recoveryError) }
            throw IOException("保存存档失败，之前的存档仍已保留：${error.message ?: "存储不可用"}", error)
        }
    }

    private fun checkedName(name: String): String = name.trim().also {
        DomainValidation.text(it, "存档名称", MAX_NAME_LENGTH)
    }

    private fun nextTimestamp(info: SnapshotInfo): Long =
        maxOf(info.updatedAt, System.currentTimeMillis().coerceIn(1L, MAX_TIMESTAMP))

    private fun infoFor(id: String, name: String, created: Long, updated: Long, data: AppData) =
        SnapshotInfo(id, name, created, updated, data.plans.count { !it.archived && !it.recordsOnly },
            data.plans.count { it.archived && !it.recordsOnly }, data.checkIns.size, data.focusRecords.size,
            recordsOnly = data.plans.count { it.recordsOnly }, dailyNotes = data.dailyNotes.size)

    private fun readText(input: DataInputStream, maximumBytes: Int): String {
        val length = input.readInt()
        require(length in 1..maximumBytes) { "存档文本长度无效" }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(ByteArray(length).also(input::readFully))).toString()
    }

    private fun writeText(output: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private data class StoredSnapshot(val info: SnapshotInfo, val payload: ByteArray, val data: AppData)

    companion object {
        const val MAX_SNAPSHOTS = 50
        const val MAX_NAME_LENGTH = 40
        private const val MAX_NAME_BYTES = MAX_NAME_LENGTH * 4
        private const val MAX_HEADER_BYTES = 512
        private const val MAX_TIMESTAMP = 253_402_300_799_999L
        private const val VERSION = 2
        private const val EXTENSION = ".snapshot"
        private val MAGIC = "PLAN-SNAPSHOT".toByteArray(Charsets.US_ASCII)
        private val ID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val locks = ConcurrentHashMap<String, Any>()
    }
}
