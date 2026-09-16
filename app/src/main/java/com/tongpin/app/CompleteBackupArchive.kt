package com.tongpin.app

import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class CompleteBackupSummary(val createdAt: Long, val plans: Int, val archived: Int,
    val checkIns: Int, val focusRecords: Int, val snapshots: Int, val sounds: Int, val bytes: Long,
    val autoSnapshots: Int = 0, val dailyNotes: Int = 0)

internal data class CompleteBackupContent(val directory: File, val data: AppData,
    val preferences: Map<String, Any?>, val summary: CompleteBackupSummary, val formatVersion: Int = 5)

/** Portable, bounded ZIP format. Entry names are allowlisted before any filesystem access. */
internal object CompleteBackupArchive {
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    const val MAX_ENTRIES = 80
    private const val MANIFEST_LIMIT = 32 * 1024L
    private val snapshotPath = Regex("snapshots/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.snapshot")
    private val autoSnapshotPath = Regex("auto_snapshots/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.snapshot")
    private val soundPath = Regex("sounds/custom_[0-9a-f]{64}\\.(json|mp3|m4a|wav|ogg|flac|amr|audio)")
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val legacyPreferenceKeys = setOf("enabled", "sound", "vibrate", "sound_id", "compatible_sound", "theme_mode")
    private val versionThreePreferenceKeys = legacyPreferenceKeys + "theme_color"
    internal val focusPreferenceKeys = setOf("focus_favorites", "focus_last_minutes")
    private val versionFourPreferenceKeys = versionThreePreferenceKeys + focusPreferenceKeys
    internal val homePreferenceKeys = setOf("home_order", "home_show_progress", "home_show_recent", "home_density", "start_page")
    internal val preferenceKeys = versionFourPreferenceKeys + homePreferenceKeys + "theme_style"
    internal val appearancePreferenceKeys = setOf("theme_mode", "theme_color", "theme_style")
    internal val uiPreferenceKeys = appearancePreferenceKeys + focusPreferenceKeys + homePreferenceKeys
    internal val defaultFocusPreferences: Map<String, Any?> = mapOf("focus_favorites" to "15,25,45,60", "focus_last_minutes" to 25L)
    internal val defaultPersonalizationPreferences: Map<String, Any?> = mapOf("theme_style" to "SOFT",
        "home_order" to "PROGRESS,TASKS,RECENT", "home_show_progress" to true, "home_show_recent" to false,
        "home_density" to "COMFORTABLE", "start_page" to 0L)

    fun limitFor(path: String): Long = when {
        path == "current.json" -> DataCodec.MAX_BYTES.toLong()
        path == "settings.json" -> 4096L
        snapshotPath.matches(path) || autoSnapshotPath.matches(path) -> DataCodec.MAX_BYTES + 512L
        soundPath.matches(path) -> if (path.endsWith(".json")) 4096L else SoundRules.MAX_IMPORT_BYTES
        else -> throw IllegalArgumentException("备份包含不支持的文件路径")
    }

    fun write(directory: File, output: OutputStream, createdAt: Long = System.currentTimeMillis()) {
        val files = directory.walkTopDown().filter { it.isFile }.map { file ->
            require(!Files.isSymbolicLink(file.toPath())) { "备份来源路径无效" }
            val name = file.relativeTo(directory).invariantSeparatorsPath
            require(file.length() in 1..limitFor(name)) { "备份文件大小无效" }
            Entry(name, file.length(), digest(file))
        }.sortedBy { it.path }.toList()
        require(files.size + 1 <= MAX_ENTRIES && files.sumOf { it.size } <= MAX_TOTAL_BYTES) { "打包内容超过支持的大小" }
        require(files.any { it.path == "current.json" } && files.any { it.path == "settings.json" }) { "备份内容不完整" }
        val manifest = DataCodec.writeJsonObject(linkedMapOf("format" to "plan-complete", "version" to 5,
            "createdAt" to createdAt, "files" to files.map { linkedMapOf("path" to it.path, "size" to it.size, "sha256" to it.sha256) }))
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            files.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.path))
                File(directory, entry.path).inputStream().use { input -> copyBounded(input, zip, entry.size) }
                zip.closeEntry()
            }
        }
    }

    fun read(input: InputStream, directory: File, validateAudio: (File, Long) -> Unit = { _, _ -> }): CompleteBackupContent {
        require(!directory.exists() || directory.listFiles().orEmpty().isEmpty()) { "备份临时目录不可用" }
        require(directory.isDirectory || directory.mkdirs()) { "无法创建备份临时目录" }
        try {
            var createdAt = 0L
            var formatVersion = 0
            ZipInputStream(BoundedInputStream(input.buffered(), MAX_TOTAL_BYTES + 1024 * 1024)).use { zip ->
                val first = zip.nextEntry ?: throw IllegalArgumentException("备份文件为空")
                require(first.name == "manifest.json" && !first.isDirectory) { "请选择 plan 一键备份文件" }
                val manifest = DataCodec.readJsonObject(readUtf8(zip, MANIFEST_LIMIT))
                require(manifest.keys == setOf("format", "version", "createdAt", "files") && manifest["format"] == "plan-complete" && manifest["version"] in setOf(1L, 2L, 3L, 4L, 5L)) { "不支持这个完整备份版本" }
                formatVersion = (manifest["version"] as Long).toInt()
                createdAt = (manifest["createdAt"] as? Long)?.takeIf { it in 1..253_402_300_799_999L }
                    ?: throw IllegalArgumentException("备份日期无效")
                val rows = manifest["files"] as? List<*> ?: throw IllegalArgumentException("备份清单无效")
                require(rows.size in 2 until MAX_ENTRIES) { "备份文件数量超过限制" }
                val entries = rows.map { value ->
                    val row = value as? Map<*, *> ?: throw IllegalArgumentException("备份清单无效")
                    require(row.keys == setOf("path", "size", "sha256")) { "备份清单无效" }
                    val path = row["path"] as? String ?: throw IllegalArgumentException("备份路径无效")
                    require(formatVersion >= 2 || !path.startsWith("auto_snapshots/")) { "旧版备份不能包含自动存档" }
                    val limit = limitFor(path)
                    val size = row["size"] as? Long ?: throw IllegalArgumentException("备份大小无效")
                    val hash = row["sha256"] as? String ?: throw IllegalArgumentException("备份校验信息无效")
                    require(size in 1..limit && digestPattern.matches(hash)) { "备份大小或校验信息无效" }
                    Entry(path, size, hash)
                }
                require(entries.map { it.path }.toSet().size == entries.size) { "备份存在重复文件" }
                require(entries.sumOf { it.size } <= MAX_TOTAL_BYTES) { "备份内容超过 512 MB" }
                val expected = entries.associateBy { it.path }
                require("current.json" in expected && "settings.json" in expected) { "备份缺少必要内容" }
                val seen = mutableSetOf<String>()
                var total = 0L
                while (true) {
                    val next = zip.nextEntry ?: break
                    require(!next.isDirectory && seen.add(next.name)) { "备份存在重复或无效文件" }
                    val entry = expected[next.name] ?: throw IllegalArgumentException("备份中出现清单外的文件")
                    limitFor(next.name)
                    require(next.size < 0 || next.size == entry.size) { "备份文件大小不匹配" }
                    val target = File(directory, next.name)
                    require(target.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { "备份路径无效" }
                    require(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) { "无法准备备份内容" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var count = 0L
                        while (true) {
                            val size = zip.read(buffer)
                            if (size < 0) break
                            if (size == 0) continue
                            count += size; total += size
                            require(count <= entry.size && total <= MAX_TOTAL_BYTES) { "备份实际内容超过限制" }
                            output.write(buffer, 0, size); digest.update(buffer, 0, size)
                        }
                        require(count == entry.size && hex(digest.digest()) == entry.sha256) { "备份内容校验失败，文件可能已损坏" }
                        output.fd.sync()
                    }
                }
                require(seen == expected.keys) { "备份文件不完整" }
            }
            return validate(directory, createdAt, validateAudio, formatVersion)
        } catch (error: Exception) { directory.deleteRecursively(); throw error }
    }

    fun validate(directory: File, createdAt: Long, validateAudio: (File, Long) -> Unit = { _, _ -> }, formatVersion: Int = 5): CompleteBackupContent {
        require(formatVersion in 1..5) { "不支持这个完整备份版本" }
        val current = File(directory, "current.json")
        val data = current.inputStream().use { DataCodec.decode(readUtf8(it, DataCodec.MAX_BYTES.toLong())) }
        val preferences = File(directory, "settings.json").inputStream().use { validatePreferences(DataCodec.readJsonObject(readUtf8(it, 4096)), formatVersion) }
        val snapshots = SnapshotStore(File(directory, "snapshots")).list()
        require(snapshots.size <= SnapshotStore.MAX_SNAPSHOTS && snapshots.none { it.corrupt }) { "备份包含无法读取的存档" }
        val autoSnapshots = AutoSnapshotStore.validateDirectory(File(directory, AutoSnapshotStore.DIRECTORY))
        require(formatVersion >= 2 || autoSnapshots.isEmpty()) { "旧版备份不能包含自动存档" }
        val audioDirectory = File(directory, "sounds")
        val allSounds = audioDirectory.listFiles().orEmpty()
        val metadata = allSounds.filter { it.name.endsWith(".json") }
        require(metadata.size <= SoundRules.MAX_CUSTOM_SOUNDS) { "备份提示音数量超过限制" }
        val used = mutableSetOf<String>()
        metadata.forEach { file ->
            val json = file.inputStream().use { DataCodec.readJsonObject(readUtf8(it, 4096)) }
            require(json.keys == setOf("version", "id", "name", "durationMs", "extension", "bytes")) { "提示音信息格式无效" }
            val id = json["id"] as? String ?: throw IllegalArgumentException("提示音标识无效")
            val ext = json["extension"] as? String ?: throw IllegalArgumentException("提示音格式无效")
            val name = json["name"] as? String ?: throw IllegalArgumentException("提示音名称无效")
            val duration = json["durationMs"] as? Long ?: throw IllegalArgumentException("提示音时长无效")
            val size = json["bytes"] as? Long ?: throw IllegalArgumentException("提示音大小无效")
            require(json["version"] == 1L && SoundRules.isCustomId(id) && file.name == "$id.json" && SoundRules.isStorageExtension(ext)) { "提示音信息无效" }
            require(SoundRules.sanitizeName(name) == name) { "提示音名称无效" }
            SoundRules.validateDuration(duration); SoundRules.validateSize(size)
            val sound = File(audioDirectory, "$id.$ext")
            require(sound.isFile && sound.length() == size && id == "custom_${digest(sound)}") { "提示音内容校验失败" }
            validateAudio(sound, duration)
            used += file.name; used += sound.name
        }
        require(used == allSounds.map { it.name }.toSet()) { "备份存在未匹配的提示音文件" }
        val selected = preferences["sound_id"] as String
        require(selected in setOf("chime", "wood", "double", "soft") || "$selected.json" in used) { "备份未包含所选提示音" }
        return CompleteBackupContent(directory, data, preferences, CompleteBackupSummary(createdAt,
            data.plans.count { !it.archived && !it.recordsOnly }, data.plans.count { it.archived && !it.recordsOnly },
            data.checkIns.size, data.focusRecords.size, snapshots.size, metadata.size,
            directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }, autoSnapshots.size, data.dailyNotes.size), formatVersion)
    }

    fun validatePreferences(values: Map<String, Any?>, formatVersion: Int? = null): Map<String, Any?> {
        require(formatVersion == null || formatVersion in 1..5) { "不支持这个完整备份版本" }
        // ZIP schemas remain versioned; a new preference must not widen an old ZIP's schema.
        // Unversioned validation is also used by durable rollback journals from older releases.
        val allowed = when (formatVersion) { 1, 2 -> legacyPreferenceKeys; 3 -> versionThreePreferenceKeys; 4 -> versionFourPreferenceKeys; else -> preferenceKeys }
        require(values.keys.all { it in allowed } && setOf("enabled", "sound", "vibrate", "sound_id").all { it in values }) { "提醒偏好格式无效" }
        for (key in listOf("enabled", "sound", "vibrate")) require(values[key] is Boolean) { "提醒偏好格式无效" }
        val id = values["sound_id"] as? String ?: throw IllegalArgumentException("提示音选项无效")
        require(id in setOf("chime", "wood", "double", "soft") || SoundRules.isCustomId(id)) { "提示音选项无效" }
        if ("compatible_sound" in values) require(values["compatible_sound"] is Boolean) { "提醒播放模式无效" }
        if ("theme_mode" in values) require(values["theme_mode"] in setOf("SYSTEM", "LIGHT", "DARK")) { "外观选项无效" }
        if ((formatVersion ?: 0) >= 3 || "theme_color" in values)
            require(values["theme_color"] in setOf("GREEN", "BLUE", "PURPLE", "ROSE", "ORANGE", "GRAPHITE")) { "配色选项无效" }
        if ((formatVersion ?: 0) >= 4 || focusPreferenceKeys.any { it in values }) {
            val favorites = values["focus_favorites"] as? String ?: throw IllegalArgumentException("常用时长格式无效")
            require(favorites.length <= 39) { "常用时长格式无效" }
            val durations = favorites.split(',').map { entry ->
                require(entry.matches(Regex("[1-9][0-9]{0,3}"))) { "常用时长格式无效" }
                entry.toInt().also { require(it in 1..MAX_FOCUS_MINUTES) { "常用时长超出范围" } }
            }
            require(durations.size in 1..8 && durations == durations.distinct().sorted()) { "常用时长重复、顺序或数量无效" }
            val last = values["focus_last_minutes"]
            require((last is Int || last is Long) && (last as Number).toLong() in 1L..MAX_FOCUS_MINUTES.toLong()) { "上次专注时长无效" }
        }
        if (formatVersion == 5 || "theme_style" in values)
            require(values["theme_style"] in setOf("CLEAN", "PAPER", "SOFT")) { "主题风格无效" }
        if (formatVersion == 5 || homePreferenceKeys.any { it in values }) {
            val order = (values["home_order"] as? String)?.split(',') ?: throw IllegalArgumentException("首页顺序无效")
            require(order.size == 3 && order.toSet() == setOf("PROGRESS", "TASKS", "RECENT")) { "首页顺序无效" }
            require(values["home_show_progress"] is Boolean && values["home_show_recent"] is Boolean) { "首页模块选项无效" }
            require(values["home_density"] in setOf("COMFORTABLE", "COMPACT")) { "首页密度选项无效" }
            val page = values["start_page"]
            require((page is Int || page is Long) && (page as Number).toLong() in 0L..3L) { "默认打开页面无效" }
        }
        var result = values
        if (formatVersion != null && formatVersion < 3) result = result + ("theme_color" to "GREEN")
        if (formatVersion != null && formatVersion < 4) result = result + defaultFocusPreferences
        if (formatVersion != null && formatVersion < 5) result = result + defaultPersonalizationPreferences
        return result
    }

    internal fun readUtf8(input: InputStream, max: Long): String {
        val bytes = ByteArrayOutputStream(); copyBounded(input, bytes, max)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
    internal fun digest(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(32 * 1024)
        while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }; hex(digest.digest())
    }
    internal fun copyBounded(input: InputStream, output: OutputStream, max: Long): Long {
        val buffer = ByteArray(32 * 1024); var total = 0L
        while (true) { val size = input.read(buffer); if (size < 0) break; if (size == 0) continue
            total += size; require(total <= max) { "备份内容超过支持的大小" }; output.write(buffer, 0, size) }
        return total
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private data class Entry(val path: String, val size: Long, val sha256: String)
    private class BoundedInputStream(input: InputStream, val max: Long): FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) checkCount(1) }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also { if (it > 0) checkCount(it) }
        private fun checkCount(n: Int) { count += n; require(count <= max) { "备份文件超过 512 MB" } }
    }
}
