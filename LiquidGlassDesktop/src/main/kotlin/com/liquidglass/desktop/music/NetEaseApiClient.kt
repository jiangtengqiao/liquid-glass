package com.liquidglass.desktop.music

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 网易云 HTTP 客户端 + weapi 请求封装（桌面版）。
 *
 * 桌面版改动：
 * - 移除 Android Context 依赖，SessionStore 直接调用（Preferences 全局单例）
 * - android.util.Log → java.util.logging.Logger
 * - 移除 ContextProvider 兜底（桌面端不存在 Activity 时序竞态）
 *
 * - 自带 CookieJar：登录成功后 cookie 写入 SessionStore，后续接口自动带 MUSIC_U
 * - weapiPost：统一加密 + 提交 + 解析 JSON
 */
object NetEaseApiClient {

    const val BASE = "https://music.163.com"

    private val logger = Logger.getLogger("NetEaseApiClient")

    private val cookieStore = ConcurrentHashMap<String, MutableMap<String, Cookie>>()

    /** 读写锁保护 cookieStore 的并发访问 */
    private val cookieLock = ReentrantReadWriteLock()

    /**
     * 线程安全地获取当前所有 cookie 的扁平化 "k=v; k=v" 字符串。
     */
    private fun flattenCookies(): String {
        val merged = linkedMapOf<String, String>()
        cookieLock.read {
            for (hostMap in cookieStore.values) {
                synchronized(hostMap) {
                    for ((name, c) in hostMap) {
                        if (c.value.isNotBlank()) merged[name] = c.value
                    }
                }
            }
        }
        if (merged.isEmpty()) return ""
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieLock.write {
                val hostMap = cookieStore.getOrPut(url.host) { mutableMapOf() }
                synchronized(hostMap) {
                    for (c in cookies) {
                        if (c.name in CLIENT_COOKIE_NAMES) continue
                        if (c.value.isNotBlank()) {
                            hostMap[c.name] = c
                        }
                    }
                }
            }
            // 持久化：用线程安全的方式扁平化所有 cookie
            val flat = flattenCookies()
            if (flat.isNotBlank()) {
                // 只保存包含 MUSIC_U 或 __csrf 的 cookie（有意义的 cookie）
                if (flat.contains("MUSIC_U=") || flat.contains("__csrf=")) {
                    SessionStore.saveCookies(flat)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            // 合并三层：客户端标识（最低）→ 持久化登录态 → 内存（最高），按 name 去重。
            val byName = LinkedHashMap<String, Cookie>()
            // 1) 客户端标识（最低优先级）
            for (c in parseClientCookies()) {
                byName[c.name] = c
            }
            // 2) 持久化登录态（从 Preferences 读取）
            val persisted = SessionStore.getCookies()
            if (persisted.isNotBlank()) {
                persisted.split("; ").forEach { pair ->
                    val idx = pair.indexOf('=')
                    if (idx > 0) {
                        val k = pair.substring(0, idx)
                        val v = pair.substring(idx + 1)
                        if (k.isNotBlank() && v.isNotBlank()) {
                            try {
                                byName[k] = Cookie.Builder()
                                    .name(k).value(v)
                                    .domain("music.163.com")
                                    .path("/")
                                    .build()
                            } catch (_: Exception) { }
                        }
                    }
                }
            }
            // 3) 内存（最高优先级，覆盖同名）
            cookieLock.read {
                cookieStore[url.host]?.let { hostMap ->
                    synchronized(hostMap) {
                        for ((_, c) in hostMap) {
                            byName[c.name] = c
                        }
                    }
                }
            }
            return byName.values.toList()
        }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 网易云 weapi 必备的**纯静态客户端标识** Cookie。
     *
     * 只保留 os/osver/appver/channel/wevt 这 5 个客户端静态声明字段：
     *  - 这是请求被服务端识别为"合法客户端"的关键，缺失触发反爬
     *  - os=pc 表示 PC 网页端，与 Referer/Origin/UA 一致
     */
    private const val CLIENT_COOKIE =
        "os=pc; osver=Microsoft-Windows-10-Build-19045-64.0; appver=2.10.14; channel=netease; wevt=web"

    /** PC 网页端 UA，与 os=pc 一致 */
    private const val CLIENT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"

    /**
     * 模拟的中国 IP 地址，用于 X-Real-IP / X-Forwarded-For 请求头。
     * 网易云对内容类接口需要识别客户端 IP 做地域版权校验。
     */
    private const val CHINA_IP = "122.228.19.64"

    /**
     * 客户端静态标识 cookie 的 name 集合，用于 saveFromResponse 时跳过不存。
     */
    private val CLIENT_COOKIE_NAMES: Set<String> = CLIENT_COOKIE.split("; ")
        .filter { it.contains('=') }
        .map { it.substringBefore('=').trim() }
        .toSet()

    /**
     * 把 [CLIENT_COOKIE] 字符串解析成 OkHttp [Cookie] 列表。
     */
    private fun parseClientCookies(): List<Cookie> {
        val list = mutableListOf<Cookie>()
        CLIENT_COOKIE.split("; ").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = pair.substring(0, idx).trim()
                val v = pair.substring(idx + 1).trim()
                if (k.isNotBlank()) {
                    try {
                        list.add(
                            Cookie.Builder()
                                .name(k).value(v)
                                .domain("music.163.com")
                                .path("/")
                                .build()
                        )
                    } catch (_: Exception) { /* 跳过非法 cookie */ }
                }
            }
        }
        return list
    }

    /**
     * 从当前 cookie（持久化+内存）提取 __csrf 的值。
     * 用于注入 weapi payload 的 csrf_token 字段。
     */
    private fun extractCsrfToken(): String {
        val cookies = SessionStore.getCookies()
        // 也查内存
        val memCookie = cookieStore["music.163.com"]?.get("__csrf")?.value
        if (!memCookie.isNullOrBlank()) return memCookie
        // 查持久化
        cookies.split("; ").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0 && pair.substring(0, idx).trim() == "__csrf") {
                val v = pair.substring(idx + 1).trim()
                if (v.isNotBlank()) return v
            }
        }
        return ""
    }

    /**
     * 发起 weapi 请求。
     *
     * Cookie 由 [cookieJar] 自动管理。
     * csrf_token 自动注入：从 cookie 提取 __csrf 值填入 payload 的 csrf_token 字段。
     * X-Real-IP 模拟中国 IP。
     *
     * @param path 形如 "/weapi/login/qrcode/unikey"
     * @param payload 业务参数（明文 JSON 字符串）
     */
    fun weapiPost(path: String, payload: String): JSONObject {
        // 自动注入 csrf_token
        val csrfToken = extractCsrfToken()
        val finalPayload = if (csrfToken.isNotBlank()) {
            try {
                val json = JSONObject(payload)
                json.put("csrf_token", csrfToken)
                json.toString()
            } catch (_: Exception) { payload }
        } else {
            payload
        }

        val (params, encSecKey) = NetEaseCrypto.encrypt(finalPayload)
        val body = FormBody.Builder()
            .add("params", params)
            .add("encSecKey", encSecKey)
            .build()

        val request = Request.Builder()
            .url(BASE + path)
            .post(body)
            .header("User-Agent", CLIENT_UA)
            .header("Referer", "$BASE/")
            .header("Origin", BASE)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("X-Real-IP", CHINA_IP)
            .header("X-Forwarded-For", CHINA_IP)
            .build()

        val response = client.newCall(request).execute()
        val text = response.body?.string() ?: "{}"
        val code = response.code
        response.close()

        if (code !in 200..299) {
            logger.log(Level.WARNING, "weapiPost $path returned HTTP $code: $text")
        }

        return try {
            val json = JSONObject(text)
            val bizCode = json.optInt("code", 200)
            if (bizCode != 200 && bizCode != 0) {
                logger.log(Level.WARNING, "weapiPost $path biz code=$bizCode, msg=${json.optString("message")}")
            }
            json
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "weapiPost $path failed to parse JSON: ${e.message}")
            JSONObject().apply { put("code", -1); put("message", "Parse error: ${e.message}") }
        }
    }

    /**
     * 检查当前是否已持有登录 cookie（MUSIC_U）。
     */
    fun hasLoginCookie(): Boolean {
        val cookies = SessionStore.getCookies()
        return cookies.contains("MUSIC_U=") && cookies.isNotBlank()
    }
}
