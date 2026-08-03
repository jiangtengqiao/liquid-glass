import Foundation
import UIKit
import AVFoundation
import MediaPlayer
import Combine

// ─────────────────────────────────────────────────────────────────
// 音频播放器 —— 对应 Android 端 music/MusicService.kt + MusicControllerManager.kt
//
// 职责：
//   1. 封装 AVPlayer，承担播放/暂停/上一首/下一首/拖动进度
//   2. 维护播放队列与循环/随机模式
//   3. MPNowPlayingInfoCenter：写入锁屏/控制中心的曲目信息（标题/艺人/封面/进度）
//   4. MPRemoteCommandCenter：响应锁屏控件、耳机线控、车载控制
//   5. 定时刷新进度（对应 Android tickPosition）
//
// 后台播放前置：AVAudioSession 已在 App 入口配置为 .playback，
// 且 Info.plist 声明 UIBackgroundModes = ["audio"]。
// ─────────────────────────────────────────────────────────────────

// MARK: - 数据模型（对应 Android dto.kt）
struct Song: Identifiable, Codable, Equatable {
    let id: String
    let title: String
    let artist: String
    let url: URL          // 可播放地址（远程或本地 file://）
    let coverURL: URL?    // 封面图地址
    let duration: TimeInterval  // 秒，未知时为 0
}

enum RepeatMode: Int, Codable {
    case off = 0, all = 1, one = 2
}

// MARK: - 播放状态快照（对应 Android MusicControllerManager 的 PlaybackState）
struct PlaybackSnapshot: Equatable {
    var currentSong: Song?
    var isPlaying: Bool = false
    var isBuffering: Bool = false
    var position: TimeInterval = 0      // 当前播放秒
    var duration: TimeInterval = 0      // 总时长秒
    var queueIndex: Int = 0
    var queueCount: Int = 0
}

// MARK: - 音频播放器
final class AudioPlayer: NSObject, ObservableObject {

    static let shared = AudioPlayer()

    // MARK: - 对外可观察状态
    @Published private(set) var snapshot = PlaybackSnapshot()
    /// 播放错误（VIP/无版权等），UI 弹 Toast 后调 consumeError 清空。
    @Published var error: String?

    // MARK: - 内部 AVPlayer 资源
    private var player: AVPlayer?
    private var timeObserverToken: Any?
    private var statusObservation: NSKeyValueObservation?
    private var itemEndObserver: NSObjectProtocol?

    // MARK: - 队列与模式
    private var queue: [Song] = []
    private var currentIndex: Int = 0
    @Published var shuffleEnabled: Bool = false
    @Published var repeatMode: RepeatMode = .off

    private var progressTimer: Timer?

    private override init() {
        super.init()
        configureRemoteCommands()
        // 恢复持久化的播放偏好
        shuffleEnabled = Persistence.shared.bool(for: PersistenceKey.shuffleEnabled)
        if let rm = RepeatMode(rawValue: Persistence.shared.int(for: PersistenceKey.repeatMode)) {
            repeatMode = rm
        }
    }

    // MARK: - 队列操作（对应 Android setMediaItems / playFrom）
    /// 设置播放队列并从指定下标开始播放。
    func setQueue(_ songs: [Song], startIndex: Int = 0) {
        queue = songs
        currentIndex = max(0, min(startIndex, max(0, songs.count - 1)))
        if queue.isEmpty {
            stop()
            return
        }
        play(at: currentIndex)
    }

    /// 追加到队列（不立即切歌）。
    func append(_ songs: [Song]) {
        queue.append(contentsOf: songs)
        if queue.count == songs.count {
            play(at: 0)
        }
        snapshot.queueCount = queue.count
    }

    // MARK: - 播放控制
    /// 从队列中加载并播放指定下标的歌曲。
    private func play(at index: Int) {
        guard queue.indices.contains(index) else { return }
        currentIndex = index
        let song = queue[index]
        replacePlayerItem(with: song)
        snapshot.currentSong = song
        snapshot.queueIndex = index
        snapshot.queueCount = queue.count
        snapshot.duration = song.duration
        play()
    }

    /// 切换播放/暂停。
    func togglePlayPause() {
        if snapshot.isPlaying { pause() } else { play() }
    }

    func play() {
        guard player != nil else { return }
        do {
            try AVAudioSession.sharedInstance().setActive(true, options: [])
        } catch {
            #if DEBUG
            print("[AudioPlayer] 激活会话失败: \(error)")
            #endif
        }
        player?.play()
        snapshot.isPlaying = true
        startProgressTimer()
        updateNowPlayingInfo()
    }

    func pause() {
        player?.pause()
        snapshot.isPlaying = false
        stopProgressTimer()
        updateNowPlayingInfo()
    }

    func stop() {
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        snapshot = PlaybackSnapshot()
        stopProgressTimer()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    func next() {
        guard !queue.isEmpty else { return }
        if repeatMode == .one {
            seek(to: 0)
            play()
            return
        }
        var nextIndex = currentIndex + 1
        if nextIndex >= queue.count {
            if repeatMode == .all {
                nextIndex = 0
            } else {
                // 队列结束：停止播放
                pause()
                return
            }
        }
        play(at: nextIndex)
    }

    func previous() {
        guard !queue.isEmpty else { return }
        // 若已播放超过 3 秒，先回到本曲开头（与多数播放器一致）
        if snapshot.position > 3 {
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
            self?.snapshot.position = seconds
            self?.updateNowPlayingInfo()
        }
    }

    // MARK: - 模式切换（持久化）
    func toggleShuffle() {
        shuffleEnabled.toggle()
        Persistence.shared.setBool(shuffleEnabled, for: PersistenceKey.shuffleEnabled)
    }

    func cycleRepeatMode() {
        switch repeatMode {
        case .off: repeatMode = .all
        case .all: repeatMode = .one
        case .one: repeatMode = .off
        }
        Persistence.shared.setInt(repeatMode.rawValue, for: PersistenceKey.repeatMode)
    }

    // MARK: - AVPlayer 装配
    private func replacePlayerItem(with song: Song) {
        let item = AVPlayerItem(url: song.url)
        // 监听缓冲状态
        statusObservation?.invalidate()
        statusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            DispatchQueue.main.async {
                switch item.status {
                case .readyToPlay:
                    self?.snapshot.isBuffering = false
                case .failed:
                    self?.snapshot.isBuffering = false
                    self?.error = "播放失败：\(item.error?.localizedDescription ?? "未知错误")"
                case .unknown:
                    self?.snapshot.isBuffering = true
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
        snapshot.isBuffering = true
    }

    /// 监听当前曲目播放结束 → 自动下一首（对应 Android Player.Listener onPlaybackStateChanged STATE_ENDED）。
    private func observeItemEnd() {
        guard let player else { return }
        itemEndObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.next()
        }
        _ = player // 保持 player 引用，避免编译器优化掉监听
    }

    /// 周期性时间观察器，用于刷新进度（每 500ms，对应 Android tickPosition）。
    private func addPeriodicTimeObserver() {
        guard let player else { return }
        let interval = CMTime(seconds: 0.5, preferredTimescale: 600)
        timeObserverToken = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            guard let self else { return }
            self.snapshot.position = time.seconds
            if let item = self.player?.currentItem, item.duration.seconds.isFinite, item.duration.seconds > 0 {
                self.snapshot.duration = item.duration.seconds
            }
        }
    }

    // MARK: - 进度刷新 Timer（对应 Android tickPosition 循环）
    /// 已通过 periodic time observer 刷新进度，这里仅做锁屏信息同步。
    private func startProgressTimer() {
        stopProgressTimer()
        progressTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.updateNowPlayingInfo()
        }
    }

    private func stopProgressTimer() {
        progressTimer?.invalidate()
        progressTimer = nil
    }

    // MARK: - 锁屏信息中心（对应 Android 自定义媒体通知）
    /// 写入 MPNowPlayingInfoCenter，锁屏与控制中心据此显示标题/艺人/进度/封面。
    private func updateNowPlayingInfo() {
        var info: [String: Any] = [:]
        if let song = snapshot.currentSong {
            info[MPMediaItemPropertyTitle] = song.title
            info[MPMediaItemPropertyArtist] = song.artist
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = snapshot.position
            info[MPMediaItemPropertyPlaybackDuration] = max(snapshot.duration, 0.1)
            info[MPNowPlayingInfoPropertyPlaybackRate] = snapshot.isPlaying ? 1.0 : 0.0
            info[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaType.audio.rawValue
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info

        // 异步加载封面图（对应 Android artworkUri）
        if let coverURL = snapshot.currentSong?.coverURL {
            loadArtwork(from: coverURL)
        }
    }

    private func loadArtwork(from url: URL) {
        URLSession.shared.dataTask(with: url) { data, _, _ in
            guard let data, let image = UIImage(data: data) else { return }
            // 用 artworkCache key 避免 task 覆盖导致竞态
            let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            DispatchQueue.main.async {
                var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                info[MPMediaItemPropertyArtwork] = artwork
                MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            }
        }.resume()
    }

    // MARK: - 远程控制（对应 Android MediaSession + MediaButtonReceiver）
    /// 注册 MPRemoteCommandCenter：响应锁屏按钮、耳机线控、车载。
    private func configureRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()

        center.playCommand.addTarget { [weak self] _ in
            self?.play()
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            self?.pause()
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            self?.togglePlayPause()
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            self?.next()
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            self?.previous()
            return .success
        }
        // 拖动进度条
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }
            self?.seek(to: event.positionTime)
            return .success
        }

        // 默认允许后台远程控制
        UIApplication.shared.beginReceivingRemoteControlEvents()
    }

    /// 消费错误（UI 弹 Toast 后调用）。
    func consumeError() {
        error = nil
    }

    /// 应用进入后台/前台时调用：保持播放，但停止 Timer 节流（后台由系统接管）。
    func handleEnterBackground() {
        stopProgressTimer()
    }

    func handleEnterForeground() {
        if snapshot.isPlaying { startProgressTimer() }
    }

    deinit {
        if let token = timeObserverToken { player?.removeTimeObserver(token) }
        if let observer = itemEndObserver { NotificationCenter.default.removeObserver(observer) }
        statusObservation?.invalidate()
        stopProgressTimer()
    }
}
