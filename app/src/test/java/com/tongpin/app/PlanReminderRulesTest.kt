package com.tongpin.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class PlanReminderRulesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 9, 14)
    private val plan = Plan(id="read",title="阅读",category=Category.STUDY,target=1,unit="次",tracking=TrackingMode.TASK,
        startDate=date.toString(),reminderTime="08:30")
    private fun instant(day:LocalDate=date,time:String="08:00",zone:ZoneId=this.zone)=day.atTime(java.time.LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
    private fun events(p:Plan=plan,now:Long=instant(),days:Map<String,PlanReminderDay> = emptyMap(),data:AppData=AppData(plans=listOf(p)),minimum:Long=now)=
        PlanReminderRules.candidates(data,days,now,zone,minimum)
    private fun states(state:PlanReminderDay)=mapOf(PlanReminderRules.key(state.seriesId,state.date) to state)
    private fun delivered(at:Long=instant(time="08:30"))=PlanReminderDay(plan.seriesId,date.toString(),handled=true,deliveredAt=at)
    private fun event(at:Long=instant(time="08:30"))=PlanReminderEvent(plan.id,plan.seriesId,date.toString(),at)

    @Test fun reminderTimeRoundTripsAndRejectsNonCanonicalClockValues() {
        val original=AppData(plans=listOf(plan))
        assertEquals(original,DataCodec.decode(DataCodec.encode(original)))
        listOf("8:30","24:00","00:60","12:1"," 08:30","08:30:00","１２:３０","").forEach { time ->
            assertThrows(time,IllegalArgumentException::class.java){DomainValidation.plan(plan.copy(reminderTime=time))}
        }
        listOf("00:00","23:59",null).forEach { DomainValidation.plan(plan.copy(reminderTime=it)) }
    }

    @Test fun versionFourMigratesWithRemindersDisabledWithoutLosingOtherState() {
        val original=AppData(plans=listOf(plan.copy(reminderTime=null,pinned=true,skips=listOf(PlanSkip(date.toString(),"休息")))),collapseCompleted=true)
        val root=DataCodec.readJsonObject(DataCodec.encode(original)).toMutableMap()
        root["version"]=4; root.remove("categories"); root.remove("profile"); root.remove("dailyNotes")
        @Suppress("UNCHECKED_CAST")
        root["plans"]=(root["plans"] as List<Map<String,Any?>>).map{it-"reminderTime"-"superseded"-"customCategoryId"-"iconId"}
        assertEquals(original,DataCodec.decode(DataCodec.writeJsonObject(root)))
    }

    @Test fun planTemplateAndBothBackupScopesPreserveConfiguredTime() {
        assertEquals("08:30",DataCodec.decodePlans(DataCodec.encodePlans(listOf(plan))).single().reminderTime)
        for(scope in listOf(TransferScope.PLANS,TransferScope.ALL))assertEquals("08:30",TransferCodec.decode(TransferCodec.encode(AppData(plans=listOf(plan)),scope)).data.plans.single().reminderTime)
    }

    @Test fun nextEventRespectsWeekdaysAndSkipsElapsedTimesByDefault() {
        assertEquals(instant(time="08:30"),events().single().at)
        assertEquals(instant(date.plusDays(1),"08:30"),events(now=instant(time="08:31")).single().at)
        assertEquals(instant(date.plusDays(2),"08:30"),events(plan.copy(weekdays=setOf(3))).single().at)
    }

    @Test fun validDelayedAlarmCanCatchUpWithinTheSameDayOnly() {
        assertEquals(instant(time="08:30"),events(now=instant(time="10:00"),minimum=instant(time="08:30")).single().at)
        val tomorrow=events(now=instant(date.plusDays(1),"10:00"),minimum=instant(time="08:30")).single()
        assertEquals(date.plusDays(1).toString(),tomorrow.date)
    }

    @Test fun startEndAndDeadlineBoundReminders() {
        assertEquals(instant(date.plusDays(200),"08:30"),events(plan.copy(startDate=date.plusDays(200).toString())).single().at)
        assertTrue(events(plan.copy(endDate=date.toString())).isEmpty())
        assertTrue(events(plan.copy(dueDate=date.toString()),now=instant(time="09:00")).isEmpty())
        assertEquals(date.toString(),events(plan.copy(dueDate=date.toString())).single().date)
    }

    @Test fun archivedRecordOnlyOrUnconfiguredTasksNeverRemind() {
        assertTrue(events(plan.copy(archived=true,endDate=date.plusDays(1).toString())).isEmpty())
        assertTrue(events(plan.copy(recordsOnly=true)).isEmpty())
        assertTrue(events(plan.copy(reminderTime=null)).isEmpty())
    }

    @Test fun skipsAndClosedPauseIntervalsAdvanceToTheNextRealExecutionDay() {
        val p=plan.copy(skips=listOf(PlanSkip(date.toString())),pauses=listOf(PlanPause(date.plusDays(1).toString(),date.plusDays(4).toString())))
        assertEquals(date.plusDays(4).toString(),events(p).single().date)
        assertTrue(events(p.copy(pauses=p.pauses+PlanPause(date.plusDays(4).toString()))).isEmpty())
    }

    @Test fun futureEffectivePauseKeepsTodaysPartialTaskReminderThenStopsTomorrow() {
        val p=plan.copy(target=10,tracking=TrackingMode.QUANTITY,unit="页")
        val data=pausePlan(setCheckIn(AppData(plans=listOf(p)),p.id,date,2),p.id,date)
        assertTrue(isScheduled(data.plans.single(),date))
        assertEquals(date.toString(),events(p=data.plans.single(),data=data).single().date)
        assertTrue(events(p=data.plans.single(),data=data,now=instant(date.plusDays(1))).isEmpty())
    }

    @Test fun completionSuppressesTodayWhilePartialProgressKeepsReminder() {
        val p=plan.copy(target=10,tracking=TrackingMode.QUANTITY,unit="页")
        val partial=setCheckIn(AppData(plans=listOf(p)),p.id,date,2)
        assertEquals(date.toString(),events(p,data=partial).single().date)
        val complete=setCheckIn(partial,p.id,date,10)
        assertEquals(date.plusDays(1).toString(),events(p,data=complete).single().date)
    }

    @Test fun regularReminderIsDeduplicatedBySeriesAndDate() {
        val state=delivered()
        assertEquals(date.plusDays(1).toString(),events(days=states(state)).single().date)
        val revision=plan.copy(id="replacement")
        assertEquals(date.plusDays(1).toString(),events(revision,days=states(state)).single().date)
    }

    @Test fun snoozeIsAnExplicitSameDaySecondDelivery() {
        val now=instant(time="08:32")
        val state=PlanReminderRules.action(AppData(plans=listOf(plan)),delivered(),event(),now,zone,true)!!
        assertEquals(now+15*60_000,state.snoozeAt)
        val next=events(now=now,days=states(state)).single()
        assertTrue(next.snoozed);assertEquals(state.snoozeAt,next.at)
        assertNull(PlanReminderRules.action(AppData(plans=listOf(plan)),state,event(),now+1000,zone,true))
    }

    @Test fun snoozeRechecksCompletionSkipPauseAndArchive() {
        val now=instant(time="08:32")
        val state=delivered().copy(snoozeAt=now+15*60_000)
        val variants=listOf(setCheckIn(AppData(plans=listOf(plan)),plan.id,date,1),
            skipPlanDay(AppData(plans=listOf(plan)),plan.id,date,""),pausePlan(AppData(plans=listOf(plan)),plan.id,date),
            archivePlan(AppData(plans=listOf(plan)),plan.id,date))
        variants.forEach { data -> assertTrue(events(data.plans.single(),now,states(state),data).none{it.snoozed}) }
    }

    @Test fun staleButtonsAndPreviousDateActionsDoNotChangeCurrentDelivery() {
        val data=AppData(plans=listOf(plan))
        assertNull(PlanReminderRules.action(data,delivered(instant(time="09:00")),event(),instant(time="09:01"),zone,true))
        assertNull(PlanReminderRules.action(data,delivered(),event(),instant(date.plusDays(1)),zone,true))
        assertNull(PlanReminderRules.action(AppData(),delivered(),event(),instant(time="09:00"),zone,true))
    }

    @Test fun dismissDoesNotSkipTaskOrChangeItsCompletionState() {
        val data=AppData(plans=listOf(plan))
        val next=PlanReminderRules.action(data,delivered(),event(),instant(time="08:32"),zone,false)!!
        assertTrue(next.dismissed);assertNull(next.snoozeAt)
        assertEquals(0 to 1,dayProgress(data,date))
        assertEquals(date.plusDays(1).toString(),events(days=states(next)).single().date)
    }

    @Test fun snoozeNearMidnightNeverAlertsForTomorrowsTask() {
        val p=plan.copy(reminderTime="23:45")
        val at=instant(time="23:45")
        val next=PlanReminderRules.action(AppData(plans=listOf(p)),delivered(at),event(at),instant(time="23:50"),zone,true)!!
        assertTrue(next.dismissed);assertNull(next.snoozeAt)
    }

    @Test fun changedFutureTimeReenablesOnlyPreviouslyElapsedUnsentReminder() {
        val data=AppData(plans=listOf(plan))
        val past=PlanReminderRules.configuredState(data,plan,null,true,instant(time="09:00"),zone)!!
        assertTrue(past.handled);assertNull(past.deliveredAt)
        val later=plan.copy(reminderTime="10:00")
        val changed=PlanReminderRules.configuredState(AppData(plans=listOf(later)),later,past,true,instant(time="09:01"),zone)!!
        assertFalse(changed.handled)
        assertTrue(PlanReminderRules.configuredState(AppData(plans=listOf(later)),later,delivered(),true,instant(time="09:01"),zone)!!.handled)
        assertTrue(PlanReminderRules.configuredState(AppData(plans=listOf(later)),later,past.copy(dismissed=true),true,instant(time="09:01"),zone)!!.dismissed)
    }

    @Test fun configurationChangeAndCompletedTaskClearPendingSnooze() {
        val pending=delivered().copy(snoozeAt=instant(time="09:00"))
        assertNull(PlanReminderRules.configuredState(AppData(plans=listOf(plan)),plan,pending,true,instant(time="08:40"),zone)!!.snoozeAt)
        val done=setCheckIn(AppData(plans=listOf(plan)),plan.id,date,1)
        assertNull(PlanReminderRules.configuredState(done,plan,pending,false,instant(time="08:40"),zone)!!.snoozeAt)
    }

    @Test fun longFuturePauseIsJumpedRatherThanScanningEveryCalendarDay() {
        val p=plan.copy(pauses=listOf(PlanPause(date.toString(),"9026-09-14")))
        assertEquals("9026-09-14",events(p).single().date)
    }

    @Test fun manyPlansHaveOneDeterministicEarliestAlarmCandidate() {
        val plans=(0 until 200).map{plan.copy(id="task-$it",seriesId="task-$it",reminderTime=if(it==99)"08:05" else "20:00")}
        val next=events(data=AppData(plans=plans))
        assertEquals(200,next.size)
        assertEquals("task-99",next.first().planId)
        assertEquals(instant(time="08:05"),next.first().at)
    }

    @Test fun dstGapAndOverlapUseOneLocalCalendarOccurrence() {
        val ny=ZoneId.of("America/New_York")
        val spring=plan.copy(startDate="2026-03-08",reminderTime="02:30")
        assertEquals("2026-03-08T07:30:00Z",Instant.ofEpochMilli(PlanReminderRules.at(spring,LocalDate.parse("2026-03-08"),ny)).toString())
        val autumn=plan.copy(startDate="2026-11-01",reminderTime="01:30")
        assertEquals("2026-11-01T05:30:00Z",Instant.ofEpochMilli(PlanReminderRules.at(autumn,LocalDate.parse("2026-11-01"),ny)).toString())
        val state=PlanReminderDay(autumn.seriesId,"2026-11-01",handled=true,deliveredAt=Instant.parse("2026-11-01T05:30:00Z").toEpochMilli())
        val result=PlanReminderRules.candidates(AppData(plans=listOf(autumn)),states(state),Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(),ny)
        assertEquals("2026-11-02",result.single().date)
    }
}
