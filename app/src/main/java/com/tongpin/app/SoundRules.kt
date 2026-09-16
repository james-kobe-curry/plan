package com.tongpin.app

import java.text.Normalizer

/** Limits and identifiers shared by import, storage, and the sound picker. */
object SoundRules {
    const val MAX_IMPORT_BYTES = 20L * 1024 * 1024
    const val MAX_DURATION_MS = 60_000L
    const val MAX_CUSTOM_SOUNDS = 10
    const val MAX_NAME_CODE_POINTS = 40

    private val customIdPattern = Regex("custom_[0-9a-f]{64}")
    private val soundExtension = Regex("(?i)\\.(mp3|wav|wave|m4a|aac|ogg|oga|opus|flac|amr|3gp|mp4)$")

    fun validateSize(bytes: Long) {
        require(bytes > 0) { "音频文件为空，请选择其他文件" }
        require(bytes <= MAX_IMPORT_BYTES) { "音频文件不能超过 20 MB" }
    }

    fun validateDuration(durationMs: Long) {
        require(durationMs > 0) { "无法读取音频时长，请选择其他文件" }
        require(durationMs <= MAX_DURATION_MS) { "提示音最长为 60 秒，请先截取需要的片段" }
    }

    fun validateCapacity(existing: Int, duplicate: Boolean = false) {
        require(existing >= 0) { "提示音数量无效" }
        require(duplicate || existing < MAX_CUSTOM_SOUNDS) { "最多保存 10 首自定义提示音，请先删除不需要的声音" }
    }

    fun customId(sha256: ByteArray): String {
        require(sha256.size == 32) { "音频标识无效" }
        return "custom_" + sha256.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun isCustomId(id: String): Boolean = customIdPattern.matches(id)

    fun displayName(raw: String?): String {
        val leaf = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        return sanitizeName(leaf.trim().replace(soundExtension, ""))
    }

    fun sanitizeName(raw: String?): String {
        val normalized = Normalizer.normalize(raw.orEmpty(), Normalizer.Form.NFC)
        val clean = buildString {
            normalized.codePoints().forEach { cp ->
                when {
                    Character.isWhitespace(cp) -> append(' ')
                    Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt() -> Unit
                    else -> appendCodePoint(cp)
                }
            }
        }.replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank() || clean == "." || clean == "..") return "自定义提示音"
        val length = clean.codePointCount(0, clean.length).coerceAtMost(MAX_NAME_CODE_POINTS)
        return clean.substring(0, clean.offsetByCodePoints(0, length))
    }

    /** Canonical extensions come from the parsed audio track, never from a supplied filename. */
    fun extensionForMime(mime: String): String = when (mime.lowercase()) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4a-latm", "audio/aac" -> "m4a"
        "audio/raw", "audio/wav", "audio/x-wav" -> "wav"
        "audio/vorbis", "audio/opus", "audio/ogg" -> "ogg"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/3gpp", "audio/amr", "audio/amr-wb" -> "amr"
        else -> "audio"
    }

    fun isStorageExtension(value: String): Boolean = value in setOf("mp3", "m4a", "wav", "ogg", "flac", "amr", "audio")
}
