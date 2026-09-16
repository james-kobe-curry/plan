package com.tongpin.app

import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val task = Plan(id = "read", title = "阅读", category = Category.STUDY, target = 1, unit = "次", tracking = TrackingMode.TASK, startDate = "2026-09-14")

    @Test fun existingVersionOneFileLoadsAndUpgradesAtItsOriginalPath() {
        val source = File(temp.root, "tongpin-data.json")
        source.writeText(QuantityMigrationTest.legacyBackup())
        val store = AppStore(temp.root)
        val data = store.load()
        assertEquals("legacy", data.plans.single().id)
        assertEquals(2, data.plans.single().scale)
        store.save(data)
        assertTrue(source.readText().contains("\"version\":8"))
        assertEquals(data, AppStore(temp.root).load())
    }

    @Test fun restorationPreservesCurrentDataAndRecoveryCanBeUndone() {
        val store = AppStore(temp.root)
        val current = setCheckIn(AppData(plans = listOf(task)), task.id, LocalDate.of(2026, 9, 14), 1, "已完成")
        val replacement = AppData(nickname = "新数据")
        store.save(current)
        assertFalse(store.hasPreviousBackup())
        store.restore(replacement)
        assertEquals(replacement, store.load())
        assertTrue(store.hasPreviousBackup())
        assertEquals(current, store.recoverPrevious())
        assertEquals(current, store.load())
        assertEquals(replacement, store.recoverPrevious())
    }

    @Test fun invalidRestoreDoesNotReplaceCurrentDataOrSafetyCopy() {
        val store = AppStore(temp.root)
        val original = AppData(nickname = "原数据")
        val current = AppData(nickname = "当前数据")
        store.save(original)
        store.restore(current)
        assertThrows(IllegalArgumentException::class.java) { store.restore(AppData(nickname = "")) }
        assertEquals(current, store.load())
        assertEquals(original, store.recoverPrevious())
    }

    @Test fun interruptedLegacyAtomicWriteRecoversItsBackup() {
        val committed = AppData(nickname = "已保存")
        File(temp.root, "tongpin-data.json").writeText("incomplete")
        File(temp.root, "tongpin-data.json.bak").writeText(DataCodec.encode(committed))
        assertEquals(committed, AppStore(temp.root).load())
        assertFalse(File(temp.root, "tongpin-data.json.bak").exists())
    }

    @Test fun corruptDataIsPreservedAndNeverSilentlyReplacedWithEmptyData() {
        val source = File(temp.root, "tongpin-data.json")
        source.writeText("{broken")
        assertThrows(IOException::class.java) { AppStore(temp.root).load() }
        assertEquals("{broken", source.readText())
        assertTrue(temp.root.listFiles()!!.any { it.name.startsWith("tongpin-unreadable-") && it.readText() == "{broken" })
    }

    @Test fun failureBeforeAtomicReplacementKeepsCommittedData() {
        val store = AppStore(temp.root)
        val committed = AppData(nickname = "已保存")
        store.save(committed)
        File(temp.root, "tongpin-data.json.new").mkdir()
        assertThrows(IOException::class.java) { store.save(AppData(nickname = "未保存")) }
        assertEquals(committed, store.load())
    }

    @Test fun aValidSafetyCopyCanRescueACorruptCurrentFile() {
        val store = AppStore(temp.root)
        val committed = AppData(nickname = "安全副本")
        store.save(committed)
        store.restore(AppData(nickname = "恢复后的数据"))
        File(temp.root, "tongpin-data.json").writeText("broken")
        assertEquals(committed, store.recoverPrevious())
        assertEquals(committed, store.load())
        assertTrue(temp.root.listFiles()!!.any { it.name.startsWith("tongpin-unreadable-") && it.readText() == "broken" })
    }

    @Test fun separateStoreInstancesSerializeConcurrentFocusAndPlanUpdates() {
        val first = AppStore(temp.root)
        val second = AppStore(File(temp.root, ".").canonicalFile)
        first.save(AppData(plans = listOf(task)))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val writes = (0 until 20).map { index ->
                pool.submit {
                    start.await()
                    val store = if (index % 2 == 0) first else second
                    store.update { current ->
                        if (index == 0) setCheckIn(current, task.id, LocalDate.of(2026, 9, 14), 1)
                        else current.copy(focusRecords = current.focusRecords + FocusRecord("focus-$index", task.id, 60, index.toLong()))
                    }
                }
            }
            start.countDown()
            writes.forEach { it.get() }
            val result = first.load()
            assertEquals(19, result.focusRecords.size)
            assertEquals(1, result.checkIns.single().amount)
        } finally { pool.shutdownNow() }
    }

    @Test fun historyLargerThanOldTwoMegabyteLimitRoundTrips() {
        val history = (0 until 2500).map {
            CheckIn(task.id, LocalDate.of(2010, 1, 1).plusDays(it.toLong()).toString(), 1, "n".repeat(1000), it.toLong())
        }
        val data = AppData(plans = listOf(task), checkIns = history)
        val encoded = DataCodec.encode(data)
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024)
        val store = AppStore(temp.root)
        store.save(data)
        assertEquals(data, store.load())
    }
}
