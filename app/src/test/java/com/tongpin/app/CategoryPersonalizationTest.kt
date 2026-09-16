package com.tongpin.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64

class CategoryPersonalizationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val day = LocalDate.of(2026, 9, 14)
    private val category = CustomCategory("english", "英语", "LANGUAGE", "BLUE")
    private val task = Plan(id = "read", title = "阅读", category = Category.STUDY, target = 20, unit = "页",
        startDate = day.toString(), customCategoryId = category.id, iconId = "STAR", reminderTime = "08:30")
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="
    private val profile = PersonalProfile("每天留一点时间给自己", "MOON", png, false)
    private fun source(): AppData {
        val archived = task.copy(id = "old", seriesId = "old", archived = true, endDate = day.plusDays(1).toString())
        return AppData(plans = listOf(task, archived), categories = listOf(category), profile = profile, nickname = "星星",
            checkIns = listOf(CheckIn(task.id, day.toString(), 12, "今天的收获", 100), CheckIn(archived.id, day.toString(), 20, "旧记录", 110)),
            focusRecords = listOf(FocusRecord("focus", archived.id, 1500, day.atTime(9, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli())))
    }
    private fun reject(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun allPersonalMetadataAndArchivedHistoryRoundTripInCurrentVersion() {
        val data = source()
        val encoded = DataCodec.encode(data)
        assertTrue(encoded.contains("\"version\":8"))
        assertEquals(data, DataCodec.decode(encoded))
        assertEquals(data, TransferCodec.decode(TransferCodec.encode(data, TransferScope.ALL)).data)
    }

    @Test fun everyEarlierDataVersionReadsWithSafePersonalDefaults() {
        val original = AppData(plans = listOf(task.copy(customCategoryId = null, iconId = null, reminderTime = null)),
            checkIns = listOf(CheckIn(task.id, day.toString(), 12, "旧记录", 100)), nickname = "原昵称")
        for (version in 1..6) {
            val migrated = DataCodec.decode(downgrade(original, version))
            assertEquals("v$version", original, migrated)
            assertTrue(migrated.categories.isEmpty())
            assertEquals(PersonalProfile(), migrated.profile)
        }
    }

    @Test fun oldVersionsRejectNewFieldsAndCurrentVersionRequiresThem() {
        val encoded = DataCodec.encode(source())
        reject { DataCodec.decode(encoded.replace("\"version\":8", "\"version\":6")) }
        val root = DataCodec.readJsonObject(encoded).toMutableMap()
        root.remove("categories")
        reject { DataCodec.decode(DataCodec.writeJsonObject(root)) }
        root["categories"] = emptyList<Any>()
        reject { DataCodec.decode(DataCodec.writeJsonObject(root)) }
    }

    @Test fun partialExportsKeepCategoryDefinitionsWithoutPersonalProfile() {
        val data = source()
        for (scope in listOf(TransferScope.PLANS, TransferScope.RECORDS)) {
            val encoded = TransferCodec.encode(data, scope)
            assertFalse(encoded.contains(profile.motto))
            assertFalse(encoded.contains(png))
            val restored = TransferCodec.decode(encoded).data
            assertEquals(data.categories, restored.categories)
            assertEquals(PersonalProfile(), restored.profile)
            assertEquals("plan用户", restored.nickname)
            assertTrue(restored.plans.all { it.customCategoryId == category.id && it.iconId == "STAR" })
        }
    }

    @Test fun recordsExportDoesNotCollectUnrelatedCategoryDefinitions() {
        val extra = category.copy(id = "unused", name = "尚未安排")
        val data = source().copy(categories = listOf(category, extra))
        assertEquals(listOf(category), TransferCodec.decode(TransferCodec.encode(data, TransferScope.RECORDS)).data.categories)
        assertEquals(data.categories, TransferCodec.decode(TransferCodec.encode(data, TransferScope.PLANS)).data.categories)
    }

    @Test fun fullImportRestoresProfileButPartialImportsKeepLocalProfile() {
        val local = AppData(nickname = "本机", profile = PersonalProfile("我的寄语", "LEAF"))
        val incoming = TransferCodec.decode(TransferCodec.encode(source(), TransferScope.ALL))
        for (scope in listOf(TransferScope.PLANS, TransferScope.RECORDS)) {
            val result = mergeTransfer(local, incoming, scope).result
            assertEquals(local.profile, result.profile)
            assertEquals(local.nickname, result.nickname)
        }
        assertEquals(source(), mergeTransfer(local, incoming, TransferScope.ALL).result)
    }

    @Test fun conflictingCategoryIdentityGetsStableRemappingAndNeverChangesLocalTasks() {
        val localCategory = category.copy(name = "本机阅读", iconId = "BOOK", colorKey = "SAGE")
        val localTask = task.copy(id = "local", seriesId = "local")
        val local = AppData(plans = listOf(localTask), categories = listOf(localCategory))
        val incoming = TransferCodec.decode(TransferCodec.encode(source(), TransferScope.PLANS))
        val first = mergeTransfer(local, incoming, TransferScope.PLANS).result
        assertEquals(localTask, first.plans.single { it.id == "local" })
        assertEquals(localCategory, first.categories.single { it.id == category.id })
        val importedId = first.plans.single { it.id == task.id }.customCategoryId!!
        assertNotEquals(category.id, importedId)
        assertEquals(category.copy(id = importedId), first.categories.single { it.id == importedId })
        assertEquals(importedId, first.plans.single { it.id == "old" }.customCategoryId)
        assertEquals(first, mergeTransfer(first, incoming, TransferScope.PLANS).result)
    }

    @Test fun identicalCategoryContentReusesExistingDefinitionEvenAcrossDifferentIds() {
        val local = AppData(categories = listOf(category.copy(id = "local-label")))
        val result = mergeTransfer(local, TransferCodec.decode(TransferCodec.encode(source(), TransferScope.PLANS)), TransferScope.PLANS).result
        assertEquals(local.categories, result.categories)
        assertTrue(result.plans.all { it.customCategoryId == "local-label" })
    }

    @Test fun sameNamedImportedCategoryKeepsLocalColorsAndIsIdempotent() {
        val localCategory = category.copy(id = "local", name = "English", iconId = "BOOK", colorKey = "SAGE")
        val incomingCategory = category.copy(name = "ENGLISH")
        val data = source().copy(categories = listOf(incomingCategory))
        val local = AppData(categories = listOf(localCategory))
        val bundle = TransferCodec.decode(TransferCodec.encode(data, TransferScope.PLANS))
        val result = mergeTransfer(local, bundle, TransferScope.PLANS).result
        assertEquals(listOf(localCategory), result.categories)
        assertTrue(result.plans.all { it.customCategoryId == localCategory.id })
        assertEquals(result, mergeTransfer(result, bundle, TransferScope.PLANS).result)
        assertEquals(localCategory, saveCustomCategory(result, localCategory).categories.single())
    }

    @Test fun recordsThenPlansWithCategoryCollisionPromoteMetadataWithoutLosingHistory() {
        val local = AppData(categories = listOf(category.copy(name = "不同的本机分类")))
        val data = source()
        val records = TransferCodec.decode(TransferCodec.encode(data, TransferScope.RECORDS))
        val plans = TransferCodec.decode(TransferCodec.encode(data, TransferScope.PLANS))
        val onlyRecords = mergeTransfer(local, records, TransferScope.RECORDS).result
        assertTrue(onlyRecords.plans.all { it.recordsOnly })
        val result = mergeTransfer(onlyRecords, plans, TransferScope.PLANS).result
        assertEquals(data.checkIns, result.checkIns)
        assertEquals(data.focusRecords, result.focusRecords)
        assertTrue(result.plans.none { it.recordsOnly })
        assertEquals(2, result.categories.size)
        assertTrue(result.plans.all { it.customCategoryId != category.id })
        assertEquals(result, mergeTransfer(result, records, TransferScope.RECORDS).result)
    }

    @Test fun importedCategoryCapacityIsCheckedBeforeAnyMutation() {
        val full = AppData(categories = (0 until 20).map { category.copy(id = "c$it", name = "分类$it") })
        reject { mergeTransfer(full, TransferCodec.decode(TransferCodec.encode(source(), TransferScope.PLANS)), TransferScope.PLANS) }
        assertEquals(20, full.categories.size)
    }

    @Test fun deletingCategoryRelabelsEveryVersionButKeepsAllHistoryAndOwnIcons() {
        val data = source()
        val removed = deleteCustomCategory(data, category.id)
        assertTrue(removed.categories.isEmpty())
        assertEquals(data.checkIns, removed.checkIns)
        assertEquals(data.focusRecords, removed.focusRecords)
        assertEquals(data.profile, removed.profile)
        assertEquals(data.plans.map { it.copy(customCategoryId = null) }, removed.plans)
        assertTrue(removed.plans.all { it.iconId == "STAR" })
        assertEquals(removed, DataCodec.decode(DataCodec.encode(removed)))
    }

    @Test fun categoryEditsPreserveIdentityAndRejectDuplicatesInvalidKeysAndOverflow() {
        val original = source()
        val revised = saveCustomCategory(original, category.copy(name = "英语练习", iconId = "EDIT", colorKey = "LAVENDER"))
        assertEquals(original.plans, revised.plans)
        assertEquals(original.checkIns, revised.checkIns)
        assertEquals("英语练习", planCategoryName(task, revised))
        reject { saveCustomCategory(original, category.copy(id = "other")) }
        reject { saveCustomCategory(original, category.copy(name = " ")) }
        reject { saveCustomCategory(original, category.copy(name = "学习")) }
        reject { DomainValidation.data(original.copy(categories = listOf(category, category.copy(id = "other", iconId = "EDIT")))) }
        reject { saveCustomCategory(original, category.copy(name = "名".repeat(17))) }
        reject { saveCustomCategory(original, category.copy(iconId = "untrusted")) }
        reject { saveCustomCategory(original, category.copy(colorKey = "#ffffff")) }
        reject { DomainValidation.data(original.copy(categories = original.categories + category)) }
        reject { DomainValidation.data(original.copy(plans = listOf(task.copy(iconId = "unknown")))) }
    }

    @Test fun changingCategoryOrIconIsImmediateMetadataWithoutCreatingScheduleVersion() {
        val original = source()
        val edited = revisePlan(original, task.id, task.copy(customCategoryId = null, iconId = "MUSIC"), day)
        assertEquals(original.plans.size, edited.plans.size)
        assertEquals(original.checkIns, edited.checkIns)
        assertEquals(original.focusRecords, edited.focusRecords)
        assertEquals("MUSIC", edited.plans.single { it.id == task.id }.iconId)
        assertNull(edited.plans.single { it.id == task.id }.customCategoryId)
    }

    @Test fun timedSuccessorReceivesTheNewPersonalMetadataWithoutChangingAmounts() {
        val original = source()
        val edited = revisePlan(original, task.id, task.copy(target = 30, iconId = "EDIT"), day)
        val successor = edited.plans.single { !it.archived }
        assertEquals(category.id, successor.customCategoryId)
        assertEquals("EDIT", successor.iconId)
        assertEquals(original.checkIns, edited.checkIns)
        assertEquals("EDIT", edited.plans.single { it.id == task.id }.iconId)
    }

    @Test fun taskTemplateKeepsCustomCategoryAndIconWithoutCopyingPersonalProfile() {
        val template = TransferCodec.decode(DataCodec.encodePlans(listOf(task), listOf(category)))
        assertEquals(listOf(category), template.data.categories)
        assertEquals(category.id, template.data.plans.single().customCategoryId)
        assertEquals("STAR", template.data.plans.single().iconId)
        assertEquals(PersonalProfile(), template.data.profile)
        val first = mergeTransfer(AppData(), template, TransferScope.PLANS).result
        assertEquals(first, mergeTransfer(first, template, TransferScope.PLANS).result)
    }

    @Test fun namedAndAutomaticSnapshotsRestoreProfileAndArchiveMetadata() {
        val data = source()
        val named = SnapshotStore(temporary.newFolder("snapshots"))
        assertEquals(data, named.read(named.create("我的学习空间", data).id))
        val files = temporary.newFolder("app")
        AppStore(files).save(data)
        val automatic = AutoSnapshotStore(files)
        val snapshot = requireNotNull(automatic.checkpoint(day, 100))
        assertEquals(data, automatic.read(snapshot.id))
    }

    @Test fun customCategoryReviewDoesNotDoubleCountBuiltinLearningAndRetainsNotes() {
        val data = source()
        val result = PeriodReviewIndex(data, ZoneId.of("UTC")).summarize(reviewPeriod(ReviewPeriodKind.WEEK, day, day))
        val custom = result.categories.single { it.customCategory?.id == category.id }
        assertEquals("英语", custom.label)
        assertEquals(1, custom.counts.completed)
        assertEquals(1, custom.counts.unfinished)
        assertEquals(2, custom.records)
        assertEquals(1500L, custom.focusSeconds)
        assertEquals(0, result.categories.single { it.key == "builtin:STUDY" }.counts.expected)
        assertEquals(result.counts.expected, result.categories.sumOf { it.counts.expected })
        assertTrue(result.notes.all { it.categoryLabel == "英语" })
    }

    @Test fun planFingerprintStaysCompatibleForOlderDraftsAndChangesForNewIcons() {
        val original = task.copy(customCategoryId = null, iconId = null)
        assertEquals(draftHash(downgrade(AppData(plans = listOf(original)), 6)), draftPlanFingerprint(original))
        assertNotEquals(draftPlanFingerprint(original), draftPlanFingerprint(original.copy(iconId = "STAR")))
        assertNotEquals(draftPlanFingerprint(original), draftPlanFingerprint(original.copy(customCategoryId = category.id)))
    }

    @Test fun validProfileAndAvatarRoundTripWhileMalformedOrOversizedValuesAreRejected() {
        PersonalizationRules.profile(profile)
        reject { PersonalizationRules.profile(profile.copy(motto = "字".repeat(81))) }
        reject { PersonalizationRules.profile(profile.copy(avatarId = "unknown")) }
        reject { PersonalizationRules.profile(profile.copy(avatarImage = "invalid base64")) }
        reject { PersonalizationRules.profile(profile.copy(avatarImage = png + "\n")) }
        reject { PersonalizationRules.profile(profile.copy(avatarImage = Base64.getEncoder().encodeToString(ByteArray(100)))) }
        reject { PersonalizationRules.profile(profile.copy(avatarImage = "A".repeat(65_540))) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun downgrade(data: AppData, version: Int): String {
        val root = DataCodec.readJsonObject(DataCodec.encode(data)).toMutableMap()
        root["version"] = version
        root.remove("dailyNotes")
        root.remove("categories"); root.remove("profile")
        if (version < 4) root.remove("collapseCompleted")
        root["plans"] = (root["plans"] as List<Map<String, Any?>>).map { item -> item.filterKeys { key ->
            key !in setOf("customCategoryId", "iconId") && !(version < 6 && key == "superseded") &&
                !(version < 5 && key == "reminderTime") && !(version < 4 && key in setOf("pauses", "skips", "pinned", "sortOrder")) &&
                !(version < 3 && key in setOf("recordsOnly", "originId")) &&
                !(version < 2 && key in setOf("tracking", "scale", "seriesId", "totalTarget", "dueDate"))
        } }
        return DataCodec.writeJsonObject(root)
    }
}
