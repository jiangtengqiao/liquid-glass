package com.liquidglass.app.music

import android.content.Context

/**
 * 搜索历史持久化（SharedPreferences，JSON 数组串存）。
 *
 * - 最近 20 条，新的置顶，去重
 * - [record] 成功搜索后调用
 * - [clear] 一键清空
 */
object SearchHistoryStore {

    private const val PREFS = "music_search_history"
    private const val KEY_HISTORY = "history"
    private const val MAX_SIZE = 20

    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }

    fun record(context: Context, keyword: String) {
        val k = keyword.trim()
        if (k.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = load(context).toMutableList()
        current.remove(k)          // 去重
        current.add(0, k)          // 置顶
        if (current.size > MAX_SIZE) current.subList(MAX_SIZE, current.size).clear()
        prefs.edit().putString(KEY_HISTORY, current.joinToString("\n")).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_HISTORY).apply()
    }
}
