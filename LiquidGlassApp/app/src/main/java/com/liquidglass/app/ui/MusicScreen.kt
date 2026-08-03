package com.liquidglass.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.zxing.common.BitMatrix
import com.liquidglass.app.music.*
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────
// 音乐播放器主入口
// 三 Tab：网易云（扫码登录+搜索+歌单）/ 本地（设备音乐扫描）/ 平台（酷狗·汽水·QQ 跳转）
// 底部迷你播放条 + 全屏 Now Playing（封面/进度/歌词）
// ─────────────────────────────────────────────────────────────────

private enum class MusicTab(val label: String) { DISCOVER("发现"), NETEASE("网易云"), LOCAL("本地"), PLATFORM("平台") }
private enum class MusicPage { MAIN, SEARCH, PLAYLIST, NOW_PLAYING, QUEUE, QUALITY, SLEEP, LYRICS }

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MusicScreen(animTime: Float, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 初始化网络客户端 + 播放控制器 + 歌词设置
    LaunchedEffect(Unit) {
        NetEaseApiClient.init(context)
        MusicControllerManager.init(context)
        LyricsSettings.init(context)
    }

    var tab by rememberSaveable { mutableStateOf(MusicTab.NETEASE) }
    var page by rememberSaveable { mutableStateOf(MusicPage.MAIN) }
    var pendingPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var searchKeyword by remember { mutableStateOf("") }

    // 登录态变更计数器：登录/退出时 +1，触发顶部栏 VIP 徽章与 NeteaseTab 重组
    var loginTick by remember { mutableStateOf(0) }

    val playback by MusicControllerManager.state.collectAsState()
    val playError by MusicControllerManager.error.collectAsState()

    // 播放错误提示（VIP/无版权等）→ Toast 后清空
    LaunchedEffect(playError) {
        playError?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            MusicControllerManager.consumeError()
        }
    }

    // Android 13+ 通知权限：首次开始播放时请求（否则通知栏/锁屏控件不显示）
    var notifAsked by rememberSaveable { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(playback.song?.id) {
        if (!notifAsked && playback.song != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifAsked = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 进度刷新（播放中每 500ms 推一次位置）
    LaunchedEffect(playback.isPlaying, playback.isBuffering) {
        while (playback.isPlaying || playback.isBuffering) {
            MusicControllerManager.tickPosition()
            delay(500)
        }
    }

    LiquidGlassScaffold(animTime = animTime) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // 顶部栏
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (page != MusicPage.MAIN) page = MusicPage.MAIN else onBack()
                }) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = appTextSecondary())
                }
                Text(
                    when (page) {
                        MusicPage.MAIN -> "音乐"
                        MusicPage.SEARCH -> "搜索"
                        MusicPage.PLAYLIST -> pendingPlaylist?.name ?: "歌单"
                        MusicPage.NOW_PLAYING -> "正在播放"
                        MusicPage.QUEUE -> "播放队列"
                        MusicPage.QUALITY -> "音质调节"
                        MusicPage.SLEEP -> "睡眠定时器"
                        MusicPage.LYRICS -> "歌词设置"
                    },
                    fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    color = appTextPrimary(),
                    modifier = Modifier.weight(1f)
                )
                // 正在播放页：右上角队列入口
                if (page == MusicPage.NOW_PLAYING) {
                    IconButton(onClick = { page = MusicPage.QUEUE }) {
                        Icon(Icons.Default.QueueMusic, "播放队列", tint = appTextSecondary())
                    }
                }
                // VIP 状态徽章（loginTick 触发登录/退出后重组）
                val isLoggedIn = remember(loginTick) { SessionStore.isLoggedIn(context) }
                val isVip = remember(loginTick) { SessionStore.isVip(context) }
                if (isLoggedIn && isVip) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Brush.linearGradient(listOf(FluidOrange, FluidPink)))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("VIP", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }

            // Tab 切换 + 功能入口卡片行（仅 MAIN 页显示）
            AnimatedVisibility(
                visible = page == MusicPage.MAIN,
                enter = fadeIn(), exit = fadeOut()
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.10f)
                            .padding(4.dp)
                    ) {
                        MusicTab.values().forEach { t ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .then(
                                        if (tab == t) Modifier.background(
                                            Brush.linearGradient(listOf(FluidCyan.copy(alpha = 0.25f), FluidPurple.copy(alpha = 0.25f)))
                                        ) else Modifier
                                    )
                                    .clickable { tab = t }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    t.label,
                                    fontSize = 13.sp,
                                    color = if (tab == t) FluidCyan else appTextTertiary(),
                                    fontWeight = if (tab == t) FontWeight.Medium else FontWeight.Normal
                                )
                            }
                        }
                    }

                    // 功能入口卡片：横向滚动，把音乐相关附加功能整合为独立入口
                    MusicFeatureEntries(
                        onSearch = { page = MusicPage.SEARCH },
                        onQueue = { page = MusicPage.QUEUE },
                        onQuality = { page = MusicPage.QUALITY },
                        onSleep = { page = MusicPage.SLEEP },
                        onLyrics = { page = MusicPage.LYRICS }
                    )
                }
            }

            // 内容区
            Box(modifier = Modifier.weight(1f)) {
                when (page) {
                    MusicPage.MAIN -> when (tab) {
                        MusicTab.DISCOVER -> DiscoverTab(
                            onOpenPlaylist = { pendingPlaylist = it; page = MusicPage.PLAYLIST },
                            onPlaySongs = { songs, idx -> MusicControllerManager.playSongs(context, songs, idx) }
                        )
                        MusicTab.NETEASE -> NeteaseTab(
                            onOpenSearch = { page = MusicPage.SEARCH },
                            onOpenPlaylist = { pendingPlaylist = it; page = MusicPage.PLAYLIST },
                            onLoginChanged = { loginTick++ }
                        )
                        MusicTab.LOCAL -> LocalTab()
                        MusicTab.PLATFORM -> PlatformTab()
                    }
                    MusicPage.SEARCH -> SearchPage(
                        initialKeyword = searchKeyword,
                        onKeywordChange = { searchKeyword = it },
                        onBack = { page = MusicPage.MAIN }
                    )
                    MusicPage.PLAYLIST -> PlaylistDetailPage(playlist = pendingPlaylist)
                    MusicPage.NOW_PLAYING -> NowPlayingPage(onBack = { page = MusicPage.MAIN })
                    MusicPage.QUEUE -> QueuePage(onBack = { page = MusicPage.NOW_PLAYING })
                    MusicPage.QUALITY -> QualitySettingsPage(onBack = { page = MusicPage.MAIN })
                    MusicPage.SLEEP -> SleepTimerPage(onBack = { page = MusicPage.MAIN })
                    MusicPage.LYRICS -> LyricsSettingsPage(onBack = { page = MusicPage.MAIN })
                }
            }

            // 迷你播放条
            AnimatedVisibility(
                visible = playback.song != null && page != MusicPage.NOW_PLAYING,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it }
            ) {
                MiniPlayerBar(
                    state = playback,
                    onClick = { page = MusicPage.NOW_PLAYING },
                    onPlayPause = { MusicControllerManager.playPause() },
                    onNext = { MusicControllerManager.next() }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// 网易云 Tab：登录态分发 + 搜索入口 + 我的歌单
// ─────────────────────────────────────────────────────────────────

@Composable
private fun NeteaseTab(
    onOpenSearch: () -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onLoginChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberLazyListState()

    // 登录态作为 Compose state，退出登录后能触发重组切回登录页
    var loggedIn by remember { mutableStateOf(SessionStore.isLoggedIn(context)) }

    if (!loggedIn) {
        NeteaseLoginView(onLoginSuccess = {
            loggedIn = true
            onLoginChanged()
        })
        return
    }

    // 已登录：账户头 + 搜索入口 + 歌单
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        // 预热：先调 fetchAccount 触发服务端 Set-Cookie __csrf，
        // 确保后续 userPlaylists 请求带上真实 csrf token（网易云校验 csrf_token
        // 必须与 cookie __csrf 一致，否则返回空）。旧版本升级用户持久化里没有
        // __csrf，这里补上。
        withContext(Dispatchers.IO) { NetEaseAuth.fetchAccount(context) }
        playlists = withContext(Dispatchers.IO) { NetEaseApi.userPlaylists(context) }
        loading = false
    }

    LazyColumn(
        state = scrollState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 账户卡
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.12f)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(SessionStore.getAvatarUrl(context)).crossfade(true).build(),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(SessionStore.getNickname(context), fontSize = 15.sp, color = appTextPrimary(), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (SessionStore.isVip(context)) "黑胶VIP会员" else "普通用户",
                        fontSize = 11.sp,
                        color = if (SessionStore.isVip(context)) FluidOrange else appTextTertiary()
                    )
                }
                TextButton(onClick = {
                    NetEaseAuth.logout(context)
                    MusicControllerManager.clearQueue()
                    playlists = emptyList()
                    loggedIn = false
                    onLoginChanged()
                }) { Text("退出", fontSize = 12.sp, color = AccentDanger) }
            }
        }

        // 搜索入口
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.10f)
                    .clickable { onOpenSearch() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, null, tint = appTextTertiary(), modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("搜索歌曲、歌手、专辑", fontSize = 14.sp, color = appTextTertiary(), modifier = Modifier.weight(1f))
            }
        }

        item {
            Text("我的歌单", fontSize = 14.sp, color = appTextSecondary(), fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (loading) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FluidCyan, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                }
            }
        } else {
            items(playlists, key = { it.id }) { p ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.08f)
                        .clickable { onOpenPlaylist(p) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = p.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.size(50.dp).clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(p.name, fontSize = 14.sp, color = appTextPrimary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.trackCount}首 · ${p.creator}", fontSize = 11.sp, color = appTextTertiary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// 登录方式分发：扫码登录 / 手机验证码登录
// ─────────────────────────────────────────────────────────────────

private enum class LoginMethod(val label: String) { QR("扫码登录"), PHONE("手机验证码登录") }

@Composable
private fun NeteaseLoginView(onLoginSuccess: () -> Unit = {}) {
    var method by rememberSaveable { mutableStateOf(LoginMethod.QR) }
    Column(modifier = Modifier.fillMaxSize()) {
        // 登录方式切换 Tab
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.10f)
                .padding(4.dp)
        ) {
            LoginMethod.values().forEach { m ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (method == m) Modifier.background(
                                Brush.linearGradient(listOf(FluidCyan.copy(alpha = 0.22f), FluidPurple.copy(alpha = 0.22f)))
                            ) else Modifier
                        )
                        .clickable { method = m }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        m.label,
                        fontSize = 12.sp,
                        color = if (method == m) FluidCyan else appTextTertiary(),
                        fontWeight = if (method == m) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        }
        when (method) {
            LoginMethod.QR -> QrLoginView(onLoginSuccess = onLoginSuccess)
            LoginMethod.PHONE -> PhoneLoginView(onLoginSuccess = onLoginSuccess)
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// 手机验证码登录
// 对接 NetEaseAuth.sendSmsCode / loginWithPhone
// ─────────────────────────────────────────────────────────────────

@Composable
private fun PhoneLoginView(onLoginSuccess: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phone by rememberSaveable { mutableStateOf("") }
    var captcha by rememberSaveable { mutableStateOf("") }
    var state by remember { mutableStateOf(PhoneLoginState.IDLE) }
    var message by remember { mutableStateOf("") }
    var countdown by remember { mutableStateOf(0) }       // 重发倒计时（秒）
    var sending by remember { mutableStateOf(false) }     // 正在发送验证码
    var loggingIn by remember { mutableStateOf(false) }   // 正在登录

    // 倒计时驱动：每秒 -1，到 0 停
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    // 上次绑定手机号预填，便于"用绑定手机登录平台账号"
    LaunchedEffect(Unit) {
        val bound = SessionStore.getBoundPhone(context)
        if (bound.isNotBlank()) phone = bound
    }

    fun sendCode() {
        if (sending || countdown > 0) return
        scope.launch {
            sending = true
            state = PhoneLoginState.SENDING_CODE
            val r = NetEaseAuth.sendSmsCode(phone)
            state = r.state
            message = r.message
            if (r.state == PhoneLoginState.CODE_SENT) {
                countdown = 60
            }
            sending = false
        }
    }

    fun doLogin() {
        if (loggingIn) return
        scope.launch {
            loggingIn = true
            state = PhoneLoginState.LOGGING_IN
            // 平台账号相同绑定手机校验：若与上次绑定的手机号不一致，提示用户
            if (!SessionStore.isBoundPhoneMatched(context, phone) && SessionStore.getBoundPhone(context).isNotBlank()) {
                // 此处简化处理：仍允许登录（用户可能想换号），但提示一下
                message = "提示：该手机号与上次绑定手机号不同，将切换为新账号"
            }
            val r = NetEaseAuth.loginWithPhone(context, phone, captcha)
            state = r.state
            message = r.message
            if (r.state == PhoneLoginState.SUCCESS) {
                onLoginSuccess()
            }
            loggingIn = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("网易云手机号登录", fontSize = 18.sp, color = appTextPrimary(), fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(6.dp))
        Text("使用平台账号绑定的手机号 + 短信验证码登录", fontSize = 11.sp, color = appTextTertiary(), textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(24.dp))

        // 手机号输入
        Row(
            modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 14.dp, glassAlpha = 0.10f).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("+86", fontSize = 14.sp, color = appTextSecondary(), fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.width(1.dp).height(20.dp).background(appTextTertiary().copy(alpha = 0.3f)))
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = phone,
                onValueChange = { if (it.length <= 11 && it.all { c -> c.isDigit() }) phone = it },
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                textStyle = TextStyle(color = appTextPrimary(), fontSize = 15.sp),
                cursorBrush = SolidColor(FluidCyan),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = ImeAction.Next),
                decorationBox = { inner ->
                    if (phone.isEmpty()) Text("请输入手机号", fontSize = 14.sp, color = appTextTertiary())
                    inner()
                }
            )
            if (phone.isNotEmpty()) {
                IconButton(onClick = { phone = "" }, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Close, "清空", tint = appTextTertiary(), modifier = Modifier.size(14.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 验证码 + 获取验证码
        Row(
            modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 14.dp, glassAlpha = 0.10f).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = captcha,
                onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) captcha = it },
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                textStyle = TextStyle(color = appTextPrimary(), fontSize = 15.sp),
                cursorBrush = SolidColor(FluidCyan),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (phone.length == 11 && captcha.length >= 4) doLogin() }),
                decorationBox = { inner ->
                    if (captcha.isEmpty()) Text("请输入短信验证码", fontSize = 14.sp, color = appTextTertiary())
                    inner()
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (phone.length == 11 && countdown == 0 && !sending) FluidCyan.copy(alpha = 0.18f)
                        else Color.White.copy(alpha = 0.05f)
                    )
                    .clickable(enabled = phone.length == 11 && countdown == 0 && !sending) { sendCode() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    when {
                        sending -> "发送中"
                        countdown > 0 -> "${countdown}s"
                        else -> "获取验证码"
                    },
                    fontSize = 12.sp,
                    color = if (phone.length == 11 && countdown == 0 && !sending) FluidCyan else appTextTertiary()
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 登录按钮
        Button(
            onClick = { doLogin() },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = FluidCyan.copy(alpha = 0.85f), contentColor = Color.White),
            enabled = !loggingIn && phone.length == 11 && captcha.length >= 4
        ) {
            if (loggingIn) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("登录中...", fontSize = 14.sp)
            } else {
                Text("登录", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        // 状态/消息提示
        if (message.isNotEmpty() || state != PhoneLoginState.IDLE) {
            Spacer(modifier = Modifier.height(12.dp))
            val msgColor = when (state) {
                PhoneLoginState.SUCCESS -> AccentSuccess
                PhoneLoginState.CODE_SENT -> FluidCyan
                PhoneLoginState.SMS_FAILED, PhoneLoginState.LOGIN_FAILED, PhoneLoginState.BOUND_MISMATCH, PhoneLoginState.ERROR -> AccentWarning
                else -> appTextTertiary()
            }
            Text(
                text = message.ifBlank { when (state) {
                    PhoneLoginState.SENDING_CODE -> "正在发送验证码..."
                    PhoneLoginState.CODE_SENT -> "验证码已发送"
                    PhoneLoginState.LOGGING_IN -> "正在登录..."
                    else -> ""
                }},
                fontSize = 12.sp,
                color = msgColor,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "登录即表示同意《网易云音乐服务条款》\n仅支持已绑定网易云账号的手机号",
            fontSize = 10.sp, color = appTextTertiary(), textAlign = TextAlign.Center, lineHeight = 14.sp
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// 二维码扫码登录
// ─────────────────────────────────────────────────────────────────

@Composable
private fun QrLoginView(onLoginSuccess: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var unikey by remember { mutableStateOf<String?>(null) }
    var qrState by remember { mutableStateOf(QrLoginState.WAITING) }
    var generating by remember { mutableStateOf(true) }
    var retryCount by remember { mutableStateOf(0) }

    // 生成 key — 失败自动重试 3 次，每次间隔 1.5s
    LaunchedEffect(Unit, retryCount) {
        generating = true
        var key: String? = null
        repeat(3) {
            key = NetEaseAuth.createQrKey()
            if (key != null) return@repeat
            delay(1500)
        }
        unikey = key
        generating = false
    }

    // 轮询
    LaunchedEffect(unikey) {
        val key = unikey ?: return@LaunchedEffect
        qrState = QrLoginState.WAITING
        var errorRetry = 0
        while (qrState == QrLoginState.WAITING || qrState == QrLoginState.SCANNED) {
            val r = NetEaseAuth.pollQrStatus(key)
            qrState = r.state
            if (r.state == QrLoginState.CONFIRMED) {
                // 等待 fetchAccount 完成：成功才通知父组件，失败显示明确错误
                val account = NetEaseAuth.fetchAccount(context)
                if (account != null) {
                    onLoginSuccess()
                } else {
                    // cookie 没拿到，登录失败，显示错误而非"过期"
                    qrState = QrLoginState.ERROR
                }
                break
            }
            if (r.state == QrLoginState.EXPIRED) break
            if (r.state == QrLoginState.ERROR) {
                // 网络抖动重试2次，仍失败才退出循环显示错误
                errorRetry++
                if (errorRetry > 2) break
                qrState = QrLoginState.WAITING  // 继续轮询
            }
            delay(2000)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("网易云扫码登录", fontSize = 18.sp, color = appTextPrimary(), fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("用网易云音乐 App 扫描下方二维码", fontSize = 12.sp, color = appTextTertiary(), textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .size(220.dp)
                .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.15f)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                generating -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = FluidCyan, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("正在生成二维码…", fontSize = 11.sp, color = appTextTertiary())
                }
                unikey != null && (qrState == QrLoginState.WAITING || qrState == QrLoginState.SCANNED || qrState == QrLoginState.CONFIRMED) ->
                    QrImage(content = NetEaseAuth.qrContent(unikey!!))
                unikey == null && !generating -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.WifiOff, null, tint = AccentWarning, modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("生成失败，请检查网络", fontSize = 12.sp, color = appTextTertiary())
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(onClick = { retryCount++ }) {
                        Text("重试", color = FluidCyan)
                    }
                }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.QrCodeScanner, null, tint = appTextTertiary(), modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("二维码已过期", fontSize = 12.sp, color = appTextTertiary())
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        when (qrState) {
            QrLoginState.WAITING -> Text("等待扫码…", fontSize = 13.sp, color = appTextSecondary())
            QrLoginState.SCANNED -> Text("已扫码，请在手机上确认", fontSize = 13.sp, color = FluidCyan)
            QrLoginState.EXPIRED, QrLoginState.ERROR -> TextButton(onClick = {
                scope.launch {
                    qrState = QrLoginState.WAITING
                    generating = true
                    unikey = null
                    retryCount++
                }
            }) { Text("重新生成二维码", color = FluidCyan) }
            QrLoginState.CONFIRMED -> Text("登录成功", fontSize = 13.sp, color = AccentSuccess)
        }
    }
}

/** 用 ZXing 渲染登录二维码（复用 QRCodeScreen 的 encodeQrMatrix） */
@Composable
private fun QrImage(content: String) {
    var matrix by remember(content) { mutableStateOf<BitMatrix?>(null) }
    LaunchedEffect(content) {
        matrix = withContext(Dispatchers.Default) {
            try { encodeQrMatrix(content) } catch (_: Exception) { null }
        }
    }
    val m = matrix ?: return
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val w = m.width; val h = m.height
        val module = minOf(size.width, size.height) / maxOf(w, h).toFloat()
        val totalW = module * w; val totalH = module * h
        val offX = (size.width - totalW) / 2f; val offY = (size.height - totalH) / 2f
        drawRect(Color.White, androidx.compose.ui.geometry.Offset(offX, offY), androidx.compose.ui.geometry.Size(totalW, totalH))
        for (r in 0 until h) for (c in 0 until w) {
            if (m[c, r]) drawRect(Color.Black, androidx.compose.ui.geometry.Offset(offX + c * module, offY + r * module), androidx.compose.ui.geometry.Size(module, module))
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// 本地音乐 Tab
// ─────────────────────────────────────────────────────────────────

@Composable
private fun LocalTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
            else
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        )
    }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) scope.launch { loadLocal(context) { songs = it; loading = false } }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission && songs.isEmpty()) {
            loading = true
            val list = withContext(Dispatchers.IO) { LocalMusicScanner.scan(context) }
            songs = list; loading = false
        }
    }

    if (!hasPermission) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.LibraryMusic, null, tint = appTextTertiary(), modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text("需要音频访问权限", fontSize = 15.sp, color = appTextPrimary())
            Spacer(modifier = Modifier.height(4.dp))
            Text("用于扫描设备本地音乐文件", fontSize = 12.sp, color = appTextTertiary())
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
                    else Manifest.permission.READ_EXTERNAL_STORAGE
                    permLauncher.launch(perm)
                },
                colors = ButtonDefaults.buttonColors(containerColor = FluidCyan.copy(alpha = 0.2f), contentColor = FluidCyan)
            ) { Text("授予权限") }
        }
        return
    }

    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FluidCyan, strokeWidth = 2.dp)
        }
        return
    }

    if (songs.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.MusicOff, null, tint = appTextTertiary(), modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text("设备上没有找到音乐", fontSize = 14.sp, color = appTextSecondary())
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = {
                scope.launch { loading = true; songs = withContext(Dispatchers.IO) { LocalMusicScanner.scan(context) }; loading = false }
            }) { Text("重新扫描", color = FluidCyan) }
        }
        return
    }

    SongList(
        songs = songs,
        onPlay = { idx -> MusicControllerManager.playSongs(context, songs, idx) },
        onAddToQueue = { song ->
            MusicControllerManager.addToQueue(context, song)
            android.widget.Toast.makeText(context, "已加入播放队列", android.widget.Toast.LENGTH_SHORT).show()
        }
    )
}

private suspend fun loadLocal(context: android.content.Context, onDone: (List<Song>) -> Unit) {
    val list = withContext(Dispatchers.IO) { LocalMusicScanner.scan(context) }
    onDone(list)
}

// ─────────────────────────────────────────────────────────────────
// 平台跳转 Tab：酷狗 / 汽水 / QQ音乐
// ─────────────────────────────────────────────────────────────────

private data class PlatformApp(val name: String, val pkg: String, val webUrl: String, val colors: List<Color>)

@Composable
private fun PlatformTab() {
    val context = LocalContext.current
    val platforms = remember {
        listOf(
            PlatformApp("酷狗音乐", "com.kugou.android", "https://www.kugou.com/", listOf(FluidBlue, FluidCyan)),
            PlatformApp("汽水音乐", "com.luna.music", "https://sf3-cdn-tos.douyinstatic.com/obj/sf3-cdn-tos-static-static/qishui/index.html", listOf(FluidOrange, FluidPink)),
            PlatformApp("QQ音乐", "com.tencent.qqmusic", "https://y.qq.com/", listOf(FluidTeal, FluidPurple))
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "由于这三家无公开播放接口，提供一键跳转官方 App 入口。已安装直接打开，未安装跳网页版。",
            fontSize = 11.sp, color = appTextTertiary(), lineHeight = 16.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        platforms.forEach { p ->
            // 双重检测：getPackageInfo + getLaunchIntentForPackage。
            // manifest 已声明 <queries>，Android 11+ 也能正确返回。
            val installed = remember(p.pkg) {
                try {
                    context.packageManager.getPackageInfo(p.pkg, 0)
                    context.packageManager.getLaunchIntentForPackage(p.pkg) != null
                } catch (_: Exception) { false }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 18.dp, glassAlpha = 0.10f)
                    .clickable {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(p.pkg)
                        val intent = if (installed && launchIntent != null) {
                            launchIntent
                        } else {
                            Intent(Intent.ACTION_VIEW, Uri.parse(p.webUrl))
                        }
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            // 极端情况：launchIntent 解析失败，回退网页
                            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(p.webUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try { context.startActivity(webIntent) } catch (_: Exception) {}
                        }
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(p.colors)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(p.name.first().toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(p.name, fontSize = 15.sp, color = appTextPrimary(), fontWeight = FontWeight.Medium)
                    Text(if (installed) "已安装，点击打开" else "未安装，点击打开网页版", fontSize = 11.sp, color = appTextTertiary())
                }
                Icon(Icons.Default.ChevronRight, null, tint = appTextTertiary())
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// 发现 Tab：推荐歌曲 / 推荐歌单 / 排行榜（无需登录的公开内容）
// ─────────────────────────────────────────────────────────────────

@Composable
private fun DiscoverTab(
    onOpenPlaylist: (Playlist) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit
) {
    val context = LocalContext.current
    var recommendSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var recommendPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var toplists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var newSongsData by remember { mutableStateOf<List<Song>>(emptyList()) }
    var newAlbumsData by remember { mutableStateOf<List<NetEaseApi.AlbumInfo>>(emptyList()) }
    var mvData by remember { mutableStateOf<List<NetEaseApi.MvInfo>>(emptyList()) }
    var djData by remember { mutableStateOf<List<NetEaseApi.DjProgram>>(emptyList()) }
    var fmData by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loadingSongs by remember { mutableStateOf(true) }
    var loadingPlaylists by remember { mutableStateOf(true) }
    var loadingCharts by remember { mutableStateOf(true) }
    var loadingNew by remember { mutableStateOf(true) }
    var loadingExtra by remember { mutableStateOf(true) }

    // 所有公开接口并行拉取（无需登录）
    LaunchedEffect(Unit) {
        loadingSongs = true
        recommendSongs = withContext(Dispatchers.IO) { NetEaseApi.recommendSongs() }
        loadingSongs = false
    }
    LaunchedEffect(Unit) {
        loadingPlaylists = true
        recommendPlaylists = withContext(Dispatchers.IO) { NetEaseApi.recommendPlaylists() }
        loadingPlaylists = false
    }
    LaunchedEffect(Unit) {
        loadingCharts = true
        toplists = withContext(Dispatchers.IO) { NetEaseApi.toplist() }
        loadingCharts = false
    }
    // 新歌速递 + 私人FM
    LaunchedEffect(Unit) {
        loadingNew = true
        kotlinx.coroutines.coroutineScope {
            launch { newSongsData = NetEaseApi.newSongs(0) }
            launch { fmData = NetEaseApi.personalFm() }
        }
        loadingNew = false
    }
    // 新碟 + MV + DJ电台
    LaunchedEffect(Unit) {
        loadingExtra = true
        kotlinx.coroutines.coroutineScope {
            launch { newAlbumsData = NetEaseApi.newAlbums() }
            launch { mvData = NetEaseApi.recommendMv() }
            launch { djData = NetEaseApi.recommendDj() }
        }
        loadingExtra = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 推荐歌曲 ──
        item {
            SectionHeader(
                icon = { Icon(Icons.Default.Recommend, null, tint = FluidCyan, modifier = Modifier.size(18.dp)) },
                title = "推荐歌曲",
                subtitle = "每日推荐"
            )
        }
        item {
            when {
                loadingSongs -> SectionLoading()
                recommendSongs.isEmpty() -> SectionEmpty("暂无推荐歌曲")
                else -> LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    items(recommendSongs, key = { it.id }) { song ->
                        RecommendSongCard(
                            song = song,
                            onClick = { onPlaySongs(recommendSongs, recommendSongs.indexOf(song)) },
                            onAddToQueue = {
                                MusicControllerManager.addToQueue(context, song)
                                android.widget.Toast.makeText(context, "已加入播放队列", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }

        // ── 私人FM ──
        if (!loadingNew && fmData.isNotEmpty()) {
            item {
                SectionHeader(
                    icon = { Icon(Icons.Default.Radio, null, tint = FluidPink, modifier = Modifier.size(18.dp)) },
                    title = "私人FM",
                    subtitle = "红心电台"
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                    items(fmData.take(10), key = { "fm_${it.id}" }) { song ->
                        RecommendSongCard(
                            song = song,
                            onClick = { onPlaySongs(fmData, fmData.indexOf(song)) },
                            onAddToQueue = {
                                MusicControllerManager.addToQueue(context, song)
                                android.widget.Toast.makeText(context, "已加入播放队列", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }

        // ── 新歌速递 ──
        if (!loadingNew && newSongsData.isNotEmpty()) {
            item {
                SectionHeader(
                    icon = { Icon(Icons.Default.NewReleases, null, tint = FluidTeal, modifier = Modifier.size(18.dp)) },
                    title = "新歌速递",
                    subtitle = "最新上架"
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                    items(newSongsData.take(12), key = { "new_${it.id}" }) { song ->
                        RecommendSongCard(
                            song = song,
                            onClick = { onPlaySongs(newSongsData, newSongsData.indexOf(song)) },
                            onAddToQueue = {
                                MusicControllerManager.addToQueue(context, song)
                                android.widget.Toast.makeText(context, "已加入播放队列", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }

        // ── 推荐歌单 ──
        item {
            SectionHeader(
                icon = { Icon(Icons.Default.QueueMusic, null, tint = FluidPurple, modifier = Modifier.size(18.dp)) },
                title = "推荐歌单",
                subtitle = "精选歌单"
            )
        }
        item {
            when {
                loadingPlaylists -> SectionLoading()
                recommendPlaylists.isEmpty() -> SectionEmpty("暂无推荐歌单")
                else -> LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    items(recommendPlaylists, key = { it.id }) { p ->
                        RecommendPlaylistCard(playlist = p, onClick = { onOpenPlaylist(p) })
                    }
                }
            }
        }

        // ── 新碟上架 ──
        if (!loadingExtra && newAlbumsData.isNotEmpty()) {
            item {
                SectionHeader(
                    icon = { Icon(Icons.Default.Album, null, tint = FluidBlue, modifier = Modifier.size(18.dp)) },
                    title = "新碟上架",
                    subtitle = "最新专辑"
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                    items(newAlbumsData.take(12), key = { "alb_${it.id}" }) { album ->
                        Column(modifier = Modifier.width(96.dp)) {
                            AsyncImage(
                                model = album.coverUrl,
                                contentDescription = null,
                                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp))
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(album.name, fontSize = 11.sp, color = appTextSecondary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(album.artist, fontSize = 10.sp, color = appTextTertiary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // ── 排行榜 ──
        item {
            SectionHeader(
                icon = { Icon(Icons.Default.Leaderboard, null, tint = FluidOrange, modifier = Modifier.size(18.dp)) },
                title = "排行榜",
                subtitle = "热门榜单"
            )
        }
        if (loadingCharts) {
            item { SectionLoading() }
        } else if (toplists.isEmpty()) {
            item { SectionEmpty("暂无榜单数据") }
        } else {
            items(toplists, key = { it.id }) { chart ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.08f)
                        .clickable {
                            // 榜单本质是歌单，用榜单 id 打开歌单详情
                            onOpenPlaylist(
                                Playlist(
                                    id = chart.id,
                                    name = chart.name,
                                    coverUrl = chart.coverUrl,
                                    trackCount = chart.trackCount,
                                    creator = chart.creator
                                )
                            )
                        }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = chart.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(chart.name, fontSize = 14.sp, color = appTextPrimary(), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (chart.creator.isNotBlank()) chart.creator else "${chart.trackCount}首",
                            fontSize = 11.sp,
                            color = appTextTertiary(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = appTextTertiary())
                }
            }
        }

        // ── 推荐MV ──
        if (!loadingExtra && mvData.isNotEmpty()) {
            item {
                SectionHeader(
                    icon = { Icon(Icons.Default.MusicVideo, null, tint = FluidPink, modifier = Modifier.size(18.dp)) },
                    title = "推荐MV",
                    subtitle = "精选音乐视频"
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                    items(mvData.take(10), key = { "mv_${it.id}" }) { mv ->
                        Column(modifier = Modifier.width(128.dp)) {
                            AsyncImage(
                                model = mv.coverUrl,
                                contentDescription = null,
                                modifier = Modifier.size(128.dp, 72.dp).clip(RoundedCornerShape(10.dp))
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(mv.name, fontSize = 11.sp, color = appTextSecondary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(mv.artist, fontSize = 10.sp, color = appTextTertiary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // ── DJ电台 ──
        if (!loadingExtra && djData.isNotEmpty()) {
            item {
                SectionHeader(
                    icon = { Icon(Icons.Default.Podcasts, null, tint = FluidTeal, modifier = Modifier.size(18.dp)) },
                    title = "DJ电台",
                    subtitle = "精选电台节目"
                )
            }
            items(djData.take(8), key = { "dj_${it.id}" }) { dj ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.08f)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = dj.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(dj.name, fontSize = 13.sp, color = appTextPrimary(), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("主播: ${dj.djName}", fontSize = 11.sp, color = appTextTertiary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${dj.listenerCount}订阅", fontSize = 10.sp, color = appTextTertiary())
                }
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

/** 发现页区块标题：图标 + 标题 + 副标题 */
@Composable
private fun SectionHeader(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String = ""
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(6.dp))
        Text(title, fontSize = 15.sp, color = appTextPrimary(), fontWeight = FontWeight.Medium)
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(subtitle, fontSize = 11.sp, color = appTextTertiary())
        }
    }
}

/** 发现页区块加载中占位 */
@Composable
private fun SectionLoading() {
    Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = FluidCyan, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

/** 发现页区块空态占位 */
@Composable
private fun SectionEmpty(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 12.sp, color = appTextTertiary())
    }
}

/** 推荐歌曲卡片：封面 + 标题 + 艺人，点击播放，附带加入队列按钮 */
@Composable
private fun RecommendSongCard(
    song: Song,
    onClick: () -> Unit,
    onAddToQueue: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(112.dp)
            .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.08f)
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Box {
            AsyncImage(
                model = song.coverUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
            )
            // VIP 标记
            if (song.isVipOnly) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Brush.linearGradient(listOf(FluidOrange, FluidPink)))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) { Text("VIP", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold) }
            }
            // 加入队列悬浮按钮
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(FluidCyan, FluidPurple)))
                    .clickable { onAddToQueue() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlaylistAdd, "加入播放队列", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(song.title, fontSize = 12.sp, color = appTextPrimary(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        Text(song.artist, fontSize = 10.sp, color = appTextTertiary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 推荐歌单卡片：封面 + 名称 + 创建者 */
@Composable
private fun RecommendPlaylistCard(playlist: Playlist, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(112.dp)
            .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.08f)
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        AsyncImage(
            model = playlist.coverUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
        )
        Spacer(Modifier.height(6.dp))
        Text(playlist.name, fontSize = 12.sp, color = appTextPrimary(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        Text(
            if (playlist.creator.isNotBlank()) playlist.creator else "${playlist.trackCount}首",
            fontSize = 10.sp,
            color = appTextTertiary(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// 搜索页
// ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchPage(
    initialKeyword: String,
    onKeywordChange: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf(initialKeyword) }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    // 触发搜索的版本号：每次按搜索键自增，驱动 LaunchedEffect
    var searchTrigger by remember { mutableStateOf(0) }
    // 搜索历史 + 热搜词（未搜索时展示）
    var history by remember { mutableStateOf<List<String>>(emptyList()) }
    var hotWords by remember { mutableStateOf<List<String>>(emptyList()) }

    // 分页加载状态：offset/total/loadingMore 驱动滚动到底自动加载下一页
    var offset by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var loadingMore by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val playback by MusicControllerManager.state.collectAsState()
    val currentId = playback.song?.id

    // 进入搜索页：加载历史 + 热搜
    LaunchedEffect(Unit) {
        history = SearchHistoryStore.load(context)
        hotWords = withContext(Dispatchers.IO) { NetEaseApi.hotSearch() }
    }

    // 用指定词搜索：填入框 + 触发
    fun doSearch(k: String) {
        keyword = k; onKeywordChange(k)
        searchTrigger++
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // 搜索框
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.12f)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, null, tint = appTextTertiary(), modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = keyword,
                onValueChange = { keyword = it; onKeywordChange(it) },
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                textStyle = TextStyle(color = appTextPrimary(), fontSize = 15.sp),
                cursorBrush = SolidColor(FluidCyan),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (keyword.isNotBlank()) searchTrigger++
                }),
                decorationBox = { inner ->
                    if (keyword.isEmpty()) Text("搜索歌曲/歌手", color = appTextTertiary(), fontSize = 15.sp)
                    inner()
                }
            )
            if (keyword.isNotEmpty()) {
                IconButton(onClick = { keyword = ""; results = emptyList(); searched = false; total = 0; offset = 0 }) {
                    Icon(Icons.Default.Close, null, tint = appTextTertiary(), modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // 搜索触发：观察 searchTrigger，避免每次输入都打接口
        LaunchedEffect(searchTrigger) {
            if (searchTrigger > 0 && keyword.isNotBlank()) {
                searching = true; searched = true
                offset = 0; results = emptyList()
                val r = withContext(Dispatchers.IO) { NetEaseApi.search(keyword) }
                results = r.songs
                total = r.total
                offset = r.songs.size
                searching = false
                // 有结果才记入历史（避免空搜索污染历史）
                if (r.songs.isNotEmpty()) {
                    SearchHistoryStore.record(context, keyword)
                    history = SearchHistoryStore.load(context)
                }
            }
        }

        when {
            searching -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = FluidCyan, strokeWidth = 2.dp)
            }
            searched && results.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.SearchOff, null, tint = appTextTertiary(), modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text("没有找到结果", fontSize = 13.sp, color = appTextTertiary())
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { searched = false }) { Text("返回推荐", color = FluidCyan, fontSize = 12.sp) }
            }
            searched -> {
                // 字母分组：按艺人首字母（A-Z + #），中文/非英文字符归 #
                val groups = remember(results) {
                    results.withIndex()
                        .groupBy { it.value.artist.firstOrNull()?.uppercaseChar()?.takeIf { c -> c in 'A'..'Z' } ?: '#' }
                        .map { it.key to it.value.first().index }
                }
                val firstVisibleIndex = listState.firstVisibleItemIndex
                val activeLetter = remember(firstVisibleIndex, groups) {
                    groups.lastOrNull { it.second <= firstVisibleIndex }?.first
                }

                // 滚动到底自动加载下一页：观察最后一个可见 item 索引
                LaunchedEffect(
                    listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index,
                    results.size,
                    total,
                    loadingMore
                ) {
                    if (loadingMore || total == 0 || results.size >= total) return@LaunchedEffect
                    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                        ?: return@LaunchedEffect
                    if (lastVisible >= results.size - 3) {
                        loadingMore = true
                        val newOffset = offset + 30
                        val r = withContext(Dispatchers.IO) { NetEaseApi.search(keyword, limit = 30, offset = newOffset) }
                        results = results + r.songs
                        offset = newOffset
                        loadingMore = false
                    }
                }

                // 叠加布局：列表 + 右侧字母导航 + 右下角返回顶部
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(results, key = { _, s -> s.id }) { idx, s ->
                            SongRow(
                                song = s,
                                isCurrent = s.id == currentId,
                                isPlaying = playback.isPlaying,
                                onPlay = { MusicControllerManager.playSongs(context, results, idx) },
                                onAddToQueue = {
                                    MusicControllerManager.addToQueue(context, s)
                                    android.widget.Toast.makeText(context, "已加入播放队列", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                when {
                                    loadingMore -> Text("加载中…", fontSize = 11.sp, color = appTextTertiary())
                                    results.size >= total -> Text("已全部加载（共 ${results.size} 首）", fontSize = 11.sp, color = appTextTertiary())
                                    else -> Text("上拉加载更多", fontSize = 11.sp, color = appTextTertiary())
                                }
                            }
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }

                    // 右侧字母导航条：点击跳转到对应分组起始位置
                    if (groups.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 2.dp)
                                .width(16.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            groups.forEach { (letter, startIndex) ->
                                Text(
                                    letter.toString(),
                                    fontSize = 9.sp,
                                    color = if (letter == activeLetter) FluidCyan else appTextTertiary(),
                                    fontWeight = if (letter == activeLetter) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.clickable {
                                        scope.launch { listState.animateScrollToItem(startIndex) }
                                    }
                                )
                            }
                        }
                    }

                    // 返回顶部按钮：滚动超过 5 项时显示，带淡入淡出
                    // 用 Column 包一层让 AnimatedVisibility 解析到 ColumnScope 重载
                    Column(modifier = Modifier.align(Alignment.BottomEnd)) {
                        AnimatedVisibility(
                            visible = firstVisibleIndex > 5,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.20f)
                                    .clickable { scope.launch { listState.animateScrollToItem(0) } },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.VerticalAlignTop, "返回顶部", tint = appTextPrimary(), modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
            else -> SearchSuggestionPanel(
                history = history,
                hotWords = hotWords,
                onTap = { doSearch(it) },
                onClearHistory = {
                    SearchHistoryStore.clear(context)
                    history = emptyList()
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// 搜索推荐面板：搜索历史 + 热搜词（未触发搜索时展示）
// ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchSuggestionPanel(
    history: List<String>,
    hotWords: List<String>,
    onTap: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 搜索历史
        if (history.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.History, null, tint = appTextSecondary(), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("搜索历史", fontSize = 13.sp, color = appTextSecondary(), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearHistory, contentPadding = PaddingValues(0.dp)) {
                        Text("清空", fontSize = 11.sp, color = AccentDanger)
                    }
                }
            }
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    history.forEach { word ->
                        SearchChip(word, onClick = { onTap(word) }, leading = {
                            Icon(Icons.Default.Schedule, null, tint = appTextTertiary(), modifier = Modifier.size(12.dp))
                        })
                    }
                }
            }
        }

        // 热搜词
        if (hotWords.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.LocalFireDepartment, null, tint = FluidOrange, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("热搜榜", fontSize = 13.sp, color = appTextSecondary(), fontWeight = FontWeight.Medium)
                }
            }
            itemsIndexed(hotWords) { idx, word ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onTap(word) }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 排名标号：前三名高亮
                    val rankColor = when (idx) {
                        0 -> FluidOrange
                        1 -> FluidPink
                        2 -> FluidPurple
                        else -> appTextTertiary()
                    }
                    Text("${idx + 1}", fontSize = 13.sp, color = rankColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                    Text(word, fontSize = 14.sp, color = appTextPrimary(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (idx < 3) Icon(Icons.Default.Whatshot, null, tint = FluidOrange.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                }
            }
        }

        if (history.isEmpty() && hotWords.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text("输入歌曲或歌手名开始搜索", fontSize = 13.sp, color = appTextTertiary())
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

/** 搜索词胶囊 */
@Composable
private fun SearchChip(text: String, onClick: () -> Unit, leading: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(appTextTertiary().copy(alpha = 0.08f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(4.dp))
        }
        Text(text, fontSize = 13.sp, color = appTextPrimary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ─────────────────────────────────────────────────────────────────
// 歌单详情页
// ─────────────────────────────────────────────────────────────────

@Composable
private fun PlaylistDetailPage(playlist: Playlist?) {
    val context = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(playlist?.id) {
        if (playlist == null) return@LaunchedEffect
        loading = true
        songs = withContext(Dispatchers.IO) { NetEaseApi.playlistTracks(playlist.id) }
        loading = false
    }

    if (playlist == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("歌单加载失败", color = appTextTertiary()) }
        return
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(color = FluidCyan, strokeWidth = 2.dp)
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = playlist.coverUrl,
                contentDescription = null,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.name, fontSize = 16.sp, color = appTextPrimary(), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${songs.size}首", fontSize = 12.sp, color = appTextTertiary())
            }
            Button(
                onClick = { if (songs.isNotEmpty()) MusicControllerManager.playSongs(context, songs, 0) },
                colors = ButtonDefaults.buttonColors(containerColor = FluidCyan.copy(alpha = 0.2f), contentColor = FluidCyan)
            ) { Text("播放全部", fontSize = 13.sp) }
        }
        SongList(
            songs = songs,
            onPlay = { idx -> MusicControllerManager.playSongs(context, songs, idx) },
            onAddToQueue = { song ->
                MusicControllerManager.addToQueue(context, song)
                android.widget.Toast.makeText(context, "已加入播放队列", android.widget.Toast.LENGTH_SHORT).show()
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// 通用歌曲列表
// ─────────────────────────────────────────────────────────────────

@Composable
private fun SongList(
    songs: List<Song>,
    onPlay: (Int) -> Unit,
    onAddToQueue: (Song) -> Unit = {}
) {
    val playback by MusicControllerManager.state.collectAsState()
    val currentId = playback.song?.id
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(songs) { idx, s ->
            SongRow(
                song = s,
                isCurrent = s.id == currentId,
                isPlaying = playback.isPlaying,
                onPlay = { onPlay(idx) },
                onAddToQueue = { onAddToQueue(s) }
            )
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────
// 迷你播放条
// ─────────────────────────────────────────────────────────────────

@Composable
private fun MiniPlayerBar(
    state: MusicControllerManager.PlaybackState,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    val song = state.song ?: return
    // 控制台状态栏：实时拉取当前歌词行，配合动画均衡器与状态指示
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(song.id) {
        lyrics = null
        if (song.source == Source.NETEASE) {
            lyrics = withContext(Dispatchers.IO) { NetEaseApi.lyrics(song.id) }
            // 同步缓存到 MusicControllerManager，供 MusicService 实时计算歌词通知
            lyrics?.let { MusicControllerManager.setLyrics(song.id, it) }
        }
    }
    // 当前流动歌词行（按 positionMs 实时计算）
    val flowingLine = remember(lyrics, state.positionMs) {
        val l = lyrics ?: return@remember ""
        if (l.hasYrc) {
            var active: YrcLine? = null
            for (line in l.yrcLines) {
                if (line.startMs <= state.positionMs) active = line else break
            }
            active?.chars?.joinToString("") { it.content } ?: ""
        } else {
            var active: LyricLine? = null
            for (line in l.lrcLines) {
                if (line.timeMs <= state.positionMs) active = line else break
            }
            active?.content?.takeIf { it.isNotBlank() && it != "纯音乐，请欣赏" } ?: ""
        }
    }
    // 状态文本
    val statusText = when {
        state.isBuffering -> "缓冲中…"
        state.isPlaying -> "正在播放 · ${formatMs(state.positionMs)} / ${formatMs(state.durationMs)}"
        else -> "已暂停"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .glassSurface(cornerRadius = 18.dp, glassAlpha = 0.20f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 封面 + 动画均衡器叠加（状态指示）
            Box(modifier = Modifier.size(44.dp)) {
                AsyncImage(
                    model = song.coverUrl.ifBlank { song.coverUri },
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                )
                // 左下角均衡器小灯：播放时跳动，缓冲时脉冲，暂停时静止
                EqualizerIndicator(
                    isPlaying = state.isPlaying,
                    isBuffering = state.isBuffering,
                    modifier = Modifier.align(Alignment.BottomStart).padding(2.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, fontSize = 13.sp, color = appTextPrimary(), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (flowingLine.isNotBlank()) {
                    // 流动歌词：marquee 滚动，液态玻璃水滴状高亮
                    Text(
                        text = flowingLine,
                        fontSize = 11.sp,
                        color = FluidCyan,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth().basicMarquee(
                            repeatDelayMillis = 800,
                            initialDelayMillis = 300
                        )
                    )
                } else {
                    Text(song.artist, fontSize = 11.sp, color = appTextTertiary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(statusText, fontSize = 9.sp, color = appTextTertiary(), maxLines = 1)
            }
            IconButton(onClick = { onPlayPause() }, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null, tint = appTextPrimary(), modifier = Modifier.size(22.dp)
                )
            }
            IconButton(onClick = { onNext() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.SkipNext, null, tint = appTextPrimary(), modifier = Modifier.size(22.dp))
            }
        }
    }
}

/**
 * 动画均衡器指示器：4 根竖条，播放时各自不同频率上下跳动（液态玻璃水滴状）。
 * - 播放：4 根条独立 animateFloatAsState，高度随机化营造频谱感
 * - 缓冲：整体脉冲（透明度呼吸）
 * - 暂停：3 根等高静止条
 */
@Composable
private fun EqualizerIndicator(
    isPlaying: Boolean,
    isBuffering: Boolean,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "eq")
    // 4 根条各自的相位偏移，营造频谱跳动感
    val barCount = 4
    val phases = listOf(0L, 130L, 250L, 90L)
    val heights = (0 until barCount).map { i ->
        infinite.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 320, delayMillis = phases[i].toInt(), easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "eqBar$i"
        )
    }
    // 缓冲时透明度呼吸
    val breathAnim = infinite.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(700), repeatMode = RepeatMode.Reverse),
        label = "eqBreath"
    )
    val breathAlpha = if (isBuffering) breathAnim.value else 1f

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 3.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (i in 0 until barCount) {
            val h = if (isPlaying) heights[i].value else if (isBuffering) 0.6f else 0.4f
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height((8 * h).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        if (isPlaying) Brush.verticalGradient(listOf(FluidCyan, FluidPurple))
                        else Brush.verticalGradient(listOf(appTextTertiary(), appTextTertiary()))
                    )
                    .alpha(breathAlpha)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// 全屏 Now Playing：封面 + 进度 + 控制按钮 + 歌词切换
// ─────────────────────────────────────────────────────────────────

@Composable
private fun NowPlayingPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val playback by MusicControllerManager.state.collectAsState()
    val song = playback.song

    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    var showLyrics by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var seekPos by remember { mutableStateOf(0f) }

    // 切歌时拉歌词
    LaunchedEffect(song?.id) {
        lyrics = null
        if (song != null && song.source == Source.NETEASE) {
            lyrics = withContext(Dispatchers.IO) { NetEaseApi.lyrics(song.id) }
            // 同步缓存到 MusicControllerManager，供 MusicService 实时计算歌词通知
            lyrics?.let { MusicControllerManager.setLyrics(song.id, it) }
        }
    }

    if (song == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("没有正在播放的歌曲", color = appTextTertiary(), fontSize = 14.sp)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // 封面 / 歌词切换
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (showLyrics && lyrics != null) {
                LyricsView(
                    lyrics = lyrics!!,
                    positionMs = playback.positionMs,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(song.coverUrl.ifBlank { song.coverUri }).crossfade(true).build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
        }

        // 切换封面/歌词按钮
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !showLyrics,
                onClick = { showLyrics = false },
                label = { Text("封面", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = FluidCyan.copy(alpha = 0.2f),
                    selectedLabelColor = FluidCyan
                )
            )
            FilterChip(
                selected = showLyrics,
                onClick = { showLyrics = true },
                label = { Text("歌词", fontSize = 11.sp) },
                enabled = lyrics != null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = FluidCyan.copy(alpha = 0.2f),
                    selectedLabelColor = FluidCyan
                )
            )
        }

        // 标题 / 艺人
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(song.title, fontSize = 18.sp, color = appTextPrimary(), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (song.isVipOnly) {
                    Spacer(Modifier.width(6.dp))
                    Text("VIP", fontSize = 9.sp, color = Color.White, modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(Brush.linearGradient(listOf(FluidOrange, FluidPink)))
                        .padding(horizontal = 4.dp, vertical = 1.dp))
                }
            }
            Text(song.artist, fontSize = 13.sp, color = appTextTertiary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // 进度条
        val duration = playback.durationMs.coerceAtLeast(1L)
        val progress = if (seeking) seekPos else (playback.positionMs.toFloat() / duration)
        Slider(
            value = progress.coerceIn(0f, 1f),
            onValueChange = { seeking = true; seekPos = it },
            onValueChangeFinished = {
                MusicControllerManager.seekTo((seekPos * duration).toLong())
                seeking = false
            },
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = FluidCyan,
                activeTrackColor = FluidCyan,
                inactiveTrackColor = appTextTertiary().copy(alpha = 0.3f)
            )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(playback.positionMs), fontSize = 11.sp, color = appTextTertiary())
            Text(formatMs(playback.durationMs), fontSize = 11.sp, color = appTextTertiary())
        }

        // 控制按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 循环模式：OFF(不亮) → ONE(亮·RepeatOne) → ALL(亮·Repeat)
            IconButton(onClick = { MusicControllerManager.cycleRepeatMode() }, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (playback.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne
                    else Icons.Default.Repeat,
                    null,
                    tint = if (playback.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) FluidCyan else appTextTertiary(),
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = { MusicControllerManager.previous() }, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Default.SkipPrevious, null, tint = appTextPrimary(), modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.width(20.dp))
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(FluidCyan, FluidPurple)))
                    .clickable { MusicControllerManager.playPause() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null, tint = Color.White, modifier = Modifier.size(34.dp)
                )
            }
            Spacer(Modifier.width(20.dp))
            IconButton(onClick = { MusicControllerManager.next() }, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Default.SkipNext, null, tint = appTextPrimary(), modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.width(12.dp))
            // 随机播放
            IconButton(onClick = { MusicControllerManager.toggleShuffle() }, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.Shuffle, null,
                    tint = if (playback.shuffle) FluidCyan else appTextTertiary(),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ─────────────────────────────────────────────────────────────────
// 播放队列管理：查看/上下移/删除/清空/跳播
// ─────────────────────────────────────────────────────────────────

@Composable
private fun QueuePage(onBack: () -> Unit) {
    val playback by MusicControllerManager.state.collectAsState()
    val queue = playback.queue
    val currentIdx = playback.currentIndex

    if (queue.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.QueueMusic, null, tint = appTextTertiary(), modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("播放队列为空", fontSize = 14.sp, color = appTextSecondary())
            Spacer(Modifier.height(4.dp))
            Text("从搜索、歌单或本地音乐中播放即可加入队列", fontSize = 11.sp, color = appTextTertiary(), textAlign = TextAlign.Center)
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 头部：总数 + 清空
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("共 ${queue.size} 首", fontSize = 13.sp, color = appTextSecondary(), modifier = Modifier.weight(1f))
            TextButton(onClick = {
                MusicControllerManager.clearQueue()
                onBack()
            }) { Text("清空", fontSize = 12.sp, color = AccentDanger) }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(queue, key = { idx, song -> "$idx-${song.id}" }) { idx, s ->
                val isCurrent = idx == currentIdx
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .then(if (isCurrent) Modifier.background(FluidCyan.copy(alpha = 0.10f)) else Modifier)
                        .clickable { MusicControllerManager.playQueueItemAt(idx) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 序号/播放指示
                    Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        if (isCurrent) {
                            Icon(
                                if (playback.isPlaying) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
                                null, tint = FluidCyan, modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Text("${idx + 1}", fontSize = 12.sp, color = appTextTertiary())
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    AsyncImage(
                        model = s.coverUrl.ifBlank { s.coverUri },
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.title,
                            fontSize = 13.sp,
                            color = if (isCurrent) FluidCyan else appTextPrimary(),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(s.artist, fontSize = 10.sp, color = appTextTertiary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // 上移
                    IconButton(
                        onClick = { MusicControllerManager.moveQueueUp(idx) },
                        enabled = idx > 0,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, "上移", tint = if (idx > 0) appTextSecondary() else appTextTertiary().copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
                    }
                    // 下移
                    IconButton(
                        onClick = { MusicControllerManager.moveQueueDown(idx) },
                        enabled = idx < queue.lastIndex,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, "下移", tint = if (idx < queue.lastIndex) appTextSecondary() else appTextTertiary().copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
                    }
                    // 删除
                    IconButton(
                        onClick = { MusicControllerManager.removeQueueItem(idx) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, "移除", tint = AccentDanger, modifier = Modifier.size(18.dp))
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

// ─────────────────────────────────────────────────────────────────
// 歌词视图：逐字 yrc 优先，回退逐行 lrc
// ─────────────────────────────────────────────────────────────────

@Composable
private fun LyricsView(
    lyrics: Lyrics,
    positionMs: Long,
    modifier: Modifier = Modifier
) {
    // 应用时间偏移（提前/延后），其余设置（字体/颜色/字号/行距/翻译/背景）在子视图内读取
    val adjusted = LyricsSettings.adjustedPosition(positionMs)
    if (lyrics.hasYrc) {
        YrcLyricsView(lyrics.yrcLines, adjusted, modifier)
    } else {
        LrcLyricsView(lyrics.lrcLines, adjusted, modifier)
    }
}

/** 逐字歌词：逐行渲染，每行内按 YrcChar 时间戳逐字平滑高亮 + 距离模糊渐变 */
@Composable
private fun YrcLyricsView(lines: List<YrcLine>, positionMs: Long, modifier: Modifier = Modifier) {
    // 当前活动行：最后一个 startMs <= position 的行
    val activeIndex = remember(positionMs, lines) {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].startMs <= positionMs) idx = i else break
        }
        idx
    }
    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            // 滚动让活动行居中
            listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
        }
    }
    val activeColor = LyricsSettings.themeColor.color
    val inactiveColor = appTextTertiary()
    val activeDimColor = appTextPrimary()   // 活动行内尚未唱到的字（比 inactiveColor 亮）
    val family = LyricsSettings.fontFamily.family
    val baseFontSize = LyricsSettings.fontSize

    LazyColumn(
        state = listState,
        modifier = modifier.background(Color.Black.copy(alpha = LyricsSettings.bgOpacity)),
        contentPadding = PaddingValues(vertical = 80.dp),
        verticalArrangement = Arrangement.spacedBy(LyricsSettings.lineSpacing.dp)
    ) {
        itemsIndexed(lines) { i, line ->
            val isActive = i == activeIndex
            val distance = kotlin.math.abs(i - activeIndex)
            // 距离模糊渐变：活动行全亮，相邻行递减透明度，营造聚焦感
            val distAlpha = when (distance) {
                0 -> 1f
                1 -> 0.5f
                2 -> 0.3f
                else -> 0.18f
            }
            // 活动行缩放动画
            val scale by animateFloatAsState(
                targetValue = if (isActive) 1f else 0.92f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                label = "yrcScale"
            )
            // 活动行字号动画
            val fontSize by animateFloatAsState(
                targetValue = if (isActive) baseFontSize else (baseFontSize - 4f).coerceAtLeast(11f),
                animationSpec = tween(300),
                label = "yrcFont"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(distAlpha)
                    .scale(scale)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                // 逐字渲染：每个 YrcChar 一个 Text 段，按演唱进度平滑着色
                line.chars.forEach { ch ->
                    val end = ch.startMs + ch.durationMs
                    val color = when {
                        !isActive -> inactiveColor
                        positionMs >= end -> activeColor                              // 已唱完：全亮
                        positionMs < ch.startMs -> activeDimColor                     // 未开始：暗
                        else -> {                                                     // 正在唱：渐变
                            val frac = ((positionMs - ch.startMs).toFloat() / ch.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
                            lerp(activeDimColor, activeColor, frac)
                        }
                    }
                    Text(
                        ch.content,
                        color = color,
                        fontSize = fontSize.sp,
                        fontFamily = family,
                        fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                }
            }
            if (line.translation.isNotBlank() && isActive && LyricsSettings.showTranslation) {
                Text(
                    line.translation,
                    color = appTextTertiary(),
                    fontSize = 12.sp,
                    fontFamily = family,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                )
            }
        }
    }
}

/** 逐行歌词：活动行高亮 + 距离模糊渐变 + 自动滚动居中 */
@Composable
private fun LrcLyricsView(lines: List<LyricLine>, positionMs: Long, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) {
        Box(modifier, Alignment.Center) {
            Text("暂无歌词", color = appTextTertiary(), fontSize = 13.sp)
        }
        return
    }
    val activeIndex = remember(positionMs, lines) {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) idx = i else break
        }
        idx
    }
    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
        }
    }
    val inactiveColor = appTextTertiary()
    // 主题色由设置驱动（鲜明色）
    val activeColor = LyricsSettings.themeColor.color
    val family = LyricsSettings.fontFamily.family
    val baseFontSize = LyricsSettings.fontSize

    LazyColumn(
        state = listState,
        modifier = modifier.background(Color.Black.copy(alpha = LyricsSettings.bgOpacity)),
        contentPadding = PaddingValues(vertical = 80.dp),
        verticalArrangement = Arrangement.spacedBy(LyricsSettings.lineSpacing.dp)
    ) {
        itemsIndexed(lines) { i, line ->
            val isActive = i == activeIndex
            val distance = kotlin.math.abs(i - activeIndex)
            val distAlpha = when (distance) {
                0 -> 1f
                1 -> 0.5f
                2 -> 0.3f
                else -> 0.18f
            }
            // 活动行颜色平滑过渡
            val color by animateColorAsState(
                targetValue = if (isActive) activeColor else inactiveColor,
                animationSpec = tween(300),
                label = "lrcColor"
            )
            val scale by animateFloatAsState(
                targetValue = if (isActive) 1f else 0.92f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                label = "lrcScale"
            )
            val fontSize by animateFloatAsState(
                targetValue = if (isActive) baseFontSize else (baseFontSize - 4f).coerceAtLeast(11f),
                animationSpec = tween(300),
                label = "lrcFont"
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(distAlpha)
                    .scale(scale)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    line.content,
                    color = color,
                    fontSize = fontSize.sp,
                    fontFamily = family,
                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
                if (line.translation.isNotBlank() && isActive && LyricsSettings.showTranslation) {
                    Text(line.translation, color = appTextTertiary(), fontSize = 12.sp, fontFamily = family, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// 通用歌曲行：SongList / SearchPage 共用
// ─────────────────────────────────────────────────────────────────

@Composable
private fun SongRow(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onAddToQueue: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (isCurrent) Modifier.background(FluidCyan.copy(alpha = 0.08f)) else Modifier)
            .clickable { onPlay() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = song.coverUrl.ifBlank { song.coverUri },
            contentDescription = null,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    song.title,
                    fontSize = 14.sp,
                    color = if (isCurrent) FluidCyan else appTextPrimary(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (song.isVipOnly) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "VIP", fontSize = 9.sp, color = FluidOrange,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(FluidOrange.copy(alpha = 0.15f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                "${song.artist}${if (song.album.isNotBlank()) " · ${song.album}" else ""}",
                fontSize = 11.sp, color = appTextTertiary(),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onAddToQueue, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.PlaylistAdd, "加入播放队列", tint = appTextTertiary(), modifier = Modifier.size(20.dp))
        }
        Icon(
            if (isCurrent && isPlaying) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
            null, tint = if (isCurrent) FluidCyan else appTextTertiary(), modifier = Modifier.size(20.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// 功能入口卡片行：搜索 / 播放队列 / 音质调节 / 睡眠定时器
// ─────────────────────────────────────────────────────────────────

@Composable
private fun MusicFeatureEntries(
    onSearch: () -> Unit,
    onQueue: () -> Unit,
    onQuality: () -> Unit,
    onSleep: () -> Unit,
    onLyrics: () -> Unit
) {
    val entries = listOf(
        Triple(Icons.Default.Search, "搜索", FluidCyan),
        Triple(Icons.Default.QueueMusic, "播放队列", FluidPurple),
        Triple(Icons.Default.GraphicEq, "音质调节", FluidOrange),
        Triple(Icons.Default.Bedtime, "睡眠定时", FluidBlue),
        Triple(Icons.Default.Lyrics, "歌词设置", FluidTeal)
    )
    val callbacks = listOf(onSearch, onQueue, onQuality, onSleep, onLyrics)
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(entries.size) { idx ->
            val (icon, label, color) = entries[idx]
            val onClick = callbacks[idx]
            Column(
                modifier = Modifier
                    .width(72.dp)
                    .height(86.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.10f)
                    .clickable { onClick() }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.6f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, label, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(label, fontSize = 11.sp, color = appTextPrimary(), maxLines = 1)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// 音质调节页：三档单选 + 当前音质显示 + VIP 降级提示
// ─────────────────────────────────────────────────────────────────

@Composable
private fun QualitySettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by MusicControllerManager.state.collectAsState()
    val current = state.quality
    val isLoggedIn = remember { SessionStore.isLoggedIn(context) }
    val isVip = remember { SessionStore.isVip(context) }

    fun applyQuality(q: PlaybackQuality) {
        if (q == PlaybackQuality.LOSSLESS && (!isLoggedIn || !isVip)) {
            android.widget.Toast.makeText(
                context,
                "无损音质为 VIP 专享，将自动降级到极高",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        MusicControllerManager.setQuality(context, q)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                "选择音质档位，下次播放或换歌时按新音质拉流。无损 FLAC 需 VIP，非 VIP 自动降级到极高 320kbps。",
                fontSize = 12.sp,
                color = appTextTertiary(),
                lineHeight = 17.sp
            )
        }
        items(PlaybackQuality.values().toList()) { q ->
            val selected = q == current
            val (icon, color, recommend) = when (q) {
                PlaybackQuality.STANDARD -> Triple(Icons.Default.AudioFile, FluidCyan, false)
                PlaybackQuality.EXHIGH -> Triple(Icons.Default.HighQuality, FluidOrange, true)
                PlaybackQuality.LOSSLESS -> Triple(Icons.Default.Diamond, FluidPink, false)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.10f)
                    .clickable { applyQuality(q) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.6f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, q.label, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(q.label, fontSize = 15.sp, color = appTextPrimary(), fontWeight = FontWeight.Medium)
                    Text(q.desc, fontSize = 11.sp, color = appTextTertiary())
                }
                if (recommend) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Brush.linearGradient(listOf(FluidOrange, FluidPink)))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) { Text("推荐", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(6.dp))
                }
                RadioButton(
                    selected = selected,
                    onClick = { applyQuality(q) },
                    colors = RadioButtonDefaults.colors(selectedColor = FluidCyan)
                )
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "当前音质：${current.label} · ${current.desc}",
                fontSize = 12.sp,
                color = appTextSecondary()
            )
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────
// 睡眠定时器
// 全局持久状态：SleepTimerState 单例（页面退出后状态仍保留）
// 注：定时 tick 依赖 SleepTimerPage 在栈内，退出 MusicScreen 后失效（简化版）
// ─────────────────────────────────────────────────────────────────

private object SleepTimerState {
    var endAtMs: Long by mutableStateOf(0L)           // 0=未启用；否则到点时间戳
    var afterCurrent: Boolean by mutableStateOf(false) // 播完本曲后停止
    var remainingMs: Long by mutableStateOf(0L)        // 剩余毫秒，UI 每秒刷新
    fun isActive(): Boolean = endAtMs > 0 || afterCurrent
    fun cancel() {
        endAtMs = 0L
        afterCurrent = false
        remainingMs = 0L
    }
}

@Composable
private fun SleepTimerPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by MusicControllerManager.state.collectAsState()

    // 倒计时 tick：每秒检查是否到点，到点则停止播放并清空状态
    LaunchedEffect(SleepTimerState.endAtMs) {
        if (SleepTimerState.endAtMs <= 0) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            val end = SleepTimerState.endAtMs
            if (now >= end) {
                if (state.isPlaying) MusicControllerManager.playPause()
                SleepTimerState.cancel()
                android.widget.Toast.makeText(context, "睡眠定时已到点，已停止播放", android.widget.Toast.LENGTH_SHORT).show()
                break
            }
            SleepTimerState.remainingMs = end - now
            delay(1000)
        }
    }

    // 播完本曲后：监听切歌，切歌时若 afterCurrent=true 则停止播放
    var lastSongId by remember { mutableStateOf(state.song?.id) }
    LaunchedEffect(state.song?.id) {
        val cur = state.song?.id
        if (cur != lastSongId && SleepTimerState.afterCurrent) {
            if (state.isPlaying) MusicControllerManager.playPause()
            SleepTimerState.cancel()
            android.widget.Toast.makeText(context, "本曲播完，已停止播放", android.widget.Toast.LENGTH_SHORT).show()
        }
        lastSongId = cur
    }

    val durations = listOf(
        10 to "10 分钟", 20 to "20 分钟", 30 to "30 分钟",
        45 to "45 分钟", 60 to "60 分钟", 90 to "90 分钟"
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 倒计时显示
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.12f)
                .padding(vertical = 24.dp, horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (SleepTimerState.isActive()) {
                val display = if (SleepTimerState.endAtMs > 0) {
                    val secs = (SleepTimerState.remainingMs / 1000).coerceAtLeast(0)
                    "还剩 %02d:%02d".format(secs / 60, secs % 60)
                } else {
                    "播完本曲后停止"
                }
                Text(display, fontSize = 32.sp, fontWeight = FontWeight.Thin, color = FluidCyan)
            } else {
                Text("未启用睡眠定时", fontSize = 14.sp, color = appTextTertiary())
            }
        }

        Text("选择定时", fontSize = 13.sp, color = appTextSecondary(), fontWeight = FontWeight.Medium)

        // 时长选项：3 列网格
        durations.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { (mins, label) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .glassSurface(cornerRadius = 12.dp, glassAlpha = 0.10f)
                            .clickable {
                                SleepTimerState.endAtMs = System.currentTimeMillis() + mins * 60_000L
                                SleepTimerState.afterCurrent = false
                                android.widget.Toast.makeText(
                                    context,
                                    "已设置 $mins 分钟后停止",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 13.sp, color = appTextPrimary())
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        // 播完本曲后
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .glassSurface(cornerRadius = 12.dp, glassAlpha = 0.10f)
                .clickable {
                    SleepTimerState.endAtMs = 0L
                    SleepTimerState.afterCurrent = true
                    android.widget.Toast.makeText(context, "播完本曲后停止", android.widget.Toast.LENGTH_SHORT).show()
                }
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.MusicNote, null, tint = FluidPurple, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("播完本曲后", fontSize = 14.sp, color = appTextPrimary(), modifier = Modifier.weight(1f))
            if (SleepTimerState.afterCurrent && SleepTimerState.endAtMs == 0L) {
                Icon(Icons.Default.Check, null, tint = FluidCyan, modifier = Modifier.size(18.dp))
            }
        }

        // 关闭定时器
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AccentDanger.copy(alpha = 0.10f))
                .clickable {
                    SleepTimerState.cancel()
                    android.widget.Toast.makeText(context, "已关闭睡眠定时器", android.widget.Toast.LENGTH_SHORT).show()
                }
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Close, null, tint = AccentDanger, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("关闭定时器", fontSize = 14.sp, color = AccentDanger, modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(60.dp))
    }
}

// ─────────────────────────────────────────────────────────────────
// 歌词设置页：字体更换 / 主题色更换 / 时间偏移 / 字号 / 行间距 / 翻译 / 背景
// ─────────────────────────────────────────────────────────────────

@Composable
private fun LyricsSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    // 读取当前设置（mutableStateOf 委托的 var，直接读取即可被 Compose 快照系统跟踪）
    val font = LyricsSettings.fontFamily
    val color = LyricsSettings.themeColor
    val offset = LyricsSettings.timeOffsetMs
    val fontSize = LyricsSettings.fontSize
    val lineSpacing = LyricsSettings.lineSpacing
    val showTrans = LyricsSettings.showTranslation
    val bgOpacity = LyricsSettings.bgOpacity

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        // ── 字体更换 ──
        SettingsSectionTitle("歌词字体")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LyricsFont.entries.forEach { f ->
                val selected = font == f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .glassSurface(cornerRadius = 12.dp, glassAlpha = if (selected) 0.25f else 0.10f)
                        .clickable { LyricsSettings.updateFont(f) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(f.label, fontSize = 13.sp, fontFamily = f.family,
                        color = if (selected) FluidCyan else appTextPrimary(),
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // ── 主题色更换（鲜明色）──
        SettingsSectionTitle("字幕主题色")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LyricsThemeColor.entries.forEach { c ->
                val selected = color == c
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(c.color)
                        .then(if (selected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                        .clickable { LyricsSettings.updateColor(c) },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Icon(Icons.Default.Check, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // ── 字幕滚动时间偏移（提前/延后）──
        SettingsSectionTitle("字幕时间偏移")
        Text(
            "提前显示 ${(-offset).coerceAtLeast(0) / 1000.0}s  /  延后显示 ${(offset).coerceAtLeast(0) / 1000.0}s",
            fontSize = 12.sp, color = appTextTertiary(), modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("早", fontSize = 11.sp, color = appTextTertiary())
            Slider(
                value = offset.toFloat(),
                onValueChange = { LyricsSettings.updateOffset(it.toLong()) },
                valueRange = -3000f..3000f,
                steps = 59, // 每 100ms 一档
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(thumbColor = FluidCyan, activeTrackColor = FluidCyan)
            )
            Text("晚", fontSize = 11.sp, color = appTextTertiary())
        }
        // 一键归零
        TextButton(onClick = { LyricsSettings.updateOffset(0L) }) { Text("重置为 0", fontSize = 12.sp, color = FluidPurple) }

        Spacer(Modifier.height(18.dp))

        // ── 字号 ──
        SettingsSectionTitle("歌词字号")
        Text("当前 ${fontSize.toInt()} sp", fontSize = 12.sp, color = appTextTertiary(), modifier = Modifier.padding(bottom = 6.dp))
        Slider(
            value = fontSize,
            onValueChange = { LyricsSettings.updateFontSize(it) },
            valueRange = 13f..24f,
            steps = 10,
            colors = SliderDefaults.colors(thumbColor = FluidCyan, activeTrackColor = FluidCyan)
        )

        Spacer(Modifier.height(18.dp))

        // ── 行间距 ──
        SettingsSectionTitle("歌词行间距")
        Text("当前 ${lineSpacing.toInt()} dp", fontSize = 12.sp, color = appTextTertiary(), modifier = Modifier.padding(bottom = 6.dp))
        Slider(
            value = lineSpacing,
            onValueChange = { LyricsSettings.updateLineSpacing(it) },
            valueRange = 6f..28f,
            steps = 10,
            colors = SliderDefaults.colors(thumbColor = FluidCyan, activeTrackColor = FluidCyan)
        )

        Spacer(Modifier.height(18.dp))

        // ── 翻译显示开关 ──
        SettingsToggleRow(
            title = "显示歌词翻译",
            desc = "开启后活动行下方显示译文",
            checked = showTrans,
            onCheckedChange = { LyricsSettings.updateShowTranslation(it) }
        )

        Spacer(Modifier.height(14.dp))

        // ── 背景透明度 ──
        SettingsSectionTitle("歌词背景透明度")
        Text("当前 ${(bgOpacity * 100).toInt()}%", fontSize = 12.sp, color = appTextTertiary(), modifier = Modifier.padding(bottom = 6.dp))
        Slider(
            value = bgOpacity,
            onValueChange = { LyricsSettings.updateBgOpacity(it) },
            valueRange = 0f..0.8f,
            colors = SliderDefaults.colors(thumbColor = FluidCyan, activeTrackColor = FluidCyan)
        )

        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = appTextPrimary(),
        modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun SettingsToggleRow(title: String, desc: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.10f)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = appTextPrimary(), fontWeight = FontWeight.Medium)
            Text(desc, fontSize = 11.sp, color = appTextTertiary())
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = FluidCyan, checkedTrackColor = FluidCyan.copy(alpha = 0.4f)))
    }
}
