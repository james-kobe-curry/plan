package com.tongpin.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.Manifest
import android.os.Build
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.Locale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun choosePlanDate(context: Context, value: LocalDate, onDate: (LocalDate) -> Unit) {
    DatePickerDialog(context, planDialogTheme(context), { _, year, month, day -> onDate(LocalDate.of(year, month + 1, day)) }, value.year, value.monthValue - 1, value.dayOfMonth).show()
}

fun planTargetLabel(plan: Plan): String = if (plan.tracking == TrackingMode.TASK) "完成打卡" else "${formatQuantity(plan.target, plan)} ${plan.unit}"

@Composable private fun FormSection(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        content()
    }
}

@Composable fun PlanEditor(initial: Plan?, data: AppData, onDismiss: () -> Unit, onSave: suspend (Plan) -> Unit) {
    key(initial?.id) { PlanEditorForm(initial, data, onDismiss, onSave) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PlanEditorForm(initial: Plan?, data: AppData, onDismiss: () -> Unit, onSave: suspend (Plan) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val seed = remember { initial }
    var saving by remember { mutableStateOf(false) }
    var exitQuestion by remember { mutableStateOf(false) }
    var proposedId by remember { mutableStateOf(seed?.id ?: UUID.randomUUID().toString()) }
    var title by remember { mutableStateOf(seed?.title ?: "") }
    var category by remember { mutableStateOf(seed?.category ?: Category.STUDY) }
    var customCategoryId by remember { mutableStateOf(seed?.customCategoryId) }
    var iconId by remember { mutableStateOf(seed?.iconId) }
    var iconExpanded by remember { mutableStateOf(false) }
    var measured by remember { mutableStateOf(seed?.tracking == TrackingMode.QUANTITY) }
    var unit by remember { mutableStateOf(if (seed?.tracking == TrackingMode.QUANTITY) seed.unit else "分钟") }
    var target by remember { mutableStateOf(if (seed?.tracking == TrackingMode.QUANTITY) formatQuantity(seed.target, seed) else "") }
    var days by remember { mutableStateOf(seed?.weekdays ?: (1..7).toSet()) }
    var start by remember { mutableStateOf(seed?.startDate?.let(LocalDate::parse) ?: LocalDate.now()) }
    var due by remember { mutableStateOf(seed?.dueDate?.let(LocalDate::parse)) }
    var totalEnabled by remember { mutableStateOf(seed?.totalTarget != null) }
    var total by remember { mutableStateOf(seed?.totalTarget?.let { formatQuantity(it, seed) } ?: "") }
    var scheduleExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var errorField by remember { mutableStateOf("") }
    var allocationMessage by remember { mutableStateOf("") }
    var reminderEnabled by remember { mutableStateOf(seed?.reminderTime != null) }
    var reminderTime by remember { mutableStateOf(seed?.reminderTime ?: "20:00") }
    fun fields() = PlanDraftFields(title, category, measured, unit, target, days, start, due, totalEnabled, total, reminderEnabled, reminderTime, customCategoryId, iconId)
    val originalFields = remember { fields().values() }
    val baseline = remember {
        initial?.let(::draftPlanFingerprint)
        ?: "new-plan:${draftHash(data.plans.map { it.id }.sorted().joinToString("\n"))}"
    }
    fun draftValues() = fields().values().takeUnless { it == originalFields }?.plus("planId" to proposedId)
    val draft = rememberDraftEditor(
        key = initial?.let { "edit-plan:${it.seriesId}" } ?: "new-plan", baseline = baseline,
        values = ::draftValues,
        restore = { values ->
            val restored = PlanDraftFields.read(values)
            val restoredId = requireNotNull(values["planId"]).also(DomainValidation::id)
            require(initial == null || restoredId == initial.id)
            require(initial != null || data.plans.none { it.id == restoredId })
            require(restored.customCategoryId == null || data.categories.any { it.id == restored.customCategoryId })
            proposedId = restoredId
            customCategoryId = restored.customCategoryId; iconId = restored.iconId
            title = restored.title; category = restored.category; measured = restored.measured; unit = restored.unit
            target = restored.target; days = restored.days; start = restored.start; due = restored.due
            totalEnabled = restored.totalEnabled; total = restored.total; reminderEnabled = restored.reminderEnabled; reminderTime = restored.reminderTime
        },
    )
    fun requestDismiss() {
        if (saving || !draft.ready) return
        draft.update(draftValues())
        if (draft.dirty) exitQuestion = true else onDismiss()
    }
    fun leave(keep: Boolean) {
        if (saving) return
        saving = true
        scope.launch {
            withContext(NonCancellable) {
                try {
                    draft.update(draftValues())
                    if (keep) draft.keep() else draft.discard()
                    onDismiss()
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) { error = failure.message ?: "草稿处理失败，请重试"; exitQuestion = false }
                finally { saving = false }
            }
        }
    }
    var reminderRevision by remember { mutableIntStateOf(0) }
    val reminderPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        reminderRevision++; PlanReminderScheduler.reschedule(context)
    }
    DisposableEffect(context) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) reminderRevision++ }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    val reminderStatus = remember(reminderRevision, reminderEnabled) {
        if (reminderEnabled) runCatching { PlanReminderScheduler.status(context) }.getOrElse { listOf("暂时无法读取系统提醒设置") } else emptyList()
    }
    val scale = if (supportsDecimal(unit)) 2 else 0
    fun clearError() { error = ""; errorField = "" }
    fun fail(message: String, field: String = "") { error = message; errorField = field }
    fun changeUnit(value: String) {
        if (value != unit) { unit = value; target = ""; total = ""; allocationMessage = ""; clearError() }
    }
    fun savePlan() {
        if (saving || !draft.ready) return
        val currentScale = if (supportsDecimal(unit)) 2 else 0
        val amount = if (measured) parseQuantity(target, unit, currentScale) else 1
        val totalAmount = if (measured && totalEnabled) parseQuantity(total, unit, currentScale) else null
        when {
            title.isBlank() -> fail("请填写任务名称", "title")
            measured && unit.isBlank() -> fail("请填写进度单位", "unit")
            amount == null -> fail(if (currentScale == 2) "请填写每次目标，支持最多两位小数" else "请填写有效的整数目标", "target")
            measured && amount > DomainValidation.maxTarget(currentScale) -> fail("每次目标超出支持的数量范围", "target")
            days.isEmpty() -> { scheduleExpanded = true; fail("请至少选择一个执行日", "days") }
            due != null && due!! < start -> { scheduleExpanded = true; fail("截止日期不能早于开始日期", "date") }
            measured && totalEnabled && totalAmount == null -> fail("请填写有效的长期总目标", "total")
            totalAmount != null && totalAmount < amount -> fail("长期总目标不能小于每次目标", "total")
            else -> {
                clearError()
                val id = proposedId
                val proposed = (initial ?: Plan(id = id, title = title.trim(), category = category, target = amount, unit = "次")).copy(
                        id = id, title = title.trim(), category = category,
                        target = amount, unit = if (measured) unit.trim() else "次", weekdays = days, startDate = start.toString(),
                        archived = initial?.archived ?: false, endDate = initial?.endDate,
                        tracking = if (measured) TrackingMode.QUANTITY else TrackingMode.TASK, scale = if (measured) currentScale else 0,
                        seriesId = initial?.seriesId ?: id, totalTarget = totalAmount, dueDate = due?.toString(),
                        recordsOnly = initial?.recordsOnly ?: false, originId = initial?.originId,
                        reminderTime = reminderTime.takeIf { reminderEnabled },
                        customCategoryId = customCategoryId, iconId = iconId,
                    )
                saving = true
                draft.update(draftValues())
                scope.launch {
                    withContext(NonCancellable) {
                        try {
                            draft.prepareSave()
                            onSave(proposed)
                            savedDraftCleanupNotice(context, draft.saved())
                            onDismiss()
                        } catch (failure: CancellationException) { draft.failedSave(); throw failure }
                        catch (failure: Exception) {
                            val cleanupFailure = draft.failedSave()
                            fail((failure.message ?: "任务暂未保存，请重试") + if (cleanupFailure != null) "\n草稿仍保留为待确认内容，重新打开后可查看和复制。" else "")
                        }
                        finally { saving = false }
                    }
                }
            }
        }
    }
    fun allocateTarget() {
        if (initial != null && isPlanPaused(initial)) { fail("请先恢复计划安排，再分配每次目标"); return }
        val currentScale = if (supportsDecimal(unit)) 2 else 0
        val full = parseQuantity(total, unit, currentScale)
        val end = due
        if (full == null) { fail("请先填写有效的长期总目标", "total"); return }
        if (end == null || end < start) { scheduleExpanded = true; fail("请在「每周安排」中设置有效的开始和截止日期", "date"); return }
        if (days.isEmpty()) { scheduleExpanded = true; fail("请先选择执行日", "days"); return }
        val seriesIds = initial?.let { current -> data.plans.filter { it.seriesId == current.seriesId }.mapTo(mutableSetOf()) { it.id } }.orEmpty()
        val hasTodayProgress = data.checkIns.any { it.planId in seriesIds && it.date == LocalDate.now().toString() && it.amount > 0 }
        val effective = if (initial == null) start else maxOf(start, LocalDate.now().plusDays(if (hasTodayProgress) 1 else 0))
        val accumulated = if (initial == null) BigDecimal.ZERO else totalGoalProgress(data, initial.copy(unit = unit)).first.toBigDecimal()
        val outstanding = BigDecimal.valueOf(full.toLong(), currentScale).subtract(accumulated).max(BigDecimal.ZERO).movePointRight(currentScale).setScale(0, RoundingMode.CEILING).toLong()
        val count = countExecutionDays(effective, end, days, initial?.pauses.orEmpty(), initial?.skips.orEmpty())
        when {
            outstanding == 0L -> fail("总目标已经完成，无需再分配")
            count == 0L -> fail("剩余日期没有安排执行日，请调整每周安排", "days")
            else -> {
                val perDay = ((outstanding + count - 1) / count).toInt().coerceAtLeast(1)
                target = formatQuantity(perDay, Plan(title = "目标", category = category, target = perDay, unit = unit, scale = currentScale))
                allocationMessage = "已分配：每次 $target $unit，共 $count 个执行日"
                clearError()
            }
        }
    }
    val frequency = when (days) {
        (1..7).toSet() -> "每天"
        (1..5).toSet() -> "工作日"
        setOf(6, 7) -> "周末"
        emptySet<Int>() -> "尚未选择执行日"
        else -> "周" + days.sorted().joinToString("、") { weekNames[it - 1] }
    }
    FormDialog(
        title = if (initial == null) "添加任务" else "编辑任务",
        subtitle = if (initial == null) "写下要做的事，从一个小目标开始。" else "调整接下来的安排，保留已有记录。",
        error = listOf(error, draft.error).filter(String::isNotBlank).joinToString("\n"), onDismiss = ::requestDismiss,
        confirmLabel = if (saving) "正在保存…" else if (!draft.ready) "正在读取…" else "保存任务", onConfirm = ::savePlan,
        busy = saving || !draft.ready,
    ) {
        DraftNotice(draft.notice, draft.isolatedText)
        OutlinedTextField(
            title, { if (it.length <= 80) { title = it; if (errorField == "title") clearError() } },
            label = { Text("任务名称") }, placeholder = { Text("例如：阅读、练习英语") }, singleLine = true,
            isError = errorField == "title", shape = PlanSmallShape, modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Category.entries.forEach { choice ->
                FilterChip(customCategoryId == null && category == choice, { category = choice; customCategoryId = null }, label = { Text(categoryName(choice)) },
                    leadingIcon = { Icon(categoryIcon(choice), null, Modifier.size(17.dp)) })
            }
            data.categories.forEach { choice ->
                FilterChip(customCategoryId == choice.id, { customCategoryId = choice.id }, label = { Text(choice.name) },
                    leadingIcon = { Icon(planIcon(choice.iconId), null, Modifier.size(17.dp)) })
            }
        }
        TextButton({ iconExpanded = !iconExpanded }) {
            Icon(iconId?.let(::planIcon) ?: customCategoryId?.let { id -> data.categories.firstOrNull { it.id == id } }?.let { planIcon(it.iconId) } ?: categoryIcon(category), null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp)); Text("任务图标 · " + (iconId?.let { id -> PlanIcon.entries.firstOrNull { it.name == id }?.label } ?: "跟随分类"))
            Icon(if (iconExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (iconExpanded) PlanIconChoices(iconId, { iconId = it })
        if (data.categories.isEmpty()) Text("可在「计划 → 管理分类」中添加自己的分类。", color = Muted, style = MaterialTheme.typography.bodySmall)
        Surface(color = SoftSurface, shape = PlanCardShape) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("添加进度目标", style = MaterialTheme.typography.titleSmall)
                        Text(if (measured) "用时长或数量记录进步" else "选填，普通任务完成后直接打卡", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    Switch(measured, { measured = it; clearError() }, modifier = Modifier.semantics { contentDescription = "添加进度目标" })
                }
                if (measured) {
                    OutlinedTextField(target, { if (it.length <= 12) { target = it; allocationMessage = ""; if (errorField == "target") clearError() } },
                        label = { Text("每次目标") }, placeholder = { Text("请输入数量") }, isError = errorField == "target",
                        keyboardOptions = KeyboardOptions(keyboardType = if (scale == 2) KeyboardType.Decimal else KeyboardType.Number),
                        singleLine = true, shape = PlanSmallShape, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(unit, { if (it.length <= 12) changeUnit(it) }, label = { Text("单位") }, isError = errorField == "unit",
                        singleLine = true, shape = PlanSmallShape, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        listOf("分钟", "小时", "页", "题", "次", "公里").forEach { value ->
                            FilterChip(unit == value, { changeUnit(value) }, label = { Text(value) })
                        }
                    }
                    Text(if (scale == 2) "支持最多两位小数，例如 1.5 $unit。" else "按当前单位填写整数数量。", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                onClick = { scheduleExpanded = !scheduleExpanded }, modifier = Modifier.fillMaxWidth(),
                shape = PlanCardShape, color = SoftSurface,
            ) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CalendarMonth, null, tint = Teal, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("每周安排", style = MaterialTheme.typography.titleSmall)
                        Text("$frequency · ${if (start == LocalDate.now()) "今天开始" else "${start}开始"}" + (due?.let { "\n截止至 $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    Icon(if (scheduleExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (scheduleExpanded) "收起安排" else "调整安排", tint = Muted)
                }
            }
            if (scheduleExpanded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    (1..7).forEach { day ->
                        val selected = day in days
                        Box(
                            Modifier.weight(1f).heightIn(min = 46.dp).clip(PlanSmallShape)
                                .background(if (selected) Teal else Paper)
                                .toggleable(selected, role = Role.Checkbox) { days = if (selected) days - day else days + day; if (errorField == "days") clearError() }
                                .semantics { contentDescription = "星期${weekNames[day - 1]}" }.padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(weekNames[day - 1], color = if (selected) OnAccent else Muted, fontSize = 14.sp) }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ days = (1..7).toSet(); clearError() }) { Text("每天") }
                    TextButton({ days = (1..5).toSet(); clearError() }) { Text("工作日") }
                    TextButton({ days = setOf(6, 7); clearError() }) { Text("周末") }
                }
                OutlinedButton({ choosePlanDate(context, start) { start = it; clearError() } }, modifier = Modifier.fillMaxWidth(), shape = PlanSmallShape) { Text("开始日期：$start") }
                OutlinedButton({ choosePlanDate(context, due ?: start.plusDays(29)) { due = it; clearError() } }, modifier = Modifier.fillMaxWidth(), shape = PlanSmallShape) { Text(due?.let { "截止日期：$it" } ?: "添加截止日期（选填）") }
                if (due != null) TextButton({ due = null; clearError() }) { Text("不设截止日期") }
            }
        }
        HorizontalDivider(color = Line)
        FormSection("任务提醒", "选填，在执行日提醒尚未完成的任务。") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("设置提醒时间", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(reminderEnabled, { reminderEnabled = it }, modifier = Modifier.semantics { contentDescription = "设置任务提醒" })
            }
            if (reminderEnabled) {
                OutlinedButton({
                    val time = LocalTime.parse(reminderTime)
                    TimePickerDialog(context, planDialogTheme(context), { _, hour, minute ->
                        reminderTime = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
                    }, time.hour, time.minute, true).show()
                }, Modifier.fillMaxWidth(), shape = PlanSmallShape) {
                    Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("提醒时间：$reminderTime")
                }
                Text("完成当天目标、跳过当天或进入暂停期后不再提醒。已过今天设定时间时，从下一次执行日开始。通知中可选择稍后 15 分钟或今天不再提醒。", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (reminderStatus.isNotEmpty()) Text(reminderStatus.joinToString("\n"), color = Muted, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!PlanReminderScheduler.notificationsAllowed(context)) TextButton({
                        if (Build.VERSION.SDK_INT >= 33) reminderPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else runCatching { PlanReminderScheduler.openSettings(context) }.onFailure { fail("无法打开系统设置，请从手机设置中允许通知") }
                    }) { Text("允许通知") }
                    if (!PlanReminderScheduler.canScheduleExact(context)) TextButton({
                        runCatching { PlanReminderScheduler.openExactSettings(context) }.onFailure { fail("无法打开准时提醒设置") }
                    }) { Text("允许准时提醒") }
                    TextButton({ runCatching { PlanReminderScheduler.openSettings(context) }.onFailure { fail("无法打开任务提醒设置") } }) { Text("系统提醒设置") }
                }
            }
        }
        if (measured) {
            HorizontalDivider(color = Line)
            FormSection("长期目标", "选填，适合阅读一本书、累计跑量等安排。") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("设置总目标", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(totalEnabled, { totalEnabled = it; clearError() }, modifier = Modifier.semantics { contentDescription = "设置长期总目标" })
                }
                if (totalEnabled) {
                    OutlinedTextField(total, { if (it.length <= 12) { total = it; allocationMessage = ""; if (errorField == "total") clearError() } },
                        label = { Text("总目标（$unit）") }, isError = errorField == "total",
                        keyboardOptions = KeyboardOptions(keyboardType = if (scale == 2) KeyboardType.Decimal else KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = PlanSmallShape)
                    OutlinedButton(onClick = ::allocateTarget, modifier = Modifier.fillMaxWidth(), shape = PlanSmallShape) {
                        Text("按执行天数分配每次目标")
                    }
                    if (allocationMessage.isNotEmpty()) Text(allocationMessage, style = MaterialTheme.typography.bodyMedium, color = Teal)
                    Text("先设置截止日期，再分配每次目标；已完成的进度会自动扣除。", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
        if (initial != null) Text("名称、分类、图标与提醒立即更新；目标、单位或日程变化时，今天已有打卡则从明天生效。", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
    if (exitQuestion) DraftExitDialog(saving, { leave(true) }, { leave(false) }, { exitQuestion = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun CheckInDialog(plan: Plan, data: AppData, date: LocalDate, onDismiss: () -> Unit, onSave: suspend (CheckInSubmission) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val previous = remember(plan.id, date) { amountFor(data, plan.id, date) }
    val simple = plan.tracking == TrackingMode.TASK
    val planBaseline = remember(plan.id, date) { draftPlanFingerprint(plan) }
    val entryBaseline = remember(plan.id, date) { draftCheckInFingerprint(data, plan.id, date) }
    var operationId by remember(plan.id, date.toString()) { mutableStateOf(UUID.randomUUID().toString()) }
    var mode by remember(plan.id, date.toString()) { mutableStateOf(CheckInAmountMode.ADD) }
    var addAmount by remember(plan.id, date.toString()) { mutableStateOf("") }
    var totalAmount by remember(plan.id, date.toString()) { mutableStateOf(if (previous > 0) formatQuantity(previous, plan) else "") }
    var checked by remember(plan.id, date.toString()) { mutableStateOf(true) }
    var note by remember(plan.id, date.toString()) { mutableStateOf(data.checkIns.find { it.planId == plan.id && it.date == date.toString() }?.note ?: "") }
    var error by remember(plan.id, date.toString()) { mutableStateOf("") }
    var amountError by remember(plan.id, date.toString()) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var exitQuestion by remember { mutableStateOf(false) }
    fun fields(): Map<String, String> = linkedMapOf("mode" to mode.name, "addAmount" to addAmount, "totalAmount" to totalAmount,
        "checked" to checked.toString(), "note" to note)
    val originalFields = remember(plan.id, date) {
        linkedMapOf("mode" to CheckInAmountMode.ADD.name, "addAmount" to "", "totalAmount" to (if (previous > 0) formatQuantity(previous, plan) else ""),
            "checked" to "true", "note" to (data.checkIns.find { it.planId == plan.id && it.date == date.toString() }?.note ?: ""))
    }
    val draft = rememberDraftEditor(
        key = "checkin:${plan.seriesId}:$date", baseline = "$planBaseline:$entryBaseline",
        values = { fields().takeUnless { it == originalFields }?.plus("operationId" to operationId) },
        restore = { values ->
            val restored = CheckInDraftFields.read(values)
            mode = restored.mode; addAmount = restored.addAmount; totalAmount = restored.totalAmount
            checked = restored.checked; note = restored.note; operationId = restored.operationId
        },
    )
    fun updateDraft() { draft.update(fields().takeUnless { it == originalFields }?.plus("operationId" to operationId)) }
    fun requestDismiss() {
        if (saving || !draft.ready) return
        updateDraft()
        if (draft.dirty) exitQuestion = true else onDismiss()
    }
    fun leave(keep: Boolean) {
        if (saving) return
        saving = true
        scope.launch {
            withContext(NonCancellable) {
                try { updateDraft(); if (keep) draft.keep() else draft.discard(); onDismiss() }
                catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) { error = failure.message ?: "草稿处理失败，请重试"; exitQuestion = false }
                finally { saving = false }
            }
        }
    }
    val amount = if (mode == CheckInAmountMode.ADD) addAmount else totalAmount
    val parsed = if (simple) { if (checked) 1 else 0 } else parseQuantity(amount, plan.unit, plan.scale, allowZero = mode == CheckInAmountMode.TOTAL)
    val effectiveMode = if (simple) CheckInAmountMode.TOTAL else mode
    val preview = parsed?.let { runCatching { checkInResultAmount(previous, it, effectiveMode, plan) }.getOrNull() }
    fun saveCheckIn() {
        if (saving || !draft.ready) return
        // Read state at the click, not a derived value captured by a remembered callback.
        val currentMode = if (simple) CheckInAmountMode.TOTAL else mode
        val currentInput = if (mode == CheckInAmountMode.ADD) addAmount else totalAmount
        val currentAmount = if (simple) { if (checked) 1 else 0 }
            else parseQuantity(currentInput, plan.unit, plan.scale, allowZero = currentMode == CheckInAmountMode.TOTAL)
        if (currentAmount == null) {
            amountError = true
            error = if (currentMode == CheckInAmountMode.ADD && currentInput.trim() == "0") "本次新增请填写大于 0 的数量；撤销打卡可使用修改当天总量。"
                else if (plan.scale == 2) "请输入有效数量，支持最多两位小数" else "请输入有效的整数数量"
            return
        }
        try { checkInResultAmount(previous, currentAmount, currentMode, plan) }
        catch (failure: IllegalArgumentException) { amountError = true; error = failure.message.orEmpty(); return }
        amountError = false; error = ""
        val submission = CheckInSubmission(operationId, currentMode, currentAmount, note.trim(), planBaseline, entryBaseline)
        saving = true
        updateDraft()
        scope.launch {
            withContext(NonCancellable) {
                try {
                    draft.prepareSave()
                    onSave(submission)
                    savedDraftCleanupNotice(context, draft.saved())
                    onDismiss()
                } catch (failure: CancellationException) { draft.failedSave(); throw failure }
                catch (failure: Exception) {
                    val cleanupFailure = draft.failedSave()
                    error = (failure.message ?: "打卡暂未保存，请重试") + if (cleanupFailure != null) "\n草稿仍保留为待确认内容，重新打开后可查看和复制。" else ""
                }
                finally { saving = false }
            }
        }
    }
    FormDialog(
        title = plan.title,
        subtitle = date.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日")) + if (date < LocalDate.now()) " · 补记 / 修改" else " · 今日打卡",
        error = listOf(error, draft.error).filter(String::isNotBlank).joinToString("\n"), onDismiss = ::requestDismiss,
        confirmLabel = if (saving) "正在保存…" else if (!draft.ready) "正在读取…" else "保存打卡",
        onConfirm = ::saveCheckIn, busy = saving || !draft.ready,
    ) {
        DraftNotice(draft.notice, draft.isolatedText)
        if (simple) {
            Surface(color = if (checked) Mint else SoftSurface, shape = PlanCardShape) {
                Row(
                    Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox) { checked = it }.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked, onCheckedChange = null)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (checked) "完成任务" else "尚未完成", style = MaterialTheme.typography.titleMedium)
                        Text(if (checked) { if (date == LocalDate.now()) "记录今天的小小进步" else "记录这一天的小小进步" } else "保存后撤销当天的完成状态", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
            }
            if (previous > 0) TextButton({ checked = false }) { Text("撤销这次打卡") }
            if (!checked && note.isNotBlank()) Text("撤销当天打卡也会移除这条记录的心得。", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            Surface(color = SoftSurface, shape = PlanCardShape) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("每次目标  ${formatQuantity(plan.target, plan)} ${plan.unit}", style = MaterialTheme.typography.titleSmall)
                    Text("当天已记录 ${formatQuantity(previous, plan)} ${plan.unit}", style = MaterialTheme.typography.bodyMedium, color = Muted)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == CheckInAmountMode.ADD, { mode = CheckInAmountMode.ADD; amountError = false; error = "" }, label = { Text("本次新增") })
                FilterChip(mode == CheckInAmountMode.TOTAL, { mode = CheckInAmountMode.TOTAL; amountError = false; error = "" }, label = { Text("修改当天总量") })
            }
            OutlinedTextField(amount, { if (it.length <= 12) { if (mode == CheckInAmountMode.ADD) addAmount = it else totalAmount = it; amountError = false; error = "" } },
                label = { Text(if (mode == CheckInAmountMode.ADD) "本次新增（${plan.unit}）" else "当天总量（${plan.unit}）") },
                placeholder = { Text("请输入数量") },
                supportingText = { Text(if (mode == CheckInAmountMode.ADD) "只填写这次完成的数量，会加到已记录进度。" else "填写当天最终总量，会替换已记录数量。") }, isError = amountError,
                keyboardOptions = KeyboardOptions(keyboardType = if (plan.scale == 2) KeyboardType.Decimal else KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = PlanSmallShape)
            Text(if (preview == null) "保存后总量：待填写" else "保存后总量：${formatQuantity(preview, plan)} ${plan.unit}", color = if (preview == null) Muted else Teal, style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SuggestionChip({ mode = CheckInAmountMode.TOTAL; totalAmount = formatQuantity(maxOf(previous, plan.target), plan); amountError = false; error = "" }, label = { Text("完成目标") })
                if (previous > 0) SuggestionChip({ mode = CheckInAmountMode.TOTAL; totalAmount = "0"; amountError = false; error = "" }, label = { Text("撤销打卡") })
            }
            if (preview == 0) Text("保存为 0 将撤销当天打卡并移除该条心得。", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(note, { if (it.length <= 1000) note = it }, label = { Text("任务心得（选填）") },
            placeholder = { Text("完成这项任务，有什么收获？") }, supportingText = { Text("可留空；独立的每日心得在首页「写心得」中填写。") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(), shape = PlanSmallShape)
    }
    if (exitQuestion) DraftExitDialog(saving, { leave(true) }, { leave(false) }, { exitQuestion = false })
}
