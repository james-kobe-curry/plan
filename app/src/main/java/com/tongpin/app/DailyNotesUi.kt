package com.tongpin.app

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** A day's reflection is optional and independent of task check-ins. */
@Composable
fun DailyNoteEditor(
    date: LocalDate,
    initial: DailyNote?,
    onDismiss: () -> Unit,
    onSave: suspend (String) -> Unit,
) {
    val dateKey = date.toString()
    val original = initial?.text.orEmpty()
    var text by rememberSaveable(dateKey) { mutableStateOf(original) }
    var error by rememberSaveable(dateKey) { mutableStateOf("") }
    var confirmDiscard by rememberSaveable(dateKey) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(dateKey) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun requestDismiss() {
        if (busy) return
        if (text != original) confirmDiscard = true else onDismiss()
    }

    fun persist(value: String) {
        if (busy) return
        if (initial == null && date.isAfter(LocalDate.now())) {
            error = "请选择今天或过去的日期"
            return
        }
        if (value.length > DailyNoteRules.MAX_TEXT_LENGTH) {
            error = "每日心得最多 ${DailyNoteRules.MAX_TEXT_LENGTH} 个字"
            return
        }
        // A blank new editor never creates an empty entry or an unnecessary data write.
        if (initial == null && value.isBlank()) { onDismiss(); return }
        busy = true
        error = ""
        scope.launch {
            try {
                onSave(value)
                onDismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "心得未能保存，请重试"
            } finally {
                busy = false
            }
        }
    }

    FormDialog(
        title = "每日心得",
        subtitle = dailyNoteDateLabel(date),
        error = error,
        onDismiss = ::requestDismiss,
        confirmLabel = if (busy) "正在保存…" else "保存心得",
        onConfirm = {
            if (initial != null && text.isBlank()) confirmDelete = true else persist(text)
        },
        busy = busy,
    ) {
        Text("选填，想写时再记下。", color = Muted, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                if (value.length <= DailyNoteRules.MAX_TEXT_LENGTH) {
                    text = value
                    error = ""
                } else {
                    error = "每日心得最多 ${DailyNoteRules.MAX_TEXT_LENGTH} 个字，请缩短后再填写"
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = PlanSmallShape,
            label = { Text("这一天，想记下什么？") },
            placeholder = { Text("今天的收获、遇到的困难，或一件值得记住的小事……") },
            minLines = 8,
            maxLines = 18,
            supportingText = { Text("${text.length} / ${DailyNoteRules.MAX_TEXT_LENGTH}") },
        )
        if (initial != null) {
            PlanQuietButton(onClick = { confirmDelete = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp), tint = ErrorInk)
                Spacer(Modifier.width(8.dp))
                Text("删除这篇心得", color = ErrorInk)
            }
        }
    }

    if (confirmDiscard) PlanAlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("放弃尚未保存的修改？") },
        text = { Text("这次输入的内容还没有保存。") },
        confirmButton = {
            PlanPrimaryButton(onClick = { confirmDiscard = false }) { Text("继续编辑") }
        },
        dismissButton = {
            PlanQuietButton(onClick = { confirmDiscard = false; onDismiss() }) { Text("放弃修改") }
        },
    )
    if (confirmDelete) PlanAlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("删除这篇心得？") },
        text = { Text("将删除 ${dailyNoteDateLabel(date)} 的每日心得，已有打卡和专注记录会保留。") },
        confirmButton = {
            Button(
                onClick = { confirmDelete = false; persist("") },
                enabled = !busy,
                shape = PlanButtonShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("删除心得") }
        },
        dismissButton = {
            PlanQuietButton(onClick = { confirmDelete = false }) { Text("继续编辑") }
        },
    )
}

@Composable
fun DailyNotesDialog(
    data: AppData,
    onDismiss: () -> Unit,
    onEdit: (LocalDate) -> Unit,
    readOnly: Boolean = false,
) {
    val context = LocalContext.current
    val today = LocalDate.now()
    var query by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    val notes = remember(data.dailyNotes, query) {
        val search = query.trim()
        data.dailyNotes.filter { search.isBlank() || it.text.contains(search, ignoreCase = true) || it.date.contains(search) }
            .sortedByDescending { it.date }
    }

    fun editDate(date: LocalDate) {
        if (readOnly) return
        if (dailyNoteFor(data, date) == null && date.isAfter(LocalDate.now())) error = "请选择今天或过去的日期"
        else { error = ""; onEdit(date) }
    }

    fun pickDate() {
        try {
            DatePickerDialog(context, planDialogTheme(context), { _, year, month, day ->
                editDate(LocalDate.of(year, month + 1, day))
            }, today.year, today.monthValue - 1, today.dayOfMonth).apply {
                datePicker.maxDate = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.show()
        } catch (failure: Exception) {
            error = failure.message ?: "日期选择器未能打开，请重试"
        }
    }

    PlanDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回记录") }
                    Column(Modifier.weight(1f)) {
                        Text("每日心得", style = MaterialTheme.typography.titleLarge)
                        Text(if (readOnly) "存档中的 ${data.dailyNotes.size} 篇心得" else "已记下 ${data.dailyNotes.size} 天", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
                OutlinedTextField(
                    query, { query = it.take(200) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    shape = PlanSmallShape,
                    singleLine = true,
                    label = { Text("搜索心得或日期") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        { IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "清空搜索") } }
                    } else null,
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (notes.isEmpty()) item(key = "empty") {
                        Surface(color = SurfaceColor, shape = PlanCardShape, border = PlanCardBorder) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Outlined.EditNote, null, Modifier.size(32.dp), tint = Teal)
                                Text(if (query.isNotBlank()) "没有找到相关心得" else "还没有每日心得", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    when {
                                        query.isNotBlank() -> "试试其他关键词，或清空搜索查看全部。"
                                        readOnly -> "这份存档尚未保存每日心得。"
                                        else -> "记下这一天的收获与感受。想写时再写，不影响打卡与完成率。"
                                    },
                                    style = MaterialTheme.typography.bodyMedium, color = Muted,
                                )
                            }
                        }
                    }
                    items(notes, key = { it.date }) { note ->
                        DailyNoteCard(note, editable = !readOnly) {
                            editDate(LocalDate.parse(note.date))
                        }
                    }
                }
                if (!readOnly || error.isNotBlank()) {
                    HorizontalDivider(color = Line)
                    Column(
                        Modifier.fillMaxWidth().background(SurfaceColor).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FormErrorBanner(error, maxHeight = 100.dp)
                        if (!readOnly) BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val stacked = maxWidth < 310.dp || LocalDensity.current.fontScale >= 1.5f
                            if (stacked) {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PlanPrimaryButton(onClick = { editDate(LocalDate.now()) }, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Outlined.EditNote, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("写今日心得")
                                    }
                                    PlanSecondaryButton(onClick = ::pickDate, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("选择日期")
                                    }
                                }
                            } else {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    PlanSecondaryButton(onClick = ::pickDate, modifier = Modifier.weight(.44f)) {
                                        Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("选择日期")
                                    }
                                    PlanPrimaryButton(onClick = { editDate(LocalDate.now()) }, modifier = Modifier.weight(.56f)) {
                                        Icon(Icons.Outlined.EditNote, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("写今日心得")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyNoteCard(note: DailyNote, editable: Boolean, onEdit: () -> Unit) {
    var expanded by rememberSaveable(note.date) { mutableStateOf(false) }
    var overflows by remember(note.text) { mutableStateOf(false) }
    Surface(color = SurfaceColor, shape = PlanCardShape, border = PlanCardBorder) {
        Column(Modifier.fillMaxWidth().padding(PlanCardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(dailyNoteDateLabel(LocalDate.parse(note.date)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            SelectionContainer {
                Text(
                    note.text, style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (expanded) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                )
            }
            if (expanded || overflows || editable) BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.5f
                if (stacked) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (expanded || overflows) PlanQuietButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) { Text(if (expanded) "收起全文" else "展开全文") }
                        if (editable) PlanSecondaryButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.EditNote, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("编辑心得")
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (expanded || overflows) PlanQuietButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起全文" else "展开全文") }
                        Spacer(Modifier.weight(1f))
                        if (editable) PlanSecondaryButton(onClick = onEdit) {
                            Icon(Icons.Outlined.EditNote, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("编辑心得")
                        }
                    }
                }
            }
        }
    }
}

private fun dailyNoteDateLabel(date: LocalDate): String {
    val weekday = listOf("一", "二", "三", "四", "五", "六", "日")[date.dayOfWeek.value - 1]
    return "${date.year}年${date.monthValue}月${date.dayOfMonth}日 · 周$weekday"
}
