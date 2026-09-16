package com.tongpin.app

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupReminderTest {
    @get:Rule val temp = TemporaryFolder()
    private val start = 1_000_000L
    private val week = 7 * BackupReminderStore.DAY
    private val data = AppData(plans = listOf(Plan(id="one",title="阅读",category=Category.STUDY,target=1,unit="次",tracking=TrackingMode.TASK,startDate="2026-09-01")))

    @Test fun firstRealDataStartsSevenFullDaysAndEmptyDataDoesNotStartTheClock() {
        val store = BackupReminderStore(temp.root)
        assertNull(store.observe(AppData(nickname="名字"), start).firstDataAt)
        assertFalse(store.observe(data,start).due)
        assertFalse(store.observe(data,start+week-1).due)
        assertTrue(BackupReminderStore(temp.root).observe(data,start+week).due)
    }
    @Test fun emptyCurrentDataSuppressesAnOtherwiseDueReminder() {
        val store=BackupReminderStore(temp.root); store.observe(data,start)
        assertFalse(store.observe(AppData(),start+week).due)
        assertTrue(store.observe(data,start+week).due)
    }
    @Test fun anEmptyBackupDoesNotStartTheFirstRealDataClock() {
        val store=BackupReminderStore(temp.root)
        store.markExported(start)
        assertNull(store.observe(AppData(),start+week).firstDataAt)
        val firstReal=store.observe(data,start+2*week)
        assertEquals(start+2*week,firstReal.firstDataAt); assertFalse(firstReal.due)
        assertFalse(store.observe(data,start+3*week-1).due); assertTrue(store.observe(data,start+3*week).due)
    }
    @Test fun LaterAndSevenDayChoicesArePersisted() {
        val store=BackupReminderStore(temp.root); store.observe(data,start)
        store.defer(1,start+week)
        assertFalse(BackupReminderStore(temp.root).observe(data,start+week+BackupReminderStore.DAY-1).due)
        assertTrue(store.observe(data,start+week+BackupReminderStore.DAY).due)
        store.defer(7,start+week+BackupReminderStore.DAY)
        assertFalse(store.observe(data,start+2*week+BackupReminderStore.DAY-1).due)
        assertTrue(store.observe(data,start+2*week+BackupReminderStore.DAY).due)
    }
    @Test fun SuccessfulFullExportResetsTheClockAndClearsSnooze() {
        val store=BackupReminderStore(temp.root); store.observe(data,start); store.defer(7,start+week)
        store.markExported(start+week+1)
        val state=store.observe(data,start+week+2)
        assertEquals(start+week+1,state.lastExportAt); assertNull(state.snoozedUntil); assertFalse(state.due)
        assertFalse(store.observe(data,start+2*week).due); assertTrue(store.observe(data,start+2*week+1).due)
    }
    @Test fun ObservationsAndClassifiedTransferCodecsDoNotPretendAFullBackupExists() {
        val store=BackupReminderStore(temp.root); store.observe(data,start)
        TransferCodec.encode(data,TransferScope.ALL); TransferCodec.encode(data,TransferScope.PLANS)
        val status=store.observe(data,start+week)
        assertNull(status.lastExportAt); assertTrue(status.due)
    }
    @Test fun ClockRollbackDoesNotCausePrematureReminders() {
        val store=BackupReminderStore(temp.root); store.observe(data,start)
        assertFalse(store.observe(data,start-1).due)
        store.markExported(start+week)
        assertFalse(store.observe(data,start).due)
    }
    @Test fun DamagedReminderMetadataIsPreservedAndRestartsWithoutTouchingPlans() {
        val file=File(temp.root,"backup-reminder.json"); file.writeText("broken metadata")
        val status=BackupReminderStore(temp.root).observe(data,start)
        assertFalse(status.due); assertEquals(start,status.firstDataAt)
        assertEquals("broken metadata",File(file.path+".unreadable").readText())
    }
    @Test fun FailedStateWriteRetainsPreviousReminderDate() {
        val store=BackupReminderStore(temp.root); store.observe(data,start)
        val file=File(temp.root,"backup-reminder.json"); val original=file.readBytes()
        File(file.path+".new").mkdir()
        try { store.markExported(start+week); fail("Must fail") } catch (_: Exception) {}
        assertArrayEquals(original,file.readBytes()); assertTrue(store.observe(data,start+week).due)
    }
}
