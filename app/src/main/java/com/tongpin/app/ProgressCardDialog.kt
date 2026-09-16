package com.tongpin.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

private data class CardPreview(val request: Any, val model: ProgressCardModel, val bitmap: Bitmap)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgressCardDialog(data: AppData, date: LocalDate, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Reading configuration also refreshes following-system appearance after a mode change.
    LocalConfiguration.current
    val palette = ShareHelper.cardPalette(context)
    var weekly by rememberSaveable { mutableStateOf(false) }
    var styleName by rememberSaveable { mutableStateOf(ProgressCardStyle.DETAILED.name) }
    var showTitles by rememberSaveable { mutableStateOf(true) }
    var showNotes by rememberSaveable { mutableStateOf(false) }
    var showExplanation by rememberSaveable { mutableStateOf(false) }
    var fullPreview by rememberSaveable { mutableStateOf(false) }
    val style = ProgressCardStyle.valueOf(styleName)
    val options = ProgressCardOptions(weekly, style, showTitles, showNotes)
    var retry by remember { mutableIntStateOf(0) }
    val request = remember(data, date, options, palette, retry) { Any() }
    val latestRequest by rememberUpdatedState(request)
    var preview by remember { mutableStateOf<CardPreview?>(null) }
    var generating by remember { mutableStateOf(true) }
    var sharing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val current = preview?.takeIf { it.request === request }

    LaunchedEffect(request) {
        generating = true
        error = ""
        // Drop displayed pixel references. Android owns those pixels while a draw is in flight;
        // recycling displayed bitmaps manually would race the renderer. Only one half-size preview is held.
        preview = null
        var unclaimed: Bitmap? = null
        try {
            val model = withContext(Dispatchers.Default) {
                val cancellation = currentCoroutineContext()
                cancellation.ensureActive()
                val value = ProgressCardRules.make(data, date, options)
                unclaimed = ProgressCardRenderer.render(value, palette, preview = true) { cancellation.ensureActive() }
                value
            }
            currentCoroutineContext().ensureActive()
            preview = CardPreview(request, model, requireNotNull(unclaimed))
            unclaimed = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (request === latestRequest) error = failure.message ?: "进度卡预览未能生成，请重试"
        } catch (_: OutOfMemoryError) {
            if (request === latestRequest) error = "内存暂时不足，请关闭后重新制作进度卡"
        } finally {
            // A cancelled worker may have completed bitmap creation before returning to the UI.
            unclaimed?.recycle()
            if (request === latestRequest) generating = false
        }
    }

    fun share() {
        val ready = current ?: return
        if (sharing || generating) return
        sharing = true
        error = ""
        scope.launch {
            var unclaimed: File? = null
            try {
                withContext(Dispatchers.IO) {
                    val cancellation = currentCoroutineContext()
                    unclaimed = ShareHelper.prepareProgressCard(context, ready.model, palette) { cancellation.ensureActive() }
                }
                currentCoroutineContext().ensureActive()
                ShareHelper.sendProgressCard(context, requireNotNull(unclaimed), ready.model)
                unclaimed = null // The destination app needs this cache file after the dialog closes.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "进度卡暂时无法分享，请重试"
            } catch (_: OutOfMemoryError) {
                error = "内存暂时不足，请关闭后重新制作进度卡"
            } finally {
                unclaimed?.delete()
                sharing = false
            }
        }
    }

    PlanDialog(
        onDismissRequest = { if (!sharing) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            dismissOnBackPress = !sharing, dismissOnClickOutside = !sharing),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp), contentAlignment = Alignment.BottomCenter) {
            val availableHeight = maxHeight
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = availableHeight), shape = PlanCardShape, border = PlanCardBorder) {
                Column(Modifier.heightIn(max = availableHeight)) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("分享进度卡", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss, enabled = !sharing) { Icon(Icons.Outlined.Close, "关闭进度卡") }
                    }
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val stacked = maxWidth < 330.dp || LocalDensity.current.fontScale >= 1.3f
                            if (stacked) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CardSegments(listOf("每日", "最近 7 天"), if (weekly) 1 else 0, !sharing) { weekly = it == 1 }
                                    CardSegments(ProgressCardStyle.entries.map { it.label }, style.ordinal, !sharing) { styleName = ProgressCardStyle.entries[it].name }
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CardSegments(listOf("每日", "近 7 天"), if (weekly) 1 else 0, !sharing, Modifier.weight(1.15f)) { weekly = it == 1 }
                                    CardSegments(ProgressCardStyle.entries.map { it.label }, style.ordinal, !sharing, Modifier.weight(1f)) { styleName = ProgressCardStyle.entries[it].name }
                                }
                            }
                        }
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = PlanSmallShape) {
                            val previewHeight = (availableHeight * .40f).coerceIn(180.dp, 300.dp)
                            if (current != null) {
                                val bitmap = remember(current) { current.bitmap.asImageBitmap() }
                                Image(
                                    bitmap, "${current.model.title}预览，点按放大",
                                    Modifier.fillMaxWidth().height(previewHeight)
                                        .clickable(enabled = !sharing, onClickLabel = "放大预览") { fullPreview = true }.padding(10.dp),
                                    contentScale = ContentScale.Fit,
                                )
                            } else {
                                Box(Modifier.fillMaxWidth().height(previewHeight), contentAlignment = Alignment.Center) {
                                    if (generating) CircularProgressIndicator(Modifier.size(28.dp))
                                    else PlanSecondaryButton(onClick = { retry++ }, enabled = !sharing) {
                                        Icon(Icons.Outlined.Refresh, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("重新生成预览")
                                    }
                                }
                            }
                        }
                        if (style == ProgressCardStyle.DETAILED) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                CardToggle("显示任务名称", showTitles, !sharing) { showTitles = it }
                                CardToggle("显示每日与任务心得", showNotes, !sharing, "默认关闭，仅用于这次分享") { showNotes = it }
                            }
                        }
                        Column {
                            Row(
                                Modifier.fillMaxWidth().clip(PlanSmallShape)
                                    .clickable(role = Role.Button, onClickLabel = if (showExplanation) "收起内容说明" else "展开内容说明") { showExplanation = !showExplanation }
                                    .heightIn(min = 48.dp).padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("内容说明", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Icon(if (showExplanation) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (showExplanation) Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("点按预览可查看大图。风格与配色跟随当前外观。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (style == ProgressCardStyle.DETAILED) {
                                    Text("心得默认隐藏，打开后才会加入这次分享。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("关闭任务名称后用序号代替，心得中与任务名称相同的文字也会隐藏。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(ProgressCardRules.DISPLAY_RULE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Text("仅展示总览、完成进度与专注时长。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = Line)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FormErrorBanner(error, maxHeight = (availableHeight * .22f).coerceIn(48.dp, 140.dp))
                        PlanPrimaryButton(onClick = ::share, enabled = current != null && !generating && !sharing, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            if (sharing || generating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.IosShare, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (sharing || generating) "生成中…" else "分享进度卡")
                        }
                    }
                }
            }
        }
    }
    if (fullPreview && current != null) {
        PlanDialog(onDismissRequest = { fullPreview = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("进度卡预览", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = { fullPreview = false }) { Icon(Icons.Outlined.Close, "关闭大图") }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Image(
                            remember(current) { current.bitmap.asImageBitmap() }, "${current.model.title}完整预览",
                            Modifier.fillMaxWidth().aspectRatio(current.bitmap.width.toFloat() / current.bitmap.height),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CardSegments(labels: List<String>, selected: Int, enabled: Boolean, modifier: Modifier = Modifier, select: (Int) -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(PlanSmallShape).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(3.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                Modifier.weight(1f).clip(PlanSmallShape)
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                    .selectable(active, enabled = enabled, role = Role.RadioButton, onClick = { select(index) })
                    .heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CardToggle(label: String, value: Boolean, enabled: Boolean, detail: String? = null, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = value, enabled = enabled, role = Role.Switch, onValueChange = change).heightIn(min = 48.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = null, enabled = enabled)
    }
}
