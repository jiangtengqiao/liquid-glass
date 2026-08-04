package com.liquidglass.desktop.system

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 * Beta 版本结构化信息
 *
 * 由 beta-version.json 解析得到；远程不可达时为 null，
 * 由调用方决定是否使用 fallback（仅显示提示文案）。
 */
data class BetaInfo(
    val version: String,
    val date: String,
    val notes: String,
    val downloadUrl: String,
    val sha256: String,
    /** 安装包体积（字节），可能为 0 表示未知 */
    val sizeBytes: Long,
    /** 安装包文件名（从 downloadUrl 推导） */
    val fileName: String
)

/** Beta 先锋申请表单（昵称 / 邮箱 / 使用场景 / 加入理由） */
data class BetaApplication(
    val nickname: String,
    val email: String,
    val useCase: String,
    val reason: String,
    val submittedAt: Long
)

/** Beta 申请状态 */
enum class BetaStatus { NOT_APPLIED, APPLIED, APPROVED }

/** Beta 用户反馈（bug 报告 / 功能建议 / 其他） */
data class BetaFeedback(
    val type: String,
    val content: String,
    val contact: String,
    val createdAt: Long
)

/**
 * Beta 先锋码管理器（Desktop 版本）
 *
 * 与 Android 端逻辑一致：
 * - 8 道问卷题 + 评分
 * - 55 分阈值
 * - SecureRandom 生成 17-25 位先锋码 + 校验位
 * - 账户检测（Desktop 用 Preferences 存）
 * - 验证先锋码后拉取 beta-version.json
 *
 * v2.11.0 扩展：
 * - Beta 先锋申请表单（本地持久化 + 可选 GitHub Issue 提交）
 * - Beta 通道说明 / 状态徽章
 * - Beta 版本日志（从 version.json 读取 releaseNotes）
 * - Beta 用户反馈（保存到本地文件）
 *
 * 网络请求使用 Java 原生 HttpURLConnection，避免引入 OkHttp 依赖。
 */
class BetaPioneerManager {

    private val prefs: Preferences = Preferences.userNodeForPackage(BetaPioneerManager::class.java)
    private val random = SecureRandom()

    /** 8 道问卷题目 */
    val questions: List<Question> = listOf(
        Question("你使用过液态玻璃相关应用吗?", listOf("从未用过", "偶尔使用", "经常使用", "深度用户"), listOf(5, 10, 15, 20)),
        Question("你的技术背景?", listOf("非技术用户", "技术爱好者", "开发者", "资深开发者"), listOf(3, 8, 15, 20)),
        Question("你更看重应用的哪个方面?", listOf("颜值优先", "功能优先", "性能优先", "全部都要"), listOf(8, 10, 8, 15)),
        Question("你是否愿意主动提交使用反馈?", listOf("不愿意", "偶尔会", "愿意", "积极反馈"), listOf(2, 8, 12, 18)),
        Question("你能否接受 Beta 版本的不稳定?", listOf("完全不能", "勉强接受", "可以接受", "乐于尝鲜"), listOf(2, 8, 12, 20)),
        Question("你的主要使用平台?", listOf("仅手机", "仅桌面", "两者都有", "多平台通吃"), listOf(5, 8, 12, 18)),
        Question("你愿意参与后续内测吗?", listOf("不愿意", "看情况", "愿意", "非常愿意"), listOf(2, 8, 12, 20)),
        Question("你对全应用清除 emoji 的设计怎么看?", listOf("反感", "无感", "支持", "强烈支持"), listOf(2, 8, 12, 18))
    )

    private val passThreshold: Int = 55

    fun evaluate(answers: Map<Int, Int>): QuestionnaireResult {
        var score = 0
        answers.forEach { (qIndex, oIndex) ->
            val q = questions.getOrNull(qIndex) ?: return@forEach
            score += q.scores.getOrNull(oIndex) ?: 0
        }
        return QuestionnaireResult(score = score, passed = score >= passThreshold)
    }

    fun generateCode(): PioneerCode {
        val length = 17 + random.nextInt(9)
        val charset = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val body = (1..length).map { charset[random.nextInt(charset.length)] }.joinToString("")
        val checksum = body.fold(0) { acc, c -> (acc * 31 + c.code) and 0xFFFF }
        val check = checksum.toString(16).uppercase().padStart(4, '0').take(4)
        val code = "LG-$body-$check"

        prefs.put(KEY_CODE, code)
        prefs.putLong(KEY_GEN_TIME, System.currentTimeMillis())
        prefs.flush()

        val expiry = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis() + VALIDITY_MILLIS))
        return PioneerCode(code = code, expiry = expiry)
    }

    /** 读取已保存的先锋码（若未过期），用于启动时恢复状态 */
    fun loadSavedCode(): PioneerCode? {
        val stored = prefs.get(KEY_CODE, null) ?: return null
        val genTime = prefs.getLong(KEY_GEN_TIME, 0L)
        if (System.currentTimeMillis() - genTime >= VALIDITY_MILLIS) return null
        val expiry = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(genTime + VALIDITY_MILLIS))
        return PioneerCode(code = stored, expiry = expiry)
    }

    suspend fun verifyAndFetchBeta(code: String): String? {
        if (!verify(code)) return null
        return fetchBetaInfo()
    }

    /** 验证先锋码并返回结构化 Beta 信息（供下载向导使用） */
    suspend fun verifyAndFetchBetaRaw(code: String): BetaInfo? {
        if (!verify(code)) return null
        return fetchBetaInfoRaw()
    }

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

    private fun isValidFormat(code: String): Boolean {
        val parts = code.split("-")
        if (parts.size != 3) return false
        if (parts[0] != "LG") return false
        val body = parts[1]
        if (body.length !in 17..25) return false
        val charset = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        if (body.any { it !in charset } ) return false
        val expected = body.fold(0) { acc, c -> (acc * 31 + c.code) and 0xFFFF }
            .toString(16).uppercase().padStart(4, '0').take(4)
        return expected == parts[2]
    }

    /** 拉取 beta-version.json */
    private suspend fun fetchBetaInfo(): String? = withContext(Dispatchers.IO) {
        fetchBetaInfoRaw()?.let { info ->
            buildString {
                appendLine("版本号: ${info.version}")
                appendLine("发布日期: ${info.date}")
                appendLine("更新说明: ${info.notes}")
                appendLine("下载链接: ${info.downloadUrl}")
                appendLine("校验值: ${info.sha256}")
            }
        } ?: fallbackBetaInfo()
    }

    /** 拉取并解析 beta-version.json 为结构化对象（jsDelivr 优先，raw 兜底） */
    private suspend fun fetchBetaInfoRaw(): BetaInfo? = withContext(Dispatchers.IO) {
        // 多源尝试：jsDelivr CDN → raw.githubusercontent
        for (url in listOf(BETA_URL, BETA_URL_FALLBACK)) {
            val result = fetchBetaFromUrl(url)
            if (result != null) return@withContext result
        }
        null
    }

    private fun fetchBetaFromUrl(urlStr: String): BetaInfo? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                useCaches = false
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val url = json.optString("downloadUrl", "").takeIf { it.isNotBlank() }
                ?: return null
            val fileName = url.substringAfterLast('/', "LiquidGlass-beta.exe")
            BetaInfo(
                version = json.optString("version", "未知"),
                date = json.optString("date", "未知"),
                notes = json.optString("notes", "无"),
                downloadUrl = url,
                sha256 = json.optString("sha256", "").uppercase(),
                sizeBytes = json.optLong("size", 0),
                fileName = fileName
            )
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun fallbackBetaInfo(): String =
        "Beta 版本信息暂时无法获取（网络异常），请稍后重试。"

    /**
     * 下载 Beta 安装包到指定目录，实时回调已下载字节 / 总字节 / 当前阶段文案
     *
     * @param info Beta 信息
     * @param targetDir 目标目录（自动创建）
     * @param onProgress (已下载字节, 总字节) —— 总字节 < 0 表示未知
     * @param onStage 当前阶段文案（如"正在连接服务器"、"正在下载主程序"）
     * @return 下载成功后的本地文件，失败返回 null
     */
    suspend fun downloadBeta(
        info: BetaInfo,
        targetDir: java.io.File,
        onProgress: (Long, Long) -> Unit,
        onStage: (String) -> Unit
    ): java.io.File? = withContext(Dispatchers.IO) {
        try {
            if (!targetDir.exists()) targetDir.mkdirs()
            val target = java.io.File(targetDir, info.fileName)
            val tmp = java.io.File(target.parentFile, target.name + ".part")

            onStage("正在连接服务器...")
            val conn = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                useCaches = false
                requestMethod = "GET"
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return@withContext null
            }
            val total = conn.contentLengthLong.let { if (it > 0) it else -1L }
            onStage("正在下载: ${info.fileName}")

            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    var read: Int
                    var downloaded = 0L
                    var lastReport = 0L
                    while (true) {
                        read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        downloaded += read
                        // 节流：每 64KB 或最后一段回报一次
                        if (downloaded - lastReport >= 64 * 1024 || read < buf.size) {
                            onProgress(downloaded, total)
                            lastReport = downloaded
                        }
                    }
                }
            }
            conn.disconnect()

            onStage("正在校验完整性...")
            if (info.sha256.isNotBlank()) {
                val actual = sha256OfFile(tmp)
                if (!actual.equals(info.sha256, ignoreCase = true)) {
                    tmp.delete()
                    onStage("校验失败：SHA256 不匹配（预期 ${info.sha256.take(12)}…，实际 ${actual.take(12)}…）")
                    return@withContext null
                }
            }

            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true); tmp.delete()
            }
            onStage("下载完成")
            target
        } catch (e: Exception) {
            onStage("下载失败：${e.message ?: "未知错误"}")
            null
        }
    }

    /** 计算文件 SHA256 */
    private fun sha256OfFile(f: java.io.File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 创建桌面快捷方式（Windows .lnk）
     *
     * 由于 JDK 不原生支持 .lnk 写入，这里采用最小化 PowerShell 调用，
     * 仅在 Windows 平台生效；其他平台返回 false。
     */
    fun createDesktopShortcut(targetPath: String, name: String): Boolean {
        if (!System.getProperty("os.name").lowercase().contains("windows")) return false
        return try {
            val desktop = java.io.File(System.getProperty("user.home"), "Desktop")
            val lnk = java.io.File(desktop, "$name.lnk").absolutePath
            val ps = """
                ${'$'}ws = New-Object -ComObject WScript.Shell;
                ${'$'}s = ${'$'}ws.CreateShortcut('$lnk');
                ${'$'}s.TargetPath = '$targetPath';
                ${'$'}s.WorkingDirectory = '${java.io.File(targetPath).parentFile?.absolutePath ?: ""}';
                ${'$'}s.WindowStyle = 1;
                ${'$'}s.Description = '$name';
                ${'$'}s.Save()
            """.trimIndent()
            val pb = ProcessBuilder("powershell", "-NoProfile", "-Command", ps)
                .redirectErrorStream(true)
            pb.start().waitFor() == 0
        } catch (_: Exception) { false }
    }

    /** 创建开始菜单快捷方式（Windows） */
    fun createStartMenuShortcut(targetPath: String, group: String, name: String): Boolean {
        if (!System.getProperty("os.name").lowercase().contains("windows")) return false
        return try {
            val startMenu = java.io.File(
                System.getenv("APPDATA") ?: "${System.getProperty("user.home")}/AppData/Roaming/Microsoft/Windows/Start Menu/Programs",
                group
            ).apply { if (!exists()) mkdirs() }
            val lnk = java.io.File(startMenu, "$name.lnk").absolutePath
            val ps = """
                ${'$'}ws = New-Object -ComObject WScript.Shell;
                ${'$'}s = ${'$'}ws.CreateShortcut('$lnk');
                ${'$'}s.TargetPath = '$targetPath';
                ${'$'}s.WorkingDirectory = '${java.io.File(targetPath).parentFile?.absolutePath ?: ""}';
                ${'$'}s.WindowStyle = 1;
                ${'$'}s.Description = '$name';
                ${'$'}s.Save()
            """.trimIndent()
            val pb = ProcessBuilder("powershell", "-NoProfile", "-Command", ps)
                .redirectErrorStream(true)
            pb.start().waitFor() == 0
        } catch (_: Exception) { false }
    }

    // ===================== Beta 先锋申请 / 反馈 / 版本日志 =====================

    /**
     * 提交 Beta 先锋申请表单。
     *
     * 保存到本地 Preferences（状态置为 APPLIED）；若配置了 GitHub Token，
     * 则尝试通过 GitHub Issues API 远程提交，否则跳过远程提交。
     *
     * 网络请求在 IO 调度器执行，调用方应在协程中调用。
     *
     * @return 状态文案（用于 UI 提示）
     */
    suspend fun submitApplication(form: BetaApplication): String = withContext(Dispatchers.IO) {
        // 本地保存
        prefs.put(KEY_APP_NICKNAME, form.nickname)
        prefs.put(KEY_APP_EMAIL, form.email)
        prefs.put(KEY_APP_USECASE, form.useCase)
        prefs.put(KEY_APP_REASON, form.reason)
        prefs.putLong(KEY_APP_SUBMITTED_AT, form.submittedAt)
        prefs.put(KEY_APP_STATUS, BetaStatus.APPLIED.name)
        prefs.flush()

        // 可选远程提交
        val token = getGithubToken()
        if (token.isBlank()) {
            "申请已保存到本地（未配置 GitHub Token，远程提交已跳过）"
        } else {
            val remote = submitApplicationToGithub(form, token)
            if (remote) "申请已保存并提交到 GitHub Issue"
            else "申请已保存到本地，GitHub 远程提交失败"
        }
    }

    /** 读取已保存的申请表单（无则 null） */
    fun loadApplication(): BetaApplication? {
        val nickname = prefs.get(KEY_APP_NICKNAME, null) ?: return null
        return BetaApplication(
            nickname = nickname,
            email = prefs.get(KEY_APP_EMAIL, ""),
            useCase = prefs.get(KEY_APP_USECASE, ""),
            reason = prefs.get(KEY_APP_REASON, ""),
            submittedAt = prefs.getLong(KEY_APP_SUBMITTED_AT, 0L)
        )
    }

    /** 当前申请状态 */
    fun applicationStatus(): BetaStatus {
        val raw = prefs.get(KEY_APP_STATUS, null) ?: return BetaStatus.NOT_APPLIED
        return runCatching { BetaStatus.valueOf(raw) }.getOrDefault(BetaStatus.NOT_APPLIED)
    }

    /** 本地标记申请为已批准（开发者人工审核后） */
    fun approveApplication() {
        prefs.put(KEY_APP_STATUS, BetaStatus.APPROVED.name)
        prefs.flush()
    }

    /** 重新申请：清空申请状态，允许用户再次填写 */
    fun resetApplication() {
        prefs.remove(KEY_APP_NICKNAME)
        prefs.remove(KEY_APP_EMAIL)
        prefs.remove(KEY_APP_USECASE)
        prefs.remove(KEY_APP_REASON)
        prefs.remove(KEY_APP_SUBMITTED_AT)
        prefs.remove(KEY_APP_STATUS)
        prefs.flush()
    }

    /** 配置 GitHub Token（用于远程提交申请 Issue；留空则跳过远程） */
    fun setGithubToken(token: String) {
        prefs.put(KEY_GITHUB_TOKEN, token.trim())
        prefs.flush()
    }

    /** 读取已配置的 GitHub Token */
    fun getGithubToken(): String = prefs.get(KEY_GITHUB_TOKEN, "").orEmpty()

    /**
     * 通过 GitHub Issues API 提交申请为 Issue。
     * 创建 Issue 必须带认证 Token，否则会 401。
     */
    private fun submitApplicationToGithub(form: BetaApplication, token: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val body = JSONObject().apply {
                put("title", "[Beta 申请] ${form.nickname} - ${form.email}")
                put(
                    "body", buildString {
                        appendLine("## Beta 先锋申请")
                        appendLine("- 昵称：${form.nickname}")
                        appendLine("- 邮箱：${form.email}")
                        appendLine("- 使用场景：")
                        appendLine(form.useCase)
                        appendLine("- 加入理由：")
                        appendLine(form.reason)
                        appendLine("- 提交时间：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(form.submittedAt))}")
                        appendLine("---")
                        appendLine("_由 LiquidGlass Desktop 自动提交_")
                    }
                )
                put("labels", org.json.JSONArray().apply { put("beta-application") })
            }
            conn = (URL(GITHUB_ISSUES_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "POST"
                setRequestProperty("Authorization", "token $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 保存 Beta 用户反馈到本地文件。
     *
     * 存储位置：~/.liquidglass/beta-feedback/feedback-<时间戳>.txt
     *
     * @return 保存成功后的文件路径，失败返回 null
     */
    fun saveFeedback(feedback: BetaFeedback): String? {
        return try {
            val dir = java.io.File(
                System.getProperty("user.home"),
                ".liquidglass/beta-feedback"
            ).apply { if (!exists()) mkdirs() }
            val df = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
            val file = java.io.File(dir, "feedback-${df.format(Date(feedback.createdAt))}.txt")
            file.writeText(
                buildString {
                    appendLine("=== LiquidGlass Beta 反馈 ===")
                    appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(feedback.createdAt))}")
                    appendLine("类型: ${feedback.type}")
                    appendLine("联系方式: ${feedback.contact.ifBlank { "未提供" }}")
                    appendLine("内容:")
                    appendLine(feedback.content)
                },
                Charsets.UTF_8
            )
            // 同时追加一份索引到 Preferences，便于列表展示
            appendFeedbackIndex(file.absolutePath)
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /** 读取已保存的反馈文件路径列表（最近在前，最多 50 条） */
    fun listFeedback(): List<String> =
        prefs.get(KEY_FEEDBACK_INDEX, "").orEmpty().split('\n').filter { it.isNotBlank() }

    private fun appendFeedbackIndex(path: String) {
        val existing = prefs.get(KEY_FEEDBACK_INDEX, "").orEmpty()
        val lines = (listOf(path) + existing.split('\n').filter { it.isNotBlank() }).take(50)
        prefs.put(KEY_FEEDBACK_INDEX, lines.joinToString("\n"))
        prefs.flush()
    }

    /**
     * 拉取 version.json 中的版本日志（releaseNotes）。
     *
     * 多镜像源尝试：jsDelivr fastly → jsDelivr → raw.githubusercontent。
     * @return 版本号 + 发布日志的合并文本，失败返回 null
     */
    suspend fun fetchChangelog(): String? = withContext(Dispatchers.IO) {
        for (url in VERSION_JSON_URLS) {
            val result = fetchChangelogFromUrl(url)
            if (result != null) return@withContext result
        }
        null
    }

    private fun fetchChangelogFromUrl(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                useCaches = false
                setRequestProperty("User-Agent", "LiquidGlass-Desktop/2.11.0")
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val version = json.optString("version", "")
            val notes = json.optString("releaseNotes", "")
            if (notes.isBlank()) return null
            buildString {
                if (version.isNotBlank()) appendLine("最新版本：v$version")
                append(notes)
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        // jsDelivr 国内镜像优先，raw.githubusercontent 兜底
        private const val BETA_URL =
            "https://cdn.jsdelivr.net/gh/jiangtengqiao/liquid-glass@main/beta-version.json"
        private const val BETA_URL_FALLBACK =
            "https://raw.githubusercontent.com/jiangtengqiao/liquid-glass/main/beta-version.json"
        private const val KEY_CODE = "beta_pioneer_code"
        private const val KEY_GEN_TIME = "beta_pioneer_gen_time"
        /** 90 天有效期 */
        private const val VALIDITY_MILLIS = 90L * 24 * 3600 * 1000

        // ---- Beta 申请 / 反馈相关 key ----
        private const val KEY_APP_NICKNAME = "beta_app_nickname"
        private const val KEY_APP_EMAIL = "beta_app_email"
        private const val KEY_APP_USECASE = "beta_app_usecase"
        private const val KEY_APP_REASON = "beta_app_reason"
        private const val KEY_APP_SUBMITTED_AT = "beta_app_submitted_at"
        private const val KEY_APP_STATUS = "beta_app_status"
        private const val KEY_GITHUB_TOKEN = "beta_github_token"
        private const val KEY_FEEDBACK_INDEX = "beta_feedback_index"

        /** GitHub Issues API（远程提交 Beta 申请，需 Token） */
        private const val GITHUB_ISSUES_API =
            "https://api.github.com/repos/jiangtengqiao/liquid-glass/issues"

        /** version.json 多镜像源（用于拉取版本日志 releaseNotes） */
        private val VERSION_JSON_URLS = listOf(
            "https://fastly.jsdelivr.net/gh/jiangtengqiao/liquid-glass@main/version.json",
            "https://cdn.jsdelivr.net/gh/jiangtengqiao/liquid-glass@main/version.json",
            "https://raw.githubusercontent.com/jiangtengqiao/liquid-glass/main/version.json"
        )
    }
}
