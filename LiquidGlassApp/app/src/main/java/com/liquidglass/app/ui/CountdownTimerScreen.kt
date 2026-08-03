package com.liquidglass.app.ui

import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.*

enum class TimerTab { COUNTDOWN, STOPWATCH }

// ── 倒计时状态 ──
private enum class CountdownState { IDLE, RUNNING, PAUSED, FINISHED }

// ── 秒表状态 ──
private enum class StopwatchState { IDLE, RUNNING, PAUSED }

@Composable
fun CountdownTimerScreen(animTime: Float, onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(TimerTab.COUNTDOWN) }

    // ── 倒计时 ──
    var countdownState by remember { mutableStateOf(CountdownState.IDLE) }
    var totalSeconds by remember { mutableStateOf(0L) }
    var remainingSeconds by remember { mutableStateOf(0L) }
    var pickerHours by remember { mutableStateOf(0) }
    var pickerMinutes by remember { mutableStateOf(0) }
    var pickerSeconds by remember { mutableStateOf(0) }
    var showAlarm by remember { mutableStateOf(false) }
    // 倒计时结束的真实时间戳（SystemClock.elapsedRealtime()），用于精确计算剩余秒数
    // 修复"倒计时漂移"：原 delay(1000); remainingSeconds-- 每次重组重启 delay，
    // 实际间隔 >1000ms 导致长时间倒计时不准。现基于真实时间戳计算，delay 仅控制刷新频率。
    var countdownEndRealtime by remember { mutableStateOf(0L) }

    // ── 秒表 ──
    var stopwatchState by remember { mutableStateOf(StopwatchState.IDLE) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var lapTimes by remember { mutableStateOf(listOf<Long>()) }
    var lastLapMs by remember { mutableStateOf(0L) }
    // 已运行段累计毫秒（暂停时固化），与本次运行真实时间戳相加得总 elapsed
    var accumulatedMs by remember { mutableStateOf(0L) }

    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    fun triggerAlarm() {
        showAlarm = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(500)
            }
        } catch (_: Exception) {}
    }

    // ── 倒计时逻辑 ──
    // 修复"倒计时漂移"：基于 SystemClock.elapsedRealtime() 真实时间戳计算剩余秒数，
    // delay(200) 仅控制 UI 刷新频率（~5次/秒），不参与计时。
    // 原实现 delay(1000); remainingSeconds-- 每次重组重启 delay，
    // 实际间隔 >1000ms（含调度/重组开销），长时间倒计时累积显著漂移。
    LaunchedEffect(countdownState) {
        if (countdownState == CountdownState.RUNNING) {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val remainingMs = countdownEndRealtime - now
                if (remainingMs <= 0) {
                    remainingSeconds = 0
                    countdownState = CountdownState.FINISHED
                    triggerAlarm()
                    break
                }
                // 向上取整：剩余 0.1s 也显示 1s
                remainingSeconds = (remainingMs + 999) / 1000
                delay(200)
            }
        }
    }

    // ── 秒表逻辑 ──
    // 修复"1秒顶真实2秒"：旧实现 delay(17); elapsedMs+=17 忽略了调度与重组开销，
    // 单次循环实际耗时 >17ms 却只计 17ms，导致走时比真实慢一半。
    // 现基于 SystemClock.elapsedRealtime() 真实时间戳计算，delay 仅控制刷新频率。
    // finally 固化：协程被取消（切到 PAUSED/IDLE）时把本段已运行时长并入累计值。
    LaunchedEffect(stopwatchState) {
        if (stopwatchState == StopwatchState.RUNNING) {
            val startRealtime = SystemClock.elapsedRealtime()
            val base = accumulatedMs
            try {
                while (true) {
                    delay(16) // 仅控制 UI 刷新频率（~60fps），不参与计时
                    elapsedMs = base + (SystemClock.elapsedRealtime() - startRealtime)
                }
            } finally {
                accumulatedMs = base + (SystemClock.elapsedRealtime() - startRealtime)
                elapsedMs = accumulatedMs
            }
        }
    }

    // 闹钟闪烁动画
    val alarmAlpha by animateFloatAsState(
        targetValue = if (showAlarm && countdownState == CountdownState.FINISHED) 1f else 0f,
        animationSpec = if (showAlarm) {
            infiniteRepeatable(
                animation = tween(600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        } else {
            tween(300)
        },
        label = "alarmAlpha"
    )

    val alarmColor by animateColorAsState(
        targetValue = if (showAlarm && countdownState == CountdownState.FINISHED)
            FluidPink else FluidCyan,
        animationSpec = tween(400),
        label = "alarmColor"
    )

    LiquidGlassScaffold(animTime = animTime) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // ── 顶栏 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = appTextSecondary())
                }
                Text("计时器", fontSize = 16.sp, color = appTextSecondary(), modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Tab 切换 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TimerTabButton("倒计时", TimerTab.COUNTDOWN, selectedTab) {
                    selectedTab = it
                    if (countdownState == CountdownState.FINISHED) {
                        showAlarm = false
                    }
                }
                TimerTabButton("秒表", TimerTab.STOPWATCH, selectedTab) { selectedTab = it }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (selectedTab) {
                TimerTab.COUNTDOWN -> CountdownContent(
                    state = countdownState,
                    totalSeconds = totalSeconds,
                    remainingSeconds = remainingSeconds,
                    pickerHours = pickerHours,
                    pickerMinutes = pickerMinutes,
                    pickerSeconds = pickerSeconds,
                    showAlarm = showAlarm,
                    alarmAlpha = alarmAlpha,
                    alarmColor = alarmColor,
                    onPickerHoursChange = { pickerHours = it },
                    onPickerMinutesChange = { pickerMinutes = it },
                    onPickerSecondsChange = { pickerSeconds = it },
                    onStart = {
                        if (countdownState == CountdownState.IDLE || countdownState == CountdownState.FINISHED) {
                            totalSeconds = (pickerHours * 3600L + pickerMinutes * 60L + pickerSeconds)
                            if (totalSeconds > 0) {
                                remainingSeconds = totalSeconds
                                // 设置结束时间戳：当前真实时间 + 总秒数
                                countdownEndRealtime = SystemClock.elapsedRealtime() + totalSeconds * 1000
                                countdownState = CountdownState.RUNNING
                                showAlarm = false
                            }
                        } else {
                            // 从暂停恢复：用剩余秒数重新计算结束时间戳
                            countdownEndRealtime = SystemClock.elapsedRealtime() + remainingSeconds * 1000
                            countdownState = CountdownState.RUNNING
                        }
                    },
                    onPause = { countdownState = CountdownState.PAUSED },
                    onReset = {
                        countdownState = CountdownState.IDLE
                        remainingSeconds = 0L
                        totalSeconds = 0L
                        countdownEndRealtime = 0L
                        showAlarm = false
                    },
                    onPreset = { seconds ->
                        countdownState = CountdownState.IDLE
                        totalSeconds = seconds
                        remainingSeconds = seconds
                        countdownEndRealtime = 0L
                        pickerHours = (seconds / 3600).toInt()
                        pickerMinutes = ((seconds % 3600) / 60).toInt()
                        pickerSeconds = (seconds % 60).toInt()
                        showAlarm = false
                    }
                )
                TimerTab.STOPWATCH -> StopwatchContent(
                    state = stopwatchState,
                    elapsedMs = elapsedMs,
                    lapTimes = lapTimes,
                    onStart = { stopwatchState = StopwatchState.RUNNING },
                    onPause = { stopwatchState = StopwatchState.PAUSED },
                    onReset = {
                        stopwatchState = StopwatchState.IDLE
                        elapsedMs = 0L
                        accumulatedMs = 0L
                        lapTimes = emptyList()
                        lastLapMs = 0L
                    },
                    onLap = {
                        val lapMs = elapsedMs - lastLapMs
                        lapTimes = lapTimes + lapMs
                        lastLapMs = elapsedMs
                    }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

// ── Tab 按钮 ──
@Composable
private fun RowScope.TimerTabButton(label: String, tab: TimerTab, selected: TimerTab, onClick: (TimerTab) -> Unit) {
    val isSelected = selected == tab
    val bgModifier = if (isSelected) {
        Modifier.background(Brush.horizontalGradient(listOf(FluidCyan.copy(alpha = 0.2f), FluidPurple.copy(alpha = 0.2f))))
    } else {
        Modifier.background(Color.White.copy(alpha = 0.05f))
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .then(bgModifier)
            .clickable { onClick(tab) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = if (isSelected) FluidCyan else TextTertiary,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

// ═══════════════ 倒计时内容 ═══════════════
@Composable
private fun CountdownContent(
    state: CountdownState,
    totalSeconds: Long,
    remainingSeconds: Long,
    pickerHours: Int,
    pickerMinutes: Int,
    pickerSeconds: Int,
    showAlarm: Boolean,
    alarmAlpha: Float,
    alarmColor: Color,
    onPickerHoursChange: (Int) -> Unit,
    onPickerMinutesChange: (Int) -> Unit,
    onPickerSecondsChange: (Int) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    onPreset: (Long) -> Unit
) {
    val isIdle = state == CountdownState.IDLE
    val isFinished = state == CountdownState.FINISHED
    val isRunning = state == CountdownState.RUNNING
    val isPaused = state == CountdownState.PAUSED

    // ── 圆形进度与时间显示 ──
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        val progress = if (totalSeconds > 0) {
            remainingSeconds.toFloat() / totalSeconds.toFloat()
        } else 0f

        val progressColor = when {
            isFinished -> FluidPink
            progress < 0.1f -> FluidOrange
            progress < 0.3f -> FluidPink
            else -> alarmColor
        }

        val pulseScale by animateFloatAsState(
            targetValue = if (isFinished && showAlarm) 1.04f else 1f,
            animationSpec = if (isFinished && showAlarm) {
                infiniteRepeatable(
                    animation = tween(800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                )
            } else {
                tween(300)
            },
            label = "pulseScale"
        )

        Canvas(
            modifier = Modifier
                .size(240.dp)
                .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale }
        ) {
            val strokeWidth = 10.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2
            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
            val arcSize = Size(radius * 2, radius * 2)

            // 背景轨道
            drawArc(
                color = Color.White.copy(alpha = 0.08f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // 进度弧
            if (totalSeconds > 0) {
                val sweepAngle = progress * 360f
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            progressColor.copy(alpha = 0.3f),
                            progressColor,
                            progressColor.copy(alpha = 0.7f)
                        )
                    ),
                    startAngle = -90f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // 进度末端发光点
                val endAngle = Math.toRadians((-90 + sweepAngle).toDouble())
                val dotX = size.width / 2 + radius * cos(endAngle).toFloat()
                val dotY = size.height / 2 + radius * sin(endAngle).toFloat()
                drawCircle(
                    color = progressColor.copy(alpha = 0.9f),
                    radius = 7.dp.toPx(),
                    center = Offset(dotX, dotY)
                )
                drawCircle(
                    color = progressColor.copy(alpha = 0.3f),
                    radius = 15.dp.toPx(),
                    center = Offset(dotX, dotY)
                )
            }
        }

        // 时间文字
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            if (isIdle) {
                // 闲置时显示选择器直读
                val displayTime = formatTimeDisplay(pickerHours, pickerMinutes, pickerSeconds)
                Text(
                    displayTime,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Thin,
                    color = appTextPrimary(),
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "滑动下方滚轮设置时间",
                    fontSize = 11.sp,
                    color = appTextTertiary()
                )
            } else {
                val displayTime = formatTimeFromSeconds(remainingSeconds)
                Text(
                    displayTime,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Thin,
                    color = if (isFinished) FluidPink.copy(alpha = 0.7f + alarmAlpha * 0.3f) else TextPrimary,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isFinished) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "⏰ 时间到！",
                        fontSize = 14.sp,
                        color = FluidPink.copy(alpha = 0.5f + alarmAlpha * 0.5f),
                        fontWeight = FontWeight.Medium
                    )
                } else if (isPaused) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("已暂停", fontSize = 12.sp, color = appTextTertiary())
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // ── 时间选择器（仅闲置时显示） ──
    if (isIdle) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(cornerRadius = 24.dp, glassAlpha = 0.15f)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TimePickerColumn("时", 0, 23, pickerHours, onPickerHoursChange)
                Text(":", fontSize = 28.sp, color = appTextSecondary(), fontWeight = FontWeight.Thin)
                TimePickerColumn("分", 0, 59, pickerMinutes, onPickerMinutesChange)
                Text(":", fontSize = 28.sp, color = appTextSecondary(), fontWeight = FontWeight.Thin)
                TimePickerColumn("秒", 0, 59, pickerSeconds, onPickerSecondsChange)
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // ── 预设快捷按钮 ──
    if (isIdle || isFinished) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.12f)
                .padding(12.dp)
        ) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val presets = listOf(
                    "1分" to 60L,
                    "3分" to 180L,
                    "5分" to 300L,
                    "10分" to 600L,
                    "15分" to 900L,
                    "30分" to 1800L
                )
                items(presets.size) { i ->
                    val (label, secs) = presets[i]
                    PresetChip(label, onClick = { onPreset(secs) })
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // ── 控制按钮 ──
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isRunning) {
            // 暂停
            ControlButton(
                text = "暂停",
                icon = Icons.Default.Pause,
                color = FluidOrange,
                modifier = Modifier.weight(1f),
                onClick = onPause
            )
        } else if (isPaused || isIdle) {
            // 开始
            val canStart = if (isIdle) {
                pickerHours > 0 || pickerMinutes > 0 || pickerSeconds > 0
            } else true
            ControlButton(
                text = if (isPaused) "继续" else "开始",
                icon = Icons.Default.PlayArrow,
                color = FluidTeal,
                modifier = Modifier.weight(if (isIdle || isPaused) 1f else 0.5f),
                enabled = canStart,
                onClick = onStart
            )
        }

        if (isFinished) {
            ControlButton(
                text = "重新开始",
                icon = Icons.Default.Replay,
                color = FluidCyan,
                modifier = Modifier.weight(1f),
                onClick = onStart
            )
        }

        if (state != CountdownState.IDLE) {
            ControlButton(
                text = "重置",
                icon = Icons.Default.Refresh,
                color = FluidPurple,
                modifier = Modifier.weight(1f),
                onClick = onReset
            )
        }
    }
}

// ═══════════════ 秒表内容 ═══════════════
@Composable
private fun StopwatchContent(
    state: StopwatchState,
    elapsedMs: Long,
    lapTimes: List<Long>,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    onLap: () -> Unit
) {
    val isIdle = state == StopwatchState.IDLE
    val isRunning = state == StopwatchState.RUNNING
    val isPaused = state == StopwatchState.PAUSED

    // ── 秒表显示 ──
    val totalMs = elapsedMs
    val hours = (totalMs / 3600000)
    val minutes = (totalMs % 3600000) / 60000
    val seconds = (totalMs % 60000) / 1000
    val centiseconds = (totalMs % 1000) / 10

    val displayText = String.format("%02d:%02d:%02d.%02d", hours, minutes, seconds, centiseconds)

    // 秒表圆环
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        // 外圈秒扫动画
        val sweepAngle = remember(totalMs) {
            ((totalMs % 60000).toFloat() / 60000f) * 360f
        }

        Canvas(modifier = Modifier.size(220.dp)) {
            val strokeWidth = 8.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2
            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
            val arcSize = Size(radius * 2, radius * 2)

            // 轨道
            drawArc(
                color = Color.White.copy(alpha = 0.06f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // 分钟刻度
            for (i in 0 until 60) {
                val angle = Math.toRadians((i * 6.0) - 90)
                val innerR = radius - strokeWidth * 0.5f
                val outerR = radius + strokeWidth * 0.3f
                val isMainTick = i % 5 == 0
                val tickLen = if (isMainTick) 12.dp.toPx() else 6.dp.toPx()
                val tickStart = if (isMainTick) innerR - tickLen else innerR - tickLen * 0.7f
                val tickEnd = innerR
                val startX = size.width / 2 + tickStart * cos(angle).toFloat()
                val startY = size.height / 2 + tickStart * sin(angle).toFloat()
                val endX = size.width / 2 + tickEnd * cos(angle).toFloat()
                val endY = size.height / 2 + tickEnd * sin(angle).toFloat()
                drawLine(
                    color = if (isMainTick) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.06f),
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = if (isMainTick) 2.dp.toPx() else 1.dp.toPx()
                )
            }

            // 秒扫弧
            if (isRunning || isPaused) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(FluidCyan, FluidPurple, FluidCyan)
                    ),
                    startAngle = -90f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        // 时间文字
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                displayText,
                fontSize = 36.sp,
                fontWeight = FontWeight.Thin,
                color = if (isRunning) FluidCyan else TextPrimary,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            if (isIdle) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("点击开始计时", fontSize = 11.sp, color = appTextTertiary())
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // ── 控制按钮 ──
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isIdle) {
            // 开始
            ControlButton(
                text = "开始",
                icon = Icons.Default.PlayArrow,
                color = FluidTeal,
                modifier = Modifier.weight(1f),
                onClick = onStart
            )
        } else if (isRunning) {
            // 计次 + 暂停
            ControlButton(
                text = "计次",
                icon = Icons.Default.Flag,
                color = FluidOrange,
                modifier = Modifier.weight(1f),
                onClick = onLap
            )
            ControlButton(
                text = "暂停",
                icon = Icons.Default.Pause,
                color = FluidPink,
                modifier = Modifier.weight(1f),
                onClick = onPause
            )
        } else if (isPaused) {
            // 继续 + 重置
            ControlButton(
                text = "继续",
                icon = Icons.Default.PlayArrow,
                color = FluidTeal,
                modifier = Modifier.weight(1f),
                onClick = onStart
            )
            ControlButton(
                text = "重置",
                icon = Icons.Default.Refresh,
                color = FluidPurple,
                modifier = Modifier.weight(1f),
                onClick = onReset
            )
        }
    }

    // ── 计次列表 ──
    if (lapTimes.isNotEmpty()) {
        Spacer(modifier = Modifier.height(16.dp))

        val bestLap = lapTimes.minOrNull() ?: 0L
        val worstLap = lapTimes.maxOrNull() ?: 0L

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(cornerRadius = 24.dp, glassAlpha = 0.15f)
                .padding(16.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("计次记录", fontSize = 14.sp, color = appTextSecondary(), fontWeight = FontWeight.Medium)
                    Text("${lapTimes.size} 次", fontSize = 12.sp, color = appTextTertiary())
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 表头
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("#", fontSize = 11.sp, color = appTextTertiary(), modifier = Modifier.width(30.dp))
                    Text("分段时间", fontSize = 11.sp, color = appTextTertiary(), modifier = Modifier.weight(1f))
                    Text("累计时间", fontSize = 11.sp, color = appTextTertiary(), modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 4.dp))

                // 反向显示（最新的在上面）
                // 修复 O(n²) 性能：原实现每行都从头重新求和计算累计时间，
                // 计次次数多时每次重组都卡顿。现用前缀和数组预计算，O(n) 一次搞定。
                val reversed = lapTimes.reversed()
                // 前缀和：prefixSum[k] = lapTimes[0] + ... + lapTimes[k-1]
                val prefixSum = LongArray(lapTimes.size + 1)
                for (k in lapTimes.indices) {
                    prefixSum[k + 1] = prefixSum[k] + lapTimes[k]
                }

                for (i in reversed.indices) {
                    val lapMs = reversed[i]
                    // 累计 = 前 (lapTimes.size - i) 个分段的和
                    val cumulative = prefixSum[lapTimes.size - i]

                    val isBest = lapMs == bestLap && lapTimes.size > 1
                    val isWorst = lapMs == worstLap && lapTimes.size > 1

                    val highlightColor = when {
                        isBest -> FluidTeal
                        isWorst -> FluidPink
                        else -> Color.Transparent
                    }

                    val rowBg = when {
                        isBest -> FluidTeal.copy(alpha = 0.08f)
                        isWorst -> FluidPink.copy(alpha = 0.08f)
                        else -> Color.Transparent
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(rowBg)
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.width(30.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isBest || isWorst) {
                                Canvas(modifier = Modifier.size(6.dp)) {
                                    drawCircle(color = highlightColor)
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                "${lapTimes.size - i}",
                                fontSize = 12.sp,
                                color = if (isBest || isWorst) highlightColor else TextSecondary,
                                fontWeight = if (isBest || isWorst) FontWeight.Medium else FontWeight.Normal
                            )
                        }

                        Text(
                            formatLapTime(lapMs),
                            fontSize = 13.sp,
                            color = if (isBest) FluidTeal else if (isWorst) FluidPink else TextPrimary,
                            fontWeight = if (isBest || isWorst) FontWeight.Medium else FontWeight.Light,
                            modifier = Modifier.weight(1f)
                        )

                        Text(
                            formatLapTime(cumulative),
                            fontSize = 13.sp,
                            color = appTextSecondary(),
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        }
    }
}

// ── 时间选择器滚轮 ──
@Composable
private fun TimePickerColumn(
    label: String,
    min: Int,
    max: Int,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(60.dp)
    ) {
        // 上箭头
        IconButton(
            onClick = {
                if (value < max) onValueChange(value + 1)
            },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = "+",
                tint = appTextSecondary(),
                modifier = Modifier.size(20.dp)
            )
        }

        // 数值显示
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                String.format("%02d", value),
                fontSize = 28.sp,
                fontWeight = FontWeight.Thin,
                color = appTextPrimary(),
                letterSpacing = 2.sp
            )
        }

        // 下箭头
        IconButton(
            onClick = {
                if (value > min) onValueChange(value - 1)
            },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "-",
                tint = appTextSecondary(),
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(label, fontSize = 10.sp, color = appTextTertiary())
    }
}

// ── 预设快捷按钮 ──
@Composable
private fun PresetChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        FluidCyan.copy(alpha = 0.12f),
                        FluidPurple.copy(alpha = 0.12f)
                    )
                )
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = FluidCyan,
            fontWeight = FontWeight.Medium
        )
    }
}

// ── 控制按钮 ──
@Composable
private fun ControlButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (enabled) {
                    Modifier.background(
                        Brush.horizontalGradient(
                            listOf(
                                color.copy(alpha = 0.25f),
                                color.copy(alpha = 0.1f)
                            )
                        )
                    )
                } else {
                    Modifier.background(Color.White.copy(alpha = 0.05f))
                }
            )
            .clickable(enabled = enabled) { onClick() }
            .graphicsLayer { this.alpha = alpha },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = text,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text,
                fontSize = 14.sp,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ── 格式化工具 ──
private fun formatTimeDisplay(h: Int, m: Int, s: Int): String {
    return String.format("%02d:%02d:%02d", h, m, s)
}

private fun formatTimeFromSeconds(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return String.format("%02d:%02d:%02d", h, m, s)
}

private fun formatLapTime(ms: Long): String {
    val minutes = (ms % 3600000) / 60000
    val seconds = (ms % 60000) / 1000
    val centis = (ms % 1000) / 10
    return String.format("%02d:%02d.%02d", minutes, seconds, centis)
}