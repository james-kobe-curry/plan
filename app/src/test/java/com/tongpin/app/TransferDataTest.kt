package com.tongpin.app

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class TransferDataTest {
    private val day = LocalDate.of(2026, 9, 14)
    private val reading = Plan(id = "read", title = "阅读", category = Category.STUDY,
        target = 20, unit = "页", startDate = "2026-09-01")
    private val archived = Plan(id = "walk", title = "八月散步", category = Category.FITNESS,
        target = 200, unit = "公里", scale = 2, startDate = "2026-08-01",
        archived = true, endDate = "2026-08-31", totalTarget = 4000, dueDate = "2026-08-30")
    private val unused = Plan(id = "review", title = "复习", category = Category.STUDY,
        target = 1, unit = "次", tracking = TrackingMode.TASK, startDate = day.toString())
    private val metadata = reading.copy(id = "old-record", seriesId = "old-series", title = "早期学习记录",
        recordsOnly = true, originId = "original-record")
    private val source = AppData(
        plans = listOf(reading, archived, unused, metadata),
        checkIns = listOf(CheckIn("read", day.toString(), 12, "读到第三章", 100L),
            CheckIn("walk", "2026-08-30", 250, "完成两公里半", 200L)),
        focusRecords = listOf(FocusRecord("focus-read", "read", 1500, 300L),
            FocusRecord("focus-free", null, 600, 400L),
            FocusRecord("focus-old", "old-record", 300, 500L)),
        nickname = "文件昵称",
    )

    private fun bundle(data: AppData, scope: TransferScope): TransferBundle =
        TransferCodec.decode(TransferCodec.encode(data, scope))

    private fun merge(current: AppData, data: AppData, scope: TransferScope): AppData =
        mergeTransfer(current, bundle(data, scope), scope).result

    private fun normalized(data: AppData): AppData = data.copy(plans = data.plans.sortedBy { it.id },
        checkIns = data.checkIns.sortedWith(compareBy({ it.planId }, { it.date })),
        focusRecords = data.focusRecords.sortedBy { it.id })

    private fun rejects(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java, block)

    @Test fun scopedExportsPreserveOnlyTheirDeclaredContent() {
        val plans = bundle(source, TransferScope.PLANS)
        assertEquals(TransferScope.PLANS, plans.scope)
        assertEquals(listOf(reading, archived, unused), plans.data.plans)
        assertTrue(plans.data.checkIns.isEmpty())
        assertTrue(plans.data.focusRecords.isEmpty())
        assertEquals("plan用户", plans.data.nickname)
        assertFalse(plans.legacyTemplate)

        val records = bundle(source, TransferScope.RECORDS)
        assertEquals(TransferScope.RECORDS, records.scope)
        assertEquals(listOf(reading, archived, metadata), records.data.plans)
        assertEquals(source.checkIns, records.data.checkIns)
        assertEquals(source.focusRecords, records.data.focusRecords)
        assertEquals("plan用户", records.data.nickname)
        assertEquals(source, bundle(source, TransferScope.ALL).data)
    }

    @Test fun emptyScopeFilesAndUnassignedFocusAreValid() {
        TransferScope.entries.forEach { scope ->
            val empty = bundle(AppData(), scope)
            assertEquals(AppData(), mergeTransfer(AppData(), empty, scope).result)
        }
        val freeFocus = AppData(focusRecords = listOf(FocusRecord("free", null, 60, 100L)))
        assertEquals(freeFocus, merge(AppData(), freeFocus, TransferScope.RECORDS))
    }

    @Test fun recordImportIncludesTaskInformationWithoutAddingAnySchedule() {
        val imported = merge(AppData(nickname = "本机昵称"), source, TransferScope.RECORDS)
        assertEquals("本机昵称", imported.nickname)
        assertEquals(3, imported.plans.size)
        assertTrue(imported.plans.all { it.recordsOnly })
        assertFalse(imported.plans.any { isScheduled(it, day) || isScheduled(it, LocalDate.of(2026, 8, 30)) })
        assertEquals(0 to 0, dayProgress(imported, day))
        assertEquals(source.checkIns, imported.checkIns)
        assertEquals(source.focusRecords, imported.focusRecords)
        assertEquals(source.plans.first { it.id == "walk" }.copy(recordsOnly = true), imported.plans.first { it.id == "walk" })
        assertEquals(imported, DataCodec.decode(DataCodec.encode(imported)))
        rejects { setCheckIn(imported, reading.id, day, 20) }
    }

    @Test fun plansAndRecordsCanBeImportedInEitherOrder() {
        val current = AppData(nickname = "本机昵称")
        val plansThenRecords = merge(merge(current, source, TransferScope.PLANS), source, TransferScope.RECORDS)
        val recordsThenPlans = merge(merge(current, source, TransferScope.RECORDS), source, TransferScope.PLANS)
        assertEquals(normalized(source.copy(nickname = current.nickname)), normalized(plansThenRecords))
        assertEquals(normalized(plansThenRecords), normalized(recordsThenPlans))
        assertTrue(isScheduled(recordsThenPlans.plans.first { it.id == reading.id }, day))
        assertEquals(archived, recordsThenPlans.plans.first { it.id == archived.id })
    }

    @Test fun repeatedPartialImportsDoNotDuplicatePlansCheckInsOrFocus() {
        var current = merge(merge(AppData(), source, TransferScope.RECORDS), source, TransferScope.PLANS)
        val once = normalized(current)
        repeat(3) {
            current = merge(current, source, TransferScope.RECORDS)
            current = merge(current, source, TransferScope.PLANS)
        }
        assertEquals(once, normalized(current))
    }

    @Test fun archiveDatesNotesAndActualDecimalAmountsSurviveFullRestore() {
        val different = AppData(plans = listOf(reading.copy(id = "local")), nickname = "当前昵称")
        val result = merge(different, source, TransferScope.ALL)
        assertEquals(source, result)
        val restoredArchive = result.plans.first { it.id == "walk" }
        assertTrue(restoredArchive.archived)
        assertEquals("2026-08-31", restoredArchive.endDate)
        assertEquals("2.5", formatQuantity(result.checkIns.first { it.planId == "walk" }.amount, restoredArchive))
        assertTrue(isScheduled(restoredArchive, LocalDate.of(2026, 8, 30)))
        assertFalse(isScheduled(restoredArchive, day))
        assertEquals(source.checkIns.last().note, result.checkIns.last().note)
    }

    @Test fun allBackupCanSupplyEitherScopeWithoutReplacingLocalNickname() {
        val current = AppData(nickname = "保留昵称")
        val all = bundle(source, TransferScope.ALL)
        val plans = mergeTransfer(current, all, TransferScope.PLANS).result
        val records = mergeTransfer(current, all, TransferScope.RECORDS).result
        assertEquals(normalized(merge(current, source, TransferScope.PLANS)), normalized(plans))
        assertEquals(normalized(merge(current, source, TransferScope.RECORDS)), normalized(records))
        assertEquals("保留昵称", plans.nickname)
        assertEquals("保留昵称", records.nickname)
    }

    @Test fun wrongScopeCannotBeUsedForAnotherPartialImportOrFullRestore() {
        val planFile = bundle(source, TransferScope.PLANS)
        val recordFile = bundle(source, TransferScope.RECORDS)
        rejects { mergeTransfer(AppData(), planFile, TransferScope.RECORDS) }
        rejects { mergeTransfer(AppData(), recordFile, TransferScope.PLANS) }
        rejects { mergeTransfer(AppData(), planFile, TransferScope.ALL) }
        rejects { mergeTransfer(AppData(), recordFile, TransferScope.ALL) }
        rejects { mergeTransfer(AppData(), TransferBundle(TransferScope.ALL, source, legacyTemplate = true), TransferScope.ALL) }
    }

    @Test fun partialImportNeverOverwritesLocalPlanOrConflictingEntries() {
        val localPlan = reading.copy(title = "本机任务", weekdays = setOf(1, 3, 5))
        val localCheck = CheckIn("read", day.toString(), 19, "本机记录", 900L)
        val localFocus = FocusRecord("focus-read", "read", 60, 999L)
        val current = AppData(plans = listOf(localPlan), checkIns = listOf(localCheck),
            focusRecords = listOf(localFocus), nickname = "本机昵称")
        val incoming = AppData(plans = listOf(reading), checkIns = listOf(source.checkIns.first()),
            focusRecords = listOf(source.focusRecords.first()), nickname = "其他昵称")
        assertEquals(current, merge(current, incoming, TransferScope.PLANS))
        assertEquals(current, merge(current, incoming, TransferScope.RECORDS))
    }

    @Test fun differentUnitsWithTheSamePlanIdKeepTheirOwnMetadataAndNeverMixAmounts() {
        val local = AppData(plans = listOf(reading), checkIns = listOf(CheckIn("read", day.toString(), 12, "本机页数", 10L)))
        val incomingPlan = archived.copy(id = reading.id, seriesId = reading.id, startDate = "2026-08-01")
        val incoming = AppData(plans = listOf(incomingPlan),
            checkIns = listOf(CheckIn(reading.id, day.toString(), 250, "公里记录", 100L)),
            focusRecords = listOf(FocusRecord("remote-focus", reading.id, 600, 200L)))
        val result = merge(local, incoming, TransferScope.RECORDS)
        assertEquals(reading, result.plans.first { it.id == reading.id })
        assertEquals(local.checkIns.single(), result.checkIns.first { it.planId == reading.id })
        val historical = result.plans.single { it.recordsOnly }
        assertNotEquals(reading.id, historical.id)
        assertEquals(reading.id, historical.originId)
        assertEquals("公里", historical.unit)
        assertEquals("2.5", formatQuantity(result.checkIns.single { it.planId == historical.id }.amount, historical))
        assertEquals(historical.id, result.focusRecords.single().planId)
        assertEquals(result, merge(result, incoming, TransferScope.RECORDS))
        // Re-exporting and importing the already-remapped records must still resolve their origin.
        assertEquals(result, merge(result, result, TransferScope.RECORDS))
    }

    @Test fun targetScaleAndTrackingConflictsAlsoPreserveSeparateHistoricalMeaning() {
        val pairs = listOf(reading to reading.copy(target = 30),
            reading.copy(unit = "小时", target = 2, scale = 0) to reading.copy(unit = "小时", target = 2, scale = 2),
            unused to unused.copy(tracking = TrackingMode.QUANTITY))
        pairs.forEach { (local, incoming) ->
            val result = merge(AppData(plans = listOf(local)), AppData(plans = listOf(incoming),
                checkIns = listOf(CheckIn(incoming.id, day.toString(), 1, updatedAt = 100L))), TransferScope.RECORDS)
            assertEquals(local, result.plans.first { it.id == local.id })
            assertEquals(2, result.plans.size)
            assertTrue(result.plans.single { it.id == result.checkIns.single().planId }.recordsOnly)
            assertNotEquals(local.id, result.checkIns.single().planId)
        }
    }

    @Test fun conflictingRecordMetadataDoesNotReserveARealPlanIdentityInEitherOrder() {
        val oldUnit = reading.copy(unit = "分钟", target = 30)
        val recordData = AppData(plans = listOf(oldUnit), checkIns = listOf(CheckIn("read", day.toString(), 30, updatedAt = 10L)),
            focusRecords = listOf(FocusRecord("old-focus", "read", 1800, 100L)))
        val planData = AppData(plans = listOf(reading))
        val plansFirst = merge(merge(AppData(), planData, TransferScope.PLANS), recordData, TransferScope.RECORDS)
        val recordsFirst = merge(merge(AppData(), recordData, TransferScope.RECORDS), planData, TransferScope.PLANS)
        assertEquals(normalized(plansFirst), normalized(recordsFirst))
        assertEquals(reading, recordsFirst.plans.single { !it.recordsOnly })
        assertEquals("分钟", recordsFirst.plans.single { it.recordsOnly }.unit)
        assertTrue(isScheduled(reading, day))
        assertNotEquals(reading.id, recordsFirst.checkIns.single().planId)
        assertEquals(recordsFirst.checkIns.single().planId, recordsFirst.focusRecords.single().planId)
    }

    @Test fun aliasedRecordMetadataNeverAttachesToAnUnrelatedLocalPlan() {
        val unrelatedLocal = reading.copy(id = "alias", seriesId = "alias")
        val aliased = reading.copy(id = "alias", recordsOnly = true, originId = "read")
        val incoming = AppData(plans = listOf(aliased), checkIns = listOf(CheckIn("alias", day.toString(), 7, updatedAt = 100L)))
        val result = merge(AppData(plans = listOf(unrelatedLocal)), incoming, TransferScope.RECORDS)
        assertEquals(unrelatedLocal, result.plans.first { it.id == "alias" })
        assertNotEquals("alias", result.checkIns.single().planId)
        assertEquals("read", result.plans.single { it.recordsOnly }.originId)
        assertEquals(result, merge(result, incoming, TransferScope.RECORDS))
    }

    @Test fun metadataRelinkedTwiceDuringOnePlanImportResolvesToItsFinalIdentity() {
        val alias = reading.copy(id = "alias", recordsOnly = true, originId = "read")
        val local = AppData(plans = listOf(alias),
            checkIns = listOf(CheckIn("alias", day.toString(), 7, updatedAt = 100L)),
            focusRecords = listOf(FocusRecord("focus-alias", "alias", 60, 200L)))
        val unrelated = reading.copy(id = "alias", seriesId = "alias", title = "独立的任务")
        val plans = AppData(plans = listOf(unrelated, reading))
        val result = merge(local, plans, TransferScope.PLANS)
        assertEquals(setOf(unrelated, reading), result.plans.toSet())
        assertEquals("read", result.checkIns.single().planId)
        assertEquals("read", result.focusRecords.single().planId)
        assertEquals(normalized(result), normalized(merge(local, plans.copy(plans = plans.plans.reversed()), TransferScope.PLANS)))
    }

    @Test fun exchangedMetadataAliasesKeepEachRecordAttachedToItsOwnOrigin() {
        val first = reading.copy(id = "a", seriesId = "a", title = "任务 A")
        val second = reading.copy(id = "b", seriesId = "b", title = "任务 B")
        val local = AppData(plans = listOf(
            first.copy(id = "b", recordsOnly = true, originId = "a"),
            second.copy(id = "a", recordsOnly = true, originId = "b")),
            checkIns = listOf(CheckIn("b", day.toString(), 7, "A 的记录", 100L),
                CheckIn("a", day.toString(), 9, "B 的记录", 200L)))
        val plans = AppData(plans = listOf(first, second))
        val result = merge(local, plans, TransferScope.PLANS)
        assertEquals(setOf(first, second), result.plans.toSet())
        assertEquals(7, result.checkIns.single { it.planId == "a" }.amount)
        assertEquals(9, result.checkIns.single { it.planId == "b" }.amount)
        assertEquals(normalized(result), normalized(merge(local, plans.copy(plans = plans.plans.reversed()), TransferScope.PLANS)))
    }

    @Test fun recordMetadataDoesNotConsumeActivePlanSlotsButRealPlanImportRespectsLimit() {
        val data = AppData(plans = (0..DomainValidation.MAX_PLANS).map { reading.copy(id = "metadata-$it", recordsOnly = true) })
        assertEquals(data, DataCodec.decode(DataCodec.encode(data)))
        val full = AppData(plans = (1..DomainValidation.MAX_PLANS).map { reading.copy(id = "active-$it", seriesId = "active-$it") })
        rejects { merge(full, AppData(plans = listOf(reading)), TransferScope.PLANS) }
        val result = merge(full, AppData(plans = listOf(reading), checkIns = listOf(CheckIn("read", day.toString(), 1))), TransferScope.RECORDS)
        assertEquals(DomainValidation.MAX_PLANS + 1, result.plans.size)
        assertTrue(result.plans.last().recordsOnly)
    }

    @Test fun oldVersionOneBackupsMigrateThroughTheTransferEntryPoint() {
        val file = legacyBackup()
        val result = TransferCodec.decode(file)
        assertEquals(TransferScope.ALL, result.scope)
        assertFalse(result.legacyTemplate)
        assertEquals("plan用户", result.data.nickname)
        val plan = result.data.plans.single()
        assertTrue(plan.archived)
        assertEquals("2026-09-15", plan.endDate)
        assertEquals(2, plan.scale)
        assertEquals(200, plan.target)
        assertEquals(300, result.data.checkIns.single().amount)
        assertFalse(plan.recordsOnly)
        assertNull(plan.originId)
        assertEquals(result.data, mergeTransfer(AppData(), result, TransferScope.ALL).result)
    }

    @Test fun versionTwoThreeAndCurrentFilesRoundTripWithoutLosingArchiveFields() {
        val data = source.copy(plans = source.plans.filterNot { it.recordsOnly },
            focusRecords = source.focusRecords.filterNot { it.planId == metadata.id })
        val v2 = downgrade(DataCodec.encode(data), 2)
        assertEquals(data, TransferCodec.decode(v2).data)
        assertEquals(source, TransferCodec.decode(downgrade(DataCodec.encode(source), 3)).data)
        assertEquals(source, TransferCodec.decode(DataCodec.encode(source)).data)
        assertEquals(source, TransferCodec.decode(TransferCodec.encode(source, TransferScope.ALL)).data)
    }

    @Test fun oldPlanTemplatesStartTodayAndRepeatedImportsRemainIdempotent() {
        val planEnvelope = DataCodec.readJsonObject(legacyBackup()).let { root ->
            DataCodec.writeJsonObject(linkedMapOf("format" to "tongpin-plans", "version" to 1, "plans" to root["plans"]))
        }
        val template = TransferCodec.decode(planEnvelope)
        assertTrue(template.legacyTemplate)
        assertEquals(TransferScope.PLANS, template.scope)
        val plan = template.data.plans.single()
        assertEquals(LocalDate.now().toString(), plan.startDate)
        assertEquals(plan.id, plan.seriesId)
        assertTrue(plan.id.startsWith("template-"))
        assertEquals(200, plan.target)
        assertFalse(plan.archived)
        assertNull(plan.endDate)
        val imported = mergeTransfer(AppData(), template, TransferScope.PLANS).result
        assertEquals(imported, mergeTransfer(imported, TransferCodec.decode(planEnvelope), TransferScope.PLANS).result)
        val datedTemplate = TransferCodec.decode(downgrade(DataCodec.encodePlans(listOf(archived)), 2))
        assertEquals(LocalDate.now().plusDays(29).toString(), datedTemplate.data.plans.single().dueDate)
    }

    @Test fun forgedScopeClaimsAndUnrelatedRecordMetadataAreRejected() {
        val planFile = TransferCodec.encode(source, TransferScope.PLANS)
        val allFile = TransferCodec.encode(source, TransferScope.ALL)
        rejects { TransferCodec.decode(planFile.replace("\"scope\":\"PLANS\"", "\"scope\":\"RECORDS\"")) }
        rejects { TransferCodec.decode(allFile.replace("\"scope\":\"ALL\"", "\"scope\":\"PLANS\"")) }
        rejects { TransferCodec.decode(allFile.replace("\"scope\":\"ALL\"", "\"scope\":\"RECORDS\"")) }
        rejects { mergeTransfer(AppData(), TransferBundle(TransferScope.PLANS, source), TransferScope.PLANS) }
        rejects { mergeTransfer(AppData(), TransferBundle(TransferScope.RECORDS, source), TransferScope.RECORDS) }
    }

    @Test fun malformedTransferEnvelopesAreRejectedBeforeMerging() {
        val encoded = TransferCodec.encode(source, TransferScope.ALL)
        val invalid = listOf(
            encoded.replaceFirst("\"version\":1", "\"version\":2"),
            encoded.replaceFirst("\"version\":1", "\"version\":\"1\""),
            encoded.replaceFirst("\"version\":1", "\"version\":1.0"),
            encoded.replaceFirst("\"scope\":\"ALL\"", "\"scope\":\"UNKNOWN\""),
            encoded.replaceFirst("\"scope\":\"ALL\"", "\"scope\":null"),
            encoded.replaceFirst("\"format\":\"plan-transfer\"", "\"format\":\"another-app\""),
            encoded.replaceFirst("\"scope\":\"ALL\",", ""),
            encoded.replaceFirst("{", "{\"unexpected\":true,"),
            encoded.replaceFirst("{", "{\"scope\":\"PLANS\","),
            "{\"format\":\"plan-transfer\",\"version\":1,\"scope\":\"ALL\",\"data\":[]}",
            encoded + " false",
            encoded.dropLast(1),
        )
        invalid.forEach { bad -> rejects { TransferCodec.decode(bad) } }
    }

    @Test fun invalidDomainFieldsAndRecordReferencesAreRejectedInTransferFiles() {
        val encoded = TransferCodec.encode(source, TransferScope.ALL)
        val invalid = listOf(
            encoded.replace("\"target\":20", "\"target\":0"),
            encoded.replace("\"title\":\"阅读\"", "\"title\":\"\""),
            encoded.replace("\"startDate\":\"2026-09-01\"", "\"startDate\":\"2026-02-30\""),
            encoded.replace("\"scale\":2", "\"scale\":1"),
            encoded.replace("\"recordsOnly\":true", "\"recordsOnly\":\"true\""),
            encoded.replace("\"originId\":\"original-record\"", "\"originId\":\"old-record\""),
            encoded.replace("\"originId\":\"original-record\"", "\"originId\":\"../escape\""),
            encoded.replace("\"recordsOnly\":true", "\"recordsOnly\":false"),
            encoded.replace("\"planId\":\"read\"", "\"planId\":\"missing\""),
            encoded.replace("\"amount\":12", "\"amount\":2147483648"),
            encoded.replace("\"seconds\":1500", "\"seconds\":0"),
            encoded.replace("\"updatedAt\":100", "\"updatedAt\":-1"),
            encoded.replace("\"nickname\":\"文件昵称\"", "\"nickname\":\"\\uD800\""),
        )
        invalid.forEach { bad -> rejects { TransferCodec.decode(bad) } }
    }

    @Test fun schemaVersionsCannotSmuggleNewMetadataIntoOldFormats() {
        val encoded = DataCodec.encode(source)
        rejects { TransferCodec.decode(encoded.replace("\"version\":8", "\"version\":2")) }
        rejects { TransferCodec.decode(encoded.replace("\"version\":8", "\"version\":3")) }
        rejects { TransferCodec.decode(encoded.replace("\"version\":8", "\"version\":1")) }
        rejects { TransferCodec.decode(encoded.replace("\"version\":8", "\"version\":9")) }
        rejects { TransferCodec.decode(DataCodec.encodePlans(listOf(metadata))) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun downgrade(encoded: String, version: Int): String {
        val root = DataCodec.readJsonObject(encoded).toMutableMap()
        root["version"] = version
        if (version < 8) root.remove("dailyNotes")
        if (version < 7) { root.remove("categories"); root.remove("profile") }
        if (version < 4) root.remove("collapseCompleted")
        root["plans"] = (root.getValue("plans") as List<Map<String, Any?>>).map { item ->
            item.filterKeys { key ->
                !(version < 7 && key in setOf("customCategoryId", "iconId")) &&
                !(version < 6 && key == "superseded") &&
                !(version < 5 && key == "reminderTime") &&
                !(version < 4 && key in setOf("pauses", "skips", "pinned", "sortOrder")) &&
                    !(version < 3 && key in setOf("recordsOnly", "originId"))
            }
        }
        return DataCodec.writeJsonObject(root)
    }

    private fun legacyBackup(): String = """
        {"format":"tongpin-data","version":1,
         "plans":[{"id":"legacy","title":"旧任务","category":"STUDY","target":2,"unit":"小时",
           "weekdays":[1,2,3,4,5,6,7],"startDate":"2026-09-01","archived":true,"endDate":"2026-09-15"}],
         "checkIns":[{"planId":"legacy","date":"2026-09-14","amount":3,"note":"旧心得","updatedAt":100}],
         "focusRecords":[{"id":"legacy-focus","planId":"legacy","seconds":1800,"completedAt":200}],
         "nickname":"同频学员"}
    """.trimIndent()
}
