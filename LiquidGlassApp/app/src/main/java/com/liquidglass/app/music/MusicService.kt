package com.liquidglass.app.music

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.liquidglass.app.MainActivity
import com.liquidglass.app.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 后台音乐播放服务。
 *
 * - 承载 ExoPlayer + MediaSession，承担前台播放、媒体通知、锁屏控件
 * - 自定义媒体通知：RemoteViews 同时显示多行歌词（当前行高亮放大）+ 播放控制按钮 + 进度条
 * - 通过 FullScreenIntent 在锁屏时直接弹出 LockScreenActivity（专辑封面占满+手势解锁）
 * - 监听 ACTION_SCREEN_OFF：息屏时主动刷新通知触发 FullScreenIntent（解决"播放中息屏不弹锁屏"）
 * - 歌词刷新 100ms tick，仅在歌词行变化时才 notify（避免无变化时被系统节流丢帧造成延迟感）
 *
 * UI 通过 MediaController（SessionToken）与该服务通信，见 [MusicControllerManager]。
 */
class MusicService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lyricJob: Job? = null

    /** 上一次通知的歌词签名，用于判断是否需要刷新通知 */
    private var lastNotifSignature: String = ""

    /**
     * 屏幕状态监听：息屏且正在播放时，直接启动 LockScreenActivity 覆盖系统锁屏。
     *
     * 关键点：MusicService 是前台服务（foregroundServiceType=mediaPlayback），
     * 豁免 Android 10+ 的后台 Activity 启动限制，可直接 startActivity。
     * 这是比 FullScreenIntent 更可靠的方案（后者仅在通知首次发布时触发）。
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                val p = player ?: return
                if (p.isPlaying) {
                    try {
                        // WakeLock 强制点亮屏幕（SCREEN_OFF 时屏幕已黑，setTurnScreenOn 在 Activity 创建后才生效，时序不可靠）
                        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                        val wakeLock = pm.newWakeLock(
                            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            android.os.PowerManager.ON_AFTER_RELEASE,
                            "LiquidGlass:LockScreen"
                        )
                        wakeLock.setReferenceCounted(false)
                        wakeLock.acquire(10000) // 10秒自动释放

                        val lockIntent = Intent(context, com.liquidglass.app.ui.LockScreenActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
                        }
                        context.startActivity(lockIntent)
                    } catch (e: Exception) {
                        android.util.Log.e("LockScreen", "SCREEN_OFF 启动锁屏失败", e)
                    }
                }
            }
        }
    }

    /**
     * 重写 onUpdateNotification：完全用自定义媒体通知（多行歌词+播放控制合一），
     * 不调用 super 以避免 Media3 默认通知（系统默认样式无歌词）。
     *
     * 同时处理前台服务启动：[startInForegroundRequired]=true 时调用 startForeground。
     *
     * 关键修复：首次播放时 activeNotifications 中还没有 NOTIF_MEDIA 通知，
     * 必须先用构建好的通知调用 startForeground（而不是从 activeNotifications 取），
     * 否则 Media3 会因"前台服务未在 5 秒内启动"抛 IllegalStateException → 闪退。
     * 整个方法包裹 try/catch，任何异常都不应导致闪退。
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        try {
            val p = player ?: return
            val item = p.currentMediaItem
            val title = item?.mediaMetadata?.title?.toString() ?: "未在播放"
            val artist = item?.mediaMetadata?.artist?.toString() ?: ""
            val positionMs = p.currentPosition.coerceAtLeast(0L)
            val durationMs = p.duration.coerceAtLeast(0L)
            val lines = MusicControllerManager.computeLyricLines(positionMs)
            val isPlaying = p.isPlaying
            val coverUrl = item?.mediaMetadata?.artworkUri?.toString() ?: ""

            // 切歌/状态变化时强制更新
            val signature = "$title|$artist|${lines.joinToString("|")}|$isPlaying|$coverUrl"
            val force = startInForegroundRequired || signature != lastNotifSignature
            lastNotifSignature = signature

            // 构建通知（不直接 notify，先拿到 Notification 对象）
            val notif = NotificationHelper.buildMediaNotification(
                context = this,
                title = title,
                artist = artist,
                lines = lines,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                coverUrl = coverUrl
            )

            // 前台服务启动：Media3 在首次 play() 时会要求 startForeground。
            // 必须用刚构建的通知调用 startForeground，不能从 activeNotifications 取（首次播放时还没 notify 过）。
            if (startInForegroundRequired && notif != null) {
                startForeground(NotificationHelper.NOTIF_MEDIA, notif)
            }

            // notify 通知（startForeground 后再 notify 确保通知可见）
            if (notif != null) {
                val mgr = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                mgr.notify(NotificationHelper.NOTIF_MEDIA, notif)
            }
        } catch (e: Exception) {
            android.util.Log.e("MusicService", "onUpdateNotification 失败", e)
            // 降级：尝试用 super 的默认通知启动前台服务，避免 Media3 崩溃
            try {
                @Suppress("DEPRECATION")
                super.onUpdateNotification(session, startInForegroundRequired)
            } catch (_: Exception) {
                // 最后兜底也失败，不再传播异常
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)  // 拔耳机自动暂停
            .build()

        // 监听播放状态变化，启停歌词通知协程 + 强制刷新一次通知
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // 状态变化时立即强制刷新一次通知（按钮图标/前台服务）
                lastNotifSignature = ""
                updateNotificationNow()
                if (isPlaying) startLyricTick() else stopLyricTick()
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // 切歌时立即刷新通知（标题/歌词全变）
                lastNotifSignature = ""
                updateNotificationNow()
            }
        })

        // 点击通知回到 App
        val pendingIntent = Intent(this, MainActivity::class.java).let { intent ->
            // 修复：缺少 FLAG_ACTIVITY_NEW_TASK，从 Service 上下文启动 Activity 必须加此 flag
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        session = MediaSession.Builder(this, exo)
            .setSessionActivity(pendingIntent)
            .build()

        player = exo

        // 注册屏幕状态监听（息屏时触发 FullScreenIntent 弹锁屏）
        // RECEIVER_NOT_EXPORTED：SCREEN_OFF 是系统 protected broadcast，必须非导出（targetSdk 34+ 强制）
        registerReceiver(
            screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF) },
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * 歌词通知协程：100ms tick 计算当前歌词行。
     * 仅在歌词行签名变化时才 notify（避免无变化时频繁更新被系统节流丢帧，造成"延迟感"）。
     */
    private fun startLyricTick() {
        lyricJob?.cancel()
        lyricJob = serviceScope.launch {
            while (true) {
                updateNotificationNow()
                delay(100)  // 100ms 高频 tick，歌词行变化时立即刷新
            }
        }
    }

    /** 停止歌词通知协程（暂停/停止时） */
    private fun stopLyricTick() {
        lyricJob?.cancel()
        lyricJob = null
        // 暂停时保留最后一条通知（锁屏仍可见歌词状态），不 cancel
    }

    /** 立即刷新一次通知（onUpdateNotification 的轻量封装） */
    private fun updateNotificationNow() {
        session?.let { onUpdateNotification(it, false) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * 用户从最近任务列表划掉 App 时调用。
     * 释放播放器并停止服务，避免后台残留进程。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady ||
            p.mediaItemCount == 0 ||
            p.playbackState == Player.STATE_ENDED
        ) {
            // 没在播放或已结束：直接停止服务
            stopSelf()
        }
        // 否则保持前台服务继续播放（用户划掉 App 但音乐继续，符合音乐 App 预期）
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        stopLyricTick()
        NotificationHelper.cancelMediaNotification(this)
        serviceScope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }
}

