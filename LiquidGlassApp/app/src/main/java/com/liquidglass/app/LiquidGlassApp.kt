package com.liquidglass.app

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自定义 Application：注册全局崩溃捕获器。
 *
 * 所有未捕获异常（包括 Media3 回调、Compose 渲染、协程等）都会被捕获并写入
 * /sdcard/Download/LiquidGlass/crash_log.txt，下次启动时弹窗显示给用户，
 * 方便定位真实崩溃原因（此前用户反馈"点歌闪退"但无日志，无法定位）。
 */
class LiquidGlassApp : Application() {

    companion object {
        private const val TAG = "LiquidGlassApp"
        private const val CRASH_DIR = "LiquidGlass"
        private const val CRASH_FILE = "crash_log.txt"
    }

    override fun onCreate() {
        super.onCreate()
        // 注册全局崩溃捕获器：保留默认处理（让应用仍会退出），但先把堆栈写文件
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // ── 兜底：吞掉 Media3 MediaController release 时的良性 "Service not registered" 异常 ──
            // 根因：MediaController 连接 MediaSessionService 失败/超时后，内部 release() 调用
            // unbindService，但 service 从未注册成功，抛 IllegalArgumentException。
            // 该异常发生在 controller 已在释放的过程中，吞掉无副作用，避免点击通知进 App 闪退。
            if (isMedia3ServiceNotRegistered(throwable)) {
                Log.w(TAG, "已吞掉 Media3 良性异常（controller release 时 service 未注册）", throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            try {
                writeCrashLog(thread, throwable)
            } catch (_: Exception) {
                // 写日志失败不影响默认崩溃流程
            }
            // 调用之前的 handler，让应用正常退出（不吞崩溃，避免僵尸进程）
            previousHandler?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "全局崩溃捕获器已注册")

        // v2.9.1 三大新系统初始化
        LogUploadManager.init(this)
        AnnouncementManager.init(this)
        BetaPioneerManager.init(this)
    }

    /**
     * 判断是否为 Media3 MediaController release 时 "Service not registered" 良性异常。
     *
     * 触发场景：点击通知冷启动 App → MediaController 连接 MusicService 失败/超时 →
     * 内部 release → unbindService → IllegalArgumentException("Service not registered")。
     * 该异常从 main Handler callback 抛出，无法被业务代码 try-catch 捕获，
     * 只能在此全局兜底拦截。
     */
    private fun isMedia3ServiceNotRegistered(throwable: Throwable): Boolean {
        if (throwable !is IllegalArgumentException) return false
        val msg = throwable.message ?: return false
        if (!msg.contains("Service not registered")) return false
        // 确认是 Media3 MediaController release 链路（而非其他 unbindService 调用）
        val stack = throwable.stackTrace
        return stack.any { it.className.contains("MediaControllerImplBase") }
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val dir = File(getExternalFilesDir(null)?.parentFile?.parentFile, CRASH_DIR)
        // 路径：/sdcard/Android/data/com.liquidglass.app/files/../../../LiquidGlass/crash_log.txt
        // 用 app 私有目录确保不需要额外权限
        val crashDir = File(filesDir, "crash").apply { mkdirs() }
        val crashFile = File(crashDir, CRASH_FILE)

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

        val log = buildString {
            append("========================================\n")
            append("崩溃时间: $time\n")
            append("线程: ${thread.name}\n")
            append("异常: ${throwable.javaClass.name}\n")
            append("消息: ${throwable.message}\n")
            append("堆栈:\n")
            append(sw.toString())
            append("\n")
        }

        // 追加写入（保留历史崩溃记录，最多保留 50KB）
        crashFile.appendText(log)
        if (crashFile.length() > 50_000) {
            // 超过 50KB，保留最后一半
            val content = crashFile.readText()
            crashFile.writeText(content.takeLast(25_000))
        }

        Log.e(TAG, "崩溃已记录到 ${crashFile.absolutePath}", throwable)
    }
}
