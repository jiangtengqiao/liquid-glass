import Foundation
import CryptoKit
#if canImport(Security)
import Security
#endif

// ─────────────────────────────────────────────────────────────────
// 网易云音乐 iOS 客户端 —— 对应 Android 端 music/ 全套
//
// 纯 Swift 直连 weapi，无后端依赖。覆盖：
//   - weapi 加密（AES-CBC + 教材式 RSA modpow，BigInt 自实现）
//   - Cookie 管理（HTTPCookieStorage + UserDefaults 持久化 MUSIC_U/__csrf）
//   - 二维码扫码登录 + 手机验证码登录
//   - 搜索 / 歌曲URL / 歌词 / 用户歌单 / 歌单详情 / 排行榜 / 推荐 / 私人FM / 新歌 / 相似歌曲
//
// 所有方法 async/await，在 SwiftUI Task 中调用。
// ─────────────────────────────────────────────────────────────────

// MARK: - 数据模型（对应 dto.kt）

enum NetEaseSource { case netease, local }

struct NetEaseSong: Identifiable, Equatable {
    let id: String
    let title: String
    let artist: String
    var album: String = ""
    var durationMs: Int64 = 0
    var coverUrl: String = ""
    var streamUrl: String = ""
    var fee: Int = 0
    let source: NetEaseSource = .netease

    var id_str: String { id }
    var isVipOnly: Bool { fee == 1 }
    var isPlayable: Bool { fee == 0 || fee == 8 }
}

struct NetEaseSearchResult {
    let songs: [NetEaseSong]
    let total: Int
}

struct NetEaseLyricLine {
    let timeMs: Int64
    let content: String
    let translation: String
}

struct NetEaseYrcChar {
    let startMs: Int64
    let durationMs: Int64
    let content: String
}

struct NetEaseYrcLine {
    let startMs: Int64
    let durationMs: Int64
    let chars: [NetEaseYrcChar]
    let translation: String
}

struct NetEaseLyrics {
    var yrcLines: [NetEaseYrcLine] = []
    var lrcLines: [NetEaseLyricLine] = []
    var hasYrc: Bool { !yrcLines.isEmpty }
}

struct NetEasePlaylist: Identifiable, Equatable {
    let id: String
    let name: String
    var coverUrl: String = ""
    var trackCount: Int = 0
    var creator: String = ""
}

struct NetEaseUserAccount {
    let userId: Int64
    let nickname: String
    let avatarUrl: String
    let vipType: Int
    var isVip: Bool { vipType > 0 }
}

enum QrLoginState { case waiting, scanned, confirmed, expired, error }
struct QrLoginResult { let state: QrLoginState }

enum PhoneLoginState { case idle, codeSent, success, smsFailed, loginFailed, error }
struct PhoneLoginResult { let state: PhoneLoginState; let message: String }

// MARK: - BigInt（教材式 RSA modpow 所需）
// 网易云 RSA：明文反转后做 base^exp mod modulus，base 仅 16 字节（128bit），
// modulus 为 1024bit，exp=65537。无需 Padding，用 UInt32 limbs 实现 modpow 即可。

struct BigInt {
    // little-endian limbs，无前导零
    var limbs: [UInt32]

    init(limbs: [UInt32]) {
        self.limbs = limbs
        trim()
    }

    init(_ data: Data) {
        var bytes = [UInt8](data)
        while bytes.count % 4 != 0 { bytes.insert(0, at: 0) }
        var l: [UInt32] = []
        var i = bytes.count - 4
        while i >= 0 {
            let v = UInt32(bytes[i]) << 24
                 | UInt32(bytes[i + 1]) << 16
                 | UInt32(bytes[i + 2]) << 8
                 | UInt32(bytes[i + 3])
            l.append(v)
            i -= 4
        }
        self.limbs = l
        trim()
    }

    init?(hex: String) {
        var h = hex
        if h.hasPrefix("0x") { h.removeFirst(2) }
        if h.isEmpty { return nil }
        if h.count % 2 != 0 { h = "0" + h }
        var bytes = [UInt8]()
        var idx = h.startIndex
        while idx < h.endIndex {
            let nxt = h.index(idx, offsetBy: 2)
            guard let b = UInt8(h[idx..<nxt], radix: 16) else { return nil }
            bytes.append(b)
            idx = nxt
        }
        self.init(Data(bytes))
    }

    init(_ v: UInt64) {
        limbs = [UInt32(v & 0xFFFFFFFF), UInt32(v >> 32)]
        trim()
    }

    mutating func trim() {
        while limbs.count > 1 && limbs.last == 0 { limbs.removeLast() }
    }

    var isZero: Bool { limbs.count == 1 && limbs[0] == 0 }

    func toHexString() -> String {
        var s = ""
        for limb in limbs.reversed() { s += String(format: "%08x", limb) }
        while s.count > 1 && s.first == "0" { s.removeFirst() }
        return s
    }

    // 比较：-1 self<o, 0 相等, 1 self>o
    func compare(_ o: BigInt) -> Int {
        if limbs.count != o.limbs.count {
            return limbs.count < o.limbs.count ? -1 : 1
        }
        for i in (0..<limbs.count).reversed() {
            if limbs[i] != o.limbs[i] {
                return limbs[i] < o.limbs[i] ? -1 : 1
            }
        }
        return 0
    }

    // 加法
    static func + (a: BigInt, b: BigInt) -> BigInt {
        let n = max(a.limbs.count, b.limbs.count)
        var result = [UInt32](repeating: 0, count: n + 1)
        var carry: UInt64 = 0
        for i in 0..<n {
            let x: UInt64 = i < a.limbs.count ? UInt64(a.limbs[i]) : 0
            let y: UInt64 = i < b.limbs.count ? UInt64(b.limbs[i]) : 0
            let sum = x + y + carry
            result[i] = UInt32(sum & 0xFFFFFFFF)
            carry = sum >> 32
        }
        result[n] = UInt32(carry)
        return BigInt(limbs: result)
    }

    // 减法（假定 self >= o）
    static func - (a: BigInt, b: BigInt) -> BigInt {
        var result = [UInt32](repeating: 0, count: a.limbs.count)
        var borrow: Int64 = 0
        for i in 0..<a.limbs.count {
            let x: Int64 = Int64(a.limbs[i])
            let y: Int64 = i < b.limbs.count ? Int64(b.limbs[i]) : 0
            var diff = x - y - borrow
            if diff < 0 { diff += 0x100000000; borrow = 1 } else { borrow = 0 }
            result[i] = UInt32(diff & 0xFFFFFFFF)
        }
        return BigInt(limbs: result)
    }

    // 乘法（schoolbook，用 UInt64 累积）
    static func * (a: BigInt, b: BigInt) -> BigInt {
        var result = [UInt32](repeating: 0, count: a.limbs.count + b.limbs.count)
        for i in 0..<a.limbs.count {
            var carry: UInt64 = 0
            let ai = UInt64(a.limbs[i])
            for j in 0..<b.limbs.count {
                let bj = UInt64(b.limbs[j])
                let cur = UInt64(result[i + j]) + ai * bj + carry
                result[i + j] = UInt32(cur & 0xFFFFFFFF)
                carry = cur >> 32
            }
            result[i + b.limbs.count] += UInt32(carry)
        }
        return BigInt(limbs: result)
    }

    // 左移 n 位（按 bit）
    func leftShift(_ bits: Int) -> BigInt {
        if bits == 0 { return self }
        let limbShift = bits / 32
        let bitShift = bits % 32
        var result = [UInt32](repeating: 0, count: limbs.count + limbShift + 1)
        if bitShift == 0 {
            for i in 0..<limbs.count { result[i + limbShift] = limbs[i] }
        } else {
            for i in 0..<limbs.count {
                let lo = UInt64(limbs[i]) << bitShift
                result[i + limbShift] |= UInt32(lo & 0xFFFFFFFF)
                result[i + limbShift + 1] |= UInt32(lo >> 32)
            }
        }
        return BigInt(limbs: result)
    }

    // 右移 n 位
    func rightShift(_ bits: Int) -> BigInt {
        if bits == 0 { return self }
        let limbShift = bits / 32
        let bitShift = bits % 32
        if limbShift >= limbs.count { return BigInt(0) }
        var result = [UInt32](repeating: 0, count: limbs.count - limbShift)
        if bitShift == 0 {
            for i in 0..<result.count { result[i] = limbs[i + limbShift] }
        } else {
            for i in 0..<result.count {
                let lo = UInt64(limbs[i + limbShift]) >> bitShift
                result[i] = UInt32(lo & 0xFFFFFFFF)
                if i + limbShift + 1 < limbs.count {
                    let hi = UInt64(limbs[i + limbShift + 1]) << (32 - bitShift)
                    result[i] |= UInt32(hi & 0xFFFFFFFF)
                }
            }
        }
        return BigInt(limbs: result)
    }

    // bit 长度
    func bitLength() -> Int {
        guard let top = limbs.last else { return 0 }
        if top == 0 { return (limbs.count - 1) * 32 }
        var b = top
        var bits = 0
        while b != 0 { bits += 1; b >>= 1 }
        return (limbs.count - 1) * 32 + bits
    }

    func bit(_ i: Int) -> Int {
        let limb = i / 32
        let off = i % 32
        if limb >= limbs.count { return 0 }
        return Int((limbs[limb] >> off) & 1)
    }

    // 取模：self mod o（长除法）
    func mod(_ o: BigInt) -> BigInt {
        if o.isZero { return self }
        if self.compare(o) < 0 { return self }
        var rem = self
        let oBits = o.bitLength()
        var remBits = rem.bitLength()
        while remBits >= oBits {
            let shift = remBits - oBits
            let shifted = o.leftShift(shift)
            if rem.compare(shifted) >= 0 {
                rem = rem - shifted
            } else {
                if shift > 0 {
                    rem = rem - o.leftShift(shift - 1)
                } else {
                    break
                }
            }
            remBits = rem.bitLength()
            if rem.isZero { break }
        }
        return rem
    }

    // 模幂：self^exp mod mod（平方-乘）
    static func modpow(_ base: BigInt, _ exp: BigInt, _ mod: BigInt) -> BigInt {
        if mod.isZero { return BigInt(0) }
        var result = BigInt(1)
        var b = base.mod(mod)
        let bits = exp.bitLength()
        for i in 0..<bits {
            if exp.bit(i) == 1 {
                result = (result * b).mod(mod)
            }
            b = (b * b).mod(mod)
        }
        return result
    }
}

// MARK: - weapi 加密（对应 NetEaseCrypto.kt）

enum NetEaseCrypto {
    static let fixedKey = "0CoJUm6Qyw8W8jud"
    static let iv = "0102030405060708"
    static let rsaModulusHex =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    static let rsaExponentHex = "010001"
    static let base62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    static func randomSecretKey() -> String {
        var rng = SystemRandomNumberGenerator()
        let indices = (0..<16).map { _ in Int.random(in: 0..<62, using: &rng) }
        return String(indices.map { base62[base62.index(base62.startIndex, offsetBy: $0)] })
    }

    static func aesCbcEncrypt(_ plaintext: String, key: String) -> String? {
        let keyData = Array(key.utf8)
        let ivData = Array(iv.utf8)
        let plainData = Array(plaintext.utf8)
        // CCCrypt 传 kCCOptionPKCS7Padding 自动补齐，无需手动 padding（否则双重 padding）
        let encrypted = aesCbcEncryptRaw(Data(plainData), key: Data(keyData), iv: Data(ivData))
        return encrypted?.base64EncodedString()
    }

    static func aesCbcEncryptRaw(_ data: Data, key: Data, iv: Data) -> Data? {
        // 用 CCCrypt（CommonCrypto，iOS 原生可用），kCCOptionPKCS7Padding 自动补齐
        let keyBytes = key.withUnsafeBytes { Array($0) }
        let ivBytes = iv.withUnsafeBytes { Array($0) }
        let dataBytes = data.withUnsafeBytes { Array($0) }
        var out = [UInt8](repeating: 0, count: dataBytes.count + 16)
        var numMoved = 0
        let status = dataBytes.withUnsafeBufferPointer { dataPtr in
            keyBytes.withUnsafeBufferPointer { keyPtr in
                ivBytes.withUnsafeBufferPointer { ivPtr in
                    out.withUnsafeMutableBufferPointer { outPtr in
                        CCCryptRaw(
                            CCOperation(kCCEncrypt),
                            CCAlgorithm(kCCAlgorithmAES),
                            CCOptions(kCCOptionPKCS7Padding),
                            keyPtr.baseAddress, keyPtr.count,
                            ivPtr.baseAddress,
                            dataPtr.baseAddress, dataPtr.count,
                            outPtr.baseAddress, outPtr.count, &numMoved
                        )
                    }
                }
            }
        }
        guard status == kCCSuccess else { return nil }
        return Data(out.prefix(numMoved))
    }

    static func rsaEncrypt(_ text: String) -> String? {
        // 明文反转
        let reversed = String(text.reversed())
        guard let mod = BigInt(hex: rsaModulusHex),
              let exp = BigInt(hex: rsaExponentHex) else { return nil }
        let base = BigInt(Data(reversed.utf8))
        let result = BigInt.modpow(base, exp, mod)
        var hex = result.toHexString()
        // padStart(256, '0')
        while hex.count < 256 { hex = "0" + hex }
        return hex
    }

    /// weapi 加密：返回 (params, encSecKey)
    static func encrypt(_ rawJson: String) -> (params: String, encSecKey: String)? {
        let secretKey = randomSecretKey()
        guard let p1 = aesCbcEncrypt(rawJson, key: fixedKey),
              let params = aesCbcEncrypt(p1, key: secretKey),
              let encSecKey = rsaEncrypt(secretKey) else { return nil }
        return (params, encSecKey)
    }
}

// CommonCrypto 桥接：CCCrypt 是 C 函数，直接 import CommonCrypto
// 注意：iOS 上需 import CommonCrypto。这里用裸 C 符号 CCCrypt。
// 为避免桥接头，用 @silgen_name 直接声明。
@_silgen_name("CCCrypt")
private func CCCryptRaw(
    _ op: CCOperation,
    _ alg: CCAlgorithm,
    _ options: CCOptions,
    _ key: UnsafeRawPointer?,
    _ keyLength: Int,
    _ iv: UnsafeRawPointer?,
    _ dataIn: UnsafeRawPointer?,
    _ dataInLength: Int,
    _ dataOut: UnsafeMutableRawPointer?,
    _ dataOutAvailable: Int,
    _ dataOutMoved: UnsafeMutablePointer<Int>?
) -> Int32

private typealias CCOperation = Int32
private typealias CCAlgorithm = Int32
private typealias CCOptions = Int32
private let kCCEncrypt: CCOperation = 0
private let kCCAlgorithmAES: CCAlgorithm = 0
private let kCCOptionPKCS7Padding: CCOptions = 0x1
private let kCCSuccess: Int32 = 0

// MARK: - 会话存储（对应 SessionStore.kt）

enum NetEaseSession {
    private let cookieKey = "netease_cookies"
    private let userIdKey = "netease_uid"
    private let nicknameKey = "netease_nickname"
    private let avatarKey = "netease_avatar"
    private let vipKey = "netease_viptype"
    private let boundPhoneKey = "netease_bound_phone"

    static func saveCookies(_ flat: String) {
        guard flat.contains("MUSIC_U=") || flat.contains("__csrf=") else { return }
        UserDefaults.standard.set(flat, forKey: cookieKey)
    }

    static func getCookies() -> String {
        UserDefaults.standard.string(forKey: cookieKey) ?? ""
    }

    static func hasLoginCookie() -> Bool {
        getCookies().contains("MUSIC_U=")
    }

    static func isLoggedIn() -> Bool { hasLoginCookie() }

    static func saveUser(_ userId: Int64, _ nickname: String, _ avatar: String, _ vipType: Int) {
        UserDefaults.standard.set(userId, forKey: userIdKey)
        UserDefaults.standard.set(nickname, forKey: nicknameKey)
        UserDefaults.standard.set(avatar, forKey: avatarKey)
        UserDefaults.standard.set(vipType, forKey: vipKey)
    }

    static func getUserId() -> Int64 { UserDefaults.standard.object(forKey: userIdKey) as? Int64 ?? 0 }
    static func getNickname() -> String { UserDefaults.standard.string(forKey: nicknameKey) ?? "" }
    static func getAvatarUrl() -> String { UserDefaults.standard.string(forKey: avatarKey) ?? "" }
    static func getVipType() -> Int { UserDefaults.standard.integer(forKey: vipKey) }

    static func saveBoundPhone(_ phone: String) {
        UserDefaults.standard.set(phone, forKey: boundPhoneKey)
    }
    static func getBoundPhone() -> String { UserDefaults.standard.string(forKey: boundPhoneKey) ?? "" }

    static func logout() {
        UserDefaults.standard.removeObject(forKey: cookieKey)
        UserDefaults.standard.removeObject(forKey: userIdKey)
        UserDefaults.standard.removeObject(forKey: nicknameKey)
        UserDefaults.standard.removeObject(forKey: avatarKey)
        UserDefaults.standard.removeObject(forKey: vipKey)
        // 清 HTTPCookieStorage 中的网易云 cookie
        if let url = URL(string: "https://music.163.com") {
            for c in HTTPCookieStorage.shared.cookies(for: url) ?? [] {
                HTTPCookieStorage.shared.deleteCookie(c)
            }
        }
    }

    /// 提取 __csrf 值（用于注入 payload.csrf_token）
    static func extractCsrfToken() -> String {
        let flat = getCookies()
        for pair in flat.split(separator: "; ") {
            let parts = pair.split(separator: "=", maxSplits: 1)
            if parts.count == 2, parts[0] == "__csrf" {
                let v = String(parts[1])
                if !v.isEmpty { return v }
            }
        }
        return ""
    }

    /// 合并客户端静态 cookie + 持久化登录态，返回 "k=v; k=v" 串
    static func flattenRequestCookies() -> String {
        let clientStatic = "os=pc; osver=Microsoft-Windows-10-Build-19045-64.0; appver=2.10.14; channel=netease; wevt=web"
        var merged = LinkedHashMap<String, String>()
        // 客户端静态（最低优先级）
        for pair in clientStatic.split(separator: "; ") {
            let parts = pair.split(separator: "=", maxSplits: 1)
            if parts.count == 2 { merged[String(parts[0])] = String(parts[1]) }
        }
        // 持久化登录态
        let persisted = getCookies()
        for pair in persisted.split(separator: "; ") {
            let parts = pair.split(separator: "=", maxSplits: 1)
            if parts.count == 2 {
                let k = String(parts[0]); let v = String(parts[1])
                if !k.isEmpty && !v.isEmpty { merged[k] = v }
            }
        }
        return merged.map { "\($0.key)=\($0.value)" }.joined(separator: "; ")
    }
}

/// 保持插入顺序的字典（去重 cookie 用）
private struct LinkedHashMap<K: Hashable, V> {
    private var keys: [K] = []
    private var values: [K: V] = [:]
    mutating func set(_ k: K, _ v: V) {
        if values[k] == nil { keys.append(k) }
        values[k] = v
    }
    subscript(_ k: K) -> V? {
        get { values[k] }
        set { if let nv = newValue { set(k, nv) } else { values[k] = nil } }
    }
    func map<T>(_ transform: (K, V) -> T) -> [T] {
        keys.compactMap { k in values[k].map { v in transform(k, v) } }
    }
}

// MARK: - 网易云客户端（对应 NetEaseApiClient + NetEaseApi + NetEaseAuth）

enum NetEaseClient {
    static let base = "https://music.163.com"
    static let clientUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"
    static let chinaIP = "122.228.19.64"

    static let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 15
        cfg.timeoutIntervalForResource = 30
        cfg.httpCookieAcceptPolicy = .always
        cfg.httpShouldSetCookies = true
        cfg.httpCookieStorage = HTTPCookieStorage.shared
        return URLSession(configuration: cfg)
    }()

    // MARK: weapi POST（对应 NetEaseApiClient.weapiPost）

    /// 发起 weapi 请求，返回解析后的 JSON 字典
    static func weapiPost(_ path: String, payload: String) async -> [String: Any] {
        // 注入 csrf_token
        let csrf = NetEaseSession.extractCsrfToken()
        var finalPayload = payload
        if !csrf.isEmpty {
            if let data = payload.data(using: .utf8),
               var obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                obj["csrf_token"] = csrf
                if let redata = try? JSONSerialization.data(withJSONObject: obj),
                   let reStr = String(data: redata, encoding: .utf8) {
                    finalPayload = reStr
                }
            }
        }
        guard let enc = NetEaseCrypto.encrypt(finalPayload) else {
            return ["code": -1, "message": "encrypt failed"]
        }
        guard let url = URL(string: base + path) else {
            return ["code": -1, "message": "bad url"]
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue(clientUA, forHTTPHeaderField: "User-Agent")
        req.setValue("\(base)/", forHTTPHeaderField: "Referer")
        req.setValue(base, forHTTPHeaderField: "Origin")
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json, text/plain, */*", forHTTPHeaderField: "Accept")
        req.setValue("zh-CN,zh;q=0.9", forHTTPHeaderField: "Accept-Language")
        req.setValue(chinaIP, forHTTPHeaderField: "X-Real-IP")
        req.setValue(chinaIP, forHTTPHeaderField: "X-Forwarded-For")
        // Cookie：合并客户端静态 + 持久化登录态
        req.setValue(NetEaseSession.flattenRequestCookies(), forHTTPHeaderField: "Cookie")
        let bodyStr = "params=\(enc.params.urlEncoded)&encSecKey=\(enc.encSecKey.urlEncoded)"
        req.httpBody = bodyStr.data(using: .utf8)

        do {
            let (data, response) = try await session.data(for: req)
            // 捕获 Set-Cookie 并持久化
            if let http = response as? HTTPURLResponse {
                captureSetCookies(http, url: url)
            }
            guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return ["code": -1, "message": "parse error"]
            }
            return obj
        } catch {
            return ["code": -1, "message": "transport: \(error.localizedDescription)"]
        }
    }

    /// 捕获响应 Set-Cookie，落库 MUSIC_U/__csrf
    private static func captureSetCookies(_ http: HTTPURLResponse, url: URL) {
        // 优先用 HTTPCookieStorage 解析（自动存）
        if let cookies = HTTPCookie.cookies(
            withResponseHeaderFields: http.allHeaderFields as? [String: String] ?? [:],
            for: url
        ) {
            for c in cookies {
                HTTPCookieStorage.shared.setCookie(c)
            }
        }
        // 手动 flatten 持久化
        var merged = LinkedHashMap<String, String>()
        for pair in NetEaseSession.getCookies().split(separator: "; ") {
            let parts = pair.split(separator: "=", maxSplits: 1)
            if parts.count == 2 { merged[String(parts[0])] = String(parts[1]) }
        }
        for c in HTTPCookieStorage.shared.cookies(for: url) ?? [] {
            if !c.value.isEmpty { merged[c.name] = c.value }
        }
        let flat = merged.map { "\($0.key)=\($0.value)" }.joined(separator: "; ")
        NetEaseSession.saveCookies(flat)
    }

    private static func checkResponse(_ json: [String: Any], _ tag: String) -> Bool {
        let code = (json["code"] as? Int) ?? -1
        if code != 200 && code != 0 {
            #if DEBUG
            print("[NetEaseClient] \(tag): code=\(code)")
            #endif
            return false
        }
        return true
    }

    // MARK: - 热搜词

    static func hotSearch() async -> [String] {
        let payload = "{\"type\":1111}"
        let json = await weapiPost("/weapi/search/hot", payload: payload)
        guard checkResponse(json, "hotSearch") else { return [] }
        guard let result = json["result"] as? [String: Any],
              let hots = result["hots"] as? [[String: Any]] else { return [] }
        return hots.compactMap { ($0["first"] as? String) }.filter { !$0.isEmpty }
    }

    // MARK: - 搜索

    static func search(_ keyword: String, limit: Int = 30, offset: Int = 0) async -> NetEaseSearchResult {
        let payload: [String: Any] = [
            "s": keyword, "type": 1, "limit": limit, "offset": offset, "total": true
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let str = String(data: data, encoding: .utf8) else {
            return NetEaseSearchResult(songs: [], total: 0)
        }
        let json = await weapiPost("/weapi/cloudsearch/get/web", payload: str)
        guard checkResponse(json, "search"),
              let result = json["result"] as? [String: Any],
              let songs = result["songs"] as? [[String: Any]] else {
            return NetEaseSearchResult(songs: [], total: 0)
        }
        let list = songs.map { parseSong($0) }
        let total = (result["songCount"] as? Int) ?? list.count
        return NetEaseSearchResult(songs: list, total: total)
    }

    // MARK: - 歌曲播放 URL

    static func songUrl(songId: String, level: String = "exhigh") async -> String {
        let idsStr = "[\(songId)]"
        let encodeType = (level == "lossless" || level == "hires") ? "flac" : "aac"
        let payload = "{\"ids\":\(idsStr),\"level\":\"\(level)\",\"encodeType\":\"\(encodeType)\",\"csrf_token\":\"\"}"
        let json = await weapiPost("/weapi/song/enhance/player/url/v1", payload: payload)
        guard checkResponse(json, "songUrl"),
              let data = json["data"] as? [[String: Any]], !data.isEmpty else { return "" }
        let url = (data[0]["url"] as? String) ?? ""
        // 非 VIP 请求 lossless 返回空 → 降级 exhigh
        if url.isEmpty && level == "lossless" {
            return await songUrl(songId: songId, level: "exhigh")
        }
        return url
    }

    // MARK: - 歌词

    static func lyrics(songId: String) async -> NetEaseLyrics {
        let payload = "{\"id\":\(songId),\"lv\":-1,\"tv\":-1,\"kv\":-1}"
        let json = await weapiPost("/weapi/song/lyric", payload: payload)
        guard checkResponse(json, "lyrics") else { return NetEaseLyrics() }
        let yrcText = (json["yrc"] as? [String: Any])?["lyric"] as? String ?? ""
        let yrcTrans = (json["ytlrc"] as? [String: Any])?["lyric"] as? String ?? ""
        let lrcText = (json["lrc"] as? [String: Any])?["lyric"] as? String ?? ""
        let lrcTrans = (json["tlyric"] as? [String: Any])?["lyric"] as? String ?? ""
        let yrcLines = yrcText.isEmpty ? [] : LyricsParserSwift.parseYrc(yrcText, yrcTrans)
        let lrcLines = lrcText.isEmpty ? [] : LyricsParserSwift.parseLrc(lrcText, lrcTrans)
        return NetEaseLyrics(yrcLines: yrcLines, lrcLines: lrcLines)
    }

    // MARK: - 用户歌单

    static func userPlaylists() async -> [NetEasePlaylist] {
        guard NetEaseSession.isLoggedIn() else { return [] }
        let uid = NetEaseSession.getUserId()
        if uid <= 0 { return [] }
        let payload = "{\"uid\":\(uid),\"limit\":30,\"offset\":0,\"includeVideo\":true,\"csrf_token\":\"\"}"
        let json = await weapiPost("/weapi/user/playlist", payload: payload)
        guard checkResponse(json, "userPlaylists"),
              let arr = json["playlist"] as? [[String: Any]] else { return [] }
        return arr.map { parsePlaylist($0) }
    }

    // MARK: - 歌单详情 / 曲目

    static func playlistTracks(playlistId: String) async -> [NetEaseSong] {
        let payload = "{\"id\":\(playlistId),\"n\":1000,\"s\":8,\"csrf_token\":\"\"}"
        let json = await weapiPost("/weapi/v6/playlist/detail", payload: payload)
        guard checkResponse(json, "playlistTracks"),
              let tracks = (json["playlist"] as? [String: Any])?["tracks"] as? [[String: Any]] else {
            return []
        }
        return tracks.map { parseSong($0) }
    }

    // MARK: - 排行榜

    static func toplist() async -> [NetEasePlaylist] {
        let json = await weapiPost("/weapi/toplist/detail", payload: "{}")
        guard checkResponse(json, "toplist"),
              let arr = json["list"] as? [[String: Any]] else { return [] }
        return arr.map { parsePlaylist($0) }
    }

    static func toplistTracks(id: String) async -> [NetEaseSong] {
        await playlistTracks(playlistId: id)
    }

    // MARK: - 每日推荐

    static func recommendSongs() async -> [NetEaseSong] {
        let payload = "{\"limit\":30,\"offset\":0,\"total\":true,\"csrf_token\":\"\"}"
        let json = await weapiPost("/weapi/v3/discovery/recommend/songs", payload: payload)
        guard checkResponse(json, "recommendSongs"),
              let arr = json["recommend"] as? [[String: Any]] else { return [] }
        return arr.map { parseSong($0) }
    }

    // MARK: - 推荐歌单

    static func recommendPlaylists() async -> [NetEasePlaylist] {
        let payload = "{\"limit\":12,\"offset\":0,\"total\":true,\"csrf_token\":\"\"}"
        let json = await weapiPost("/weapi/personalized/playlist", payload: payload)
        guard checkResponse(json, "recommendPlaylists"),
              let arr = json["result"] as? [[String: Any]] else { return [] }
        return arr.map { parsePlaylist($0) }
    }

    // MARK: - 私人 FM

    static func personalFm() async -> [NetEaseSong] {
        let json = await weapiPost("/weapi/v1/radio/get", payload: "{\"csrf_token\":\"\"}")
        guard checkResponse(json, "personalFm"),
              let arr = json["data"] as? [[String: Any]] else { return [] }
        return arr.map { parseSongFmStyle($0) }
    }

    // MARK: - 新歌速递

    static func newSongs(areaId: Int = 0) async -> [NetEaseSong] {
        let payload = "{\"type\":\(areaId),\"limit\":20,\"offset\":0,\"total\":true,\"csrf_token\":\"\"}"
        let json = await weapiPost("/weapi/v1/discovery/new/songs", payload: payload)
        guard checkResponse(json, "newSongs"),
              let arr = json["data"] as? [[String: Any]] else { return [] }
        return arr.map { parseSongFmStyle($0) }
    }

    // MARK: - 相似歌曲

    static func similarSongs(songId: String) async -> [NetEaseSong] {
        let payload = "{\"songid\":\(songId),\"limit\":20,\"offset\":0,\"csrf_token\":\"\"}"
        let json = await weapiPost("/weapi/v1/discovery/simiSong", payload: payload)
        guard checkResponse(json, "similarSongs"),
              let arr = json["songs"] as? [[String: Any]] else { return [] }
        return arr.map { parseSongFmStyle($0) }
    }

    // MARK: - 解析辅助

    private static func parseSong(_ s: [String: Any]) -> NetEaseSong {
        let artists = artistsString(s["ar"])
        let al = s["al"] as? [String: Any]
        return NetEaseSong(
            id: toString(s["id"]),
            title: (s["name"] as? String) ?? "",
            artist: artists,
            album: (al?["name"] as? String) ?? "",
            durationMs: Int64((s["dt"] as? Int) ?? 0),
            coverUrl: (al?["picUrl"] as? String) ?? "",
            fee: (s["fee"] as? Int) ?? 0
        )
    }

    private static func parseSongFmStyle(_ s: [String: Any]) -> NetEaseSong {
        let artists = artistsString(s["artists"])
        let al = s["album"] as? [String: Any]
        return NetEaseSong(
            id: toString(s["id"]),
            title: (s["name"] as? String) ?? "",
            artist: artists,
            album: (al?["name"] as? String) ?? "",
            durationMs: Int64((s["duration"] as? Int) ?? 0),
            coverUrl: (al?["picUrl"] as? String) ?? "",
            fee: (s["fee"] as? Int) ?? 0
        )
    }

    private static func parsePlaylist(_ p: [String: Any]) -> NetEasePlaylist {
        let creator = (p["creator"] as? [String: Any])?["nickname"] as? String
            ?? (p["description"] as? String) ?? ""
        return NetEasePlaylist(
            id: toString(p["id"]),
            name: (p["name"] as? String) ?? "",
            coverUrl: (p["coverImgUrl"] as? String) ?? (p["picUrl"] as? String) ?? "",
            trackCount: (p["trackCount"] as? Int) ?? 0,
            creator: creator
        )
    }

    private static func artistsString(_ any: Any?) -> String {
        guard let arr = any as? [[String: Any]] else { return "" }
        return arr.compactMap { $0["name"] as? String }.joined(separator: "/")
    }

    private static func toString(_ any: Any?) -> String {
        if let s = any as? String { return s }
        if let i = any as? Int { return String(i) }
        if let i = any as? Int64 { return String(i) }
        if let d = any as? Double { return String(Int64(d)) }
        return ""
    }
}

// MARK: - 二维码 / 手机验证码登录（对应 NetEaseAuth.kt）

enum NetEaseAuth {
    /// 生成二维码 key
    static func createQrKey() async -> String? {
        let json = await NetEaseClient.weapiPost(
            "/weapi/login/qrcode/unikey", payload: "{\"type\":1,\"noCheckToken\":true}"
        )
        return (json["unikey"] as? String) ?? nil
    }

    /// 二维码内容串（UI 据此渲染二维码）
    static func qrContent(unikey: String) -> String {
        "\(NetEaseClient.base)/login?codekey=\(unikey)"
    }

    /// 轮询扫码状态
    static func pollQrStatus(unikey: String) async -> QrLoginResult {
        let payload = "{\"key\":\"\(unikey)\",\"type\":1}"
        let json = await NetEaseClient.weapiPost("/weapi/login/qrcode/client/login", payload: payload)
        let code = (json["code"] as? Int) ?? -1
        switch code {
        case 801: return QrLoginResult(state: .waiting)
        case 802: return QrLoginResult(state: .scanned)
        case 803: return QrLoginResult(state: .confirmed)
        case 800: return QrLoginResult(state: .expired)
        default: return QrLoginResult(state: .error)
        }
    }

    /// 拉账户信息并持久化
    static func fetchAccount() async -> NetEaseUserAccount? {
        let json = await NetEaseClient.weapiPost("/weapi/w/nuser/account/get", payload: "{}")
        let account = json["account"] as? [String: Any]
        let profile = json["profile"] as? [String: Any]
        if let account, let profile {
            let userId = Int64((account["id"] as? Int) ?? 0)
            let nickname = (profile["nickname"] as? String) ?? ""
            let avatar = (profile["avatarUrl"] as? String) ?? ""
            let vipType = (account["vipType"] as? Int) ?? 0
            if userId > 0 && !nickname.isEmpty {
                NetEaseSession.saveUser(userId, nickname, avatar, vipType)
                return NetEaseUserAccount(userId: userId, nickname: nickname, avatarUrl: avatar, vipType: vipType)
            }
        }
        // 兜底：MUSIC_U 已落库
        if NetEaseClient.hasLoginCookie() {
            let existingUid = NetEaseSession.getUserId()
            if existingUid > 0 {
                return NetEaseUserAccount(
                    userId: existingUid,
                    nickname: NetEaseSession.getNickname(),
                    avatarUrl: NetEaseSession.getAvatarUrl(),
                    vipType: NetEaseSession.getVipType()
                )
            }
            NetEaseSession.saveUser(0, "网易云用户", "", 0)
            return NetEaseUserAccount(userId: 0, nickname: "网易云用户", avatarUrl: "", vipType: 0)
        }
        return nil
    }

    static func logout() { NetEaseSession.logout() }

    // MARK: 手机验证码登录

    static func sendSmsCode(cellphone: String, ctcode: String = "86") async -> PhoneLoginResult {
        guard cellphone.count == 11, cellphone.allSatisfy(\.isNumber) else {
            return PhoneLoginResult(state: .smsFailed, message: "手机号格式不正确，需为11位数字")
        }
        let payload = "{\"cellphone\":\"\(cellphone)\",\"ctcode\":\"\(ctcode)\"}"
        let json = await NetEaseClient.weapiPost("/weapi/sms/captcha/sent", payload: payload)
        let code = (json["code"] as? Int) ?? -1
        switch code {
        case 200: return PhoneLoginResult(state: .codeSent, message: "验证码已发送，5分钟内有效")
        case 400: return PhoneLoginResult(state: .smsFailed, message: "手机号格式错误")
        case 501: return PhoneLoginResult(state: .smsFailed, message: "该手机号尚未绑定网易云账号")
        case 404, 506: return PhoneLoginResult(state: .smsFailed, message: "发送过于频繁，请稍后再试")
        case 502: return PhoneLoginResult(state: .smsFailed, message: "该手机号未绑定账号")
        default:
            let msg = (json["message"] as? String) ?? (json["msg"] as? String) ?? ""
            return PhoneLoginResult(state: .smsFailed,
                message: msg.isEmpty ? "发送失败(code=\(code))" : msg)
        }
    }

    static func loginWithPhone(cellphone: String, captcha: String, ctcode: String = "86") async -> PhoneLoginResult {
        guard cellphone.count == 11, cellphone.allSatisfy(\.isNumber) else {
            return PhoneLoginResult(state: .loginFailed, message: "手机号格式不正确")
        }
        guard captcha.count >= 4, captcha.count <= 6, captcha.allSatisfy(\.isNumber) else {
            return PhoneLoginResult(state: .loginFailed, message: "验证码应为4-6位数字")
        }
        // 注意：登录接口字段名是 phone + countrycode（不是 cellphone/ctcode）
        let payload = "{\"phone\":\"\(cellphone)\",\"countrycode\":\"\(ctcode)\",\"captcha\":\"\(captcha)\",\"rememberLogin\":\"true\"}"
        let json = await NetEaseClient.weapiPost("/weapi/login/cellphone", payload: payload)
        let code = (json["code"] as? Int) ?? -1
        if code != 200 {
            let msg = (json["message"] as? String) ?? (json["msg"] as? String) ?? ""
            switch code {
            case 503: return PhoneLoginResult(state: .loginFailed, message: "验证码错误或已过期")
            case 502: return PhoneLoginResult(state: .loginFailed, message: msg.isEmpty ? "账号或验证码错误" : msg)
            case 501: return PhoneLoginResult(state: .loginFailed, message: "该手机号尚未绑定网易云账号")
            case 505: return PhoneLoginResult(state: .loginFailed, message: "参数错误：手机号或验证码格式不对")
            case 400: return PhoneLoginResult(state: .loginFailed, message: "请求参数错误")
            default: return PhoneLoginResult(state: .loginFailed,
                message: msg.isEmpty ? "登录失败(code=\(code))" : msg)
            }
        }
        let account = json["account"] as? [String: Any]
        let profile = json["profile"] as? [String: Any]
        if let account, let profile {
            let userId = Int64((account["id"] as? Int) ?? 0)
            let nickname = (profile["nickname"] as? String) ?? ""
            let avatar = (profile["avatarUrl"] as? String) ?? ""
            let vipType = (account["vipType"] as? Int) ?? 0
            NetEaseSession.saveUser(userId, nickname, avatar, vipType)
            NetEaseSession.saveBoundPhone(cellphone)
        } else {
            let fetched = await fetchAccount()
            if fetched == nil {
                return PhoneLoginResult(state: .error, message: "登录成功但拉取账户信息失败")
            }
            NetEaseSession.saveBoundPhone(cellphone)
        }
        if !NetEaseSession.hasLoginCookie() {
            return PhoneLoginResult(state: .error,
                message: "登录成功但 MUSIC_U cookie 未落库，请重试或改用扫码登录")
        }
        _ = await fetchAccount()
        return PhoneLoginResult(state: .success, message: "登录成功")
    }
}

// MARK: - 歌词解析（对应 LyricsParser.kt，精简版）

enum LyricsParserSwift {
    static func parseLrc(_ text: String, _ translation: String) -> [NetEaseLyricLine] {
        var transMap: [Int64: String] = [:]
        for line in translation.split(separator: "\n") {
            guard let (ms, content) = parseLrcLine(String(line)) else { continue }
            transMap[ms] = content
        }
        var result: [NetEaseLyricLine] = []
        for line in text.split(separator: "\n") {
            guard let (ms, content) = parseLrcLine(String(line)) else { continue }
            result.append(NetEaseLyricLine(timeMs: ms, content: content, translation: transMap[ms] ?? ""))
        }
        return result.sorted { $0.timeMs < $1.timeMs }
    }

    private static func parseLrcLine(_ line: String) -> (Int64, String)? {
        // [mm:ss.xx]content，可能多个时间戳
        let pattern = #"\[(\d+):(\d+)(?:[.:](\d+))?\]"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let nsLine = line as NSString
        let matches = regex.matches(in: line, range: NSRange(location: 0, length: nsLine.length))
        if matches.isEmpty { return nil }
        let last = matches.last!
        let mm = Int64(nsLine.substring(with: last.range(at: 1))) ?? 0
        let ss = Int64(nsLine.substring(with: last.range(at: 2))) ?? 0
        var ms: Int64 = 0
        if last.range(at: 3).location != NSNotFound {
            let frac = nsLine.substring(with: last.range(at: 3))
            // 补齐到 3 位
            let padded = frac.padding(toLength: 3, withPad: "0", startingAt: 0)
            ms = Int64(padded) ?? 0
        }
        let totalMs = mm * 60_000 + ss * 1000 + ms
        let contentStart = last.range.location + last.range.length
        let content = contentStart < nsLine.length ? nsLine.substring(from: contentStart).trimmingCharacters(in: .whitespaces) : ""
        return (totalMs, content)
    }

    static func parseYrc(_ text: String, _ translation: String) -> [NetEaseYrcLine] {
        // 精简解析：[startMs,durationMs]字(起始,持续)...
        var result: [NetEaseYrcLine] = []
        for line in text.split(separator: "\n") {
            let s = String(line)
            // 行首 [ms,dur]
            guard let headerRange = s.range(of: #"\[(\d+),(\d+)\]"#, options: .regularExpression) else { continue }
            let header = String(s[headerRange])
            let nums = header.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
                .split(separator: ",")
            guard nums.count == 2,
                  let startMs = Int64(nums[0]),
                  let dur = Int64(nums[1]) else { continue }
            let contentPart = String(s[headerRange.upperBound...])
            // 提取纯文本作为整行内容（简化：去掉 (num,num) 标记）
            let cleaned = contentPart.replacingOccurrences(
                of: #"\(\d+,\d+\)"#, with: "", options: .regularExpression
            ).trimmingCharacters(in: .whitespaces)
            let chars = [NetEaseYrcChar(startMs: startMs, durationMs: dur, content: cleaned)]
            result.append(NetEaseYrcLine(startMs: startMs, durationMs: dur, chars: chars, translation: ""))
        }
        return result.sorted { $0.startMs < $1.startMs }
    }
}

// MARK: - URL 编码辅助

private extension String {
    var urlEncoded: String {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "&=+")
        return addingPercentEncoding(withAllowedCharacters: allowed) ?? self
    }
}

extension NetEaseClient {
    /// 便捷：是否已登录（暴露给 UI）
    static func hasLoginCookie() -> Bool { NetEaseSession.hasLoginCookie() }
}
