package com.liquidglass.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.liquidglass.desktop.theme.LiquidGlassTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 流体动画背景 v3 - 调亮色系 + 鼠标物理交互 + 无缝循环
 *
 * 设计依据（iOS 26 Liquid Glass + 2026 glassmorphism 指南）：
 * 1. 调亮色系：底色从 #08080F 提升至 #14142B（午夜蓝紫，更通透不死黑）
 * 2. 鼠标交互：鼠标作为「引力源」吸引最近的光斑，光斑被吸引后绕鼠标旋转
 *    形成「液态玻璃实时物理引擎交互流动」效果
 * 3. 无缝循环：使用周期性 sin 函数，动画 time 自然连续（在 Main.kt 已用 Reverse）
 * 4. 多层光斑：8 个大光斑柔光层 + 6 个小高光层 + 鼠标焦点光晕
 * 5. 顶部高光 + 四周暗角增强深度聚焦
 *
 * @param time 动画驱动时间（弧度相位）
 * @param mousePos 鼠标位置归一化坐标 (0~1, 0~1)
 */
@Composable
fun FluidBackground(
    time: Float,
    mousePos: Offset = Offset(0.5f, 0.5f),
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        drawFluid(time, mousePos)
    }
}

private fun DrawScope.drawFluid(time: Float, mousePos: Offset) {
    val w = size.width
    val h = size.height

    // ===== 第 0 层：明亮蓝紫底色（v2.11.1 再次提亮）=====
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF454580),      // 中心更亮
                Color(0xFF353568),      // 中段
                Color(0xFF2A2A5A)       // 边缘（与 Theme.backgroundColor 一致）
            ),
            center = Offset(w * 0.5f, h * 0.45f),
            radius = maxOf(w, h) * 0.85f
        )
    )

    val colors = LiquidGlassTheme.fluidColors
    val baseRadius = minOf(w, h) * 0.45f

    // 鼠标物理源（屏幕坐标）
    val mx = w * mousePos.x
    val my = h * mousePos.y

    // ===== 第 1 层：8 个大光斑（柔光层 + 鼠标引力）=====
    // 鼠标作为引力源：每个光斑都被鼠标吸引，距离越近吸引力越大
    // 形成「液态玻璃被鼠标牵扯流动」的物理感
    for (i in 0 until 8) {
        val phase = i * 0.785f  // 0~2π 均匀分布

        // 基础轨迹（流体自主运动）
        val baseX = w * (0.5f + 0.36f * sin(time + phase))
        val baseY = h * (0.5f + 0.36f * sin(time * 0.73f + phase * 1.3f))

        // 鼠标引力：距离鼠标越近，光斑被拉得越靠近鼠标
        val dx = mx - baseX
        val dy = my - baseY
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        val maxDist = maxOf(w, h) * 0.6f
        // 引力强度：距离反比，最近时光斑位移可达 35%
        val attractStrength = (1f - (dist / maxDist).coerceIn(0f, 1f)) * 0.35f
        // 加入绕鼠标的旋转分量（避免全部光斑都堆叠在鼠标处）
        val swirl = sin(time * 1.5f + phase) * 0.15f
        val cx = baseX + dx * attractStrength + cos(time * 1.2f + phase) * swirl * w * 0.1f
        val cy = baseY + dy * attractStrength + sin(time * 1.2f + phase) * swirl * h * 0.1f

        // 半径随距离鼠标变化：近鼠标时光斑略膨胀（被点亮感）
        val r = baseRadius * (0.85f + 0.25f * sin(time * 0.6f + phase) +
                attractStrength * 0.15f)

        val color = colors[i % colors.size]
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = 0.55f),
                    color.copy(alpha = 0.18f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = r
            ),
            center = Offset(cx, cy),
            radius = r
        )
    }

    // ===== 第 2 层：6 个小高光斑（折射高光，alpha 高，色彩饱和）=====
    for (i in 0 until 6) {
        val phase = i * 1.05f + 0.5f
        val baseX = w * (0.5f + 0.3f * sin(time * 0.85f + phase))
        val baseY = h * (0.5f + 0.3f * cos(time * 0.65f + phase * 0.7f))

        // 鼠标对高光斑的吸引力更强（高光更灵活）
        val dx = mx - baseX
        val dy = my - baseY
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        val maxDist = maxOf(w, h) * 0.5f
        val attractStrength = (1f - (dist / maxDist).coerceIn(0f, 1f)) * 0.45f
        val cx = baseX + dx * attractStrength
        val cy = baseY + dy * attractStrength

        val r = baseRadius * 0.32f * (0.85f + 0.18f * sin(time * 1.1f + phase) +
                attractStrength * 0.2f)
        val color = colors[(i + 2) % colors.size]

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = 0.62f),
                    color.copy(alpha = 0.22f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = r
            ),
            center = Offset(cx, cy),
            radius = r
        )
    }

    // ===== 第 3 层：鼠标焦点光晕（强化交互感）=====
    // 鼠标处一个柔和的光晕，色彩随时间在 fluidColors 间循环
    val focusColor = colors[((time / (2f * PI.toFloat())).toInt() % colors.size).coerceIn(0, colors.size - 1)]
    val focusR = baseRadius * 0.5f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                focusColor.copy(alpha = 0.18f),
                focusColor.copy(alpha = 0.06f),
                Color.Transparent
            ),
            center = Offset(mx, my),
            radius = focusR
        ),
        center = Offset(mx, my),
        radius = focusR
    )

    // ===== 第 4 层：细微网格纹理（增强玻璃质感，极淡）=====
    val gridSpacing = 56f
    val gridColor = Color.White.copy(alpha = 0.018f)
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

    // ===== 第 5 层：顶部高光（模拟光源从上方照射，加亮）=====
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.06f),
                Color.Transparent,
                Color.Transparent
            ),
            startY = 0f,
            endY = h * 0.3f
        )
    )

    // ===== 第 6 层：四周暗角（保留聚焦感，但减弱避免太暗）=====
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Transparent,
                Color.Transparent,
                Color.Black.copy(alpha = 0.22f)   // 从 0.35 减到 0.22，避免太暗
            ),
            center = Offset(w * 0.5f, h * 0.5f),
            radius = maxOf(w, h) * 0.75f
        )
    )
}
