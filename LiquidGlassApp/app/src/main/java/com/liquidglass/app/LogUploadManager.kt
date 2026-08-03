package com.liquidglass.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 日志类型。
 * - [CRASH]      未捕获的崩溃异常
 * - [ERROR]      运行时错误
 * - [FEEDBACK]   用户反馈
 * - [SUGGESTION] 用户建议
 */
enum class LogType { CRASH, ERROR, FEEDBACK, SUGGESTION }

/**
 * 内存环形缓冲中的单条日志条目。
 */
data class LogEntry(
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String
)

/**
 * 上传给服务端的完整日志报告。
 *
 * @param type             日志类型
 * @param appVersion       应用版本名（BuildConfig/PackageManager 的 versionName）
 * @param deviceModel      设备型号（Build.MODEL）
 * @param androidVersion   系统版本（Build.VERSION.RELEASE）
 * @param sdkInt           SDK 版本号（Build.VERSION.SDK_INT）
 * @param availableMemMB   可用内存（MB）
 * @param availableStorageMB 可用存储空间（MB）
 * @param description      用户描述
 * @param userSteps        用户复现步骤
 * @param recentLogs       最近内存日志缓冲快照
 * @param crashTrace       最近一次崩溃堆栈（无崩溃时为 null）
 */
data class LogReport(
    val type: LogType,
    val appVersion: String,
    val deviceModel: String,
    val androidVersion: String,
    val sdkInt: Int,
    val availableMemMB: Long,
    val availableStorageMB: Long,
    val description: String,
    val userSteps: String,
    val recentLogs: List<LogEntry>,
    val crashTrace: String?
)

/**
 * 全局未捕获异常处理器。
 *
 * 实现 [Thread.UncaughtExceptionHandler]，接管应用默认崩溃流程：
 * 1. 将崩溃堆栈同步写入崩溃日志文件（本地备份，确保进程被杀也不丢失）；
 * 2. 同步将崩溃报告 JSON 落盘到 pending_uploads/，待下次启动重试上传；
 * 3. 重启到主界面（Launcher Activity）；
 * 4. 结束当前进程，让系统以新进程拉起主界面。
 *
 * 注意：此处不调用 previousHandler，以避免弹出系统"应用已停止运行"对话框，
 * 实现无感重启。安装后会接管 [LiquidGlassApp] 中已有的崩溃处理逻辑。
 *
 * @param context         应用上下文（已取 applicationContext）
 * @param previousHandler 安装前默认的异常处理器（保留引用以便需要时链式调用）
 */
class CrashHandler private constructor(
    private val context: Context,
    private val previousHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        // 1. 写入崩溃日志文件 + 同步落盘崩溃报告，确保进程被杀后仍可在下次启动重试上传
        try {
            LogUploadManager.handleCrash(t, e)
        } catch (_: Exception) {
            // 兜底：吞掉写日志过程中的异常，避免影响重启流程
        }
        // 2. 重启到主界面
        try {
            restartToMainScreen()
        } catch (_: Exception) {
            // 重启失败也不阻塞，继续走进程结束流程
        }
        // 3. 结束当前进程，系统会以新进程拉起刚启动的 Launcher Activity
        Process.killProcess(Process.myPid())
        System.exit(0)
    }

    /**
     * 通过 PackageManager 获取 Launcher Intent，重启到主界面。
     */
    private fun restartToMainScreen() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        context.startActivity(intent)
    }

    companion object {
        private const val TAG = "CrashHandler"

        /**
         * 安装为全局默认未捕获异常处理器。
         */
        fun install(context: Context) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context.applicationContext, previous))
            Log.i(TAG, "全局崩溃捕获器已安装")
        }
    }
}

/**
 * 用户日志上传管理器（单例）。
 *
 * 负责：
 * - 捕获应用崩溃/异常/用户反馈日志；
 * - 写入按天分割的本地日志文件 + 内存环形缓冲；
 * - 收集设备信息打包成 JSON 上传到指定端点；
 * - 上传失败本地留存，待后续重试；
 * - 导出全部日志供用户分享。
 *
 * ──────────────────────────────────────────────────────────────
 * 初始化说明（不要修改 LiquidGlassApp.kt，请在 LiquidGlassApp.onCreate 中调用）：
 *
 *   override fun onCreate() {
 *       super.onCreate()
 *       // 安装崩溃捕获器、创建日志目录、异步重试待上传日志
 *       LogUploadManager.init(this)
 *       // ...其余原有初始化逻辑
 *   }
 *
 * 注意：[init] 会安装 [CrashHandler] 接管全局未捕获异常处理。
 *       若 [LiquidGlassApp] 已有自己的崩溃处理逻辑，建议在调用 [init] 后移除原有逻辑，
 *       或将 [init] 放在原有逻辑之后调用，由 [CrashHandler] 统一接管崩溃流程。
 * ──────────────────────────────────────────────────────────────
 */
object LogUploadManager {

    private const val TAG = "LogUploadManager"

    /** 默认上传端点。可通过 [setUploadEndpoint] 修改。 */
    private const val DEFAULT_UPLOAD_ENDPOINT =
        "https://liquidglass-log.example.com/api/log/upload"

    /** 日志目录名（位于 context.filesDir 下）。 */
    private const val LOG_DIR_NAME = "logs"
    /** 上传失败时的待重试目录名（位于日志目录下）。 */
    private const val PENDING_DIR = "pending_uploads"
    /** 崩溃日志文件名前缀。 */
    private const val CRASH_FILE_PREFIX = "crash_"
    /** 导出文件名前缀。 */
    private const val EXPORT_FILE_PREFIX = "logs_export_"
    /** 内存环形缓冲最大条数。 */
    private const val RING_BUFFER_SIZE = 500

    /** 日志行时间戳格式：[yyyy-MM-dd HH:mm:ss.SSS] [LEVEL] [TAG] message */
    private const val LINE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS"
    /** 按天日志文件名格式：yyyy-MM-dd.log */
    private const val DAY_PATTERN = "yyyy-MM-dd"
    /** 崩溃文件名时间戳格式。 */
    private const val CRASH_FILE_PATTERN = "yyyy-MM-dd_HH-mm-ss.SSS"
    /** pending 文件名时间戳格式。 */
    private const val PENDING_FILE_PATTERN = "yyyyMMdd_HHmmss_SSS"
    /** 导出文件名时间戳格式。 */
    private const val EXPORT_FILE_PATTERN = "yyyyMMdd_HHmmss"

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var logDir: File? = null
    @Volatile
    private var initialized = false

    /** 上传端点（可配置）。 */
    @Volatile
    var uploadEndpoint: String = DEFAULT_UPLOAD_ENDPOINT
        private set

    /** 内存环形缓冲（最近 [RING_BUFFER_SIZE] 条日志）。 */
    private val ringBuffer = ArrayDeque<LogEntry>(RING_BUFFER_SIZE)
    private val ringLock = Any()
    /** 文件写入锁。 */
    private val fileLock = Any()

    /** 单线程后台执行器，串行处理上传/重试，避免并发网络与文件竞争。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LogUploadManager").apply { isDaemon = true }
    }

    /** OkHttp 客户端（懒加载，超时 15 秒）。 */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 初始化：安装 [CrashHandler]，创建日志目录与 pending 目录，并异步重试待上传日志。
     *
     * 应在 [LiquidGlassApp.onCreate] 中调用，且仅需调用一次（重复调用会被忽略）。
     */
    fun init(context: Context) {
        if (initialized) return
        val ctx = context.applicationContext
        appContext = ctx
        val dir = File(ctx.filesDir, LOG_DIR_NAME).apply { mkdirs() }
        File(dir, PENDING_DIR).mkdirs()
        logDir = dir
        initialized = true
        // 安装全局崩溃捕获器
        CrashHandler.install(ctx)
        // 启动时异步重试待上传日志（崩溃重启后由这里把上次的崩溃报告发出）
        retryPendingUploads()
        Log.i(TAG, "初始化完成，日志目录: ${dir.absolutePath}")
    }

    /**
     * 配置上传端点。可在 [init] 之前或之后调用。
     */
    fun setUploadEndpoint(url: String) {
        uploadEndpoint = url
    }

    /**
     * 写入一条日志。
     *
     * 同时：
     * - 写入按天分割的文件（yyyy-MM-dd.log），格式 `[yyyy-MM-dd HH:mm:ss.SSS] [LEVEL] [TAG] message`；
     * - 若 [throwable] 非空，追加其堆栈到文件；
     * - 写入内存环形缓冲（最近 [RING_BUFFER_SIZE] 条），缓冲中仅保留纯文本 message。
     *
     * 即使尚未 [init]，内存缓冲仍可工作；文件写入在未初始化时静默跳过。
     *
     * @param level     日志级别（如 INFO/WARN/ERROR）
     * @param tag       日志标签
     * @param message   日志正文
     * @param throwable 可选异常，非空时其堆栈会写入文件
     */
    fun writeLog(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val now = Date()
        val ts = formatTime(LINE_TIME_PATTERN, now)
        // 写入内存环形缓冲
        offerRing(LogEntry(timestamp = ts, level = level, tag = tag, message = message))
        // 写入按天分割的文件
        val dir = logDir ?: return
        try {
            val line = buildString {
                append('[').append(ts).append("] ")
                append('[').append(level).append("] ")
                append('[').append(tag).append("] ")
                append(message)
                if (throwable != null) {
                    append('\n')
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    append(sw.toString())
                }
                append('\n')
            }
            synchronized(fileLock) {
                File(dir, "${formatTime(DAY_PATTERN, now)}.log").appendText(line)
            }
        } catch (_: Exception) {
            // 写文件失败不影响主流程
        }
    }

    /**
     * 收集设备信息 + 内存日志缓冲 + 最近崩溃堆栈 + 用户描述，打包成 JSON 并异步上传。
     *
     * 上传在后台线程执行，不阻塞调用方；失败时由 [upload] 自动落盘到 pending_uploads/。
     *
     * @param type        日志类型
     * @param description 用户描述
     * @param userSteps   用户复现步骤（可选）
     */
    fun collectAndUpload(type: LogType, description: String, userSteps: String = "") {
        val report = buildReport(type, description, userSteps)
        val json = reportToJson(report)
        executor.execute {
            upload(json)
        }
    }

    /**
     * 同步上传 JSON 到 [uploadEndpoint]。
     *
     * OkHttp POST，超时 15 秒；失败（异常或非 2xx 响应）时将 payload 保存到
     * pending_uploads/ 目录等待重试。
     *
     * 注意：该方法是阻塞网络调用，调用方应避免在主线程直接调用；
     * 通过 [collectAndUpload] 触发的上传已自动在后台线程执行。
     *
     * @return 上传是否成功
     */
    fun upload(jsonPayload: String): Boolean {
        val ok = doPost(jsonPayload)
        if (!ok) {
            try {
                saveToPending(jsonPayload)
            } catch (_: Exception) {
                // 落盘失败不影响返回值
            }
        }
        return ok
    }

    /**
     * 重试本地 pending_uploads/ 目录下所有待上传日志。
     *
     * 在后台线程串行执行：上传成功的文件会被删除，失败则保留待下次重试。
     * 建议在 [init] 后或网络恢复时调用。
     */
    fun retryPendingUploads() {
        executor.execute {
            try {
                val dir = logDir ?: return@execute
                val pendingDir = File(dir, PENDING_DIR)
                val files = pendingDir.listFiles { f ->
                    f.isFile && f.name.endsWith(".json")
                } ?: return@execute
                for (f in files) {
                    val content = try {
                        f.readText()
                    } catch (_: Exception) {
                        continue
                    }
                    // 仅做网络请求，失败时不在此处再落盘（文件本身已在 pending 中），避免重复
                    if (doPost(content)) {
                        f.delete()
                    }
                }
            } catch (_: Exception) {
                // 重试整体失败不影响应用
            }
        }
    }

    /**
     * 导出全部日志（按天日志 + 崩溃日志）为单个文本文件，供用户通过分享功能发送。
     *
     * @return 导出文件；导出失败返回 null。
     */
    fun exportLogs(): File? {
        val dir = logDir ?: return null
        return try {
            val ts = formatTime(EXPORT_FILE_PATTERN)
            val outFile = File(dir, "${EXPORT_FILE_PREFIX}$ts.txt")
            // 按文件名排序合并所有 .log 文件（排除导出文件自身与 pending 目录）
            val logFiles = dir.listFiles { f ->
                f.isFile && f.name.endsWith(".log") && !f.name.startsWith(EXPORT_FILE_PREFIX)
            }?.sortedBy { it.name } ?: emptyList()
            outFile.bufferedWriter().use { writer ->
                for (f in logFiles) {
                    writer.write("==== ${f.name} ====")
                    writer.newLine()
                    f.bufferedReader().use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            writer.write(line)
                            writer.newLine()
                            line = reader.readLine()
                        }
                    }
                    writer.newLine()
                }
            }
            outFile
        } catch (_: Exception) {
            null
        }
    }

    // ───────────────────────────── 内部方法 ─────────────────────────────

    /**
     * 线程安全的时间格式化。
     * SimpleDateFormat 非线程安全，writeLog/handleCrash/上传等可能并发调用，
     * 故每次新建实例而非共享字段。
     */
    private fun formatTime(pattern: String, date: Date = Date()): String =
        SimpleDateFormat(pattern, Locale.US).format(date)

    /**
     * 处理未捕获崩溃：写崩溃文件 + 同步落盘崩溃报告，供重启后重试上传。
     * 仅由 [CrashHandler] 调用，故设为 internal 而非 public。
     */
    internal fun handleCrash(thread: Thread, throwable: Throwable) {
        // 1. 写入崩溃日志文件（含按天日志），作为本地备份
        writeCrashToFile(thread, throwable)
        // 2. 同步将崩溃报告 JSON 落盘到 pending_uploads/，确保进程被杀后下次启动可重试
        try {
            val desc = "未捕获异常: ${throwable.javaClass.name}: ${throwable.message ?: ""}"
            val report = buildReport(LogType.CRASH, desc, "")
            val json = reportToJson(report)
            saveToPending(json)
        } catch (_: Exception) {
            // 落盘失败不阻塞重启流程
        }
    }

    /**
     * 写入崩溃日志文件（crash_<时间戳>.log），并同步追加一条记录到按天日志文件。
     */
    private fun writeCrashToFile(thread: Thread, throwable: Throwable) {
        val dir = logDir ?: return
        try {
            val now = Date()
            val ts = formatTime(LINE_TIME_PATTERN, now)
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val content = buildString {
                append("崩溃时间: ").append(ts).append('\n')
                append("线程: ").append(thread.name).append('\n')
                append("异常: ").append(throwable.javaClass.name).append('\n')
                append("消息: ").append(throwable.message ?: "").append('\n')
                append("堆栈:\n").append(sw.toString()).append('\n')
            }
            // 单独的崩溃文件
            File(dir, "$CRASH_FILE_PREFIX${formatTime(CRASH_FILE_PATTERN, now)}.log").writeText(content)
            // 同时追加到按天日志，便于 exportLogs 汇总
            synchronized(fileLock) {
                File(dir, "${formatTime(DAY_PATTERN, now)}.log")
                    .appendText("[$ts] [ERROR] [CrashHandler] 未捕获异常: ${throwable.javaClass.name}: ${throwable.message}\n")
            }
        } catch (_: Exception) {
            // 写文件失败不影响崩溃流程
        }
    }

    /**
     * 构建日志报告：收集设备信息、内存日志缓冲、最近崩溃堆栈。
     */
    private fun buildReport(type: LogType, description: String, userSteps: String): LogReport {
        val ctx = appContext
        return LogReport(
            type = type,
            appVersion = if (ctx != null) getAppVersion(ctx) else "",
            deviceModel = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            availableMemMB = if (ctx != null) getAvailableMemMB(ctx) else 0L,
            availableStorageMB = getAvailableStorageMB(),
            description = description,
            userSteps = userSteps,
            recentLogs = snapshotRing(),
            crashTrace = readLatestCrashTrace()
        )
    }

    /**
     * 将 [LogReport] 序列化为 JSON 字符串（使用 org.json）。
     */
    private fun reportToJson(report: LogReport): String {
        val json = JSONObject()
        json.put("type", report.type.name)
        json.put("appVersion", report.appVersion)
        json.put("deviceModel", report.deviceModel)
        json.put("androidVersion", report.androidVersion)
        json.put("sdkInt", report.sdkInt)
        json.put("availableMemMB", report.availableMemMB)
        json.put("availableStorageMB", report.availableStorageMB)
        json.put("description", report.description)
        json.put("userSteps", report.userSteps)
        val arr = JSONArray()
        for (e in report.recentLogs) {
            val o = JSONObject()
            o.put("timestamp", e.timestamp)
            o.put("level", e.level)
            o.put("tag", e.tag)
            o.put("message", e.message)
            arr.put(o)
        }
        json.put("recentLogs", arr)
        // crashTrace 为 null 时写入 JSON null（避免 org.json 移除该键）
        json.put("crashTrace", report.crashTrace ?: JSONObject.NULL)
        return json.toString()
    }

    /**
     * 执行 OkHttp POST（不落盘 pending）。成功返回 true，失败返回 false。
     */
    private fun doPost(jsonPayload: String): Boolean {
        return try {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = jsonPayload.toRequestBody(mediaType)
            val request = Request.Builder()
                .url(uploadEndpoint)
                .post(body)
                .build()
            httpClient.newCall(request).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 将 payload 保存到 pending_uploads/ 目录，文件名含时间戳与随机后缀。
     * @return 落盘文件；失败返回 null。
     */
    private fun saveToPending(jsonPayload: String): File? {
        val dir = logDir ?: return null
        return try {
            val pendingDir = File(dir, PENDING_DIR).apply { mkdirs() }
            val ts = formatTime(PENDING_FILE_PATTERN)
            val rnd = Integer.toHexString((Math.random() * 0xFFFF).toInt())
            val file = File(pendingDir, "pending_${ts}_$rnd.json")
            file.writeText(jsonPayload)
            file
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 读取最近一次崩溃文件内容作为 crashTrace；无崩溃文件或读取失败返回 null。
     */
    private fun readLatestCrashTrace(): String? {
        val dir = logDir ?: return null
        return try {
            val files = dir.listFiles { f ->
                f.isFile && f.name.startsWith(CRASH_FILE_PREFIX) && f.name.endsWith(".log")
            } ?: return null
            val latest = files.maxByOrNull { it.lastModified() } ?: return null
            latest.readText().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 获取应用版本名。
     */
    private fun getAppVersion(context: Context): String {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            pi.versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 获取可用内存（MB），基于 ActivityManager.MemoryInfo.availMem。
     */
    private fun getAvailableMemMB(context: Context): Long {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 0L
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem / (1024L * 1024L)
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * 获取可用存储空间（MB），基于日志目录所在分区的 StatFs.availableBytes。
     */
    private fun getAvailableStorageMB(): Long {
        val dir = logDir ?: return 0L
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes / (1024L * 1024L)
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * 向环形缓冲追加一条日志，超出容量时丢弃最旧的一条。
     */
    private fun offerRing(entry: LogEntry) {
        synchronized(ringLock) {
            if (ringBuffer.size >= RING_BUFFER_SIZE) {
                ringBuffer.pollFirst()
            }
            ringBuffer.addLast(entry)
        }
    }

    /**
     * 获取环形缓冲的快照（按时间顺序）。
     */
    private fun snapshotRing(): List<LogEntry> {
        synchronized(ringLock) {
            return ringBuffer.toList()
        }
    }
}
