import SwiftUI
import CoreImage
import CoreImage.CIFilterBuiltins
import UIKit

// ─────────────────────────────────────────────────────────────────
// 二维码生成 —— 对应 Android 端 ui/QRCodeScreen.kt
//
// 关键实现：
//   1. CoreImage CIFilter.qrCodeGenerator() 生成 QR（自动选择编码模式，
//      支持 URL/中文/特殊字符，对应 Android 端 ZXing MultiFormatWriter）
//   2. 着色管线：原 QR（黑底白点）→ colorInvert → maskToAlpha → multiplyCompositing
//      得到「彩色模块 + 透明背景」的 QR，可在深色玻璃背景上保持扫描兼容
//   3. 历史记录用 Persistence.shared 持久化 [QRHistoryItem] 数组
//   4. 保存到相册：白色背景复合（白色 QR 例外，用黑色背景），保证扫描兼容性
// ─────────────────────────────────────────────────────────────────

/// 持久化键：二维码生成历史 JSON Data。
private let kQrHistoryKey = "liquid_glass_qr_history"

// MARK: - 数据模型
/// 二维码历史项（对应 Android QrHistoryItem）。
struct QRHistoryItem: Identifiable, Codable {
    let id: UUID
    var content: String
    var timestamp: Date
}

// MARK: - 主视图
struct QRCodeScreen: View {
    var onBack: () -> Void

    @State private var inputText: String = ""
    @State private var generatedImage: UIImage? = nil
    @State private var selectedColorIndex: Int = 1   // 默认青色
    @State private var history: [QRHistoryItem] = []
    @State private var toastText: String = ""
    @State private var showToast: Bool = false

    /// 前景色预设（白/青/紫/绿/粉/橙），同步 SwiftUI Color 与 UIColor，
    /// UIColor 用于 CIFilter 染色，SwiftUI Color 用于按钮显示。
    private let colorPresets: [(name: String, color: Color, uiColor: UIColor)] = [
        ("白", .white, .white),
        ("青", FluidCyan, UIColor(red: 0/255, green: 212/255, blue: 255/255, alpha: 1)),
        ("紫", FluidPurple, UIColor(red: 123/255, green: 92/255, blue: 252/255, alpha: 1)),
        ("绿", FluidTeal, UIColor(red: 0/255, green: 229/255, blue: 160/255, alpha: 1)),
        ("粉", FluidPink, UIColor(red: 255/255, green: 59/255, blue: 139/255, alpha: 1)),
        ("橙", FluidOrange, UIColor(red: 255/255, green: 107/255, blue: 53/255, alpha: 1))
    ]

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: 0, theme: theme)

            VStack(spacing: 16) {
                topBar(theme: theme)

                ScrollView {
                    VStack(spacing: 16) {
                        inputSection(theme: theme)
                        if let img = generatedImage {
                            qrPreview(img: img, theme: theme)
                            colorPicker(theme: theme)
                            actionButtons(img: img, theme: theme)
                        }
                        if !history.isEmpty {
                            historySection(theme: theme)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 24)
                }
            }
            .padding(.top, 50)
            .padding(.bottom, 24)
        }
        .onAppear { loadHistory() }
        .overlay(alignment: .bottom) {
            if showToast {
                ToastView(text: toastText, theme: theme)
                    .padding(.bottom, 60)
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: showToast)
    }

    // MARK: - 顶部栏
    private func topBar(theme: AppTheme) -> some View {
        HStack {
            Button { onBack() } label: {
                Image(systemName: "chevron.left").foregroundStyle(theme.textSecondary)
            }
            Text("二维码").font(.headline).foregroundStyle(theme.textPrimary)
            Spacer()
        }
        .padding(.horizontal, 16)
    }

    // MARK: - 输入区
    private func inputSection(theme: AppTheme) -> some View {
        VStack(spacing: 12) {
            TextField("输入文本或 URL", text: $inputText, axis: .vertical)
                .lineLimit(1...4)
                .font(.body)
                .foregroundStyle(theme.textPrimary)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .padding(12)
                .glassSurface(cornerRadius: 16, glassAlpha: 0.15, theme: theme)

            Button {
                generateQR()
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "qrcode")
                    Text("生成二维码")
                        .font(.headline)
                }
                .foregroundStyle(theme.bgDark)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(theme.fluidCyan, in: RoundedRectangle(cornerRadius: 16))
            }
        }
    }

    // MARK: - 二维码预览
    private func qrPreview(img: UIImage, theme: AppTheme) -> some View {
        Image(uiImage: img)
            .interpolation(.none)
            .resizable()
            .scaledToFit()
            .frame(maxWidth: 240, maxHeight: 240)
            .padding(20)
            .frame(maxWidth: .infinity)
            .glassSurface(cornerRadius: 24, glassAlpha: 0.15, theme: theme)
    }

    // MARK: - 颜色选择
    private func colorPicker(theme: AppTheme) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("前景色")
                .font(.subheadline)
                .foregroundStyle(theme.textSecondary)

            HStack(spacing: 14) {
                ForEach(colorPresets.indices, id: \.self) { idx in
                    let preset = colorPresets[idx]
                    let isSelected = selectedColorIndex == idx
                    Button {
                        selectedColorIndex = idx
                        regenerate()
                    } label: {
                        ZStack {
                            Circle()
                                .fill(preset.color)
                                .frame(width: 36, height: 36)
                            if isSelected {
                                Circle()
                                    .stroke(theme.textPrimary, lineWidth: 2.5)
                                    .frame(width: 42, height: 42)
                            }
                        }
                        .frame(width: 44, height: 44)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .glassSurface(cornerRadius: 16, glassAlpha: 0.15, theme: theme)
    }

    // MARK: - 操作按钮
    private func actionButtons(img: UIImage, theme: AppTheme) -> some View {
        HStack(spacing: 12) {
            actionButton(title: "保存到相册",
                         icon: "square.and.arrow.down",
                         color: theme.fluidTeal,
                         theme: theme) {
                saveToAlbum(img: img)
            }
            actionButton(title: "复制到剪贴板",
                         icon: "doc.on.doc",
                         color: theme.fluidPurple,
                         theme: theme) {
                copyToClipboard(img: img)
            }
        }
    }

    private func actionButton(title: String, icon: String, color: Color,
                              theme: AppTheme, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.title2)
                Text(title).font(.caption)
            }
            .foregroundStyle(color)
            .frame(maxWidth: .infinity)
            .frame(height: 70)
            .glassSurface(cornerRadius: 16, glassAlpha: 0.15, theme: theme)
        }
    }

    // MARK: - 历史记录
    private func historySection(theme: AppTheme) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: "clock.arrow.circlepath")
                    .foregroundStyle(theme.fluidCyan)
                Text("最近生成")
                    .font(.headline)
                    .foregroundStyle(theme.textPrimary)
                Spacer()
                Button {
                    history.removeAll()
                    persistHistory()
                } label: {
                    Text("清空")
                        .font(.caption)
                        .foregroundStyle(theme.textTertiary)
                }
            }
            .padding(.horizontal, 4)

            ForEach(history) { item in
                Button {
                    inputText = item.content
                    generateQR()
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "qrcode")
                            .font(.subheadline)
                            .foregroundStyle(theme.fluidPurple)
                        Text(item.content)
                            .font(.subheadline)
                            .foregroundStyle(theme.textSecondary)
                            .lineLimit(1)
                        Spacer()
                    }
                    .padding(.vertical, 10)
                    .padding(.horizontal, 12)
                    .glassSurface(cornerRadius: 16, glassAlpha: 0.10, theme: theme)
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - 二维码生成（CIFilter 着色管线）
    private func generateQR() {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        guard let img = generateQRImage(
            text: text,
            foregroundColor: colorPresets[selectedColorIndex].uiColor
        ) else { return }
        generatedImage = img

        // 写入历史（去重 + 最多 20 条）
        let item = QRHistoryItem(id: UUID(), content: text, timestamp: Date())
        history.removeAll { $0.content == text }
        history.insert(item, at: 0)
        if history.count > 20 { history = Array(history.prefix(20)) }
        persistHistory()
    }

    /// 颜色切换后基于当前文本重新生成。
    private func regenerate() {
        guard generatedImage != nil else { return }
        generateQR()
    }

    /// 生成彩色透明背景 QR：
    /// 原 QR（黑底白点）→ colorInvert → maskToAlpha（白→不透明，黑→透明）
    /// → multiplyCompositing（白色蒙版 × 指定色 = 彩色模块）
    private func generateQRImage(text: String, foregroundColor: UIColor) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let qrImage = filter.outputImage else { return nil }

        // 放大 10 倍提升清晰度（默认 27×27 模块太小）
        let scaled = qrImage.transformed(by: CGAffineTransform(scaleX: 10, y: 10))

        let invert = CIFilter.colorInvert()
        invert.inputImage = scaled
        guard let inverted = invert.outputImage else { return nil }

        let mask = CIFilter.maskToAlpha()
        mask.inputImage = inverted
        guard let masked = mask.outputImage else { return nil }

        let colorGen = CIFilter.constantColorGenerator()
        colorGen.color = CIColor(color: foregroundColor)
        guard let colorImg = colorGen.outputImage else { return nil }

        let multiply = CIFilter.multiplyCompositing()
        multiply.inputImage = colorImg
        multiply.backgroundImage = masked
        guard let result = multiply.outputImage else { return nil }

        let context = CIContext()
        guard let cgImage = context.createCGImage(result, from: result.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }

    // MARK: - 保存 / 复制
    private func saveToAlbum(img: UIImage) {
        // 白色 QR 用黑色背景，其余用白色背景，保证扫描兼容性
        let bgColor: UIColor = selectedColorIndex == 0 ? .black : .white
        let composed = composeOnBackground(img, backgroundColor: bgColor)
        UIImageWriteToSavedPhotosAlbum(composed, nil, nil, nil)
        presentToast("已保存到相册")
    }

    private func copyToClipboard(img: UIImage) {
        UIPasteboard.general.image = img
        presentToast("已复制到剪贴板")
    }

    private func composeOnBackground(_ image: UIImage, backgroundColor: UIColor) -> UIImage {
        let size = image.size
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { ctx in
            backgroundColor.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    private func presentToast(_ text: String) {
        toastText = text
        withAnimation { showToast = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            withAnimation { showToast = false }
        }
    }

    // MARK: - 持久化
    private func loadHistory() {
        if let saved = Persistence.shared.object([QRHistoryItem].self, for: kQrHistoryKey) {
            history = saved
        }
    }

    private func persistHistory() {
        Persistence.shared.setObject(history, for: kQrHistoryKey)
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
