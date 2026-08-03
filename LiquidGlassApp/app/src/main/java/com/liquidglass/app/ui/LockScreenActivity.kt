package com.liquidglass.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.liquidglass.app.MainActivity
import com.liquidglass.app.music.MusicControllerManager
import com.liquidglass.app.ui.theme.LiquidGlassTheme
import kotlinx.coroutines.delay

/**
 * 全屏锁屏播放器（酷狗式）。
 *
 * - 专辑封面占满整屏作为背景（叠加深色渐变保证歌词可读）
 * - 多行实时歌词居中显示，当前行高亮
 * - 底部播放控制（上一首/播放暂停/下一首）
 * - 左滑或右滑解锁手势指引（动画箭头 + "滑动解锁"提示），
 *   滑动距离超过阈值后 finish() 返回 App 界面
 *
 * 通过 [android.app.Activity.setShowWhenLocked] + [setTurnScreenOn]
 * 让 Activity 直接覆盖在系统锁屏之上，无需解锁即可操作。
 */
class LockScreenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 在锁屏之上显示并点亮屏幕——必须在 super.onCreate 前调用，
        // 让 WindowManager 在第一次布局前就拿到 showWhenLocked/turnScreenOn 标志，
        // 否则 Android 11 上某些 OEM 锁屏会先于本 Activity 抢占屏幕导致看不到。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // 用 addFlags 而非 setFlags，避免覆盖上面通过 window.addFlags 设置的 lockscreen 标志
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            // 低版本兜底（上面 setShowWhenLocked 在 <O_MR1 不可用，靠 flag 实现同等效果）
        }
        super.onCreate(savedInstanceState)

        setContent {
            LiquidGlassTheme {
                // 滑动解锁后：启动 MainActivity 回到前台（避免后台被杀），再 finish 自己
                LockScreenPlayer(onDismiss = {
                    val intent = Intent(this@LockScreenActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("action", "open_music")
                    }
                    startActivity(intent)
                    finish()
                })
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Android 11+ 在锁屏上显示后，请求关闭系统 keyguard（不强制，仅请求）
        // 这能让本 Activity 真正"覆盖"在系统锁屏之上，而不只是叠加
        try {
            val km = getSystemService(android.app.KeyguardManager::class.java)
            if (km != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                km.requestDismissKeyguard(this, null)
            }
        } catch (_: Throwable) {
            // 部分 OEM 实现会拒绝，忽略错误，本 Activity 仍可显示在锁屏之上
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun LockScreenPlayer(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by MusicControllerManager.state.collectAsState()
    val song = state.song

    // 高频 tick 播放进度（100ms），让锁屏歌词无延迟跟随
    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            MusicControllerManager.tickPosition()
            delay(100)
        }
    }

    // 手势滑动偏移量（用于视觉反馈）
    var dragOffset by remember { mutableStateOf(0f) }
    // 是否触发了解锁（滑动距离足够）
    var unlockTriggered by remember { mutableStateOf(false) }
    val threshold = 600f  // 滑动解锁阈值（像素）

    // 手势指引动画（左右往复提示）
    val infiniteTransition = rememberInfiniteTransition(label = "hint")
    val hintOffset by infiniteTransition.animateFloat(
        initialValue = -20f,
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hintOffset"
    )

    // 触发解锁后渐隐退出
    LaunchedEffect(unlockTriggered) {
        if (unlockTriggered) {
            delay(200)
            onDismiss()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (kotlin.math.abs(dragOffset) > threshold) {
                            unlockTriggered = true
                        } else {
                            dragOffset = 0f  // 未达阈值回弹
                        }
                    }
                ) { _, dragAmount ->
                    dragOffset += dragAmount
                }
            }
    ) {
        // ── 1. 专辑封面全屏背景（模糊处理增强歌词可读性） ──
        val coverModel = song?.coverUrl?.ifBlank { null } ?: song?.coverUri?.ifBlank { null }
        if (coverModel != null) {
            AsyncImage(
                model = coverModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(radiusX = 40.dp, radiusY = 40.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            )
        } else {
            // 无封面时用渐变兜底
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF1A1A2E), Color(0xFF000000))))
            )
        }

        // 深色渐变遮罩（从上到下加深，保证歌词可读）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.3f),
                            Color.Black.copy(alpha = 0.5f),
                            Color.Black.copy(alpha = 0.7f)
                        )
                    )
                )
        )

        // ── 2. 内容区 ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(20.dp))

            // 歌曲信息
            Text(
                text = song?.title ?: "未在播放",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = song?.artist ?: "",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(32.dp))

            // ── 3. 多行实时歌词 ──
            MultiLineLyrics(
                modifier = Modifier.weight(1f),
                positionMs = state.positionMs
            )

            // ── 4. 播放进度条 ──
            if (state.durationMs > 0) {
                val progress = (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.2f)
                )
                Spacer(Modifier.height(8.dp))
            }

            // ── 5. 播放控制按钮 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { MusicControllerManager.previous() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { MusicControllerManager.playPause() }, modifier = Modifier.size(56.dp)) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        null, tint = Color.White, modifier = Modifier.size(48.dp)
                    )
                }
                IconButton(onClick = { MusicControllerManager.next() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── 6. 手势滑动解锁指引 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.ChevronLeft,
                    null,
                    tint = Color.White.copy(alpha = 0.6f + (hintOffset + 20f) / 80f * 0.4f),
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    "左右滑动解锁返回应用",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Icon(
                    Icons.Default.ChevronRight,
                    null,
                    tint = Color.White.copy(alpha = 0.6f + (20f - hintOffset) / 80f * 0.4f),
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        // ── 7. 滑动视觉反馈：整体随拖拽偏移并渐隐 ──
        if (kotlin.math.abs(dragOffset) > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = (kotlin.math.abs(dragOffset) / threshold).coerceIn(0f, 0.6f)))
            )
        }
    }
}

/**
 * 多行实时歌词组件：显示当前行±若干行，当前行高亮放大，自动滚动跟随。
 */
@Composable
private fun MultiLineLyrics(modifier: Modifier, positionMs: Long) {
    val lyrics = remember(positionMs / 1000) {  // 每秒刷新一次歌词列表（避免高频重组）
        MusicControllerManager.currentLyricsData()
    }

    // 构建统一行列表：(timeMs, text)
    val lines = remember(lyrics) {
        when {
            lyrics.yrcLines.isNotEmpty() ->
                lyrics.yrcLines.map { it.startMs to it.chars.joinToString("") { c -> c.content }.ifBlank { "..." } }
            lyrics.lrcLines.isNotEmpty() ->
                lyrics.lrcLines.map { it.timeMs to it.content.ifBlank { "..." } }
            else -> emptyList()
        }
    }

    val listState = rememberLazyListState()

    // 当前行索引
    val currentIndex = remember(positionMs, lines.size) {
        var idx = -1
        for ((i, pair) in lines.withIndex()) {
            if (pair.first <= positionMs) idx = i else break
        }
        idx
    }

    // 自动滚动让当前行居中
    LaunchedEffect(currentIndex, lines.size) {
        if (currentIndex >= 0 && lines.isNotEmpty()) {
            // 滚动到当前行，偏移让其在视口中部
            val target = (currentIndex - 3).coerceAtLeast(0)
            listState.animateScrollToItem(target)
        }
    }

    if (lines.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("纯音乐", color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp)
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 60.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(lines.size) { i ->
            val (timeMs, text) = lines[i]
            val isCurrent = i == currentIndex
            val isNear = kotlin.math.abs(i - currentIndex) <= 1
            // 当前行：青色高亮 + 放大加粗；邻近行：白色半透明；远行：更淡
            val color = when {
                isCurrent -> Color(0xFF00E5FF)
                isNear -> Color.White.copy(alpha = 0.6f)
                else -> Color.White.copy(alpha = 0.3f)
            }
            val fontSize = when {
                isCurrent -> 22.sp
                isNear -> 17.sp
                else -> 15.sp
            }
            val weight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
            // v2.9.0 修复：锁屏歌词应用用户选择的字体（原代码遗漏 fontFamily 参数）
            val family = LyricsSettings.fontFamily.family
            // v2.9.0 修复：锁屏歌词也应用用户选择的主题色和字号
            val userColor = if (isCurrent) LyricsSettings.themeColor.color else color
            val userFontSize = when {
                isCurrent -> LyricsSettings.fontSize.sp
                isNear -> (LyricsSettings.fontSize - 3).sp
                else -> (LyricsSettings.fontSize - 5).sp
            }
            Text(
                text = text,
                color = userColor,
                fontSize = userFontSize,
                fontWeight = weight,
                fontFamily = family,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
