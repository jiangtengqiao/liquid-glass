# 液态玻璃框架 — iOS 完整实现文档

## 文档说明

本文档是"液态玻璃 · 灵动工具箱"Android 应用中液态玻璃视觉效果的完整技术规范，供 iOS 平台（SwiftUI）复现同等效果。所有参数、色值、动画曲线均来自实际 Android（Jetpack Compose）代码的直接翻译。

---

## 一、色彩系统

### 1.1 背景色

```swift
// 极暗深色背景
let BgDark    = Color(hex: "#08080F")
let BgDark2   = Color(hex: "#0D0D1A")
```

### 1.2 流体主题色（5 色渐变体系）

```swift
let FluidCyan    = Color(hex: "#00D4FF")  // 青色 - 主强调色
let FluidPurple  = Color(hex: "#7B5CFC")  // 紫色
let FluidPink    = Color(hex: "#FF3B8B")  // 粉色
let FluidBlue    = Color(hex: "#3366FF")  // 蓝色
let FluidTeal    = Color(hex: "#00E5A0")  // 碧绿色
let FluidOrange  = Color(hex: "#FF6B35")  // 橙色
```

### 1.3 功能色

```swift
let AccentPrimary = Color(hex: "#5B9AFF")
let AccentDanger  = Color(hex: "#FF4757")
let AccentSuccess = Color(hex: "#2ED573")
let AccentWarning = Color(hex: "#FFA502")
```

### 1.4 文字色

```swift
let TextPrimary   = Color(hex: "#F0F0F5")       // 主要文字
let TextSecondary = Color.white.opacity(0.60)   // 次要文字
let TextTertiary  = Color.white.opacity(0.33)   // 三级文字
```

### 1.5 玻璃透明度常量（6 级）

```swift
let GlassClear     = Color.white.opacity(0.06)  // 基底玻璃
let GlassLight     = Color.white.opacity(0.09)  // 轻量化
let GlassMedium    = Color.white.opacity(0.13)  // 中等
let GlassBorder    = Color.white.opacity(0.16)  // 边框
let GlassHighlight = Color.white.opacity(0.21)  // 高光
let GlassBright    = Color.white.opacity(0.31)  // 亮色玻璃
```

---

## 二、玻璃卡片渲染管线（8 层叠加）

这是整个框架的核心。所有玻璃卡片必须按以下 8 层顺序叠加渲染，缺一不可。

### 实现方式：SwiftUI ViewModifier

```swift
struct GlassSurfaceModifier: ViewModifier {
    let cornerRadius: CGFloat
    let glassAlpha: CGFloat  // 推荐 0.18 ~ 0.22
    let showBorder: Bool

    init(cornerRadius: CGFloat = 24, glassAlpha: CGFloat = 0.18, showBorder: Bool = true) {
        self.cornerRadius = cornerRadius
        self.glassAlpha = glassAlpha
        self.showBorder = showBorder
    }

    func body(content: View) -> some View {
        content
            .background(
                GlassSurfaceView(
                    cornerRadius: cornerRadius,
                    glassAlpha: glassAlpha,
                    showBorder: showBorder
                )
            )
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
    }
}
```

### 逐层渲染规范（使用 Canvas / drawRect）

#### 第 1 层：基底毛玻璃

```swift
// 绘制：填充圆角矩形
// 颜色: Color.white.opacity(glassAlpha * 0.6)
// 形状: RoundedRectangle(cornerRadius: cornerRadius)
// 填充模式: fill
```

#### 第 2 层：发光边框（3 条嵌套描边）

```swift
// 2a. 外发光边框
// 颜色: Color.white.opacity(0.30)
// 描边宽度: 1.5pt
// 圆角半径: cornerRadius

// 2b. 内层高亮边框
// 颜色: Color.white.opacity(0.20)
// 描边宽度: 1.0pt
// 圆角半径: cornerRadius - 1.5pt

// 2c. 最内层微光边框
// 颜色: Color.white.opacity(0.10)
// 描边宽度: 0.6pt
// 圆角半径: cornerRadius - 3.0pt
```

#### 第 3 层：顶部强反射高光（Path 绘制）

```swift
// 绘制一个闭合路径，模拟光源照射在毛玻璃顶部的反射
// 路径顶点:
//   (cornerRadius * 0.3,  0)
//   (width * 0.85,        0)
//   (width * 0.6,         height * 0.09)
//   (cornerRadius * 0.2,  height * 0.05)
//
// 填充渐变: LinearGradient (从上到下)
//   0%:   Color.white.opacity(0.50)
//   50%:  Color.white.opacity(0.08)
//   100%: Color.white.opacity(0.0)
// 渐变方向: 垂直向下，结束于 height * 0.14
```

#### 第 4 层：左上角斜向高光（三角渐变）

```swift
// 绘制一个三角形路径:
//   顶点: (0, 0)
//   顶点: (width * 0.38, 0)
//   顶点: (0, height * 0.38)
//
// 填充渐变: LinearGradient (从左上到右下)
//   0%:   Color.white.opacity(0.35)
//   50%:  Color.white.opacity(0.03)
//   100%: Color.white.opacity(0.0)
// 渐变方向: 从 (0, 0) 到 (width * 0.35, height * 0.35)
```

#### 第 5 层：顶部弧形高光条（贝塞尔曲线）

```swift
// 沿顶部边缘的弧形光带，模拟曲面玻璃反射
// 路径:
//   moveTo(cornerRadius * 0.8, cornerRadius * 0.12)
//   cubicTo(
//     width * 0.25, cornerRadius * 0.0,
//     width * 0.75, cornerRadius * 0.0,
//     width - cornerRadius * 0.8, cornerRadius * 0.12
//   )
// 然后向下偏移 cornerRadius * 0.07 形成闭合路径
//
// 填充渐变: LinearGradient (水平)
//   0%:   Color.white.opacity(0.0)
//   25%:  Color.white.opacity(0.25)
//   50%:  Color.white.opacity(0.40)  ← 峰值
//   75%:  Color.white.opacity(0.25)
//   100%: Color.white.opacity(0.0)
```

#### 第 6 层：底部边缘环境光

```swift
// 绘制：圆角矩形底部渐变
// 渐变: LinearGradient (从下到上)
//   0%:   Color.white.opacity(0.12)
//   100%: Color.white.opacity(0.0)
// 渐变范围: 从底部开始，到 height * 0.80 结束
```

#### 第 7 层：浮动光斑（2 组圆形）

```swift
// 7a. 右上角光斑（双层）
// 内层: radius = cornerRadius * 0.3, center = (width - cornerRadius * 0.6, cornerRadius * 0.5)
//       颜色: Color.white.opacity(0.18)
// 外层: radius = cornerRadius * 0.5, center 同上
//       颜色: Color.white.opacity(0.10)

// 7b. 左下角光斑（单层）
// radius = cornerRadius * 0.2, center = (cornerRadius * 0.8, height - cornerRadius * 0.8)
// 颜色: Color.white.opacity(0.10)
```

#### 第 8 层：彩虹色散折射（3 条描边）

```swift
// 8a. 青色折射
// 颜色: FluidCyan.opacity(0.06)
// 描边宽度: 1.5pt
// 圆角半径: cornerRadius

// 8b. 紫色折射
// 颜色: FluidPurple.opacity(0.05)
// 描边宽度: 1.0pt
// 圆角半径: cornerRadius - 0.5pt

// 8c. 粉色折射
// 颜色: FluidPink.opacity(0.03)
// 描边宽度: 0.6pt
// 圆角半径: cornerRadius - 1.0pt
```

### SwiftUI 用法

```swift
// 扩展 View
extension View {
    func glassSurface(cornerRadius: CGFloat = 24, 
                      glassAlpha: CGFloat = 0.18, 
                      showBorder: Bool = true) -> some View {
        self.modifier(GlassSurfaceModifier(
            cornerRadius: cornerRadius,
            glassAlpha: glassAlpha,
            showBorder: showBorder
        ))
    }
}

// 使用示例
VStack { ... }
    .glassSurface(cornerRadius: 24, glassAlpha: 0.18)
```

---

## 三、动态流体背景

### 3.1 概述

流体背景由 4 个子系统组成，持续渲染在 Canvas 上：

| 层级 | 子系统 | 元素数量 | 刷新率 |
|------|--------|----------|--------|
| 1 | 流动色块 | 6 个 | 60fps |
| 2 | 浮动光晕 | 4 个 | 60fps |
| 3 | 正弦波纹 | 4 组（每像素行） | 60fps |
| 4 | 彩色粒子 | 25 个 | 60fps |

### 3.2 动画驱动

```swift
// 使用 Timer 或 CADisplayLink 驱动 time 参数
// time 范围: 0.0 ~ 100.0，循环递增
// 每帧步进: 约 0.016（60fps）
// 内部使用 time % 6.2832 进行三角计算，避免大浮点数精度问题
```

### 3.3 子系统 1：流动色块（6 个）

```swift
// 每个色块定义：
// 颜色1: 深蓝紫色系    颜色2: 深蓝紫系    基础半径系数
//
// 色块0: Color(0xFF1A1040), Color(0xFF002040), 0.45
// 色块1: Color(0xFF0D2040), Color(0xFF1A0A30), 0.40
// 色块2: Color(0xFF102040), Color(0xFF0D1030), 0.48
// 色块3: Color(0xFF0A1530), Color(0xFF150A30), 0.38
// 色块4: Color(0xFF0D1A35), Color(0xFF100A35), 0.42
// 色块5: Color(0xFF0F0D28), Color(0xFF0A1535), 0.35
//
// 每个色块的圆心随时间正弦波动:
//   cx = width * (0.2 + 0.20 * sin(time * 0.15 + i * 1.4))
//   cy = height * (0.2 + 0.20 * cos(time * 0.17 + i * 1.8))
//   radius = max(width * baseR + width * 0.08 * sin(time * 0.25 + i), 60)
//
// 绘制：径向渐变圆
//   中心: 颜色1.opacity(0.15)
//   中间: 颜色2.opacity(0.06)
//   边缘: Color.clear
```

### 3.4 子系统 2：浮动光晕（4 个）

```swift
// 4 个光晕颜色: FluidCyan, FluidPurple, FluidTeal, FluidPink
// 每个光晕的圆心:
//   cx = width * (0.5 + 0.3 * sin(time * 0.12 + phase))
//   cy = height * (0.5 + 0.3 * cos(time * 0.14 + phase * 1.2))
//   radius = width * (0.12 + 0.04 * sin(time * 0.2 + phase))
//   phase = i * 1.8
//
// 绘制：径向渐变圆
//   中心: color.opacity(0.08)
//   中间: color.opacity(0.02)
//   边缘: Color.clear
```

### 3.5 子系统 3：正弦波纹（4 组）

```swift
// 4 组波纹，从顶部到底部逐行扫描
// 每行间距: 3pt
// 相位: phase = i * 1.5
// 速度: speed = 0.08 + i * 0.04
//
// 对画布高度上的每一行 (y 从 0 到 height, step=3):
//   yf = y / height
//   offset = sin(yf * 5 + time * speed + phase) * width * 0.07
//          + cos(yf * 3 - time * speed * 0.5) * width * 0.04
//   alpha = clamp(sin(yf * 3 + time * speed * 0.7) * 0.01 + 0.018, 0, 0.04)
//
//   绘制水平线: (offset, y) → (width + offset * 0.3, y)
//   颜色: Color.white.opacity(alpha)
//   线宽: 1.5pt
```

### 3.6 子系统 4：彩色粒子（25 个）

```swift
// 粒子颜色轮换: [FluidCyan, FluidPurple, FluidTeal, FluidBlue, FluidPink]
// 每个粒子位置:
//   seed = i * 127.1
//   px = (sin(time * 0.3 + seed) * 0.5 + 0.5) * width
//   py = (cos(time * 0.35 + seed * 1.3) * 0.5 + 0.5) * height
//   alpha = clamp(sin(time * 0.5 + seed * 0.7) * 0.05 + 0.05, 0, 0.10)
//   radius = 2.5 + sin(time * 0.6 + seed) * 2.0
//
// 绘制: 实心圆
//   颜色: particleColors[i % 5].opacity(alpha)
//   半径: radius
//   圆心: (px, py)
```

---

## 四、水滴涟漪交互

### 4.1 数据结构

```swift
struct DropletState {
    let x: CGFloat        // 归一化 x 坐标 (0~1)
    let y: CGFloat        // 归一化 y 坐标 (0~1)
    var progress: CGFloat // 动画进度 (0~1)
    let id: UUID
}
```

### 4.2 动画参数

```swift
// 点击触发涟漪:
// 1. 创建 DropletState(x: tapX/width, y: tapY/height, progress: 0)
// 2. 在 50 帧内 (每帧 25ms，共 1.25 秒) 将 progress 从 0 动画到 1
// 3. 缓动函数: easeOutQuad, progress = 1 - (1 - t)²
// 4. 动画结束后移除该 droplet
```

### 4.3 涟漪渲染（每个 droplet 绘制 4 层同心圆）

```swift
// 对每个活跃的 droplet:
//   alpha = clamp(1 - progress, 0, 1) * 0.8
//   maxR = max(width, height) * 0.5
//   radius = progress * maxR
//   cx = droplet.x * width
//   cy = droplet.y * height
//
//   第 1 层: FluidCyan.opacity(alpha), 描边宽度 3pt, 半径 radius
//   第 2 层: FluidPurple.opacity(alpha * 0.7), 描边宽度 2.5pt, 半径 radius * 0.6
//   第 3 层: FluidTeal.opacity(alpha * 0.4), 描边宽度 2pt, 半径 radius * 0.3
//   第 4 层: Color.white.opacity(alpha * 0.7), 实心圆, 半径 6pt, 圆心 (cx, cy)
```

### 4.4 SwiftUI 集成

```swift
// 在 FluidBackground 的 Canvas 中叠加渲染
// 在 HomeScreen 中，工具卡片点击时调用:
//   dropletAnimator.addDroplet(x: tapX/width, y: tapY/height)
```

---

## 五、主题配置

### 5.1 SwiftUI 完整主题

```swift
import SwiftUI

// MARK: - 色彩定义
extension Color {
    init(hex: String) {
        let scanner = Scanner(string: hex)
        _ = scanner.scanString("#")
        var rgb: UInt64 = 0
        scanner.scanHexInt64(&rgb)
        self.init(
            red: Double((rgb >> 16) & 0xFF) / 255.0,
            green: Double((rgb >> 8) & 0xFF) / 255.0,
            blue: Double(rgb & 0xFF) / 255.0
        )
    }
}

// MARK: - 玻璃 Modifier
struct GlassSurfaceModifier: ViewModifier { ... }
// (完整实现在"二、玻璃卡片渲染管线"中)

// MARK: - 流体背景
struct FluidBackgroundView: View {
    let time: CGFloat
    let droplets: [DropletState]

    var body: some View {
        Canvas { context, size in
            let w = size.width
            let h = size.height
            let t = time.truncatingRemainder(dividingBy: 6.2832)

            drawFluidBlobs(context: &context, w: w, h: h, t: t)
            drawGlowCircles(context: &context, w: w, h: h, t: t)
            drawFluidRipples(context: &context, w: w, h: h, t: t)
            drawParticles(context: &context, w: w, h: h, t: t)
            drawDroplets(context: &context, w: w, h: h, droplets: droplets)
        }
    }
    // ... 各 draw 方法实现
}

// MARK: - 扩展
extension View {
    func glassSurface(cornerRadius: CGFloat = 24, 
                      glassAlpha: CGFloat = 0.18,
                      showBorder: Bool = true) -> some View {
        self.modifier(GlassSurfaceModifier(
            cornerRadius: cornerRadius,
            glassAlpha: glassAlpha,
            showBorder: showBorder
        ))
    }
}

// MARK: - 顶部标题样式
struct GlassTitleStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .font(.system(size: 36, weight: .thin))
            .foregroundColor(Color(hex: "#F0F0F5").opacity(0.9))
            .kerning(12)
    }
}

// MARK: - 副标题样式
struct GlassSubtitleStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .font(.system(size: 11, weight: .light))
            .foregroundColor(Color.white.opacity(0.33))
            .kerning(6)
    }
}
```

---

## 六、关键动画曲线

### 6.1 无限动画

```swift
// 动画时间驱动: 0 → 100 循环，tween 100 秒, LinearEasing
// SwiftUI 实现:
let animation = Animation.linear(duration: 100).repeatForever(autoreverses: false)
```

### 6.2 脉冲动画

```swift
// 缩放范围: 0.85 → 1.15
// 时长: 1.8 秒/周期
// 缓动: EaseInOut
// 模式: 往返 (autoreverses: true)
// SwiftUI 实现:
let pulse = Animation.easeInOut(duration: 1.8).repeatForever(autoreverses: true)
```

### 6.3 旋转动画

```swift
// 角度范围: 0° → 360°
// 时长: 12 秒/周期
// 缓动: Linear
// SwiftUI 实现:
let rotate = Animation.linear(duration: 12).repeatForever(autoreverses: false)
```

### 6.4 卡片按压缩放

```swift
// 默认 scale: 1.0, 按下 scale: 0.93
// 回弹: spring(response: 0.3, dampingFraction: 0.4)
// SwiftUI 实现:
.scaleEffect(isPressed ? 0.93 : 1.0)
.animation(.spring(response: 0.3, dampingFraction: 0.4), value: isPressed)
```

### 6.5 屏幕切换动画

```swift
// 进入: fadeIn(0.35s) + scaleIn(0.94→1.0, easeOutCubic, 0.35s)
// 退出: fadeOut(0.2s)
// SwiftUI 实现:
.transition(.asymmetric(
    insertion: .opacity.animation(.easeInOut(duration: 0.35))
        .combined(with: .scale(scale: 0.94).animation(.timingCurve(0.33, 1, 0.68, 1, duration: 0.35))),
    removal: .opacity.animation(.easeInOut(duration: 0.2))
))
```

---

## 七、工具卡片规范

### 7.1 卡片尺寸

```swift
// 宽高比: 1:1 (正方形)
// 圆角: 24pt
// 玻璃透明度: 0.18
// 内边距: 居中内容
```

### 7.2 卡片内部布局

```swift
// 从上到下:
// 1. 图标背景圆 (50pt, 圆形, 渐变填充 15%透明度)
// 2. 间距 12pt
// 3. 标题文本 (15pt, Medium, 主文字色 90%透明度)
// 4. 间距 3pt
// 5. 副标题文本 (10pt, 三级文字色)
```

### 7.3 18 个工具卡片色系

```swift
let toolGradients: [(String, [Color])] = [
    ("时钟·天气",     [FluidCyan, FluidBlue]),
    ("计算器",        [FluidPurple, FluidPink]),
    ("音乐可视化",    [FluidTeal, FluidCyan]),
    ("待办清单",      [FluidBlue, FluidPurple]),
    ("倒计时秒表",    [FluidOrange, FluidPink]),
    ("日历日程",      [FluidCyan, FluidTeal]),
    ("记事本",        [FluidTeal, FluidBlue]),
    ("单位换算",      [FluidPurple, FluidCyan]),
    ("密码生成器",    [FluidBlue, FluidTeal]),
    ("二维码",        [FluidPink, FluidPurple]),
    ("健康计算",      [FluidPink, FluidOrange]),
    ("白噪音",        [FluidTeal, FluidPurple]),
    ("壁纸画廊",      [FluidCyan, FluidPurple]),
    ("涂鸦画板",      [FluidOrange, FluidPink]),
    ("颜色选择器",    [FluidPurple, FluidPink]),
    ("文件管理",      [FluidBlue, FluidCyan]),
    ("指南针水平仪",  [FluidTeal, FluidBlue]),
    ("手电筒",        [FluidOrange, FluidCyan]),
]
```

---

## 八、完整页面结构

### 8.1 主屏幕布局

```
┌──────────────────────────────┐
│     [状态栏占位] 44pt        │
│                              │
│  液态玻璃          (36sp Thin)│
│  LIQUID GLASS · 工具箱 (11sp)│
│                              │
│  ┌─────────┐ ┌─────────┐    │
│  │ 工具卡片  │ │ 工具卡片  │    │  2 列
│  │ 1:1 正方形│ │ 1:1 正方形│    │  可滚动
│  ├─────────┤ ├─────────┤    │  LazyVGrid
│  │ 工具卡片  │ │ 工具卡片  │    │
│  │          │ │          │    │
│  └─────────┘ └─────────┘    │
│                              │
│  ┌──────────────────────────┐│
│  │  关于 · 更新 · 创作者   ││  底部固定
│  └──────────────────────────┘│
│  JTQ Allen © 2026 · 18个工具 │
│     [导航栏占位]              │
└──────────────────────────────┘
```

### 8.2 页面切换

```swift
// 使用 enum 状态驱动
enum AppScreen {
    case home, clock, calculator, visualizer, todo, about
    case countdown, note, unitConverter, passwordGen, bmi
    case gallery, audioPlayer, fileManager, qrCode, drawing
    case compass, flashlight, colorPicker, calendar
}
```

---

## 九、iOS 实现注意事项

1. **Canvas 绘制顺序**：SwiftUI Canvas 中的绘制顺序即图层顺序，先绘制的在底层，后绘制的在上层。严格按照 8 层管线顺序绘制。

2. **性能优化**：
   - 流体背景 25 个粒子使用 Canvas 一次性绘制，避免 25 个独立 View
   - 波纹绘制使用逐行扫描优化，每 3pt 一行
   - 避免在 `body` 中创建大量临时对象

3. **动画精度**：
   - 使用 `CGFloat` 进行所有浮点计算
   - `time` 变量使用 `truncatingRemainder(dividingBy: 6.2832)` 避免精度丢失
   - 所有三角函数使用 `sin()` / `cos()` 标准库

4. **毛玻璃效果**：
   - iOS 原生支持 `UIVisualEffectView` 的 `.regular` / `.light` 模糊效果
   - 建议在玻璃卡片底层叠加 iOS 原生模糊 + 上述 8 层自定义绘制
   - 可获得更接近 iOS 原生毛玻璃的质感

5. **签名与发布**：
   - 应用名称：液态玻璃 · 灵动工具箱
   - Bundle ID：com.liquidglass.app
   - 最低版本：iOS 16.0（SwiftUI Canvas 和 animation 支持）
   - 图标：液态玻璃水滴形状，参考 Android 矢量图标 `ic_launcher_foreground.xml`

---

## 十、文件清单

```
LiquidGlassApp-iOS/
├── LiquidGlassApp.xcodeproj
├── Sources/
│   ├── App/
│   │   ├── LiquidGlassApp.swift        // @main App 入口
│   │   └── ContentView.swift           // 根视图（状态路由）
│   ├── Theme/
│   │   ├── Colors.swift                // 色彩系统
│   │   ├── GlassSurfaceModifier.swift  // 8 层玻璃渲染管线
│   │   ├── FluidBackgroundView.swift   // 流体背景
│   │   └── DropletAnimator.swift       // 水滴涟漪
│   ├── Screens/
│   │   ├── HomeScreen.swift            // 主屏幕
│   │   ├── ClockScreen.swift           // 时钟·天气
│   │   ├── CalculatorScreen.swift      // 计算器
│   │   ├── VisualizerScreen.swift      // 音乐可视化
│   │   └── ... (其余 14 个屏幕)
│   └── Extensions/
│       └── View+GlassSurface.swift     // glassSurface() 扩展
└── Resources/
    └── Assets.xcassets                 // 图标资源
```

---

*本文档基于 Android 平台"液态玻璃 · 灵动工具箱"v2.1.0 的实际代码逐行翻译整理，所有参数与 Android 版本完全一致。*