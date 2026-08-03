package com.liquidglass.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.liquidglass.app.music.MediaButtonReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用通知助手 — 更新提醒 / 推荐通知 / 下载进度通知 / 媒体歌词通知。
 *
 * Android 13+ 需要 POST_NOTIFICATIONS 运行时权限，未授予时通知静默失败（不崩溃）。
 */
object NotificationHelper {

    // 通知频道
    const val CHANNEL_UPDATE = "channel_update"
    // v2 频道：原 channel_recommend 使用 IMPORTANCE_DEFAULT 不弹横幅，
    // 升级为 IMPORTANCE_HIGH 后需用新频道ID才能生效（Android 不允许修改已存在频道的importance）
    const val CHANNEL_RECOMMEND = "channel_recommend_v2"
    const val CHANNEL_DOWNLOAD = "channel_download"
    const val CHANNEL_MEDIA = "channel_media"

    // 通知 ID
    const val NOTIF_UPDATE = 1001
    const val NOTIF_RECOMMEND = 1002
    const val NOTIF_DOWNLOAD = 1003
    /** 媒体通知（含多行歌词+播放控制）ID，作为前台服务通知常驻 */
    const val NOTIF_MEDIA = 1005

    /** 初始化通知频道（App 启动时调用一次） */
    fun initChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        fun create(id: String, name: String, desc: String, importance: Int) {
            if (mgr.getNotificationChannel(id) == null) {
                val ch = NotificationChannel(id, name, importance).apply { description = desc }
                mgr.createNotificationChannel(ch)
            }
        }
        create(CHANNEL_UPDATE, "更新提醒", "新版本可用时推送提醒", NotificationManager.IMPORTANCE_HIGH)
        create(CHANNEL_RECOMMEND, "内容推荐", "音乐/功能推荐消息", NotificationManager.IMPORTANCE_HIGH)
        create(CHANNEL_DOWNLOAD, "下载进度", "资源包下载进度通知", NotificationManager.IMPORTANCE_LOW)
        // 媒体通知频道：高优先级（支持 FullScreenIntent 弹锁屏），但静音避免每次更新响铃
        if (mgr.getNotificationChannel(CHANNEL_MEDIA) == null) {
            val ch = NotificationChannel(CHANNEL_MEDIA, "音乐播放", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "音乐播放时显示多行歌词与播放控制按钮"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        }
    }

    /** 检查通知权限是否已授予（Android 13+） */
    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** 发送"发现新版本"通知 */
    fun notifyUpdate(context: Context, version: String, notes: String) {
        if (!hasPermission(context)) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("action", "check_update")
        }
        val pi = PendingIntent.getActivity(
            context, NOTIF_UPDATE, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_UPDATE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("发现新版本 v$version")
            .setContentText(notes.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(notes))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        mgr.notify(NOTIF_UPDATE, notif)
    }

    /** 发送内容推荐通知（target 为目标功能页路由名，对应 Screen 枚举名，点击直达） */
    fun notifyRecommend(context: Context, title: String, content: String, target: String = "MUSIC") {
        if (!hasPermission(context)) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("action", "open_feature")
            putExtra("target", target)
        }
        val pi = PendingIntent.getActivity(
            context, NOTIF_RECOMMEND, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 自定义 RemoteViews 布局：渐变背景 + 色条 + 标签 + 图标，替代简陋的纯文本通知
        val expandedView = android.widget.RemoteViews(context.packageName, R.layout.notification_recommend)
        expandedView.setTextViewText(R.id.notif_title, "液态玻璃 · 推荐")
        expandedView.setTextViewText(R.id.notif_content_title, title)
        expandedView.setTextViewText(R.id.notif_content, content)
        // 根据目标功能显示不同标签
        val tagText = when (target) {
            "MUSIC" -> "音乐"
            "GALLERY" -> "壁纸"
            "COMPASS" -> "工具"
            "CALCULATOR" -> "工具"
            "ABOUT" -> "更新"
            else -> "推荐"
        }
        expandedView.setTextViewText(R.id.notif_tag, tagText)
        // 时间显示
        val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        expandedView.setTextViewText(R.id.notif_time, timeStr)

        // 折叠状态下的精简布局（系统通知栏收起时）
        val collapsedView = android.widget.RemoteViews(context.packageName, R.layout.notification_recommend)
        collapsedView.setTextViewText(R.id.notif_title, title)
        collapsedView.setTextViewText(R.id.notif_content_title, title)
        collapsedView.setTextViewText(R.id.notif_content, content)
        collapsedView.setTextViewText(R.id.notif_tag, tagText)
        collapsedView.setTextViewText(R.id.notif_time, timeStr)

        val notif = NotificationCompat.Builder(context, CHANNEL_RECOMMEND)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setCustomContentView(collapsedView)
            .setCustomBigContentView(expandedView)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        mgr.notify(NOTIF_RECOMMEND, notif)
    }

    /** 更新下载进度通知（连续调用更新进度条） */
    fun notifyDownloadProgress(context: Context, title: String, progress: Int, speedText: String) {
        if (!hasPermission(context)) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notif = NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("$progress%  $speedText")
            .setProgress(100, progress, progress < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        mgr.notify(NOTIF_DOWNLOAD, notif)
    }

    /** 取消下载进度通知 */
    fun cancelDownloadNotif(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.cancel(NOTIF_DOWNLOAD)
    }

    // ======================== 自定义媒体通知（多行歌词 + 播放控制合一） ========================
    // RemoteViews 同时承载：专辑封面背景 / 渐变遮罩 / 多行歌词 / 可拖动进度条 / 播放按钮。

    /** 上一次通知的歌词签名，用于判断是否需要刷新通知 */
    private var lastLyricSignature: String = ""
    /** 封面 bitmap 缓存（key=coverUrl），避免每次通知更新都重新下载 */
    private var coverBitmapCache: Bitmap? = null
    private var coverUrlCache: String = ""
    private val notifScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 构建媒体通知（返回 Notification 对象，不直接 notify）。
     * 供 MusicService.onUpdateNotification 使用：先拿到通知对象调用 startForeground，
     * 再 notify，确保首次播放时前台服务正确启动。
     * 返回 null 表示无通知权限或构建失败。
     */
    fun buildMediaNotification(
        context: Context,
        title: String,
        artist: String,
        lines: List<String>,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        coverUrl: String = ""
    ): android.app.Notification? {
        if (!hasPermission(context)) return null

        val prev = lines.getOrNull(0)?.ifBlank { "♪" } ?: "♪"
        val curr = lines.getOrNull(1)?.ifBlank { "♪ 纯音乐 ♪" } ?: "♪ 纯音乐 ♪"
        val next = lines.getOrNull(2)?.ifBlank { "♪" } ?: "♪"

        // ── 构建 RemoteViews ──
        val expandedView = RemoteViews(context.packageName, R.layout.notification_media_lyric)
        expandedView.setTextViewText(R.id.notif_title, title)
        expandedView.setTextViewText(R.id.notif_artist, artist)
        expandedView.setTextViewText(R.id.notif_lyric_prev, prev)
        expandedView.setTextViewText(R.id.notif_lyric_curr, curr)
        expandedView.setTextViewText(R.id.notif_lyric_next, next)

        // 封面背景：优先用缓存 bitmap，无封面时用纯色兜底
        if (coverBitmapCache != null && coverUrlCache == coverUrl) {
            expandedView.setImageViewBitmap(R.id.notif_cover_bg, coverBitmapCache)
        } else if (coverUrl.isNotBlank() && coverUrl != coverUrlCache) {
            coverUrlCache = coverUrl
            notifScope.launch {
                val bmp = loadCoverBitmap(coverUrl)
                if (bmp != null) {
                    coverBitmapCache = bmp
                    mainHandler.post { lastLyricSignature = "" }
                }
            }
        }

        // 可拖动进度条（SeekBar，范围 0-1000）
        if (durationMs > 0) {
            val progress = ((positionMs.toFloat() / durationMs) * 1000).toInt().coerceIn(0, 1000)
            expandedView.setProgressBar(R.id.notif_seekbar, 1000, progress, false)
        } else {
            expandedView.setProgressBar(R.id.notif_seekbar, 1000, 0, true)
        }

        expandedView.setImageViewResource(
            R.id.btn_play_pause,
            if (isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play
        )

        fun mediaPending(action: String): PendingIntent = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, MediaButtonReceiver::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        expandedView.setOnClickPendingIntent(R.id.btn_prev, mediaPending(MediaButtonReceiver.ACTION_PREV))
        expandedView.setOnClickPendingIntent(R.id.btn_play_pause, mediaPending(MediaButtonReceiver.ACTION_PLAY_PAUSE))
        expandedView.setOnClickPendingIntent(R.id.btn_next, mediaPending(MediaButtonReceiver.ACTION_NEXT))

        val seekIntent = Intent(context, MediaButtonReceiver::class.java).apply {
            action = MediaButtonReceiver.ACTION_SEEK
            putExtra(MediaButtonReceiver.EXTRA_SEEK_DURATION, durationMs)
        }
        expandedView.setOnClickPendingIntent(
            R.id.notif_seekbar,
            PendingIntent.getBroadcast(
                context, MediaButtonReceiver.ACTION_SEEK.hashCode(),
                seekIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            // 修复：缺少 FLAG_ACTIVITY_NEW_TASK，从 Service 上下文启动 Activity 必须加此 flag
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("action", "open_music")
        }
        val contentPi = PendingIntent.getActivity(
            context, NOTIF_MEDIA, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_MEDIA)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(curr)
            .setCustomBigContentView(expandedView)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /**
     * 更新自定义媒体通知（直接 notify）。
     * 保留供外部调用，内部委托给 [buildMediaNotification]。
     */
    fun updateMediaNotification(
        context: Context,
        title: String,
        artist: String,
        lines: List<String>,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        coverUrl: String = "",
        forceUpdate: Boolean = false
    ) {
        val notif = buildMediaNotification(
            context, title, artist, lines, isPlaying, positionMs, durationMs, coverUrl
        ) ?: return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val prev = lines.getOrNull(0)?.ifBlank { "♪" } ?: "♪"
        val curr = lines.getOrNull(1)?.ifBlank { "♪ 纯音乐 ♪" } ?: "♪ 纯音乐 ♪"
        val next = lines.getOrNull(2)?.ifBlank { "♪" } ?: "♪"
        val progressPercent = if (durationMs > 0) (positionMs * 100 / durationMs).toInt() else 0
        val signature = "$title|$artist|$prev|$curr|$next|$isPlaying|$progressPercent|$coverUrl"
        if (!forceUpdate && signature == lastLyricSignature) return
        lastLyricSignature = signature

        mgr.notify(NOTIF_MEDIA, notif)
    }

    /** IO 线程下载封面 bitmap，失败返回 null */
    private suspend fun loadCoverBitmap(urlStr: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }

    /** 取消媒体通知（停止播放时） */
    fun cancelMediaNotification(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.cancel(NOTIF_MEDIA)
        lastLyricSignature = ""
        coverBitmapCache = null
        coverUrlCache = ""
    }

    // ======================== 内容推荐通知文案池 ========================
    // 36 条覆盖音乐/工具/资源包/壁纸/白噪音/生活等场景，定时推送不重复轮播。
    // 每条第三项 target 对应 Screen 枚举名，通知点击直达对应功能页。
    private data class RecommendItem(val title: String, val content: String, val target: String)
    private val RECOMMEND_POOL = listOf(
        RecommendItem("今日推荐 · 私人FM", "发现你的专属电台，点击进入音乐播放器即刻收听 →", "MUSIC"),
        RecommendItem("新歌速递已就位", "本周最新华语/欧美/日韩新歌上线，来听听有没有你的菜", "MUSIC"),
        RecommendItem("DJ电台精选", "深夜emo时刻，主播电波陪你看月亮，点击收听 →", "MUSIC"),
        RecommendItem("相似歌曲推荐", "喜欢这首歌的人也喜欢这些，点击发现更多 →", "MUSIC"),
        RecommendItem("新碟上架", "本周新专辑速览，周杰伦/泰勒/防弹都在内 →", "MUSIC"),
        RecommendItem("MV精选", "热门MV排行榜，视觉与听觉的双重盛宴 →", "MUSIC"),
        RecommendItem("壁纸画廊更新", "30张高清液态玻璃壁纸待你查收，点击设为桌面 →", "GALLERY"),
        RecommendItem("白噪音助眠", "雨声/海浪/篝火...挑一段陪你入眠，点击播放 →", "AUDIO_PLAYER"),
        RecommendItem("单位换算小贴士", "支持货币实时汇率换算，出国旅行必备工具，点击体验 →", "UNIT_CONVERTER"),
        RecommendItem("待办清单提醒", "今天还有未完成的事吗？来待办清单理一理 →", "TODO"),
        RecommendItem("密码生成器", "还在用 123456？让密码生成器帮你造个强密码 →", "PASSWORD_GEN"),
        RecommendItem("二维码生成", "Wi-Fi/网址/文本一键生成二维码，分享更优雅 →", "QR_CODE"),
        RecommendItem("资源包管理", "解锁更多主题与特效，7层资源包等你下载 →", "ABOUT"),
        RecommendItem("倒计时提醒", "距离重要日子还有多久？建个倒计时一目了然 →", "COUNTDOWN"),
        RecommendItem("BMI健康计算", "关注身体状态，30秒算出你的健康指数 →", "BMI"),
        RecommendItem("指南针水平仪", "户外探险好帮手，点击打开液态玻璃指南针 →", "COMPASS"),
        RecommendItem("颜色选择器", "设计师必备取色工具，色带取色一键复制色值 →", "COLOR_PICKER"),
        RecommendItem("网易云VIP特权", "登录后畅享千万曲库，扫码即可安全登录 →", "MUSIC"),
        RecommendItem("日历日程提醒", "别错过今天的安排，打开日历查看本周日程 →", "CALENDAR"),
        RecommendItem("记事本速记", "灵感转瞬即逝？打开记事本随手记下来 →", "NOTE"),
        RecommendItem("涂鸦画板", "无聊了？来涂鸦画板画两笔解压 →", "DRAWING"),
        RecommendItem("手电筒快捷", "夜晚找钥匙？一键开启液态玻璃手电筒 →", "FLASHLIGHT"),
        RecommendItem("科学计算器", "复杂公式一键搞定，记忆功能记录历史 →", "CALCULATOR"),
        RecommendItem("世界时钟", "跨国开会/联系亲友？查查对方现在几点 →", "CLOCK"),
        RecommendItem("实时天气", "出门穿什么？查查实时天气与7日预报 →", "CLOCK"),
        RecommendItem("城市天气搜索", "输入任意城市名，全球天气一查便知 →", "CLOCK"),
        RecommendItem("主题切换", "超级无敌淡雅白/深海蓝调/翡翠森林...换个心情 →", "ABOUT"),
        RecommendItem("iOS液态玻璃主题", "苹果风液态玻璃主题已上线，快来体验 →", "ABOUT"),
        RecommendItem("交互外观包", "下载交互包解锁霓虹赛博/薄荷清新等额外主题 →", "ABOUT"),
        RecommendItem("核心功能补丁包", "未下载补丁包则计算器/单位换算不可用，点击下载 →", "ABOUT"),
        RecommendItem("高级体验初始化包", "解锁音乐/日历/待办/笔记等高级功能，点击下载 →", "ABOUT"),
        RecommendItem("7层资源系统", "全新强制层级资源包，逐层解锁完整体验 →", "ABOUT"),
        RecommendItem("检查更新", "发现新版本？点击立即检查并一键升级 →", "ABOUT"),
        RecommendItem("法律与公告中心", "隐私政策/用户协议已更新，点击查看详情 →", "LEGAL_CENTER"),
        RecommendItem("更新日志", "想看每次更新都改了啥？关于页有完整更新日志 →", "ABOUT"),
        RecommendItem("声波可视化下线", "已移除花架子的声波可视化，聚焦实用功能 →", "ABOUT")
    )

    /**
     * 不重复轮播队列：洗牌一份索引，按序弹出；全部播完再洗牌下一轮。
     * 这样每条广告在一轮内只出现一次，避免短期内重复打扰。
     */
    private val recommendQueue = java.util.ArrayDeque<Int>()
    private val queueLock = Any()

    /**
     * 取下一条推荐文案并推送（不重复轮播）。
     * 线程安全：多协程并发调用时也只按队列顺序出队。
     */
    fun pushNextRecommend(context: Context) {
        val idx = synchronized(queueLock) {
            if (recommendQueue.isEmpty()) {
                // 重新洗牌一轮：所有索引随机排列入队
                val indices = RECOMMEND_POOL.indices.toMutableList()
                indices.shuffle()
                recommendQueue.addAll(indices)
            }
            recommendQueue.poll()
        } ?: return
        val item = RECOMMEND_POOL[idx]
        notifyRecommend(context, item.title, item.content, item.target)
    }

    /** 随机选一条推荐文案并推送（兼容旧调用，仍可能重复）。 */
    fun pushRandomRecommend(context: Context) {
        val item = RECOMMEND_POOL.random()
        notifyRecommend(context, item.title, item.content, item.target)
    }

    /** 推荐文案池大小（供 UI 展示统计用） */
    val recommendPoolSize: Int get() = RECOMMEND_POOL.size
}
