package com.liquidglass.app.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 待办清单持久化（SharedPreferences + JSON）。
 *
 * 修复前：[TodoScreen] 的 todos 仅存内存态，退出即丢。
 * 现在：增删改后调用 [save]，进入页面调用 [load] 恢复。
 */
object TodoStore {

    private const val PREFS = "todo_store"
    private const val KEY_LIST = "todos"

    fun load(context: Context): List<TodoItem> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<TodoItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    TodoItem(
                        id = o.optLong("id", System.currentTimeMillis() + i),
                        text = o.optString("text"),
                        isCompleted = o.optBoolean("done", false)
                    )
                )
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    fun save(context: Context, todos: List<TodoItem>) {
        val arr = JSONArray()
        todos.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("text", t.text)
                put("done", t.isCompleted)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIST, arr.toString()).apply()
    }
}
