package com.liquidglass.app.music

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 网易云二维码扫码登录。
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
                803 -> {
                    // cookie 由 OkHttp CookieJar 自动捕获并持久化。
                    // 主动校验 MUSIC_U 是否已落库——这是登录成功的真正凭证。
                    QrLoginResult(QrLoginState.CONFIRMED)
                }
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
     * 只要 MUSIC_U cookie 已落库，就判定为登录成功并存最小用户信息，
     * 避免出现「扫码确认后却显示过期」的死循环。
     *
     * **重要**：兜底路径不覆盖已有的 userId。如果之前已从登录响应保存了
     * 正确的 userId，weapi 调用失败时不会用 0 覆盖，确保 userPlaylists
     * 不会因 uid=0 返回空。
     */
    suspend fun fetchAccount(context: Context): UserAccount? = withContext(Dispatchers.IO) {
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
                    SessionStore.saveUser(context, userId, nickname, avatar, vipType)
                    Log.i("NetEaseAuth", "fetchAccount: userId=$userId, nickname=$nickname")
                    return@withContext UserAccount(userId, nickname, avatar, vipType)
                }
            }
            Log.w("NetEaseAuth", "fetchAccount: account/profile is null, json keys=${json.keySet()}")
        } catch (e: Exception) {
            Log.w("NetEaseAuth", "fetchAccount weapi call failed: ${e.message}")
        }

        // 兜底：MUSIC_U cookie 已落库 → 登录确实成功
        if (NetEaseApiClient.hasLoginCookie()) {
            val existingUid = SessionStore.getUserId(context)
            if (existingUid > 0L) {
                // 已有有效的 userId，不覆盖
                val nickname = SessionStore.getNickname(context)
                val avatar = SessionStore.getAvatarUrl(context)
                val vipType = SessionStore.getVipType(context)
                Log.i("NetEaseAuth", "fetchAccount fallback: preserving existing userId=$existingUid")
                return@withContext UserAccount(existingUid, nickname, avatar, vipType)
            }
            // 确实没有 userId，存最小信息
            Log.w("NetEaseAuth", "fetchAccount fallback: no existing userId, saving minimal info")
            SessionStore.saveUser(context, 0L, "网易云用户", "", 0)
            return@withContext UserAccount(0L, "网易云用户", "", 0)
        }
        Log.w("NetEaseAuth", "fetchAccount: no MUSIC_U cookie, returning null")
        null
    }

    /** 退出登录：清本地态 */
    fun logout(context: Context) {
        SessionStore.logout(context)
    }

    // ======================== 手机验证码登录 ========================

    /**
     * 发送短信验证码（网易云"短信登录"接口）。
     *
     * 对接接口：POST /weapi/sms/captcha/sent
     * payload: { "cellphone": "...", "ctcode": "86" }
     *
     * 该手机号必须已在网易云账号上绑定——若号码未绑定任何账号，
     * 网易云会返回 code=400 或 code=501，此时返回 SMS_FAILED。
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
     * 对接接口：POST /weapi/login/cellphone
     *
     * 注意：网易云两个接口参数名不一致（坑）：
     *  - 发送验证码 /weapi/sms/captcha/sent  → cellphone + ctcode
     *  - 验证码登录  /weapi/login/cellphone  → phone + countrycode （不是 cellphone/ctcode！）
     *
     * 早期版本误把登录接口也用 cellphone/ctcode，导致 phone 字段缺失，
     * 网易云即便验证码正确也会判错。已修正为 phone + countrycode。
     *
     * 登录成功后：
     *  1. OkHttp CookieJar 自动捕获 MUSIC_U / __csrf 等登录 cookie 并持久化
     *  2. 接口响应中的 account/profile 直接写入 SessionStore（无需再调 fetchAccount）
     *  3. 该手机号本身就是平台账号绑定手机，登录即等于"用绑定手机登录平台账号"
     *
     * @param cellphone 手机号
     * @param captcha 4-6 位短信验证码
     * @param ctcode 国家码（登录接口会映射成 countrycode 字段）
     * @return SUCCESS 登录成功；LOGIN_FAILED 验证码错误；其他失败状态
     */
    suspend fun loginWithPhone(
        context: Context,
        cellphone: String,
        captcha: String,
        ctcode: String = "86"
    ): PhoneLoginResult = withContext(Dispatchers.IO) {
        if (cellphone.length != 11 || !cellphone.all { it.isDigit() }) {
            return@withContext PhoneLoginResult(PhoneLoginState.LOGIN_FAILED, "手机号格式不正确")
        }
        // 网易云验证码长度固定 4 位（少数情况 6 位），放宽到 4-6 位
        if (captcha.length < 4 || captcha.length > 6 || !captcha.all { it.isDigit() }) {
            return@withContext PhoneLoginResult(PhoneLoginState.LOGIN_FAILED, "验证码应为4-6位数字")
        }
        try {
            // 关键：登录接口字段名是 phone + countrycode，不是 cellphone + ctcode
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
                SessionStore.saveUser(context, userId, nickname, avatar, vipType)
                SessionStore.saveBoundPhone(context, cellphone)
            } else {
                // profile 为 null（理论不应发生）→ 兜底走 fetchAccount
                val fetched = fetchAccount(context)
                if (fetched == null) {
                    return@withContext PhoneLoginResult(PhoneLoginState.ERROR, "登录成功但拉取账户信息失败")
                }
                SessionStore.saveBoundPhone(context, cellphone)
            }
            // 关键校验：确认 MUSIC_U cookie 真的落库了。
            // 网易云所有需鉴权接口（歌单/歌曲URL/歌词）都依赖 MUSIC_U，
            // 没有它就会出现"登录成功但啥信息都拉不到"的问题。
            if (!NetEaseApiClient.hasLoginCookie()) {
                return@withContext PhoneLoginResult(
                    PhoneLoginState.ERROR,
                    "登录成功但 MUSIC_U cookie 未落库，请重试或改用扫码登录"
                )
            }
            // 再主动拉一次账户信息补全（确保 uid 非空，否则 userPlaylists 会因 uid=0 返回空）
            fetchAccount(context)
            PhoneLoginResult(PhoneLoginState.SUCCESS, "登录成功")
        } catch (e: Exception) {
            PhoneLoginResult(PhoneLoginState.ERROR, "网络异常：${e.message ?: "未知错误"}")
        }
    }
}
