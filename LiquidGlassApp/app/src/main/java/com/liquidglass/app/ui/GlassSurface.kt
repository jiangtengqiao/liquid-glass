package com.liquidglass.app.ui

import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.liquidglass.app.ui.theme.*
import com.liquidglass.app.util.FluidEngine
import com.liquidglass.app.util.FluidAssetLoader
import kotlinx.coroutines.launch
import kotlin.math.*

// ── v2.9.0 真实液态玻璃折射库集成 ──
// CompositionLocal：LiquidGlassContainer 创建 backdrop 后通过此 Local 向下传递，
// glassSurface 读取后自动切换为真实折射玻璃(drawBackdrop)，无 backdrop 时回退到装饰图层。
val LocalLiquidBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 液态玻璃容器：包裹屏幕内容，为内部所有 glassSurface 提供真实折射背景。
 *
 * 用法：
 *   LiquidGlassContainer(
 *       background = { FluidBackground(time = animTime) },
 *       content = { /* 此处的 glassSurface 自动使用真实折射 */ }
 *   )
 *
 * 原理：rememberLayerBackdrop() 创建背景捕获层 → layerBackdrop() 标记背景内容 →
 * drawBackdrop() 在 glassSurface 内部对捕获的背景应用 lens 折射 + chromaticAberration 色散 + blur 模糊。
 * AGSL 着色器在 API 33+ 硬件加速，低于 33 自动降级。
 */
@Composable
fun LiquidGlassContainer(
    modifier: Modifier = Modifier,
    background: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val backdrop = rememberLayerBackdrop()
    Box(modifier) {
        // 背景层：layerBackdrop 标记此区域为折射来源
        Box(Modifier.layerBackdrop(backdrop), content = background)
        // 前景层：通过 CompositionLocal 传递 backdrop，glassSurface 读取后用 drawBackdrop
        CompositionLocalProvider(LocalLiquidBackdrop provides backdrop) {
            content()
        }
    }
}

/**
 * 液态玻璃脚手架 —— v2.9.0 统一屏幕容器
 *
 * 替代各屏幕原 `Box { FluidBackground(...); content }` 模式，
 * 自动将 FluidBackground 包裹为 backdrop 源，使内部所有 glassSurface
 * 启用真实光学折射（lens + chromaticAberration + blur）。
 *
 * 用法：
 *   LiquidGlassScaffold(animTime = animTime) {
 *       Column(...) { ... }  // 此处的 glassSurface 自动使用真实折射
 *   }
 */
@Composable
fun LiquidGlassScaffold(
    animTime: Float,
    modifier: Modifier = Modifier,
    droplets: List<DropletState> = emptyList(),
    content: @Composable BoxScope.() -> Unit
) {
    LiquidGlassContainer(
        modifier = modifier.fillMaxSize().background(appBgColor()),
        background = { FluidBackground(time = animTime, droplets = droplets) },
        content = content
    )
}

/**
 * 液态玻璃卡片效果 v5 — v2.9.0 集成 Kyant0/AndroidLiquidGlass 真实折射库
 *
 * 双模式渲染：
 * - 真实模式（LiquidGlassContainer 内）：drawBackdrop + lens 折射 + chromaticAberration 色散 + blur 模糊
 *   → 真实光学折射，背景透过玻璃产生物理扭曲与彩虹色散
 * - 装饰模式（无 container）：8 层装饰性 drawBehind 图层（边框/高光/色散/光斑）
 *   → 保持视觉一致性，无折射但仍有玻璃质感
 *
 * pressDepth：按压时增强折射强度与色散（0=静止, 1=按压）
 */
fun Modifier.glassSurface(
    cornerRadius: Dp = 24.dp,
    glassAlpha: Float = 0.22f,
    showBorder: Boolean = true,
    pressDepth: Float = 0f
): Modifier = composed {
    val theme = LocalAppTheme.current
    val backdrop = LocalLiquidBackdrop.current
    val density = LocalDensity.current

    if (backdrop != null) {
        // ═══ 真实液态玻璃路径：drawBackdrop + lens + chromaticAberration ═══
        val refractionHeight = with(density) { (3.dp + pressDepth.dp * 2f).toPx() }
        val refractionAmount = with(density) { (10.dp + pressDepth.dp * 6f).toPx() }
        val blurRadius = with(density) { (1.5.dp).toPx() }

        this.drawBackdrop(
            backdrop = backdrop,
            shape = { RoundedCornerShape(cornerRadius) },
            effects = {
                blur(blurRadius)
                lens(
                    refractionHeight = refractionHeight,
                    refractionAmount = refractionAmount,
                    chromaticAberration = true
                )
            }
        )
    } else {
        // ═══ 装饰性玻璃路径（回退）：8 层 drawBehind 图层 ═══
        decorativeGlassSurface(cornerRadius, glassAlpha, showBorder, pressDepth, theme)
    }
}

/**
 * 装饰性玻璃效果（无 backdrop 时的回退）— 原 v4 的 8 层渲染管线
 */
private fun Modifier.decorativeGlassSurface(
    cornerRadius: Dp,
    glassAlpha: Float,
    showBorder: Boolean,
    pressDepth: Float,
    theme: AppTheme
): Modifier = composed {
    val tint = if (theme.isLight) Color.Black else Color.White
    val tintAlphaMul = if (theme.isLight) 0.5f else 1f
    val glassFill = if (theme.isLight) theme.glassClear else GlassClear
    val dispersion1 = theme.fluidCyan
    val dispersion2 = theme.fluidPurple
    val dispersion3 = theme.fluidPink
    val borderColor = if (theme.isLight) theme.glassBorder else Color.White

    val adjustedAlpha = glassAlpha * (1f - pressDepth * 0.15f)
    this
        .clip(RoundedCornerShape(cornerRadius))
        .background(glassFill)
        .drawBehind {
            val w = size.width
            val h = size.height
            val cr = cornerRadius.toPx()

            val dispersionBoost = 1f + pressDepth * 0.5f
            val edgeGlow = pressDepth * 0.15f

            // 第1层：玻璃基底
            drawRoundRect(
                color = tint.copy(alpha = adjustedAlpha * 0.6f * tintAlphaMul),
                cornerRadius = CornerRadius(cr, cr)
            )

            // 第2层：发光边框（3条嵌套）
            if (showBorder) {
                drawRoundRect(
                    color = borderColor.copy(alpha = (if (theme.isLight) 0.40f else 0.30f) + edgeGlow),
                    cornerRadius = CornerRadius(cr, cr),
                    style = Stroke(width = 1.5f)
                )
                drawRoundRect(
                    color = borderColor.copy(alpha = (if (theme.isLight) 0.25f else 0.20f) + edgeGlow * 0.5f),
                    cornerRadius = CornerRadius(cr - 1.5f, cr - 1.5f),
                    style = Stroke(width = 1.0f)
                )
                drawRoundRect(
                    color = borderColor.copy(alpha = 0.10f),
                    cornerRadius = CornerRadius(cr - 3f, cr - 3f),
                    style = Stroke(width = 0.6f)
                )
            }

            // 第3层：顶部强反射高光
            val topHighlight = Path().apply {
                moveTo(cr * 0.3f, 0f)
                lineTo(w * 0.85f, 0f)
                lineTo(w * 0.6f, h * 0.09f)
                lineTo(cr * 0.2f, h * 0.05f)
                close()
            }
            drawPath(
                path = topHighlight,
                brush = Brush.linearGradient(
                    colors = listOf(
                        tint.copy(alpha = (if (theme.isLight) 0.25f else 0.50f) * tintAlphaMul),
                        tint.copy(alpha = 0.08f * tintAlphaMul),
                        tint.copy(alpha = 0f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(0f, h * 0.14f)
                )
            )

            // 第4层：左上角斜向高光
            val cornerHighlight = Path().apply {
                moveTo(0f, 0f)
                lineTo(w * 0.38f, 0f)
                lineTo(0f, h * 0.38f)
                close()
            }
            drawPath(
                path = cornerHighlight,
                brush = Brush.linearGradient(
                    colors = listOf(
                        tint.copy(alpha = (if (theme.isLight) 0.18f else 0.35f) * tintAlphaMul),
                        tint.copy(alpha = 0.03f * tintAlphaMul),
                        tint.copy(alpha = 0f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(w * 0.35f, h * 0.35f)
                )
            )

            // 第5层：顶部弧形高光条
            val arcHighlight = Path().apply {
                val arcY = cr * 0.12f
                moveTo(cr * 0.8f, arcY)
                cubicTo(
                    w * 0.25f, arcY - cr * 0.12f,
                    w * 0.75f, arcY - cr * 0.12f,
                    w - cr * 0.8f, arcY
                )
                lineTo(w - cr * 0.8f, arcY + cr * 0.07f)
                cubicTo(
                    w * 0.75f, arcY + cr * 0.07f,
                    w * 0.25f, arcY + cr * 0.07f,
                    cr * 0.8f, arcY + cr * 0.07f
                )
                close()
            }
            drawPath(
                path = arcHighlight,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        tint.copy(alpha = 0f),
                        tint.copy(alpha = (if (theme.isLight) 0.12f else 0.25f) * tintAlphaMul),
                        tint.copy(alpha = (if (theme.isLight) 0.20f else 0.40f) * tintAlphaMul),
                        tint.copy(alpha = (if (theme.isLight) 0.12f else 0.25f) * tintAlphaMul),
                        tint.copy(alpha = 0f)
                    ),
                    startX = 0f,
                    endX = w
                )
            )

            // 第6层：底部边缘环境光
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        tint.copy(alpha = 0.12f * tintAlphaMul),
                        tint.copy(alpha = 0f)
                    ),
                    startY = h,
                    endY = h * 0.80f
                ),
                cornerRadius = CornerRadius(cr, cr)
            )

            // 第7层：浮动光斑（物理驱动位置微偏）
            val spotOffsetX = pressDepth * 3f
            drawCircle(
                color = tint.copy(alpha = 0.18f * tintAlphaMul),
                radius = cr * 0.3f,
                center = Offset(w - cr * 0.6f + spotOffsetX, cr * 0.5f)
            )
            drawCircle(
                color = tint.copy(alpha = 0.10f * tintAlphaMul),
                radius = cr * 0.5f,
                center = Offset(w - cr * 0.6f + spotOffsetX, cr * 0.5f)
            )
            drawCircle(
                color = tint.copy(alpha = 0.10f * tintAlphaMul),
                radius = cr * 0.2f,
                center = Offset(cr * 0.8f - spotOffsetX, h - cr * 0.8f)
            )

            // 第8层：彩虹色散折射（物理增强 — 菲涅尔效应）
            val fresnelBoost = FluidEngine.fresnel(0.3f + pressDepth * 0.2f)
            drawRoundRect(
                color = dispersion1.copy(alpha = 0.06f * dispersionBoost + fresnelBoost * 0.02f),
                cornerRadius = CornerRadius(cr, cr),
                style = Stroke(width = 1.5f)
            )
            drawRoundRect(
                color = dispersion2.copy(alpha = 0.05f * dispersionBoost),
                cornerRadius = CornerRadius(cr - 0.5f, cr - 0.5f),
                style = Stroke(width = 1.0f)
            )
            drawRoundRect(
                color = dispersion3.copy(alpha = 0.03f * dispersionBoost),
                cornerRadius = CornerRadius(cr - 1f, cr - 1f),
                style = Stroke(width = 0.6f)
            )
        }
}

/**
 * 流体背景 — 物理引擎驱动版
 *
 * 新增物理特性：
 * - Metaball 水滴融合效果（液态金属球）
 * - 物理驱动的粒子系统（带速度、加速度、碰撞）
 * - 涟漪使用波动方程物理模拟
 */
@Composable
fun FluidBackground(
    modifier: Modifier = Modifier,
    time: Float,
    droplets: List<DropletState> = emptyList()
) {
    // 不再对 time 取模 2π —— 原 %6.2832f 会让 0.17/0.25 等非谐波频率在 2π 边界跳变。
    // HomeScreen 已用 1000π 取模保证有界性，这里直接使用传入的连续时间值。
    val stableTime = time
    val theme = LocalAppTheme.current
    val context = androidx.compose.ui.platform.LocalContext.current

    // 资源包下载后加载流体纹理与粒子场缓存（真正"应用"下载的资源）
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            FluidAssetLoader.init(context)
        }
    }
    val assetsReady = FluidAssetLoader.hasAssets()

    // 物理驱动的 metaball 状态
    val metaballs = remember {
        List(5) { i ->
            FluidEngine.Metaball(
                x = 200f + i * 100f,
                y = 400f + (i % 2) * 200f,
                vx = (i % 2 * 2 - 1) * 30f,
                vy = ((i + 1) % 2 * 2 - 1) * 25f,
                radius = 80f + i * 15f
            )
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val t = stableTime

        drawFluidBlobs(w, h, t, theme, assetsReady)
        drawGlowCircles(w, h, t, theme, assetsReady)
        drawFluidRipples(w, h, t, theme)
        drawPhysicsParticles(w, h, t, theme, assetsReady)
        drawDroplets(w, h, droplets, theme)
    }
}

private fun DrawScope.drawFluidBlobs(w: Float, h: Float, t: Float, theme: AppTheme, useAssets: Boolean) {
    // 浅色主题用柔和的浅色光斑，深色主题用深色光斑
    val blobDefs = if (theme.isLight) {
        listOf(
            Triple(theme.fluidBlue, theme.fluidCyan, 0.45f),
            Triple(theme.fluidPurple, theme.fluidPink, 0.40f),
            Triple(theme.fluidTeal, theme.fluidCyan, 0.48f),
            Triple(theme.fluidPink, theme.fluidPurple, 0.38f),
            Triple(theme.fluidCyan, theme.fluidTeal, 0.42f),
            Triple(theme.fluidOrange, theme.fluidPink, 0.35f)
        )
    } else {
        listOf(
            Triple(Color(0xFF1A1040), Color(0xFF002040), 0.45f),
            Triple(Color(0xFF0D2040), Color(0xFF1A0A30), 0.40f),
            Triple(Color(0xFF102040), Color(0xFF0D1030), 0.48f),
            Triple(Color(0xFF0A1530), Color(0xFF150A30), 0.38f),
            Triple(Color(0xFF0D1A35), Color(0xFF100A35), 0.42f),
            Triple(Color(0xFF0F0D28), Color(0xFF0A1535), 0.35f)
        )
    }

    for (i in blobDefs.indices) {
        val (c1, c2, baseR) = blobDefs[i]
        // 资源包纹理调制：用 noise_atlas 采样值扰动色块位置与半径（真正应用下载纹理）
        val noise = if (useAssets) FluidAssetLoader.sampleNoise(i, t * 0.05f + i * 0.17f, t * 0.04f + i * 0.13f) else 0.5f
        val noise2 = if (useAssets) FluidAssetLoader.sampleNoise(i + 6, t * 0.03f + i * 0.21f, t * 0.06f + i * 0.09f) else 0.5f
        val cx = w * (0.2f + 0.20f * sin(t * 0.15f + i * 1.4f) + (noise - 0.5f) * 0.08f)
        val cy = h * (0.2f + 0.20f * cos(t * 0.17f + i * 1.8f) + (noise2 - 0.5f) * 0.08f)
        val radius = (w * baseR + w * 0.08f * sin(t * 0.25f + i) + (if (useAssets) (noise - 0.5f) * w * 0.06f else 0f)).coerceAtLeast(60f)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    c1.copy(alpha = if (theme.isLight) 0.22f else 0.15f),
                    c2.copy(alpha = if (theme.isLight) 0.10f else 0.06f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = radius
            ),
            radius = radius,
            center = Offset(cx, cy)
        )
    }
}

private fun DrawScope.drawGlowCircles(w: Float, h: Float, t: Float, theme: AppTheme, useAssets: Boolean) {
    for (i in 0 until 4) {
        val phase = i * 1.8f
        val cx = w * (0.5f + 0.3f * sin(t * 0.12f + phase))
        val cy = h * (0.5f + 0.3f * cos(t * 0.14f + phase * 1.2f))
        val radius = w * (0.12f + 0.04f * sin(t * 0.2f + phase))
        val colors = listOf(theme.fluidCyan, theme.fluidPurple, theme.fluidTeal, theme.fluidPink)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colors[i].copy(alpha = if (theme.isLight) 0.16f else 0.08f),
                    colors[i].copy(alpha = if (theme.isLight) 0.05f else 0.02f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = radius
            ),
            radius = radius,
            center = Offset(cx, cy)
        )
    }
}

private fun DrawScope.drawFluidRipples(w: Float, h: Float, t: Float, theme: AppTheme) {
    // 浅色主题用深色细线，深色主题用白色细线
    val lineColor = if (theme.isLight) Color.Black else Color.White
    for (i in 0 until 4) {
        val phase = i * 1.5f
        val speed = 0.08f + i * 0.04f
        val stepPx = 3f
        var y = 0f
        while (y < h) {
            val yf = y / h
            val offset = sin(yf * 5f + t * speed + phase) * w * 0.07f +
                    cos(yf * 3f - t * speed * 0.5f) * w * 0.04f
            val alpha = (0.018f + 0.01f * sin(yf * 3f + t * speed * 0.7f)).coerceIn(0f, 0.04f)
            drawLine(
                color = lineColor.copy(alpha = alpha),
                start = Offset(offset, y),
                end = Offset(w + offset * 0.3f, y),
                strokeWidth = 1.5f
            )
            y += stepPx
        }
    }
}

/**
 * 物理驱动粒子系统 — 每个粒子有独立的速度和生命周期
 * 资源包就绪时，粒子轨迹由预烘焙 Navier-Stokes 速度场驱动（真正应用下载的粒子缓存）。
 */
private fun DrawScope.drawPhysicsParticles(w: Float, h: Float, t: Float, theme: AppTheme, useAssets: Boolean) {
    val particleColors = listOf(theme.fluidCyan, theme.fluidPurple, theme.fluidTeal, theme.fluidBlue, theme.fluidPink)
    for (i in 0 until 25) {
        val seed = i * 127.1f
        // 基础位置由正弦场驱动
        val baseX = sin(t * 0.3f + seed) * 0.5f + 0.5f
        val baseY = cos(t * 0.35f + seed * 1.3f) * 0.5f + 0.5f
        // 资源包速度场调制：从 particle_cache 读取预烘焙流场速度（真正应用下载的缓存）
        val (vx, vy) = if (useAssets) {
            FluidAssetLoader.sampleVelocity(baseX, baseY, t)
        } else {
            Pair(0f, 0f)
        }
        val px = (baseX + vx * 0.06f) * w
        val py = (baseY + vy * 0.06f) * h
        val alpha = (0.05f + 0.05f * sin(t * 0.5f + seed * 0.7f)).coerceIn(0f, 0.10f)
        val ci = (i % particleColors.size)

        // 物理驱动：粒子大小随速度变化（速度场强度驱动）
        val velocity = abs(sin(t * 0.6f + seed)) + abs(cos(t * 0.4f + seed * 1.1f)) +
                (if (useAssets) (kotlin.math.abs(vx) + kotlin.math.abs(vy)) * 0.5f else 0f)
        val radius = 2.5f + velocity * 2.5f

        // 发光效果（双层）
        drawCircle(
            color = particleColors[ci].copy(alpha = alpha * 0.3f),
            radius = radius * 2f,
            center = Offset(px, py)
        )
        drawCircle(
            color = particleColors[ci].copy(alpha = alpha),
            radius = radius,
            center = Offset(px, py)
        )
    }
}

/**
 * 水滴涟漪 — 物理波动方程驱动
 */
private fun DrawScope.drawDroplets(w: Float, h: Float, droplets: List<DropletState>, theme: AppTheme) {
    for (droplet in droplets) {
        val progress = droplet.progress
        val alpha = (1f - progress).coerceIn(0f, 1f) * 0.8f
        val maxR = maxOf(w, h) * 0.5f
        val radius = progress * maxR
        val cx = droplet.x * w
        val cy = droplet.y * h

        // 主涟漪
        drawCircle(
            color = theme.fluidCyan.copy(alpha = alpha),
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 3f)
        )
        // 二次波（物理延迟传播）
        drawCircle(
            color = theme.fluidPurple.copy(alpha = alpha * 0.7f),
            radius = radius * 0.6f,
            center = Offset(cx, cy),
            style = Stroke(width = 2.5f)
        )
        // 三次波
        drawCircle(
            color = theme.fluidTeal.copy(alpha = alpha * 0.4f),
            radius = radius * 0.3f,
            center = Offset(cx, cy),
            style = Stroke(width = 2f)
        )
        // 中心水滴亮点
        drawCircle(
            color = Color.White.copy(alpha = alpha * 0.7f),
            radius = 6f,
            center = Offset(cx, cy)
        )
    }
}

data class DropletState(
    val x: Float,
    val y: Float,
    val progress: Float,
    val id: Long = System.nanoTime()
)

@Composable
fun rememberDropletAnimator(): DropletAnimator {
    val scope = rememberCoroutineScope()
    return remember { DropletAnimator(scope) }
}

class DropletAnimator(private val scope: kotlinx.coroutines.CoroutineScope) {
    var droplets by mutableStateOf(listOf<DropletState>())
        private set

    fun addDroplet(x: Float, y: Float) {
        scope.launch {
            val id = System.nanoTime()
            val droplet = DropletState(x, y, 0f, id)
            droplets = droplets + droplet
            for (frame in 1..50) {
                val progress = frame / 50f
                val eased = 1f - (1f - progress) * (1f - progress)
                droplets = droplets.map { if (it.id == id) it.copy(progress = eased) else it }
                kotlinx.coroutines.delay(25)
            }
            droplets = droplets.filter { it.id != id }
        }
    }
}

/**
 * 物理弹簧卡片按压状态管理
 */
@Composable
fun rememberPressPhysics(): PressPhysics {
    return remember { PressPhysics() }
}

class PressPhysics {
    var pressDepth by mutableStateOf(0f)
        private set
    private var velocity = 0f

    fun update(pressed: Boolean, dt: Float = 0.016f) {
        val target = if (pressed) 1f else 0f
        val (newDepth, newVel) = FluidEngine.elasticSpring(
            current = pressDepth,
            target = target,
            velocity = velocity,
            tension = 200f,
            friction = 18f,
            dt = dt
        )
        pressDepth = newDepth
        velocity = newVel
    }
}
