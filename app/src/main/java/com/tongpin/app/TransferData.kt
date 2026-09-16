package com.tongpin.app

import java.security.MessageDigest
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class TransferScope { PLANS, RECORDS, ALL }

data class TransferBundle(val scope: TransferScope, val data: AppData, val legacyTemplate: Boolean = false)
data class ImportPreview(val result: AppData, val summary: String)

/** Versioned envelopes reuse the strict, bounded data schema and accept earlier exported files. */
object TransferCodec {
    fun encode(data: AppData, scope: TransferScope): String {
        DomainValidation.data(data)
        val selected = when (scope) {
            TransferScope.PLANS -> AppData(plans = data.plans.filterNot { it.recordsOnly }, categories = data.categories)
            TransferScope.RECORDS -> {
                val referenced = data.checkIns.map { it.planId }.toSet() + data.focusRecords.mapNotNull { it.planId }
                AppData(plans = data.plans.filter { it.id in referenced }, checkIns = data.checkIns, focusRecords = data.focusRecords,
                    categories = data.categories.filter { category -> data.plans.any { it.id in referenced && it.customCategoryId == category.id } },
                    dailyNotes = data.dailyNotes)
            }
            TransferScope.ALL -> data
        }
        return DataCodec.writeJsonObject(linkedMapOf(
            "format" to "plan-transfer", "version" to 1, "scope" to scope.name,
            "data" to DataCodec.readJsonObject(DataCodec.encode(selected)),
        ))
    }

    fun decode(text: String): TransferBundle {
        val root = DataCodec.readJsonObject(text)
        return when (root["format"]) {
            "tongpin-data" -> TransferBundle(TransferScope.ALL, DataCodec.decode(text))
            "tongpin-plans" -> {
                val archive = DataCodec.decodePlanArchiveData(text)
                val plans = archive.plans
                require(plans.isNotEmpty() && plans.none { it.recordsOnly }) { "文件中没有可导入的计划模板" }
                val today = LocalDate.now()
                val templates = plans.map { plan ->
                    val id = "template-" + digest(DataCodec.planFingerprint(plan)).take(40)
                    val days = plan.dueDate?.let { ChronoUnit.DAYS.between(LocalDate.parse(plan.startDate), LocalDate.parse(it)) }
                    plan.copy(id = id, seriesId = id, startDate = today.toString(), dueDate = days?.let { today.plusDays(it).toString() },
                        archived = false, endDate = null, recordsOnly = false, originId = null, superseded = false,
                        pauses = emptyList(), skips = emptyList(), pinned = false, sortOrder = 0, reminderTime = null)
                }
                TransferBundle(TransferScope.PLANS, AppData(plans = templates, categories = archive.categories), legacyTemplate = true)
            }
            "plan-transfer" -> {
                require(root.keys == setOf("format", "version", "scope", "data")) { "传输文件字段缺失或包含不支持的字段" }
                require(root["version"] == 1L) { "不支持此传输文件版本" }
                val scope = try { TransferScope.valueOf(root["scope"] as? String ?: "") }
                    catch (_: IllegalArgumentException) { throw IllegalArgumentException("传输内容范围无效") }
                @Suppress("UNCHECKED_CAST")
                val value = root["data"] as? Map<String, Any?> ?: throw IllegalArgumentException("传输数据必须为对象")
                val data = DataCodec.decode(DataCodec.writeJsonObject(value))
                validateScope(scope, data)
                TransferBundle(scope, data)
            }
            else -> throw IllegalArgumentException("请选择 plan 导出的计划、记录或完整备份文件")
        }.also { DomainValidation.data(it.data) }
    }

    internal fun validateScope(scope: TransferScope, data: AppData) {
        when (scope) {
            TransferScope.PLANS -> require(data.checkIns.isEmpty() && data.focusRecords.isEmpty() && data.dailyNotes.isEmpty() && data.plans.none { it.recordsOnly }) {
                "计划文件不能包含打卡、专注、每日心得或记录专用资料"
            }
            TransferScope.RECORDS -> {
                val referenced = data.checkIns.map { it.planId }.toSet() + data.focusRecords.mapNotNull { it.planId }
                require(data.plans.map { it.id }.toSet() == referenced) { "记录文件的关联任务资料不完整或包含无关计划" }
            }
            TransferScope.ALL -> Unit
        }
    }
}

/** Partial imports never overwrite local entries. Only ALL intentionally replaces local data. */
fun mergeTransfer(current: AppData, bundle: TransferBundle, requested: TransferScope): ImportPreview {
    DomainValidation.data(current)
    DomainValidation.data(bundle.data)
    TransferCodec.validateScope(bundle.scope, bundle.data)
    require(!bundle.legacyTemplate || bundle.scope == TransferScope.PLANS) { "模板文件的范围无效" }
    require(bundle.scope == requested || bundle.scope == TransferScope.ALL) {
        when (requested) {
            TransferScope.PLANS -> "请选择计划备份或完整备份文件"
            TransferScope.RECORDS -> "请选择记录备份或完整备份文件"
            TransferScope.ALL -> "完整恢复需要完整备份文件"
        }
    }
    if (requested == TransferScope.ALL) return checkedPreview(bundle.data,
        "将恢复 ${bundle.data.plans.count { !it.recordsOnly }} 项计划（含归档）、${bundle.data.checkIns.size} 条打卡和 ${bundle.data.focusRecords.size} 段专注、${bundle.data.dailyNotes.size} 篇每日心得。")
    val selected = if (requested == TransferScope.RECORDS) {
        val referenced = bundle.data.checkIns.map { it.planId }.toSet() + bundle.data.focusRecords.mapNotNull { it.planId }
        val plans = bundle.data.plans.filter { it.id in referenced }
        bundle.data.copy(plans = plans, categories = bundle.data.categories.filter { c -> plans.any { it.customCategoryId == c.id } })
    } else bundle.data
    val (withCategories, incoming) = mergeImportedCategories(current, selected)
    return if (requested == TransferScope.PLANS) mergePlans(withCategories, bundle.copy(data = incoming)) else mergeRecords(withCategories, incoming)
}

/** Category identities from another collection must never silently re-label local history. */
private fun mergeImportedCategories(current: AppData, incoming: AppData): Pair<AppData, AppData> {
    val categories = current.categories.associateByTo(linkedMapOf()) { it.id }
    val remap = mutableMapOf<String, String>()
    incoming.categories.forEach { source ->
        val nameMatch = categories.values.firstOrNull { it.name.equals(source.name, ignoreCase = true) }
        val existing = categories[source.id]
        val destination = when {
            existing == source -> source.id
            nameMatch != null -> nameMatch.id
            existing == null -> source.id
            else -> {
                var nonce = 0
                var candidate: String
                do { candidate = "category-" + digest("${source.id}:${source.name}:${source.iconId}:${source.colorKey}:${nonce++}").take(40) }
                while (candidate in categories)
                candidate
            }
        }
        remap[source.id] = destination
        categories.putIfAbsent(destination, source.copy(id = destination))
    }
    require(categories.size <= PersonalizationRules.MAX_CATEGORIES) { "合并后分类超过 ${PersonalizationRules.MAX_CATEGORIES} 个，请先整理分类再导入" }
    val combined = categories.values.toList()
    return current.copy(categories = combined) to incoming.copy(
        categories = combined,
        plans = incoming.plans.map { plan -> plan.copy(customCategoryId = plan.customCategoryId?.let(remap::getValue)) },
    )
}

private fun mergePlans(current: AppData, bundle: TransferBundle): ImportPreview {
    val plans = current.plans.associateByTo(linkedMapOf()) { it.id }
    val remap = current.plans.associateTo(mutableMapOf()) { it.id to it.id }
    fun relink(from: String, to: String) {
        // Values follow metadata as it moves; keys always describe the original local records.
        // This also handles exchanged aliases without treating A -> B and B -> A as a cycle.
        remap.replaceAll { _, currentId -> if (currentId == from) to else currentId }
    }
    var added = 0
    var promoted = 0
    var duplicates = 0
    var conflicts = 0
    var conflictingSeries = 0
    val incomingSeries = bundle.data.plans.filterNot { it.recordsOnly }.groupBy { it.seriesId }
    val localSeries = plans.values.filterNot { it.recordsOnly }.groupBy { it.seriesId }
    val accepted = incomingSeries.values.flatMap { versions ->
        val hasVersionConflict = versions.any { incoming ->
            plans[incoming.id]?.let { existing -> !existing.recordsOnly && existing != incoming } == true
        }
        val localVersions = localSeries[versions.first().seriesId].orEmpty()
        val localIds = localVersions.mapTo(hashSetOf()) { it.id }
        val combined = localVersions + versions.filterNot { it.id in localIds }
        if (hasVersionConflict || !consistentImportedSeries(combined)) {
            // A partial import keeps local arrangements. Adding only the successor while
            // retaining a conflicting active predecessor would schedule one task twice.
            conflictingSeries++
            conflicts += versions.size
            emptyList()
        } else versions
    }
    for (incoming in accepted) {
        var existing = plans[incoming.id]
        if (existing != null && !existing.recordsOnly) {
            if (existing == incoming) duplicates++ else conflicts++
            continue
        }
        if (existing != null && (!sameMeasurement(existing, incoming) || originalId(existing) != incoming.id)) {
            // Records imported first must not reserve the identity of a real plan. Move their
            // metadata, retaining every amount and its original unit, before adding the plan.
            val movedId = availableMetadataId(existing, plans.keys)
            plans.remove(existing.id)
            plans[movedId] = existing.copy(id = movedId, originId = originalId(existing))
            relink(existing.id, movedId)
            existing = null
        }
        val metadata = existing ?: plans.values.firstOrNull {
            it.recordsOnly && originalId(it) == incoming.id && sameMeasurement(it, incoming)
        }
        if (metadata != null) {
            plans.remove(metadata.id)
            relink(metadata.id, incoming.id)
            plans[incoming.id] = incoming.copy(recordsOnly = false, originId = null)
            promoted++
        } else {
            plans[incoming.id] = incoming
            added++
        }
    }
    val checks = remapCheckIns(current.checkIns, remap)
    val focus = current.focusRecords.map { it.copy(planId = it.planId?.let { id -> remap[id] ?: id }) }
    val loggedDates = checks.groupBy { it.planId }.mapValues { (_, entries) -> entries.map { it.date }.toSet() }
    val retainedPlans = plans.values.map { plan ->
        val skips = plan.skips.filterNot { it.date in loggedDates[plan.id].orEmpty() }
        if (skips != plan.skips) { conflicts++; plan.copy(skips = skips) } else plan
    }
    return checkedPreview(current.copy(plans = retainedPlans, checkIns = checks, focusRecords = focus),
        "新增 $added 项计划，补全 $promoted 项任务；略过 $duplicates 项重复内容，保留 $conflicts 项本机冲突。" +
            (if (conflictingSeries > 0) "略过 $conflictingSeries 项任务的冲突安排，保留本机整个任务系列。" else "") +
            if (bundle.legacyTemplate) "旧版模板从今天开始安排。" else "归档和历史安排会一并保留。")
}

/** Versions of one task may meet at a boundary, but may not overlap or both remain active. */
private fun consistentImportedSeries(plans: List<Plan>): Boolean {
    if (plans.count { !it.archived } > 1) return false
    val intervals = plans.mapNotNull { plan ->
        if (plan.archived && plan.endDate == null) return@mapNotNull null
        val start = DomainValidation.date(plan.startDate)
        val end = minOf(plan.endDate?.let(DomainValidation::date) ?: LocalDate.MAX,
            plan.dueDate?.let { DomainValidation.date(it).plusDays(1) } ?: LocalDate.MAX)
        if (end <= start) null else start to end
    }.sortedBy { it.first }
    return intervals.zipWithNext().none { (before, after) -> before.second > after.first }
}

private fun mergeRecords(current: AppData, incoming: AppData): ImportPreview {
    val plans = current.plans.associateByTo(linkedMapOf()) { it.id }
    val referenced = incoming.checkIns.map { it.planId }.toSet() + incoming.focusRecords.mapNotNull { it.planId }
    val remap = mutableMapOf<String, String>()
    var metadataAdded = 0
    for (source in incoming.plans.filter { it.id in referenced }) {
        val origin = originalId(source)
        val original = plans[origin]?.takeIf { originalId(it) == origin && sameMeasurement(it, source) }
        val exact = plans[source.id]?.takeIf { originalId(it) == origin && sameMeasurement(it, source) }
        val metadata = plans.values.firstOrNull { it.recordsOnly && originalId(it) == origin && sameMeasurement(it, source) }
        val existing = original ?: exact ?: metadata
        if (existing != null) {
            remap[source.id] = existing.id
            continue
        }
        var id = source.id
        if (id in plans) id = availableMetadataId(source, plans.keys)
        val historical = source.copy(id = id, recordsOnly = true, originId = origin.takeIf { it != id })
        plans[id] = historical
        remap[source.id] = id
        metadataAdded++
    }
    val checks = current.checkIns.associateByTo(linkedMapOf()) { it.planId to it.date }
    val focus = current.focusRecords.associateByTo(linkedMapOf()) { it.id }
    var addedChecks = 0
    var addedFocus = 0
    var addedNotes = 0
    val notes = current.dailyNotes.associateByTo(linkedMapOf()) { it.date }
    var duplicates = 0
    var conflicts = 0
    incoming.checkIns.forEach { entry ->
        val mapped = entry.copy(planId = remap.getValue(entry.planId))
        val key = mapped.planId to mapped.date
        val old = checks[key]
        if (old == null && plans[mapped.planId]?.skips?.any { it.date == mapped.date } == true) conflicts++
        else if (old == null) { checks[key] = mapped; addedChecks++ }
        else if (old == mapped) duplicates++ else conflicts++
    }
    incoming.focusRecords.forEach { entry ->
        val mapped = entry.copy(planId = entry.planId?.let { remap.getValue(it) })
        val old = focus[mapped.id]
        if (old == null) { focus[mapped.id] = mapped; addedFocus++ }
        else if (old == mapped) duplicates++ else conflicts++
    }
    incoming.dailyNotes.forEach { note ->
        val old = notes[note.date]
        when {
            old == null -> { notes[note.date] = note; addedNotes++ }
            old.text == note.text -> duplicates++
            else -> conflicts++ // A newer file must not overwrite a local day's writing.
        }
    }
    return checkedPreview(current.copy(plans = plans.values.toList(), checkIns = checks.values.toList(), focusRecords = focus.values.toList(),
        dailyNotes = notes.values.toList()),
        "新增 $addedChecks 条打卡、$addedFocus 段专注、$addedNotes 篇每日心得，补齐 $metadataAdded 项任务资料；略过 $duplicates 条重复内容，保留 $conflicts 条本机冲突。")
}

/** Metadata does not impose a schedule, and quantities only attach to matching original units. */
private fun sameMeasurement(first: Plan, second: Plan): Boolean =
    first.tracking == second.tracking && first.scale == second.scale && first.target == second.target &&
        first.unit.trim().lowercase(Locale.ROOT) == second.unit.trim().lowercase(Locale.ROOT)

private fun originalId(plan: Plan): String = plan.originId ?: plan.id

private fun metadataSignature(plan: Plan): String = DataCodec.planFingerprint(plan.copy(
    id = originalId(plan), recordsOnly = false, originId = null, archived = false, endDate = null, superseded = false,
))

private fun availableMetadataId(plan: Plan, occupied: Set<String>): String {
    val signature = metadataSignature(plan)
    var nonce = 0
    var id: String
    do { id = "record-" + digest(signature + ":" + nonce++).take(40) } while (id in occupied || id == originalId(plan))
    return id
}

private fun remapCheckIns(entries: List<CheckIn>, remap: Map<String, String>): List<CheckIn> {
    val result = linkedMapOf<Pair<String, String>, CheckIn>()
    // A record already using the destination identity wins over a relinked historical record.
    entries.sortedBy { it.planId in remap && remap[it.planId] != it.planId }.forEach { entry ->
        val mapped = entry.copy(planId = remap[entry.planId] ?: entry.planId)
        result.putIfAbsent(mapped.planId to mapped.date, mapped)
    }
    return result.values.toList()
}

private fun checkedPreview(data: AppData, summary: String): ImportPreview {
    DataCodec.encode(data) // Validate merged counts and byte capacity before the UI offers confirmation.
    return ImportPreview(data, summary)
}

private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
