package com.liquidglass.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Beta 先锋体验码申请系统。
 *
 * 严格的分级分阶 beta 版本申请流程：
 * 1. 用户填写问卷（8 道题，涵盖使用频率、功能偏好、反馈意愿、设备型号、测试经验等）；
 * 2. 系统检测账户是否达标（安装天数、功能使用数量、会话数、反馈次数、崩溃上报次数）；
 * 3. 问卷评分 * 0.6 + 账户评分 * 0.4 综合判定，通过阈值 55 分，通过率约 50%；
 * 4. 通过后生成 17-25 位完全不规则、含校验位的先锋体验码，写入已发放列表；
 * 5. 用户输入先锋码验证，校验通过后标记已使用并激活，随后可拉取 beta 内测版本抢先体验。
 *
 * 全部能力由 [BetaPioneerManager] 单例统一管理，对外暴露可观察的 [MutableStateFlow] 状态。
 */
// =====================================================================================
// 数据模型
// =====================================================================================

/** 问卷选项 */
data class QuestionOption(
    val id: String,
    val text: String,
    /** 该选项得分（0-100），不同选项分值不同 */
    val score: Int
)

/** 问卷题目 */
data class Question(
    val id: String,
    val text: String,
    val options: List<QuestionOption>,
    /** 该题在问卷总分中的权重（0-1），所有题目权重之和为 1.0 */
    val weight: Float
)

/** 账户指标，从 SharedPreferences（key 前缀 "beta_account_"）读取 */
data class AccountMetrics(
    val installDays: Int,
    val featuresUsed: Int,
    val sessionsCount: Int,
    val feedbackCount: Int,
    val crashReportCount: Int
)

/** 综合判定结果 */
data class BetaApplyResult(
    val passed: Boolean,
    /** 综合总分 */
    val score: Int,
    val questionnaireScore: Int,
    val accountScore: Int,
    val reason: String
)

/** 先锋码验证结果 */
data class PioneerCodeValidationResult(
    val valid: Boolean,
    val reason: String
)

/** Beta 内测版本信息 */
data class BetaVersion(
    val betaVersion: String,
    val versionCode: Int,
    val downloadUrl: String,
    val releaseNotes: String,
    val knownIssues: List<String>,
    val newFeatures: List<String>
)

// =====================================================================================
// 单例管理器
// =====================================================================================

/**
 * Beta 先锋体验统一管理器。
 *
 * 线程安全说明：SharedPreferences 读写本身线程安全；[SecureRandom] 与 [OkHttpClient] 均线程安全；
 * 状态字段使用 [MutableStateFlow]，可在 Compose 中直接 collectAsState 观察。
 */
object BetaPioneerManager {

    private const val PREFS = "beta_pioneer_prefs"
    private const val KEY_ISSUED_CODES = "issued_pioneer_codes"
    private const val KEY_USED_CODES = "used_pioneer_codes"
    private const val KEY_PIONEER_STATUS = "pioneer_status"
    private const val KEY_ACCOUNT_PREFIX = "beta_account_"

    private const val STATUS_NONE = "none"
    private const val STATUS_ACTIVATED = "activated"

    /** 综合判定通过阈值，约 50% 用户可达到 */
    private const val PASS_THRESHOLD = 55

    /** 先锋码生成去重最大尝试次数 */
    private const val MAX_GENERATE_ATTEMPTS = 1000

    /** beta 版本元信息 JSON 地址 */
    private const val BETA_VERSION_URL =
        "https://raw.githubusercontent.com/jiangtengqiao/liquid-glass/main/beta-version.json"

    // ── 先锋码字符集（排除易混淆字符 0/O/o/1/l/I）──
    private const val CHARSET_UPPER = "ABCDEFGHJKMNPQRSTUVWXYZ"
    private const val CHARSET_LOWER = "abcdefghijkmnpqrstuvwxyz"
    private const val CHARSET_DIGIT = "23456789"
    private const val CHARSET_SPECIAL = "!@#$%^&*"
    private val CHARSET_ALL = CHARSET_UPPER + CHARSET_LOWER + CHARSET_DIGIT + CHARSET_SPECIAL

    // ── 可观察状态（跨页面持久化，参考 ResourceManager 的暴露方式）──
    /** 最近一次综合判定结果 */
    val applyResult = MutableStateFlow<BetaApplyResult?>(null)
    /** 当前用户通过审核后生成的先锋体验码 */
    val issuedCode = MutableStateFlow<String?>(null)
    /** 最近一次先锋码验证结果 */
    val validationResult = MutableStateFlow<PioneerCodeValidationResult?>(null)
    /** 最近一次拉取到的 beta 版本信息 */
    val betaVersion = MutableStateFlow<BetaVersion?>(null)
    /** 先锋状态：none / activated */
    val pioneerStatus = MutableStateFlow(STATUS_NONE)
    /** 是否正在拉取 beta 版本 */
    val isLoading = MutableStateFlow(false)
    /** 最近一次错误信息 */
    val errorMessage = MutableStateFlow<String?>(null)

    @Volatile
    private var appContext: Context? = null

    private val secureRandom = SecureRandom()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 预设 8 道问卷题目，权重之和为 1.0，每题选项分值 0-100，问卷满分 100。
     */
    val questions: List<Question> = listOf(
        Question(
            id = "q1",
            text = "你每周使用本 App 的频率是？",
            weight = 0.15f,
            options = listOf(
                QuestionOption("a", "每天多次使用", 100),
                QuestionOption("b", "每天至少一次", 75),
                QuestionOption("c", "每周数次", 50),
                QuestionOption("d", "偶尔使用", 25)
            )
        ),
        Question(
            id = "q2",
            text = "你最常使用本 App 的哪类功能？",
            weight = 0.10f,
            options = listOf(
                QuestionOption("a", "音乐播放相关功能", 90),
                QuestionOption("b", "实用工具类功能", 80),
                QuestionOption("c", "系统增强类功能", 70),
                QuestionOption("d", "多类功能混合使用", 100)
            )
        ),
        Question(
            id = "q3",
            text = "你是否愿意主动反馈 bug 或改进建议？",
            weight = 0.15f,
            options = listOf(
                QuestionOption("a", "非常愿意，已多次反馈", 100),
                QuestionOption("b", "愿意反馈，但次数不多", 70),
                QuestionOption("c", "看情况，遇到大问题才反馈", 40),
                QuestionOption("d", "不太愿意反馈", 10)
            )
        ),
        Question(
            id = "q4",
            text = "你的设备 Android 系统版本是？",
            weight = 0.10f,
            options = listOf(
                QuestionOption("a", "Android 14 及以上", 100),
                QuestionOption("b", "Android 12 - 13", 80),
                QuestionOption("c", "Android 10 - 11", 60),
                QuestionOption("d", "Android 9 及以下", 30)
            )
        ),
        Question(
            id = "q5",
            text = "你的软件测试或开发经验情况？",
            weight = 0.15f,
            options = listOf(
                QuestionOption("a", "专业开发或测试工程师", 100),
                QuestionOption("b", "有过 beta 内测经验", 80),
                QuestionOption("c", "技术爱好者，常刷机折腾", 60),
                QuestionOption("d", "普通用户，无相关经验", 30)
            )
        ),
        Question(
            id = "q6",
            text = "遇到 App 崩溃时你通常如何处理？",
            weight = 0.10f,
            options = listOf(
                QuestionOption("a", "主动上报并附带日志", 100),
                QuestionOption("b", "上报但无详细日志", 70),
                QuestionOption("c", "重启 App 继续使用", 40),
                QuestionOption("d", "直接卸载或弃用", 0)
            )
        ),
        Question(
            id = "q7",
            text = "你使用过本 App 多少个功能？",
            weight = 0.10f,
            options = listOf(
                QuestionOption("a", "10 个以上", 100),
                QuestionOption("b", "6 - 10 个", 75),
                QuestionOption("c", "3 - 5 个", 50),
                QuestionOption("d", "1 - 2 个", 20)
            )
        ),
        Question(
            id = "q8",
            text = "你是否愿意在社区分享体验或帮助其他用户？",
            weight = 0.15f,
            options = listOf(
                QuestionOption("a", "非常愿意，已是社区活跃成员", 100),
                QuestionOption("b", "愿意分享使用体验", 70),
                QuestionOption("c", "偶尔参与讨论", 40),
                QuestionOption("d", "仅个人使用，不参与社区", 10)
            )
        )
    )

    /**
     * 初始化管理器，注入全局上下文并恢复持久化的先锋状态。
     * 应在 [MainActivity.onCreate] 早期调用。
     */
    @Synchronized
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            refreshPioneerStatus()
        }
    }

    // =====================================================================================
    // 1. 问卷评分
    // =====================================================================================

    /**
     * 计算问卷得分。
     *
     * @param answers key 为题目 id（如 "q1"），value 为选项 id（如 "a"）
     * @return 0-100 的整数得分；未作答的题目按 0 分计入
     */
    fun calculateQuestionnaireScore(answers: Map<String, String>): Int {
        var total = 0f
        for (q in questions) {
            val optId = answers[q.id] ?: continue
            val opt = q.options.firstOrNull { it.id == optId } ?: continue
            total += opt.score * q.weight
        }
        return total.roundToInt().coerceIn(0, 100)
    }

    // =====================================================================================
    // 2. 账户达标检测
    // =====================================================================================

    /**
     * 从 SharedPreferences 读取账户指标。
     * 对应 key：beta_account_install_days / beta_account_features_used /
     * beta_account_sessions_count / beta_account_feedback_count / beta_account_crash_report_count
     */
    fun readAccountMetrics(): AccountMetrics {
        val ctx = appContext ?: return AccountMetrics(0, 0, 0, 0, 0)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AccountMetrics(
            installDays = sp.getInt("${KEY_ACCOUNT_PREFIX}install_days", 0),
            featuresUsed = sp.getInt("${KEY_ACCOUNT_PREFIX}features_used", 0),
            sessionsCount = sp.getInt("${KEY_ACCOUNT_PREFIX}sessions_count", 0),
            feedbackCount = sp.getInt("${KEY_ACCOUNT_PREFIX}feedback_count", 0),
            crashReportCount = sp.getInt("${KEY_ACCOUNT_PREFIX}crash_report_count", 0)
        )
    }

    /**
     * 写入账户指标，供 App 内埋点/统计模块调用以喂入数据。
     */
    fun saveAccountMetrics(metrics: AccountMetrics) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putInt("${KEY_ACCOUNT_PREFIX}install_days", metrics.installDays)
            putInt("${KEY_ACCOUNT_PREFIX}features_used", metrics.featuresUsed)
            putInt("${KEY_ACCOUNT_PREFIX}sessions_count", metrics.sessionsCount)
            putInt("${KEY_ACCOUNT_PREFIX}feedback_count", metrics.feedbackCount)
            putInt("${KEY_ACCOUNT_PREFIX}crash_report_count", metrics.crashReportCount)
        }.apply()
    }

    /**
     * 账户达标硬性门槛：安装天数 >= 7，功能使用 >= 5 个，会话数 >= 20。
     * 不满足则直接判定不通过，不再进入综合评分。
     */
    fun isAccountEligible(metrics: AccountMetrics): Boolean =
        metrics.installDays >= 7 && metrics.featuresUsed >= 5 && metrics.sessionsCount >= 20

    /**
     * 账户得分（0-100）：
     * min(installDays/30,1)*30 + min(featuresUsed/10,1)*30 + min(sessionsCount/50,1)*20
     * + min(feedbackCount/3,1)*10 + min(crashReportCount/2,1)*10
     */
    fun calculateAccountScore(metrics: AccountMetrics): Int {
        val score = min(metrics.installDays / 30f, 1f) * 30f +
                min(metrics.featuresUsed / 10f, 1f) * 30f +
                min(metrics.sessionsCount / 50f, 1f) * 20f +
                min(metrics.feedbackCount / 3f, 1f) * 10f +
                min(metrics.crashReportCount / 2f, 1f) * 10f
        return score.toInt().coerceIn(0, 100)
    }

    // =====================================================================================
    // 3. 综合判定
    // =====================================================================================

    /**
     * 综合判定：先校验账户达标门槛，再按 问卷分*0.6 + 账户分*0.4 与 [PASS_THRESHOLD] 比较。
     * 通过时自动生成先锋体验码并写入已发放列表。
     *
     * @param answers 问卷作答，key 为题目 id，value 为选项 id
     */
    fun evaluate(answers: Map<String, String>): BetaApplyResult {
        val metrics = readAccountMetrics()
        val qScore = calculateQuestionnaireScore(answers)
        val aScore = calculateAccountScore(metrics)
        val total = (qScore * 0.6f + aScore * 0.4f).roundToInt()

        val result: BetaApplyResult = when {
            !isAccountEligible(metrics) -> BetaApplyResult(
                passed = false,
                score = total,
                questionnaireScore = qScore,
                accountScore = aScore,
                reason = "账户未达标：需安装满 7 天、使用 5 个以上功能、累计 20 次以上会话"
            )
            total >= PASS_THRESHOLD -> {
                val code = generatePioneerCode()
                BetaApplyResult(
                    passed = true,
                    score = total,
                    questionnaireScore = qScore,
                    accountScore = aScore,
                    reason = "恭喜通过先锋体验审核，综合评分 $total 分，先锋体验码已生成：$code"
                )
            }
            else -> BetaApplyResult(
                passed = false,
                score = total,
                questionnaireScore = qScore,
                accountScore = aScore,
                reason = "综合评分 $total 分未达 $PASS_THRESHOLD 分阈值，暂未通过"
            )
        }
        applyResult.value = result
        return result
    }

    // =====================================================================================
    // 4. 先锋体验码生成
    // =====================================================================================

    /**
     * 生成 17-25 位完全不规则、含校验位的先锋体验码。
     *
     * 规则：
     * - 总长度（含校验位）在 17-25 间随机；
     * - 每个字符由 [SecureRandom] 从 [CHARSET_ALL] 中选取；
     * - 主体部分必须同时包含大写、小写、数字、特殊字符各至少 1 个；
     * - 末尾追加 1 位校验字符 = 主体各字符 charCode 之和对字符集长度取模所得索引对应的字符；
     * - 与本地已发放列表去重，重复则重新生成；
     * - 生成后写入已发放列表（key=[KEY_ISSUED_CODES]）。
     */
    fun generatePioneerCode(): String {
        val issued = loadIssuedCodes()
        var lastCode = ""
        repeat(MAX_GENERATE_ATTEMPTS) {
            val totalLen = 17 + secureRandom.nextInt(9) // 17..25
            val bodyLen = totalLen - 1                   // 主体长度，留 1 位给校验位

            // 先放入四类各 1 个，保证组成合规
            val chars = ArrayList<Char>(totalLen)
            chars.add(pickChar(CHARSET_UPPER))
            chars.add(pickChar(CHARSET_LOWER))
            chars.add(pickChar(CHARSET_DIGIT))
            chars.add(pickChar(CHARSET_SPECIAL))
            while (chars.size < bodyLen) chars.add(pickChar(CHARSET_ALL))
            shuffleSecure(chars)

            val body = String(chars.toCharArray())
            // 校验位：主体 charCode 之和 mod 字符集长度
            val checksumIndex = charCodeSum(body) % CHARSET_ALL.length
            val code = body + CHARSET_ALL[checksumIndex]
            lastCode = code

            if (code !in issued) {
                saveIssuedCode(code)
                issuedCode.value = code
                return code
            }
        }
        // 理论上几乎不可能走到这里（17-25 位、64 字符集碰撞概率极低）
        errorMessage.value = "先锋码生成失败：多次生成均与已发放码重复"
        return lastCode
    }

    private fun pickChar(charset: String): Char =
        charset[secureRandom.nextInt(charset.length)]

    /** Fisher-Yates 洗牌，使用 [SecureRandom] 保证随机性 */
    private fun shuffleSecure(list: MutableList<Char>) {
        for (i in list.size - 1 downTo 1) {
            val j = secureRandom.nextInt(i + 1)
            val tmp = list[i]
            list[i] = list[j]
            list[j] = tmp
        }
    }

    /** 计算字符串各字符 charCode 之和，用于先锋码校验位 */
    private fun charCodeSum(s: String): Int {
        var sum = 0
        for (c in s) sum += c.code
        return sum
    }

    // =====================================================================================
    // 5. 先锋码验证
    // =====================================================================================

    /**
     * 验证先锋体验码。
     *
     * 校验项：长度 17-25、字符集合法、校验位正确、四类字符组成合规、
     * 在已发放列表中、未被使用。全部通过则标记为已使用并激活先锋状态。
     */
    fun validatePioneerCode(code: String): PioneerCodeValidationResult {
        val normalized = code.trim()
        val fail: (String) -> PioneerCodeValidationResult = { msg ->
            PioneerCodeValidationResult(false, msg).also { validationResult.value = it }
        }

        if (normalized.length !in 17..25) return fail("先锋码长度应为 17-25 位")
        if (normalized.any { it !in CHARSET_ALL }) return fail("先锋码包含非法字符")

        val body = normalized.dropLast(1)
        val checksumChar = normalized.last()
        val expectedIndex = charCodeSum(body) % CHARSET_ALL.length
        if (CHARSET_ALL[expectedIndex] != checksumChar) return fail("先锋码校验位错误")

        if (body.none { it in CHARSET_UPPER } ||
            body.none { it in CHARSET_LOWER } ||
            body.none { it in CHARSET_DIGIT } ||
            body.none { it in CHARSET_SPECIAL }
        ) return fail("先锋码字符组成不合规")

        if (normalized !in loadIssuedCodes()) return fail("该先锋码未在已发放列表中")
        if (normalized in loadUsedCodes()) return fail("该先锋码已被使用")

        // 全部通过：标记已使用并激活
        markCodeUsed(normalized)
        setPioneerStatus(STATUS_ACTIVATED)
        val ok = PioneerCodeValidationResult(true, "先锋码验证通过，beta 抢先体验已激活")
        validationResult.value = ok
        return ok
    }

    // =====================================================================================
    // 6. Beta 版本拉取
    // =====================================================================================

    /**
     * 拉取 beta 内测版本信息。仅在先锋状态为 [STATUS_ACTIVATED] 时允许。
     *
     * 该函数为挂起函数，内部在 IO 调度器执行网络请求；调用方需在协程中调用。
     * 成功时返回 [BetaVersion] 并更新 [betaVersion] 状态，失败返回 null 并写入 [errorMessage]。
     */
    suspend fun fetchBetaVersion(): BetaVersion? = withContext(Dispatchers.IO) {
        if (pioneerStatus.value != STATUS_ACTIVATED) {
            errorMessage.value = "先锋码未激活，无法拉取 beta 版本"
            return@withContext null
        }
        isLoading.value = true
        try {
            val request = Request.Builder().url(BETA_VERSION_URL).get().build()
            val response = httpClient.newCall(request).execute()
            val result: BetaVersion? = response.use { resp ->
                if (!resp.isSuccessful) {
                    errorMessage.value = "拉取 beta 版本失败：HTTP ${resp.code}"
                    null
                } else {
                    val raw = resp.body?.string()
                    if (raw.isNullOrEmpty()) {
                        errorMessage.value = "拉取 beta 版本失败：响应为空"
                        null
                    } else {
                        parseBetaVersion(JSONObject(raw))
                    }
                }
            }
            if (result != null) {
                betaVersion.value = result
                errorMessage.value = null
            }
            result
        } catch (e: Exception) {
            errorMessage.value = "拉取 beta 版本异常：${e.message}"
            null
        } finally {
            isLoading.value = false
        }
    }

    private fun parseBetaVersion(json: JSONObject): BetaVersion = BetaVersion(
        betaVersion = json.optString("betaVersion"),
        versionCode = json.optInt("versionCode"),
        downloadUrl = json.optString("downloadUrl"),
        releaseNotes = json.optString("releaseNotes"),
        knownIssues = json.optJSONArray("knownIssues").toStringList(),
        newFeatures = json.optJSONArray("newFeatures").toStringList()
    )

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) out.add(optString(i))
        return out
    }

    // =====================================================================================
    // 持久化辅助
    // =====================================================================================

    private fun loadCodeSet(key: String): MutableSet<String> {
        val ctx = appContext ?: return mutableSetOf()
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null) ?: return mutableSetOf()
        return try {
            val arr = JSONArray(raw)
            val set = LinkedHashSet<String>(arr.length())
            for (i in 0 until arr.length()) set.add(arr.optString(i))
            set
        } catch (_: Exception) {
            mutableSetOf()
        }
    }

    private fun loadIssuedCodes(): MutableSet<String> = loadCodeSet(KEY_ISSUED_CODES)

    private fun loadUsedCodes(): MutableSet<String> = loadCodeSet(KEY_USED_CODES)

    private fun appendCodeToList(key: String, code: String) {
        val ctx = appContext ?: return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = loadCodeSet(key)
        set.add(code)
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        sp.edit().putString(key, arr.toString()).apply()
    }

    private fun saveIssuedCode(code: String) = appendCodeToList(KEY_ISSUED_CODES, code)

    private fun markCodeUsed(code: String) = appendCodeToList(KEY_USED_CODES, code)

    private fun setPioneerStatus(status: String) {
        pioneerStatus.value = status
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PIONEER_STATUS, status).apply()
    }

    /** 从持久化恢复先锋状态 */
    fun refreshPioneerStatus() {
        val ctx = appContext ?: return
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PIONEER_STATUS, STATUS_NONE) ?: STATUS_NONE
        pioneerStatus.value = s
    }
}
