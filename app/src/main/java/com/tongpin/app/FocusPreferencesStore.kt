package com.tongpin.app

import android.content.Context
import android.content.SharedPreferences

class FocusPreferencesStore(context: Context) {
    internal val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)

    fun load(): FocusPreferences {
        val values = prefs.all
        return readFocusPreferences(values[FAVORITES_KEY] as? String, values[LAST_MINUTES_KEY] as? Int)
    }

    /** Both settings publish together, including on complete-backup restoration. */
    fun save(value: FocusPreferences) {
        val favorites = encodeFocusFavorites(value.favorites)
        val previous = load()
        if (!prefs.edit().putString(FAVORITES_KEY, favorites).putInt(LAST_MINUTES_KEY, value.lastMinutes).commit()) {
            prefs.edit().putString(FAVORITES_KEY, encodeFocusFavorites(previous.favorites))
                .putInt(LAST_MINUTES_KEY, previous.lastMinutes).commit()
            error("专注偏好未能保存，请重试")
        }
    }

    companion object {
        const val FAVORITES_KEY = "focus_favorites"
        const val LAST_MINUTES_KEY = "focus_last_minutes"
    }
}
