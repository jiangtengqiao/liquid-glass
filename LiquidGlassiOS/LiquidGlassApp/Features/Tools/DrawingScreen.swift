import SwiftUI
import UIKit

// ─────────────────────────────────────────────────────────────────
// 绘图板 —— 对应 Android 端 ui/DrawingScreen.kt
//
// 关键实现：
//   1. 画布：SwiftUI Canvas + DragGesture，手指拖动绘制；当前笔画以
//      [CGPoint] 暂存，松手转 Path 并入 paths（对应 Android detectDragGestures）
//   2. 笔刷：普通 / 霓虹发光（多层叠加+白心）/ 虚线（StrokeStyle.dash）
//   3. 颜色：16 色预设 + 自定义 RGB 滑块（对应 Android PresetColors + ColorSlider）
//   4. 粗细滑块、橡皮擦（用背景色覆盖）、撤销/重做、清空、背景三态
//   5. 保存：ImageRenderer 渲染画布为 UIImage，写 PNG 到 App Documents
//      并显示 toast（iOS 端不接 PHPhotoLibrary，简化权限）
//   6. 分享：导出 PNG 到临时文件后弹 UIActivityViewController
//   7. 视觉：FluidBackground + glassSurface，深色主题统一
// ─────────────────────────────────────────────────────────────────

// MARK: - 数据模型
/// 笔刷类型（对应 Android BrushType）。
enum BrushType: String, CaseIterable {
    case normal, neon, dotted
}

/// 背景类型（对应 Android BgType）。
enum BgType: String, CaseIterable {
    case transparent, white, dark
}

/// 一条已提交的绘制路径（对应 Android DrawingPath）。
/// 注：Path 不可 Codable，画板不做持久化（与 Android 一致）。
struct DrawingPath: Identifiable {
    let id = UUID()
    var path: Path
    let color: Color
    let strokeWidth: CGFloat
    let brushType: BrushType
    let isEraser: Bool
}

// MARK: - 预设色板（与 Android PresetColors 完全一致）
private let presetColors: [Color] = [
    .white, .black, .red,
    Color(hex: 0xFF6B35), Color(hex: 0xFFD700), Color(hex: 0x2ED573),
    Color(hex: 0x00D4FF), Color(hex: 0x3366FF), Color(hex: 0x7B5CFC),
    Color(hex: 0xFF3B8B), Color(hex: 0x8B4513), Color(hex: 0x808080),
    Color(hex: 0x00E5A0), Color(hex: 0xFFA502), Color(hex: 0xE040FB),
    Color(hex: 0x40C4FF)
]

// MARK: - 画布尺寸 PreferenceKey（用于导出时获取真实尺寸）
private struct CanvasSizeKey: PreferenceKey {
    static var defaultValue: CGSize = .zero
    static func reduce(value: inout CGSize, nextValue: () -> CGSize) { value = nextValue() }
}

// MARK: - 主视图
struct DrawingScreen: View {
    var onBack: () -> Void

    // 绘制状态
    @State private var paths: [DrawingPath] = []
    @State private var undonePaths: [DrawingPath] = []
    @State private var currentPoints: [CGPoint] = []
    @State private var canvasSize: CGSize = .zero

    // 工具状态
    @State private var selectedColor: Color = .white
    @State private var brushSize: CGFloat = 7
    @State private var brushType: BrushType = .normal
    @State private var isEraser: Bool = false
    @State private var bgType: BgType = .dark

    // 弹层/反馈
    @State private var showColorPicker: Bool = false
    @State private var showClearConfirm: Bool = false
    @State private var customRed: Double = 1
    @State private var customGreen: Double = 1
    @State private var customBlue: Double = 1
    @State private var toast: String? = nil
    @State private var shareItem: ShareItem? = nil

    private var bgColor: Color {
        switch bgType {
        case .transparent: return .clear
        case .white:       return .white
        case .dark:        return Color(hex: 0x1A1A2E)
        }
    }

    private var customColor: Color {
        Color(red: customRed, green: customGreen, blue: customBlue)
    }

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: 0, theme: theme)

            VStack(spacing: 8) {
                topBar(theme: theme)
                canvasArea(theme: theme)
                bottomToolbar(theme: theme)
            }
            .padding(.top, 50)
            .padding(.horizontal, 20)
            .padding(.bottom, 16)
        }
        .alert("清空画布", isPresented: $showClearConfirm) {
            Button("清空", role: .destructive) { clearCanvas() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("确定要清空所有绘制内容吗？此操作不可撤销。")
        }
        .sheet(item: $shareItem) { item in
            ShareSheet(items: [item.url])
        }
        .overlay(alignment: .bottom) {
            if let toast {
                Text(toast)
                    .font(.subheadline)
                    .foregroundStyle(theme.textPrimary)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .glassSurface(cornerRadius: 16, glassAlpha: 0.22, theme: theme)
                    .padding(.bottom, 96)
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: toast)
        .animation(.easeInOut(duration: 0.2), value: showColorPicker)
    }

    // MARK: - 顶部栏（返回 + 居中标题 + 撤销/重做/保存 + 更多菜单）
    private func topBar(theme: AppTheme) -> some View {
        ZStack {
            HStack(spacing: 2) {
                Button { onBack() } label: {
                    Image(systemName: "chevron.left")
                        .foregroundStyle(theme.textSecondary)
                        .frame(width: 30, height: 30)
                }
                Spacer()
                ToolButton(systemName: "arrow.uturn.backward",
                           color: theme.textSecondary,
                           enabled: !paths.isEmpty,
                           action: undo)
                ToolButton(systemName: "arrow.uturn.forward",
                           color: theme.textSecondary,
                           enabled: !undonePaths.isEmpty,
                           action: redo)
                ToolButton(systemName: "square.and.arrow.down",
                           color: AccentSuccess,
                           enabled: !paths.isEmpty,
                           action: saveToDocuments)
                Menu {
                    Button(role: .destructive) { showClearConfirm = true } label: {
                        Label("清空画布", systemImage: "trash")
                    }
                    Button { share() } label: {
                        Label("分享绘图", systemImage: "square.and.arrow.up")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(theme.textSecondary)
                        .frame(width: 30, height: 30)
                }
            }
            Text("绘图板")
                .font(.headline)
                .foregroundStyle(theme.textPrimary)
        }
    }

    // MARK: - 画布
    private func canvasArea(theme: AppTheme) -> some View {
        Canvas { context, size in
            // 背景
            switch bgType {
            case .transparent: break
            case .white:       context.fill(Path(CGRect(origin: .zero, size: size)), with: .white)
            case .dark:        context.fill(Path(CGRect(origin: .zero, size: size)), with: Color(hex: 0x1A1A2E))
            }
            // 已提交路径
            for dp in paths {
                drawDrawingPath(context, dp, bgColor: bgColor)
            }
            // 当前进行中的笔画
            if !currentPoints.isEmpty {
                var p = Path()
                p.move(to: currentPoints[0])
                for pt in currentPoints.dropFirst() { p.addLine(to: pt) }
                let liveColor = isEraser ? bgColor : selectedColor
                let liveType: BrushType = isEraser ? .normal : brushType
                drawStroke(context, path: p, color: liveColor,
                           width: brushSize, type: liveType)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .glassSurface(cornerRadius: 20, glassAlpha: 0.10, theme: theme)
        .background(
            GeometryReader { geo in
                Color.clear.preference(key: CanvasSizeKey.self, value: geo.size)
            }
        )
        .onPreferenceChange(CanvasSizeKey.self) { canvasSize = $0 }
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { value in
                    if currentPoints.isEmpty {
                        currentPoints = [value.location]
                    } else {
                        currentPoints.append(value.location)
                    }
                }
                .onEnded { _ in commitCurrent() }
        )
    }

    // MARK: - 底部工具栏
    private func bottomToolbar(theme: AppTheme) -> some View {
        VStack(spacing: 8) {
            // 第 1 行：色板（横向滚动）+ 自定义色入口
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(presetColors.indices, id: \.self) { i in
                        let c = presetColors[i]
                        let selected = selectedColor == c && !isEraser
                        Circle()
                            .fill(c)
                            .frame(width: selected ? 30 : 26, height: selected ? 30 : 26)
                            .overlay(
                                Circle().stroke(.white, lineWidth: selected ? 2.5 : 0)
                            )
                            .onTapGesture {
                                selectedColor = c
                                isEraser = false
                            }
                    }
                    Circle()
                        .fill(AngularGradient(colors: [.red, .yellow, .green, .cyan, .blue, .magenta, .red],
                                              center: .center))
                        .frame(width: 26, height: 26)
                        .overlay(
                            Circle().stroke(.white.opacity(0.5), lineWidth: showColorPicker ? 2 : 0)
                        )
                        .onTapGesture {
                            withAnimation(.easeInOut(duration: 0.2)) { showColorPicker.toggle() }
                        }
                }
                .padding(.horizontal, 2)
            }

            // 自定义颜色面板（可展开）
            if showColorPicker {
                VStack(spacing: 6) {
                    HStack(spacing: 8) {
                        RoundedRectangle(cornerRadius: 6)
                            .fill(customColor)
                            .frame(width: 30, height: 30)
                        Text("自定义颜色")
                            .font(.caption)
                            .foregroundStyle(theme.textSecondary)
                        Spacer()
                        Button("应用") {
                            selectedColor = customColor
                            isEraser = false
                        }
                        .font(.caption)
                        .foregroundStyle(theme.accentPrimary)
                    }
                    ColorSliderRow(label: "R", value: $customRed, color: .red)
                    ColorSliderRow(label: "G", value: $customGreen, color: .green)
                    ColorSliderRow(label: "B", value: $customBlue, color: .blue)
                }
                .padding(.top, 4)
            }

            // 第 2 行：粗细 + 橡皮擦 + 笔刷类型 + 背景
            HStack(spacing: 6) {
                Image(systemName: "circle.fill")
                    .font(.system(size: 7))
                    .foregroundStyle(theme.textSecondary)
                Slider(value: $brushSize, in: 1...30, step: 1)
                    .tint(selectedColor)
                Text("\(Int(brushSize))px")
                    .font(.caption2)
                    .foregroundStyle(theme.textTertiary)
                    .frame(width: 32, alignment: .trailing)

                Button {
                    isEraser.toggle()
                } label: {
                    Image(systemName: "eraser")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(isEraser ? AccentWarning : theme.textTertiary)
                        .frame(width: 30, height: 30)
                }

                Menu {
                    Button { brushType = .normal } label: { Text("\(brushType == .normal ? "✓ " : "")普通笔刷") }
                    Button { brushType = .neon }    label: { Text("\(brushType == .neon ? "✓ " : "")霓虹发光") }
                    Button { brushType = .dotted }  label: { Text("\(brushType == .dotted ? "✓ " : "")虚线笔刷") }
                } label: {
                    Image(systemName: "paintbrush")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(theme.textSecondary)
                        .frame(width: 30, height: 30)
                }

                Menu {
                    Button { bgType = .dark }        label: { Text("\(bgType == .dark ? "✓ " : "")深色背景") }
                    Button { bgType = .white }       label: { Text("\(bgType == .white ? "✓ " : "")白色背景") }
                    Button { bgType = .transparent } label: { Text("\(bgType == .transparent ? "✓ " : "")透明背景") }
                } label: {
                    Image(systemName: "rectangle.stack")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(theme.textSecondary)
                        .frame(width: 30, height: 30)
                }
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .glassSurface(cornerRadius: 20, glassAlpha: 0.10, theme: theme)
    }

    // MARK: - 绘制操作
    private func commitCurrent() {
        guard !currentPoints.isEmpty else { return }
        var p = Path()
        p.move(to: currentPoints[0])
        for pt in currentPoints.dropFirst() { p.addLine(to: pt) }
        paths.append(DrawingPath(
            path: p,
            color: isEraser ? bgColor : selectedColor,
            strokeWidth: brushSize,
            brushType: isEraser ? .normal : brushType,
            isEraser: isEraser
        ))
        undonePaths.removeAll()
        currentPoints = []
    }

    private func undo() {
        guard let last = paths.popLast() else { return }
        undonePaths.append(last)
    }

    private func redo() {
        guard let last = undonePaths.popLast() else { return }
        paths.append(last)
    }

    private func clearCanvas() {
        paths.removeAll()
        undonePaths.removeAll()
        currentPoints = []
    }

    // MARK: - 导出 / 保存 / 分享
    /// 渲染当前画布为 UIImage（对应 Android 的 Bitmap + Canvas 离屏渲染）。
    private func renderImage() -> UIImage? {
        guard canvasSize.width > 0, canvasSize.height > 0 else { return nil }
        let view = DrawingExportView(paths: paths, bgType: bgType, size: canvasSize)
        let renderer = ImageRenderer(content: view)
        renderer.scale = UIScreen.main.scale
        return renderer.uiImage
    }

    private func saveToDocuments() {
        guard !paths.isEmpty else { showToast("画布为空"); return }
        guard let image = renderImage(), let data = image.pngData() else {
            showToast("保存失败"); return
        }
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let url = docs.appendingPathComponent("drawing_\(Int(Date().timeIntervalSince1970)).png")
        do {
            try data.write(to: url)
            showToast("已保存到文档：\(url.lastPathComponent)")
        } catch {
            showToast("保存失败：\(error.localizedDescription)")
        }
    }

    private func share() {
        guard !paths.isEmpty else { showToast("画布为空"); return }
        guard let image = renderImage(), let data = image.pngData() else {
            showToast("分享失败"); return
        }
        let temp = FileManager.default.temporaryDirectory
            .appendingPathComponent("drawing_share.png")
        do {
            try data.write(to: temp)
            shareItem = ShareItem(url: temp)
        } catch {
            showToast("分享失败")
        }
    }

    // MARK: - Toast
    private func showToast(_ msg: String) {
        withAnimation(.easeInOut(duration: 0.25)) { toast = msg }
        let expected = msg
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            withAnimation(.easeInOut(duration: 0.25)) {
                if toast == expected { toast = nil }
            }
        }
    }
}

// MARK: - 工具按钮
private struct ToolButton: View {
    let systemName: String
    let color: Color
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(enabled ? color : Color.white.opacity(0.25))
                .frame(width: 30, height: 30)
        }
        .disabled(!enabled)
    }
}

// MARK: - 自定义颜色滑块行（对应 Android ColorSlider）
private struct ColorSliderRow: View {
    let label: String
    @Binding var value: Double
    let color: Color

    var body: some View {
        HStack(spacing: 6) {
            Text(label)
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.5))
                .frame(width: 12)
            Slider(value: $value, in: 0...1)
                .tint(color)
            Text("\(Int(value * 255))")
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.5))
                .frame(width: 28, alignment: .trailing)
        }
    }
}

// MARK: - 离屏导出视图（供 ImageRenderer 渲染）
private struct DrawingExportView: View {
    let paths: [DrawingPath]
    let bgType: BgType
    let size: CGSize

    var body: some View {
        Canvas { context, canvasSize in
            switch bgType {
            case .transparent: break
            case .white:       context.fill(Path(CGRect(origin: .zero, size: canvasSize)), with: .white)
            case .dark:        context.fill(Path(CGRect(origin: .zero, size: canvasSize)), with: Color(hex: 0x1A1A2E))
            }
            for dp in paths {
                drawDrawingPath(context, dp, bgColor: bgColorValue)
            }
        }
        .frame(width: size.width, height: size.height)
    }

    private var bgColorValue: Color {
        switch bgType {
        case .transparent: return .clear
        case .white:       return .white
        case .dark:        return Color(hex: 0x1A1A2E)
        }
    }
}

// MARK: - 分享项
private struct ShareItem: Identifiable {
    let id = UUID()
    let url: URL
}

// MARK: - UIActivityViewController 包装（对应 Android 的分享 Intent）
private struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

// MARK: - 画布绘制函数（文件级，主画布与导出视图共用）
/// 按笔刷类型绘制一条路径（对应 Android DrawScope 的多分支渲染）。
private func drawStroke(_ context: GraphicsContext,
                        path: Path,
                        color: Color,
                        width: CGFloat,
                        type: BrushType) {
    let base = StrokeStyle(lineWidth: width, lineCap: .round, lineJoin: .round)
    switch type {
    case .normal:
        context.stroke(path, with: color, style: base)
    case .neon:
        context.stroke(path, with: color.opacity(0.15),
                       style: StrokeStyle(lineWidth: width + 12, lineCap: .round, lineJoin: .round))
        context.stroke(path, with: color.opacity(0.35),
                       style: StrokeStyle(lineWidth: width + 6, lineCap: .round, lineJoin: .round))
        context.stroke(path, with: color.opacity(0.8), style: base)
        context.stroke(path, with: Color.white.opacity(0.6),
                       style: StrokeStyle(lineWidth: max(0.5, width * 0.3), lineCap: .round, lineJoin: .round))
    case .dotted:
        context.stroke(path, with: color,
                       style: StrokeStyle(lineWidth: width, lineCap: .round, lineJoin: .round,
                                          dash: [width * 1.5, width * 3]))
    }
}

/// 渲染一条已提交路径，橡皮擦用背景色覆盖。
private func drawDrawingPath(_ context: GraphicsContext,
                             _ dp: DrawingPath,
                             bgColor: Color) {
    let color = dp.isEraser ? bgColor : dp.color
    let type: BrushType = dp.isEraser ? .normal : dp.brushType
    drawStroke(context, path: dp.path, color: color, width: dp.strokeWidth, type: type)
}
