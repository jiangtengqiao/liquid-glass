package com.liquidglass.app.music

import android.content.Context

/**
 * 网易云登录态持久化。
 * - cookies：扫码登录成功后由 OkHttp CookieJar 持久化，key 形如 "MUSIC_U=xxx; __csrf=yyy"
 * - userId / nickname / avatarUrl / vipType：账户信息
 *
 * MUSIC_U 是网易云登录态的核心 cookie，几乎全部需鉴权接口都靠它。
 */
object SessionStore {
    private const val PREFS = "netease_session"
    private const val KEY_COOKIES = "cookies"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_AVATAR = "avatar_url"
    private const val KEY_VIP_TYPE = "vip_type"
    private const val KEY_LOGGED_IN = "logged_in"
    private const val KEY_BOUND_PHONE = "bound_phone"  // 手机验证码登录后保存的绑定手机号

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isLoggedIn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOGGED_IN, false)

    fun getCookies(context: Context): String =
        prefs(context).getString(KEY_COOKIES, "") ?: ""

    fun saveCookies(context: Context, cookies: String) {
        prefs(context).edit().putString(KEY_COOKIES, cookies).apply()
    }

    fun saveUser(
        context: Context,
        userId: Long,
        nickname: String,
        avatarUrl: String,
        vipType: Int
    ) {
        prefs(context).edit()
            .putBoolean(KEY_LOGGED_IN, true)
            .putLong(KEY_USER_ID, userId)
            .putString(KEY_NICKNAME, nickname)
            .putString(KEY_AVATAR, avatarUrl)
            .putInt(KEY_VIP_TYPE, vipType)
            .apply()
    }

    fun getUserId(context: Context): Long =
        prefs(context).getLong(KEY_USER_ID, 0L)

    fun getNickname(context: Context): String =
        prefs(context).getString(KEY_NICKNAME, "") ?: ""

    fun getAvatarUrl(context: Context): String =
        prefs(context).getString(KEY_AVATAR, "") ?: ""

    fun getVipType(context: Context): Int =
        prefs(context).getInt(KEY_VIP_TYPE, 0)

    fun isVip(context: Context): Boolean = getVipType(context) > 0

    /** 保存手机验证码登录时使用的手机号（平台账号绑定的手机号） */
    fun saveBoundPhone(context: Context, phone: String) {
        prefs(context).edit().putString(KEY_BOUND_PHONE, phone).apply()
    }

    /** 取最近一次手机登录使用的绑定手机号；未做过手机登录返回空串 */
    fun getBoundPhone(context: Context): String =
        prefs(context).getString(KEY_BOUND_PHONE, "") ?: ""

    /**
     * 校验给定手机号是否与平台账号绑定手机号一致。
     * 用户要求的"平台账号相同绑定手机"校验：再次手机登录时若与上次绑定手机不一致，
     * 视为换号操作，可由 UI 提示用户确认。
     */
    fun isBoundPhoneMatched(context: Context, phone: String): Boolean {
        val bound = getBoundPhone(context)
        return bound.isBlank() || bound == phone
    }

    fun logout(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
