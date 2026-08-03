import SwiftUI
import AVFoundation
import AudioToolbox
import UIKit

// ─────────────────────────────────────────────────────────────────
// 秒表 · 倒计时 —— 对应 Android 端 ui/CountdownTimerScreen.kt
//
// 关键实现：
//   1. 用 Timer + Date() 精确计时，避免 DispatchTimer 累加漂移
//      （对应 Android SystemClock.elapsedRealtime() 思路）：
//      - 倒计时：记录 endTime（Date），剩余 = endTime - now
//      - 秒表：记录 startedAt（Date），elapsed = now - startedAt + accumulated
//   2. 计次列表用前缀和优化：仅存每次计次的累计时间戳，显示当前圈用时
//      = 本次计次 - 上次计次，O(1) 计算，无需每次重新求和
//   3. 倒计时结束触发震动 + 提示
// ─────────────────────────────────────────────────────────────────

private enum TimerTab: String, CaseIterable { case countdown = "倒计时", stopwatch = "秒表" }
private enum CountdownState { case idle, running, paused, finished }
private enum StopwatchState { case idle, running, paused }

struct CountdownTimerScreen: View {
    let animTime: Double
    let onBack: () -> Void
    @State private var selectedTab: TimerTab = .countdown

    // 倒计时状态
    @State private var countdownState: CountdownState = .idle
    @State private var totalSeconds: Int = 0
    @State private var remainingSeconds: Int = 0
    @State private var pickerH: Int = 0
    @State private var pickerM: Int = 0
    @State private var pickerS: Int = 0
    @State private var showAlarm: Bool = false
    /// 倒计时结束的真实时间戳（基于 Date()，避免 delay 累加漂移）。
    @State private var countdownEnd: Date = Date()
    @State private var countdownTicker: Timer?

    // 秒表状态
    @State private var stopwatchState: StopwatchState = .idle
    @State private var elapsedMs: Int = 0
    /// 已运行段累计毫秒（暂停时固化），与本次运行真实时间相加得总 elapsed。
    @State private var accumulatedMs: Int = 0
    @State private var stopwatchStart: Date = Date()
    @State private var stopwatchTicker: Timer?
    /// 计次累计时间戳列表（前缀和：每次计次存"到此为止总毫秒"，圈用时 = 相邻差）。
    @State private var lapTimestamps: [Int] = []

    // 系统反馈
    @State private var audioPlayer: AVAudioPlayer?

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: animTime, theme: theme)

            VStack(spacing: 16) {
                topBar(theme: theme)

                // Tab 切换
                HStack(spacing: 0) {
                    ForEach(TimerTab.allCases, id: \.self) { tab in
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
                    if selectedTab == .countdown {
                        countdownView(theme: theme)
                    } else {
                        stopwatchView(theme: theme)
                    }
                }
            }
            .padding(.top, 50)
            .padding(.bottom, 24)
        }
        .alert("时间到", isPresented: $showAlarm) {
            Button("确定") { countdownState = .idle; remainingSeconds = 0 }
        } message: { Text("倒计时已结束") }
        .onDisappear {
            countdownTicker?.invalidate()
            stopwatchTicker?.invalidate()
        }
    }

    // MARK: - 顶部栏
    private func topBar(theme: AppTheme) -> some View {
        HStack {
            Button { onBack() } label: {
                Image(systemName: "chevron.left").foregroundStyle(theme.textSecondary)
            }
            Text("秒表·倒计时").font(.headline).foregroundStyle(theme.textPrimary)
            Spacer()
        }
        .padding(.horizontal, 16)
    }

    // MARK: - 倒计时视图
    private func countdownView(theme: AppTheme) -> some View {
        VStack(spacing: 20) {
            // 倒计时数字显示
            Text(formatSeconds(remainingSeconds))
                .font(.system(size: 64, weight: .bold, design: .rounded).monospacedDigit())
                .foregroundStyle(countdownState == .finished ? theme.fluidCyan : theme.textPrimary)

            // 时间选择器（仅在 idle 时显示）
            if countdownState == .idle {
                HStack {
                    timePicker(value: $pickerH, max: 23, label: "时", theme: theme)
                    Text(":").foregroundStyle(theme.textTertiary)
                    timePicker(value: $pickerM, max: 59, label: "分", theme: theme)
                    Text(":").foregroundStyle(theme.textTertiary)
                    timePicker(value: $pickerS, max: 59, label: "秒", theme: theme)
                }
            }

            // 控制按钮
            HStack(spacing: 20) {
                if countdownState == .running {
                    controlButton(title: "暂停", icon: "pause.fill", color: theme.fluidBlue, theme: theme) {
                        pauseCountdown()
                    }
                } else if countdownState == .paused {
                    controlButton(title: "继续", icon: "play.fill", color: theme.fluidTeal, theme: theme) {
                        resumeCountdown()
                    }
                } else {
                    controlButton(title: "开始", icon: "play.fill", color: theme.fluidCyan, theme: theme) {
                        startCountdown()
                    }
                }
                controlButton(title: "重置", icon: "arrow.counterclockwise", color: AccentDanger, theme: theme) {
                    resetCountdown()
                }
            }
        }
        .padding(24)
        .glassSurface(cornerRadius: 24, theme: theme)
        .padding(.horizontal, 16)
    }

    private func timePicker(value: Binding<Int>, max: Int, label: String, theme: AppTheme) -> some View {
        VStack(spacing: 4) {
            Picker(label, selection: value) {
                ForEach(0...max, id: \.self) { Text(String(format: "%02d", $0)) }
            }
            .pickerStyle(.wheel)
            .frame(width: 70, height: 100)
            Text(label).font(.caption2).foregroundStyle(theme.textTertiary)
        }
    }

    // MARK: - 倒计时控制（基于 Date() 精确计时，杜绝漂移）
    private func startCountdown() {
        let total = pickerH * 3600 + pickerM * 60 + pickerS
        guard total > 0 else { return }
        totalSeconds = total
        remainingSeconds = total
        // 关键：记录结束时间戳，后续按 Date() 重算剩余，避免 delay 累加导致漂移
        countdownEnd = Date().addingTimeInterval(TimeInterval(total))
        countdownState = .running
        startCountdownTicker()
    }

    private func pauseCountdown() {
        countdownState = .paused
        countdownTicker?.invalidate()
        countdownTicker = nil
        // 暂停时记录剩余秒，恢复时基于剩余秒重算 endTime
    }

    private func resumeCountdown() {
        // 用剩余秒重新计算结束时间戳，保证暂停-继续后仍精确
        countdownEnd = Date().addingTimeInterval(TimeInterval(remainingSeconds))
        countdownState = .running
        startCountdownTicker()
    }

    private func resetCountdown() {
        countdownState = .idle
        countdownTicker?.invalidate()
        countdownTicker = nil
        remainingSeconds = 0
        pickerH = 0; pickerM = 0; pickerS = 0
    }

    private func startCountdownTicker() {
        countdownTicker?.invalidate()
        // 每 200ms 刷新一次（仅控制刷新频率，计时基准是 Date() 而非累加）
        countdownTicker = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { _ in
            let remaining = countdownEnd.timeIntervalSinceNow
            if remaining <= 0 {
                remainingSeconds = 0
                countdownState = .finished
                countdownTicker?.invalidate()
                countdownTicker = nil
                triggerAlarm()
            } else {
                remainingSeconds = Int(remaining.rounded(.up)) // 向上取整避免提前归零
            }
        }
    }

    private func triggerAlarm() {
        showAlarm = true
        // 触觉反馈（对应 Android VibrationEffect）
        let generator = UINotificationFeedbackGenerator()
        generator.notificationWarningOccurred()
        // 蜂鸣音（用系统提示音替代 Android Vibrator 长震）
        playBeep()
    }

    private func playBeep() {
        // 用 SystemSoundID 触发系统提示音，无需音频文件
        AudioServicesPlaySystemSound(1057) // peek 音效
    }

    // MARK: - 秒表视图
    private func stopwatchView(theme: AppTheme) -> some View {
        VStack(spacing: 20) {
            // 计时显示（毫秒精度）
            Text(formatMilliseconds(elapsedMs))
                .font(.system(size: 56, weight: .bold, design: .rounded).monospacedDigit())
                .foregroundStyle(theme.textPrimary)

            // 控制按钮
            HStack(spacing: 20) {
                switch stopwatchState {
                case .idle:
                    controlButton(title: "开始", icon: "play.fill", color: theme.fluidCyan, theme: theme) {
                        startStopwatch()
                    }
                case .running:
                    controlButton(title: "计次", icon: "flag.fill", color: theme.fluidPurple, theme: theme) {
                        recordLap()
                    }
                    controlButton(title: "暂停", icon: "pause.fill", color: AccentDanger, theme: theme) {
                        pauseStopwatch()
                    }
                case .paused:
                    controlButton(title: "继续", icon: "play.fill", color: theme.fluidTeal, theme: theme) {
                        resumeStopwatch()
                    }
                    controlButton(title: "重置", icon: "arrow.counterclockwise", color: theme.textSecondary, theme: theme) {
                        resetStopwatch()
                    }
                }
            }

            // 计次列表（前缀和优化：每行圈用时 = 本计次累计 - 上次计次累计）
            if !lapTimestamps.isEmpty {
                lapList(theme: theme)
            }
        }
        .padding(24)
        .glassSurface(cornerRadius: 24, theme: theme)
        .padding(.horizontal, 16)
    }

    /// 计次列表：存的是累计时间戳（前缀和），显示时用相邻差得单圈用时，O(1)。
    private func lapList(theme: AppTheme) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(lapTimestamps.enumerated()), id: \.offset) { idx, ts in
                let prev = idx == 0 ? 0 : lapTimestamps[idx - 1]
                let lapMs = ts - prev   // 前缀和差 = 单圈用时
                HStack {
                    Text("计次 \(idx + 1)")
                        .font(.subheadline).foregroundStyle(theme.textSecondary)
                    Spacer()
                    Text(formatMilliseconds(lapMs))
                        .font(.subheadline.monospacedDigit()).foregroundStyle(theme.textPrimary)
                }
                .padding(.vertical, 8)
                Divider().opacity(0.2)
            }
        }
    }

    // MARK: - 秒表控制（基于 Date() 精确计时）
    private func startStopwatch() {
        accumulatedMs = 0
        elapsedMs = 0
        lapTimestamps = []
        stopwatchStart = Date()        // 记录本次运行起点
        stopwatchState = .running
        startStopwatchTicker()
    }

    private func pauseStopwatch() {
        // 暂停时把本次运行段固化进 accumulated，停止 ticker
        accumulatedMs = elapsedMs
        stopwatchState = .paused
        stopwatchTicker?.invalidate()
        stopwatchTicker = nil
    }

    private func resumeStopwatch() {
        stopwatchStart = Date()        // 新的运行起点，elapsed = now - start + accumulated
        stopwatchState = .running
        startStopwatchTicker()
    }

    private func resetStopwatch() {
        stopwatchState = .idle
        stopwatchTicker?.invalidate()
        stopwatchTicker = nil
        elapsedMs = 0
        accumulatedMs = 0
        lapTimestamps = []
    }

    /// 记次：把当前累计毫秒追加到前缀和数组。
    private func recordLap() {
        lapTimestamps.append(elapsedMs)
    }

    private func startStopwatchTicker() {
        stopwatchTicker?.invalidate()
        // 每 50ms 刷新显示；elapsed = 真实时间差 + accumulated，基准是 Date() 而非累加
        stopwatchTicker = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { _ in
            let deltaMs = Int(Date().timeIntervalSince(stopwatchStart) * 1000)
            elapsedMs = accumulatedMs + deltaMs
        }
    }

    // MARK: - 通用控件
    private func controlButton(title: String, icon: String, color: Color, theme: AppTheme, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.title2)
                Text(title).font(.caption)
            }
            .foregroundStyle(color)
            .frame(width: 80, height: 70)
            .glassSurface(cornerRadius: 18, glassAlpha: 0.12, theme: theme)
        }
    }

    // MARK: - 格式化
    private func formatSeconds(_ s: Int) -> String {
        let h = s / 3600
        let m = (s % 3600) / 60
        let sec = s % 60
        return String(format: "%02d:%02d:%02d", h, m, sec)
    }

    private func formatMilliseconds(_ ms: Int) -> String {
        let totalCs = ms / 10   // 厘秒
        let h = totalCs / 360000
        let m = (totalCs % 360000) / 6000
        let s = (totalCs % 6000) / 100
        let cs = totalCs % 100
        if h > 0 {
            return String(format: "%02d:%02d:%02d.%02d", h, m, s, cs)
        }
        return String(format: "%02d:%02d.%02d", m, s, cs)
    }
}
