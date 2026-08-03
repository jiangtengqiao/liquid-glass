import SwiftUI
import Combine

// ─────────────────────────────────────────────────────────────────
// 全局路由管理 —— 对应 Android 端 AppRouter.kt
//
// 职责：
//   1. 管理当前所在功能页（currentScreen）
//   2. 承接通知点击跳转的桥梁：通知点击后写入 pendingRoute，
//      ContentView 观察其变化并跳转到对应功能页，消费后清空，
//      实现"点通知直达对应功能页"而非仅跳首页。
//
// Android 端用 @Volatile backupRoute 防止 Compose snapshot 在冷启动
// 多层异步下丢失状态；iOS 端用 ObservableObject + @Published 已能保证
// SwiftUI 状态正确传播，但仍保留 backupRoute 兜底以防极端时序。
// ─────────────────────────────────────────────────────────────────

// MARK: - 功能页枚举（对应 Android Screen.kt）
enum AppScreen: String, CaseIterable, Identifiable {
    case home, clock, calculator, todo, about
    case countdown, note, unitConverter, passwordGen, bmi
    case gallery, audioPlayer, fileManager, qrCode, drawing
    case compass, flashlight, colorPicker, calendar
    case music, legalCenter

    var id: String { rawValue }

    /// 显示名称（与 Android 端工具卡片标题一致）。
    var title: String {
        switch self {
        case .home:          return "液态玻璃"
        case .clock:         return "时钟"
        case .calculator:    return "计算器"
        case .todo:          return "待办"
        case .about:         return "关于"
        case .countdown:     return "秒表·倒计时"
        case .note:          return "便签"
        case .unitConverter: return "单位换算"
        case .passwordGen:   return "密码生成"
        case .bmi:           return "BMI 计算"
        case .gallery:       return "壁纸库"
        case .audioPlayer:   return "音频播放器"
        case .fileManager:   return "文件管理"
        case .qrCode:        return "二维码"
        case .drawing:       return "画板"
        case .compass:       return "指南针"
        case .flashlight:    return "手电筒"
        case .colorPicker:   return "取色器"
        case .calendar:      return "日历"
        case .music:         return "音乐"
        case .legalCenter:   return "法律中心"
        }
    }

    /// SF Symbol 图标名。
    var iconName: String {
        switch self {
        case .home:          return "house.fill"
        case .clock:         return "clock.fill"
        case .calculator:    return "plus.forwardslash.minus"
        case .todo:          return "checklist"
        case .about:         return "info.circle.fill"
        case .countdown:     return "timer"
        case .note:          return "note.text"
        case .unitConverter: return "ruler"
        case .passwordGen:   return "key.fill"
        case .bmi:           return "heart.text.square.fill"
        case .gallery:       return "photo.on.rectangle.angled"
        case .audioPlayer:   return "waveform"
        case .fileManager:   return "folder.fill"
        case .qrCode:        return "qrcode"
        case .drawing:       return "pencil.tip.crop.circle"
        case .compass:       return "location.north.line.fill"
        case .flashlight:    return "flashlight.off.fill"
        case .colorPicker:   return "eyedropper"
        case .calendar:      return "calendar"
        case .music:         return "music.note"
        case .legalCenter:   return "doc.text.fill"
        }
    }
}

// MARK: - 全局路由单例（对应 Android object AppRouter）
// 用 ObservableObject 实现，跨页面共享；@Observable 宏需 iOS 17+，
// 为兼容 iOS 16 这里使用 ObservableObject。
final class AppRouter: ObservableObject {

    static let shared = AppRouter()

    /// 当前所在功能页。
    @Published var currentScreen: AppScreen = .home

    /// 待跳转的路由名（AppScreen.rawValue），nil 表示无待处理跳转。
    @Published var pendingRoute: String?

    /// 兜底备份：冷启动时 pendingRoute 可能在 ContentView 首次组合前被设置，
    /// 保留备份确保不丢失（对应 Android @Volatile backupRoute）。
    private var backupRoute: String?

    private init() {}

    /// 设置待跳转路由（同时写 @Published 与备份）。
    func navigate(to screen: AppScreen) {
        backupRoute = screen.rawValue
        pendingRoute = screen.rawValue
    }

    /// 通过 rawValue 设置待跳转路由（通知 payload 解析后调用）。
    func navigate(route: String) {
        backupRoute = route
        pendingRoute = route
    }

    /// 直接切换当前页（用户在首页点击工具卡片）。
    func push(_ screen: AppScreen) {
        currentScreen = screen
    }

    /// 返回首页。
    func popToHome() {
        currentScreen = .home
    }

    /// 消费待跳转路由：优先读 @Published，为空时读备份；返回后清空两个存储，
    /// 确保只消费一次（对应 Android consumeRoute）。
    func consumeRoute() -> AppScreen? {
        let raw = pendingRoute ?? backupRoute
        if let raw, let screen = AppScreen(rawValue: raw) {
            pendingRoute = nil
            backupRoute = nil
            return screen
        }
        pendingRoute = nil
        backupRoute = nil
        return nil
    }
}
