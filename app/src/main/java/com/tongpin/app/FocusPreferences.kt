package com.tongpin.app

const val MAX_FOCUS_FAVORITES = 8
val DEFAULT_FOCUS_FAVORITES = listOf(15, 25, 45, 60)
const val DEFAULT_FOCUS_MINUTES = 25

/** The last explicit choice is separate from durations supplied by a linked time task. */
data class FocusPreferences(
    val favorites: List<Int> = DEFAULT_FOCUS_FAVORITES,
    val lastMinutes: Int = DEFAULT_FOCUS_MINUTES,
) {
    init {
        require(favorites.size in 1..MAX_FOCUS_FAVORITES) { "常用时长需保留 1–$MAX_FOCUS_FAVORITES 项" }
        require(favorites.all { it in 1..MAX_FOCUS_MINUTES }) { "请输入 1–$MAX_FOCUS_MINUTES 的整数分钟" }
        require(favorites.distinct().size == favorites.size) { "常用时长不能重复" }
        require(lastMinutes in 1..MAX_FOCUS_MINUTES) { "请输入 1–$MAX_FOCUS_MINUTES 的整数分钟" }
    }

    fun withFavorite(minutes: Int): FocusPreferences {
        require(minutes in 1..MAX_FOCUS_MINUTES) { "请输入 1–$MAX_FOCUS_MINUTES 的整数分钟" }
        if (minutes in favorites) return this
        require(favorites.size < MAX_FOCUS_FAVORITES) { "最多保存 $MAX_FOCUS_FAVORITES 项常用时长，请先在管理中移除一项" }
        return copy(favorites = (favorites + minutes).sorted())
    }

    fun withoutFavorite(minutes: Int): FocusPreferences {
        if (minutes !in favorites) return this
        require(favorites.size > 1) { "请至少保留一项常用时长" }
        return copy(favorites = favorites.filterNot { it == minutes }.sorted())
    }

    fun withManualMinutes(minutes: Int) = copy(lastMinutes = minutes)
    fun withDefaultFavorites() = copy(favorites = DEFAULT_FOCUS_FAVORITES)
}

/** Strict bounds also protect complete-backup validation from malformed preference strings. */
fun parseFocusFavorites(value: String?): List<Int>? {
    if (value == null || value.length !in 1..(MAX_FOCUS_FAVORITES * 5 - 1)) return null
    val parts = value.split(',')
    if (parts.size !in 1..MAX_FOCUS_FAVORITES) return null
    val minutes = parts.map { part ->
        if (part.isEmpty() || part.any { it !in '0'..'9' }) return null
        parseFocusMinutes(part) ?: return null
    }
    return minutes.takeIf { it.distinct().size == it.size }?.sorted()
}

fun encodeFocusFavorites(favorites: List<Int>): String =
    FocusPreferences(favorites = favorites).favorites.sorted().joinToString(",")

/** Invalid/missing legacy settings fall back independently so a good value is preserved. */
fun readFocusPreferences(favorites: String?, lastMinutes: Int?) = FocusPreferences(
    favorites = parseFocusFavorites(favorites) ?: DEFAULT_FOCUS_FAVORITES,
    lastMinutes = lastMinutes?.takeIf { it in 1..MAX_FOCUS_MINUTES } ?: DEFAULT_FOCUS_MINUTES,
)

/** Selecting a task never changes manual-duration memory; unsupported targets retain guidance. */
fun focusMinutesForPlan(plan: Plan?, preferences: FocusPreferences): PlanFocusDuration {
    val suggested = planFocusDuration(plan)
    return suggested.copy(minutes = suggested.minutes ?: preferences.lastMinutes)
}
