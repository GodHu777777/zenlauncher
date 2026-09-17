package com.zenlauncher.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.github.promeg.pinyinhelper.Pinyin
import com.zenlauncher.app.model.AppInfo
import java.net.URLEncoder

object PinyinSearchEngine {

    fun enrich(app: AppInfo): AppInfo {
        val full = StringBuilder()
        val short = StringBuilder()

        for (c in app.appName) {
            if (Pinyin.isChinese(c)) {
                val p = Pinyin.toPinyin(c)
                full.append(p)
                if (p.isNotEmpty()) {
                    short.append(p[0])
                }
            } else {
                full.append(c)
                short.append(c)
            }
        }

        app.pinyin = full.toString().lowercase()
        app.pinyinShort = short.toString().lowercase()
        return app
    }

    fun search(allApps: List<AppInfo>, query: String): List<AppInfo> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        data class Scored(val app: AppInfo, val score: Int)
        val matches = ArrayList<Scored>()

        for (app in allApps) {
            val name = app.appName.lowercase()
            val py = app.pinyin
            val pyShort = app.pinyinShort

            var score = 0
            when {
                name == q -> score = 100
                name.startsWith(q) -> score = 85
                pyShort == q -> score = 80
                pyShort.startsWith(q) -> score = 75
                py.startsWith(q) -> score = 70
                name.contains(q) -> score = 50
                py.contains(q) -> score = 40
                pyShort.contains(q) -> score = 30
                app.packageName.lowercase().contains(q) -> score = 10
            }

            if (score > 0) {
                matches.add(Scored(app, score))
            }
        }

        matches.sortByDescending { it.score }
        return matches.map { it.app }
    }

    fun openWebSearch(context: Context, query: String, engine: String) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = when (engine.lowercase()) {
            "google" -> "https://www.google.com/search?q=$encoded"
            "bing" -> "https://www.bing.com/search?q=$encoded"
            "duckduckgo" -> "https://duckduckgo.com/?q=$encoded"
            else -> "https://www.baidu.com/s?wd=$encoded"
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
