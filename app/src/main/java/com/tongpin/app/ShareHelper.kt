package com.tongpin.app

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.time.LocalDate

/** Creates a local file, then opens the system destination picker. */
object ShareHelper {
    // Keep old callers source-compatible; app screens use ProgressCardDialog for background rendering.
    fun shareDaily(context: Context, data: AppData, date: LocalDate) = legacyCard(context, data, date, false)
    fun shareWeekly(context: Context, data: AppData, endDate: LocalDate = LocalDate.now()) = legacyCard(context, data, endDate, true)

    private fun legacyCard(context: Context, data: AppData, date: LocalDate, weekly: Boolean) {
        val model = ProgressCardRules.make(data, date, ProgressCardOptions(weekly = weekly))
        val file = prepareProgressCard(context, model, cardPalette(context))
        sendProgressCard(context, file, model)
    }

    fun cardPalette(context: Context): PlanPalette {
        val store = AppearanceStore(context)
        val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val dark = store.mode() == ThemeMode.DARK || store.mode() == ThemeMode.SYSTEM && systemDark
        return planPalette(store.color(), dark, store.style())
    }

    /** Call from a worker. A failed/cancelled write removes the partial file and always releases pixels. */
    fun prepareProgressCard(context: Context, model: ProgressCardModel, palette: PlanPalette, checkCancelled: () -> Unit = {}): File {
        val bitmap = ProgressCardRenderer.render(model, palette, checkCancelled = checkCancelled)
        var file: File? = null
        try {
            checkCancelled()
            file = shareFile(context, model.filePrefix, ".png")
            file.outputStream().use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IOException("无法生成进度卡，请重试")
            }
            checkCancelled()
            trimProgressCards(file.parentFile!!, file)
            return file
        } catch (error: Throwable) {
            file?.delete()
            throw error
        } finally {
            bitmap.recycle()
        }
    }

    fun sendProgressCard(context: Context, file: File, model: ProgressCardModel) {
        // These strings contain dates only, so hidden fields never leak into chooser captions.
        sendFile(context, file, "image/png", if (model.weekly) "分享七日进度卡" else "分享每日进度卡", model.description)
    }

    /** Keep at most eight cards, including files created by older releases. */
    private fun trimProgressCards(directory: File, current: File) {
        val previous = directory.listFiles().orEmpty().filter {
            it != current && it.isFile && it.extension == "png" &&
                (it.name.startsWith("plan-daily-") || it.name.startsWith("plan-weekly-"))
        }.sortedByDescending { it.lastModified() }
        previous.drop(7).forEach { it.delete() }
    }

    fun sharePlans(context: Context, plans: List<Plan>, categories: List<CustomCategory> = emptyList()) {
        val file = shareFile(context, "plan-plans", ".plan.json")
        file.writeText(DataCodec.encodePlans(plans, categories), Charsets.UTF_8)
        sendFile(context, file, "application/json", "导出计划文件", "plan 计划文件 · ${plans.size} 项计划")
    }

    fun shareBackup(context: Context, data: AppData) {
        val file = shareFile(context, "plan-backup", ".plan.json")
        file.writeText(DataCodec.encode(data).toString(), Charsets.UTF_8)
        sendFile(context, file, "application/json", "导出完整备份", "plan 完整备份 · 包含计划、打卡记录与专注记录")
    }

    private fun shareFile(context: Context, prefix: String, extension: String): File {
        val directory = File(context.cacheDir, "share")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建导出文件夹，请检查可用存储空间")
        return File.createTempFile("$prefix-${System.currentTimeMillis()}-", extension, directory)
    }

    private fun sendFile(context: Context, file: File, mime: String, title: String, description: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val clip = ClipData.newUri(context.contentResolver, title, uri)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TITLE, title)
            putExtra(Intent.EXTRA_TEXT, description)
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, title).apply {
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
