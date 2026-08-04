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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
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
 * 音乐板块主屏幕（v2 - 彻底重做 UI）。
 *
 * 修复要点：
 * 1. 点击歌曲时把整列表加入队列并从该歌开始播放（修复"没有加入队列"）
 * 2. VIP/无 URL 歌曲自动跳下一首（修复"放不了歌"）
 * 3. 搜索框带搜索历史下拉（修复"搜索歌一切都没有"）
 * 4. 热搜词可点击触发搜索
 * 5. 推荐歌单用横向卡片网格（修复"排版太恶心"）
 * 6. 底部播放栏重新设计：封面+信息 | 控制 | 进度 | 音量+模式+队列+歌词
 * 7. 队列抽屉：当前播放高亮，点击切换，可移除
 * 8. 歌词抽屉：逐行高亮+翻译，自动滚动
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
    var quality by remember { mutableStateOf("exhigh") }
    var sleepTimerMs by remember { mutableLongStateOf(0L) }

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
    var hasSearched by remember { mutableStateOf(false) }
    var showSearchHistory by remember { mutableStateOf(false) }
    val searchHistory = remember { mutableStateListOf<String>() }

    // 初始化搜索历史
    LaunchedEffect(Unit) {
        searchHistory.clear()
        searchHistory.addAll(SearchHistoryStore.load())
    }

    // ============ 上一首/下一首（先定义，供 playSong 自动跳过使用）============
    fun playPrev() {
        if (queue.isEmpty()) return
        val prev = if (currentIndex > 0) currentIndex - 1 else queue.size - 1
        currentIndex = prev
        currentSong = queue[prev]
        currentLyrics = null
        scope.launch {
            val (src, isLocal) = resolvePlayableSource(currentSong!!, quality)
            if (src.isNotBlank()) {
                player.play(src, isLocal, currentSong!!.durationMs)
                if (currentSong!!.source == Source.NETEASE) {
                    launch { currentLyrics = NetEaseApi.lyrics(currentSong!!.id) }
                }
            }
        }
    }

    fun playNext() {
        if (queue.isEmpty()) return
        val next = if (currentIndex < queue.size - 1) currentIndex + 1 else 0
        currentIndex = next
        currentSong = queue[next]
        currentLyrics = null
        scope.launch {
            val (src, isLocal) = resolvePlayableSource(currentSong!!, quality)
            if (src.isNotBlank()) {
                player.play(src, isLocal, currentSong!!.durationMs)
                if (currentSong!!.source == Source.NETEASE) {
                    launch { currentLyrics = NetEaseApi.lyrics(currentSong!!.id) }
                }
            }
        }
    }

    // ============ 核心：播放一首歌（带队列上下文）============
    // 点击歌曲时，把整列表加入队列并从该歌开始播放
    fun playSong(song: Song, queueList: List<Song> = emptyList()) {
        val finalQueue = if (queueList.isEmpty()) listOf(song) else queueList
        queue.clear()
        queue.addAll(finalQueue)
        currentIndex = finalQueue.indexOf(song).coerceAtLeast(0)
        currentSong = song
        currentLyrics = null

        scope.launch {
            val (src, isLocal) = resolvePlayableSource(song, quality)
            if (src.isBlank()) {
                // URL 为空（VIP / 下架 / 网络异常）：自动跳下一首
                if (queue.size > 1) {
                    delay(300)
                    playNext()
                }
                return@launch
            }
            player.play(src, isLocal, song.durationMs)
            // 异步拉歌词（仅网易云）
            if (song.source == Source.NETEASE && song.id.isNotBlank()) {
                launch { currentLyrics = NetEaseApi.lyrics(song.id) }
            }
        }
    }

    // 加入队列但不播放
    fun addToQueue(song: Song) {
        queue.add(song)
    }

    // 从队列移除
    fun removeFromQueue(index: Int) {
        if (index < 0 || index >= queue.size) return
        queue.removeAt(index)
        if (index < currentIndex) {
            currentIndex--
        } else if (index == currentIndex) {
            // 移除的是当前播放：停止播放
            player.stop()
            currentSong = null
            currentLyrics = null
            currentIndex = -1
        }
    }

    // ============ 自动衔接：播放完成回调 ============
    DisposableEffect(player) {
        player.onComplete = {
            scope.launch {
                when (playMode.value) {
                    PlayMode.SINGLE -> {
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
                            if (src.isNotBlank()) {
                                player.play(src, isLocal, currentSong!!.durationMs)
                                if (currentSong!!.source == Source.NETEASE) {
                                    launch { currentLyrics = NetEaseApi.lyrics(currentSong!!.id) }
                                }
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
                            if (src.isNotBlank()) {
                                player.play(src, isLocal, currentSong!!.durationMs)
                                if (currentSong!!.source == Source.NETEASE) {
                                    launch { currentLyrics = NetEaseApi.lyrics(currentSong!!.id) }
                                }
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
            player.stop()
        }
    }

    // 搜索执行
    fun doSearch(kw: String) {
        val keyword = kw.trim()
        if (keyword.isBlank()) return
        searchKeyword = keyword
        hasSearched = true
        searching = true
        showSearchHistory = false
        scope.launch {
            val r = NetEaseApi.search(keyword, limit = 30)
            searchResults = r.songs
            searching = false
            SearchHistoryStore.record(keyword)
            searchHistory.clear()
            searchHistory.addAll(SearchHistoryStore.load())
        }
    }

    // ============ 布局 ============
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(bottom = 100.dp)) {
            // ===== 顶部：Tab + 搜索框 =====
            MusicTopBar(
                tab = tab,
                onTabChange = { tab = it },
                searchKeyword = searchKeyword,
                onSearchChange = {
                    searchKeyword = it
                    showSearchHistory = it.isEmpty()
                },
                onSearch = { doSearch(searchKeyword) },
                searching = searching,
                showHistory = showSearchHistory,
                searchHistory = searchHistory,
                onHistoryClick = { kw ->
                    searchKeyword = kw
                    doSearch(kw)
                },
                onClearHistory = {
                    SearchHistoryStore.clear()
                    searchHistory.clear()
                }
            )

            // ===== 内容区 =====
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                when (tab) {
                    "discover" -> DiscoverTab(
                        searchResults = searchResults,
                        hasSearched = hasSearched,
                        searching = searching,
                        onPlaySong = { song, list -> playSong(song, list) },
                        onAddToQueue = { addToQueue(it) },
                        onSearchHot = { kw ->
                            searchKeyword = kw
                            doSearch(kw)
                        }
                    )
                    "netease" -> NetEaseTab(
                        onPlaySong = { song, list -> playSong(song, list) },
                        onAddToQueue = { addToQueue(it) }
                    )
                    "local" -> LocalTab(
                        onPlaySong = { song, list -> playSong(song, list) },
                        onAddToQueue = { addToQueue(it) }
                    )
                }
            }
        }

        // ===== 底部播放栏（固定悬浮）=====
        PlayerBar(
            player = player,
            currentSong = currentSong,
            playMode = playMode.value,
            sleepTimerMs = sleepTimerMs,
            queueSize = queue.size,
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
                    currentLyrics = null
                    scope.launch {
                        val (src, isLocal) = resolvePlayableSource(currentSong!!, quality)
                        if (src.isNotBlank()) {
                            player.play(src, isLocal, currentSong!!.durationMs)
                            if (currentSong!!.source == Source.NETEASE) {
                                launch { currentLyrics = NetEaseApi.lyrics(currentSong!!.id) }
                            }
                        }
                    }
                },
                onRemove = ::removeFromQueue,
                onClose = { showQueue = false }
            )
        }
        if (showLyrics) {
            LyricsPanel(
                lyrics = currentLyrics,
                positionMs = player.positionMs,
                song = currentSong,
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
    }
}

// ==================== 顶部 Tab + 搜索框 + 搜索历史 ====================

@Composable
private fun MusicTopBar(
    tab: String,
    onTabChange: (String) -> Unit,
    searchKeyword: String,
    onSearchChange: (String) -> Unit,
    onSearch: () -> Unit,
    searching: Boolean,
    showHistory: Boolean,
    searchHistory: List<String>,
    onHistoryClick: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // 第一行：Tab + 搜索框
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Tab 切换（玻璃态胶囊）
            listOf("discover" to "🎵 发现", "netease" to "☁️ 网易云", "local" to "📁 本地").forEach { (id, label) ->
                val selected = tab == id
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) LiquidGlassTheme.accentPrimary.copy(alpha = 0.4f)
                            else LiquidGlassTheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                        .border(
                            width = 1.dp,
                            color = if (selected) LiquidGlassTheme.accentPrimary.copy(alpha = 0.6f)
                            else LiquidGlassTheme.glassBorder,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { onTabChange(id) }
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = label,
                        color = if (selected) LiquidGlassTheme.onSurfaceBright else LiquidGlassTheme.onSurfaceMuted,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // 搜索框（带历史下拉）
            Box(modifier = Modifier.weight(1f)) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(LiquidGlassTheme.surfaceVariant.copy(alpha = 0.7f))
                            .border(1.dp, LiquidGlassTheme.glassBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔍", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchKeyword.isEmpty()) {
                                Text(
                                    text = "搜索歌曲、歌手、专辑…",
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
                            Text("⏳", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 12.sp)
                        } else if (searchKeyword.isNotEmpty()) {
                            Text(
                                "✕",
                                color = LiquidGlassTheme.onSurfaceMuted,
                                fontSize = 14.sp,
                                modifier = Modifier.clickable { onSearchChange("") }
                            )
                        }
                    }

                    // 搜索历史下拉
                    if (showHistory && searchHistory.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(LiquidGlassTheme.glassBaseColor.copy(alpha = 0.95f))
                                .border(1.dp, LiquidGlassTheme.glassBorder, RoundedCornerShape(10.dp))
                                .padding(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "搜索历史",
                                    color = LiquidGlassTheme.onSurfaceMuted,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "清空",
                                    color = LiquidGlassTheme.announcementMedium,
                                    fontSize = 11.sp,
                                    modifier = Modifier.clickable { onClearHistory() }
                                )
                            }
                            searchHistory.forEach { kw ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { onHistoryClick(kw) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🕐", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 11.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        kw,
                                        color = LiquidGlassTheme.onSurfaceColor,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = onSearch,
                enabled = !searching && searchKeyword.isNotBlank(),
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
            ) {
                Text("搜索")
            }
        }
    }
}

// ==================== 发现 Tab ====================

@Composable
private fun DiscoverTab(
    searchResults: List<Song>,
    hasSearched: Boolean,
    searching: Boolean,
    onPlaySong: (Song, List<Song>) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onSearchHot: (String) -> Unit
) {
    var hotWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var recommendPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var toplists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var dailyRecommend by remember { mutableStateOf<List<Song>>(emptyList()) }
    var newSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var newAlbums by remember { mutableStateOf<List<NetEaseApi.AlbumInfo>>(emptyList()) }
    var personalFm by remember { mutableStateOf<List<Song>>(emptyList()) }
    var mvs by remember { mutableStateOf<List<NetEaseApi.MvInfo>>(emptyList()) }

    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }

    // 加载发现页数据
    LaunchedEffect(retryKey) {
        loading = true
        loadError = null
        try {
            kotlinx.coroutines.coroutineScope {
                launch { hotWords = NetEaseApi.hotSearch() }
                launch { recommendPlaylists = NetEaseApi.recommendPlaylists() }
                launch { toplists = NetEaseApi.toplist() }
                launch { dailyRecommend = NetEaseApi.recommendSongs() }
                launch { newSongs = NetEaseApi.newSongs(0) }
                launch { newAlbums = NetEaseApi.newAlbums() }
                launch { personalFm = NetEaseApi.personalFm() }
                launch { mvs = NetEaseApi.recommendMv() }
            }
        } catch (e: Exception) {
            loadError = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 20.dp)
    ) {
        // 搜索结果优先显示
        if (hasSearched) {
            item {
                SectionHeader(
                    title = "搜索结果（${searchResults.size} 首）",
                    actionText = if (searchResults.isNotEmpty()) "播放全部" else null,
                    onAction = if (searchResults.isNotEmpty()) {
                        { onPlaySong(searchResults.first(), searchResults) }
                    } else null
                )
            }
            when {
                searching -> item { LoadingState("搜索中…") }
                searchResults.isEmpty() -> item { EmptyState("没有找到相关歌曲\n试试其他关键词") }
                else -> items(searchResults) { song ->
                    SongRow(
                        song = song,
                        onPlay = { onPlaySong(song, searchResults) },
                        onAddToQueue = { onAddToQueue(song) }
                    )
                }
            }
        } else if (loading) {
            item { LoadingState("正在加载音乐数据…") }
        } else if (loadError != null) {
            item {
                ErrorState(
                    message = loadError!!,
                    onRetry = { retryKey++ }
                )
            }
        } else if (hotWords.isEmpty() && recommendPlaylists.isEmpty() &&
            toplists.isEmpty() && dailyRecommend.isEmpty() && newSongs.isEmpty()) {
            item {
                ErrorState(
                    message = "暂时无法获取音乐数据\n请检查网络连接或稍后重试",
                    onRetry = { retryKey++ }
                )
            }
        } else {
            // 热搜词
            if (hotWords.isNotEmpty()) {
                item {
                    SectionHeader(title = "🔥 热搜榜")
                    FlowChips(items = hotWords.take(15), onClick = onSearchHot)
                }
            }

            // 推荐歌单（横向卡片网格）
            if (recommendPlaylists.isNotEmpty()) {
                item {
                    SectionHeader(title = "🎵 推荐歌单")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(recommendPlaylists) { pl ->
                            PlaylistCard(playlist = pl)
                        }
                    }
                }
            }

            // 每日推荐
            if (dailyRecommend.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "📅 每日推荐",
                        actionText = "播放全部",
                        onAction = { onPlaySong(dailyRecommend.first(), dailyRecommend) }
                    )
                }
                items(dailyRecommend.take(10)) { song ->
                    SongRow(
                        song = song,
                        onPlay = { onPlaySong(song, dailyRecommend) },
                        onAddToQueue = { onAddToQueue(song) }
                    )
                }
            }

            // 排行榜（横向卡片）
            if (toplists.isNotEmpty()) {
                item {
                    SectionHeader(title = "🏆 排行榜")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(toplists.take(10)) { pl ->
                            PlaylistCard(playlist = pl)
                        }
                    }
                }
            }

            // 新歌速递
            if (newSongs.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "🆕 新歌速递",
                        actionText = "播放全部",
                        onAction = { onPlaySong(newSongs.first(), newSongs) }
                    )
                }
                items(newSongs.take(10)) { song ->
                    SongRow(
                        song = song,
                        onPlay = { onPlaySong(song, newSongs) },
                        onAddToQueue = { onAddToQueue(song) }
                    )
                }
            }

            // 私人 FM
            if (personalFm.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "📡 私人 FM",
                        actionText = "播放全部",
                        onAction = { onPlaySong(personalFm.first(), personalFm) }
                    )
                }
                items(personalFm.take(10)) { song ->
                    SongRow(
                        song = song,
                        onPlay = { onPlaySong(song, personalFm) },
                        onAddToQueue = { onAddToQueue(song) }
                    )
                }
            }

            // 新碟上架（横向卡片）
            if (newAlbums.isNotEmpty()) {
                item {
                    SectionHeader(title = "💿 新碟上架")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(newAlbums) { album ->
                            AlbumCard(album = album)
                        }
                    }
                }
            }

            // MV 推荐（横向卡片）
            if (mvs.isNotEmpty()) {
                item {
                    SectionHeader(title = "📺 MV 推荐")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(mvs) { mv ->
                            MvCard(mv = mv)
                        }
                    }
                }
            }
        }
    }
}

// ==================== 网易云 Tab ====================

@Composable
private fun NetEaseTab(
    onPlaySong: (Song, List<Song>) -> Unit,
    onAddToQueue: (Song) -> Unit
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
        contentPadding = PaddingValues(bottom = 20.dp)
    ) {
        // 用户信息卡
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(LiquidGlassTheme.glassBaseColor.copy(alpha = 0.7f))
                    .border(1.dp, LiquidGlassTheme.glassBorder, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (userAccount != null) {
                    // 头像
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(LiquidGlassTheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (userAccount!!.avatarUrl.isNotBlank()) {
                            CoverArt(url = userAccount!!.avatarUrl, size = 48.dp, shape = CircleShape)
                        } else {
                            Text("👤", fontSize = 24.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                userAccount!!.nickname,
                                color = LiquidGlassTheme.onSurfaceColor,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            "ID: ${userAccount!!.userId}",
                            color = LiquidGlassTheme.onSurfaceMuted,
                            fontSize = 12.sp
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            NetEaseAuth.logout()
                            userAccount = null
                            myPlaylists = emptyList()
                            selectedPlaylist = null
                            playlistTracks = emptyList()
                        },
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    ) { Text("退出") }
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(LiquidGlassTheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) { Text("🔐", fontSize = 24.sp) }
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
                    Button(
                        onClick = { showLoginDialog = true },
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    ) { Text("登录") }
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
                    item { LoadingState("加载曲目中…") }
                } else if (playlistTracks.isEmpty()) {
                    item { EmptyState("歌单为空") }
                } else {
                    item {
                        SectionHeader(
                            title = "🎵 ${selectedPlaylist!!.name}（${playlistTracks.size}）",
                            actionText = "播放全部",
                            onAction = { onPlaySong(playlistTracks.first(), playlistTracks) }
                        )
                    }
                    items(playlistTracks) { song ->
                        SongRow(
                            song = song,
                            onPlay = { onPlaySong(song, playlistTracks) },
                            onAddToQueue = { onAddToQueue(song) }
                        )
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
    onPlaySong: (Song, List<Song>) -> Unit,
    onAddToQueue: (Song) -> Unit
) {
    val scope = rememberCoroutineScope()
    var localSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableStateOf<LocalMusicScanner.ScanProgress?>(null) }
    var scanDir by remember { mutableStateOf(System.getProperty("user.home")) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 20.dp)
    ) {
        // 扫描目录选择卡
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(LiquidGlassTheme.glassBaseColor.copy(alpha = 0.7f))
                    .border(1.dp, LiquidGlassTheme.glassBorder, RoundedCornerShape(14.dp))
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
                OutlinedButton(
                    onClick = {
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
                    },
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                ) { Text("选择") }
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
                    enabled = !scanning,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                ) { Text(if (scanning) "扫描中…" else "扫描") }
            }
        }

        // 扫描进度
        scanProgress?.let { p ->
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(LiquidGlassTheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📊", color = LiquidGlassTheme.accentSecondary, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "已扫描 ${p.scanned} 个文件，找到 ${p.found} 首音乐",
                        color = LiquidGlassTheme.onSurfaceMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 本地歌曲列表
        if (localSongs.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "🎵 本地音乐（${localSongs.size}）",
                    actionText = "播放全部",
                    onAction = { onPlaySong(localSongs.first(), localSongs) }
                )
            }
            items(localSongs) { song ->
                SongRow(
                    song = song,
                    onPlay = { onPlaySong(song, localSongs) },
                    onAddToQueue = { onAddToQueue(song) }
                )
            }
        } else if (!scanning) {
            item { EmptyState("点击「扫描」按钮添加本地音乐\n支持 MP3 格式") }
        }
    }
}

// ==================== 底部播放栏（重新设计）====================

@Composable
private fun PlayerBar(
    player: PlaybackController,
    currentSong: Song?,
    playMode: PlayMode,
    sleepTimerMs: Long,
    queueSize: Int,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleQueue: () -> Unit,
    onToggleLyrics: () -> Unit,
    onToggleSettings: () -> Unit,
    onModeChange: (PlayMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var seekingMs by remember { mutableStateOf<Long?>(null) }
    var volSlider by remember { mutableStateOf(player.volume) }

    Row(
        modifier = modifier
            .height(100.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(LiquidGlassTheme.glassBaseColor.copy(alpha = LiquidGlassTheme.glassAlphaBright))
            .border(1.dp, LiquidGlassTheme.glassBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ===== 左：封面 + 标题 + 艺术家 =====
        CoverArt(url = currentSong?.coverUrl, size = 60.dp, shape = RoundedCornerShape(10.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.width(200.dp)) {
            Text(
                currentSong?.title ?: "未播放",
                color = LiquidGlassTheme.onSurfaceColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                currentSong?.artist?.ifBlank { "未知艺术家" } ?: "—",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (currentSong?.isVipOnly == true) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "VIP 专享 · 可能无法播放",
                    color = LiquidGlassTheme.announcementHigh,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(Modifier.width(20.dp))

        // ===== 中：控制按钮 + 进度条 =====
        Column(modifier = Modifier.weight(1f)) {
            // 控制按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 播放模式（点击切换）
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onModeChange(nextPlayMode(playMode)) }
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
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
                Spacer(Modifier.width(16.dp))

                // 上一首
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { onPrev() }
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⏮", color = LiquidGlassTheme.onSurfaceColor, fontSize = 18.sp)
                }
                Spacer(Modifier.width(16.dp))

                // 播放/暂停（大圆形按钮）
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(LiquidGlassTheme.accentPrimary)
                        .border(1.dp, LiquidGlassTheme.glassHighlight, CircleShape)
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
                Spacer(Modifier.width(16.dp))

                // 下一首
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { onNext() }
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⏭", color = LiquidGlassTheme.onSurfaceColor, fontSize = 18.sp)
                }
                Spacer(Modifier.width(16.dp))

                // 队列
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onToggleQueue() }
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("☰", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 16.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            // 进度条（可拖动）
            val position = seekingMs ?: player.positionMs
            val duration = player.durationMs.coerceAtLeast(0L)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatMs(position),
                    color = LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.width(40.dp)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(LiquidGlassTheme.surfaceVariant.copy(alpha = 0.6f))
                        .pointerInput(duration) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: continue
                                    val ratio = (change.position.x / size.width).coerceIn(0f, 1f)
                                    if (event.type == PointerEventType.Press ||
                                        (event.type == PointerEventType.Move && change.pressed)) {
                                        if (duration > 0) {
                                            seekingMs = (ratio * duration).toLong()
                                        }
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
                            .clip(RoundedCornerShape(3.dp))
                            .background(LiquidGlassTheme.accentSecondary)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    formatMs(duration),
                    color = LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.width(40.dp)
                )
            }
        }

        Spacer(Modifier.width(20.dp))

        // ===== 右：音量 + 歌词 + 设置 =====
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 音量
            Text("🔊", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(LiquidGlassTheme.surfaceVariant.copy(alpha = 0.6f))
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
                        .clip(RoundedCornerShape(3.dp))
                        .background(LiquidGlassTheme.accentSecondary)
                )
            }

            Spacer(Modifier.width(16.dp))

            // 歌词
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { onToggleLyrics() }
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("词", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 13.sp)
            }

            Spacer(Modifier.width(8.dp))

            // 设置
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { onToggleSettings() }
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("⚙", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 16.sp)
            }

            // 睡眠定时指示
            if (sleepTimerMs > 0L) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "⏰${formatMs(sleepTimerMs)}",
                    color = LiquidGlassTheme.announcementMedium,
                    fontSize = 10.sp
                )
            }

            // 队列数量
            if (queueSize > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "$queueSize",
                    color = LiquidGlassTheme.accentSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
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
    onRemove: (Int) -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onClose)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(400.dp)
                .background(LiquidGlassTheme.glassBaseColor.copy(alpha = 0.97f))
                .border(1.dp, LiquidGlassTheme.glassBorder)
                .padding(20.dp)
                .clickable(enabled = false) { }
        ) {
            // 标题栏
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "播放队列",
                    color = LiquidGlassTheme.onSurfaceColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${queue.size} 首",
                    color = LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 13.sp
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { onClose() }
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(16.dp))

            // 队列列表
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                itemsIndexed(queue) { idx, song ->
                    val playing = idx == currentIndex
                    var hovered by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    playing -> LiquidGlassTheme.accentPrimary.copy(alpha = 0.3f)
                                    hovered -> LiquidGlassTheme.surfaceVariant.copy(alpha = 0.5f)
                                    else -> Color.Transparent
                                }
                            )
                            .clickable { onSongClick(idx) }
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
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 序号或播放指示
                        Box(
                            modifier = Modifier.width(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (playing) {
                                Text("▶", color = LiquidGlassTheme.accentSecondary, fontSize = 12.sp)
                            } else {
                                Text(
                                    "${idx + 1}",
                                    color = LiquidGlassTheme.onSurfaceMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        // 封面
                        CoverArt(url = song.coverUrl, size = 36.dp, shape = RoundedCornerShape(6.dp))
                        Spacer(Modifier.width(10.dp))
                        // 标题+艺术家
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                song.title,
                                color = if (playing) LiquidGlassTheme.onSurfaceBright
                                else LiquidGlassTheme.onSurfaceColor,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                song.artist.ifBlank { "未知艺术家" },
                                color = LiquidGlassTheme.onSurfaceMuted,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        // VIP 标签
                        if (song.isVipOnly) {
                            Text(
                                "VIP",
                                color = LiquidGlassTheme.announcementHigh,
                                fontSize = 9.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(LiquidGlassTheme.announcementHigh.copy(alpha = 0.2f))
                                    .padding(horizontal = 3.dp, vertical = 1.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        // 时长
                        Text(
                            formatMs(song.durationMs),
                            color = LiquidGlassTheme.onSurfaceMuted,
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        // 移除按钮
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable { onRemove(idx) }
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✕", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 11.sp)
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
    song: Song?,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onClose)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.65f)
                .height(560.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(LiquidGlassTheme.glassBaseColor.copy(alpha = 0.97f))
                .border(1.dp, LiquidGlassTheme.glassBorder, RoundedCornerShape(18.dp))
                .padding(28.dp)
                .clickable(enabled = false) { }
        ) {
            // 标题栏
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        song?.title ?: "歌词",
                        color = LiquidGlassTheme.onSurfaceColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (song != null) {
                        Text(
                            song.artist.ifBlank { "未知艺术家" },
                            color = LiquidGlassTheme.onSurfaceMuted,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { onClose() }
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(20.dp))

            if (lyrics == null || (lyrics.lrcLines.isEmpty() && lyrics.yrcLines.isEmpty())) {
                EmptyState("暂无歌词\n可能是纯音乐或歌词尚未收录")
            } else {
                // 统一歌词显示项
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
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                // 自动滚动到当前歌词
                LaunchedEffect(currentIdx) {
                    if (displayLines.isNotEmpty() && currentIdx in displayLines.indices) {
                        listState.animateScrollToItem(currentIdx)
                    }
                }
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 220.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(displayLines.size) { idx ->
                        val isCurrent = idx == currentIdx
                        val line = displayLines[idx]
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                line.content,
                                color = if (isCurrent) LiquidGlassTheme.accentSecondary
                                else LiquidGlassTheme.onSurfaceMuted.copy(alpha = 0.7f),
                                fontSize = if (isCurrent) 18.sp else 14.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                textAlign = TextAlign.Center
                            )
                            if (line.translation.isNotBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    line.translation,
                                    color = if (isCurrent) LiquidGlassTheme.onSurfaceColor.copy(alpha = 0.85f)
                                    else LiquidGlassTheme.onSurfaceMuted.copy(alpha = 0.5f),
                                    fontSize = if (isCurrent) 13.sp else 11.sp,
                                    textAlign = TextAlign.Center
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
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onClose)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.5f)
                .clip(RoundedCornerShape(18.dp))
                .background(LiquidGlassTheme.glassBaseColor.copy(alpha = 0.97f))
                .border(1.dp, LiquidGlassTheme.glassBorder, RoundedCornerShape(18.dp))
                .padding(28.dp)
                .clickable(enabled = false) { }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "播放设置",
                    color = LiquidGlassTheme.onSurfaceColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { onClose() }
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(20.dp))

            // 播放模式
            SettingSection("播放模式") {
                ChipGroup(
                    options = listOf(
                        PlayMode.SEQUENCE to "顺序播放",
                        PlayMode.SINGLE to "单曲循环",
                        PlayMode.RANDOM to "随机播放"
                    ),
                    selected = playMode,
                    onSelect = onModeChange
                )
            }
            Spacer(Modifier.height(20.dp))

            // 音质
            SettingSection("音质") {
                ChipGroup(
                    options = listOf(
                        "standard" to "标准",
                        "exhigh" to "极高",
                        "lossless" to "无损",
                        "hires" to "Hi-Res"
                    ),
                    selected = quality,
                    onSelect = onQualityChange
                )
            }
            Spacer(Modifier.height(20.dp))

            // 睡眠定时
            SettingSection("睡眠定时") {
                ChipGroup(
                    options = listOf(
                        0L to "关闭",
                        15 * 60_000L to "15分钟",
                        30 * 60_000L to "30分钟",
                        60 * 60_000L to "60分钟"
                    ),
                    selected = sleepTimerMs,
                    onSelect = onSleepTimerChange
                )
            }
        }
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable () -> Unit) {
    Text(title, color = LiquidGlassTheme.onSurfaceColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    content()
}

@Composable
private fun <T> ChipGroup(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) LiquidGlassTheme.accentPrimary.copy(alpha = 0.4f)
                        else LiquidGlassTheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) LiquidGlassTheme.accentPrimary.copy(alpha = 0.6f)
                        else Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    label,
                    color = if (isSelected) LiquidGlassTheme.onSurfaceBright
                    else LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 12.sp
                )
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
    var mode by remember { mutableStateOf("qr") }

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
                            Text(
                                label,
                                color = if (selected) LiquidGlassTheme.onSurfaceBright
                                else LiquidGlassTheme.onSurfaceMuted,
                                fontSize = 12.sp
                            )
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
                QrLoginState.EXPIRED -> "二维码已过期，请关闭重试"
                QrLoginState.ERROR -> "登录异常，请重试"
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

/** 异步加载二维码图片 */
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
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (actionText != null && onAction != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(LiquidGlassTheme.accentSecondary.copy(alpha = 0.15f))
                    .clickable { onAction() }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    actionText,
                    color = LiquidGlassTheme.accentSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun SongRow(
    song: Song,
    onPlay: () -> Unit,
    onAddToQueue: () -> Unit
) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (hovered) LiquidGlassTheme.surfaceVariant.copy(alpha = 0.6f)
                else LiquidGlassTheme.glassBaseColor.copy(alpha = 0.35f)
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
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面
        CoverArt(url = song.coverUrl, size = 44.dp, shape = RoundedCornerShape(8.dp))
        Spacer(Modifier.width(12.dp))
        // 标题+艺术家
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    song.title,
                    color = LiquidGlassTheme.onSurfaceColor,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (song.isVipOnly) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "VIP",
                        color = LiquidGlassTheme.onSurfaceBright,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(LiquidGlassTheme.orange)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${song.artist.ifBlank { "未知" }} · ${song.album.ifBlank { "—" }}",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        // 时长
        Text(
            formatMs(song.durationMs),
            color = LiquidGlassTheme.onSurfaceMuted,
            fontSize = 11.sp
        )
        // 加入队列按钮（hover 显示）
        if (hovered) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(LiquidGlassTheme.surfaceVariant.copy(alpha = 0.6f))
                    .clickable { onAddToQueue() }
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("+", color = LiquidGlassTheme.onSurfaceColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: (() -> Unit)? = null) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (hovered) LiquidGlassTheme.surfaceVariant.copy(alpha = 0.6f)
                else LiquidGlassTheme.glassBaseColor.copy(alpha = 0.35f)
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
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(url = playlist.coverUrl, size = 52.dp, shape = RoundedCornerShape(8.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                playlist.name,
                color = LiquidGlassTheme.onSurfaceColor,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${playlist.trackCount} 首 · ${playlist.creator.ifBlank { "—" }}",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 推荐歌单/排行榜横向卡片 */
@Composable
private fun PlaylistCard(playlist: Playlist) {
    var hovered by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (hovered) LiquidGlassTheme.surfaceVariant.copy(alpha = 0.5f)
                else LiquidGlassTheme.glassBaseColor.copy(alpha = 0.4f)
            )
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
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(124.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(LiquidGlassTheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            CoverArt(url = playlist.coverUrl, size = 124.dp, shape = RoundedCornerShape(10.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            playlist.name,
            color = LiquidGlassTheme.onSurfaceColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "${playlist.trackCount} 首",
            color = LiquidGlassTheme.onSurfaceMuted,
            fontSize = 11.sp
        )
    }
}

/** 新碟卡片 */
@Composable
private fun AlbumCard(album: NetEaseApi.AlbumInfo) {
    var hovered by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(130.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (hovered) LiquidGlassTheme.surfaceVariant.copy(alpha = 0.5f)
                else LiquidGlassTheme.glassBaseColor.copy(alpha = 0.4f)
            )
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
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(114.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(LiquidGlassTheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            CoverArt(url = album.coverUrl, size = 114.dp, shape = RoundedCornerShape(10.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            album.name,
            color = LiquidGlassTheme.onSurfaceColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(2.dp))
        Text(
            album.artist.ifBlank { "—" },
            color = LiquidGlassTheme.onSurfaceMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** MV 卡片 */
@Composable
private fun MvCard(mv: NetEaseApi.MvInfo) {
    var hovered by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (hovered) LiquidGlassTheme.surfaceVariant.copy(alpha = 0.5f)
                else LiquidGlassTheme.glassBaseColor.copy(alpha = 0.4f)
            )
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
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(144.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(LiquidGlassTheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            CoverArt(url = mv.coverUrl, size = 144.dp, shape = RoundedCornerShape(10.dp))
            // 播放按钮覆盖
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Text("▶", color = LiquidGlassTheme.onSurfaceBright, fontSize = 18.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            mv.name,
            color = LiquidGlassTheme.onSurfaceColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "${mv.artist} · ${formatPlayCount(mv.playCount)}",
            color = LiquidGlassTheme.onSurfaceMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FlowChips(items: List<String>, onClick: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.take(12).forEachIndexed { idx, word ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(LiquidGlassTheme.surfaceVariant.copy(alpha = 0.6f))
                    .border(1.dp, LiquidGlassTheme.glassBorder, RoundedCornerShape(14.dp))
                    .clickable { onClick(word) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (idx < 3) {
                    Text(
                        "${idx + 1}",
                        color = LiquidGlassTheme.accentSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    word,
                    color = LiquidGlassTheme.onSurfaceColor,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = LiquidGlassTheme.onSurfaceMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun LoadingState(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material.CircularProgressIndicator(
                color = LiquidGlassTheme.accentSecondary,
                strokeWidth = 3.dp
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text,
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "⚠",
                color = LiquidGlassTheme.announcementHigh,
                fontSize = 32.sp
            )
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
            ) { Text("重试") }
        }
    }
}

/** 异步加载封面图，加载失败显示音乐占位符 */
@Composable
private fun CoverArt(
    url: String?,
    size: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(6.dp)
) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = null
        if (!url.isNullOrBlank()) {
            bitmap = withContext(Dispatchers.IO) {
                try {
                    // 网易云封面 URL 走 http/https，用 ImageIO 异步加载
                    val img = ImageIO.read(URL(url))
                    img?.toComposeImageBitmap()
                } catch (_: Exception) { null }
            }
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
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
        } ?: Text("🎵", color = LiquidGlassTheme.onSurfaceMuted, fontSize = (size.value / 2.5f).sp)
    }
}

// ==================== 工具函数 ====================

/** 播放模式 */
enum class PlayMode { SEQUENCE, SINGLE, RANDOM }

/** 循环切换播放模式 */
private fun nextPlayMode(mode: PlayMode): PlayMode {
    return when (mode) {
        PlayMode.SEQUENCE -> PlayMode.SINGLE
        PlayMode.SINGLE -> PlayMode.RANDOM
        PlayMode.RANDOM -> PlayMode.SEQUENCE
    }
}

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

/** 播放次数 → 万/亿 显示 */
private fun formatPlayCount(count: Int): String {
    return when {
        count >= 100_000_000 -> "%.1f亿".format(count / 100_000_000.0)
        count >= 10_000 -> "%.0f万".format(count / 10_000.0)
        else -> count.toString()
    }
}
