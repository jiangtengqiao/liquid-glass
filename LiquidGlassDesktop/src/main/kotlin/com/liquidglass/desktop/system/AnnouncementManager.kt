package com.liquidglass.desktop.system

import com.squareup.okhttp3.OkHttpClient
import com.squareup.okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.concurrent.TimeUnit
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
 * 与 Android 端逻辑一致，区别：
 * - 缓存使用 java.util.prefs.Preferences（Desktop 无 SharedPreferences）
 * - 远程拉取使用 OkHttp
 */
class AnnouncementManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Desktop 端用 Preferences 替代 SharedPreferences */
    private val prefs: Preferences = Preferences.userNodeForPackage(AnnouncementManager::class.java)

    private val _announcements = MutableStateFlow<List<Announcement>>(emptyList())
    val announcements: StateFlow<List<Announcement>> = _announcements.asStateFlow()

    private val _dismissed = MutableStateFlow<Set<String>>(emptySet())
    val dismissed: StateFlow<Set<String>> = _dismissed.asStateFlow()

    init {
        // 启动时加载本地缓存
        loadCache()
        loadDismissed()
    }

    /**
     * 当前可见公告（排除已关闭）
     */
    fun activeAnnouncements(): List<Announcement> =
        _announcements.value.filter { it.id !in _dismissed.value }

    /**
     * 关闭单条公告
     */
    fun dismiss(id: String) {
        val updated = _dismissed.value + id
        _dismissed.value = updated
        prefs.put(KEY_DISMISSED, updated.joinToString(","))
        prefs.flush()
    }

    /**
     * 远程拉取公告列表，失败时回退到本地缓存
     */
    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(REMOTE_URL).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    // 写入缓存
                    prefs.put(KEY_CACHE, body)
                    prefs.flush()
                    parse(body)?.let { _announcements.value = it }
                }
            } catch (_: Exception) {
                // 网络异常时回退本地缓存
                loadCache()
            }
        }
    }

    /** 读取本地缓存并解析 */
    private fun loadCache() {
        val cached = prefs.get(KEY_CACHE, null) ?: return
        parse(cached)?.let { _announcements.value = it }
    }

    /** 读取已关闭的公告 id 集合 */
    private fun loadDismissed() {
        val raw = prefs.get(KEY_DISMISSED, "") ?: ""
        _dismissed.value = raw.split(",").filter { it.isNotEmpty() }.toSet()
    }

    /** 解析远程 JSON：兼容数组与 {announcements:[...]} 两种格式 */
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
