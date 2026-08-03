import SwiftUI

// ─────────────────────────────────────────────────────────────────
// 色彩系统 —— 对应 Android 端 ui/theme/Color.kt 与 ThemeManager.kt
// 所有色值与 Android 端完全一致，确保跨端视觉统一。
// ─────────────────────────────────────────────────────────────────

// MARK: - 16 进制颜色扩展
extension Color {
    /// 通过 16 进制整数值创建颜色（如 0xFF00D4FF），与 Android 的 Color(0xFF......) 一一对应。
    init(hex: UInt32, alpha: Double = 1.0) {
        let r = Double((hex & 0xFF0000) >> 16) / 255.0
        let g = Double((hex & 0x00FF00) >> 8) / 255.0
        let b = Double(hex & 0x0000FF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }

    /// 调整透明度（链式调用，避免反复写 Color.xxx.opacity(...)）。
    func alpha(_ a: Double) -> Color {
        self.opacity(a)
    }
}

// MARK: - 极简深色背景
let BgDark  = Color(hex: 0x08080F)
let BgDark2 = Color(hex: 0x0D0D1A)

// MARK: - 液态玻璃透明度常量（6 级，对应 Android GlassClear~GlassBright）
// 用白色叠加不同透明度，构成玻璃层级。
let GlassClear     = Color.white.opacity(0.06)  // 基底玻璃
let GlassLight     = Color.white.opacity(0.09)  // 轻量化
let GlassMedium    = Color.white.opacity(0.13)  // 中等
let GlassBorder    = Color.white.opacity(0.16)  // 边框
let GlassHighlight = Color.white.opacity(0.21)  // 高光
let GlassBright    = Color.white.opacity(0.31)  // 亮色玻璃

// MARK: - 流体色彩（5 色渐变体系 + 橙色）
let FluidCyan   = Color(hex: 0x00D4FF)  // 青色 - 主强调色
let FluidPurple = Color(hex: 0x7B5CFC)  // 紫色
let FluidPink   = Color(hex: 0xFF3B8B)  // 粉色
let FluidBlue   = Color(hex: 0x3366FF)  // 蓝色
let FluidTeal   = Color(hex: 0x00E5A0)  // 碧绿色
let FluidOrange = Color(hex: 0xFF6B35)  // 橙色

// MARK: - 功能色
let AccentPrimary = Color(hex: 0x5B9AFF)
let AccentDanger  = Color(hex: 0xFF4757)
let AccentSuccess = Color(hex: 0x2ED573)
let AccentWarning = Color(hex: 0xFFA502)

// MARK: - 文字色（深色主题）
let TextPrimary   = Color(hex: 0xF0F0F5)
let TextSecondary = Color.white.opacity(0.60)
let TextTertiary  = Color.white.opacity(0.33)

// MARK: - 渐变流体色组（用于背景色块、按钮渐变）
let FluidGradientColors: [Color] = [
    FluidCyan, FluidPurple, FluidPink, FluidBlue, FluidTeal
]

// ─────────────────────────────────────────────────────────────────
// 主题数据模型 —— 对应 Android 端 ThemeManager.kt 中的 AppTheme
// isLight 为 true 时玻璃层与文字色需要反转，否则文字不可见。
// ─────────────────────────────────────────────────────────────────
struct AppTheme: Identifiable, Equatable {
    let id: String
    let name: String
    let description: String
    let isLight: Bool

    let bgDark: Color
    let bgDark2: Color
    let glassClear: Color
    let glassLight: Color
    let glassMedium: Color
    let glassBorder: Color
    let glassHighlight: Color
    let glassBright: Color

    let fluidCyan: Color
    let fluidPurple: Color
    let fluidPink: Color
    let fluidBlue: Color
    let fluidTeal: Color
    let fluidOrange: Color

    let accentPrimary: Color
    let textPrimary: Color
    let textSecondary: Color
    let textTertiary: Color

    /// 流体渐变色组，便于背景/按钮复用。
    var fluidGradient: [Color] {
        [fluidCyan, fluidPurple, fluidPink, fluidBlue, fluidTeal]
    }
}

// MARK: - 内置主题（与 Android Themes.all 完全对应）
enum Themes {
    /// 午夜深空：经典液态玻璃深色主题。
    static let midnightDark = AppTheme(
        id: "midnight_dark",
        name: "午夜深空",
        description: "经典液态玻璃深色主题",
        isLight: false,
        bgDark: BgDark,
        bgDark2: BgDark2,
        glassClear: GlassClear,
        glassLight: GlassLight,
        glassMedium: GlassMedium,
        glassBorder: GlassBorder,
        glassHighlight: GlassHighlight,
        glassBright: GlassBright,
        fluidCyan: FluidCyan,
        fluidPurple: FluidPurple,
        fluidPink: FluidPink,
        fluidBlue: FluidBlue,
        fluidTeal: FluidTeal,
        fluidOrange: FluidOrange,
        accentPrimary: AccentPrimary,
        textPrimary: TextPrimary,
        textSecondary: TextSecondary,
        textTertiary: TextTertiary
    )

    /// 超级无敌淡雅白：通透雅致的浅色主题，温润如玉。
    static let elegantWhite = AppTheme(
        id: "elegant_white",
        name: "超级无敌淡雅白",
        description: "通透雅致的浅色主题，温润如玉",
        isLight: true,
        bgDark: Color(hex: 0xF4F5F8),
        bgDark2: Color(hex: 0xEDEFF4),
        // 浅色主题玻璃用深色着色，保证对比度
        glassClear: Color.black.opacity(0.03),
        glassLight: Color.black.opacity(0.05),
        glassMedium: Color.black.opacity(0.08),
        glassBorder: Color.black.opacity(0.10),
        glassHighlight: Color.black.opacity(0.14),
        glassBright: Color.black.opacity(0.20),
        fluidCyan: Color(hex: 0x00B8E6),
        fluidPurple: Color(hex: 0x6B4CE0),
        fluidPink: Color(hex: 0xE6307A),
        fluidBlue: Color(hex: 0x2D55CC),
        fluidTeal: Color(hex: 0x00C788),
        fluidOrange: Color(hex: 0xE55A28),
        accentPrimary: Color(hex: 0x3D7AE0),
        textPrimary: Color(hex: 0x1A1A22),
        textSecondary: Color.black.opacity(0.60),
        textTertiary: Color.black.opacity(0.38)
    )

    /// 全部内置主题。
    static let all: [AppTheme] = [midnightDark, elegantWhite]
}

// MARK: - 主题环境（对应 Android 的 LocalAppTheme）
private struct AppThemeKey: EnvironmentKey {
    static let defaultValue: AppTheme = Themes.midnightDark
}

extension EnvironmentValues {
    /// 当前应用主题，通过 .environment(\.appTheme, ...) 注入。
    var appTheme: AppTheme {
        get { self[AppThemeKey.self] }
        set { self[AppThemeKey.self] = newValue }
    }
}

// MARK: - 主题管理器（对应 Android ThemeManager）
// ObservableObject 实现，跨页面共享当前主题；@Observable 宏需要 iOS 17+，
// 为兼容 iOS 16 这里使用 ObservableObject。
final class ThemeManager: ObservableObject {
    @Published var currentTheme: AppTheme

    init(theme: AppTheme = Themes.midnightDark) {
        self.currentTheme = theme
    }

    /// 切换主题（持久化由 Persistence 层负责，此处仅更新内存）。
    func switchTheme(_ theme: AppTheme) {
        currentTheme = theme
    }
}
