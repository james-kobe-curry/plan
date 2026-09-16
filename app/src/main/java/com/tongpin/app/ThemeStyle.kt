package com.tongpin.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Stable keys. A style changes shape, typography, spacing and decoration together. */
enum class ThemeStyle(
    val label: String, val description: String,
    val cardRadius: Int, val smallRadius: Int, val buttonRadius: Int,
    val pageGap: Int, val cardPadding: Int, val pagePadding: Int,
) {
    CLEAN("清简", "中性底色 · 利落卡片", 12, 8, 10, 14, 16, 20),
    PAPER("纸页", "暖纸底色 · 书页排版", 6, 4, 6, 18, 18, 20),
    SOFT("柔和", "圆润卡片 · 轻盈留白", 20, 10, 14, 16, 16, 20);
    companion object {
        fun parse(value: String?): ThemeStyle = entries.firstOrNull { it.name == value } ?: SOFT
    }
}

internal val LocalPlanStyle = staticCompositionLocalOf { ThemeStyle.SOFT }
val PlanStyle: ThemeStyle @Composable @ReadOnlyComposable get() = LocalPlanStyle.current
val PlanCardShape: RoundedCornerShape @Composable @ReadOnlyComposable get() = RoundedCornerShape(PlanStyle.cardRadius.dp)
val PlanSmallShape: RoundedCornerShape @Composable @ReadOnlyComposable get() = RoundedCornerShape(PlanStyle.smallRadius.dp)
val PlanButtonShape: RoundedCornerShape @Composable @ReadOnlyComposable get() = RoundedCornerShape(PlanStyle.buttonRadius.dp)
val PlanPageGap: Dp @Composable @ReadOnlyComposable get() = PlanStyle.pageGap.dp
val PlanCardPadding: Dp @Composable @ReadOnlyComposable get() = PlanStyle.cardPadding.dp
val PlanPagePadding: Dp @Composable @ReadOnlyComposable get() = PlanStyle.pagePadding.dp
val PlanCardBorder: BorderStroke? @Composable @ReadOnlyComposable get() =
    if (PlanStyle == ThemeStyle.PAPER) BorderStroke(1.dp, Line) else null

/** Quiet decorations are limited to summary panels, so task lists remain easy to scan. */
@Composable
fun Modifier.planStyleDecoration(): Modifier {
    val style = PlanStyle
    val line = Line
    return when (style) {
        ThemeStyle.CLEAN, ThemeStyle.SOFT -> this
        ThemeStyle.PAPER -> drawBehind {
            val gap = 28.dp.toPx()
            var y = gap
            while (y < size.height) {
                drawLine(line.copy(alpha = .38f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                y += gap
            }
        }
    }
}
