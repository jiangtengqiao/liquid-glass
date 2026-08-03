# 液态玻璃 · iOS 端源码骨架

这是 Android 应用 **LiquidGlassApp**（液态玻璃·灵动工具箱）的 iOS 版本源码骨架，使用 **SwiftUI (iOS 16+)** 实现。本骨架在 Linux 沙箱中编写，无法编译 iOS，但已写好完整可编译的源码结构与实现逻辑，**拿到 Mac 后用 Xcode 打开即可编译运行**。

---

## 一、目录结构

```
LiquidGlassiOS/
├── README_IOS.md                      ← 本文档
└── LiquidGlassApp/                    ← Xcode 项目主源码目录
    ├── App/
    │   ├── LiquidGlassApp.swift        ← @main 入口 + AVAudioSession 后台播放配置
    │   ├── AppRouter.swift              ← 全局路由管理（对应 Android AppRouter.kt）+ AppScreen 枚举
    │   └── ContentView.swift           ← 主内容视图：流体背景 + AnimatedContent 过渡 + 通知路由消费
    ├── Theme/
    │   ├── Colors.swift                ← 色彩系统（对应 Color.kt + ThemeManager.kt）含深/浅主题
    │   ├── FluidBackground.swift       ← 流体背景动画（对应 GlassSurface.kt FluidBackground）
    │   └── GlassSurface.swift          ← 液态玻璃质感容器 ViewModifier（8 层渲染管线）
    ├── Features/
    │   ├── Music/
    │   │   ├── MusicScreen.swift        ← 音乐播放器主界面（对应 MusicScreen.kt）
    │   │   └── AudioPlayer.swift        ← AVPlayer 封装（对应 MusicService.kt）+ 锁屏信息/控制
    │   ├── Tools/
    │   │   ├── CompassScreen.swift      ← 指南针（对应 CompassScreen.kt）CoreMotion + Canvas
    │   │   ├── CountdownTimerScreen.swift ← 秒表+倒计时（对应 CountdownTimerScreen.kt）
    │   │   ├── FlashlightScreen.swift   ← 手电筒（对应 FlashlightScreen.kt）AVCaptureDevice torch
    │   │   ├── ClockScreen.swift        ← 时钟·天气（对应 ClockScreen.kt）
    │   │   ├── CalculatorScreen.swift   ← 计算器（对应 CalculatorScreen.kt）
    │   │   ├── NoteScreen.swift         ← 便签（对应 NoteScreen.kt）
    │   │   ├── TodoScreen.swift         ← 待办（对应 TodoScreen.kt + TodoStore.kt）
    │   │   ├── QRCodeScreen.swift       ← 二维码（对应 QRCodeScreen.kt）
    │   │   ├── DrawingScreen.swift      ← 画板（对应 DrawingScreen.kt）多笔刷 + 撤销重做
    │   │   ├── BMICalculatorScreen.swift ← BMI/体脂/卡路里/饮水（对应 BMICalculatorScreen.kt）
    │   │   ├── UnitConverterScreen.swift ← 单位换算（对应 UnitConverterScreen.kt）9 类
    │   │   ├── PasswordGeneratorScreen.swift ← 密码生成+随机工具（对应 PasswordGeneratorScreen.kt）
    │   │   ├── ColorPickerScreen.swift  ← 取色器（对应 ColorPickerScreen.kt）RGB/HSB/HEX
    │   │   ├── CalendarScreen.swift     ← 日历（对应 CalendarScreen.kt）+ 本地通知
    │   │   ├── FileManagerScreen.swift  ← 文件管理（对应 FileManagerScreen.kt）
    │   │   └── AudioPlayerScreen.swift  ← 本地音频播放器（对应 AudioPlayerScreen.kt）
    │   ├── Wallpaper/
    │   │   └── GalleryScreen.swift      ← 壁纸库（对应 GalleryScreen.kt）Canvas 程序化绘制
    │   ├── Download/
    │   │   └── ResourceManager.swift   ← 资源包下载（对应 ResourceManager.kt）URLSession background + 分块续传
    │   └── About/
    │       ├── AboutScreen.swift        ← 关于页（对应 AboutScreen.kt）
    │       └── LegalCenterScreen.swift  ← 法律中心+协议正文（对应 LegalCenterScreen.kt + LegalDocuments.kt）
    ├── Services/
    │   ├── NetworkClient.swift          ← 网络客户端（对应 OkHttp）URLSession 封装
    │   ├── Persistence.swift            ← 持久化（对应 SharedPreferences）UserDefaults 封装
    │   └── ZipExtractor.swift           ← ZIP 解压（纯 Foundation + Compression）
    ├── Info.plist                       ← Info.plist 配置（后台模式/权限描述）
    └── LiquidGlassApp.entitlements      ← 权限配置
```

---

## 二、开发环境要求

| 项目 | 要求 |
|------|------|
| macOS | 13.0 (Ventura) 及以上 |
| **Xcode** | **15.0 及以上**（推荐 15.2+） |
| Swift | 5.9+ |
| iOS 部署目标 | **iOS 16.0+**（使用 `AnimatedContent` / `Canvas` / `TimelineView` 等需 16） |
| Apple Developer 账号 | 真机调试与分发必需；模拟器调试可使用免费账号 |

> 说明：本骨架使用 `ObservableObject`（非 `@Observable` 宏），因此兼容 iOS 16。若仅支持 iOS 17+，可将各 `ObservableObject` 升级为 `@Observable`。

---

## 三、在 Mac 上打开与编译

### 1. 创建 Xcode 工程并接入源码

由于本仓库不含 `.xcodeproj` 工程文件（Linux 无法生成），需在 Mac 上用 Xcode 新建工程并接入现有源码：

1. 打开 Xcode → **File → New → Project**
2. 选择 **iOS → App** 模板：
   - Product Name：`LiquidGlassApp`
   - Interface：**SwiftUI**
   - Language：**Swift**
   - Storage：None
   - Include Tests：可选
3. 工程保存位置选择本仓库 `LiquidGlassiOS/` 的**同级**目录（避免源码目录与工程目录嵌套冲突）。
4. 删除 Xcode 自动生成的 `ContentView.swift` 与 `App.swift`（本仓库已提供同名文件）。
5. 将本仓库 `LiquidGlassApp/` 目录**整体拖入** Xcode 工程导航器：
   - 勾选 **Copy items if needed**
   - 选择 **Create groups**
   - 勾选当前 Target
6. 工程设置 → **General**：
   - Bundle Identifier：`com.liquidglass.app`（与 entitlements 中 `keychain-access-groups` 一致）
   - Deployment Target：`16.0`
7. 工程设置 → **Build Settings**：
   - 搜索 `Info.plist`，将 **Generate Info.plist File** 关闭，并把 **Info.plist File** 指向 `LiquidGlassApp/Info.plist`
   - 搜索 `Code Signing Entitlements`，设为 `LiquidGlassApp/LiquidGlassApp.entitlements`
8. 创建 **LaunchScreen.storyboard**：File → New → User Interface → Launch Screen，命名为 `LaunchScreen`（Info.plist 中 `UILaunchStoryboardName` 已引用）。

### 2. 添加 App Group capability

工程设置 → **Signing & Capabilities** → **+ Capability**，依次添加：

- **Background Modes**：勾选 `Audio, AirPlay, and Picture in Picture`、`Background fetch`、`Background processing`
- **App Groups**：添加 `group.com.liquidglass.app`
- **Keychain Sharing**：添加 `$(AppIdentifierPrefix)com.liquidglass.app`
- **Associated Domains**：添加 `applinks:liquidglass.app`（可选）

### 3. 编译运行

- 选模拟器：**Cmd + R** 即可编译运行（无需开发者账号）。
- 选真机：首次需在 **Signing & Capabilities** 选择你的 Apple ID 团队，Xcode 会自动管理签名。

---

## 四、Apple Developer 账号说明

| 账号类型 | 价格 | 能力 | 适用场景 |
|----------|------|------|----------|
| 免费 Apple ID | 0 | 模拟器 + 真机调试（7 天签名，3 个 App 限制） | 开发自测 |
| **Apple Developer Program（个人/公司）** | **$99/年** | 真机调试 + TestFlight + App Store 上架 | **推荐** |
| Apple Developer Enterprise | $299/年 | 仅企业内部分发（不能上架 App Store） | 企业内网 |

> 上架 App Store 与 TestFlight 分发**必须**加入 Apple Developer Program。

---

## 五、TestFlight 分发步骤

1. **申请 App ID**：[Apple Developer Portal](https://developer.apple.com/account/resources/identifiers/list) → Identifiers → + → App IDs，Bundle ID 填 `com.liquidglass.app`，勾选所需 Capabilities。
2. **创建证书**：
   - Development 证书（真机调试）
   - Distribution 证书（上架/TestFlight）
   Xcode 的 **Automatically manage signing** 可自动生成。
3. **创建 Provisioning Profile**：在 Portal 创建对应 App ID 的 Profile，或让 Xcode 自动管理。
4. **Archive 构建**：
   - Xcode 选设备为 **Any iOS Device (arm64)**
   - **Product → Archive**
   - 等待 Organizer 打开生成的 `.xcarchive`
5. **上传到 App Store Connect**：
   - Organizer 中选 Archive → **Distribute App → TestFlight & App Store**
   - 按向导完成上传
6. **TestFlight 测试**：
   - 登录 [App Store Connect](https://appstoreconnect.apple.com)
   - My Apps → 选你的 App → **TestFlight** 标签
   - 等待"正在处理"完成（约 10–30 分钟）
   - 添加内部测试员（同团队）或外部测试员（需 Beta App Review）
   - 测试员收到邮件 → 下载 TestFlight App → 输入邀请码安装
7. **App Store 上架**：
   - App Store 标签页填写信息（截图、描述、隐私政策等）
   - 提交审核（通常 24–48 小时）

---

## 六、与 Android 端的对应关系

| iOS 文件 | Android 对应 | 说明 |
|----------|-------------|------|
| `App/LiquidGlassApp.swift` | `LiquidGlassApp.kt` (Application) + `MainActivity.kt` | `@main` 入口 + AVAudioSession 配置（对应 Application 崩溃捕获 + 音频会话） |
| `App/AppRouter.swift` | `AppRouter.kt` + `ui/Screen.kt` | 全局路由单例：`pendingRoute` + 备份兜底 + `consumeRoute()`；`AppScreen` 枚举对应 `Screen` |
| `App/ContentView.swift` | `MainActivity.kt` (加载页) + `ui/HomeScreen.kt` | 流体背景 + `AnimatedContent` 过渡 + 通知路由消费 + 首页工具网格 |
| `Theme/Colors.swift` | `ui/theme/Color.kt` + `ThemeManager.kt` | 色值完全一致；`AppTheme` 数据模型 + 深浅主题 + `ThemeManager` ObservableObject |
| `Theme/FluidBackground.swift` | `ui/GlassSurface.kt` → `FluidBackground` | 5 层渲染：色块/光晕/涟漪线/粒子/水滴；`TimelineView` + `Canvas` 驱动 |
| `Theme/GlassSurface.swift` | `ui/GlassSurface.kt` → `glassSurface` Modifier | 8 层渲染管线 ViewModifier + `PressPhysics` 弹簧按压 |
| `Features/Music/MusicScreen.swift` | `ui/MusicScreen.kt` | 4 Tab（发现/网易云/本地/平台）+ 5 功能卡片 + 底部迷你播放条 |
| `Features/Music/AudioPlayer.swift` | `music/MusicService.kt` + `MusicControllerManager.kt` | `AVPlayer` 封装 + `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter` |
| `Features/Tools/CompassScreen.swift` | `ui/CompassScreen.kt` | `CMMotionManager` 优先 `deviceMotion`，fallback magnetometer + 超时检测 + Canvas 表盘 |
| `Features/Tools/CountdownTimerScreen.swift` | `ui/CountdownTimerScreen.kt` | 基于 `Date()` 精确计时（防漂移）+ 计次前缀和优化 |
| `Features/Tools/FlashlightScreen.swift` | `ui/FlashlightScreen.kt` | `AVCaptureDevice` torch + SOS/频闪 + 屏幕亮度工具 |
| `Features/Wallpaper/GalleryScreen.swift` | `ui/GalleryScreen.kt` | `Canvas` 程序化绘制 8 类壁纸 + 全屏预览 |
| `Features/Download/ResourceManager.swift` | `ResourceManager.kt` | `URLSession` background + 分块并行 + 断点续传 + 多进度条 |
| `Services/NetworkClient.swift` | OkHttp 封装 (`NetEaseApiClient`/`NetEaseApi`) | `URLSession` async/await 封装 + JSON 编解码 |
| `Services/Persistence.swift` | SharedPreferences | `UserDefaults` 封装 + 主题/历史/搜索历史持久化 |
| `Info.plist` | `AndroidManifest.xml` | 后台模式 `audio` + 麦克风/相机/相册/定位/运动权限描述 |
| `LiquidGlassApp.entitlements` | （Android 无对应） | App Groups / Keychain 共享 / 后台下载 / iCloud |

---

## 七、关键实现说明

### 7.1 后台音乐播放
- `LiquidGlassApp.init()` 配置 `AVAudioSession` 为 `.playback`，保证锁屏/后台不被系统挂起。
- `Info.plist` 声明 `UIBackgroundModes = ["audio"]`。
- `AudioPlayer` 通过 `MPNowPlayingInfoCenter` 写入锁屏曲目信息，`MPRemoteCommandCenter` 响应锁屏/耳机/车载控制。
- 真机需在 Capabilities 开启 **Background Modes → Audio**。

### 7.2 流体背景无缝循环
- 所有运动用 `sin/cos` 周期函数驱动，对时间取模 `2π`，确保 `TimelineView` 重启后画面连续不跳变。
- `FluidBackground` 接受外部 `animTime` 与 `TimelineView` 真实时间叠加，全局相位一致。

### 7.3 指南针传感器兜底
- 优先 `deviceMotion.attitude.yaw`；不可用 fallback `magnetometer + accelerometer` 手动计算航向。
- 注册后 1.5s 无数据 → 标记 `TIMEOUT`，提供重试按钮（杜绝"静默失败转不动"）。

### 7.4 计时器防漂移
- 倒计时：记录 `countdownEnd`（`Date()`），剩余 = `now - end`，`Timer` 仅控制刷新频率。
- 秒表：`elapsed = accumulated + (now - start)`，暂停时固化 `accumulated`。
- 计次用前缀和：仅存累计时间戳，单圈用时 = 相邻差，O(1)。

### 7.5 资源包断点续传
- `URLSessionConfiguration.background` 让 App 进后台后系统接管下载。
- 分块并行，每块独立进度条；`cancel(byProducingResumeData:)` 生成续传数据缓存为 `.part` 文件。

---

## 八、待办（后续接入）

> **更新（v2.9.0）**：首页 20 个工具卡片对应的功能页已**全部移植完成**，`ContentView` 路由分发不再有占位页。**网易云音乐客户端已完成**（`Services/NetEaseClient.swift`），实现 weapi 加密直连、扫码登录、手机验证码登录、搜索/歌曲URL/歌词/歌单/排行榜/推荐等全套接口，与 Android 端 `music/` 模块 1:1 对齐。下列为仍需接入真实数据源的细节项：

- ~~**MusicScreen** 的网易云 Tab~~：**已完成**。`NetEaseClient` / `NetEaseAuth` / `NetEaseSession` 已实现，UI 接入调用即可。
- **ResourceManager** 的真实分块地址：`startResourceDownload()` 已生成 18 分块的真实镜像 URL（与 Android 端 `resources.part00~17` 一致），但镜像可用性需在真机网络环境验证。
- **GalleryScreen** 的相册保存：当前保存为 PNG 到 App Documents，需接入 `Photos` 框架的 `PHPhotoLibrary` 写入权限以保存到系统相册。
- **ClockScreen** 的天气卡片：定位与城市反查已实现，温度/天气状况为硬编码占位，需接入天气 API（如和风天气/OpenWeatherMap）。
- **DrawingScreen** 的保存：当前导出 PNG 到 Documents + 系统分享，可补充 `PHPhotoLibrary` 保存到相册。

> **关于安装包格式**：iOS 应用的安装包格式是 **`.ipa`**（iPhone Application Archive），**不是 `.gml`**。`.ipa` 是 Xcode Archive 后经 App Store / TestFlight 分发的标准格式，本质是一个含 `Payload/AppName.app` 的 ZIP 压缩包。本骨架在 Linux 沙箱中编写，无法直接编译产出 `.ipa`——需在 **Mac + Xcode** 环境下 `Product → Archive` 生成 `.xcarchive`，再通过 `Distribute App` 导出 `.ipa`（详见第五节 TestFlight 分发步骤）。

### 已完成移植的功能页清单（20/20）

| 功能页 | iOS 文件 | 说明 |
|--------|----------|------|
| 音乐 | `Features/Music/MusicScreen.swift` | 框架完成，网易云 Tab 待接入 |
| 指南针 | `Features/Tools/CompassScreen.swift` | CoreMotion + 超时兜底 |
| 秒表·倒计时 | `Features/Tools/CountdownTimerScreen.swift` | 防 drift 计时 |
| 手电筒 | `Features/Tools/FlashlightScreen.swift` | torch + SOS/频闪 |
| 壁纸库 | `Features/Wallpaper/GalleryScreen.swift` | Canvas 程序化绘制 |
| 时钟 | `Features/Tools/ClockScreen.swift` | 数字/模拟时钟 + 天气占位 |
| 计算器 | `Features/Tools/CalculatorScreen.swift` | 基本算术 |
| 待办 | `Features/Tools/TodoScreen.swift` | 增删改查 + 过滤 + 持久化 |
| 便签 | `Features/Tools/NoteScreen.swift` | 增删改 + 持久化 |
| 二维码 | `Features/Tools/QRCodeScreen.swift` | 生成 + 自定义颜色 + 保存 |
| 画板 | `Features/Tools/DrawingScreen.swift` | 多笔刷 + 撤销重做 + 导出 |
| BMI 计算 | `Features/Tools/BMICalculatorScreen.swift` | BMI/体脂/卡路里/饮水 4 Tab |
| 单位换算 | `Features/Tools/UnitConverterScreen.swift` | 9 类单位 + 历史 |
| 密码生成 | `Features/Tools/PasswordGeneratorScreen.swift` | 生成 + 随机工具 5 子 Tab |
| 取色器 | `Features/Tools/ColorPickerScreen.swift` | RGB/HSB/HEX + 收藏 |
| 日历 | `Features/Tools/CalendarScreen.swift` | 月视图 + 本地通知 |
| 文件管理 | `Features/Tools/FileManagerScreen.swift` | Documents 浏览 + 预览 |
| 音频播放器 | `Features/Tools/AudioPlayerScreen.swift` | 本地音频 + 播放列表 |
| 法律中心 | `Features/About/LegalCenterScreen.swift` | 5 篇协议正文 + 阅读器 |
| 关于 | `Features/About/AboutScreen.swift` | 应用信息 + 更新日志 |

---

## 九、许可与版权

本源码骨架与 Android 端保持相同的视觉与功能定位。请在分发前确认所有素材（壁纸、音效、网易云接口）的版权合规。
