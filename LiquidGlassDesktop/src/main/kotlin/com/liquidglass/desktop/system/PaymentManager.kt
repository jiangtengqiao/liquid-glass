package com.liquidglass.desktop.system

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.prefs.Preferences

/**
 * 支付宝收款码付款管理器
 *
 * 适用场景：开发者未接入支付宝商户认证，无法调用正式支付 API。
 * 因此采用「收款码 + 用户自主回填交易号」的简化信任流程：
 *  1. 应用内展示开发者支付宝收款二维码，用户用支付宝 App 扫码付款
 *  2. 付款完成后用户将支付宝交易号填入应用
 *  3. 应用对交易号做格式校验（宽松），通过后激活对应会员等级
 *  4. 激活态写入与 [LoginManager] 同一 Preferences 节点，
 *     使 [LoginManager.currentUser] 立即反映新会员等级
 *
 * 说明：这是基于信任的简化方案，不与服务端对账，存在被绕过的可能。
 * 正式商用请接入支付宝当面付 / 电脑网站支付并服务端验签。
 */
object PaymentManager {

    /** PRO 专业版年费（元） */
    const val PRO_PRICE_YEAR = 29

    /** PREMIUM 高级版年费（元） */
    const val PREMIUM_PRICE_YEAR = 99

    /** 套餐对应的购买时长（年） */
    const val DURATION_YEARS = 1

    // ---- Preferences 节点（与 LoginManager 完全一致，确保激活态被 currentUser 读取）----
    private val prefs: Preferences =
        Preferences.userNodeForPackage(LoginManager::class.java).node("liquidglass_account")

    // 与 LoginManager 中的 key 保持一致（LoginManager 内部为 private，这里复制字面量）
    private const val KEY_LOGGED_IN = "logged_in"
    private const val KEY_MEMBERSHIP = "membership"
    private const val KEY_EXPIRE_AT = "expire_at"

    // 本模块独占的激活记录 key
    private const val KEY_PAY_LAST_TX = "payment_last_tx"
    private const val KEY_PAY_ACTIVATED_AT = "payment_activated_at"
    private const val KEY_PAY_TIER = "payment_tier"
    private const val KEY_PAY_HISTORY = "payment_history"

    /** 一次激活记录 */
    data class ActivationRecord(
        val tier: LoginManager.Membership,
        val transactionId: String,
        val activatedAt: Long,
        val expireAt: Long
    )

    /**
     * 校验支付宝交易号格式。
     *
     * 支付宝交易号通常为 28 位纯数字；商户订单号则可能含字母。
     * 这里采用宽松校验：
     *  - 去空格后长度 ≥ 20
     *  - 数字占比 ≥ 80%（允许少量字母，兼容商户订单号）
     *  - 仅允许数字与字母
     */
    fun validateTransactionId(txId: String): Boolean {
        val trimmed = txId.trim()
        if (trimmed.length < 20) return false
        if (trimmed.any { !(it.isDigit() || it.isLetter()) }) return false
        val digits = trimmed.count { it.isDigit() }
        return digits.toDouble() / trimmed.length >= 0.8
    }

    /** 是否已登录平台账号（激活前必须先登录） */
    fun isLoggedIn(): Boolean = prefs.getBoolean(KEY_LOGGED_IN, false)

    /**
     * 激活会员。
     *
     * @param tier 目标会员等级（PRO / PREMIUM）
     * @param transactionId 用户填写的支付宝交易号
     * @param durationYears 时长（年），默认 1 年
     * @return true=激活成功；false=未登录或交易号格式不合法
     */
    fun activateMembership(
        tier: LoginManager.Membership,
        transactionId: String,
        durationYears: Int = DURATION_YEARS
    ): Boolean {
        // 免费版没有激活意义
        if (tier == LoginManager.Membership.FREE) return false
        if (!validateTransactionId(transactionId)) return false
        if (!isLoggedIn()) return false

        val now = System.currentTimeMillis()
        // 若当前会员尚未过期，则在原到期时间基础上叠加，避免用户损失剩余时长
        val currentExpire = prefs.getLong(KEY_EXPIRE_AT, 0L)
        val base = if (currentExpire > now) currentExpire else now
        val durationMs = durationYears.toLong() * 365L * 24 * 3600 * 1000L
        val expireAt = base + durationMs

        prefs.put(KEY_MEMBERSHIP, tier.name)
        prefs.putLong(KEY_EXPIRE_AT, expireAt)
        prefs.put(KEY_PAY_LAST_TX, transactionId)
        prefs.putLong(KEY_PAY_ACTIVATED_AT, now)
        prefs.put(KEY_PAY_TIER, tier.name)
        appendHistory(tier, transactionId, now, expireAt)
        prefs.flush()
        return true
    }

    /** 读取最近一次激活记录（无则 null） */
    fun lastActivation(): ActivationRecord? {
        val tierName = prefs.get(KEY_PAY_TIER, null) ?: return null
        val tx = prefs.get(KEY_PAY_LAST_TX, "") ?: return null
        val activatedAt = prefs.getLong(KEY_PAY_ACTIVATED_AT, 0L)
        val expireAt = prefs.getLong(KEY_EXPIRE_AT, 0L)
        val tier = runCatching { LoginManager.Membership.valueOf(tierName) }.getOrNull()
            ?: return null
        return ActivationRecord(tier, tx, activatedAt, expireAt)
    }

    /** 将激活记录追加到历史（最多保留最近 20 条，换行分隔） */
    private fun appendHistory(
        tier: LoginManager.Membership,
        tx: String,
        activatedAt: Long,
        expireAt: Long
    ) {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val entry = "${df.format(Date(activatedAt))} | ${tier.name} | $tx | 到期 ${df.format(Date(expireAt))}"
        val existing = prefs.get(KEY_PAY_HISTORY, "").orEmpty()
        val lines = (listOf(entry) + existing.split('\n').filter { it.isNotBlank() }).take(20)
        prefs.put(KEY_PAY_HISTORY, lines.joinToString("\n"))
    }

    /** 读取激活历史（最近在前） */
    fun activationHistory(): List<String> =
        prefs.get(KEY_PAY_HISTORY, "").orEmpty().split('\n').filter { it.isNotBlank() }
}
