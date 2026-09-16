package com.tongpin.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FocusBackupTest {
    @get:Rule val temp = TemporaryFolder()
    private val old = mapOf<String, Any?>("enabled" to true, "sound" to true, "vibrate" to false,
        "sound_id" to "wood", "theme_mode" to "DARK", "theme_color" to "PURPLE")
    private val versionFour = old + mapOf("focus_favorites" to "1,40,50,90,1440", "focus_last_minutes" to 50L)
    private val current = versionFour + CompleteBackupArchive.defaultPersonalizationPreferences
    private fun source(name: String, prefs: Map<String, Any?>) = temp.newFolder(name).apply {
        File(this, "current.json").writeText(DataCodec.encode(AppData(nickname = "专注偏好")))
        File(this, "settings.json").writeText(DataCodec.writeJsonObject(prefs))
    }
    private fun archive(folder: File, version: Int = 5): ByteArray {
        val bytes = ByteArrayOutputStream()
        if (version == 5) CompleteBackupArchive.write(folder, bytes, 100)
        else ZipOutputStream(bytes).use { zip ->
            val files = folder.listFiles()!!.sortedBy { it.name }
            val manifest = mapOf("format" to "plan-complete", "version" to version, "createdAt" to 100L,
                "files" to files.map { mapOf("path" to it.name, "size" to it.length(), "sha256" to CompleteBackupArchive.digest(it)) })
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(DataCodec.writeJsonObject(manifest).toByteArray()); zip.closeEntry()
            files.forEach { zip.putNextEntry(ZipEntry(it.name)); zip.write(it.readBytes()); zip.closeEntry() }
        }
        return bytes.toByteArray()
    }
    private fun read(bytes: ByteArray, name: String) = CompleteBackupArchive.read(bytes.inputStream(), File(temp.root, name))
    private fun reject(block: () -> Unit) { try { block(); fail("Invalid focus preferences accepted") } catch (_: IllegalArgumentException) {} }

    @Test fun completeBackupKeepsCustomPresetsAndManualMemoryWithAppearance() {
        val result = read(archive(source("source", current)), "result")
        assertEquals(5, result.formatVersion)
        assertEquals(current, result.preferences)
        assertEquals("专注偏好", result.data.nickname)
    }

    @Test fun allOlderZipVersionsReceiveDefaultsWithoutLosingExistingTheme() {
        for (version in 1..3) {
            val choices = if (version < 3) old - "theme_color" else old
            val result = read(archive(source("v$version", choices), version), "result$version")
            assertEquals(version, result.formatVersion)
            assertEquals("15,25,45,60", result.preferences["focus_favorites"])
            assertEquals(25L, result.preferences["focus_last_minutes"])
            assertEquals("DARK", result.preferences["theme_mode"])
            assertEquals(if (version < 3) "GREEN" else "PURPLE", result.preferences["theme_color"])
            CompleteBackupArchive.defaultPersonalizationPreferences.forEach { (key, value) -> assertEquals(value, result.preferences[key]) }
        }
    }

    @Test fun versionFourKeepsCustomFocusChoicesAndAddsDefaultStyleAndHome() {
        val result = read(archive(source("version4", versionFour), 4), "result4")
        assertEquals(4, result.formatVersion)
        assertEquals(versionFour + CompleteBackupArchive.defaultPersonalizationPreferences, result.preferences)
    }

    @Test fun legacySchemaCannotSmuggleNewPreferences() {
        for (version in 1..3) {
            val choices = if (version < 3) versionFour - "theme_color" else versionFour
            reject { read(archive(source("v$version", choices), version), "bad$version") }
            assertFalse(File(temp.root, "bad$version").exists())
        }
    }

    @Test fun malformedFavoritesAreRejectedBeforeRestoreWithVerifiedChecksums() {
        listOf<Any?>(null, "", "0", "1441", "40,40", "50,40", "040", "40, 50", "40,", "-1", "1.5",
            "1,2,3,4,5,6,7,8,9", "9".repeat(100), listOf(40,50), true).forEachIndexed { index, invalid ->
            reject { read(archive(source("source$index", current + ("focus_favorites" to invalid))), "bad$index") }
            assertFalse(File(temp.root, "bad$index").exists())
        }
    }

    @Test fun missingOrInvalidManualMemoryCannotPartiallyApplyPreferences() {
        val invalid = listOf(current - "focus_favorites", current - "focus_last_minutes") +
            listOf<Any?>(null, 0, 1441, "50", 50.5, true).map { current + ("focus_last_minutes" to it) }
        invalid.forEachIndexed { index, choices ->
            reject { read(archive(source("source$index", choices)), "bad$index") }
        }
        assertEquals(current, CompleteBackupArchive.validatePreferences(current))
        assertEquals(old, CompleteBackupArchive.validatePreferences(old))
    }

    @Test fun interruptedRestoreRollsBackCustomDurationsAndOriginalData() {
        val target = temp.newFolder("target")
        val originalData = AppData(nickname = "恢复前")
        AppStore(target).save(originalData)
        val incoming = current + mapOf("focus_favorites" to "25,60", "focus_last_minutes" to 60L)
        var actual = current
        try {
            CompleteBackupTransaction(target).restore(source("source", incoming), current, incoming, { actual = it },
                { if (it == "preferences") throw ProcessDeath() })
            fail("Expected interruption")
        } catch (_: ProcessDeath) {}
        assertEquals(incoming, actual)
        CompleteBackupTransaction(target).recover { actual = it }
        assertEquals(current, actual)
        assertEquals(originalData, AppStore(target).load())
    }
    private class ProcessDeath : Error()
}
