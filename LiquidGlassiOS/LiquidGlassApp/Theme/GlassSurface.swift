import SwiftUI

// ─────────────────────────────────────────────────────────────────
// 液态玻璃质感容器 —— 对应 Android 端 ui/GlassSurface.kt 的 glassSurface Modifier
//
// 8 层渲染管线（与 Android 完全对应）：
//   1. 玻璃基底
//   2. 发光边框（3 条嵌套）
//   3. 顶部强反射高光
//   4. 左上角斜向高光
//   5. 顶部弧形高光条
//   6. 底部边缘环境光
//   7. 浮动光斑
//   8. 彩虹色散折射（菲涅尔效应）
//
// 用 ViewModifier 实现，调用方式：view.glassSurface(cornerRadius: 24)
// 主题感知：深色主题用白色着色，浅色主题用深色着色，保证玻璃卡片与背景对比度。
// ─────────────────────────────────────────────────────────────────

struct GlassSurface: ViewModifier {
    var cornerRadius: CGFloat = 24
    var glassAlpha: Double = 0.22
    var showBorder: Bool = true
    /// 按压深度 [0,1]，驱动色散增强与边缘发光（物理弹性形变）。
    var pressDepth: Double = 0
    var theme: AppTheme = Themes.midnightDark

    func body(content: Content) -> some View {
        // 主题感知着色
        let tint: Color = theme.isLight ? .black : .white
        let tintAlphaMul: Double = theme.isLight ? 0.5 : 1.0
        let adjustedAlpha = glassAlpha * (1 - pressDepth * 0.15)
        let dispersionBoost = 1 + pressDepth * 0.5
        let edgeGlow = pressDepth * 0.15

        content
            .background(
                // 第 1 层：玻璃基底填充
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(tint.opacity(adjustedAlpha * 0.6 * tintAlphaMul))
            )
            .background(
                // 第 2~8 层装饰，用 Canvas 在裁剪区域内绘制
                Canvas { context, size in
                    drawBorders(in: context, size: size, tint: tint, tintAlphaMul: tintAlphaMul,
                                edgeGlow: edgeGlow, theme: theme)
                    drawTopHighlight(in: context, size: size, tint: tint, tintAlphaMul: tintAlphaMul, theme: theme)
                    drawCornerHighlight(in: context, size: size, tint: tint, tintAlphaMul: tintAlphaMul, theme: theme)
                    drawArcHighlight(in: context, size: size, tint: tint, tintAlphaMul: tintAlphaMul, theme: theme)
                    drawBottomAmbient(in: context, size: size, tint: tint, tintAlphaMul: tintAlphaMul, cornerRadius: cornerRadius)
                    drawLightSpots(in: context, size: size, tint: tint, tintAlphaMul: tintAlphaMul,
                                   cornerRadius: cornerRadius, pressDepth: pressDepth)
                    drawDispersion(in: context, size: size, theme: theme,
                                   dispersionBoost: dispersionBoost, cornerRadius: cornerRadius, pressDepth: pressDepth)
                }
            )
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    }

    // MARK: - 第 2 层：发光边框（3 条嵌套）
    private func drawBorders(in context: GraphicsContext, size: CGSize,
                             tint: Color, tintAlphaMul: Double, edgeGlow: Double, theme: AppTheme) {
        guard showBorder else { return }
        let w = size.width
        let h = size.height
        let cr = cornerRadius
        let borderColor: Color = theme.isLight ? theme.glassBorder : .white

        let outer = Path(roundedRect: CGRect(x: 0.75, y: 0.75, width: w - 1.5, height: h - 1.5),
                         cornerRadius: cr)
        context.stroke(outer, with: borderColor.opacity((theme.isLight ? 0.40 : 0.30) + edgeGlow), lineWidth: 1.5)

        let mid = Path(roundedRect: CGRect(x: 2.25, y: 2.25, width: w - 4.5, height: h - 4.5),
                       cornerRadius: max(0, cr - 1.5))
        context.stroke(mid, with: borderColor.opacity((theme.isLight ? 0.25 : 0.20) + edgeGlow * 0.5), lineWidth: 1.0)

        let inner = Path(roundedRect: CGRect(x: 3.3, y: 3.3, width: w - 6.6, height: h - 6.6),
                         cornerRadius: max(0, cr - 3))
        context.stroke(inner, with: borderColor.opacity(0.10), lineWidth: 0.6)
    }

    // MARK: - 第 3 层：顶部强反射高光
    private func drawTopHighlight(in context: GraphicsContext, size: CGSize,
                                  tint: Color, tintAlphaMul: Double, theme: AppTheme) {
        let w = size.width
        let h = size.height
        let cr = cornerRadius
        var path = Path()
        path.move(to: CGPoint(x: cr * 0.3, y: 0))
        path.addLine(to: CGPoint(x: w * 0.85, y: 0))
        path.addLine(to: CGPoint(x: w * 0.6, y: h * 0.09))
        path.addLine(to: CGPoint(x: cr * 0.2, y: h * 0.05))
        path.closeSubpath()
        let gradient = LinearGradient(
            colors: [
                tint.opacity((theme.isLight ? 0.25 : 0.50) * tintAlphaMul),
                tint.opacity(0.08 * tintAlphaMul),
                tint.opacity(0)
            ],
            startPoint: .init(x: 0, y: 0),
            endPoint: .init(x: 0, y: 0.14)
        )
        context.fill(path, with: gradient)
    }

    // MARK: - 第 4 层：左上角斜向高光
    private func drawCornerHighlight(in context: GraphicsContext, size: CGSize,
                                     tint: Color, tintAlphaMul: Double, theme: AppTheme) {
        let w = size.width
        let h = size.height
        var path = Path()
        path.move(to: CGPoint(x: 0, y: 0))
        path.addLine(to: CGPoint(x: w * 0.38, y: 0))
        path.addLine(to: CGPoint(x: 0, y: h * 0.38))
        path.closeSubpath()
        let gradient = LinearGradient(
            colors: [
                tint.opacity((theme.isLight ? 0.18 : 0.35) * tintAlphaMul),
                tint.opacity(0.03 * tintAlphaMul),
                tint.opacity(0)
            ],
            startPoint: .init(x: 0, y: 0),
            endPoint: .init(x: 0.35, y: 0.35)
        )
        context.fill(path, with: gradient)
    }

    // MARK: - 第 5 层：顶部弧形高光条
    private func drawArcHighlight(in context: GraphicsContext, size: CGSize,
                                  tint: Color, tintAlphaMul: Double, theme: AppTheme) {
        let w = size.width
        let cr = cornerRadius
        let arcY = cr * 0.12
        var path = Path()
        path.move(to: CGPoint(x: cr * 0.8, y: arcY))
        path.addCurve(to: CGPoint(x: w - cr * 0.8, y: arcY),
                      control1: CGPoint(x: w * 0.25, y: arcY - cr * 0.12),
                      control2: CGPoint(x: w * 0.75, y: arcY - cr * 0.12))
        path.addLine(to: CGPoint(x: w - cr * 0.8, y: arcY + cr * 0.07))
        path.addCurve(to: CGPoint(x: cr * 0.8, y: arcY + cr * 0.07),
                      control1: CGPoint(x: w * 0.75, y: arcY + cr * 0.07),
                      control2: CGPoint(x: w * 0.25, y: arcY + cr * 0.07))
        path.closeSubpath()
        let gradient = LinearGradient(
            colors: [
                tint.opacity(0),
                tint.opacity((theme.isLight ? 0.12 : 0.25) * tintAlphaMul),
                tint.opacity((theme.isLight ? 0.20 : 0.40) * tintAlphaMul),
                tint.opacity((theme.isLight ? 0.12 : 0.25) * tintAlphaMul),
                tint.opacity(0)
            ],
            startPoint: .init(x: 0, y: 0),
            endPoint: .init(x: 1, y: 0)
        )
        context.fill(path, with: gradient)
    }

    // MARK: - 第 6 层：底部边缘环境光
    private func drawBottomAmbient(in context: GraphicsContext, size: CGSize,
                                   tint: Color, tintAlphaMul: Double, cornerRadius: CGFloat) {
        let w = size.width
        let h = size.height
        let rect = CGRect(x: 0, y: h * 0.80, width: w, height: h * 0.20)
        let path = Path(roundedRect: CGRect(x: 0, y: 0, width: w, height: h), cornerRadius: cornerRadius)
        context.clip(to: path)  // 裁剪到圆角矩形，避免底部光溢出
        let gradient = LinearGradient(
            colors: [tint.opacity(0.12 * tintAlphaMul), tint.opacity(0)],
            startPoint: .init(x: 0, y: 1),
            endPoint: .init(x: 0, y: 0.80)
        )
        context.fill(Path(rect), with: gradient)
    }

    // MARK: - 第 7 层：浮动光斑（按压时位置微偏）
    private func drawLightSpots(in context: GraphicsContext, size: CGSize,
                                tint: Color, tintAlphaMul: Double,
                                cornerRadius: CGFloat, pressDepth: Double) {
        let w = size.width
        let h = size.height
        let cr = cornerRadius
        let spotOffsetX = pressDepth * 3

        // 右上角光斑（双层）
        let c1 = CGPoint(x: w - cr * 0.6 + spotOffsetX, y: cr * 0.5)
        context.fill(Path(ellipseIn: CGRect(x: c1.x - cr * 0.3, y: c1.y - cr * 0.3,
                                             width: cr * 0.6, height: cr * 0.6)),
                     with: tint.opacity(0.18 * tintAlphaMul))
        context.fill(Path(ellipseIn: CGRect(x: c1.x - cr * 0.5, y: c1.y - cr * 0.5,
                                             width: cr, height: cr)),
                     with: tint.opacity(0.10 * tintAlphaMul))
        // 左下角光斑
        let c2 = CGPoint(x: cr * 0.8 - spotOffsetX, y: h - cr * 0.8)
        context.fill(Path(ellipseIn: CGRect(x: c2.x - cr * 0.2, y: c2.y - cr * 0.2,
                                             width: cr * 0.4, height: cr * 0.4)),
                     with: tint.opacity(0.10 * tintAlphaMul))
    }

    // MARK: - 第 8 层：彩虹色散折射（菲涅尔效应）
    private func drawDispersion(in context: GraphicsContext, size: CGSize,
                                theme: AppTheme, dispersionBoost: Double,
                                cornerRadius: CGFloat, pressDepth: Double) {
        let w = size.width
        let h = size.height
        let cr = cornerRadius
        let fresnelBoost = fresnel(0.3 + pressDepth * 0.2)

        let outer = Path(roundedRect: CGRect(x: 0.75, y: 0.75, width: w - 1.5, height: h - 1.5), cornerRadius: cr)
        context.stroke(outer, with: theme.fluidCyan.opacity(0.06 * dispersionBoost + fresnelBoost * 0.02), lineWidth: 1.5)

        let mid = Path(roundedRect: CGRect(x: 1.25, y: 1.25, width: w - 2.5, height: h - 2.5), cornerRadius: max(0, cr - 0.5))
        context.stroke(mid, with: theme.fluidPurple.opacity(0.05 * dispersionBoost), lineWidth: 1.0)

        let inner = Path(roundedRect: CGRect(x: 1.75, y: 1.75, width: w - 3.5, height: h - 3.5), cornerRadius: max(0, cr - 1))
        context.stroke(inner, with: theme.fluidPink.opacity(0.03 * dispersionBoost), lineWidth: 0.6)
    }

    /// 菲涅尔近似：入射角越大反射越强（对应 Android FluidEngine.fresnel）。
    private func fresnel(_ cosTheta: Double) -> Double {
        let c = max(0, min(1, cosTheta))
        return pow(1 - c, 3)
    }
}

// MARK: - View 扩展：便捷调用
extension View {
    /// 应用液态玻璃质感容器。
    func glassSurface(cornerRadius: CGFloat = 24,
                     glassAlpha: Double = 0.22,
                     showBorder: Bool = true,
                     pressDepth: Double = 0,
                     theme: AppTheme = Themes.midnightDark) -> some View {
        modifier(GlassSurface(cornerRadius: cornerRadius,
                              glassAlpha: glassAlpha,
                              showBorder: showBorder,
                              pressDepth: pressDepth,
                              theme: theme))
    }
}

// MARK: - 物理弹簧按压状态管理（对应 Android PressPhysics）
/// 用阻尼弹簧驱动按压深度，松手后弹性回弹。
final class PressPhysics: ObservableObject {
    @Published private(set) var pressDepth: Double = 0
    private var velocity: Double = 0

    /// 每帧更新（dt 秒）。tension 越大越硬，friction 越大越阻尼。
    func update(pressed: Bool, dt: Double = 0.016) {
        let target: Double = pressed ? 1 : 0
        let tension = 200.0
        let friction = 18.0
        // 半隐式欧拉积分阻尼弹簧
        let force = -tension * (pressDepth - target) - friction * velocity
        velocity += force * dt
        pressDepth += velocity * dt
        // 钳制避免数值溢出
        pressDepth = max(0, min(1.2, pressDepth))
    }
}
