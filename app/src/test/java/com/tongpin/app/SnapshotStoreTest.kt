package com.tongpin.app

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SnapshotStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun sample(): AppData {
        val past = Plan(id = "past", seriesId = "reading-series", title = "旧跑步安排", category = Category.FITNESS,
            target = 200, unit = "公里", scale = 2, startDate = "2026-09-01", archived = true, endDate = "2026-09-10")
        val current = past.copy(id = "current", title = "跑步", archived = false, startDate = "2026-09-10",
            endDate = null, target = 250, totalTarget = 3000, dueDate = "2026-09-30")
        val simple = Plan(id = "simple", title = "阅读", category = Category.STUDY, target = 1, unit = "次",
            tracking = TrackingMode.TASK, startDate = "2026-09-01")
        return AppData(plans = listOf(past, current, simple), checkIns = listOf(
            CheckIn("past", "2026-09-09", 125, "旧安排的心得\n第二行", 10),
            CheckIn("current", "2026-09-14", 250, "完成 2.5 公里", 20),
            CheckIn("simple", "2026-09-14", 1, "完成阅读", 30),
        ), focusRecords = listOf(FocusRecord("focus-old", "past", 1500, 40),
            FocusRecord("focus-free", null, 60, 50)), nickname = "读书与运动")
    }

    private fun file(id: String) = File(temp.root, "$id.snapshot")

    /** Fixture for the first envelope, including its original summary counting rules. */
    private fun writeLegacySnapshot(id: String, data: AppData) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fun text(value: String) {
                val encoded = value.toByteArray(Charsets.UTF_8)
                output.writeInt(encoded.size)
                output.write(encoded)
            }
            output.write("PLAN-SNAPSHOT".toByteArray(Charsets.US_ASCII))
            output.writeInt(1)
            text(id)
            text("旧存档")
            output.writeLong(1_000)
            output.writeLong(2_000)
            output.writeInt(data.plans.count { !it.archived })
            output.writeInt(data.plans.count { it.archived })
            output.writeInt(data.checkIns.size)
            output.writeInt(data.focusRecords.size)
            text(DataCodec.encode(data))
        }
        val payload = bytes.toByteArray()
        file(id).writeBytes(payload + MessageDigest.getInstance("SHA-256").digest(payload))
    }

    @Test fun completeSnapshotPreservesArchivedVersionsHistoryDecimalsAndFocus() {
        val original = sample()
        val store = SnapshotStore(temp.root)
        val info = store.create("九月计划", original)
        assertEquals(2, info.plans)
        assertEquals(1, info.archived)
        assertEquals(3, info.checkIns)
        assertEquals(2, info.focusRecords)
        assertTrue(info.createdAt > 0)
        assertEquals(info.createdAt, info.updatedAt)
        assertEquals(original, SnapshotStore(temp.root).read(info.id))
        assertEquals(listOf(info), store.list())
    }

    @Test fun recordOnlyImportsDoNotConsumeActivePlanSummaryCapacity() {
        val original = sample()
        val metadata = (0..250).map { index -> original.plans.first().copy(
            id = "imported-$index", seriesId = "imported-series-$index", recordsOnly = true,
            archived = index % 2 == 0, endDate = null,
        ) }
        val data = original.copy(plans = original.plans + metadata,
            checkIns = original.checkIns + CheckIn(metadata.first().id, "2026-09-01", 100, "导入的心得", 100))
        val store = SnapshotStore(temp.root)
        val info = store.create("完整保留导入记录", data)
        assertEquals(2, info.plans)
        assertEquals(1, info.archived)
        assertEquals(251, info.recordsOnly)
        assertEquals(4, info.checkIns)
        assertEquals(info, store.list().single())
        assertEquals(data, SnapshotStore(temp.root).read(info.id))
        assertEquals(251, store.rename(info.id, "更名后仍保留").recordsOnly)
        assertEquals(0, store.overwrite(info.id, original).recordsOnly)
    }

    @Test fun legacySnapshotIsReadAndUpgradedOnRenameWithoutLosingRecordOnlyMetadata() {
        val original = sample()
        val data = original.copy(plans = original.plans + (0..250).map { index ->
            original.plans.last().copy(id = "import-$index", seriesId = "import-series-$index", recordsOnly = true)
        })
        val id = UUID.randomUUID().toString()
        writeLegacySnapshot(id, data)
        val store = SnapshotStore(temp.root)
        assertEquals(data, store.read(id))
        val legacy = store.list().single()
        assertFalse(legacy.corrupt)
        assertEquals("旧存档", legacy.name)
        assertEquals(2, legacy.plans)
        assertEquals(1, legacy.archived)
        assertEquals(251, legacy.recordsOnly)
        assertEquals(1_000L, legacy.createdAt)
        val renamed = store.rename(id, "兼容存档")
        assertEquals(renamed, SnapshotStore(temp.root).list().single())
        assertEquals(data, store.read(id))
    }

    @Test fun renameChangesOnlyNameAndKeepsCompleteStoredData() {
        val original = sample()
        val store = SnapshotStore(temp.root)
        val before = store.create("开始", original)
        val after = store.rename(before.id, "  春季计划 📚  ")
        assertEquals("春季计划 📚", after.name)
        assertEquals(before.id, after.id)
        assertEquals(before.createdAt, after.createdAt)
        assertTrue(after.updatedAt >= before.updatedAt)
        assertEquals(original, store.read(before.id))
        assertEquals(after, store.list().single())
    }

    @Test fun overwriteRefreshesStoredDataAndSummaryWithoutChangingIdentity() {
        val store = SnapshotStore(temp.root)
        val before = store.create("长期安排", sample())
        val replacement = AppData(nickname = "新阶段")
        val after = store.overwrite(before.id, replacement)
        assertEquals(before.name, after.name)
        assertEquals(before.id, after.id)
        assertEquals(before.createdAt, after.createdAt)
        assertTrue(after.updatedAt >= before.updatedAt)
        assertEquals(0, after.plans)
        assertEquals(0, after.archived)
        assertEquals(0, after.checkIns)
        assertEquals(0, after.focusRecords)
        assertEquals(replacement, store.read(before.id))
    }

    @Test fun namesRejectEmptyControlsTooLongAndInvalidUnicodeBeforeWriting() {
        val store = SnapshotStore(temp.root)
        listOf("", "  ", "a\nb", "a\u0000b", "a".repeat(41), "\uD800").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { store.create(name, AppData()) }
        }
        assertTrue(store.list().isEmpty())
        assertEquals("有效名称", store.create(" 有效名称 ", AppData()).name)
    }

    @Test fun invalidRenameOrDataDoesNotAlterExistingSnapshot() {
        val store = SnapshotStore(temp.root)
        val info = store.create("原存档", sample())
        val committed = file(info.id).readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.rename(info.id, "") }
        assertThrows(IllegalArgumentException::class.java) { store.overwrite(info.id, AppData(nickname = "")) }
        assertArrayEquals(committed, file(info.id).readBytes())
        assertEquals(sample(), store.read(info.id))
    }

    @Test fun identifiersCannotEscapeTheSnapshotDirectory() {
        val sentinel = File(temp.root, "keep.txt").apply { writeText("keep") }
        val store = SnapshotStore(temp.root)
        listOf("../keep.txt", "..\\keep.txt", "/tmp/other", "C:\\other", "", "1-1-1-1-1").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { store.read(id) }
            assertThrows(IllegalArgumentException::class.java) { store.rename(id, "测试") }
            assertThrows(IllegalArgumentException::class.java) { store.overwrite(id, AppData()) }
            assertThrows(IllegalArgumentException::class.java) { store.delete(id) }
        }
        assertEquals("keep", sentinel.readText())
    }

    @Test fun corruptSnapshotIsListedByNameAndDoesNotHideHealthyCopies() {
        val store = SnapshotStore(temp.root)
        val damaged = store.create("损坏样例", sample())
        val healthy = store.create("保留样例", AppData(nickname = "保留"))
        RandomAccessFile(file(damaged.id), "rw").use { stream ->
            stream.seek(stream.length() - 1)
            val last = stream.readByte()
            stream.seek(stream.length() - 1)
            stream.writeByte(last.toInt() xor 1)
        }
        val entries = store.list().associateBy { it.id }
        assertTrue(entries.getValue(damaged.id).corrupt)
        assertEquals("损坏样例", entries.getValue(damaged.id).name)
        assertFalse(entries.getValue(damaged.id).error.isNullOrBlank())
        assertFalse(entries.getValue(healthy.id).corrupt)
        assertThrows(IOException::class.java) { store.read(damaged.id) }
        assertThrows(IOException::class.java) { store.rename(damaged.id, "重命名") }
        assertThrows(IOException::class.java) { store.overwrite(damaged.id, AppData()) }
        assertEquals("保留", store.read(healthy.id).nickname)
        store.delete(damaged.id)
        assertEquals(listOf(healthy), store.list())
    }

    @Test fun truncatedHeaderIsReportedAndCanBeDeleted() {
        val store = SnapshotStore(temp.root)
        val info = store.create("短文件", AppData())
        file(info.id).writeBytes(byteArrayOf(1, 2, 3))
        val listed = store.list().single()
        assertEquals(info.id, listed.id)
        assertTrue(listed.corrupt)
        assertFalse(listed.error.isNullOrBlank())
        store.delete(info.id)
        assertTrue(store.list().isEmpty())
    }

    @Test fun unexpectedTrailingBytesAreRejected() {
        val store = SnapshotStore(temp.root)
        val info = store.create("尾部检查", AppData())
        file(info.id).appendBytes(byteArrayOf(0))
        assertTrue(store.list().single().corrupt)
        assertThrows(IOException::class.java) { store.read(info.id) }
    }

    @Test fun oversizedFilesAreRejectedBeforeAllocatingPayload() {
        val id = UUID.randomUUID().toString()
        RandomAccessFile(file(id), "rw").use { it.setLength(DataCodec.MAX_BYTES.toLong() + 1024) }
        val store = SnapshotStore(temp.root)
        assertTrue(store.list().single().corrupt)
        assertThrows(IOException::class.java) { store.read(id) }
    }

    @Test fun interruptedReplacementRecoversThePreviousWholeFile() {
        val store = SnapshotStore(temp.root)
        val data = sample()
        val info = store.create("提交完成", data)
        file(info.id).copyTo(File(file(info.id).path + ".bak"))
        file(info.id).writeText("incomplete")
        assertEquals(data, SnapshotStore(temp.root).read(info.id))
        assertFalse(File(file(info.id).path + ".bak").exists())
        assertEquals(info, store.list().single())
    }

    @Test fun listFindsBackupWhenInterruptedReplacementHasNoMainFile() {
        val store = SnapshotStore(temp.root)
        val data = sample()
        val info = store.create("副本恢复", data)
        assertTrue(file(info.id).renameTo(File(file(info.id).path + ".bak")))
        assertEquals(info, SnapshotStore(temp.root).list().single())
        assertEquals(data, store.read(info.id))
    }

    @Test fun deletingAnInterruptedReplacementCannotResurrectTheBackup() {
        val store = SnapshotStore(temp.root)
        val info = store.create("即将删除", sample())
        val backup = File(file(info.id).path + ".bak")
        assertTrue(file(info.id).renameTo(backup))
        store.delete(info.id)
        assertFalse(backup.exists())
        assertFalse(file(info.id).exists())
        assertTrue(SnapshotStore(temp.root).list().isEmpty())
    }

    @Test fun aDirectoryAtTheRecoveryPathDoesNotReplaceCommittedData() {
        val store = SnapshotStore(temp.root)
        val info = store.create("已保存", sample())
        val committed = file(info.id).readBytes()
        val invalidBackup = File(file(info.id).path + ".bak").apply { mkdir() }
        File(invalidBackup, "keep").writeText("directory sentinel")
        assertTrue(store.list().single().corrupt)
        assertThrows(IllegalArgumentException::class.java) { store.rename(info.id, "未保存") }
        assertArrayEquals(committed, file(info.id).readBytes())
        assertEquals("directory sentinel", File(invalidBackup, "keep").readText())
    }

    @Test fun failedAtomicWriteKeepsThePreviousNameAndData() {
        val store = SnapshotStore(temp.root)
        val data = sample()
        val info = store.create("已保存", data)
        val committed = file(info.id).readBytes()
        val blocker = File(file(info.id).path + ".new").apply { mkdir() }
        File(blocker, "keep").writeText("block replacement")
        assertThrows(IOException::class.java) { store.rename(info.id, "未保存") }
        assertThrows(IOException::class.java) { store.overwrite(info.id, AppData()) }
        assertArrayEquals(committed, file(info.id).readBytes())
        assertEquals(info, store.list().single())
        assertEquals(data, store.read(info.id))
    }

    @Test fun snapshotCapacityIncludesCorruptCopiesAndDeletionFreesOneSlot() {
        val store = SnapshotStore(temp.root)
        val infos = (1..SnapshotStore.MAX_SNAPSHOTS).map { store.create("存档 $it", AppData()) }
        file(infos.first().id).writeText("broken")
        assertThrows(IllegalArgumentException::class.java) { store.create("超出数量", AppData()) }
        store.delete(infos.first().id)
        store.create("替补存档", AppData())
        assertEquals(SnapshotStore.MAX_SNAPSHOTS, store.list().size)
    }

    @Test fun separateInstancesSerializeConcurrentCreations() {
        val first = SnapshotStore(temp.root)
        val second = SnapshotStore(File(temp.root, ".").canonicalFile)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 20).map { index ->
                pool.submit<SnapshotInfo> {
                    start.await()
                    (if (index % 2 == 0) first else second).create("任务 $index", AppData(nickname = "用户 $index"))
                }
            }
            start.countDown()
            val infos = jobs.map { it.get() }
            assertEquals(20, infos.map { it.id }.toSet().size)
            assertEquals(20, first.list().size)
            infos.forEach { info -> assertFalse(first.read(info.id).nickname.isBlank()) }
        } finally { pool.shutdownNow() }
    }

    @Test fun unrelatedCurrentDataFilesAreNeverChanged() {
        val current = File(temp.root, "tongpin-data.json").apply { writeText("current record sentinel") }
        val backup = File(temp.root, "tongpin-before-restore.json").apply { writeText("backup sentinel") }
        val store = SnapshotStore(temp.root)
        val info = store.create("独立存档", sample())
        store.rename(info.id, "独立存档二")
        store.overwrite(info.id, AppData())
        store.read(info.id)
        store.list()
        store.delete(info.id)
        assertEquals("current record sentinel", current.readText())
        assertEquals("backup sentinel", backup.readText())
    }

    @Test fun missingValidIdCannotAccidentallyCreateASnapshot() {
        val store = SnapshotStore(temp.root)
        val missing = UUID.randomUUID().toString()
        assertThrows(IOException::class.java) { store.read(missing) }
        assertThrows(IOException::class.java) { store.rename(missing, "新名字") }
        assertThrows(IOException::class.java) { store.overwrite(missing, AppData()) }
        assertThrows(IOException::class.java) { store.delete(missing) }
        assertTrue(store.list().isEmpty())
    }
}
