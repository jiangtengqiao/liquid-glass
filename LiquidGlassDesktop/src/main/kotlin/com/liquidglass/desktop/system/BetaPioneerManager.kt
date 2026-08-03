package com.liquidglass.desktop.system

import com.squareup.okhttp3.OkHttpClient
import com.squareup.okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

/**
 * 问卷题目
 * @param scores 与 options 一一对应的分值
 */
data class Question(
    val text: String,
    val options: List<String>,
    val scores: List<Int>
)

/** 问卷评分结果 */
data class QuestionnaireResult(val score: Int, val passed: Boolean)

/** 生成的先锋体验码 */
data class PioneerCode(val code: String, val expiry: String)

/**
 * Beta 先锋码管理器（Desktop 版本）
 *
 * 与 Android 端逻辑一致：
 * - 8 道问卷题 + 评分
 * - 55 分阈值
 * - SecureRandom 生成 17-25 位先锋码 + 校验位
 * - 账户检测（Desktop 用 Preferences 存）
 * - 验证先锋码后拉取 beta-version.json
 */
class BetaPioneerManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 账户检测：Desktop 端用 Preferences 替代 SharedPreferences */
    private val prefs: Preferences = Preferences.userNodeForPackage(BetaPioneerManager::class.java)

    private val random = SecureRandom()

    /** 8 道问卷题目 */
    val questions: List<Question> = listOf(
        Question(
            "你使用过液态玻璃相关应用吗?",
            listOf("从未用过", "偶尔使用", "经常使用", "深度用户"),
            listOf(5, 10, 15, 20)
        ),
        Question(
            "你的技术背景?",
            listOf("非技术用户", "技术爱好者", "开发者", "资深开发者"),
            listOf(3, 8, 15, 20)
        ),
        Question(
            "你更看重应用的哪个方面?",
            listOf("颜值优先", "功能优先", "性能优先", "全部都要"),
            listOf(8, 10, 8, 15)
        ),
        Question(
            "你是否愿意主动提交使用反馈?",
            listOf("不愿意", "偶尔会", "愿意", "积极反馈"),
            listOf(2, 8, 12, 18)
        ),
        Question(
            "你能否接受 Beta 版本的不稳定?",
            listOf("完全不能", "勉强接受", "可以接受", "乐于尝鲜"),
            listOf(2, 8, 12, 20)
        ),
        Question(
            "你的主要使用平台?",
            listOf("仅手机", "仅桌面", "两者都有", "多平台通吃"),
            listOf(5, 8, 12, 18)
        ),
        Question(
            "你愿意参与后续内测吗?",
            listOf("不愿意", "看情况", "愿意", "非常愿意"),
            listOf(2, 8, 12, 20)
        ),
        Question(
            "你对全应用清除 emoji 的设计怎么看?",
            listOf("反感", "无感", "支持", "强烈支持"),
            listOf(2, 8, 12, 18)
        )
    )

    /** 评分阈值（与 Android 端一致） */
    private val passThreshold: Int = 55

    /**
     * 评估问卷得分
     * @param answers key 为题目下标，value 为选项下标
     */
    fun evaluate(answers: Map<Int, Int>): QuestionnaireResult {
        var score = 0
        answers.forEach { (qIndex, oIndex) ->
            val q = questions.getOrNull(qIndex) ?: return@forEach
            score += q.scores.getOrNull(oIndex) ?: 0
        }
        return QuestionnaireResult(score = score, passed = score >= passThreshold)
    }

    /**
     * 生成先锋体验码（17-25 位 + 校验位）
     * 同时写入 Preferences 用于账户检测
     */
    fun generateCode(): PioneerCode {
        // 长度区间 17-25
        val length = 17 + random.nextInt(9)
        // 去除易混淆字符（0/O/1/I/L）
        val charset = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val body = (1..length).map { charset[random.nextInt(charset.length)] }.joinToString("")
        // 4 位校验位：基于 body 内容做简易 hash
        val checksum = body.fold(0) { acc, c -> (acc * 31 + c.code) and 0xFFFF }
        val check = checksum.toString(16).uppercase().padStart(4, '0').take(4)
        val code = "LG-$body-$check"

        // 账户检测：本地留存（避免重复申请）
        prefs.put(KEY_CODE, code)
        prefs.putLong(KEY_GEN_TIME, System.currentTimeMillis())
        prefs.flush()

        val expiry = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis() + VALIDITY_MILLIS))
        return PioneerCode(code = code, expiry = expiry)
    }

    /**
     * 验证先锋码并拉取 beta 版本信息
     * @return beta 信息字符串；验证失败返回 null
     */
    suspend fun verifyAndFetchBeta(code: String): String? {
        if (!verify(code)) return null
        return fetchBetaInfo()
    }

    /**
     * 校验先锋码：
     * - 若本地有存储码，必须严格匹配且在有效期内
     * - 否则按格式 + 校验位进行合法性校验
     */
    fun verify(code: String): Boolean {
        val trimmed = code.trim()
        val stored = prefs.get(KEY_CODE, null)
        return if (stored != null) {
            if (trimmed != stored) return false
            val genTime = prefs.getLong(KEY_GEN_TIME, 0L)
            System.currentTimeMillis() - genTime < VALIDITY_MILLIS
        } else {
            isValidFormat(trimmed)
        }
    }

    /** 格式 + 校验位合法性校验 */
    private fun isValidFormat(code: String): Boolean {
        val parts = code.split("-")
        if (parts.size != 3) return false
        if (parts[0] != "LG") return false
        val body = parts[1]
        if (body.length !in 17..25) return false
        val charset = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        if (body.any { it !in charset }) return false
        val expected = body.fold(0) { acc, c -> (acc * 31 + c.code) and 0xFFFF }
            .toString(16).uppercase().padStart(4, '0').take(4)
        return expected == parts[2]
    }

    /** 拉取 beta-version.json */
    private suspend fun fetchBetaInfo(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(BETA_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext fallbackBetaInfo()
                val body = response.body?.string() ?: return@withContext fallbackBetaInfo()
                val json = JSONObject(body)
                buildString {
                    appendLine("版本号: ${json.optString("version", "未知")}")
                    appendLine("发布日期: ${json.optString("date", "未知")}")
                    appendLine("更新说明: ${json.optString("notes", "无")}")
                    appendLine("下载链接: ${json.optString("downloadUrl", "无")}")
                    appendLine("校验值: ${json.optString("sha256", "无")}")
                }
            }
        } catch (_: Exception) {
            fallbackBetaInfo()
        }
    }

    private fun fallbackBetaInfo(): String =
        "Beta 版本信息暂时无法获取（网络异常），请稍后重试。"

    companion object {
        private const val BETA_URL =
            "https://raw.githubusercontent.com/jiangtengqiao/liquid-glass/main/beta-version.json"
        private const val KEY_CODE = "beta_pioneer_code"
        private const val KEY_GEN_TIME = "beta_pioneer_gen_time"
        /** 90 天有效期 */
        private const val VALIDITY_MILLIS = 90L * 24 * 3600 * 1000
    }
}
