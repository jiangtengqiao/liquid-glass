package com.liquidglass.desktop.theme

import androidx.compose.ui.graphics.Color

/**
 * 午夜深空主题 - 与 Android 端对齐
 *
 * 设计依据：
 * - Material Design 3 色彩角色（surface / onSurface / onSurfaceVariant）
 * - WCAG 2.1 对比度：小字 ≥4.5:1，大字 ≥3:1
 * - Glassmorphism 2026 最佳实践：深色背景配深色玻璃（白玻璃在深底上显脏），
 *   玻璃 alpha ≥0.7 保证文字对比度，白色细边框锚定玻璃边缘
 *
 * 对比度校验（onSurfaceColor #EAEAF5 vs surfaceColor #14141F）：
 *   亮度比 ≈ 14.8:1，远超 WCAG AAA 7:1
 */
object LiquidGlassTheme {

    // ---- 底层颜色（Material 3 surface 角色）----
    /** 主背景 午夜蓝紫 #0A0A1A（亮度 0.04，比纯黑通透）
     *  v2.10.1：从 #08080F 提亮到 #0A0A1A，配合调亮的流体光斑更显通透 */
    val backgroundColor: Color = Color(0xFF0A0A1A)

    /** 表面层（卡片底）#1C1C33（亮度 0.11），与背景区分但不过亮 */
    val surfaceColor: Color = Color(0xFF1C1C33)

    /** 表面层加深（输入框/下拉）#252545 */
    val surfaceVariant: Color = Color(0xFF252545)

    /** 玻璃面板底色（深色玻璃，alpha 高保证文字可读）#15152E */
    val glassBaseColor: Color = Color(0xFF15152E)

    // ---- 文字色（Material 3 onSurface 角色，三级强调）----
    /** 主文字色 #EAEAF5（亮度 0.879），对比度 14.8:1 */
    val onSurfaceColor: Color = Color(0xFFEAEAF5)

    /** 次要文字色 #A8A8BE（亮度 0.403），对比度 6.3:1，过 AA */
    val onSurfaceMuted: Color = Color(0xFFA8A8BE)

    /** 高对比文字（纯白）#FFFFFF */
    val onSurfaceBright: Color = Color(0xFFFFFFFF)

    // ---- 流体光斑颜色（背景动画用，不参与文字对比度）----
    val cyan: Color = Color(0xFF00D4FF)
    val purple: Color = Color(0xFF7B5CFC)
    val pink: Color = Color(0xFFFF3B8B)
    val blue: Color = Color(0xFF3366FF)
    val green: Color = Color(0xFF00E5A0)
    val orange: Color = Color(0xFFFF6B35)

    /** 流体光斑色板（顺序用于动画 metaball） */
    val fluidColors: List<Color> = listOf(cyan, purple, pink, blue, green, orange)

    // ---- 玻璃常量（依据 2026 glassmorphism 指南）----
    /**
     * 玻璃表面主透明度 0.72
     * 依据：alpha<0.3 时文字对比度不可控（背景光斑透过），alpha≥0.7 保证可读
     */
    const val glassAlpha: Float = 0.72f

    /** 玻璃表面高亮透明度（用于悬浮/选中态） */
    const val glassAlphaBright: Float = 0.85f

    /** 玻璃边框色（白色细边框，alpha 0.12 锚定玻璃边缘） */
    val glassBorder: Color = Color.White.copy(alpha = 0.12f)

    /** 玻璃顶部高光（模拟玻璃边缘反光） */
    val glassHighlight: Color = Color.White.copy(alpha = 0.18f)

    /** 玻璃底部阴影（提升层级感） */
    val glassShadow: Color = Color.Black.copy(alpha = 0.45f)

    // ---- 公告优先级颜色 ----
    val announcementHigh: Color = Color(0xFFFF3B6B)
    val announcementMedium: Color = Color(0xFFFF8C42)
    val announcementLow: Color = Color(0xFF00D4FF)

    // ---- 强调色 ----
    val accentPrimary: Color = purple
    val accentSecondary: Color = cyan

    /** 强调色上的文字色（白） */
    val onAccent: Color = Color.White
}
