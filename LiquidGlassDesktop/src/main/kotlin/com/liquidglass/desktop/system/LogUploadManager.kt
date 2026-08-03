package com.liquidglass.desktop.system

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志上传管理器（Desktop 版本）
 *
 * 与 Android 端逻辑一致：
 * - CrashHandler 通过 Thread.setDefaultUncaughtExceptionHandler 注册
 * - 日志存放于 System.getProperty("user.home")/.liquidglass/logs/
 * - 上传逻辑使用 Java 原生 HttpURLConnection，避免引入 OkHttp 依赖
 */
class LogUploadManager {

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
                // 崩溃流程中无法用协程，用 runBlocking 阻塞至上传完成
                runBlocking { uploadLog(throwable) }
            } catch (_: Throwable) {
                // 防止在异常处理流程中再次抛出
            } finally {
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
            var conn: HttpURLConnection? = null
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val payload = JSONObject()
                    .put("platform", "desktop")
                    .put("appVersion", "2.9.2")
                    .put("timestamp", System.currentTimeMillis())
                    .put("message", throwable.message ?: "")
                    .put("trace", sw.toString())
                    .toString()

                conn = (URL(UPLOAD_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
                // 触发请求发送，忽略响应
                conn.inputStream.use { it.read() }
            } catch (_: Exception) {
                // 静默失败，不影响主流程
            } finally {
                conn?.disconnect()
            }
        }
    }

    companion object {
        private const val UPLOAD_URL = "https://liquidglass.example.com/api/logs/upload"
    }
}
