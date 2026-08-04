package com.liquidglass.desktop.system

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.awt.Desktop
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Desktop 端更新检查器。
 *
 * 与 Android 端 UpdateChecker 逻辑一致：
 * - 从 version.json 拉取最新版本信息（多源 + cache busting）
 * - 对比本地版本号
 * - 检测到新版本时，引导用户用系统浏览器打开下载链接
 *
 * Desktop 端无 PackageInfo，本地版本号硬编码在 [LOCAL_VERSION] / [LOCAL_VERSION_CODE]。
 */
object UpdateChecker {

    /** 当前 Desktop 端版本（需与 build.gradle.kts packageVersion 保持一致） */
    const val LOCAL_VERSION = "2.9.4"
    const val LOCAL_VERSION_CODE = 53

    private const val VERSION_JSON_GITHUB =
        "https://raw.githubusercontent.com/jiangtengqiao/liquid-glass/main/version.json"
    private const val VERSION_JSON_GITHUBPROXY =
        "https://gh-proxy.com/https://raw.githubusercontent.com/jiangtengqiao/liquid-glass/main/version.json"
    private const val VERSION_JSON_GHFASTLY =
        "https://ghfast.top/https://raw.githubusercontent.com/jiangtengqiao/liquid-glass/main/version.json"
    private const val VERSION_JSON_JSDELIVR =
        "https://cdn.jsdelivr.net/gh/jiangtengqiao/liquid-glass@main/version.json"
    private const val VERSION_JSON_JSDELIVR_FASTLY =
        "https://fastly.jsdelivr.net/gh/jiangtengqiao/liquid-glass@main/version.json"

    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val UA = "LiquidGlass-Desktop/2.9.4"

    /** 上次成功检查时间戳（节流：30 分钟内不重复请求） */
    @Volatile
    private var lastCheckAtMs: Long = 0L
    private const val MIN_CHECK_INTERVAL_MS = 30L * 60 * 1000

    /**
     * 更新信息。远端 versionCode 大于本地时返回。
     * downloadUrl 优先取 desktopDownloadUrl，缺失时回退到 downloadUrl。
     */
    data class UpdateInfo(
        val version: String,
        val versionCode: Int,
        val downloadUrl: String,
        val releaseNotes: String
    )

    /**
     * 检查更新。需在 IO 调度器中调用。
     *
     * @param force 强制检查（忽略节流），供"手动检查更新"按钮使用
     * @return 有新版本时返回 [UpdateInfo]；已是最新或网络不可达时返回 null
     */
    suspend fun checkForUpdate(force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!force) {
            val now = System.currentTimeMillis()
            if (now - lastCheckAtMs < MIN_CHECK_INTERVAL_MS) return@withContext null
        }
        lastCheckAtMs = System.currentTimeMillis()

        val cacheBust = System.currentTimeMillis()
        val urls = listOf(
            "$VERSION_JSON_GITHUB?t=$cacheBust",
            "$VERSION_JSON_GITHUBPROXY?t=$cacheBust",
            "$VERSION_JSON_GHFASTLY?t=$cacheBust",
            "$VERSION_JSON_JSDELIVR?t=$cacheBust",
            "$VERSION_JSON_JSDELIVR_FASTLY?t=$cacheBust"
        )

        for (baseUrl in urls) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Pragma", "no-cache")
                }
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    continue
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val parsed = JSONObject(body)
                val remoteVc = parsed.optInt("versionCode", 0)
                // 脏缓存检测：CDN 返回比本地还旧的版本，跳过该源
                if (remoteVc in 1..(LOCAL_VERSION_CODE - 1)) continue

                val latestVersion = parsed.optString("version", "")
                // Desktop 优先 desktopDownloadUrl，缺失回退 downloadUrl（兼容旧 version.json）
                val downloadUrl = parsed.optString("desktopDownloadUrl", "")
                    .ifEmpty { parsed.optString("downloadUrl", "") }
                if (latestVersion.isEmpty() || downloadUrl.isEmpty()) continue

                return@withContext if (remoteVc > LOCAL_VERSION_CODE) {
                    UpdateInfo(
                        version = latestVersion,
                        versionCode = remoteVc,
                        downloadUrl = downloadUrl,
                        releaseNotes = parsed.optString("releaseNotes", "")
                    )
                } else {
                    null
                }
            } catch (_: Exception) {
                conn?.disconnect()
                continue
            }
        }
        null
    }

    /**
     * 用系统默认浏览器打开下载链接（Desktop 端无自动安装，引导用户手动下载安装）。
     */
    fun openDownloadPage(url: String) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            } else {
                // 某些平台 Desktop.browse 不可用，回退到运行系统命令
                val os = System.getProperty("os.name").lowercase()
                val cmd = if (os.contains("win")) {
                    arrayOf("cmd", "/c", "start", "", url)
                } else if (os.contains("mac")) {
                    arrayOf("open", url)
                } else {
                    arrayOf("xdg-open", url)
                }
                Runtime.getRuntime().exec(cmd)
            }
        } catch (_: Exception) {
            // 静默失败，避免影响主流程
        }
    }

    /**
     * 应用内下载更新包，实时回调进度与阶段（与 BetaPioneerManager.downloadBeta 同思路）。
     *
     * 多镜像源优先级：jsDelivr fastly > jsDelivr > ghfast > gh-proxy > GitHub raw
     * 国内访问速度：jsDelivr 通常最快
     *
     * @param info 更新信息
     * @param targetDir 目标目录（自动创建）
     * @param onProgress (已下载字节, 总字节)，总字节<0 表示未知
     * @param onStage 当前阶段文案
     * @return 下载成功的本地文件，失败返回 null
     */
    suspend fun downloadUpdate(
        info: UpdateInfo,
        targetDir: java.io.File,
        onProgress: (Long, Long) -> Unit,
        onStage: (String) -> Unit
    ): java.io.File? = withContext(Dispatchers.IO) {
        val fileName = info.downloadUrl.substringAfterLast('/', "LiquidGlass-update.exe")
        val target = java.io.File(targetDir, fileName)
        val tmp = java.io.File(target.parentFile, target.name + ".part")

        // 多镜像源（GitHub release 资产需通过代理加速）
        val mirrorUrls = buildList {
            // jsDelivr 不支持 release 资产，仅代理仓库文件；release 资产用 gh-proxy 加速
            val raw = info.downloadUrl
            add(raw) // 原始 GitHub release（国外快）
            add("https://gh-proxy.com/$raw") // gh-proxy
            add("https://ghfast.top/$raw") // ghfast
            add("https://mirror.ghproxy.com/$raw") // mirror.ghproxy
        }

        if (!targetDir.exists()) targetDir.mkdirs()

        for ((idx, url) in mirrorUrls.withIndex()) {
            var conn: HttpURLConnection? = null
            try {
                onStage("正在连接服务器（镜像 ${idx + 1}/${mirrorUrls.size}）...")
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    useCaches = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", UA)
                    instanceFollowRedirects = true
                }
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    continue
                }
                val total = conn.contentLengthLong.let { if (it > 0) it else -1L }
                onStage("正在下载: $fileName")

                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        var read: Int
                        var downloaded = 0L
                        var lastReport = 0L
                        while (true) {
                            read = input.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            downloaded += read
                            if (downloaded - lastReport >= 128 * 1024 || read < buf.size) {
                                onProgress(downloaded, total)
                                lastReport = downloaded
                            }
                        }
                    }
                }
                conn.disconnect()

                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true); tmp.delete()
                }
                onStage("下载完成")
                return@withContext target
            } catch (e: Exception) {
                conn?.disconnect()
                onStage("镜像 ${idx + 1} 失败：${e.message ?: "未知"}，尝试下一个...")
                continue
            }
        }
        onStage("所有镜像下载失败，请检查网络后重试")
        null
    }

    /**
     * 启动已下载的安装包（Windows 下用 cmd /c start 启动 exe 安装程序）。
     * 调用方应在确认下载成功后调用，并提示用户即将退出应用安装。
     */
    fun launchInstaller(installerPath: String): Boolean {
        return try {
            val os = System.getProperty("os.name").lowercase()
            val cmd = if (os.contains("win")) {
                arrayOf("cmd", "/c", "start", "", installerPath)
            } else if (os.contains("mac")) {
                arrayOf("open", installerPath)
            } else {
                arrayOf("xdg-open", installerPath)
            }
            Runtime.getRuntime().exec(cmd)
            true
        } catch (_: Exception) {
            false
        }
    }
}
