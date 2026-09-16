package com.tongpin.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThemeBackupTest {
    @get:Rule val temp = TemporaryFolder()
    private val legacyPreferences: Map<String, Any?> = linkedMapOf(
        "enabled" to true, "sound" to true, "vibrate" to false,
        "sound_id" to "wood", "compatible_sound" to true,
    )
    private val colors = listOf("GREEN", "BLUE", "PURPLE", "ROSE", "ORANGE", "GRAPHITE")
    private fun preferences(color: String = "GREEN", mode: String = "SYSTEM") =
        legacyPreferences + mapOf("theme_color" to color, "theme_mode" to mode) + CompleteBackupArchive.defaultFocusPreferences + CompleteBackupArchive.defaultPersonalizationPreferences
    private fun source(name: String, choices: Map<String, Any?>) = temp.newFolder(name).apply {
        File(this, "current.json").writeText(DataCodec.encode(AppData(nickname = "配色备份")))
        File(this, "settings.json").writeText(DataCodec.writeJsonObject(choices))
    }
    private fun archive(directory: File) = ByteArrayOutputStream().also {
        CompleteBackupArchive.write(directory, it, 100)
    }.toByteArray()
    private fun read(bytes: ByteArray, name: String) =
        CompleteBackupArchive.read(bytes.inputStream(), File(temp.root, name))
    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected invalid backup to be rejected") }
        catch (_: IllegalArgumentException) {}
        catch (_: IOException) {}
    }

    @Test fun currentVersionRoundTripsEveryColorWithEveryDisplayMode() {
        colors.forEach { color -> listOf("SYSTEM", "LIGHT", "DARK").forEach { mode ->
            val choices = preferences(color, mode)
            val restored = read(archive(source("$color-$mode", choices)), "result-$color-$mode")
            assertEquals(5, restored.formatVersion)
            assertEquals(choices, restored.preferences)
            assertEquals("配色备份", restored.data.nickname)
        } }
    }

    @Test fun currentFormatStillIncludesAutomaticSnapshotsAndAppearanceTogether() {
        val directory = source("source", preferences("PURPLE", "DARK"))
        val snapshotData = AppData(nickname = "昨天", plans = listOf(Plan(id = "reading", title = "阅读",
            category = Category.STUDY, target = 1, unit = "次", tracking = TrackingMode.TASK, startDate = "2026-09-01")))
        AppStore(directory).save(snapshotData)
        val snapshot = AutoSnapshotStore(directory).checkpoint(java.time.LocalDate.of(2026, 9, 16), 100)!!
        File(directory, "tongpin-data.json").delete()
        val restored = read(archive(directory), "restored")
        assertEquals(5, restored.formatVersion)
        assertEquals(1, restored.summary.autoSnapshots)
        assertEquals(snapshot, SnapshotStore(File(restored.directory, "auto_snapshots")).list().single())
        assertEquals(snapshotData, SnapshotStore(File(restored.directory, "auto_snapshots")).read(snapshot.id))
        assertEquals("PURPLE", restored.preferences["theme_color"])
        assertEquals("DARK", restored.preferences["theme_mode"])
    }

    @Test fun legacyVersionsUseGreenAndCanBeExportedIntoTheCurrentFormat() {
        for (version in 1..2) {
            val old = if (version == 1) legacyPreferences - "compatible_sound"
                else legacyPreferences + ("theme_mode" to "DARK")
            val restored = read(legacyArchive(source("v$version", old), version), "read-v$version")
            assertEquals(old + ("theme_color" to "GREEN") + CompleteBackupArchive.defaultFocusPreferences + CompleteBackupArchive.defaultPersonalizationPreferences, restored.preferences)
            assertEquals(version, restored.formatVersion)
            File(restored.directory, "settings.json").writeText(DataCodec.writeJsonObject(restored.preferences))
            val upgraded = read(archive(restored.directory), "upgraded-v$version")
            assertEquals(5, upgraded.formatVersion)
            assertEquals(restored.preferences, upgraded.preferences)
            assertEquals(restored.data, upgraded.data)
        }
    }

    @Test fun currentVersionRequiresAValidColorAndRejectsUnknownPreferenceFields() {
        val invalid = listOf(
            preferences() - "theme_color", preferences() + ("theme_color" to null),
            preferences() + ("theme_color" to 1), preferences() + ("theme_color" to true),
            preferences() + ("theme_color" to "blue"), preferences() + ("theme_color" to "CUSTOM"),
            preferences() + ("theme_color" to " BLUE"), preferences() + ("appearance_runtime" to "preview"),
        )
        invalid.forEachIndexed { index, value ->
            reject { read(archive(source("invalid-$index", value)), "rejected-$index") }
            assertFalse(File(temp.root, "rejected-$index").exists())
        }
    }

    @Test fun olderSchemasRejectColorInsteadOfSilentlyAcceptingNewFields() {
        for (version in 1..2) {
            reject { read(legacyArchive(source("v$version", preferences("ROSE")), version), "rejected-v$version") }
        }
    }

    @Test fun failedAppearanceWriteRestoresOldColorModeAndReminderPreferences() {
        val original = preferences("BLUE", "LIGHT")
        val incoming = preferences("ROSE", "DARK") + mapOf("sound" to false, "focus_favorites" to "40,90", "focus_last_minutes" to 90L)
        val target = temp.newFolder("target")
        val originalData = AppData(nickname = "原来的数据")
        AppStore(target).save(originalData)
        var actual = original
        var writes = 0
        reject {
            CompleteBackupTransaction(target).restore(source("source", incoming), original, incoming, { value ->
                writes++
                // Reproduce the first preference file succeeding before the appearance file fails.
                actual = value.filterKeys { it !in CompleteBackupArchive.uiPreferenceKeys } +
                    actual.filterKeys { it in CompleteBackupArchive.uiPreferenceKeys }
                if (writes == 1) throw IOException("appearance storage unavailable")
                actual = value
            })
        }
        assertEquals(2, writes)
        assertEquals(original, actual)
        assertEquals(originalData, AppStore(target).load())
        assertFalse(File(target, "complete-restore").exists())
    }

    @Test fun interruptedRestoreReplaysTheOriginalColorAlongWithTheOriginalData() {
        val original = preferences("GRAPHITE", "DARK")
        val incoming = preferences("ORANGE", "LIGHT")
        val target = temp.newFolder("target")
        val originalData = AppData(nickname = "崩溃前")
        AppStore(target).save(originalData)
        var actual = original
        try {
            CompleteBackupTransaction(target).restore(source("source", incoming), original, incoming,
                { actual = it }, { if (it == "preferences") throw ProcessDeath() })
            fail("Expected process death")
        } catch (_: ProcessDeath) {}
        assertEquals(incoming, actual)
        CompleteBackupTransaction(target).recover { actual = it }
        assertEquals(original, actual)
        assertEquals(originalData, AppStore(target).load())
    }

    @Test fun recoveryStillAcceptsJournalsCreatedBeforeColorExisted() {
        val target = temp.newFolder("target")
        val journal = File(target, "complete-restore").apply { mkdirs() }
        File(journal, "journal.json").writeText(DataCodec.writeJsonObject(mapOf(
            "version" to 1, "original" to emptyList<String>(), "preferences" to legacyPreferences,
        )))
        var recovered: Map<String, Any?>? = null
        CompleteBackupTransaction(target).recover { recovered = it }
        assertEquals(legacyPreferences, recovered)
        assertFalse(journal.exists())
    }

    private class ProcessDeath : Error()

    private fun legacyArchive(directory: File, version: Int): ByteArray {
        val files = directory.walkTopDown().filter { it.isFile }.toList()
        val rows = files.map { file -> mapOf("path" to file.relativeTo(directory).invariantSeparatorsPath,
            "size" to file.length(), "sha256" to CompleteBackupArchive.digest(file)) }
        val manifest = DataCodec.writeJsonObject(mapOf("format" to "plan-complete", "version" to version,
            "createdAt" to 100, "files" to rows))
        return ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use { zip ->
            fun entry(name: String, value: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry()
            }
            entry("manifest.json", manifest.toByteArray())
            files.forEach { entry(it.relativeTo(directory).invariantSeparatorsPath, it.readBytes()) }
        } }.toByteArray()
    }
}
