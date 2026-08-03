import SwiftUI
import AVFoundation

// ─────────────────────────────────────────────────────────────────
// 手电筒 —— 对应 Android 端 ui/FlashlightScreen.kt
//
// 功能：
//   1. AVCaptureDevice torch：开/关手电筒
//   2. SOS 模式：摩斯电码 ... --- ... 循环
//   3. 频闪模式：可调频率的快速闪烁
//   4. 屏幕工具：纯白屏照明、亮度调节、屏幕常亮（超时禁用）
//
// iOS 端 AVCaptureDevice.torchMode 直接控制后置闪光灯，
// SOS/频闪用 Timer 驱动 torchMode 在 on/off 间切换。
// ─────────────────────────────────────────────────────────────────

private enum FlashlightTab: String, CaseIterable { case flashlight = "手电筒", screenTools = "屏幕工具" }
private enum FlashMode: String, CaseIterable { case off, on, sos, strobe, screenLight }

struct FlashlightScreen: View {
    let animTime: Double
    let onBack: () -> Void
    @State private var selectedTab: FlashlightTab = .flashlight
    @StateObject private var torch = TorchController()
    @State private var flashMode: FlashMode = .off
    @State private var strobeFrequency: Double = 8   // Hz
    @State private var screenBrightness: Double = Double(UIScreen.main.brightness)
    @State private var keepScreenOn: Bool = false

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            // 屏幕光模式下整个背景变白
            if flashMode == .screenLight {
                Color.white.ignoresSafeArea()
            } else {
                FluidBackground(animTime: animTime, theme: theme)
            }

            VStack(spacing: 16) {
                topBar(theme: flashMode == .screenLight ? Themes.elegantWhite : theme)

                HStack(spacing: 0) {
                    ForEach(FlashlightTab.allCases, id: \.self) { tab in
                        Button {
                            withAnimation { selectedTab = tab }
                        } label: {
                            Text(tab.rawValue)
                                .font(.subheadline.weight(selectedTab == tab ? .semibold : .regular))
                                .foregroundStyle(selectedTab == tab ? theme.textPrimary : theme.textTertiary)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                                .background(selectedTab == tab ? theme.fluidCyan.opacity(0.15) : Color.clear,
                                            in: RoundedRectangle(cornerRadius: 12))
                        }
                    }
                }
                .padding(4)
                .glassSurface(cornerRadius: 18, glassAlpha: 0.10, theme: theme)
                .padding(.horizontal, 16)

                ScrollView {
                    if selectedTab == .flashlight {
                        flashlightTab(theme: theme)
                    } else {
                        screenToolsTab(theme: theme)
                    }
                }
            }
            .padding(.top, 50)
            .padding(.bottom, 24)
        }
        .onDisappear {
            // 离开页面时关闭手电筒，避免持续耗电
            torch.turnOff()
            flashMode = .off
        }
        .onChange(of: keepScreenOn) { on in
            UIApplication.shared.isIdleTimerDisabled = on
        }
    }

    // MARK: - 顶部栏
    private func topBar(theme: AppTheme) -> some View {
        HStack {
            Button { onBack() } label: {
                Image(systemName: "chevron.left").foregroundStyle(theme.textSecondary)
            }
            Text("手电筒").font(.headline).foregroundStyle(theme.textPrimary)
            Spacer()
        }
        .padding(.horizontal, 16)
    }

    // MARK: - 手电筒 Tab
    private func flashlightTab(theme: AppTheme) -> some View {
        VStack(spacing: 20) {
            // 当前模式大图标
            ZStack {
                Circle()
                    .fill(RadialGradient(colors: [modeColor().opacity(0.4), Color.clear],
                                         center: .center, startRadius: 0, endRadius: 80))
                    .frame(width: 160, height: 160)
                Image(systemName: modeIcon())
                    .font(.system(size: 56))
                    .foregroundStyle(modeColor())
            }

            // 模式选择按钮组
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                modeButton(title: "开关", icon: flashlightOn ? "flashlight.on.fill" : "flashlight.off.fill",
                           color: theme.fluidCyan, theme: theme) {
                    toggleOnOff()
                }
                modeButton(title: "SOS", icon: "exclamationmark.triangle.fill", color: AccentDanger, theme: theme) {
                    selectMode(.sos)
                }
                modeButton(title: "频闪", icon: "bolt.fill", color: theme.fluidPurple, theme: theme) {
                    selectMode(.strobe)
                }
                modeButton(title: "屏幕灯", icon: "rectangle.fill", color: theme.fluidTeal, theme: theme) {
                    selectMode(.screenLight)
                }
            }

            // 频闪频率滑块（仅频闪模式显示）
            if flashMode == .strobe {
                VStack(spacing: 8) {
                    HStack {
                        Text("频率").font(.caption).foregroundStyle(theme.textSecondary)
                        Spacer()
                        Text(String(format: "%.1f Hz", strobeFrequency))
                            .font(.caption.monospacedDigit()).foregroundStyle(theme.fluidCyan)
                    }
                    Slider(value: $strobeFrequency, in: 1...20, step: 0.5) { _ in
                        torch.setStrobe(frequency: strobeFrequency)
                    }
                    .tint(theme.fluidCyan)
                }
                .padding(16)
                .glassSurface(cornerRadius: 16, glassAlpha: 0.10, theme: theme)
            }

            // 关闭按钮（任何模式运行时显示）
            if flashMode != .off {
                Button {
                    turnOffAll()
                } label: {
                    Text("关闭")
                        .font(.callout.weight(.medium))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(AccentDanger, in: RoundedRectangle(cornerRadius: 16))
                }
            }
        }
        .padding(24)
        .glassSurface(cornerRadius: 24, theme: theme)
        .padding(.horizontal, 16)
    }

    private var flashlightOn: Bool { flashMode == .on }

    private func modeIcon() -> String {
        switch flashMode {
        case .off:         return "flashlight.off.fill"
        case .on:          return "flashlight.on.fill"
        case .sos:         return "exclamationmark.triangle.fill"
        case .strobe:      return "bolt.fill"
        case .screenLight: return "rectangle.fill"
        }
    }

    private func modeColor() -> Color {
        switch flashMode {
        case .off:         return Themes.midnightDark.textTertiary
        case .on:          return FluidCyan
        case .sos:         return AccentDanger
        case .strobe:      return FluidPurple
        case .screenLight: return FluidTeal
        }
    }

    private func toggleOnOff() {
        if flashMode == .on { turnOffAll() }
        else { selectMode(.on) }
    }

    private func selectMode(_ mode: FlashMode) {
        turnOffAll()
        flashMode = mode
        switch mode {
        case .on:          torch.turnOn()
        case .sos:         torch.startSOS()
        case .strobe:      torch.startStrobe(frequency: strobeFrequency)
        case .screenLight: break // 背景已变白
        case .off:         break
        }
    }

    private func turnOffAll() {
        torch.turnOff()
        flashMode = .off
    }

    // MARK: - 屏幕工具 Tab
    private func screenToolsTab(theme: AppTheme) -> some View {
        VStack(spacing: 20) {
            // 亮度调节
            VStack(spacing: 8) {
                HStack {
                    Image(systemName: "sun.min").foregroundStyle(theme.textSecondary)
                    Text("屏幕亮度").font(.subheadline).foregroundStyle(theme.textPrimary)
                    Spacer()
                    Text("\(Int(screenBrightness * 100))%")
                        .font(.caption.monospacedDigit()).foregroundStyle(theme.fluidCyan)
                }
                Slider(value: $screenBrightness, in: 0.05...1.0, step: 0.05) { editing in
                    if !editing {
                        UIScreen.main.brightness = CGFloat(screenBrightness)
                    }
                }
                .tint(theme.fluidCyan)
            }
            .padding(16)
            .glassSurface(cornerRadius: 16, glassAlpha: 0.10, theme: theme)

            // 屏幕常亮（防息屏）
            Toggle(isOn: $keepScreenOn) {
                Label("屏幕常亮", systemImage: "lock.open.fill")
                    .font(.subheadline).foregroundStyle(theme.textPrimary)
            }
            .tint(theme.fluidCyan)
            .padding(16)
            .glassSurface(cornerRadius: 16, glassAlpha: 0.10, theme: theme)

            // 屏幕白光
            Button {
                selectMode(.screenLight)
                selectedTab = .flashlight
            } label: {
                Label("开启屏幕白光", systemImage: "rectangle.fill")
                    .font(.callout.weight(.medium))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(theme.fluidTeal, in: RoundedRectangle(cornerRadius: 16))
            }
        }
        .padding(.horizontal, 16)
    }

    private func modeButton(title: String, icon: String, color: Color, theme: AppTheme, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: icon).font(.title2).foregroundStyle(color)
                Text(title).font(.caption.weight(.medium)).foregroundStyle(theme.textPrimary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .glassSurface(cornerRadius: 18, glassAlpha: 0.12, theme: theme)
        }
    }
}

// MARK: - 闪光灯控制器（对应 Android CameraManager + setTorchMode）
/// 封装 AVCaptureDevice torch 控制，支持常亮 / SOS / 频闪。
final class TorchController: ObservableObject {

    private var sosTimer: Timer?
    private var strobeTimer: Timer?
    /// SOS 摩斯序列：... --- ... （点=短亮，划=长亮，间隔用灭灯表示）。
    /// 单位时长 0.2s：点=0.2s 亮，划=0.6s 亮，元素间隔=0.2s 灭，字母间隔=0.6s 灭。
    private let sosPattern: [(Bool, TimeInterval)] = [
        (true, 0.2), (false, 0.2),   // 点
        (true, 0.2), (false, 0.2),   // 点
        (true, 0.2), (false, 0.6),   // 点 + 字母间隔
        (true, 0.6), (false, 0.2),   // 划
        (true, 0.6), (false, 0.2),   // 划
        (true, 0.6), (false, 0.6),   // 划 + 字母间隔
        (true, 0.2), (false, 0.2),   // 点
        (true, 0.2), (false, 0.2),   // 点
        (true, 0.2), (false, 1.0)    // 点 + 循环间隔
    ]

    func turnOn() {
        turnOff()
        setTorch(level: 1.0)
    }

    func turnOff() {
        sosTimer?.invalidate(); sosTimer = nil
        strobeTimer?.invalidate(); strobeTimer = nil
        setTorch(level: 0.0)
    }

    /// SOS 循环：按 pattern 依次亮灭。
    func startSOS() {
        turnOff()
        var index = 0
        sosTimer = Timer.scheduledTimer(withTimeInterval: 0.01, repeats: true) { [weak self] timer in
            guard let self else { timer.invalidate(); return }
            let (on, duration) = self.sosPattern[index]
            self.setTorch(level: on ? 1.0 : 0.0)
            // 重新调度下一次切换时间
            timer.invalidate()
            self.sosTimer = Timer.scheduledTimer(withTimeInterval: duration, repeats: false) { [weak self] _ in
                guard let self else { return }
                index = (index + 1) % self.sosPattern.count
                self.sosTimer = Timer.scheduledTimer(withTimeInterval: 0.01, repeats: false) { [weak self] _ in
                    self?.startSOS()
                }
            }
        }
    }

    /// 频闪：按频率在亮灭间快速切换。
    func startStrobe(frequency: Double) {
        turnOff()
        let interval = 1.0 / (frequency * 2) // 半周期：亮半周灭半周
        var on = false
        strobeTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
            on.toggle()
            self?.setTorch(level: on ? 1.0 : 0.0)
        }
    }

    /// 设置闪光灯亮度（level ∈ [0,1]，AVCaptureDevice.maxAvailableTorchLevel 对应常亮）。
    private func setTorch(level: Float) {
        guard let device = AVCaptureDevice.default(for: .video), device.hasTorch else {
            #if DEBUG
            print("[Torch] 设备无闪光灯或不可用")
            #endif
            return
        }
        do {
            try device.lockForConfiguration()
            // level > 0 用 .torchOn + brightness；level == 0 用 .off
            if level > 0 {
                try device.setTorchModeOn(level: min(level, device.maxAvailableTorchLevel))
            } else {
                device.torchMode = .off
            }
            device.unlockForConfiguration()
        } catch {
            #if DEBUG
            print("[Torch] 设置失败: \(error)")
            #endif
        }
    }
}
