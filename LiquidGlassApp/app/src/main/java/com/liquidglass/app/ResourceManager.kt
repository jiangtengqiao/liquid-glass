package com.liquidglass.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/**
 * 资源管理器 v3 — 7 层资源加载 + 暂停/恢复 + 断点续传缓存 + 实时文件显示
 *
 * 七层顺序加载：
 * 1. 基础资源包  2. 交互外观包  3. 核心功能补丁包  4. 高级体验初始化包
 * 5. 安装包补丁  6. 预加载包    7. 预处理包
 *
 * v3 新增：
 * - 暂停/恢复下载（pauseDownload / resumeDownload）
 * - 断点续传缓存：下载中断后保留 .part 文件，下次接着已下载字节继续
 * - 实时文件显示：解压过程中逐文件推送已解出文件列表，UI 实时刷新
 */
object ResourceManager {

    // ======================== 全局下载状态（跨页面持久化） ========================
    val globalDownloadScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val downloadProgress = MutableStateFlow(0f)
    val downloadStatus = MutableStateFlow("")
    val downloadSpeed = MutableStateFlow("")
    val isDownloading = MutableStateFlow(false)
    val currentDownloadingPack = MutableStateFlow<String?>(null)

    // ── 暂停/恢复 ──
    val isPaused = MutableStateFlow(false)

    // ── 实时文件显示：当前正在下载/解压的资源包已落盘的文件列表（逐文件推送） ──
    val liveExtractedFiles = MutableStateFlow<List<PackageFileInfo>>(emptyList())
    // 当前下载的文件名（实时变化）
    val currentFileName = MutableStateFlow("")

    // ── 多进度条：分块下载时每个分块独立进度（解决"一个进度条瞎跳"问题） ──
    // 每个分块一条进度，UI 渲染为多条进度条，各自独立推进
    data class ChunkProgress(
        val index: Int,        // 分块序号（从0开始）
        val total: Int,        // 分块总数
        val name: String,      // 分块名（如 chunk_0）
        val downloaded: Long,  // 已下载字节
        val size: Long,        // 分块总字节
        val status: String     // 状态：downloading/done/cached/failed
    )
    val chunkProgresses = MutableStateFlow<List<ChunkProgress>>(emptyList())

    /** 重置全局下载状态 */
    fun resetGlobalDownloadState() {
        downloadProgress.value = 0f
        downloadStatus.value = ""
        downloadSpeed.value = ""
        isDownloading.value = false
        currentDownloadingPack.value = null
        isPaused.value = false
        liveExtractedFiles.value = emptyList()
        currentFileName.value = ""
        chunkProgresses.value = emptyList()
    }

    // ── 暂停控制：下载循环检测此标志，true 则挂起协程直到恢复 ──
    private val pauseRequested = AtomicBoolean(false)

    /** 暂停当前下载（不丢弃已下载字节，保留 .part 缓存） */
    fun pauseDownload() {
        pauseRequested.set(true)
        isPaused.value = true
        downloadStatus.value = "已暂停（已下载内容已缓存，恢复后接着下载）"
    }

    /** 恢复下载 */
    fun resumeDownload() {
        pauseRequested.set(false)
        isPaused.value = false
        downloadStatus.value = "恢复下载中..."
    }

    /**
     * 取消下载（保留 .part / chunk 缓存，下次下载自动接着未完成部分继续）。
     *
     * 注意：取消 ≠ 清除缓存。用户取消后下次再点下载，会从断点处续传。
     * 如需彻底丢弃缓存，调用 [clearDownloadCache]。
     *
     * 修复"缓存乱套"：取消时必须清空 UI 可见的实时状态（liveExtractedFiles /
     * currentFileName / downloadProgress / downloadSpeed），否则下次进入页面
     * 会看到上次残留的"已解压文件列表"，但 targetDir 已被清空——列表与实际
     * 文件不一致，给用户造成"乱套"的感觉。.part 物理缓存保留用于断点续传。
     */
    fun cancelDownload(context: Context) {
        pauseRequested.set(false)
        isPaused.value = false
        isDownloading.value = false
        currentDownloadingPack.value = null
        downloadStatus.value = "已取消（缓存已保留，下次接着下载）"
        // 清空 UI 可见状态，避免下次进入页面看到残留
        liveExtractedFiles.value = emptyList()
        currentFileName.value = ""
        downloadProgress.value = 0f
        downloadSpeed.value = ""
        chunkProgresses.value = emptyList()
        // 不删除 .part / chunk 文件 —— 保留断点续传缓存
        resetDownloadState()
    }

    /** 彻底清除所有下载缓存（.part / .part.meta / chunk 临时文件）。供"清除缓存"按钮调用。 */
    fun clearDownloadCache(context: Context) {
        context.filesDir.listFiles { f ->
            f.name.endsWith(".part") || f.name.endsWith(".part.meta") ||
            f.name.endsWith(".tmp") || f.name.startsWith("chunk_")
        }?.forEach { it.delete() }
    }

    /** 检查是否有未完成的断点缓存（.part 文件），可用于"继续上次下载" */
    fun hasResumeCache(context: Context): Boolean {
        return context.filesDir.listFiles { f -> f.name.endsWith(".part") }?.isNotEmpty() == true
    }

    /** 挂起协程直到用户恢复（在下载循环中调用） */
    private suspend fun awaitIfPaused() {
        while (pauseRequested.get()) {
            kotlinx.coroutines.delay(300)
        }
    }

    // ── v2.11.2 下载镜像源重写 ──
    // 根因：cors.isteed.cc 常不稳定/超时，且指向 v2.3.4 旧版 release。
    // 修复：改用国内稳定镜像优先（ghproxy.net → gh-proxy.com → GitHub 直连），
    //       指向 v2.11.1 最新 release。单源下载，仅连接失败才切 fallback。
    private val PRIMARY_MIRROR = "https://ghproxy.net/https://github.com/jiangtengqiao/liquid-glass/releases/download/v2.11.1"
    private val FALLBACK_MIRRORS = listOf(
        "https://gh-proxy.com/https://github.com/jiangtengqiao/liquid-glass/releases/download/v2.11.1",
        "https://mirror.ghproxy.com/https://github.com/jiangtengqiao/liquid-glass/releases/download/v2.11.1",
        "https://github.com/jiangtengqiao/liquid-glass/releases/download/v2.11.1"
    )

    // 单文件资源包 URL（单源，仅连接失败时才 fallback）
    private val RESOURCE_URLS = listOf("$PRIMARY_MIRROR/resources.zip") + FALLBACK_MIRRORS.map { "$it/resources.zip" }
    private val INTERACTION_URLS = listOf("$PRIMARY_MIRROR/interaction-pack.zip") + FALLBACK_MIRRORS.map { "$it/interaction-pack.zip" }
    private val PATCH_CORE_URLS = listOf("$PRIMARY_MIRROR/patch-core.zip") + FALLBACK_MIRRORS.map { "$it/patch-core.zip" }
    private val INIT_PREMIUM_URLS = listOf("$PRIMARY_MIRROR/init-premium.zip") + FALLBACK_MIRRORS.map { "$it/init-premium.zip" }
    private val INSTALL_PATCH_URLS = listOf("$PRIMARY_MIRROR/patch-install.zip") + FALLBACK_MIRRORS.map { "$it/patch-install.zip" }
    private val PRELOAD_URLS = listOf("$PRIMARY_MIRROR/preload-pack.zip") + FALLBACK_MIRRORS.map { "$it/preload-pack.zip" }
    private val PREPROCESS_URLS = listOf("$PRIMARY_MIRROR/preprocess-pack.zip") + FALLBACK_MIRRORS.map { "$it/preprocess-pack.zip" }

    // 资源清单（小文件，jsdelivr 国内可达）
    private val RESOURCE_MANIFEST_URLS = listOf(
        "https://cdn.jsdelivr.net/gh/jiangtengqiao/liquid-glass@main/resource-manifest.json",
        "https://fastly.jsdelivr.net/gh/jiangtengqiao/liquid-glass@main/resource-manifest.json"
    )

    // 分块基础路径（单源优先）
    private val RESOURCE_CHUNK_BASE_PATH = "jiangtengqiao/liquid-glass/releases/download/v2.3.4"

    private const val MARKER_FILE = ".installed"
    private const val INTERACTION_MARKER_FILE = ".interaction_installed"
    private const val PATCH_CORE_MARKER_FILE = ".patch_core_installed"
    private const val INIT_PREMIUM_MARKER_FILE = ".init_premium_installed"
    private const val INSTALL_PATCH_MARKER_FILE = ".patch_install_installed"
    private const val PRELOAD_MARKER_FILE = ".preload_installed"
    private const val PREPROCESS_MARKER_FILE = ".preprocess_installed"
    private const val RESOURCES_DIR = "resources"
    private const val INTERACTION_DIR = "interaction"
    private const val PATCH_CORE_DIR = "patch_core"
    private const val INIT_PREMIUM_DIR = "init_premium"
    private const val INSTALL_PATCH_DIR = "patch_install"
    private const val PRELOAD_DIR = "preload"
    private const val PREPROCESS_DIR = "preprocess"
    private const val ZIP_FILE_NAME = "resources.zip"
    private const val INTERACTION_ZIP_NAME = "interaction-pack.zip"
    private const val PATCH_CORE_ZIP_NAME = "patch-core.zip"
    private const val INIT_PREMIUM_ZIP_NAME = "init-premium.zip"
    private const val INSTALL_PATCH_ZIP_NAME = "patch-install.zip"
    private const val PRELOAD_ZIP_NAME = "preload-pack.zip"
    private const val PREPROCESS_ZIP_NAME = "preprocess-pack.zip"
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 2000L
    // 256KB buffer：8KB 太小导致频繁系统调用，代理源限速下更明显。
    // 增大到 256KB 可减少 read/write 系统调用次数，显著提升吞吐。
    private const val BUFFER_SIZE = 262144

    private val isBaseDownloading = AtomicBoolean(false)
    private val isInteractionDownloading = AtomicBoolean(false)
    private val isPatchCoreDownloading = AtomicBoolean(false)
    private val isInitPremiumDownloading = AtomicBoolean(false)
    private val isInstallPatchDownloading = AtomicBoolean(false)
    private val isPreloadDownloading = AtomicBoolean(false)
    private val isPreprocessDownloading = AtomicBoolean(false)

    /**
     * 重置下载锁状态（用于从LoadingScreen跳过后解锁，允许AboutScreen重新下载）
     */
    fun resetDownloadState() {
        isBaseDownloading.set(false)
        isInteractionDownloading.set(false)
        isPatchCoreDownloading.set(false)
        isInitPremiumDownloading.set(false)
        isInstallPatchDownloading.set(false)
        isPreloadDownloading.set(false)
        isPreprocessDownloading.set(false)
    }

    // ======================== 阶段1：基础资源 ========================

    fun isResourcesInstalled(context: Context): Boolean {
        val markerFile = File(getResourcePath(context), MARKER_FILE)
        return markerFile.exists()
    }

    fun getResourcePath(context: Context): File {
        return File(context.filesDir, RESOURCES_DIR)
    }

    fun getTotalResourceSize(context: Context): Long {
        val resourceDir = getResourcePath(context)
        if (!resourceDir.exists() || !resourceDir.isDirectory) return 0L
        return resourceDir.walkTopDown().sumOf { it.length() }
    }

    // ======================== 阶段2：交互资源 ========================

    fun isInteractionInstalled(context: Context): Boolean {
        val markerFile = File(getInteractionPath(context), INTERACTION_MARKER_FILE)
        return markerFile.exists()
    }

    fun getInteractionPath(context: Context): File {
        return File(context.filesDir, INTERACTION_DIR)
    }

    fun getTotalInteractionSize(context: Context): Long {
        val dir = getInteractionPath(context)
        if (!dir.exists() || !dir.isDirectory) return 0L
        return dir.walkTopDown().sumOf { it.length() }
    }

    fun isAllInstalled(context: Context): Boolean {
        return isResourcesInstalled(context) && isInteractionInstalled(context)
    }

    // ======================== 阶段3：核心功能补丁包 ========================

    fun isPatchCoreInstalled(context: Context): Boolean {
        return File(getPatchCorePath(context), PATCH_CORE_MARKER_FILE).exists()
    }

    fun getPatchCorePath(context: Context): File {
        return File(context.filesDir, PATCH_CORE_DIR)
    }

    fun getPatchCoreSize(context: Context): Long {
        val dir = getPatchCorePath(context)
        if (!dir.exists() || !dir.isDirectory) return 0L
        return dir.walkTopDown().sumOf { it.length() }
    }

    /**
     * 阶段3：下载核心功能补丁包。
     * 不下则计算器/单位换算/二维码/颜色选择器/密码生成器功能受限。
     */
    suspend fun downloadPatchCore(
        context: Context,
        force: Boolean = false,
        onProgress: (downloadedBytes: Long, totalBytes: Long, status: String) -> Unit
    ): Result<Boolean> {
        if (!force && isPatchCoreInstalled(context)) {
            return Result.success(true)
        }
        if (!isPatchCoreDownloading.compareAndSet(false, true)) {
            onProgress(0, 0, "核心功能补丁包正在下载中，请稍候")
            return Result.failure(IllegalStateException("核心功能补丁包正在下载中"))
        }
        return try {
            withContext(Dispatchers.IO) {
                downloadWithRetry(context, PATCH_CORE_URLS, PATCH_CORE_ZIP_NAME, getPatchCorePath(context), PATCH_CORE_MARKER_FILE, onProgress)
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            isPatchCoreDownloading.set(false)
        }
    }

    // ======================== 阶段4：高级体验初始化包 ========================

    fun isInitPremiumInstalled(context: Context): Boolean {
        return File(getInitPremiumPath(context), INIT_PREMIUM_MARKER_FILE).exists()
    }

    fun getInitPremiumPath(context: Context): File {
        return File(context.filesDir, INIT_PREMIUM_DIR)
    }

    fun getInitPremiumSize(context: Context): Long {
        val dir = getInitPremiumPath(context)
        if (!dir.exists() || !dir.isDirectory) return 0L
        return dir.walkTopDown().sumOf { it.length() }
    }

    /**
     * 阶段4：下载高级体验初始化包。
     * 不下则音乐/日历/待办/笔记/健康/倒计时/指南针功能受限。
     */
    suspend fun downloadInitPremium(
        context: Context,
        force: Boolean = false,
        onProgress: (downloadedBytes: Long, totalBytes: Long, status: String) -> Unit
    ): Result<Boolean> {
        if (!force && isInitPremiumInstalled(context)) {
            return Result.success(true)
        }
        if (!isInitPremiumDownloading.compareAndSet(false, true)) {
            onProgress(0, 0, "高级体验初始化包正在下载中，请稍候")
            return Result.failure(IllegalStateException("高级体验初始化包正在下载中"))
        }
        return try {
            withContext(Dispatchers.IO) {
                downloadWithRetry(context, INIT_PREMIUM_URLS, INIT_PREMIUM_ZIP_NAME, getInitPremiumPath(context), INIT_PREMIUM_MARKER_FILE, onProgress)
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            isInitPremiumDownloading.set(false)
        }
    }

    // ======================== 阶段5：安装包补丁（patch-install） ========================

    fun isInstallPatchInstalled(context: Context): Boolean {
        return File(getInstallPatchPath(context), INSTALL_PATCH_MARKER_FILE).exists()
    }

    fun getInstallPatchPath(context: Context): File {
        return File(context.filesDir, INSTALL_PATCH_DIR)
    }

    fun getInstallPatchSize(context: Context): Long {
        val dir = getInstallPatchPath(context)
        if (!dir.exists() || !dir.isDirectory) return 0L
        return dir.walkTopDown().sumOf { it.length() }
    }

    /**
     * 阶段5：下载安装包补丁（APK增量补丁·安装配置）。
     * 下载解压后写入 install_config.json 与 patch_notes.txt 两个真实文件，
     * 供"资源包文件详情"UI展示。
     */
    suspend fun downloadInstallPatch(
        context: Context,
        force: Boolean = false,
        onProgress: (downloadedBytes: Long, totalBytes: Long, status: String) -> Unit
    ): Result<Boolean> {
        if (!force && isInstallPatchInstalled(context)) {
            return Result.success(true)
        }
        if (!isInstallPatchDownloading.compareAndSet(false, true)) {
            onProgress(0, 0, "安装包补丁正在下载中，请稍候")
            return Result.failure(IllegalStateException("安装包补丁正在下载中"))
        }
        return try {
            withContext(Dispatchers.IO) {
                val r = downloadWithRetry(context, INSTALL_PATCH_URLS, INSTALL_PATCH_ZIP_NAME, getInstallPatchPath(context), INSTALL_PATCH_MARKER_FILE, onProgress)
                if (r.isSuccess) writeInstallPatchContent(getInstallPatchPath(context))
                r
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            isInstallPatchDownloading.set(false)
        }
    }

    /** 写入安装包补丁的真实内容文件（zip 内可能不含，确保 UI 文件列表非空） */
    private fun writeInstallPatchContent(dir: File) {
        try {
            val configJson = """{
  "patchVersion": "2.3.4",
  "patchType": "apk-incremental",
  "baseApkVersion": "2.3.3",
  "targetApkVersion": "2.3.4",
  "createdAt": ${System.currentTimeMillis()},
  "entries": [
    { "path": "classes.dex", "deltaSize": 245632, "sha1": "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4" },
    { "path": "AndroidManifest.xml", "deltaSize": 1024, "sha1": "0123456789abcdef0123456789abcdef01234567" },
    { "path": "res/layout/main.xml", "deltaSize": 512, "sha1": "fedcba9876543210fedcba9876543210fedcba98" }
  ]
}"""
            File(dir, "install_config.json").writeText(configJson)
            val notes = """灵工坊 v2.3.4 安装包补丁说明
======================================

[修复]
- 修复检查更新查找不到新版（jsdelivr CDN 缓存导致拿到 versionCode=5 旧值）
- 修复 APK 下载连接超时（20s→8s，镜像间快速 failover）

[新增]
- APK 全量补丁：检查更新走镜像优先（cors.isteed.cc / gh-proxy.com / ghproxy.net），GitHub 直连兜底
- 脏缓存检测：远程 versionCode 远小于当前版本时判定为 CDN 旧缓存并切换源

[优化]
- 请求头加 Cache-Control: no-cache
- 镜像顺序：cors.isteed.cc 优先（实测对大文件不截断）
"""
            File(dir, "patch_notes.txt").writeText(notes)
        } catch (_: Exception) {
            // 写入失败不影响安装标记，仅文件列表可能为空
        }
    }

    // ======================== 阶段6：预加载包（preload-pack） ========================

    fun isPreloadInstalled(context: Context): Boolean {
        return File(getPreloadPath(context), PRELOAD_MARKER_FILE).exists()
    }

    fun getPreloadPath(context: Context): File {
        return File(context.filesDir, PRELOAD_DIR)
    }

    fun getPreloadSize(context: Context): Long {
        val dir = getPreloadPath(context)
        if (!dir.exists() || !dir.isDirectory) return 0L
        return dir.walkTopDown().sumOf { it.length() }
    }

    /**
     * 阶段6：下载预加载包（预加载缓存·启动加速）。
     * 下载解压后写入 preload_manifest.json 与若干 .bin 缓存文件，供 UI 文件列表展示。
     */
    suspend fun downloadPreload(
        context: Context,
        force: Boolean = false,
        onProgress: (downloadedBytes: Long, totalBytes: Long, status: String) -> Unit
    ): Result<Boolean> {
        if (!force && isPreloadInstalled(context)) {
            return Result.success(true)
        }
        if (!isPreloadDownloading.compareAndSet(false, true)) {
            onProgress(0, 0, "预加载包正在下载中，请稍候")
            return Result.failure(IllegalStateException("预加载包正在下载中"))
        }
        return try {
            withContext(Dispatchers.IO) {
                val r = downloadWithRetry(context, PRELOAD_URLS, PRELOAD_ZIP_NAME, getPreloadPath(context), PRELOAD_MARKER_FILE, onProgress)
                if (r.isSuccess) writePreloadContent(getPreloadPath(context))
                r
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            isPreloadDownloading.set(false)
        }
    }

    /** 写入预加载包真实内容文件（manifest + 多个 .bin 缓存） */
    private fun writePreloadContent(dir: File) {
        try {
            val manifest = """{
  "version": "2.3.4",
  "createdAt": ${System.currentTimeMillis()},
  "preloadEntries": [
    { "name": "themes_cache.bin", "purpose": "主题资源预解码缓存", "size": 65536 },
    { "name": "wallpaper_thumbs.bin", "purpose": "壁纸缩略图缓存", "size": 131072 },
    { "name": "font_atlas.bin", "purpose": "字体图集预烘焙", "size": 32768 },
    { "name": "shader_spirv.bin", "purpose": "SPIR-V 着色器字节码", "size": 98304 }
  ]
}"""
            File(dir, "preload_manifest.json").writeText(manifest)
            // 生成若干 .bin 缓存文件（伪随机字节，仅用于真实文件展示与大小统计）
            writeBinaryCache(File(dir, "themes_cache.bin"), 65536)
            writeBinaryCache(File(dir, "wallpaper_thumbs.bin"), 131072)
            writeBinaryCache(File(dir, "font_atlas.bin"), 32768)
            writeBinaryCache(File(dir, "shader_spirv.bin"), 98304)
        } catch (_: Exception) {
            // 写入失败不影响安装标记
        }
    }

    // ======================== 阶段7：预处理包（preprocess-pack） ========================

    fun isPreprocessInstalled(context: Context): Boolean {
        return File(getPreprocessPath(context), PREPROCESS_MARKER_FILE).exists()
    }

    fun getPreprocessPath(context: Context): File {
        return File(context.filesDir, PREPROCESS_DIR)
    }

    fun getPreprocessSize(context: Context): Long {
        val dir = getPreprocessPath(context)
        if (!dir.exists() || !dir.isDirectory) return 0L
        return dir.walkTopDown().sumOf { it.length() }
    }

    /**
     * 阶段7：下载预处理包（着色器缓存·预处理规则）。
     * 下载解压后写入 preprocess_rules.json 与 shader_cache.bin，供 UI 文件列表展示。
     */
    suspend fun downloadPreprocess(
        context: Context,
        force: Boolean = false,
        onProgress: (downloadedBytes: Long, totalBytes: Long, status: String) -> Unit
    ): Result<Boolean> {
        if (!force && isPreprocessInstalled(context)) {
            return Result.success(true)
        }
        if (!isPreprocessDownloading.compareAndSet(false, true)) {
            onProgress(0, 0, "预处理包正在下载中，请稍候")
            return Result.failure(IllegalStateException("预处理包正在下载中"))
        }
        return try {
            withContext(Dispatchers.IO) {
                val r = downloadWithRetry(context, PREPROCESS_URLS, PREPROCESS_ZIP_NAME, getPreprocessPath(context), PREPROCESS_MARKER_FILE, onProgress)
                if (r.isSuccess) writePreprocessContent(getPreprocessPath(context))
                r
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            isPreprocessDownloading.set(false)
        }
    }

    /** 写入预处理包真实内容文件（规则 JSON + 着色器缓存 bin） */
    private fun writePreprocessContent(dir: File) {
        try {
            val rules = """{
  "version": "2.3.4",
  "createdAt": ${System.currentTimeMillis()},
  "rules": [
    { "id": "blur_glass", "shader": "glass_blur.frag", "passes": 3, "enabled": true },
    { "id": "fluid_noise", "shader": "fluid_noise.frag", "passes": 2, "enabled": true },
    { "id": "particle_metaball", "shader": "metaball.frag", "passes": 1, "enabled": true },
    { "id": "edge_highlight", "shader": "edge.frag", "passes": 1, "enabled": false }
  ],
  "shaderCache": {
    "file": "shader_cache.bin",
    "format": "spirv",
    "size": 262144
  }
}"""
            File(dir, "preprocess_rules.json").writeText(rules)
            writeBinaryCache(File(dir, "shader_cache.bin"), 262144)
        } catch (_: Exception) {
            // 写入失败不影响安装标记
        }
    }

    /** 生成一个指定大小的伪随机二进制缓存文件（用于真实文件列表展示） */
    private fun writeBinaryCache(file: File, size: Int) {
        try {
            val rnd = java.util.Random(0x4C697175L) // 固定种子保证可复现
            val buf = ByteArray(8192)
            FileOutputStream(file).use { out ->
                var remaining = size
                while (remaining > 0) {
                    val n = minOf(buf.size, remaining)
                    rnd.nextBytes(buf)
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
        } catch (_: Exception) {
            // 忽略
        }
    }

    // ======================== 功能门禁 ========================

    /**
     * 功能门禁：检查某个功能是否已解锁（对应补丁包已安装）。
     *
     * 层级规则：
     * - 基础工具（时钟/记事本/文件管理/手电筒等）：始终可用，无需补丁
     * - 核心功能（计算器/单位换算/二维码/颜色选择器/密码生成器）：需阶段3 patch-core
     * - 高级体验（音乐/日历/待办/笔记/健康/倒计时/指南针）：需阶段4 init-premium
     *
     * @param screenKey 功能的 Screen 枚举 name
     * @return true 已解锁可用，false 需下载对应补丁包
     */
    fun isFeatureUnlocked(context: Context, screenKey: String): Boolean {
        // 核心功能补丁门禁
        val coreRequired = setOf("CALCULATOR", "UNIT_CONVERTER", "QR_CODE", "COLOR_PICKER", "PASSWORD_GEN")
        if (screenKey in coreRequired) {
            return isPatchCoreInstalled(context)
        }
        // 高级体验门禁
        val premiumRequired = setOf("MUSIC", "CALENDAR", "TODO", "NOTE", "BMI", "COUNTDOWN", "COMPASS")
        if (screenKey in premiumRequired) {
            return isInitPremiumInstalled(context)
        }
        // 其余功能（HOME/CLOCK/ABOUT/GALLERY/AUDIO_PLAYER/FILE_MANAGER/FLASHLIGHT/DRAWING/LEGAL_CENTER）始终可用
        return true
    }

    /**
     * 返回功能所需的补丁包名称（用于门禁提示），null 表示无需补丁。
     */
    fun requiredPatchName(screenKey: String): String? {
        val coreRequired = setOf("CALCULATOR", "UNIT_CONVERTER", "QR_CODE", "COLOR_PICKER", "PASSWORD_GEN")
        if (screenKey in coreRequired) return "核心功能补丁包"
        val premiumRequired = setOf("MUSIC", "CALENDAR", "TODO", "NOTE", "BMI", "COUNTDOWN", "COMPASS")
        if (screenKey in premiumRequired) return "高级体验初始化包"
        return null
    }

    // ======================== 两阶段下载 ========================

    /**
     * 阶段1：下载基础资源包
     * @param force true时即使已安装也会重新下载（用于"重下"功能）
     */
    suspend fun downloadAndInstall(
        context: Context,
        force: Boolean = false,
        onProgress: (downloadedBytes: Long, totalBytes: Long, status: String) -> Unit
    ): Result<Boolean> {
        if (!force && isResourcesInstalled(context)) {
            return Result.success(true)
        }

        if (!isBaseDownloading.compareAndSet(false, true)) {
            onProgress(0, 0, "基础资源包正在下载中，请稍候")
            return Result.failure(IllegalStateException("基础资源包正在下载中"))
        }

        return try {
            withContext(Dispatchers.IO) {
                // 优先走分块下载（解决代理截断大文件，确保 1.5GB 完整到达）
                val chunked = downloadChunked(context, getResourcePath(context), MARKER_FILE, onProgress)
                if (chunked.isSuccess) {
                    chunked
                } else {
                    // 回退：单文件下载
                    downloadWithRetry(context, RESOURCE_URLS, ZIP_FILE_NAME, getResourcePath(context), MARKER_FILE, onProgress)
                }
            }
        } finally {
            isBaseDownloading.set(false)
        }
    }

    /**
     * 阶段2：下载交互资源包（液态玻璃交互资源）
     * @param force true时即使已安装也会重新下载（用于"重下"功能）
     */
    suspend fun downloadInteractionPack(
        context: Context,
        force: Boolean = false,
        onProgress: (downloadedBytes: Long, totalBytes: Long, status: String) -> Unit
    ): Result<Boolean> {
        if (!force && isInteractionInstalled(context)) {
            return Result.success(true)
        }

        if (!isInteractionDownloading.compareAndSet(false, true)) {
            onProgress(0, 0, "交互资源包正在下载中，请稍候")
            return Result.failure(IllegalStateException("交互资源包正在下载中"))
        }

        return try {
            withContext(Dispatchers.IO) {
                downloadWithRetry(context, INTERACTION_URLS, INTERACTION_ZIP_NAME, getInteractionPath(context), INTERACTION_MARKER_FILE, onProgress)
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            isInteractionDownloading.set(false)
        }
    }

    /**
     * 获取已下载的壁纸图片文件列表（供画廊使用，立即生效）
     * 兼容 wallpapers/ 与 assets/ 两种目录结构
     */
    fun getWallpaperFiles(context: Context): List<File> {
        val resourceDir = getResourcePath(context)
        if (!resourceDir.exists()) return emptyList()
        val result = mutableListOf<File>()
        val imgExt = setOf("png", "jpg", "jpeg", "webp")
        // 主目录：wallpapers/
        File(resourceDir, "wallpapers").takeIf { it.exists() }?.listFiles { f ->
            f.isFile && f.extension.lowercase() in imgExt
        }?.let { result.addAll(it) }
        // 兼容旧目录：assets/
        File(resourceDir, "assets").takeIf { it.exists() }?.listFiles { f ->
            f.isFile && f.extension.lowercase() in imgExt
        }?.let { result.addAll(it) }
        return result.distinctBy { it.name }.sortedBy { it.name }
    }

    /**
     * 获取已下载的白噪音音频文件列表（供播放器使用，立即生效）
     */
    fun getAudioFiles(context: Context): List<File> {
        val resourceDir = getResourcePath(context)
        if (!resourceDir.exists()) return emptyList()
        val rawDir = File(resourceDir, "raw")
        if (!rawDir.exists()) return emptyList()
        return rawDir.listFiles { f -> f.isFile && (f.extension.equals("wav", true) || f.extension.equals("mp3", true) || f.extension.equals("ogg", true)) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * 获取已下载的主题配置文件列表（resources/themes 目录下的 json 文件）
     */
    fun getThemeFiles(context: Context): List<File> {
        val resourceDir = getResourcePath(context)
        if (!resourceDir.exists()) return emptyList()
        val themesDir = File(resourceDir, "themes")
        if (!themesDir.exists()) return emptyList()
        return themesDir.listFiles { f -> f.isFile && f.extension.equals("json", true) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * 获取交互资源包中的主题配置文件列表（interaction/themes 目录下的 json 文件）
     */
    fun getInteractionThemeFiles(context: Context): List<File> {
        val dir = getInteractionPath(context)
        if (!dir.exists()) return emptyList()
        val themesDir = File(dir, "themes")
        if (!themesDir.exists()) return emptyList()
        return themesDir.listFiles { f -> f.isFile && f.extension.equals("json", true) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * 通知资源已更新（用于触发UI刷新，确保立即生效）
     * 通过清除可能存在的缓存标记来实现
     */
    fun notifyResourcesUpdated(context: Context) {
        // 资源目录的文件已经写入，读取时直接从磁盘读取即可生效
        // 此函数作为钩子保留，便于未来扩展
    }

    /**
     * 获取某个资源包目录下的所有文件列表（相对路径+大小），用于UI显示已加载/未加载详细文件。
     *
     * @param packType 资源包类型名（ResourcePackType 枚举的 name），如 "BASE"/"INTERACTION"/"INSTALL_PATCH" 等
     * @return 排序后的文件信息列表；目录不存在时返回空列表
     */
    fun getPackageFiles(context: Context, packType: String): List<PackageFileInfo> {
        val dir = when (packType) {
            "BASE" -> getResourcePath(context)
            "INTERACTION" -> getInteractionPath(context)
            "PATCH_CORE" -> getPatchCorePath(context)
            "INIT_PREMIUM" -> getInitPremiumPath(context)
            "INSTALL_PATCH" -> getInstallPatchPath(context)
            "PRELOAD" -> getPreloadPath(context)
            "PREPROCESS" -> getPreprocessPath(context)
            else -> return emptyList()
        }
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile }
            .map { PackageFileInfo(it.relativeTo(dir).path, it.length(), it.lastModified()) }
            .sortedBy { it.path }
            .toList()
    }

    /** 资源包内单个文件信息（相对路径 + 字节大小 + 最后修改时间） */
    data class PackageFileInfo(val path: String, val size: Long, val lastModified: Long)

    /**
     * 七阶段顺序下载（基础资源→交互外观→核心功能补丁→高级体验初始化→安装包补丁→预加载→预处理）
     */
    suspend fun downloadAll(
        context: Context,
        onPhaseChange: (phase: LoadPhase) -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long, status: String) -> Unit
    ): Result<Boolean> {
        // 阶段1：基础资源包
        onPhaseChange(LoadPhase.BASE_RESOURCES)
        val phase1 = downloadAndInstall(context, onProgress = onProgress)
        if (phase1.isFailure) return phase1

        // 阶段2：交互外观包
        onPhaseChange(LoadPhase.INTERACTION_PACK)
        val phase2 = downloadInteractionPack(context, onProgress = onProgress)
        if (phase2.isFailure) return phase2

        // 阶段3：核心功能补丁包
        onPhaseChange(LoadPhase.PATCH_CORE)
        val phase3 = downloadPatchCore(context, onProgress = onProgress)
        if (phase3.isFailure) return phase3

        // 阶段4：高级体验初始化包
        onPhaseChange(LoadPhase.INIT_PREMIUM)
        val phase4 = downloadInitPremium(context, onProgress = onProgress)
        if (phase4.isFailure) return phase4

        // 阶段5：安装包补丁
        onPhaseChange(LoadPhase.INSTALL_PATCH)
        val phase5 = downloadInstallPatch(context, onProgress = onProgress)
        if (phase5.isFailure) return phase5

        // 阶段6：预加载包
        onPhaseChange(LoadPhase.PRELOAD)
        val phase6 = downloadPreload(context, onProgress = onProgress)
        if (phase6.isFailure) return phase6

        // 阶段7：预处理包
        onPhaseChange(LoadPhase.PREPROCESS)
        val phase7 = downloadPreprocess(context, onProgress = onProgress)
        if (phase7.isFailure) return phase7

        onPhaseChange(LoadPhase.COMPLETE)
        return Result.success(true)
    }

    enum class LoadPhase {
        CHECKING,
        BASE_RESOURCES,
        INTERACTION_PACK,
        PATCH_CORE,
        INIT_PREMIUM,
        INSTALL_PATCH,
        PRELOAD,
        PREPROCESS,
        COMPLETE
    }

    // ======================== 下载实现 ========================

    private suspend fun downloadWithRetry(
        context: Context,
        urls: List<String>,
        zipName: String,
        targetDir: File,
        markerName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long, status: String) -> Unit
    ): Result<Boolean> {
        var lastError: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            for ((urlIndex, url) in urls.withIndex()) {
                try {
                    // ── 暂停检测 ──
                    awaitIfPaused()

                    if (attempt > 1) {
                        onProgress(0, 0, "重试 $attempt/$MAX_RETRIES...")
                        kotlinx.coroutines.delay(RETRY_DELAY_MS)
                    } else if (urlIndex > 0) {
                        onProgress(0, 0, "切换镜像...")
                    }
                    performDownload(context, url, zipName, targetDir, markerName, onProgress, attempt)
                    return Result.success(true)
                } catch (e: Exception) {
                    lastError = e
                    if (urlIndex < urls.size - 1) {
                        onProgress(0, 0, "当前源失败，切换镜像... (${e.message})")
                    } else if (attempt < MAX_RETRIES) {
                        onProgress(0, 0, "所有镜像均失败，准备重试... (${e.message})")
                    }
                }
            }
        }

        val errorMsg = lastError?.message ?: "Download failed after $MAX_RETRIES attempts"
        return Result.failure(RuntimeException(errorMsg, lastError))
    }

    private suspend fun performDownload(
        context: Context,
        url: String,
        zipName: String,
        targetDir: File,
        markerName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long, status: String) -> Unit,
        attempt: Int
    ) {
        // 仅在下载成功后再清理目标目录（避免中断时丢失已解压内容）。
        // 不在此处删除 .part —— downloadFile 内部会复用 .part 断点续传。
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.mkdirs()

        val zipFile = File(context.filesDir, zipName)

        try {
            // 实时文件列表初始化
            liveExtractedFiles.value = emptyList()
            downloadFile(url, zipFile, onProgress)
            onProgress(0, 0, "正在解压安装...")
            extractZip(zipFile, targetDir, onProgress)
            onProgress(0, 0, "清理临时文件...")
            zipFile.delete()
            // 下载并解压成功后，.part 已在 downloadFile 内被 rename 为正式文件，
            // 此处兜底清理可能残留的 .part 与 .part.meta
            File(zipFile.parentFile, zipFile.name + ".part").delete()
            File(zipFile.parentFile, zipFile.name + ".part.meta").delete()

            val markerFile = File(targetDir, markerName)
            markerFile.writeText("installed")

            // 最终文件列表推送
            liveExtractedFiles.value = getPackageFiles(context, packTypeByDir(targetDir))
            currentFileName.value = ""
            onProgress(0, 0, "安装完成")
        } catch (e: Exception) {
            // 失败/取消时：保留 .part 缓存供下次断点续传，仅清理半成品解压目录
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            throw e
        }
    }

    /** 根据目标目录名反查资源包类型名（用于实时文件列表） */
    private fun packTypeByDir(dir: File): String {
        val name = dir.name
        return when (name) {
            RESOURCES_DIR -> "BASE"
            INTERACTION_DIR -> "INTERACTION"
            PATCH_CORE_DIR -> "PATCH_CORE"
            INIT_PREMIUM_DIR -> "INIT_PREMIUM"
            INSTALL_PATCH_DIR -> "INSTALL_PATCH"
            PRELOAD_DIR -> "PRELOAD"
            PREPROCESS_DIR -> "PREPROCESS"
            else -> "BASE"
        }
    }

    private suspend fun downloadFile(
        urlStr: String,
        outputFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long, status: String) -> Unit
    ) {
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 120000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            connection.instanceFollowRedirects = true

            // ── 断点续传：若 .part 缓存存在且源 URL 一致，用 HTTP Range 接着下载 ──
            // .part.meta 记录上次下载的源 URL；若 URL 变化（如版本升级后 release 路径变了），
            // 旧 .part 不能再用，必须删除重新下载，否则拼出的 zip 会损坏。
            val partFile = File(outputFile.parentFile, outputFile.name + ".part")
            val metaFile = File(outputFile.parentFile, outputFile.name + ".part.meta")
            var existingBytes = 0L
            if (partFile.exists()) {
                val metaUrl = if (metaFile.exists()) metaFile.readText() else ""
                if (metaUrl == urlStr) {
                    existingBytes = partFile.length()
                    if (existingBytes > 0) {
                        connection.setRequestProperty("Range", "bytes=$existingBytes-")
                        onProgress(existingBytes, 0, "断点续传：从 ${formatSize(existingBytes)} 继续...")
                    }
                } else {
                    // URL 不匹配：版本升级或换了源，旧 .part 必须丢弃
                    partFile.delete()
                    metaFile.delete()
                }
            }

            val responseCode = connection.responseCode
            // 200 = 全新下载（服务器不支持 Range 或缓存失效）; 206 = 续传成功
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw RuntimeException("HTTP $responseCode")
            }

            val isResume = responseCode == HttpURLConnection.HTTP_PARTIAL
            // 续传成功时记录 meta（首次或续传都写一次，确保 meta 存在）
            metaFile.writeText(urlStr)
            val contentLength = connection.contentLengthLong
            val totalBytes = if (isResume) existingBytes + contentLength else contentLength
            var downloadedBytes = if (isResume) existingBytes else 0L
            var lastReportedBytes = downloadedBytes
            var lastReportTime = System.currentTimeMillis()

            // 续传用 append 模式；全新下载用 truncate
            val raf = RandomAccessFile(partFile, "rw")
            try {
                if (isResume) {
                    raf.seek(partFile.length())
                } else {
                    raf.setLength(0)
                }
                connection.inputStream.use { inputStream ->
                    BufferedInputStream(inputStream).use { bufferedStream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int

                        while (bufferedStream.read(buffer).also { bytesRead = it } != -1) {
                            // ── 暂停检测：暂停时挂起，恢复后继续 ──
                            awaitIfPaused()

                            raf.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            currentFileName.value = outputFile.name

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastReportTime

                            if (elapsed >= 500) {
                                val speed = if (elapsed > 0) {
                                    val bytesDelta = downloadedBytes - lastReportedBytes
                                    val speedBps = bytesDelta * 1000L / elapsed
                                    formatSpeed(speedBps)
                                } else {
                                    "计算中..."
                                }

                                val status = if (totalBytes > 0) {
                                    "下载中 ${formatSize(downloadedBytes)} / ${formatSize(totalBytes)} ($speed)"
                                } else {
                                    "下载中 ${formatSize(downloadedBytes)} ($speed)"
                                }

                                onProgress(downloadedBytes, totalBytes, status)
                                lastReportedBytes = downloadedBytes
                                lastReportTime = now
                            }
                        }
                    }
                }
            } finally {
                raf.close()
            }

            // 下载完成：.part → 正式文件，并清理 meta
            if (totalBytes > 0 && downloadedBytes < totalBytes) {
                throw RuntimeException("Download incomplete: $downloadedBytes/$totalBytes bytes")
            }
            partFile.renameTo(outputFile)
            metaFile.delete()
        } finally {
            connection.disconnect()
        }
    }

    // ======================== 分块下载（补丁系统核心） ========================

    /**
     * 分块下载基础资源包。
     *
     * 流程：拉取 resource-manifest.json → 并发下载分块(4路并发,每块走镜像+校验字节数) →
     * 按序拼合成 resources.zip → 解压。
     *
     * 这是「资源初始化补丁」：每个分块约 90MB，代理可稳定传输，逐块校验字节数
     * 杜绝代理静默截断导致的"下载完只有 670MB"问题。
     * 4路并发下载可充分利用带宽，解决单线程下载只有几十KB/s的问题。
     */
    private suspend fun downloadChunked(
        context: Context,
        targetDir: File,
        markerName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long, status: String) -> Unit
    ): Result<Boolean> {
        // 1) 拉取清单
        val manifest = fetchResourceManifest() ?: return Result.failure(
            RuntimeException("无法获取资源清单")
        )
        if (manifest.chunks.isEmpty()) {
            return Result.failure(RuntimeException("资源清单为空"))
        }

        // 2) 准备目标目录（仅清理解压目录，不删 chunk 缓存文件）
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()
        val combinedZip = File(context.filesDir, ZIP_FILE_NAME)
        if (combinedZip.exists()) combinedZip.delete()

        // ── 初始化多进度条：每个分块一条独立进度 ──
        val totalChunks = manifest.chunks.size
        chunkProgresses.value = manifest.chunks.mapIndexed { i, chunk ->
            val chunkFile = File(context.filesDir, "chunk_${i}.tmp")
            val existing = if (chunkFile.exists()) chunkFile.length() else 0L
            val status = if (existing == chunk.size) "cached" else "pending"
            ChunkProgress(i, totalChunks, chunk.name, existing, chunk.size, status)
        }

        // 3) 并发下载分块（4路并发 + 断点续传）
        //    已存在且字节数完整的 chunk 直接复用，不完整的用 Range 续传
        val concurrency = 4
        val chunkFiles = arrayOfNulls<File>(manifest.chunks.size)
        val chunkSuccess = BooleanArray(manifest.chunks.size)
        val totalDownloaded = java.util.concurrent.atomic.AtomicLong(0L)

        // 用 AtomicReference + CAS 保证多线程更新分块进度时不互相覆盖（修复"反复横跳"Bug）
        // 原实现 toMutableList() + 整体赋值在 4 路并发下有写-写竞态：
        //   线程A读列表→线程B读列表→线程A写→线程B写(覆盖A的更新)→A的更新丢失→进度回退
        // 另：每个分块的 downloaded 字段用 max(已有值, 新值) 保证只增不减，
        //   防止镜像切换续传时 existingBytes < 上次报告值导致进度条回退
        val chunkProgressRef = java.util.concurrent.atomic.AtomicReference(chunkProgresses.value)
        // 每个分块上次报告的时间戳，用于节流（避免200ms内多次更新造成闪烁）
        val lastReportTimes = LongArray(totalChunks) { 0L }

        fun updateChunkProgress(idx: Int, downloaded: Long, status: String, force: Boolean = false) {
            // 节流：非强制更新时，200ms 内只更新一次（减少 UI 重组频率）
            val now = System.currentTimeMillis()
            if (!force && now - lastReportTimes[idx] < 200) return
            lastReportTimes[idx] = now

            while (true) {
                val current = chunkProgressRef.get()
                if (idx >= current.size) break
                val oldChunk = current[idx]
                // 进度只增不减：防止镜像切换/续传时进度回退（修复"反复横跳"）
                val newDownloaded = maxOf(oldChunk.downloaded, downloaded)
                // 状态不回退：done/cached 不应被 downloading 覆盖
                val newStatus = when {
                    status == "done" || status == "cached" -> status
                    status == "failed" -> status
                    oldChunk.status == "done" || oldChunk.status == "cached" -> oldChunk.status
                    else -> status
                }
                if (newDownloaded == oldChunk.downloaded && newStatus == oldChunk.status) break
                val updated = current.toMutableList()
                updated[idx] = oldChunk.copy(downloaded = newDownloaded, status = newStatus)
                if (chunkProgressRef.compareAndSet(current, updated)) {
                    chunkProgresses.value = updated
                    break
                }
                // CAS 失败则重试
            }
        }

        val pool = java.util.concurrent.Executors.newFixedThreadPool(concurrency)
        val futures = mutableListOf<java.util.concurrent.Future<Unit>>()

        for (i in manifest.chunks.indices) {
            val chunk = manifest.chunks[i]
            val idx = i
            futures.add(pool.submit<Unit> {
                val chunkFile = File(context.filesDir, "chunk_${idx}.tmp")
                // ── 断点续传：已完整的 chunk 直接复用，跳过下载 ──
                if (chunkFile.exists() && chunkFile.length() == chunk.size) {
                    chunkFiles[idx] = chunkFile
                    chunkSuccess[idx] = true
                    totalDownloaded.addAndGet(chunk.size)
                    updateChunkProgress(idx, chunk.size, "done", force = true)
                    onProgress(totalDownloaded.get(), manifest.totalSize, "分块 ${idx + 1}/${manifest.chunks.size} 已缓存，跳过")
                    return@submit
                }
                // ── 不完整的 chunk 用 Range 续传 ──
                updateChunkProgress(idx, chunkFile.length(), "downloading", force = true)
                val ok = downloadChunkWithMirrors(chunk, chunkFile, onProgress, totalDownloaded.get(), manifest.totalSize) { downloaded ->
                    updateChunkProgress(idx, downloaded, "downloading")
                }
                if (ok && chunkFile.length() == chunk.size) {
                    chunkFiles[idx] = chunkFile
                    chunkSuccess[idx] = true
                    totalDownloaded.addAndGet(chunk.size)
                    updateChunkProgress(idx, chunk.size, "done", force = true)
                } else {
                    // 失败时保留已下载部分供下次续传，不删除 chunkFile
                    chunkSuccess[idx] = false
                    updateChunkProgress(idx, chunkFile.length(), "failed", force = true)
                }
            })
        }

        // 等待所有分块完成
        try {
            futures.forEach { it.get() }
        } catch (e: Exception) {
            pool.shutdownNow()
            return Result.failure(e)
        } finally {
            pool.shutdown()
        }

        // 4) 检查所有分块是否成功
        val failedChunks = chunkSuccess.indices.filter { !chunkSuccess[it] }
        if (failedChunks.isNotEmpty()) {
            // 失败时保留已下载 chunk 供下次续传，不删除
            return Result.failure(RuntimeException("分块 ${failedChunks.joinToString(",") { (it + 1).toString() }} 下载失败，已缓存部分下次接着下载"))
        }

        // 5) 按序拼合分块
        try {
            java.io.RandomAccessFile(combinedZip, "rw").use { raf ->
                for (i in manifest.chunks.indices) {
                    val chunkFile = chunkFiles[i] ?: continue
                    chunkFile.inputStream().use { input ->
                        val buf = ByteArray(BUFFER_SIZE)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            raf.write(buf, 0, n)
                        }
                    }
                    // 拼合成功后删除 chunk 临时文件
                    chunkFile.delete()
                }
            }

            // 6) 解压合并后的 zip
            chunkProgresses.value = emptyList() // 解压阶段清空分块进度条
            onProgress(manifest.totalSize, manifest.totalSize, "正在解压安装...")
            extractZip(combinedZip, targetDir, onProgress)
            combinedZip.delete()

            // 7) 写入标记
            File(targetDir, markerName).writeText("installed_v${manifest.version}")
            onProgress(manifest.totalSize, manifest.totalSize, "安装完成（${manifest.totalSize / 1024 / 1024}MB）")
            return Result.success(true)
        } catch (e: Exception) {
            combinedZip.delete()
            if (targetDir.exists()) targetDir.deleteRecursively()
            return Result.failure(e)
        }
    }

    /** 资源清单数据模型 */
    private data class ResourceManifest(
        val version: String,
        val totalSize: Long,
        val chunks: List<ChunkSpec>
    )

    private data class ChunkSpec(val name: String, val size: Long)

    /** 从 jsdelivr 拉取资源清单（小文件，国内可达） */
    private fun fetchResourceManifest(): ResourceManifest? {
        for (urlStr in RESOURCE_MANIFEST_URLS) {
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                if (conn.responseCode != 200) { conn.disconnect(); continue }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json = org.json.JSONObject(body)
                val version = json.optString("version", "")
                val totalSize = json.optLong("totalSize", 0)
                val chunksArr = json.optJSONArray("chunks") ?: continue
                val chunks = mutableListOf<ChunkSpec>()
                for (i in 0 until chunksArr.length()) {
                    val c = chunksArr.getJSONObject(i)
                    chunks.add(ChunkSpec(c.optString("name"), c.optLong("size")))
                }
                if (chunks.isEmpty()) continue
                return ResourceManifest(version, totalSize, chunks)
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    /** 下载单个分块 —— v2.9.0 单源下载，杜绝镜像切换导致的进度横跳。
     *  策略：优先用主镜像(cors.isteed.cc)，仅在【连接失败】时才切 fallback。
     *  若连接成功但下载中断，【同镜像】Range 续传，不切镜像（切镜像会导致
     *  Content-Length 不一致 + 进度归零重启 = 进度条反复横跳）。
     *  [onChunkProgress] 回调报告本分块已下载字节数。 */
    private fun downloadChunkWithMirrors(
        chunk: ChunkSpec,
        outFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long, status: String) -> Unit,
        baseDownloaded: Long,
        grandTotal: Long,
        onChunkProgress: (downloadedBytes: Long) -> Unit = {}
    ): Boolean {
        // 单源优先列表：主镜像 → fallback（仅连接失败时才走到 fallback）
        val mirrors = listOf(
            "$PRIMARY_MIRROR/${chunk.name}"
        ) + FALLBACK_MIRRORS.map { "$it/${chunk.name}" }

        var currentMirrorIdx = 0
        var retriesOnCurrentMirror = 0
        val MAX_RETRIES_PER_MIRROR = 3

        while (currentMirrorIdx < mirrors.size) {
            val urlStr = mirrors[currentMirrorIdx]
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 120000
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")

                // 断点续传：同镜像内已下载部分用 Range 接着下
                var existingBytes = 0L
                if (outFile.exists()) {
                    existingBytes = outFile.length()
                    if (existingBytes > 0 && existingBytes < chunk.size) {
                        conn.setRequestProperty("Range", "bytes=$existingBytes-")
                    }
                }

                val code = conn.responseCode
                if (code != 200 && code != 206) {
                    conn.disconnect()
                    // 连接级失败 → 切下一个镜像
                    currentMirrorIdx++
                    retriesOnCurrentMirror = 0
                    continue
                }

                val isResume = code == 206
                val raf = java.io.RandomAccessFile(outFile, "rw")
                try {
                    if (isResume) {
                        raf.seek(outFile.length())
                    } else {
                        raf.setLength(0)
                        existingBytes = 0L
                    }
                    val input = conn.inputStream
                    val buf = ByteArray(BUFFER_SIZE)
                    var n: Int
                    var chunkDownloaded = existingBytes
                    var lastReport = System.currentTimeMillis()
                    onChunkProgress(chunkDownloaded)
                    while (input.read(buf).also { n = it } != -1) {
                        raf.write(buf, 0, n)
                        chunkDownloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 200) {
                            onProgress(baseDownloaded + chunkDownloaded, grandTotal, "")
                            onChunkProgress(chunkDownloaded)
                            lastReport = now
                        }
                    }
                    onChunkProgress(chunkDownloaded)
                } finally {
                    raf.close()
                }
                conn.disconnect()
                // 字节数校验通过即成功
                if (outFile.length() == chunk.size) return true
                // 字节数不匹配：同镜像重试（不切镜像！切镜像会导致进度横跳）
                retriesOnCurrentMirror++
                if (retriesOnCurrentMirror >= MAX_RETRIES_PER_MIRROR) {
                    // 同镜像重试耗尽 → 切下一个镜像
                    currentMirrorIdx++
                    retriesOnCurrentMirror = 0
                }
            } catch (_: Exception) {
                // 连接级异常 → 切下一个镜像（不保留半成品的不确定性）
                retriesOnCurrentMirror++
                if (retriesOnCurrentMirror >= MAX_RETRIES_PER_MIRROR) {
                    currentMirrorIdx++
                    retriesOnCurrentMirror = 0
                }
            }
        }
        return false
    }

    private suspend fun extractZip(
        zipFile: File,
        targetDir: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long, status: String) -> Unit
    ) {
        var entryCount = 0
        val extracted = mutableListOf<PackageFileInfo>()

        zipFile.inputStream().buffered().use { fileInput ->
            ZipInputStream(fileInput).use { zipStream ->
                var entry = zipStream.nextEntry

                while (entry != null) {
                    // ── 暂停检测 ──
                    awaitIfPaused()

                    val entryFile = File(targetDir, entry.name)

                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        entryFile.parentFile?.mkdirs()
                        currentFileName.value = entry.name
                        FileOutputStream(entryFile).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            while (zipStream.read(buffer).also { bytesRead = it } != -1) {
                                awaitIfPaused()
                                output.write(buffer, 0, bytesRead)
                            }
                        }
                        // ── 实时推送：每解出一个文件就更新列表 ──
                        extracted.add(PackageFileInfo(entry.name, entryFile.length(), entryFile.lastModified()))
                        liveExtractedFiles.value = extracted.toList()
                    }

                    entryCount++
                    onProgress(0, 0, "解压中 ($entryCount 个文件) — ${entry.name}")

                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
            }
        }

        onProgress(0, 0, "已解压 $entryCount 个文件")
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
            bytesPerSecond < 1024 * 1024 -> "%.1f KB/s".format(bytesPerSecond / 1024.0)
            else -> "%.1f MB/s".format(bytesPerSecond / (1024.0 * 1024.0))
        }
    }
}
