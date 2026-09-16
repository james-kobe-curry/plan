package com.tongpin.app

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class TransferPlanStateTest {
    private val day = "2026-09-16"
    private val plan = Plan(id="read",seriesId="series",title="阅读",category=Category.STUDY,
        target=1,unit="次",tracking=TrackingMode.TASK,startDate="2026-09-01")

    @Test fun importingRecordPreservesLocalSkipAsAnExplicitConflict() {
        val current=AppData(plans=listOf(plan.copy(skips=listOf(PlanSkip(day,"休息")))))
        val incoming=AppData(plans=listOf(plan),checkIns=listOf(CheckIn(plan.id,day,1)))
        val result=mergeTransfer(current,TransferBundle(TransferScope.RECORDS,incoming),TransferScope.RECORDS)
        assertEquals(current,result.result)
        assertTrue(result.summary.contains("1 条本机冲突"))
    }

    @Test fun promotingPlanKeepsExistingRecordAndReportsImportedSkipConflict() {
        val current=AppData(plans=listOf(plan.copy(recordsOnly=true)),checkIns=listOf(CheckIn(plan.id,day,1)))
        val incoming=AppData(plans=listOf(plan.copy(skips=listOf(PlanSkip(day,"休息")))))
        val result=mergeTransfer(current,TransferBundle(TransferScope.PLANS,incoming),TransferScope.PLANS)
        assertEquals(current.checkIns,result.result.checkIns)
        assertFalse(result.result.plans.single().recordsOnly)
        assertTrue(result.result.plans.single().skips.isEmpty())
        assertTrue(isScheduled(result.result.plans.single(),LocalDate.parse(day)))
        assertTrue(result.summary.contains("1 项本机冲突"))
    }

    @Test fun fullBackupRoundTripKeepsEveryNewPlanState() {
        val data=AppData(plans=listOf(plan.copy(pauses=listOf(PlanPause("2026-09-10","2026-09-12")),
            skips=listOf(PlanSkip(day,"休息")),pinned=true,sortOrder=3,reminderTime="08:30")),collapseCompleted=true)
        val bundle=TransferCodec.decode(TransferCodec.encode(data,TransferScope.ALL))
        assertEquals(data,mergeTransfer(AppData(),bundle,TransferScope.ALL).result)
    }
}
