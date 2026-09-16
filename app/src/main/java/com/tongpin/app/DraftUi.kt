package com.tongpin.app

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.LocalTime

private val draftWorker = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@Stable internal class DraftEditorState(private val store: DraftStore) {
    private val requestOrder = DraftStore.nextSessionOrder()
    var ready by mutableStateOf(false)
    var dirty by mutableStateOf(false)
    var notice by mutableStateOf("")
    var error by mutableStateOf("")
    var isolatedText by mutableStateOf("")
    private var session: DraftSession? = null
    private var lastValues: Map<String, String>? = null
    private var hasValues = false
    private var pending: Job? = null
    private var committed = false

    suspend fun open(key: String, baseline: String, restore: (Map<String, String>) -> Unit) {
        try {
            val result = withContext(Dispatchers.IO) { store.open(key, baseline, requestOrder) }
            session = result.session
            notice = result.notice.orEmpty()
            isolatedText = result.isolatedValues?.let(::draftReadableText).orEmpty()
            result.values?.let {
                try { restore(it); notice = "已恢复上次未保存的草稿。" }
                catch (failure: CancellationException) { throw failure }
                catch (_: Exception) {
                    isolatedText = draftReadableText(it)
                    withContext(Dispatchers.IO) { result.session.isolateInvalid() }
                    notice = "旧草稿内容已不适用，已隔离；请使用当前内容继续编辑。"
                }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { error = "暂时无法保存草稿：${failure.message ?: "存储不可用"}" }
        finally { ready = true }
    }

    fun update(values: Map<String, String>?) {
        if (!ready || committed) return
        dirty = values != null
        if (hasValues && lastValues == values) return
        hasValues = true
        lastValues = values?.toMap()
        session?.update(lastValues)
        pending?.cancel()
        pending = draftWorker.launch {
            delay(250)
            persistReporting()
        }
    }
    private suspend fun persistReporting() {
        try {
            session?.persist()
            withContext(Dispatchers.Main) { if (session != null) error = "" }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { withContext(Dispatchers.Main) { error = "草稿尚未保存：${failure.message ?: "请重试"}" } }
    }
    fun flushInBackground() { pending?.cancel(); draftWorker.launch { persistReporting() } }
    suspend fun keep() {
        pending?.cancel()
        val active = session ?: throw IllegalStateException("无法保存草稿，请继续编辑或保存正式记录")
        // This explicit exit choice also resumes a draft after a failed main save.
        withContext(Dispatchers.IO) { active.abortCommit() }
    }
    suspend fun discard() {
        pending?.cancel()
        withContext(Dispatchers.IO) { session?.discard() }
        committed = true
    }
    suspend fun prepareSave() {
        pending?.cancel()
        val active = session ?: throw IllegalStateException("无法保护当前草稿，请关闭后重新打开再保存")
        withContext(Dispatchers.IO) { active.prepareCommit() }
    }
    suspend fun failedSave(): Exception? = withContext(Dispatchers.IO) {
        try { session?.abortCommit(); null }
        catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { failure }
    }
    suspend fun saved(): Exception? {
        committed = true
        pending?.cancel()
        return withContext(Dispatchers.IO) {
            try { session?.finishCommitted(); null }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { failure }
        }
    }
}

@Composable internal fun rememberDraftEditor(
    key: String,
    baseline: String,
    values: () -> Map<String, String>?,
    restore: (Map<String, String>) -> Unit,
): DraftEditorState {
    val context = LocalContext.current
    val state = remember(key, baseline) { DraftEditorState(DraftStore(context)) }
    val latestValues by rememberUpdatedState(values)
    val latestRestore by rememberUpdatedState(restore)
    LaunchedEffect(state) { state.open(key, baseline, latestRestore) }
    SideEffect { state.update(latestValues()) }
    DisposableEffect(state, context) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { state.update(latestValues()); state.flushInBackground() }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            state.update(latestValues())
            state.flushInBackground()
            lifecycle?.removeObserver(observer)
        }
    }
    return state
}

@Composable internal fun DraftNotice(text: String, isolatedText: String = "") {
    if (text.isBlank()) return
    var showOld by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Surface(color = SoftSurface, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = Muted)
            if (isolatedText.isNotBlank()) TextButton({ showOld = true }) { Text("查看旧草稿") }
        }
    }
    if (showOld) PlanAlertDialog(
        onDismissRequest = { showOld = false }, title = { Text("旧草稿内容") },
        text = { SelectionContainer { Text(isolatedText, Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) } },
        confirmButton = { TextButton({ clipboard.setText(AnnotatedString(isolatedText)) }) { Text("复制内容") } },
        dismissButton = { TextButton({ showOld = false }) { Text("关闭") } },
    )
}

private fun draftReadableText(values: Map<String, String>): String {
    val labels = linkedMapOf("title" to "任务名称", "category" to "分类", "unit" to "单位", "target" to "每次目标",
        "total" to "总目标", "days" to "执行星期", "start" to "开始日期", "due" to "截止日期", "reminderTime" to "提醒时间",
        "mode" to "打卡模式", "addAmount" to "本次新增", "totalAmount" to "当天总量", "checked" to "完成状态", "note" to "今日心得")
    return labels.mapNotNull { (key, label) -> values[key]?.takeIf(String::isNotBlank)?.let { "$label：$it" } }.joinToString("\n")
        .ifBlank { "这份草稿没有可显示的文字。" }
}

@Composable internal fun DraftExitDialog(busy: Boolean, onKeep: () -> Unit, onDiscard: () -> Unit, onContinue: () -> Unit) {
    PlanAlertDialog(
        onDismissRequest = { if (!busy) onContinue() },
        title = { Text("保留未保存的修改？") },
        text = { Text("保留草稿后，下次打开这里可以继续。草稿不计入计划或打卡记录。") },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onKeep, enabled = !busy) { Text("保留草稿并退出") }
                TextButton(onContinue, enabled = !busy) { Text("继续编辑") }
            }
        },
        dismissButton = { TextButton(onDiscard, enabled = !busy) { Text("放弃修改") } },
    )
}

internal fun savedDraftCleanupNotice(context: android.content.Context, failure: Exception?) {
    if (failure != null) Toast.makeText(context, "已保存；旧草稿未能清理，重新打开时会核对并隔离。", Toast.LENGTH_LONG).show()
}

internal data class PlanDraftFields(
    val title: String, val category: Category, val measured: Boolean, val unit: String, val target: String,
    val days: Set<Int>, val start: LocalDate, val due: LocalDate?, val totalEnabled: Boolean, val total: String,
    val reminderEnabled: Boolean, val reminderTime: String,
    val customCategoryId: String? = null, val iconId: String? = null,
) {
    fun values(): Map<String, String> = linkedMapOf(
        "title" to title, "category" to category.name, "measured" to measured.toString(), "unit" to unit,
        "target" to target, "days" to days.sorted().joinToString(","), "start" to start.toString(), "due" to (due?.toString() ?: ""),
        "totalEnabled" to totalEnabled.toString(), "total" to total,
        "reminderEnabled" to reminderEnabled.toString(), "reminderTime" to reminderTime,
        "customCategoryId" to customCategoryId.orEmpty(), "iconId" to iconId.orEmpty(),
    )
    companion object {
        fun read(values: Map<String, String>): PlanDraftFields {
            fun text(key: String, maximum: Int): String = requireNotNull(values[key]).also { require(it.length <= maximum) }
            fun bool(key: String) = requireNotNull(values[key]).toBooleanStrict()
            val days = text("days", 13).let { if (it.isEmpty()) emptySet() else it.split(',').map(String::toInt).toSet() }
            require(days.all { it in 1..7 })
            val time = text("reminderTime", 5).also { require(LocalTime.parse(it).toString() == it) }
            return PlanDraftFields(text("title", 80), Category.valueOf(text("category", 20)), bool("measured"), text("unit", 12),
                text("target", 12), days, DomainValidation.date(text("start", 10)), text("due", 10).takeIf(String::isNotEmpty)?.let(DomainValidation::date),
                bool("totalEnabled"), text("total", 12), bool("reminderEnabled"), time,
                values["customCategoryId"]?.takeIf(String::isNotEmpty)?.also(DomainValidation::id),
                values["iconId"]?.takeIf(String::isNotEmpty)?.also { PlanIcon.valueOf(it) })
        }
    }
}
