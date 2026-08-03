package com.liquidglass.desktop.system

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.prefs.Preferences

/**
 * 公告优先级
 */
enum class AnnouncementPriority(val label: String) {
    High("重要"),
    Medium("提醒"),
    Low("公告")
}

/**
 * 单条公告数据
 */
data class Announcement(
    val id: String,
    val content: String,
    val priority: AnnouncementPriority
)

/**
 * 公告管理器（Desktop 版本）
 *
 * 使用 Java 原生 HttpURLConnection 拉取远程公告，避免引入 OkHttp 依赖。
 * 缓存使用 java.util.prefs.Preferences（Desktop 无 SharedPreferences）。
 */
class AnnouncementManager {

    private val prefs: Preferences = Preferences.userNodeForPackage(AnnouncementManager::class.java)

    private val _announcements = MutableStateFlow<List<Announcement>>(emptyList())
    val announcements: StateFlow<List<Announcement>> = _announcements.asStateFlow()

    private val _dismissed = MutableStateFlow<Set<String>>(emptySet())
    val dismissed: StateFlow<Set<String>> = _dismissed.asStateFlow()

    init {
        loadCache()
        loadDismissed()
    }

    fun activeAnnouncements(): List<Announcement> =
        _announcements.value.filter { it.id !in _dismissed.value }

    fun dismiss(id: String) {
        val updated = _dismissed.value + id
        _dismissed.value = updated
        prefs.put(KEY_DISMISSED, updated.joinToString(","))
        prefs.flush()
    }

    /**
     * 远程拉取公告列表，失败时回退到本地缓存。
     */
    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(REMOTE_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    useCaches = false
                }
                if (conn.responseCode != 200) return@withContext
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                prefs.put(KEY_CACHE, body)
                prefs.flush()
                parse(body)?.let { parsed -> _announcements.value = parsed }
            } catch (_: Exception) {
                loadCache()
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun loadCache() {
        val cached = prefs.get(KEY_CACHE, null) ?: return
        parse(cached)?.let { _announcements.value = it }
    }

    private fun loadDismissed() {
        val raw = prefs.get(KEY_DISMISSED, "") ?: ""
        _dismissed.value = raw.split(",").filter { it.isNotEmpty() }.toSet()
    }

    private fun parse(json: String): List<Announcement>? = try {
        val trimmed = json.trim()
        val arr: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(json)
            trimmed.startsWith("{") -> {
                val obj = org.json.JSONObject(json)
                obj.optJSONArray("announcements") ?: JSONArray()
            }
            else -> JSONArray()
        }
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Announcement(
                id = o.optString("id"),
                content = o.optString("content"),
                priority = when (o.optString("priority").lowercase()) {
                    "high" -> AnnouncementPriority.High
                    "medium" -> AnnouncementPriority.Medium
                    else -> AnnouncementPriority.Low
                }
            )
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val REMOTE_URL =
            "https://raw.githubusercontent.com/jiangtengqiao/liquid-glass/main/announcements.json"
        private const val KEY_CACHE = "announcements_cache"
        private const val KEY_DISMISSED = "dismissed_ids"
    }
}
