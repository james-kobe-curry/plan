package com.tongpin.app

import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompleteBackupTest {
    @get:Rule val temp = TemporaryFolder()
    private val preferences: Map<String, Any?> = linkedMapOf("enabled" to true, "sound" to true,
        "vibrate" to false, "sound_id" to "chime", "compatible_sound" to true,
        "theme_mode" to "SYSTEM", "theme_color" to "GREEN",
        "focus_favorites" to "15,25,45,60", "focus_last_minutes" to 25L) + CompleteBackupArchive.defaultPersonalizationPreferences
    private fun data(name: String = "阅读") = AppData(plans = listOf(Plan(id = "read", title = name,
        category = Category.STUDY, target = 1, unit = "次", tracking = TrackingMode.TASK, startDate = "2026-09-01")),
        checkIns = listOf(CheckIn("read", "2026-09-10", 1, "温故知新", 10)),
        focusRecords = listOf(FocusRecord("focus", "read", 300, 20)), nickname = "学习者")
    private fun content(name: String = "source", appData: AppData = data()): File = temp.newFolder(name).apply {
        File(this, "current.json").writeText(DataCodec.encode(appData))
        File(this, "settings.json").writeText(DataCodec.writeJsonObject(preferences))
    }
    private fun archive(source: File): ByteArray = ByteArrayOutputStream().also { CompleteBackupArchive.write(source, it, 100) }.toByteArray()
    private fun extract(bytes: ByteArray, name: String = "result") = CompleteBackupArchive.read(bytes.inputStream(), File(temp.root, name))
    private fun assertRejected(block: () -> Unit) { try { block(); fail("Should reject") } catch (_: IllegalArgumentException) {} catch (_: IOException) {} }

    @Test fun roundTripPreservesCurrentArchivedPlansEverySnapshotAudioAndPreferences() {
        val original = data().let { it.copy(plans = it.plans + it.plans.first().copy(id = "past", seriesId = "past",
            title = "以前的目标", archived = true, endDate = "2026-09-05")) }
        val source = content(appData = original)
        val snapshot = SnapshotStore(File(source, "snapshots")).create("第一阶段", original)
        val audio = addAudio(source)
        val selected = preferences + ("sound_id" to audio)
        File(source, "settings.json").writeText(DataCodec.writeJsonObject(selected))
        val restored = extract(archive(source))
        assertEquals(original, restored.data)
        assertEquals(selected, restored.preferences)
        assertEquals(snapshot, SnapshotStore(File(restored.directory, "snapshots")).list().single())
        assertEquals(original, SnapshotStore(File(restored.directory, "snapshots")).read(snapshot.id))
        assertEquals(1, restored.summary.snapshots); assertEquals(1, restored.summary.sounds)
        assertEquals(1, restored.summary.archived); assertEquals(100L, restored.summary.createdAt)
    }

    @Test fun unsupportedNamesNeverCreateFilesOutsideStaging() {
        for ((index, path) in listOf("../outside", "/absolute", "sounds/../outside", "C:/outside",
            "snapshots\\evil.snapshot", "focus_clock.xml", "shared_prefs/focus_reminders.xml", "sounds/a.json").withIndex()) {
            assertRejected { extract(maliciousArchive(path), "bad$index") }
            assertFalse(File(temp.root, "outside").exists()); assertFalse(File(temp.root, "bad$index").exists())
        }
    }

    @Test fun checksumMismatchRejectsWithoutLeavingPartialContent() {
        val source = content(); val bytes = alteredZip(source, mutate = { name, value -> if (name == "current.json") value.replaceFirst("阅读", "偷换").toByteArray() else value.toByteArray() })
        assertRejected { extract(bytes) }; assertFalse(File(temp.root, "result").exists())
    }

    @Test fun actualStreamCannotExceedDeclaredEntrySize() {
        val source = content()
        val bytes = alteredZip(source, mutate = { name, value -> if (name == "current.json") (value + "x".repeat(20000)).toByteArray() else value.toByteArray() })
        assertRejected { extract(bytes) }
    }

    @Test fun missingRequiredFileRejects() { assertRejected { extract(alteredZip(content(), omit = "settings.json")) } }

    @Test fun unlistedFileRejects() {
        val source = content(); val bytes = alteredZip(source, extra = "unlisted" to "secret".toByteArray())
        assertRejected { extract(bytes) }
    }

    @Test fun duplicateManifestPathsReject() {
        val source = content()
        val bytes = alteredZip(source, transformManifest = { it + ("files" to ((it["files"] as List<*>) + (it["files"] as List<*>).first())) })
        assertRejected { extract(bytes) }
    }

    @Test fun impossibleDeclaredSizeRejectsBeforeCreatingLargeFile() {
        val source = content()
        val bytes = alteredZip(source, transformManifest = { manifest -> manifest + ("files" to (manifest["files"] as List<*>).map { value ->
            @Suppress("UNCHECKED_CAST") val row = value as Map<String, Any?>
            if (row["path"] == "current.json") row + ("size" to Long.MAX_VALUE) else row
        }) })
        assertRejected { extract(bytes) }
    }

    @Test fun futureFormatAndWrongManifestAreRejected() {
        assertRejected { extract(alteredZip(content(), transformManifest = { it + ("version" to 9) })) }
        val bytes = ByteArrayOutputStream(); ZipOutputStream(bytes).use { zip -> zip.putNextEntry(ZipEntry("current.json")); zip.write("{}".toByteArray()); zip.closeEntry() }
        assertRejected { extract(bytes.toByteArray(), "second") }
    }

    @Test fun appDataAndSnapshotPayloadsAreValidatedEvenWithCorrectZipHashes() {
        val source = content(); File(source, "current.json").writeText("{}")
        assertRejected { extract(archive(source)) }
        File(source, "current.json").writeText(DataCodec.encode(data()))
        val id = SnapshotStore(File(source, "snapshots")).create("阶段", data()).id
        File(source, "snapshots/$id.snapshot").appendText("broken")
        assertRejected { extract(archive(source), "second") }
    }

    @Test fun audioContentHashAndMetadataMustMatch() {
        val source = content(); val id = addAudio(source)
        File(source, "sounds/$id.wav").writeBytes(byteArrayOf(9, 8, 7, 6))
        assertRejected { extract(archive(source)) }
    }

    @Test fun orphanAudioAndMissingSelectedAudioReject() {
        val source = content(); val id = addAudio(source)
        File(source, "sounds/$id.json").delete()
        assertRejected { extract(archive(source)) }
        File(source, "sounds/$id.wav").delete()
        File(source, "settings.json").writeText(DataCodec.writeJsonObject(preferences + ("sound_id" to id)))
        assertRejected { extract(archive(source), "second") }
    }

    @Test fun decoderValidationRunsForEveryImportedSoundAndCanReject() {
        val source = content(); addAudio(source); var calls = 0
        assertRejected { CompleteBackupArchive.read(archive(source).inputStream(), File(temp.root, "result")) { _, duration ->
            calls++; assertEquals(1000L, duration); throw IllegalArgumentException("unsupported codec")
        } }; assertEquals(1, calls)
    }

    @Test fun runtimeAndUnknownPreferencesCannotBeImported() {
        assertRejected { CompleteBackupArchive.validatePreferences(preferences + ("focus_clock" to "running")) }
        assertRejected { CompleteBackupArchive.validatePreferences(preferences + ("enabled" to "yes")) }
        assertRejected { CompleteBackupArchive.validatePreferences(preferences + ("compatible_sound" to "direct")) }
    }

    @Test fun replacementCommitsAllPartsAndRemovesStaleClassifiedUndo() {
        val target = currentFiles(); val source = content(); addAudio(source)
        SnapshotStore(File(source, "snapshots")).create("新存档", data())
        var prefs = preferences
        CompleteBackupTransaction(target).restore(source, prefs, preferences + ("sound" to false), { prefs = it })
        assertEquals(data(), AppStore(target).load())
        assertEquals("新存档", SnapshotStore(File(target, "snapshots")).list().single().name)
        assertFalse(File(target, "reminder_sounds/old.txt").exists())
        assertEquals(2, File(target, "reminder_sounds").listFiles()!!.size)
        assertFalse(File(target, "tongpin-before-restore.json").exists()); assertFalse(File(target, "complete-restore").exists())
        assertEquals(false, prefs["sound"])
    }

    @Test fun failureAtEveryReplacementStepRollsBackAllFilesAndPreferences() {
        val points = listOf("old:tongpin-data.json", "new:tongpin-data.json", "new:tongpin-before-restore.json",
            "old:snapshots", "new:snapshots", "old:reminder_sounds", "new:reminder_sounds", "preferences")
        points.forEachIndexed { index, point ->
            val target = currentFiles("current$index"); val before = fileBytes(target); var prefs = preferences
            val source = content("incoming$index")
            assertRejected { CompleteBackupTransaction(target).restore(source, prefs, preferences + ("sound" to false), { prefs = it }, {
                if (it == point) throw IOException("simulated failure")
            }) }
            assertEquals(before.keys, fileBytes(target).keys)
            before.forEach { (name, bytes) -> assertArrayEquals(name, bytes, fileBytes(target)[name]) }
            assertEquals(preferences, prefs)
        }
    }

    @Test fun preferencesFailureAlsoRollsBackReplacedDataAndDirectories() {
        val target = currentFiles(); val before = fileBytes(target); var calls = 0
        assertRejected { CompleteBackupTransaction(target).restore(content(), preferences, preferences + ("sound" to false), {
            if (++calls == 1) throw IOException("settings disk failure")
        }) }
        assertEquals(2, calls); before.forEach { (name, bytes) -> assertArrayEquals(bytes, fileBytes(target)[name]) }
    }

    @Test fun interruptedProcessRecoversOnNextStartupIncludingPreferences() {
        val target = currentFiles(); val before = fileBytes(target); var prefs = preferences
        try { CompleteBackupTransaction(target).restore(content(), prefs, preferences + ("sound" to false), { prefs = it }, {
            if (it == "preferences") throw SimulatedProcessDeath()
        }); fail("must simulate process death") } catch (_: SimulatedProcessDeath) {}
        assertEquals(false, prefs["sound"])
        CompleteBackupTransaction(target).recover { prefs = it }
        assertEquals(preferences, prefs); before.forEach { (name, bytes) -> assertArrayEquals(bytes, fileBytes(target)[name]) }
    }

    @Test fun rollbackCanBeRetriedAfterAnotherFailureWithoutLosingOriginals() {
        val target = currentFiles(); val before = fileBytes(target)
        try { CompleteBackupTransaction(target).restore(content(), preferences, preferences + ("sound" to false), {}, {
            if (it == "new:reminder_sounds") throw SimulatedProcessDeath()
        }) } catch (_: SimulatedProcessDeath) {}
        assertRejected { CompleteBackupTransaction(target).recover { throw IOException("try later") } }
        assertTrue(File(target, "complete-restore/old/tongpin-data.json").exists())
        CompleteBackupTransaction(target).recover {}
        before.forEach { (name, bytes) -> assertArrayEquals(bytes, fileBytes(target)[name]) }
    }

    @Test fun restoringOverEmptyInstallationRollsBackToEmptyAfterFailure() {
        val target = temp.newFolder("empty")
        assertRejected { CompleteBackupTransaction(target).restore(content(), preferences, preferences, {}, {
            if (it == "new:reminder_sounds") throw IOException("simulated")
        }) }
        assertTrue(target.listFiles().orEmpty().isEmpty())
    }

    @Test fun completedRollbackNeverReplaysPartiallyDeletedSafetyFiles() {
        val target = currentFiles(); val before = fileBytes(target)
        val journal = File(target, "complete-restore").apply { mkdirs() }
        File(journal, "journal.json").writeText(DataCodec.writeJsonObject(linkedMapOf("version" to 1,
            "original" to listOf("tongpin-data.json", "snapshots", "reminder_sounds"), "preferences" to preferences)))
        File(journal, "committed").writeText("PLAN-COMMITTED-1")
        // Simulate a previous cleanup that has already lost part of its obsolete copy.
        File(journal, "old/snapshots").mkdirs()
        File(journal, "old/tongpin-data.json").writeText(DataCodec.encode(data("旧的残片")))
        CompleteBackupTransaction(target).recover { fail("Completed rollback must not replay preferences") }
        before.forEach { (name, bytes) -> assertArrayEquals(bytes, fileBytes(target)[name]) }
        assertEquals(before.keys, fileBytes(target).keys)
    }

    @Test fun interruptedRetiredDirectoryCleanupDoesNotChangeCommittedData() {
        val target = currentFiles(); val before = fileBytes(target)
        val retired = File(target, "complete-restore-cleanup-abandoned/old/snapshots").apply { mkdirs() }
        File(retired, "incomplete.snapshot").writeText("obsolete partial copy")
        CompleteBackupTransaction(target).recover { fail("Retired directory is never replayed") }
        before.forEach { (name, bytes) -> assertArrayEquals(bytes, fileBytes(target)[name]) }
        assertEquals(before.keys, fileBytes(target).keys)
    }

    @Test fun closingPreviewDuringIoDefersCleanupUntilTheReaderFinishes() {
        val workspace = temp.newFolder("preview")
        val directory = File(workspace, "content").apply { mkdirs() }
        File(directory, "marker").writeText("safe")
        val prepared = PreparedCompleteBackup(CompleteBackupContent(directory, data(), preferences,
            CompleteBackupSummary(1, 1, 0, 1, 1, 0, 0, 4)))
        val started = CountDownLatch(1); val finish = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val work = executor.submit {
                prepared.access { started.countDown(); check(finish.await(5, TimeUnit.SECONDS)); assertEquals("safe", File(directory, "marker").readText()) }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            prepared.close()
            assertTrue(directory.exists())
            finish.countDown(); work.get(5, TimeUnit.SECONDS)
            assertFalse(workspace.exists())
            try { prepared.access { fail("Closed handle cannot start more IO") }; fail("Expected closed handle") }
            catch (_: IllegalStateException) {}
        } finally { finish.countDown(); executor.shutdownNow() }
    }

    @Test fun legacyVersionTwoPreservesAutomaticSnapshotsAndThemeWithDefaultGreen() {
        val source=content()
        AppStore(source).save(data())
        val auto=AutoSnapshotStore(source)
        val daily=auto.checkpoint(LocalDate.of(2026,9,16),100)!!
        File(source,"tongpin-data.json").delete()
        File(source,"settings.json").writeText(DataCodec.writeJsonObject(preferences.filterKeys { it !in CompleteBackupArchive.uiPreferenceKeys }+("theme_mode" to "DARK")))
        val restored=extract(alteredZip(source, formatVersion = 2))
        assertEquals(2,restored.formatVersion); assertEquals(1,restored.summary.autoSnapshots)
        assertEquals("DARK",restored.preferences["theme_mode"])
        assertEquals("GREEN",restored.preferences["theme_color"])
        assertEquals(daily,SnapshotStore(File(restored.directory,"auto_snapshots")).list().single())
        assertEquals(data(),SnapshotStore(File(restored.directory,"auto_snapshots")).read(daily.id))
    }

    @Test fun legacyVersionOneWithoutAutomaticSnapshotsOrThemeStillImports() {
        val source = content()
        File(source, "settings.json").writeText(DataCodec.writeJsonObject(preferences.filterKeys { it !in CompleteBackupArchive.uiPreferenceKeys }))
        val restored=extract(alteredZip(source, formatVersion = 1))
        assertEquals(1,restored.formatVersion); assertEquals(0,restored.summary.autoSnapshots)
        assertNull(restored.preferences["theme_mode"]); assertEquals("GREEN", restored.preferences["theme_color"])
        assertEquals(data(),restored.data)
    }

    @Test fun versionOneCannotSmuggleAutomaticFilesAndInvalidThemeIsRejected() {
        val source=content(); AppStore(source).save(data())
        AutoSnapshotStore(source).checkpoint(LocalDate.of(2026,9,16),100)
        File(source,"tongpin-data.json").delete()
        assertRejected { extract(alteredZip(source, formatVersion = 1)) }
        assertRejected { CompleteBackupArchive.validatePreferences(preferences+("theme_mode" to "BLUE")) }
    }

    @Test fun automaticSnapshotReplacementFailureRestoresAllOriginalCopies() {
        val target=currentFiles(); val auto=AutoSnapshotStore(target)
        val day=LocalDate.of(2026,9,16); val original=auto.checkpoint(day,100)!!
        val before=fileBytes(target); val source=content()
        AppStore(source).save(data()); AutoSnapshotStore(source).checkpoint(day.plusDays(1),200)
        File(source,"tongpin-data.json").delete()
        assertRejected { CompleteBackupTransaction(target).restore(source,preferences,preferences,{}, {
            if(it=="new:auto_snapshots")throw IOException("auto replacement failed")
        }) }
        before.forEach{(name,bytes)->assertArrayEquals(bytes,fileBytes(target)[name])}
        assertEquals(listOf(original),auto.list())
    }

    @Test fun crossPreferenceStoreFailureRollsBackReminderAndThemeSettings() {
        val target=currentFiles(); val before=fileBytes(target)
        val original=preferences+("theme_mode" to "LIGHT")
        val incoming=preferences+("sound" to false)+("theme_mode" to "DARK")
        var reminders=original.filterKeys{it !in CompleteBackupArchive.uiPreferenceKeys}; var theme:Any?="LIGHT"; var first=true
        assertRejected { CompleteBackupTransaction(target).restore(content(),original,incoming,{values->
            reminders=values.filterKeys{it !in CompleteBackupArchive.uiPreferenceKeys}
            if(first){first=false;throw IOException("second preference file failed")}
            theme=values["theme_mode"]
        }) }
        assertEquals(preferences.filterKeys { it !in CompleteBackupArchive.uiPreferenceKeys },reminders); assertEquals("LIGHT",theme)
        before.forEach{(name,bytes)->assertArrayEquals(bytes,fileBytes(target)[name])}
    }

    private class SimulatedProcessDeath : Error()
    private fun currentFiles(name: String = "current"): File = temp.newFolder(name).apply {
        AppStore(this).save(data("原有阅读")); File(this, "tongpin-before-restore.json").writeText(DataCodec.encode(data("上次安全副本")))
        SnapshotStore(File(this, "snapshots")).create("原有存档", data("原有阅读"))
        File(this, "reminder_sounds").mkdirs(); File(this, "reminder_sounds/old.txt").writeText("original audio bytes")
    }
    private fun fileBytes(directory: File) = directory.walkTopDown().filter { it.isFile }.associate { it.relativeTo(directory).invariantSeparatorsPath to it.readBytes() }
    private fun addAudio(directory: File): String {
        val sounds = File(directory, "sounds").apply { mkdirs() }
        val file = File(sounds, "temp").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val id = "custom_${CompleteBackupArchive.digest(file)}"; file.renameTo(File(sounds, "$id.wav"))
        File(sounds, "$id.json").writeText(DataCodec.writeJsonObject(linkedMapOf("version" to 1, "id" to id,
            "name" to "测试音频", "durationMs" to 1000, "extension" to "wav", "bytes" to 4)))
        return id
    }
    private fun maliciousArchive(path: String): ByteArray {
        val source = content("source-${System.nanoTime()}")
        return alteredZip(source, transformManifest = { manifest -> manifest + ("files" to (manifest["files"] as List<*>).mapIndexed { index, row ->
            @Suppress("UNCHECKED_CAST") if (index == 0) (row as Map<String, Any?>) + ("path" to path) else row
        }) })
    }
    private fun alteredZip(source: File, omit: String? = null, extra: Pair<String, ByteArray>? = null, formatVersion: Int = 5,
        mutate: ((String, String) -> ByteArray)? = null,
        transformManifest: (Map<String, Any?>) -> Map<String, Any?> = { it }): ByteArray {
        val rows = source.walkTopDown().filter { it.isFile }.map { file ->
            linkedMapOf("path" to file.relativeTo(source).invariantSeparatorsPath, "size" to file.length(), "sha256" to CompleteBackupArchive.digest(file))
        }.toList()
        val manifest = transformManifest(linkedMapOf("format" to "plan-complete", "version" to formatVersion, "createdAt" to 100L, "files" to rows))
        return ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use { zip ->
            fun entry(name: String, value: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry() }
            entry("manifest.json", DataCodec.writeJsonObject(manifest).toByteArray())
            rows.forEach { row ->
                val name = row["path"] as String
                if (name != omit) {
                    val file = File(source, name)
                    // Snapshots are binary; preserve bytes unless a test explicitly mutates text.
                    entry(name, mutate?.invoke(name, file.readText()) ?: file.readBytes())
                }
            }
            extra?.let { entry(it.first, it.second) }
        } }.toByteArray()
    }
}
