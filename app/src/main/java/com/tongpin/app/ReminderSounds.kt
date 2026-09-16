package com.tongpin.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

data class ReminderSound(
    val id: String,
    val name: String,
    val durationMs: Long,
    val custom: Boolean,
    val subtitle: String = ""
)

/** Call disk operations on an IO thread. URIs remain valid after the picked source is moved. */
class ReminderSoundLibrary(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "reminder_sounds")

    fun list(): List<ReminderSound> = synchronized(lock) {
        builtIns + readCustom().map { it.sound }
    }

    fun find(id: String): ReminderSound? = synchronized(lock) {
        builtIns.firstOrNull { it.id == id } ?: readCustom().firstOrNull { it.sound.id == id }?.sound
    }

    fun uri(sound: ReminderSound): Uri = synchronized(lock) {
        val file = if (!sound.custom) {
            val builtIn = builtIns.firstOrNull { it.id == sound.id }
                ?: throw IllegalArgumentException("找不到这个提示音，请重新选择")
            ensureBuiltIn(builtIn)
        } else {
            readCustom().firstOrNull { it.sound.id == sound.id }?.file
                ?: throw IllegalArgumentException("找不到这个音频文件，请重新导入")
        }
        FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    fun importAudio(source: Uri): ReminderSound {
        ensureDirectory()
        val temporary = File.createTempFile("import-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var byteCount = 0L
            val input = app.contentResolver.openInputStream(source)
                ?: throw IllegalArgumentException("无法打开这个文件，请重新选择")
            input.use { stream ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val size = stream.read(buffer)
                        if (size < 0) break
                        if (size == 0) continue
                        byteCount += size
                        SoundRules.validateSize(byteCount)
                        output.write(buffer, 0, size)
                        digest.update(buffer, 0, size)
                    }
                    output.fd.sync()
                }
            }
            SoundRules.validateSize(byteCount)
            val id = SoundRules.customId(digest.digest())
            // Providers and codecs can block. Keep their work outside the library lock so a
            // finishing timer can still resolve and play its already selected sound.
            synchronized(lock) { readCustom().firstOrNull { it.sound.id == id }?.sound }
                ?.let { return it }
            val audio = inspectAudio(temporary)
            val sound = ReminderSound(id, SoundRules.displayName(sourceName(source)), audio.durationMs, true, "已保存到此设备")
            return synchronized(lock) {
                // Another import or deletion may have finished during the copy/decode.
                val existing = readCustom()
                existing.firstOrNull { it.sound.id == id }?.let { return@synchronized it.sound }
                SoundRules.validateCapacity(existing.size)
                val target = File(directory, "$id.${audio.extension}")
                // A file without metadata can remain after a crash; its ID is content-derived.
                if (target.exists() && !target.delete()) throw IOException("无法保存提示音，请稍后重试")
                if (!temporary.renameTo(target)) throw IOException("无法保存提示音，请稍后重试")
                try {
                    writeMetadata(sound, audio.extension, byteCount)
                } catch (failure: Exception) {
                    target.delete()
                    throw failure
                }
                sound
            }
        } finally {
            temporary.delete()
        }
    }

    /** The caller changes the selected sound first, so a channel never references a deleted file. */
    fun delete(id: String) = synchronized(lock) {
        require(SoundRules.isCustomId(id)) { "内置提示音不能删除" }
        val stored = readCustom().firstOrNull { it.sound.id == id }
        if (stored != null && stored.file.exists() && !stored.file.delete()) {
            throw IOException("无法删除提示音，请稍后重试")
        }
        AtomicFile(File(directory, "$id.json")).delete()
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("无法创建提示音目录")
        }
    }

    private fun ensureBuiltIn(sound: ReminderSound): File {
        ensureDirectory()
        val target = File(directory, "plan_${sound.id}.wav")
        if (target.isFile && target.length() > 44L) return target
        val resource = app.resources.getIdentifier("plan_${sound.id}", "raw", app.packageName)
        if (resource == 0) throw IOException("内置提示音暂时无法读取")
        val temporary = File.createTempFile("builtin-", ".tmp", directory)
        try {
            app.resources.openRawResource(resource).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (target.exists() && !target.delete()) throw IOException("无法保存内置提示音")
            if (!temporary.renameTo(target)) throw IOException("无法保存内置提示音")
        } finally {
            temporary.delete()
        }
        return target
    }

    private data class StoredSound(val sound: ReminderSound, val file: File)

    private fun readCustom(): List<StoredSound> {
        if (!directory.isDirectory) return emptyList()
        val metadataFiles = directory.listFiles().orEmpty().mapNotNull { file ->
            when {
                file.name.endsWith(".json") -> file
                file.name.endsWith(".json.bak") -> File(directory, file.name.removeSuffix(".bak"))
                else -> null
            }
        }.distinctBy { it.name }.sortedBy { it.name }
        return metadataFiles.mapNotNull { file ->
            val id = file.name.removeSuffix(".json")
            if (!SoundRules.isCustomId(id)) return@mapNotNull null
            try {
                val bytes = AtomicFile(file).openRead().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 4096)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val json = JSONObject(bytes.toString(Charsets.UTF_8))
                require(json.getInt("version") == 1 && json.getString("id") == id)
                val extension = json.getString("extension")
                require(SoundRules.isStorageExtension(extension))
                val duration = json.getLong("durationMs")
                val size = json.getLong("bytes")
                SoundRules.validateDuration(duration)
                SoundRules.validateSize(size)
                val audio = File(directory, "$id.$extension")
                require(audio.isFile && audio.length() == size)
                StoredSound(ReminderSound(id, SoundRules.sanitizeName(json.getString("name")), duration, true, "已保存到此设备"), audio)
            } catch (_: Exception) {
                // Keep damaged metadata for possible recovery, while letting built-in sounds work.
                null
            }
        }
    }

    private fun writeMetadata(sound: ReminderSound, extension: String, size: Long) {
        val json = JSONObject().put("version", 1).put("id", sound.id).put("name", sound.name)
            .put("durationMs", sound.durationMs).put("extension", extension).put("bytes", size)
        val atomic = AtomicFile(File(directory, "${sound.id}.json"))
        val output = atomic.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
    }

    private fun sourceName(uri: Uri): String? = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment
    } catch (_: Exception) { uri.lastPathSegment }

    /** A complete backup uses the same decoder validation as an individual audio import. */
    internal fun validateStoredAudio(file: File, expectedDurationMs: Long) {
        val audio = inspectAudio(file)
        require(kotlin.math.abs(audio.durationMs - expectedDurationMs) <= 1000L) { "提示音时长与备份信息不一致" }
        require(audio.extension == file.extension) { "提示音格式与备份信息不一致" }
    }

    private data class AudioInfo(val durationMs: Long, val extension: String)

    private fun inspectAudio(file: File): AudioInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var audioTrack = -1
            var audioFormat: MediaFormat? = null
            var trackDurationUs = 0L
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                require(!mime.startsWith("video/")) { "请选择音频文件，视频不能用作提示音" }
                if (mime.startsWith("audio/")) {
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        trackDurationUs = maxOf(trackDurationUs, format.getLong(MediaFormat.KEY_DURATION))
                    }
                    if (audioTrack < 0) { audioTrack = index; audioFormat = format }
                }
            }
            require(audioTrack >= 0 && audioFormat != null) { "没有找到可播放的音轨，请选择其他音频" }
            val retriever = MediaMetadataRetriever()
            val metadataDuration = try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally { retriever.release() }
            val duration = maxOf((trackDurationUs + 999L) / 1000L, metadataDuration)
            SoundRules.validateDuration(duration)
            val format = requireNotNull(audioFormat)
            val decoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            require(decoder != null) { "手机不支持这种音频格式，请选择 MP3、WAV 或 M4A 文件" }
            extractor.selectTrack(audioTrack)
            verifyDecoding(extractor, format, decoder)
            return AudioInfo(duration, SoundRules.extensionForMime(format.getString(MediaFormat.KEY_MIME).orEmpty()))
        } catch (failure: IllegalArgumentException) {
            if (failure.message?.any { it.code > 127 } == true) throw failure
            throw IllegalArgumentException("这个文件无法播放，请选择完整的音频文件", failure)
        } catch (failure: Exception) {
            throw IllegalArgumentException("这个文件无法播放，请选择完整的音频文件", failure)
        } finally { extractor.release() }
    }

    /** Decode a real sample rather than accepting a renamed file or metadata alone. */
    private fun verifyDecoding(extractor: MediaExtractor, format: MediaFormat, decoderName: String) {
        val codec = MediaCodec.createByCodecName(decoderName)
        var started = false
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            started = true
            var inputEnded = false
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 5000L
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(inputIndex))
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, count, extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)
                if (outputIndex >= 0) {
                    val decoded = info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (decoded) return
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
            throw IllegalArgumentException("这个文件没有可播放的声音，请选择其他音频")
        } finally {
            if (started) try { codec.stop() } catch (_: Exception) { }
            codec.release()
        }
    }

    companion object {
        const val defaultId = "chime"
        private val lock = Any()
        private val builtIns = listOf(
            ReminderSound("chime", "清晨铃", 2800L, false, "明亮、清晰"),
            ReminderSound("wood", "轻木琴", 2400L, false, "短促、利落"),
            ReminderSound("double", "清脆双音", 1900L, false, "两声提示"),
            ReminderSound("soft", "柔和回响", 3600L, false, "舒缓、轻柔")
        )
    }
}
