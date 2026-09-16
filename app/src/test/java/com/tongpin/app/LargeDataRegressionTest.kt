package com.tongpin.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Exercises years of history through the real persistence and update path, without timing thresholds. */
class LargeDataRegressionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun largeHistorySurvivesReloadAndConcurrentPlanAndFocusUpdates() {
        val start = LocalDate.of(2024, 1, 1)
        val dates = (0 until 500).map { start.plusDays(it.toLong()).toString() }
        val plans = (0 until 40).map { i -> Plan(id="study-$i",title="学习任务 $i",category=Category.STUDY,
            target=20,unit="页",startDate=start.toString()) }
        val entries = plans.flatMap { plan -> dates.mapIndexed { i, date ->
            CheckIn(plan.id,date,if(i%2==0)20 else 10,"第 $i 天的学习心得",1000L+i)
        } }
        val instant = start.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val focus = (0 until 20_000).map { i -> FocusRecord("focus-$i",plans[i%plans.size].id,1500,instant+i*60_000L) }
        val original = AppData(plans=plans,checkIns=entries,focusRecords=focus,nickname="长期积累")
        val store = AppStore(temp.root)
        store.save(original)
        val reopened = AppStore(temp.root)
        assertEquals(original,reopened.load())
        assertTrue(temp.root.resolve("tongpin-data.json").length()<DataCodec.MAX_BYTES)

        val begin=CountDownLatch(1)
        val pool=Executors.newFixedThreadPool(2)
        try {
            val rename=pool.submit { begin.await(); store.update { it.copy(nickname="保存后") } }
            val complete=pool.submit { begin.await(); reopened.update { it.copy(focusRecords=it.focusRecords+FocusRecord("last-focus",null,60,instant+30_000_000L)) } }
            begin.countDown();rename.get();complete.get()
        } finally { pool.shutdownNow() }
        val result=AppStore(temp.root).load()
        assertEquals("保存后",result.nickname)
        assertEquals(entries,result.checkIns)
        assertEquals(plans,result.plans)
        assertEquals(20_001,result.focusRecords.size)
        assertEquals(1,result.focusRecords.count { it.id=="last-focus" })
        val day=start.plusDays(498)
        assertEquals(40 to 40,dayProgress(result,day))
        assertEquals(0 to 40,dayProgress(result,day.plusDays(1)))
    }
}
