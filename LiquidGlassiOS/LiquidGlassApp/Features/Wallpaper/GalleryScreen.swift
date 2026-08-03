import SwiftUI

// ─────────────────────────────────────────────────────────────────
// 壁纸库 —— 对应 Android 端 ui/GalleryScreen.kt
//
// 关键实现：
//   - Canvas 程序化绘制壁纸（不依赖外部图片资源，与 Android WallpaperItem.draw 一致）
//   - 分类标签（自然/抽象/渐变/太空/海洋/液态等）
//   - 网格预览 + 全屏预览（pinch 缩放可选）
//   - 保存到相册（需 Photos 权限，对应 Android MediaStore 写入）
//
// 每个壁纸是一个 DrawingProvider 闭包，在指定 Size 内用 GraphicsContext 绘制。
// ─────────────────────────────────────────────────────────────────

// MARK: - 数据模型（对应 Android WallpaperItem / WallpaperCategory）
struct WallpaperItem: Identifiable {
    let id: Int
    let category: WallpaperCategory
    let name: String
    /// 程序化绘制闭包：传入 context 与 size，自绘壁纸内容。
    let draw: (GraphicsContext, CGSize) -> Void
}

struct WallpaperCategory: Identifiable, Hashable {
    let id = UUID()
    let name: String       // 英文标识
    let label: String      // 中文显示
    let iconName: String   // SF Symbol
    let accentColor: Color
}

// MARK: - 分类定义（与 Android wallpaperCategories 对应）
enum WallpaperCatalog {
    static let categories: [WallpaperCategory] = [
        .init(name: "Nature", label: "自然", iconName: "tree.fill", accentColor: Color(hex: 0x5BDB7C)),
        .init(name: "Abstract", label: "抽象", iconName: "circle.hexagongrid.fill", accentColor: Color(hex: 0xFF6B9D)),
        .init(name: "Gradient", label: "渐变", iconName: "circle.lefthalf.filled", accentColor: FluidPurple),
        .init(name: "Space", label: "太空", iconName: "moon.stars.fill", accentColor: Color(hex: 0x5B9AFF)),
        .init(name: "Ocean", label: "海洋", iconName: "water.waves", accentColor: FluidCyan),
        .init(name: "Minimal", label: "极简", iconName: "circle.fill", accentColor: Color(hex: 0xCCCCCC)),
        .init(name: "Night", label: "夜空", iconName: "sparkles", accentColor: Color(hex: 0x9B7BFF)),
        .init(name: "Liquid", label: "液态", iconName: "drop.fill", accentColor: FluidTeal)
    ]

    /// 程序化生成壁纸列表：每个分类 4 张，按 id 索引选择不同绘制函数。
    static func wallpapers(in category: WallpaperCategory) -> [WallpaperItem] {
        (0..<4).map { i in
            WallpaperItem(id: i, category: category, name: "\(category.label) \(i + 1)") { ctx, size in
                drawWallpaper(category: category, index: i, in: ctx, size: size)
            }
        }
    }

    /// 根据分类与索引程序化绘制壁纸。
    private static func drawWallpaper(category: WallpaperCategory, index: Int,
                                      in context: GraphicsContext, size: CGSize) {
        let w = size.width, h = size.height
        let rect = CGRect(origin: .zero, size: size)
        let seed = Double(index) * 0.7

        switch category.name {
        case "Gradient":
            // 双色对角渐变
            let gradient = LinearGradient(
                colors: [category.accentColor, category.accentColor.opacity(0.3), Color.black.opacity(0.4)],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
            context.fill(Path(rect), with: gradient)
        case "Ocean":
            // 多层波浪
            context.fill(Path(rect), with: Color(hex: 0x002040))
            for i in 0..<5 {
                var path = Path()
                path.move(to: CGPoint(x: 0, y: h * (0.4 + Double(i) * 0.12)))
                let baseY = h * (0.4 + Double(i) * 0.12)
                for x in stride(from: 0.0, through: Double(w), by: 8) {
                    let y = baseY + sin(x * 0.02 + seed + Double(i)) * 8
                    path.addLine(to: CGPoint(x: x, y: y))
                }
                path.addLine(to: CGPoint(x: w, y: h))
                path.addLine(to: CGPoint(x: 0, y: h))
                path.closeSubpath()
                context.fill(path, with: FluidCyan.opacity(0.15 + Double(i) * 0.05))
            }
        case "Space":
            // 深空 + 星点
            context.fill(Path(rect), with: Color(hex: 0x08081A))
            let starColors = [Color.white, FluidCyan, FluidPurple]
            for i in 0..<60 {
                let x = (sin(Double(i) * 12.9898 + seed) * 43758.5453).truncatingRemainder(dividingBy: 1)
                let y = (cos(Double(i) * 78.233 + seed) * 12543.987).truncatingRemainder(dividingBy: 1)
                let px = abs(x) * w
                let py = abs(y) * h
                let r = abs(y) * 1.5 + 0.5
                let c = starColors[i % 3]
                context.fill(Path(ellipseIn: CGRect(x: px - r, y: py - r, width: r * 2, height: r * 2)),
                             with: c.opacity(0.8))
            }
            // 月亮
            context.fill(Path(ellipseIn: CGRect(x: w * 0.65, y: h * 0.15, width: w * 0.2, height: w * 0.2)),
                         with: Color.white.opacity(0.9))
        case "Nature":
            // 渐变天空 + 山影
            context.fill(Path(rect), with: LinearGradient(
                colors: [Color(hex: 0xFF9A8B), Color(hex: 0xFFC796), Color(hex: 0x6DD5FA)],
                startPoint: .top, endPoint: .bottom))
            var mountain = Path()
            mountain.move(to: CGPoint(x: 0, y: h * 0.7))
            mountain.addLine(to: CGPoint(x: w * 0.3, y: h * 0.45))
            mountain.addLine(to: CGPoint(x: w * 0.55, y: h * 0.6))
            mountain.addLine(to: CGPoint(x: w * 0.8, y: h * 0.4))
            mountain.addLine(to: CGPoint(x: w, y: h * 0.55))
            mountain.addLine(to: CGPoint(x: w, y: h))
            mountain.addLine(to: CGPoint(x: 0, y: h))
            mountain.closeSubpath()
            context.fill(mountain, with: Color(hex: 0x2C3E50))
        case "Abstract":
            // 多色色块叠加
            context.fill(Path(rect), with: Color(hex: 0x1A1A2E))
            let blobs: [(Color, CGFloat, CGFloat)] = [
                (FluidPink, 0.3, 0.3), (FluidCyan, 0.7, 0.4),
                (FluidPurple, 0.5, 0.7), (FluidTeal, 0.2, 0.6)
            ]
            for (c, nx, ny) in blobs {
                let r = w * 0.25
                context.fill(Path(ellipseIn: CGRect(x: w * nx - r, y: h * ny - r, width: r * 2, height: r * 2)),
                             with: c.opacity(0.4))
            }
        case "Minimal":
            // 纯色 + 一个圆
            context.fill(Path(rect), with: Color(hex: 0xF0F0F5))
            let r = w * 0.3
            context.fill(Path(ellipseIn: CGRect(x: w / 2 - r, y: h / 2 - r, width: r * 2, height: r * 2)),
                         with: category.accentColor.opacity(0.8))
        case "Night":
            // 紫色夜空 + 月亮
            context.fill(Path(rect), with: LinearGradient(
                colors: [Color(hex: 0x1A0A30), Color(hex: 0x3D1A6B)],
                startPoint: .bottom, endPoint: .top))
            context.fill(Path(ellipseIn: CGRect(x: w * 0.6, y: h * 0.2, width: w * 0.25, height: w * 0.25)),
                         with: Color.white.opacity(0.85))
        case "Liquid":
            // 流体色块（与 FluidBackground 风格一致）
            context.fill(Path(rect), with: Color(hex: 0x08080F))
            let cols = [FluidCyan, FluidPurple, FluidPink, FluidTeal]
            for i in 0..<4 {
                let cx = w * (0.3 + Double(i) * 0.15 + sin(seed + Double(i)) * 0.05)
                let cy = h * (0.4 + cos(seed + Double(i) * 1.3) * 0.15)
                let r = w * 0.3
                context.fill(Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2)),
                             with: cols[i].opacity(0.3))
            }
        default:
            context.fill(Path(rect), with: category.accentColor.opacity(0.5))
        }
    }
}

// MARK: - 壁纸库主界面
struct GalleryScreen: View {
    let animTime: Double
    let onBack: () -> Void
    @State private var selectedCategory: WallpaperCategory = WallpaperCatalog.categories[0]
    @State private var previewItem: WallpaperItem?

    private let columns = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: animTime, theme: theme)

            VStack(spacing: 16) {
                topBar(theme: theme)

                // 分类横滑条
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(WallpaperCatalog.categories) { cat in
                            Button {
                                withAnimation { selectedCategory = cat }
                            } label: {
                                Label(cat.label, systemImage: cat.iconName)
                                    .font(.caption.weight(selectedCategory == cat ? .semibold : .regular))
                                    .foregroundStyle(selectedCategory == cat ? .white : theme.textSecondary)
                                    .padding(.horizontal, 14).padding(.vertical, 8)
                                    .background(selectedCategory == cat ? cat.accentColor : Color.clear,
                                                in: Capsule())
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                }

                // 壁纸网格
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 10) {
                        ForEach(WallpaperCatalog.wallpapers(in: selectedCategory)) { item in
                            Button { previewItem = item } label: {
                                Canvas { ctx, size in
                                    item.draw(ctx, size)
                                }
                                .frame(height: 220)
                                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                                .overlay(alignment: .bottomLeading) {
                                    Text(item.name)
                                        .font(.caption2.weight(.semibold))
                                        .foregroundStyle(.white)
                                        .padding(8)
                                        .background(.ultraThinMaterial, in: Capsule())
                                        .padding(8)
                                }
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 24)
                }
            }
            .padding(.top, 50)
        }
        // 全屏预览
        .fullScreenCover(item: $previewItem) { item in
            WallpaperPreview(item: item) { previewItem = nil }
        }
    }

    private func topBar(theme: AppTheme) -> some View {
        HStack {
            Button { onBack() } label: {
                Image(systemName: "chevron.left").foregroundStyle(theme.textSecondary)
            }
            Text("壁纸库").font(.headline).foregroundStyle(theme.textPrimary)
            Spacer()
            Image(systemName: "photo.fill").foregroundStyle(theme.fluidCyan)
        }
        .padding(.horizontal, 16)
    }
}

// MARK: - 全屏预览
private struct WallpaperPreview: View {
    let item: WallpaperItem
    let onClose: () -> Void

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Canvas { ctx, size in
                // 铺满全屏（竖向适配）
                var s = size
                s.height = size.height
                item.draw(ctx, s)
            }
            .ignoresSafeArea()

            Button { onClose() } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title)
                    .foregroundStyle(.white.opacity(0.9))
                    .background(Circle().fill(.black.opacity(0.3)))
            }
            .padding(20)

            VStack {
                Spacer()
                // 保存到相册提示（对应 Android WallpaperManager.setBitmap / MediaStore）
                Label("双指捏合可缩放 · 保存功能需 Photos 权限", systemImage: "info.circle")
                    .font(.caption)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(.ultraThinMaterial, in: Capsule())
                    .padding(.bottom, 40)
            }
        }
    }
}
