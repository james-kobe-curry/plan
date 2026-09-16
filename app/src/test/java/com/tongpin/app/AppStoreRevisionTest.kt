package com.tongpin.app

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppStoreRevisionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun separateInstancesNotifyAnAlreadyWaitingScreenAfterACommit() = runBlocking {
        val screen = AppStore(temp.root)
        val background = AppStore(File(temp.root, "."))
        val initial = screen.loadSnapshot()
        assertEquals(0L, initial.revision)
        assertEquals(AppData(), initial.result.getOrThrow())
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { screen.revision.first { it > initial.revision } }
        }
        withContext(Dispatchers.IO) { background.save(AppData(nickname = "旧页面完成的保存")) }
        assertEquals(1L, next.await())
        val result = screen.loadSnapshot()
        assertEquals(1L, result.revision)
        assertEquals("旧页面完成的保存", result.result.getOrThrow().nickname)
        assertEquals(background.revision.value, result.revision)
    }

    @Test fun anActivityCreatedDuringASaveSeesTheLaterCommitEvenWhenItsFirstReadWasEarlier() {
        val oldActivity = AppStore(temp.root)
        oldActivity.save(AppData(nickname = "保存之前"))
        val recreated = AppStore(temp.root)
        val earlyRead = recreated.loadSnapshot()
        oldActivity.update { it.copy(nickname = "保存之后") }
        // A collector created after this write still receives the newest StateFlow value.
        assertTrue(recreated.revision.value > earlyRead.revision)
        val latest = recreated.loadSnapshot()
        assertEquals("保存之后", latest.result.getOrThrow().nickname)
        assertNotEquals(recreated.revision.value, earlyRead.revision)
        assertEquals(recreated.revision.value, latest.revision)
    }

    @Test fun failedAndUnchangedUpdatesDoNotEmitACommittedVersion() {
        val writer = AppStore(temp.root)
        val reader = AppStore(temp.root)
        writer.save(AppData(nickname = "保留"))
        val committed = reader.revision.value
        writer.update { it.copy() }
        assertEquals(committed, reader.revision.value)
        assertThrows(IllegalArgumentException::class.java) { writer.update { it.copy(nickname = "") } }
        assertEquals(committed, reader.revision.value)
        File(temp.root, "tongpin-data.json.new").mkdir()
        assertThrows(IOException::class.java) { writer.save(AppData(nickname = "写入失败")) }
        assertEquals(committed, reader.revision.value)
        assertEquals("保留", reader.loadSnapshot().result.getOrThrow().nickname)
    }

    @Test fun restoreAndUndoEachPublishOneVersionWithTheirMatchingSafetyCopyState() {
        val writer = AppStore(temp.root)
        val reader = AppStore(temp.root)
        writer.save(AppData(nickname = "原内容"))
        assertFalse(reader.loadSnapshot().canUndoRestore)
        val baseline = reader.revision.value
        writer.restore(AppData(nickname = "恢复内容"))
        val restored = reader.loadSnapshot()
        assertEquals(baseline + 1, restored.revision)
        assertEquals("恢复内容", restored.result.getOrThrow().nickname)
        assertTrue(restored.canUndoRestore)
        writer.recoverPrevious()
        val undone = reader.loadSnapshot()
        assertEquals(baseline + 2, undone.revision)
        assertEquals("原内容", undone.result.getOrThrow().nickname)
        assertTrue(undone.canUndoRestore)
    }

    @Test fun corruptInitialReadCanBecomeASuccessfulSnapshotAfterRestore() {
        File(temp.root, "tongpin-data.json").writeText("broken")
        val reader = AppStore(temp.root)
        val failed = reader.loadSnapshot()
        assertTrue(failed.result.isFailure)
        assertEquals(0L, failed.revision)
        AppStore(temp.root).restore(AppData(nickname = "已修复"))
        val repaired = reader.loadSnapshot()
        assertTrue(repaired.revision > failed.revision)
        assertEquals("已修复", repaired.result.getOrThrow().nickname)
        assertEquals(1L, reader.revision.value)
    }

    @Test fun externalZipRestoreSignalsTheSameDirectoryButOtherDirectoriesRemainIndependent() {
        val first = AppStore(temp.root)
        val sameDirectory = AppStore(temp.root)
        val other = AppStore(temp.newFolder("other"))
        first.exclusive {
            File(temp.root, "tongpin-data.json").writeText(DataCodec.encode(AppData(nickname = "ZIP恢复")))
            first.notifyExternalChange()
        }
        assertEquals(1L, sameDirectory.revision.value)
        assertEquals("ZIP恢复", sameDirectory.loadSnapshot().result.getOrThrow().nickname)
        assertEquals(0L, other.revision.value)
        assertEquals(AppData(), other.loadSnapshot().result.getOrThrow())
        repeat(3) { sameDirectory.loadSnapshot(); sameDirectory.load() }
        assertEquals(1L, sameDirectory.revision.value)
    }
}
