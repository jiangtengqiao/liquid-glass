import Foundation

// ─────────────────────────────────────────────────────────────────
// 持久化 —— 对应 Android 端的 SharedPreferences（用 UserDefaults 封装）
//
// 职责：
//   - 封装 UserDefaults 的读写，提供类型安全 API
//   - 管理主题选择、播放历史、歌词设置等用户偏好
//   - 跨进程/跨页面共享（UserDefaults 单例）
// ─────────────────────────────────────────────────────────────────

// MARK: - 持久化键值（对应 Android 端各 SharedPreferences 的 key）
enum PersistenceKey {
    static let currentThemeId    = "current_theme_id"
    static let playHistory        = "play_history"        // [Song]
    static let lastPlaylist        = "last_playlist"
    static let playbackPosition    = "playback_position"   // Int 秒
    static let shuffleEnabled      = "shuffle_enabled"
    static let repeatMode          = "repeat_mode"          // 0/1/2
    static let lyricsFontSize      = "lyrics_font_size"
    static let lyricsAlignment      = "lyrics_alignment"
    static let sleepTimerMinutes    = "sleep_timer_minutes"
    static let resourcePackVersion  = "resource_pack_version"
    static let lastDownloadSpeed    = "last_download_speed"
    static let searchHistory        = "search_history"      // [String]
    static let nightModeAuto        = "night_mode_auto"
}

// MARK: - 持久化管理器
final class Persistence {

    static let shared = Persistence()

    private let defaults: UserDefaults

    init(suiteName: String? = nil) {
        // 支持应用组（App Group）以便未来与小组件共享数据；
        // 未配置 suiteName 时退回 standard UserDefaults。
        if let suiteName, let suite = UserDefaults(suiteName: suiteName) {
            self.defaults = suite
        } else {
            self.defaults = .standard
        }
    }

    // MARK: - 基础类型读写（对应 SharedPreferences 的各种 getX/putX）
    func setBool(_ value: Bool, for key: String)   { defaults.set(value, forKey: key) }
    func setInt(_ value: Int, for key: String)     { defaults.set(value, forKey: key) }
    func setDouble(_ value: Double, for key: String) { defaults.set(value, forKey: key) }
    func setString(_ value: String, for key: String) { defaults.set(value, forKey: key) }

    func bool(for key: String, default def: Bool = false) -> Bool {
        defaults.object(forKey: key) == nil ? def : defaults.bool(forKey: key)
    }
    func int(for key: String, default def: Int = 0) -> Int {
        defaults.object(forKey: key) == nil ? def : defaults.integer(forKey: key)
    }
    func double(for key: String, default def: Double = 0) -> Double {
        defaults.object(forKey: key) == nil ? def : defaults.double(forKey: key)
    }
    func string(for key: String) -> String? {
        defaults.string(forKey: key)
    }

    // MARK: - JSON 对象读写（用 JSONEncoder/Decoder 持久化自定义模型）
    /// 保存可编码对象为 JSON Data。
    func setObject<T: Encodable>(_ value: T, for key: String) {
        do {
            let data = try JSONEncoder().encode(value)
            defaults.set(data, forKey: key)
        } catch {
            #if DEBUG
            print("[Persistence] 编码失败 key=\(key): \(error)")
            #endif
        }
    }

    /// 读取并解码为指定类型。
    func object<T: Decodable>(_ type: T.Type, for key: String) -> T? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    // MARK: - 集合快捷读写（对应 Android 的 StringSet / JSONArray）
    func stringArray(for key: String) -> [String] {
        defaults.stringArray(forKey: key) ?? []
    }
    func setStringArray(_ value: [String], for key: String) {
        defaults.set(value, forKey: key)
    }

    // MARK: - 删除
    func remove(_ key: String) {
        defaults.removeObject(forKey: key)
    }

    /// 清空所有偏好（注销/重置时调用）。
    func clearAll() {
        if let dict = defaults.dictionaryRepresentation() as? [String: Any] {
            for key in dict.keys { defaults.removeObject(forKey: key) }
        }
    }

    // MARK: - 主题持久化便捷方法
    /// 读取当前主题 id，未配置时返回默认深色主题。
    func currentTheme() -> AppTheme {
        let id = string(for: PersistenceKey.currentThemeId) ?? Themes.midnightDark.id
        return Themes.all.first { $0.id == id } ?? Themes.midnightDark
    }

    func setCurrentTheme(_ theme: AppTheme) {
        setString(theme.id, for: PersistenceKey.currentThemeId)
    }

    // MARK: - 搜索历史（对应 Android SearchHistoryStore）
    func searchHistory(maxItems: Int = 20) -> [String] {
        var history = stringArray(for: PersistenceKey.searchHistory)
        return Array(history.prefix(maxItems))
    }

    func appendSearchHistory(_ keyword: String, maxItems: Int = 20) {
        let trimmed = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        var history = stringArray(for: PersistenceKey.searchHistory)
        // 去重：移除已存在的同名项，再插到最前
        history.removeAll { $0 == trimmed }
        history.insert(trimmed, at: 0)
        history = Array(history.prefix(maxItems))
        setStringArray(history, for: PersistenceKey.searchHistory)
    }

    func clearSearchHistory() {
        remove(PersistenceKey.searchHistory)
    }
}
