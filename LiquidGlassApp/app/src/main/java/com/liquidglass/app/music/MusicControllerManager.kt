package com.liquidglass.app.music

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 音质档位（与主流音乐软件一致）。
 *
 * - [STANDARD] 标准 128kbps（流量小，弱网推荐）
 * - [EXHIGH]   极高 320kbps（默认，平衡音质与流量）
 * - [LOSSLESS] 无损 FLAC（VIP 专享；非 VIP 实际拉到的是 exhigh，由 [NetEaseApi.songUrl] 自动降级）
 */
enum class PlaybackQuality(val label: String, val level: String, val desc: String) {
    STANDARD("标准", "standard", "128 kbps · 流量小"),
    EXHIGH("极高", "exhigh", "320 kbps · 推荐"),
    LOSSLESS("无损", "lossless", "FLAC · VIP 专享");

    companion object {
        /** 持久化键名 */
        private const val PREFS = "music_quality"
        private const val KEY_QUALITY = "quality_ordinal"

        fun load(context: Context): PlaybackQuality {
            val ord = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_QUALITY, EXHIGH.ordinal)
            return entries.getOrElse(ord) { EXHIGH }
        }

        fun save(context: Context, q: PlaybackQuality) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_QUALITY, q.ordinal).apply()
        }
    }
}

/**
 * UI 侧播放控制器（单例）。
 *
 * - 持有 [MediaController] 与 [MusicService] 通信
 * - [playSongs] 预解析网易云歌曲 URL（IO 线程），再一次性 setMediaItems
 * - 暴露 [state]：当前曲目/播放状态/进度/队列，UI 据此渲染
 */
object MusicControllerManager {

    data class PlaybackState(
        val song: Song? = null,
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val queue: List<Song> = emptyList(),
        val currentIndex: Int = -1,
        val repeatMode: Int = Player.REPEAT_MODE_OFF,
        val shuffle: Boolean = false,
        /** 当前音质档位（UI 可观察，切换后立即反映） */
        val quality: PlaybackQuality = PlaybackQuality.EXHIGH,
        // 当前歌词文本（用于状态栏/锁屏通知显示，空串表示无歌词或纯音乐）
        val currentLyric: String = ""
    )

    /** 当前曲目的完整歌词缓存（供 MusicService 实时计算当前行） */
    private var currentLyrics: Lyrics = Lyrics()
    private var currentLyricSongId: String = ""

    /** 缓存当前曲目的歌词（播放新歌时由 UI 调用） */
    fun setLyrics(songId: String, lyrics: Lyrics) {
        currentLyricSongId = songId
        currentLyrics = lyrics
    }

    /**
     * 根据当前播放进度计算当前应显示的歌词行。
     * 优先逐字 yrc，回退逐行 lrc。
     */
    fun computeCurrentLyric(positionMs: Long): String {
        return computeLyricLines(positionMs).getOrNull(1) ?: ""
    }

    /**
     * 根据当前播放进度计算多行歌词（供状态栏/锁屏多行显示）。
     * @return 长度为 3 的列表：[0]=上一行, [1]=当前行, [2]=下一行
     *         无歌词时返回 ["","纯音乐",""]（v2.9.1：移除音符符号，纯文字）
     */
    fun computeLyricLines(positionMs: Long): List<String> {
        // 统一成 (timeMs, text) 列表处理
        val lines: List<Pair<Long, String>> = when {
            currentLyrics.yrcLines.isNotEmpty() ->
                currentLyrics.yrcLines.map { it.startMs to it.chars.joinToString("") { c -> c.content }.ifBlank { "..." } }
            currentLyrics.lrcLines.isNotEmpty() ->
                currentLyrics.lrcLines.map { it.timeMs to it.content.ifBlank { "..." } }
            else -> return listOf("", "纯音乐", "")
        }

        // 找到当前行索引：最后一个 timeMs <= positionMs 的行
        var currentIndex = -1
        for ((i, pair) in lines.withIndex()) {
            if (pair.first <= positionMs) currentIndex = i else break
        }

        val prev = if (currentIndex > 0) lines[currentIndex - 1].second else ""
        val curr = if (currentIndex >= 0) lines[currentIndex].second else "纯音乐"
        val next = if (currentIndex in 0 until lines.size - 1) lines[currentIndex + 1].second else ""
        return listOf(prev, curr, next)
    }

    /** 当前歌词对象（供锁屏 Activity 取全部行做滚动渲染） */
    fun currentLyricsData(): Lyrics = currentLyrics

    /** 当前歌曲 id（供锁屏 Activity 判断是否切歌） */
    fun currentLyricSongId(): String = currentLyricSongId

    /** 播放错误事件（VIP/无版权拿不到URL等）。UI collect 后 Toast 提示并清空。 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun consumeError() { _error.value = null }

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var controller: MediaController? = null
    @Volatile
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var appContext: Context? = null
    /**
     * 协程 scope：装 CoroutineExceptionHandler 兜底所有未捕获异常，
     * 避免 playSongs/resolveStreamUrl 抛异常时直接闪退（根因之一）。
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
            if (e !is kotlinx.coroutines.CancellationException) {
                _error.value = "播放失败：${e.message ?: "未知错误"}"
            }
        }
    )

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateState()
        override fun onPlaybackStateChanged(playbackState: Int) = updateState()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updateState()
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = updateState()
        override fun onRepeatModeChanged(repeatMode: Int) = updateState()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = updateState()
        override fun onPlayerErrorChanged(error: androidx.media3.common.PlaybackException?) {
            if (error != null) _error.value = "播放失败：可能无版权或网络异常"
        }
    }

    private var queue: List<Song> = emptyList()

    /** future 回调是否已执行（用于区分"连接中"与"连接已失败"，支持失败后重连） */
    @Volatile
    private var connectionCompleted: Boolean = false

    @Synchronized
    fun init(context: Context) {
        // 仅当已成功连接（controller != null）才跳过；
        // 若 controllerFuture != null 但 controller == null 且 connectionCompleted=true，
        // 说明上次连接已失败，清理后重新创建，避免永远卡死无法重连。
        if (controller != null) return
        if (controllerFuture != null && !connectionCompleted) return  // 仍在连接中，等待
        // 上次连接已失败，清理残留后重新创建
        if (controllerFuture != null) {
            try { MediaController.releaseFuture(controllerFuture!!) } catch (_: Exception) {}
            controllerFuture = null
        }
        connectionCompleted = false
        appContext = context.applicationContext
        // 启动时加载持久化的音质档位（默认 EXHIGH，与主流音乐软件一致）
        val savedQuality = PlaybackQuality.load(context.applicationContext)
        _state.value = _state.value.copy(quality = savedQuality)
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        controllerFuture = MediaController.Builder(context, token).buildAsync()
        controllerFuture?.addListener({
            connectionCompleted = true
            try {
                controller = controllerFuture?.get()
                controller?.addListener(listener)
                updateState()
            } catch (e: Exception) {
                // 连接失败不再静默吞掉：记录日志 + 给用户反馈，避免"点歌没反应"难以定位
                android.util.Log.e("MusicControllerManager", "MediaController 连接失败", e)
                controller = null
                _error.value = "播放器连接失败，请稍后重试"
            }
        }, ContextCompat_mainExecutor(context))
    }

    /**
     * 切换音质档位：持久化保存 + 更新可观察状态。
     *
     * 切换不影响当前正在播放的曲目（已下载到 ExoPlayer 缓存的码率不会变），
     * 下一首或重新播放当前曲目时按新音质拉取流。
     */
    fun setQuality(context: Context, q: PlaybackQuality) {
        PlaybackQuality.save(context.applicationContext, q)
        _state.value = _state.value.copy(quality = q)
    }

    fun release() {
        try {
            controller?.removeListener(listener)
            val future = controllerFuture
            if (future != null) MediaController.releaseFuture(future)
        } catch (_: Exception) {
            // Media3 在 service 未注册成功时 release 会抛 IllegalArgumentException("Service not registered")，
            // 属良性异常（controller 已在释放中），忽略即可。
        }
        controller = null
        controllerFuture = null
        connectionCompleted = false
    }

    /**
     * 播放一组歌曲。
     * 网易云歌曲先解析播放 URL，本地歌曲直接用 contentUri。
     * URL 为空（VIP/无版权）的歌曲会被过滤，若全部为空则报错提示。
     */
    fun playSongs(context: Context, songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        queue = songs
        scope.launch {
            try {
                // controller 未就绪时给用户明确反馈，不再静默丢弃
                if (controller == null) {
                    _error.value = "播放器初始化中，请稍后重试"
                    return@launch
                }
                // 解析每首歌可播放 URL 并构建 MediaItem；urlBlank 标记 VIP/无版权
                data class Resolved(val mediaItem: MediaItem, val urlBlank: Boolean)
                val resolved = songs.mapIndexed { idx, song ->
                    async(Dispatchers.IO) {
                        val url = resolveStreamUrl(song)
                        idx to Resolved(song.buildMediaItem(if (url.isBlank()) null else url), url.isBlank())
                    }
                }.awaitAll()
                val startIdx = startIndex.coerceIn(0, songs.lastIndex)
                // 起始曲拿不到 URL（VIP/无版权）→ 友好提示并直接返回，不再把无 URI 的 item 喂给播放器
                val startResolved = resolved.firstOrNull { it.first == startIdx }?.second
                if (startResolved != null && startResolved.urlBlank) {
                    val s = songs[startIdx]
                    _error.value = if (s.isVipOnly) "该歌曲为 VIP 专享，当前账号无播放权限" else "该歌曲暂无播放源（无版权）"
                    return@launch
                }
                val mediaItems = resolved.map { it.second.mediaItem }
                val c = controller ?: run {
                    _error.value = "播放器未就绪，请稍后重试"
                    return@launch
                }
                c.setMediaItems(mediaItems, startIdx, 0L)
                c.prepare()
                c.play()
                updateState()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // CancellationException 必须重新抛出，不能吞
            } catch (e: Exception) {
                _error.value = "播放失败：${e.message ?: "未知错误"}"
            }
        }
    }

    fun playSingle(context: Context, song: Song) = playSongs(context, listOf(song), 0)

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() { controller?.seekToNext() }
    fun previous() { controller?.seekToPrevious() }
    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }

    // ───────────────────────── 播放队列管理 ─────────────────────────

    /** 跳到队列中指定位置并播放 */
    fun playQueueItemAt(index: Int) {
        val c = controller ?: return
        if (index !in queue.indices) return
        c.seekToDefaultPosition(index)
        c.play()
    }

    /** 将单首歌添加到播放队列末尾（不切换当前播放） */
    fun addToQueue(context: Context, song: Song) {
        scope.launch {
            try {
                val url = resolveStreamUrl(song)
                val mediaItem = song.buildMediaItem(if (url.isBlank()) null else url)
                val c = controller ?: run {
                    _error.value = "播放器未就绪"
                    return@launch
                }
                c.addMediaItem(mediaItem)
                queue = queue + song
                updateState()
                if (url.isBlank()) {
                    _error.value = if (song.isVipOnly) "该歌曲为VIP专享，已加入队列但可能无法播放" else "该歌曲暂无播放源"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "加入队列失败：${e.message ?: "未知错误"}"
            }
        }
    }

    /** 批量将多首歌添加到播放队列末尾（不切换当前播放） */
    fun addToQueue(context: Context, songs: List<Song>) {
        if (songs.isEmpty()) return
        scope.launch {
            try {
                // 并发解析每首歌可播放 URL，构建 MediaItem 列表
                data class Resolved(val mediaItem: MediaItem, val song: Song, val urlBlank: Boolean)
                val resolved = songs.map { song ->
                    async(Dispatchers.IO) {
                        val url = resolveStreamUrl(song)
                        Resolved(song.buildMediaItem(if (url.isBlank()) null else url), song, url.isBlank())
                    }
                }.awaitAll()
                val c = controller ?: run {
                    _error.value = "播放器未就绪"
                    return@launch
                }
                c.addMediaItems(resolved.map { it.mediaItem })
                queue = queue + resolved.map { it.song }
                updateState()
                // 任一歌曲拿不到 URL 时给出提示
                val firstBlank = resolved.firstOrNull { it.urlBlank }
                if (firstBlank != null) {
                    _error.value = if (firstBlank.song.isVipOnly) "部分歌曲为VIP专享，已加入队列但可能无法播放" else "部分歌曲暂无播放源"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "加入队列失败：${e.message ?: "未知错误"}"
            }
        }
    }

    /** 上移一首（与前一曲交换）。已在首位则无效 */
    fun moveQueueUp(index: Int) {
        if (index <= 0) return
        moveQueueItem(index, index - 1)
    }

    /** 下移一首（与后一曲交换）。已在末位则无效 */
    fun moveQueueDown(index: Int) {
        if (index >= queue.lastIndex) return
        moveQueueItem(index, index + 1)
    }

    /** 移动队列项：同步更新 MediaController 与本地 queue 镜像 */
    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val c = controller ?: return
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) return
        c.moveMediaItem(fromIndex, toIndex)
        queue = queue.toMutableList().apply {
            val item = removeAt(fromIndex)
            add(toIndex, item)
        }
        updateState()
    }

    /** 删除队列中指定位置的歌曲。删最后一首时停止播放 */
    fun removeQueueItem(index: Int) {
        val c = controller ?: return
        if (index !in queue.indices) return
        if (queue.size == 1) {
            c.clearMediaItems()
            queue = emptyList()
            _state.value = _state.value.copy(song = null, isPlaying = false, isBuffering = false, queue = emptyList(), currentIndex = -1, positionMs = 0L, durationMs = 0L)
            return
        }
        c.removeMediaItem(index)
        queue = queue.toMutableList().apply { removeAt(index) }
        updateState()
    }

    /** 清空整个播放队列并停止 */
    fun clearQueue() {
        val c = controller ?: return
        c.clearMediaItems()
        c.stop()
        queue = emptyList()
        _state.value = _state.value.copy(song = null, isPlaying = false, isBuffering = false, queue = emptyList(), currentIndex = -1, positionMs = 0L, durationMs = 0L)
    }

    /** 循环模式：OFF → ONE → ALL → OFF */
    fun cycleRepeatMode() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    /** 由 UI 定时调用刷新进度（Media3 不对每帧推流） */
    fun tickPosition() {
        val c = controller ?: return
        if (_state.value.isPlaying || _state.value.isBuffering) {
            _state.value = _state.value.copy(
                positionMs = c.currentPosition.coerceAtLeast(0L),
                durationMs = c.duration.coerceAtLeast(0L)
            )
        }
    }

    private fun updateState() {
        val c = controller ?: return
        val idx = c.currentMediaItemIndex
        val song = queue.getOrNull(idx)
        _state.value = _state.value.copy(
            song = song,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = c.duration.coerceAtLeast(0L),
            queue = queue,
            currentIndex = idx,
            repeatMode = c.repeatMode,
            shuffle = c.shuffleModeEnabled
        )
    }

    /** 解析歌曲可播放 URL：本地直接用 streamUrl，网易云走 songUrl 接口（按当前音质档位） */
    private suspend fun resolveStreamUrl(song: Song): String = when (song.source) {
        Source.LOCAL -> song.streamUrl
        Source.NETEASE -> NetEaseApi.songUrl(song.id, _state.value.quality.level)
    }

    /** 构建 Media3 MediaItem；url 为 null 时不设置播放源（VIP/无版权情形） */
    private fun Song.buildMediaItem(url: String?): MediaItem {
        val mdBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
        if (coverUrl.isNotBlank()) mdBuilder.setArtworkUri(android.net.Uri.parse(coverUrl))
        val builder = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(mdBuilder.build())
        if (url != null) builder.setUri(url)
        return builder.build()
    }
}

// 主线程 Executor 快捷引用（避免 import 冗长）
private fun ContextCompat_mainExecutor(context: Context): java.util.concurrent.Executor =
    androidx.core.content.ContextCompat.getMainExecutor(context)
