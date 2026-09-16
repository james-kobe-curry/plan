package com.tongpin.app

import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutoSnapshotStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val day = LocalDate.of(2026, 9, 16)
    private fun data(title: String = "阅读") = AppData(plans = listOf(Plan(id = "read", title = title,
        category = Category.STUDY, target = 1, unit = "次", tracking = TrackingMode.TASK, startDate = "2026-09-01")))
    private fun save(directory: File, title: String = "阅读") { AppStore(directory).save(data(title)) }

    @Test fun emptyDataAndNicknameAloneDoNotCreateSnapshots() {
        val auto = AutoSnapshotStore(temp.root)
        assertNull(auto.checkpoint(day, 100))
        AppStore(temp.root).save(AppData(nickname = "新名字"))
        assertNull(auto.checkpoint(day, 200)); assertTrue(auto.list().isEmpty())
    }

    @Test fun firstDailySnapshotPersistsAndLaterChangesNeverOverwriteIt() {
        save(temp.root)
        val first = AutoSnapshotStore(temp.root).checkpoint(day, 100)!!
        save(temp.root, "修改后的计划")
        val auto = AutoSnapshotStore(temp.root)
        assertNull(auto.checkpoint(day, 200))
        assertEquals(data(), auto.read(first.id))
        assertEquals(listOf(first), auto.list())
        assertEquals("2026-09-16 自动存档", first.name)
    }

    @Test fun suppliedUiDataIsOnlyAHintAndCurrentDataIsReadInsideTheStoreLock() {
        save(temp.root, "最新状态")
        val auto = AutoSnapshotStore(temp.root)
        val info = auto.checkpoint(data("旧界面状态"), day, 100)!!
        assertEquals(data("最新状态"), auto.read(info.id))
    }

    @Test fun latestSevenDailySnapshotsDoNotUseNamedSnapshotCapacity() {
        save(temp.root)
        val named = SnapshotStore(File(temp.root, "snapshots"))
        repeat(50) { named.create("阶段 $it", data()) }
        val auto = AutoSnapshotStore(temp.root)
        repeat(9) { offset -> save(temp.root, "第 $offset 天"); auto.checkpoint(day.plusDays(offset.toLong()), 100L + offset) }
        assertEquals(50, named.list().size); assertEquals(7, auto.list().size)
        assertEquals((2L..8L).map { day.plusDays(it) }.reversed(), auto.list().map { AutoSnapshotStore.dateOf(it) })
    }

    @Test fun concurrentInstancesCreateExactlyOneDailyCheckpoint() {
        save(temp.root)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (1..16).map { executor.submit<SnapshotInfo?> { AutoSnapshotStore(temp.root).checkpoint(day, 100) } }
                .map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it != null }); assertEquals(1, AutoSnapshotStore(temp.root).list().size)
        } finally { executor.shutdownNow() }
    }

    @Test fun damagedCurrentDayIsVisibleAndNeverSilentlyOverwritten() {
        save(temp.root); val auto = AutoSnapshotStore(temp.root); val info = auto.checkpoint(day, 100)!!
        val file = File(temp.root, "auto_snapshots/${info.id}.snapshot"); file.appendText("broken")
        val bytes = file.readBytes(); save(temp.root, "新状态")
        assertNull(auto.checkpoint(day, 200)); assertArrayEquals(bytes, file.readBytes()); assertTrue(auto.list().single().corrupt)
    }

    @Test fun nextReadFinishesRetentionAfterInterruptionBetweenCreateAndPrune() {
        val raw = SnapshotStore(File(temp.root, "auto_snapshots"))
        repeat(8) { index -> val date = day.plusDays(index.toLong())
            raw.createIfAbsent(AutoSnapshotStore.identity(date), AutoSnapshotStore.name(date), data(), index + 100L) }
        assertEquals(8, raw.list().size)
        assertEquals(7, AutoSnapshotStore(temp.root).list().size)
        assertFalse(raw.list().any { it.id == AutoSnapshotStore.identity(day) })
    }

    @Test fun restoringNewBackupKeepsLocalTodayEvenWhenIncomingDaysAreLater() {
        val current = temp.newFolder("current"); save(current, "恢复前安全状态")
        val auto = AutoSnapshotStore(current); val today = auto.checkpoint(day, 100)!!
        val incoming = temp.newFolder("incoming"); val incomingStore = AutoSnapshotStore(incoming)
        repeat(7) { index -> save(incoming, "备份中的状态"); incomingStore.checkpoint(day.plusDays(index.toLong()), index + 200L) }
        val stage = File(temp.root, "staged")
        auto.stageForRestore(File(incoming, "auto_snapshots"), stage, day)
        val staged = SnapshotStore(stage)
        assertEquals(7, staged.list().size)
        assertEquals(data("恢复前安全状态"), staged.read(today.id))
        assertEquals(7, AutoSnapshotStore.validateDirectory(stage).size)
    }

    @Test fun restoringLegacyBackupPreservesAllExistingAutomaticCopies() {
        save(temp.root); val auto = AutoSnapshotStore(temp.root)
        auto.checkpoint(day.minusDays(1), 100); auto.checkpoint(day, 200)
        val stage = File(temp.root, "stage"); auto.stageForRestore(null, stage, day)
        assertEquals(auto.list().toSet(), SnapshotStore(stage).list().toSet())
    }

    @Test fun automaticArchiveRejectsWrongIdentityOrMoreThanSevenDays() {
        val directory = temp.newFolder("bad")
        SnapshotStore(directory).create(AutoSnapshotStore.name(day), data())
        try { AutoSnapshotStore.validateDirectory(directory); fail("Forged daily identity") } catch (_: IllegalArgumentException) {}
        val tooMany = temp.newFolder("too-many")
        val store = SnapshotStore(tooMany)
        repeat(8) { offset -> val date=day.plusDays(offset.toLong())
            store.createIfAbsent(AutoSnapshotStore.identity(date),AutoSnapshotStore.name(date),data(),offset+100L) }
        try { AutoSnapshotStore.validateDirectory(tooMany); fail("More than seven days") } catch (_: IllegalArgumentException) {}
    }
}
