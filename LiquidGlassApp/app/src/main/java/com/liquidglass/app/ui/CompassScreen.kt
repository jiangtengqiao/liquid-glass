package com.liquidglass.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.liquidglass.app.ui.theme.*
import kotlin.math.*

enum class CompassTab { COMPASS, LEVEL }

@Composable
fun CompassScreen(animTime: Float, onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(CompassTab.COMPASS) }

    // ── 传感器状态 ──
    var azimuth by remember { mutableStateOf(0f) }
    var pitch by remember { mutableStateOf(0f) }
    var roll by remember { mutableStateOf(0f) }
    var accelerometerValues by remember { mutableStateOf(floatArrayOf(0f, 0f, 0f)) }

    // ── GPS 状态 ──
    var latitude by remember { mutableStateOf(0.0) }
    var longitude by remember { mutableStateOf(0.0) }
    var altitude by remember { mutableStateOf(0.0) }
    var hasLocation by remember { mutableStateOf(false) }
    var hasAltitude by remember { mutableStateOf(false) }

    // ── 指南针传感器可用性状态（用于给用户显示提示，杜绝"静默失败转不动"）──
    // OK: 正常工作；NO_SENSOR: 设备无方向传感器；CALIBRATE: 磁力计精度低需校准
    // WAITING: 传感器已注册但暂未收到数据；TIMEOUT: 传感器注册后超时无数据
    var compassStatus by remember { mutableStateOf("WAITING") }
    // 仅当方位角(azimuth)被成功计算时才设 true（用于超时检测）
    var hasReceivedAzimuth by remember { mutableStateOf(false) }
    // 重试触发器：用户点击"重试"时自增，驱动 DisposableEffect 重新注册传感器
    var retryTrigger by remember { mutableStateOf(0) }

    // ── 传感器监听 ──
    // v2.9.0 指南针终极根治：
    //  根因1：缺少 remapCoordinateSystem —— 手机竖直持握（屏幕朝向用户）时，
    //         设备坐标系 Z 轴朝向用户，直接用 getOrientation 得到的方位角完全错误，
    //         指针指向错误方向或乱转。必须 remapCoordinateSystem(R, AXIS_X, AXIS_Z, R)
    //         将设备坐标系转为"屏幕竖直"坐标系。
    //  根因2：无低通滤波 —— 传感器原始数据抖动大，指针高频抖动。
    //         加 alpha=0.15 的低通滤波器平滑原始 azimuth。
    //  根因3：兜底条件 —— 用 lastRotationVectorTime 时间戳判断旋转矢量活跃性。
    DisposableEffect(context, retryTrigger) {
        if (retryTrigger > 0) {
            compassStatus = "WAITING"
            hasReceivedAzimuth = false
        }
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val geomagneticRotationVector = if (rotationVector == null) {
            sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        } else null
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        val gravity = FloatArray(3)
        val geomagnetic = FloatArray(3)
        var hasGravity = false
        var hasGeomagnetic = false

        var lastRotationVectorTime = 0L
        val STALE_THRESHOLD_MS = 1500L

        // 低通滤波器系数：越小越平滑但响应越慢，0.15 是抖动与响应的平衡点
        val LOW_PASS_ALPHA = 0.15f
        var filteredAzimuth = 0f
        var azimuthInitialized = false

        val directionSensor = rotationVector ?: geomagneticRotationVector

        if (directionSensor == null && magnetometer == null) {
            compassStatus = "NO_SENSOR"
        }

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                        try {
                            val R = FloatArray(9)
                            SensorManager.getRotationMatrixFromVector(R, event.values)
                            // ★ 关键修复：remap 到竖直持握坐标系（屏幕朝向用户，顶部朝上）
                            // 设备 Z(出屏幕) → 显示 Y(上)；设备 X(右) → 显示 X(右)
                            val Rremapped = FloatArray(9)
                            SensorManager.remapCoordinateSystem(
                                R, SensorManager.AXIS_X, SensorManager.AXIS_Z, Rremapped
                            )
                            val orientation = FloatArray(3)
                            SensorManager.getOrientation(Rremapped, orientation)
                            val rawAzimuth = Math.toDegrees(orientation[0].toDouble()).toFloat().let {
                                if (it < 0) it + 360f else it
                            }
                            // 低通滤波平滑
                            if (azimuthInitialized) {
                                // 处理角度环绕的最短路径插值
                                val diff = ((rawAzimuth - filteredAzimuth + 540f) % 360f) - 180f
                                filteredAzimuth = (filteredAzimuth + diff * LOW_PASS_ALPHA + 360f) % 360f
                            } else {
                                filteredAzimuth = rawAzimuth
                                azimuthInitialized = true
                            }
                            azimuth = filteredAzimuth
                            pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                            roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                            hasReceivedAzimuth = true
                            lastRotationVectorTime = SystemClock.elapsedRealtime()
                            if (compassStatus != "OK") compassStatus = "OK"
                        } catch (_: Exception) {
                            lastRotationVectorTime = 0L
                        }
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        // 低通滤波加速度计数据（去高频抖动）
                        val alpha = 0.8f
                        gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
                        gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
                        gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]
                        hasGravity = true

                        val gX = event.values[0]
                        val gY = event.values[1]
                        val gZ = event.values[2]
                        pitch = Math.toDegrees(atan2(gX.toDouble(), sqrt((gY * gY + gZ * gZ).toDouble()))).toFloat()
                        roll = Math.toDegrees(atan2(gY.toDouble(), sqrt((gX * gX + gZ * gZ).toDouble()))).toFloat()

                        val rotationVectorStale = (SystemClock.elapsedRealtime() - lastRotationVectorTime) > STALE_THRESHOLD_MS
                        if (rotationVectorStale && hasGravity && hasGeomagnetic) {
                            val Rm = FloatArray(9)
                            val Im = FloatArray(9)
                            if (SensorManager.getRotationMatrix(Rm, Im, gravity, geomagnetic)) {
                                // ★ 同样 remap
                                val Rremapped = FloatArray(9)
                                SensorManager.remapCoordinateSystem(
                                    Rm, SensorManager.AXIS_X, SensorManager.AXIS_Z, Rremapped
                                )
                                val orientation = FloatArray(3)
                                SensorManager.getOrientation(Rremapped, orientation)
                                val rawAzimuth = Math.toDegrees(orientation[0].toDouble()).toFloat().let {
                                    if (it < 0) it + 360f else it
                                }
                                if (azimuthInitialized) {
                                    val diff = ((rawAzimuth - filteredAzimuth + 540f) % 360f) - 180f
                                    filteredAzimuth = (filteredAzimuth + diff * LOW_PASS_ALPHA + 360f) % 360f
                                } else {
                                    filteredAzimuth = rawAzimuth
                                    azimuthInitialized = true
                                }
                                azimuth = filteredAzimuth
                                hasReceivedAzimuth = true
                                if (compassStatus != "OK") compassStatus = "OK"
                            }
                        }
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        // 磁力计也做低通滤波
                        val alpha = 0.8f
                        geomagnetic[0] = alpha * geomagnetic[0] + (1 - alpha) * event.values[0]
                        geomagnetic[1] = alpha * geomagnetic[1] + (1 - alpha) * event.values[1]
                        geomagnetic[2] = alpha * geomagnetic[2] + (1 - alpha) * event.values[2]
                        hasGeomagnetic = true
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD && accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                    if (compassStatus == "OK") compassStatus = "CALIBRATE"
                } else if (compassStatus == "CALIBRATE" && accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
                    compassStatus = "OK"
                }
            }
        }

        var directionRegistered = false
        if (directionSensor != null) {
            directionRegistered = sensorManager.registerListener(
                sensorListener, directionSensor, SensorManager.SENSOR_DELAY_GAME
            )
        }
        if (accelerometer != null) {
            sensorManager.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        }
        if (magnetometer != null) {
            sensorManager.registerListener(sensorListener, magnetometer, SensorManager.SENSOR_DELAY_GAME)
        }

        if (!directionRegistered && magnetometer == null) {
            compassStatus = "NO_SENSOR"
        }

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutCheck = Runnable {
            if (!hasReceivedAzimuth && compassStatus == "WAITING") {
                compassStatus = "TIMEOUT"
            }
        }
        mainHandler.postDelayed(timeoutCheck, 3000)

        onDispose {
            mainHandler.removeCallbacks(timeoutCheck)
            sensorManager.unregisterListener(sensorListener)
        }
    }

    // ── GPS 监听 ──
    DisposableEffect(context) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var disposed = false

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (disposed) return
                latitude = location.latitude
                longitude = location.longitude
                hasLocation = true
                if (location.hasAltitude()) {
                    altitude = location.altitude
                    hasAltitude = true
                }
            }
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        val hasFinePermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarsePermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasFinePermission || hasCoarsePermission) {
            if (hasFinePermission) {
                locationManager.requestSingleUpdate(
                    LocationManager.GPS_PROVIDER, locationListener, Looper.getMainLooper()
                )
            }
            if (hasCoarsePermission) {
                locationManager.requestSingleUpdate(
                    LocationManager.NETWORK_PROVIDER, locationListener, Looper.getMainLooper()
                )
            }
            // 尝试获取上次已知位置
            try {
                val lastKnown = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (lastKnown != null) {
                    latitude = lastKnown.latitude
                    longitude = lastKnown.longitude
                    hasLocation = true
                    if (lastKnown.hasAltitude()) {
                        altitude = lastKnown.altitude
                        hasAltitude = true
                    }
                }
            } catch (_: Exception) {}
        }

        onDispose {
            disposed = true
            try {
                locationManager.removeUpdates(locationListener)
            } catch (_: Exception) {}
        }
    }

    // ── 平滑动画 ──
    // 关键修复（v2.8.7）：方位角使用"最短角路径"插值，避免穿越正北(359°→1°)时
    // 指针反向旋转 358° 的卡顿/抖动。原 animateFloatAsState 不感知角度环绕，
    // 当 azimuth 从 359 跳到 1 时会走 359→180→1 的长路径，造成指针乱转。
    // 改用 Animatable + 手动计算最短角差，让指针始终走 ≤180° 的最短路径。
    val smoothAzimuth = remember { Animatable(0f) }
    LaunchedEffect(azimuth) {
        val current = smoothAzimuth.value
        // 最短角差：((target - current + 540) % 360) - 180 ∈ [-180, 180]
        val diff = ((azimuth - current + 540f) % 360f) - 180f
        val target = current + diff
        smoothAzimuth.animateTo(target, animationSpec = tween(durationMillis = 150, easing = LinearEasing))
    }
    // 显示用归一化到 [0, 360) 的值
    val smoothAzimuthDisplay = ((smoothAzimuth.value % 360f) + 360f) % 360f
    val smoothPitch by animateFloatAsState(
        targetValue = pitch,
        animationSpec = tween(durationMillis = 100, easing = LinearEasing)
    )
    val smoothRoll by animateFloatAsState(
        targetValue = roll,
        animationSpec = tween(durationMillis = 100, easing = LinearEasing)
    )

    LiquidGlassScaffold(animTime = animTime) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
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
                Text(
                    "指南针·水平仪",
                    fontSize = 16.sp,
                    color = appTextSecondary(),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Tab 切换 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompassTabButton(
                    label = "指南针",
                    icon = Icons.Default.Explore,
                    selected = selectedTab == CompassTab.COMPASS
                ) { selectedTab = CompassTab.COMPASS }
                CompassTabButton(
                    label = "水平仪",
                    icon = Icons.Default.Straighten,
                    selected = selectedTab == CompassTab.LEVEL
                ) { selectedTab = CompassTab.LEVEL }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (selectedTab) {
                CompassTab.COMPASS -> CompassContent(
                    azimuth = smoothAzimuth.value,
                    latitude = latitude,
                    longitude = longitude,
                    altitude = altitude,
                    hasLocation = hasLocation,
                    hasAltitude = hasAltitude,
                    compassStatus = compassStatus,
                    animTime = animTime,
                    onRetry = { retryTrigger++ }
                )
                CompassTab.LEVEL -> LevelContent(
                    pitch = smoothPitch,
                    roll = smoothRoll,
                    animTime = animTime
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

// ═══════════════ Tab 按钮 ═══════════════
@Composable
private fun CompassTabButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bgModifier = if (selected) {
        Modifier.background(
            Brush.horizontalGradient(
                listOf(FluidCyan.copy(alpha = 0.2f), FluidPurple.copy(alpha = 0.2f))
            )
        )
    } else {
        Modifier.background(Color.White.copy(alpha = 0.05f))
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .then(bgModifier)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            null,
            tint = if (selected) FluidCyan else TextTertiary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            label,
            fontSize = 13.sp,
            color = if (selected) FluidCyan else TextTertiary,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

// ═══════════════ 指南针选项卡 ═══════════════
@Composable
private fun CompassContent(
    azimuth: Float,
    latitude: Double,
    longitude: Double,
    altitude: Double,
    hasLocation: Boolean,
    hasAltitude: Boolean,
    compassStatus: String = "OK",
    animTime: Float,
    onRetry: () -> Unit = {}
) {
    // azimuth 入参可能是"未归一化"的连续值（来自最短角路径插值，可能 >360 或 <0），
    // 用于 CompassRoseCanvas 的 rotate（rotate 接受任意角度）。
    // 文本/方向判断需归一化到 [0, 360)。
    val azimuthDisplay = ((azimuth % 360f) + 360f) % 360f
    val direction = getDirectionName(azimuthDisplay)
    val isNorth = azimuthDisplay < 3f || azimuthDisplay > 357f

    // 传感器异常提示文案
    val statusHint: String? = when (compassStatus) {
        "NO_SENSOR" -> "本设备无方向传感器（磁力计/旋转矢量），指南针不可用"
        "CALIBRATE" -> "磁力计精度低，请将手机做8字形挥动校准后重试"
        "WAITING" -> "正在等待传感器数据..."
        "TIMEOUT" -> "传感器注册成功但3秒内未收到数据，可能驱动异常，请尝试退出后重新进入"
        else -> null
    }

    Column(modifier = Modifier.animateContentSize()) {
        // ── 指南针表盘 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(cornerRadius = 32.dp, glassAlpha = 0.15f)
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            CompassRoseCanvas(
                azimuth = azimuth,
                isNorth = isNorth,
                modifier = Modifier.fillMaxSize(0.85f)
            )
        }

        // ── 传感器异常提示（无传感器/需校准/超时时显示，正常不显示）──
        // TIMEOUT 状态可点击重试：重新注册传感器
        if (statusHint != null) {
            Spacer(modifier = Modifier.height(10.dp))
            val canRetry = compassStatus == "TIMEOUT" || compassStatus == "WAITING"
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AccentWarning.copy(alpha = 0.12f))
                    .then(
                        if (canRetry) Modifier.clickable { onRetry() } else Modifier
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = AccentWarning, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (canRetry) "$statusHint（点击重试）" else statusHint,
                        fontSize = 12.sp,
                        color = AccentWarning,
                        modifier = Modifier.weight(1f)
                    )
                    if (canRetry) {
                        Icon(Icons.Default.Refresh, null, tint = AccentWarning, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── 方向信息卡片 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(cornerRadius = 24.dp, glassAlpha = 0.15f)
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 方向名称
                Text(
                    direction,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Thin,
                    color = if (isNorth) FluidTeal else TextPrimary
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 度数
                Text(
                    "${azimuthDisplay.toInt()}°",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Light,
                    color = appTextSecondary()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 细分隔线
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(1.dp)
                        .background(GlassBorder)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // GPS 坐标信息
                if (hasLocation) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.MyLocation,
                            null,
                            tint = FluidCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            String.format("%.6f, %.6f", latitude, longitude),
                            fontSize = 13.sp,
                            color = appTextSecondary()
                        )
                    }
                    if (hasAltitude) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "海拔 %.1f m".format(altitude),
                            fontSize = 12.sp,
                            color = appTextTertiary()
                        )
                    }
                } else {
                    Text(
                        "等待 GPS 信号...",
                        fontSize = 13.sp,
                        color = appTextTertiary()
                    )
                }
            }
        }
    }
}

// ═══════════════ 指南针表盘 Canvas ═══════════════
@Composable
private fun CompassRoseCanvas(
    azimuth: Float,
    isNorth: Boolean,
    modifier: Modifier
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f * 0.82f
        val outerRadius = size.minDimension / 2f * 0.95f

        // ── 外层渐变光晕 ──
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    FluidCyan.copy(alpha = 0.18f),
                    FluidPurple.copy(alpha = 0.06f),
                    Color.Transparent
                ),
                center = center,
                radius = outerRadius
            ),
            radius = outerRadius,
            center = center
        )

        // ── 外圈刻度环 ──
        drawCircle(
            color = GlassBorder,
            radius = outerRadius,
            center = center,
            style = Stroke(width = 2f)
        )
        drawCircle(
            color = GlassLight,
            radius = outerRadius - 4f,
            center = center,
            style = Stroke(width = 0.8f)
        )
        drawCircle(
            color = GlassBorder,
            radius = radius,
            center = center,
            style = Stroke(width = 1.2f)
        )

        // ── 刻度线 ──
        for (i in 0 until 360) {
            val angle = Math.toRadians(i.toDouble() - 90.0) // 0度在顶部
            val isMajor = i % 30 == 0
            val isMedium = i % 10 == 0 && !isMajor
            val isMinor = i % 2 == 0 && !isMajor && !isMedium

            val innerR = when {
                isMajor -> radius - 16f
                isMedium -> radius - 10f
                else -> radius - 6f
            }
            val outerR = radius - 1f

            val startX = center.x + (innerR * cos(angle)).toFloat()
            val startY = center.y + (innerR * sin(angle)).toFloat()
            val endX = center.x + (outerR * cos(angle)).toFloat()
            val endY = center.y + (outerR * sin(angle)).toFloat()

            val alpha = when {
                isMajor -> 0.8f
                isMedium -> 0.5f
                else -> 0.25f
            }
            val strokeWidth = when {
                isMajor -> 2.2f
                isMedium -> 1.2f
                else -> 0.6f
            }
            val color = when (i) {
                in 355..360, in 0..5 -> FluidTeal.copy(alpha = alpha)
                else -> Color.White.copy(alpha = alpha)
            }

            drawLine(
                color = color,
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }

        // ── 方向标签 (N/S/E/W) ──
        val directions = listOf(
            Triple("N", 0f, FluidTeal),
            Triple("NE", 45f, FluidCyan),
            Triple("E", 90f, FluidCyan),
            Triple("SE", 135f, FluidCyan),
            Triple("S", 180f, FluidPurple),
            Triple("SW", 225f, FluidPurple),
            Triple("W", 270f, FluidPurple),
            Triple("NW", 315f, FluidCyan)
        )

        for ((label, deg, color) in directions) {
            val angle = Math.toRadians((deg + 90.0).toDouble())
            val textRadius = radius - 48f
            val tx = center.x + (textRadius * cos(angle)).toFloat()
            val ty = center.y + (textRadius * sin(angle)).toFloat()

            val fontSize = when (label) {
                "N", "S", "E", "W" -> 36f
                else -> 22f
            }
            val paint = android.graphics.Paint().apply {
                this.color = android.graphics.Color.argb(
                    (color.alpha * 0.9f).toInt(),
                    (color.red * 255).toInt(),
                    (color.green * 255).toInt(),
                    (color.blue * 255).toInt()
                )
                textSize = fontSize
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
                typeface = android.graphics.Typeface.DEFAULT
                isFakeBoldText = label.length == 1
            }
            val metrics = paint.fontMetrics
            val textCenterY = ty - (metrics.ascent + metrics.descent) / 2f
            drawContext.canvas.nativeCanvas.drawText(label, tx, textCenterY, paint)
        }

        // ── 旋转的罗盘针（整体旋转） ──
        rotate(degrees = -azimuth, pivot = center) {
            // 北指针（红色萤光三角形）
            val needleTip = Offset(center.x, center.y - radius + 42f)
            val needleBaseLeft = Offset(center.x - 10f, center.y + 10f)
            val needleBaseRight = Offset(center.x + 10f, center.y + 10f)

            val needlePath = Path().apply {
                moveTo(needleTip.x, needleTip.y)
                lineTo(needleBaseRight.x, needleBaseRight.y)
                lineTo(needleBaseLeft.x, needleBaseLeft.y)
                close()
            }
            drawPath(
                path = needlePath,
                brush = Brush.linearGradient(
                    colors = listOf(
                        FluidPink.copy(alpha = 0.95f),
                        FluidOrange.copy(alpha = 0.9f)
                    ),
                    start = needleTip,
                    end = Offset(center.x, center.y)
                )
            )
            // 北指针发光
            drawPath(
                path = needlePath,
                color = FluidPink.copy(alpha = 0.35f),
                style = Stroke(width = 4f, join = StrokeJoin.Round)
            )

            // 南指针（蓝色）
            val southTip = Offset(center.x, center.y + radius - 42f)
            val southBaseLeft = Offset(center.x - 10f, center.y - 10f)
            val southBaseRight = Offset(center.x + 10f, center.y - 10f)

            val southPath = Path().apply {
                moveTo(southTip.x, southTip.y)
                lineTo(southBaseRight.x, southBaseRight.y)
                lineTo(southBaseLeft.x, southBaseLeft.y)
                close()
            }
            drawPath(
                path = southPath,
                brush = Brush.linearGradient(
                    colors = listOf(
                        FluidBlue.copy(alpha = 0.8f),
                        FluidPurple.copy(alpha = 0.7f)
                    ),
                    start = southTip,
                    end = Offset(center.x, center.y)
                )
            )

            // 中心圆
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = 8f,
                center = center
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        FluidCyan.copy(alpha = 0.4f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = 16f
                ),
                radius = 16f,
                center = center
            )
        }

        // ── 当指北时，外圈绿光 ──
        if (isNorth) {
            drawCircle(
                color = FluidTeal.copy(alpha = 0.15f),
                radius = outerRadius + 6f,
                center = center,
                style = Stroke(width = 3f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        FluidTeal.copy(alpha = 0.2f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = outerRadius + 12f
                ),
                radius = outerRadius + 12f,
                center = center
            )
        }
    }
}

// ═══════════════ 水平仪选项卡 ═══════════════
@Composable
private fun LevelContent(
    pitch: Float,
    roll: Float,
    animTime: Float
) {
    var isHorizontal by remember { mutableStateOf(true) }
    // 水平模式下: pitch = Y轴倾斜, roll = X轴倾斜
    val xAngle = roll
    val yAngle = pitch
    val isLevel = abs(xAngle) < 1.5f && abs(yAngle) < 1.5f

    Column(modifier = Modifier.animateContentSize()) {
        // ── 水平仪表盘 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(cornerRadius = 32.dp, glassAlpha = 0.15f)
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            if (isHorizontal) {
                HorizontalLevelCanvas(
                    xAngle = xAngle,
                    yAngle = yAngle,
                    isLevel = isLevel,
                    modifier = Modifier.fillMaxSize(0.85f)
                )
            } else {
                VerticalLevelCanvas(
                    pitch = pitch,
                    isLevel = abs(pitch) < 1.5f,
                    modifier = Modifier.fillMaxSize(0.85f)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── 角度信息卡片 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(cornerRadius = 24.dp, glassAlpha = 0.15f)
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isLevel) {
                    Text(
                        "已水平",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Thin,
                        color = FluidTeal
                    )
                } else {
                    Text(
                        "倾斜",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Thin,
                        color = appTextPrimary()
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (isHorizontal) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        TiltInfoChip("X轴", xAngle)
                        TiltInfoChip("Y轴", yAngle)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        TiltInfoChip("倾斜角", pitch)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 模式切换
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 水平模式按钮
                    val hBg = if (isHorizontal) {
                        Brush.horizontalGradient(
                            listOf(FluidCyan.copy(alpha = 0.25f), FluidPurple.copy(alpha = 0.25f))
                        )
                    } else {
                        Brush.horizontalGradient(
                            listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.06f))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(hBg)
                            .clickable { isHorizontal = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "水平",
                            fontSize = 12.sp,
                            color = if (isHorizontal) FluidCyan else TextTertiary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    val vBg = if (!isHorizontal) {
                        Brush.horizontalGradient(
                            listOf(FluidCyan.copy(alpha = 0.25f), FluidPurple.copy(alpha = 0.25f))
                        )
                    } else {
                        Brush.horizontalGradient(
                            listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.06f))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(vBg)
                            .clickable { isHorizontal = false }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "垂直",
                            fontSize = 12.sp,
                            color = if (!isHorizontal) FluidCyan else TextTertiary
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════ 水平仪 Canvas（水平模式 - 气泡水平仪） ═══════════════
@Composable
private fun HorizontalLevelCanvas(
    xAngle: Float,
    yAngle: Float,
    isLevel: Boolean,
    modifier: Modifier
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f * 0.85f
        val innerRadius = radius * 0.55f

        // ── 外圈 ──
        drawCircle(
            color = GlassBorder,
            radius = radius,
            center = center,
            style = Stroke(width = 2f)
        )
        drawCircle(
            color = GlassLight,
            radius = radius - 3f,
            center = center,
            style = Stroke(width = 0.8f)
        )

        // ── 十字准线 ──
        val lineLen = radius * 0.85f
        drawLine(
            color = GlassBorder,
            start = Offset(center.x - lineLen, center.y),
            end = Offset(center.x + lineLen, center.y),
            strokeWidth = 0.8f
        )
        drawLine(
            color = GlassBorder,
            start = Offset(center.x, center.y - lineLen),
            end = Offset(center.x, center.y + lineLen),
            strokeWidth = 0.8f
        )

        // ── 同心内圈 ──
        drawCircle(
            color = GlassBorder,
            radius = innerRadius,
            center = center,
            style = Stroke(width = 1.2f)
        )
        drawCircle(
            color = GlassLight,
            radius = innerRadius * 0.7f,
            center = center,
            style = Stroke(width = 0.7f)
        )
        drawCircle(
            color = GlassBorder,
            radius = innerRadius * 0.35f,
            center = center,
            style = Stroke(width = 0.6f)
        )

        // ── 气泡位置（受倾斜角度影响） ──
        val maxOffset = (radius - innerRadius) * 0.7f
        val bubbleX = center.x + (xAngle / 10f * maxOffset).coerceIn(-maxOffset, maxOffset)
        val bubbleY = center.y + (yAngle / 10f * maxOffset).coerceIn(-maxOffset, maxOffset)

        // ── 气泡发光 ──
        if (isLevel) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        FluidTeal.copy(alpha = 0.25f),
                        FluidTeal.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = Offset(bubbleX, bubbleY),
                    radius = innerRadius * 1.5f
                ),
                radius = innerRadius * 1.5f,
                center = Offset(bubbleX, bubbleY)
            )
            drawCircle(
                color = FluidTeal.copy(alpha = 0.3f),
                radius = radius + 6f,
                center = center,
                style = Stroke(width = 3f)
            )
        }

        // ── 气泡球 ──
        val bubbleRadius = innerRadius * 0.3f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.95f),
                    if (isLevel) FluidTeal.copy(alpha = 0.7f) else FluidCyan.copy(alpha = 0.6f),
                    if (isLevel) FluidTeal.copy(alpha = 0.3f) else FluidCyan.copy(alpha = 0.2f)
                ),
                center = Offset(bubbleX, bubbleY),
                radius = bubbleRadius
            ),
            radius = bubbleRadius,
            center = Offset(bubbleX, bubbleY)
        )
        // 高光点
        drawCircle(
            color = Color.White.copy(alpha = 0.8f),
            radius = bubbleRadius * 0.3f,
            center = Offset(bubbleX - bubbleRadius * 0.25f, bubbleY - bubbleRadius * 0.3f)
        )
    }
}

// ═══════════════ 水平仪 Canvas（垂直模式） ═══════════════
@Composable
private fun VerticalLevelCanvas(
    pitch: Float,
    isLevel: Boolean,
    modifier: Modifier
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val tubeWidth = size.width * 0.22f
        val tubeHeight = size.height * 0.78f
        val tubeRadius = tubeWidth / 2f

        // ── 管身 ──
        drawRoundRect(
            color = GlassBorder,
            topLeft = Offset(center.x - tubeWidth / 2f, center.y - tubeHeight / 2f),
            size = Size(tubeWidth, tubeHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(tubeRadius, tubeRadius),
            style = Stroke(width = 1.5f)
        )
        drawRoundRect(
            color = GlassLight,
            topLeft = Offset(center.x - tubeWidth / 2f + 2f, center.y - tubeHeight / 2f + 2f),
            size = Size(tubeWidth - 4f, tubeHeight - 4f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(tubeRadius - 2f, tubeRadius - 2f),
            style = Stroke(width = 0.6f)
        )

        // ── 刻度线 ──
        for (i in -5..5) {
            val y = center.y + (i * tubeHeight / 12f).toFloat()
            val markWidth = if (i == 0) tubeWidth * 0.5f else tubeWidth * 0.3f
            val markAlpha = if (i == 0) 0.8f else 0.4f
            val markColor = if (i == 0) FluidTeal.copy(alpha = markAlpha)
            else Color.White.copy(alpha = markAlpha)

            drawLine(
                color = markColor,
                start = Offset(center.x - markWidth / 2f, y),
                end = Offset(center.x + markWidth / 2f, y),
                strokeWidth = if (i == 0) 2f else 0.8f
            )
        }

        // ── 气泡位置 ──
        val maxBubbleOffset = tubeHeight / 2f - tubeRadius * 1.2f
        val bubbleY = (center.y + (pitch / 30f * maxBubbleOffset))
            .coerceIn(center.y - maxBubbleOffset, center.y + maxBubbleOffset)

        // ── 气泡发光 ──
        if (isLevel) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        FluidTeal.copy(alpha = 0.3f),
                        FluidTeal.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = Offset(center.x, bubbleY),
                    radius = tubeRadius * 2f
                ),
                radius = tubeRadius * 2f,
                center = Offset(center.x, bubbleY)
            )
        }

        // ── 气泡球 ──
        val bubbleRadius = tubeRadius * 0.7f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.95f),
                    if (isLevel) FluidTeal.copy(alpha = 0.7f) else FluidCyan.copy(alpha = 0.6f),
                    if (isLevel) FluidTeal.copy(alpha = 0.3f) else FluidCyan.copy(alpha = 0.2f)
                ),
                center = Offset(center.x, bubbleY),
                radius = bubbleRadius
            ),
            radius = bubbleRadius,
            center = Offset(center.x, bubbleY)
        )
        // 高光点
        drawCircle(
            color = Color.White.copy(alpha = 0.8f),
            radius = bubbleRadius * 0.3f,
            center = Offset(center.x - bubbleRadius * 0.25f, bubbleY - bubbleRadius * 0.3f)
        )
    }
}

// ═══════════════ 倾斜信息芯片 ═══════════════
@Composable
private fun TiltInfoChip(label: String, angle: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${angle.toInt()}°",
            fontSize = 28.sp,
            fontWeight = FontWeight.Thin,
            color = if (abs(angle) < 1.5f) FluidTeal else TextPrimary
        )
        Text(
            label,
            fontSize = 11.sp,
            color = appTextTertiary()
        )
    }
}

// ═══════════════ 方向名称辅助函数 ═══════════════
private fun getDirectionName(azimuth: Float): String {
    return when (azimuth.toInt()) {
        in 0..22, in 338..360 -> "N"
        in 23..67 -> "NE"
        in 68..112 -> "E"
        in 113..157 -> "SE"
        in 158..202 -> "S"
        in 203..247 -> "SW"
        in 248..292 -> "W"
        in 293..337 -> "NW"
        else -> "N"
    }
}