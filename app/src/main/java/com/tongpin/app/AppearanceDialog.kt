package com.tongpin.app

import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Each choice is saved immediately. Closing this sheet never undoes a choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { AppearanceStore(context) }
    var selectedMode by remember(store) { mutableStateOf(store.mode()) }
    var selectedColor by remember(store) { mutableStateOf(store.color()) }
    var selectedStyle by remember(store) { mutableStateOf(store.style()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    DisposableEffect(store) {
        fun refresh() {
            selectedMode = store.mode()
            selectedColor = store.color()
            selectedStyle = store.style()
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "theme_mode" || key == "theme_color" || key == "theme_style" || key == null) refresh()
        }
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        refresh()
        onDispose { store.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val systemDark = isSystemInDarkTheme()
    val dark = selectedMode == ThemeMode.DARK || selectedMode == ThemeMode.SYSTEM && systemDark

    fun save(change: () -> Unit) {
        // A tiny preference commit finishes in the click handler, before another tap or recreation.
        if (saving) return
        saving = true
        error = ""
        try {
            change()
            selectedMode = store.mode()
            selectedColor = store.color()
            selectedStyle = store.style()
        } catch (e: Exception) {
            error = e.message ?: "外观设置未能保存，请重试"
        } finally {
            saving = false
        }
    }

    PlanDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = !saving,
            dismissOnClickOutside = !saving,
        ),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val availableHeight = maxHeight
            val compact = availableHeight < 440.dp
            val scheme = MaterialTheme.colorScheme
            Surface(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = availableHeight),
                shape = PlanCardShape, color = scheme.surface,
                contentColor = scheme.onSurface, shadowElevation = 6.dp,
            ) {
                Column(Modifier.heightIn(max = availableHeight)) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 22.dp, top = if (compact) 4.dp else 12.dp, end = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("外观", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss, enabled = !saving) { Icon(Icons.Outlined.Close, "关闭外观") }
                    }
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("选择喜欢的风格与主题色，点选后立即生效。", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("界面风格", style = MaterialTheme.typography.titleSmall)
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val columns = if (LocalDensity.current.fontScale >= 1.3f || maxWidth < 290.dp) 1 else 3
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    ThemeStyle.entries.chunked(columns).forEach { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            row.forEach { style ->
                                                StyleChoice(style, selectedColor, dark, selectedStyle == style, !saving, Modifier.weight(1f)) {
                                                    if (selectedStyle != style) save { store.setStyle(style) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("主题色", style = MaterialTheme.typography.titleSmall)
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val columns = if (LocalDensity.current.fontScale >= 1.5f || maxWidth < 290.dp) 1 else 2
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    ThemeColor.entries.chunked(columns).forEach { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            row.forEach { color ->
                                                ThemeChoice(
                                                    color = color, dark = dark, style = selectedStyle, selected = selectedColor == color,
                                                    enabled = !saving, modifier = Modifier.weight(1f),
                                                    onClick = { if (selectedColor != color) save { store.setColor(color) } },
                                                )
                                            }
                                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("明暗模式", style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                ThemeMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = selectedMode == mode,
                                        onClick = { if (selectedMode != mode) save { store.setMode(mode) } },
                                        enabled = !saving,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        shape = PlanButtonShape,
                                        border = null,
                                        colors = FilterChipDefaults.filterChipColors(
                                            containerColor = scheme.surfaceContainerHighest,
                                            selectedContainerColor = scheme.primary,
                                            selectedLabelColor = scheme.onPrimary,
                                            selectedLeadingIconColor = scheme.onPrimary,
                                        ),
                                        label = { Text(mode.label) },
                                        leadingIcon = if (selectedMode == mode) {
                                            { Icon(Icons.Outlined.Check, null, Modifier.size(17.dp)) }
                                        } else null,
                                    )
                                }
                            }
                            Text("字体大小跟随手机的显示设置。", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = scheme.outlineVariant)
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FormErrorBanner(error, maxHeight = (availableHeight * 0.24f).coerceIn(48.dp, 140.dp))
                        if (LocalDensity.current.fontScale >= 1.5f) {
                            PlanPrimaryButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("完成") }
                            PlanQuietButton(
                                onClick = { save { store.resetColor() } }, enabled = !saving && selectedColor != ThemeColor.GREEN,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text("恢复默认配色") }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                PlanQuietButton(
                                    onClick = { save { store.resetColor() } }, enabled = !saving && selectedColor != ThemeColor.GREEN,
                                    modifier = Modifier.weight(1f),
                                ) { Text("恢复默认配色") }
                                PlanPrimaryButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.weight(1f)) { Text("完成") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeChoice(color: ThemeColor, dark: Boolean, style: ThemeStyle, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val palette = planPalette(color, dark, style)
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier.clip(PlanSmallShape).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = PlanSmallShape,
        color = if (selected) scheme.primaryContainer else scheme.surface,
        contentColor = scheme.onSurface,
        border = BorderStroke(1.dp, if (selected) scheme.primary else scheme.outlineVariant),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(palette.accent, CircleShape), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = palette.onAccent)
                else Box(Modifier.size(11.dp).background(palette.paper, CircleShape))
            }
            Text(color.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
private fun StyleChoice(style: ThemeStyle, color: ThemeColor, dark: Boolean, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val palette = planPalette(color, dark, style)
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(style.cardRadius.dp)
    Surface(
        modifier.clip(shape).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = shape, color = palette.paper, contentColor = palette.ink,
        border = BorderStroke(1.dp, if (selected) scheme.primary else palette.line),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StyleMiniPreview(style, palette)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(style.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(17.dp), tint = palette.accent)
            }
            Text(style.description.replace(" · ", "\n"), style = MaterialTheme.typography.bodySmall, color = palette.muted)
        }
    }
}

@Composable
private fun StyleMiniPreview(style: ThemeStyle, palette: PlanPalette) {
    Column(Modifier.fillMaxWidth().clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("plan", color = palette.ink, style = MaterialTheme.typography.titleMedium,
            fontFamily = if (style == ThemeStyle.PAPER) FontFamily.Serif else FontFamily.SansSerif)
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val radius = CornerRadius((style.cardRadius * .4f).dp.toPx())
            val topHeight = 31.dp.toPx()
            drawRoundRect(palette.hero, size = Size(size.width, topHeight), cornerRadius = radius)
            if (style == ThemeStyle.PAPER) {
                for (row in 1..2) drawLine(palette.line, Offset(0f, row * 10.dp.toPx()), Offset(size.width, row * 10.dp.toPx()), .5.dp.toPx())
            }
            drawRoundRect(palette.accent, Offset(8.dp.toPx(), 11.dp.toPx()), Size(size.width * .34f, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
            drawRoundRect(palette.muted, Offset(8.dp.toPx(), 21.dp.toPx()), Size(size.width * .53f, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
            drawRoundRect(palette.surface, Offset(0f, 38.dp.toPx()), Size(size.width, 25.dp.toPx()), radius)
            if (style == ThemeStyle.PAPER) {
                drawLine(palette.line, Offset(0f, 62.dp.toPx()), Offset(size.width, 62.dp.toPx()), 1.dp.toPx())
            }
            drawRoundRect(palette.accent, Offset(8.dp.toPx(), 45.dp.toPx()), Size(10.dp.toPx(), 10.dp.toPx()), CornerRadius((style.smallRadius * .35f).dp.toPx()))
            drawRoundRect(palette.muted, Offset(23.dp.toPx(), 49.dp.toPx()), Size((size.width - 29.dp.toPx()).coerceAtLeast(1f), 2.dp.toPx()), CornerRadius(1.dp.toPx()))
        }
    }
}
