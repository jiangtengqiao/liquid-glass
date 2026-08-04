package com.liquidglass.desktop.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 网易云登录（桌面版，从安卓端移植）。
 *
 * 桌面版改动：移除 Context 参数，SessionStore 直接调用（Preferences 全局单例）。
 *
 * 流程：
 *  1. createQrKey() → unikey（POST /weapi/login/qrcode/unikey）
 *  2. 用 unikey 拼成二维码内容 "https://music.163.com/login?codekey={unikey}"，由 UI 渲染成二维码
 *  3. pollQrStatus(key) 每 2s 轮询一次（POST /weapi/login/qrcode/client/login）
 *     - 801 等待扫码  802 已扫码待确认  803 确认登录(cookie 已由 CookieJar 落库)
 *     - 800 过期需重新生成
 *  4. fetchAccount() 用新 cookie 拉账户信息(nickname/avatar/vipType) 并存 SessionStore
 */
object NetEaseAuth {

    private val logger = Logger.getLogger("NetEaseAuth")

    /** 生成二维码 key；失败返回 null */
    suspend fun createQrKey(): String? = withContext(Dispatchers.IO) {
        try {
            val json = NetEaseApiClient.weapiPost(
                "/weapi/login/qrcode/unikey",
                """{"type":1,"noCheck":true}"""
            )
            json.optString("unikey").ifBlank { null }
        } catch (_: Exception) { null }
    }

    /** 二维码内容串（UI 据此渲染二维码图片） */
    fun qrContent(unikey: String): String = "${NetEaseApiClient.BASE}/login?codekey=$unikey"

    /** 轮询扫码状态 */
    suspend fun pollQrStatus(unikey: String): QrLoginResult = withContext(Dispatchers.IO) {
        try {
            val json = NetEaseApiClient.weapiPost(
                "/weapi/login/qrcode/client/login",
                """{"key":"$unikey","type":1}"""
            )
            val code = json.optInt("code", -1)
            when (code) {
                801 -> QrLoginResult(QrLoginState.WAITING)
                802 -> QrLoginResult(QrLoginState.SCANNED)
                803 -> QrLoginResult(QrLoginState.CONFIRMED)
                800 -> QrLoginResult(QrLoginState.EXPIRED)
                else -> QrLoginResult(QrLoginState.ERROR)
            }
        } catch (_: Exception) {
            QrLoginResult(QrLoginState.ERROR)
        }
    }

    /**
     * 登录成功后拉账户信息并持久化。
     *
     * 即便接口返回的 account/profile 为 null（网络抖动或接口限流），
     * 只要 MUSIC_U cookie 已落库，就判定为登录成功并存最小用户信息。
     */
    suspend fun fetchAccount(): UserAccount? = withContext(Dispatchers.IO) {
        // 优先调接口拿完整账户信息
        try {
            val json = NetEaseApiClient.weapiPost(
                "/weapi/w/nuser/account/get",
                """{}"""
            )
            val account = json.optJSONObject("account")
            val profile = json.optJSONObject("profile")
            if (account != null && profile != null) {
                val userId = account.optLong("id", 0L)
                val nickname = profile.optString("nickname", "")
                val avatar = profile.optString("avatarUrl", "")
                val vipType = account.optInt("vipType", 0)
                if (userId > 0L && nickname.isNotBlank()) {
                    SessionStore.saveUser(userId, nickname, avatar, vipType)
                    logger.log(Level.INFO, "fetchAccount: userId=$userId, nickname=$nickname")
                    return@withContext UserAccount(userId, nickname, avatar, vipType)
                }
            }
            logger.log(Level.WARNING, "fetchAccount: account/profile is null, json keys=${json.keySet()}")
        } catch (e: Exception) {
            logger.log(Level.WARNING, "fetchAccount weapi call failed: ${e.message}")
        }

        // 兜底：MUSIC_U cookie 已落库 → 登录确实成功
        if (NetEaseApiClient.hasLoginCookie()) {
            val existingUid = SessionStore.getUserId()
            if (existingUid > 0L) {
                // 已有有效的 userId，不覆盖
                val nickname = SessionStore.getNickname()
                val avatar = SessionStore.getAvatarUrl()
                val vipType = SessionStore.getVipType()
                logger.log(Level.INFO, "fetchAccount fallback: preserving existing userId=$existingUid")
                return@withContext UserAccount(existingUid, nickname, avatar, vipType)
            }
            // 确实没有 userId，存最小信息
            logger.log(Level.WARNING, "fetchAccount fallback: no existing userId, saving minimal info")
            SessionStore.saveUser(0L, "网易云用户", "", 0)
            return@withContext UserAccount(0L, "网易云用户", "", 0)
        }
        logger.log(Level.WARNING, "fetchAccount: no MUSIC_U cookie, returning null")
        null
    }

    /** 退出登录：清本地态 */
    fun logout() {
        SessionStore.logout()
    }

    // ======================== 手机验证码登录 ========================

    /**
     * 发送短信验证码。
     *
     * @param cellphone 手机号（11 位）
     * @param ctcode 国家码，默认 "86"（中国大陆）
     * @return CODE_SENT 发送成功；SMS_FAILED 发送失败（带原因）
     */
    suspend fun sendSmsCode(cellphone: String, ctcode: String = "86"): PhoneLoginResult =
        withContext(Dispatchers.IO) {
            if (cellphone.length != 11 || !cellphone.all { it.isDigit() }) {
                return@withContext PhoneLoginResult(PhoneLoginState.SMS_FAILED, "手机号格式不正确，需为11位数字")
            }
            try {
                val payload = """{"cellphone":"$cellphone","ctcode":"$ctcode"}"""
                val json = NetEaseApiClient.weapiPost("/weapi/sms/captcha/sent", payload)
                val code = json.optInt("code", -1)
                when (code) {
                    200 -> PhoneLoginResult(PhoneLoginState.CODE_SENT, "验证码已发送，5分钟内有效")
                    400 -> PhoneLoginResult(PhoneLoginState.SMS_FAILED, "手机号格式错误")
                    501 -> PhoneLoginResult(PhoneLoginState.SMS_FAILED, "该手机号尚未绑定网易云账号")
                    404, 506 -> PhoneLoginResult(PhoneLoginState.SMS_FAILED, "发送过于频繁，请稍后再试")
                    502 -> PhoneLoginResult(PhoneLoginState.SMS_FAILED, "该手机号未绑定账号")
                    else -> {
                        val msg = json.optString("message", json.optString("msg", ""))
                        PhoneLoginResult(PhoneLoginState.SMS_FAILED, if (msg.isNotBlank()) msg else "发送失败(code=$code)")
                    }
                }
            } catch (e: Exception) {
                PhoneLoginResult(PhoneLoginState.ERROR, "网络异常：${e.message ?: "未知错误"}")
            }
        }

    /**
     * 用手机号 + 验证码登录。
     *
     * 登录接口字段名是 phone + countrycode（不是 cellphone + ctcode）。
     *
     * @param cellphone 手机号
     * @param captcha 4-6 位短信验证码
     * @param ctcode 国家码
     * @return SUCCESS 登录成功；LOGIN_FAILED 验证码错误；其他失败状态
     */
    suspend fun loginWithPhone(
        cellphone: String,
        captcha: String,
        ctcode: String = "86"
    ): PhoneLoginResult = withContext(Dispatchers.IO) {
        if (cellphone.length != 11 || !cellphone.all { it.isDigit() }) {
            return@withContext PhoneLoginResult(PhoneLoginState.LOGIN_FAILED, "手机号格式不正确")
        }
        if (captcha.length < 4 || captcha.length > 6 || !captcha.all { it.isDigit() }) {
            return@withContext PhoneLoginResult(PhoneLoginState.LOGIN_FAILED, "验证码应为4-6位数字")
        }
        try {
            val payload = """{"phone":"$cellphone","countrycode":"$ctcode","captcha":"$captcha","rememberLogin":"true"}"""
            val json = NetEaseApiClient.weapiPost("/weapi/login/cellphone", payload)
            val code = json.optInt("code", -1)
            if (code != 200) {
                val msg = json.optString("message", json.optString("msg", ""))
                return@withContext when (code) {
                    503 -> PhoneLoginResult(PhoneLoginState.LOGIN_FAILED, "验证码错误或已过期")
                    502 -> PhoneLoginResult(PhoneLoginState.LOGIN_FAILED, if (msg.isNotBlank()) msg else "账号或验证码错误")
                    501 -> PhoneLoginResult(PhoneLoginState.LOGIN_FAILED, "该手机号尚未绑定网易云账号")
                    505 -> PhoneLoginResult(PhoneLoginState.LOGIN_FAILED, "参数错误：手机号或验证码格式不对")
                    400 -> PhoneLoginResult(PhoneLoginState.LOGIN_FAILED, "请求参数错误")
                    else -> PhoneLoginResult(PhoneLoginState.LOGIN_FAILED, if (msg.isNotBlank()) msg else "登录失败(code=$code)")
                }
            }
            // 登录成功：cookie 已由 CookieJar 自动落库
            val account = json.optJSONObject("account")
            val profile = json.optJSONObject("profile")
            if (account != null && profile != null) {
                val userId = account.optLong("id", 0L)
                val nickname = profile.optString("nickname", "")
                val avatar = profile.optString("avatarUrl", "")
                val vipType = account.optInt("vipType", 0)
                SessionStore.saveUser(userId, nickname, avatar, vipType)
                SessionStore.saveBoundPhone(cellphone)
            } else {
                val fetched = fetchAccount()
                if (fetched == null) {
                    return@withContext PhoneLoginResult(PhoneLoginState.ERROR, "登录成功但拉取账户信息失败")
                }
                SessionStore.saveBoundPhone(cellphone)
            }
            if (!NetEaseApiClient.hasLoginCookie()) {
                return@withContext PhoneLoginResult(
                    PhoneLoginState.ERROR,
                    "登录成功但 MUSIC_U cookie 未落库，请重试或改用扫码登录"
                )
            }
            fetchAccount()
            PhoneLoginResult(PhoneLoginState.SUCCESS, "登录成功")
        } catch (e: Exception) {
            PhoneLoginResult(PhoneLoginState.ERROR, "网络异常：${e.message ?: "未知错误"}")
        }
    }
}
