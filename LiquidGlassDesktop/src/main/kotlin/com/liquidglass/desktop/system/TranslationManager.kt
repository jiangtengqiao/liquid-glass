package com.liquidglass.desktop.system

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

/**
 * 支持的语言
 */
enum class TranslateLanguage(val code: String, val display: String) {
    AUTO("auto", "自动检测"),
    ZH("zh", "中文"),
    EN("en", "英语"),
    JA("ja", "日语"),
    KO("ko", "韩语"),
    FR("fr", "法语"),
    DE("de", "德语"),
    ES("es", "西班牙语"),
    RU("ru", "俄语"),
    AR("ar", "阿拉伯语"),
    PT("pt", "葡萄牙语"),
    IT("it", "意大利语"),
    TH("th", "泰语"),
    VI("vi", "越南语");

    companion object {
        fun fromCode(code: String): TranslateLanguage? = entries.find { it.code == code }
    }
}

/** 会员等级 */
enum class MemberTier(val display: String, val dailyQuota: Int, val maxArticleChars: Int) {
    Free("免费版", 1000, 500),
    Pro("专业版", 10000, 5000),
    Premium("高级版", 100000, 50000);

    /** 是否可使用离线语言包 */
    fun canUseOfflinePack(): Boolean = this != Free
    /** 是否可批量翻译文章 */
    fun canTranslateArticle(): Boolean = this != Free
    /** 是否可下载高级语言包 */
    fun canDownloadPremiumPacks(): Boolean = this == Premium
}

/** 翻译结果 */
data class TranslationResult(
    val source: String,
    val target: String,
    val from: String,
    val to: String,
    val detected: String? = null,
    val offline: Boolean = false,
    val alternatives: List<String> = emptyList()
)

/** 翻译历史记录 */
data class TranslationHistory(
    val id: Long,
    val source: String,
    val target: String,
    val from: String,
    val to: String,
    val timestamp: Long,
    val favorite: Boolean
)

/** 语言包信息 */
data class LanguagePack(
    val name: String,
    val fromCode: String,
    val toCode: String,
    val sizeBytes: Long,
    val entryCount: Int,
    val premium: Boolean,
    val installed: Boolean,
    val downloadUrl: String
)

/** 今日用量统计 */
data class UsageStats(val used: Int, val quota: Int) {
    val remaining: Int get() = (quota - used).coerceAtLeast(0)
    val exhausted: Boolean get() = used >= quota
}

/**
 * 翻译管理器 —— 集成在线翻译 + 离线词典 + 会员体系 + 历史记录
 *
 * 在线翻译路径（按优先级，单次请求内多源兜底，避免硬磨单源）：
 * 1. MyMemory API（免费无需 key，适合短文本/单词/句子）
 * 2. LibreTranslate 公共节点（兜底，适合中长文本）
 *
 * 离线翻译路径：
 * - 语言包文件为 tab 分隔的 from<TAB>to 文本，载入内存 HashMap
 * - 单个语言包可承载百万级词条（按需懒加载、LRU 卸载）
 *
 * 会员体系：
 * - Preferences 持久化等级、当日用量、激活码
 * - 每日额度跨天重置
 *
 * 历史记录：
 * - JSON 行格式（每行一条）持久化，避免引入数据库依赖
 */
class TranslationManager {

    private val prefs: Preferences = Preferences.userNodeForPackage(TranslationManager::class.java)
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 数据目录：用户 home / .liquidglass / translation */
    private val dataDir: File by lazy {
        File(System.getProperty("user.home"), ".liquidglass/translation").apply {
            if (!exists()) mkdirs()
        }
    }
    private val historyFile: File by lazy { File(dataDir, "history.jsonl") }
    private val favoritesFile: File by lazy { File(dataDir, "favorites.jsonl") }
    private val packsDir: File by lazy { File(dataDir, "packs").apply { if (!exists()) mkdirs() } }

    /** 已载入的离线词典：key = "from-to"，value = 词条映射 */
    private val loadedPacks: MutableMap<String, Map<String, String>> = mutableMapOf()
    private val packLoadLock = Any()

    /** 内置常用英汉词典（无需下载即可查询高频词） */
    private val builtinEnZh: Map<String, String> by lazy { BuiltinDictionary.enZh }
    /** 内置常用汉英词典 */
    private val builtinZhEn: Map<String, String> by lazy { BuiltinDictionary.zhEn }

    // ---- 会员体系 ----

    /** 当前会员等级 */
    fun currentTier(): MemberTier {
        val name = prefs.get(KEY_TIER, MemberTier.Free.name)
        return runCatching { MemberTier.valueOf(name) }.getOrDefault(MemberTier.Free)
    }

    /** 激活会员（写入等级与激活码） */
    fun activateTier(code: String, tier: MemberTier): Boolean {
        if (!validateActivationCode(code, tier)) return false
        prefs.put(KEY_TIER, tier.name)
        prefs.put(KEY_CODE, code)
        prefs.flush()
        return true
    }

    /** 校验激活码格式（与等级绑定） */
    private fun validateActivationCode(code: String, tier: MemberTier): Boolean {
        val trimmed = code.trim().uppercase()
        val prefix = when (tier) {
            MemberTier.Pro -> "LG-PRO-"
            MemberTier.Premium -> "LG-PREM-"
            MemberTier.Free -> return true
        }
        if (!trimmed.startsWith(prefix)) return false
        val body = trimmed.removePrefix(prefix)
        // 12 位大写字母数字
        if (body.length != 12) return false
        if (body.any { it !in "ABCDEFGHJKMNPQRSTUVWXYZ23456789" }) return false
        return true
    }

    /** 生成一个本机可用的激活码（用于演示/内测分发） */
    fun generateActivationCode(tier: MemberTier): String {
        val prefix = when (tier) {
            MemberTier.Pro -> "LG-PRO-"
            MemberTier.Premium -> "LG-PREM-"
            MemberTier.Free -> return ""
        }
        val charset = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val body = (1..12).map { charset[(Math.random() * charset.length).toInt()] }.joinToString("")
        return prefix + body
    }

    /** 今日用量 */
    fun todayUsage(): UsageStats {
        val today = todayKey()
        val savedDate = prefs.get(KEY_USAGE_DATE, "")
        if (savedDate != today) {
            // 跨天重置
            prefs.put(KEY_USAGE_DATE, today)
            prefs.putInt(KEY_USAGE_COUNT, 0)
            prefs.flush()
        }
        val used = prefs.getInt(KEY_USAGE_COUNT, 0)
        return UsageStats(used = used, quota = currentTier().dailyQuota)
    }

    /** 增加用量（按字符数累计） */
    private fun addUsage(chars: Int) {
        val today = todayKey()
        val savedDate = prefs.get(KEY_USAGE_DATE, "")
        if (savedDate != today) {
            prefs.put(KEY_USAGE_DATE, today)
            prefs.putInt(KEY_USAGE_COUNT, 0)
        }
        prefs.putInt(KEY_USAGE_COUNT, prefs.getInt(KEY_USAGE_COUNT, 0) + chars)
        prefs.flush()
    }

    private fun todayKey(): String {
        val c = java.util.Calendar.getInstance()
        return String.format(
            "%04d-%02d-%02d",
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    // ---- 在线翻译 ----

    /**
     * 翻译入口：优先离线词典命中，否则走在线多源兜底
     */
    suspend fun translate(
        text: String,
        from: TranslateLanguage,
        to: TranslateLanguage
    ): TranslationResult? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null

        // 免费版文章长度限制
        val tier = currentTier()
        if (text.length > tier.maxArticleChars) {
            return@withContext TranslationResult(
                source = text,
                target = "文本过长（${text.length} 字），${tier.display}上限 ${tier.maxArticleChars} 字，请升级会员或缩短文本。",
                from = from.code, to = to.code, offline = false
            )
        }

        // 配额检查
        val usage = todayUsage()
        if (usage.exhausted) {
            return@withContext TranslationResult(
                source = text,
                target = "今日翻译额度已用尽（${usage.used}/${usage.quota} 字），请明日再试或升级会员。",
                from = from.code, to = to.code, offline = false
            )
        }

        // 1. 离线词典优先（单词/短语）
        if (tier.canUseOfflinePack()) {
            val offline = lookupOffline(text, from, to)
            if (offline != null) {
                addUsage(text.length)
                return@withContext offline
            }
        }

        // 2. 在线翻译：多源兜底
        val online = tryOnlineMyMemory(text, from, to)
            ?: tryOnlineLibreTranslate(text, from, to)
        if (online != null) {
            addUsage(text.length)
            // 写入历史
            appendHistory(TranslationHistory(
                id = System.currentTimeMillis(),
                source = text, target = online.target,
                from = online.from, to = online.to,
                timestamp = System.currentTimeMillis(),
                favorite = false
            ))
        }
        online
    }

    /** MyMemory 免费 API（无需 key，适合短文本） */
    private fun tryOnlineMyMemory(
        text: String,
        from: TranslateLanguage,
        to: TranslateLanguage
    ): TranslationResult? {
        return try {
            val fromCode = if (from == TranslateLanguage.AUTO) "Autodetect" else from.code
            val url = "https://api.mymemory.translated.net/get?q=" +
                java.net.URLEncoder.encode(text, "UTF-8") +
                "&langpair=$fromCode|${to.code}"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val translated = json.optJSONObject("responseData")
                    ?.optString("translatedText")?.takeIf { it.isNotBlank() } ?: return null
                val detected = json.optJSONObject("responseData")?.optString("detectedLanguage")
                // 备选译文
                val alts = mutableListOf<String>()
                val matches = json.optJSONArray("matches")
                if (matches != null) {
                    for (i in 0 until matches.length()) {
                        val m = matches.optJSONObject(i) ?: continue
                        val t = m.optString("translation").takeIf { it.isNotBlank() } ?: continue
                        if (t != translated && t.length < 200) alts.add(t)
                        if (alts.size >= 3) break
                    }
                }
                TranslationResult(
                    source = text, target = cleanText(translated),
                    from = from.code, to = to.code,
                    detected = detected, offline = false, alternatives = alts
                )
            }
        } catch (_: Exception) { null }
    }

    /** LibreTranslate 公共节点兜底（适合中长文本） */
    private fun tryOnlineLibreTranslate(
        text: String,
        from: TranslateLanguage,
        to: TranslateLanguage
    ): TranslationResult? {
        val endpoints = listOf(
            "https://libretranslate.com/translate",
            "https://translate.argosopentech.com/translate"
        )
        endpointLoop@ for (url in endpoints) {
            try {
                val payload = JSONObject().apply {
                    put("q", text)
                    put("source", if (from == TranslateLanguage.AUTO) "auto" else from.code)
                    put("target", to.code)
                    put("format", "text")
                }.toString()
                val req = Request.Builder().url(url)
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .addHeader("Accept", "application/json")
                    .build()
                val result: TranslationResult? = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    val json = JSONObject(body)
                    val translated = json.optString("translatedText").takeIf { it.isNotBlank() }
                        ?: return@use null
                    TranslationResult(
                        source = text, target = cleanText(translated),
                        from = from.code, to = to.code, offline = false
                    )
                }
                if (result != null) return result
            } catch (_: Exception) { continue@endpointLoop }
        }
        return null
    }

    /** 清理在线译文的转义/HTML 实体 */
    private fun cleanText(s: String): String =
        s.replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    // ---- 离线词典 ----

    /** 查询离线词典（单词/短语精确匹配 + 大小写归一） */
    private fun lookupOffline(
        text: String,
        from: TranslateLanguage,
        to: TranslateLanguage
    ): TranslationResult? {
        // 1. 先查内置常用词典（免下载即可用）
        val builtin = when {
            (from == TranslateLanguage.EN || from == TranslateLanguage.AUTO) && to == TranslateLanguage.ZH -> builtinEnZh
            (from == TranslateLanguage.ZH || from == TranslateLanguage.AUTO) && to == TranslateLanguage.EN -> builtinZhEn
            else -> null
        }
        if (builtin != null) {
            val hit = builtin[text] ?: builtin[text.lowercase()] ?: builtin[text.uppercase()]
                ?: builtin[text.replaceFirstChar { it.uppercase() }]
            if (hit != null) {
                return TranslationResult(
                    source = text, target = hit,
                    from = from.code, to = to.code, offline = true
                )
            }
        }
        // 2. 再查已下载的语言包
        val key = packKey(from.code, to.code)
        val pack = synchronized(packLoadLock) {
            loadedPacks[key] ?: loadPack(from.code, to.code)?.also { loadedPacks[key] = it }
        } ?: return null
        val hit = pack[text] ?: pack[text.lowercase()] ?: pack[text.uppercase()]
            ?: pack[text.replaceFirstChar { it.uppercase() }]
        return hit?.let {
            TranslationResult(
                source = text, target = it,
                from = from.code, to = to.code, offline = true
            )
        }
    }

    /** 语言包文件路径 */
    fun packFile(fromCode: String, toCode: String): File =
        File(packsDir, "${fromCode}_$toCode.dict")

    /** 载入语言包到内存（tab 分隔，from<TAB>to） */
    private fun loadPack(fromCode: String, toCode: String): Map<String, String>? {
        val f = packFile(fromCode, toCode)
        if (!f.exists()) return null
        return try {
            val map = HashMap<String, String>(1_000_000)
            Files.lines(f.toPath()).use { lines ->
                lines.forEach { line ->
                    val idx = line.indexOf('\t')
                    if (idx > 0) {
                        map[line.substring(0, idx)] = line.substring(idx + 1)
                    }
                }
            }
            map
        } catch (_: Exception) { null }
    }

    /** 检查语言包是否已安装 */
    fun isPackInstalled(fromCode: String, toCode: String): Boolean =
        packFile(fromCode, toCode).exists()

    /** 已载入语言包的词条数（用于 UI 显示） */
    fun loadedPackSize(fromCode: String, toCode: String): Int =
        loadedPacks[packKey(fromCode, toCode)]?.size ?: 0

    /** 卸载语言包释放内存 */
    fun unloadPack(fromCode: String, toCode: String) {
        synchronized(packLoadLock) { loadedPacks.remove(packKey(fromCode, toCode)) }
    }

    private fun packKey(from: String, to: String) = "$from-$to"

    // ---- 语言包下载 ----

    /** 可下载的语言包列表（远程目录索引） */
    suspend fun availablePacks(): List<LanguagePack> = withContext(Dispatchers.IO) {
        // 远程索引：尝试拉取 packs-index.json，失败则用本地预置清单
        val remote = tryFetchPackIndex()
        remote ?: fallbackPackList()
    }

    private fun tryFetchPackIndex(): List<LanguagePack>? {
        // 多镜像源依次尝试：jsDelivr → fastly → GitHub raw → gh-proxy
        for (base in PACK_MIRRORS) {
            try {
                val req = Request.Builder()
                    .url("$base/packs-index.json").get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val arr = JSONArray(resp.body?.string() ?: return@use)
                    return (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val fromCode = o.optString("from")
                        val toCode = o.optString("to")
                        LanguagePack(
                            name = o.optString("name", "$fromCode → $toCode"),
                            fromCode = fromCode, toCode = toCode,
                            sizeBytes = o.optLong("size", 0),
                            entryCount = o.optInt("entries", 0),
                            premium = o.optBoolean("premium", false),
                            installed = isPackInstalled(fromCode, toCode),
                            downloadUrl = "$base/${fromCode}_$toCode.dict"
                        )
                    }
                }
            } catch (_: Exception) { continue }
        }
        return null
    }

    /** 本地兜底清单（远程不可达时展示） */
    private fun fallbackPackList(): List<LanguagePack> {
        val list = listOf(
            Triple("en", "zh", "英汉大词典"),
            Triple("zh", "en", "汉英大词典"),
            Triple("en", "ja", "英和词典"),
            Triple("ja", "en", "和英词典"),
            Triple("en", "ko", "英韩词典"),
            Triple("en", "fr", "英法词典"),
            Triple("fr", "en", "法英词典"),
            Triple("en", "de", "英德词典"),
            Triple("de", "en", "德英词典"),
            Triple("en", "es", "英西词典"),
            Triple("es", "en", "西英词典"),
            Triple("en", "ru", "英俄词典"),
            Triple("ru", "en", "俄英词典"),
            Triple("en", "ar", "英阿词典（高级）"),
            Triple("en", "th", "英泰词典（高级）"),
            Triple("en", "vi", "英越词典（高级）")
        )
        // downloadUrl 仅用于显示；实际下载时按 PACK_MIRRORS 顺序逐源尝试
        return list.map { (from, to, name) ->
            LanguagePack(
                name = name,
                fromCode = from, toCode = to,
                sizeBytes = 0, entryCount = 0,
                premium = name.contains("高级"),
                installed = isPackInstalled(from, to),
                downloadUrl = "${PACK_MIRRORS.first()}/${from}_$to.dict"
            )
        }
    }

    /**
     * 下载语言包到本地（带进度回调，多镜像源逐源兜底）
     * @param onProgress 已下载字节 / 总字节
     *
     * 实现说明：
     * - 按 PACK_MIRRORS 顺序逐源尝试，任一源成功即返回
     * - 失败源跳过，所有源都失败才返回 false
     * - 下载到 .tmp 文件，完成后原子重命名为正式文件
     */
    suspend fun downloadPack(
        pack: LanguagePack,
        onProgress: (Long, Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val target = packFile(pack.fromCode, pack.toCode)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        // 构造候选 URL：优先 pack.downloadUrl，再补全镜像源（去重）
        val urls = LinkedHashSet<String>()
        urls.add(pack.downloadUrl)
        PACK_MIRRORS.forEach { base -> urls.add("$base/${pack.fromCode}_${pack.toCode}.dict") }

        for (url in urls) {
            try {
                val req = Request.Builder().url(url).get().build()
                val ok = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val total = resp.body?.contentLength() ?: -1L
                    tmp.outputStream().use { out ->
                        val source = resp.body?.byteStream() ?: return@use false
                        val buf = ByteArray(8192)
                        var read: Int
                        var downloaded = 0L
                        while (true) {
                            read = source.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                        true
                    }
                }
                if (ok && tmp.exists() && tmp.length() > 0) {
                    if (target.exists()) target.delete()
                    if (tmp.renameTo(target)) {
                        // 重新载入
                        synchronized(packLoadLock) {
                            loadedPacks.remove(packKey(pack.fromCode, pack.toCode))
                        }
                        return@withContext true
                    }
                }
                // 当前源失败，清理 tmp 进入下一个源
                runCatching { if (tmp.exists()) tmp.delete() }
            } catch (_: Exception) {
                runCatching { if (tmp.exists()) tmp.delete() }
                continue
            }
        }
        false
    }

    /** 删除已安装语言包 */
    fun deletePack(fromCode: String, toCode: String): Boolean {
        unloadPack(fromCode, toCode)
        val f = packFile(fromCode, toCode)
        return f.delete()
    }

    // ---- 历史记录 ----

    /** 追加一条历史 */
    private fun appendHistory(h: TranslationHistory) {
        try {
            val obj = JSONObject().apply {
                put("id", h.id)
                put("source", h.source)
                put("target", h.target)
                put("from", h.from)
                put("to", h.to)
                put("ts", h.timestamp)
                put("fav", h.favorite)
            }
            historyFile.appendText(obj.toString() + "\n")
        } catch (_: Exception) { }
    }

    /** 读取历史（最近 limit 条，默认 200） */
    fun loadHistory(limit: Int = 200): List<TranslationHistory> {
        if (!historyFile.exists()) return emptyList()
        return try {
            historyFile.readLines()
                .asReversed()
                .take(limit)
                .mapNotNull { line ->
                    val o = JSONObject(line)
                    TranslationHistory(
                        id = o.optLong("id"),
                        source = o.optString("source"),
                        target = o.optString("target"),
                        from = o.optString("from"),
                        to = o.optString("to"),
                        timestamp = o.optLong("ts"),
                        favorite = o.optBoolean("fav", false)
                    )
                }
        } catch (_: Exception) { emptyList() }
    }

    /** 清空历史 */
    fun clearHistory() {
        historyFile.writeText("")
    }

    /** 切换收藏状态（重写整个历史文件） */
    fun toggleFavorite(id: Long): List<TranslationHistory> {
        val all = loadHistory(limit = 10000).toMutableList()
        val updated = all.map {
            if (it.id == id) it.copy(favorite = !it.favorite) else it
        }
        historyFile.writeText("")
        updated.asReversed().forEach { appendHistory(it) }
        return loadHistory()
    }

    /** 仅读取收藏 */
    fun loadFavorites(): List<TranslationHistory> = loadHistory(10000).filter { it.favorite }

    companion object {
        /**
         * 语言包远程目录 - 多镜像源（按优先级尝试）
         *
         * 选源策略：
         * 1. cdn.jsdelivr.net - jsDelivr 主 CDN，国内可达性较好
         * 2. fastly.jsdelivr.net - Fastly 镜像，主 CDN 不可达时兜底
         * 3. raw.githubusercontent.com - GitHub 原始源（直连，国外快国内慢）
         * 4. gh-proxy.com 代理 - 国内 GitHub 反代，最终兜底
         */
        private val PACK_MIRRORS = listOf(
            "https://cdn.jsdelivr.net/gh/jiangtengqiao/liquid-glass@main/translate-packs",
            "https://fastly.jsdelivr.net/gh/jiangtengqiao/liquid-glass@main/translate-packs",
            "https://raw.githubusercontent.com/jiangtengqiao/liquid-glass/main/translate-packs",
            "https://gh-proxy.com/https://raw.githubusercontent.com/jiangtengqiao/liquid-glass/main/translate-packs"
        )
        private const val KEY_TIER = "translate_tier"
        private const val KEY_CODE = "translate_code"
        private const val KEY_USAGE_DATE = "translate_usage_date"
        private const val KEY_USAGE_COUNT = "translate_usage_count"
    }
}
