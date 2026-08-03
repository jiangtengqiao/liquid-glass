package com.liquidglass.app.music

import android.content.Context
import com.liquidglass.app.ContextProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import android.util.Log
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 网易云 HTTP 客户端 + weapi 请求封装。
 *
 * - 自带 CookieJar：登录成功后 cookie 写入 SessionStore，后续接口自动带 MUSIC_U
 * - weapiPost：统一加密 + 提交 + 解析 JSON
 *
 * 全部在 IO 线程发起，调用方需自行切线程。
 */
object NetEaseApiClient {

    const val BASE = "https://music.163.com"

    @Volatile
    private var appContext: Context? = null

    private val cookieStore = ConcurrentHashMap<String, MutableMap<String, Cookie>>()

    /** 读写锁保护 cookieStore 的并发访问 */
    private val cookieLock = ReentrantReadWriteLock()

    /**
     * 线程安全地获取当前所有 cookie 的扁平化 "k=v; k=v" 字符串。
     * 在 cookieLock.read 锁保护下遍历，避免 ConcurrentModificationException。
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
                        // 只保存非空值的 cookie，避免空值覆盖有效值
                        if (c.value.isNotBlank()) {
                            hostMap[c.name] = c
                        }
                    }
                }
            }
            // 持久化：用线程安全的方式扁平化所有 cookie
            val flat = flattenCookies()
            val ctx = appContext
            if (flat.isNotBlank() && ctx != null) {
                // 只保存包含 MUSIC_U 或 __csrf 的 cookie（有意义的 cookie）
                if (flat.contains("MUSIC_U=") || flat.contains("__csrf=")) {
                    SessionStore.saveCookies(ctx, flat)
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
            val ctx = appContext
            if (ctx != null) {
                // 2) 持久化登录态（从 SharedPreferences 读取）
                val persisted = SessionStore.getCookies(ctx)
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
     * 初始化应用上下文。在 Application/Activity 启动时尽早调用，
     * 但即便没调用，ensureContext() 也会用兜底 Context 自愈，
     * 避免 lateinit 未初始化导致扫码二维码一辈子出不来。
     */
    @Synchronized
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    /**
     * 取已初始化的 context；若未初始化，尝试从 [ContextProvider] 拿全局兜底 context。
     * 这样即便 MusicScreen 的 LaunchedEffect 时序竞态导致 init 晚于 createQrKey，
     * 也不会崩 UninitializedPropertyAccessException。
     */
    private fun ensureContext(): Context {
        appContext?.let { return it }
        // 兜底：从全局 ApplicationProvider 拿（在 MainActivity 中预先注册）
        ContextProvider.appContext?.let { appContext = it; return it }
        throw IllegalStateException("NetEaseApiClient not initialized and no fallback context available")
    }

    /**
     * 网易云 weapi 必备的**纯静态客户端标识** Cookie。
     *
     * 只保留 os/osver/appver/channel/wevt 这 5 个客户端静态声明字段：
     *  - 这是请求被服务端识别为"合法客户端"的关键，缺失触发反爬
     *  - os=pc 表示 PC 网页端，与 Referer/Origin/UA 一致
     *
     * **绝不**把 __csrf / NMTID / __remember_me / _ntes_nuid / _ntes_nnid 等
     * 动态 cookie 放在这里——它们由服务端 Set-Cookie 动态下发，必须由
     * CookieJar.saveFromResponse 捕获并持久化。
     *
     * 之前的严重 bug：把 __csrf=（空值）放进这里，又把 __csrf 加入
     * CLIENT_COOKIE_NAMES 跳过名单，导致登录成功后服务端回写的真实 __csrf
     * token 永远存不下来，所有需鉴权的 weapi 接口都带空 __csrf，被网易云
     * 拒绝服务（返回空 result），表现为"登录后只有账号名，歌单/搜索/歌曲全空"。
     *
     * 注意：这些 cookie 必须通过 CookieJar.loadForRequest 返回，而非手动设置
     * Cookie header——OkHttp BridgeInterceptor 会用 CookieJar 的结果覆盖手动
     * 设置的 Cookie header。
     */
    private const val CLIENT_COOKIE =
        "os=pc; osver=Microsoft-Windows-10-Build-19045-64.0; appver=2.10.14; channel=netease; wevt=web"

    /** PC 网页端 UA，与 os=pc 一致 */
    private const val CLIENT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"

    /**
     * 模拟的中国 IP 地址，用于 X-Real-IP / X-Forwarded-For 请求头。
     * 网易云对内容类接口（歌单/搜索/歌曲URL）需要识别客户端 IP 做地域版权校验，
     * 缺少此头会导致服务器返回空结果（歌单为空、搜索无结果、歌曲URL为空）。
     * 使用浙江省杭州市的电信 IP，与 osver=Windows-10-Build-19045-64.0 一致。
     */
    private const val CHINA_IP = "122.228.19.64"

    /**
     * 客户端静态标识 cookie 的 name 集合，用于 saveFromResponse 时跳过不存。
     * 只含 os/osver/appver/channel/wevt，**不含** __csrf/NMTID 等动态 cookie。
     */
    private val CLIENT_COOKIE_NAMES: Set<String> = CLIENT_COOKIE.split("; ")
        .filter { it.contains('=') }
        .map { it.substringBefore('=').trim() }
        .toSet()

    /**
     * 把 [CLIENT_COOKIE] 字符串解析成 OkHttp [Cookie] 列表。
     * 用于在 [cookieJar].loadForRequest 中合并到返回值，让 OkHttp BridgeInterceptor
     * 自动设置 Cookie header（手动设置会被覆盖，所以必须走 CookieJar）。
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
     * 用于注入 weapi payload 的 csrf_token 字段——网易云要求 payload 的 csrf_token
     * 必须和 cookie 的 __csrf 一致，否则部分接口返回 301 NOT LOGIN。
     */
    private fun extractCsrfToken(): String {
        val ctx = appContext ?: ContextProvider.appContext ?: return ""
        val cookies = SessionStore.getCookies(ctx)
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
     * Cookie 由 [cookieJar] 自动管理：loadForRequest 合并客户端标识 + 登录态，
     * OkHttp BridgeInterceptor 自动设置 Cookie header。
     * **不要手动设置 Cookie header**——会被 CookieJar 的结果覆盖。
     *
     * csrf_token 自动注入：从 cookie 提取 __csrf 值填入 payload 的 csrf_token 字段，
     * 保证两者一致（网易云校验要求）。
     *
     * X-Real-IP 模拟中国 IP：网易云对内容类接口需要识别客户端 IP 做地域版权校验，
     * 缺少此头会导致服务器返回空结果。使用浙江省杭州市电信 IP。
     *
     * @param path 形如 "/weapi/login/qrcode/unikey"
     * @param payload 业务参数（明文 JSON 字符串）
     */
    @Throws(IOException::class)
    fun weapiPost(path: String, payload: String): JSONObject {
        // 自动注入 csrf_token：从 cookie 提取 __csrf，与 payload 的 csrf_token 保持一致
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
        response.close()

        // 即使 HTTP 状态码不是 2xx，也尝试解析 JSON 体
        if (!response.isSuccessful) {
            Log.w("NetEaseApiClient", "weapiPost $path returned HTTP ${response.code}: $text")
        }

        // 解析 JSON 并检查业务 code 字段
        return try {
            val json = JSONObject(text)
            val bizCode = json.optInt("code", 200)
            if (bizCode != 200 && bizCode != 0) {
                Log.w("NetEaseApiClient", "weapiPost $path biz code=$bizCode, msg=${json.optString("message")}")
            }
            json
        } catch (e: Exception) {
            Log.e("NetEaseApiClient", "weapiPost $path failed to parse JSON: ${e.message}")
            JSONObject().apply { put("code", -1); put("message", "Parse error: ${e.message}") }
        }
    }

    /**
     * 检查当前是否已持有登录 cookie（MUSIC_U）。
     * 扫码 803 确认后 cookieJar 会自动落库，此函数用于判断登录是否真正成功。
     */
    fun hasLoginCookie(): Boolean {
        val ctx = appContext ?: ContextProvider.appContext ?: return false
        val cookies = SessionStore.getCookies(ctx)
        return cookies.contains("MUSIC_U=") && cookies.isNotBlank()
    }

    /** 异步版本，便于协程调用 */
    fun weapiPostAsync(path: String, payload: String,
                       onResult: (JSONObject?) -> Unit) {
        val (params, encSecKey) = NetEaseCrypto.encrypt(payload)
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
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onResult(null) }
            override fun onResponse(call: Call, response: Response) {
                val text = response.body?.string() ?: "{}"
                try { onResult(JSONObject(text)) } catch (_: Exception) { onResult(null) }
            }
        })
    }
}
