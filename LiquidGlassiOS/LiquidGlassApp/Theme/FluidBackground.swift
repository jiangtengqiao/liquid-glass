import SwiftUI

// ─────────────────────────────────────────────────────────────────
// 流体背景 —— 对应 Android 端 ui/GlassSurface.kt 中的 FluidBackground
//
// 5 层渲染管线：
//   1. 渐变色块（drawFluidBlobs）   6 个径向渐变圆，sin/cos 驱动漂浮
//   2. 光晕圆（drawGlowCircles）    4 个柔和大圆，缓慢游动
//   3. 涟漪线（drawFluidRipples）   水平细线波纹，模拟流体表面
//   4. 粒子系统（drawPhysicsParticles）25 个发光粒子
//   5. 水滴涟漪（drawDroplets）     触摸点扩散波纹
//
// 关键：所有运动用 sin/cos 周期函数驱动，使用真实经过时间（TimelineView 提供），
// 时间单调递增永不重启，sin/cos 自然连续，彻底消除动画割裂。
// 取模 1000π 仅为防浮点精度下降（非 2π，因 2π 取模会让 0.17 等非谐波频率跳变）。
// TimelineView(.animation) 驱动每帧重绘。
// ─────────────────────────────────────────────────────────────────

/// 水滴涟漪状态（对应 Android DropletState）。
struct DropletState: Identifiable {
    let id = UUID()
    let x: CGFloat        // 归一化坐标 [0,1]
    let y: CGFloat
    var progress: CGFloat  // 0→1 扩散进度
}

/// 流体背景视图。
struct FluidBackground: View {
    /// 外部传入的动画时间（与其它页面共享同一时钟，保证全局同步）。
    var animTime: Double = 0
    /// 当前主题（默认深色）。
    var theme: AppTheme = Themes.midnightDark
    /// 触摸产生的水滴涟漪。
    var droplets: [DropletState] = []

    var body: some View {
        TimelineView(.animation) { timeline in
            // 使用 TimelineView 的真实经过时间（单调递增永不重启），叠加外部 animTime。
            // 不对 2π 取模 —— 原 %2π 会让 0.17/0.25 等非谐波频率在 2π 边界跳变，
            // 造成动画割裂。真实时间连续 → sin/cos 自然连续，彻底无割裂。
            // 取模 1000π 防止长时间运行后浮点精度下降（1000π 是任意 0.001
            // 整数倍频率的整数周期，所有绘制频率均满足）。
            let t = combineTime(timeline.date.timeIntervalSinceReferenceDate, animTime)
            Canvas { context, size in
                drawFluidBlobs(in: context, size: size, time: t, theme: theme)
                drawGlowCircles(in: context, size: size, time: t, theme: theme)
                drawFluidRipples(in: context, size: size, time: t, theme: theme)
                drawPhysicsParticles(in: context, size: size, time: t, theme: theme)
                drawDroplets(in: context, size: size, droplets: droplets, theme: theme)
            }
        }
        .ignoresSafeArea()
        .background(theme.bgDark)
    }

    /// 合并时间值。取模 1000π（而非 2π）避免非谐波频率跳变。
    private func combineTime(_ timelineTime: Double, _ external: Double) -> Double {
        (timelineTime + external).truncatingRemainder(dividingBy: 1000 * .pi)
    }

    // MARK: - 第 1 层：渐变色块
    private func drawFluidBlobs(in context: GraphicsContext, size: CGSize, time t: Double, theme: AppTheme) {
        let w = size.width
        let h = size.height
        // 每个色块：(主色, 次色, 基础半径占比)
        // 深色主题用深色光斑，浅色主题用柔和浅色光斑（与 Android 一致）
        let blobDefs: [(Color, Color, CGFloat)] = theme.isLight ? [
            (theme.fluidBlue, theme.fluidCyan, 0.45),
            (theme.fluidPurple, theme.fluidPink, 0.40),
            (theme.fluidTeal, theme.fluidCyan, 0.48),
            (theme.fluidPink, theme.fluidPurple, 0.38),
            (theme.fluidCyan, theme.fluidTeal, 0.42),
            (theme.fluidOrange, theme.fluidPink, 0.35)
        ] : [
            (Color(hex: 0x1A1040), Color(hex: 0x002040), 0.45),
            (Color(hex: 0x0D2040), Color(hex: 0x1A0A30), 0.40),
            (Color(hex: 0x102040), Color(hex: 0x0D1030), 0.48),
            (Color(hex: 0x0A1530), Color(hex: 0x150A30), 0.38),
            (Color(hex: 0x0D1A35), Color(hex: 0x100A35), 0.42),
            (Color(hex: 0x0F0D28), Color(hex: 0x0A1535), 0.35)
        ]

        for (i, def) in blobDefs.enumerated() {
            let (c1, c2, baseR) = def
            // sin/cos 驱动位置漂浮，周期 2π 内连续
            let cx = w * (0.2 + 0.20 * CGFloat(sin(t * 0.15 + Double(i) * 1.4)))
            let cy = h * (0.2 + 0.20 * CGFloat(cos(t * 0.17 + Double(i) * 1.8)))
            let radius = max(60, w * baseR + w * 0.08 * CGFloat(sin(t * 0.25 + Double(i))))

            let rect = CGRect(x: cx - radius, y: cy - radius, width: radius * 2, height: radius * 2)
            let gradient = RadialGradient(
                colors: [
                    c1.opacity(theme.isLight ? 0.22 : 0.15),
                    c2.opacity(theme.isLight ? 0.10 : 0.06),
                    Color.clear
                ],
                center: .center,
                startRadius: 0,
                endRadius: radius
            )
            context.fill(Path(ellipseIn: rect), with: gradient)
        }
    }

    // MARK: - 第 2 层：光晕圆
    private func drawGlowCircles(in context: GraphicsContext, size: CGSize, time t: Double, theme: AppTheme) {
        let w = size.width
        let h = size.height
        let colors = [theme.fluidCyan, theme.fluidPurple, theme.fluidTeal, theme.fluidPink]
        for i in 0..<4 {
            let phase = Double(i) * 1.8
            let cx = w * (0.5 + 0.3 * CGFloat(sin(t * 0.12 + phase)))
            let cy = h * (0.5 + 0.3 * CGFloat(cos(t * 0.14 + phase * 1.2)))
            let radius = w * (0.12 + 0.04 * CGFloat(sin(t * 0.2 + phase)))
            let rect = CGRect(x: cx - radius, y: cy - radius, width: radius * 2, height: radius * 2)
            let gradient = RadialGradient(
                colors: [
                    colors[i].opacity(theme.isLight ? 0.16 : 0.08),
                    colors[i].opacity(theme.isLight ? 0.05 : 0.02),
                    Color.clear
                ],
                center: .center,
                startRadius: 0,
                endRadius: radius
            )
            context.fill(Path(ellipseIn: rect), with: gradient)
        }
    }

    // MARK: - 第 3 层：涟漪线（水平细线波纹）
    private func drawFluidRipples(in context: GraphicsContext, size: CGSize, time t: Double, theme: AppTheme) {
        let w = size.width
        let h = size.height
        // 浅色主题用深色细线，深色主题用白色细线
        let lineColor = theme.isLight ? Color.black : Color.white
        for i in 0..<4 {
            let phase = Double(i) * 1.5
            let speed = 0.08 + Double(i) * 0.04
            let step: CGFloat = 3
            var y: CGFloat = 0
            while y < h {
                let yf = Double(y / h)
                let offset = CGFloat(sin(yf * 5 + t * speed + phase) * Double(w) * 0.07 +
                                     cos(yf * 3 - t * speed * 0.5) * Double(w) * 0.04)
                let alpha = max(0, min(0.04, 0.018 + 0.01 * sin(yf * 3 + t * speed * 0.7)))
                var path = Path()
                path.move(to: CGPoint(x: offset, y: y))
                path.addLine(to: CGPoint(x: w + offset * 0.3, y: y))
                context.stroke(path, with: lineColor.opacity(alpha), lineWidth: 1.5)
                y += step
            }
        }
    }

    // MARK: - 第 4 层：物理粒子系统（25 个发光粒子）
    private func drawPhysicsParticles(in context: GraphicsContext, size: CGSize, time t: Double, theme: AppTheme) {
        let w = size.width
        let h = size.height
        let particleColors = [theme.fluidCyan, theme.fluidPurple, theme.fluidTeal, theme.fluidBlue, theme.fluidPink]
        for i in 0..<25 {
            let seed = Double(i) * 127.1
            // 基础位置由正弦场驱动
            let baseX = CGFloat(sin(t * 0.3 + seed) * 0.5 + 0.5)
            let baseY = CGFloat(cos(t * 0.35 + seed * 1.3) * 0.5 + 0.5)
            let px = baseX * w
            let py = baseY * h
            let alpha = max(0, min(0.10, 0.05 + 0.05 * sin(t * 0.5 + seed * 0.7)))
            let ci = i % particleColors.count

            // 粒子大小随速度变化
            let velocity = abs(sin(t * 0.6 + seed)) + abs(cos(t * 0.4 + seed * 1.1))
            let radius = 2.5 + velocity * 2.5

            // 发光效果（双层）
            let glowRect = CGRect(x: px - radius * 2, y: py - radius * 2, width: radius * 4, height: radius * 4)
            context.fill(Path(ellipseIn: glowRect), with: particleColors[ci].opacity(alpha * 0.3))
            let coreRect = CGRect(x: px - radius, y: py - radius, width: radius * 2, height: radius * 2)
            context.fill(Path(ellipseIn: coreRect), with: particleColors[ci].opacity(alpha))
        }
    }

    // MARK: - 第 5 层：水滴涟漪（触摸点扩散波）
    private func drawDroplets(in context: GraphicsContext, size: CGSize, droplets: [DropletState], theme: AppTheme) {
        let w = size.width
        let h = size.height
        for droplet in droplets {
            let progress = droplet.progress
            let alpha = max(0, min(1, 1 - progress)) * 0.8
            let maxR = max(w, h) * 0.5
            let radius = progress * maxR
            let cx = droplet.x * w
            let cy = droplet.y * h

            // 主涟漪
            context.stroke(
                Path(ellipseIn: CGRect(x: cx - radius, y: cy - radius, width: radius * 2, height: radius * 2)),
                with: theme.fluidCyan.opacity(alpha),
                lineWidth: 3
            )
            // 二次波
            let r2 = radius * 0.6
            context.stroke(
                Path(ellipseIn: CGRect(x: cx - r2, y: cy - r2, width: r2 * 2, height: r2 * 2)),
                with: theme.fluidPurple.opacity(alpha * 0.7),
                lineWidth: 2.5
            )
            // 三次波
            let r3 = radius * 0.3
            context.stroke(
                Path(ellipseIn: CGRect(x: cx - r3, y: cy - r3, width: r3 * 2, height: r3 * 2)),
                with: theme.fluidTeal.opacity(alpha * 0.4),
                lineWidth: 2
            )
            // 中心水滴亮点
            context.fill(
                Path(ellipseIn: CGRect(x: cx - 6, y: cy - 6, width: 12, height: 12)),
                with: Color.white.opacity(alpha * 0.7)
            )
        }
    }
}

// MARK: - 水滴涟漪动画器（对应 Android DropletAnimator）
/// 在触摸点添加一个扩散涟漪，1.25 秒内完成扩散后自动移除。
final class DropletAnimator: ObservableObject {
    @Published private(set) var droplets: [DropletState] = []

    func addDroplet(at point: CGPoint, in size: CGSize) {
        guard size.width > 0, size.height > 0 else { return }
        let droplet = DropletState(
            x: point.x / size.width,
            y: point.y / size.height,
            progress: 0
        )
        droplets.append(droplet)
        // 用 eased 曲线推进进度，2π 周期内连续
        let id = droplet.id
        Task { @MainActor in
            let steps = 50
            for frame in 1...steps {
                let progress = CGFloat(frame) / CGFloat(steps)
                let eased = 1 - (1 - progress) * (1 - progress) // easeOutQuad
                if let idx = self.droplets.firstIndex(where: { $0.id == id }) {
                    self.droplets[idx].progress = eased
                }
                try? await Task.sleep(nanoseconds: 25_000_000) // 25ms
            }
            self.droplets.removeAll { $0.id == id }
        }
    }
}
