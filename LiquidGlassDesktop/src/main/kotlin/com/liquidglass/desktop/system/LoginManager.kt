package com.liquidglass.desktop.system

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.prefs.Preferences
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 系统级登录管理器（LiquidGlass 平台账号）。
 *
 * 与「网易云音乐登录」解耦——这是平台通用账号，
 * 用于打通翻译会员、Beta 先锋、未来扩展业务。
 *
 * 会员等级：
 * - FREE     免费用户：基础功能，每日翻译 1000 字
 * - PRO      付费会员：无限翻译 + 全部工具
 * - PREMIUM  高级会员：所有功能 + 优先客服 + Beta 优先体验
 *
 * 数据来源：远端 users.json（GitHub 仓库，多镜像源拉取）。
 * 实际生产可换为后端 API，当前为简化的静态文件校验。
 */
object LoginManager {

    private val logger = Logger.getLogger("LoginManager")

    /** 平台账号会员等级 */
    enum class Membership(val label: String) {
        FREE("免费版"),
        PRO("专业版"),
        PREMIUM("高级版")
    }

    /** 当前登录用户信息 */
    data class UserInfo(
        val userId: String,
        val username: String,
        val email: String,
        val membership: Membership,
        val expireAt: Long,    // 会员到期时间戳，0 表示永久或免费版
        val avatarUrl: String
    )

    private const val KEY_USER_ID = "user_id"
    private const val KEY_USERNAME = "username"
    private const val KEY_EMAIL = "email"
    private const val KEY_MEMBERSHIP = "membership"
    private const val KEY_EXPIRE_AT = "expire_at"
    private const val KEY_AVATAR = "avatar_url"
    private const val KEY_LOGGED_IN = "logged_in"
    private const val KEY_TOKEN = "token"  // 简单 token，实际生产应使用 JWT

    private val prefs: Preferences =
        Preferences.userNodeForPackage(LoginManager::class.java).node("liquidglass_account")

    // users.json 多镜像源（GitHub raw + 国内代理）
    private val USERS_JSON_URLS = listOf(
        "https://raw.githubusercontent.com/jiangtengqiao/liquid-glass/main/users.json",
        "https://gh-proxy.com/https://raw.githubusercontent.com/jiangtengqiao/liquid-glass/main/users.json",
        "https://ghfast.top/https://raw.githubusercontent.com/jiangtengqiao/liquid-glass/main/users.json",
        "https://fastly.jsdelivr.net/gh/jiangtengqiao/liquid-glass@main/users.json"
    )

    /** 是否已登录 */
    fun isLoggedIn(): Boolean = prefs.getBoolean(KEY_LOGGED_IN, false)

    /** 取当前用户信息（未登录返回 null） */
    fun currentUser(): UserInfo? {
        if (!isLoggedIn()) return null
        return UserInfo(
            userId = prefs.get(KEY_USER_ID, ""),
            username = prefs.get(KEY_USERNAME, ""),
            email = prefs.get(KEY_EMAIL, ""),
            membership = Membership.valueOf(prefs.get(KEY_MEMBERSHIP, Membership.FREE.name)),
            expireAt = prefs.getLong(KEY_EXPIRE_AT, 0L),
            avatarUrl = prefs.get(KEY_AVATAR, "")
        )
    }

    /**
     * 登录（用户名/邮箱 + 密码）。
     *
     * 实际校验逻辑：拉取远端 users.json，匹配用户名+密码。
     * 这是个简化实现，生产环境应使用 HTTPS + 服务端校验 + bcrypt 密码哈希。
     *
     * @return 成功返回 UserInfo，失败返回错误信息
     */
    suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        if (username.isBlank() || password.isBlank()) {
            return@withContext LoginResult.Error("用户名和密码不能为空")
        }
        try {
            val usersJson = fetchUsersJson() ?: return@withContext LoginResult.Error(
                "无法连接到登录服务，请检查网络后重试"
            )
            val usersArr = usersJson.optJSONArray("users") ?: return@withContext LoginResult.Error(
                "登录服务数据格式错误"
            )
            for (i in 0 until usersArr.length()) {
                val u = usersArr.getJSONObject(i)
                val name = u.optString("username")
                val email = u.optString("email")
                val pwd = u.optString("password")
                // 用户名或邮箱匹配
                if ((name.equals(username, ignoreCase = true) ||
                        email.equals(username, ignoreCase = true)) &&
                    pwd == password) {
                    val info = UserInfo(
                        userId = u.optString("id", name),
                        username = name,
                        email = email,
                        membership = Membership.valueOf(u.optString("membership", Membership.FREE.name)),
                        expireAt = u.optLong("expireAt", 0L),
                        avatarUrl = u.optString("avatarUrl", "")
                    )
                    saveUser(info, token = u.optString("token", "static-${System.currentTimeMillis()}"))
                    return@withContext LoginResult.Success(info)
                }
            }
            LoginResult.Error("用户名或密码错误")
        } catch (e: Exception) {
            logger.log(Level.WARNING, "login failed: ${e.message}")
            LoginResult.Error("登录失败：${e.message ?: "未知错误"}")
        }
    }

    /**
     * 注册新用户（简化版：写入本地，实际生产应提交到服务端）。
     *
     * 当前实现：仅本地创建 FREE 会员账号，不写入远端。
     * 用户可用此账号登录本机，但跨设备不同步。
     */
    suspend fun register(username: String, email: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        if (username.length < 2) return@withContext LoginResult.Error("用户名至少 2 个字符")
        if (!email.contains('@') || !email.contains('.')) return@withContext LoginResult.Error("邮箱格式不正确")
        if (password.length < 6) return@withContext LoginResult.Error("密码至少 6 位")
        // 简化：直接当作 FREE 用户登录（不实际写入远端 users.json）
        val info = UserInfo(
            userId = "local-$username",
            username = username,
            email = email,
            membership = Membership.FREE,
            expireAt = 0L,
            avatarUrl = ""
        )
        saveUser(info, token = "local-${System.currentTimeMillis()}")
        LoginResult.Success(info)
    }

    /** 退出登录 */
    fun logout() {
        prefs.clear()
        prefs.flush()
    }

    private fun saveUser(info: UserInfo, token: String) {
        prefs.putBoolean(KEY_LOGGED_IN, true)
        prefs.put(KEY_USER_ID, info.userId)
        prefs.put(KEY_USERNAME, info.username)
        prefs.put(KEY_EMAIL, info.email)
        prefs.put(KEY_MEMBERSHIP, info.membership.name)
        prefs.putLong(KEY_EXPIRE_AT, info.expireAt)
        prefs.put(KEY_AVATAR, info.avatarUrl)
        prefs.put(KEY_TOKEN, token)
        prefs.flush()
    }

    private fun fetchUsersJson(): JSONObject? {
        val cacheBust = System.currentTimeMillis()
        for (url in USERS_JSON_URLS) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("$url?t=$cacheBust").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6_000
                    readTimeout = 8_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "LiquidGlass-Desktop/2.10.0")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                if (conn.responseCode != 200) {
                    conn.disconnect(); continue
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                return JSONObject(body)
            } catch (e: Exception) {
                conn?.disconnect()
                continue
            }
        }
        return null
    }

    sealed class LoginResult {
        data class Success(val user: UserInfo) : LoginResult()
        data class Error(val message: String) : LoginResult()
    }
}
