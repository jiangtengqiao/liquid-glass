package com.liquidglass.desktop.system

import com.squareup.okhttp3.MediaType.Companion.toMediaType
import com.squareup.okhttp3.OkHttpClient
import com.squareup.okhttp3.Request
import com.squareup.okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 日志上传管理器（Desktop 版本）
 *
 * 与 Android 端逻辑一致：
 * - CrashHandler 通过 Thread.setDefaultUncaughtExceptionHandler 注册
 * - 日志存放于 System.getProperty("user.home")/.liquidglass/logs/
 * - 上传逻辑与 Android 端保持一致
 */
class LogUploadManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 日志目录：~/\.liquidglass/logs/ */
    val logDir: File = File(
        File(System.getProperty("user.home"), ".liquidglass"),
        "logs"
    ).apply { mkdirs() }

    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * 安装全局崩溃捕获处理器（与 Android 端 CrashHandler 对齐）
     */
    fun installCrashHandler() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(thread, throwable)
                uploadLog(throwable)
            } catch (_: Throwable) {
                // 防止在异常处理流程中再次抛出
            } finally {
                // 委托给原始处理器，保证 JVM 默认行为
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    /**
     * 将崩溃堆栈写入本地文件
     */
    private fun writeCrash(thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(logDir, "crash_$timestamp.txt")
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        file.writeText(
            buildString {
                appendLine("===== LiquidGlass Desktop Crash Report =====")
                appendLine("时间: ${Date()}")
                appendLine("线程: ${thread.name}")
                appendLine("异常类型: ${throwable::class.java.name}")
                appendLine("异常消息: ${throwable.message}")
                appendLine("---------- 堆栈 ----------")
                appendLine(sw.toString())
            }
        )
    }

    /**
     * 上传崩溃日志到服务端（与 Android 端一致）
     */
    suspend fun uploadLog(throwable: Throwable) {
        withContext(Dispatchers.IO) {
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val payload = JSONObject()
                    .put("platform", "desktop")
                    .put("appVersion", "2.9.1")
                    .put("timestamp", System.currentTimeMillis())
                    .put("message", throwable.message ?: "")
                    .put("trace", sw.toString())
                    .toString()
                val body = payload.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(UPLOAD_URL)
                    .post(body)
                    .build()
                client.newCall(request).execute().use { /* 忽略响应结果，保证不阻塞退出 */ }
            } catch (_: Exception) {
                // 静默失败，不影响主流程
            }
        }
    }

    companion object {
        private const val UPLOAD_URL = "https://liquidglass.example.com/api/logs/upload"
    }
}
