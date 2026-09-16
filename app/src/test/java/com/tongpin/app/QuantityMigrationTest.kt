package com.tongpin.app

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class QuantityMigrationTest {
    private val date = LocalDate.of(2026, 9, 14)
    private val task = Plan(id = "read", title = "阅读", category = Category.STUDY, target = 1, unit = "次", tracking = TrackingMode.TASK, startDate = date.toString())
    private val distance = task.copy(target = 250, unit = "公里", tracking = TrackingMode.QUANTITY, scale = 2)

    @Test fun decimalParsingNeverRemovesThePointOrRoundsExcessPrecision() {
        assertEquals(250, parseQuantity("2.5", "公里", 2))
        assertEquals(150, parseQuantity(" 1.50 ", "小时", 2))
        assertEquals(1, parseQuantity("0.01", "km", 2))
        assertEquals(200, parseQuantity("2", "公里", 2))
        listOf("2.555", "1.", ".5", "2..5", "2,5", "1e2", "-1", "+2", "２.５", "999999999999999999999").forEach {
            assertNull(it, parseQuantity(it, "公里", 2))
        }
        assertNull(parseQuantity("2.5", "页", 0))
        assertNull(parseQuantity("2.5", "页", 2))
        assertNull(parseQuantity("2.5", "公里", 0))
        assertNull(parseQuantity("2.5", "公里", 1))
        assertNull(parseQuantity("0", "公里", 2))
        assertEquals(0, parseQuantity("0", "公里", 2, allowZero = true))
    }

    @Test fun decimalPresentationUsesThePlansPrecisionWithoutTrailingZeros() {
        assertEquals("2.5", formatQuantity(250, distance))
        assertEquals("0.01", formatQuantity(1, distance))
        assertEquals("0", formatQuantity(0, distance))
        assertEquals("2", formatQuantity(200, distance))
        assertEquals("250", formatQuantity(250, distance.copy(scale = 0)))
    }

    @Test fun ordinaryTaskRecordsCompletionAndCannotAccumulateQuantities() {
        val data = AppData(plans = listOf(task))
        assertEquals(1 to 1, dayProgress(setCheckIn(data, task.id, date, 1), date))
        assertTrue(setCheckIn(setCheckIn(data, task.id, date, 1), task.id, date, 0).checkIns.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { setCheckIn(data, task.id, date, 2) }
        assertThrows(IllegalArgumentException::class.java) { DomainValidation.plan(task.copy(target = 2)) }
        assertThrows(IllegalArgumentException::class.java) { DomainValidation.plan(task.copy(unit = "分钟")) }
        assertThrows(IllegalArgumentException::class.java) { DomainValidation.data(data.copy(checkIns = listOf(CheckIn(task.id, date.toString(), 2)))) }
    }

    @Test fun versionOneBackupMigratesWithoutChangingRealQuantitiesOrHistory() {
        val migrated = DataCodec.decode(legacyBackup())
        val plan = migrated.plans.single()
        assertEquals(TrackingMode.QUANTITY, plan.tracking)
        assertEquals(2, plan.scale)
        assertEquals(plan.id, plan.seriesId)
        assertEquals(200, plan.target)
        assertEquals("2", formatQuantity(plan.target, plan))
        assertNull(plan.totalTarget)
        assertNull(plan.dueDate)
        assertEquals(CheckIn("legacy", "2026-09-14", 300, "原记录", 100L), migrated.checkIns.single())
        assertEquals(FocusRecord("focus-old", "legacy", 1800, 200L), migrated.focusRecords.single())
        assertEquals("旧昵称", migrated.nickname)
        assertEquals(migrated, DataCodec.decode(DataCodec.encode(migrated)))
        assertEquals("plan用户", DataCodec.decode(legacyBackup().replace("旧昵称", "同频学员")).nickname)
    }

    @Test fun versionOneDecimalMigrationPreservesTheFullPreviousRange() {
        val old = legacyBackup().replace("\"target\":2", "\"target\":100000").replace("\"amount\":3", "\"amount\":1000000")
        val migrated = DataCodec.decode(old)
        val plan = migrated.plans.single()
        assertEquals(10_000_000, plan.target)
        assertEquals(100_000_000, migrated.checkIns.single().amount)
        assertEquals("100000", formatQuantity(plan.target, plan))
        assertEquals("1000000", formatQuantity(migrated.checkIns.single().amount, plan))
        assertEquals(migrated, DataCodec.decode(DataCodec.encode(migrated)))
        assertEquals(100_000_000, parseQuantity("1000000", "小时", 2))
        assertNull(parseQuantity("1000000.01", "小时", 2))
        assertThrows(IllegalArgumentException::class.java) { DataCodec.decode(old.replace("\"target\":100000", "\"target\":100001")) }
        assertThrows(IllegalArgumentException::class.java) { DataCodec.decode(old.replace("\"amount\":1000000", "\"amount\":1000001")) }
    }

    @Test fun oldCountUnitsStayIntegerAndMigratedContinuousUnitsAcceptNewFractions() {
        listOf("页", "题", "次", "分钟").forEach { unit ->
            val migrated = DataCodec.decode(legacyBackup().replace("小时", unit))
            assertEquals(0, migrated.plans.single().scale)
            assertEquals(2, migrated.plans.single().target)
            assertEquals(3, migrated.checkIns.single().amount)
        }
        listOf("小时", "公里").forEach { unit ->
            val migrated = DataCodec.decode(legacyBackup().replace("小时", unit))
            val plan = migrated.plans.single()
            val amount = parseQuantity("2.5", plan.unit, plan.scale)!!
            val next = setCheckIn(migrated, plan.id, date, amount)
            assertEquals("2.5", formatQuantity(next.checkIns.single().amount, plan))
            assertEquals(1 to 1, dayProgress(next, date))
        }
    }

    @Test fun versionTwoIntegerPlansRemainStrictlyReversible() {
        val oldStyle = distance.copy(target = 2, scale = 0)
        val data = AppData(plans = listOf(oldStyle), checkIns = listOf(CheckIn(oldStyle.id, date.toString(), 3)))
        assertEquals(data, DataCodec.decode(DataCodec.encode(data)))
    }

    @Test fun versionTwoRoundTripsTasksDecimalsDeadlinesAndRevisionLineage() {
        val original = AppData(plans = listOf(task, distance.copy(id = "run", seriesId = "run", totalTarget = 10_000, dueDate = "2026-09-30")))
        val logged = setCheckIn(original, "run", date, 125)
        assertEquals(logged, DataCodec.decode(DataCodec.encode(logged)))
        val revised = revisePlan(logged, "run", logged.plans.last().copy(target = 300), date.plusDays(1))
        assertEquals("run", revised.plans.last().seriesId)
        assertEquals(125, revised.checkIns.single().amount)
        assertEquals(2, revised.plans.count { it.seriesId == "run" })
    }

    @Test fun versionSpecificFieldsAreStrictAndNewPlansImportWithFreshLineage() {
        val encoded = DataCodec.encodePlans(listOf(distance))
        listOf(
            encoded.replace("\"version\":8", "\"version\":1"),
            encoded.replace("\"scale\":2", "\"scale\":1"),
            encoded.replace("\"tracking\":\"QUANTITY\"", "\"tracking\":\"UNKNOWN\""),
            encoded.replace("\"totalTarget\":null", "\"totalTarget\":1.5"),
        ).forEach { bad -> assertThrows(IllegalArgumentException::class.java) { DataCodec.decodePlans(bad) } }
        val imported = DataCodec.decodePlans(encoded).single()
        assertNotEquals(distance.id, imported.id)
        assertEquals(imported.id, imported.seriesId)
        assertEquals(250, imported.target)
        assertEquals(2, imported.scale)
    }

    @Test fun deadlineIsInclusiveAndDoesNotReplaceHistoricalExclusiveEnd() {
        val dated = task.copy(dueDate = "2026-09-16")
        assertTrue(isScheduled(dated, date.plusDays(2)))
        assertFalse(isScheduled(dated, date.plusDays(3)))
        val archived = dated.copy(archived = true, endDate = "2026-09-15")
        assertTrue(isScheduled(archived, date))
        assertFalse(isScheduled(archived, date.plusDays(1)))
        assertThrows(IllegalArgumentException::class.java) { DomainValidation.plan(task.copy(dueDate = "2026-09-13")) }
        assertThrows(IllegalArgumentException::class.java) { DomainValidation.plan(distance.copy(totalTarget = 1)) }
    }

    @Test fun oldCalendarDatesCanBeBackfilledAndRevisionsDoNotConsumeActivePlanSlots() {
        val original = AppData(plans = listOf(task.copy(startDate = "2020-01-01")))
        val backfilled = setCheckIn(original, task.id, LocalDate.of(2020, 6, 1), 1, "补记")
        assertEquals("2020-06-01", backfilled.checkIns.single().date)
        val historical = (0..DomainValidation.MAX_PLANS).map { task.copy(id = "old-$it", archived = true, endDate = task.startDate) }
        DomainValidation.plans(historical + task)
        assertThrows(IllegalArgumentException::class.java) {
            DomainValidation.plans((0..DomainValidation.MAX_PLANS).map { task.copy(id = "active-$it") })
        }
    }

    @Test fun movingAPlanToTheFutureKeepsItsOldEndAtTheChangeDate() {
        val original = AppData(plans = listOf(task))
        val future = date.plusDays(7)
        val revised = revisePlan(original, task.id, task.copy(startDate = future.toString()), date)
        assertEquals(date.toString(), revised.plans.first().endDate)
        assertEquals(future.toString(), revised.plans.last().startDate)
        assertEquals(0 to 0, dayProgress(revised, date))
        assertEquals(0 to 1, dayProgress(revised, future))
    }

    @Test fun aFutureTaskCanStartEarlierWithoutInventingHistoricalCheckIns() {
        val today = LocalDate.of(2026, 9, 15)
        val future = task.copy(startDate = "2026-10-01")
        val original = AppData(plans = listOf(future))
        val revised = revisePlan(original, future.id, future.copy(startDate = today.toString()), today)
        val oldVersion = revised.plans.single { it.archived }
        val newVersion = revised.plans.single { !it.archived }
        assertEquals("2026-10-01", oldVersion.startDate)
        assertEquals("2026-10-01", oldVersion.endDate)
        assertFalse(isScheduled(oldVersion, today))
        assertFalse(isScheduled(oldVersion, LocalDate.of(2026, 10, 1)))
        assertEquals(today.toString(), newVersion.startDate)
        assertTrue(isScheduled(newVersion, today))
        assertFalse(isScheduled(newVersion, today.minusDays(1)))
        assertEquals(0 to 1, dayProgress(revised, today))
        assertEquals(0 to 1, dayProgress(revised, LocalDate.of(2026, 10, 1)))
        assertTrue(revised.checkIns.isEmpty())
        assertEquals(original.focusRecords, revised.focusRecords)
    }

    companion object {
        fun legacyBackup(): String = """{"format":"tongpin-data","version":1,"plans":[{"id":"legacy","title":"阅读","category":"STUDY","target":2,"unit":"小时","weekdays":[1,2,3,4,5,6,7],"startDate":"2026-09-14","archived":false,"endDate":null}],"checkIns":[{"planId":"legacy","date":"2026-09-14","amount":3,"note":"原记录","updatedAt":100}],"focusRecords":[{"id":"focus-old","planId":"legacy","seconds":1800,"completedAt":200}],"nickname":"旧昵称"}"""
    }
}
