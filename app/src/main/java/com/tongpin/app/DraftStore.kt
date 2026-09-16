package com.tongpin.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

data class DraftOpenResult(val session: DraftSession, val values: Map<String, String>?, val notice: String?, val isolatedValues: Map<String, String>? = null)

/** Drafts are separate from saved application data and never enter exports or statistics. */
class DraftStore internal constructor(directory: File, private val beforeDelete: (() -> Unit)? = null) {
    constructor(context: Context) : this(context.applicationContext.filesDir)
    private val root = File(directory, "editor-drafts")
    private val shared = states.getOrPut(root.canonicalPath) { SharedState() }
    private val epochFile = File(root, "epoch")

    fun open(key: String, baseline: String, requestOrder: Long = nextSessionOrder()): DraftOpenResult = synchronized(shared) {
        require(key.length in 1..200 && baseline.length in 1..200)
        check(requestOrder > shared.minimumOrder && requestOrder > (shared.orders[key] ?: 0L)) { "这个编辑界面已过期，请重新打开" }
        val epoch = epoch()
        // Rotation may open the next editor before the stop-time IO job runs. Flush
        // the previous owner's latest in-memory fields here, before replacing it.
        shared.sessions[key]?.takeIf { !it.closed.get() && it.epoch == epoch }?.let { previous ->
            if (shared.owners[key] == previous.token) persist(previous)
        }
        val token = UUID.randomUUID().toString()
        shared.owners[key] = token
        shared.orders[key] = requestOrder
        val activeFile = entryFile(key)
        val file = if (activeFile.exists()) activeFile else File(root, "isolated-${activeFile.name}")
        val alreadyIsolated = file != activeFile
        var values: Map<String, String>? = null
        var notice: String? = null
        var isolatedValues: Map<String, String>? = null
        if (file.exists()) {
            try {
                require(file.length() <= MAX_BYTES)
                val record = DataCodec.readJsonObject(file.readText(Charsets.UTF_8))
                val requiredKeys = setOf("version", "key", "epoch", "baseline", "values")
                require(record.keys.containsAll(requiredKeys) && record.keys.all { it in requiredKeys || it == "status" } && record["version"] == 1L)
                val status = record["status"] ?: "draft"
                require(status == "draft" || status == "submitting")
                require(record["key"] == key)
                val raw = record["values"] as? Map<*, *> ?: error("草稿内容无效")
                val decoded = raw.entries.associate { (name, value) ->
                    (name as? String ?: error("草稿字段无效")) to (value as? String ?: error("草稿字段无效"))
                }.also(::validateValues)
                if (alreadyIsolated || status == "submitting" || record["epoch"] != epoch || record["baseline"] != baseline) {
                    notice = if (status == "submitting") "上次保存状态未确认，草稿已隔离；请核对已保存记录，旧输入仍可查看和复制。"
                        else "任务或记录已变化，旧草稿已隔离，本次使用当前内容。"
                    isolatedValues = decoded
                    if (!alreadyIsolated) isolate(file)
                } else {
                    values = decoded
                }
            } catch (_: Exception) {
                notice = "旧草稿无法读取，已隔离；已保存的任务和记录不受影响。"
                if (!alreadyIsolated) isolate(file)
            }
        }
        val session = DraftSession(this, key, baseline, epoch, token, values)
        shared.sessions[key] = session
        DraftOpenResult(session, values, notice, isolatedValues)
    }

    /** Persist a new epoch first. Any in-flight session from before restore is now harmless. */
    fun clearAll() = synchronized(shared) {
        ensureRoot()
        atomicWrite(epochFile, UUID.randomUUID().toString())
        shared.owners.clear()
        shared.sessions.clear()
        shared.orders.clear()
        shared.minimumOrder = nextSessionOrder()
        // Retain old drafts as isolated evidence; open() explains why they cannot be reused.
    }

    internal fun write(session: DraftSession, values: Map<String, String>?, submitting: Boolean = session.submitting.get()) = synchronized(shared) {
        check(session.epoch == epoch() && shared.owners[session.key] == session.token) { "草稿对应的数据已变化，请关闭后重新打开" }
        val target = entryFile(session.key)
        if (values == null) {
            beforeDelete?.invoke()
            Files.deleteIfExists(target.toPath())
        } else {
            validateValues(values)
            val text = DataCodec.writeJsonObject(linkedMapOf("version" to 1, "key" to session.key,
                "epoch" to session.epoch, "baseline" to session.baseline, "status" to (if (submitting) "submitting" else "draft"), "values" to values))
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "草稿内容过长" }
            ensureRoot()
            val active = root.listFiles { item -> item.name.endsWith(".json") && !item.name.startsWith("isolated-") }.orEmpty()
            require(target.exists() || active.size < MAX_DRAFTS) { "草稿数量已达上限，请先保存或放弃其他草稿" }
            atomicWrite(target, text)
        }
    }

    internal fun persist(session: DraftSession) = synchronized(shared) {
        if (!session.closed.get()) write(session, session.latest.get() ?: emptyMap<String, String>().takeIf { session.submitting.get() })
    }
    internal fun discard(session: DraftSession) = synchronized(shared) {
        write(session, null)
        session.closed.set(true)
    }
    internal fun prepareCommit(session: DraftSession) = synchronized(shared) {
        check(!session.closed.get()) { "此草稿已关闭，请重新打开编辑" }
        write(session, session.latest.get() ?: emptyMap(), submitting = true)
        session.submitting.set(true)
    }
    internal fun abortCommit(session: DraftSession) = synchronized(shared) {
        if (!session.closed.get()) {
            write(session, session.latest.get(), submitting = false)
            session.submitting.set(false)
        }
    }

    internal fun invalidate(session: DraftSession) = synchronized(shared) {
        if (shared.owners[session.key] == session.token) isolate(entryFile(session.key))
    }

    private fun epoch(): String {
        ensureRoot()
        if (!epochFile.exists()) atomicWrite(epochFile, UUID.randomUUID().toString())
        return epochFile.readText().also { require(runCatching { UUID.fromString(it) }.isSuccess) { "草稿保存信息损坏，请先重置草稿" } }
    }
    private fun ensureRoot() { require(root.isDirectory || root.mkdirs()) { "无法创建草稿目录" } }
    private fun entryFile(key: String) = File(root, "${draftHash(key)}.json")
    private fun isolate(file: File) {
        if (!file.exists()) return
        Files.move(file.toPath(), File(root, "isolated-${file.name}").toPath(), StandardCopyOption.REPLACE_EXISTING)
        root.listFiles { item -> item.name.startsWith("isolated-") }.orEmpty()
            .sortedByDescending { it.lastModified() }.drop(MAX_DRAFTS).forEach { it.delete() }
    }
    private fun atomicWrite(file: File, text: String) {
        val pending = File(file.path + ".new")
        try {
            FileOutputStream(pending).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            try { Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { pending.delete() }
    }

    private class SharedState {
        val owners = mutableMapOf<String, String>()
        val sessions = mutableMapOf<String, DraftSession>()
        val orders = mutableMapOf<String, Long>()
        var minimumOrder = 0L
    }
    companion object {
        private const val MAX_BYTES = 24 * 1024L
        private const val MAX_DRAFTS = 200
        private val states = ConcurrentHashMap<String, SharedState>()
        private val order = AtomicLong()
        internal fun nextSessionOrder(): Long = order.incrementAndGet()
        private fun validateValues(values: Map<String, String>) {
            require(values.size <= 32 && values.all { (key, value) -> key.length in 1..60 && value.length <= 2000 }) { "草稿字段无效" }
        }
    }
}

/** Field updates never wait for disk IO. The store lock orders flushing and final clearing. */
class DraftSession internal constructor(
    private val store: DraftStore,
    internal val key: String,
    internal val baseline: String,
    internal val epoch: String,
    internal val token: String,
    initial: Map<String, String>?,
) {
    internal val latest = AtomicReference<Map<String, String>?>(initial)
    internal val closed = AtomicBoolean(false)
    internal val submitting = AtomicBoolean(false)
    fun update(values: Map<String, String>?) { if (!closed.get()) latest.set(values?.toMap()) }
    fun persist() = store.persist(this)
    fun prepareCommit() = store.prepareCommit(this)
    fun abortCommit() = store.abortCommit(this)
    fun finishCommitted() {
        // Close before touching storage: even a failed cleanup must never revive a committed ADD.
        closed.set(true)
        store.write(this, null)
    }
    fun discard() = store.discard(this)
    fun isolateInvalid() { store.invalidate(this); latest.set(null) }
}
