package com.liquidglass.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.desktop.music.LocalMusicScanner
import com.liquidglass.desktop.music.Lyrics
import com.liquidglass.desktop.music.NetEaseApi
import com.liquidglass.desktop.music.NetEaseAuth
import com.liquidglass.desktop.music.PlaybackController
import com.liquidglass.desktop.music.Playlist
import com.liquidglass.desktop.music.QrLoginState
import com.liquidglass.desktop.music.SearchHistoryStore
import com.liquidglass.desktop.music.SessionStore
import com.liquidglass.desktop.music.Song
import com.liquidglass.desktop.music.Source
import com.liquidglass.desktop.music.UserAccount
import com.liquidglass.desktop.theme.LiquidGlassTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import javax.imageio.ImageIO

/**
 * 音乐板块主屏幕。
 *
 * 整合 20+ 功能，覆盖在线/本地/账号/播放全链路：
 *
 * 发现 Tab：
 *  1. 热搜词  2. 推荐歌单  3. 排行榜  4. 每日推荐  5. 新歌速递
 *  6. 新碟上架  7. 私人FM  8. DJ电台  9. MV推荐  10. 在线搜索
 *
 * 网易云 Tab：
 *  11. 二维码登录  12. 手机号验证码登录  13. 我的歌单  14. 歌单详情
 *
 * 本地 Tab：
 *  15. 目录选择  16. 本地音乐扫描  17. 本地音乐播放
 *
 * 播放栏：
 *  18. 播放/暂停/上一首/下一首  19. 进度条拖动  20. 音量调节
 *  21. 播放队列管理  22. 播放模式（顺序/单曲循环/随机）
 *  23. 歌词显示（逐字+逐行+翻译）  24. 音质切换  25. 睡眠定时
 *  26. 搜索历史
 */
@Composable
fun MusicScreen() {
    val scope = rememberCoroutineScope()
    val player = remember { PlaybackController() }

    // ============ 全局播放状态 ============
    val queue = remember { mutableStateListOf<Song>() }
    var currentIndex by remember { mutableStateOf(-1) }
    var currentSong by remember { mutableStateOf<Song?>(null) }
    var currentLyrics by remember { mutableStateOf<Lyrics?>(null) }
    val playMode = remember { mutableStateOf(PlayMode.SEQUENCE) }
    var quality by remember { mutableStateOf("exhigh") }   // standard / exhigh / lossless / hires
    var sleepTimerMs by remember { mutableLongStateOf(0L) } // 0=关闭，>0=剩余毫秒

    // 播放栏面板开关
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // 当前 Tab：discover / netease / local
    var tab by remember { mutableStateOf("discover") }

    // 搜索态
    var searchKeyword by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    // 启动播放一首歌：加入队列并从该位置开始
    fun playSong(song: Song, queueList: List<Song> = emptyList()) {
        val finalQueue = if (queueList.isEmpty()) listOf(song) else queueList
        queue.clear()
        queue.addAll(finalQueue)
        currentIndex = finalQueue.indexOf(song).coerceAtLeast(0)
        currentSong = song
        currentLyrics = null

        // 决定播放源：本地直接 streamUrl；网易云需先取 URL
        scope.launch {
            val (src, isLocal) = resolvePlayableSource(song, quality)
            if (src.isBlank()) {
                // 取 URL 失败：可能是 VIP 歌曲，直接跳下一首
                return@launch
            }
            player.play(src, isLocal, song.durationMs)
            // 异步拉歌词（仅网易云）
            if (song.source == Source.NETEASE && song.id.isNotBlank()) {
                launch {
                    currentLyrics = NetEaseApi.lyrics(song.id)
                }
            }
        }
    }

    // 播放完成回调：根据播放模式自动衔接
    DisposableEffect(player) {
        player.onComplete = {
            scope.launch {
                when (playMode.value) {
                    PlayMode.SINGLE -> {
                        // 单曲循环：重播当前
                        currentSong?.let { s ->
                            val (src, isLocal) = resolvePlayableSource(s, quality)
                            if (src.isNotBlank()) {
                                player.play(src, isLocal, s.durationMs)
                            }
                        }
                    }
                    PlayMode.SEQUENCE -> {
                        val next = currentIndex + 1
                        if (next < queue.size) {
                            currentIndex = next
                            currentSong = queue[next]
                            val (src, isLocal) = resolvePlayableSource(currentSong!!, quality)
                            if (src.isNotBlank()) player.play(src, isLocal, currentSong!!.durationMs)
                            if (currentSong!!.source == Source.NETEASE) {
                                launch { currentLyrics = NetEaseApi.lyrics(currentSong!!.id) }
                            }
                        }
                    }
                    PlayMode.RANDOM -> {
                        if (queue.size > 1) {
                            var nextIdx: Int
                            do {
                                nextIdx = (0 until queue.size).random()
                            } while (nextIdx == currentIndex)
                            currentIndex = nextIdx
                            currentSong = queue[nextIdx]
                            val (src, isLocal) = resolvePlayableSource(currentSong!!, quality)
                            if (src.isNotBlank()) player.play(src, isLocal, currentSong!!.durationMs)
                            if (currentSong!!.source == Source.NETEASE) {
                                launch { currentLyrics = NetEaseApi.lyrics(currentSong!!.id) }
                            }
                        }
                    }
                }
            }
        }
        onDispose { player.onComplete = null }
    }

    // 睡眠定时倒计时
    LaunchedEffect(sleepTimerMs) {
        if (sleepTimerMs > 0L) {
            while (sleepTimerMs > 0L) {
                delay(1000L)
                sleepTimerMs = (sleepTimerMs - 1000L).coerceAtLeast(0L)
            }
            // 时间到，停止播放
            player.stop()
        }
    }

    // 上一首/下一首
    fun playPrev() {
        if (queue.isEmpty()) return
        val prev = if (currentIndex > 0) currentIndex - 1 else queue.size - 1
        currentIndex = prev
        currentSong = queue[prev]
        scope.launch {
            val (src, isLocal) = resolvePlayableSource(currentSong!!, quality)
            if (src.isNotBlank()) player.play(src, isLocal, currentSong!!.durationMs)
            if (currentSong!!.source == Source.NETEASE) {
                launch { currentLyrics = NetEaseApi.lyrics(currentSong!!.id) }
            }
        }
    }
    fun playNext() {
        if (queue.isEmpty()) return
        val next = if (currentIndex < queue.size - 1) currentIndex + 1 else 0
        currentIndex = next
        currentSong = queue[next]
        scope.launch {
            val (src, isLocal) = resolvePlayableSource(currentSong!!, quality)
            if (src.isNotBlank()) player.play(src, isLocal, currentSong!!.durationMs)
            if (currentSong!!.source == Source.NETEASE) {
                launch { currentLyrics = NetEaseApi.lyrics(currentSong!!.id) }
            }
        }
    }

    // 外层 Box：让 PlayerBar 的 align(BottomCenter) 和抽屉的 align 可用
    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().padding(bottom = 96.dp)) {
        // ===== 顶部：Tab + 搜索框 =====
        MusicTopBar(
            tab = tab,
            onTabChange = { tab = it },
            searchKeyword = searchKeyword,
            onSearchChange = { searchKeyword = it },
            onSearch = {
                val kw = searchKeyword.trim()
                if (kw.isBlank()) return@MusicTopBar
                searching = true
                scope.launch {
                    val r = NetEaseApi.search(kw, limit = 30)
                    searchResults = r.songs
                    searching = false
                    SearchHistoryStore.record(kw)
                }
            },
            searching = searching
        )

        // ===== 内容区 =====
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            when (tab) {
                "discover" -> DiscoverTab(
                    searchResults = searchResults,
                    hasSearched = searchKeyword.isNotBlank() || searching,
                    searching = searching,
                    onPlaySong = { playSong(it) },
                    onPlayAll = { list -> if (list.isNotEmpty()) playSong(list.first(), list) }
                )
                "netease" -> NetEaseTab(
                    onPlaySong = { playSong(it) },
                    onPlayAll = { list -> if (list.isNotEmpty()) playSong(list.first(), list) }
                )
                "local" -> LocalTab(
                    onPlaySong = { playSong(it) },
                    onPlayAll = { list -> if (list.isNotEmpty()) playSong(list.first(), list) }
                )
            }
        }
    }

    // ===== 底部播放栏（固定悬浮） =====
    PlayerBar(
        player = player,
        currentSong = currentSong,
        currentLyrics = currentLyrics,
        playMode = playMode.value,
        quality = quality,
        sleepTimerMs = sleepTimerMs,
        onPlayPause = {
            when (player.state) {
                PlaybackController.State.PLAYING -> player.pause()
                PlaybackController.State.PAUSED -> player.resume()
                PlaybackController.State.IDLE, PlaybackController.State.ERROR -> {
                    currentSong?.let { s ->
                        scope.launch {
                            val (src, isLocal) = resolvePlayableSource(s, quality)
                            if (src.isNotBlank()) player.play(src, isLocal, s.durationMs)
                        }
                    }
                }
            }
        },
        onPrev = ::playPrev,
        onNext = ::playNext,
        onSeek = { ms -> player.seekTo(ms) },
        onVolumeChange = { v -> player.changeVolume(v) },
        onToggleQueue = { showQueue = !showQueue; showLyrics = false; showSettings = false },
        onToggleLyrics = { showLyrics = !showLyrics; showQueue = false; showSettings = false },
        onToggleSettings = { showSettings = !showSettings; showQueue = false; showLyrics = false },
        onModeChange = { playMode.value = it },
        onQualityChange = { quality = it },
        onSleepTimerChange = { sleepTimerMs = it },
        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
    )

    // ===== 抽屉：播放队列 / 歌词 / 设置 =====
    if (showQueue) {
        QueuePanel(
            queue = queue,
            currentIndex = currentIndex,
            onSongClick = { idx ->
                currentIndex = idx
                currentSong = queue[idx]
                scope.launch {
                    val (src, isLocal) = resolvePlayableSource(currentSong!!, quality)
                    if (src.isNotBlank()) player.play(src, isLocal, currentSong!!.durationMs)
                }
            },
            onClose = { showQueue = false }
        )
    }
    if (showLyrics) {
        LyricsPanel(
            lyrics = currentLyrics,
            positionMs = player.positionMs,
            onClose = { showLyrics = false }
        )
    }
    if (showSettings) {
        SettingsPanel(
            playMode = playMode.value,
            quality = quality,
            sleepTimerMs = sleepTimerMs,
            onModeChange = { playMode.value = it },
            onQualityChange = { quality = it },
            onSleepTimerChange = { sleepTimerMs = it },
            onClose = { showSettings = false }
        )
    }
    } // Box 结束
}

// ==================== 顶部 Tab + 搜索框 ====================

@Composable
private fun MusicTopBar(
    tab: String,
    onTabChange: (String) -> Unit,
    searchKeyword: String,
    onSearchChange: (String) -> Unit,
    onSearch: () -> Unit,
    searching: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Tab 切换
        listOf("discover" to "发现", "netease" to "网易云", "local" to "本地").forEach { (id, label) ->
            val selected = tab == id
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) LiquidGlassTheme.accentPrimary.copy(alpha = 0.35f)
                        else LiquidGlassTheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .clickable { onTabChange(id) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = label,
                    color = if (selected) LiquidGlassTheme.onSurfaceBright else LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        // 搜索框
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(LiquidGlassTheme.surfaceVariant.copy(alpha = 0.7f))
                .border(1.dp, LiquidGlassTheme.glassBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🔍", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (searchKeyword.isEmpty()) {
                    Text(
                        text = "搜索歌曲、歌手…",
                        color = LiquidGlassTheme.onSurfaceMuted,
                        fontSize = 14.sp
                    )
                }
                BasicTextField(
                    value = searchKeyword,
                    onValueChange = onSearchChange,
                    textStyle = TextStyle(
                        color = LiquidGlassTheme.onSurfaceColor,
                        fontSize = 14.sp
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.width(8.dp))
            if (searching) {
                Text("...", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 12.sp)
            } else if (searchKeyword.isNotEmpty()) {
                Text(
                    "✕",
                    color = LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { onSearchChange("") }
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Button(onClick = onSearch, enabled = !searching) {
            Text("搜索")
        }
    }
}

// ==================== 发现 Tab ====================

@Composable
private fun DiscoverTab(
    searchResults: List<Song>,
    hasSearched: Boolean,
    searching: Boolean,
    onPlaySong: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit
) {
    val scope = rememberCoroutineScope()

    // 9 个发现模块的数据
    var hotWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var recommendPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var toplists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var dailyRecommend by remember { mutableStateOf<List<Song>>(emptyList()) }
    var newSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var newAlbums by remember { mutableStateOf<List<NetEaseApi.AlbumInfo>>(emptyList()) }
    var personalFm by remember { mutableStateOf<List<Song>>(emptyList()) }
    var djRadios by remember { mutableStateOf<List<NetEaseApi.DjProgram>>(emptyList()) }
    var mvs by remember { mutableStateOf<List<NetEaseApi.MvInfo>>(emptyList()) }

    // 加载发现页数据
    LaunchedEffect(Unit) {
        scope.launch { hotWords = NetEaseApi.hotSearch() }
        scope.launch { recommendPlaylists = NetEaseApi.recommendPlaylists() }
        scope.launch { toplists = NetEaseApi.toplist() }
        scope.launch { dailyRecommend = NetEaseApi.recommendSongs() }
        scope.launch { newSongs = NetEaseApi.newSongs(0) }
        scope.launch { newAlbums = NetEaseApi.newAlbums() }
        scope.launch { personalFm = NetEaseApi.personalFm() }
        scope.launch { djRadios = NetEaseApi.recommendDj() }
        scope.launch { mvs = NetEaseApi.recommendMv() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        // 搜索结果优先显示
        if (hasSearched && searchResults.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "搜索结果（${searchResults.size} 首）",
                    actionText = "播放全部",
                    onAction = { onPlayAll(searchResults) }
                )
            }
            items(searchResults) { song ->
                SongRow(song = song, onPlay = { onPlaySong(song) })
            }
        } else if (hasSearched && !searching) {
            item { EmptyState("没有找到相关歌曲") }
        } else if (searching) {
            item { EmptyState("搜索中…") }
        }

        // 热搜词
        if (hotWords.isNotEmpty()) {
            item {
                SectionHeader(title = "🔥 热搜榜")
                FlowChips(
                    items = hotWords.take(15),
                    onClick = { /* 搜索热词需要外部触发；这里点击无操作，避免循环依赖 */ }
                )
            }
        }

        // 每日推荐
        if (dailyRecommend.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "📅 每日推荐",
                    actionText = "播放全部",
                    onAction = { onPlayAll(dailyRecommend) }
                )
            }
            items(dailyRecommend.take(10)) { song ->
                SongRow(song = song, onPlay = { onPlaySong(song) })
            }
        }

        // 推荐歌单
        if (recommendPlaylists.isNotEmpty()) {
            item { SectionHeader(title = "🎵 推荐歌单") }
            items(recommendPlaylists) { pl ->
                PlaylistRow(playlist = pl)
            }
        }

        // 排行榜
        if (toplists.isNotEmpty()) {
            item { SectionHeader(title = "🏆 排行榜") }
            items(toplists.take(10)) { pl ->
                PlaylistRow(playlist = pl)
            }
        }

        // 新歌速递
        if (newSongs.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "🆕 新歌速递",
                    actionText = "播放全部",
                    onAction = { onPlayAll(newSongs) }
                )
            }
            items(newSongs.take(10)) { song ->
                SongRow(song = song, onPlay = { onPlaySong(song) })
            }
        }

        // 私人 FM
        if (personalFm.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "📡 私人 FM",
                    actionText = "播放全部",
                    onAction = { onPlayAll(personalFm) }
                )
            }
            items(personalFm.take(10)) { song ->
                SongRow(song = song, onPlay = { onPlaySong(song) })
            }
        }

        // 新碟上架
        if (newAlbums.isNotEmpty()) {
            item { SectionHeader(title = "💿 新碟上架") }
            items(newAlbums) { album ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(LiquidGlassTheme.glassBaseColor.copy(alpha = 0.5f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("💿", fontSize = 24.sp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            album.name,
                            color = LiquidGlassTheme.onSurfaceColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "${album.artist} · ${album.publishDate}",
                            color = LiquidGlassTheme.onSurfaceMuted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // DJ 电台
        if (djRadios.isNotEmpty()) {
            item { SectionHeader(title = "🎙️ DJ 电台") }
            items(djRadios) { dj ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(LiquidGlassTheme.glassBaseColor.copy(alpha = 0.5f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🎙️", fontSize = 24.sp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            dj.name,
                            color = LiquidGlassTheme.onSurfaceColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "${dj.djName} · ${dj.listenerCount} 听众",
                            color = LiquidGlassTheme.onSurfaceMuted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // MV 推荐
        if (mvs.isNotEmpty()) {
            item { SectionHeader(title = "📺 MV 推荐") }
            items(mvs) { mv ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(LiquidGlassTheme.glassBaseColor.copy(alpha = 0.5f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📺", fontSize = 24.sp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            mv.name,
                            color = LiquidGlassTheme.onSurfaceColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "${mv.artist} · ${mv.playCount} 次播放",
                            color = LiquidGlassTheme.onSurfaceMuted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

// ==================== 网易云 Tab ====================

@Composable
private fun NetEaseTab(
    onPlaySong: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var userAccount by remember { mutableStateOf<UserAccount?>(null) }
    var showLoginDialog by remember { mutableStateOf(false) }

    var myPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var playlistTracks by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loadingTracks by remember { mutableStateOf(false) }

    // 初始检查登录态
    LaunchedEffect(Unit) {
        if (SessionStore.isLoggedIn()) {
            userAccount = UserAccount(
                userId = SessionStore.getUserId(),
                nickname = SessionStore.getNickname(),
                avatarUrl = SessionStore.getAvatarUrl(),
                vipType = SessionStore.getVipType()
            )
            scope.launch { myPlaylists = NetEaseApi.userPlaylists() }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        // 用户信息卡
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(LiquidGlassTheme.glassBaseColor.copy(alpha = 0.6f))
                    .border(1.dp, LiquidGlassTheme.glassBorder, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (userAccount != null) {
                    Text("👤", fontSize = 36.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                userAccount!!.nickname,
                                color = LiquidGlassTheme.onSurfaceColor,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (userAccount!!.isVip) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "VIP",
                                    color = LiquidGlassTheme.onSurfaceBright,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(LiquidGlassTheme.orange)
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            "ID: ${userAccount!!.userId}",
                            color = LiquidGlassTheme.onSurfaceMuted,
                            fontSize = 12.sp
                        )
                    }
                    OutlinedButton(onClick = {
                        NetEaseAuth.logout()
                        userAccount = null
                        myPlaylists = emptyList()
                        selectedPlaylist = null
                        playlistTracks = emptyList()
                    }) { Text("退出") }
                } else {
                    Text("🔐", fontSize = 36.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "未登录",
                            color = LiquidGlassTheme.onSurfaceColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "登录后解锁我的歌单、每日推荐等",
                            color = LiquidGlassTheme.onSurfaceMuted,
                            fontSize = 12.sp
                        )
                    }
                    Button(onClick = { showLoginDialog = true }) { Text("登录") }
                }
            }
        }

        // 我的歌单
        if (userAccount != null) {
            item {
                SectionHeader(
                    title = "📁 我的歌单（${myPlaylists.size}）",
                    actionText = if (selectedPlaylist != null) "返回歌单列表" else null,
                    onAction = if (selectedPlaylist != null) {
                        { selectedPlaylist = null; playlistTracks = emptyList() }
                    } else null
                )
            }

            if (selectedPlaylist == null) {
                items(myPlaylists) { pl ->
                    PlaylistRow(
                        playlist = pl,
                        onClick = {
                            selectedPlaylist = pl
                            loadingTracks = true
                            scope.launch {
                                playlistTracks = NetEaseApi.playlistTracks(pl.id)
                                loadingTracks = false
                            }
                        }
                    )
                }
            } else {
                if (loadingTracks) {
                    item { EmptyState("加载曲目中…") }
                } else if (playlistTracks.isEmpty()) {
                    item { EmptyState("歌单为空") }
                } else {
                    item {
                        SectionHeader(
                            title = "🎵 ${selectedPlaylist!!.name}（${playlistTracks.size}）",
                            actionText = "播放全部",
                            onAction = { onPlayAll(playlistTracks) }
                        )
                    }
                    items(playlistTracks) { song ->
                        SongRow(song = song, onPlay = { onPlaySong(song) })
                    }
                }
            }
        }
    }

    if (showLoginDialog) {
        LoginDialog(
            onDismiss = { showLoginDialog = false },
            onLoginSuccess = {
                showLoginDialog = false
                scope.launch {
                    userAccount = NetEaseAuth.fetchAccount()
                    if (userAccount != null) {
                        myPlaylists = NetEaseApi.userPlaylists()
                    }
                }
            }
        )
    }
}

// ==================== 本地 Tab ====================

@Composable
private fun LocalTab(
    onPlaySong: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var localSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableStateOf<LocalMusicScanner.ScanProgress?>(null) }
    var scanDir by remember { mutableStateOf(System.getProperty("user.home")) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(LiquidGlassTheme.glassBaseColor.copy(alpha = 0.6f))
                    .border(1.dp, LiquidGlassTheme.glassBorder, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "扫描目录",
                        color = LiquidGlassTheme.onSurfaceColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        scanDir,
                        color = LiquidGlassTheme.onSurfaceMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val chooser = javax.swing.JFileChooser(scanDir)
                            chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                            chooser.dialogTitle = "选择音乐目录"
                            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                                scanDir = chooser.selectedFile.absolutePath
                            }
                        }
                    }
                }) { Text("选择") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (scanning) return@Button
                        scanning = true
                        scanProgress = null
                        scope.launch {
                            val list = LocalMusicScanner.scanList(scanDir) { p ->
                                scanProgress = p
                            }
                            localSongs = list
                            scanning = false
                        }
                    },
                    enabled = !scanning
                ) { Text(if (scanning) "扫描中…" else "扫描") }
            }
        }

        scanProgress?.let { p ->
            item {
                Text(
                    "已扫描 ${p.scanned} 个文件，找到 ${p.found} 首音乐",
                    color = LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        if (localSongs.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "🎵 本地音乐（${localSongs.size}）",
                    actionText = "播放全部",
                    onAction = { onPlayAll(localSongs) }
                )
            }
            items(localSongs) { song ->
                SongRow(song = song, onPlay = { onPlaySong(song) })
            }
        } else if (!scanning) {
            item { EmptyState("点击「扫描」按钮添加本地音乐\n支持 MP3 格式") }
        }
    }
}

// ==================== 播放栏 ====================

@Composable
private fun PlayerBar(
    player: PlaybackController,
    currentSong: Song?,
    currentLyrics: Lyrics?,
    playMode: PlayMode,
    quality: String,
    sleepTimerMs: Long,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleQueue: () -> Unit,
    onToggleLyrics: () -> Unit,
    onToggleSettings: () -> Unit,
    onModeChange: (PlayMode) -> Unit,
    onQualityChange: (String) -> Unit,
    onSleepTimerChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var seekingMs by remember { mutableStateOf<Long?>(null) }

    Row(
        modifier = modifier
            .height(96.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(LiquidGlassTheme.glassBaseColor.copy(alpha = LiquidGlassTheme.glassAlphaBright))
            .border(1.dp, LiquidGlassTheme.glassBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面 + 标题
        CoverArt(url = currentSong?.coverUrl, size = 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.width(220.dp)) {
            Text(
                currentSong?.title ?: "未播放",
                color = LiquidGlassTheme.onSurfaceColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                currentSong?.artist?.ifBlank { "未知艺术家" } ?: "—",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (currentSong?.isVipOnly == true) {
                Text(
                    "VIP 专享",
                    color = LiquidGlassTheme.announcementHigh,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        // 中间：控制按钮 + 进度条
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 播放模式
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onToggleSettings() }
                        .padding(4.dp)
                ) {
                    Text(
                        when (playMode) {
                            PlayMode.SEQUENCE -> "↻"
                            PlayMode.SINGLE -> "⟲"
                            PlayMode.RANDOM -> "🔀"
                        },
                        color = LiquidGlassTheme.onSurfaceMuted,
                        fontSize = 16.sp
                    )
                }
                Spacer(Modifier.width(12.dp))

                // 上一首
                Text(
                    "⏮",
                    color = LiquidGlassTheme.onSurfaceColor,
                    fontSize = 20.sp,
                    modifier = Modifier.clickable { onPrev() }.padding(4.dp)
                )
                Spacer(Modifier.width(12.dp))

                // 播放/暂停
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(LiquidGlassTheme.accentPrimary)
                        .clickable { onPlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when (player.state) {
                            PlaybackController.State.PLAYING -> "⏸"
                            PlaybackController.State.PAUSED -> "▶"
                            else -> "▶"
                        },
                        color = LiquidGlassTheme.onAccent,
                        fontSize = 18.sp
                    )
                }
                Spacer(Modifier.width(12.dp))

                // 下一首
                Text(
                    "⏭",
                    color = LiquidGlassTheme.onSurfaceColor,
                    fontSize = 20.sp,
                    modifier = Modifier.clickable { onNext() }.padding(4.dp)
                )
                Spacer(Modifier.width(12.dp))

                // 队列
                Text(
                    "☰",
                    color = LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 16.sp,
                    modifier = Modifier.clickable { onToggleQueue() }.padding(4.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            // 进度条
            val position = seekingMs ?: player.positionMs
            val duration = player.durationMs.coerceAtLeast(0L)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatMs(position),
                    color = LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(LiquidGlassTheme.surfaceVariant)
                        .pointerInput(duration) {
                            // 拖动进度
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: continue
                                    val ratio = (change.position.x / size.width).coerceIn(0f, 1f)
                                    if (event.type == PointerEventType.Press ||
                                        (event.type == PointerEventType.Move && change.pressed)) {
                                        val targetMs = (ratio * duration).toLong()
                                        seekingMs = targetMs
                                    }
                                    if (event.type == PointerEventType.Release && seekingMs != null) {
                                        onSeek(seekingMs!!)
                                        seekingMs = null
                                    }
                                }
                            }
                        }
                ) {
                    val progress = if (duration > 0) (position.toFloat() / duration) else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .clip(RoundedCornerShape(2.dp))
                            .background(LiquidGlassTheme.accentSecondary)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    formatMs(duration),
                    color = LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        // 右侧：歌词/设置/音量
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 音量
            var volSlider by remember { mutableStateOf(player.volume) }
            Text("🔊", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 14.sp)
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(LiquidGlassTheme.surfaceVariant)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: continue
                                if (event.type == PointerEventType.Press ||
                                    (event.type == PointerEventType.Move && change.pressed)) {
                                    val ratio = (change.position.x / size.width).coerceIn(0f, 1f)
                                    volSlider = ratio
                                    onVolumeChange(ratio)
                                }
                            }
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(volSlider)
                        .clip(RoundedCornerShape(2.dp))
                        .background(LiquidGlassTheme.accentSecondary)
                )
            }

            Spacer(Modifier.width(12.dp))

            // 歌词
            Text(
                "词",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onToggleLyrics() }.padding(4.dp)
            )
            Spacer(Modifier.width(8.dp))

            // 设置（音质/模式/定时）
            Text(
                "⚙",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 16.sp,
                modifier = Modifier.clickable { onToggleSettings() }.padding(4.dp)
            )

            // 睡眠定时指示
            if (sleepTimerMs > 0L) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "⏰ ${formatMs(sleepTimerMs)}",
                    color = LiquidGlassTheme.announcementMedium,
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ==================== 队列抽屉 ====================

@Composable
private fun QueuePanel(
    queue: List<Song>,
    currentIndex: Int,
    onSongClick: (Int) -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onClose)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(380.dp)
                .background(LiquidGlassTheme.glassBaseColor.copy(alpha = 0.95f))
                .border(1.dp, LiquidGlassTheme.glassBorder)
                .padding(16.dp)
                .clickable(enabled = false) { /* 拦截点击 */ }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "播放队列（${queue.size}）",
                    color = LiquidGlassTheme.onSurfaceColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "✕",
                    color = LiquidGlassTheme.onSurfaceMuted,
                    modifier = Modifier.clickable { onClose() }
                )
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(queue.size) { idx ->
                    val song = queue[idx]
                    val playing = idx == currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (playing) LiquidGlassTheme.accentPrimary.copy(alpha = 0.3f)
                                else Color.Transparent
                            )
                            .clickable { onSongClick(idx) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${idx + 1}",
                            color = if (playing) LiquidGlassTheme.accentSecondary
                            else LiquidGlassTheme.onSurfaceMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.width(28.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                song.title,
                                color = if (playing) LiquidGlassTheme.onSurfaceBright
                                else LiquidGlassTheme.onSurfaceColor,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                song.artist.ifBlank { "未知" },
                                color = LiquidGlassTheme.onSurfaceMuted,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (playing) {
                            Text("▶", color = LiquidGlassTheme.accentSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// ==================== 歌词抽屉 ====================

@Composable
private fun LyricsPanel(
    lyrics: Lyrics?,
    positionMs: Long,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onClose)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.6f)
                .height(500.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(LiquidGlassTheme.glassBaseColor.copy(alpha = 0.95f))
                .border(1.dp, LiquidGlassTheme.glassBorder, RoundedCornerShape(16.dp))
                .padding(24.dp)
                .clickable(enabled = false) { }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "歌词",
                    color = LiquidGlassTheme.onSurfaceColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "✕",
                    color = LiquidGlassTheme.onSurfaceMuted,
                    modifier = Modifier.clickable { onClose() }
                )
            }
            Spacer(Modifier.height(16.dp))

            if (lyrics == null || (lyrics.lrcLines.isEmpty() && lyrics.yrcLines.isEmpty())) {
                EmptyState("暂无歌词")
            } else {
                // 把 yrc / lrc 统一成显示项，避免丑陋的类型转换
                val displayLines = remember(lyrics) {
                    if (lyrics.hasYrc) {
                        lyrics.yrcLines.map {
                            LyricDisplayItem(it.startMs, it.chars.joinToString("") { c -> c.content }, it.translation)
                        }
                    } else {
                        lyrics.lrcLines.map {
                            LyricDisplayItem(it.timeMs, it.content, it.translation)
                        }
                    }
                }
                val currentIdx by remember(positionMs, displayLines) {
                    derivedStateOf {
                        var lo = 0
                        var hi = displayLines.lastIndex
                        var ans = 0
                        while (lo <= hi) {
                            val mid = (lo + hi) ushr 1
                            if (displayLines[mid].timeMs <= positionMs) {
                                ans = mid
                                lo = mid + 1
                            } else {
                                hi = mid - 1
                            }
                        }
                        ans
                    }
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 200.dp)
                ) {
                    items(displayLines.size) { idx ->
                        val isCurrent = idx == currentIdx
                        val line = displayLines[idx]
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                line.content,
                                color = if (isCurrent) LiquidGlassTheme.accentSecondary
                                else LiquidGlassTheme.onSurfaceMuted,
                                fontSize = if (isCurrent) 18.sp else 14.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                            )
                            if (line.translation.isNotBlank()) {
                                Text(
                                    line.translation,
                                    color = if (isCurrent) LiquidGlassTheme.onSurfaceColor.copy(alpha = 0.8f)
                                    else LiquidGlassTheme.onSurfaceMuted.copy(alpha = 0.6f),
                                    fontSize = if (isCurrent) 13.sp else 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 歌词显示统一模型 */
private data class LyricDisplayItem(
    val timeMs: Long,
    val content: String,
    val translation: String
)

// ==================== 设置抽屉 ====================

@Composable
private fun SettingsPanel(
    playMode: PlayMode,
    quality: String,
    sleepTimerMs: Long,
    onModeChange: (PlayMode) -> Unit,
    onQualityChange: (String) -> Unit,
    onSleepTimerChange: (Long) -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onClose)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.5f)
                .clip(RoundedCornerShape(16.dp))
                .background(LiquidGlassTheme.glassBaseColor.copy(alpha = 0.95f))
                .border(1.dp, LiquidGlassTheme.glassBorder, RoundedCornerShape(16.dp))
                .padding(24.dp)
                .clickable(enabled = false) { }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "播放设置",
                    color = LiquidGlassTheme.onSurfaceColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text("✕", color = LiquidGlassTheme.onSurfaceMuted, modifier = Modifier.clickable { onClose() })
            }
            Spacer(Modifier.height(16.dp))

            // 播放模式
            Text("播放模式", color = LiquidGlassTheme.onSurfaceColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(PlayMode.SEQUENCE to "顺序播放", PlayMode.SINGLE to "单曲循环", PlayMode.RANDOM to "随机播放").forEach { (mode, label) ->
                    val selected = playMode == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) LiquidGlassTheme.accentPrimary.copy(alpha = 0.4f)
                                else LiquidGlassTheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                            .clickable { onModeChange(mode) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(label, color = if (selected) LiquidGlassTheme.onSurfaceBright else LiquidGlassTheme.onSurfaceMuted, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // 音质
            Text("音质", color = LiquidGlassTheme.onSurfaceColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("standard" to "标准", "exhigh" to "极高", "lossless" to "无损", "hires" to "Hi-Res").forEach { (q, label) ->
                    val selected = quality == q
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) LiquidGlassTheme.accentPrimary.copy(alpha = 0.4f)
                                else LiquidGlassTheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                            .clickable { onQualityChange(q) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(label, color = if (selected) LiquidGlassTheme.onSurfaceBright else LiquidGlassTheme.onSurfaceMuted, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // 睡眠定时
            Text("睡眠定时", color = LiquidGlassTheme.onSurfaceColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0L to "关闭", 15 * 60_000L to "15分钟", 30 * 60_000L to "30分钟", 60 * 60_000L to "60分钟").forEach { (ms, label) ->
                    val selected = sleepTimerMs == ms || (ms == 0L && sleepTimerMs == 0L)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) LiquidGlassTheme.accentPrimary.copy(alpha = 0.4f)
                                else LiquidGlassTheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                            .clickable { onSleepTimerChange(ms) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(label, color = if (selected) LiquidGlassTheme.onSurfaceBright else LiquidGlassTheme.onSurfaceMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ==================== 登录对话框 ====================

@Composable
private fun LoginDialog(
    onDismiss: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    var mode by remember { mutableStateOf("qr") }  // "qr" or "phone"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("登录网易云音乐") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("qr" to "扫码登录", "phone" to "手机号登录").forEach { (id, label) ->
                        val selected = mode == id
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (selected) LiquidGlassTheme.accentPrimary.copy(alpha = 0.4f)
                                    else LiquidGlassTheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                                .clickable { mode = id }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(label, color = if (selected) LiquidGlassTheme.onSurfaceBright else LiquidGlassTheme.onSurfaceMuted, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (mode == "qr") {
                    QrLoginView(onLoginSuccess = onLoginSuccess)
                } else {
                    PhoneLoginView(onLoginSuccess = onLoginSuccess)
                }
            }
        },
        confirmButton = {},
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun QrLoginView(onLoginSuccess: () -> Unit) {
    val scope = rememberCoroutineScope()
    var qrUrl by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<QrLoginState?>(null) }
    var statusText by remember { mutableStateOf("正在生成二维码…") }

    LaunchedEffect(Unit) {
        val unikey = NetEaseAuth.createQrKey()
        if (unikey == null) {
            statusText = "生成二维码失败，请重试"
            return@LaunchedEffect
        }
        qrUrl = "https://music.163.com/login?codekey=$unikey"
        statusText = "请用网易云音乐 App 扫描二维码"
        // 轮询扫码状态
        while (true) {
            delay(2000)
            val r = NetEaseAuth.pollQrStatus(unikey)
            status = r.state
            statusText = when (r.state) {
                QrLoginState.WAITING -> "等待扫码…"
                QrLoginState.SCANNED -> "已扫码，请在手机上确认"
                QrLoginState.CONFIRMED -> {
                    onLoginSuccess()
                    "登录成功"
                }
                QrLoginState.EXPIRED -> "二维码已过期"
                QrLoginState.ERROR -> "登录异常"
            }
            if (r.state == QrLoginState.CONFIRMED || r.state == QrLoginState.EXPIRED || r.state == QrLoginState.ERROR) {
                break
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (qrUrl.isNotBlank() && status != QrLoginState.EXPIRED && status != QrLoginState.ERROR) {
            QrCodeImage(content = qrUrl)
        } else {
            Box(
                modifier = Modifier.size(200.dp).background(LiquidGlassTheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text("二维码加载中", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(statusText, color = LiquidGlassTheme.onSurfaceColor, fontSize = 13.sp)
    }
}

@Composable
private fun PhoneLoginView(onLoginSuccess: () -> Unit) {
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var logging by remember { mutableStateOf(false) }

    Column {
        BasicTextField(
            value = phone,
            onValueChange = { phone = it.filter { c -> c.isDigit() }.take(11) },
            textStyle = TextStyle(color = LiquidGlassTheme.onSurfaceColor, fontSize = 14.sp),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(LiquidGlassTheme.surfaceVariant)
                .padding(12.dp)
        )
        if (phone.isNotEmpty() && phone.length != 11) {
            Text("手机号需 11 位", color = LiquidGlassTheme.announcementHigh, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = code,
                    onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                    textStyle = TextStyle(color = LiquidGlassTheme.onSurfaceColor, fontSize = 14.sp),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(LiquidGlassTheme.surfaceVariant)
                        .padding(12.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    if (sending || phone.length != 11) return@OutlinedButton
                    sending = true
                    status = "发送中…"
                    scope.launch {
                        val r = NetEaseAuth.sendSmsCode(phone)
                        status = r.message
                        sending = false
                    }
                },
                enabled = !sending && phone.length == 11
            ) { Text(if (sending) "..." else "发送") }
        }
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                if (logging || phone.length != 11 || code.length < 4) return@Button
                logging = true
                status = "登录中…"
                scope.launch {
                    val r = NetEaseAuth.loginWithPhone(phone, code)
                    status = r.message
                    logging = false
                    if (r.state == com.liquidglass.desktop.music.PhoneLoginState.SUCCESS) {
                        onLoginSuccess()
                    }
                }
            },
            enabled = !logging && phone.length == 11 && code.length >= 4,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (logging) "登录中…" else "登录") }

        if (status.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(status, color = LiquidGlassTheme.onSurfaceMuted, fontSize = 12.sp)
        }
    }
}

/** 异步加载二维码图片（用在线 QR 生成服务，避免引入 zxing 依赖） */
@Composable
private fun QrCodeImage(content: String) {
    var bitmap by remember(content) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(content) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                val url = "https://api.qrserver.com/v1/create-qr-code/?size=240x240&data=" +
                    java.net.URLEncoder.encode(content, "UTF-8")
                val img = ImageIO.read(URL(url))
                img?.toComposeImageBitmap()
            } catch (_: Exception) { null }
        }
    }
    Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "登录二维码",
                modifier = Modifier.size(200.dp),
                contentScale = ContentScale.Fit
            )
        } ?: Text("加载中…", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 12.sp)
    }
}

// ==================== 通用组件 ====================

@Composable
private fun SectionHeader(
    title: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = LiquidGlassTheme.onSurfaceColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (actionText != null && onAction != null) {
            Text(
                actionText,
                color = LiquidGlassTheme.accentSecondary,
                fontSize = 12.sp,
                modifier = Modifier.clickable { onAction() }
            )
        }
    }
}

@Composable
private fun SongRow(song: Song, onPlay: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (hovered) LiquidGlassTheme.surfaceVariant.copy(alpha = 0.7f)
                else LiquidGlassTheme.glassBaseColor.copy(alpha = 0.4f)
            )
            .clickable { onPlay() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        when (e.type) {
                            PointerEventType.Enter -> hovered = true
                            PointerEventType.Exit -> hovered = false
                            else -> {}
                        }
                    }
                }
            }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(url = song.coverUrl, size = 40.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    song.title,
                    color = LiquidGlassTheme.onSurfaceColor,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (song.isVipOnly) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "VIP",
                        color = LiquidGlassTheme.announcementHigh,
                        fontSize = 9.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(LiquidGlassTheme.announcementHigh.copy(alpha = 0.2f))
                            .padding(horizontal = 3.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                "${song.artist.ifBlank { "未知" }} · ${song.album.ifBlank { "—" }}",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatMs(song.durationMs),
            color = LiquidGlassTheme.onSurfaceMuted,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: (() -> Unit)? = null) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (hovered) LiquidGlassTheme.surfaceVariant.copy(alpha = 0.7f)
                else LiquidGlassTheme.glassBaseColor.copy(alpha = 0.4f)
            )
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        when (e.type) {
                            PointerEventType.Enter -> hovered = true
                            PointerEventType.Exit -> hovered = false
                            else -> {}
                        }
                    }
                }
            }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(url = playlist.coverUrl, size = 48.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                playlist.name,
                color = LiquidGlassTheme.onSurfaceColor,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium
            )
            Text(
                "${playlist.trackCount} 首 · ${playlist.creator.ifBlank { "—" }}",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FlowChips(items: List<String>, onClick: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.take(8).forEach { word ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(LiquidGlassTheme.surfaceVariant.copy(alpha = 0.6f))
                    .clickable { onClick(word) }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(word, color = LiquidGlassTheme.onSurfaceColor, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = LiquidGlassTheme.onSurfaceMuted, fontSize = 13.sp)
    }
}

/** 异步加载封面图，加载失败显示音乐占位符 */
@Composable
private fun CoverArt(url: String?, size: androidx.compose.ui.unit.Dp) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = null
        if (!url.isNullOrBlank()) {
            bitmap = withContext(Dispatchers.IO) {
                try {
                    val img = ImageIO.read(URL(url))
                    img?.toComposeImageBitmap()
                } catch (_: Exception) { null }
            }
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(LiquidGlassTheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } ?: Text("🎵", color = LiquidGlassTheme.onSurfaceMuted, fontSize = (size.value / 2).sp)
    }
}

// ==================== 工具函数 ====================

/** 播放模式 */
enum class PlayMode { SEQUENCE, SINGLE, RANDOM }

/** 解析歌曲可播放源：本地直接用 streamUrl；网易云需先调 songUrl 接口 */
private suspend fun resolvePlayableSource(song: Song, quality: String): Pair<String, Boolean> {
    return when (song.source) {
        Source.LOCAL -> song.streamUrl to true
        Source.NETEASE -> {
            if (song.streamUrl.isNotBlank()) {
                song.streamUrl to false
            } else {
                val url = NetEaseApi.songUrl(song.id, quality)
                url to false
            }
        }
    }
}

/** 毫秒 → mm:ss 格式 */
private fun formatMs(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}
