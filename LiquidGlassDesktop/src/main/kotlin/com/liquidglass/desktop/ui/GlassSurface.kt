package com.liquidglass.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.liquidglass.desktop.theme.LiquidGlassTheme

/**
 * 液态玻璃表面
 *
 * 设计说明：
 * 原始设计采用 KMPLiquidGlass 库（io.github.kashif-mehmood-km:backdrop）的
 *   rememberLayerBackdrop() -> layerBackdrop() -> drawBackdrop() + effects.lens() + effects.blur()
 * 实现真实玻璃折射与高斯模糊。
 * 由于该库 API 在多平台下存在不确定性，此处采用任务允许的 Compose Desktop 原生回退方案：
 *   drawBehind 半透明白色叠加 + 顶部高光渐变边框 + 底部阴影，模拟液态玻璃质感。
 * 若后续确认库 API，可将本文件替换为如下调用：
 *   val scope = rememberLayerBackdrop()
 *   Box(modifier = Modifier.layerBackdrop(scope)) {
 *       // 内容
 *       drawBackdrop(scope) { lens(); blur(radiusX = 16.dp, radiusY = 16.dp) }
 *   }
 */

/**
 * 玻璃表面修饰符扩展
 */
fun Modifier.glassSurface(
    cornerRadius: Dp = 20.dp,
    alpha: Float = LiquidGlassTheme.glassAlpha,
    withBorder: Boolean = true
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .background(Color.White.copy(alpha = alpha))
    .then(
        if (withBorder) Modifier.border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    LiquidGlassTheme.glassHighlight,
                    Color.White.copy(alpha = 0.05f)
                )
            ),
            shape = RoundedCornerShape(cornerRadius)
        ) else Modifier
    )

/**
 * 玻璃卡片 Composable
 * 内置半透明叠加 + 顶部高光 + 径向光晕
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    alpha: Float = LiquidGlassTheme.glassAlpha,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Color.White.copy(alpha = alpha))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        LiquidGlassTheme.glassHighlight,
                        Color.White.copy(alpha = 0.05f)
                    )
                ),
                shape = RoundedCornerShape(cornerRadius)
            )
    ) {
        // 径向光晕（模拟玻璃内部折射），此处处于 BoxScope，可直接使用 matchParentSize
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.10f),
                            Color.Transparent
                        )
                    )
                )
        )
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}
