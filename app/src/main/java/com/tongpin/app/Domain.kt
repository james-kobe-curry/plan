package com.tongpin.app

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.UUID

enum class Category { STUDY, FITNESS, LIFE }
enum class TrackingMode { TASK, QUANTITY }

/** End dates are exclusive, so resuming today immediately restores today's schedule. */
data class PlanPause(val startDate: String, val endDate: String? = null)
data class PlanSkip(val date: String, val reason: String = "")

data class Plan(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val category: Category,
    val target: Int,
    val unit: String,
    val weekdays: Set<Int> = (1..7).toSet(),
    val startDate: String = LocalDate.now().toString(),
    val archived: Boolean = false,
    val endDate: String? = null,
    val tracking: TrackingMode = TrackingMode.QUANTITY,
    val scale: Int = 0,
    val seriesId: String = id,
    val totalTarget: Int? = null,
    val dueDate: String? = null,
    val recordsOnly: Boolean = false,
    val originId: String? = null,
    val pauses: List<PlanPause> = emptyList(),
    val skips: List<PlanSkip> = emptyList(),
    val pinned: Boolean = false,
    val sortOrder: Int = 0,
    val reminderTime: String? = null,
    /** An edited historical version may finish its already logged day before its successor starts. */
    val superseded: Boolean = false,
    val customCategoryId: String? = null,
    val iconId: String? = null,
)

data class CheckIn(
    val planId: String,
    val date: String,
    val amount: Int,
    val note: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

data class FocusRecord(
    val id: String = UUID.randomUUID().toString(),
    val planId: String? = null,
    val seconds: Int,
    val completedAt: Long = System.currentTimeMillis(),
)

data class AppData(
    val plans: List<Plan> = emptyList(),
    val checkIns: List<CheckIn> = emptyList(),
    val focusRecords: List<FocusRecord> = emptyList(),
    val nickname: String = "plan用户",
    val collapseCompleted: Boolean = false,
    val categories: List<CustomCategory> = emptyList(),
    val profile: PersonalProfile = PersonalProfile(),
    val dailyNotes: List<DailyNote> = emptyList(),
)

/** Monday is 1 and Sunday is 7, matching java.time.DayOfWeek. */
private fun isPlanDateCandidate(plan: Plan, date: LocalDate): Boolean =
    !plan.recordsOnly && !(plan.archived && plan.endDate == null) &&
        !date.isBefore(DomainValidation.date(plan.startDate)) &&
        (plan.endDate == null || date.isBefore(DomainValidation.date(plan.endDate))) &&
        (plan.dueDate == null || !date.isAfter(DomainValidation.date(plan.dueDate))) &&
        date.dayOfWeek.value in plan.weekdays

/** Matches the deterministic tie-break used by the period review's precomputed index. */
internal fun isNewerPlanVersion(candidate: Plan, current: Plan): Boolean = when {
    candidate.startDate != current.startDate -> candidate.startDate > current.startDate
    candidate.archived != current.archived -> !candidate.archived
    else -> candidate.id > current.id
}

/**
 * Old imported series can contain overlapping versions. Select the effective version before
 * checking its pauses or skips, so an excluded new version never revives an older arrangement.
 * All original versions and records remain untouched and available in history.
 */
fun effectivePlansForDate(data: AppData, date: LocalDate): List<Plan> {
    val selected = linkedMapOf<String, Plan>()
    data.plans.forEach { plan ->
        if (isPlanDateCandidate(plan, date)) {
            val previous = selected[plan.seriesId]
            if (previous == null || isNewerPlanVersion(plan, previous)) selected[plan.seriesId] = plan
        }
    }
    return selected.values.toList()
}

fun isArranged(plan: Plan, date: LocalDate): Boolean =
    isPlanDateCandidate(plan, date) &&
        plan.pauses.none { date >= DomainValidation.date(it.startDate) && (it.endDate == null || date < DomainValidation.date(it.endDate)) }

fun isScheduled(plan: Plan, date: LocalDate): Boolean =
    isArranged(plan, date) && plan.skips.none { it.date == date.toString() }

fun amountFor(data: AppData, planId: String, date: LocalDate): Int =
    data.checkIns.firstOrNull { it.planId == planId && it.date == date.toString() }?.amount ?: 0

/** Changes future expectations while keeping logged quantities attached to their original units. */
fun revisePlan(data: AppData, oldId: String, proposed: Plan, today: LocalDate): AppData {
    val old = requireNotNull(data.plans.firstOrNull { it.id == oldId }) { "找不到这个计划" }
    require(!old.archived) { "已归档的计划不能再次修改" }
    // The editor owns these fields only. Preserve identity, pause/skip history and
    // ordering even when a caller constructs its proposal from fresh defaults.
    val edited = old.copy(title = proposed.title, category = proposed.category,
        target = proposed.target, unit = proposed.unit, weekdays = proposed.weekdays,
        startDate = proposed.startDate, tracking = proposed.tracking, scale = proposed.scale,
        totalTarget = proposed.totalTarget, dueDate = proposed.dueDate, reminderTime = proposed.reminderTime,
        customCategoryId = proposed.customCategoryId, iconId = proposed.iconId)
    if (edited == old) return data
    fun currentMetadata(plan: Plan) = plan.copy(title = edited.title, category = edited.category, reminderTime = edited.reminderTime,
        customCategoryId = edited.customCategoryId, iconId = edited.iconId)
    fun relayToday(plan: Plan) = plan.seriesId == old.seriesId && plan.superseded &&
        plan.startDate <= today.toString() && plan.endDate?.let { today.toString() < it } == true
    if (edited.copy(title = old.title, category = old.category, reminderTime = old.reminderTime,
        customCategoryId = old.customCategoryId, iconId = old.iconId) == old) {
        return data.copy(plans = data.plans.map {
            when { it.id == oldId -> edited; relayToday(it) -> currentMetadata(it); else -> it }
        }).also(DomainValidation::data)
    }
    val seriesIds = data.plans.filter { it.seriesId == old.seriesId }.mapTo(hashSetOf()) { it.id }
    val hasLoggedToday = data.checkIns.any { it.planId in seriesIds && it.date == today.toString() && it.amount > 0 }
    val requested = today.plusDays(if (hasLoggedToday) 1 else 0)
    val effective = maxOf(requested.toString(), old.startDate)
    val replacementStart = maxOf(requested.toString(), edited.startDate)
    val replacement = edited.copy(
        id = UUID.randomUUID().toString(),
        startDate = replacementStart,
        archived = false, endDate = null, superseded = false,
        seriesId = old.seriesId,
        pinned = old.pinned, sortOrder = old.sortOrder,
        pauses = old.pauses.mapNotNull { pause ->
            if (pause.endDate != null && pause.endDate <= replacementStart) null
            else pause.copy(startDate = maxOf(pause.startDate, replacementStart))
        },
        skips = old.skips.filter { it.date >= replacementStart },
    )
    return data.copy(plans = data.plans.map {
        when {
            it.id == oldId -> currentMetadata(it).copy(archived = true, endDate = effective, superseded = true)
            relayToday(it) -> currentMetadata(it)
            else -> it
        }
    } + replacement).also(DomainValidation::data)
}

/** A logged day remains visible; future plans can end at their start without creating history. */
fun archivePlan(data: AppData, id: String, today: LocalDate): AppData {
    val old = requireNotNull(data.plans.firstOrNull { it.id == id }) { "找不到这个计划" }
    if (old.archived) return data
    val effective = planChangeDate(data, old, today).toString()
    return data.copy(plans = data.plans.map {
        when {
            it.id == id -> it.copy(archived = true, endDate = effective, superseded = false)
            it.seriesId == old.seriesId && it.superseded -> it.copy(superseded = false)
            else -> it
        }
    }).also(DomainValidation::data)
}

private fun planChangeDate(data: AppData, plan: Plan, today: LocalDate): LocalDate {
    val requested = if (amountFor(data, plan.id, today) > 0) today.plusDays(1) else today
    return maxOf(requested, DomainValidation.date(plan.startDate))
}

/** Replaces a day's entry. A zero amount removes the entry and its note. */
fun setCheckIn(
    data: AppData,
    planId: String,
    date: LocalDate,
    amount: Int,
    note: String = "",
): AppData {
    DomainValidation.text(note, "打卡备注", DomainValidation.MAX_NOTE_LENGTH, allowEmpty = true, multiline = true)
    val plan = requireNotNull(data.plans.firstOrNull { it.id == planId }) { "找不到这个计划" }
    require(amount in 0..DomainValidation.maxAmount(plan.scale)) { "打卡数量须在 0 到 ${DomainValidation.MAX_AMOUNT} 之间" }
    require(plan.tracking != TrackingMode.TASK || amount in 0..1) { "普通任务只能记录待完成或已完成" }
    require(isScheduled(plan, date)) { "这个日期没有安排该计划" }
    val remaining = data.checkIns.filterNot { it.planId == planId && it.date == date.toString() }
    if (amount == 0) return data.copy(checkIns = remaining)
    require(remaining.size < DomainValidation.MAX_CHECK_INS) { "打卡记录已达上限，请先导出备份并整理历史记录" }
    return data.copy(checkIns = remaining + CheckIn(planId, date.toString(), amount, note))
}

/** Returns (completed plans, scheduled plans), without inventing history for new plans. */
fun dayProgress(data: AppData, date: LocalDate): Pair<Int, Int> {
    val scheduled = effectivePlansForDate(data, date).filter { isScheduled(it, date) }
    return scheduled.count { amountFor(data, it.id, date) >= it.target } to scheduled.size
}

/** Decimal quantities are stored as integer hundredths; old integer plans stay unchanged. */
fun supportsDecimal(unit: String): Boolean = unit.trim().lowercase(Locale.ROOT) in setOf(
    "公里", "千米", "km", "kilometer", "kilometers", "kilometre", "kilometres",
    "小时", "时", "h", "hour", "hours",
)

fun formatQuantity(value: Int, plan: Plan): String =
    BigDecimal.valueOf(value.toLong(), plan.scale).stripTrailingZeros().toPlainString()

/** Rejects malformed numbers and excessive precision instead of deleting or rounding digits. */
fun parseQuantity(text: String, unit: String, scale: Int, allowZero: Boolean = false): Int? {
    if (scale !in setOf(0, 2) || (scale > 0 && !supportsDecimal(unit))) return null
    val value = text.trim()
    if (value.length > 32) return null
    val pattern = if (scale == 0) Regex("[0-9]+") else Regex("[0-9]+(?:\\.[0-9]{1,2})?")
    if (!pattern.matches(value)) return null
    return try {
        BigDecimal(value).movePointRight(scale).setScale(0, RoundingMode.UNNECESSARY).intValueExact()
            .takeIf { it in (if (allowZero) 0 else 1)..DomainValidation.maxAmount(scale) }
    } catch (_: ArithmeticException) { null } catch (_: NumberFormatException) { null }
}

/** Shared bounds for editors, persistence and untrusted imports. */
object DomainValidation {
    const val MAX_PLANS = 200
    const val MAX_PLAN_VERSIONS = 20_000
    const val MAX_CHECK_INS = 100_000
    const val MAX_FOCUS_RECORDS = 100_000
    const val MAX_TITLE_LENGTH = 80
    const val MAX_UNIT_LENGTH = 12
    const val MAX_NOTE_LENGTH = 1_000
    const val MAX_NICKNAME_LENGTH = 30
    const val MAX_TARGET = 100_000
    const val MAX_AMOUNT = 1_000_000
    const val MAX_FOCUS_SECONDS = 86_400
    private const val MAX_TIMESTAMP = 253_402_300_799_999L
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
    private val datePattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    /** Limits describe real units; hundredths retain the same usable range as old plans. */
    fun maxTarget(scale: Int): Int = MAX_TARGET * scaleFactor(scale)
    fun maxAmount(scale: Int): Int = MAX_AMOUNT * scaleFactor(scale)
    private fun scaleFactor(scale: Int): Int = when (scale) {
        0 -> 1
        2 -> 100
        else -> throw IllegalArgumentException("不支持此数量精度")
    }

    fun date(value: String): LocalDate {
        require(datePattern.matches(value)) { "日期格式必须为 YYYY-MM-DD" }
        val parsed = try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("日期无效：$value")
        }
        require(parsed.year in 1..9999 && parsed.toString() == value) { "日期超出支持范围" }
        return parsed
    }

    fun text(value: String, name: String, max: Int, allowEmpty: Boolean = false, multiline: Boolean = false) {
        require(value.length <= max) { "$name 不能超过 $max 个字符" }
        require(allowEmpty || value.isNotBlank()) { "$name 不能为空" }
        require(value.none { it.isISOControl() && !(multiline && (it == '\n' || it == '\r' || it == '\t')) }) {
            "$name 包含不支持的控制字符"
        }
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char.isHighSurrogate()) {
                require(index < value.length && value[index++].isLowSurrogate()) { "$name 包含无效字符" }
            } else require(!char.isLowSurrogate()) { "$name 包含无效字符" }
        }
    }

    fun id(value: String) {
        require(idPattern.matches(value)) { "记录标识格式无效" }
    }

    fun plan(plan: Plan) {
        id(plan.id)
        id(plan.seriesId)
        plan.customCategoryId?.let(::id)
        plan.iconId?.let { value -> require(PlanIcon.entries.any { it.name == value }) { "任务图标无效" } }
        plan.originId?.let { id(it); require(plan.recordsOnly && it != plan.id) { "记录来源标识无效" } }
        text(plan.title, "计划名称", MAX_TITLE_LENGTH)
        plan.reminderTime?.let { require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(it)) { "提醒时间必须为 HH:mm，例如 08:30" } }
        text(plan.unit, "目标单位", MAX_UNIT_LENGTH)
        require(plan.scale in setOf(0, 2) && (plan.scale == 0 || supportsDecimal(plan.unit))) { "这个单位不支持此数量精度" }
        require(plan.target in 1..maxTarget(plan.scale)) { "每日目标必须大于 0，且不能超过 $MAX_TARGET" }
        if (plan.tracking == TrackingMode.TASK) {
            require(plan.target == 1 && plan.unit == "次" && plan.scale == 0 && plan.totalTarget == null) { "普通任务不需要数量目标" }
        }
        plan.totalTarget?.let { require(it in plan.target..maxAmount(plan.scale)) { "总目标不能小于每日目标或超过数量上限" } }
        require(plan.weekdays.isNotEmpty() && plan.weekdays.all { it in 1..7 }) { "每周至少选择一天，星期编号须在 1 到 7 之间" }
        val start = date(plan.startDate)
        plan.endDate?.let { require(!date(it).isBefore(start)) { "计划结束日期不能早于开始日期" } }
        require(!plan.superseded || plan.archived && plan.endDate != null) { "历史接替状态无效" }
        plan.dueDate?.let { require(!date(it).isBefore(start)) { "截止日期不能早于开始日期" } }
        require(plan.sortOrder in 0..MAX_PLAN_VERSIONS) { "任务顺序无效" }
        require(plan.pauses.size <= 10_000 && plan.skips.size <= 20_000) { "暂停或跳过记录过多" }
        var previousEnd: LocalDate? = null
        plan.pauses.forEachIndexed { index, pause ->
            val begin = date(pause.startDate)
            val end = pause.endDate?.let(::date)
            require(begin >= start && (end == null || end > begin)) { "暂停日期范围无效" }
            require(index == 0 || previousEnd?.let { begin >= it } == true) { "暂停日期不能重叠" }
            require(end != null || index == plan.pauses.lastIndex) { "未恢复的暂停必须位于最后" }
            previousEnd = end
        }
        require(plan.skips.map { it.date }.toSet().size == plan.skips.size) { "同一天不能重复跳过" }
        plan.skips.forEach { skip ->
            require(date(skip.date) >= start) { "跳过日期不能早于计划开始" }
            text(skip.reason, "跳过原因", MAX_NOTE_LENGTH, allowEmpty = true, multiline = true)
        }
    }

    fun plans(plans: List<Plan>) {
        require(plans.count { !it.archived && !it.recordsOnly } <= MAX_PLANS) { "进行中的计划不能超过 $MAX_PLANS 个" }
        require(plans.size <= MAX_PLAN_VERSIONS) { "计划历史版本过多，请先导出备份" }
        plans.forEach(::plan)
        require(plans.map { it.id }.toSet().size == plans.size) { "存在重复的计划标识" }
    }

    fun data(data: AppData) {
        plans(data.plans)
        require(data.categories.size <= PersonalizationRules.MAX_CATEGORIES) { "自定义分类过多" }
        data.categories.forEach(PersonalizationRules::category)
        require(data.categories.map { it.id }.toSet().size == data.categories.size) { "分类标识重复" }
        require(data.categories.map { it.name.lowercase(Locale.ROOT) }.toSet().size == data.categories.size) { "存在重复的分类名称" }
        val categoryIds = data.categories.mapTo(hashSetOf()) { it.id }
        require(data.plans.all { it.customCategoryId == null || it.customCategoryId in categoryIds }) { "任务引用了不存在的自定义分类" }
        PersonalizationRules.profile(data.profile)
        DailyNoteRules.notes(data.dailyNotes)
        text(data.nickname, "昵称", MAX_NICKNAME_LENGTH)
        require(data.checkIns.size <= MAX_CHECK_INS) { "打卡记录过多" }
        require(data.focusRecords.size <= MAX_FOCUS_RECORDS) { "专注记录过多" }
        val plansById = data.plans.associateBy { it.id }
        val planIds = plansById.keys
        val checkInKeys = mutableSetOf<Pair<String, String>>()
        data.checkIns.forEach {
            require(it.planId in planIds) { "打卡记录引用了不存在的计划" }
            date(it.date)
            require(it.amount in 1..maxAmount(plansById.getValue(it.planId).scale)) { "打卡数量超出范围" }
            require(plansById[it.planId]?.tracking != TrackingMode.TASK || it.amount == 1) { "普通任务的完成记录无效" }
            require(plansById.getValue(it.planId).skips.none { skip -> skip.date == it.date }) { "已打卡日期不能同时标记跳过" }
            text(it.note, "打卡备注", MAX_NOTE_LENGTH, allowEmpty = true, multiline = true)
            require(it.updatedAt in 0..MAX_TIMESTAMP) { "打卡时间无效" }
            require(checkInKeys.add(it.planId to it.date)) { "同一计划在同一天存在重复打卡" }
        }
        val focusIds = mutableSetOf<String>()
        data.focusRecords.forEach {
            id(it.id)
            require(focusIds.add(it.id)) { "存在重复的专注记录标识" }
            require(it.planId == null || it.planId in planIds) { "专注记录引用了不存在的计划" }
            require(it.seconds in 1..MAX_FOCUS_SECONDS) { "专注时长超出范围" }
            require(it.completedAt in 0..MAX_TIMESTAMP) { "专注完成时间无效" }
        }
    }
}

/** Versioned, bounded JSON. Plan transfers never contain check-ins or focus history. */
object DataCodec {
    const val MAX_BYTES = 32 * 1024 * 1024
    private const val VERSION = 8

    fun encode(data: AppData): String {
        DomainValidation.data(data)
        return serialize(linkedMapOf(
            "format" to "tongpin-data", "version" to VERSION,
            "plans" to data.plans.map(::planObject),
            "checkIns" to data.checkIns.map { linkedMapOf(
                "planId" to it.planId, "date" to it.date, "amount" to it.amount,
                "note" to it.note, "updatedAt" to it.updatedAt,
            ) },
            "focusRecords" to data.focusRecords.map { linkedMapOf(
                "id" to it.id, "planId" to it.planId, "seconds" to it.seconds,
                "completedAt" to it.completedAt,
            ) },
            "nickname" to data.nickname,
            "collapseCompleted" to data.collapseCompleted,
            "categories" to data.categories.map(::categoryObject),
            "profile" to linkedMapOf("motto" to data.profile.motto, "avatarId" to data.profile.avatarId,
                "avatarImage" to data.profile.avatarImage, "showOnHome" to data.profile.showOnHome),
            "dailyNotes" to data.dailyNotes.map { linkedMapOf("date" to it.date, "text" to it.text, "updatedAt" to it.updatedAt) },
        ))
    }

    fun decode(text: String): AppData {
        val root = envelope(text, "tongpin-data", setOf("format", "version", "plans", "checkIns", "focusRecords", "nickname"))
        val plans = readPlans(root)
        val migratedDecimalIds = if (root.int("version") == 1) plans.filter { it.scale == 2 }.map { it.id }.toSet() else emptySet()
        val checkIns = root.array("checkIns", DomainValidation.MAX_CHECK_INS).map {
            val item = it.objectValue("打卡记录")
            item.fields(setOf("planId", "date", "amount", "note", "updatedAt"))
            val planId = item.string("planId")
            val amount = item.int("amount")
            CheckIn(planId, item.string("date"), if (planId in migratedDecimalIds) migrateHundredths(amount, DomainValidation.MAX_AMOUNT) else amount,
                item.string("note"), item.long("updatedAt"))
        }
        val focusRecords = root.array("focusRecords", DomainValidation.MAX_FOCUS_RECORDS).map {
            val item = it.objectValue("专注记录")
            item.fields(setOf("id", "planId", "seconds", "completedAt"))
            FocusRecord(item.string("id"), item.nullableString("planId"), item.int("seconds"), item.long("completedAt"))
        }
        val nickname = root.string("nickname").let {
            if (root.int("version") == 1 && it == "同频学员") "plan用户" else it
        }
        return AppData(plans, checkIns, focusRecords, nickname,
            if (root.int("version") >= 4) root.boolean("collapseCompleted") else false,
            if (root.int("version") >= 7) readCategories(root) else emptyList(),
            if (root.int("version") >= 7) readProfile(root) else PersonalProfile(),
            if (root.int("version") >= 8) readDailyNotes(root) else emptyList()).also(DomainValidation::data)
    }

    fun encodePlans(plans: List<Plan>, categories: List<CustomCategory> = emptyList()): String {
        DomainValidation.data(AppData(plans = plans, categories = categories))
        require(plans.isNotEmpty()) { "请至少选择一个计划" }
        return serialize(linkedMapOf(
            "format" to "tongpin-plans", "version" to VERSION,
            "plans" to plans.map { planObject(it.copy(archived = false, endDate = null, superseded = false)) },
            "categories" to categories.filter { c -> plans.any { it.customCategoryId == c.id } }.map(::categoryObject),
        ))
    }

    fun decodePlans(text: String): List<Plan> {
        val plans = decodePlanArchiveData(text).plans
        require(plans.isNotEmpty()) { "文件中没有可导入的计划" }
        val today = LocalDate.now().toString()
        return plans.map {
            val id = UUID.randomUUID().toString()
            val duration = it.dueDate?.let { due -> java.time.temporal.ChronoUnit.DAYS.between(DomainValidation.date(it.startDate), DomainValidation.date(due)) }
            it.copy(id = id, seriesId = id, startDate = today, archived = false, endDate = null, superseded = false,
                pauses = emptyList(), skips = emptyList(), pinned = false, sortOrder = 0,
                dueDate = duration?.let { days -> LocalDate.parse(today).plusDays(days).toString() })
        }.also(DomainValidation::plans)
    }

    /** Transfer files retain identities and archive dates; template import assigns them separately. */
    internal fun decodePlanArchive(text: String): List<Plan> = decodePlanArchiveData(text).plans

    internal fun decodePlanArchiveData(text: String): AppData {
        val root = envelope(text, "tongpin-plans", setOf("format", "version", "plans"))
        return AppData(plans = readPlans(root), categories = if (root.int("version") >= 7) readCategories(root) else emptyList())
            .also(DomainValidation::data)
    }

    /** Standalone plan metadata has no dependency on a surrounding category collection. */
    internal fun planFingerprint(plan: Plan): String {
        DomainValidation.plan(plan)
        // Keep the pre-personalization hash stable for old drafts and pending check-ins.
        if (plan.customCategoryId == null && plan.iconId == null) return serialize(linkedMapOf(
            "format" to "tongpin-data", "version" to 6,
            "plans" to listOf(planObject(plan).filterKeys { it != "customCategoryId" && it != "iconId" }),
            "checkIns" to emptyList<Any>(), "focusRecords" to emptyList<Any>(),
            "nickname" to "plan用户", "collapseCompleted" to false,
        ))
        return serialize(planObject(plan))
    }

    private fun categoryObject(category: CustomCategory): Map<String, Any?> = linkedMapOf(
        "id" to category.id, "name" to category.name, "iconId" to category.iconId, "colorKey" to category.colorKey,
    )
    private fun readCategories(root: Map<String, Any?>): List<CustomCategory> =
        root.array("categories", PersonalizationRules.MAX_CATEGORIES).map {
            val item = it.objectValue("自定义分类")
            item.fields(setOf("id", "name", "iconId", "colorKey"))
            CustomCategory(item.string("id"), item.string("name"), item.string("iconId"), item.string("colorKey"))
        }
    private fun readDailyNotes(root: Map<String, Any?>): List<DailyNote> =
        root.array("dailyNotes", DailyNoteRules.MAX_NOTES).map {
            val item = it.objectValue("每日心得")
            item.fields(setOf("date", "text", "updatedAt"))
            DailyNote(item.string("date"), item.string("text"), item.long("updatedAt"))
        }

    private fun readProfile(root: Map<String, Any?>): PersonalProfile {
        val item = root["profile"].objectValue("个人资料")
        item.fields(setOf("motto", "avatarId", "avatarImage", "showOnHome"))
        return PersonalProfile(item.string("motto"), item.string("avatarId"), item.nullableString("avatarImage"), item.boolean("showOnHome"))
    }

    internal fun readJsonObject(text: String): Map<String, Any?> {
        checkSize(text)
        return StrictJsonReader(text).read().objectValue("文件")
    }

    internal fun writeJsonObject(value: Map<String, Any?>): String = serialize(value)

    private fun planObject(plan: Plan): Map<String, Any?> = linkedMapOf(
        "id" to plan.id, "title" to plan.title, "category" to plan.category.name,
        "target" to plan.target, "unit" to plan.unit, "weekdays" to plan.weekdays.sorted(),
        "startDate" to plan.startDate, "archived" to plan.archived, "endDate" to plan.endDate,
        "tracking" to plan.tracking.name, "scale" to plan.scale, "seriesId" to plan.seriesId,
        "totalTarget" to plan.totalTarget, "dueDate" to plan.dueDate,
        "recordsOnly" to plan.recordsOnly, "originId" to plan.originId,
        "pauses" to plan.pauses.map { linkedMapOf("startDate" to it.startDate, "endDate" to it.endDate) },
        "skips" to plan.skips.map { linkedMapOf("date" to it.date, "reason" to it.reason) },
        "pinned" to plan.pinned, "sortOrder" to plan.sortOrder,
        "reminderTime" to plan.reminderTime,
        "superseded" to plan.superseded,
        "customCategoryId" to plan.customCategoryId, "iconId" to plan.iconId,
    )

    private fun readPlans(root: Map<String, Any?>): List<Plan> =
        root.array("plans", DomainValidation.MAX_PLAN_VERSIONS).map {
            val item = it.objectValue("计划")
            val version = root.int("version")
            val legacyFields = setOf("id", "title", "category", "target", "unit", "weekdays", "startDate", "archived", "endDate")
            val v2Fields = legacyFields + setOf("tracking", "scale", "seriesId", "totalTarget", "dueDate")
            val v3Fields = v2Fields + setOf("recordsOnly", "originId")
            val v4Fields = v3Fields + setOf("pauses", "skips", "pinned", "sortOrder")
            val v5Fields = v4Fields + "reminderTime"
            val v6Fields = v5Fields + "superseded"
            item.fields(when(version) { 1 -> legacyFields; 2 -> v2Fields; 3 -> v3Fields; 4 -> v4Fields; 5 -> v5Fields; 6 -> v6Fields; else -> v6Fields + setOf("customCategoryId", "iconId") })
            val category = try {
                Category.valueOf(item.string("category"))
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("计划分类无效")
            }
            val weekdays = item.array("weekdays", 7).map { value -> value.integerValue("星期编号") }
            require(weekdays.size == weekdays.toSet().size) { "星期编号不能重复" }
            val unit = item.string("unit")
            val scale = if (version == 1) { if (supportsDecimal(unit)) 2 else 0 } else item.int("scale")
            val target = item.int("target")
            Plan(
                id = item.string("id"), title = item.string("title"), category = category,
                target = if (version == 1 && scale == 2) migrateHundredths(target, DomainValidation.MAX_TARGET) else target,
                unit = unit, weekdays = weekdays.toSet(),
                startDate = item.string("startDate"), archived = item.boolean("archived"),
                endDate = item.nullableString("endDate"),
                tracking = if (version == 1) TrackingMode.QUANTITY else try {
                    TrackingMode.valueOf(item.string("tracking"))
                } catch (_: IllegalArgumentException) { throw IllegalArgumentException("任务进度类型无效") },
                scale = scale,
                seriesId = if (version == 1) item.string("id") else item.string("seriesId"),
                totalTarget = if (version == 1 || item["totalTarget"] == null) null else item.int("totalTarget"),
                dueDate = if (version == 1) null else item.nullableString("dueDate"),
                recordsOnly = if (version < 3) false else item.boolean("recordsOnly"),
                originId = if (version < 3) null else item.nullableString("originId"),
                pauses = if (version < 4) emptyList() else item.array("pauses", 10_000).map { value ->
                    val pause = value.objectValue("暂停记录")
                    pause.fields(setOf("startDate", "endDate"))
                    PlanPause(pause.string("startDate"), pause.nullableString("endDate"))
                },
                skips = if (version < 4) emptyList() else item.array("skips", 20_000).map { value ->
                    val skip = value.objectValue("跳过记录")
                    skip.fields(setOf("date", "reason"))
                    PlanSkip(skip.string("date"), skip.string("reason"))
                },
                pinned = version >= 4 && item.boolean("pinned"),
                sortOrder = if (version < 4) 0 else item.int("sortOrder"),
                reminderTime = if (version < 5) null else item.nullableString("reminderTime"),
                superseded = version >= 6 && item.boolean("superseded"),
                customCategoryId = if (version < 7) null else item.nullableString("customCategoryId"),
                iconId = if (version < 7) null else item.nullableString("iconId"),
            )
        }.also(DomainValidation::plans)

    private fun migrateHundredths(value: Int, oldMaximum: Int): Int {
        require(value in 1..oldMaximum) { "旧版数量超出范围" }
        return value * 100
    }

    private fun envelope(text: String, format: String, fields: Set<String>): Map<String, Any?> {
        checkSize(text)
        val root = StrictJsonReader(text).read().objectValue("文件")
        val version = root.int("version")
        val expected = fields + (if (format == "tongpin-data" && version >= 4) setOf("collapseCompleted") else emptySet()) +
            (if (version >= 7) if (format == "tongpin-data") setOf("categories", "profile") else setOf("categories") else emptySet()) +
            (if (format == "tongpin-data" && version >= 8) setOf("dailyNotes") else emptySet())
        root.fields(expected)
        require(root.string("format") == format) { "文件类型不匹配，请选择 plan 导出的${if (format == "tongpin-plans") "计划" else "备份"}文件" }
        require(root.int("version") in 1..VERSION) { "不支持此文件版本，请更新 plan 后重试" }
        return root
    }

    private fun checkSize(text: String) {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "文件不能超过 32 MB" }
    }

    private fun Map<String, Any?>.fields(expected: Set<String>) {
        require(keys == expected) { "文件字段缺失或包含不支持的字段" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.objectValue(name: String): Map<String, Any?> =
        this as? Map<String, Any?> ?: throw IllegalArgumentException("$name 必须是 JSON 对象")

    private fun Map<String, Any?>.array(key: String, max: Int): List<Any?> {
        val value = this[key] as? List<*> ?: throw IllegalArgumentException("$key 必须是数组")
        require(value.size <= max) { "$key 超过数量上限 $max" }
        return value
    }

    private fun Map<String, Any?>.string(key: String): String =
        this[key] as? String ?: throw IllegalArgumentException("$key 必须是文本")

    private fun Map<String, Any?>.nullableString(key: String): String? {
        val value = this[key] ?: return null
        return value as? String ?: throw IllegalArgumentException("$key 必须是文本或 null")
    }

    private fun Map<String, Any?>.boolean(key: String): Boolean =
        this[key] as? Boolean ?: throw IllegalArgumentException("$key 必须是布尔值")

    private fun Map<String, Any?>.long(key: String): Long =
        this[key] as? Long ?: throw IllegalArgumentException("$key 必须是整数")

    private fun Any?.integerValue(name: String): Int {
        val value = this as? Long ?: throw IllegalArgumentException("$name 必须是整数")
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$name 超出整数范围" }
        return value.toInt()
    }

    private fun Map<String, Any?>.int(key: String): Int = this[key].integerValue(key)

    private fun serialize(value: Any?): String = buildString { writeJson(value) }.also(::checkSize)

    private fun StringBuilder.writeJson(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> {
                append('"')
                value.forEach { char ->
                    when (char) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (char.code < 32) append("\\u" + char.code.toString(16).padStart(4, '0')) else append(char)
                    }
                }
                append('"')
            }
            is Int, is Long, is Boolean -> append(value.toString())
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    writeJson(entry.key)
                    append(':')
                    writeJson(entry.value)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    writeJson(item)
                }
                append(']')
            }
            else -> throw IllegalArgumentException("不支持的数据类型")
        }
    }
}

/** Small strict parser for the app's integer-only schema; no Android runtime is needed. */
private class StrictJsonReader(private val source: String) {
    private var offset = 0
    private var nodes = 0

    fun read(): Any? {
        val result = value(0)
        whitespace()
        require(offset == source.length) { "JSON 末尾包含多余内容" }
        return result
    }

    private fun value(depth: Int): Any? {
        require(depth <= 12 && ++nodes <= 2_000_000) { "JSON 结构过于复杂" }
        whitespace()
        require(offset < source.length) { "JSON 内容不完整" }
        return when (source[offset]) {
            '{' -> objectValue(depth + 1)
            '[' -> arrayValue(depth + 1)
            '"' -> stringValue()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            '-', in '0'..'9' -> numberValue()
            else -> throw IllegalArgumentException("JSON 在位置 $offset 格式无效")
        }
    }

    private fun objectValue(depth: Int): Map<String, Any?> {
        offset++
        val result = linkedMapOf<String, Any?>()
        whitespace()
        if (take('}')) return result
        while (true) {
            whitespace()
            require(offset < source.length && source[offset] == '"') { "JSON 字段名必须使用双引号" }
            val key = stringValue()
            require(!result.containsKey(key)) { "JSON 存在重复字段：$key" }
            whitespace()
            require(take(':')) { "JSON 缺少冒号" }
            result[key] = value(depth)
            whitespace()
            if (take('}')) return result
            require(take(',')) { "JSON 对象缺少逗号或右括号" }
        }
    }

    private fun arrayValue(depth: Int): List<Any?> {
        offset++
        val result = mutableListOf<Any?>()
        whitespace()
        if (take(']')) return result
        while (true) {
            result += value(depth)
            whitespace()
            if (take(']')) return result
            require(take(',')) { "JSON 数组缺少逗号或右括号" }
        }
    }

    private fun stringValue(): String {
        offset++
        val result = StringBuilder()
        while (offset < source.length) {
            val char = source[offset++]
            when {
                char == '"' -> {
                    val text = result.toString()
                    var index = 0
                    while (index < text.length) {
                        val current = text[index++]
                        if (current.isHighSurrogate()) {
                            require(index < text.length && text[index++].isLowSurrogate()) { "JSON Unicode 字符无效" }
                        } else require(!current.isLowSurrogate()) { "JSON Unicode 字符无效" }
                    }
                    return text
                }
                char == '\\' -> {
                    require(offset < source.length) { "JSON 转义不完整" }
                    when (val escaped = source[offset++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(offset + 4 <= source.length) { "JSON Unicode 转义不完整" }
                            val hex = source.substring(offset, offset + 4)
                            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "JSON Unicode 转义无效" }
                            result.append(hex.toInt(16).toChar())
                            offset += 4
                        }
                        else -> throw IllegalArgumentException("JSON 转义无效")
                    }
                }
                char.code < 32 -> throw IllegalArgumentException("JSON 文本包含未转义的控制字符")
                else -> result.append(char)
            }
        }
        throw IllegalArgumentException("JSON 文本缺少结束引号")
    }

    private fun numberValue(): Long {
        val begin = offset
        take('-')
        require(offset < source.length && source[offset] in '0'..'9') { "JSON 数字无效" }
        if (source[offset] == '0') offset++ else while (offset < source.length && source[offset] in '0'..'9') offset++
        require(offset >= source.length || source[offset] !in ".eE") { "文件中的数字必须为整数" }
        return source.substring(begin, offset).toLongOrNull() ?: throw IllegalArgumentException("JSON 数字超出范围")
    }

    private fun literal(word: String, result: Any?): Any? {
        require(source.startsWith(word, offset)) { "JSON 值无效" }
        offset += word.length
        return result
    }

    private fun whitespace() {
        while (offset < source.length && source[offset] in " \t\r\n") offset++
    }

    private fun take(char: Char): Boolean {
        if (offset < source.length && source[offset] == char) {
            offset++
            return true
        }
        return false
    }
}
