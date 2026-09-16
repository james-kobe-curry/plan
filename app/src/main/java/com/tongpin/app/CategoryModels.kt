package com.tongpin.app

import java.util.Base64
import java.util.UUID

enum class PlanIcon(val label: String) {
    BOOK("书本"), EDIT("书写"), LANGUAGE("语言"), SCIENCE("科学"), SCHOOL("学习"),
    FITNESS("力量"), RUN("跑步"), WALK("步行"), MUSIC("音乐"), LEAF("生活"), STAR("目标"), COFFEE("日常")
}
enum class CategoryTint(val label: String) {
    SAGE("鼠尾草"), BLUE("雾蓝"), LAVENDER("浅紫"), ROSE("柔粉"), SAND("暖沙"), SLATE("灰蓝")
}
enum class AvatarIcon(val label: String) {
    SPARK("星光"), LEAF("新叶"), SUN("暖阳"), MOON("月亮"), BOOK("书页"), MOUNTAIN("远山")
}
data class CustomCategory(
    val id: String = UUID.randomUUID().toString(), val name: String,
    val iconId: String = PlanIcon.BOOK.name, val colorKey: String = CategoryTint.SAGE.name,
)
data class PersonalProfile(
    val motto: String = "", val avatarId: String = AvatarIcon.SPARK.name,
    val avatarImage: String? = null, val showOnHome: Boolean = true,
)

object PersonalizationRules {
    const val MAX_CATEGORIES = 20
    const val MAX_CATEGORY_NAME = 16
    const val MAX_MOTTO = 80
    const val MAX_AVATAR_BASE64 = 65_536
    const val MAX_AVATAR_BYTES = 49_152

    fun category(category: CustomCategory) {
        DomainValidation.id(category.id)
        DomainValidation.text(category.name, "分类名称", MAX_CATEGORY_NAME)
        require(category.name == category.name.trim()) { "分类名称前后不能有空格" }
        require(Category.entries.none { builtinCategoryName(it).equals(category.name, ignoreCase = true) }) { "这个名称已用于基础分类，请换一个名称" }
        require(PlanIcon.entries.any { it.name == category.iconId }) { "分类图标无效" }
        require(CategoryTint.entries.any { it.name == category.colorKey }) { "分类颜色无效" }
    }

    fun profile(profile: PersonalProfile) {
        DomainValidation.text(profile.motto, "首页寄语", MAX_MOTTO, allowEmpty = true, multiline = true)
        require(AvatarIcon.entries.any { it.name == profile.avatarId }) { "头像样式无效" }
        profile.avatarImage?.let { encoded ->
            require(encoded.length in 1..MAX_AVATAR_BASE64) { "头像图片过大" }
            val bytes = try { Base64.getDecoder().decode(encoded) }
                catch (_: IllegalArgumentException) { throw IllegalArgumentException("头像图片编码无效") }
            require(bytes.size in 12..MAX_AVATAR_BYTES && Base64.getEncoder().encodeToString(bytes) == encoded) { "头像图片编码或大小无效" }
            val png = bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte)
            val jpeg = bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() &&
                bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte()
            require(png || jpeg) { "头像仅支持 PNG 或 JPEG 图片" }
        }
    }
}

fun saveCustomCategory(data: AppData, category: CustomCategory): AppData {
    PersonalizationRules.category(category)
    require(data.categories.none { it.id != category.id && it.name.equals(category.name, ignoreCase = true) }) { "已有同名分类，请换一个名称" }
    val existing = data.categories.any { it.id == category.id }
    require(existing || data.categories.size < PersonalizationRules.MAX_CATEGORIES) { "最多添加 ${PersonalizationRules.MAX_CATEGORIES} 个自定义分类" }
    return data.copy(categories = if (existing) data.categories.map { if (it.id == category.id) category else it } else data.categories + category)
        .also(DomainValidation::data)
}

/** Deleting a label never deletes a task, an archived version, or a record. */
fun deleteCustomCategory(data: AppData, id: String): AppData = data.copy(
    categories = data.categories.filterNot { it.id == id },
    plans = data.plans.map { if (it.customCategoryId == id) it.copy(customCategoryId = null) else it },
).also(DomainValidation::data)

fun planCategoryKey(plan: Plan): String = plan.customCategoryId?.let { "custom:$it" } ?: "builtin:${plan.category.name}"
fun categoryFilterOptions(data: AppData): List<Pair<String, String>> =
    Category.entries.map { "builtin:${it.name}" to builtinCategoryName(it) } + data.categories.map { "custom:${it.id}" to it.name }
fun builtinCategoryName(category: Category): String = when (category) { Category.STUDY -> "学习"; Category.FITNESS -> "健身"; Category.LIFE -> "日常" }
fun planCategoryName(plan: Plan, data: AppData): String = data.categories.firstOrNull { it.id == plan.customCategoryId }?.name ?: builtinCategoryName(plan.category)
fun planIconId(plan: Plan, data: AppData): String = plan.iconId ?: data.categories.firstOrNull { it.id == plan.customCategoryId }?.iconId
    ?: when (plan.category) { Category.STUDY -> PlanIcon.BOOK.name; Category.FITNESS -> PlanIcon.FITNESS.name; Category.LIFE -> PlanIcon.LEAF.name }
