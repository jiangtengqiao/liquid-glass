package com.liquidglass.app.ui

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.isActive
import kotlin.math.*

private enum class FlashlightTab { FLASHLIGHT, SCREEN_TOOLS }

// ── 手电筒模式 ──
private enum class FlashMode { OFF, ON, SOS, STROBE, SCREEN_LIGHT }

private data class ScreenTimeoutOption(
    val label: String,
    val valueMs: Long
)

@Composable
fun FlashlightScreen(animTime: Float, onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(FlashlightTab.FLASHLIGHT) }

    // ── 手电筒状态 ──
    var flashMode by remember { mutableStateOf(FlashMode.OFF) }
    val cameraManager = remember { context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager }
    var cameraId by remember { mutableStateOf<String?>(null) }
    var strobeFrequency by remember { mutableStateOf(8f) } // Hz

    // 获取闪光灯相机ID
    LaunchedEffect(Unit) {
        try {
            cameraManager?.let { cm ->
                for (id in cm.cameraIdList) {
                    val characteristics = cm.getCameraCharacteristics(id)
                    val hasFlash = characteristics.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                    val lensFacing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                    if (hasFlash && lensFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                        cameraId = id
                        break
                    }
                }
                if (cameraId == null) {
                    for (id in cm.cameraIdList) {
                        val characteristics = cm.getCameraCharacteristics(id)
                        val hasFlash = characteristics.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                        if (hasFlash) {
                            cameraId = id
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // 关闭闪光灯
    fun turnOffFlash() {
        try {
            cameraManager?.setTorchMode(cameraId ?: return, false)
        } catch (_: Exception) {}
    }

    // 打开闪光灯
    fun turnOnFlash() {
        try {
            cameraManager?.setTorchMode(cameraId ?: return, true)
        } catch (_: Exception) {}
    }

    // 切换手电筒
    fun setFlashMode(mode: FlashMode) {
        if (flashMode == FlashMode.SOS || flashMode == FlashMode.STROBE) {
            turnOffFlash()
        }
        flashMode = mode
        when (mode) {
            FlashMode.OFF -> turnOffFlash()
            FlashMode.ON -> turnOnFlash()
            FlashMode.SCREEN_LIGHT -> {}
            FlashMode.SOS -> {}
            FlashMode.STROBE -> {}
        }
    }

    // SOS 模式闪烁逻辑
    LaunchedEffect(flashMode) {
        if (flashMode == FlashMode.SOS) {
            val dotDuration = 200L
            val dashDuration = 600L
            val elementGap = 200L
            val letterGap = 600L
            val cycleGap = 1400L

            val pattern = listOf(
                Pair(true, dotDuration), Pair(false, elementGap),
                Pair(true, dotDuration), Pair(false, elementGap),
                Pair(true, dotDuration), Pair(false, letterGap),
                Pair(true, dashDuration), Pair(false, elementGap),
                Pair(true, dashDuration), Pair(false, elementGap),
                Pair(true, dashDuration), Pair(false, letterGap),
                Pair(true, dotDuration), Pair(false, elementGap),
                Pair(true, dotDuration), Pair(false, elementGap),
                Pair(true, dotDuration), Pair(false, cycleGap)
            )

            while (isActive && flashMode == FlashMode.SOS) {
                for ((on, dur) in pattern) {
                    if (flashMode != FlashMode.SOS) break
                    try {
                        cameraManager?.setTorchMode(cameraId ?: "", on)
                    } catch (_: Exception) {}
                    delay(dur)
                }
            }
        }
    }

    // 频闪模式逻辑
    LaunchedEffect(flashMode, strobeFrequency) {
        if (flashMode == FlashMode.STROBE) {
            val periodMs = (1000f / strobeFrequency).toLong()
            val halfPeriod = (periodMs / 2).coerceAtLeast(20)
            while (isActive && flashMode == FlashMode.STROBE) {
                try {
                    cameraManager?.setTorchMode(cameraId ?: "", true)
                } catch (_: Exception) {}
                delay(halfPeriod)
                if (flashMode != FlashMode.STROBE) break
                try {
                    cameraManager?.setTorchMode(cameraId ?: "", false)
                } catch (_: Exception) {}
                delay(halfPeriod)
            }
        }
    }

    // 清理
    DisposableEffect(Unit) {
        onDispose { turnOffFlash() }
    }

    // ── 屏幕工具状态 ──
    val windowManager = remember { context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager }
    val displayMetrics = remember { DisplayMetrics() }
    var screenBrightness by remember { mutableFloatStateOf(0.5f) }
    var currentTimeout by remember { mutableLongStateOf(0L) }
    var keepScreenOn by remember { mutableStateOf(false) }
    var colorTemperature by remember { mutableFloatStateOf(0f) }
    var screenLightActive by remember { mutableStateOf(false) }
    var screenLightBrightness by remember { mutableFloatStateOf(1f) }

    // 记录原始系统亮度/超时，退出时还原（避免修改后不恢复导致耗电/不熄屏）
    var originalBrightness by remember { mutableStateOf(-1) }
    var originalTimeout by remember { mutableLongStateOf(-1L) }
    // 防止亮度滑块无权限时反复拉起系统设置 Activity（仅提示一次）
    var hasPromptedWriteSettings by remember { mutableStateOf(false) }

    // 读取屏幕信息
    LaunchedEffect(Unit) {
        try {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getMetrics(displayMetrics)
        } catch (_: Exception) {}

        try {
            val brightness = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            screenBrightness = brightness / 255f
            originalBrightness = brightness // 保存原始亮度，退出时还原
        } catch (_: Exception) {}

        try {
            currentTimeout = Settings.System.getLong(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT
            )
            originalTimeout = currentTimeout // 保存原始超时，退出时还原
        } catch (_: Exception) {}

        // Refresh rate info is read on-demand in ScreenInfoRow
    }

    // 退出页面时还原系统亮度/超时（修复"修改后不恢复导致持续高亮耗电"）
    DisposableEffect(Unit) {
        onDispose {
            turnOffFlash()
            // 还原系统亮度和屏幕超时
            if (originalBrightness >= 0) {
                try {
                    if (Settings.System.canWrite(context)) {
                        Settings.System.putInt(
                            context.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS,
                            originalBrightness
                        )
                    }
                } catch (_: Exception) {}
            }
            if (originalTimeout > 0) {
                try {
                    if (Settings.System.canWrite(context)) {
                        Settings.System.putLong(
                            context.contentResolver,
                            Settings.System.SCREEN_OFF_TIMEOUT,
                            originalTimeout
                        )
                    }
                } catch (_: Exception) {}
            }
        }
    }

    val timeoutOptions = remember {
        listOf(
            ScreenTimeoutOption("15秒", 15_000L),
            ScreenTimeoutOption("30秒", 30_000L),
            ScreenTimeoutOption("1分钟", 60_000L),
            ScreenTimeoutOption("2分钟", 120_000L),
            ScreenTimeoutOption("5分钟", 300_000L),
            ScreenTimeoutOption("10分钟", 600_000L),
            ScreenTimeoutOption("30分钟", 1_800_000L),
            ScreenTimeoutOption("永不", Long.MAX_VALUE)
        )
    }

    val hasFlashlight = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
    }

    // ── 主布局 ──
    LiquidGlassScaffold(animTime = animTime) {

        // 屏幕光模式覆盖层
        if (screenLightActive && flashMode == FlashMode.SCREEN_LIGHT) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = screenLightBrightness))
            )
        }

        // 色温覆盖层
        if (colorTemperature > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color(1f, 0.7f + 0.3f * (1f - colorTemperature), 0.3f + 0.7f * (1f - colorTemperature))
                            .copy(alpha = colorTemperature * 0.5f)
                    )
            )
        }

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
                IconButton(onClick = {
                    turnOffFlash()
                    onBack()
                }) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = appTextSecondary())
                }
                Text("手电筒·屏幕工具", fontSize = 16.sp, color = appTextSecondary(), modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Tab 切换 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TabButton("手电筒", FlashlightTab.FLASHLIGHT, selectedTab) {
                    selectedTab = it
                    if (flashMode == FlashMode.SCREEN_LIGHT) {
                        screenLightActive = false
                        flashMode = FlashMode.OFF
                    }
                }
                TabButton("屏幕工具", FlashlightTab.SCREEN_TOOLS, selectedTab) {
                    selectedTab = it
                    // 切到屏幕工具页签时关闭手电筒（避免闪光灯持续工作而无可视提示）
                    if (flashMode != FlashMode.OFF && flashMode != FlashMode.SCREEN_LIGHT) {
                        turnOffFlash()
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (selectedTab) {
                FlashlightTab.FLASHLIGHT -> FlashlightContent(
                    flashMode = flashMode,
                    hasFlashlight = hasFlashlight,
                    strobeFrequency = strobeFrequency,
                    screenLightActive = screenLightActive,
                    screenLightBrightness = screenLightBrightness,
                    onSetMode = { setFlashMode(it) },
                    onStrobeFrequencyChange = { strobeFrequency = it },
                    onScreenLightToggle = { active ->
                        screenLightActive = active
                        if (active) {
                            setFlashMode(FlashMode.SCREEN_LIGHT)
                        } else {
                            setFlashMode(FlashMode.OFF)
                        }
                    },
                    onScreenLightBrightnessChange = { screenLightBrightness = it }
                )
                FlashlightTab.SCREEN_TOOLS -> ScreenToolsContent(
                    brightness = screenBrightness,
                    currentTimeout = currentTimeout,
                    timeoutOptions = timeoutOptions,
                    keepScreenOn = keepScreenOn,
                    colorTemperature = colorTemperature,
                    displayMetrics = displayMetrics,
                    onBrightnessChange = { value ->
                        screenBrightness = value
                        try {
                            val brightnessVal = (value * 255).toInt().coerceIn(0, 255)
                            if (Settings.System.canWrite(context)) {
                                Settings.System.putInt(
                                    context.contentResolver,
                                    Settings.System.SCREEN_BRIGHTNESS,
                                    brightnessVal
                                )
                            } else {
                                // 修复：滑块拖动高频触发，仅首次无权限时引导一次，
                                // 避免瞬间堆叠数十个系统设置 Activity 导致 ANR/卡死
                                if (!hasPromptedWriteSettings) {
                                    hasPromptedWriteSettings = true
                                    val intent = android.content.Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                        data = android.net.Uri.parse("package:${context.packageName}")
                                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                }
                            }
                        } catch (_: Exception) {}
                    },
                    onTimeoutChange = { valueMs ->
                        currentTimeout = valueMs
                        try {
                            if (Settings.System.canWrite(context)) {
                                Settings.System.putLong(
                                    context.contentResolver,
                                    Settings.System.SCREEN_OFF_TIMEOUT,
                                    valueMs
                                )
                            }
                        } catch (_: Exception) {}
                    },
                    onKeepScreenOnChange = { enabled ->
                        keepScreenOn = enabled
                        try {
                            if (context is android.app.Activity) {
                                if (enabled) {
                                    context.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                } else {
                                    context.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                }
                            }
                        } catch (_: Exception) {}
                    },
                    onColorTemperatureChange = { colorTemperature = it }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

// ── Tab 按钮 ──
@Composable
private fun RowScope.TabButton(label: String, tab: FlashlightTab, selected: FlashlightTab, onClick: (FlashlightTab) -> Unit) {
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

// ═══════════════ 手电筒内容 ═══════════════
@Composable
private fun FlashlightContent(
    flashMode: FlashMode,
    hasFlashlight: Boolean,
    strobeFrequency: Float,
    screenLightActive: Boolean,
    screenLightBrightness: Float,
    onSetMode: (FlashMode) -> Unit,
    onStrobeFrequencyChange: (Float) -> Unit,
    onScreenLightToggle: (Boolean) -> Unit,
    onScreenLightBrightnessChange: (Float) -> Unit
) {
    val isOn = flashMode != FlashMode.OFF
    val isSOS = flashMode == FlashMode.SOS
    val isStrobe = flashMode == FlashMode.STROBE
    val isScreenLight = flashMode == FlashMode.SCREEN_LIGHT

    // ── 主开关按钮 ──
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        // 发光效果
        if (isOn && !isScreenLight) {
            val glowScale by animateFloatAsState(
                targetValue = if (isOn) 1.15f else 1f,
                animationSpec = if (isSOS || isStrobe) {
                    repeatable(
                        iterations = Int.MAX_VALUE,
                        animation = tween(500, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                } else {
                    tween(600)
                },
                label = "glowScale"
            )

            val glowColor = when (flashMode) {
                FlashMode.ON -> FluidCyan
                FlashMode.SOS -> FluidOrange
                FlashMode.STROBE -> FluidPink
                else -> FluidCyan
            }

            Canvas(modifier = Modifier.size(180.dp).scale(glowScale)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = 0.25f),
                            glowColor.copy(alpha = 0.08f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = size.minDimension / 2
                    ),
                    radius = size.minDimension / 2
                )
                drawCircle(
                    color = glowColor.copy(alpha = 0.12f),
                    radius = size.minDimension / 2 * 0.7f
                )
            }
        }

        // 主按钮
        val buttonColor by animateColorAsState(
            targetValue = when {
                isScreenLight -> Color.White
                flashMode == FlashMode.ON -> FluidCyan
                isSOS -> FluidOrange
                isStrobe -> FluidPink
                else -> Color.White.copy(alpha = 0.12f)
            },
            animationSpec = tween(400),
            label = "buttonColor"
        )

        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            buttonColor.copy(alpha = if (isOn) 0.25f else 0.08f),
                            buttonColor.copy(alpha = if (isOn) 0.08f else 0.03f)
                        )
                    )
                )
                .clickable {
                    if (isScreenLight) {
                        onScreenLightToggle(false)
                    } else if (hasFlashlight) {
                        onSetMode(if (isOn) FlashMode.OFF else FlashMode.ON)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (isScreenLight) Icons.Default.BrightnessHigh
                    else if (isOn) Icons.Default.FlashlightOn
                    else Icons.Default.FlashlightOff,
                    contentDescription = null,
                    tint = when {
                        isScreenLight -> Color(0xFFFFD700)
                        isOn -> buttonColor
                        else -> TextSecondary
                    },
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when {
                        isScreenLight -> "屏幕光"
                        isOn && !isSOS && !isStrobe -> "已开启"
                        isSOS -> "SOS"
                        isStrobe -> "频闪"
                        else -> "已关闭"
                    },
                    fontSize = 13.sp,
                    color = when {
                        isScreenLight -> Color(0xFFFFD700)
                        isOn -> buttonColor
                        else -> TextSecondary
                    },
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // ── 模式选择卡片 ──
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 24.dp, glassAlpha = 0.15f)
            .padding(16.dp)
    ) {
        Column {
            Text("模式选择", fontSize = 14.sp, color = appTextSecondary(), fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeButton(
                    label = "SOS",
                    icon = Icons.Default.Sos,
                    isActive = isSOS,
                    activeColor = FluidOrange,
                    modifier = Modifier.weight(1f),
                    enabled = hasFlashlight,
                    onClick = {
                        onSetMode(if (isSOS) FlashMode.OFF else FlashMode.SOS)
                    }
                )
                ModeButton(
                    label = "频闪",
                    icon = Icons.Default.FlashAuto,
                    isActive = isStrobe,
                    activeColor = FluidPink,
                    modifier = Modifier.weight(1f),
                    enabled = hasFlashlight,
                    onClick = {
                        onSetMode(if (isStrobe) FlashMode.OFF else FlashMode.STROBE)
                    }
                )
                ModeButton(
                    label = "屏幕光",
                    icon = Icons.Default.BrightnessHigh,
                    isActive = isScreenLight,
                    activeColor = Color(0xFFFFD700),
                    modifier = Modifier.weight(1f),
                    enabled = true,
                    onClick = {
                        if (isScreenLight) {
                            onScreenLightToggle(false)
                        } else {
                            onScreenLightToggle(true)
                        }
                    }
                )
            }
        }
    }

    // ── 频闪频率滑块 ──
    if (isStrobe) {
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.12f)
                .padding(16.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("频闪频率", fontSize = 13.sp, color = appTextSecondary())
                    Text(
                        "${strobeFrequency.toInt()} Hz",
                        fontSize = 13.sp,
                        color = FluidPink,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = strobeFrequency,
                    onValueChange = onStrobeFrequencyChange,
                    valueRange = 2f..30f,
                    steps = 27,
                    colors = SliderDefaults.colors(
                        thumbColor = FluidPink,
                        activeTrackColor = FluidPink,
                        inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("2 Hz", fontSize = 10.sp, color = appTextTertiary())
                    Text("30 Hz", fontSize = 10.sp, color = appTextTertiary())
                }
            }
        }
    }

    // ── 屏幕光亮度滑块 ──
    if (isScreenLight) {
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.12f)
                .padding(16.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("屏幕亮度", fontSize = 13.sp, color = appTextSecondary())
                    Text(
                        "${(screenLightBrightness * 100).toInt()}%",
                        fontSize = 13.sp,
                        color = Color(0xFFFFD700),
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = screenLightBrightness,
                    onValueChange = onScreenLightBrightnessChange,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFFD700),
                        activeTrackColor = Color(0xFFFFD700),
                        inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                    )
                )
            }
        }
    }

    // ── 无闪光灯提示 ──
    if (!hasFlashlight) {
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.1f)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "⚠ 此设备没有闪光灯，可使用屏幕光模式",
                fontSize = 12.sp,
                color = appTextTertiary(),
                textAlign = TextAlign.Center
            )
        }
    }
}

// ── 模式按钮 ──
@Composable
private fun ModeButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isActive) activeColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f),
        animationSpec = tween(300),
        label = "modeBg"
    )
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (isActive) activeColor else TextTertiary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            label,
            fontSize = 12.sp,
            color = if (isActive) activeColor else TextTertiary,
            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
        )
    }
}

// ═══════════════ 屏幕工具内容 ═══════════════
@Composable
private fun ScreenToolsContent(
    brightness: Float,
    currentTimeout: Long,
    timeoutOptions: List<ScreenTimeoutOption>,
    keepScreenOn: Boolean,
    colorTemperature: Float,
    displayMetrics: DisplayMetrics,
    onBrightnessChange: (Float) -> Unit,
    onTimeoutChange: (Long) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onColorTemperatureChange: (Float) -> Unit
) {
    // ── 亮度控制 ──
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.BrightnessMedium,
                        contentDescription = null,
                        tint = FluidCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("屏幕亮度", fontSize = 14.sp, color = appTextSecondary(), fontWeight = FontWeight.Medium)
                }
                Text(
                    "${(brightness * 100).toInt()}%",
                    fontSize = 13.sp,
                    color = FluidCyan,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = brightness,
                onValueChange = onBrightnessChange,
                colors = SliderDefaults.colors(
                    thumbColor = FluidCyan,
                    activeTrackColor = FluidCyan,
                    inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("暗", fontSize = 10.sp, color = appTextTertiary())
                Text("亮", fontSize = 10.sp, color = appTextTertiary())
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // ── 屏幕超时 ──
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 24.dp, glassAlpha = 0.15f)
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Timer,
                    contentDescription = null,
                    tint = FluidPurple,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("屏幕超时", fontSize = 14.sp, color = appTextSecondary(), fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                timeoutOptions.chunked(4).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { option ->
                            val isSelected = currentTimeout == option.valueMs
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSelected) FluidPurple.copy(alpha = 0.25f)
                                        else Color.White.copy(alpha = 0.05f)
                                    )
                                    .clickable { onTimeoutChange(option.valueMs) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    option.label,
                                    fontSize = 11.sp,
                                    color = if (isSelected) FluidPurple else TextTertiary,
                                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // ── 保持屏幕常亮 ──
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 24.dp, glassAlpha = 0.15f)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.ScreenLockPortrait,
                    contentDescription = null,
                    tint = FluidTeal,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("保持屏幕常亮", fontSize = 14.sp, color = appTextSecondary(), fontWeight = FontWeight.Medium)
                    Text(
                        if (keepScreenOn) "屏幕将不会自动关闭" else "屏幕将在超时后自动关闭",
                        fontSize = 11.sp,
                        color = appTextTertiary()
                    )
                }
            }
            Switch(
                checked = keepScreenOn,
                onCheckedChange = onKeepScreenOnChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = FluidTeal,
                    checkedTrackColor = FluidTeal.copy(alpha = 0.3f),
                    uncheckedThumbColor = TextTertiary,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                )
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // ── 色温调节 ──
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.WbTwilight,
                        contentDescription = null,
                        tint = FluidOrange,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("色温调节", fontSize = 14.sp, color = appTextSecondary(), fontWeight = FontWeight.Medium)
                }
                Text(
                    "${(colorTemperature * 100).toInt()}%",
                    fontSize = 13.sp,
                    color = FluidOrange,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                "护眼暖色模式",
                fontSize = 11.sp,
                color = appTextTertiary()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = colorTemperature,
                onValueChange = onColorTemperatureChange,
                colors = SliderDefaults.colors(
                    thumbColor = FluidOrange,
                    activeTrackColor = FluidOrange,
                    inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("正常", fontSize = 10.sp, color = appTextTertiary())
                Text("暖色", fontSize = 10.sp, color = appTextTertiary())
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // ── 屏幕信息 ──
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 24.dp, glassAlpha = 0.15f)
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = FluidCyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("屏幕信息", fontSize = 14.sp, color = appTextSecondary(), fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(12.dp))

            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            Spacer(modifier = Modifier.height(8.dp))

            ScreenInfoRow(
                label = "分辨率",
                value = "${displayMetrics.widthPixels} × ${displayMetrics.heightPixels}"
            )
            ScreenInfoRow(
                label = "密度",
                value = "${displayMetrics.densityDpi} dpi (${String.format("%.1f", displayMetrics.density)}x)"
            )
            val ctx = LocalContext.current
            val refreshRate = remember {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                        val display = wm?.defaultDisplay
                        val mode = display?.mode
                        if (mode != null) "${String.format("%.1f", mode.refreshRate)} Hz" else "未知"
                    } else {
                        "API 30+ 可用"
                    }
                } catch (_: Exception) {
                    "未知"
                }
            }
            val screenSize = remember {
                try {
                    val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm?.defaultDisplay?.getRealMetrics(metrics)
                    val widthInches = metrics.widthPixels / metrics.xdpi
                    val heightInches = metrics.heightPixels / metrics.ydpi
                    val diagonal = sqrt(widthInches * widthInches + heightInches * heightInches)
                    String.format("%.1f\"", diagonal)
                } catch (_: Exception) {
                    "未知"
                }
            }
            ScreenInfoRow(
                label = "刷新率",
                value = refreshRate
            )
            ScreenInfoRow(
                label = "屏幕尺寸",
                value = screenSize
            )
        }
    }
}

@Composable
private fun ScreenInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = appTextTertiary())
        Text(value, fontSize = 13.sp, color = appTextPrimary(), fontWeight = FontWeight.Medium)
    }
}