package com.tongpin.app

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FocusPersistenceTest {
    private data class State(val running: Boolean, val pending: List<String>)
    private val previous = State(false, emptyList())
    private val started = State(true, emptyList())

    @Test fun successfulWritePublishesBeforeCallerStartsRuntime() {
        var disk = previous
        var restored = false
        persistFocusChange(previous, commit = { disk = started; true }, restore = { restored = true; false })
        assertEquals(started, disk)
        assertFalse(restored)
    }

    @Test fun falseCommitRestoresPrematurelyPublishedMemoryAndDoesNotStartRuntime() {
        var memory = previous
        var disk = previous
        var runtimeStarted = false
        assertThrows(FocusPersistenceException::class.java) {
            persistFocusChange(previous, commit = { memory = started; false }, restore = {
                memory = it; disk = it; true
            })
            runtimeStarted = true
        }
        assertEquals(previous, memory)
        assertEquals(previous, disk)
        assertFalse(runtimeStarted)
    }

    @Test fun persistentDiskFailureStillRestoresMemoryAndReportsFailure() {
        var memory = previous
        val error = assertThrows(FocusPersistenceException::class.java) {
            persistFocusChange(previous, commit = { memory = started; false }, restore = {
                // Android updates memory before commit returns false on this second write too.
                memory = it; false
            })
        }
        assertEquals(previous, memory)
        assertEquals(1, error.suppressed.size)
        assertTrue(error.message!!.contains("未能保存"))
    }

    @Test fun thrownWriteFailureRestoresStateAndKeepsOriginalCause() {
        val failure = IOException("injected write failure")
        var memory = previous
        val error = assertThrows(FocusPersistenceException::class.java) {
            persistFocusChange(previous, commit = { memory = started; throw failure }, restore = {
                memory = it; true
            })
        }
        assertEquals(previous, memory)
        assertSame(failure, error.cause)
    }

    @Test fun thrownRollbackFailureDoesNotMaskOriginalWriteFailure() {
        val writeFailure = IOException("write")
        val restoreFailure = IOException("restore")
        val error = assertThrows(FocusPersistenceException::class.java) {
            persistFocusChange(previous, commit = { throw writeFailure }, restore = { throw restoreFailure })
        }
        assertSame(writeFailure, error.cause)
        assertSame(restoreFailure, error.suppressed.single())
    }

    @Test fun failedCompletionKeepsRunningStateAndDoesNotPublishPendingEvent() {
        val running = State(true, emptyList())
        var memory = running
        var delivered = false
        assertThrows(FocusPersistenceException::class.java) {
            persistFocusChange(running, commit = { memory = State(false, listOf("session")); false }, restore = {
                memory = it; true
            })
            delivered = true
        }
        assertEquals(running, memory)
        assertFalse(delivered)
    }

    @Test fun failedAcknowledgementKeepsPendingRecordAvailableForDeduplicatedRetry() {
        val completed = State(false, listOf("session"))
        var memory = completed
        assertThrows(FocusPersistenceException::class.java) {
            persistFocusChange(completed, commit = { memory = State(false, emptyList()); false }, restore = {
                memory = it; true
            })
        }
        assertEquals(completed, memory)
    }

    @Test fun failedResetRestoresTimerAndPendingCompletionsTogether() {
        val old = State(true, listOf("earlier-session"))
        var memory = old
        assertThrows(FocusPersistenceException::class.java) {
            persistFocusChange(old, commit = { memory = State(false, emptyList()); false }, restore = {
                memory = it; true
            })
        }
        assertEquals(old, memory)
    }

    @Test fun failedCompletionCanBeRetriedAndPublishedExactlyOnceAfterStorageRecovers() {
        val running = State(true, emptyList())
        val finished = State(false, listOf("session"))
        var memory = running
        var disk = running
        var delivered = 0
        assertThrows(FocusPersistenceException::class.java) {
            persistFocusChange(running, commit = { memory = finished; false }, restore = {
                memory = it; false
            })
            delivered++
        }
        assertEquals(running, memory)
        assertEquals(running, disk)
        persistFocusChange(memory, commit = { memory = finished; disk = finished; true }, restore = {
            memory = it; disk = it; true
        })
        delivered++
        assertEquals(finished, memory)
        assertEquals(finished, disk)
        assertEquals(1, delivered)
    }
}
