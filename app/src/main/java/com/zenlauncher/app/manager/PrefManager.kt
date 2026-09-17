package com.zenlauncher.app.manager

import android.content.Context
import android.content.SharedPreferences

class PrefManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("zen_launcher_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FAVORITES = "key_favorites"
        private const val KEY_HIDDEN = "key_hidden"
        private const val KEY_DOPAMINE = "key_dopamine"
        private const val KEY_MOTTO = "key_motto"
        private const val KEY_FRICTION_SECONDS = "key_friction_seconds"
        private const val KEY_SEARCH_ENGINE = "key_search_engine"
        private const val KEY_CALENDAR_ENABLED = "key_calendar_enabled"
        private const val KEY_CALENDAR_MAX_COUNT = "key_calendar_max_count"
        private const val KEY_SELECTED_CALENDARS = "key_selected_calendars"
        private const val PREFIX_ALIAS = "alias_"
    }

    fun getFavorites(): Set<String> {
        val set = prefs.getStringSet(KEY_FAVORITES, null)
        if (set == null) {
            // Sensible defaults
            return setOf(
                "com.android.dialer",
                "com.android.chrome",
                "com.tencent.mm",
                "com.android.mms"
            )
        }
        return set
    }

    fun saveFavorites(set: Set<String>) {
        prefs.edit().putStringSet(KEY_FAVORITES, set).apply()
    }

    fun toggleFavorite(id: String) {
        val current = getFavorites().toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        saveFavorites(current)
    }

    fun getHiddenApps(): Set<String> {
        return prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()
    }

    fun saveHiddenApps(set: Set<String>) {
        prefs.edit().putStringSet(KEY_HIDDEN, set).apply()
    }

    fun toggleHidden(id: String) {
        val current = getHiddenApps().toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        saveHiddenApps(current)
    }

    fun getDopamineApps(): Set<String> {
        val set = prefs.getStringSet(KEY_DOPAMINE, null)
        if (set == null) {
            // Default dopamine apps on first run
            return setOf(
                "com.ss.android.ugc.aweme", // 抖音
                "com.xingin.xhs",           // 小红书
                "tv.danmaku.bili",          // B站
                "com.zhihu.android"         // 知乎
            )
        }
        return set
    }

    fun saveDopamineApps(set: Set<String>) {
        prefs.edit().putStringSet(KEY_DOPAMINE, set).apply()
    }

    fun toggleDopamine(id: String) {
        val current = getDopamineApps().toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        saveDopamineApps(current)
    }

    fun getAlias(id: String): String? {
        return prefs.getString(PREFIX_ALIAS + id, null)
    }

    fun setAlias(id: String, alias: String?) {
        val editor = prefs.edit()
        if (alias.isNullOrBlank()) {
            editor.remove(PREFIX_ALIAS + id)
        } else {
            editor.putString(PREFIX_ALIAS + id, alias.trim())
        }
        editor.apply()
    }

    fun getMotto(): String {
        return prefs.getString(KEY_MOTTO, "保持专注，活在当下") ?: "保持专注，活在当下"
    }

    fun setMotto(motto: String) {
        prefs.edit().putString(KEY_MOTTO, motto.trim()).apply()
    }

    fun getFrictionSeconds(): Int {
        return prefs.getInt(KEY_FRICTION_SECONDS, 5)
    }

    fun setFrictionSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_FRICTION_SECONDS, seconds).apply()
    }

    fun getSearchEngine(): String {
        return prefs.getString(KEY_SEARCH_ENGINE, "Baidu") ?: "Baidu"
    }

    fun setSearchEngine(engine: String) {
        prefs.edit().putString(KEY_SEARCH_ENGINE, engine).apply()
    }

    fun isCalendarEnabled(): Boolean {
        return prefs.getBoolean(KEY_CALENDAR_ENABLED, false)
    }

    fun setCalendarEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CALENDAR_ENABLED, enabled).apply()
    }

    fun getCalendarMaxCount(): Int {
        return prefs.getInt(KEY_CALENDAR_MAX_COUNT, 3)
    }

    fun setCalendarMaxCount(count: Int) {
        prefs.edit().putInt(KEY_CALENDAR_MAX_COUNT, count.coerceIn(1, 10)).apply()
    }

    fun getSelectedCalendars(): Set<String> {
        return prefs.getStringSet(KEY_SELECTED_CALENDARS, emptySet()) ?: emptySet()
    }

    fun saveSelectedCalendars(set: Set<String>) {
        prefs.edit().putStringSet(KEY_SELECTED_CALENDARS, set).apply()
    }
}
