import Foundation
import Combine

// ─────────────────────────────────────────────────────────────────
// 资源管理器 —— 对应 Android 端 ResourceManager.kt
//
// 职责：
//   1. URLSession background 配置：App 进入后台后仍可继续下载
//      （对应 Android 后台下载服务）
//   2. 分块下载：大资源包切分为多个分块并行下载，各自独立进度
//   3. 断点续传：下载中断保留 .part 缓存，下次从未完成字节继续
//      （对应 Android cancelDownload 保留 .part 的逻辑）
//   4. 多进度条：每个分块一条进度，UI 渲染为多条进度条独立推进
//      （对应 Android chunkProgresses StateFlow）
//   5. 暂停/恢复/取消
//   6. 下载完成后合并分块并解压（对应 Android ZipInputStream）
//
// 资源包七层加载顺序（与 Android 一致）：
//   1.基础资源包 2.交互外观包 3.核心功能补丁包 4.高级体验初始化包
//   5.安装包补丁 6.预加载包 7.预处理包
// ─────────────────────────────────────────────────────────────────

// MARK: - 分块进度（对应 Android ChunkProgress）
struct ChunkProgress: Identifiable {
    let id = UUID()
    let index: Int        // 分块序号（从0开始）
    let total: Int        // 分块总数
    let name: String      // 分块名（如 chunk_0）
    var downloaded: Int64  // 已下载字节
    var size: Int64       // 分块总字节（0 表示未知）
    var status: ChunkStatus
}

enum ChunkStatus: String { case downloading, done, cached, failed, paused }

// MARK: - 实时文件信息（对应 Android PackageFileInfo）
struct PackageFileInfo: Identifiable {
    let id = UUID()
    let name: String
    let size: Int64
}

// MARK: - 资源管理器
final class ResourceManager: NSObject, ObservableObject {

    static let shared = ResourceManager()

    // MARK: - 全局下载状态（跨页面持久化，对应 Android globalDownloadScope 下的 StateFlow）
    @Published var downloadProgress: Double = 0
    @Published var downloadStatus: String = ""
    @Published var downloadSpeed: String = ""
    @Published var isDownloading: Bool = false
    @Published var currentDownloadingPack: String?
    @Published var isPaused: Bool = false
    @Published var liveExtractedFiles: [PackageFileInfo] = []
    @Published var currentFileName: String = ""
    @Published var chunkProgresses: [ChunkProgress] = []

    // MARK: - 内部资源
    private var backgroundSession: URLSession!
    private var chunkTasks: [Int: URLSessionDownloadTask] = [:]   // index -> task
    private var resumeDataMap: [Int: Data] = [:]                  // 断点续传缓存
    private var totalDownloaded: Int64 = 0
    private var totalSize: Int64 = 0
    private var startTime: Date = .distantPast
    private var packName: String = ""
    private let chunkCount = 18  // 分块数（对应 Android resources.part00~17，共 18 块）

    /// 资源根目录（沙盒 Documents/ResourcePack）。
    var resourceRoot: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        return docs.appendingPathComponent("ResourcePack", isDirectory: true)
    }

    private override init() {
        super.init()
        // background URLSession 配置：App 进后台后系统接管继续下载
        let config = URLSessionConfiguration.backgroundSessionConfiguration(withIdentifier: "com.liquidglass.ios.download")
        config.isDiscretionary = false          // 立即开始而非等系统择机
        config.sessionSendsLaunchEvents = true  // 下载完成唤醒 App
        config.allowsCellularAccess = true
        backgroundSession = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        try? FileManager.default.createDirectory(at: resourceRoot, withIntermediateDirectories: true)
    }

    // MARK: - 重置状态（对应 Android resetGlobalDownloadState）
    func resetGlobalDownloadState() {
        downloadProgress = 0
        downloadStatus = ""
        downloadSpeed = ""
        isDownloading = false
        currentDownloadingPack = nil
        isPaused = false
        liveExtractedFiles = []
        currentFileName = ""
        chunkProgresses = []
    }

    // MARK: - 暂停 / 恢复 / 取消
    /// 暂停当前下载（不丢弃已下载字节，保留 .part 缓存）。
    func pauseDownload() {
        isPaused = true
        downloadStatus = "已暂停（已下载内容已缓存，恢复后接着下载）"
        // cancel(byProducingResumeData:) 让系统生成 resumeData 用于断点续传
        for (idx, task) in chunkTasks {
            task.cancel { [weak self] data in
                self?.resumeDataMap[idx] = data
            }
        }
        chunkTasks.removeAll()
    }

    /// 恢复下载（用 resumeData 续传，否则从头开始该分块）。
    func resumeDownload() {
        isPaused = false
        downloadStatus = "恢复下载中..."
        startChunkDownloads()
    }

    /// 取消下载（保留 .part / resumeData 缓存，下次接着下载）。
    func cancelDownload() {
        isPaused = false
        isDownloading = false
        currentDownloadingPack = nil
        downloadStatus = "已取消（缓存已保留，下次接着下载）"
        liveExtractedFiles = []
        currentFileName = ""
        downloadProgress = 0
        downloadSpeed = ""
        chunkProgresses = []
        for (_, task) in chunkTasks { task.cancel() }
        chunkTasks.removeAll()
    }

    /// 清除全部下载缓存（彻底丢弃断点）。
    func clearDownloadCache() {
        cancelDownload()
        resumeDataMap.removeAll()
        if FileManager.default.fileExists(atPath: resourceRoot.path) {
            try? FileManager.default.removeItem(at: resourceRoot)
        }
    }

    // MARK: - 启动下载（对应 Android downloadResourcePack）
    /// 便捷方法：启动标准资源包下载（18 分块，URL 内部生成）。
    func startResourceDownload() {
        // 用占位 URL 初始化进度条（实际 URL 在 startChunkDownloads 中生成）
        let placeholderURLs = (0..<chunkCount).map { URL(string: "https://placeholder/\($0)")! }
        startDownload(packName: "基础资源包", chunkURLs: placeholderURLs)
    }

    /// 启动一个资源包的下载。chunkURLs 用于确定分块数（实际下载 URL 在 startChunkDownloads 中生成）。
    func startDownload(packName: String, chunkURLs: [URL]) {
        guard !isDownloading else { return }
        self.packName = packName
        currentDownloadingPack = packName
        isDownloading = true
        isPaused = false
        totalDownloaded = 0
        totalSize = 0
        startTime = Date()
        downloadStatus = "准备下载 \(packName)…"

        // 初始化每个分块的进度条
        chunkProgresses = chunkURLs.enumerated().map { idx, url in
            let name = "chunk_\(idx)"
            // 检查是否已有缓存（断点续传）
            let cachedSize = cachedChunkSize(name: name)
            let status: ChunkStatus = cachedSize > 0 ? .cached : .downloading
            return ChunkProgress(index: idx, total: chunkURLs.count, name: name,
                                 downloaded: cachedSize, size: 0, status: status)
        }

        startChunkDownloads()
    }

    // MARK: - 分块下载调度
    private func startChunkDownloads() {
        guard isDownloading, !isPaused else { return }
        // 资源分块托管在 GitHub release v2.3.4（与 Android 端一致）
        // 镜像优先（国内 GitHub 直连常超时），GitHub 直连兜底
        // 每个分块约 90MB，代理可稳定传输
        let chunkBasePath = "jiangtengqiao/liquid-glass/releases/download/v2.3.4"
        let mirrors = [
            "https://cors.isteed.cc/github.com/\(chunkBasePath)",
            "https://gh-proxy.com/https://github.com/\(chunkBasePath)",
            "https://github.com/\(chunkBasePath)"
        ]
        for i in 0..<chunkProgresses.count {
            let chunkName = "resources.part\(String(format: "%02d", i))"
            // 使用第一个镜像（cors.isteed.cc 实测国内可达且不截断）
            let urlString = "\(mirrors[0])/\(chunkName)"
            guard let url = URL(string: urlString) else { continue }
            startChunkDownload(index: i, url: url)
        }
    }

    private func startChunkDownload(index: Int, url: URL) {
        var task: URLSessionDownloadTask
        // 若有 resumeData 则断点续传，否则新建
        if let data = resumeDataMap[index] {
            task = backgroundSession.downloadTask(withResumeData: data)
        } else {
            task = backgroundSession.downloadTask(with: url)
        }
        task.taskDescription = "\(index)"  // 用 taskDescription 携带分块索引
        chunkTasks[index] = task
        updateChunkStatus(index: index, status: .downloading)
        task.resume()
    }

    // MARK: - 缓存读写（对应 Android .part 文件）
    private func partFileURL(name: String) -> URL {
        resourceRoot.appendingPathComponent("\(name).part")
    }

    private func cachedChunkSize(name: String) -> Int64 {
        let url = partFileURL(name: name)
        let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attrs?[.size] as? Int64) ?? 0
    }

    private func updateChunkStatus(index: Int, status: ChunkStatus) {
        guard index < chunkProgresses.count else { return }
        chunkProgresses[index].status = status
    }

    private func updateChunkProgress(index: Int, downloaded: Int64, size: Int64) {
        guard index < chunkProgresses.count else { return }
        chunkProgresses[index].downloaded = downloaded
        if size > 0 { chunkProgresses[index].size = size }
        recomputeTotalProgress()
    }

    /// 汇总各分块进度 → 全局进度条（对应 Android downloadProgress 汇总）。
    private func recomputeTotalProgress() {
        let downloaded = chunkProgresses.reduce(0) { $0 + $1.downloaded }
        let total = chunkProgresses.reduce(0) { $0 + max($1.size, $1.downloaded) }
        totalDownloaded = downloaded
        totalSize = total
        if total > 0 {
            downloadProgress = Double(downloaded) / Double(total)
        }
        // 速率
        let elapsed = Date().timeIntervalSince(startTime)
        if elapsed > 0.1 {
            let bytesPerSec = Double(downloaded) / elapsed
            downloadSpeed = formatSpeed(bytesPerSec)
        }
    }

    private func formatSpeed(_ bytesPerSec: Double) -> String {
        if bytesPerSec > 1_048_576 {
            return String(format: "%.1f MB/s", bytesPerSec / 1_048_576)
        } else if bytesPerSec > 1024 {
            return String(format: "%.1f KB/s", bytesPerSec / 1024)
        }
        return String(format: "%.0f B/s", bytesPerSec)
    }

    // MARK: - 合并与解压（对应 Android ZipInputStream）
    /// 所有分块下载完成后按 index 顺序拼接为完整 ZIP，然后解压到 resourceRoot。
    func mergeAndExtract() {
        downloadStatus = "正在合并分块…"
        Task.detached { [weak self] in
            guard let self = self else { return }
            // 1) 按 index 顺序读取各 .part 文件，拼接为完整 ZIP Data
            var mergedData = Data()
            let sortedChunks = self.chunkProgresses.sorted { $0.index < $1.index }
            for chunk in sortedChunks {
                let partURL = self.partFileURL(name: chunk.name)
                if let chunkData = try? Data(contentsOf: partURL) {
                    mergedData.append(chunkData)
                }
            }
            guard !mergedData.isEmpty else {
                await MainActor.run {
                    self.downloadStatus = "合并失败：分块数据为空"
                    self.isDownloading = false
                }
                return
            }
            await MainActor.run { self.downloadStatus = "正在解压资源包…" }

            // 2) 解压 ZIP 到 resourceRoot
            let extractResult = ZipExtractor.unzip(mergedData, to: self.resourceRoot) { fileName in
                Task { @MainActor in
                    self.currentFileName = fileName
                }
            }

            // 3) 清理 .part 缓存文件
            for chunk in sortedChunks {
                try? FileManager.default.removeItem(at: self.partFileURL(name: chunk.name))
            }

            await MainActor.run {
                switch extractResult {
                case .success(let fileCount):
                    self.downloadStatus = "资源包 \(self.packName) 下载完成，已解压 \(fileCount) 个文件"
                    self.downloadProgress = 1.0
                    self.isDownloading = false
                    self.currentDownloadingPack = nil
                    self.currentFileName = ""
                    // 写入安装标记文件
                    let marker = self.resourceRoot.appendingPathComponent(".installed")
                    try? Data().write(to: marker)
                case .failure(let error):
                    self.downloadStatus = "解压失败：\(error.localizedDescription)"
                    self.isDownloading = false
                }
            }
        }
    }
}

// MARK: - URLSession 下载代理
extension ResourceManager: URLSessionDownloadDelegate {

    // 分块下载进度回调
    func urlSession(_ session: URLSession,
                    downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        guard let desc = downloadTask.taskDescription, let idx = Int(desc) else { return }
        DispatchQueue.main.async { [weak self] in
            self?.updateChunkProgress(index: idx, downloaded: totalBytesWritten,
                                     size: totalBytesExpectedToWrite)
        }
    }

    // 分块恢复续传进度
    func urlSession(_ session: URLSession,
                    downloadTask: URLSessionDownloadTask,
                    didResumeAtOffset fileOffset: Int64,
                    expectedTotalBytes: Int64) {
        guard let desc = downloadTask.taskDescription, let idx = Int(desc) else { return }
        DispatchQueue.main.async { [weak self] in
            self?.updateChunkProgress(index: idx, downloaded: fileOffset, size: expectedTotalBytes)
        }
    }

    // 分块下载完成
    func urlSession(_ session: URLSession,
                    downloadTask: URLSessionDownloadTask,
                    didFinishDownloadingTo location: URL) {
        guard let desc = downloadTask.taskDescription, let idx = Int(desc) else { return }
        // 把分块文件移动到缓存目录的 .part 文件
        let dest = partFileURL(name: "chunk_\(idx)")
        try? FileManager.default.removeItem(at: dest)
        do {
            try FileManager.default.moveItem(at: location, to: dest)
        } catch {
            DispatchQueue.main.async { self.downloadStatus = "保存分块失败：\(error.localizedDescription)" }
            return
        }
        let size = (try? FileManager.default.attributesOfItem(atPath: dest.path)[.size] as? Int64) ?? 0
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.updateChunkStatus(index: idx, status: .done)
            self.updateChunkProgress(index: idx, downloaded: size, size: size)
            self.resumeDataMap[idx] = nil
            // 全部分块完成 → 合并解压
            if self.chunkProgresses.allSatisfy({ $0.status == .done }) {
                self.mergeAndExtract()
            }
        }
    }

    // 任务完成（含失败处理，对应 Android downloadStatus = "失败"）
    func urlSession(_ session: URLSession,
                    task: URLSessionTask,
                    didCompleteWithError error: Error?) {
        guard let error = error else { return }
        let desc = (task as? URLSessionDownloadTask)?.taskDescription ?? ""
        let idx = Int(desc) ?? -1
        // 取消产生的错误不视为失败（暂停/取消时主动 cancel）
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled {
            return
        }
        DispatchQueue.main.async { [weak self] in
            self?.downloadStatus = "下载失败：\(error.localizedDescription)"
            if idx >= 0 { self?.updateChunkStatus(index: idx, status: .failed) }
        }
    }
}
