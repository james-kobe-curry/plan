package com.tongpin.app

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

enum class ThemeMode(val label: String, val description: String) {
    SYSTEM("跟随系统", "随手机的深色模式自动切换"), LIGHT("浅色", "明亮柔和的背景，适合日间使用"), DARK("深色", "柔和的深色背景，适合夜间使用");
    companion object { fun parse(value: String?) = entries.firstOrNull { it.name == value } ?: SYSTEM }
}
class AppearanceStore(context: Context) {
    internal val prefs = context.applicationContext.getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
    fun mode() = ThemeMode.parse(prefs.getString("theme_mode", null))
    fun color() = ThemeColor.parse(prefs.getString("theme_color", null))
    fun style() = ThemeStyle.parse(prefs.getString("theme_style", null))
    fun setMode(mode: ThemeMode) {
        val previous = this.mode()
        if (!prefs.edit().putString("theme_mode", mode.name).commit()) {
            prefs.edit().putString("theme_mode", previous.name).commit()
            error("外观设置未能保存，请重试")
        }
    }
    fun setColor(color: ThemeColor) {
        val previous = this.color()
        if (!prefs.edit().putString("theme_color", color.name).commit()) {
            prefs.edit().putString("theme_color", previous.name).commit()
            error("配色未能保存，请重试")
        }
    }
    fun resetColor() = setColor(ThemeColor.GREEN)
    fun setStyle(style: ThemeStyle) {
        val previous = this.style()
        if (!prefs.edit().putString("theme_style", style.name).commit()) {
            prefs.edit().putString("theme_style", previous.name).commit()
            error("风格未能保存，请重试")
        }
    }
}
/** Native date/time pickers use the same choice as Compose, including manual overrides. */
fun planDialogTheme(context: Context): Int {
    val mode = AppearanceStore(context).mode()
    val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    return if (mode == ThemeMode.DARK || mode == ThemeMode.SYSTEM && systemDark)
        android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
}
private val LocalPlanPalette = staticCompositionLocalOf { planPalette(ThemeColor.GREEN, false) }
val Ink: Color @Composable @ReadOnlyComposable get() = LocalPlanPalette.current.ink
val Teal: Color @Composable @ReadOnlyComposable get() = LocalPlanPalette.current.accent
val Paper: Color @Composable @ReadOnlyComposable get() = LocalPlanPalette.current.paper
val SurfaceColor: Color @Composable @ReadOnlyComposable get() = LocalPlanPalette.current.surface
val Muted: Color @Composable @ReadOnlyComposable get() = LocalPlanPalette.current.muted
val Line: Color @Composable @ReadOnlyComposable get() = LocalPlanPalette.current.line
val Mint: Color @Composable @ReadOnlyComposable get() = LocalPlanPalette.current.mint
val Apricot: Color @Composable @ReadOnlyComposable get() = LocalPlanPalette.current.apricot
val BlueWash: Color @Composable @ReadOnlyComposable get() = LocalPlanPalette.current.blue
val Gold: Color @Composable @ReadOnlyComposable get() = LocalPlanPalette.current.gold
val ChartBar: Color @Composable @ReadOnlyComposable get() = LocalPlanPalette.current.chart
val SoftSurface: Color @Composable @ReadOnlyComposable get() = LocalPlanPalette.current.soft
val ErrorWash: Color @Composable @ReadOnlyComposable get() = LocalPlanPalette.current.errorWash
val ErrorInk: Color @Composable @ReadOnlyComposable get() = LocalPlanPalette.current.errorInk
val Hero: Color @Composable @ReadOnlyComposable get() = LocalPlanPalette.current.hero
val OnHero: Color @Composable @ReadOnlyComposable get() = LocalPlanPalette.current.onHero
val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalPlanPalette.current.onAccent

@Composable fun TongpinTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { AppearanceStore(context) }
    var mode by remember { mutableStateOf(store.mode()) }
    var color by remember { mutableStateOf(store.color()) }
    var style by remember { mutableStateOf(store.style()) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "theme_mode" || key == "theme_color" || key == "theme_style" || key == null) {
                mode = store.mode(); color = store.color(); style = store.style()
            }
        }
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        mode = store.mode(); color = store.color(); style = store.style()
        onDispose { store.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val systemDark = isSystemInDarkTheme()
    val dark = mode == ThemeMode.DARK || mode == ThemeMode.SYSTEM && systemDark
    val p = planPalette(color, dark, style)
    val headingFont = if (style == ThemeStyle.PAPER) FontFamily.Serif else FontFamily.SansSerif
    SideEffect {
        (context as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
    }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    CompositionLocalProvider(LocalPlanPalette provides p, LocalPlanStyle provides style) {
        MaterialTheme(colorScheme = base.copy(primary=p.accent, onPrimary=p.onAccent,
            secondary=p.accent, onSecondary=p.onAccent, background=p.paper, surface=p.surface, onSurface=p.ink,
            tertiary=p.accent, onTertiary=p.onAccent, tertiaryContainer=p.blue, onTertiaryContainer=p.ink,
            surfaceTint=p.accent, inverseSurface=p.ink, inverseOnSurface=p.paper, inversePrimary=p.paper,
            onBackground=p.ink, surfaceVariant=p.mint, onSurfaceVariant=p.muted,
            outline=p.outline, outlineVariant=p.line,
            error=p.error, onError=p.onError,
            errorContainer=p.errorWash,onErrorContainer=p.errorInk,
            primaryContainer=p.mint,onPrimaryContainer=p.ink,secondaryContainer=p.mint,onSecondaryContainer=p.ink,
            surfaceContainer=p.surface,surfaceContainerHigh=p.surface,surfaceContainerHighest=p.soft,
            surfaceContainerLow=p.paper,surfaceContainerLowest=p.paper),
            shapes=Shapes(
                extraSmall=RoundedCornerShape((style.smallRadius / 2).dp),
                small=RoundedCornerShape(style.smallRadius.dp),
                medium=RoundedCornerShape(style.buttonRadius.dp),
                large=RoundedCornerShape(style.cardRadius.dp),
                extraLarge=RoundedCornerShape((style.cardRadius + 4).dp),
            ),
            typography=Typography(
                headlineLarge=TextStyle(fontFamily=headingFont,fontWeight=FontWeight.SemiBold,fontSize=30.sp,lineHeight=40.sp),
                headlineMedium=TextStyle(fontFamily=headingFont,fontWeight=FontWeight.SemiBold,fontSize=24.sp,lineHeight=34.sp),
                headlineSmall=TextStyle(fontFamily=headingFont,fontWeight=FontWeight.SemiBold,fontSize=22.sp,lineHeight=30.sp),
                titleLarge=TextStyle(fontFamily=headingFont,fontWeight=FontWeight.Medium,fontSize=20.sp,lineHeight=28.sp),
                titleMedium=TextStyle(fontFamily=headingFont,fontWeight=FontWeight.SemiBold,fontSize=17.sp,lineHeight=26.sp),
                titleSmall=TextStyle(fontFamily=headingFont,fontWeight=FontWeight.SemiBold,fontSize=14.sp,lineHeight=22.sp),
                bodyLarge=TextStyle(fontSize=16.sp,lineHeight=26.sp),bodyMedium=TextStyle(fontSize=14.sp,lineHeight=22.sp),
                bodySmall=TextStyle(fontSize=12.sp,lineHeight=19.sp),labelLarge=TextStyle(fontWeight=FontWeight.Medium,fontSize=14.sp,lineHeight=20.sp),
                labelMedium=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=12.sp,lineHeight=18.sp),labelSmall=TextStyle(fontSize=12.sp,lineHeight=18.sp)
            ), content=content)
    }
}
