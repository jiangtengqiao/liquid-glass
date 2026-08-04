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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.liquidglass.desktop.theme.LiquidGlassTheme

/**
 * 液态玻璃表面
 *
 * 设计依据（2026 glassmorphism 权威指南）：
 * 1. 深色背景配深色玻璃（白玻璃在深底上显脏）→ 用 glassBaseColor #0F0F1E
 * 2. alpha ≥ 0.7 保证文字对比度（WCAG 4.5:1）
 * 3. 白色细边框（alpha 0.12）锚定玻璃边缘
 * 4. blur 模拟靠径向光晕 + 顶部高光渐变
 * 5. 阴影提升层级感
 *
 * 关键修复：原 alpha=0.08 导致文字与背景流体光斑重叠不可读
 */

/**
 * 玻璃表面修饰符扩展
 */
fun Modifier.glassSurface(
    cornerRadius: Dp = 16.dp,
    alpha: Float = LiquidGlassTheme.glassAlpha,
    withBorder: Boolean = true
): Modifier = this
    .shadow(
        elevation = 8.dp,
        shape = RoundedCornerShape(cornerRadius),
        ambientColor = LiquidGlassTheme.glassShadow,
        spotColor = LiquidGlassTheme.glassShadow
    )
    .clip(RoundedCornerShape(cornerRadius))
    .background(LiquidGlassTheme.glassBaseColor.copy(alpha = alpha))
    .then(
        if (withBorder) Modifier.border(
            width = 1.dp,
            color = LiquidGlassTheme.glassBorder,
            shape = RoundedCornerShape(cornerRadius)
        ) else Modifier
    )

/**
 * 玻璃卡片 Composable
 *
 * 深色玻璃底 + 顶部高光渐变 + 径向光晕 + 阴影
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    alpha: Float = LiquidGlassTheme.glassAlpha,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(cornerRadius),
                ambientColor = LiquidGlassTheme.glassShadow,
                spotColor = LiquidGlassTheme.glassShadow
            )
            .clip(RoundedCornerShape(cornerRadius))
            .background(LiquidGlassTheme.glassBaseColor.copy(alpha = alpha))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        LiquidGlassTheme.glassHighlight,
                        LiquidGlassTheme.glassBorder
                    )
                ),
                shape = RoundedCornerShape(cornerRadius)
            )
    ) {
        // 径向光晕（模拟玻璃内部折射光）
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.04f),
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
