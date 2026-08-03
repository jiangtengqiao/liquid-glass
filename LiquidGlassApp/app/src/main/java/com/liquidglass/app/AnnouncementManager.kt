package com.liquidglass.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * 公告数据模型。
 *
 * @param id          公告唯一标识
 * @param priority    优先级，取值 high / medium / low
 * @param title       标题
 * @param content     正文
 * @param actionLabel 操作按钮文案，可为空（无操作按钮）
 * @param actionTarget 操作跳转目标（AppRouter 路由名），可为空
 * @param startTime   生效开始时间（ISO-8601 UTC，如 2026-07-31T00:00:00Z）
 * @param endTime     生效结束时间（ISO-8601 UTC）
 * @param dismissible 是否允许用户关闭
 */
data class Announcement(
    val id: String,
    val priority: String,
    val title: String,
    val content: String,
    val actionLabel: String?,
    val actionTarget: String?,
    val startTime: String,
    val endTime: String,
    val dismissible: Boolean
)

/**
 * 公告栏管理器：全局单例。
 *
 * 职责：
 * - 从远程 GitHub JSON 拉取公告；
 * - 本地缓存（SharedPreferences 存原始 JSON 字符串，key="cached_announcements"）；
 * - 提供可观察状态 [announcementsState]，供 Compose UI 观察刷新；
 * - 公告有效期过滤（startTime~endTime）与已关闭公告过滤。
 *
 * 初始化流程：[init] 先同步读本地缓存填充状态（实现秒开），再后台异步刷新远程；
 * 网络失败时回退到缓存，保证弱网下仍有内容可展示。
 */
object AnnouncementManager {

    /** 远程公告 JSON 地址 */
    private const val REMOTE_URL =
        "https://raw.githubusercontent.com/jiangtengqiao/liquid-glass/main/announcements.json"

    private const val PREFS_NAME = "announcement_prefs"
    /** 本地缓存的公告 JSON 字符串 */
    private const val KEY_CACHED = "cached_announcements"
    /** 已关闭的公告 ID（逗号分隔） */
    private const val KEY_DISMISSED = "dismissed_announcements"

    /** 连接超时 15 秒 */
    private const val CONNECT_TIMEOUT_S = 15L
    /** 读取超时 15 秒 */
    private const val READ_TIMEOUT_S = 15L

    /** 可观察的公告列表状态，UI 通过读取此状态响应刷新 */
    var announcementsState = mutableStateOf<List<Announcement>>(emptyList())
        private set

    /** 应用上下文，初始化后持有 */
    @Volatile
    private var appContext: Context? = null

    /** OkHttp 客户端，懒加载以复用连接池 */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 初始化：先读本地缓存填充状态实现秒开，再后台异步拉取远程刷新。
     * 幂等，重复调用安全。
     */
    @Synchronized
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        // 先读缓存，立即填充状态（缓存通常很小，主线程轻量解析）
        val cached = loadCachedAnnouncements()
        if (cached.isNotEmpty()) {
            announcementsState.value = filterActive(cached)
        }
        // 后台异步刷新远程
        Thread {
            try {
                fetchAnnouncements()
            } catch (_: Exception) {
                // 远程刷新失败时静默回退到缓存，不崩溃
            }
        }.start()
    }

    /**
     * 拉取远程公告：OkHttp GET 远程 JSON，解析后过滤有效期内的公告并更新状态。
     * 成功时刷新本地缓存；失败时回退到缓存并尽量填充状态。
     * 该方法为阻塞 IO，调用方需在子线程调用。
     *
     * @return 本次拉取并过滤后的公告列表（失败返回空列表）
     */
    fun fetchAnnouncements(): List<Announcement> {
        val ctx = appContext ?: return emptyList()
        return try {
            val request = Request.Builder()
                .url(REMOTE_URL)
                .get()
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return emptyList()
                }
                val body = response.body?.string().orEmpty()
                if (body.isEmpty()) return emptyList()
                val list = parseAnnouncements(body)
                // 拉取成功后写入本地缓存
                saveCache(ctx, body)
                val active = filterActive(list)
                announcementsState.value = active
                active
            }
        } catch (_: Exception) {
            // 网络失败，回退到缓存：读缓存并填充状态
            val cached = loadCachedAnnouncements()
            if (cached.isNotEmpty()) {
                val active = filterActive(cached)
                announcementsState.value = active
                active
            } else {
                emptyList()
            }
        }
    }

    /**
     * 关闭指定公告：记录其 ID 到已关闭列表，并从当前显示列表移除。
     * 对 dismissible=false 的公告调用无效（不持久化、不移除）。
     */
    fun dismiss(id: String) {
        val current = announcementsState.value
        val target = current.firstOrNull { it.id == id }
        // 仅当公告存在且允许关闭时才处理
        if (target == null || !target.dismissible) return
        addDismissedId(id)
        announcementsState.value = current.filterNot { it.id == id }
    }

    /**
     * 返回当前未关闭且在有效期内的公告列表。
     * 基于 [announcementsState] 再次过滤有效期与已关闭 ID，确保结果始终正确。
     */
    fun activeAnnouncements(): List<Announcement> {
        val dismissed = loadDismissedIds()
        return filterActive(announcementsState.value)
            .filterNot { it.id in dismissed }
    }

    // ── 内部：解析 / 过滤 / 持久化 ───────────────────────────────

    /** 解析 JSON 字符串为公告列表（不做有效期过滤） */
    private fun parseAnnouncements(json: String): List<Announcement> {
        val result = mutableListOf<Announcement>()
        return try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("announcements") ?: return result
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                result.add(
                    Announcement(
                        id = o.optString("id", ""),
                        priority = o.optString("priority", "low"),
                        title = o.optString("title", ""),
                        content = o.optString("content", ""),
                        actionLabel = o.optString("actionLabel", "").ifEmpty { null },
                        actionTarget = o.optString("actionTarget", "").ifEmpty { null },
                        startTime = o.optString("startTime", ""),
                        endTime = o.optString("endTime", ""),
                        dismissible = o.optBoolean("dismissible", true)
                    )
                )
            }
            result
        } catch (_: Exception) {
            result
        }
    }

    /** 过滤当前时间落在 startTime~endTime 范围内的公告 */
    private fun filterActive(list: List<Announcement>): List<Announcement> {
        val now = System.currentTimeMillis()
        return list.filter { a ->
            val start = parseIsoTime(a.startTime)
            val end = parseIsoTime(a.endTime)
            // 缺失时间视为不限制：无开始时间视为已开始，无结束时间视为永久有效
            (start == null || now >= start) && (end == null || now <= end)
        }
    }

    /** 解析 ISO-8601 时间字符串为毫秒时间戳，失败返回 null */
    private fun parseIsoTime(s: String): Long? {
        if (s.isEmpty()) return null
        return try {
            // 形如 2026-07-31T00:00:00Z
            Instant.parse(s).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    // ── SharedPreferences 读写 ───────────────────────────────────

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取并解析本地缓存的公告（不做有效期过滤，返回原始解析结果） */
    private fun loadCachedAnnouncements(): List<Announcement> {
        val ctx = appContext ?: return emptyList()
        val raw = prefs(ctx).getString(KEY_CACHED, null) ?: return emptyList()
        return parseAnnouncements(raw)
    }

    private fun saveCache(ctx: Context, json: String) {
        prefs(ctx).edit().putString(KEY_CACHED, json).apply()
    }

    /** 读取已关闭的公告 ID 集合 */
    private fun loadDismissedIds(): Set<String> {
        val ctx = appContext ?: return emptySet()
        val raw = prefs(ctx).getString(KEY_DISMISSED, "") ?: ""
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /** 追加一个已关闭的公告 ID（去重后以逗号分隔持久化） */
    private fun addDismissedId(id: String) {
        val ctx = appContext ?: return
        val current = loadDismissedIds().toMutableSet()
        if (current.add(id)) {
            prefs(ctx).edit().putString(KEY_DISMISSED, current.joinToString(",")).apply()
        }
    }
}
