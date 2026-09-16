package com.tongpin.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable fun RecordsScreen(data:AppData,today:LocalDate,onShare:()->Unit,onName:()->Unit,onData:()->Unit,onAbout:()->Unit,
    onHistory:()->Unit,onCheck:(Plan,LocalDate)->Unit,onReminders:()->Unit,onPlans:()->Unit,
    onReview:()->Unit={},onAppearance:()->Unit={},onPlanReminders:()->Unit={},
    onProfile:()->Unit=onName,onHomeCustomization:()->Unit={},onCategories:()->Unit={},onDailyNotes:()->Unit={}) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var overview by remember { mutableStateOf<RecordsOverview?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(data, today, retry) {
        loadFailed = false
        try {
            overview = withContext(Dispatchers.Default) { recordsOverview(data, today) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        }
    }
    // Restore the saved scroll only after the full page is measurable. A short loading
    // page would otherwise clamp that position before the overview arrives.
    if (overview == null) {
        Column(Modifier.fillMaxSize().padding(horizontal=PlanPagePadding), verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("我的积累", style=MaterialTheme.typography.headlineMedium)
            if (loadFailed) {
                Text("暂时无法整理记录，请重试。", color=Muted)
                PlanSecondaryButton({ retry++ }) { Text("重新整理") }
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在整理记录…", style=MaterialTheme.typography.bodyMedium, color=Muted)
            }
        }
        return
    }
    PageColumn {
        val largeText = LocalDensity.current.fontScale >= 1.5f
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (largeText || maxWidth < 300.dp) {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("我的积累", style=MaterialTheme.typography.headlineMedium)
                    PlanQuietButton({ settingsOpen = true }) {
                        Icon(Icons.Outlined.Settings, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("设置")
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("我的积累", Modifier.weight(1f), style=MaterialTheme.typography.headlineMedium)
                    PlanQuietButton({ settingsOpen = true }) {
                        Icon(Icons.Outlined.Settings, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("设置")
                    }
                }
            }
        }
        val summary = overview
        if (summary != null) {
            RecordsMetrics(summary)
            RecordsWeekChart(summary, today, onShare)
        } else {
            Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loadFailed) {
                    Text("暂时无法整理记录，请重试。", color = Muted)
                    PlanSecondaryButton(onClick = { retry++ }) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("重新整理")
                    }
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在整理记录…", color = Muted, fontSize = 14.sp)
                }
            }
        }
        if (summary == null) PlanSecondaryButton(onShare) {
            Icon(Icons.Outlined.IosShare, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("分享进度卡")
        }
        RecordsNavigationCard(data.dailyNotes.size, onDailyNotes, onHistory, onReview)
        if (summary != null) {
            SectionTitle("最近打卡", "全部记录", onHistory)
            if (summary.recent.isEmpty()) EmptyPanel(Icons.Outlined.TaskAlt, "从第一次完成开始", "打卡后可以在这里查看进度和心得。")
            summary.recent.forEach { recent ->
                val entry = recent.entry
                val plan = recent.plan
                Row(
                    Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor)
                        .clickable(enabled = recent.canEdit, onClickLabel = "查看打卡记录") { if (plan != null) onCheck(plan, recent.date) }.padding(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(if (plan == null) Icons.Outlined.CheckCircle else planCategoryIcon(plan, data), null, Modifier.size(20.dp), tint = Teal)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(plan?.title ?: "历史任务", fontWeight = FontWeight.SemiBold)
                        Text(entry.date + if (plan?.tracking == TrackingMode.QUANTITY) " · ${formatQuantity(entry.amount, plan)} ${plan.unit}" else " · 已完成", fontSize = 12.sp, color = Muted)
                        if (entry.note.isNotEmpty()) Text(entry.note, fontSize = 14.sp, color = Muted, modifier = Modifier.padding(top = 6.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    if (recent.canEdit) Icon(Icons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
    if (settingsOpen) SettingsDialog(data.nickname, { settingsOpen = false }, onName, onData, onPlans, onReminders, onPlanReminders, onAppearance, onAbout,
        onProfile=onProfile,onHomeCustomization=onHomeCustomization,onCategories=onCategories,profile=data.profile)
}

@Composable
private fun RecordsMetrics(summary: RecordsOverview) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (fontScale >= 1.6f && maxWidth < 440.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RecordMetric("打卡天数", "${summary.checkInDays}", "天", Modifier.fillMaxWidth())
                RecordMetric("累计专注", "${summary.focusSeconds / 60}", "分钟", Modifier.fillMaxWidth())
            }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RecordMetric("打卡天数", "${summary.checkInDays}", "天", Modifier.weight(1f))
            RecordMetric("累计专注", "${summary.focusSeconds / 60}", "分钟", Modifier.weight(1f))
        }
    }
}

@Composable
private fun RecordMetric(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier.clip(PlanCardShape).background(SurfaceColor).padding(16.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(label, color=Muted, style=MaterialTheme.typography.bodyMedium)
        Text("$value $unit", color=Ink, fontSize=22.sp, lineHeight=30.sp, fontWeight=FontWeight.SemiBold)
    }
}

@Composable
private fun RecordsNavigationCard(noteCount: Int, onDailyNotes: () -> Unit, onHistory: () -> Unit, onReview: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor)) {
        RecordsNavigationRow(Icons.Outlined.EditNote, "每日心得", if(noteCount > 0) "$noteCount 篇" else "", onDailyNotes)
        HorizontalDivider(Modifier.padding(start=50.dp,end=16.dp), color=Line.copy(alpha=.6f))
        RecordsNavigationRow(Icons.Outlined.CalendarMonth, "全部记录与月历", "", onHistory)
        HorizontalDivider(Modifier.padding(start=50.dp,end=16.dp), color=Line.copy(alpha=.6f))
        RecordsNavigationRow(Icons.Outlined.Assessment, "周复盘与月复盘", "", onReview)
    }
}

@Composable
private fun RecordsNavigationRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=52.dp).clickable(role=Role.Button,onClick=onClick).padding(horizontal=16.dp,vertical=12.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Icon(icon,null,Modifier.size(20.dp),tint=Teal)
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=Ink)
        if(value.isNotEmpty()) Text(value,color=Muted,style=MaterialTheme.typography.bodySmall)
        Icon(Icons.Outlined.ChevronRight,null,Modifier.size(18.dp),tint=Muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordsWeekChart(summary: RecordsOverview, today: LocalDate, onShare: () -> Unit) {
    val density = LocalDensity.current
    Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor).padding(PlanCardPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val heading: @Composable () -> Unit = {
                Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    Text("最近 7 天", color=Ink, style=MaterialTheme.typography.titleMedium)
                    Text("${summary.days.first().date.format(DateTimeFormatter.ofPattern("M.d"))} — ${today.format(DateTimeFormatter.ofPattern("M.d"))}", color=Muted, style=MaterialTheme.typography.bodySmall)
                }
            }
            val share: @Composable () -> Unit = {
                PlanSecondaryButton(onShare) { Icon(Icons.Outlined.IosShare,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("分享") }
            }
            if(density.fontScale >= 1.5f || maxWidth < 280.dp) {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { heading(); share() }
            } else {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { heading() }
                    share()
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val scroll = rememberScrollState()
            val cellWidth = (32 * density.fontScale).dp
            val needsScroll = cellWidth * 7 + 24.dp > maxWidth
            val measuredMax = scroll.maxValue.takeUnless { it == Int.MAX_VALUE }
            // Measurement changes on rotation or font size changes; ordinary user scrolling
            // does not restart this effect. The newest day is visible on the first layout.
            LaunchedEffect(today, maxWidth, cellWidth, needsScroll, measuredMax) {
                if (needsScroll && measuredMax != null) scroll.scrollTo(measuredMax)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().then(if (needsScroll) Modifier.horizontalScroll(scroll) else Modifier), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                    summary.days.forEach { day ->
                        val ratio = if (day.scheduled == 0) 0f else day.completed.toFloat() / day.scheduled
                        val weekday = "周" + weekNames[day.date.dayOfWeek.value - 1]
                        val description = "${day.date.monthValue}月${day.date.dayOfMonth}日，$weekday" +
                            (if (day.date == today) "，今天" else "") +
                            (if (day.scheduled == 0) "，没有安排任务" else "，已完成 ${day.completed} 项，共安排 ${day.scheduled} 项")
                        Column((if (needsScroll) Modifier.width(cellWidth) else Modifier.weight(1f)).clearAndSetSemantics { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (day.scheduled == 0) "—" else "${day.completed}/${day.scheduled}", fontSize = 11.sp, lineHeight = 18.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.width(20.dp).height(54.dp).clip(RoundedCornerShape(6.dp)).background(SoftSurface), contentAlignment = Alignment.BottomCenter) {
                                Box(Modifier.fillMaxWidth().fillMaxHeight(ratio).clip(RoundedCornerShape(6.dp)).background(Teal))
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(weekday, fontSize = 13.sp, lineHeight = 22.sp, color = Ink, fontWeight = if (day.date == today) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                            Text(if (day.date == today) "今天" else "${day.date.monthValue}/${day.date.dayOfMonth}", fontSize = 11.sp, lineHeight = 18.sp, color = if (day.date == today) Teal else Muted, maxLines = 1)
                        }
                    }
                }
                if (needsScroll && measuredMax != null && measuredMax > 0) {
                    Text("左右滑动查看 7 天", color = Muted, style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(if (summary.scheduled == 0) "这七天还没有安排任务" else "已完成 ${summary.completed} / ${summary.scheduled} 项安排", color = Muted, style=MaterialTheme.typography.bodySmall)
            if(summary.scheduled > 0) Text("${summary.completed * 100 / summary.scheduled}%", color=Teal,style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold)
        }
    }
}
