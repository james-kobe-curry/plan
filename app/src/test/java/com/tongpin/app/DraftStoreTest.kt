package com.tongpin.app

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DraftStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun store() = DraftStore(temp.root)
    private fun file(key: String) = File(temp.root, "editor-drafts/${draftHash(key)}.json")
    private val fields = mapOf("title" to "读第二章", "note" to "记住这个想法\n下次继续", "amount" to "1.25")

    @Test fun untouchedFormDoesNotCreateAnEntry() {
        val session = store().open("plan:new", "baseline").session
        session.update(null); session.persist()
        assertFalse(file("plan:new").exists())
        assertNull(store().open("plan:new", "baseline").values)
    }

    @Test fun fieldsSurviveNewStoreAndEachPlanAndDateHasItsOwnDraft() {
        val first = store().open("checkin:plan:2026-09-16", "baseline").session
        first.update(fields); first.persist()
        val second = store().open("checkin:plan:2026-09-17", "baseline").session
        second.update(mapOf("note" to "明天")); second.persist()
        assertEquals(fields, store().open("checkin:plan:2026-09-16", "baseline").values)
        assertEquals("明天", store().open("checkin:plan:2026-09-17", "baseline").values?.get("note"))
        assertNull(store().open("checkin:other:2026-09-16", "baseline").values)
    }

    @Test fun changedBaselineIsIsolatedAndStillAvailableToCopyAfterReopening() {
        val session = store().open("one", "old-unit").session
        session.update(fields); session.persist()
        val result = store().open("one", "new-unit")
        assertNull(result.values); assertNotNull(result.notice); assertEquals(fields, result.isolatedValues)
        val again = store().open("one", "new-unit")
        assertNull(again.values); assertEquals(fields, again.isolatedValues)
    }

    @Test fun restoreEpochBlocksOldWritesAndAllowsFreshContent() {
        val first = store().open("one", "same").session
        first.update(fields); first.persist()
        store().clearAll()
        first.update(mapOf("note" to "迟到的写入"))
        try { first.persist(); fail("Old session cannot write after restore") } catch (_: IllegalStateException) { }
        val replacement = store().open("one", "same")
        assertNull(replacement.values); assertEquals(fields, replacement.isolatedValues)
        replacement.session.update(mapOf("note" to "新草稿")); replacement.session.persist()
        assertEquals("新草稿", store().open("one", "same").values?.get("note"))
    }

    @Test fun aNewlyOpenedEditorOwnsTheKeyAndCannotBeClearedByTheOldEditor() {
        val older = store().open("one", "base").session
        older.update(fields); older.persist()
        val newer = store().open("one", "base").session
        newer.update(mapOf("note" to "第二个界面")); newer.persist()
        try { older.finishCommitted(); fail("Old editor cannot clear new owner") } catch (_: IllegalStateException) { }
        assertEquals("第二个界面", store().open("one", "base").values?.get("note"))
    }

    @Test fun aQueuedAutosaveAfterDiscardCannotReviveTheEntry() {
        val session = store().open("one", "base").session
        session.update(fields); session.persist()
        val executor = Executors.newSingleThreadExecutor()
        val begin = CountDownLatch(1)
        try {
            val queued = executor.submit { begin.await(); session.persist(); session.update(fields); session.persist() }
            session.discard(); begin.countDown(); queued.get()
            assertFalse(file("one").exists())
        } finally { begin.countDown(); executor.shutdownNow() }
    }

    @Test fun stopFlushAlwaysWritesLatestValuesInsteadOfAnOldDebounceSnapshot() {
        val session = store().open("one", "base").session
        session.update(mapOf("note" to "旧输入"))
        session.update(fields)
        session.persist()
        assertEquals(fields, store().open("one", "base").values)
    }

    @Test fun reopeningBeforeStopFlushStillRecoversTheLatestInMemoryInput() {
        val old = store().open("one", "base").session
        old.update(mapOf("note" to "已保存的旧草稿")); old.persist()
        old.update(fields)
        val replacement = store().open("one", "base")
        assertEquals(fields, replacement.values)
        try { old.persist(); fail("Replaced owner cannot write") } catch (_: IllegalStateException) { }
    }

    @Test fun aCancelledOldOpenCannotStealANewerEditorOrCrossRestore() {
        val olderRequest = DraftStore.nextSessionOrder()
        val current = store().open("one", "base")
        current.session.update(fields); current.session.persist()
        try { store().open("one", "base", olderRequest); fail("Old request must be rejected") } catch (_: IllegalStateException) { }
        val beforeRestore = DraftStore.nextSessionOrder()
        store().clearAll()
        try { store().open("two", "base", beforeRestore); fail("Pre-restore open must be rejected") } catch (_: IllegalStateException) { }
    }

    @Test fun failedWriteKeepsThePreviouslyDurableDraftAndCanBeRetried() {
        val session = store().open("one", "base").session
        session.update(fields); session.persist()
        val committed = file("one").readBytes()
        val blocked = File(file("one").path + ".new").apply { mkdir() }
        File(blocked, "block").writeText("keep")
        session.update(mapOf("note" to "新输入"))
        try { session.persist(); fail("Storage failure expected") } catch (_: Exception) { }
        assertArrayEquals(committed, file("one").readBytes())
        File(blocked, "block").delete(); blocked.delete()
        session.persist()
        assertEquals("新输入", store().open("one", "base").values?.get("note"))
    }

    @Test fun failedCommittedCleanupStillClosesTheSessionAgainstLateWrites() {
        val session = store().open("one", "base").session
        session.update(fields); session.persist()
        file("one").delete(); file("one").mkdir(); File(file("one"), "block").writeText("keep")
        try { session.finishCommitted(); fail("Cleanup failure expected") } catch (_: Exception) { }
        File(file("one"), "block").delete(); file("one").delete()
        session.update(fields); session.persist()
        assertFalse(file("one").exists())
    }

    @Test fun damagedAndOversizedDraftsNeverApplyToTheForm() {
        store().open("one", "base").session.closed.set(true)
        file("one").writeText("{broken")
        assertNull(store().open("one", "base").values)
        val reopened = store().open("one", "base")
        assertNotNull(reopened.notice)
        reopened.session.closed.set(true)
        file("one").writeText("x".repeat(25 * 1024))
        assertNull(store().open("one", "base").values)
    }

    @Test fun unsafeLookingKeysStayInsideTheDraftDirectory() {
        val session = store().open("../../outside", "base").session
        session.update(fields); session.persist()
        assertTrue(file("../../outside").isFile)
        assertFalse(File(temp.root, "outside").exists())
    }

    @Test fun invalidFieldsAreRejectedBeforeReplacingTheSavedDraft() {
        val session = store().open("one", "base").session
        session.update(fields); session.persist()
        val original = file("one").readBytes()
        session.update(mapOf("note" to "x".repeat(2001)))
        try { session.persist(); fail("Bound expected") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(original, file("one").readBytes())
    }

    @Test fun anInterruptedSubmissionIsIsolatedEvenWhenTheBaselineIsExactlyTheSame() {
        val session = store().open("one", "unchanged").session
        session.update(fields); session.prepareCommit()
        session.closed.set(true) // Simulate a process ending after the durable intent.
        val reopened = store().open("one", "unchanged")
        assertNull(reopened.values); assertEquals(fields, reopened.isolatedValues)
        assertTrue(reopened.notice.orEmpty().contains("保存状态未确认"))
    }

    @Test fun failedCleanupAndLaterUndoToSameBaselineCannotRestoreACommittedIncrement() {
        var failDelete = false
        val failingStore = DraftStore(temp.root, beforeDelete = { if (failDelete) throw java.io.IOException("storage unavailable") })
        val session = failingStore.open("one", "empty-day").session
        session.update(fields); session.prepareCommit()
        // The main ADD succeeds, but deleting its submitting draft fails.
        failDelete = true
        try { session.finishCommitted(); fail("Expected cleanup failure") } catch (_: java.io.IOException) { }
        session.update(mapOf("amount" to "5")); session.persist()
        // Main data later returns to the empty-day fingerprint through an undo.
        val reopened = store().open("one", "empty-day")
        assertNull(reopened.values); assertEquals(fields, reopened.isolatedValues)
    }

    @Test fun delayedAutosaveCannotChangeSubmissionIntentBackIntoAnOrdinaryDraft() {
        val session = store().open("one", "base").session
        session.update(fields); session.prepareCommit()
        session.update(fields + ("note" to "较晚的内存输入")); session.persist()
        session.closed.set(true)
        val reopened = store().open("one", "base")
        assertNull(reopened.values); assertEquals("较晚的内存输入", reopened.isolatedValues?.get("note"))
    }

    @Test fun failedMainSaveCanRestoreTheDraftAndRetry() {
        val session = store().open("one", "base").session
        session.update(fields); session.prepareCommit()
        session.abortCommit()
        assertEquals(fields, store().open("one", "base").values)
    }

    @Test fun failedIntentWritePreventsCommitAndKeepsThePreviousDraft() {
        val session = store().open("one", "base").session
        session.update(fields); session.persist()
        val pending = File(file("one").path + ".new").apply { mkdir() }
        File(pending, "block").writeText("keep")
        var mainSaveCalled = false
        try { session.prepareCommit(); mainSaveCalled = true } catch (_: Exception) { }
        assertFalse(mainSaveCalled)
        assertEquals("draft", DataCodec.readJsonObject(file("one").readText())["status"])
    }

    @Test fun failedAbortRetainsTheIntentAndAllowsOnlyAnIsolatedReopen() {
        val session = store().open("one", "base").session
        session.update(fields); session.prepareCommit()
        val pending = File(file("one").path + ".new").apply { mkdir() }
        File(pending, "block").writeText("keep")
        try { session.abortCommit(); fail("Abort write must fail") } catch (_: Exception) { }
        session.closed.set(true)
        assertNull(store().open("one", "base").values)
        assertEquals(fields, store().open("one", "base").isolatedValues)
    }
}
