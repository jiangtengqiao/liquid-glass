import SwiftUI

// ─────────────────────────────────────────────────────────────────
// 主内容视图 —— 对应 Android 端 MainActivity（加载页→主页过渡）
//                + HomeScreen（按 currentScreen 路由到各功能页）
//
// 职责：
//   1. 始终绘制流体背景 FluidBackground
//   2. 用 AnimatedContent 在功能页间丝滑过渡
//   3. 首次组合时消费通知跳转路由（pendingRoute），直达对应功能页
//   4. 维护一个全局动画时钟 animTime，驱动背景与各页面同步
// ─────────────────────────────────────────────────────────────────

struct ContentView: View {
    @EnvironmentObject private var themeManager: ThemeManager
    @EnvironmentObject private var router: AppRouter

    /// 全局动画时钟（弧度），驱动背景与各页面共享同一相位。
    @State private var animTime: Double = 0
    /// 冷启动加载页（与 Android 一样每次冷启动显示一次）。
    @State private var showLoading: Bool = true

    var body: some View {
        ZStack {
            // 流体背景始终在最底层
            FluidBackground(animTime: animTime, theme: themeManager.currentTheme)

            // AnimatedContent 在加载页↔主页、各功能页间过渡
            AnimatedContent(
                targetState: showLoading ? LoadingTarget.loading : LoadingTarget.content(router.currentScreen),
                animation: .easeInOut(duration: 0.45)
            ) { target in
                switch target {
                case .loading:
                    LoadingView(theme: themeManager.currentTheme)
                case .content(let screen):
                    screenView(for: screen)
                }
            }
            .ignoresSafeArea(edges: .bottom)
        }
        // 驱动动画时钟：每 1/60 秒推进相位。取模 1000π（非 2π）避免非谐波频率跳变。
        .onReceive(Timer.publish(every: 1.0 / 60.0, on: .main, in: .common).autoconnect()) { _ in
            animTime = (animTime + 0.0166).truncatingRemainder(dividingBy: 1000 * .pi)
        }
        // 首次组合：检查通知跳转路由 + 加载页倒计时
        .onAppear {
            consumePendingRouteIfNeeded()
            // 加载页显示约 1.2 秒后进入主页（与 Android LoadingScreen 时长接近）
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                withAnimation(.easeInOut(duration: 0.5)) {
                    showLoading = false
                }
                // 进入主页后再次检查通知路由（兜底）
                consumePendingRouteIfNeeded()
            }
        }
        // 监听路由变化：通知跳转写入 pendingRoute 时触发消费
        .onChange(of: router.pendingRoute) { _ in
            consumePendingRouteIfNeeded()
        }
        // 切换主题时刷新环境
        .environment(\.appTheme, themeManager.currentTheme)
    }

    // MARK: - 路由消费
    /// 消费待跳转路由：若有 pendingRoute 则跳转到对应功能页（对应 Android consumeRoute）。
    private func consumePendingRouteIfNeeded() {
        if let screen = router.consumeRoute() {
            router.push(screen)
        }
    }

    // MARK: - 各功能页视图分发
    @ViewBuilder
    private func screenView(for screen: AppScreen) -> some View {
        switch screen {
        case .home:
            HomeView(animTime: animTime) { screen in
                router.push(screen)
            }
        case .music:
            MusicScreen(animTime: animTime) { router.popToHome() }
        case .compass:
            CompassScreen(animTime: animTime) { router.popToHome() }
        case .countdown:
            CountdownTimerScreen(animTime: animTime) { router.popToHome() }
        case .flashlight:
            FlashlightScreen(animTime: animTime) { router.popToHome() }
        case .gallery:
            GalleryScreen(animTime: animTime) { router.popToHome() }
        case .clock:
            ClockScreen(onBack: { router.popToHome() })
        case .calculator:
            CalculatorScreen(onBack: { router.popToHome() })
        case .note:
            NoteScreen(onBack: { router.popToHome() })
        case .todo:
            TodoScreen(onBack: { router.popToHome() })
        case .drawing:
            DrawingScreen(onBack: { router.popToHome() })
        case .qrCode:
            QRCodeScreen(onBack: { router.popToHome() })
        case .passwordGen:
            PasswordGeneratorScreen(onBack: { router.popToHome() })
        case .bmi:
            BMICalculatorScreen(onBack: { router.popToHome() })
        case .unitConverter:
            UnitConverterScreen(onBack: { router.popToHome() })
        case .colorPicker:
            ColorPickerScreen(onBack: { router.popToHome() })
        case .calendar:
            CalendarScreen(onBack: { router.popToHome() })
        case .fileManager:
            FileManagerScreen(onBack: { router.popToHome() })
        case .audioPlayer:
            AudioPlayerScreen(onBack: { router.popToHome() })
        case .legalCenter:
            LegalCenterScreen(onBack: { router.popToHome() })
        case .about:
            AboutScreen(onBack: { router.popToHome() })
        // ── 所有功能页均已移植完成，default 仅作防御性兜底 ──
        default:
            ComingSoonScreen(title: screen.title, onBack: { router.popToHome() })
        }
    }
}

// MARK: - 过渡目标枚举
private enum LoadingTarget: Equatable {
    case loading
    case content(AppScreen)
}

// MARK: - 加载页（对应 Android LoadingScreen）
private struct LoadingView: View {
    let theme: AppTheme

    var body: some View {
        ZStack {
            VStack(spacing: 24) {
                // 液态玻璃 Logo 圆形
                ZStack {
                    Circle()
                        .fill(
                            RadialGradient(
                                colors: [theme.fluidCyan.opacity(0.6), theme.fluidPurple.opacity(0.3), Color.clear],
                                center: .center,
                                startRadius: 0,
                                endRadius: 60
                            )
                        )
                        .frame(width: 120, height: 120)
                    Image(systemName: "drop.fill")
                        .font(.system(size: 48))
                        .foregroundStyle(.white)
                }
                Text("液态玻璃")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(theme.textPrimary)
                Text("灵动工具箱")
                    .font(.subheadline)
                    .foregroundStyle(theme.textSecondary)
            }
        }
    }
}

// MARK: - 首页（对应 Android HomeScreen）
private struct HomeView: View {
    let animTime: Double
    let onSelect: (AppScreen) -> Void
    @EnvironmentObject private var themeManager: ThemeManager

    // 工具卡片布局（与 Android ToolItem 一致）
    private let tools: [AppScreen] = [
        .music, .compass, .countdown, .flashlight, .gallery,
        .clock, .calculator, .todo, .note, .qrCode,
        .drawing, .bmi, .unitConverter, .passwordGen, .colorPicker,
        .calendar, .fileManager, .audioPlayer, .legalCenter, .about
    ]

    private let columns = [
        GridItem(.flexible(), spacing: 14),
        GridItem(.flexible(), spacing: 14)
    ]

    var body: some View {
        let theme = themeManager.currentTheme
        ScrollView {
            LazyVGrid(columns: columns, spacing: 14) {
                ForEach(tools) { tool in
                    Button {
                        onSelect(tool)
                    } label: {
                        VStack(alignment: .leading, spacing: 10) {
                            Image(systemName: tool.iconName)
                                .font(.system(size: 22))
                                .foregroundStyle(theme.fluidCyan)
                            Text(tool.title)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(theme.textPrimary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .glassSurface(cornerRadius: 20, theme: theme)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 60) // 顶部留出状态栏空间
            .padding(.bottom, 32)
        }
    }
}

// MARK: - 占位功能页（未实现的功能统一走此视图，避免空壳）
struct ComingSoonScreen: View {
    let title: String
    let onBack: () -> Void
    @EnvironmentObject private var themeManager: ThemeManager

    var body: some View {
        let theme = themeManager.currentTheme
        VStack(spacing: 16) {
            HStack {
                Button { onBack() } label: {
                    Image(systemName: "chevron.left")
                        .foregroundStyle(theme.textSecondary)
                }
                Spacer()
                Text(title)
                    .font(.headline)
                    .foregroundStyle(theme.textPrimary)
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 50)
            Spacer()
            VStack(spacing: 12) {
                Image(systemName: "wrench.and.screwdriver.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(theme.fluidPurple)
                Text("\(title) 即将上线")
                    .font(.title3.weight(.medium))
                    .foregroundStyle(theme.textPrimary)
                Text("此功能在 iOS 端尚在开发中，请期待后续版本")
                    .font(.caption)
                    .foregroundStyle(theme.textTertiary)
                    .multilineTextAlignment(.center)
            }
            .padding(40)
            .glassSurface(cornerRadius: 24, theme: theme)
            Spacer()
        }
    }
}
