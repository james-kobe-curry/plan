package com.tongpin.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun avatarIcon(id: String): ImageVector = when (id) {
    "BOOK" -> Icons.Outlined.MenuBook
    "LEAF" -> Icons.Outlined.Spa
    "SUN" -> Icons.Outlined.WbSunny
    "MOON" -> Icons.Outlined.DarkMode
    "MOUNTAIN" -> Icons.Outlined.Landscape
    else -> Icons.Outlined.AutoAwesome
}

@Composable
fun ProfileAvatar(profile: PersonalProfile, modifier: Modifier = Modifier) {
    val bitmap = remember(profile.avatarImage) { decodeProfileAvatar(profile.avatarImage)?.asImageBitmap() }
    Box(modifier.clip(CircleShape).background(Mint), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, "头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(avatarIcon(profile.avatarId), "头像", Modifier.fillMaxSize().padding(11.dp), tint = Teal)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileDialog(data: AppData, onDismiss: () -> Unit, onSave: suspend (nickname: String, profile: PersonalProfile) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var nickname by rememberSaveable { mutableStateOf(data.nickname) }
    var motto by rememberSaveable { mutableStateOf(data.profile.motto) }
    var avatarId by rememberSaveable { mutableStateOf(data.profile.avatarId) }
    var avatarImage by rememberSaveable { mutableStateOf(data.profile.avatarImage) }
    var showOnHome by rememberSaveable { mutableStateOf(data.profile.showOnHome) }
    var error by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            error = ""
            try { avatarImage = withContext(Dispatchers.IO) { importProfileAvatar(context, uri) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { error = e.message ?: "头像读取失败，请重新选择" }
            finally { busy = false }
        }
    }
    PersonalizationDialogFrame("个人资料", onDismiss, busy, error, "保存资料", {
        if (nickname.isBlank()) { error = "请填写昵称"; return@PersonalizationDialogFrame }
        if (nickname.trim().length > DomainValidation.MAX_NICKNAME_LENGTH) { error = "昵称最多 ${DomainValidation.MAX_NICKNAME_LENGTH} 个字"; return@PersonalizationDialogFrame }
        if (motto.trim().length > 80) { error = "首页寄语最多 80 个字"; return@PersonalizationDialogFrame }
        scope.launch {
            busy = true
            error = ""
            try {
                onSave(nickname.trim(), PersonalProfile(motto.trim(), avatarId, avatarImage, showOnHome))
                onDismiss()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { error = e.message ?: "资料未能保存，请重试" }
            finally { busy = false }
        }
    }) {
        ProfileAvatar(PersonalProfile(avatarId = avatarId, avatarImage = avatarImage), Modifier.size(84.dp).align(Alignment.CenterHorizontally))
        Text("选择头像", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AvatarIcon.entries.forEach { avatar ->
                FilterChip(selected = avatarId == avatar.name && avatarImage == null,
                    onClick = { avatarId = avatar.name; avatarImage = null }, enabled = !busy,
                    label = { Text(avatar.label) }, leadingIcon = { Icon(avatarIcon(avatar.name), null, Modifier.size(18.dp)) })
            }
        }
        OutlinedButton(onClick = { gallery.launch("image/*") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.AddPhotoAlternate, null); Spacer(Modifier.width(8.dp)); Text("从相册选择头像")
        }
        if (avatarImage != null) TextButton({ avatarImage = null }, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("移除相册头像") }
        OutlinedTextField(nickname, { nickname = it.take(DomainValidation.MAX_NICKNAME_LENGTH + 1); if(error=="请填写昵称" && nickname.isNotBlank() || error=="昵称最多 ${DomainValidation.MAX_NICKNAME_LENGTH} 个字" && nickname.trim().length<=DomainValidation.MAX_NICKNAME_LENGTH)error="" }, label = { Text("昵称") }, enabled = !busy,
            singleLine = true, modifier = Modifier.fillMaxWidth(), supportingText = { Text("${nickname.length} / ${DomainValidation.MAX_NICKNAME_LENGTH}") })
        OutlinedTextField(motto, { motto = it.take(81); if(error=="首页寄语最多 80 个字" && motto.trim().length<=80)error="" }, label = { Text("首页寄语") }, placeholder = { Text("写一句想对自己说的话") }, enabled = !busy,
            minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(), supportingText = { Text("${motto.length} / 80") })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("在首页展示个人资料"); Text("头像、昵称与寄语", style = MaterialTheme.typography.bodySmall, color = Muted) }
            Switch(showOnHome, { showOnHome = it }, enabled = !busy)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeCustomizationDialog(initial: HomePreferences, onDismiss: () -> Unit, onSave: (HomePreferences) -> Unit) {
    var order by rememberSaveable { mutableStateOf(initial.order.joinToString(",") { it.name }) }
    var showProgress by rememberSaveable { mutableStateOf(initial.showProgress) }
    var showRecent by rememberSaveable { mutableStateOf(initial.showRecent) }
    var density by rememberSaveable { mutableStateOf(initial.density.name) }
    var startPage by rememberSaveable { mutableIntStateOf(initial.startPage) }
    var error by rememberSaveable { mutableStateOf("") }
    val value = readHomePreferences(order, showProgress, showRecent, density, startPage)
    PersonalizationDialogFrame("首页布局", onDismiss, false, error, "保存布局", {
        try { onSave(value); onDismiss() } catch (e: Exception) { error = e.message ?: "布局未能保存，请重试" }
    }) {
        Text("显示与顺序", style = MaterialTheme.typography.titleMedium)
        Text("任务列表始终保留；用上下箭头调整顺序。", style = MaterialTheme.typography.bodySmall, color = Muted)
        value.order.forEachIndexed { index, module ->
            Surface(shape = PlanCardShape, color = Paper) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(module.label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        if (module == HomeModule.TASKS) Text("始终显示", style = MaterialTheme.typography.bodySmall, color = Muted)
                        else Switch(value.shows(module), { if (module == HomeModule.PROGRESS) showProgress = it else showRecent = it })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton({ order = value.move(module, -1).order.joinToString(",") { it.name } }, enabled = index > 0) { Icon(Icons.Outlined.ArrowUpward, "上移${module.label}") }
                        IconButton({ order = value.move(module, 1).order.joinToString(",") { it.name } }, enabled = index < value.order.lastIndex) { Icon(Icons.Outlined.ArrowDownward, "下移${module.label}") }
                    }
                }
            }
        }
        Text("任务显示", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeDensity.entries.forEach { choice -> FilterChip(value.density == choice, { density = choice.name }, label = { Text(choice.label) }) }
        }
        Text("简洁模式压缩卡片留白，保留打卡、置顶与跳过操作。", style = MaterialTheme.typography.bodySmall, color = Muted)
        Text("默认打开页面", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("今日", "计划", "专注", "记录").forEachIndexed { index, label -> FilterChip(startPage == index, { startPage = index }, label = { Text(label) }) }
        }
        Text("下次启动时生效，提醒入口仍会打开对应页面。", style = MaterialTheme.typography.bodySmall, color = Muted)
        TextButton({ order = "PROGRESS,TASKS,RECENT"; showProgress = true; showRecent = false; density = HomeDensity.COMFORTABLE.name; startPage = 0 }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("恢复默认布局") }
    }
}

@Composable
private fun PersonalizationDialogFrame(title: String, onDismiss: () -> Unit, busy: Boolean, error: String, saveLabel: String, onSave: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    PlanDialog({ if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        Column(Modifier.fillMaxSize().background(Paper).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onDismiss, enabled = !busy) { Icon(Icons.Outlined.Close, "关闭$title") }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            HorizontalDivider(color = Line)
            Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormErrorBanner(error)
                Button(onSave, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (busy) "正在处理…" else saveLabel) }
            }
        }
    }
}
