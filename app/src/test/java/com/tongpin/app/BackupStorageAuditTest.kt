package com.tongpin.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupStorageAuditTest {
    @get:Rule val temp = TemporaryFolder()
    private val day = LocalDate.of(2026, 9, 16)
    private val reading = Plan(id = "reading", title = "阅读", category = Category.STUDY,
        target = 20, unit = "页", startDate = "2026-09-01")
    private val preferences: Map<String, Any?> = mapOf("enabled" to true, "sound" to true,
        "vibrate" to false, "sound_id" to "chime", "compatible_sound" to true,
        "theme_mode" to "LIGHT", "theme_color" to "GREEN") +
        CompleteBackupArchive.defaultFocusPreferences + CompleteBackupArchive.defaultPersonalizationPreferences

    private fun planBundle(data: AppData) = TransferCodec.decode(TransferCodec.encode(data, TransferScope.PLANS))
    private fun importPlans(current: AppData, incoming: AppData) = mergeTransfer(current, planBundle(incoming), TransferScope.PLANS)

    @Test fun revisedPlanConflictSkipsItsWholeSeriesAndStillImportsAnUnrelatedTask() {
        val before = AppData(plans = listOf(reading))
        val revised = revisePlan(before, reading.id, reading.copy(target = 30), day)
        val unrelated = reading.copy(id = "exercise", seriesId = "exercise", title = "练习", unit = "题")
        val preview = importPlans(before, revised.copy(plans = revised.plans + unrelated))
        assertEquals(listOf(reading, unrelated), preview.result.plans)
        assertTrue(preview.summary.contains("略过 1 项任务的冲突安排"))
        assertEquals(preview.result, importPlans(preview.result, revised.copy(plans = revised.plans + unrelated)).result)
    }

    @Test fun missingEarlierRevisionCanBeAddedWhenItDoesNotOverlapLocalArrangements() {
        val current = reading.copy(startDate = "2026-09-10")
        val earlier = reading.copy(id = "early", archived = true, endDate = current.startDate)
        val local = AppData(plans = listOf(current), checkIns = listOf(CheckIn(current.id, day.toString(), 7, "本机进度", 100)))
        val incoming = AppData(plans = listOf(earlier, current))
        val merged = importPlans(local, incoming).result
        assertEquals(setOf(earlier, current), merged.plans.toSet())
        assertEquals(local.checkIns, merged.checkIns)
        assertEquals(1, merged.plans.count { isScheduled(it, day) })
        assertEquals(1, merged.plans.count { isScheduled(it, LocalDate.of(2026, 9, 9)) })
        assertEquals(merged, importPlans(merged, incoming).result)
    }

    @Test fun aDifferentArchivedVersionCannotIntroduceOverlappingHistoricalProgress() {
        val localVersion = reading.copy(archived = true, endDate = "2026-09-10")
        val conflictingHistory = reading.copy(id = "different-old", archived = true, startDate = "2026-09-05", endDate = "2026-09-12")
        val local = AppData(plans = listOf(localVersion))
        val result = importPlans(local, AppData(plans = listOf(conflictingHistory)))
        assertEquals(local, result.result)
        assertTrue(result.summary.contains("冲突安排"))
    }

    @Test fun anUnmatchedSuccessorCannotCreateTwoActiveVersionsEvenWithDifferentDateRanges() {
        val current = reading.copy(dueDate = "2026-09-15")
        val successor = reading.copy(id = "next", startDate = day.toString())
        val local = AppData(plans = listOf(current))
        val result = importPlans(local, AppData(plans = listOf(successor)))
        assertEquals(local, result.result)
    }

    @Test fun recordsFirstCanStillPromoteEveryVersionAndKeepDailyNotesAndOriginalUnits() {
        val before = AppData(plans = listOf(reading), checkIns = listOf(CheckIn(reading.id, "2026-09-15", 12, "旧目标心得", 100)))
        val revised = revisePlan(before, reading.id, reading.copy(target = 30), day)
        val current = revised.plans.single { !it.archived }
        val source = revised.copy(checkIns = revised.checkIns + CheckIn(current.id, day.toString(), 15, "新目标心得", 200),
            dailyNotes = listOf(DailyNote(day.toString(), "每日心得独立保存", 300)))
        val recordBundle = TransferCodec.decode(TransferCodec.encode(source, TransferScope.RECORDS))
        val records = mergeTransfer(AppData(), recordBundle, TransferScope.RECORDS).result
        assertTrue(records.plans.all { it.recordsOnly })
        val promoted = importPlans(records, source).result
        assertEquals(source.plans.toSet(), promoted.plans.toSet())
        assertEquals(source.checkIns, promoted.checkIns)
        assertEquals(source.dailyNotes, promoted.dailyNotes)
        assertEquals(1, promoted.plans.count { isScheduled(it, day) })
        assertEquals(promoted, importPlans(promoted, source).result)
    }

    @Test fun importTransactionKeepsAFocusCompletionThatArrivesDuringTheMerge() {
        val directory = temp.newFolder("atomic-import")
        val importer = AppStore(directory)
        val timer = AppStore(directory)
        val initial = AppData(plans = listOf(reading))
        importer.save(initial)
        val incoming = reading.copy(id = "math", seriesId = "math", title = "数学练习", unit = "题")
        val bundle = planBundle(AppData(plans = listOf(incoming)))
        val mergeStarted = CountDownLatch(1)
        val timerAttempted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val importing = pool.submit<AppData> {
                importer.restore { current ->
                    mergeStarted.countDown()
                    check(timerAttempted.await(5, TimeUnit.SECONDS))
                    mergeTransfer(current, bundle, TransferScope.PLANS).result
                }
            }
            val completion = pool.submit<AppData> {
                check(mergeStarted.await(5, TimeUnit.SECONDS))
                timerAttempted.countDown()
                timer.update { it.copy(focusRecords = it.focusRecords + FocusRecord("timer", reading.id, 1500, 100)) }
            }
            importing.get(5, TimeUnit.SECONDS)
            completion.get(5, TimeUnit.SECONDS)
            val saved = importer.load()
            assertEquals(setOf(reading, incoming), saved.plans.toSet())
            assertEquals(listOf(FocusRecord("timer", reading.id, 1500, 100)), saved.focusRecords)
            assertEquals(initial, importer.recoverPrevious())
            assertEquals(saved, importer.recoverPrevious())
        } finally { pool.shutdownNow() }
    }

    @Test fun transformFailureDoesNotReplaceDataOrItsExistingUndoCopy() {
        val store = AppStore(temp.newFolder("rejected-import"))
        val initial = AppData(plans = listOf(reading))
        val current = initial.copy(nickname = "当前资料")
        store.save(initial)
        store.restore(current)
        assertThrows(IllegalArgumentException::class.java) {
            store.restore { it.copy(nickname = "") }
        }
        assertEquals(current, store.load())
        assertEquals(initial, store.recoverPrevious())
    }

    @Test fun aFullZipCanRecoverAnUnreadableCurrentJsonAndBringBackItsNamedSnapshot() {
        val source = temp.newFolder("zip-source")
        val expected = AppData(plans = listOf(reading), dailyNotes = listOf(DailyNote(day.toString(), "备份中的每日心得", 100)))
        File(source, "current.json").writeText(DataCodec.encode(expected))
        File(source, "settings.json").writeText(DataCodec.writeJsonObject(preferences))
        val named = SnapshotStore(File(source, "snapshots")).create("九月积累", expected)
        val bytes = ByteArrayOutputStream().also { CompleteBackupArchive.write(source, it, 100) }.toByteArray()
        val prepared = CompleteBackupArchive.read(bytes.inputStream(), File(temp.root, "zip-staging"))
        val target = temp.newFolder("damaged-current")
        File(target, "tongpin-data.json").writeText("{broken")
        assertThrows(IOException::class.java) { AppStore(target).load() }
        var savedPreferences = preferences
        CompleteBackupTransaction(target).restore(prepared.directory, preferences, prepared.preferences, { savedPreferences = it })
        assertEquals(expected, AppStore(target).load())
        assertEquals(expected, SnapshotStore(File(target, "snapshots")).read(named.id))
        assertEquals(preferences, savedPreferences)
        assertTrue(target.listFiles().orEmpty().any { it.name.startsWith("tongpin-unreadable-") && it.readText() == "{broken" })
    }

    @Test fun failedFullRestorePreservesUnreadableOriginalBytesAndHealthyLocalSnapshots() {
        val target = temp.newFolder("damaged-rollback")
        val original = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x01)
        File(target, "tongpin-data.json").writeBytes(original)
        val expected = AppData(plans = listOf(reading))
        val local = SnapshotStore(File(target, "snapshots")).create("本机可恢复存档", expected)
        val source = temp.newFolder("rollback-source")
        File(source, "current.json").writeText(DataCodec.encode(AppData()))
        assertThrows(IOException::class.java) {
            CompleteBackupTransaction(target).restore(source, preferences, preferences, {}, { point ->
                if (point == "new:snapshots") throw IOException("simulated storage failure")
            })
        }
        assertArrayEquals(original, File(target, "tongpin-data.json").readBytes())
        assertEquals(expected, SnapshotStore(File(target, "snapshots")).read(local.id))
    }
}
