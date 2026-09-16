package com.tongpin.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** A bounded raster renderer; preview uses half resolution and export uses 1080 pixels. */
object ProgressCardRenderer {
    fun render(model: ProgressCardModel, palette: PlanPalette, preview: Boolean = false, checkCancelled: () -> Unit = {}): Bitmap {
        val bodyStart = if (model.weekly) 840 else 532
        val rowsHeight = if (!model.detailed) 0 else 48 + max(124, model.rows.size * 91) + if (model.extraRows > 0) 45 else 0
        val notesHeight = if (!model.notesRequested) 0 else 72 + max(68, model.notes.size * 180) + if (model.extraNotes > 0) 40 else 0
        val height = bodyStart + rowsHeight + notesHeight + 240
        val scale = if (preview) .5f else 1f
        val bitmap = Bitmap.createBitmap((1080 * scale).toInt(), (height * scale).toInt(), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap).apply { scale(scale, scale) }
            val card = Card(canvas, height, palette)
            checkCancelled()
            card.header(model)
            card.summary(324f, model)
            if (model.weekly) card.weekChart(model.days, 580f)
            var y = bodyStart.toFloat()
            if (model.detailed) {
                card.text(if (model.weekly) "计划积累" else "这一天的坚持", 64f, y + 20f, 31f, palette.ink.toArgb(), true)
                y += 48f
                if (model.rows.isEmpty()) {
                    card.empty(y, if (model.weekly) "这七天没有安排计划" else "这一天没有安排计划")
                    y += 124f
                } else model.rows.forEach { row ->
                    checkCancelled()
                    card.planRow(y, row)
                    y += 91f
                }
                if (model.extraRows > 0) {
                    card.text("另有 ${model.extraRows} 项计划 · 已计入上方统计", 64f, y + 28f, 23f, palette.muted.toArgb())
                    y += 45f
                }
            }
            if (model.notesRequested) {
                y += 30f
                card.text("记录里的心得", 64f, y + 20f, 31f, palette.ink.toArgb(), true)
                y += 42f
                if (model.notes.isEmpty()) {
                    card.text("这段时间还没有记录心得", 64f, y + 35f, 25f, palette.muted.toArgb())
                    y += 68f
                } else model.notes.forEach { note ->
                    checkCancelled()
                    card.note(y, note)
                    y += 180f
                }
                if (model.extraNotes > 0) {
                    card.text("另有 ${model.extraNotes} 条心得 · 可在记录中查看", 64f, y + 25f, 23f, palette.muted.toArgb())
                    y += 40f
                }
            }
            card.focus(y + 42f, model.focusSeconds, if (model.weekly) "七日专注" else "当天专注")
            card.footer()
            checkCancelled()
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    private class Card(private val canvas: Canvas, private val height: Int, palette: PlanPalette) {
        private val style = palette.style
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val ink = palette.ink.toArgb()
        private val accent = palette.accent.toArgb()
        private val surface = palette.surface.toArgb()
        private val muted = palette.muted.toArgb()
        private val line = palette.line.toArgb()
        private val wash = palette.mint.toArgb()
        private val hero = palette.hero.toArgb()
        private val onHero = palette.onHero.toArgb()
        private val onAccent = palette.onAccent.toArgb()
        private val chart = palette.chart.toArgb()
        private val dateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.CHINA)

        init {
            canvas.drawColor(palette.paper.toArgb())
            when (style) {
                ThemeStyle.CLEAN -> Unit
                ThemeStyle.PAPER -> {
                    paint.color = line
                    paint.strokeWidth = 1f
                    canvas.drawLine(64f, 304f, 1016f, 304f, paint)
                    canvas.drawLine(64f, height - 86f, 1016f, height - 86f, paint)
                }
                ThemeStyle.SOFT -> {
                    paint.color = wash
                    canvas.drawCircle(1030f, 7f, 252f, paint)
                    paint.color = palette.soft.toArgb()
                    canvas.drawCircle(1050f, 18f, 192f, paint)
                }
            }
        }

        fun header(model: ProgressCardModel) {
            rounded(64f, 59f, 122f, 117f, 19f, accent)
            paint.color = onAccent
            paint.strokeWidth = 5f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(79f, 89f, 89f, 99f, paint)
            canvas.drawLine(89f, 99f, 108f, 78f, paint)
            text("plan", 139f, 101f, 34f, ink, true)
            text(if (model.nickname.isBlank()) "我的成长记录" else "${model.nickname}的记录", 1016f, 101f, 25f, muted, align = Paint.Align.RIGHT, maxWidth = 730f)
            text(if (model.weekly) "WEEKLY REFLECTION" else "DAILY RECORD", 64f, 163f, 21f, accent, true)
            text(model.title, 64f, 235f, 60f, ink, true)
            val dates = if (model.weekly) "${model.start.format(dateFormat)} — ${model.end.format(dateFormat)}"
                else "${model.end.format(dateFormat)}  星期${listOf("一", "二", "三", "四", "五", "六", "日")[model.end.dayOfWeek.value - 1]}"
            text(dates, 64f, 283f, 27f, muted)
            text(if (model.detailed) "详细记录" else "简洁总览", 1016f, 283f, 23f, muted, align = Paint.Align.RIGHT)
        }

        fun summary(top: Float, model: ProgressCardModel) {
            rounded(64f, top, 1016f, top + 168f, 29f, hero)
            text(model.completed.toString(), 100f, top + 84f, 64f, onHero, true, maxWidth = 255f)
            text(if (model.weekly) "累计完成计划" else "已完成计划", 102f, top + 132f, 25f, onHero)
            text(model.scheduled.toString(), 394f, top + 84f, 64f, onHero, true, maxWidth = 255f)
            text(if (model.weekly) "七日计划次数" else "当天计划", 396f, top + 132f, 25f, onHero)
            paint.color = onHero
            paint.alpha = 50
            paint.strokeWidth = 2f
            canvas.drawLine(353f, top + 38f, 353f, top + 129f, paint)
            paint.alpha = 255
            val ratio = if (model.scheduled > 0) (model.completed.toFloat() / model.scheduled).coerceIn(0f, 1f) else 0f
            val circle = RectF(846f, top + 23f, 968f, top + 145f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 10f
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = onHero
            paint.alpha = 35
            canvas.drawOval(circle, paint)
            paint.alpha = 255
            if (ratio > 0f) canvas.drawArc(circle, -90f, ratio * 360f, false, paint)
            paint.style = Paint.Style.FILL
            text(if (model.scheduled > 0) "${(ratio * 100).toInt()}%" else "—", 907f, top + 90f, 31f, onHero, true, Paint.Align.CENTER)
            text("完成率", 737f, top + 91f, 25f, onHero, align = Paint.Align.CENTER)
        }

        fun planRow(y: Float, row: ProgressCardRow) {
            rounded(64f, y + 5f, 70f, y + 60f, 3f, accent)
            text(row.title, 91f, y + if (row.detail.isEmpty()) 44f else 29f, 28f, ink, true, maxWidth = 670f)
            if (row.detail.isNotEmpty()) text(row.detail, 91f, y + 63f, 23f, muted, maxWidth = 670f)
            rounded(840f, y + 4f, 1016f, y + 55f, 25f, if (row.done) wash else surface)
            text(row.status, 928f, y + 38f, 24f, if (row.done) accent else muted, true, Paint.Align.CENTER, 152f)
            rounded(840f, y + 68f, 1016f, y + 73f, 2.5f, line)
            val width = 176f * row.ratio.coerceIn(0f, 1f)
            if (width > 0f) rounded(840f, y + 68f, 840f + width, y + 73f, min(2.5f, width / 2), accent)
            paint.color = line
            paint.strokeWidth = 1f
            canvas.drawLine(91f, y + 84f, 1016f, y + 84f, paint)
        }

        fun empty(y: Float, title: String) {
            rounded(64f, y, 1016f, y + 108f, 23f, surface)
            text(title, 94f, y + 66f, 29f, ink, true)
        }

        fun weekChart(days: List<ProgressCardDay>, y: Float) {
            text("七日完成进度", 64f, y - 34f, 31f, ink, true)
            text("每天：已完成 / 已安排", 1016f, y - 34f, 22f, muted, align = Paint.Align.RIGHT)
            rounded(64f, y, 1016f, y + 216f, 25f, surface)
            days.forEachIndexed { index, day ->
                val center = 134f + index * 135f
                val ratio = if (day.scheduled > 0) (day.completed.toFloat() / day.scheduled).coerceIn(0f, 1f) else 0f
                rounded(center - 17f, y + 34f, center + 17f, y + 126f, 17f, wash)
                if (ratio > 0f) {
                    val barHeight = max(7f, 92f * ratio)
                    rounded(center - 17f, y + 126f - barHeight, center + 17f, y + 126f, min(17f, barHeight / 2f), chart)
                }
                text("${day.completed}/${day.scheduled}", center, y + 24f, 21f, ink, true, Paint.Align.CENTER, 115f)
                text("周${listOf("一", "二", "三", "四", "五", "六", "日")[day.date.dayOfWeek.value - 1]}", center, y + 159f, 23f, ink, align = Paint.Align.CENTER)
                text("${day.date.monthValue}/${day.date.dayOfMonth}", center, y + 191f, 21f, muted, align = Paint.Align.CENTER)
            }
        }

        fun note(y: Float, note: ProgressCardNote) {
            rounded(64f, y, 1016f, y + 165f, 20f, surface)
            text(note.heading, 88f, y + 32f, 22f, accent, true, maxWidth = 904f)
            paint.textSize = 24f
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            var remaining = note.text
            for (lineIndex in 0..2) {
                if (remaining.isEmpty()) break
                var count = paint.breakText(remaining, true, 904f, null).coerceAtLeast(1)
                if (count < remaining.length && remaining[count - 1].isHighSurrogate()) count--
                val output = if (lineIndex == 2) remaining else remaining.take(count)
                text(output, 88f, y + 71f + lineIndex * 34f, 24f, ink, maxWidth = 904f)
                remaining = remaining.drop(count)
            }
        }

        fun focus(y: Float, seconds: Long, label: String) {
            rounded(64f, y, 1016f, y + 88f, 23f, wash)
            text(label, 94f, y + 56f, 27f, accent, true)
            val hours = seconds / 3600
            val minutes = seconds % 3600 / 60
            val remainder = seconds % 60
            val duration = buildList { if (hours > 0) add("$hours 小时"); if (minutes > 0) add("$minutes 分钟"); if (remainder > 0 || isEmpty()) add("$remainder 秒") }.joinToString(" ")
            text(duration, 985f, y + 57f, 33f, ink, true, Paint.Align.RIGHT, 670f)
        }

        fun footer() {
            text("plan · 每一步，都有迹可循", 64f, height - 46f, 23f, muted)
            val generated = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA))
            text("生成于 $generated", 1016f, height - 46f, 21f, muted, align = Paint.Align.RIGHT)
        }

        fun text(value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT, maxWidth: Float? = null) {
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.alpha = 255
            paint.textSize = size
            paint.textAlign = align
            paint.typeface = Typeface.create(if (style == ThemeStyle.PAPER && bold && size >= 31f) "serif" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            val singleLine = value.replace('\n', ' ').replace('\r', ' ')
            val output = if (maxWidth != null) TextUtils.ellipsize(singleLine, paint, maxWidth, TextUtils.TruncateAt.END).toString() else singleLine
            canvas.drawText(output, x, y, paint)
        }

        private fun rounded(left: Float, top: Float, right: Float, bottom: Float, radius: Float, color: Int) {
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.alpha = 255
            val panel = right - left > 500f && bottom - top > 70f
            val rounding = if (panel) when (style) { ThemeStyle.CLEAN -> 14f; ThemeStyle.PAPER -> 5f; ThemeStyle.SOFT -> radius } else radius
            canvas.drawRoundRect(left, top, right, bottom, rounding, rounding, paint)
            if (panel && style == ThemeStyle.PAPER) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                paint.color = line
                canvas.drawRoundRect(left, top, right, bottom, rounding, rounding, paint)
                paint.style = Paint.Style.FILL
            }
        }
    }
}
