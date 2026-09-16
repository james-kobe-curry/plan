package com.tongpin.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Close this hub before opening a child supplied by PlanApp, so only one dialog is active. */
@Composable
fun SettingsDialog(
    nickname: String,
    onDismiss: () -> Unit,
    onName: () -> Unit,
    onData: () -> Unit,
    onPlans: () -> Unit,
    onReminders: () -> Unit,
    onPlanReminders: () -> Unit,
    onAppearance: () -> Unit,
    onAbout: () -> Unit,
    onProfile: () -> Unit = onName,
    onHomeCustomization: () -> Unit = {},
    onCategories: () -> Unit = {},
    profile: PersonalProfile = PersonalProfile(),
) {
    fun open(action: () -> Unit) { onDismiss(); action() }
    PlanDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Paper).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回记录") }
                Text("设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = PlanPagePadding).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(PlanPageGap),
            ) {
                SettingsProfileCard(nickname, profile) { open(onProfile) }
                SettingsGroup("数据与计划") {
                    SettingsRow(Icons.Outlined.FolderOpen, "数据与存档", "备份、导入与管理命名存档") { open(onData) }
                    SettingsDivider()
                    SettingsRow(Icons.Outlined.Inventory2, "计划与归档", "查看任务安排与归档计划") { open(onPlans) }
                    SettingsDivider()
                    SettingsRow(Icons.Outlined.Category, "分类管理", "建立自己的分类、颜色与图标") { open(onCategories) }
                }
                SettingsGroup("提醒与外观") {
                    SettingsRow(Icons.Outlined.NotificationsActive, "专注提醒", "完成提醒、声音与振动") { open(onReminders) }
                    SettingsDivider()
                    SettingsRow(Icons.Outlined.Alarm, "计划提醒", "提醒权限与系统设置") { open(onPlanReminders) }
                    SettingsDivider()
                    SettingsRow(Icons.Outlined.Palette, "外观", "主题风格、强调色与明暗模式") { open(onAppearance) }
                    SettingsDivider()
                    SettingsRow(Icons.Outlined.DashboardCustomize, "首页布局", "显示顺序、显示密度与默认页面") { open(onHomeCustomization) }
                }
                SettingsGroup("关于 plan") {
                    SettingsRow(Icons.Outlined.Info, "使用说明", "功能介绍与使用方法") { open(onAbout) }
                }
                Text("卸载或更换设备前，请先导出备份。", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp))
        Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor), content = content)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Line.copy(alpha = .65f))
}

@Composable
private fun SettingsProfileCard(nickname: String, profile: PersonalProfile, onEdit: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor).padding(PlanCardPadding)) {
        val stack = LocalDensity.current.fontScale >= 1.5f || maxWidth < 280.dp
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(profile, Modifier.size(44.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp, end = if (stack) 0.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(nickname, style = MaterialTheme.typography.titleMedium)
                    Text("头像、昵称与首页寄语", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                if (!stack) {
                    PlanSecondaryButton(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("编辑")
                    }
                }
            }
            if (stack) {
                PlanSecondaryButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("编辑个人资料")
                }
            }
        }
    }
}
