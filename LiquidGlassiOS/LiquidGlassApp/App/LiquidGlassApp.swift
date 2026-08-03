import SwiftUI
import AVFoundation

// ─────────────────────────────────────────────────────────────────
// 应用入口 —— 对应 Android 端 LiquidGlassApp.kt（Application）+ MainActivity
//
// 职责：
//   1. @main 入口
//   2. 配置 AVAudioSession：playback 类别 + 后台模式，保证锁屏/后台仍能播放音乐
//      （对应 Android MediaSessionService 的前台播放 + 后台播放权限）
//   3. 状态栏样式：默认浅色内容（深色背景下文字发亮）
//   4. 注入主题管理器与路由
// ─────────────────────────────────────────────────────────────────

@main
struct LiquidGlassApp: App {
    // 状态栏样式控制（.preferredColorScheme 影响状态栏文字明暗）
    @StateObject private var themeManager = ThemeManager()
    @StateObject private var router = AppRouter.shared

    init() {
        configureAudioSession()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(themeManager)
                .environmentObject(router)
                .environment(\.appTheme, themeManager.currentTheme)
                .preferredColorScheme(themeManager.currentTheme.isLight ? .light : .dark)
                // 状态栏文字始终为亮色，与液态深色玻璃风格匹配
                .statusBarColorScheme(themeManager.currentTheme.isLight ? .light : .dark)
        }
    }

    // MARK: - 音频会话配置（后台播放）
    /// 配置 AVAudioSession：
    /// - category = .playback：纯播放，不录制，静音键不静音音乐
    /// - mode = .default
    /// - options 包含 .mixWithOthers（与其它 App 共存）等
    ///
    /// 后台播放还需在 Info.plist 声明 UIBackgroundModes = ["audio"]，
    /// 见 Info.plist。此处仅配置会话类别，确保锁屏后播放不被系统挂起。
    private func configureAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(
                .playback,
                mode: .default,
                options: [.mixWithOthers, .allowAirPlay]
            )
            // 激活会话；AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation 在停播时通知其它 App
            try session.setActive(true, options: [.notifyOthersOnDeactivation])
        } catch {
            // 配置失败不影响启动，播放时 AudioPlayer 会再次尝试激活
            #if DEBUG
            print("[LiquidGlassApp] AVAudioSession 配置失败: \(error)")
            #endif
        }
    }
}

// MARK: - 状态栏样式扩展
private extension View {
    /// 控制状态栏文字明暗（iOS 16+ 通过 preferredColorScheme 间接控制，
    /// 此处显式包装便于未来切换为基于 Scene 的状态栏控制）。
    @ViewBuilder
    func statusBarColorScheme(_ scheme: ColorScheme?) -> some View {
        self
    }
}
