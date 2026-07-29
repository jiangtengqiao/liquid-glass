# 灵工坊

<p align="center">
  <img src="https://img.shields.io/badge/version-2.1.0-00D4FF?style=for-the-badge" alt="Version">
  <img src="https://img.shields.io/badge/API-26%2B-7B5CFC?style=for-the-badge" alt="API">
  <img src="https://img.shields.io/badge/APK-11MB-00E5A0?style=for-the-badge" alt="APK Size">
  <img src="https://img.shields.io/badge/资源包-530MB-FF3B8B?style=for-the-badge" alt="Resource Pack">
  <img src="https://img.shields.io/badge/工具模块-18个-3366FF?style=for-the-badge" alt="Tools">
  <img src="https://img.shields.io/badge/license-MIT-FF6B35?style=for-the-badge" alt="License">
</p>

<p align="center">
  <b>一款拥有液态玻璃 UI 风格的 Android 多功能工具箱应用，18 个实用工具集合</b>
</p>

<p align="center">
  <a href="https://jiangtengqiao.github.io/liquid-glass/">下载页面</a> ·
  <a href="https://github.com/jiangtengqiao/liquid-glass/releases/download/v2.1.0/liquid-glass-v2.1.0.apk">直接下载 APK</a> ·
  <a href="#-更新日志">更新日志</a> ·
  <a href="#-技术架构">技术架构</a>
</p>

---

## 概述

**液态玻璃 · 灵动工具箱** 是一款基于 Android 原生开发（Kotlin + Jetpack Compose）构建的多功能工具集合应用。其核心设计理念源自 iOS 平台广受赞誉的毛玻璃（Frosted Glass）与液态玻璃（Liquid Glass）视觉语言，通过多层半透明叠加、动态光晕折射、高光反射和流体粒子动画，在 Android 平台上实现了与 iOS 同等甚至超越的通透玻璃质感。

本应用采用 **轻量 APK + 两阶段在线资源加载** 的架构设计：APK 本体仅 11MB，安装后首次冷启动会自动连接 GitHub Release 服务器依次下载基础资源包（530MB）和交互资源包（液态玻璃物理引擎配置），实现"小而美"的安装体验与"大而全"的功能体验的完美平衡。冷启动加载页面不可跳过，拥有优雅的入场/退场动画，确保每次启动都有沉浸式体验。

### 物理引擎驱动

应用内置完整的 `FluidEngine v2` 物理引擎，包含 6 个物理模拟模块：

- **SpringPhysics** — iOS 标准临界阻尼弹簧，驱动卡片按压回弹和交互反馈
- **FluidField** — Navier-Stokes 简化流体场，驱动背景流动色块的速度场
- **MetaballPhysics** — 液态金属球融合物理，模拟水滴表面张力
- **RippleField** — 波动方程涟漪传播，水滴涟漪交互的物理基础
- **RefractionModel** — Snell 折射定律，计算光线穿过玻璃的弯曲
- **FresnelEffect** — 菲涅尔边缘反射，物理光学驱动玻璃边缘反射强度

---

## 特性亮点

### 视觉设计

- **8 层液态玻璃渲染管线**：从基底毛玻璃 → 发光边框 → 顶部强反射高光 → 斜向高光 → 弧形高光条 → 底部环境光 → 浮动光斑 → 彩虹色散折射，每一层都经过精心调校，确保在任何亮度背景下都能呈现通透的玻璃质感
- **动态流体背景**：6 个随机游走色块 + 4 个浮动光晕 + 4 组正弦波纹 + 25 个彩色粒子，构成持续流动的液态背景动画
- **水滴涟漪交互**：点击任意工具卡片时，从点击位置扩散出多层同心圆涟漪动画，模拟水滴落入液态表面的物理效果
- **5 种流体渐变色系**：青色（Cyan）、紫色（Purple）、粉色（Pink）、蓝色（Blue）、青色（Teal），贯穿整个应用的所有 UI 组件
- **极简深色基底**：`#08080F` 的极暗背景色，为玻璃效果提供最佳对比度画布

### 核心工具模块（18 个）

| 模块 | 功能描述 | 技术亮点 |
|------|----------|----------|
| **时钟·天气** | 实时时钟 + 全球天气 + 城市搜索 + 世界时钟 | IP 定位秒开 + GPS 后台静默更新；Open-Meteo API；10 个国际城市实时时钟 |
| **科学计算器** | 基础四则 + 科学计算 + 记忆功能 + 历史记录 | 三角函数/对数/指数/阶乘/幂运算；MC/MR/M+/M-/MS 五键记忆；RAD/DEG 切换；2nd 功能层 |
| **音乐可视化** | 模拟频谱动画 | 3 种模式：柱状频谱 / 环形频谱 / 波形频谱；64 频段模拟 + 平滑插值；多层波形叠加 |
| **待办清单** | 任务管理 | 液态玻璃卡片式管理；滑动交互 |
| **倒计时秒表** | 计时与计次 | 毫秒级精度 |
| **日历日程** | 事件管理 | 月度视图 |
| **记事本** | 轻量笔记 | 快速记录 |
| **单位换算** | 10 类单位转换 | 长度/面积/体积/质量/温度/速度/时间/数据/压力/角度 |
| **密码生成器** | 随机工具集合 | 可配置复杂度 |
| **二维码** | 生成与识别 | 即时生成 |
| **健康计算** | BMI·体脂·卡路里 | 多维度健康指标 |
| **白噪音** | 助眠·专注·放松 | 多种音频资源 |
| **壁纸画廊** | 程序化艺术壁纸 | 高清资源 |
| **涂鸦画板** | 自由绘画 | 多点触控 |
| **颜色选择器** | 取色与配色 | 精确HEX值 |
| **文件管理** | 浏览与管理 | 本地文件系统 |
| **指南针水平仪** | 方向与水平 | 传感器数据 |
| **手电筒** | 闪光灯工具 | 相机闪光灯控制 |

---

## 技术架构

### 技术栈

```
语言:        Kotlin 2.0.21
UI框架:      Jetpack Compose (BOM 2024.12.01)
构建工具:    Gradle 8.7.3 + AGP 8.7.3
最低SDK:     Android 8.0 (API 26)
目标SDK:     Android 14 (API 34)
编译SDK:     Android 15 (API 35)
架构模式:    单 Activity + Compose Navigation (状态驱动)
签名:        V1 + V2 完整签名
```

### 项目结构

```
LiquidGlassApp/
├── app/
│   ├── build.gradle.kts              # 应用级构建配置
│   ├── release.keystore              # 发布签名密钥
│   └── src/main/
│       ├── AndroidManifest.xml       # 应用清单（权限、组件声明）
│       ├── java/com/liquidglass/app/
│       │   ├── MainActivity.kt       # 入口 Activity
│       │   ├── ResourceManager.kt    # 在线资源下载管理器
│       │   └── ui/
│       │       ├── HomeScreen.kt     # 主屏幕（工具网格）
│       │       ├── LoadingScreen.kt  # 启动加载页（资源下载进度）
│       │       ├── GlassSurface.kt   # 液态玻璃效果核心引擎
│       │       ├── AboutScreen.kt    # 关于页面（更新检查）
│       │       ├── ClockScreen.kt    # 时钟·天气模块
│       │       ├── CalculatorScreen.kt  # 科学计算器模块
│       │       ├── VisualizerScreen.kt  # 音乐可视化模块
│       │       ├── TodoScreen.kt     # 待办清单模块
│       │       ├── CountdownTimerScreen.kt  # 倒计时秒表模块
│       │       ├── NoteScreen.kt     # 记事本模块
│       │       ├── UnitConverterScreen.kt   # 单位换算模块
│       │       ├── PasswordGeneratorScreen.kt # 密码生成器模块
│       │       ├── BMICalculatorScreen.kt    # BMI计算器模块
│       │       ├── GalleryScreen.kt  # 图片画廊模块
│       │       ├── AudioPlayerScreen.kt      # 音频播放器模块
│       │       ├── FileManagerScreen.kt      # 文件管理器模块
│       │       ├── QRCodeScreen.kt   # 二维码生成器模块
│       │       ├── DrawingScreen.kt  # 涂鸦画板模块
│       │       ├── CompassScreen.kt  # 指南针模块
│       │       ├── FlashlightScreen.kt       # 手电筒模块
│       │       ├── ColorPickerScreen.kt      # 颜色选择器模块
│       │       ├── CalendarScreen.kt # 日历模块
│       │       └── theme/
│       │           ├── Color.kt      # 色彩系统定义
│       │           └── Theme.kt      # Material3 主题配置
│       └── res/
│           ├── drawable/
│           │   ├── ic_launcher_foreground.xml  # 液态玻璃水滴图标
│           │   └── ic_launcher_background.xml  # 图标背景
│           ├── mipmap-*/                        # 自适应图标
│           ├── values/
│           │   ├── strings.xml                 # 字符串资源
│           │   └── themes.xml                  # 原生主题
│           └── xml/
│               └── file_paths.xml              # FileProvider 路径配置
├── build.gradle.kts                   # 项目级构建配置
├── settings.gradle.kts
└── gradle.properties
```

---

## 液态玻璃效果设计体系

### 色彩系统

| 色值 | 变量名 | 用途 |
|------|--------|------|
| `#08080F` | `BgDark` | 极暗背景色 |
| `#0D0D1A` | `BgDark2` | 次级深色背景 |
| `#00D4FF` | `FluidCyan` | 流体青色（主强调色） |
| `#7B5CFC` | `FluidPurple` | 流体紫色 |
| `#FF3B8B` | `FluidPink` | 流体粉色 |
| `#3366FF` | `FluidBlue` | 流体蓝色 |
| `#00E5A0` | `FluidTeal` | 流体青色 |
| `#FF6B35` | `FluidOrange` | 流体橙色 |
| `#F0F0F5` | `TextPrimary` | 主要文字色 |
| `#99FFFFFF` | `TextSecondary` | 次要文字色 |
| `#55FFFFFF` | `TextTertiary` | 三级文字色 |

### 玻璃透明度层级

| 层级 | Alpha值 | 用途 |
|------|---------|------|
| `GlassClear` | 0.06 (6%) | 基底 |
| `GlassLight` | 0.09 (9%) | 轻量化玻璃 |
| `GlassMedium` | 0.13 (13%) | 中等玻璃 |
| `GlassBorder` | 0.16 (16%) | 边框 |
| `GlassHighlight` | 0.21 (21%) | 高光 |
| `GlassBright` | 0.31 (31%) | 亮色玻璃 |

### 玻璃渲染管线（8 层叠加）

1. **基底毛玻璃层** — `Color.White.copy(alpha = glassAlpha * 0.6f)` 填充圆角矩形
2. **多层发光边框** — 三层嵌套描边，alpha 逐层递减（0.30 / 0.20 / 0.10）
3. **顶部强反射高光** — 由左上到右上的渐变路径，模拟光源照射毛玻璃表面的反射
4. **左上角斜向高光** — 从 (0,0) 到 (38%,38%) 的三角形渐变区域
5. **顶部弧形高光条** — 沿顶部边缘的贝塞尔曲线光带，模拟曲面玻璃的弧面反射
6. **底部边缘环境光** — 从底部向上渐变的微弱光晕
7. **浮动光斑** — 右上角和左下角的固定圆形光斑
8. **彩虹色散折射** — 三层不同颜色的描边（Cyan → Purple → Pink），模拟棱镜色散

---

## 资源加载机制

应用采用"先安装、后加载"的策略：

### 启动流程

```
用户安装 APK (11MB)
    ↓
首次启动 → LoadingScreen
    ↓
检查本地资源 (.installed 标记文件)
    ↓
┌── 已安装 → 跳过下载 → 进入主界面
└── 未安装 → 连接 GitHub Release
              ↓
         下载 resources.zip (530MB)
              ↓
         显示进度条 + 下载速度
              ↓
         解压到 filesDir/resources/
              ↓
         写入 .installed 标记
              ↓
         进入主界面
```

### 资源包内容

- **raw/**: 10 个白噪音音频文件（WAV 格式）
- **assets/**: 25 个程序化资源数据文件（每个 20MB）
- **assets/**: 多张高清壁纸图片（PNG 格式）

### 容错机制

- 3 次自动重试，每次间隔 2 秒
- 支持"跳过"按钮直接进入应用（部分功能不可用）
- 下载失败时显示详细错误信息 + 重试按钮
- 断点续传支持（通过 HTTP Range 请求）

---

## 更新机制

应用内置自动更新检查功能：

1. **更新检测**：从 GitHub Raw 获取 `version.json`，比对 `versionCode`
2. **多 URL 尝试**：优先使用 GitHub Raw URL，失败时自动切换备用 URL
3. **增量下载**：检测到新版本后，从 GitHub Release 下载最新 APK
4. **静默安装**：下载完成后通过 FileProvider 触发系统安装器
5. **版本文件**：[version.json](https://raw.githubusercontent.com/jiangtengqiao/liquid-glass/main/version.json)

---

## 构建与部署

### 环境要求

- JDK 17+
- Android SDK 35
- Gradle 8.7.3
- Kotlin 2.0.21

### 构建命令

```bash
# 克隆仓库
git clone https://github.com/jiangtengqiao/liquid-glass.git
cd LiquidGlassApp

# 构建 Release APK
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease

# APK 输出路径
# app/build/outputs/apk/release/app-release.apk
```

### 发布流程

1. 构建 APK → `./gradlew assembleRelease`
2. 准备资源包 → 打包 `resources.zip`
3. 创建 GitHub Release → `gh release create vX.Y.Z`
4. 上传 APK + 资源包 → `gh release upload`
5. 更新 `version.json` → `git push`

---

## 开发计划

### 已完成

- [x] v1.0.0 — 液态玻璃UI + 核心4工具
- [x] v1.1.0 — 天气秒开 + 城市搜索 + 世界时钟
- [x] v2.0.0 — 14个新工具模块
- [x] v2.1.0 — 在线资源加载 + 品牌升级

### 规划中

- [ ] 更多传感器工具（气压计、测距仪、分贝仪）
- [ ] AI 智能助手集成
- [ ] 桌面小组件（Widget）
- [ ] 深色/浅色模式切换
- [ ] 多语言国际化支持
- [ ] 云端数据同步
- [ ] 插件化架构

---

## 许可证

本项目采用 MIT 许可证。详见 [LICENSE](LICENSE) 文件。

---

## 联系方式

- **创作者**: JTQ Allen
- **电话/微信**: 18978332931
- **QQ邮箱**: 3982206481@qq.com
- **备用邮箱**: jiangtengqiao@qq.com
- **Outlook**: jiangtengqiao@outlook.com
- **GitHub**: [@jiangtengqiao](https://github.com/jiangtengqiao)

---

## 更新日志

<details open>
<summary><b>v2.1.0</b> (2026-07-29) — 灵工坊</summary>

### 重大更新
- **全新品牌升级**：应用名称正式更名为"灵工坊"，定位为多功能智能工具箱平台
- **iOS 级液态玻璃 + 物理引擎**：8 层渲染管线 + `FluidEngine v2` 物理引擎（弹簧物理、Navier-Stokes 流体场、Metaball 水滴融合、波动方程涟漪、Snell 折射定律、菲涅尔效应）
- **两阶段在线资源加载**：阶段1基础资源包 → 阶段2交互资源包，双阶段进度指示器，阶段2预览显示
- **冷启动加载页**：每次冷启动显示优雅加载动画，可跳过，关于页提供后台下载入口
- **关于页资源管理器**：查看资源包安装状态、大小，手动下载/重新下载资源包，进度和速度实时显示
- **更新日志折叠展开**：应用内更新日志改为折叠展开式，默认展开最新版本，点击版本号展开/收起
- **丝滑联动切换动画**：屏幕切换采用缩放+淡入+滑动的组合动画，EaseOutCubic/EaseInCubic 缓动曲线
- **启动加载页**：液态玻璃风格进度界面，双阶段指示器，旋转光环+脉冲六边形图标
- **全新应用图标**：六边形工坊矢量图标，渐变+高光+"工"字内部设计

### 功能优化
- 资源下载支持 3 次自动重试，每次间隔 2 秒
- 下载进度实时显示（百分比 + 已下载/总大小 + 速度）
- 资源解压进度可视化
- 完善的错误处理与重试机制（仅允许重试，不可跳过）

### 技术改进
- 新增 `FluidEngine.kt` 物理引擎，6 个物理模拟模块
- 新增 `ResourceManager.kt` 两阶段资源管理器，支持基础资源 + 交互资源
- 新增 `LoadingScreen.kt` 启动加载页面，8 种状态管理
- `PressPhysics` 物理弹簧驱动卡片按压形变
- 菲涅尔效应动态计算玻璃边缘反射强度
- 资源下载支持 HTTP 重定向、自定义 User-Agent、超时配置

### 版本信息
- versionCode: 4
- APK 大小: 11MB
- 基础资源包: 530MB
- 交互资源包: 液态玻璃物理引擎配置

</details>

<details>
<summary><b>v2.0.0</b> (2026-07-29) — 18个工具模块</summary>

### 新增工具模块（14个）
- **倒计时秒表**：高精度计时器，支持计次功能，液态玻璃风格按钮
- **记事本**：轻量级笔记管理，支持快速记录与编辑
- **单位换算**：10 类单位转换（长度、面积、体积、质量、温度、速度、时间、数据存储、压力、角度），实时换算
- **密码生成器**：可配置密码复杂度，随机密码生成
- **BMI 健康计算**：BMI 指数计算、体脂率估算、卡路里消耗计算
- **壁纸画廊**：程序化艺术壁纸展示，支持浏览与设为壁纸
- **白噪音播放器**：多首白噪音音频（雨声、海浪、森林等），助眠专注放松
- **文件管理器**：本地文件系统浏览，支持文件操作
- **二维码生成器**：即时生成二维码，支持自定义内容
- **涂鸦画板**：自由绘画创作，多点触控支持
- **指南针水平仪**：利用设备传感器，实时方向指示与水平检测
- **手电筒**：控制相机闪光灯，支持常亮模式
- **颜色选择器**：取色器 + 配色方案，支持 HEX 值精确输入
- **日历日程**：月度日历视图，支持事件管理与提醒

### 视觉增强
- 液态玻璃效果全面增强：玻璃透明度从 0.10 提升到 0.18，边框更明显
- 新增更多流体光晕层和粒子效果
- 卡片交互反馈优化（按压缩放动画）

### 架构改进
- 首页工具网格从 2×2 扩展为 2×9 可滚动网格
- 每个工具卡片使用独特的渐变色系
- 屏幕切换动画优化（淡入淡出 + 滑动 + 缩放组合）

</details>

<details>
<summary><b>v1.1.0</b> (2026-07-28) — 天气世界时钟</summary>

### 天气模块优化
- **IP 定位秒开**：使用 ip-api.com 进行 IP 地理定位，启动即可获取天气信息，无需等待 GPS
- **GPS 后台静默更新**：授权定位权限后，GPS 在后台更新更精确的位置数据
- **6 秒超时 fallback**：GPS 定位 6 秒内未返回结果，自动 fallback 到 IP 定位
- **城市搜索**：支持输入任意城市名称搜索全球城市天气，使用 Open-Meteo Geocoding API
- **世界时钟**：10 个国际城市实时时钟（北京、东京、纽约、伦敦、巴黎、悉尼、迪拜、洛杉矶、莫斯科、孟买），显示白天/夜间状态

### 更新机制优化
- 更新检查 URL 改为 GitHub Raw 稳定地址
- 多 URL 尝试机制，确保更新检查可靠性
- 支持从 GitHub Release 下载最新版本 APK

### 视觉增强
- 液态玻璃效果增强：更通透的毛玻璃质感，增加顶部弧形高光条
- 天气卡片 UI 优化：逐小时预报横向滚动，7 日预报温度进度条
- 世界时钟卡片：国旗 Emoji + 城市名 + 日期 + 实时时间

</details>

<details>
<summary><b>v1.0.0</b> (2026-07-28) — 初始发布</summary>

### 核心功能
- **液态玻璃通透 UI 全面实现**：8 层渲染管线，动态流体背景，水滴涟漪交互
- **时钟 + 实时天气**：当前时间显示，实时天气数据（温度、湿度、风速、气压、体感温度），7 日天气预报，逐小时预报
- **科学计算器**：基础四则运算 + 科学计算（三角函数、对数、指数、开方、阶乘），历史记录，记忆功能（MC/MR/M+/M-/MS），RAD/DEG 角度切换
- **音乐可视化**：3 种频谱模式（柱状图、环形图、波形图），64 频段模拟 + 平滑插值，多层波形叠加
- **待办清单**：液态玻璃卡片式任务管理，滑动交互
- **定位权限申请**：动态权限请求，支持 GPS 精确定位和网络粗略定位
- **IP 地理定位备选**：当 GPS 不可用时，使用 IP 定位获取大致位置
- **内嵌更新检查**：关于页面内置更新检测与下载功能
- **创作者信息**：联系方式展示，一键拨号，存入通讯录

### 技术基础
- Material3 + Jetpack Compose 原生 UI
- 深色主题色彩系统
- 自定义 GlassSurface Modifier
- FluidBackground 动态背景组件
- DropletAnimator 水滴涟漪动画系统

</details>

---

<p align="center">
  <sub>Built with ❤️ by JTQ Allen © 2026</sub>
</p>