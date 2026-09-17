package com.zenlauncher.app.model

data class LauncherSettings(
    val frictionSeconds: Int = 5,
    val frictionPrompt: String = "停顿 5 秒。\n确认这是你真正想做的事，还是下意识的习惯？",
    val customMotto: String = "保持专注，活在当下",
    val defaultSearchEngine: String = "Baidu"
)
