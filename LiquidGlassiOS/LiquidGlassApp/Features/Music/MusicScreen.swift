import SwiftUI

// ─────────────────────────────────────────────────────────────────
// 音乐播放器主界面 —— 对应 Android 端 ui/MusicScreen.kt
//
// 结构：
//   - 顶部栏（返回 + 标题 + 当前页面名）
//   - Tab 切换：发现 / 网易云 / 本地 / 平台
//   - 5 个功能入口卡片：搜索 / 队列 / 音质 / 睡眠 / 歌词设置
//   - 底部迷你播放条（封面 + 标题 + 进度 + 播放/暂停）
//   - 子页面（搜索/队列/音质/睡眠/歌词设置/NowPlaying）通过 AnimatedContent 切换
// ─────────────────────────────────────────────────────────────────

private enum MusicTab: String, CaseIterable {
    case discover  = "发现"
    case netease   = "网易云"
    case local      = "本地"
    case platform   = "平台"
}

private enum MusicPage: Equatable {
    case main, search, queue, quality, sleep, lyrics, nowPlaying
}

struct MusicScreen: View {
    let animTime: Double
    let onBack: () -> Void

    @StateObject private var player = AudioPlayer.shared
    @State private var selectedTab: MusicTab = .netease
    @State private var page: MusicPage = .main
    @State private var searchKeyword: String = ""

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: animTime, theme: theme)

            VStack(spacing: 0) {
                topBar(theme: theme)

                // 主内容区：根据 page 与 tab 渲染
                AnimatedContent(targetState: page, animation: .easeInOut(duration: 0.3)) { p in
                    switch p {
                    case .main:
                        mainContent(theme: theme)
                    case .search:
                        searchPage(theme: theme)
                    case .queue:
                        queuePage(theme: theme)
                    case .quality:
                        qualityPage(theme: theme)
                    case .sleep:
                        sleepPage(theme: theme)
                    case .lyrics:
                        lyricsPage(theme: theme)
                    case .nowPlaying:
                        nowPlayingPage(theme: theme)
                    }
                }

                // 底部迷你播放条
                miniPlayerBar(theme: theme)
            }
            .padding(.top, 50) // 状态栏空间
            .padding(.bottom, 20)
        }
        // 播放错误提示（VIP/无版权等）
        .onChange(of: player.error) { err in
            guard err != nil else { return }
            // iOS 端用 overlay 提示替代 Android Toast
            showError = true
        }
        .overlay(alignment: .top) {
            if showError, let err = player.error {
                Text(err)
                    .font(.caption)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(AccentDanger.opacity(0.9), in: Capsule())
                    .padding(.top, 60)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .task {
                        try? await Task.sleep(nanoseconds: 2_200_000_000)
                        showError = false
                        player.consumeError()
                    }
            }
        }
    }

    @State private var showError: Bool = false

    // MARK: - 顶部栏
    private func topBar(theme: AppTheme) -> some View {
        HStack(spacing: 12) {
            Button {
                if page != .main { page = .main } else { onBack() }
            } label: {
                Image(systemName: "chevron.left")
                    .foregroundStyle(theme.textSecondary)
            }
            Text(pageTitle)
                .font(.headline)
                .foregroundStyle(theme.textPrimary)
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    private var pageTitle: String {
        switch page {
        case .main:       return "音乐"
        case .search:     return "搜索"
        case .queue:      return "播放队列"
        case .quality:    return "音质调节"
        case .sleep:      return "睡眠定时器"
        case .lyrics:     return "歌词设置"
        case .nowPlaying: return "正在播放"
        }
    }

    // MARK: - 主内容区（Tab + 5 功能卡片 + Tab 内容占位）
    private func mainContent(theme: AppTheme) -> some View {
        ScrollView {
            VStack(spacing: 16) {
                // Tab 切换条
                HStack(spacing: 0) {
                    ForEach(MusicTab.allCases, id: \.self) { tab in
                        Button {
                            selectedTab = tab
                        } label: {
                            Text(tab.rawValue)
                                .font(.subheadline.weight(selectedTab == tab ? .semibold : .regular))
                                .foregroundStyle(selectedTab == tab ? theme.textPrimary : theme.textTertiary)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                                .background(
                                    selectedTab == tab
                                    ? theme.fluidCyan.opacity(0.15)
                                    : Color.clear,
                                    in: RoundedRectangle(cornerRadius: 12)
                                )
                        }
                    }
                }
                .padding(4)
                .glassSurface(cornerRadius: 18, glassAlpha: 0.10, theme: theme)

                // 5 个功能入口卡片（搜索/队列/音质/睡眠/歌词设置）
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                    featureCard(icon: "magnifyingglass", title: "搜索", color: theme.fluidCyan, theme: theme) {
                        page = .search
                    }
                    featureCard(icon: "list.bullet", title: "队列", color: theme.fluidPurple, theme: theme) {
                        page = .queue
                    }
                    featureCard(icon: "waveform.badge.checkmark", title: "音质", color: theme.fluidTeal, theme: theme) {
                        page = .quality
                    }
                    featureCard(icon: "moon.zzz.fill", title: "睡眠", color: theme.fluidBlue, theme: theme) {
                        page = .sleep
                    }
                    featureCard(icon: "text.alignleft", title: "歌词", color: theme.fluidPink, theme: theme) {
                        page = .lyrics
                    }
                }

                // 当前 Tab 内容占位
                tabContentPlaceholder(theme: theme)
            }
            .padding(.horizontal, 16)
        }
    }

    private func featureCard(icon: String, title: String, color: Color, theme: AppTheme, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 22))
                    .foregroundStyle(color)
                Text(title)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(theme.textPrimary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .glassSurface(cornerRadius: 18, glassAlpha: 0.12, theme: theme)
        }
    }

    private func tabContentPlaceholder(theme: AppTheme) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "music.note.list")
                .font(.system(size: 36))
                .foregroundStyle(theme.fluidCyan.opacity(0.7))
            Text("\(selectedTab.rawValue) · 内容区")
                .font(.subheadline)
                .foregroundStyle(theme.textSecondary)
            Text("此处接入对应 Tab 的歌曲/歌单列表")
                .font(.caption)
                .foregroundStyle(theme.textTertiary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
        .glassSurface(cornerRadius: 20, theme: theme)
    }

    // MARK: - 子页面（骨架占位，带返回主页面按钮）
    private func subPage(title: String, icon: String, hint: String, theme: AppTheme) -> some View {
        VStack(spacing: 16) {
            Image(systemName: icon)
                .font(.system(size: 40))
                .foregroundStyle(theme.fluidPurple)
            Text(title)
                .font(.title3.weight(.semibold))
                .foregroundStyle(theme.textPrimary)
            Text(hint)
                .font(.caption)
                .foregroundStyle(theme.textTertiary)
                .multilineTextAlignment(.center)
            Button("返回音乐主页") { page = .main }
                .foregroundStyle(theme.fluidCyan)
        }
        .padding(32)
        .glassSurface(cornerRadius: 24, theme: theme)
    }

    private func searchPage(theme: AppTheme) -> some View {
        VStack(spacing: 16) {
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(theme.textSecondary)
                TextField("搜索歌曲/歌手", text: $searchKeyword)
                    .foregroundStyle(theme.textPrimary)
                    .submitLabel(.search)
                    .onSubmit {
                        guard !searchKeyword.isEmpty else { return }
                        Persistence.shared.appendSearchHistory(searchKeyword)
                    }
            }
            .padding(12)
            .glassSurface(cornerRadius: 16, glassAlpha: 0.10, theme: theme)
            subPage(title: "搜索结果", icon: "magnifyingglass",
                    hint: "调用 NetEaseApi / 本地扫描返回搜索结果", theme: theme)
        }
        .padding(.horizontal, 16)
    }

    private func queuePage(theme: AppTheme) -> some View {
        subPage(title: "播放队列", icon: "list.bullet",
                hint: "显示当前队列，支持拖动排序与移除", theme: theme)
            .padding(.horizontal, 16)
    }

    private func qualityPage(theme: AppTheme) -> some View {
        subPage(title: "音质调节", icon: "waveform.badge.checkmark",
                hint: "标准 / 高品 / 无损 / Hi-Res 切换", theme: theme)
            .padding(.horizontal, 16)
    }

    private func sleepPage(theme: AppTheme) -> some View {
        subPage(title: "睡眠定时器", icon: "moon.zzz.fill",
                hint: "倒计时结束后自动停止播放", theme: theme)
            .padding(.horizontal, 16)
    }

    private func lyricsPage(theme: AppTheme) -> some View {
        subPage(title: "歌词设置", icon: "text.alignleft",
                hint: "字体大小 / 对齐 / 翻译 / 卡拉OK 高亮", theme: theme)
            .padding(.horizontal, 16)
    }

    private func nowPlayingPage(theme: AppTheme) -> some View {
        VStack(spacing: 20) {
            // 封面（占位用渐变方块）
            RoundedRectangle(cornerRadius: 24)
                .fill(LinearGradient(colors: [theme.fluidCyan, theme.fluidPurple],
                                     startPoint: .topLeading, endPoint: .bottomTrailing))
                .frame(height: 280)

            // 标题
            VStack(spacing: 4) {
                Text(player.snapshot.currentSong?.title ?? "未在播放")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(theme.textPrimary)
                Text(player.snapshot.currentSong?.artist ?? "")
                    .font(.caption)
                    .foregroundStyle(theme.textSecondary)
            }

            // 进度条
            ProgressView(value: progressValue(theme: theme))
                .tint(theme.fluidCyan)
                .padding(.horizontal, 8)

            // 控制按钮
            HStack(spacing: 40) {
                Button { player.previous() } label: {
                    Image(systemName: "backward.fill").font(.title2).foregroundStyle(theme.textPrimary)
                }
                Button { player.togglePlayPause() } label: {
                    Image(systemName: player.snapshot.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 64))
                        .foregroundStyle(theme.fluidCyan)
                }
                Button { player.next() } label: {
                    Image(systemName: "forward.fill").font(.title2).foregroundStyle(theme.textPrimary)
                }
            }
        }
        .padding(20)
        .glassSurface(cornerRadius: 28, theme: theme)
        .padding(.horizontal, 16)
    }

    private func progressValue(theme: AppTheme) -> Double {
        let dur = max(player.snapshot.duration, 0.1)
        return min(1, max(0, player.snapshot.position / dur))
    }

    // MARK: - 底部迷你播放条
    @ViewBuilder
    private func miniPlayerBar(theme: AppTheme) -> some View {
        if let song = player.snapshot.currentSong {
            HStack(spacing: 12) {
                // 封面占位
                RoundedRectangle(cornerRadius: 10)
                    .fill(LinearGradient(colors: [theme.fluidPurple, theme.fluidPink],
                                         startPoint: .topLeading, endPoint: .bottomTrailing))
                    .frame(width: 44, height: 44)

                VStack(alignment: .leading, spacing: 2) {
                    Text(song.title)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(theme.textPrimary)
                        .lineLimit(1)
                    Text(song.artist)
                        .font(.system(size: 10))
                        .foregroundStyle(theme.textTertiary)
                        .lineLimit(1)
                    ProgressView(value: progressValue(theme: theme))
                        .tint(theme.fluidCyan)
                        .scaleEffect(y: 0.6)
                }

                Spacer()

                Button { player.togglePlayPause() } label: {
                    Image(systemName: player.snapshot.isPlaying ? "pause.fill" : "play.fill")
                        .font(.title3)
                        .foregroundStyle(theme.textPrimary)
                }
                Button { page = .nowPlaying } label: {
                    Image(systemName: "music.note")
                        .font(.title3)
                        .foregroundStyle(theme.textSecondary)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .glassSurface(cornerRadius: 20, glassAlpha: 0.15, theme: theme)
            .padding(.horizontal, 12)
            .onTapGesture { page = .nowPlaying }
        }
    }
}
