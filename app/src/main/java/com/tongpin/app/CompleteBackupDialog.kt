package com.tongpin.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun CompleteBackupDialog(onDismiss: () -> Unit, onRestored: suspend (AppData) -> Unit,
    canRestore: () -> Boolean = { true }, onBeforeRestore: suspend () -> Unit = {},
    onRecoveryRequired: (String) -> Unit = {}, onExported: () -> Unit = {}, restoreOnly: Boolean = false) {
    val context = LocalContext.current
    val store = remember { CompleteBackupStore(context) }
    val coroutine = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var prepared by remember { mutableStateOf<PreparedCompleteBackup?>(null) }
    var importing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val latestPrepared by rememberUpdatedState(prepared)
    val latestCanRestore by rememberUpdatedState(canRestore)
    val latestBeforeRestore by rememberUpdatedState(onBeforeRestore)
    val latestRecoveryRequired by rememberUpdatedState(onRecoveryRequired)
    DisposableEffect(Unit) { onDispose { latestPrepared?.close() } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null && !busy) {
            val content = prepared
            if (content == null) error = "备份预览已失效，请重新准备备份"
            else { busy = true; error = ""; coroutine.launch {
                try {
                    val reminderSaved = withContext(Dispatchers.IO) { store.export(content, uri) }
                    notice = if(reminderSaved) "一键备份已保存，计划、存档与提示音已一起打包。" else "一键备份已保存；备份提醒日期未能更新，请检查可用存储空间。"
                    onExported()
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "备份未能保存，请选择其他位置重试" }
                finally { busy = false }
            } }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true; error = ""; notice = ""
            prepared?.close(); prepared = null
            coroutine.launch {
                var pending: PreparedCompleteBackup? = null
                try {
                    val content = withContext(Dispatchers.IO) { store.prepareImport(uri).also { pending = it } }
                    prepared?.close(); prepared = content; importing = true; pending = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "无法读取备份，请检查文件是否完整" }
                finally { pending?.close(); busy = false }
            }
        }
    }

    PlanDialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ onDismiss() }, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    Text("一键备份", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Teal)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Surface(shape = PlanCardShape, color = Hero) {
                        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.Backup, null, tint = Gold, modifier = Modifier.size(32.dp))
                            Text("把积累，一起带走", color = OnHero, style = MaterialTheme.typography.headlineSmall)
                            Text("计划与分类、归档、打卡与每日心得、个人资料、命名与自动存档、提示音，以及外观、首页和专注偏好，一次保存。", color = OnHero, fontSize = 14.sp, lineHeight = 23.sp)
                        }
                    }
                    if(restoreOnly)Text("当前数据需要恢复，请选择已保存的一键备份文件。恢复时会结束当前专注。",color=Muted,fontSize=14.sp)
                    if(!restoreOnly)Button({
                        if (!busy) { busy = true; error = ""; notice = ""; coroutine.launch {
                            prepared?.close(); prepared = null
                            var pending: PreparedCompleteBackup? = null
                            try { val content = withContext(Dispatchers.IO) { store.prepareExport().also { pending = it } }
                                prepared?.close(); prepared = content; importing = false; pending = null }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = failure.message ?: "暂时无法准备完整备份" }
                            finally { pending?.close(); busy = false }
                        } }
                    }, Modifier.fillMaxWidth(), enabled = !busy, shape = PlanButtonShape) {
                        Icon(Icons.Outlined.Inventory2, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("准备一键备份")
                    }
                    OutlinedButton({ if (!busy) { error = ""; importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) } },
                        Modifier.fillMaxWidth(), enabled = !busy, shape = PlanButtonShape) {
                        Icon(Icons.Outlined.FileOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("选择一键备份文件")
                    }
                    prepared?.let { content ->
                        val summary = content.summary
                        Surface(shape = PlanCardShape, color = SurfaceColor) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(if (importing) "恢复预览" else "导出预览", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text(Instant.ofEpochMilli(summary.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")), color = Muted, fontSize = 12.sp)
                                Text("${summary.plans} 项当前计划 · ${summary.archived} 个归档版本\n${summary.checkIns} 条打卡 · ${summary.focusRecords} 次专注\n${summary.dailyNotes} 篇每日心得\n${summary.snapshots} 份命名存档 · ${summary.autoSnapshots} 份自动存档\n${summary.sounds} 首自定义提示音", color = Ink, fontSize = 14.sp, lineHeight = 25.sp)
                                Text("包含昵称、提醒与外观偏好${if (content.content.formatVersion >= 4) "、常用时长与上次手动时长" else ""}${if (content.content.formatVersion >= 5) "、个人资料与首页布局" else ""} · 内容大小 ${backupSize(summary.bytes)}", color = Teal, fontSize = 12.sp)
                                if (importing) Text("恢复将替换这台设备的上述内容，并清除旧的分类导入撤销副本。需要保留当前内容时，请先导出一键备份。", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
                                if (importing) Text(if(content.content.formatVersion<2) "这份旧版备份没有自动存档，恢复时会保留本机自动存档。" else "恢复时会保留本机今天首次使用的安全存档，再带回备份中的自动存档，最多保留 7 份；同日内容以本机今天的安全存档为准。", color = Teal, fontSize = 12.sp, lineHeight = 20.sp)
                                if (importing && content.content.formatVersion < 5) Text("旧版备份未包含界面风格与首页布局，恢复后采用柔和风格和默认首页布局。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                                if (importing && content.content.formatVersion < 4) Text(buildString {
                                    append("旧版备份未包含专注偏好：常用时长设为 15/25/45/60 分钟，上次手动时长设为 25 分钟。")
                                    if (content.content.formatVersion < 3) append("配色设为松绿。")
                                    if ("theme_mode" !in content.content.preferences) append("未保存明暗模式，将跟随系统。")
                                }, color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                            }
                        }
                    }
                    Text("单独导入计划或记录，仍可使用“数据与存档”中的分类备份。正在进行的计时、系统通知权限及系统音量不包含在备份中。", color = Muted, fontSize = 12.sp, lineHeight = 21.sp)
                    Text("备份完整性验证通过后才会替换数据；恢复失败时会还原原有内容。", color = Muted, fontSize = 12.sp, lineHeight = 21.sp)
                    Spacer(Modifier.height(8.dp))
                }
                Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (error.isNotBlank()) FormErrorBanner(error)
                    else if (notice.isNotBlank()) Text(notice, color = Teal, fontSize = 13.sp, lineHeight = 20.sp)
                    if (busy) Text("正在校验和整理文件，请稍候…", color = Muted, fontSize = 13.sp)
                    prepared?.let {
                        Button({
                            if (!busy) {
                                if (importing) {
                                    if (!latestCanRestore()) error = "请先结束当前专注，再恢复完整备份" else confirm = true
                                } else exportLauncher.launch("plan-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.plan.zip")
                            }
                        }, Modifier.fillMaxWidth(), enabled = !busy, shape = PlanButtonShape) {
                            Text(if (importing) "恢复这份备份" else "保存备份文件")
                        }
                    }
                }
            }
        }
    }
    if (confirm) PlanAlertDialog(onDismissRequest = { if (!busy) confirm = false }, title = { Text("替换当前全部内容？") },
        text = { Text("当前计划、记录、命名存档、自定义提示音及应用偏好将替换为预览内容。自动存档按预览说明恢复，并保留今天的本机安全存档。请确认已保存需要保留的当前数据。") },
        confirmButton = { TextButton({
            if (!busy) {
                if (!latestCanRestore()) { error = "请先结束当前专注，再恢复完整备份"; confirm = false }
                else prepared?.let { content -> busy = true; error = ""; confirm = false; coroutine.launch {
                    withContext(NonCancellable) {
                    var committed = false
                    try {
                        latestBeforeRestore()
                        val restored = withContext(Dispatchers.IO) { store.restore(content) }
                        committed = true
                        prepared = null; content.close()
                        notice = "完整备份已恢复，计划、存档与提示音均已更新。"
                        onRestored(restored)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: CompleteBackupRecoveryRequired) { error = failure.message.orEmpty(); latestRecoveryRequired(error) }
                    catch (failure: Exception) { error = if (committed) "备份已恢复，请重新打开应用刷新界面。" else failure.message ?: "恢复失败，原有内容已保留" }
                    finally { PlanReminderScheduler.reschedule(context); busy = false }
                    }
                } }
            }
        }, enabled = !busy) { Text("确认替换并恢复") } },
        dismissButton = { TextButton({ confirm = false }, enabled = !busy) { Text("取消") } })
}

private fun backupSize(bytes: Long): String = if (bytes < 1024 * 1024) String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
