package com.liquidglass.desktop.ui.tools

/**
 * 桌面端工具类型（电脑端择需：保留适合桌面场景的工具，传感器类如手电筒/指南针不在桌面端提供）
 */
enum class ToolType(val label: String, val color: Long) {
    Calculator("科学计算器", 0xFF00E5A0),
    Countdown("倒计时", 0xFF3366FF),
    Todo("待办清单", 0xFFFF6B35),
    Note("记事本", 0xFF7B5CFC),
    Password("密码生成器", 0xFF00D4FF),
    Converter("单位换算", 0xFFFF3B8B),
    Calendar("日历", 0xFF7B5CFC),
    Health("健康计算", 0xFF00D4FF),
    Drawing("涂鸦画板", 0xFFFF6B35),
    WhiteNoise("白噪音", 0xFF00E5A0),
}
