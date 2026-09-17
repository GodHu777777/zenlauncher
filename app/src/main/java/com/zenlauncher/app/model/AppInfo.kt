package com.zenlauncher.app.model

data class AppInfo(
    var appName: String,
    val originalName: String,
    val packageName: String,
    val activityName: String,
    val isSystemApp: Boolean = false,
    var isFavorite: Boolean = false,
    var isHidden: Boolean = false,
    var isDopamineApp: Boolean = false,
    var pinyin: String = "",
    var pinyinShort: String = "",
    val isWebSearchItem: Boolean = false
)
