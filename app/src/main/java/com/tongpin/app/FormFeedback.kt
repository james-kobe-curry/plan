package com.tongpin.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Persistent validation feedback. Keep outside scrolling form content, beside the save action. */
@Composable fun FormErrorBanner(text: String, modifier: Modifier = Modifier, maxHeight: Dp = 160.dp) {
    if (text.isBlank()) return
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(max = maxHeight).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = PlanSmallShape,
    ) {
        Row(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.ErrorOutline, null, modifier = Modifier.size(20.dp))
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A fixed header and action area keep validation visible even with a keyboard or a long form. */
@Composable fun FormDialog(
    title: String,
    subtitle: String? = null,
    error: String = "",
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    busy: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    PlanDialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        LaunchedEffect(busy) { if (busy) { keyboard?.hide(); focusManager.clearFocus(force = true) } }
        LaunchedEffect(error) {
            if (error.isNotBlank()) {
                keyboard?.hide()
                focusManager.clearFocus(force = true)
            }
        }
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val availableHeight = maxHeight
            val compact = availableHeight < 420.dp
            val sidePadding = if (maxWidth < 340.dp) 16.dp else 20.dp
            val stackActions = LocalDensity.current.fontScale >= 1.6f || maxWidth < 300.dp
            Surface(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = availableHeight),
                shape = PlanCardShape, border = PlanCardBorder, color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp, shadowElevation = 4.dp,
            ) {
                Column(Modifier.heightIn(max = availableHeight)) {
                    Row(Modifier.fillMaxWidth().padding(start = sidePadding, top = if (compact) 4.dp else 8.dp, end = 8.dp, bottom = if (compact) 4.dp else 10.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f).padding(top = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                            if (!subtitle.isNullOrBlank() && !compact) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Outlined.Close, "关闭") }
                    }
                    Box(Modifier.weight(1f, fill = false)) {
                        Column(
                            modifier = Modifier.then(if (busy) Modifier.clearAndSetSemantics {} else Modifier)
                                .verticalScroll(rememberScrollState()).padding(horizontal = sidePadding, vertical = if (compact) 12.dp else 16.dp),
                            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
                            content = content,
                        )
                        if (busy) Box(Modifier.matchParentSize().pointerInput(Unit) {
                            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                        })
                    }
                    HorizontalDivider(color = Line)
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 12.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                    ) {
                        FormErrorBanner(error, maxHeight = (availableHeight * 0.28f).coerceIn(48.dp, 160.dp))
                        val confirmAction = { keyboard?.hide(); focusManager.clearFocus(force = true); onConfirm() }
                        if (stackActions) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                PlanPrimaryButton(onClick = confirmAction, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(confirmLabel) }
                                PlanQuietButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PlanQuietButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(.32f)) { Text("取消") }
                                PlanPrimaryButton(onClick = confirmAction, enabled = !busy, modifier = Modifier.weight(.68f)) { Text(confirmLabel) }
                            }
                        }
                    }
                }
            }
        }
    }
}
