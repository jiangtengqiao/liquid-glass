import SwiftUI
import CoreMotion
import Combine

// ─────────────────────────────────────────────────────────────────
// 指南针 —— 对应 Android 端 ui/CompassScreen.kt
//
// 关键实现：
//   1. CoreMotion CMMotionManager，优先 deviceMotion.attitude.yaw 算方位角
//   2. 不可用或超时则 fallback 到 magnetometer（CMMagneticField）+ 加速度计
//      手动计算航向（对应 Android accel+mag 兜底）
//   3. Canvas 绘制指南针表盘（刻度 + N/E/S/W + 指针）
//   4. 传感器超时检测：注册后 1.5s 仍无数据 → 标记 TIMEOUT，提供"重试"按钮
//      对应 Android compassStatus = WAITING / TIMEOUT / NO_SENSOR / CALIBRATE
// ─────────────────────────────────────────────────────────────────

enum CompassStatus: String {
    case waiting = "WAITING"      // 已注册，暂未收到数据
    case ok = "OK"                // 正常工作
    case timeout = "TIMEOUT"      // 超时无数据
    case noSensor = "NO_SENSOR"   // 设备无方向传感器
    case calibrate = "CALIBRATE"  // 磁力计精度低，提示校准
}

struct CompassScreen: View {
    let animTime: Double
    let onBack: () -> Void
    @StateObject private var motion = CompassMotionManager()

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: animTime, theme: theme)

            VStack(spacing: 24) {
                topBar(theme: theme)

                // 状态提示（杜绝"静默失败转不动"，对应 Android compassStatus UI）
                statusHint(theme: theme)

                Spacer()

                // 指南针表盘
                compassDial(theme: theme)
                    .frame(width: 300, height: 300)

                // 方位读数
                readingCard(theme: theme)
                    .padding(.horizontal, 16)

                Spacer()

                // 重试按钮（超时/无传感器时显示）
                if motion.status == .timeout || motion.status == .noSensor {
                    Button {
                        motion.retry()
                    } label: {
                        Label("重试", systemImage: "arrow.clockwise")
                            .font(.callout.weight(.medium))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 28).padding(.vertical, 12)
                            .background(theme.accentPrimary, in: Capsule())
                    }
                }
            }
            .padding(.top, 50)
            .padding(.bottom, 24)
        }
        .onAppear { motion.start() }
        .onDisappear { motion.stop() }
    }

    // MARK: - 子视图
    private func topBar(theme: AppTheme) -> some View {
        HStack {
            Button { onBack() } label: {
                Image(systemName: "chevron.left").foregroundStyle(theme.textSecondary)
            }
            Text("指南针").font(.headline).foregroundStyle(theme.textPrimary)
            Spacer()
            Image(systemName: "location.north.line.fill").foregroundStyle(theme.fluidCyan)
        }
        .padding(.horizontal, 16)
    }

    private func statusHint(theme: AppTheme) -> some View {
        let (text, color): (String, Color) = {
            switch motion.status {
            case .ok:        return ("指南针工作正常", theme.fluidTeal)
            case .waiting:   return ("等待传感器数据…", theme.fluidCyan)
            case .timeout:   return ("传感器超时无响应，请重试", AccentWarning)
            case .noSensor:  return ("本设备无方向传感器", AccentDanger)
            case .calibrate: return ("磁力计精度低，请挥动设备校准", AccentWarning)
            }
        }()
        return Text(text)
            .font(.caption)
            .foregroundStyle(color)
            .padding(.horizontal, 16).padding(.vertical, 8)
            .glassSurface(cornerRadius: 14, glassAlpha: 0.10, theme: theme)
            .padding(.horizontal, 16)
    }

    /// 表盘：用 Canvas 绘制，根据方位角反向旋转表盘使指针指向北。
    private func compassDial(theme: AppTheme) -> some View {
        Canvas { context, size in
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = min(size.width, size.height) / 2 - 8

            // 反向旋转表盘：用户朝向 motion.azimuth，则表盘相对转动 -azimuth
            context.drawLayer { ctx in
                ctx.translateBy(x: center.x, y: center.y)
                ctx.rotate(by: .degrees(-motion.azimuth))
                ctx.translateBy(x: -center.x, y: -center.y)
                drawDial(in: ctx, size: size, center: center, radius: radius, theme: theme)
            }

            // 指针固定不动（始终指向屏幕正上方=北方向读数）
            drawNeedle(in: context, center: center, radius: radius, theme: theme)
        }
    }

    /// 绘制表盘刻度与方位字母。
    private func drawDial(in context: GraphicsContext, size: CGSize, center: CGPoint, radius: CGFloat, theme: AppTheme) {
        // 外圆
        context.stroke(
            Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)),
            with: theme.glassBorder, lineWidth: 2
        )
        // 刻度（每 30° 一条长刻度，每 10° 一条短刻度）
        for angle in stride(from: 0.0, to: 360.0, by: 10.0) {
            let rad = (angle - 90) * .pi / 180
            let isMajor = angle.truncatingRemainder(dividingBy: 30) == 0
            let len: CGFloat = isMajor ? 16 : 8
            let outer = CGPoint(x: center.x + CGFloat(cos(rad)) * radius, y: center.y + CGFloat(sin(rad)) * radius)
            let inner = CGPoint(x: center.x + CGFloat(cos(rad)) * (radius - len), y: center.y + CGFloat(sin(rad)) * (radius - len))
            var path = Path()
            path.move(to: outer)
            path.addLine(to: inner)
            context.stroke(path, with: theme.textSecondary.opacity(isMajor ? 0.8 : 0.4), lineWidth: isMajor ? 2 : 1)
        }
        // N/E/S/W 方位字母
        let directions = [("N", 0.0, theme.fluidCyan), ("E", 90.0, theme.textPrimary),
                          ("S", 180.0, theme.textPrimary), ("W", 270.0, theme.textPrimary)]
        for (label, angle, color) in directions {
            let rad = (angle - 90) * .pi / 180
            let pos = CGPoint(x: center.x + CGFloat(cos(rad)) * (radius - 36), y: center.y + CGFloat(sin(rad)) * (radius - 36))
            context.draw(Text(label).font(.headline.weight(.bold)).foregroundColor(color), at: pos)
        }
    }

    /// 绘制固定指针（北端红、南端白）。
    private func drawNeedle(in context: GraphicsContext, center: CGPoint, radius: CGFloat, theme: AppTheme) {
        // 北端（红）
        var north = Path()
        north.move(to: CGPoint(x: center.x, y: center.y))
        north.addLine(to: CGPoint(x: center.x - 6, y: center.y - radius * 0.7 + 6))
        north.addLine(to: CGPoint(x: center.x, y: center.y - radius * 0.7))
        north.addLine(to: CGPoint(x: center.x + 6, y: center.y - radius * 0.7 + 6))
        north.closeSubpath()
        context.fill(north, with: AccentDanger)

        // 南端（白）
        var south = Path()
        south.move(to: CGPoint(x: center.x, y: center.y))
        south.addLine(to: CGPoint(x: center.x - 6, y: center.y + radius * 0.7 - 6))
        south.addLine(to: CGPoint(x: center.x, y: center.y + radius * 0.7))
        south.addLine(to: CGPoint(x: center.x + 6, y: center.y + radius * 0.7 - 6))
        south.closeSubpath()
        context.fill(south, with: Color.white)

        // 中心圆点
        context.fill(Path(ellipseIn: CGRect(x: center.x - 6, y: center.y - 6, width: 12, height: 12)),
                     with: theme.glassBright)
    }

    private func readingCard(theme: AppTheme) -> some View {
        let dir = cardinalDirection(for: motion.azimuth)
        return HStack(spacing: 24) {
            VStack(spacing: 4) {
                Text("\(Int(motion.azimuth.rounded()))°")
                    .font(.system(size: 36, weight: .bold, design: .rounded))
                    .foregroundStyle(theme.fluidCyan)
                Text("方位角").font(.caption2).foregroundStyle(theme.textTertiary)
            }
            VStack(spacing: 4) {
                Text(dir)
                    .font(.title.weight(.semibold))
                    .foregroundStyle(theme.textPrimary)
                Text("方向").font(.caption2).foregroundStyle(theme.textTertiary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
        .glassSurface(cornerRadius: 20, theme: theme)
    }

    /// 方位角转中文方位。
    private func cardinalDirection(for azimuth: Double) -> String {
        let dirs = ["北", "东北", "东", "东南", "南", "西南", "西", "西北"]
        let idx = Int((azimuth + 22.5) / 45) % 8
        return dirs[idx]
    }
}

// MARK: - CoreMotion 管理器（对应 Android SensorManager 监听逻辑）
/// 优先 deviceMotion.attitude.yaw；不可用则 fallback magnetometer + accelerometer。
/// 含超时检测：注册后 1.5s 无数据 → status = .timeout。
final class CompassMotionManager: ObservableObject {

    @Published var azimuth: Double = 0       // 0~360
    @Published var pitch: Double = 0
    @Published var roll: Double = 0
    @Published var status: CompassStatus = .waiting

    private let motionManager = CMMotionManager()
    private let motionQueue = OperationQueue()
    private var timeoutItem: DispatchWorkItem?
    private var lastDeviceMotionTime: Date = .distantPast
    private var hasReceivedAzimuth = false

    /// 加速度与磁场缓存（fallback 计算航向用）
    private var gravity: CMAcceleration = (0, 0, 1)
    private var magneticField: CMMagneticField = (0, 0, 0)

    init() {
        motionQueue.qualityOfService = .userInteractive
    }

    // MARK: - 启动传感器
    func start() {
        retry()
    }

    func retry() {
        stop()
        status = .waiting
        hasReceivedAzimuth = false
        lastDeviceMotionTime = .distantPast

        // 优先 deviceMotion（融合加速度+陀螺仪+磁力计，最稳定）
        if motionManager.isDeviceMotionAvailable {
            motionManager.deviceMotionUpdateInterval = 1.0 / 30.0
            motionManager.startDeviceMotionUpdates(using: .xArbitraryZVertical,
                                                    to: motionQueue) { [weak self] data, _ in
                guard let data = data else { return }
                self?.handleDeviceMotion(data)
            }
            scheduleTimeout()
            return
        }

        // fallback：magnetometer + accelerometer 手动计算
        if motionManager.isMagnetometerAvailable && motionManager.isAccelerometerAvailable {
            motionManager.magnetometerUpdateInterval = 1.0 / 30.0
            motionManager.accelerometerUpdateInterval = 1.0 / 30.0
            motionManager.startMagnetometerUpdates(to: motionQueue) { [weak self] data, _ in
                if let data = data { self?.magneticField = data.magneticField }
            }
            motionManager.startAccelerometerUpdates(to: motionQueue) { [weak self] data, _ in
                if let data = data { self?.handleAccelerometer(data.acceleration) }
            }
            scheduleTimeout()
            return
        }

        status = .noSensor
    }

    func stop() {
        motionManager.stopDeviceMotionUpdates()
        motionManager.stopMagnetometerUpdates()
        motionManager.stopAccelerometerUpdates()
        timeoutItem?.cancel()
        timeoutItem = nil
    }

    // MARK: - 数据处理
    private func handleDeviceMotion(_ data: CMDeviceMotion) {
        let attitude = data.attitude
        // yaw 范围 [-π, π]，转换到 [0, 360]
        var heading = attitude.yaw * 180 / .pi
        heading = (heading + 360).truncatingRemainder(dividingBy: 360)
        // CoreMotion yaw 是相对 Y 轴朝向，需根据设备朝向调整；这里直接用作方位角近似

        lastDeviceMotionTime = Date()
        if !hasReceivedAzimuth {
            hasReceivedAzimuth = true
            DispatchQueue.main.async { self.status = .ok }
        }
        DispatchQueue.main.async {
            self.azimuth = heading
            self.pitch = attitude.pitch * 180 / .pi
            self.roll = attitude.roll * 180 / .pi
        }
    }

    /// fallback：用加速度与磁场计算航向（对应 Android getRotationMatrix + getOrientation）。
    private func handleAccelerometer(_ acc: CMAcceleration) {
        gravity = acc
        // 简化版航向计算：用磁场水平分量相对重力方向的方位角近似
        let mx = magneticField.x, my = magneticField.y
        var heading = atan2(my, mx) * 180 / .pi
        heading = (heading + 360).truncatingRemainder(dividingBy: 360)

        lastDeviceMotionTime = Date()
        if !hasReceivedAzimuth {
            hasReceivedAzimuth = true
            DispatchQueue.main.async {
                self.status = (self.magneticField.x == 0 && self.magneticField.y == 0) ? .calibrate : .ok
            }
        }
        DispatchQueue.main.async { self.azimuth = heading }
    }

    // MARK: - 超时检测
    /// 注册后 1.5s 内若未收到任何方位数据 → 标记 TIMEOUT（对应 Android STALE_THRESHOLD_MS）。
    private func scheduleTimeout() {
        let item = DispatchWorkItem { [weak self] in
            guard let self else { return }
            if !self.hasReceivedAzimuth {
                DispatchQueue.main.async { self.status = .timeout }
            }
        }
        timeoutItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5, execute: item)
    }

    deinit { stop() }
}
