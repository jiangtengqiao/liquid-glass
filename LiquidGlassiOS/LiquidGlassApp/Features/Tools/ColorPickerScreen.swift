import SwiftUI
import UIKit

// ─────────────────────────────────────────────────────────────────
// 取色器 —— 对应 Android 端 ui/ColorPickerScreen.kt
//
// 关键实现：
//   1. RGB 为颜色真值（red/green/blue/alpha），HSB 由 UIColor 实时换算；
//      拖动 HSB 滑块时反向换算回 RGB（UIColor(hue:saturation:brightness:)），
//      避免 RGB↔HSB 双向同步的状态撕裂。
//   2. HEX 输入框：支持 #RRGGBB / #RRGGBBAA，点击「应用」解析并同步所有滑块。
//   3. 同时提供原生 ColorPicker 与手动 RGB/HSB 滑块（对应 Android 的细粒度控制）。
//   4. 复制 HEX：UIPasteboard.general.string；收藏历史：UserDefaults 持久化。
//   5. 历史记录去重 + 最多 30 条，可点击回填、复制、删除。
// ─────────────────────────────────────────────────────────────────

/// 持久化键：已收藏颜色列表。
private let kSavedColorsKey = "liquid_glass_saved_colors"

// MARK: - 已保存颜色模型
struct SavedColor: Identifiable, Codable {
    let id: UUID
    var hex: String
    var timestamp: Date

    init(id: UUID = UUID(), hex: String, timestamp: Date = Date()) {
        self.id = id
        self.hex = hex
        self.timestamp = timestamp
    }
}

// MARK: - 颜色转换工具
/// 将 HEX 字符串解析为 SwiftUI Color（支持 #RRGGBB / #RRGGBBAA / RRGGBB 三种形式）。
private func hexToColor(_ hex: String) -> Color? {
    var s = hex.trimmingCharacters(in: .whitespacesAndNewlines)
    if s.hasPrefix("#") { s.removeFirst() }
    guard let v = UInt32(s, radix: 16) else { return nil }
    switch s.count {
    case 6:
        let r = Double((v >> 16) & 0xFF) / 255.0
        let g = Double((v >> 8) & 0xFF) / 255.0
        let b = Double(v & 0xFF) / 255.0
        return Color(.sRGB, red: r, green: g, blue: b)
    case 8:
        let a = Double((v >> 24) & 0xFF) / 255.0
        let r = Double((v >> 16) & 0xFF) / 255.0
        let g = Double((v >> 8) & 0xFF) / 255.0
        let b = Double(v & 0xFF) / 255.0
        return Color(.sRGB, red: r, green: g, blue: b, opacity: a)
    default:
        return nil
    }
}

/// 将 SwiftUI Color 转为 HEX 字符串（不透明时 6 位，含透明度时 8 位）。
private func colorToHex(_ color: Color) -> String {
    let ui = UIColor(color)
    var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
    ui.getRed(&r, green: &g, blue: &b, alpha: &a)
    let ri = Int(round(r * 255))
    let gi = Int(round(g * 255))
    let bi = Int(round(b * 255))
    if a < 1.0 {
        let ai = Int(round(a * 255))
        return String(format: "#%02X%02X%02X%02X", ai, ri, gi, bi)
    }
    return String(format: "#%02X%02X%02X", ri, gi, bi)
}

// MARK: - 视图模型
final class ColorPickerViewModel: ObservableObject {
    /// RGB 真值（0-255），alpha 为 0-1。
    @Published var red: Double = 255
    @Published var green: Double = 0
    @Published var blue: Double = 0
    @Published var alpha: Double = 1
    @Published var hexInput: String = "#FF0000"
    @Published var history: [SavedColor] = []
    @Published var toastText: String = ""
    @Published var showToast: Bool = false

    var color: Color {
        Color(.sRGB,
              red: red / 255.0,
              green: green / 255.0,
              blue: blue / 255.0,
              opacity: alpha)
    }

    var hex: String { colorToHex(color) }

    /// 当前颜色对应的 HSB（hue:0-1, saturation:0-1, brightness:0-1）。
    var hsb: (h: Double, s: Double, b: Double) {
        var h: CGFloat = 0, s: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(color).getHue(&h, saturation: &s, brightness: &b, alpha: &a)
        return (Double(h), Double(s), Double(b))
    }

    // MARK: RGB 直接设置
    func setRed(_ v: Double)   { red = v; hexInput = hex }
    func setGreen(_ v: Double) { green = v; hexInput = hex }
    func setBlue(_ v: Double)  { blue = v; hexInput = hex }
    func setAlpha(_ v: Double) { alpha = v; hexInput = hex }

    // MARK: HSB 反向同步（保持当前 s/b 或 h/s，仅改目标通道）
    func setHue(_ h: Double) {
        let cur = hsb
        applyUIColor(UIColor(hue: CGFloat(h),
                             saturation: CGFloat(cur.s),
                             brightness: CGFloat(cur.b),
                             alpha: CGFloat(alpha)))
    }

    func setSaturation(_ s: Double) {
        let cur = hsb
        applyUIColor(UIColor(hue: CGFloat(cur.h),
                             saturation: CGFloat(s),
                             brightness: CGFloat(cur.b),
                             alpha: CGFloat(alpha)))
    }

    func setBrightness(_ b: Double) {
        let cur = hsb
        applyUIColor(UIColor(hue: CGFloat(cur.h),
                             saturation: CGFloat(cur.s),
                             brightness: CGFloat(b),
                             alpha: CGFloat(alpha)))
    }

    /// 原生 ColorPicker 选色后同步 RGB（含 alpha）。
    func setColor(_ c: Color) {
        applyUIColor(UIColor(c))
    }

    /// 从 hex 输入框解析并应用颜色。
    func applyHex() {
        if let c = hexToColor(hexInput) {
            applyUIColor(UIColor(c))
            presentToast("已应用 \(hex)")
        } else {
            presentToast("无效的 HEX")
        }
    }

    /// 用 UIColor 同步 RGB + alpha + hexInput。
    private func applyUIColor(_ ui: UIColor) {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        if ui.getRed(&r, green: &g, blue: &b, alpha: &a) {
            red = Double(r) * 255
            green = Double(g) * 255
            blue = Double(b) * 255
            alpha = Double(a)
        }
        hexInput = hex
    }

    // MARK: 剪贴板（UIPasteboard.general.string）
    /// 复制指定 HEX（默认当前颜色）到剪贴板。
    func copyHex(_ hex: String? = nil) {
        let text = hex ?? self.hex
        UIPasteboard.general.string = text
        presentToast("已复制 \(text)")
    }

    // MARK: 历史记录（UserDefaults）
    func loadHistory() {
        if let saved = Persistence.shared.object([SavedColor].self, for: kSavedColorsKey) {
            history = saved
        }
    }

    func saveToHistory() {
        let item = SavedColor(hex: hex)
        history.removeAll { $0.hex == item.hex }
        history.insert(item, at: 0)
        if history.count > 30 { history = Array(history.prefix(30)) }
        persistHistory()
        presentToast("已收藏 \(hex)")
    }

    func deleteFromHistory(_ item: SavedColor) {
        history.removeAll { $0.id == item.id }
        persistHistory()
    }

    func applyHistory(_ item: SavedColor) {
        if let c = hexToColor(item.hex) {
            applyUIColor(UIColor(c))
            presentToast("已回填 \(item.hex)")
        }
    }

    private func persistHistory() {
        Persistence.shared.setObject(history, for: kSavedColorsKey)
    }

    private func presentToast(_ text: String) {
        toastText = text
        withAnimation { showToast = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            withAnimation { showToast = false }
        }
    }
}

// MARK: - 主视图
struct ColorPickerScreen: View {
    var onBack: () -> Void

    @StateObject private var vm = ColorPickerViewModel()

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: 0, theme: theme)

            VStack(spacing: 16) {
                topBar(theme: theme)

                ScrollView {
                    VStack(spacing: 16) {
                        previewCard(theme: theme)
                        hexInputCard(theme: theme)
                        rgbSlidersCard(theme: theme)
                        hsbSlidersCard(theme: theme)
                        nativePickerCard(theme: theme)
                        if !vm.history.isEmpty {
                            historySection(theme: theme)
                        }
                    }
                    .padding(.bottom, 24)
                }
            }
            .padding(.top, 50)
            .padding(.horizontal, 20)
        }
        .onAppear { vm.loadHistory() }
        .overlay(alignment: .bottom) {
            if vm.showToast {
                ToastView(text: vm.toastText, theme: theme)
                    .padding(.bottom, 60)
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: vm.showToast)
    }

    // MARK: - 顶部栏
    private func topBar(theme: AppTheme) -> some View {
        HStack {
            Button { onBack() } label: {
                Image(systemName: "chevron.left").foregroundStyle(theme.textSecondary)
            }
            Text("取色器").font(.headline).foregroundStyle(theme.textPrimary)
            Spacer()
        }
    }

    // MARK: - 颜色预览卡
    private func previewCard(theme: AppTheme) -> some View {
        VStack(spacing: 12) {
            // 颜色块（带透明度棋盘底，便于查看 alpha）
            ZStack {
                CheckerboardBackground()
                vm.color
            }
            .frame(height: 100)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(Color.white.opacity(0.2), lineWidth: 1)
            )

            // HEX + RGB 文本
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(vm.hex)
                        .font(.title3.weight(.bold).monospaced())
                        .foregroundStyle(theme.textPrimary)
                    Text("R:\(Int(vm.red))  G:\(Int(vm.green))  B:\(Int(vm.blue))  A:\(String(format: "%.2f", vm.alpha))")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(theme.textSecondary)
                }
                Spacer()
            }

            // 操作按钮
            HStack(spacing: 10) {
                actionButton(title: "复制 HEX", icon: "doc.on.doc",
                             color: theme.fluidCyan, theme: theme) {
                    vm.copyHex()
                }
                actionButton(title: "收藏", icon: "heart.fill",
                             color: theme.fluidPink, theme: theme) {
                    vm.saveToHistory()
                }
            }
        }
        .padding(16)
        .glassSurface(cornerRadius: 24, glassAlpha: 0.15, theme: theme)
    }

    private func actionButton(title: String, icon: String, color: Color,
                              theme: AppTheme, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.title3)
                Text(title).font(.caption)
            }
            .foregroundStyle(color)
            .frame(maxWidth: .infinity)
            .frame(height: 64)
            .glassSurface(cornerRadius: 16, glassAlpha: 0.15, theme: theme)
        }
        .buttonStyle(.plain)
    }

    // MARK: - HEX 输入卡
    private func hexInputCard(theme: AppTheme) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("HEX 输入").font(.subheadline).foregroundStyle(theme.textSecondary)
            HStack(spacing: 10) {
                TextField("#RRGGBB", text: $vm.hexInput)
                    .font(.body.monospaced())
                    .foregroundStyle(theme.textPrimary)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .glassSurface(cornerRadius: 12, glassAlpha: 0.10, theme: theme)

                Button {
                    vm.applyHex()
                } label: {
                    Text("应用")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(theme.bgDark)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 10)
                        .background(theme.fluidCyan, in: Capsule())
                }
            }
            Text("支持 #RRGGBB 或 #RRGGBBAA 格式")
                .font(.caption2)
                .foregroundStyle(theme.textTertiary)
        }
        .padding(16)
        .glassSurface(cornerRadius: 20, glassAlpha: 0.12, theme: theme)
    }

    // MARK: - RGB 滑块卡
    // 用自定义 Binding 走 setter，确保拖动滑块时同步刷新 HEX 输入框。
    private func rgbSlidersCard(theme: AppTheme) -> some View {
        let redBinding   = Binding<Double>(get: { vm.red   }, set: { vm.setRed($0)   })
        let greenBinding = Binding<Double>(get: { vm.green }, set: { vm.setGreen($0) })
        let blueBinding  = Binding<Double>(get: { vm.blue  }, set: { vm.setBlue($0)  })
        let alphaBinding = Binding<Double>(get: { vm.alpha }, set: { vm.setAlpha($0) })
        return VStack(alignment: .leading, spacing: 14) {
            Text("RGB").font(.subheadline).foregroundStyle(theme.textSecondary)
            ChannelSlider(label: "R", value: redBinding, range: 0...255,
                          tint: .red, theme: theme)
            ChannelSlider(label: "G", value: greenBinding, range: 0...255,
                          tint: .green, theme: theme)
            ChannelSlider(label: "B", value: blueBinding, range: 0...255,
                          tint: .blue, theme: theme)
            ChannelSlider(label: "A", value: alphaBinding, range: 0...1,
                          tint: vm.color, theme: theme, isInteger: false)
        }
        .padding(16)
        .glassSurface(cornerRadius: 20, glassAlpha: 0.12, theme: theme)
    }

    // MARK: - HSB 滑块卡（值由 RGB 反向换算，用自定义 Binding）
    private func hsbSlidersCard(theme: AppTheme) -> some View {
        let hueBinding = Binding<Double>(
            get: { vm.hsb.h * 360.0 },
            set: { vm.setHue($0 / 360.0) }
        )
        let satBinding = Binding<Double>(
            get: { vm.hsb.s * 100.0 },
            set: { vm.setSaturation($0 / 100.0) }
        )
        let briBinding = Binding<Double>(
            get: { vm.hsb.b * 100.0 },
            set: { vm.setBrightness($0 / 100.0) }
        )
        return VStack(alignment: .leading, spacing: 14) {
            Text("HSB").font(.subheadline).foregroundStyle(theme.textSecondary)
            ChannelSlider(label: "H", value: hueBinding, range: 0...360,
                          tint: vm.color, theme: theme, suffix: "°")
            ChannelSlider(label: "S", value: satBinding, range: 0...100,
                          tint: vm.color, theme: theme, suffix: "%")
            ChannelSlider(label: "B", value: briBinding, range: 0...100,
                          tint: vm.color, theme: theme, suffix: "%")
        }
        .padding(16)
        .glassSurface(cornerRadius: 20, glassAlpha: 0.12, theme: theme)
    }

    // MARK: - 原生 ColorPicker 卡
    private func nativePickerCard(theme: AppTheme) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("系统取色器").font(.subheadline).foregroundStyle(theme.textSecondary)
            ColorPicker(
                "选择颜色",
                selection: Binding(
                    get: { vm.color },
                    set: { vm.setColor($0) }
                ),
                supportsOpacity: true
            )
            .font(.body)
            .foregroundStyle(theme.textPrimary)
        }
        .padding(16)
        .glassSurface(cornerRadius: 20, glassAlpha: 0.12, theme: theme)
    }

    // MARK: - 历史记录
    private func historySection(theme: AppTheme) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: "clock.arrow.circlepath").foregroundStyle(theme.fluidCyan)
                Text("收藏记录").font(.headline).foregroundStyle(theme.textPrimary)
                Spacer()
                Text("\(vm.history.count)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(theme.textTertiary)
            }
            .padding(.horizontal, 4)

            ForEach(vm.history) { item in
                HStack(spacing: 12) {
                    Button {
                        vm.applyHistory(item)
                    } label: {
                        ZStack {
                            CheckerboardBackground()
                            if let c = hexToColor(item.hex) { c }
                        }
                        .frame(width: 40, height: 40)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .overlay(RoundedRectangle(cornerRadius: 8)
                            .stroke(Color.white.opacity(0.2), lineWidth: 1))
                    }
                    .buttonStyle(.plain)

                    Text(item.hex)
                        .font(.subheadline.monospaced())
                        .foregroundStyle(theme.textPrimary)
                    Spacer()
                    Button {
                        vm.copyHex(item.hex)
                    } label: {
                        Image(systemName: "doc.on.doc")
                            .foregroundStyle(theme.textTertiary)
                    }
                    .buttonStyle(.plain)
                    Button {
                        vm.deleteFromHistory(item)
                    } label: {
                        Image(systemName: "trash")
                            .foregroundStyle(AccentDanger)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .glassSurface(cornerRadius: 14, glassAlpha: 0.10, theme: theme)
            }
        }
    }
}

// MARK: - 通道滑块
private struct ChannelSlider: View {
    let label: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    let tint: Color
    let theme: AppTheme
    var suffix: String = ""
    var isInteger: Bool = true

    var body: some View {
        VStack(spacing: 6) {
            HStack {
                Text(label)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(theme.textSecondary)
                Spacer()
                Text(displayText)
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(theme.textSecondary)
            }
            Slider(value: $value, in: range)
                .tint(tint)
        }
    }

    private var displayText: String {
        isInteger ? "\(Int(value))\(suffix)" : String(format: "%.2f\(suffix)", value)
    }
}

// MARK: - 透明度棋盘背景（便于查看带 alpha 的颜色）
private struct CheckerboardBackground: View {
    let size: CGFloat = 8

    var body: some View {
        Canvas { context, canvasSize in
            let rows = Int(canvasSize.height / size) + 1
            let cols = Int(canvasSize.width / size) + 1
            for r in 0..<rows {
                for c in 0..<cols {
                    let isLight = (r + c) % 2 == 0
                    let rect = CGRect(x: CGFloat(c) * size,
                                      y: CGFloat(r) * size,
                                      width: size, height: size)
                    context.fill(Path(rect),
                                 with: .color(isLight ? Color.white.opacity(0.85) : Color.white.opacity(0.35)))
                }
            }
        }
    }
}

// MARK: - Toast 提示
private struct ToastView: View {
    let text: String
    let theme: AppTheme

    var body: some View {
        Text(text)
            .font(.subheadline.weight(.medium))
            .foregroundStyle(theme.textPrimary)
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .glassSurface(cornerRadius: 16, glassAlpha: 0.20, theme: theme)
    }
}
