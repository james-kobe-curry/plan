package com.tongpin.app

import android.content.Context

class HomePreferencesStore(context: Context) {
    val prefs = context.getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
    fun load(): HomePreferences = readHomePreferences(
        runCatching { prefs.getString(HOME_ORDER_KEY, null) }.getOrNull(),
        runCatching { if (prefs.contains(HOME_SHOW_PROGRESS_KEY)) prefs.getBoolean(HOME_SHOW_PROGRESS_KEY, true) else null }.getOrNull(),
        runCatching { if (prefs.contains(HOME_SHOW_RECENT_KEY)) prefs.getBoolean(HOME_SHOW_RECENT_KEY, false) else null }.getOrNull(),
        runCatching { prefs.getString(HOME_DENSITY_KEY, null) }.getOrNull(),
        runCatching { if (prefs.contains(START_PAGE_KEY)) prefs.getInt(START_PAGE_KEY, 0) else null }.getOrNull(),
    )

    fun save(value: HomePreferences) {
        validateHomePreferences(value)
        val previous = load()
        if (!write(value)) {
            write(previous)
            error("首页设置未能保存，请重试")
        }
    }

    private fun write(value: HomePreferences) = prefs.edit()
        .putString(HOME_ORDER_KEY, value.order.joinToString(",") { it.name })
        .putBoolean(HOME_SHOW_PROGRESS_KEY, value.showProgress)
        .putBoolean(HOME_SHOW_RECENT_KEY, value.showRecent)
        .putString(HOME_DENSITY_KEY, value.density.name)
        .putInt(START_PAGE_KEY, value.startPage).commit()
}
