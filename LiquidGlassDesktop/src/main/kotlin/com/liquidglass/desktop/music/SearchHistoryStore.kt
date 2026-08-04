package com.liquidglass.desktop.music

import java.util.prefs.Preferences

/**
 * 搜索历史持久化（桌面版）。
 *
 * 安卓版用 SharedPreferences，桌面版改用 java.util.prefs.Preferences。
 * 存储格式：换行分隔的字符串。
 *
 * - 最近 20 条，新的置顶，去重
 * - [record] 成功搜索后调用
 * - [clear] 一键清空
 */
object SearchHistoryStore {

    private const val KEY_HISTORY = "history"
    private const val MAX_SIZE = 20

    private val prefs: Preferences =
        Preferences.userNodeForPackage(SearchHistoryStore::class.java).node("music_search_history")

    fun load(): List<String> {
        val raw = prefs.get(KEY_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }

    fun record(keyword: String) {
        val k = keyword.trim()
        if (k.isBlank()) return
        val current = load().toMutableList()
        current.remove(k)          // 去重
        current.add(0, k)          // 置顶
        if (current.size > MAX_SIZE) current.subList(MAX_SIZE, current.size).clear()
        prefs.put(KEY_HISTORY, current.joinToString("\n"))
        prefs.flush()
    }

    fun clear() {
        prefs.remove(KEY_HISTORY)
        prefs.flush()
    }
}
