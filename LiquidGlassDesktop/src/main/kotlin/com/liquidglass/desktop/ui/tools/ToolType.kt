package com.liquidglass.desktop.ui.tools

/**
 * 桌面端工具类型
 *
 * 电脑端择需：保留适合桌面场景的工具
 * - icon: unicode 字符图标（避免引入图标库依赖）
 * - desc: 工具简短描述（显示在卡片上）
 * - color: 主题色（Long ARGB）
 */
enum class ToolType(val label: String, val icon: String, val desc: String, val color: Long) {
    Calculator("科学计算器", "🧮", "四则运算 · 括号 · 小数", 0xFF00E5A0),
    Countdown("倒计时", "⏱", "自定义秒数 · 开始/暂停/重置", 0xFF3366FF),
    Todo("待办清单", "✓", "任务管理 · 勾选完成 · 删除", 0xFFFF6B35),
    Note("记事本", "📝", "本地持久化 · 自动保存", 0xFF7B5CFC),
    Password("密码生成器", "🔐", "自定义长度 · 含符号 · 强随机", 0xFF00D4FF),
    Converter("单位换算", "📏", "长度/重量/温度 · 多单位", 0xFFFF3B8B),
    Calendar("日历", "📅", "月历视图 · 今日高亮", 0xFF7B5CFC),
    Health("健康计算", "❤", "BMI · 基础代谢 · 体重评估", 0xFF00D4FF),
    Drawing("涂鸦画板", "🎨", "自由绘画 · 颜色/粗细 · 清空", 0xFFFF6B35),
    WhiteNoise("白噪音", "🎵", "白/粉/棕噪音 · 实时生成", 0xFF00E5A0),
}
