package com.liquidglass.desktop.music

import java.util.prefs.Preferences

/**
 * 网易云登录态持久化（桌面版）。
 *
 * 安卓版用 SharedPreferences，桌面版改用 java.util.prefs.Preferences。
 *
 * - cookies：扫码登录成功后由 OkHttp CookieJar 持久化，key 形如 "MUSIC_U=xxx; __csrf=yyy"
 * - userId / nickname / avatarUrl / vipType：账户信息
 *
 * MUSIC_U 是网易云登录态的核心 cookie，几乎全部需鉴权接口都靠它。
 */
object SessionStore {
    private const val KEY_COOKIES = "cookies"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_AVATAR = "avatar_url"
    private const val KEY_VIP_TYPE = "vip_type"
    private const val KEY_LOGGED_IN = "logged_in"
    private const val KEY_BOUND_PHONE = "bound_phone"  // 手机验证码登录后保存的绑定手机号

    /** Preferences 节点：com.liquidglass.desktop.music / netease_session */
    private val prefs: Preferences =
        Preferences.userNodeForPackage(SessionStore::class.java).node("netease_session")

    fun isLoggedIn(): Boolean = prefs.getBoolean(KEY_LOGGED_IN, false)

    fun getCookies(): String = prefs.get(KEY_COOKIES, "") ?: ""

    fun saveCookies(cookies: String) {
        prefs.put(KEY_COOKIES, cookies)
        prefs.flush()
    }

    fun saveUser(
        userId: Long,
        nickname: String,
        avatarUrl: String,
        vipType: Int
    ) {
        prefs.putBoolean(KEY_LOGGED_IN, true)
        prefs.putLong(KEY_USER_ID, userId)
        prefs.put(KEY_NICKNAME, nickname)
        prefs.put(KEY_AVATAR, avatarUrl)
        prefs.putInt(KEY_VIP_TYPE, vipType)
        prefs.flush()
    }

    fun getUserId(): Long = prefs.getLong(KEY_USER_ID, 0L)

    fun getNickname(): String = prefs.get(KEY_NICKNAME, "") ?: ""

    fun getAvatarUrl(): String = prefs.get(KEY_AVATAR, "") ?: ""

    fun getVipType(): Int = prefs.getInt(KEY_VIP_TYPE, 0)

    fun isVip(): Boolean = getVipType() > 0

    /** 保存手机验证码登录时使用的手机号（平台账号绑定的手机号） */
    fun saveBoundPhone(phone: String) {
        prefs.put(KEY_BOUND_PHONE, phone)
        prefs.flush()
    }

    /** 取最近一次手机登录使用的绑定手机号；未做过手机登录返回空串 */
    fun getBoundPhone(): String = prefs.get(KEY_BOUND_PHONE, "") ?: ""

    /**
     * 校验给定手机号是否与平台账号绑定手机号一致。
     * 用户要求的"平台账号相同绑定手机"校验：再次手机登录时若与上次绑定手机不一致，
     * 视为换号操作，可由 UI 提示用户确认。
     */
    fun isBoundPhoneMatched(phone: String): Boolean {
        val bound = getBoundPhone()
        return bound.isBlank() || bound == phone
    }

    fun logout() {
        prefs.clear()
        prefs.flush()
    }
}
