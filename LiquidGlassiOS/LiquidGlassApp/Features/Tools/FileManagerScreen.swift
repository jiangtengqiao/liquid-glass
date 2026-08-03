import SwiftUI
import UIKit

// ─────────────────────────────────────────────────────────────────
// 文件管理 —— 对应 Android 端 ui/FileManagerScreen.kt
//
// 关键实现：
//   1. 浏览 App 沙盒 Documents 目录（对应 Android 外部存储根目录）。
//      iOS 沙盒限制下不可越出 rootURL，导航上行停在 Documents。
//   2. 用 FileManager.contentsOfDirectory + URLResourceValues 读取
//      子项 / 是否目录 / 大小 / 修改时间（对应 Android File.listFiles）。
//   3. 按类型显示 SF Symbols 图标 + 流体色（对应 Android getFileTypeIcon/Color）。
//   4. 排序：名称 / 大小 / 日期 / 类型；搜索过滤；目录优先。
//   5. 操作：进入文件夹、新建文件夹、重命名、删除、复制路径、属性。
//   6. 基础预览：文本 / 图片用原生渲染，其它显示属性摘要。
//   7. 多选：长按进入多选模式，批量勾选（对应 Android multiSelectMode）。
//   8. 存储空间卡：URLResourceValues 读取卷总容量 / 可用容量。
// ─────────────────────────────────────────────────────────────────

// MARK: - 数据模型
/// 文件类型（对应 Android FileType）。
enum FileType: Int, CaseIterable {
    case folder, image, video, audio, document, archive, code, apk, unknown
}

/// 排序模式（对应 Android SortMode）。
enum SortMode: CaseIterable, Hashable {
    case name, size, date, type
    var label: String {
        switch self {
        case .name: return "按名称"
        case .size: return "按大小"
        case .date: return "按日期"
        case .type: return "按类型"
        }
    }
}

/// 单个文件信息（对应 Android FileInfo）。
struct FileInfo: Identifiable {
    let id: String          // url.path
    let url: URL
    let name: String
    let isDirectory: Bool
    let size: Int64
    let lastModified: Date
    let fileType: FileType
}

// MARK: - 扩展名集合（对应 Android 各 xxxExtensions）
private let imageExtensions: Set<String> =
    ["jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "heic", "heif", "ico", "tiff"]
private let videoExtensions: Set<String> =
    ["mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "3gp", "m4v", "ts"]
private let audioExtensions: Set<String> =
    ["mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus", "mid", "midi"]
private let documentExtensions: Set<String> =
    ["pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf",
     "odt", "ods", "odp", "md", "log"]
private let archiveExtensions: Set<String> =
    ["zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "tbz", "iso", "dmg"]
private let codeExtensions: Set<String> =
    ["kt", "java", "py", "js", "ts", "html", "htm", "css", "scss", "less",
     "json", "xml", "yaml", "yml", "c", "cpp", "h", "hpp", "cs", "rb", "go",
     "rs", "swift", "sh", "bat", "sql", "gradle", "properties", "toml"]

private func getFileType(url: URL, isDirectory: Bool) -> FileType {
    if isDirectory { return .folder }
    let ext = url.pathExtension.lowercased()
    if imageExtensions.contains(ext) { return .image }
    if videoExtensions.contains(ext) { return .video }
    if audioExtensions.contains(ext) { return .audio }
    if documentExtensions.contains(ext) { return .document }
    if archiveExtensions.contains(ext) { return .archive }
    if codeExtensions.contains(ext) { return .code }
    if ext == "apk" { return .apk }
    return .unknown
}

private func fileTypeIcon(_ t: FileType) -> String {
    switch t {
    case .folder:   return "folder.fill"
    case .image:    return "photo"
    case .video:    return "video.fill"
    case .audio:    return "music.note"
    case .document: return "doc.text.fill"
    case .archive:  return "archivebox.fill"
    case .code:     return "chevron.left.forwardslash.chevron.right"
    case .apk:      return "app.fill"
    case .unknown:  return "doc"
    }
}

private func fileTypeColor(_ t: FileType, theme: AppTheme) -> Color {
    switch t {
    case .folder:   return theme.fluidCyan
    case .image:    return theme.fluidPink
    case .video:    return theme.fluidPurple
    case .audio:    return theme.fluidOrange
    case .document: return theme.accentPrimary
    case .archive:  return AccentWarning
    case .code:     return theme.fluidTeal
    case .apk:      return theme.fluidTeal
    case .unknown:  return theme.textSecondary
    }
}

// MARK: - 格式化
private func formatFileSize(_ bytes: Int64) -> String {
    if bytes <= 0 { return "0 B" }
    let units = ["B", "KB", "MB", "GB", "TB"]
    let digits = Int(log2(Double(bytes)) / 10.0) // log2(1024) = 10
    let idx = min(max(0, digits), units.count - 1)
    let size = Double(bytes) / pow(1024.0, Double(idx))
    return String(format: "%.1f %@", size, units[idx])
}

private let fileDateFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "yyyy/MM/dd HH:mm"
    return f
}()

private func formatDate(_ date: Date) -> String {
    fileDateFormatter.string(from: date)
}

// MARK: - 存储信息（对应 Android StorageInfo + StatFs）
private struct StorageInfo {
    let total: Int64
    let available: Int64
    let used: Int64
}

private func fetchStorageInfo(url: URL) -> StorageInfo {
    let keys: Set<URLResourceKey> = [
        .volumeTotalCapacityKey,
        .volumeAvailableCapacityForImportantUsageKey
    ]
    guard let values = try? url.resourceValues(forKeys: keys),
          let total = values.volumeTotalCapacity else {
        return StorageInfo(total: 0, available: 0, used: 0)
    }
    let avail = values.volumeAvailableCapacityForImportantUsage ?? 0
    return StorageInfo(total: Int64(total),
                       available: Int64(avail),
                       used: Int64(total) - Int64(avail))
}

// MARK: - 视图模型
final class FileManagerViewModel: ObservableObject {
    @Published var currentURL: URL
    @Published var files: [FileInfo] = []
    @Published var searchQuery: String = ""
    @Published var sortMode: SortMode = .name
    @Published var multiSelectMode: Bool = false
    @Published var selectedPaths: Set<String> = []
    @Published var toast: String? = nil

    /// 起始根目录：App 的 Documents 目录。
    let rootURL: URL

    private let fm = FileManager.default

    init() {
        rootURL = fm.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        currentURL = rootURL
        refresh()
    }

    func refresh() {
        files = listFiles(at: currentURL)
        selectedPaths = []
        multiSelectMode = false
    }

    func navigate(to url: URL) {
        currentURL = url
        searchQuery = ""
        refresh()
    }

    /// 上行到父目录，但不可越过 rootURL。
    func navigateUp() {
        guard currentURL != rootURL else { return }
        let parent = currentURL.deletingLastPathComponent()
        guard parent.path.hasPrefix(rootURL.path) else { return }
        navigate(to: parent)
    }

    var canNavigateUp: Bool { currentURL != rootURL }

    /// 当前目录相对 rootURL 的可显示名称（root 显示“文档”）。
    var currentDirName: String {
        currentURL == rootURL ? "文档" : currentURL.lastPathComponent
    }

    /// 面包屑路径分段（相对 root 的各层级名称 + 完整 URL）。
    var breadcrumbSegments: [(name: String, url: URL)] {
        var segs: [(String, URL)] = []
        var url = currentURL
        while true {
            let name = (url == rootURL) ? "文档" : url.lastPathComponent
            segs.append((name, url))
            if url == rootURL { break }
            let parent = url.deletingLastPathComponent()
            if !parent.path.hasPrefix(rootURL.path) { break }
            url = parent
        }
        return segs.reversed()
    }

    var sortedFiles: [FileInfo] {
        let filtered = searchQuery.isEmpty
            ? files
            : files.filter { $0.name.localizedCaseInsensitiveContains(searchQuery) }
        return filtered.sorted { a, b in
            if a.isDirectory != b.isDirectory { return a.isDirectory && !b.isDirectory }
            switch sortMode {
            case .name: return a.name.localizedLowercase < b.name.localizedLowercase
            case .size: return a.size < b.size
            case .date: return a.lastModified < b.lastModified
            case .type: return a.fileType.rawValue < b.fileType.rawValue
            }
        }
    }

    func toggleSelect(_ info: FileInfo) {
        if selectedPaths.contains(info.id) {
            selectedPaths.remove(info.id)
        } else {
            selectedPaths.insert(info.id)
        }
    }

    func clearSelection() {
        multiSelectMode = false
        selectedPaths = []
    }

    // MARK: 文件操作
    func createFolder(name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let target = currentURL.appendingPathComponent(trimmed)
        do {
            try fm.createDirectory(at: target, withIntermediateDirectories: false)
            refresh()
            toast = "已创建文件夹"
        } catch {
            toast = "创建失败：\(error.localizedDescription)"
        }
    }

    func rename(_ info: FileInfo, to newName: String) {
        let trimmed = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed != info.name else { return }
        let target = info.url.deletingLastPathComponent().appendingPathComponent(trimmed)
        do {
            try fm.moveItem(at: info.url, to: target)
            refresh()
            toast = "重命名成功"
        } catch {
            toast = "重命名失败：\(error.localizedDescription)"
        }
    }

    func delete(_ info: FileInfo) {
        do {
            try fm.removeItem(at: info.url)
            refresh()
            toast = "已删除"
        } catch {
            toast = "删除失败：\(error.localizedDescription)"
        }
    }

    func copyPath(_ info: FileInfo) {
        UIPasteboard.general.string = info.url.path
        toast = "路径已复制"
    }

    var storageInfo: StorageInfo { fetchStorageInfo(url: rootURL) }

    // MARK: 读取目录
    private func listFiles(at url: URL) -> [FileInfo] {
        guard let entries = try? fm.contentsOfDirectory(
            at: url,
            includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles]) else { return [] }
        return entries.map { fileURL in
            let values = try? fileURL.resourceValues(
                forKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey])
            let isDir = values?.isDirectory ?? false
            let size = Int64(values?.fileSize ?? 0)
            let mod = values?.contentModificationDate ?? Date()
            return FileInfo(
                url: fileURL,
                name: fileURL.lastPathComponent,
                isDirectory: isDir,
                size: size,
                lastModified: mod,
                fileType: getFileType(url: fileURL, isDirectory: isDir)
            )
        }
    }
}

// MARK: - 主视图
struct FileManagerScreen: View {
    var onBack: () -> Void

    @StateObject private var vm = FileManagerViewModel()
    @State private var showSortMenu: Bool = false
    @State private var sheet: FileManagerSheet? = nil
    @State private var deleteTarget: FileInfo? = nil

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: 0, theme: theme)

            VStack(spacing: 12) {
                topBar(theme: theme)
                searchBar(theme: theme)
                breadcrumbBar(theme: theme)
                if vm.sortedFiles.isEmpty {
                    emptyState(theme: theme)
                    Spacer()
                    storageInfoCard(theme: theme)
                } else {
                    fileList(theme: theme)
                    storageInfoCard(theme: theme)
                }
            }
            .padding(.top, 50)
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
        .sheet(item: $sheet) { s in
            switch s {
            case .createFolder:
                TextInputSheet(title: "新建文件夹",
                               placeholder: "文件夹名称",
                               initialText: "",
                               theme: theme) { name in
                    if let name { vm.createFolder(name: name) }
                    sheet = nil
                }
            case .rename(let info):
                TextInputSheet(title: "重命名",
                               placeholder: "新名称",
                               initialText: info.name,
                               theme: theme) { name in
                    if let name { vm.rename(info, to: name) }
                    sheet = nil
                }
            case .properties(let info):
                PropertiesSheet(info: info, theme: theme) { sheet = nil }
            case .preview(let info):
                PreviewSheet(info: info, theme: theme) { sheet = nil }
            }
        }
        .alert("删除文件",
               isPresented: Binding(get: { deleteTarget != nil },
                                    set: { if !$0 { deleteTarget = nil } })) {
            Button("取消", role: .cancel) { deleteTarget = nil }
            Button("删除", role: .destructive) {
                if let t = deleteTarget { vm.delete(t) }
                deleteTarget = nil
            }
        } message: {
            if let t = deleteTarget {
                Text("确定要删除「\(t.name)」吗？此操作不可撤销。")
            }
        }
        .overlay(alignment: .bottom) {
            if let toast = vm.toast {
                Text(toast)
                    .font(.system(size: 13))
                    .foregroundStyle(theme.textPrimary)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .glassSurface(cornerRadius: 16, glassAlpha: 0.22, theme: theme)
                    .padding(.bottom, 100)
                    .transition(.opacity)
            }
        }
        .onChange(of: vm.toast) { _ in
            guard vm.toast != nil else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                withAnimation { vm.toast = nil }
            }
        }
    }

    // MARK: 顶部栏
    private func topBar(theme: AppTheme) -> some View {
        HStack {
            Button { onBack() } label: {
                Image(systemName: "chevron.left")
                    .font(.headline)
                    .foregroundStyle(theme.textSecondary)
            }
            Spacer()
            Text("文件管理")
                .font(.headline)
                .foregroundStyle(theme.textPrimary)
            Spacer()
            if vm.multiSelectMode {
                Text("已选 \(vm.selectedPaths.count) 项")
                    .font(.system(size: 13))
                    .foregroundStyle(theme.accentPrimary)
                Button {
                    vm.clearSelection()
                } label: {
                    Text("取消")
                        .font(.system(size: 13))
                        .foregroundStyle(theme.textSecondary)
                }
                .buttonStyle(.plain)
            } else {
                Menu {
                    ForEach(SortMode.allCases, id: \.self) { mode in
                        Button {
                            vm.sortMode = mode
                        } label: {
                            if mode == vm.sortMode {
                                Label(mode.label, systemImage: "checkmark")
                            } else {
                                Text(mode.label)
                            }
                        }
                    }
                } label: {
                    Image(systemName: "arrow.up.arrow.down")
                        .font(.system(size: 15))
                        .foregroundStyle(theme.textSecondary)
                }
                Button {
                    sheet = .createFolder
                } label: {
                    Image(systemName: "folder.badge.plus")
                        .font(.system(size: 16))
                        .foregroundStyle(theme.fluidCyan)
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: 搜索栏
    private func searchBar(theme: AppTheme) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(theme.textTertiary)
            TextField("搜索文件...", text: $vm.searchQuery)
                .foregroundStyle(theme.textPrimary)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            if !vm.searchQuery.isEmpty {
                Button {
                    vm.searchQuery = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(theme.textTertiary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .glassSurface(cornerRadius: 16, glassAlpha: 0.12, theme: theme)
    }

    // MARK: 面包屑
    private func breadcrumbBar(theme: AppTheme) -> some View {
        HStack(spacing: 4) {
            Button {
                vm.navigateUp()
            } label: {
                Image(systemName: "arrow.up")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(vm.canNavigateUp ? theme.textSecondary : theme.textTertiary)
            }
            .disabled(!vm.canNavigateUp)
            .buttonStyle(.plain)

            Image(systemName: "folder.fill")
                .font(.system(size: 12))
                .foregroundStyle(theme.fluidCyan.opacity(0.7))

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(Array(vm.breadcrumbSegments.enumerated()), id: \.offset) { idx, seg in
                        if idx > 0 {
                            Text("›")
                                .font(.system(size: 12))
                                .foregroundStyle(theme.textTertiary)
                        }
                        Button {
                            vm.navigate(to: seg.url)
                        } label: {
                            Text(seg.name)
                                .font(.system(size: 12,
                                               weight: idx == vm.breadcrumbSegments.count - 1 ? .medium : .regular))
                                .foregroundStyle(idx == vm.breadcrumbSegments.count - 1
                                                 ? theme.textSecondary : theme.textTertiary)
                                .lineLimit(1)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .glassSurface(cornerRadius: 14, glassAlpha: 0.10, theme: theme)
    }

    // MARK: 文件列表
    private func fileList(theme: AppTheme) -> some View {
        ScrollView {
            LazyVStack(spacing: 6) {
                ForEach(vm.sortedFiles) { info in
                    FileItemView(
                        info: info,
                        isSelected: vm.selectedPaths.contains(info.id),
                        multiSelectMode: vm.multiSelectMode,
                        theme: theme,
                        onTap: {
                            if vm.multiSelectMode {
                                vm.toggleSelect(info)
                            } else if info.isDirectory {
                                vm.navigate(to: info.url)
                            } else {
                                sheet = .preview(info)
                            }
                        },
                        onLongPress: {
                            if !vm.multiSelectMode {
                                vm.multiSelectMode = true
                                vm.selectedPaths.insert(info.id)
                            }
                        },
                        onMenu: { handleMenuAction($0, info: info) }
                    )
                }
                Spacer().frame(height: 20)
            }
        }
    }

    private func handleMenuAction(_ action: FileMenuAction, info: FileInfo) {
        switch action {
        case .open:
            if info.isDirectory { vm.navigate(to: info.url) }
            else { sheet = .preview(info) }
        case .rename:
            sheet = .rename(info)
        case .delete:
            deleteTarget = info
        case .copyPath:
            vm.copyPath(info)
        case .properties:
            sheet = .properties(info)
        }
    }

    // MARK: 空状态
    private func emptyState(theme: AppTheme) -> some View {
        VStack(spacing: 16) {
            ZStack {
                Image(systemName: vm.searchQuery.isEmpty ? "folder.open" : "magnifyingglass")
                    .font(.system(size: 36))
                    .foregroundStyle(theme.textTertiary)
            }
            .frame(width: 80, height: 80)
            .glassSurface(cornerRadius: 24, glassAlpha: 0.10, theme: theme)
            Text(vm.searchQuery.isEmpty ? "此目录为空" : "未找到匹配的文件")
                .font(.system(size: 15))
                .foregroundStyle(theme.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 40)
    }

    // MARK: 存储信息卡
    private func storageInfoCard(theme: AppTheme) -> some View {
        let info = vm.storageInfo
        let usedPercent: CGFloat = info.total > 0
            ? CGFloat(info.used) / CGFloat(info.total)
            : 0
        return VStack(spacing: 6) {
            HStack {
                Text("存储空间")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(theme.textSecondary)
                Spacer()
                Text("\(Int(usedPercent * 100))% 已使用")
                    .font(.system(size: 11))
                    .foregroundStyle(theme.textTertiary)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 2)
                        .fill(theme.glassLight)
                        .frame(height: 4)
                    RoundedRectangle(cornerRadius: 2)
                        .fill(
                            LinearGradient(colors: [theme.fluidCyan, theme.accentPrimary],
                                           startPoint: .leading, endPoint: .trailing)
                        )
                        .frame(width: geo.size.width * usedPercent, height: 4)
                }
            }
            .frame(height: 4)

            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("可用").font(.system(size: 10)).foregroundStyle(theme.textTertiary)
                    Text(formatFileSize(info.available))
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(AccentSuccess)
                }
                Spacer()
                VStack(alignment: .center, spacing: 2) {
                    Text("已用").font(.system(size: 10)).foregroundStyle(theme.textTertiary)
                    Text(formatFileSize(info.used))
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(theme.textSecondary)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text("总计").font(.system(size: 10)).foregroundStyle(theme.textTertiary)
                    Text(formatFileSize(info.total))
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(theme.textSecondary)
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .glassSurface(cornerRadius: 16, glassAlpha: 0.16, theme: theme)
    }
}

// MARK: - Sheet 类型
private enum FileManagerSheet: Identifiable {
    case createFolder
    case rename(FileInfo)
    case properties(FileInfo)
    case preview(FileInfo)
    var id: String {
        switch self {
        case .createFolder: return "createFolder"
        case .rename(let f): return "rename_\(f.id)"
        case .properties(let f): return "properties_\(f.id)"
        case .preview(let f): return "preview_\(f.id)"
        }
    }
}

private enum FileMenuAction { case open, rename, delete, copyPath, properties }

// MARK: - 文件项
private struct FileItemView: View {
    let info: FileInfo
    let isSelected: Bool
    let multiSelectMode: Bool
    let theme: AppTheme
    let onTap: () -> Void
    let onLongPress: () -> Void
    let onMenu: (FileMenuAction) -> Void

    var body: some View {
        let accent = fileTypeColor(info.fileType, theme: theme)
        HStack(spacing: 10) {
            if multiSelectMode {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 20))
                    .foregroundStyle(isSelected ? theme.accentPrimary : theme.textTertiary)
            }
            iconBox(accent: accent)
            VStack(alignment: .leading, spacing: 2) {
                Text(info.name)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(theme.textPrimary)
                    .lineLimit(1)
                HStack(spacing: 4) {
                    if !info.isDirectory {
                        Text(formatFileSize(info.size))
                        Text("·")
                    }
                    Text(formatDate(info.lastModified))
                }
                .font(.system(size: 11))
                .foregroundStyle(theme.textTertiary)
            }
            Spacer(minLength: 4)
            if !multiSelectMode {
                Menu {
                    Button { onMenu(.open) } label: {
                        Label(info.isDirectory ? "打开" : "预览", systemImage: "arrow.up.right.square")
                    }
                    Button { onMenu(.rename) } label: {
                        Label("重命名", systemImage: "pencil")
                    }
                    Button(role: .destructive) { onMenu(.delete) } label: {
                        Label("删除", systemImage: "trash")
                    }
                    Button { onMenu(.copyPath) } label: {
                        Label("复制路径", systemImage: "doc.on.doc")
                    }
                    Button { onMenu(.properties) } label: {
                        Label("属性", systemImage: "info.circle")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 15))
                        .foregroundStyle(theme.textTertiary)
                        .padding(8)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .glassSurface(cornerRadius: 14,
                      glassAlpha: isSelected ? 0.20 : 0.08,
                      theme: theme)
        .contentShape(Rectangle())
        .onTapGesture { onTap() }
        .onLongPressGesture { onLongPress() }
    }

    private func iconBox(accent: Color) -> some View {
        ZStack {
            Image(systemName: fileTypeIcon(info.fileType))
                .font(.system(size: 20))
                .foregroundStyle(accent.opacity(0.85))
        }
        .frame(width: 38, height: 38)
        .background(accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
    }
}

// MARK: - 文本输入 Sheet（新建文件夹 / 重命名）
private struct TextInputSheet: View {
    let title: String
    let placeholder: String
    let initialText: String
    let theme: AppTheme
    let onConfirm: (String?) -> Void

    @State private var text: String = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            FluidBackground(animTime: 0, theme: theme)
            VStack(spacing: 16) {
                HStack {
                    Button { onConfirm(nil); dismiss() } label: {
                        Image(systemName: "xmark")
                            .font(.headline)
                            .foregroundStyle(theme.textSecondary)
                    }
                    Spacer()
                    Text(title)
                        .font(.headline)
                        .foregroundStyle(theme.textPrimary)
                    Spacer()
                    Button {
                        onConfirm(text)
                        dismiss()
                    } label: {
                        Text("确定")
                            .font(.headline)
                            .foregroundStyle(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                              ? theme.textTertiary : theme.fluidCyan)
                    }
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                .padding(.horizontal, 20)

                TextField(placeholder, text: $text)
                    .font(.system(size: 15))
                    .foregroundStyle(theme.textPrimary)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .padding(12)
                    .glassSurface(cornerRadius: 14, glassAlpha: 0.10, theme: theme)
                    .padding(.horizontal, 20)
                Spacer()
            }
            .padding(.top, 40)
        }
        .onAppear { text = initialText }
    }
}

// MARK: - 属性 Sheet（对应 Android PropertiesDialog）
private struct PropertiesSheet: View {
    let info: FileInfo
    let theme: AppTheme
    let onClose: () -> Void

    var body: some View {
        ZStack {
            FluidBackground(animTime: 0, theme: theme)
            VStack(spacing: 16) {
                HStack {
                    Button { onClose() } label: {
                        Image(systemName: "xmark")
                            .font(.headline)
                            .foregroundStyle(theme.textSecondary)
                    }
                    Spacer()
                    Text("属性")
                        .font(.headline)
                        .foregroundStyle(theme.textPrimary)
                    Spacer()
                    Button { onClose() } label: {
                        Text("关闭")
                            .font(.headline)
                            .foregroundStyle(theme.fluidCyan)
                    }
                }
                .padding(.horizontal, 20)

                VStack(spacing: 14) {
                    Image(systemName: fileTypeIcon(info.fileType))
                        .font(.system(size: 36))
                        .foregroundStyle(fileTypeColor(info.fileType, theme: theme))
                    Text(info.name)
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(theme.textPrimary)
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                }
                .padding(.vertical, 8)

                VStack(spacing: 8) {
                    propertyRow("路径", info.url.deletingLastPathComponent().path)
                    propertyRow("类型", info.isDirectory ? "文件夹" : info.fileTypeTitle)
                    if !info.isDirectory {
                        propertyRow("大小", formatFileSize(info.size))
                    }
                    propertyRow("修改时间", formatDate(info.lastModified))
                    if !info.isDirectory {
                        propertyRow("扩展名", info.url.pathExtension.isEmpty ? "无" : info.url.pathExtension)
                    }
                    propertyRow("可读", info.isReadable ? "是" : "否")
                    propertyRow("可写", info.isWritable ? "是" : "否")
                }
                .padding(.horizontal, 20)
                Spacer()
            }
            .padding(.top, 40)
        }
    }

    private func propertyRow(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text("\(label)：")
                .font(.system(size: 13))
                .foregroundStyle(theme.textTertiary)
                .frame(width: 70, alignment: .leading)
            Text(value)
                .font(.system(size: 13))
                .foregroundStyle(theme.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private extension FileInfo {
    var fileTypeTitle: String {
        switch fileType {
        case .folder: return "文件夹"
        case .image: return "图片"
        case .video: return "视频"
        case .audio: return "音频"
        case .document: return "文档"
        case .archive: return "压缩包"
        case .code: return "代码"
        case .apk: return "APK"
        case .unknown: return "文件"
        }
    }
    var isReadable: Bool { FileManager.default.isReadableFile(atPath: url.path) }
    var isWritable: Bool { FileManager.default.isWritableFile(atPath: url.path) }
}

// MARK: - 预览 Sheet（文本 / 图片）
private struct PreviewSheet: View {
    let info: FileInfo
    let theme: AppTheme
    let onClose: () -> Void

    @State private var textContent: String? = nil
    @State private var image: UIImage? = nil
    @State private var loadFailed: Bool = false

    var body: some View {
        ZStack {
            FluidBackground(animTime: 0, theme: theme)
            VStack(spacing: 0) {
                HStack {
                    Button { onClose() } label: {
                        Image(systemName: "xmark")
                            .font(.headline)
                            .foregroundStyle(theme.textSecondary)
                    }
                    Spacer()
                    Text(info.name)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(theme.textPrimary)
                        .lineLimit(1)
                    Spacer()
                    // 占位，保持标题居中
                    Image(systemName: "xmark").opacity(0)
                }
                .padding(.horizontal, 20)
                .padding(.top, 40)
                .padding(.bottom, 12)

                content
                    .padding(.horizontal, 20)
                    .padding(.bottom, 24)
            }
        }
        .onAppear { loadContent() }
    }

    @ViewBuilder
    private var content: some View {
        if info.fileType == .image {
            if let image {
                ScrollView {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .cornerRadius(12)
                }
            } else if loadFailed {
                previewUnavailable
            } else {
                ProgressView().tint(theme.fluidCyan).frame(maxWidth: .infinity, minHeight: 200)
            }
        } else if isTextLike {
            if let textContent {
                ScrollView {
                    Text(textContent)
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(theme.textPrimary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                }
                .padding(12)
                .glassSurface(cornerRadius: 14, glassAlpha: 0.10, theme: theme)
            } else if loadFailed {
                previewUnavailable
            } else {
                ProgressView().tint(theme.fluidCyan).frame(maxWidth: .infinity, minHeight: 200)
            }
        } else {
            previewUnavailable
        }
    }

    private var previewUnavailable: some View {
        VStack(spacing: 12) {
            Image(systemName: fileTypeIcon(info.fileType))
                .font(.system(size: 44))
                .foregroundStyle(fileTypeColor(info.fileType, theme: theme))
            Text("暂不支持预览此文件")
                .font(.subheadline)
                .foregroundStyle(theme.textSecondary)
            Text("\(formatFileSize(info.size)) · \(formatDate(info.lastModified))")
                .font(.caption)
                .foregroundStyle(theme.textTertiary)
        }
        .frame(maxWidth: .infinity, minHeight: 240)
        .glassSurface(cornerRadius: 16, glassAlpha: 0.10, theme: theme)
    }

    private var isTextLike: Bool {
        info.fileType == .code
            || info.fileType == .document
            || ["txt", "md", "log", "csv", "json", "xml", "yaml", "yml", "html", "css", "js", "ts"]
                .contains(info.url.pathExtension.lowercased())
    }

    private func loadContent() {
        if info.fileType == .image {
            DispatchQueue.global(qos: .userInitiated).async {
                let img = UIImage(contentsOfFile: info.url.path)
                DispatchQueue.main.async {
                    if let img { image = img }
                    else { loadFailed = true }
                }
            }
        } else if isTextLike {
            DispatchQueue.global(qos: .userInitiated).async {
                if let data = try? Data(contentsOf: info.url),
                   let text = String(data: data, encoding: .utf8) {
                    DispatchQueue.main.async { textContent = text }
                } else if let data = try? Data(contentsOf: info.url),
                          let text = String(data: data, encoding: .isoLatin1) {
                    DispatchQueue.main.async { textContent = text }
                } else {
                    DispatchQueue.main.async { loadFailed = true }
                }
            }
        }
    }
}
