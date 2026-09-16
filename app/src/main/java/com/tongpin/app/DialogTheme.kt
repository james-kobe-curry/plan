package com.tongpin.app

import android.os.Build
import android.view.View
import android.view.Window
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat

val LocalPlanSnackbarHost = staticCompositionLocalOf<SnackbarHostState?> { null }

/** A dialog owns a separate window, so Activity system-bar styling does not reach it. */
@Composable
fun PlanDialogSystemBars(background: Color = MaterialTheme.colorScheme.background) {
    val view = LocalView.current
    val lightBackground = background.luminance() > 0.5f
    val color = background.toArgb()
    fun apply() { view.findDialogWindow()?.applyPlanSystemBars(lightBackground, color) }

    SideEffect { apply() }
    DisposableEffect(view, lightBackground, color) {
        // Also handle the first composition happening before the dialog is attached.
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = apply()
            override fun onViewDetachedFromWindow(view: View) = Unit
        }
        view.addOnAttachStateChangeListener(listener)
        onDispose { view.removeOnAttachStateChangeListener(listener) }
    }
}

private fun View.findDialogWindow(): Window? {
    var node: View? = this
    while (node != null) {
        if (node is DialogWindowProvider) return node.window
        node = node.parent as? View
    }
    // Never fall back to the Activity window: nested dialogs must stay independent.
    return null
}

@Suppress("DEPRECATION")
private fun Window.applyPlanSystemBars(lightBackground: Boolean, background: Int) {
    WindowInsetsControllerCompat(this, decorView).apply {
        isAppearanceLightStatusBars = lightBackground
        isAppearanceLightNavigationBars = lightBackground
    }
    // Older Android versions draw these colors; enforced edge-to-edge versions use
    // the dialog content underneath, which already paints the theme background.
    statusBarColor = background
    navigationBarColor = background
    if (Build.VERSION.SDK_INT >= 28) navigationBarDividerColor = background
    if (Build.VERSION.SDK_INT >= 29) {
        isStatusBarContrastEnforced = false
        isNavigationBarContrastEnforced = false
    }
}

@Composable
fun PlanDialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(),
    showMessages: Boolean = false,
    content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        PlanDialogSystemBars()
        Box(propagateMinConstraints = true) {
            content()
            // Detail/history dialogs own a window above the Activity snackbar.
            LocalPlanSnackbarHost.current?.takeIf { showMessages }?.let { host ->
                SnackbarHost(host, Modifier.align(Alignment.BottomCenter)
                    .imePadding().navigationBarsPadding().padding(12.dp))
            }
        }
    }
}

@Composable
fun PlanAlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null) {
    AlertDialog(onDismissRequest = onDismissRequest, confirmButton = {
        // This slot runs inside Material's own Dialog, where LocalView is its root.
        PlanDialogSystemBars()
        confirmButton()
    }, dismissButton = dismissButton, title = title, text = text)
}
