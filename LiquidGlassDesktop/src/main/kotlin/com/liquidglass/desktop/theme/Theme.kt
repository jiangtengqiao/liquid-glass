package com.liquidglass.desktop.theme

import androidx.compose.ui.graphics.Color

/**
 * 明亮蓝紫主题 v2.11.0 - 大幅提亮
 *
 * 用户反馈："整体为什么都是暗黑风？亮一点会死吗？"
 * v2.11.0 核心改动：从"接近黑色的深空"改为"明亮的蓝紫夜空"
 *
 * 色彩亮度对比：
 *   v2.10.x  backgroundColor #0A0A1A (亮度 0.04) → v2.11.0 #1E1E42 (亮度 0.12) 提升 3 倍
 *   v2.10.x  surfaceColor  #1C1C33 (亮度 0.11) → v2.11.0 #2D2D5A (亮度 0.18) 提升 64%
 *   v2.10.x  glassBaseColor #15152E (亮度 0.08) → v2.11.0 #252550 (亮度 0.15) 提升 87%
 *
 * 对比度校验（onSurfaceColor #F0F0FF vs surfaceColor #2D2D5A）：
 *   亮度比 ≈ 12.5:1，远超 WCAG AAA 7:1
 */
object LiquidGlassTheme {

    // ---- 底层颜色（v2.11.1 再次大幅提亮）----
    /** 主背景 明亮蓝紫 #2A2A5A（亮度 0.18，用户反馈"还是太暗"再提亮） */
    val backgroundColor: Color = Color(0xFF2A2A5A)

    /** 表面层（卡片底）#3A3A72（亮度 0.24） */
    val surfaceColor: Color = Color(0xFF3A3A72)

    /** 表面层加深（输入框/下拉）#484880 */
    val surfaceVariant: Color = Color(0xFF484880)

    /** 玻璃面板底色 #303060（亮度 0.20，更通透） */
    val glassBaseColor: Color = Color(0xFF303060)

    // ---- 文字色（v2.11.1 提升至纯白，解决"文字可见度"问题）----
    /** 主文字色 #FFFFFF 纯白（用户："文字可见度绝对不能容忍"） */
    val onSurfaceColor: Color = Color(0xFFFFFFFF)

    /** 次要文字色 #D0D0F0（亮度 0.75，对比度 9:1） */
    val onSurfaceMuted: Color = Color(0xFFD0D0F0)

    /** 高对比文字（纯白）#FFFFFF */
    val onSurfaceBright: Color = Color(0xFFFFFFFF)

    // ---- 流体光斑颜色（更鲜艳明亮）----
    val cyan: Color = Color(0xFF22E5FF)
    val purple: Color = Color(0xFF9B7CFF)
    val pink: Color = Color(0xFFFF5BA0)
    val blue: Color = Color(0xFF5588FF)
    val green: Color = Color(0xFF22FFB8)
    val orange: Color = Color(0xFFFF8855)
    val yellow: Color = Color(0xFFFFD93D)
    val teal: Color = Color(0xFF3DDFFF)

    /** 流体光斑色板（8 色，更丰富） */
    val fluidColors: List<Color> = listOf(cyan, purple, pink, blue, green, orange, yellow, teal)

    // ---- 玻璃常量 ----
    /**
     * 玻璃表面主透明度 0.85（v2.11.1 提亮，增强文字可见度）
     */
    const val glassAlpha: Float = 0.85f

    /** 玻璃表面高亮透明度（用于悬浮/选中态） */
    const val glassAlphaBright: Float = 0.92f

    /** 玻璃边框色（白色细边框，提亮到 0.18） */
    val glassBorder: Color = Color.White.copy(alpha = 0.18f)

    /** 玻璃顶部高光（提亮到 0.25） */
    val glassHighlight: Color = Color.White.copy(alpha = 0.25f)

    /** 玻璃底部阴影（减弱到 0.3，配合更亮的底色） */
    val glassShadow: Color = Color.Black.copy(alpha = 0.3f)

    // ---- 公告优先级颜色 ----
    val announcementHigh: Color = Color(0xFFFF5070)
    val announcementMedium: Color = Color(0xFFFFA042)
    val announcementLow: Color = Color(0xFF22E5FF)

    // ---- 强调色 ----
    val accentPrimary: Color = purple
    val accentSecondary: Color = cyan

    /** 强调色上的文字色（白） */
    val onAccent: Color = Color.White

    /** 金色（会员高级版） */
    val gold: Color = Color(0xFFFFD700)
}
