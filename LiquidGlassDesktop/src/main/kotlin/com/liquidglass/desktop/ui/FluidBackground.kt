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
 * 流体动画背景
 *
 * Canvas 绘制 5 个 metaball 光斑，正弦运动，
 * 深色背景之上叠加流动彩色光晕，营造液态玻璃氛围。
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
    // 深色底
    drawRect(color = LiquidGlassTheme.backgroundColor)

    val colors = LiquidGlassTheme.fluidColors
    val w = size.width
    val h = size.height
    val baseRadius = minOf(w, h) * 0.38f

    // 5 个流动光斑，正弦运动
    for (i in 0 until 5) {
        val phase = i * 1.25f
        // 中心点正弦摆动
        val cx = w * (0.5f + 0.34f * sin(time * 0.6f + phase))
        val cy = h * (0.5f + 0.34f * sin(time * 0.45f + phase * 1.3f))
        // 半径轻微脉动
        val r = baseRadius * (0.8f + 0.2f * sin(time * 0.8f + phase))
        val color = colors[i % colors.size]

        // 径向渐变光斑
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = 0.55f),
                    color.copy(alpha = 0.15f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = r
            ),
            center = Offset(cx, cy),
            radius = r
        )
    }

    // 顶部细微暗角，增强深度感
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Black.copy(alpha = 0.35f),
                Color.Transparent,
                Color.Black.copy(alpha = 0.25f)
            )
        )
    )
}
