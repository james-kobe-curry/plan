package com.tongpin.app

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Full history stays attached to the plan version that originally recorded its unit. */
@Composable
fun HistoryBrowser(data: AppData, onDismiss: () -> Unit, onCheck: (Plan, LocalDate) -> Unit, readOnly: Boolean = false) {
    val today = LocalDate.now()
    val context = LocalContext.current
    var selectedDateText by rememberSaveable { mutableStateOf(today.toString()) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var mode by rememberSaveable { mutableStateOf("calendar") }
    var selectedSeries by rememberSaveable { mutableStateOf<String?>(null) }
    var showFilter by rememberSaveable { mutableStateOf(false) }
    var queryText by rememberSaveable { mutableStateOf("") }
    var rangeName by rememberSaveable { mutableStateOf(HistoryRange.ALL.name) }
    var fromText by rememberSaveable { mutableStateOf(today.withDayOfMonth(1).toString()) }
    var throughText by rememberSaveable { mutableStateOf(today.toString()) }
    var showRange by rememberSaveable { mutableStateOf(false) }
    val selectedDate = LocalDate.parse(selectedDateText)
    val month = YearMonth.parse(monthText)
    val currentMonth = YearMonth.from(today)
    val plansById = remember(data.plans) { data.plans.associateBy { it.id } }
    val series = remember(data.plans) { historyTaskRepresentatives(data) }
    val filteredPlans = remember(data.plans, selectedSeries) {
        data.plans.filter { selectedSeries == null || it.seriesId == selectedSeries }
    }
    val selectedRange = HistoryRange.valueOf(rangeName)
    val dateRange = historyDateRange(selectedRange, today,
        HistoryDateRange(LocalDate.parse(fromText), LocalDate.parse(throughText)))
    val calendarEntries = remember(data.plans, data.checkIns, selectedSeries, queryText) {
        HistoryQuery(selectedSeries, queryText).checkIns(data)
    }
    val entries = remember(calendarEntries, dateRange) {
        calendarEntries.filter { dateRange == null || dateRange.contains(LocalDate.parse(it.date)) }
    }
    val calendarEntriesByDate = remember(calendarEntries) { calendarEntries.groupBy { it.date } }
    val entriesByDate = remember(entries) { entries.groupBy { it.date } }
    val focusEntries = remember(data.plans, data.focusRecords, selectedSeries, queryText, dateRange) {
        HistoryQuery(selectedSeries, queryText, dateRange).focusRecords(data)
    }
    val dayEntries = remember(calendarEntriesByDate, selectedDateText) {
        calendarEntriesByDate[selectedDateText].orEmpty().associateBy { it.planId }
    }
    val dayPlans = remember(filteredPlans, selectedDate, dayEntries, queryText) {
        // Older backups may contain a record outside its plan's final schedule. Keep it visible.
        val scheduledIds = effectivePlansForDate(data, selectedDate).filter { isScheduled(it, selectedDate) }.mapTo(hashSetOf()) { it.id }
        filteredPlans.filter { (it.id in scheduledIds || it.id in dayEntries) &&
            (queryText.isBlank() || it.title.contains(queryText.trim(), ignoreCase = true) || it.id in dayEntries) }
            .sortedWith(compareBy<Plan> { (dayEntries[it.id]?.amount ?: 0) >= it.target }.thenBy { it.title })
    }
    val filterTitle = series.firstOrNull { it.seriesId == selectedSeries }?.title ?: "全部任务"

    fun selectDate(date: LocalDate) {
        if (!date.isAfter(today)) {
            selectedDateText = date.toString()
            monthText = YearMonth.from(date).toString()
        }
    }

    fun changeMonth(next: YearMonth) {
        val date = next.atDay(minOf(selectedDate.dayOfMonth, next.lengthOfMonth()))
        selectDate(minOf(date, today))
    }

    fun pickDate() {
        DatePickerDialog(context, planDialogTheme(context), { _, year, monthIndex, day ->
            selectDate(LocalDate.of(year, monthIndex + 1, day))
        }, selectedDate.year, selectedDate.monthValue - 1, selectedDate.dayOfMonth).apply {
            datePicker.maxDate = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val earliest = filteredPlans.minOfOrNull { LocalDate.parse(it.startDate) }
            if (earliest != null && !earliest.isAfter(today)) {
                datePicker.minDate = minOf(earliest, selectedDate)
                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }.show()
    }

    PlanDialog(onDismissRequest = onDismiss, showMessages = true, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回记录")
                    }
                    Text("历史记录", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { mode = "calendar"; selectDate(today) }) { Text("今天") }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item(key = "controls") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth().clip(PlanSmallShape).background(Line).padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                HistoryModeTab("按日期", mode == "calendar", Modifier.weight(1f)) { mode = "calendar" }
                                HistoryModeTab("打卡明细", mode == "checkins", Modifier.weight(1f)) { mode = "checkins" }
                                HistoryModeTab("专注明细", mode == "focus", Modifier.weight(1f)) { mode = "focus" }
                            }
                            OutlinedTextField(value = queryText, onValueChange = { queryText = it.take(200) },
                                singleLine = true, modifier = Modifier.fillMaxWidth(),
                                shape = PlanButtonShape,
                                label = { Text(if (mode == "focus") "搜索专注任务" else "搜索任务或心得") },
                                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                trailingIcon = if (queryText.isNotEmpty()) {{
                                    IconButton(onClick = { queryText = "" }) { Icon(Icons.Outlined.Close, "清空搜索") }
                                }} else null)
                            OutlinedButton(onClick = { showFilter = true }, modifier = Modifier.fillMaxWidth(),
                                shape = PlanButtonShape, contentPadding = PaddingValues(14.dp)) {
                                Icon(Icons.Outlined.FilterList, null, Modifier.size(20.dp))
                                Text(filterTitle, Modifier.weight(1f).padding(horizontal = 10.dp),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Outlined.ExpandMore, "筛选任务", Modifier.size(20.dp))
                            }
                            if (mode != "calendar") {
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(HistoryRange.ALL to "全部", HistoryRange.THIS_WEEK to "本周",
                                        HistoryRange.THIS_MONTH to "本月", HistoryRange.CUSTOM to "自定义").forEach { (range, label) ->
                                        FilterChip(selected = selectedRange == range, onClick = {
                                            if (range == HistoryRange.CUSTOM) showRange = true else rangeName = range.name
                                        }, label = { Text(label) })
                                    }
                                }
                                if (dateRange != null) {
                                    Text("${dateRange.from} 至 ${dateRange.through}",
                                        style = MaterialTheme.typography.bodySmall, color = Muted)
                                }
                            }
                        }
                    }
                    if (mode == "calendar") {
                        item(key = "calendar") {
                            HistoryCalendar(month, selectedDate, today, calendarEntriesByDate,
                                onPrevious = { if (month.year > 1 || month.monthValue > 1) changeMonth(month.minusMonths(1)) },
                                onNext = { if (month < currentMonth) changeMonth(month.plusMonths(1)) },
                                onPick = ::pickDate, onDate = ::selectDate)
                        }
                        item(key = "day_heading") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(selectedDate.format(DateTimeFormatter.ofPattern("M 月 d 日")) + " · " +
                                    historyWeekday(selectedDate), style = MaterialTheme.typography.titleLarge)
                                Text(if (dayPlans.isEmpty()) { if (queryText.isBlank()) "这一天没有安排任务" else "这一天没有匹配的任务或心得" } else
                                    "${dayEntries.size} 项有记录 · ${dayPlans.size} 项任务", color = Muted,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (dayPlans.isEmpty()) {
                            item(key = "empty_day") {
                                    HistoryEmpty(if (queryText.isBlank()) "留一点空白，也很好" else "没有匹配的记录",
                                        "切换日期或调整筛选，查看当天的任务和记录。")
                            }
                        }
                        items(dayPlans, key = { "day:${it.id}" }) { plan ->
                            val entry = dayEntries[plan.id]
                            val editable = !readOnly && !selectedDate.isAfter(today) && isScheduled(plan, selectedDate)
                            HistoryEntryCard(data, plan, entry, editable, showDate = false) {
                                if (editable) onCheck(plan, selectedDate)
                            }
                        }
                    } else if (mode == "checkins") {
                        item(key = "all_heading") {
                            Text("${entries.size} 条打卡 · ${entriesByDate.size} 天", color = Muted,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        if (entries.isEmpty()) {
                            item(key = "empty_all") {
                                HistoryEmpty("没有匹配的打卡记录", "可以调整日期、任务或搜索关键词。")
                            }
                        }
                        itemsIndexed(entries, key = { _, entry -> "entry:${entry.date}:${entry.planId}" }) { index, entry ->
                            val plan = plansById[entry.planId]
                            if (plan != null) {
                                val date = LocalDate.parse(entry.date)
                                val editable = !readOnly && !date.isAfter(today) && isScheduled(plan, date)
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (index == 0 || entries[index - 1].date != entry.date) {
                                        Text(date.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日")) +
                                            " · " + historyWeekday(date),
                                            modifier = Modifier.padding(top = if (index == 0) 0.dp else 8.dp),
                                            style = MaterialTheme.typography.titleMedium)
                                    }
                                    HistoryEntryCard(data, plan, entry, editable, showDate = true) {
                                        if (editable) onCheck(plan, date)
                                    }
                                }
                            }
                        }
                    } else {
                        item(key = "focus_heading") {
                            Text("${focusEntries.size} 次专注 · ${historyFocusDuration(focusEntries.sumOf { it.seconds.toLong() })}",
                                color = Muted, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (focusEntries.isEmpty()) {
                            item(key = "empty_focus") {
                                HistoryEmpty("没有匹配的专注记录", "完成专注后，这里会记录每次的任务、时间和时长。")
                            }
                        }
                        itemsIndexed(focusEntries, key = { _, entry -> "focus:${entry.id}" }) { index, record ->
                            val date = focusRecordDate(record)
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (index == 0 || focusRecordDate(focusEntries[index - 1]) != date) {
                                    Text(date.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日")) + " · " + historyWeekday(date),
                                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 8.dp),
                                        style = MaterialTheme.typography.titleMedium)
                                }
                                FocusHistoryCard(data, record, plansById)
                            }
                        }
                    }
                }
            }
        }
    }
    if (showFilter) {
        HistoryFilterDialog(data, series, selectedSeries, onDismiss = { showFilter = false }) {
            selectedSeries = it
            showFilter = false
        }
    }
    if (showRange) {
        HistoryRangeDialog(LocalDate.parse(fromText), LocalDate.parse(throughText), today,
            onDismiss = { showRange = false }) { from, through ->
            fromText = from.toString()
            throughText = through.toString()
            rangeName = HistoryRange.CUSTOM.name
            showRange = false
        }
    }
}

@Composable
private fun FocusHistoryCard(data: AppData, record: FocusRecord, plans: Map<String, Plan>) {
    val plan = plans[record.planId]
    val completed = Instant.ofEpochMilli(record.completedAt).atZone(ZoneId.systemDefault())
    Row(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor).padding(16.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(PlanSmallShape)
            .background(plan?.let { planCategoryColor(it, data) } ?: Mint), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Timer, null, Modifier.size(21.dp), tint = Teal)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(focusRecordTitle(record, plans), style = MaterialTheme.typography.titleMedium)
            Text(historyFocusDuration(record.seconds.toLong()), color = Teal, fontWeight = FontWeight.SemiBold)
            Text("完成于 ${completed.format(DateTimeFormatter.ofPattern("HH:mm"))}" +
                (if (plan?.archived == true) " · 历史计划" else ""), color = Muted,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HistoryRangeDialog(initialFrom: LocalDate, initialThrough: LocalDate, today: LocalDate,
    onDismiss: () -> Unit, onSelect: (LocalDate, LocalDate) -> Unit) {
    val context = LocalContext.current
    var fromText by rememberSaveable { mutableStateOf(initialFrom.toString()) }
    var throughText by rememberSaveable { mutableStateOf(initialThrough.toString()) }
    var error by rememberSaveable { mutableStateOf("") }
    val from = LocalDate.parse(fromText)
    val through = LocalDate.parse(throughText)
    fun pick(value: LocalDate, onValue: (LocalDate) -> Unit) {
        DatePickerDialog(context, planDialogTheme(context), { _, year, month, day ->
            onValue(LocalDate.of(year, month + 1, day)); error = ""
        }, value.year, value.monthValue - 1, value.dayOfMonth).apply {
            datePicker.maxDate = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.show()
    }
    FormDialog(title = "筛选日期范围", subtitle = "包含开始和结束当天的记录。", error = error,
        onDismiss = onDismiss, confirmLabel = "应用筛选", onConfirm = {
            if (from.isAfter(through)) error = "开始日期不能晚于结束日期"
            else onSelect(from, through)
        }) {
        OutlinedButton(onClick = { pick(from) { fromText = it.toString() } }, modifier = Modifier.fillMaxWidth(),
            shape = PlanButtonShape, contentPadding = PaddingValues(16.dp)) {
            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(20.dp))
            Text("开始日期", Modifier.weight(1f).padding(horizontal = 12.dp))
            Text(fromText)
        }
        OutlinedButton(onClick = { pick(through) { throughText = it.toString() } }, modifier = Modifier.fillMaxWidth(),
            shape = PlanButtonShape, contentPadding = PaddingValues(16.dp)) {
            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(20.dp))
            Text("结束日期", Modifier.weight(1f).padding(horizontal = 12.dp))
            Text(throughText)
        }
    }
}

@Composable
private fun HistoryModeTab(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(PlanSmallShape).background(if (active) SurfaceColor else Color.Transparent)
        .semantics { selected = active }.clickable(role = Role.Tab, onClick = onClick)
        .heightIn(min = 46.dp).padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (active) Teal else Muted, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HistoryCalendar(
    month: YearMonth, selectedDate: LocalDate, today: LocalDate,
    entriesByDate: Map<String, List<CheckIn>>,
    onPrevious: () -> Unit, onNext: () -> Unit, onPick: () -> Unit, onDate: (LocalDate) -> Unit,
) {
    val firstOffset = month.atDay(1).dayOfWeek.value - 1
    val cells = ((firstOffset + month.lengthOfMonth() + 6) / 7) * 7
    Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, enabled = month.year > 1 || month.monthValue > 1) {
                Icon(Icons.Outlined.ChevronLeft, "上一个月")
            }
            TextButton(onClick = onPick, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 10.dp)) {
                Text("${month.year} 年 ${month.monthValue} 月", fontWeight = FontWeight.Bold)
                Icon(Icons.Outlined.ArrowDropDown, "选择日期", Modifier.size(18.dp))
            }
            IconButton(onClick = onNext, enabled = month < YearMonth.from(today)) {
                Icon(Icons.Outlined.ChevronRight, "下一个月")
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { weekday ->
                Box(Modifier.weight(1f).padding(vertical = 4.dp)
                    .semantics { contentDescription = "星期$weekday" }, contentAlignment = Alignment.Center) {
                    Text(weekday, style = MaterialTheme.typography.bodyMedium,
                        color = Ink, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        (0 until cells / 7).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                (0..6).forEach { column ->
                    val day = row * 7 + column - firstOffset + 1
                    if (day !in 1..month.lengthOfMonth()) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        val date = month.atDay(day)
                        val active = date == selectedDate
                        val future = date.isAfter(today)
                        val recorded = entriesByDate[date.toString()].orEmpty().isNotEmpty()
                        val shape = PlanSmallShape
                        Column(Modifier.weight(1f).clip(shape)
                            .background(if (active) Teal else Color.Transparent)
                            .border(1.dp, if (date == today && !active) Teal else Color.Transparent, shape)
                            .semantics {
                                contentDescription = "${date.year}年${date.monthValue}月${date.dayOfMonth}日，${historyWeekday(date)}" +
                                    (if (recorded) "，有打卡记录" else "") + (if (future) "，尚未到来" else "")
                                selected = active
                            }
                            .clickable(enabled = !future, role = Role.Button) { onDate(date) }
                            .heightIn(min = 48.dp).padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(day.toString(), fontSize = 15.sp,
                                color = if (active) OnAccent else if (future) Muted.copy(alpha = .45f) else Ink,
                                fontWeight = if (active || date == today) FontWeight.Bold else FontWeight.Normal)
                            Box(Modifier.size(4.dp).background(
                                if (recorded) { if (active) OnAccent else Teal } else Color.Transparent, CircleShape))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).background(Teal, CircleShape))
            Text("有打卡记录", Modifier.padding(start = 6.dp), color = Muted,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun HistoryEntryCard(data: AppData, plan: Plan, entry: CheckIn?, editable: Boolean, showDate: Boolean, onClick: () -> Unit) {
    val amount = entry?.amount ?: 0
    val complete = amount >= plan.target
    Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor)
        .clickable(enabled = editable, onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(PlanSmallShape).background(planCategoryColor(plan, data)),
                contentAlignment = Alignment.Center) {
                Icon(planCategoryIcon(plan, data), null, Modifier.size(21.dp), tint = Ink)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(plan.title, style = MaterialTheme.typography.titleMedium)
                Text(planCategoryName(plan, data) + (if (plan.archived) " · 历史计划" else ""),
                    color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            if (editable) Icon(Icons.Outlined.ChevronRight, "编辑打卡", tint = Muted, modifier = Modifier.size(20.dp))
        }
        if (plan.tracking == TrackingMode.TASK) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(if (complete) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                    null, Modifier.size(20.dp), tint = if (complete) Teal else Muted)
                Text(if (complete) "已完成" else "待打卡", color = if (complete) Teal else Muted,
                    fontWeight = FontWeight.SemiBold)
            }
        } else {
            Text("${formatQuantity(amount, plan)} / ${formatQuantity(plan.target, plan)} ${plan.unit}",
                color = if (complete) Teal else Ink, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(progress = { (amount.toFloat() / plan.target).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = Teal, trackColor = Mint)
        }
        if (!entry?.note.isNullOrBlank()) {
            Text(entry!!.note, color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        if (!editable) {
            Text("历史记录", color = Muted, style = MaterialTheme.typography.labelSmall)
        } else if (showDate) {
            Text("点按修改进度或心得", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun HistoryFilterDialog(data: AppData, plans: List<Plan>, selected: String?, onDismiss: () -> Unit, onSelect: (String?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(plans, query) { plans.filter { it.title.contains(query.trim(), ignoreCase = true) } }
    PlanAlertDialog(onDismissRequest = onDismiss, title = { Text("筛选任务") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("搜索任务") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 330.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item(key = "all") {
                    HistoryFilterOption("全部任务", "", selected == null) { onSelect(null) }
                }
                items(matches, key = { it.seriesId }) { plan ->
                    HistoryFilterOption(plan.title, if (plan.archived) "已归档" else planCategoryName(plan, data),
                        selected == plan.seriesId) { onSelect(plan.seriesId) }
                }
                if (matches.isEmpty() && query.isNotBlank()) {
                    item(key = "empty") { Text("没有找到相关任务", Modifier.padding(vertical = 16.dp), color = Muted) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
private fun HistoryFilterOption(title: String, subtitle: String, active: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(PlanSmallShape)
        .background(if (active) Mint else Color.Transparent).clickable(role = Role.RadioButton, onClick = onClick)
        .semantics { selected = active }.padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        if (active) Icon(Icons.Outlined.Check, "已选择", tint = Teal, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun HistoryEmpty(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(30.dp), tint = Teal)
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun historyWeekday(date: LocalDate): String =
    "周" + listOf("一", "二", "三", "四", "五", "六", "日")[date.dayOfWeek.value - 1]
