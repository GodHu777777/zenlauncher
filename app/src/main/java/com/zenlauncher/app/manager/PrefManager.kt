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

    fun exportConfigJson(): String {
        val root = org.json.JSONObject()
        root.put("version", 1)
        root.put("timestamp", System.currentTimeMillis())

        // Favorites
        val favArray = org.json.JSONArray()
        getFavorites().forEach { favArray.put(it) }
        root.put("favorites", favArray)

        // Hidden
        val hiddenArray = org.json.JSONArray()
        getHiddenApps().forEach { hiddenArray.put(it) }
        root.put("hidden_apps", hiddenArray)

        // Dopamine
        val dopamineArray = org.json.JSONArray()
        getDopamineApps().forEach { dopamineArray.put(it) }
        root.put("dopamine_apps", dopamineArray)

        // Motto
        root.put("motto", getMotto())

        // Friction
        root.put("friction_seconds", getFrictionSeconds())

        // Search engine
        root.put("search_engine", getSearchEngine())

        // Calendar
        root.put("calendar_enabled", isCalendarEnabled())
        root.put("calendar_max_count", getCalendarMaxCount())
        val calArray = org.json.JSONArray()
        getSelectedCalendars().forEach { calArray.put(it) }
        root.put("selected_calendars", calArray)

        // Aliases
        val aliasObj = org.json.JSONObject()
        for ((key, value) in prefs.all) {
            if (key.startsWith(PREFIX_ALIAS) && value is String) {
                val appId = key.removePrefix(PREFIX_ALIAS)
                aliasObj.put(appId, value)
            }
        }
        root.put("aliases", aliasObj)

        return root.toString(2)
    }

    fun importConfigJson(jsonStr: String): Boolean {
        return try {
            val root = org.json.JSONObject(jsonStr)
            val editor = prefs.edit()

            if (root.has("favorites")) {
                val array = root.getJSONArray("favorites")
                val set = mutableSetOf<String>()
                for (i in 0 until array.length()) set.add(array.getString(i))
                editor.putStringSet(KEY_FAVORITES, set)
            }

            if (root.has("hidden_apps")) {
                val array = root.getJSONArray("hidden_apps")
                val set = mutableSetOf<String>()
                for (i in 0 until array.length()) set.add(array.getString(i))
                editor.putStringSet(KEY_HIDDEN, set)
            }

            if (root.has("dopamine_apps")) {
                val array = root.getJSONArray("dopamine_apps")
                val set = mutableSetOf<String>()
                for (i in 0 until array.length()) set.add(array.getString(i))
                editor.putStringSet(KEY_DOPAMINE, set)
            }

            if (root.has("motto")) {
                editor.putString(KEY_MOTTO, root.getString("motto"))
            }

            if (root.has("friction_seconds")) {
                editor.putInt(KEY_FRICTION_SECONDS, root.getInt("friction_seconds"))
            }

            if (root.has("search_engine")) {
                editor.putString(KEY_SEARCH_ENGINE, root.getString("search_engine"))
            }

            if (root.has("calendar_enabled")) {
                editor.putBoolean(KEY_CALENDAR_ENABLED, root.getBoolean("calendar_enabled"))
            }

            if (root.has("calendar_max_count")) {
                editor.putInt(KEY_CALENDAR_MAX_COUNT, root.getInt("calendar_max_count"))
            }

            if (root.has("selected_calendars")) {
                val array = root.getJSONArray("selected_calendars")
                val set = mutableSetOf<String>()
                for (i in 0 until array.length()) set.add(array.getString(i))
                editor.putStringSet(KEY_SELECTED_CALENDARS, set)
            }

            if (root.has("aliases")) {
                val aliasObj = root.getJSONObject("aliases")
                val keys = aliasObj.keys()
                while (keys.hasNext()) {
                    val appId = keys.next()
                    val alias = aliasObj.getString(appId)
                    editor.putString(PREFIX_ALIAS + appId, alias)
                }
            }

            editor.apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
