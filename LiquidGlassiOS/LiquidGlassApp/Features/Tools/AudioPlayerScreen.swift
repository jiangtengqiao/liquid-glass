import SwiftUI
import AVFoundation
import UniformTypeIdentifiers

// ─────────────────────────────────────────────────────────────────
// 音频播放器（本地音频文件）—— 对应 Android 端 ui/AudioPlayerScreen.kt
//
// 说明：
//   Android 端 AudioPlayerScreen 为"环境音效"合成器（PCM 实时合成）。
//   iOS 端按产品规划改为"本地音频文件播放器"：扫描 App Documents 目录中的
//   音频文件（.mp3/.m4a/.wav/.aac/.flac），构建播放列表，支持播放/暂停、
//   上一首/下一首、拖动进度、循环/随机模式。底层使用 AVPlayer + AVAudioSession。
//
// 关键实现：
//   1. SimpleAudioPlayer：独立的 ObservableObject 播放器，封装 AVPlayer，
//      维护队列与循环/随机模式（复用 AudioPlayer.swift 中定义的 RepeatMode）。
//      不注册 MPRemoteCommandCenter，避免与 MusicScreen 的 AudioPlayer.shared 冲突。
//   2. DocumentAudioScanner：扫描 Documents 目录，递归收集音频文件，
//      并用 AVURLAsset 异步加载时长。
//   3. 通过 .fileImporter 导入音频文件到 Documents 目录，便于用户使用。
// ─────────────────────────────────────────────────────────────────

// MARK: - 本地音频文件模型

/// 单个本地音频文件条目。
struct LocalAudioFile: Identifiable, Sendable {
    let id = UUID()
    let url: URL
    let title: String            // 文件名（去扩展名）
    let duration: TimeInterval   // 秒，未知时为 0
}

// MARK: - 本地音频播放器

/// 独立的本地音频播放器（不复用 AudioPlayer.shared，避免与在线音乐功能冲突）。
final class SimpleAudioPlayer: NSObject, ObservableObject {

    /// 支持的音频扩展名。
    static let supportedExtensions: [String] = ["mp3", "m4a", "wav", "aac", "flac"]

    // MARK: - 对外可观察状态
    @Published private(set) var queue: [LocalAudioFile] = []
    @Published private(set) var currentIndex: Int = -1
    @Published private(set) var isPlaying: Bool = false
    @Published private(set) var position: TimeInterval = 0
    @Published private(set) var duration: TimeInterval = 0
    @Published private(set) var isBuffering: Bool = false
    @Published var shuffleEnabled: Bool = false
    @Published var repeatMode: RepeatMode = .off
    @Published var error: String?

    // MARK: - 内部 AVPlayer 资源
    private var player: AVPlayer?
    private var timeObserverToken: Any?
    private var statusObservation: NSKeyValueObservation?
    private var itemEndObserver: NSObjectProtocol?

    override init() {
        super.init()
        configureAudioSession()
    }

    /// 当前正在播放的文件。
    var currentFile: LocalAudioFile? {
        guard queue.indices.contains(currentIndex) else { return nil }
        return queue[currentIndex]
    }

    /// 当前正在播放的文件 URL（用于列表高亮比对）。
    var currentURL: URL? { currentFile?.url }

    // MARK: - 队列操作

    /// 设置播放队列并从指定下标开始播放。
    func setQueue(_ files: [LocalAudioFile], startAt index: Int = 0) {
        queue = files
        guard !files.isEmpty else {
            stop()
            return
        }
        let idx = max(0, min(index, files.count - 1))
        play(at: idx)
    }

    /// 播放单个文件（若不在队列中则追加）。
    func playFile(_ file: LocalAudioFile) {
        if let idx = queue.firstIndex(where: { $0.url == file.url }) {
            play(at: idx)
        } else {
            queue.append(file)
            play(at: queue.count - 1)
        }
    }

    // MARK: - 播放控制

    private func play(at index: Int) {
        guard queue.indices.contains(index) else { return }
        currentIndex = index
        let file = queue[index]
        let item = AVPlayerItem(url: file.url)

        statusObservation?.invalidate()
        statusObservation = item.observe(\.status, options: [.new]) { [weak self] observed, _ in
            DispatchQueue.main.async {
                switch observed.status {
                case .readyToPlay:
                    self?.isBuffering = false
                    if let dur = observed.duration.seconds, dur.isFinite, dur > 0 {
                        self?.duration = dur
                    } else {
                        self?.duration = file.duration
                    }
                case .failed:
                    self?.isBuffering = false
                    self?.error = "播放失败：\(observed.error?.localizedDescription ?? "未知错误")"
                case .unknown:
                    self?.isBuffering = true
                @unknown default:
                    break
                }
            }
        }

        if player == nil {
            player = AVPlayer(playerItem: item)
            observeItemEnd()
            addPeriodicTimeObserver()
        } else {
            player?.replaceCurrentItem(with: item)
        }
        duration = file.duration
        position = 0
        isBuffering = true
        play()
    }

    func togglePlayPause() {
        isPlaying ? pause() : play()
    }

    func play() {
        guard player != nil, currentIndex >= 0 else { return }
        do {
            try AVAudioSession.sharedInstance().setActive(true, options: [])
        } catch {
            #if DEBUG
            print("[SimpleAudioPlayer] 激活会话失败: \(error)")
            #endif
        }
        player?.play()
        isPlaying = true
    }

    func pause() {
        player?.pause()
        isPlaying = false
    }

    func stop() {
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        isPlaying = false
        position = 0
        duration = 0
        currentIndex = -1
    }

    func next() {
        guard !queue.isEmpty else { return }
        if repeatMode == .one {
            seek(to: 0)
            play()
            return
        }
        let nextIndex: Int
        if shuffleEnabled {
            if queue.count == 1 {
                nextIndex = currentIndex
            } else {
                repeat {
                    nextIndex = Int.random(in: 0..<queue.count)
                } while nextIndex == currentIndex
            }
        } else {
            var idx = currentIndex + 1
            if idx >= queue.count {
                if repeatMode == .all { idx = 0 } else { pause(); return }
            }
            nextIndex = idx
        }
        play(at: nextIndex)
    }

    func previous() {
        guard !queue.isEmpty else { return }
        if position > 3 {
            seek(to: 0)
            return
        }
        var prevIndex = currentIndex - 1
        if prevIndex < 0 {
            prevIndex = repeatMode == .all ? queue.count - 1 : 0
        }
        play(at: prevIndex)
    }

    /// 跳转到指定秒数（拖动进度条）。
    func seek(to seconds: TimeInterval) {
        let target = CMTime(seconds: seconds, preferredTimescale: 600)
        player?.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            DispatchQueue.main.async {
                self?.position = seconds
            }
        }
    }

    // MARK: - 模式切换

    func toggleShuffle() { shuffleEnabled.toggle() }

    func cycleRepeatMode() {
        switch repeatMode {
        case .off: repeatMode = .all
        case .all: repeatMode = .one
        case .one: repeatMode = .off
        }
    }

    func consumeError() { error = nil }

    // MARK: - AVAudioSession

    private func configureAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])
            try AVAudioSession.sharedInstance().setActive(true, options: [])
        } catch {
            #if DEBUG
            print("[SimpleAudioPlayer] 配置会话失败: \(error)")
            #endif
        }
    }

    // MARK: - AVPlayer 装配

    private func observeItemEnd() {
        guard let player else { return }
        itemEndObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.next()
        }
        _ = player
    }

    private func addPeriodicTimeObserver() {
        guard let player else { return }
        let interval = CMTime(seconds: 0.5, preferredTimescale: 600)
        timeObserverToken = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            guard let self else { return }
            self.position = time.seconds
            if let item = self.player?.currentItem,
               item.duration.seconds.isFinite, item.duration.seconds > 0 {
                self.duration = item.duration.seconds
            }
        }
    }

    deinit {
        if let token = timeObserverToken { player?.removeTimeObserver(token) }
        if let observer = itemEndObserver { NotificationCenter.default.removeObserver(observer) }
        statusObservation?.invalidate()
    }
}

// MARK: - Documents 目录扫描器

/// 扫描 App Documents 目录中的音频文件，并异步加载时长；支持导入外部音频文件。
final class DocumentAudioScanner: ObservableObject {

    @Published private(set) var files: [LocalAudioFile] = []
    @Published var isLoading: Bool = false

    private let documentsURL: URL

    init() {
        documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    /// 递归扫描 Documents 目录，收集支持的音频文件并加载时长。
    func scan() {
        isLoading = true
        let urls = collectAudioURLs(in: documentsURL)
        Task { [weak self] in
            var results: [LocalAudioFile] = []
            for url in urls {
                let duration = await Self.loadDuration(for: url)
                results.append(LocalAudioFile(
                    url: url,
                    title: url.deletingPathExtension().lastPathComponent,
                    duration: duration
                ))
            }
            results.sort {
                $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending
            }
            await MainActor.run {
                self?.files = results
                self?.isLoading = false
            }
        }
    }

    /// 将外部音频文件复制到 Documents 目录（保留原文件名）。
    /// 仅复制扩展名在支持列表内的文件。
    @discardableResult
    func importFile(from sourceURL: URL) -> Bool {
        let ext = sourceURL.pathExtension.lowercased()
        guard SimpleAudioPlayer.supportedExtensions.contains(ext) else { return false }

        let didStart = sourceURL.startAccessingSecurityScopedResource()
        defer { if didStart { sourceURL.stopAccessingSecurityScopedResource() } }

        let dest = documentsURL.appendingPathComponent(sourceURL.lastPathComponent)
        do {
            if FileManager.default.fileExists(atPath: dest.path) {
                try FileManager.default.removeItem(at: dest)
            }
            try FileManager.default.copyItem(at: sourceURL, to: dest)
            return true
        } catch {
            #if DEBUG
            print("[DocumentAudioScanner] 导入失败: \(error)")
            #endif
            return false
        }
    }

    // MARK: - 内部

    private func collectAudioURLs(in dir: URL) -> [URL] {
        var result: [URL] = []
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else {
            return result
        }
        for entry in entries {
            var isDir: ObjCBool = false
            if fm.fileExists(atPath: entry.path, isDirectory: &isDir) {
                if isDir.boolValue {
                    result.append(contentsOf: collectAudioURLs(in: entry))
                } else {
                    let ext = entry.pathExtension.lowercased()
                    if SimpleAudioPlayer.supportedExtensions.contains(ext) {
                        result.append(entry)
                    }
                }
            }
        }
        return result
    }

    static func loadDuration(for url: URL) async -> TimeInterval {
        let asset = AVURLAsset(url: url)
        do {
            let cmTime = try await asset.load(.duration)
            let s = cmTime.seconds
            return s.isFinite && s > 0 ? s : 0
        } catch {
            return 0
        }
    }
}

// MARK: - 主视图

struct AudioPlayerScreen: View {
    var onBack: () -> Void

    @StateObject private var player = SimpleAudioPlayer()
    @StateObject private var scanner = DocumentAudioScanner()

    @State private var showImporter: Bool = false
    @State private var draggingPosition: Double? = nil
    @State private var showError: Bool = false

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: 0, theme: theme)

            VStack(spacing: 16) {
                topBar(theme: theme)

                ScrollView {
                    VStack(spacing: 16) {
                        nowPlayingCard(theme: theme)
                        fileListSection(theme: theme)
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 24)
                }
            }
            .padding(.top, 50)
            .padding(.bottom, 20)
        }
        .onAppear {
            scanner.scan()
        }
        .fileImporter(
            isPresented: $showImporter,
            allowedContentTypes: [.audio],
            allowsMultipleSelection: true
        ) { result in
            switch result {
            case .success(let urls):
                var imported = 0
                for url in urls {
                    if scanner.importFile(from: url) { imported += 1 }
                }
                if imported > 0 { scanner.scan() }
            case .failure:
                player.error = "导入音频文件失败"
            }
        }
        .onChange(of: player.error) { err in
            guard err != nil else { return }
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

    // MARK: - 顶部栏
    private func topBar(theme: AppTheme) -> some View {
        HStack {
            Button { onBack() } label: {
                Image(systemName: "chevron.left")
                    .font(.title3)
                    .foregroundStyle(theme.textSecondary)
            }
            Spacer()
            Text("音频播放器")
                .font(.headline)
                .foregroundStyle(theme.textPrimary)
            Spacer()
            Button { showImporter = true } label: {
                Image(systemName: "plus.circle")
                    .font(.title3)
                    .foregroundStyle(theme.textSecondary)
            }
            Button { scanner.scan() } label: {
                Image(systemName: "arrow.clockwise")
                    .font(.title3)
                    .foregroundStyle(theme.textSecondary)
            }
        }
        .padding(.horizontal, 20)
    }

    // MARK: - 正在播放卡片
    private func nowPlayingCard(theme: AppTheme) -> some View {
        let file = player.currentFile
        let isPlaying = player.isPlaying

        return VStack(spacing: 14) {
            // 封面占位（渐变 + 音符图标）
            ZStack {
                RoundedRectangle(cornerRadius: 20)
                    .fill(LinearGradient(
                        colors: [theme.fluidCyan, theme.fluidPurple, theme.fluidTeal],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ))
                Image(systemName: isPlaying ? "waveform" : "music.note")
                    .font(.system(size: 44))
                    .foregroundStyle(.white)
            }
            .frame(height: 150)
            .clipShape(RoundedRectangle(cornerRadius: 20))

            // 标题
            VStack(spacing: 4) {
                Text(file?.title ?? "未在播放")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(theme.textPrimary)
                    .lineLimit(1)
                Text(file == nil ? "从下方列表选择音频" : "本地音频")
                    .font(.caption)
                    .foregroundStyle(theme.textTertiary)
            }

            // 时间标签 + 进度条
            VStack(spacing: 6) {
                HStack {
                    Text(formatTime(draggingPosition ?? player.position))
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(theme.textSecondary)
                    Spacer()
                    Text(formatTime(player.duration))
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(theme.textSecondary)
                }
                Slider(
                    value: Binding(
                        get: { draggingPosition ?? player.position },
                        set: { draggingPosition = $0 }
                    ),
                    in: 0...max(player.duration, 0.1),
                    onEditingChanged: { editing in
                        if !editing, let p = draggingPosition {
                            player.seek(to: p)
                            draggingPosition = nil
                        }
                    }
                )
                .tint(theme.fluidCyan)
                .disabled(file == nil)
            }

            // 控制按钮：随机 / 上一首 / 播放暂停 / 下一首 / 循环
            HStack(spacing: 28) {
                Button { player.toggleShuffle() } label: {
                    Image(systemName: "shuffle")
                        .font(.title3)
                        .foregroundStyle(player.shuffleEnabled ? theme.fluidCyan : theme.textTertiary)
                }
                Button { player.previous() } label: {
                    Image(systemName: "backward.fill")
                        .font(.title2)
                        .foregroundStyle(theme.textPrimary)
                }
                Button { player.togglePlayPause() } label: {
                    Image(systemName: isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 64))
                        .foregroundStyle(theme.fluidCyan)
                }
                Button { player.next() } label: {
                    Image(systemName: "forward.fill")
                        .font(.title2)
                        .foregroundStyle(theme.textPrimary)
                }
                Button { player.cycleRepeatMode() } label: {
                    Image(systemName: player.repeatMode == .one ? "repeat.1" : "repeat")
                        .font(.title3)
                        .foregroundStyle(player.repeatMode != .off ? theme.fluidCyan : theme.textTertiary)
                }
            }
        }
        .padding(20)
        .glassSurface(cornerRadius: 24, glassAlpha: 0.15, theme: theme)
    }

    // MARK: - 文件列表区
    private func fileListSection(theme: AppTheme) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "folder.fill")
                    .foregroundStyle(theme.fluidCyan)
                Text("本地音频文件（\(scanner.files.count)）")
                    .font(.headline)
                    .foregroundStyle(theme.textPrimary)
                Spacer()
                if !scanner.files.isEmpty {
                    Button {
                        player.setQueue(scanner.files, startAt: 0)
                    } label: {
                        Label("全部播放", systemImage: "play.fill")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(theme.fluidCyan)
                    }
                }
            }
            .padding(.horizontal, 4)

            if scanner.isLoading {
                loadingRow(theme: theme)
            } else if scanner.files.isEmpty {
                emptyRow(theme: theme)
            } else {
                ForEach(scanner.files) { file in
                    fileRow(file: file, theme: theme)
                }
            }
        }
    }

    private func fileRow(file: LocalAudioFile, theme: AppTheme) -> some View {
        let isCurrent = player.currentURL == file.url
        let isPlaying = isCurrent && player.isPlaying

        return Button {
            if isCurrent {
                player.togglePlayPause()
            } else if let idx = scanner.files.firstIndex(where: { $0.url == file.url }) {
                player.setQueue(scanner.files, startAt: idx)
            }
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    Circle()
                        .fill(isCurrent
                              ? theme.fluidCyan.opacity(0.22)
                              : theme.glassLight)
                        .frame(width: 40, height: 40)
                    Image(systemName: isCurrent
                          ? (isPlaying ? "pause.fill" : "play.fill")
                          : "music.note")
                        .font(.subheadline)
                        .foregroundStyle(isCurrent ? theme.fluidCyan : theme.textSecondary)
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(file.title)
                        .font(.subheadline.weight(isCurrent ? .semibold : .regular))
                        .foregroundStyle(isCurrent ? theme.fluidCyan : theme.textPrimary)
                        .lineLimit(1)
                    Text(formatTime(file.duration))
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(theme.textTertiary)
                }
                Spacer()
                if isCurrent {
                    Image(systemName: "waveform")
                        .font(.caption)
                        .foregroundStyle(theme.fluidCyan)
                }
            }
            .padding(14)
            .glassSurface(
                cornerRadius: 16,
                glassAlpha: isCurrent ? 0.22 : 0.12,
                theme: theme
            )
        }
        .buttonStyle(.plain)
    }

    private func loadingRow(theme: AppTheme) -> some View {
        HStack(spacing: 12) {
            ProgressView().tint(theme.fluidCyan)
            Text("正在扫描本地音频文件…")
                .font(.subheadline)
                .foregroundStyle(theme.textSecondary)
            Spacer()
        }
        .padding(16)
        .glassSurface(cornerRadius: 16, glassAlpha: 0.12, theme: theme)
    }

    private func emptyRow(theme: AppTheme) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "tray")
                .font(.system(size: 36))
                .foregroundStyle(theme.textTertiary)
            Text("Documents 目录暂无音频文件")
                .font(.subheadline)
                .foregroundStyle(theme.textSecondary)
            Text("点击右上角 + 导入 .mp3 / .m4a / .wav / .aac / .flac")
                .font(.caption)
                .foregroundStyle(theme.textTertiary)
                .multilineTextAlignment(.center)
            Button { showImporter = true } label: {
                Label("导入音频", systemImage: "plus")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(theme.fluidCyan)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .glassSurface(cornerRadius: 14, glassAlpha: 0.15, theme: theme)
            }
            .buttonStyle(.plain)
        }
        .frame(maxWidth: .infinity)
        .padding(24)
        .glassSurface(cornerRadius: 18, glassAlpha: 0.12, theme: theme)
    }

    // MARK: - 工具
    private func formatTime(_ t: TimeInterval) -> String {
        let s = max(0, Int(t))
        let h = s / 3600
        let m = (s % 3600) / 60
        let sec = s % 60
        return h > 0
            ? String(format: "%d:%02d:%02d", h, m, sec)
            : String(format: "%02d:%02d", m, sec)
    }
}
