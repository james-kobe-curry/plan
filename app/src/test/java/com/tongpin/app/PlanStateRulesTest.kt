package com.tongpin.app

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PlanStateRulesTest {
    private val monday = LocalDate.of(2026, 9, 14)
    private val task = Plan(id="read",title="阅读",category=Category.STUDY,target=1,unit="次",tracking=TrackingMode.TASK,startDate=monday.toString())
    private val initial = AppData(plans=listOf(task))

    @Test fun skipRemovesOnlyThatDaysExpectationAndCanBeUndone() {
        val skipped=skipPlanDay(initial,task.id,monday,"  休息一下  ")
        assertEquals(0 to 0,dayProgress(skipped,monday))
        assertEquals(0 to 1,dayProgress(skipped,monday.plusDays(1)))
        assertEquals("休息一下",skipped.plans.single().skips.single().reason)
        assertEquals(initial,undoPlanSkip(skipped,task.id,monday))
        assertThrows(IllegalArgumentException::class.java){setCheckIn(skipped,task.id,monday,1)}
    }

    @Test fun skipCannotEraseCompletedOrPartialProgress() {
        val count=task.copy(tracking=TrackingMode.QUANTITY,target=20,unit="页")
        val logged=setCheckIn(AppData(plans=listOf(count)),count.id,monday,2,"读了两页")
        assertThrows(IllegalArgumentException::class.java){skipPlanDay(logged,count.id,monday,"")}
        assertThrows(IllegalArgumentException::class.java){skipPlanDay(initial,task.id,monday.minusDays(1),"")}
        assertThrows(IllegalArgumentException::class.java){DomainValidation.data(logged.copy(plans=listOf(count.copy(skips=listOf(PlanSkip(monday.toString()))))))}
    }

    @Test fun pauseAndResumeKeepTheExactHistoricalGap() {
        val paused=pausePlan(initial,task.id,monday.plusDays(1))
        assertTrue(isPlanPaused(paused.plans.single()))
        val resumed=resumePlan(paused,task.id,monday.plusDays(4))
        assertFalse(isPlanPaused(resumed.plans.single()))
        assertEquals(0 to 1,dayProgress(resumed,monday))
        (1L..3L).forEach{assertEquals(0 to 0,dayProgress(resumed,monday.plusDays(it)))}
        assertEquals(0 to 1,dayProgress(resumed,monday.plusDays(4)))
        assertEquals(resumed,DataCodec.decode(DataCodec.encode(resumed)))
    }

    @Test fun pausingAfterCheckInPreservesTodayAndStartsTomorrow() {
        val logged=setCheckIn(initial,task.id,monday,1,"完成")
        val paused=pausePlan(logged,task.id,monday)
        assertEquals(logged.checkIns,paused.checkIns)
        assertEquals(monday.plusDays(1).toString(),paused.plans.single().pauses.single().startDate)
        assertEquals(1 to 1,dayProgress(paused,monday))
        assertEquals(0 to 0,dayProgress(paused,monday.plusDays(1)))
        assertEquals(logged,resumePlan(paused,task.id,monday))
    }

    @Test fun sameDayPauseUndoDoesNotInventEmptyDateRanges() {
        assertEquals(initial,resumePlan(pausePlan(initial,task.id,monday),task.id,monday))
        val future=initial.copy(plans=listOf(task.copy(startDate=monday.plusDays(10).toString())))
        val paused=pausePlan(future,task.id,monday)
        assertEquals(monday.plusDays(10).toString(),paused.plans.single().pauses.single().startDate)
        assertEquals(future,resumePlan(paused,task.id,monday))
    }

    @Test fun changingPausedTaskDoesNotEraseTheGapOrReopenOldDays() {
        val paused=pausePlan(initial,task.id,monday)
        val revised=revisePlan(paused,task.id,task.copy(title="继续阅读"),monday.plusDays(2))
        val current=revised.plans.single{!it.archived}
        assertTrue(isPlanPaused(current))
        assertEquals(monday.toString(),current.pauses.single().startDate)
        val resumed=resumePlan(revised,current.id,monday.plusDays(4))
        (0L..3L).forEach{assertEquals(0 to 0,dayProgress(resumed,monday.plusDays(it)))}
        assertEquals(0 to 1,dayProgress(resumed,monday.plusDays(4)))
    }

    @Test fun postponingARevisionClipsExclusionsToItsNewStart() {
        val p=task.copy(pauses=listOf(PlanPause(monday.toString(),monday.plusDays(2).toString()),PlanPause(monday.plusDays(4).toString())),skips=listOf(PlanSkip(monday.plusDays(3).toString())))
        val revised=revisePlan(initial.copy(plans=listOf(p)),p.id,p.copy(startDate=monday.plusDays(8).toString()),monday.plusDays(5))
        val current=revised.plans.single{!it.archived}
        assertEquals(listOf(PlanPause(monday.plusDays(8).toString())),current.pauses)
        assertTrue(current.skips.isEmpty())
        DomainValidation.data(revised)
    }

    @Test fun restoredArchiveRetainsHistoryLineageAndProgress() {
        val logged=setCheckIn(initial,task.id,monday,1,"第一天")
            .copy(focusRecords=listOf(FocusRecord("f",task.id,60,100)))
        val archived=archivePlan(logged,task.id,monday)
        val restored=restoreArchivedPlan(archived,task.id,monday.plusDays(3),monday)
        assertEquals(logged.checkIns,restored.checkIns)
        assertEquals(logged.focusRecords,restored.focusRecords)
        val current=restored.plans.single{!it.archived}
        assertNotEquals(task.id,current.id)
        assertEquals(task.seriesId,current.seriesId)
        assertEquals(1 to 1,dayProgress(restored,monday))
        assertEquals(0 to 0,dayProgress(restored,monday.plusDays(2)))
        assertEquals(0 to 1,dayProgress(restored,monday.plusDays(3)))
        assertThrows(IllegalArgumentException::class.java){restoreArchivedPlan(restored,task.id,monday.plusDays(5),monday)}
    }

    @Test fun restoreNeverOverlapsLoggedOrHistoricalDates() {
        val archived=archivePlan(setCheckIn(initial,task.id,monday,1),task.id,monday)
        assertThrows(IllegalArgumentException::class.java){restoreArchivedPlan(archived,task.id,monday,monday)}
        assertThrows(IllegalArgumentException::class.java){restoreArchivedPlan(archived,task.id,monday.minusDays(1),monday)}
        val legacy=archived.copy(plans=listOf(archived.plans.single().copy(endDate=null)))
        assertThrows(IllegalArgumentException::class.java){restoreArchivedPlan(legacy,task.id,monday,monday)}
    }

    @Test fun archivedFutureTaskCanRestartTodayIfItsOldScheduleNeverBegan() {
        val future=initial.copy(plans=listOf(task.copy(startDate=monday.plusDays(30).toString())))
        val archived=archivePlan(future,task.id,monday)
        val restored=restoreArchivedPlan(archived,task.id,monday,monday)
        assertEquals(0 to 1,dayProgress(restored,monday))
        assertEquals(0 to 1,dayProgress(restored,monday.plusDays(30)))
    }

    @Test fun expiredRestoreClearsOldDeadlineAndPauseButKeepsGoal() {
        val p=task.copy(tracking=TrackingMode.QUANTITY,unit="页",target=5,totalTarget=100,dueDate=monday.plusDays(2).toString(),
            archived=true,endDate=monday.plusDays(3).toString(),pauses=listOf(PlanPause(monday.toString())),skips=listOf(PlanSkip(monday.toString(),"休息")))
        val restored=restoreArchivedPlan(AppData(plans=listOf(p)),p.id,monday.plusDays(6),monday.plusDays(6)).plans.last()
        assertNull(restored.dueDate)
        assertEquals(100,restored.totalTarget)
        assertTrue(restored.pauses.isEmpty())
        assertTrue(restored.skips.isEmpty())
    }

    @Test fun deadlinesRemainInclusiveAndVisibleAfterExpiration() {
        val p=task.copy(dueDate=monday.toString())
        assertFalse(isPlanExpired(p,monday))
        assertTrue(isPlanExpired(p,monday.plusDays(1)))
        assertTrue(isScheduled(p,monday))
        assertThrows(IllegalArgumentException::class.java){pausePlan(AppData(plans=listOf(p)),p.id,monday.plusDays(1))}
    }

    @Test fun pinAndOrderingFollowTheSeriesAcrossRevisions() {
        val other=task.copy(id="other",seriesId="other")
        val third=task.copy(id="third",seriesId="third")
        val data=initial.copy(plans=listOf(task,other,third))
        val moved=movePlan(data,third.id,-1,listOf(task.id,other.id,third.id))
        assertEquals(listOf("read","third","other"),scheduledPlansOrdered(moved,monday).map{it.id})
        val pinned=setPlanPinned(moved,other.id,true)
        assertEquals(listOf("other","read","third"),scheduledPlansOrdered(pinned,monday).map{it.id})
        val revised=revisePlan(pinned,other.id,other.copy(title="新安排"),monday)
        assertTrue(revised.plans.filter{it.seriesId==other.seriesId}.all{it.pinned})
        assertEquals(1,revised.plans.filter{it.seriesId==other.seriesId}.map{it.sortOrder}.distinct().size)
    }

    @Test fun orderingFilteredRowsKeepsUnrelatedRowsAtTheirPositions() {
        val data=initial.copy(plans=listOf(task,task.copy(id="b",seriesId="b"),task.copy(id="c",seriesId="c")))
        val moved=movePlan(data,"c",-1,listOf("read","c"))
        assertEquals(listOf("c","b","read"),scheduledPlansOrdered(moved,monday).map{it.id})
        assertEquals(listOf("read","b","c"),scheduledPlansOrdered(data,monday).map{it.id})
        assertThrows(IllegalArgumentException::class.java){movePlan(data,"c",-1,listOf("c","c"))}
        assertThrows(IllegalArgumentException::class.java){movePlan(setPlanPinned(data,"c",true),"c",-1,listOf("read","c"))}
    }

    @Test fun allNewStateRoundTripsAndOldVersionThreeGetsSafeDefaults() {
        val p=task.copy(pinned=true,sortOrder=7,pauses=listOf(PlanPause(monday.plusDays(1).toString(),monday.plusDays(3).toString())),skips=listOf(PlanSkip(monday.toString(),"原因")))
        val data=initial.copy(plans=listOf(p),collapseCompleted=true)
        assertEquals(data,DataCodec.decode(DataCodec.encode(data)))
        val root=DataCodec.readJsonObject(DataCodec.encode(initial)).toMutableMap()
        root["version"]=3;root.remove("collapseCompleted");root.remove("categories");root.remove("profile"); root.remove("dailyNotes")
        @Suppress("UNCHECKED_CAST")
        root["plans"]=(root["plans"] as List<Map<String,Any?>>).map{it-filterSet}
        assertEquals(initial,DataCodec.decode(DataCodec.writeJsonObject(root)))
        assertThrows(IllegalArgumentException::class.java){DataCodec.decode(DataCodec.encode(data).replace("\"sortOrder\":7","\"sortOrder\":-1"))}
    }

    @Test fun invalidOverlappingPauseOrDuplicateSkipIsRejected() {
        assertThrows(IllegalArgumentException::class.java){DomainValidation.plan(task.copy(pauses=listOf(PlanPause(monday.toString(),monday.toString()))))}
        assertThrows(IllegalArgumentException::class.java){DomainValidation.plan(task.copy(pauses=listOf(PlanPause(monday.toString()),PlanPause(monday.plusDays(1).toString()))))}
        assertThrows(IllegalArgumentException::class.java){DomainValidation.plan(task.copy(pauses=listOf(PlanPause(monday.toString(),monday.plusDays(3).toString()),PlanPause(monday.plusDays(2).toString()))))}
        assertThrows(IllegalArgumentException::class.java){DomainValidation.plan(task.copy(skips=listOf(PlanSkip(monday.toString()),PlanSkip(monday.toString(),"重复"))))}
    }

    @Test fun templateImportStartsFreshWithoutInheritedAbsencesOrPins() {
        val p=task.copy(pinned=true,sortOrder=4,pauses=listOf(PlanPause(monday.toString())),skips=listOf(PlanSkip(monday.toString())))
        val imported=DataCodec.decodePlans(DataCodec.encodePlans(listOf(p))).single()
        assertTrue(imported.pauses.isEmpty());assertTrue(imported.skips.isEmpty());assertFalse(imported.pinned);assertEquals(0,imported.sortOrder)
    }

    @Test fun goalAllocationExcludesPauseDaysAndSkipsWithoutDoubleSubtraction() {
        val pauses=listOf(PlanPause(monday.plusDays(1).toString(),monday.plusDays(4).toString()))
        val skips=listOf(PlanSkip(monday.toString()),PlanSkip(monday.plusDays(2).toString()))
        assertEquals(1L,countExecutionDays(monday,monday.plusDays(6),(1..5).toSet(),pauses,skips))
        assertEquals(0L,countExecutionDays(monday,monday.plusDays(6),(1..7).toSet(),listOf(PlanPause(monday.toString()))))
        assertEquals(10L,countExecutionDays(monday,monday.plusDays(13),(1..5).toSet()))
    }

    companion object { private val filterSet=setOf("pauses","skips","pinned","sortOrder","reminderTime","superseded","customCategoryId","iconId") }
}
