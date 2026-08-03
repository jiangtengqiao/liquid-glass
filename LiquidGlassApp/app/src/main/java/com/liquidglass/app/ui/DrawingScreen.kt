package com.liquidglass.app.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

// ─── Data Models ────────────────────────────────────────────────────────────

enum class BrushType { Normal, NeonGlow, Dotted }

enum class BgType { Transparent, White, Dark }

data class DrawingPath(
    val path: Path,
    val color: Color,
    val strokeWidth: Float,
    val brushType: BrushType,
    val isEraser: Boolean
)

// ─── Preset Colors ──────────────────────────────────────────────────────────

private val PresetColors = listOf(
    Color.White,        // 0
    Color.Black,        // 1
    Color.Red,          // 2
    Color(0xFFFF6B35),  // 3  Orange
    Color(0xFFFFD700),  // 4  Gold
    Color(0xFF2ED573),  // 5  Green
    Color(0xFF00D4FF),  // 6  Cyan
    Color(0xFF3366FF),  // 7  Blue
    Color(0xFF7B5CFC),  // 8  Purple
    Color(0xFFFF3B8B),  // 9  Pink
    Color(0xFF8B4513),  // 10 Brown
    Color(0xFF808080),  // 11 Gray
    Color(0xFF00E5A0),  // 12 Teal
    Color(0xFFFFA502),  // 13 Amber
    Color(0xFFE040FB),  // 14 Magenta
    Color(0xFF40C4FF),  // 15 Light Blue
)

// ─── Main Screen ────────────────────────────────────────────────────────────

@Composable
fun DrawingScreen(animTime: Float, onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // ── Drawing State ──
    var paths by remember { mutableStateOf(listOf<DrawingPath>()) }
    var undonePaths by remember { mutableStateOf(listOf<DrawingPath>()) }
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var selectedColor by remember { mutableStateOf(Color.White) }
    var brushSize by remember { mutableFloatStateOf(7f) }
    var brushType by remember { mutableStateOf(BrushType.Normal) }
    var isEraser by remember { mutableStateOf(false) }
    var bgType by remember { mutableStateOf(BgType.Dark) }
    var showColorPicker by remember { mutableStateOf(false) }
    var customColor by remember { mutableStateOf(Color.White) }
    var showBrushMenu by remember { mutableStateOf(false) }
    var showBgMenu by remember { mutableStateOf(false) }
    var showSaveConfirm by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    // ── Background Color ──
    val bgColor = when (bgType) {
        BgType.Transparent -> Color.Transparent
        BgType.White -> Color.White
        BgType.Dark -> Color(0xFF1A1A2E)
    }

    val animatedBgColor by animateColorAsState(targetValue = bgColor, animationSpec = tween(300))

    // ── Clear undone paths on new stroke ──
    fun addPath(dp: DrawingPath) {
        paths = paths + dp
        undonePaths = emptyList()
    }

    fun undo() {
        if (paths.isNotEmpty()) {
            undonePaths = undonePaths + paths.last()
            paths = paths.dropLast(1)
        }
    }

    fun redo() {
        if (undonePaths.isNotEmpty()) {
            paths = paths + undonePaths.last()
            undonePaths = undonePaths.dropLast(1)
        }
    }

    fun clearCanvas() {
        paths = emptyList()
        undonePaths = emptyList()
    }

    // ── Save to Gallery ──
    fun saveToGallery() {
        if (paths.isEmpty()) {
            Toast.makeText(context, "画布为空", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            try {
                val w = canvasSize.width.coerceAtLeast(1f).toInt()
                val h = canvasSize.height.coerceAtLeast(1f).toInt()
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)

                // Draw background
                when (bgType) {
                    BgType.White -> canvas.drawColor(android.graphics.Color.WHITE)
                    BgType.Dark -> canvas.drawColor(android.graphics.Color.rgb(26, 26, 46))
                    BgType.Transparent -> canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                }

                val androidPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    isDither = true
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    strokeJoin = android.graphics.Paint.Join.ROUND
                    style = android.graphics.Paint.Style.STROKE
                }

                for (dp in paths) {
                    val colorInt = dp.color.toArgb()
                    androidPaint.color = if (dp.isEraser) {
                        when (bgType) {
                            BgType.White -> android.graphics.Color.WHITE
                            BgType.Dark -> android.graphics.Color.rgb(26, 26, 46)
                            BgType.Transparent -> android.graphics.Color.TRANSPARENT
                        }
                    } else {
                        colorInt
                    }
                    androidPaint.strokeWidth = dp.strokeWidth
                    androidPaint.xfermode = if (dp.isEraser && bgType == BgType.Transparent) {
                        android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                    } else {
                        null
                    }

                    when (dp.brushType) {
                        BrushType.Dotted -> {
                            androidPaint.pathEffect = android.graphics.DashPathEffect(
                                floatArrayOf(dp.strokeWidth * 1.5f, dp.strokeWidth * 3f), 0f
                            )
                        }
                        BrushType.NeonGlow -> {
                            androidPaint.pathEffect = null
                            // glow layers
                            androidPaint.setShadowLayer(dp.strokeWidth * 3f, 0f, 0f, colorInt)
                            androidPaint.alpha = 80
                            canvas.drawPath(dp.path.asAndroidPath(), androidPaint)
                            androidPaint.setShadowLayer(dp.strokeWidth * 2f, 0f, 0f, colorInt)
                            androidPaint.alpha = 150
                            canvas.drawPath(dp.path.asAndroidPath(), androidPaint)
                            androidPaint.clearShadowLayer()
                            androidPaint.alpha = 255
                        }
                        BrushType.Normal -> {
                            androidPaint.pathEffect = null
                        }
                    }
                    canvas.drawPath(dp.path.asAndroidPath(), androidPaint)
                    androidPaint.pathEffect = null
                    androidPaint.xfermode = null
                    androidPaint.clearShadowLayer()
                }

                val filename = "drawing_${System.currentTimeMillis()}.png"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LiquidGlass")
                    }
                    val uri = context.contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                    )
                    uri?.let {
                        context.contentResolver.openOutputStream(it)?.use { os ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                        }
                    }
                } else {
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "LiquidGlass"
                    )
                    dir.mkdirs()
                    val file = File(dir, filename)
                    FileOutputStream(file).use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                    MediaStore.Images.Media.insertImage(
                        context.contentResolver, file.absolutePath, filename, null
                    )
                }
                bitmap.recycle()
                Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Share Drawing ──
    fun shareDrawing() {
        if (paths.isEmpty()) {
            Toast.makeText(context, "画布为空", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            try {
                val w = canvasSize.width.coerceAtLeast(1f).toInt()
                val h = canvasSize.height.coerceAtLeast(1f).toInt()
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                when (bgType) {
                    BgType.White -> canvas.drawColor(android.graphics.Color.WHITE)
                    BgType.Dark -> canvas.drawColor(android.graphics.Color.rgb(26, 26, 46))
                    BgType.Transparent -> canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                }
                val androidPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    strokeJoin = android.graphics.Paint.Join.ROUND
                    style = android.graphics.Paint.Style.STROKE
                }
                for (dp in paths) {
                    androidPaint.color = dp.color.toArgb()
                    androidPaint.strokeWidth = dp.strokeWidth
                    androidPaint.pathEffect = if (dp.brushType == BrushType.Dotted) {
                        android.graphics.DashPathEffect(floatArrayOf(dp.strokeWidth * 1.5f, dp.strokeWidth * 3f), 0f)
                    } else null
                    canvas.drawPath(dp.path.asAndroidPath(), androidPaint)
                }

                val cacheDir = File(context.cacheDir, "shared_images")
                cacheDir.mkdirs()
                val file = File(cacheDir, "drawing_share.png")
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()

                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "分享绘图"))
            } catch (e: Exception) {
                Toast.makeText(context, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── UI ──
    LiquidGlassScaffold(animTime = animTime) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // ── Top Bar ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = appTextSecondary())
                }
                Text("绘图板", color = appTextSecondary(), fontSize = 16.sp, modifier = Modifier.weight(1f))

                // Undo
                IconButton(onClick = { undo() }, enabled = paths.isNotEmpty()) {
                    Icon(Icons.Default.Undo, "撤销",
                        tint = if (paths.isNotEmpty()) TextSecondary else TextTertiary)
                }
                // Redo
                IconButton(onClick = { redo() }, enabled = undonePaths.isNotEmpty()) {
                    Icon(Icons.Default.Redo, "重做",
                        tint = if (undonePaths.isNotEmpty()) TextSecondary else TextTertiary)
                }
                // Clear
                IconButton(onClick = { showSaveConfirm = true }, enabled = paths.isNotEmpty()) {
                    Icon(Icons.Default.Delete, "清空",
                        tint = if (paths.isNotEmpty()) AccentDanger.copy(alpha = 0.7f) else TextTertiary)
                }
                // Save
                IconButton(onClick = { saveToGallery() }, enabled = paths.isNotEmpty()) {
                    Icon(Icons.Default.Save, "保存",
                        tint = if (paths.isNotEmpty()) AccentSuccess.copy(alpha = 0.7f) else TextTertiary)
                }
                // Share
                IconButton(onClick = { shareDrawing() }, enabled = paths.isNotEmpty()) {
                    Icon(Icons.Default.Share, "分享",
                        tint = if (paths.isNotEmpty()) TextSecondary else TextTertiary)
                }
            }

            // ── Canvas Drawing Area ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.10f)
                    .clip(RoundedCornerShape(20.dp))
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(animatedBgColor)
                        .pointerInput(brushType, brushSize, isEraser, selectedColor) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val p = Path()
                                    p.moveTo(offset.x, offset.y)
                                    currentPath = p
                                },
                                onDrag = { change, _ ->
                                    currentPath?.let { p ->
                                        p.lineTo(change.position.x, change.position.y)
                                    }
                                },
                                onDragEnd = {
                                    currentPath?.let { p ->
                                        addPath(
                                            DrawingPath(
                                                path = Path().apply { addPath(p) },
                                                color = if (isEraser) Color.Transparent else selectedColor,
                                                strokeWidth = brushSize,
                                                brushType = brushType,
                                                isEraser = isEraser
                                            )
                                        )
                                    }
                                    currentPath = null
                                },
                                onDragCancel = {
                                    currentPath?.let { p ->
                                        addPath(
                                            DrawingPath(
                                                path = Path().apply { addPath(p) },
                                                color = if (isEraser) Color.Transparent else selectedColor,
                                                strokeWidth = brushSize,
                                                brushType = brushType,
                                                isEraser = isEraser
                                            )
                                        )
                                    }
                                    currentPath = null
                                }
                            )
                        }
                ) {
                    canvasSize = size

                    // Draw all saved paths
                    for (dp in paths) {
                        when {
                            dp.isEraser -> {
                                drawPath(
                                    path = dp.path,
                                    color = bgColor,
                                    style = Stroke(
                                        width = dp.strokeWidth,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            }
                            dp.brushType == BrushType.NeonGlow -> {
                                // Outer glow
                                drawPath(
                                    path = dp.path,
                                    color = dp.color.copy(alpha = 0.15f),
                                    style = Stroke(
                                        width = dp.strokeWidth + 12f,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                                // Mid glow
                                drawPath(
                                    path = dp.path,
                                    color = dp.color.copy(alpha = 0.35f),
                                    style = Stroke(
                                        width = dp.strokeWidth + 6f,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                                // Core
                                drawPath(
                                    path = dp.path,
                                    color = dp.color.copy(alpha = 0.8f),
                                    style = Stroke(
                                        width = dp.strokeWidth,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                                // Bright center
                                drawPath(
                                    path = dp.path,
                                    color = Color.White.copy(alpha = 0.6f),
                                    style = Stroke(
                                        width = dp.strokeWidth * 0.3f,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            }
                            dp.brushType == BrushType.Dotted -> {
                                drawPath(
                                    path = dp.path,
                                    color = dp.color,
                                    style = Stroke(
                                        width = dp.strokeWidth,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round,
                                        pathEffect = PathEffect.dashPathEffect(
                                            floatArrayOf(dp.strokeWidth * 1.5f, dp.strokeWidth * 3f), 0f
                                        )
                                    )
                                )
                            }
                            else -> {
                                drawPath(
                                    path = dp.path,
                                    color = dp.color,
                                    style = Stroke(
                                        width = dp.strokeWidth,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            }
                        }
                    }

                    // Draw current stroke
                    currentPath?.let { p ->
                        if (isEraser) {
                            drawPath(
                                path = p,
                                color = bgColor,
                                style = Stroke(
                                    width = brushSize,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        } else when (brushType) {
                            BrushType.NeonGlow -> {
                                drawPath(p, selectedColor.copy(alpha = 0.15f),
                                    style = Stroke(width = brushSize + 12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                drawPath(p, selectedColor.copy(alpha = 0.35f),
                                    style = Stroke(width = brushSize + 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                drawPath(p, selectedColor.copy(alpha = 0.8f),
                                    style = Stroke(width = brushSize, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                drawPath(p, Color.White.copy(alpha = 0.6f),
                                    style = Stroke(width = brushSize * 0.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                            BrushType.Dotted -> {
                                drawPath(p, selectedColor,
                                    style = Stroke(
                                        width = brushSize, cap = StrokeCap.Round, join = StrokeJoin.Round,
                                        pathEffect = PathEffect.dashPathEffect(
                                            floatArrayOf(brushSize * 1.5f, brushSize * 3f), 0f
                                        )
                                    ))
                            }
                            else -> {
                                drawPath(p, selectedColor,
                                    style = Stroke(width = brushSize, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ── Bottom Toolbar ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.10f)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                // ── Row 1: Color Palette ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PresetColors.forEachIndexed { index, color ->
                        val isSelected = selectedColor == color && !isEraser
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 30.dp else 26.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(
                                    if (isSelected) Modifier.border(2.5.dp, Color.White, CircleShape)
                                    else Modifier
                                )
                                .clickable {
                                    selectedColor = color
                                    isEraser = false
                                }
                        )
                    }

                    Spacer(modifier = Modifier.width(2.dp))

                    // Custom color picker button
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.sweepGradient(
                                    listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
                                )
                            )
                            .clickable { showColorPicker = !showColorPicker }
                    )
                }

                // ── Custom Color Picker (expandable) ──
                AnimatedVisibility(visible = showColorPicker) {
                    Column(modifier = Modifier.padding(top = 6.dp)) {
                        // RGB sliders
                        val (r, g, b) = remember(customColor) {
                            Triple(customColor.red, customColor.green, customColor.blue)
                        }
                        var red by remember { mutableFloatStateOf(r) }
                        var green by remember { mutableFloatStateOf(g) }
                        var blue by remember { mutableFloatStateOf(b) }

                        customColor = Color(red, green, blue)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(customColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("自定义颜色", fontSize = 11.sp, color = appTextSecondary())
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    selectedColor = customColor
                                    isEraser = false
                                }
                            ) {
                                Text("应用", fontSize = 11.sp, color = AccentPrimary)
                            }
                        }

                        ColorSlider("R", red, { red = it }, Color.Red, Modifier.fillMaxWidth())
                        ColorSlider("G", green, { green = it }, Color.Green, Modifier.fillMaxWidth())
                        ColorSlider("B", blue, { blue = it }, Color.Blue, Modifier.fillMaxWidth())
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // ── Row 2: Brush Size + Tools ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Brush size slider
                    Icon(Icons.Default.Circle, null, tint = appTextSecondary(), modifier = Modifier.size(16.dp))
                    Slider(
                        value = brushSize,
                        onValueChange = { brushSize = it },
                        valueRange = 1f..30f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = selectedColor,
                            activeTrackColor = selectedColor.copy(alpha = 0.5f),
                            inactiveTrackColor = GlassMedium
                        )
                    )
                    Text(
                        "${brushSize.roundToInt()}px",
                        fontSize = 10.sp,
                        color = appTextTertiary(),
                        modifier = Modifier.width(32.dp)
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    // Eraser toggle
                    IconButton(
                        onClick = { isEraser = !isEraser },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.AutoFixHigh,
                            "橡皮擦",
                            tint = if (isEraser) AccentWarning else TextTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Brush type menu
                    Box {
                        IconButton(
                            onClick = { showBrushMenu = !showBrushMenu; showBgMenu = false },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Default.Brush,
                                "笔刷类型",
                                tint = appTextSecondary(),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showBrushMenu,
                            onDismissRequest = { showBrushMenu = false },
                            containerColor = appBgColor2()
                        ) {
                            DropdownMenuItem(
                                text = { Text("普通笔刷", fontSize = 13.sp, color = if (brushType == BrushType.Normal) AccentPrimary else TextPrimary) },
                                onClick = { brushType = BrushType.Normal; showBrushMenu = false },
                                leadingIcon = { Icon(Icons.Default.Edit, null, tint = if (brushType == BrushType.Normal) AccentPrimary else TextSecondary) }
                            )
                            DropdownMenuItem(
                                text = { Text("霓虹发光", fontSize = 13.sp, color = if (brushType == BrushType.NeonGlow) AccentPrimary else TextPrimary) },
                                onClick = { brushType = BrushType.NeonGlow; showBrushMenu = false },
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, null, tint = if (brushType == BrushType.NeonGlow) AccentPrimary else TextSecondary) }
                            )
                            DropdownMenuItem(
                                text = { Text("虚线笔刷", fontSize = 13.sp, color = if (brushType == BrushType.Dotted) AccentPrimary else TextPrimary) },
                                onClick = { brushType = BrushType.Dotted; showBrushMenu = false },
                                leadingIcon = { Icon(Icons.Default.MoreHoriz, null, tint = if (brushType == BrushType.Dotted) AccentPrimary else TextSecondary) }
                            )
                        }
                    }

                    // Background toggle
                    Box {
                        IconButton(
                            onClick = { showBgMenu = !showBgMenu; showBrushMenu = false },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Default.Layers,
                                "背景",
                                tint = appTextSecondary(),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showBgMenu,
                            onDismissRequest = { showBgMenu = false },
                            containerColor = appBgColor2()
                        ) {
                            DropdownMenuItem(
                                text = { Text("深色背景", fontSize = 13.sp, color = if (bgType == BgType.Dark) AccentPrimary else TextPrimary) },
                                onClick = { bgType = BgType.Dark; showBgMenu = false },
                                leadingIcon = {
                                    Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(Color(0xFF1A1A2E)))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("白色背景", fontSize = 13.sp, color = if (bgType == BgType.White) AccentPrimary else TextPrimary) },
                                onClick = { bgType = BgType.White; showBgMenu = false },
                                leadingIcon = {
                                    Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(Color.White).border(1.dp, TextTertiary, CircleShape))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("透明背景", fontSize = 13.sp, color = if (bgType == BgType.Transparent) AccentPrimary else TextPrimary) },
                                onClick = { bgType = BgType.Transparent; showBgMenu = false },
                                leadingIcon = {
                                    Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(
                                        Brush.linearGradient(listOf(Color.White, Color.LightGray))
                                    ))
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Clear Confirmation Dialog ──
    if (showSaveConfirm) {
        AlertDialog(
            onDismissRequest = { showSaveConfirm = false },
            containerColor = appBgColor2(),
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            icon = {
                Icon(Icons.Default.Warning, null, tint = AccentWarning, modifier = Modifier.size(32.dp))
            },
            title = { Text("清空画布") },
            text = { Text("确定要清空所有绘制内容吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearCanvas()
                        showSaveConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentDanger)
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSaveConfirm = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) { Text("取消") }
            }
        )
    }
}

// ── Color Slider Helper ──

@Composable
private fun ColorSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.sp, color = appTextTertiary(), modifier = Modifier.width(14.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f).height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color.copy(alpha = 0.5f),
                inactiveTrackColor = GlassMedium
            )
        )
        Text(
            "${(value * 255).roundToInt()}",
            fontSize = 10.sp,
            color = appTextTertiary(),
            modifier = Modifier.width(28.dp)
        )
    }
}