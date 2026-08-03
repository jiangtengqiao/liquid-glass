package com.liquidglass.app.ui

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.app.ResourceManager
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.*
import kotlin.random.Random

// ── Wallpaper 数据模型 ──────────────────────────────────────────────

data class WallpaperItem(
    val id: Int,
    val category: String,
    val name: String,
    val draw: DrawScope.(Size) -> Unit
)

data class WallpaperCategory(
    val name: String,
    val label: String,
    val icon: @Composable () -> Unit,
    val accentColor: Color
)

// ── 分类定义 ─────────────────────────────────────────────────────────

val wallpaperCategories = listOf(
    WallpaperCategory("Nature", "自然", { Icon(Icons.Default.Park, null, tint = Color(0xFF5BDB7C), modifier = Modifier.size(20.dp)) }, Color(0xFF5BDB7C)),
    WallpaperCategory("Abstract", "抽象", { Icon(Icons.Default.BubbleChart, null, tint = Color(0xFFFF6B9D), modifier = Modifier.size(20.dp)) }, Color(0xFFFF6B9D)),
    WallpaperCategory("Gradient", "渐变", { Icon(Icons.Default.Gradient, null, tint = Color(0xFF7B5CFC), modifier = Modifier.size(20.dp)) }, Color(0xFF7B5CFC)),
    WallpaperCategory("Space", "太空", { Icon(Icons.Default.RocketLaunch, null, tint = Color(0xFF5B9AFF), modifier = Modifier.size(20.dp)) }, Color(0xFF5B9AFF)),
    WallpaperCategory("Ocean", "海洋", { Icon(Icons.Default.Water, null, tint = Color(0xFF00D4FF), modifier = Modifier.size(20.dp)) }, Color(0xFF00D4FF)),
    WallpaperCategory("Minimal", "极简", { Icon(Icons.Default.BlurOn, null, tint = Color(0xFFCCCCCC), modifier = Modifier.size(20.dp)) }, Color(0xFFAAAAAA)),
    WallpaperCategory("Night", "夜空", { Icon(Icons.Default.DarkMode, null, tint = Color(0xFF9B7BFF), modifier = Modifier.size(20.dp)) }, Color(0xFF9B7BFF)),
    WallpaperCategory("Liquid", "液态", { Icon(Icons.Default.Opacity, null, tint = Color(0xFF00E5A0), modifier = Modifier.size(20.dp)) }, Color(0xFF00E5A0)),
    WallpaperCategory("Downloaded", "已下载", { Icon(Icons.Default.Download, null, tint = Color(0xFF00E5A0), modifier = Modifier.size(20.dp)) }, Color(0xFF00E5A0))
)

// ── 壁纸 Canvas 绘制函数 ──────────────────────────────────────────────

// Nature 自然
fun DrawScope.drawNature1(size: Size) {
    val w = size.width; val h = size.height
    // 天空渐变
    drawRect(brush = Brush.verticalGradient(
        colors = listOf(Color(0xFFFF9A76), Color(0xFFFFD180), Color(0xFF87CEEB)),
        startY = 0f, endY = h * 0.55f
    ))
    // 太阳
    drawCircle(Color(0xFFFFF8E1).copy(alpha = 0.9f), radius = w * 0.12f, center = Offset(w * 0.78f, h * 0.16f))
    drawCircle(Color(0xFFFFF176).copy(alpha = 0.3f), radius = w * 0.20f, center = Offset(w * 0.78f, h * 0.16f))
    drawCircle(Color(0xFFFFF176).copy(alpha = 0.12f), radius = w * 0.32f, center = Offset(w * 0.78f, h * 0.16f))
    // 远山
    val mtPath = Path().apply {
        moveTo(0f, h * 0.55f)
        lineTo(w * 0.25f, h * 0.28f)
        lineTo(w * 0.50f, h * 0.42f)
        lineTo(w * 0.72f, h * 0.20f)
        lineTo(w * 0.90f, h * 0.35f)
        lineTo(w, h * 0.30f)
        lineTo(w, h)
        lineTo(0f, h); close()
    }
    drawPath(mtPath, color = Color(0xFF4A7C59).copy(alpha = 0.55f))
    drawPath(mtPath, color = Color(0xFF2D5A3F).copy(alpha = 0.35f), style = Stroke(width = 1.5f))
    // 草地
    drawRect(brush = Brush.verticalGradient(
        colors = listOf(Color(0xFF4CAF50), Color(0xFF2E7D32), Color(0xFF1B5E20)),
        startY = h * 0.55f, endY = h
    ), topLeft = Offset(0f, h * 0.55f), size = Size(w, h * 0.45f))
    // 树木
    for (i in 0..8) {
        val tx = w * (0.05f + i * 0.11f)
        val ty = h * (0.57f + sin(i * 0.8f) * 0.06f)
        drawCircle(Color(0xFF2E7D32).copy(alpha = 0.7f), radius = w * 0.04f, center = Offset(tx, ty))
        drawCircle(Color(0xFF388E3C).copy(alpha = 0.5f), radius = w * 0.03f, center = Offset(tx - w * 0.02f, ty - w * 0.02f))
        drawCircle(Color(0xFF43A047).copy(alpha = 0.4f), radius = w * 0.025f, center = Offset(tx + w * 0.015f, ty - w * 0.025f))
    }
    // 云
    drawCloud(w * 0.15f, h * 0.10f, w * 0.22f, 0.5f)
    drawCloud(w * 0.55f, h * 0.07f, w * 0.18f, 0.4f)
}

fun DrawScope.drawNature2(size: Size) {
    val w = size.width; val h = size.height
    // 夜空森林
    drawRect(brush = Brush.verticalGradient(
        colors = listOf(Color(0xFF0D0930), Color(0xFF1A1040), Color(0xFF0A1A30)),
        startY = 0f, endY = h
    ))
    // 极光
    for (i in 0..5) {
        val ay = h * (0.08f + i * 0.06f)
        drawCircle(Brush.horizontalGradient(
            colors = listOf(Color(0x44FF4081), Color(0x4400E5FF), Color(0x4400FF80), Color(0x44FF4081)),
            startX = 0f, endX = w
        ), radius = h * 0.12f, center = Offset(w * 0.5f, ay))
    }
    // 星星
    for (i in 0..80) {
        val sx = (sin(i * 127.1f) * 0.5f + 0.5f) * w
        val sy = (cos(i * 311.7f) * 0.5f + 0.5f) * h * 0.5f
        val sr = 1f + abs(sin(i * 3.7f)) * 2f
        drawCircle(Color.White.copy(alpha = 0.3f + abs(sin(i * 7.1f)) * 0.7f), radius = sr, center = Offset(sx, sy))
    }
    // 月亮
    drawCircle(Color(0xFFFFF8E1).copy(alpha = 0.9f), radius = w * 0.08f, center = Offset(w * 0.78f, h * 0.12f))
    drawCircle(Color(0xFF0D0930), radius = w * 0.06f, center = Offset(w * 0.81f, h * 0.10f))
    // 剪影山脉
    val mtPath = Path().apply {
        moveTo(0f, h * 0.75f)
        lineTo(w * 0.15f, h * 0.50f); lineTo(w * 0.30f, h * 0.62f)
        lineTo(w * 0.45f, h * 0.35f); lineTo(w * 0.60f, h * 0.55f)
        lineTo(w * 0.75f, h * 0.40f); lineTo(w * 0.90f, h * 0.58f)
        lineTo(w, h * 0.45f); lineTo(w, h); lineTo(0f, h); close()
    }
    drawPath(mtPath, color = Color(0xFF0A0A1A))
    // 树剪影
    for (i in 0..12) {
        val tx = w * (0.03f + i * 0.08f)
        drawCircle(Color(0xFF0A0A1A), radius = w * 0.035f, center = Offset(tx, h * 0.72f))
        drawCircle(Color(0xFF0A0A1A), radius = w * 0.025f, center = Offset(tx - w * 0.015f, h * 0.70f))
        drawCircle(Color(0xFF0A0A1A), radius = w * 0.02f, center = Offset(tx + w * 0.01f, h * 0.69f))
    }
}

fun DrawScope.drawNature3(size: Size) {
    val w = size.width; val h = size.height
    drawRect(brush = Brush.verticalGradient(
        colors = listOf(Color(0xFFE8F5E9), Color(0xFFC8E6C9), Color(0xFFA5D6A7)),
        startY = 0f, endY = h
    ))
    // 樱花树
    val trunk = Path().apply {
        moveTo(w * 0.48f, h * 0.7f); lineTo(w * 0.44f, h * 0.35f)
        lineTo(w * 0.46f, h * 0.35f); lineTo(w * 0.50f, h * 0.7f); close()
    }
    drawPath(trunk, color = Color(0xFF5D4037))
    // 树枝
    for (angle in listOf(-30f, -15f, 0f, 15f, 30f)) {
        val cx = w * 0.46f; val cy = h * 0.38f
        val rad = angle * PI.toFloat() / 180f
        val ex = cx + cos(rad) * w * 0.15f; val ey = cy + sin(rad) * h * 0.12f
        drawLine(Color(0xFF5D4037), Offset(cx, cy), Offset(ex, ey), strokeWidth = 3f)
        drawCircle(Color(0xFFFFCDD2).copy(alpha = 0.7f), radius = w * 0.06f, center = Offset(ex, ey))
        drawCircle(Color(0xFFFF8A80).copy(alpha = 0.5f), radius = w * 0.04f, center = Offset(ex - w * 0.02f, ey - h * 0.01f))
        drawCircle(Color(0xFFFFF0F0).copy(alpha = 0.6f), radius = w * 0.03f, center = Offset(ex + w * 0.02f, ey + h * 0.01f))
    }
    // 落花
    for (i in 0..35) {
        val px = (sin(i * 97.3f) * 0.5f + 0.5f) * w
        val py = h * 0.45f + (cos(i * 73.1f) * 0.5f + 0.5f) * h * 0.55f
        drawCircle(Color(0xFFFFB7B2).copy(alpha = 0.5f), radius = 2.5f, center = Offset(px, py))
    }
}

// Abstract 抽象
fun DrawScope.drawAbstract1(size: Size) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF0D0D1A))
    // 重叠几何图形
    drawCircle(Brush.radialGradient(
        colors = listOf(Color(0x667B5CFC), Color.Transparent),
        center = Offset(w * 0.3f, h * 0.3f), radius = w * 0.5f
    ), radius = w * 0.5f, center = Offset(w * 0.3f, h * 0.3f))
    drawCircle(Brush.radialGradient(
        colors = listOf(Color(0x66FF3B8B), Color.Transparent),
        center = Offset(w * 0.7f, h * 0.6f), radius = w * 0.45f
    ), radius = w * 0.45f, center = Offset(w * 0.7f, h * 0.6f))
    drawCircle(Brush.radialGradient(
        colors = listOf(Color(0x6600D4FF), Color.Transparent),
        center = Offset(w * 0.5f, h * 0.5f), radius = w * 0.35f
    ), radius = w * 0.35f, center = Offset(w * 0.5f, h * 0.5f))
    // 线条
    for (i in 0..8) {
        val y = h * (0.1f + i * 0.1f)
        drawLine(Color.White.copy(alpha = 0.1f), Offset(w * 0.1f, y), Offset(w * 0.9f, y), strokeWidth = 1f)
    }
    for (i in 0..8) {
        val x = w * (0.1f + i * 0.1f)
        drawLine(Color.White.copy(alpha = 0.08f), Offset(x, h * 0.1f), Offset(x, h * 0.9f), strokeWidth = 1f)
    }
    // 多边形
    val poly = Path().apply {
        moveTo(w * 0.5f, h * 0.15f); lineTo(w * 0.85f, h * 0.4f)
        lineTo(w * 0.75f, h * 0.8f); lineTo(w * 0.25f, h * 0.8f)
        lineTo(w * 0.15f, h * 0.4f); close()
    }
    drawPath(poly, color = Color.White.copy(alpha = 0.06f), style = Stroke(width = 2f))
}

fun DrawScope.drawAbstract2(size: Size) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF0A0A14))
    // 彩色圆环
    for (i in 0..5) {
        val r = w * (0.08f + i * 0.07f)
        val cx = w * 0.5f; val cy = h * 0.5f
        val colors = listOf(FluidCyan, FluidPurple, FluidPink, FluidTeal, FluidBlue, FluidOrange)
        drawCircle(colors[i].copy(alpha = 0.5f), radius = r, center = Offset(cx, cy), style = Stroke(width = 3f))
    }
    // 中心三角形
    val tri = Path().apply {
        moveTo(w * 0.5f, h * 0.3f); lineTo(w * 0.7f, h * 0.65f)
        lineTo(w * 0.3f, h * 0.65f); close()
    }
    drawPath(tri, color = Color.White.copy(alpha = 0.15f), style = Stroke(width = 2.5f))
    drawPath(tri, Brush.linearGradient(
        colors = listOf(FluidCyan.copy(alpha = 0.2f), FluidPurple.copy(alpha = 0.2f)),
        start = Offset(w * 0.3f, h * 0.3f), end = Offset(w * 0.7f, h * 0.7f)
    ))
    // 小点
    for (i in 0..30) {
        val angle = i * 2 * PI.toFloat() / 30f
        val r = w * 0.38f
        drawCircle(Color.White.copy(alpha = 0.2f), radius = 2f, center = Offset(w * 0.5f + cos(angle) * r, h * 0.5f + sin(angle) * r))
    }
}

fun DrawScope.drawAbstract3(size: Size) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF050510))
    // 对角渐变光带
    for (i in 0..7) {
        val offset = i * 0.13f
        drawLine(
            Brush.linearGradient(
                colors = listOf(Color.Transparent, FluidPurple.copy(alpha = 0.4f), Color.Transparent),
                start = Offset(0f, 0f), end = Offset(w, h)
            ),
            Offset(w * offset, 0f), Offset(0f, h * offset),
            strokeWidth = 4f
        )
        drawLine(
            Brush.linearGradient(
                colors = listOf(Color.Transparent, FluidCyan.copy(alpha = 0.4f), Color.Transparent),
                start = Offset(w, 0f), end = Offset(0f, h)
            ),
            Offset(w * (1f - offset), 0f), Offset(0f, h * (1f - offset)),
            strokeWidth = 4f
        )
    }
    // 中心方块
    val sqSize = w * 0.25f
    drawRoundRect(
        Color.White.copy(alpha = 0.08f), cornerRadius = CornerRadius(16f),
        topLeft = Offset(w * 0.5f - sqSize / 2, h * 0.5f - sqSize / 2),
        size = Size(sqSize, sqSize)
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.06f),
        style = Stroke(width = 2f),
        cornerRadius = CornerRadius(16f),
        topLeft = Offset(w * 0.5f - sqSize / 2, h * 0.5f - sqSize / 2),
        size = Size(sqSize, sqSize)
    )
}

// Gradient 渐变
fun DrawScope.drawGradient1(size: Size) {
    val w = size.width; val h = size.height
    drawRect(brush = Brush.verticalGradient(
        colors = listOf(Color(0xFFFF6B6B), Color(0xFFFFE66D), Color(0xFF4ECDC4), Color(0xFF45B7D1)),
        startY = 0f, endY = h
    ))
    // 光晕
    drawCircle(Brush.radialGradient(
        colors = listOf(Color.White.copy(alpha = 0.3f), Color.Transparent),
        center = Offset(w * 0.3f, h * 0.3f), radius = w * 0.4f
    ), radius = w * 0.4f, center = Offset(w * 0.3f, h * 0.3f))
    drawCircle(Brush.radialGradient(
        colors = listOf(Color.White.copy(alpha = 0.2f), Color.Transparent),
        center = Offset(w * 0.7f, h * 0.7f), radius = w * 0.35f
    ), radius = w * 0.35f, center = Offset(w * 0.7f, h * 0.7f))
}

fun DrawScope.drawGradient2(size: Size) {
    val w = size.width; val h = size.height
    drawRect(brush = Brush.linearGradient(
        colors = listOf(Color(0xFF2C003E), Color(0xFF7B2D8E), Color(0xFFFF3B8B), Color(0xFFFF8C42)),
        start = Offset(0f, 0f), end = Offset(w, h)
    ))
    // 网格线
    val step = h / 20f
    for (i in 0..20) {
        val y = i * step
        drawLine(Color.White.copy(alpha = 0.04f), Offset(0f, y), Offset(w, y), strokeWidth = 0.8f)
    }
    for (i in 0..20) {
        val x = i * (w / 20f)
        drawLine(Color.White.copy(alpha = 0.04f), Offset(x, 0f), Offset(x, h), strokeWidth = 0.8f)
    }
    // 光圈
    drawCircle(Brush.radialGradient(
        colors = listOf(Color.White.copy(alpha = 0.15f), Color.Transparent),
        center = Offset(w * 0.5f, h * 0.5f), radius = w * 0.5f
    ), radius = w * 0.5f, center = Offset(w * 0.5f, h * 0.5f))
}

fun DrawScope.drawGradient3(size: Size) {
    val w = size.width; val h = size.height
    drawRect(brush = Brush.linearGradient(
        colors = listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364)),
        start = Offset(0f, h), end = Offset(w, 0f)
    ))
    // 彩色条纹
    for (i in 0..10) {
        val y = h * (i / 10f)
        drawRect(
            Brush.horizontalGradient(
                colors = listOf(Color.Transparent, FluidTeal.copy(alpha = 0.15f), Color.Transparent),
                startX = 0f, endX = w
            ),
            topLeft = Offset(0f, y - h * 0.02f),
            size = Size(w, h * 0.04f)
        )
    }
    // 发光点
    for (i in 0..15) {
        val px = (cos(i * 0.9f) * 0.5f + 0.5f) * w
        val py = (sin(i * 1.1f) * 0.5f + 0.5f) * h
        drawCircle(Color.White.copy(alpha = 0.5f), radius = 2.5f, center = Offset(px, py))
        drawCircle(Color.White.copy(alpha = 0.15f), radius = 8f, center = Offset(px, py))
    }
}

// Space 太空
fun DrawScope.drawSpace1(size: Size) {
    val w = size.width; val h = size.height
    drawRect(brush = Brush.radialGradient(
        colors = listOf(Color(0xFF1A0533), Color(0xFF0D0221), Color(0xFF050010)),
        center = Offset(w * 0.5f, h * 0.5f), radius = w * 0.8f
    ))
    // 星云
    drawCircle(Brush.radialGradient(
        colors = listOf(Color(0x44FF4081), Color(0x2200E5FF), Color.Transparent),
        center = Offset(w * 0.25f, h * 0.35f), radius = w * 0.45f
    ), radius = w * 0.45f, center = Offset(w * 0.25f, h * 0.35f))
    drawCircle(Brush.radialGradient(
        colors = listOf(Color(0x447B5CFC), Color(0x2200FF80), Color.Transparent),
        center = Offset(w * 0.70f, h * 0.60f), radius = w * 0.40f
    ), radius = w * 0.40f, center = Offset(w * 0.70f, h * 0.60f))
    // 星星
    for (i in 0..200) {
        val sx = (sin(i * 127.1f) * 0.5f + 0.5f) * w
        val sy = (cos(i * 311.7f) * 0.5f + 0.5f) * h
        val sr = 0.5f + abs(sin(i * 3.7f)) * 2.5f
        val alpha = 0.3f + abs(sin(i * 7.1f)) * 0.7f
        drawCircle(Color.White.copy(alpha = alpha), radius = sr, center = Offset(sx, sy))
    }
    // 行星
    drawCircle(Brush.radialGradient(
        colors = listOf(Color(0xFFFFD54F), Color(0xFFFF8F00), Color(0xFFE65100)),
        center = Offset(w * 0.72f, h * 0.22f), radius = w * 0.07f
    ), radius = w * 0.07f, center = Offset(w * 0.72f, h * 0.22f))
    // 行星环
    drawCircle(Brush.sweepGradient(
        colors = listOf(Color(0x88FFD54F), Color.Transparent, Color(0x88FFD54F), Color.Transparent)
    ), radius = w * 0.11f, center = Offset(w * 0.72f, h * 0.22f), style = Stroke(width = 2.5f))
    // 流星
    drawLine(Color.White.copy(alpha = 0.7f), Offset(w * 0.15f, h * 0.08f), Offset(w * 0.22f, h * 0.15f), strokeWidth = 2f)
    drawLine(Color.White.copy(alpha = 0.3f), Offset(w * 0.22f, h * 0.15f), Offset(w * 0.26f, h * 0.19f), strokeWidth = 1f)
}

fun DrawScope.drawSpace2(size: Size) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF020010))
    // 银河
    for (i in 0..100) {
        val cx = w * 0.5f; val cy = h * 0.5f
        val angle = i * 0.15f
        val r = w * 0.05f + i * 2f
        repeat(8) {
            val a = angle + (it - 4) * 0.3f
            val px = cx + cos(a) * r
            val py = cy + sin(a) * r * 0.4f
            if (px in 0f..w && py in 0f..h) {
                drawCircle(Color.White.copy(alpha = Random.nextFloat() * 0.4f), radius = 1f + Random.nextFloat() * 2f, center = Offset(px, py))
            }
        }
    }
    // 大行星
    drawCircle(Brush.radialGradient(
        colors = listOf(Color(0xFF64B5F6), Color(0xFF1565C0), Color(0xFF0D47A1)),
        center = Offset(w * 0.25f, h * 0.30f), radius = w * 0.09f
    ), radius = w * 0.09f, center = Offset(w * 0.25f, h * 0.30f))
    // 小卫星
    drawCircle(Color(0xFFE0E0E0).copy(alpha = 0.8f), radius = w * 0.02f, center = Offset(w * 0.38f, h * 0.25f))
}

fun DrawScope.drawSpace3(size: Size) {
    val w = size.width; val h = size.height
    drawRect(brush = Brush.verticalGradient(
        colors = listOf(Color(0xFF0A0020), Color(0xFF150030), Color(0xFF200040)),
        startY = 0f, endY = h
    ))
    // 彩色星云
    for (i in 0..6) {
        val cx = w * (0.2f + i * 0.12f)
        val cy = h * (0.3f + sin(i * 0.8f) * 0.2f)
        val colors = listOf(FluidPink, FluidPurple, FluidBlue, FluidCyan, FluidTeal, FluidOrange, FluidPink)
        drawCircle(Brush.radialGradient(
            colors = listOf(colors[i].copy(alpha = 0.3f), Color.Transparent),
            center = Offset(cx, cy), radius = w * 0.2f
        ), radius = w * 0.2f, center = Offset(cx, cy))
    }
    // 星点
    for (i in 0..150) {
        val sx = Random.nextFloat() * w
        val sy = Random.nextFloat() * h
        drawCircle(Color.White.copy(alpha = 0.3f + Random.nextFloat() * 0.7f), radius = 0.5f + Random.nextFloat() * 2f, center = Offset(sx, sy))
    }
}

// Ocean 海洋
fun DrawScope.drawOcean1(size: Size) {
    val w = size.width; val h = size.height
    drawRect(brush = Brush.verticalGradient(
        colors = listOf(Color(0xFF87CEEB), Color(0xFF4FC3F7), Color(0xFF0288D1), Color(0xFF01579B)),
        startY = 0f, endY = h
    ))
    // 波浪
    for (i in 0..10) {
        val y = h * (0.35f + i * 0.06f)
        val wavePath = Path().apply {
            moveTo(0f, y)
            for (x in 0..100) {
                val xf = x / 100f * w
                val yf = y + sin(xf / w * 6f + i * 0.8f) * h * 0.025f
                lineTo(xf, yf)
            }
        }
        drawPath(wavePath, color = Color.White.copy(alpha = 0.08f + i * 0.02f), style = Stroke(width = 1.5f))
    }
    // 太阳反射
    drawCircle(Brush.radialGradient(
        colors = listOf(Color(0x44FFD54F), Color.Transparent),
        center = Offset(w * 0.5f, h * 0.4f), radius = w * 0.3f
    ), radius = w * 0.3f, center = Offset(w * 0.5f, h * 0.4f))
    // 气泡
    for (i in 0..20) {
        val bx = (sin(i * 47.3f) * 0.5f + 0.5f) * w
        val by = h * 0.5f + (cos(i * 73.1f) * 0.5f + 0.5f) * h * 0.5f
        drawCircle(Color.White.copy(alpha = 0.15f), radius = 2f + abs(sin(i * 3f)) * 3f, center = Offset(bx, by))
    }
}

fun DrawScope.drawOcean2(size: Size) {
    val w = size.width; val h = size.height
    drawRect(brush = Brush.verticalGradient(
        colors = listOf(Color(0xFF002040), Color(0xFF003366), Color(0xFF001A33)),
        startY = 0f, endY = h
    ))
    // 海底光线
    for (i in 0..7) {
        val cx = w * (0.1f + i * 0.12f)
        drawRect(Brush.verticalGradient(
            colors = listOf(Color(0x22FFFFFF), Color.Transparent),
            startY = 0f, endY = h
        ), topLeft = Offset(cx - w * 0.01f, 0f), size = Size(w * 0.02f, h))
    }
    // 鱼群
    for (i in 0..25) {
        val fx = (sin(i * 57.3f) * 0.5f + 0.5f) * w
        val fy = h * 0.2f + (cos(i * 83.1f) * 0.5f + 0.5f) * h * 0.7f
        val fishPath = Path().apply {
            moveTo(fx + w * 0.02f, fy); lineTo(fx - w * 0.01f, fy - h * 0.01f)
            lineTo(fx - w * 0.01f, fy + h * 0.01f); close()
        }
        drawPath(fishPath, color = FluidCyan.copy(alpha = 0.4f))
    }
    // 气泡
    for (i in 0..30) {
        val bx = Random.nextFloat() * w
        val by = Random.nextFloat() * h
        drawCircle(Color.White.copy(alpha = 0.1f), radius = 2f + Random.nextFloat() * 4f, center = Offset(bx, by))
    }
}

fun DrawScope.drawOcean3(size: Size) {
    val w = size.width; val h = size.height
    drawRect(brush = Brush.verticalGradient(
        colors = listOf(Color(0xFFFFDBA4), Color(0xFF4FC3F7), Color(0xFF0288D1), Color(0xFF01579B)),
        startY = 0f, endY = h
    ))
    // 日落
    drawCircle(Brush.radialGradient(
        colors = listOf(Color(0xFFFF8F00), Color(0xFFFF6D00), Color(0xFFFF3D00)),
        center = Offset(w * 0.5f, h * 0.25f), radius = w * 0.1f
    ), radius = w * 0.1f, center = Offset(w * 0.5f, h * 0.25f))
    // 海面反射
    for (i in 0..20) {
        val y = h * 0.3f + i * 3f
        if (y >= h * 0.3f) {
            val alpha = 0.15f - i * 0.007f
            if (alpha > 0f) {
                drawLine(
                    Color(0xFFFF8F00).copy(alpha = alpha),
                    Offset(w * 0.3f, y), Offset(w * 0.7f, y),
                    strokeWidth = 1.5f
                )
            }
        }
    }
    // 海浪
    for (i in 0..8) {
        val y = h * (0.35f + i * 0.07f)
        val wavePath = Path().apply {
            moveTo(0f, y)
            for (x in 0..100) {
                val xf = x / 100f * w
                val yf = y + sin(xf / w * 8f + i) * h * 0.02f
                lineTo(xf, yf)
            }
        }
        drawPath(wavePath, color = Color.White.copy(alpha = 0.06f + i * 0.015f), style = Stroke(width = 1.2f))
    }
}

// Minimal 极简
fun DrawScope.drawMinimal1(size: Size) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFFF5F5F0))
    drawCircle(Color(0xFFE0E0D8), radius = w * 0.25f, center = Offset(w * 0.5f, h * 0.5f))
    drawCircle(Color(0xFFD0D0C8), radius = w * 0.15f, center = Offset(w * 0.5f, h * 0.5f))
    drawCircle(Color.White, radius = w * 0.08f, center = Offset(w * 0.5f, h * 0.5f))
    drawLine(Color(0xFFC0C0B8), Offset(w * 0.5f, h * 0.15f), Offset(w * 0.5f, h * 0.85f), strokeWidth = 0.5f)
    drawLine(Color(0xFFC0C0B8), Offset(w * 0.15f, h * 0.5f), Offset(w * 0.85f, h * 0.5f), strokeWidth = 0.5f)
}

fun DrawScope.drawMinimal2(size: Size) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF1A1A1A))
    // 金线
    drawLine(Color(0xFFD4AF37), Offset(w * 0.05f, h * 0.3f), Offset(w * 0.95f, h * 0.3f), strokeWidth = 1.5f)
    drawLine(Color(0xFFD4AF37), Offset(w * 0.05f, h * 0.7f), Offset(w * 0.95f, h * 0.7f), strokeWidth = 1.5f)
    drawLine(Color(0xFFD4AF37), Offset(w * 0.3f, h * 0.05f), Offset(w * 0.3f, h * 0.95f), strokeWidth = 1.5f)
    drawLine(Color(0xFFD4AF37), Offset(w * 0.7f, h * 0.05f), Offset(w * 0.7f, h * 0.95f), strokeWidth = 1.5f)
    // 中心小方块
    drawRoundRect(Color(0xFFD4AF37).copy(alpha = 0.3f), cornerRadius = CornerRadius(2f),
        topLeft = Offset(w * 0.45f, h * 0.45f), size = Size(w * 0.1f, h * 0.1f))
}

fun DrawScope.drawMinimal3(size: Size) {
    val w = size.width; val h = size.height
    drawRect(brush = Brush.verticalGradient(
        colors = listOf(Color(0xFFE8E0D8), Color(0xFFD8D0C8)),
        startY = 0f, endY = h
    ))
    // 弧形
    val arcPath = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(Offset(w * 0.15f, h * 0.15f), Size(w * 0.7f, h * 0.7f)))
    }
    drawPath(arcPath, color = Color(0xFFB8A898), style = Stroke(width = 1.5f))
    // 小圆圈
    drawCircle(Color(0xFFC4B5A0), radius = w * 0.04f, center = Offset(w * 0.3f, h * 0.5f))
    drawCircle(Color(0xFFC4B5A0), radius = w * 0.03f, center = Offset(w * 0.5f, h * 0.35f))
    drawCircle(Color(0xFFC4B5A0), radius = w * 0.025f, center = Offset(w * 0.7f, h * 0.5f))
}

// Night 夜空
fun DrawScope.drawNight1(size: Size) {
    val w = size.width; val h = size.height
    drawRect(brush = Brush.verticalGradient(
        colors = listOf(Color(0xFF0A0A2E), Color(0xFF1A1A4E), Color(0xFF0D0D2E)),
        startY = 0f, endY = h
    ))
    // 月亮
    drawCircle(Color(0xFFFFFDE7).copy(alpha = 0.95f), radius = w * 0.1f, center = Offset(w * 0.75f, h * 0.18f))
    drawCircle(Brush.radialGradient(
        colors = listOf(Color(0x33FFFFFDE7), Color.Transparent),
        center = Offset(w * 0.75f, h * 0.18f), radius = w * 0.22f
    ), radius = w * 0.22f, center = Offset(w * 0.75f, h * 0.18f))
    // 星星
    for (i in 0..120) {
        val sx = (sin(i * 127.1f) * 0.5f + 0.5f) * w
        val sy = (cos(i * 311.7f) * 0.5f + 0.5f) * h * 0.7f
        drawCircle(Color.White.copy(alpha = 0.3f + abs(sin(i * 7f)) * 0.7f), radius = 1f + abs(sin(i * 3f)) * 2f, center = Offset(sx, sy))
    }
    // 城市剪影
    val buildings = listOf(
        Pair(0.05f, 0.6f), Pair(0.12f, 0.52f), Pair(0.20f, 0.65f), Pair(0.28f, 0.48f),
        Pair(0.35f, 0.58f), Pair(0.42f, 0.45f), Pair(0.50f, 0.62f), Pair(0.58f, 0.50f),
        Pair(0.65f, 0.55f), Pair(0.72f, 0.42f), Pair(0.80f, 0.58f), Pair(0.88f, 0.48f)
    )
    for ((px, ph) in buildings) {
        drawRect(Color(0xFF0A0A1A), topLeft = Offset(w * px, h * ph), size = Size(w * 0.07f, h * (1f - ph)))
        // 窗户
        for (wi in 0..(if (ph < 0.5f) 3 else 2)) {
            for (hi in 0..(if (ph < 0.5f) 4 else 3)) {
                if (Random.nextFloat() > 0.4f) {
                    drawRect(Color(0xFFFFD54F).copy(alpha = 0.5f),
                        topLeft = Offset(w * px + w * 0.015f + wi * w * 0.015f, h * ph + h * 0.03f + hi * h * 0.05f),
                        size = Size(w * 0.008f, h * 0.015f))
                }
            }
        }
    }
}

fun DrawScope.drawNight2(size: Size) {
    val w = size.width; val h = size.height
    drawRect(brush = Brush.verticalGradient(
        colors = listOf(Color(0xFF0F0C29), Color(0xFF302B63), Color(0xFF24243E)),
        startY = 0f, endY = h
    ))
    // 极光
    for (i in 0..4) {
        val ay = h * (0.12f + i * 0.08f)
        drawCircle(Brush.horizontalGradient(
            colors = listOf(Color(0x33FF4081), Color(0x3300E5FF), Color(0x33FF4081)),
            startX = 0f, endX = w
        ), radius = h * 0.15f, center = Offset(w * 0.5f, ay))
    }
    // 北斗七星
    val dipper = listOf(
        Offset(w * 0.2f, h * 0.15f), Offset(w * 0.25f, h * 0.18f),
        Offset(w * 0.30f, h * 0.22f), Offset(w * 0.35f, h * 0.28f),
        Offset(w * 0.33f, h * 0.35f), Offset(w * 0.28f, h * 0.38f),
        Offset(w * 0.23f, h * 0.36f)
    )
    for (i in dipper.indices) {
        drawCircle(Color.White.copy(alpha = 0.8f), radius = 3f, center = dipper[i])
        if (i > 0) drawLine(Color.White.copy(alpha = 0.3f), dipper[i-1], dipper[i], strokeWidth = 1f)
    }
    // 雪山
    val mtPath = Path().apply {
        moveTo(0f, h * 0.8f); lineTo(w * 0.2f, h * 0.55f)
        lineTo(w * 0.35f, h * 0.72f); lineTo(w * 0.5f, h * 0.45f)
        lineTo(w * 0.65f, h * 0.68f); lineTo(w * 0.8f, h * 0.50f)
        lineTo(w, h * 0.65f); lineTo(w, h); lineTo(0f, h); close()
    }
    drawPath(mtPath, color = Color(0xFF0A0A1A))
    // 雪顶
    val snowPath = Path().apply {
        moveTo(w * 0.45f, h * 0.52f); lineTo(w * 0.5f, h * 0.45f)
        lineTo(w * 0.55f, h * 0.52f); close()
    }
    drawPath(snowPath, color = Color.White.copy(alpha = 0.2f))
}

fun DrawScope.drawNight3(size: Size) {
    val w = size.width; val h = size.height
    drawRect(brush = Brush.verticalGradient(
        colors = listOf(Color(0xFF000010), Color(0xFF0A0030), Color(0xFF150050)),
        startY = 0f, endY = h
    ))
    // 银河
    for (i in 0..200) {
        val cx = w * 0.5f; val cy = h * 0.5f
        val angle = i * 0.08f
        val r = w * 0.02f + i * 1.5f
        val px = cx + cos(angle) * r
        val py = cy + sin(angle) * r * 0.3f + h * 0.1f
        if (px in 0f..w && py in 0f..h) {
            drawCircle(Color.White.copy(alpha = 0.1f + Random.nextFloat() * 0.2f), radius = 1f + Random.nextFloat() * 2f, center = Offset(px, py))
        }
    }
    // 流星
    drawLine(Brush.linearGradient(
        colors = listOf(Color.White.copy(alpha = 0.8f), Color.Transparent),
        start = Offset(w * 0.1f, h * 0.05f), end = Offset(w * 0.25f, h * 0.2f)
    ), Offset(w * 0.1f, h * 0.05f), Offset(w * 0.25f, h * 0.2f), strokeWidth = 2f)
}

// Liquid 液态
fun DrawScope.drawLiquid1(size: Size) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF08080F))
    // 流体色块
    drawCircle(Brush.radialGradient(
        colors = listOf(FluidCyan.copy(alpha = 0.5f), Color.Transparent),
        center = Offset(w * 0.3f, h * 0.3f), radius = w * 0.5f
    ), radius = w * 0.5f, center = Offset(w * 0.3f, h * 0.3f))
    drawCircle(Brush.radialGradient(
        colors = listOf(FluidPurple.copy(alpha = 0.5f), Color.Transparent),
        center = Offset(w * 0.7f, h * 0.5f), radius = w * 0.45f
    ), radius = w * 0.45f, center = Offset(w * 0.7f, h * 0.5f))
    drawCircle(Brush.radialGradient(
        colors = listOf(FluidPink.copy(alpha = 0.4f), Color.Transparent),
        center = Offset(w * 0.4f, h * 0.7f), radius = w * 0.4f
    ), radius = w * 0.4f, center = Offset(w * 0.4f, h * 0.7f))
    drawCircle(Brush.radialGradient(
        colors = listOf(FluidTeal.copy(alpha = 0.4f), Color.Transparent),
        center = Offset(w * 0.6f, h * 0.2f), radius = w * 0.35f
    ), radius = w * 0.35f, center = Offset(w * 0.6f, h * 0.2f))
    // 流体波纹
    for (i in 0..15) {
        val y = h * (0.05f + i * 0.06f)
        val wavePath = Path().apply {
            moveTo(0f, y)
            for (x in 0..100) {
                val xf = x / 100f * w
                val yf = y + sin(xf / w * 4f + i * 0.7f) * h * 0.02f
                lineTo(xf, yf)
            }
        }
        drawPath(wavePath, color = Color.White.copy(alpha = 0.05f), style = Stroke(width = 1f))
    }
    // 亮点
    for (i in 0..25) {
        val px = (cos(i * 0.7f) * 0.5f + 0.5f) * w
        val py = (sin(i * 0.9f) * 0.5f + 0.5f) * h
        drawCircle(Color.White.copy(alpha = 0.3f), radius = 2f, center = Offset(px, py))
    }
}

fun DrawScope.drawLiquid2(size: Size) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF050510))
    // 流体渐变圈
    for (i in 0..8) {
        val r = w * (0.05f + i * 0.05f)
        val colors = listOf(FluidCyan, FluidPurple, FluidPink, FluidBlue, FluidTeal, FluidOrange, FluidCyan, FluidPurple, FluidPink)
        drawCircle(colors[i].copy(alpha = 0.35f), radius = r, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(width = 3f))
    }
    // 中心流体
    drawCircle(Brush.radialGradient(
        colors = listOf(FluidCyan.copy(alpha = 0.3f), FluidPurple.copy(alpha = 0.15f), Color.Transparent),
        center = Offset(w * 0.5f, h * 0.5f), radius = w * 0.15f
    ), radius = w * 0.15f, center = Offset(w * 0.5f, h * 0.5f))
    // 流动线
    for (i in 0..12) {
        val angle = i * 2 * PI.toFloat() / 12f
        val r1 = w * 0.2f; val r2 = w * 0.45f
        drawLine(
            FluidCyan.copy(alpha = 0.15f),
            Offset(w * 0.5f + cos(angle) * r1, h * 0.5f + sin(angle) * r1),
            Offset(w * 0.5f + cos(angle) * r2, h * 0.5f + sin(angle) * r2),
            strokeWidth = 1.5f
        )
    }
}

fun DrawScope.drawLiquid3(size: Size) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF060610))
    // 对角的流体色块
    drawRect(Brush.linearGradient(
        colors = listOf(FluidPurple.copy(alpha = 0.35f), Color.Transparent),
        start = Offset(0f, 0f), end = Offset(w * 0.6f, h * 0.6f)
    ))
    drawRect(Brush.linearGradient(
        colors = listOf(Color.Transparent, FluidCyan.copy(alpha = 0.35f)),
        start = Offset(w * 0.4f, h * 0.4f), end = Offset(w, h)
    ))
    drawRect(Brush.linearGradient(
        colors = listOf(FluidPink.copy(alpha = 0.25f), Color.Transparent),
        start = Offset(w, 0f), end = Offset(0f, h * 0.7f)
    ))
    // 流体曲线
    val curvePath = Path().apply {
        moveTo(0f, h * 0.5f)
        cubicTo(w * 0.25f, h * 0.3f, w * 0.75f, h * 0.7f, w, h * 0.5f)
    }
    drawPath(curvePath, color = Color.White.copy(alpha = 0.15f), style = Stroke(width = 2f))
    val curvePath2 = Path().apply {
        moveTo(0f, h * 0.6f)
        cubicTo(w * 0.3f, h * 0.4f, w * 0.7f, h * 0.8f, w, h * 0.6f)
    }
    drawPath(curvePath2, color = FluidTeal.copy(alpha = 0.2f), style = Stroke(width = 1.5f))
    // 粒子
    for (i in 0..40) {
        val px = (sin(i * 37.3f) * 0.5f + 0.5f) * w
        val py = (cos(i * 53.1f) * 0.5f + 0.5f) * h
        val colors = listOf(FluidCyan, FluidPurple, FluidPink, FluidTeal, FluidBlue)
        drawCircle(colors[i % colors.size].copy(alpha = 0.4f), radius = 2f + abs(sin(i * 2f)) * 2f, center = Offset(px, py))
    }
}

// ── 壁纸数据 ─────────────────────────────────────────────────────────

val allWallpapers = listOf(
    // Nature
    WallpaperItem(1, "Nature", "日出山林", DrawScope::drawNature1),
    WallpaperItem(2, "Nature", "极光森林", DrawScope::drawNature2),
    WallpaperItem(3, "Nature", "樱花春色", DrawScope::drawNature3),
    // Abstract
    WallpaperItem(4, "Abstract", "几何重叠", DrawScope::drawAbstract1),
    WallpaperItem(5, "Abstract", "彩色圆环", DrawScope::drawAbstract2),
    WallpaperItem(6, "Abstract", "光带交错", DrawScope::drawAbstract3),
    // Gradient
    WallpaperItem(7, "Gradient", "日落渐变", DrawScope::drawGradient1),
    WallpaperItem(8, "Gradient", "紫夜渐变", DrawScope::drawGradient2),
    WallpaperItem(9, "Gradient", "深海渐变", DrawScope::drawGradient3),
    // Space
    WallpaperItem(10, "Space", "星云行星", DrawScope::drawSpace1),
    WallpaperItem(11, "Space", "银河星系", DrawScope::drawSpace2),
    WallpaperItem(12, "Space", "彩色星云", DrawScope::drawSpace3),
    // Ocean
    WallpaperItem(13, "Ocean", "阳光海浪", DrawScope::drawOcean1),
    WallpaperItem(14, "Ocean", "深海鱼群", DrawScope::drawOcean2),
    WallpaperItem(15, "Ocean", "日落大海", DrawScope::drawOcean3),
    // Minimal
    WallpaperItem(16, "Minimal", "极简同心", DrawScope::drawMinimal1),
    WallpaperItem(17, "Minimal", "金线几何", DrawScope::drawMinimal2),
    WallpaperItem(18, "Minimal", "素雅弧线", DrawScope::drawMinimal3),
    // Night
    WallpaperItem(19, "Night", "月夜城市", DrawScope::drawNight1),
    WallpaperItem(20, "Night", "极光雪山", DrawScope::drawNight2),
    WallpaperItem(21, "Night", "银河流星", DrawScope::drawNight3),
    // Liquid
    WallpaperItem(22, "Liquid", "流体色块", DrawScope::drawLiquid1),
    WallpaperItem(23, "Liquid", "液态光环", DrawScope::drawLiquid2),
    WallpaperItem(24, "Liquid", "流动曲线", DrawScope::drawLiquid3)
)

// ── 辅助函数 ─────────────────────────────────────────────────────────

private fun DrawScope.drawCloud(cx: Float, cy: Float, r: Float, alpha: Float) {
    drawCircle(Color.White.copy(alpha = alpha), radius = r, center = Offset(cx, cy))
    drawCircle(Color.White.copy(alpha = alpha * 0.8f), radius = r * 0.7f, center = Offset(cx - r * 0.5f, cy + r * 0.1f))
    drawCircle(Color.White.copy(alpha = alpha * 0.8f), radius = r * 0.6f, center = Offset(cx + r * 0.4f, cy + r * 0.15f))
    drawCircle(Color.White.copy(alpha = alpha * 0.6f), radius = r * 0.5f, center = Offset(cx - r * 0.2f, cy - r * 0.15f))
}

/**
 * 将壁纸的 Compose 绘制函数渲染为 Android Bitmap。
 *
 * 关键：用 [CanvasDrawScope] + Canvas(ImageBitmap) 复用预览用的同一套 [drawFn]，
 * 保证"设壁纸/保存"得到的图与屏幕预览完全一致（此前是另起一套简陋渐变+圆点，货不对板）。
 */
private fun renderWallpaperToBitmap(
    width: Int,
    height: Int,
    drawFn: DrawScope.(Size) -> Unit
): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    // asImageBitmap 包装底层 Bitmap（不拷贝），绘制直接写入该 Bitmap
    val composeCanvas = androidx.compose.ui.graphics.Canvas(bitmap.asImageBitmap())
    val size = Size(width.toFloat(), height.toFloat())
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
        density = androidx.compose.ui.unit.Density(1f, 1f),
        layoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr,
        canvas = composeCanvas,
        size = size
    ) {
        drawFn(this, size)
    }
    return bitmap
}

// ── 主屏幕 ───────────────────────────────────────────────────────────

@Composable
fun GalleryScreen(animTime: Float, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedCategory by remember { mutableStateOf("Nature") }
    var selectedWallpaper by remember { mutableStateOf<WallpaperItem?>(null) }
    var showFullScreen by remember { mutableStateOf(false) }

    // 已下载的壁纸文件列表（实时加载，下载后立即生效）
    var downloadedWallpaperFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedDownloadedFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(selectedCategory) {
        if (selectedCategory == "Downloaded") {
            downloadedWallpaperFiles = ResourceManager.getWallpaperFiles(context)
        }
    }

    val filteredWallpapers = remember(selectedCategory) {
        allWallpapers.filter { it.category == selectedCategory }
    }

    val fullScreenWallpapers = remember(selectedCategory) {
        allWallpapers.filter { it.category == selectedCategory }
    }
    val initialPage = remember(selectedWallpaper) {
        fullScreenWallpapers.indexOfFirst { it.id == selectedWallpaper?.id }.coerceAtLeast(0)
    }

    LiquidGlassScaffold(animTime = animTime) {
        if (!showFullScreen && selectedDownloadedFile == null) {
            // 网格浏览模式
            GalleryGridContent(
                animTime = animTime,
                selectedCategory = selectedCategory,
                filteredWallpapers = filteredWallpapers,
                downloadedWallpaperFiles = downloadedWallpaperFiles,
                onCategorySelected = { selectedCategory = it },
                onWallpaperTap = { wallpaper ->
                    selectedWallpaper = wallpaper
                    showFullScreen = true
                },
                onDownloadedWallpaperTap = { file ->
                    selectedDownloadedFile = file
                },
                onBack = onBack
            )
        } else if (selectedDownloadedFile != null) {
            // 已下载图片全屏模式
            DownloadedImageFullScreen(
                file = selectedDownloadedFile!!,
                onBack = {
                    selectedDownloadedFile = null
                },
                onSetWallpaper = { file ->
                    scope.launch {
                        setWallpaperFromFile(context, file)
                    }
                },
                onSaveWallpaper = { file ->
                    scope.launch {
                        saveWallpaperFromFile(context, file)
                    }
                }
            )
        } else {
            // 全屏模式
            FullScreenViewer(
                wallpapers = fullScreenWallpapers,
                initialPage = initialPage,
                onBack = {
                    showFullScreen = false
                    selectedWallpaper = null
                },
                onSetWallpaper = { wallpaper ->
                    scope.launch {
                        setWallpaper(context, wallpaper)
                    }
                },
                onSaveWallpaper = { wallpaper ->
                    scope.launch {
                        saveWallpaper(context, wallpaper)
                    }
                }
            )
        }
    }
}

// ── 网格浏览内容 ──────────────────────────────────────────────────────

@Composable
private fun GalleryGridContent(
    animTime: Float,
    selectedCategory: String,
    filteredWallpapers: List<WallpaperItem>,
    downloadedWallpaperFiles: List<File>,
    onCategorySelected: (String) -> Unit,
    onWallpaperTap: (WallpaperItem) -> Unit,
    onDownloadedWallpaperTap: (File) -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // 顶部栏
        GalleryTopBar(animTime = animTime, onBack = onBack)

        Spacer(modifier = Modifier.height(8.dp))

        // 分类标签
        CategoryTabs(
            categories = wallpaperCategories,
            selectedCategory = selectedCategory,
            onCategorySelected = onCategorySelected
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 壁纸网格
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            if (selectedCategory == "Downloaded") {
                if (downloadedWallpaperFiles.isEmpty()) {
                    item(span = { GridItemSpan(3) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.CloudDownload, null, tint = appTextTertiary(), modifier = Modifier.size(48.dp))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("暂无已下载壁纸", fontSize = 14.sp, color = appTextSecondary())
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("请在关于页下载基础资源包", fontSize = 11.sp, color = appTextTertiary())
                            }
                        }
                    }
                } else {
                    items(downloadedWallpaperFiles) { file ->
                        DownloadedWallpaperThumbnail(
                            file = file,
                            onClick = { onDownloadedWallpaperTap(file) }
                        )
                    }
                }
            } else {
                itemsIndexed(filteredWallpapers) { index, wallpaper ->
                    WallpaperThumbnail(
                        wallpaper = wallpaper,
                        onClick = { onWallpaperTap(wallpaper) }
                    )
                }
            }
        }
    }
}

// ── 顶部栏 ───────────────────────────────────────────────────────────

@Composable
private fun GalleryTopBar(animTime: Float, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 返回按钮
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(GlassClear)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.ArrowBack, null, tint = appTextPrimary(), modifier = Modifier.size(22.dp))
        }

        // 标题
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "壁纸画廊",
                fontSize = 22.sp,
                fontWeight = FontWeight.Light,
                color = appTextPrimary(),
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "WALLPAPER GALLERY",
                fontSize = 10.sp,
                fontWeight = FontWeight.Light,
                color = appTextTertiary(),
                letterSpacing = 6.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ── 分类标签栏 ─────────────────────────────────────────────────────────

@Composable
private fun CategoryTabs(
    categories: List<WallpaperCategory>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.forEach { category ->
            val isSelected = category.name == selectedCategory
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) category.accentColor.copy(alpha = 0.2f) else Color.Transparent,
                animationSpec = tween(250), label = "tabBg"
            )
            val borderColor by animateColorAsState(
                targetValue = if (isSelected) category.accentColor.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f),
                animationSpec = tween(250), label = "tabBorder"
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) category.accentColor else TextSecondary,
                animationSpec = tween(250), label = "tabText"
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(bgColor)
                    .then(
                        if (isSelected) Modifier.glassSurface(cornerRadius = 20.dp, glassAlpha = 0.12f, showBorder = true)
                        else Modifier
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onCategorySelected(category.name) }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    category.icon()
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = category.label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = textColor
                    )
                }
            }
        }
    }
}

// ── 壁纸缩略图卡片 ─────────────────────────────────────────────────────

@Composable
private fun WallpaperThumbnail(
    wallpaper: WallpaperItem,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 350f),
        label = "thumbScale"
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .aspectRatio(0.75f)
            .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.12f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                pressed = true
                onClick()
            }
            .clip(RoundedCornerShape(16.dp))
    ) {
        // 壁纸预览
        Canvas(modifier = Modifier.fillMaxSize()) {
            wallpaper.draw(this, size)
        }

        // 玻璃叠加层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
                        startY = 0f, endY = Float.POSITIVE_INFINITY
                    )
                )
        )

        // 名称标签
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
        ) {
            Text(
                text = wallpaper.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = appTextPrimary().copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    LaunchedEffect(pressed) {
        if (pressed) {
            kotlinx.coroutines.delay(150)
            pressed = false
        }
    }
}

// ── 全屏查看器 ─────────────────────────────────────────────────────────

@Composable
private fun FullScreenViewer(
    wallpapers: List<WallpaperItem>,
    initialPage: Int,
    onBack: () -> Unit,
    onSetWallpaper: (WallpaperItem) -> Unit,
    onSaveWallpaper: (WallpaperItem) -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialPage) { wallpapers.size }
    val currentWallpaper = remember(pagerState.currentPage) {
        if (pagerState.currentPage in wallpapers.indices) wallpapers[pagerState.currentPage] else wallpapers.first()
    }
    var showOverlay by remember { mutableStateOf(true) }
    var zoomScale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 壁纸分页
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val wallpaper = wallpapers[page]

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(wallpaper.id) {
                        detectTransformGestures { centroid, pan, zoom, rotation ->
                            zoomScale = (zoomScale * zoom).coerceIn(1f, 5f)
                            if (zoomScale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        showOverlay = !showOverlay
                    }
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoomScale
                            scaleY = zoomScale
                            translationX = offsetX
                            translationY = offsetY
                        }
                ) {
                    wallpaper.draw(this, size)
                }
            }
        }

        // 页指示器
        if (showOverlay && wallpapers.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(wallpapers.size) { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == pagerState.currentPage) 18.dp else 6.dp, 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (index == pagerState.currentPage) Color.White else Color.White.copy(alpha = 0.4f))
                    )
                }
            }
        }

        // 覆盖层（可切换显示/隐藏）
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 顶部渐变
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                            )
                        )
                )

                // 返回按钮
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .statusBarsPadding()
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }

                // 壁纸名称
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 48.dp)
                        .statusBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = currentWallpaper.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                    val category = wallpaperCategories.find { it.name == currentWallpaper.category }
                    if (category != null) {
                        Text(
                            text = category.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Light,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }

                // 底部操作栏
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 保存按钮
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.2f)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onSaveWallpaper(currentWallpaper) }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SaveAlt, null, tint = appTextPrimary(), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("保存", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = appTextPrimary())
                            }
                        }

                        // 设为壁纸按钮
                        Box(
                            modifier = Modifier
                                .weight(1.3f)
                                .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.25f)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onSetWallpaper(currentWallpaper) }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Wallpaper, null, tint = FluidCyan, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("设为壁纸", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = FluidCyan)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 壁纸操作 ───────────────────────────────────────────────────────────

private suspend fun setWallpaper(context: Context, wallpaper: WallpaperItem) {
    withContext(Dispatchers.IO) {
        try {
            val wm = WallpaperManager.getInstance(context)
            val w = context.resources.displayMetrics.widthPixels
            val h = context.resources.displayMetrics.heightPixels
            // 复用预览绘制函数渲染为 Bitmap，所见即所得（不再用简陋渐变替代）
            val bitmap = renderWallpaperToBitmap(w, h, wallpaper.draw)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK or WallpaperManager.FLAG_SYSTEM)
            } else {
                @Suppress("DEPRECATION")
                wm.setBitmap(bitmap)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "壁纸已设置成功", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "设置壁纸失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private suspend fun saveWallpaper(context: Context, wallpaper: WallpaperItem) {
    withContext(Dispatchers.IO) {
        try {
            val w = context.resources.displayMetrics.widthPixels
            val h = context.resources.displayMetrics.heightPixels
            // 复用预览绘制函数渲染为 Bitmap，所见即所得
            val bitmap = renderWallpaperToBitmap(w, h, wallpaper.draw)

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "LiquidGlass_${wallpaper.name}_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LiquidGlass")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(it, contentValues, null, null)
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "壁纸已保存到相册", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// ── 已下载壁纸缩略图 ─────────────────────────────────────────────────

@Composable
private fun DownloadedWallpaperThumbnail(
    file: File,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 350f),
        label = "dThumbScale"
    )

    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = 4
                }
                val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                if (bmp != null) {
                    imageBitmap = bmp.asImageBitmap()
                }
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = Modifier
            .scale(scale)
            .aspectRatio(0.75f)
            .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.12f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                pressed = true
                onClick()
            }
            .clip(RoundedCornerShape(16.dp))
    ) {
        // 图片预览
        val bmp = imageBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = file.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A2E)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = FluidCyan, strokeWidth = 1.5.dp)
            }
        }

        // 玻璃叠加层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
                        startY = 0f, endY = Float.POSITIVE_INFINITY
                    )
                )
        )

        // 名称标签
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
        ) {
            Text(
                text = file.nameWithoutExtension,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = appTextPrimary().copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    LaunchedEffect(pressed) {
        if (pressed) {
            kotlinx.coroutines.delay(150)
            pressed = false
        }
    }
}

// ── 已下载图片全屏查看 ───────────────────────────────────────────────

@Composable
private fun DownloadedImageFullScreen(
    file: File,
    onBack: () -> Unit,
    onSetWallpaper: (File) -> Unit,
    onSaveWallpaper: (File) -> Unit
) {
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try {
                val bmp = decodeSampledBitmap(file.absolutePath, reqWidth = 1080, reqHeight = 1920)
                if (bmp != null) {
                    imageBitmap = bmp.asImageBitmap()
                } else {
                    loadError = true
                }
            } catch (_: Exception) {
                loadError = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val bmp = imageBitmap
        if (loadError) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Warning, null, tint = FluidOrange, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("图片无法加载", fontSize = 14.sp, color = appTextSecondary())
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(file.name, fontSize = 11.sp, color = appTextTertiary())
                }
            }
        } else if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = file.name,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 5f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    },
                contentScale = ContentScale.Fit
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = FluidCyan)
            }
        }

        // 顶部返回按钮
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .padding(16.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(GlassClear)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, null, tint = appTextPrimary(), modifier = Modifier.size(22.dp))
        }

        // 底部操作栏
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 保存按钮
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.2f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSaveWallpaper(file) }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SaveAlt, null, tint = appTextPrimary(), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("保存", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = appTextPrimary())
                    }
                }

                // 设为壁纸按钮
                Box(
                    modifier = Modifier
                        .weight(1.3f)
                        .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.25f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSetWallpaper(file) }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Wallpaper, null, tint = FluidCyan, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("设为壁纸", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = FluidCyan)
                    }
                }
            }
        }
    }
}

// ── 已下载壁纸操作 ───────────────────────────────────────────────────

private suspend fun setWallpaperFromFile(context: Context, file: File) {
    withContext(Dispatchers.IO) {
        try {
            val bitmap = decodeSampledBitmap(file.absolutePath, reqWidth = 1080, reqHeight = 1920)
                ?: throw RuntimeException("无法解码图片，文件可能损坏")
            val wm = WallpaperManager.getInstance(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK or WallpaperManager.FLAG_SYSTEM)
            } else {
                @Suppress("DEPRECATION")
                wm.setBitmap(bitmap)
            }
            bitmap.recycle()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "壁纸已设置成功", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "设置壁纸失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private suspend fun saveWallpaperFromFile(context: Context, file: File) {
    withContext(Dispatchers.IO) {
        try {
            val bitmap = decodeSampledBitmap(file.absolutePath, reqWidth = 1080, reqHeight = 1920)
                ?: throw RuntimeException("无法解码图片，文件可能损坏")

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "LiquidGlass_${file.nameWithoutExtension}_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LiquidGlass")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(it, contentValues, null, null)
            }
            bitmap.recycle()

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "壁纸已保存到相册", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// ── Bitmap 采样解码工具（防止大图 OOM）─────────────────────────────

private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        BitmapFactory.decodeFile(path, options)
    } catch (_: OutOfMemoryError) {
        try {
            val options = BitmapFactory.Options().apply { inSampleSize = 8 }
            BitmapFactory.decodeFile(path, options)
        } catch (_: Exception) { null }
    } catch (_: Exception) { null }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}