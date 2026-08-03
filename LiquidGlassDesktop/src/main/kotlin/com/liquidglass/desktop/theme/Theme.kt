package com.liquidglass.desktop.theme

import androidx.compose.ui.graphics.Color

/**
 * 午夜深空主题 - 与 Android 端对齐
 * 深色背景 + 玻璃白色叠加 + 多彩流体光斑
 */
object LiquidGlassTheme {

    // ---- 底层颜色 ----
    /** 主背景 深空黑紫 #08080F */
    val backgroundColor: Color = Color(0xFF08080F)

    /** 表面层（卡片底） */
    val surfaceColor: Color = Color(0xFF11111C)

    /** 表面层加深 */
    val surfaceVariant: Color = Color(0xFF1A1A2A)

    /** 主文字色 */
    val onSurfaceColor: Color = Color(0xFFE6E6F0)

    /** 次要文字色 */
    val onSurfaceMuted: Color = Color(0xFF9A9AB0)

    /** 高对比文字 */
    val onSurfaceBright: Color = Color(0xFFFFFFFF)

    // ---- 流体光斑颜色 ----
    /** 青 */
    val cyan: Color = Color(0xFF00D4FF)

    /** 紫 */
    val purple: Color = Color(0xFF7B5CFC)

    /** 粉 */
    val pink: Color = Color(0xFFFF3B8B)

    /** 蓝 */
    val blue: Color = Color(0xFF3366FF)

    /** 绿 */
    val green: Color = Color(0xFF00E5A0)

    /** 橙 */
    val orange: Color = Color(0xFFFF6B35)

    /** 流体光斑色板（顺序用于动画 metaball） */
    val fluidColors: List<Color> = listOf(cyan, purple, pink, blue, green, orange)

    // ---- 玻璃常量 ----
    /** 玻璃表面主透明度 */
    const val glassAlpha: Float = 0.08f

    /** 玻璃表面高亮透明度 */
    const val glassAlphaBright: Float = 0.14f

    /** 玻璃边框色 */
    val glassBorder: Color = Color.White

    /** 玻璃顶部高光 */
    val glassHighlight: Color = Color.White.copy(alpha = 0.45f)

    /** 玻璃底部阴影 */
    val glassShadow: Color = Color.Black.copy(alpha = 0.35f)

    // ---- 公告优先级颜色 ----
    val announcementHigh: Color = Color(0xFFFF3B6B)
    val announcementMedium: Color = Color(0xFFFF8C42)
    val announcementLow: Color = Color(0xFF00D4FF)

    // ---- 强调色 ----
    val accentPrimary: Color = purple
    val accentSecondary: Color = cyan
}
