package com.liquidglass.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新信息。当远端 versionCode 大于本地时由 [UpdateChecker.checkForUpdate] 返回。
 */
data class UpdateInfo(
    val version: String,
    val versionCode: Int,
    val downloadUrl: String,
    val releaseNotes: String
)

/**
 * 应用自更新工具。
 *
 * 与旧版（AboutScreen 内联实现）相比：
 * - 检查更新与下载解耦，检查只返回 [UpdateInfo]，下载由 UI 层按需触发；
 * - 下载使用 256KB 缓冲区且无速度限制，readTimeout 放宽到 5 分钟以容纳大文件；
 * - 进度回调最多每 500ms 上报一次，避免逐次 read 刷新状态导致弹窗 UI 频繁跳动。
 */
object UpdateChecker {

    private const val VERSION_JSON_GITHUB =
        "https://raw.githubusercontent.com/jiangtengqiao/liquid-glass/main/version.json"
    // GitHub raw 国内常超时，加镜像提高可达性
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
    private const val APK_CONNECT_TIMEOUT_MS = 8_000
    private const val APK_READ_TIMEOUT_MS = 300_000
    private const val DOWNLOAD_BUFFER_SIZE = 262_144 // 256KB，无速度限制
    private const val PROGRESS_THROTTLE_MS = 500L

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"

    /** 上次成功检查的时间戳（用于节流，避免短时间内重复请求） */
    @Volatile
    private var lastCheckAtMs: Long = 0L
    /** 节流间隔：两次检查至少间隔 30 分钟，防止频繁请求 */
    private const val MIN_CHECK_INTERVAL_MS = 30L * 60 * 1000

    /**
     * 检查更新。该函数为阻塞 IO，调用方需在 [kotlinx.coroutines.Dispatchers.IO] 中调用。
     *
     * 改进点：
     * - 多源 + 强制 cache busting：GitHub raw 优先（实时无缓存），jsdelivr 兜底（带 `?t=` 防缓存）
     * - jsdelivr CDN 默认 12h 缓存，对 query string 不敏感，因此在源返回的 versionCode 小于
     *   本地时（脏缓存）跳过该源，继续尝试下一个源；只要任一源返回大于本地的 versionCode 即返回
     * - 节流：两次成功检查至少间隔 [MIN_CHECK_INTERVAL_MS]，避免用户每次切到后台再回前台都打接口
     *
     * @param force 强制检查（忽略节流），供"手动检查更新"按钮使用
     * @return 有新版本时返回 [UpdateInfo]；已是最新、网络不可达或所有源均返回脏缓存时返回 null。
     */
    fun checkForUpdate(context: Context, force: Boolean = false): UpdateInfo? {
        // 节流：非强制且距上次检查未超过 MIN_CHECK_INTERVAL_MS，直接返回 null
        if (!force) {
            val now = System.currentTimeMillis()
            if (now - lastCheckAtMs < MIN_CHECK_INTERVAL_MS) return null
        }
        lastCheckAtMs = System.currentTimeMillis()

        val cacheBust = System.currentTimeMillis()
        // GitHub raw 优先（实时不缓存），代理镜像次之（国内可达），jsdelivr 最后（曾出现 12h+ 旧缓存）
        val urls = listOf(
            "$VERSION_JSON_GITHUB?t=$cacheBust",
            "$VERSION_JSON_GITHUBPROXY?t=$cacheBust",
            "$VERSION_JSON_GHFASTLY?t=$cacheBust",
            "$VERSION_JSON_JSDELIVR?t=$cacheBust",
            "$VERSION_JSON_JSDELIVR_FASTLY?t=$cacheBust"
        )

        val currentVersionCode = getCurrentVersionCode(context)
        if (currentVersionCode <= 0) return null

        for (baseUrl in urls) {
            try {
                val conn = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Pragma", "no-cache")
                }
                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    conn.disconnect()
                    continue
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val parsed = JSONObject(body)
                val remoteVc = parsed.optInt("versionCode", 0)
                // 脏缓存检测：源返回的 versionCode 小于当前版本，疑似 CDN 旧缓存，跳过该源
                if (remoteVc in 1..(currentVersionCode - 1)) {
                    continue
                }

                val latestVersion = parsed.optString("version", "")
                val downloadUrl = parsed.optString("downloadUrl", "")
                if (latestVersion.isEmpty() || downloadUrl.isEmpty()) continue

                return if (remoteVc > currentVersionCode) {
                    UpdateInfo(
                        version = latestVersion,
                        versionCode = remoteVc,
                        downloadUrl = downloadUrl,
                        releaseNotes = parsed.optString("releaseNotes", "")
                    )
                } else {
                    // 已是最新版本
                    null
                }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    /**
     * 下载 APK。无速度限制，使用 256KB 缓冲区；自动尝试多个 GitHub 镜像。
     * 进度回调最多每 500ms 触发一次，避免 UI 跳动。
     *
     * @return 下载成功的 [File]，失败返回 null。
     */
    fun downloadApk(context: Context, url: String, onProgress: (Float) -> Unit): File? {
        val apkUrls = buildApkMirrorUrls(url)
        for (apkUrlStr in apkUrls) {
            try {
                val conn = (URL(apkUrlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = APK_CONNECT_TIMEOUT_MS
                    readTimeout = APK_READ_TIMEOUT_MS
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                }
                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    conn.disconnect()
                    continue
                }
                val totalSize = conn.contentLength.toLong()
                val outFile = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "update.apk"
                )
                FileOutputStream(outFile).use { outputStream ->
                    conn.inputStream.use { inputStream ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        var downloaded = 0L
                        var bytesRead: Int
                        var lastReportTime = 0L
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (totalSize > 0) {
                                val now = System.currentTimeMillis()
                                if (now - lastReportTime >= PROGRESS_THROTTLE_MS) {
                                    onProgress(downloaded.toFloat() / totalSize)
                                    lastReportTime = now
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
                if (totalSize > 0) onProgress(1f)
                return outFile
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    /**
     * 调用系统包安装器打开已下载的 APK（通过 FileProvider 暴露）。
     */
    fun installApk(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
                "application/vnd.android.package-archive"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun getCurrentVersionCode(context: Context): Int {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).let {
                if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt()
                else @Suppress("DEPRECATION") it.versionCode
            }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * 根据 GitHub 直连 downloadUrl 生成多个镜像 URL（镜像优先，直连兜底）。
     * 国内直连 GitHub releases 常超时，走镜像可稳定下载。
     */
    private fun buildApkMirrorUrls(githubUrl: String): List<String> {
        if (!githubUrl.contains("github.com/")) return listOf(githubUrl)
        val path = githubUrl.substringAfter("https://github.com/")
        return listOf(
            "https://cors.isteed.cc/github.com/$path",
            "https://gh-proxy.com/https://github.com/$path",
            "https://ghproxy.net/https://github.com/$path",
            githubUrl // GitHub 直连兜底
        )
    }
}
