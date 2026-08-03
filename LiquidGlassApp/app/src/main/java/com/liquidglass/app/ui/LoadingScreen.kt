package com.liquidglass.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.app.ResourceManager
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

private enum class LoadState {
    CHECKING, LOADING, DOWNLOADING, EXTRACTING, PHASE2_DOWNLOADING, PHASE2_EXTRACTING, COMPLETE, ERROR
}

@Composable
fun LoadingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loadState by remember { mutableStateOf(LoadState.CHECKING) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(100L) }
    var statusText by remember { mutableStateOf("正在初始化...") }
    var downloadSpeed by remember { mutableStateOf("") }
    var lastBytes by remember { mutableStateOf(0L) }
    var lastTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var errorMessage by remember { mutableStateOf("") }
    var currentPhase by remember { mutableIntStateOf(1) }

    // 入场动画
    var entranceAlpha by remember { mutableStateOf(0f) }
    var entranceScale by remember { mutableStateOf(0.8f) }
    // 退场动画
    var exitAlpha by remember { mutableStateOf(1f) }
    var exitScale by remember { mutableStateOf(1f) }

    val infiniteTransition = rememberInfiniteTransition(label = "loadingAnim")
    // 动画无缝循环修复：用真实经过时间驱动，永不重启（同 HomeScreen）
    val animTime by produceState(0f) {
        val startNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val elapsedSec = (now - startNanos) / 1_000_000_000f
                value = (elapsedSec * 0.5f) % 3141.5927f
            }
        }
    }
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val rotateAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotate"
    )

    // 入场动画启动
    val entranceAnimSpec = tween<Float>(durationMillis = 800, easing = EaseOutCubic)
    LaunchedEffect(Unit) {
        val alphaAnim = Animatable(0f)
        val scaleAnim = Animatable(0.8f)
        kotlinx.coroutines.coroutineScope {
            launch { alphaAnim.animateTo(1f, entranceAnimSpec); entranceAlpha = 1f }
            launch { scaleAnim.animateTo(1f, entranceAnimSpec); entranceScale = 1f }
        }
    }

    val phaseWeight = if (currentPhase == 1) 0.6f else 0.4f
    val phaseBaseProgress = if (currentPhase == 1) 0f else 0.6f

    val progress by remember(loadState, downloadedBytes, totalBytes, currentPhase) {
        derivedStateOf {
            when (loadState) {
                LoadState.CHECKING -> 0f
                LoadState.LOADING -> 0.15f
                LoadState.DOWNLOADING -> {
                    val phaseProgress = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0.25f
                    phaseBaseProgress + phaseProgress * phaseWeight
                }
                LoadState.EXTRACTING -> phaseBaseProgress + phaseWeight * (0.85f + 0.10f * (sin(animTime * 0.1f).coerceIn(-0.5f, 0.5f) + 0.5f).toFloat())
                LoadState.PHASE2_DOWNLOADING -> {
                    val phaseProgress = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0.25f
                    phaseBaseProgress + phaseProgress * phaseWeight
                }
                LoadState.PHASE2_EXTRACTING -> phaseBaseProgress + phaseWeight * (0.85f + 0.10f * (sin(animTime * 0.1f).coerceIn(-0.5f, 0.5f) + 0.5f).toFloat())
                LoadState.COMPLETE -> 1f
                LoadState.ERROR -> 0f
            }
        }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = if (loadState == LoadState.COMPLETE) {
            tween(durationMillis = 600, easing = EaseOutCubic)
        } else {
            spring(dampingRatio = 0.5f, stiffness = 200f)
        },
        label = "progressAnim"
    )

    val statusColor = when (loadState) {
        LoadState.CHECKING, LoadState.LOADING -> FluidCyan
        LoadState.DOWNLOADING -> FluidBlue
        LoadState.EXTRACTING -> FluidPurple
        LoadState.PHASE2_DOWNLOADING -> FluidTeal
        LoadState.PHASE2_EXTRACTING -> FluidPink
        LoadState.COMPLETE -> FluidTeal
        LoadState.ERROR -> AccentDanger
    }

    fun startDownload() {
        scope.launch {
            loadState = LoadState.CHECKING
            statusText = "正在检查资源..."
            downloadedBytes = 0L
            totalBytes = 100L
            downloadSpeed = ""
            lastBytes = 0L
            lastTime = System.currentTimeMillis()
            currentPhase = 1

            if (ResourceManager.isAllInstalled(context)) {
                loadState = LoadState.LOADING
                statusText = "正在加载..."
                delay(1200)
                loadState = LoadState.COMPLETE
                statusText = "全部就绪！"
                delay(800)
                // 退场动画
                exitAlpha = 0f
                exitScale = 1.15f
                delay(500)
                onComplete()
                return@launch
            }

            // ========== 阶段1 ==========
            if (ResourceManager.isResourcesInstalled(context)) {
                currentPhase = 2
            } else {
                currentPhase = 1
                loadState = LoadState.DOWNLOADING
                statusText = "阶段 1/2：下载基础资源包..."

                val result = ResourceManager.downloadAndInstall(context) { dlBytes, totBytes, status ->
                    downloadedBytes = dlBytes
                    totalBytes = totBytes
                    val now = System.currentTimeMillis()
                    val elapsed = (now - lastTime).coerceAtLeast(1)
                    if (elapsed >= 500) {
                        val bytesDiff = dlBytes - lastBytes
                        downloadSpeed = formatSpeed(bytesDiff.toFloat() / (elapsed / 1000f))
                        lastBytes = dlBytes
                        lastTime = now
                    }
                    when {
                        status.contains("解压", ignoreCase = true) -> {
                            loadState = LoadState.EXTRACTING
                            statusText = "阶段 1/2：正在解压安装..."
                        }
                        status.contains("重试", ignoreCase = true) -> {
                            statusText = "下载失败，正在重试..."
                        }
                        else -> {
                            statusText = "阶段 1/2：下载基础资源包..."
                            loadState = LoadState.DOWNLOADING
                        }
                    }
                }

                if (result.isFailure) {
                    loadState = LoadState.ERROR
                    errorMessage = result.exceptionOrNull()?.message ?: "下载失败，请检查网络连接"
                    statusText = "下载失败"
                    downloadSpeed = ""
                    return@launch
                }
            }

            // ========== 阶段2 ==========
            if (!ResourceManager.isInteractionInstalled(context)) {
                currentPhase = 2
                downloadedBytes = 0L
                totalBytes = 100L
                downloadSpeed = ""
                lastBytes = 0L
                lastTime = System.currentTimeMillis()
                loadState = LoadState.PHASE2_DOWNLOADING
                statusText = "阶段 2/2：下载交互资源包..."

                val result2 = ResourceManager.downloadInteractionPack(context) { dlBytes, totBytes, status ->
                    downloadedBytes = dlBytes
                    totalBytes = totBytes
                    val now = System.currentTimeMillis()
                    val elapsed = (now - lastTime).coerceAtLeast(1)
                    if (elapsed >= 500) {
                        val bytesDiff = dlBytes - lastBytes
                        downloadSpeed = formatSpeed(bytesDiff.toFloat() / (elapsed / 1000f))
                        lastBytes = dlBytes
                        lastTime = now
                    }
                    when {
                        status.contains("解压", ignoreCase = true) -> {
                            loadState = LoadState.PHASE2_EXTRACTING
                            statusText = "阶段 2/2：正在解压交互资源..."
                        }
                        status.contains("重试", ignoreCase = true) -> {
                            statusText = "下载失败，正在重试..."
                        }
                        else -> {
                            statusText = "阶段 2/2：下载交互资源包..."
                            loadState = LoadState.PHASE2_DOWNLOADING
                        }
                    }
                }

                if (result2.isFailure) {
                    loadState = LoadState.COMPLETE
                    statusText = "基础资源已就绪"
                    delay(800)
                    exitAlpha = 0f
                    exitScale = 1.15f
                    delay(500)
                    onComplete()
                    return@launch
                }
            }

            // ========== 完成 ==========
            loadState = LoadState.COMPLETE
            statusText = "全部资源就绪！"
            delay(800)
            exitAlpha = 0f
            exitScale = 1.15f
            delay(500)
            onComplete()
        }
    }

    LaunchedEffect(Unit) {
        delay(300) // 等待入场动画
        startDownload()
    }

    LiquidGlassScaffold(
        animTime = animTime,
        modifier = Modifier.alpha(entranceAlpha * exitAlpha).scale(entranceScale * exitScale)
    ) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.weight(0.15f))

            // === App icon orb ===
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .scale(pulse),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = size.width * 0.40f

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                FluidCyan.copy(alpha = 0.12f),
                                FluidCyan.copy(alpha = 0.03f),
                                Color.Transparent
                            ),
                            center = Offset(cx, cy),
                            radius = r * 1.5f
                        ),
                        radius = r * 1.5f,
                        center = Offset(cx, cy)
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                FluidPurple.copy(alpha = 0.10f),
                                FluidPurple.copy(alpha = 0.02f),
                                Color.Transparent
                            ),
                            center = Offset(cx, cy),
                            radius = r * 1.2f
                        ),
                        radius = r * 1.2f,
                        center = Offset(cx, cy)
                    )
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                FluidCyan.copy(alpha = 0f),
                                FluidCyan.copy(alpha = 0.6f),
                                FluidPurple.copy(alpha = 0.6f),
                                FluidPink.copy(alpha = 0.6f),
                                FluidCyan.copy(alpha = 0f)
                            )
                        ),
                        startAngle = rotateAngle,
                        sweepAngle = 120f,
                        useCenter = false,
                        topLeft = Offset(cx - r * 0.85f, cy - r * 0.85f),
                        size = Size(r * 1.7f, r * 1.7f),
                        style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                    )
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                FluidTeal.copy(alpha = 0f),
                                FluidTeal.copy(alpha = 0.5f),
                                FluidBlue.copy(alpha = 0.5f),
                                FluidTeal.copy(alpha = 0f)
                            )
                        ),
                        startAngle = -rotateAngle * 0.7f + 180f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(cx - r * 0.75f, cy - r * 0.75f),
                        size = Size(r * 1.5f, r * 1.5f),
                        style = Stroke(width = 2f, cap = StrokeCap.Round)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.08f),
                                    FluidCyan.copy(alpha = 0.12f),
                                    FluidPurple.copy(alpha = 0.08f)
                                )
                            )
                        )
                        .glassSurface(cornerRadius = 90.dp, glassAlpha = 0.15f, showBorder = true),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(36.dp)) {
                        val w = size.width
                        val h = size.height
                        val cx = w / 2f
                        val cy = h / 2f

                        // 六边形图标（灵工坊标志）
                        val hexPath = Path().apply {
                            moveTo(cx, h * 0.1f)
                            lineTo(w * 0.9f, h * 0.35f)
                            lineTo(w * 0.9f, h * 0.65f)
                            lineTo(cx, h * 0.9f)
                            lineTo(w * 0.1f, h * 0.65f)
                            lineTo(w * 0.1f, h * 0.35f)
                            close()
                        }
                        drawPath(
                            path = hexPath,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    FluidCyan.copy(alpha = 0.9f),
                                    FluidBlue.copy(alpha = 0.9f),
                                    FluidPurple.copy(alpha = 0.7f)
                                ),
                                start = Offset(0f, 0f),
                                end = Offset(w, h)
                            )
                        )
                        // 内部"工"字
                        drawRect(
                            color = Color.White.copy(alpha = 0.8f),
                            topLeft = Offset(w * 0.3f, h * 0.35f),
                            size = Size(w * 0.4f, h * 0.08f)
                        )
                        drawRect(
                            color = Color.White.copy(alpha = 0.8f),
                            topLeft = Offset(w * 0.46f, h * 0.35f),
                            size = Size(w * 0.08f, h * 0.3f)
                        )
                        drawRect(
                            color = Color.White.copy(alpha = 0.8f),
                            topLeft = Offset(w * 0.3f, h * 0.57f),
                            size = Size(w * 0.4f, h * 0.08f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "灵工坊",
                fontSize = 28.sp,
                fontWeight = FontWeight.Thin,
                color = appTextPrimary().copy(alpha = 0.9f),
                letterSpacing = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "LING GONG FANG · SMART TOOLBOX",
                fontSize = 11.sp,
                fontWeight = FontWeight.Light,
                color = appTextTertiary(),
                letterSpacing = 5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))

            // === Phase indicators ===
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PhaseIndicator(
                    label = "基础资源",
                    isActive = currentPhase == 1 && loadState != LoadState.CHECKING && loadState != LoadState.COMPLETE,
                    isComplete = currentPhase > 1 || loadState == LoadState.COMPLETE,
                    color = FluidCyan
                )
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(2.dp)
                        .background(
                            if (currentPhase > 1) FluidCyan.copy(alpha = 0.5f)
                            else Color.White.copy(alpha = 0.1f)
                        )
                )
                PhaseIndicator(
                    label = "交互资源",
                    isActive = currentPhase == 2 && loadState != LoadState.COMPLETE,
                    isComplete = loadState == LoadState.COMPLETE,
                    color = FluidTeal
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === Progress glass card ===
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 24.dp, glassAlpha = 0.16f)
                    .padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.size(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeWidth = 8.dp.toPx()
                            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                            val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)

                            drawArc(
                                color = Color.White.copy(alpha = 0.08f),
                                startAngle = -90f, sweepAngle = 360f,
                                useCenter = false, topLeft = topLeft, size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )

                            if (loadState == LoadState.EXTRACTING || loadState == LoadState.PHASE2_EXTRACTING) {
                                val sweep = (abs(sin(animTime * 0.12f)) * 80f + 200f)
                                drawArc(
                                    brush = Brush.sweepGradient(
                                        colors = listOf(
                                            FluidCyan.copy(alpha = 0.9f),
                                            FluidPurple.copy(alpha = 0.9f),
                                            FluidPink.copy(alpha = 0.7f),
                                            FluidCyan.copy(alpha = 0.9f)
                                        )
                                    ),
                                    startAngle = -90f + animTime * 2f,
                                    sweepAngle = sweep, useCenter = false,
                                    topLeft = topLeft, size = arcSize,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )
                            } else {
                                drawArc(
                                    brush = Brush.sweepGradient(
                                        colors = listOf(
                                            FluidCyan.copy(alpha = 0.9f),
                                            FluidBlue.copy(alpha = 0.8f),
                                            FluidPurple.copy(alpha = 0.7f),
                                            FluidCyan.copy(alpha = 0.9f)
                                        )
                                    ),
                                    startAngle = -90f,
                                    sweepAngle = 360f * animatedProgress,
                                    useCenter = false, topLeft = topLeft, size = arcSize,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )
                            }

                            if (animatedProgress > 0.01f) {
                                val endAngle = -90f + 360f * animatedProgress
                                val rad = Math.toRadians(endAngle.toDouble())
                                val arcRadius = (arcSize.width / 2f)
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val dotX = cx + arcRadius * cos(rad).toFloat()
                                val dotY = cy + arcRadius * sin(rad).toFloat()
                                drawCircle(
                                    color = statusColor.copy(alpha = 0.9f),
                                    radius = strokeWidth * 0.7f,
                                    center = Offset(dotX, dotY)
                                )
                                drawCircle(
                                    color = statusColor.copy(alpha = 0.3f),
                                    radius = strokeWidth * 1.3f,
                                    center = Offset(dotX, dotY)
                                )
                            }
                        }

                        Text(
                            text = if (loadState == LoadState.COMPLETE) "完成" else "${(animatedProgress * 100).roundToInt()}%",
                            fontSize = if (loadState == LoadState.COMPLETE) 32.sp else 24.sp,
                            fontWeight = FontWeight.Light,
                            color = if (loadState == LoadState.COMPLETE) FluidTeal else TextPrimary,
                            letterSpacing = 2.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = statusText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = statusColor,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (loadState == LoadState.DOWNLOADING || loadState == LoadState.EXTRACTING ||
                        loadState == LoadState.PHASE2_DOWNLOADING || loadState == LoadState.PHASE2_EXTRACTING
                    ) {
                        Text(
                            text = "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}",
                            fontSize = 12.sp, color = appTextSecondary(), letterSpacing = 0.5.sp
                        )
                    }

                    if (downloadSpeed.isNotEmpty() && (loadState == LoadState.DOWNLOADING || loadState == LoadState.PHASE2_DOWNLOADING)) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = downloadSpeed,
                            fontSize = 12.sp, color = appTextTertiary(), letterSpacing = 0.5.sp
                        )
                    }

                    if (currentPhase == 2 && loadState != LoadState.COMPLETE && loadState != LoadState.ERROR) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "正在加载交互资源包\n（物理引擎 · 流体动画 · 折射效果）",
                            fontSize = 10.sp, color = appTextTertiary(),
                            textAlign = TextAlign.Center, lineHeight = 16.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 错误时显示重试+跳过按钮
            if (loadState == LoadState.ERROR) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.12f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                ResourceManager.resetDownloadState()
                                exitAlpha = 0f
                                exitScale = 1.15f
                                scope.launch { delay(500); onComplete() }
                            }
                            .padding(horizontal = 32.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("跳过", fontSize = 14.sp, color = appTextSecondary(), fontWeight = FontWeight.Medium)
                    }
                    Box(
                        modifier = Modifier
                            .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.12f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { startDownload() }
                            .padding(horizontal = 32.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("重试", fontSize = 14.sp, color = FluidCyan, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        fontSize = 11.sp, color = appTextTertiary(),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            } else if (loadState != LoadState.COMPLETE) {
                // 下载中显示跳过按钮
                Box(
                    modifier = Modifier
                        .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.10f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            ResourceManager.resetDownloadState()
                            exitAlpha = 0f
                            exitScale = 1.15f
                            scope.launch { delay(500); onComplete() }
                        }
                        .padding(horizontal = 32.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("跳过，稍后在关于中下载", fontSize = 12.sp, color = appTextTertiary(), fontWeight = FontWeight.Light)
                }
            }

            Spacer(modifier = Modifier.weight(0.2f))
        }
    }
}

@Composable
private fun PhaseIndicator(
    label: String,
    isActive: Boolean,
    isComplete: Boolean,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isComplete -> color
                        isActive -> color.copy(alpha = 0.6f)
                        else -> Color.White.copy(alpha = 0.15f)
                    }
                )
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 9.sp,
            color = when {
                isComplete -> color
                isActive -> color.copy(alpha = 0.8f)
                else -> TextTertiary
            },
            fontWeight = FontWeight.Light
        )
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun formatSpeed(bytesPerSecond: Float): String {
    val bps = bytesPerSecond.roundToLong()
    return when {
        bps < 1024 -> "${bps} B/s"
        bps < 1024 * 1024 -> String.format("%.1f KB/s", bps / 1024.0)
        bps < 1024 * 1024 * 1024 -> String.format("%.1f MB/s", bps / (1024.0 * 1024.0))
        else -> String.format("%.2f GB/s", bps / (1024.0 * 1024.0 * 1024.0))
    }
}
