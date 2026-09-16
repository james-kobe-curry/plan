package com.tongpin.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun planIcon(id: String): ImageVector = when (PlanIcon.entries.firstOrNull { it.name == id } ?: PlanIcon.BOOK) {
    PlanIcon.BOOK -> Icons.Outlined.MenuBook; PlanIcon.EDIT -> Icons.Outlined.Edit
    PlanIcon.LANGUAGE -> Icons.Outlined.Translate; PlanIcon.SCIENCE -> Icons.Outlined.Science
    PlanIcon.SCHOOL -> Icons.Outlined.School; PlanIcon.FITNESS -> Icons.Outlined.FitnessCenter
    PlanIcon.RUN -> Icons.Outlined.DirectionsRun; PlanIcon.WALK -> Icons.Outlined.DirectionsWalk
    PlanIcon.MUSIC -> Icons.Outlined.MusicNote; PlanIcon.LEAF -> Icons.Outlined.Spa
    PlanIcon.STAR -> Icons.Outlined.StarOutline; PlanIcon.COFFEE -> Icons.Outlined.Coffee
}
fun planCategoryIcon(plan: Plan, data: AppData): ImageVector = planIcon(planIconId(plan, data))

@Composable fun categoryTint(key: String): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return when (CategoryTint.entries.firstOrNull { it.name == key } ?: CategoryTint.SAGE) {
        CategoryTint.SAGE -> if (dark) Color(0xFF263C33) else Color(0xFFE7EFE9)
        CategoryTint.BLUE -> if (dark) Color(0xFF283847) else Color(0xFFE8EEF4)
        CategoryTint.LAVENDER -> if (dark) Color(0xFF373147) else Color(0xFFEFEAF4)
        CategoryTint.ROSE -> if (dark) Color(0xFF443039) else Color(0xFFF4EAED)
        CategoryTint.SAND -> if (dark) Color(0xFF40382B) else Color(0xFFF2EDE3)
        CategoryTint.SLATE -> if (dark) Color(0xFF323A3E) else Color(0xFFE9EEF0)
    }
}
@Composable fun planCategoryColor(plan: Plan, data: AppData): Color =
    data.categories.firstOrNull { it.id == plan.customCategoryId }?.let { categoryTint(it.colorKey) } ?: categoryColor(plan.category)

@OptIn(ExperimentalLayoutApi::class)
@Composable fun PlanIconChoices(selected: String?, onSelect: (String?) -> Unit, allowDefault: Boolean = true) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (allowDefault) FilterChip(selected == null, { onSelect(null) }, label = { Text("跟随分类") })
        PlanIcon.entries.forEach { icon ->
            FilterChip(selected == icon.name, { onSelect(icon.name) }, label = { Text(icon.label) },
                leadingIcon = { Icon(planIcon(icon.name), null, Modifier.size(18.dp)) })
        }
    }
}

@Composable fun CategoryManagerDialog(data: AppData, onDismiss: () -> Unit,
    onChange: suspend ((AppData) -> AppData) -> Unit) {
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<CustomCategory?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CustomCategory?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    FormDialog(title = "管理分类", subtitle = "用自己熟悉的名称，整理不同的日常目标。", error = error,
        onDismiss = { if (!saving) onDismiss() }, confirmLabel = "完成", onConfirm = onDismiss, busy = saving) {
        Text("学习、健身、日常为基础分类，始终保留。", color = Muted, style = MaterialTheme.typography.bodySmall)
        data.categories.forEach { category ->
            Surface(color = SurfaceColor, shape = PlanCardShape, border = PlanCardBorder) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(categoryTint(category.colorKey), PlanSmallShape), contentAlignment = Alignment.Center) {
                        Icon(planIcon(category.iconId), null, tint = Ink, modifier = Modifier.size(21.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(category.name, style = MaterialTheme.typography.titleSmall)
                        val count = data.plans.filter { it.customCategoryId == category.id }.map { it.seriesId }.distinct().size
                        Text("$count 项任务", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton({ editor = category }, enabled = !saving) { Icon(Icons.Outlined.Edit, "编辑分类${category.name}") }
                    IconButton({ deleting = category }, enabled = !saving) { Icon(Icons.Outlined.DeleteOutline, "删除分类${category.name}", tint = Muted) }
                }
            }
        }
        OutlinedButton({ creating = true }, enabled = !saving && data.categories.size < PersonalizationRules.MAX_CATEGORIES,
            modifier = Modifier.fillMaxWidth(), shape = PlanButtonShape) {
            Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("添加分类 · ${data.categories.size}/${PersonalizationRules.MAX_CATEGORIES}")
        }
    }
    if (creating || editor != null) CategoryEditorDialog(editor, { creating = false; editor = null }) { category ->
        onChange { current ->
            require(creating || current.categories.any { it.id == category.id }) { "这个分类已被移除，请重新添加" }
            saveCustomCategory(current, category)
        }
    }
    deleting?.let { category ->
        PlanAlertDialog(onDismissRequest = { if (!saving) deleting = null }, title = { Text("删除「${category.name}」？") },
            text = { Text("关联任务会回到原来的基础分类，进行中、归档任务与全部记录都会保留。") },
            confirmButton = { TextButton({
                saving = true; error = ""
                scope.launch { withContext(NonCancellable) {
                    try { onChange { deleteCustomCategory(it, category.id) }; deleting = null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "分类暂未删除，请重试"; deleting = null }
                    finally { saving = false }
                } }
            }, enabled = !saving) { Text(if (saving) "正在保存…" else "删除分类") } },
            dismissButton = { TextButton({ deleting = null }, enabled = !saving) { Text("取消") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun CategoryEditorDialog(initial: CustomCategory?, onDismiss: () -> Unit,
    onSave: suspend (CustomCategory) -> Unit) {
    val seed = remember { initial ?: CustomCategory(name = "") }
    var name by remember { mutableStateOf(seed.name) }
    var icon by remember { mutableStateOf(seed.iconId) }
    var color by remember { mutableStateOf(seed.colorKey) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    FormDialog(title = if (initial == null) "添加分类" else "编辑分类", subtitle = "选择名称、图标与柔和底色。", error = error,
        onDismiss = { if (!saving) onDismiss() }, confirmLabel = if (saving) "正在保存…" else "保存分类", busy = saving,
        onConfirm = {
            val proposed = seed.copy(name = name.trim(), iconId = icon, colorKey = color)
            try {
                PersonalizationRules.category(proposed)
                saving = true; error = ""
                scope.launch { withContext(NonCancellable) {
                    try { onSave(proposed); onDismiss() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "分类暂未保存，请重试" }
                    finally { saving = false }
                } }
            } catch (failure: IllegalArgumentException) { error = failure.message.orEmpty() }
        }) {
        OutlinedTextField(name, { if (it.length <= PersonalizationRules.MAX_CATEGORY_NAME) { name = it; error = "" } },
            label = { Text("分类名称") }, placeholder = { Text("例如：英语、考研、阅读") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), shape = PlanSmallShape)
        Text("分类图标", style = MaterialTheme.typography.titleSmall)
        PlanIconChoices(icon, { icon = it ?: PlanIcon.BOOK.name }, allowDefault = false)
        Text("分类底色", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CategoryTint.entries.forEach { tint ->
                FilterChip(color == tint.name, { color = tint.name }, label = { Text(tint.label) },
                    leadingIcon = { Box(Modifier.size(18.dp).background(categoryTint(tint.name), PlanSmallShape)) })
            }
        }
    }
}
