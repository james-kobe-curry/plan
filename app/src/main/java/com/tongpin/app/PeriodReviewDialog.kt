package com.tongpin.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun PeriodReviewDialog(data: AppData, today: LocalDate = LocalDate.now(), onDismiss: () -> Unit,
    onHistory: (() -> Unit)? = null) {
    var kindName by rememberSaveable { mutableStateOf(ReviewPeriodKind.WEEK.name) }
    var anchorText by rememberSaveable { mutableStateOf(today.toString()) }
    val kind = ReviewPeriodKind.valueOf(kindName)
    val period = reviewPeriod(kind, LocalDate.parse(anchorText), today)
    var revision by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf("") }
    var index by remember(data) { mutableStateOf<PeriodReviewIndex?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(data, revision) {
        failure = ""
        try { index = withContext(Dispatchers.Default) { PeriodReviewIndex(data) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = "暂时无法整理复盘，请重试。原有记录未被修改。" }
    }
    val result by produceState<PeriodReview?>(null, index, period) {
        value = null
        val prepared = index
        if (prepared != null) {
            try { value = withContext(Dispatchers.Default) { prepared.summarize(period) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure = "暂时无法整理这个阶段的记录，请重试。" }
        }
    }
    LaunchedEffect(period) { listState.scrollToItem(0) }
    val review = result?.takeIf { it.period == period }
    val dailyNotes=remember(data.dailyNotes,period){data.dailyNotes.filter{it.date>=period.start.toString()&&it.date<=period.through.toString()}.sortedByDescending{it.date}}
    val previous = period.previous(today)
    val next = period.next(today)
    PlanDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回记录") }
                    Text("阶段复盘", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton({ anchorText = today.toString() }) { Text(if (kind == ReviewPeriodKind.WEEK) "本周" else "本月") }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(selected = kind == ReviewPeriodKind.WEEK, onClick = { kindName = ReviewPeriodKind.WEEK.name },
                            label = { Text("周复盘") }, modifier = Modifier.weight(1f))
                        FilterChip(selected = kind == ReviewPeriodKind.MONTH, onClick = { kindName = ReviewPeriodKind.MONTH.name },
                            label = { Text("月复盘") }, modifier = Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ previous?.let { anchorText = it.start.toString() } }, enabled = previous != null) {
                            Icon(Icons.Outlined.ChevronLeft, if (kind == ReviewPeriodKind.WEEK) "上一周" else "上一个月")
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (kind == ReviewPeriodKind.MONTH) "${period.start.year} 年 ${period.start.monthValue} 月"
                                else "${period.start.year} 年 · 周回顾", style = MaterialTheme.typography.titleMedium)
                            val rangeFormat = if (period.start.year == period.end.year) reviewDateFormat else reviewFullDateFormat
                            Text("${period.start.format(rangeFormat)} — ${period.end.format(rangeFormat)}",
                                style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        IconButton({ next?.let { anchorText = it.start.toString() } }, enabled = next != null) {
                            Icon(Icons.Outlined.ChevronRight, if (kind == ReviewPeriodKind.WEEK) "下一周" else "下一个月")
                        }
                    }
                }
                if (failure.isNotBlank()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FormErrorBanner(failure)
                        OutlinedButton({ revision++ }) { Text("重新整理") }
                    }
                } else if (review == null) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = Teal)
                            Text("正在整理这一阶段的积累…", color = Muted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState,
                        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item("overview") { ReviewOverview(review) }
                        item("status") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    ReviewMetric("已完成", "${review.counts.completed}", "次安排", Modifier.weight(1f))
                                    ReviewMetric("未完成", "${review.counts.unfinished}", "次安排", Modifier.weight(1f))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    ReviewMetric("已跳过", "${review.counts.skipped}", "次安排", Modifier.weight(1f))
                                    ReviewMetric("暂停期间", "${review.counts.paused}", "次安排", Modifier.weight(1f))
                                }
                            }
                        }
                        item("counting") {
                            Text("每项任务每天计 1 次安排；跳过和暂停不计入完成率。未完成包含尚未达到目标的进度。" +
                                if (period.through == today) "今天的待办也暂列未完成。" else "",
                                color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        item("focus") { ReviewFocusCard(review) }
                        if (review.isEmpty && dailyNotes.isEmpty()) item("empty") {
                            ReviewPanel {
                                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(26.dp), tint = Teal)
                                Text("这个阶段还没有安排与记录", style = MaterialTheme.typography.titleMedium)
                                Text("可以切换其他日期，回顾已经留下的积累。", color = Muted,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        item("category_heading") { Text("分类回顾", style = MaterialTheme.typography.titleLarge) }
                        items(review.categories, key = { "category:${it.key}" }) { category -> ReviewCategoryCard(category) }
                        if (review.additionalHistoryRecords > 0) item("imported_history") {
                            ReviewPanel {
                                Text("历史记录也保留在这里", style = MaterialTheme.typography.titleMedium)
                                Text("另有 ${review.additionalHistoryRecords} 条打卡在当期没有对应的有效安排，包含单独导入的历史记录。它们计入打卡天数与心得，未用于计算完成率。",
                                    color = Muted, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        item("notes_heading") {
                            Text("任务心得 · ${review.notes.size}", style = MaterialTheme.typography.titleLarge)
                        }
                        if (review.notes.isEmpty()) item("no_notes") {
                            ReviewPanel { Text("这个阶段还没有填写心得。", color = Muted, style = MaterialTheme.typography.bodyMedium) }
                        }
                        items(review.notes, key = { "note:${it.entry.planId}:${it.entry.date}" }) { note -> ReviewNoteCard(note) }
                        item("daily_notes_heading") { Text("每日心得 · ${dailyNotes.size}",style=MaterialTheme.typography.titleLarge) }
                        items(dailyNotes,key={"daily:${it.date}"}){note->
                            ReviewPanel{
                                Text(note.date,color=Teal,style=MaterialTheme.typography.titleSmall)
                                Text(note.text,style=MaterialTheme.typography.bodyMedium,color=Ink)
                            }
                        }
                        if(dailyNotes.isEmpty())item("daily_notes_empty"){Text("每日心得可选填，在首页点「写心得」即可记录。",color=Muted,style=MaterialTheme.typography.bodySmall)}
                        if (onHistory != null) item("history") {
                            OutlinedButton(onHistory, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = PlanSmallShape) {
                                Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp)); Text("查看历史明细")
                            }
                        }
                    }
                }
            }
        }
    }
}

private val reviewDateFormat = DateTimeFormatter.ofPattern("M 月 d 日")
private val reviewFullDateFormat = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日")

@Composable private fun ReviewOverview(review: PeriodReview) {
    Surface(color = Hero, contentColor = OnHero, shape = PlanCardShape) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (review.period.isPartial) "统计至 ${review.period.through.format(reviewDateFormat)}" else "这一阶段的完成情况",
                style = MaterialTheme.typography.bodyMedium, color = OnHero)
            Text(review.counts.completionPercent?.let { "$it%" } ?: "—", style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold)
            Text(if (review.counts.expected == 0) "暂无需要完成的安排" else
                "已完成 ${review.counts.completed} / ${review.counts.expected} 次安排", style = MaterialTheme.typography.titleMedium)
            if (review.counts.expected > 0) LinearProgressIndicator(
                progress = { review.counts.completed.toFloat() / review.counts.expected },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(PlanSmallShape),
                color = Gold, trackColor = OnHero.copy(alpha = .15f), drawStopIndicator = {})
            if (review.counts.partial > 0) Text("未完成中有 ${review.counts.partial} 次已记录部分进度",
                color = OnHero, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun ReviewMetric(label: String, value: String, unit: String, modifier: Modifier) {
    Surface(modifier, color = SurfaceColor, shape = PlanCardShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = Muted, style = MaterialTheme.typography.bodyMedium)
            Text(value, color = Ink, style = if (value.length > 4) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.headlineMedium)
            Text(unit, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun ReviewFocusCard(review: PeriodReview) {
    ReviewPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Timer, null, tint = Teal, modifier = Modifier.size(22.dp))
            Text("专注与记录", style = MaterialTheme.typography.titleMedium)
        }
        Text(historyFocusDuration(review.focusSeconds), style = MaterialTheme.typography.headlineMedium, color = Teal)
        Text("${review.focusSessions} 次完整专注 · ${review.checkInDays} 天留下打卡 · ${review.checkInRecords} 条打卡",
            style = MaterialTheme.typography.bodyMedium, color = Muted)
        if (review.freeFocusSessions > 0) Text("其中自由专注 ${review.freeFocusSessions} 次 · ${historyFocusDuration(review.freeFocusSeconds)}",
            style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable private fun ReviewCategoryCard(review: CategoryReview) {
    ReviewPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(38.dp).background(review.customCategory?.let { categoryTint(it.colorKey) } ?: categoryColor(review.category), PlanSmallShape), contentAlignment = Alignment.Center) {
                Icon(review.customCategory?.let { planIcon(it.iconId) } ?: categoryIcon(review.category), null, Modifier.size(20.dp), tint = Ink)
            }
            Text(review.label, style = MaterialTheme.typography.titleMedium)
        }
        val count = review.counts
        Text(if (count.expected == 0) "暂无需要完成的安排" else "完成 ${count.completed} / ${count.expected} 次 · 未完成 ${count.unfinished} 次",
            style = MaterialTheme.typography.bodyMedium)
        Text("跳过 ${count.skipped} 次 · 暂停 ${count.paused} 次 · 打卡 ${review.records} 条",
            style = MaterialTheme.typography.bodySmall, color = Muted)
        Text("专注 ${review.focusSessions} 次 · ${historyFocusDuration(review.focusSeconds)}",
            style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable private fun ReviewNoteCard(note: ReviewNote) {
    var expanded by rememberSaveable(note.entry.planId, note.entry.date) { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    ReviewPanel {
        Text(note.plan?.title ?: "历史任务", style = MaterialTheme.typography.titleMedium)
        Text(note.entry.date + " · " + (note.categoryLabel ?: note.plan?.let { categoryName(it.category) } ?: "历史记录"),
            style = MaterialTheme.typography.bodySmall, color = Muted)
        Text(note.entry.note, style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow })
        if (overflow || expanded) TextButton({ expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text(if (expanded) "收起心得" else "展开全文")
        }
    }
}

@Composable private fun ReviewPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = SurfaceColor, shape = PlanCardShape) {
        Column(Modifier.fillMaxWidth().padding(PlanCardPadding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
