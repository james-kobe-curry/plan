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

class PersonalizationBackupTest {
    @get:Rule val temp = TemporaryFolder()
    private val legacy = mapOf<String, Any?>("enabled" to true, "sound" to true, "vibrate" to false,
        "sound_id" to "wood", "compatible_sound" to true, "theme_mode" to "DARK")
    private val versionFour = legacy + mapOf("theme_color" to "PURPLE", "focus_favorites" to "40,50,90", "focus_last_minutes" to 50L)
    private val current = versionFour + CompleteBackupArchive.defaultPersonalizationPreferences
    private val incoming = current + mapOf("theme_style" to "PAPER", "home_order" to "TASKS,RECENT,PROGRESS",
        "home_show_progress" to false, "home_show_recent" to true, "home_density" to "COMPACT", "start_page" to 2L)
    // A valid 1 x 1 PNG; stored image data must travel with profile data and its safety copies.
    private val avatar = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="

    private fun data(name: String = "原来的空间") = AppData(
        nickname = name,
        categories = listOf(CustomCategory("language", "英语", "LANGUAGE", "BLUE")),
        profile = PersonalProfile("每天向前一步：$name", "LEAF", avatar, showOnHome = false),
        plans = listOf(
            Plan(id = "read", title = "阅读", category = Category.STUDY, target = 1, unit = "次",
                tracking = TrackingMode.TASK, startDate = "2026-09-01", customCategoryId = "language", iconId = "EDIT"),
            Plan(id = "past", title = "暑期词汇", category = Category.STUDY, target = 20, unit = "词",
                startDate = "2026-08-01", endDate = "2026-09-05", archived = true, customCategoryId = "language", iconId = "LANGUAGE"),
        ),
        checkIns = listOf(CheckIn("past", "2026-09-03", 20, "记住这个阶段", 100)),
        focusRecords = listOf(FocusRecord("focus", "read", 1200, 200)),
        dailyNotes = listOf(DailyNote("2026-09-03", "这一阶段的独立心得：$name", 300)),
    )
    private fun source(name: String, choices: Map<String, Any?>, appData: AppData = data()) = temp.newFolder(name).apply {
        File(this, "current.json").writeText(DataCodec.encode(appData))
        File(this, "settings.json").writeText(DataCodec.writeJsonObject(choices))
    }
    private fun archive(folder: File, version: Int = 5): ByteArray {
        val bytes = ByteArrayOutputStream()
        if (version == 5) CompleteBackupArchive.write(folder, bytes, 100)
        else ZipOutputStream(bytes).use { zip ->
            val files = folder.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
            val manifest = mapOf("format" to "plan-complete", "version" to version, "createdAt" to 100L,
                "files" to files.map { mapOf("path" to it.relativeTo(folder).invariantSeparatorsPath,
                    "size" to it.length(), "sha256" to CompleteBackupArchive.digest(it)) })
            fun entry(name: String, content: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() }
            entry("manifest.json", DataCodec.writeJsonObject(manifest).toByteArray())
            files.forEach { entry(it.relativeTo(folder).invariantSeparatorsPath, it.readBytes()) }
        }
        return bytes.toByteArray()
    }
    private fun read(bytes: ByteArray, name: String) = CompleteBackupArchive.read(bytes.inputStream(), File(temp.root, name))
    private fun reject(block: () -> Unit) {
        try { block(); fail("Invalid personalization backup accepted") }
        catch (_: IllegalArgumentException) {}
        catch (_: IOException) {}
    }
    private fun oldChoices(version: Int): Map<String, Any?> = when (version) {
        1, 2 -> legacy
        3 -> legacy + ("theme_color" to "PURPLE")
        4 -> versionFour
        else -> error("unsupported fixture version")
    }

    @Test fun currentZipKeepsEveryStyleHomeLayoutProfileCategoriesAndArchivedDetails() {
        listOf("CLEAN", "PAPER", "SOFT").forEach { style ->
            val choices = incoming + ("theme_style" to style)
            val original = data()
            val directory = source("source-$style", choices, original)
            val snapshot = SnapshotStore(File(directory, "snapshots")).create("属于我的阶段", original)
            val result = read(archive(directory), "result-$style")
            assertEquals(5, result.formatVersion)
            assertEquals(choices, result.preferences)
            assertEquals(original, result.data)
            assertEquals(1, result.summary.plans)
            assertEquals(1, result.summary.archived)
            assertEquals(1, result.summary.snapshots)
            assertEquals(1, result.summary.dailyNotes)
            assertEquals(original, SnapshotStore(File(result.directory, "snapshots")).read(snapshot.id))
            assertEquals(avatar, result.data.profile.avatarImage)
        }
    }

    @Test fun everyLegacyZipGetsSoftHomeDefaultsWithoutLosingExistingChoices() {
        for (version in 1..4) {
            val old = oldChoices(version)
            val expected = old +
                (if (version < 3) mapOf("theme_color" to "GREEN") else emptyMap()) +
                (if (version < 4) CompleteBackupArchive.defaultFocusPreferences else emptyMap()) +
                CompleteBackupArchive.defaultPersonalizationPreferences
            val result = read(archive(source("source-$version", old, AppData(nickname = "以前的计划")), version), "result-$version")
            assertEquals(version, result.formatVersion)
            assertEquals(expected, result.preferences)
            assertEquals(PersonalProfile(), result.data.profile)
            assertTrue(result.data.categories.isEmpty())
            File(result.directory, "settings.json").writeText(DataCodec.writeJsonObject(result.preferences))
            val upgraded = read(archive(result.directory), "upgraded-$version")
            assertEquals(5, upgraded.formatVersion)
            assertEquals(expected, upgraded.preferences)
            assertEquals(result.data, upgraded.data)
        }
    }

    @Test fun legacySchemasRejectStyleAndHomeKeysEvenWhenValuesAreValid() {
        for (version in 1..4) {
            listOf(mapOf<String, Any?>("theme_style" to "CLEAN"), mapOf("home_order" to "PROGRESS,TASKS,RECENT"))
                .forEachIndexed { index, added ->
                    reject { read(archive(source("source-$version-$index", oldChoices(version) + added), version), "bad-$version-$index") }
                    assertFalse(File(temp.root, "bad-$version-$index").exists())
                }
        }
    }

    @Test fun currentZipRejectsMissingStyleAndPartialHomeSettings() {
        val keys = CompleteBackupArchive.defaultPersonalizationPreferences.keys
        keys.forEachIndexed { index, key ->
            reject { read(archive(source("source-$index", current - key)), "bad-$index") }
            assertFalse(File(temp.root, "bad-$index").exists())
        }
        reject { CompleteBackupArchive.validatePreferences(current - keys, 5) }
    }

    @Test fun invalidStylesCannotReachTheRestoreStage() {
        listOf<Any?>(null, "", "paper", "CUSTOM", " PAPER", 1, true).forEachIndexed { index, style ->
            reject { read(archive(source("source-$index", current + ("theme_style" to style))), "bad-$index") }
            assertFalse(File(temp.root, "bad-$index").exists())
        }
    }

    @Test fun invalidHomeOrderVisibilityDensityAndStartPageAreRejected() {
        val invalid = listOf(
            "home_order" to null, "home_order" to "TASKS,PROGRESS", "home_order" to "TASKS,TASKS,RECENT",
            "home_order" to "TASKS,RECENT,OTHER", "home_order" to "TASKS, RECENT,PROGRESS", "home_order" to listOf("TASKS"),
            "home_show_progress" to "false", "home_show_progress" to null,
            "home_show_recent" to 1, "home_show_recent" to null,
            "home_density" to "compact", "home_density" to "OTHER", "home_density" to false,
            "start_page" to -1, "start_page" to 4, "start_page" to 1.5, "start_page" to "2", "start_page" to null,
        )
        invalid.forEachIndexed { index, invalidEntry ->
            reject { read(archive(source("source-$index", current + invalidEntry)), "bad-$index") }
            assertFalse(File(temp.root, "bad-$index").exists())
        }
    }

    @Test fun durableJournalsAllowAbsentNewGroupsButRejectPartialOrInvalidGroups() {
        assertEquals(versionFour, CompleteBackupArchive.validatePreferences(versionFour))
        assertEquals(current, CompleteBackupArchive.validatePreferences(current))
        reject { CompleteBackupArchive.validatePreferences(versionFour + ("home_order" to "TASKS,RECENT,PROGRESS")) }
        reject { CompleteBackupArchive.validatePreferences(versionFour + ("theme_style" to "UNKNOWN")) }
    }

    @Test fun failedReplacementRestoresProfileCategoriesSnapshotsAndEveryPreference() {
        listOf("new:tongpin-data.json", "new:snapshots", "preferences").forEachIndexed { index, point ->
            val target = temp.newFolder("target-$index")
            val originalData = data()
            AppStore(target).save(originalData)
            val snapshot = SnapshotStore(File(target, "snapshots")).create("恢复前", originalData)
            val originalBytes = File(target, "tongpin-data.json").readBytes()
            val incomingData = changedData()
            val directory = source("source-$index", incoming, incomingData)
            SnapshotStore(File(directory, "snapshots")).create("导入的阶段", incomingData)
            var actual = current
            reject { CompleteBackupTransaction(target).restore(directory, current, incoming, { actual = it }, {
                if (it == point) throw IOException("simulated failure after $point")
            }) }
            assertEquals(current, actual)
            assertEquals(originalData, AppStore(target).load())
            assertArrayEquals(originalBytes, File(target, "tongpin-data.json").readBytes())
            assertEquals(originalData, SnapshotStore(File(target, "snapshots")).read(snapshot.id))
            assertEquals(listOf(snapshot), SnapshotStore(File(target, "snapshots")).list())
            assertFalse(File(target, "complete-restore").exists())
        }
    }

    @Test fun interruptedRestoreReplaysTheWholePersonalSpaceOnNextStartup() {
        val target = temp.newFolder("target")
        val originalData = data()
        AppStore(target).save(originalData)
        val originalBytes = File(target, "tongpin-data.json").readBytes()
        val incomingData = changedData()
        var actual = current
        try {
            CompleteBackupTransaction(target).restore(source("source", incoming, incomingData), current, incoming,
                { actual = it }, { if (it == "preferences") throw ProcessDeath() })
            fail("Expected process death")
        } catch (_: ProcessDeath) {}
        assertEquals(incoming, actual)
        assertEquals(incomingData, AppStore(target).load())
        CompleteBackupTransaction(target).recover { actual = it }
        assertEquals(current, actual)
        assertEquals(originalData, AppStore(target).load())
        assertArrayEquals(originalBytes, File(target, "tongpin-data.json").readBytes())
        assertFalse(File(target, "complete-restore").exists())
    }

    @Test fun partialUiPreferenceWriteRollsBackStyleLayoutAndProfileTogether() {
        val target = temp.newFolder("target")
        val originalData = data()
        AppStore(target).save(originalData)
        var actual = current
        var writes = 0
        reject {
            CompleteBackupTransaction(target).restore(source("source", incoming, changedData()), current, incoming, { values ->
                writes++
                actual = actual + ("theme_style" to values["theme_style"])
                if (writes == 1) throw IOException("home preference commit failed")
                actual = values
            })
        }
        assertEquals(2, writes)
        assertEquals(current, actual)
        assertEquals(originalData, AppStore(target).load())
    }

    private fun changedData() = data("另一个空间").copy(
        categories = listOf(CustomCategory("language", "考试复习", "SCHOOL", "SAND")),
        profile = PersonalProfile("新的开始", "SUN", avatarImage = null, showOnHome = true),
    )
    private class ProcessDeath : Error()
}
