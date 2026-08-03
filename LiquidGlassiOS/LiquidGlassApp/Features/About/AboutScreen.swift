import SwiftUI

// ─────────────────────────────────────────────────────────────────
// 关于 / 更新日志 —— 对应 Android 端 ui/AboutScreen.kt
//
// 关键实现：
//   1. 应用图标用渐变圆形 + 水滴图标占位（drop.fill），与液态玻璃主题契合
//   2. 更新日志用 DisclosureGroup 折叠展开，多版本支持（当前仅 1.0.0）
//   3. 链接区用 Link + glassSurface 卡片，点击直接外跳
// ─────────────────────────────────────────────────────────────────

private let kAboutGithubURL = "https://github.com/liquidglass/liquidglass-ios"
private let kAboutFeedbackEmail = "feedback@liquidglass.app"

// MARK: - 数据模型
/// 单个版本的更新日志条目。
private struct ChangelogVersion: Identifiable {
    let id = UUID()
    let version: String   // 形如 "2.9.0"
    let date: String
    let entries: [String]

    /// 大版本号（"2.9.0" → "2.9"）
    var major: String {
        let parts = version.split(separator: ".")
        guard parts.count >= 2 else { return version }
        return "\(parts[0]).\(parts[1])"
    }
    /// 超级大版号（"2.9.0" → "2"）
    var superMajor: String {
        version.split(separator: ".").first.map(String.init) ?? version
    }
}

/// 超级大版折叠组：1.x → 超级大版 1，2.x → 超级大版 2
private struct ChangelogSuperMajor: Identifiable {
    let id = UUID()
    let superMajor: String                       // "1" / "2"
    let majorGroups: [(major: String, versions: [ChangelogVersion])]
}

// MARK: - 主视图
struct AboutScreen: View {
    var onBack: () -> Void

    /// 默认展开最新超级大版 + 最新大版本 + 最新小版本。
    @State private var expandedSuperMajors: Set<String> = ["2"]
    @State private var expandedMajors: Set<String> = ["2.9"]
    @State private var expandedVersions: Set<String> = ["2.9.0"]

    private let changelog: [ChangelogVersion] = [
        ChangelogVersion(
            version: "1.0.0",
            date: "2026.07.31",
            entries: [
                "iOS端首发版本",
                "液态玻璃设计系统(8层渲染管线)",
                "流体背景动画(5层粒子系统)",
                "音乐播放器(AVPlayer+锁屏控制)",
                "指南针(CoreMotion)",
                "手电筒(AVCaptureDevice)",
                "倒计时/秒表",
                "壁纸库(Canvas程序化绘制)"
            ]
        ),
        ChangelogVersion(
            version: "2.9.0",
            date: "2026.07.31",
            entries: [
                "大版本·正式步入2.9",
                "网易云全功能直连(weapi加密+BigInt RSA+扫码登录+手机验证码登录)",
                "搜索/歌曲URL/歌词(逐字yrc+逐行lrc+翻译)/用户歌单/排行榜/推荐/私人FM/新歌/相似歌曲",
                "Cookie持久化(UserDefaults+HTTPCookieStorage双写,自动捕获Set-Cookie)",
                "指南针终极修复(CoreMotion deviceMotion优先,磁力计兜底,1.5s超时检测)",
                "音乐字体扩展至9种(系统内置字体族,锁屏歌词字体修复)",
                "主题精简至6套精品深色(移除浅色与纯黑不可用主题)",
                "下载系统单源优先策略(主镜像不截断,仅连接失败切fallback,杜绝进度条横跳,显示真实分块文件名)",
                "更新日志超级大版折叠(1.x融合为超级大版1,2.x融合为超级大版2)"
            ]
        )
    ]

    /// 按超级大版分组：1.x → 超级大版 1，2.x → 超级大版 2
    private var superMajorGroups: [ChangelogSuperMajor] {
        let grouped = Dictionary(grouping: changelog, by: { $0.superMajor })
        return grouped.keys.sorted(by: >).map { sm in
            let versions = grouped[sm]!.sorted { $0.version > $1.version }
            let majGrouped = Dictionary(grouping: versions, by: { $0.major })
            let majPairs = majGrouped.keys.sorted(by: >).map { maj -> (major: String, versions: [ChangelogVersion]) in
                (maj, majGrouped[maj]!.sorted { $0.version > $1.version })
            }
            return ChangelogSuperMajor(superMajor: sm, majorGroups: majPairs)
        }
    }

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: 0, theme: theme)

            VStack(spacing: 16) {
                topBar(theme: theme)

                ScrollView {
                    VStack(spacing: 16) {
                        appHeader(theme: theme)
                        changelogSection(theme: theme)
                        linksSection(theme: theme)
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 24)
                }
            }
            .padding(.top, 50)
            .padding(.bottom, 24)
        }
    }

    // MARK: - 顶部栏
    private func topBar(theme: AppTheme) -> some View {
        HStack {
            Button { onBack() } label: {
                Image(systemName: "chevron.left").foregroundStyle(theme.textSecondary)
            }
            Text("关于").font(.headline).foregroundStyle(theme.textPrimary)
            Spacer()
        }
        .padding(.horizontal, 16)
    }

    // MARK: - 应用头部
    private func appHeader(theme: AppTheme) -> some View {
        VStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [theme.fluidCyan, theme.fluidPurple, theme.fluidTeal],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 96, height: 96)
                    .shadow(color: theme.fluidCyan.opacity(0.4), radius: 16)
                Image(systemName: "drop.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(.white)
            }
            .padding(.top, 8)

            Text("液态玻璃")
                .font(.title2.weight(.bold))
                .foregroundStyle(theme.textPrimary)

            Text("v2.9.0")
                .font(.subheadline)
                .foregroundStyle(theme.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 20)
        .glassSurface(cornerRadius: 24, glassAlpha: 0.15, theme: theme)
    }

    // MARK: - 更新日志（三级折叠：超级大版 → 大版本 → 小版本）
    private func changelogSection(theme: AppTheme) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "list.bullet.rectangle")
                    .foregroundStyle(theme.fluidCyan)
                Text("更新日志")
                    .font(.headline)
                    .foregroundStyle(theme.textPrimary)
                Spacer()
            }
            .padding(.horizontal, 4)

            ForEach(superMajorGroups) { sm in
                superMajorGroup(sm, theme: theme)
            }
        }
    }

    /// 超级大版折叠组（最外层）
    private func superMajorGroup(_ sm: ChangelogSuperMajor, theme: AppTheme) -> some View {
        let totalVersions = sm.majorGroups.reduce(0) { $0 + $1.versions.count }
        return DisclosureGroup(
            isExpanded: Binding(
                get: { expandedSuperMajors.contains(sm.superMajor) },
                set: { isExpanded in
                    if isExpanded { expandedSuperMajors.insert(sm.superMajor) }
                    else { expandedSuperMajors.remove(sm.superMajor) }
                }
            )
        ) {
            VStack(spacing: 8) {
                ForEach(sm.majorGroups, id: \.major) { mg in
                    majorGroup(mg.major, versions: mg.versions, theme: theme)
                }
            }
            .padding(.top, 12)
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "circle.hexagongrid.fill")
                    .foregroundStyle(theme.fluidPurple)
                VStack(alignment: .leading, spacing: 2) {
                    Text("超级大版 v\(sm.superMajor)")
                        .font(.headline)
                        .foregroundStyle(theme.fluidPurple)
                    Text("\(sm.majorGroups.count) 个大版本 · \(totalVersions) 个小版本")
                        .font(.caption)
                        .foregroundStyle(theme.textTertiary)
                }
                Spacer()
            }
        }
        .padding(16)
        .glassSurface(cornerRadius: 20, glassAlpha: 0.18, theme: theme)
    }

    /// 大版本折叠组（中间层）
    private func majorGroup(_ major: String, versions: [ChangelogVersion], theme: AppTheme) -> some View {
        DisclosureGroup(
            isExpanded: Binding(
                get: { expandedMajors.contains(major) },
                set: { isExpanded in
                    if isExpanded { expandedMajors.insert(major) }
                    else { expandedMajors.remove(major) }
                }
            )
        ) {
            VStack(spacing: 8) {
                ForEach(versions) { v in
                    versionEntry(v, theme: theme)
                }
            }
            .padding(.top, 10)
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "square.stack.3d.up.fill")
                    .foregroundStyle(theme.fluidCyan)
                Text("v\(major).x")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(theme.textPrimary)
                Spacer()
            }
        }
        .padding(14)
        .glassSurface(cornerRadius: 16, glassAlpha: 0.15, theme: theme)
    }

    /// 单个小版本条目（最内层）
    private func versionEntry(_ version: ChangelogVersion, theme: AppTheme) -> some View {
        DisclosureGroup(
            isExpanded: Binding(
                get: { expandedVersions.contains(version.version) },
                set: { isExpanded in
                    if isExpanded { expandedVersions.insert(version.version) }
                    else { expandedVersions.remove(version.version) }
                }
            )
        ) {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(version.entries, id: \.self) { entry in
                    HStack(alignment: .top, spacing: 8) {
                        Circle()
                            .fill(theme.fluidCyan)
                            .frame(width: 5, height: 5)
                            .padding(.top, 6)
                        Text(entry)
                            .font(.subheadline)
                            .foregroundStyle(theme.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .padding(.top, 12)
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text("v\(version.version)")
                    .font(.headline)
                    .foregroundStyle(theme.textPrimary)
                Text(version.date)
                    .font(.caption)
                    .foregroundStyle(theme.textTertiary)
            }
        }
        .padding(16)
        .glassSurface(cornerRadius: 16, glassAlpha: 0.15, theme: theme)
    }

    // MARK: - 链接区
    private func linksSection(theme: AppTheme) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "link")
                    .foregroundStyle(theme.fluidPurple)
                Text("相关链接")
                    .font(.headline)
                    .foregroundStyle(theme.textPrimary)
                Spacer()
            }
            .padding(.horizontal, 4)

            Link(destination: URL(string: kAboutGithubURL)!) {
                linkRow(icon: "globe",
                        title: "GitHub 仓库",
                        subtitle: "github.com/liquidglass/liquidglass-ios",
                        iconColor: theme.fluidCyan,
                        theme: theme)
            }
            .buttonStyle(.plain)

            Link(destination: URL(string: "mailto:\(kAboutFeedbackEmail)")!) {
                linkRow(icon: "envelope",
                        title: "意见反馈",
                        subtitle: kAboutFeedbackEmail,
                        iconColor: theme.fluidTeal,
                        theme: theme)
            }
            .buttonStyle(.plain)
        }
    }

    private func linkRow(icon: String, title: String, subtitle: String,
                         iconColor: Color, theme: AppTheme) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(iconColor)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(theme.textPrimary)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(theme.textTertiary)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundStyle(theme.textTertiary)
        }
        .padding(16)
        .glassSurface(cornerRadius: 16, glassAlpha: 0.15, theme: theme)
    }
}
