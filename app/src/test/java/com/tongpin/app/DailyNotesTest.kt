package com.tongpin.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

class DailyNotesTest {
    @get:Rule val temp = TemporaryFolder()
    private val day = LocalDate.of(2026, 9, 14)
    private val note = DailyNote(day.toString(), "今天慢慢读完一章。\n明天继续。", 100)
    private val plan = Plan(id = "read", title = "阅读", category = Category.STUDY, target = 2, unit = "页", startDate = day.toString())
    private val history = AppData(plans = listOf(plan),
        checkIns = listOf(CheckIn(plan.id, day.toString(), 1, "任务心得继续保留", 20)),
        focusRecords = listOf(FocusRecord("focus", plan.id, 1500, 30)))
    private fun reject(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun dailyWritingIsOptionalAndCanExistWithoutAnyTask() {
        assertEquals(AppData(), setDailyNote(AppData(), day, ""))
        val data = setDailyNote(AppData(), day, note.text, note.updatedAt)
        assertTrue(data.plans.isEmpty())
        assertEquals(note, dailyNoteFor(data, day))
        assertNull(dailyNoteFor(data, day.plusDays(1)))
    }

    @Test fun savesOnlyOneEntryPerDayAndPreservesParagraphs() {
        val original = setDailyNote(history, day, "  ${note.text}  ", 100)
        assertEquals(listOf(note), original.dailyNotes)
        val second = setDailyNote(original, day, "修改后的心得\n第二段", 200)
        assertEquals(listOf(DailyNote(day.toString(), "修改后的心得\n第二段", 200)), second.dailyNotes)
        assertEquals(history.checkIns, second.checkIns)
        assertEquals(history.focusRecords, second.focusRecords)
        assertEquals(history.plans, second.plans)
    }

    @Test fun savingIdenticalTextDoesNotRewriteTimestamp() {
        val data = history.copy(dailyNotes = listOf(note))
        assertSame(data, setDailyNote(data, day, " ${note.text} ", 500))
    }

    @Test fun blankContentDeletesOnlyThisDailyNote() {
        val other = note.copy(date = day.plusDays(1).toString(), text = "另一天")
        val data = history.copy(dailyNotes = listOf(note, other))
        val deleted = setDailyNote(data, day, "\n \t ")
        assertEquals(listOf(other), deleted.dailyNotes)
        assertEquals(history.checkIns, deleted.checkIns)
        assertEquals(history.focusRecords, deleted.focusRecords)
        assertSame(deleted, setDailyNote(deleted, day, ""))
    }

    @Test fun noteDatesContentsAndTimestampsAreValidatedBeforeStorage() {
        val invalid = listOf(note.copy(date = "2026-02-30"), note.copy(date = "2026-9-14"),
            note.copy(text = ""), note.copy(text = "  \n "), note.copy(text = "字".repeat(5001)),
            note.copy(text = "bad\u0000text"), note.copy(text = "bad\uD800"), note.copy(updatedAt = -1),
            note.copy(updatedAt = 253_402_300_800_000L))
        invalid.forEach { candidate -> reject { DomainValidation.data(history.copy(dailyNotes = listOf(candidate))) } }
        reject { DomainValidation.data(history.copy(dailyNotes = listOf(note, note.copy(text = "同日另一篇")))) }
        reject { setDailyNote(history, day, "字".repeat(5001)) }
        reject { setDailyNote(history, LocalDate.of(10_000, 1, 1), "文字") }
        assertEquals(5000, setDailyNote(history, day, "字".repeat(5000)).dailyNotes.single().text.length)
    }

    @Test fun versionEightRoundTripsDailyNotesAlongsideTaskNotesAndProfile() {
        val data = history.copy(dailyNotes = listOf(note.copy(text = "\"读书\" 与生活\\\n新的一天 🌱")),
            profile = PersonalProfile("继续练习", "LEAF"), nickname = "阅读者")
        val encoded = DataCodec.encode(data)
        assertTrue(encoded.contains("\"version\":8"))
        assertEquals(data, DataCodec.decode(encoded))
        assertEquals(data, TransferCodec.decode(TransferCodec.encode(data, TransferScope.ALL)).data)
    }

    @Test fun earlierVersionsOneThroughSevenDefaultToNoDailyNotesWithoutLosingHistory() {
        for (version in 1..7) {
            val source = if (version == 7) history.copy(profile = PersonalProfile("保留的寄语", "SUN")) else history
            assertEquals("version $version", source, DataCodec.decode(downgrade(source, version)))
        }
    }

    @Test fun malformedOrMisVersionedDailyNoteFieldsAreRejected() {
        val data = history.copy(dailyNotes = listOf(note))
        val encoded = DataCodec.encode(data)
        reject { DataCodec.decode(encoded.replace("\"version\":8", "\"version\":7")) }
        reject { DataCodec.decode(encoded.replace("\"updatedAt\":100", "\"updatedAt\":\"100\"")) }
        val root = DataCodec.readJsonObject(encoded).toMutableMap()
        root.remove("dailyNotes")
        reject { DataCodec.decode(DataCodec.writeJsonObject(root)) }
        root["dailyNotes"] = "not-an-array"
        reject { DataCodec.decode(DataCodec.writeJsonObject(root)) }
        root["dailyNotes"] = listOf(mapOf("date" to note.date, "text" to note.text, "updatedAt" to 100, "planId" to plan.id))
        reject { DataCodec.decode(DataCodec.writeJsonObject(root)) }
    }

    @Test fun dailyNotesTravelWithRecordsAndAllButNeverWithPlans() {
        val data = history.copy(dailyNotes = listOf(note), profile = PersonalProfile("自己的寄语", "MOON"))
        val plans = TransferCodec.decode(TransferCodec.encode(data, TransferScope.PLANS)).data
        val records = TransferCodec.decode(TransferCodec.encode(data, TransferScope.RECORDS)).data
        assertTrue(plans.dailyNotes.isEmpty())
        assertEquals(listOf(note), records.dailyNotes)
        assertEquals(history.checkIns, records.checkIns)
        assertEquals(PersonalProfile(), records.profile)
        assertEquals(data, TransferCodec.decode(TransferCodec.encode(data, TransferScope.ALL)).data)
    }

    @Test fun reflectionOnlyRecordsBackupRequiresNoTaskMetadata() {
        val data = AppData(dailyNotes = listOf(note))
        val records = TransferCodec.decode(TransferCodec.encode(data, TransferScope.RECORDS))
        assertEquals(data, records.data)
        val restored = mergeTransfer(AppData(), records, TransferScope.RECORDS)
        assertEquals(data, restored.result)
        assertTrue(restored.summary.contains("1 篇每日心得"))
    }

    @Test fun planScopeCannotSmuggleDailyWritingAndPlanImportLeavesLocalNotesAlone() {
        val notes = AppData(dailyNotes = listOf(note))
        reject { mergeTransfer(AppData(), TransferBundle(TransferScope.PLANS, notes), TransferScope.PLANS) }
        val serialized = TransferCodec.encode(notes, TransferScope.RECORDS).replace("\"scope\":\"RECORDS\"", "\"scope\":\"PLANS\"")
        reject { TransferCodec.decode(serialized) }
        val source = history.copy(dailyNotes = listOf(note.copy(text = "文件里的心得")))
        val result = mergeTransfer(notes, TransferBundle(TransferScope.ALL, source), TransferScope.PLANS).result
        assertEquals(listOf(note), result.dailyNotes)
        assertEquals(history.plans, result.plans)
    }

    @Test fun sameDayRecordConflictsKeepLocalWritingRegardlessOfIncomingTimestamp() {
        val local = AppData(dailyNotes = listOf(note))
        val next = note.copy(date = day.plusDays(1).toString(), text = "新一天", updatedAt = 400)
        val incoming = TransferBundle(TransferScope.RECORDS, AppData(dailyNotes = listOf(note.copy(text = "文件文字", updatedAt = 999), next)))
        val merged = mergeTransfer(local, incoming, TransferScope.RECORDS)
        assertEquals(listOf(note, next), merged.result.dailyNotes)
        assertTrue(merged.summary.contains("1 篇每日心得"))
        assertTrue(merged.summary.contains("保留 1 条本机冲突"))
        assertEquals(merged.result, mergeTransfer(merged.result, incoming, TransferScope.RECORDS).result)
    }

    @Test fun repeatedNoteImportsAreIdempotentAndSameTextWithOtherTimestampIsDuplicate() {
        val local = history.copy(dailyNotes = listOf(note))
        val incoming = TransferBundle(TransferScope.RECORDS, AppData(dailyNotes = listOf(note.copy(updatedAt = 999))))
        val merged = mergeTransfer(local, incoming, TransferScope.RECORDS)
        assertEquals(local, merged.result)
        assertTrue(merged.summary.contains("略过 1 条重复内容"))
        assertTrue(merged.summary.contains("保留 0 条本机冲突"))
        assertEquals(local, mergeTransfer(merged.result, incoming, TransferScope.RECORDS).result)
    }

    @Test fun completeRestoreReplacesDailyNotesButOldRecordImportsNeverEraseThem() {
        val local = history.copy(dailyNotes = listOf(note))
        val source = AppData(dailyNotes = listOf(note.copy(text = "恢复的日记")))
        val replaced = mergeTransfer(local, TransferBundle(TransferScope.ALL, source), TransferScope.ALL)
        assertEquals(source, replaced.result)
        assertTrue(replaced.summary.contains("1 篇每日心得"))
        val oldData = DataCodec.decode(downgrade(history, 7))
        assertEquals(local.dailyNotes, mergeTransfer(local, TransferBundle(TransferScope.ALL, oldData), TransferScope.RECORDS).result.dailyNotes)
        assertTrue(mergeTransfer(local, TransferBundle(TransferScope.ALL, oldData), TransferScope.ALL).result.dailyNotes.isEmpty())
    }

    @Test fun onlyDailyNotesCountAsBackupContentAndStartReminderClock() {
        val data = AppData(dailyNotes = listOf(note))
        assertTrue(hasBackupContent(data))
        val store = BackupReminderStore(temp.newFolder("reminder"))
        val start = 1_000_000L
        assertEquals(start, store.observe(data, start).firstDataAt)
        assertTrue(store.observe(data, start + 7 * BackupReminderStore.DAY).due)
        assertFalse(hasBackupContent(setDailyNote(data, day, "")))
    }

    @Test fun namedSnapshotDerivesDailyCountWithoutChangingItsEnvelopeVersion() {
        val directory = temp.newFolder("named")
        val store = SnapshotStore(directory)
        val data = history.copy(dailyNotes = listOf(note))
        val created = store.create("心得存档", data)
        assertEquals(1, created.dailyNotes)
        assertEquals(created, store.list().single())
        assertEquals(data, store.read(created.id))
        File(directory, "${created.id}.snapshot").inputStream().use { stream ->
            val input = java.io.DataInputStream(stream)
            val magic = ByteArray("PLAN-SNAPSHOT".length); input.readFully(magic)
            assertEquals(2, input.readInt())
        }
        assertEquals(1, store.rename(created.id, "重新命名").dailyNotes)
        assertEquals(1, store.list().single().dailyNotes)
        assertEquals(0, store.overwrite(created.id, history).dailyNotes)
        assertEquals(history, store.read(created.id))
    }

    @Test fun automaticSnapshotProtectsReflectionOnlyData() {
        val files = temp.newFolder("automatic")
        val data = AppData(dailyNotes = listOf(note))
        AppStore(files).save(data)
        val automatic = AutoSnapshotStore(files)
        val created = requireNotNull(automatic.checkpoint(day, 100))
        assertEquals(1, created.dailyNotes)
        assertEquals(1, automatic.list().single().dailyNotes)
        assertEquals(data, automatic.read(created.id))
    }

    @Test fun oldSnapshotEnvelopesReadAndRenameWithZeroDailyNoteCount() {
        for (version in 1..2) {
            val directory = temp.newFolder("legacy-$version")
            val id = UUID.randomUUID().toString()
            writeSnapshotFixture(directory, id, version, downgrade(history, 7))
            val store = SnapshotStore(directory)
            assertEquals(history, store.read(id))
            assertEquals(0, store.list().single().dailyNotes)
            assertEquals(0, store.rename(id, "旧记录仍保留").dailyNotes)
            assertEquals(history, store.read(id))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun downgrade(data: AppData, version: Int): String {
        val root = DataCodec.readJsonObject(DataCodec.encode(data)).toMutableMap()
        root["version"] = version
        root.remove("dailyNotes")
        if (version < 7) { root.remove("categories"); root.remove("profile") }
        if (version < 4) root.remove("collapseCompleted")
        root["plans"] = (root["plans"] as List<Map<String, Any?>>).map { item -> item.filterKeys { key ->
            !(version < 7 && key in setOf("customCategoryId", "iconId")) && !(version < 6 && key == "superseded") &&
                !(version < 5 && key == "reminderTime") && !(version < 4 && key in setOf("pauses", "skips", "pinned", "sortOrder")) &&
                !(version < 3 && key in setOf("recordsOnly", "originId")) &&
                !(version < 2 && key in setOf("tracking", "scale", "seriesId", "totalTarget", "dueDate"))
        } }
        return DataCodec.writeJsonObject(root)
    }

    private fun writeSnapshotFixture(directory: File, id: String, version: Int, payload: String) {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { output ->
            fun text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); output.writeInt(bytes.size); output.write(bytes) }
            output.write("PLAN-SNAPSHOT".toByteArray(Charsets.US_ASCII))
            output.writeInt(version); text(id); text("旧存档")
            output.writeLong(100); output.writeLong(200)
            output.writeInt(1); output.writeInt(0); output.writeInt(1); output.writeInt(1)
            if (version >= 2) output.writeInt(0)
            text(payload)
        }
        val bytes = body.toByteArray()
        File(directory, "$id.snapshot").writeBytes(bytes + MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
