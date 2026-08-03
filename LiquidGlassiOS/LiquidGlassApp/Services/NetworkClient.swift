import Foundation

// ─────────────────────────────────────────────────────────────────
// 网络客户端 —— 对应 Android 端的 OkHttp 封装（NetEaseApiClient / NetEaseApi）
//
// 用 URLSession 封装常用 HTTP 方法，提供：
//   - GET / POST / PUT / DELETE
//   - 自定义请求头
//   - JSON 编解码
//   - async/await 接口，便于在 SwiftUI Task 中调用
//   - 统一错误类型 NetworkError
// ─────────────────────────────────────────────────────────────────

// MARK: - 网络错误
enum NetworkError: LocalizedError {
    case invalidURL
    case requestFailed(Int, String?)   // statusCode, body
    case decodingFailed(Error)
    case transport(Error)
    case noResponse

    var errorDescription: String? {
        switch self {
        case .invalidURL:                 return "无效的请求地址"
        case .requestFailed(let code, _): return "请求失败（HTTP \(code)）"
        case .decodingFailed(let err):    return "数据解析失败：\(err.localizedDescription)"
        case .transport(let err):         return "网络传输失败：\(err.localizedDescription)"
        case .noResponse:                 return "无服务器响应"
        }
    }
}

// MARK: - HTTP 方法
enum HTTPMethod: String {
    case get = "GET"
    case post = "POST"
    case put = "PUT"
    case delete = "DELETE"
}

// MARK: - 网络客户端
final class NetworkClient {

    static let shared = NetworkClient()

    private let session: URLSession
    /// 默认请求头（User-Agent、Content-Type 等）。
    private var defaultHeaders: [String: String]

    init(session: URLSession = .shared) {
        self.session = session
        self.defaultHeaders = [
            "User-Agent": "LiquidGlassiOS/1.0 (iOS)",
            "Accept": "application/json"
        ]
    }

    /// 设置/覆盖默认请求头（如登录后写入 Cookie / Authorization）。
    func setDefaultHeader(_ value: String, for field: String) {
        defaultHeaders[field] = value
    }

    // MARK: - 通用请求
    /// 发起请求并返回 Data。
    func request(_ url: URL,
                 method: HTTPMethod = .get,
                 headers: [String: String] = [:],
                 body: Data? = nil) async throws -> (Data, HTTPURLResponse) {
        var req = URLRequest(url: url)
        req.httpMethod = method.rawValue
        // 合并默认头与本次自定义头（后者覆盖前者）
        for (k, v) in defaultHeaders { req.setValue(v, forHTTPHeaderField: k) }
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        if let body { req.httpBody = body }

        do {
            let (data, response) = try await session.data(for: req)
            guard let http = response as? HTTPURLResponse else {
                throw NetworkError.noResponse
            }
            // 对应 OkHttp 的 unsuccessful() 检查：状态码非 2xx 视为失败
            guard (200..<300).contains(http.statusCode) else {
                let bodyStr = String(data: data, encoding: .utf8)
                throw NetworkError.requestFailed(http.statusCode, bodyStr)
            }
            return (data, http)
        } catch let err as NetworkError {
            throw err
        } catch {
            throw NetworkError.transport(error)
        }
    }

    // MARK: - JSON 便捷方法
    /// GET 请求并解码为指定类型。
    func get<T: Decodable>(_ url: URL, as type: T.Type, headers: [String: String] = [:]) async throws -> T {
        let (data, _) = try await request(url, method: .get, headers: headers)
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw NetworkError.decodingFailed(error)
        }
    }

    /// POST JSON 请求并解码响应。
    func post<T: Decodable, B: Encodable>(_ url: URL, body: B, as type: T.Type,
                                          headers: [String: String] = [:]) async throws -> T {
        var merged = headers
        if merged["Content-Type"] == nil { merged["Content-Type"] = "application/json" }
        let bodyData = try JSONEncoder().encode(body)
        let (data, _) = try await request(url, method: .post, headers: merged, body: bodyData)
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw NetworkError.decodingFailed(error)
        }
    }

    /// GET 请求并直接返回 Data（不解码，便于下载流式资源）。
    func getData(_ url: URL, headers: [String: String] = [:]) async throws -> Data {
        let (data, _) = try await request(url, method: .get, headers: headers)
        return data
    }
}
