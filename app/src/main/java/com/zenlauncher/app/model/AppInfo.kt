package com.zenlauncher.app.model

import android.os.UserHandle

data class AppInfo(
    var appName: String,
    val originalName: String,
    val packageName: String,
    val activityName: String,
    val isSystemApp: Boolean = false,
    var isFavorite: Boolean = false,
    var isHidden: Boolean = false,
    var isDopamineApp: Boolean = false,
    val isClone: Boolean = false,
    val userHandle: UserHandle? = null,
    val userId: Long = 0L,
    var pinyin: String = "",
    var pinyinShort: String = "",
    val isWebSearchItem: Boolean = false
) {
    val id: String
        get() = if (userId == 0L) packageName else "$packageName#$userId"
}
