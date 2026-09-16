package com.tongpin.app

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Names are persisted in preferences and complete backups. Keep them stable. */
enum class ThemeColor(val label: String) {
    GREEN("松绿"), BLUE("晴蓝"), PURPLE("雾紫"), ROSE("柔玫瑰"), ORANGE("暖杏"), GRAPHITE("石墨");
    val swatch: Color get() = planPalette(this, dark = false).accent
    companion object {
        fun parse(value: String?): ThemeColor = entries.firstOrNull { it.name == value } ?: GREEN
    }
}

/** Backgrounds belong to a style; the chosen color is reserved for actions and small accents. */
@Immutable
data class PlanPalette(
    val ink: Color, val accent: Color, val paper: Color, val surface: Color,
    val muted: Color, val line: Color, val mint: Color, val apricot: Color,
    val blue: Color, val gold: Color, val soft: Color, val errorWash: Color,
    val errorInk: Color, val hero: Color, val onHero: Color, val onAccent: Color,
    val outline: Color, val error: Color, val onError: Color, val chart: Color,
    val style: ThemeStyle = ThemeStyle.SOFT,
)

fun planPalette(theme: ThemeColor, dark: Boolean, style: ThemeStyle = ThemeStyle.SOFT): PlanPalette {
    val n = when (style) {
        ThemeStyle.CLEAN -> if (dark) cleanDark else cleanLight
        ThemeStyle.PAPER -> if (dark) paperDark else paperLight
        ThemeStyle.SOFT -> if (dark) softDark else softLight
    }
    val accent = when (theme) {
        ThemeColor.GREEN -> if (dark) Color(0xFF90CBBB) else Color(0xFF287064)
        ThemeColor.BLUE -> if (dark) Color(0xFF9CC8E6) else Color(0xFF326C98)
        ThemeColor.PURPLE -> if (dark) Color(0xFFC4B4DE) else Color(0xFF756091)
        ThemeColor.ROSE -> if (dark) Color(0xFFE3B2C4) else Color(0xFF95556D)
        ThemeColor.ORANGE -> if (dark) Color(0xFFE3C098) else Color(0xFF91602D)
        ThemeColor.GRAPHITE -> if (dark) Color(0xFFBBC8D4) else Color(0xFF596775)
    }
    // Small selected surfaces are tinted enough to read as controls, without coloring the page.
    val wash = lerp(n.surface, accent, if (dark) .15f else .10f)
    return PlanPalette(
        ink = n.ink, accent = accent, paper = n.paper, surface = n.surface,
        muted = n.muted, line = n.line, mint = wash,
        apricot = lerp(n.surface, if (dark) Color(0xFFE3C098) else Color(0xFFDDA774), .12f),
        blue = lerp(n.surface, if (dark) Color(0xFF9CC8E6) else Color(0xFF77A4C3), .12f),
        gold = accent, soft = n.soft,
        errorWash = if (dark) Color(0xFF45282B) else Color(0xFFFFEEEC),
        errorInk = if (dark) Color(0xFFFFBBB2) else Color(0xFF8D302B),
        hero = n.hero, onHero = n.ink,
        onAccent = if (dark) Color(0xFF1C2527) else Color.White,
        outline = n.outline,
        error = if (dark) Color(0xFFFFBBB2) else Color(0xFFAD3D34),
        onError = if (dark) Color(0xFF4E151A) else Color.White,
        chart = accent, style = style,
    )
}

private data class NeutralPalette(
    val paper: Color, val surface: Color, val soft: Color, val hero: Color,
    val ink: Color, val muted: Color, val line: Color, val outline: Color,
)
private val cleanLight = NeutralPalette(
    Color(0xFFF5F5F4), Color(0xFFFFFFFF), Color(0xFFEEEFED), Color(0xFFEAEEEC),
    Color(0xFF22282A), Color(0xFF555D5E), Color(0xFFDCE0DC), Color(0xFF77827D),
)
private val cleanDark = NeutralPalette(
    Color(0xFF141718), Color(0xFF1F2425), Color(0xFF292F30), Color(0xFF283033),
    Color(0xFFE7EBE8), Color(0xFFB1BBB6), Color(0xFF41494A), Color(0xFF86928D),
)
private val paperLight = NeutralPalette(
    Color(0xFFF6F1E7), Color(0xFFFFFCF5), Color(0xFFEEE7D8), Color(0xFFF4EEE1),
    Color(0xFF332F29), Color(0xFF625B50), Color(0xFFDBD1BF), Color(0xFF897D6B),
)
private val paperDark = NeutralPalette(
    Color(0xFF1C1B18), Color(0xFF27251F), Color(0xFF302D26), Color(0xFF322F26),
    Color(0xFFEEE8DB), Color(0xFFC3BBAC), Color(0xFF4A4438), Color(0xFFAA9D86),
)
private val softLight = NeutralPalette(
    Color(0xFFF7F7F4), Color(0xFFFFFFFF), Color(0xFFEBEFEB), Color(0xFFEEF2EE),
    Color(0xFF29332F), Color(0xFF56615A), Color(0xFFD9E1DA), Color(0xFF77877B),
)
private val softDark = NeutralPalette(
    Color(0xFF181E1D), Color(0xFF222B28), Color(0xFF2C3631), Color(0xFF2C3833),
    Color(0xFFECF0EB), Color(0xFFBCC7BE), Color(0xFF445249), Color(0xFF95A79A),
)
