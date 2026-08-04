package com.liquidglass.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.liquidglass.desktop.theme.LiquidGlassTheme
import kotlin.math.sin

/**
 * 流体动画背景 v2 - 增强色彩折射与层次感
 *
 * 设计依据（2026 glassmorphism 权威指南 + iOS 26 Liquid Glass）：
 * 1. 多层光斑叠加（7 个 metaball）形成色彩流动折射
 * 2. 大半径柔光 + 小半径高光双层结构
 * 3. 顶部/底部渐变暗角增强深度
 * 4. 细微网格纹理增强玻璃质感
 * 5. 缓慢运动避免眩晕，10s 一个周期
 *
 * @param time 动画驱动时间，单位为弧度相位
 */
@Composable
fun FluidBackground(
    time: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        drawFluid(time)
    }
}

private fun DrawScope.drawFluid(time: Float) {
    val w = size.width
    val h = size.height

    // ===== 第 0 层：深空底色（径向渐变，中心略亮）=====
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF12121F),
                LiquidGlassTheme.backgroundColor,
                Color(0xFF05050A)
            ),
            center = Offset(w * 0.5f, h * 0.4f),
            radius = maxOf(w, h) * 0.8f
        )
    )

    val colors = LiquidGlassTheme.fluidColors
    val baseRadius = minOf(w, h) * 0.42f

    // ===== 第 1 层：7 个大光斑（柔光层，alpha 低，营造氛围）=====
    for (i in 0 until 7) {
        val phase = i * 0.9f
        val cx = w * (0.5f + 0.38f * sin(time * 0.5f + phase))
        val cy = h * (0.5f + 0.38f * sin(time * 0.37f + phase * 1.3f))
        val r = baseRadius * (0.9f + 0.25f * sin(time * 0.7f + phase))
        val color = colors[i % colors.size]

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = 0.42f),
                    color.copy(alpha = 0.12f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = r
            ),
            center = Offset(cx, cy),
            radius = r
        )
    }

    // ===== 第 2 层：5 个小高光斑（折射高光，alpha 高，色彩饱和）=====
    for (i in 0 until 5) {
        val phase = i * 1.6f + 0.5f
        val cx = w * (0.5f + 0.3f * sin(time * 0.8f + phase))
        val cy = h * (0.5f + 0.3f * sin(time * 0.6f + phase * 0.7f))
        val r = baseRadius * 0.35f * (0.85f + 0.15f * sin(time * 1.1f + phase))
        val color = colors[(i + 2) % colors.size]

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = 0.55f),
                    color.copy(alpha = 0.2f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = r
            ),
            center = Offset(cx, cy),
            radius = r
        )
    }

    // ===== 第 3 层：细微网格纹理（增强玻璃质感，极淡）=====
    val gridSpacing = 48f
    val gridColor = Color.White.copy(alpha = 0.015f)
    var x = 0f
    while (x < w) {
        drawLine(
            color = gridColor,
            start = Offset(x, 0f),
            end = Offset(x, h),
            strokeWidth = 1f
        )
        x += gridSpacing
    }
    var y = 0f
    while (y < h) {
        drawLine(
            color = gridColor,
            start = Offset(0f, y),
            end = Offset(w, y),
            strokeWidth = 1f
        )
        y += gridSpacing
    }

    // ===== 第 4 层：顶部高光渐变（模拟光源从上方照射）=====
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.04f),
                Color.Transparent,
                Color.Transparent
            ),
            startY = 0f,
            endY = h * 0.3f
        )
    )

    // ===== 第 5 层：四周暗角（vignette，增强深度聚焦）=====
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Transparent,
                Color.Transparent,
                Color.Black.copy(alpha = 0.35f)
            ),
            center = Offset(w * 0.5f, h * 0.5f),
            radius = maxOf(w, h) * 0.7f
        )
    )

    // ===== 第 6 层：底部加深（让悬浮内容更突出）=====
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                Color.Transparent,
                Color.Black.copy(alpha = 0.3f)
            ),
            startY = h * 0.6f,
            endY = h
        )
    )
}
