package com.tongpin.app

enum class HomeModule(val label: String) { PROGRESS("今日进度"), TASKS("任务列表"), RECENT("最近积累") }
enum class HomeDensity(val label: String) { COMFORTABLE("详细"), COMPACT("简洁") }

const val HOME_ORDER_KEY = "home_order"
const val HOME_SHOW_PROGRESS_KEY = "home_show_progress"
const val HOME_SHOW_RECENT_KEY = "home_show_recent"
const val HOME_DENSITY_KEY = "home_density"
const val START_PAGE_KEY = "start_page"
val HOME_PREFERENCE_KEYS = setOf(HOME_ORDER_KEY, HOME_SHOW_PROGRESS_KEY, HOME_SHOW_RECENT_KEY, HOME_DENSITY_KEY, START_PAGE_KEY)

data class HomePreferences(
    val order: List<HomeModule> = HomeModule.entries.toList(),
    val showProgress: Boolean = true,
    val showRecent: Boolean = false,
    val density: HomeDensity = HomeDensity.COMFORTABLE,
    val startPage: Int = 0,
) {
    init { validateHomePreferences(this) }
    fun shows(module: HomeModule) = when (module) {
        HomeModule.PROGRESS -> showProgress
        HomeModule.TASKS -> true
        HomeModule.RECENT -> showRecent
    }
    fun move(module: HomeModule, direction: Int): HomePreferences {
        val index = order.indexOf(module)
        val target = index + direction
        if (direction !in listOf(-1, 1) || index < 0 || target !in order.indices) return this
        val next = order.toMutableList()
        next[index] = next[target]
        next[target] = module
        return copy(order = next)
    }
}

fun validateHomePreferences(value: HomePreferences) {
    require(value.order.size == HomeModule.entries.size && value.order.toSet() == HomeModule.entries.toSet()) { "首页模块顺序无效" }
    require(value.startPage in 0..3) { "默认打开页面无效" }
}

fun parseHomeOrder(value: String?): List<HomeModule>? {
    if (value == null || value.length > 64) return null
    val parts = value.split(',')
    val modules = parts.map { part -> HomeModule.entries.find { it.name == part } ?: return null }
    return modules.takeIf { it.size == HomeModule.entries.size && it.toSet() == HomeModule.entries.toSet() }
}

fun readHomePreferences(order: String?, showProgress: Boolean?, showRecent: Boolean?, density: String?, startPage: Int?) = HomePreferences(
    order = parseHomeOrder(order) ?: HomeModule.entries.toList(),
    showProgress = showProgress ?: true,
    showRecent = showRecent ?: false,
    density = HomeDensity.entries.find { it.name == density } ?: HomeDensity.COMFORTABLE,
    startPage = startPage?.takeIf { it in 0..3 } ?: 0,
)

const val MAX_AVATAR_INPUT_BYTES = 10 * 1024 * 1024
const val MAX_AVATAR_STORED_CHARS = 65_536
const val AVATAR_EDGE = 192

/** Check image headers before allocating their pixel buffers. */
fun acceptableAvatarDimensions(width: Int, height: Int, stored: Boolean): Boolean =
    width > 0 && height > 0 && if (stored) width <= 512 && height <= 512
    else width <= 32_768 && height <= 32_768 && width.toLong() * height <= 100_000_000L

/** Keep a bounded recent list even when many years of check-ins have been restored. */
fun homeRecentEntries(entries: List<CheckIn>): List<CheckIn> {
    val recent = ArrayList<CheckIn>(4)
    entries.forEach { entry ->
        if (entry.amount > 0) {
            val before = recent.indexOfFirst { historyCheckInOrder.compare(entry, it) < 0 }
            if (before >= 0) recent.add(before, entry) else if (recent.size < 3) recent.add(entry)
            if (recent.size > 3) recent.removeAt(3)
        }
    }
    return recent
}
