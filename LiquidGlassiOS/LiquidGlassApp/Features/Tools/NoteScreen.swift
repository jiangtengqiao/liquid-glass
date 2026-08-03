import SwiftUI

// ─────────────────────────────────────────────────────────────────
// 笔记 —— 对应 Android 端 ui/NoteScreen.kt
//
// 关键实现：
//   1. 笔记列表用 List + swipeActions 提供原生滑动删除体验
//   2. 每条笔记用 glassSurface 卡片渲染，保持液态玻璃视觉一致性
//   3. 持久化用 Persistence.shared.setObject([NoteItem].self, ...)，
//      内部走 JSONEncoder/Decoder，与 Android SharedPreferences JSON 方案一致
//   4. 编辑器以 sheet 弹出，新建/编辑共用 NoteEditorView
// ─────────────────────────────────────────────────────────────────

/// 持久化键：笔记列表 JSON Data。
private let kNotesKey = "liquid_glass_notes"

// MARK: - 数据模型
/// 单条笔记（对应 Android Note）。
struct NoteItem: Identifiable, Codable {
    let id: UUID
    var title: String
    var content: String
    var updatedAt: Date
}

// MARK: - 主视图
struct NoteScreen: View {
    var onBack: () -> Void

    @State private var notes: [NoteItem] = []
    @State private var searchQuery: String = ""
    @State private var editorMode: EditorMode? = nil

    /// 按更新时间倒序，再按搜索词过滤标题/正文。
    private var filteredNotes: [NoteItem] {
        let filtered = searchQuery.isEmpty
            ? notes
            : notes.filter {
                $0.title.localizedCaseInsensitiveContains(searchQuery) ||
                $0.content.localizedCaseInsensitiveContains(searchQuery)
            }
        return filtered.sorted { $0.updatedAt > $1.updatedAt }
    }

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: 0, theme: theme)

            VStack(spacing: 16) {
                topBar(theme: theme)
                searchBar(theme: theme)

                List {
                    if filteredNotes.isEmpty {
                        emptyState(theme: theme)
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                            .listRowInsets(EdgeInsets())
                    } else {
                        ForEach(filteredNotes) { note in
                            NoteCard(note: note, theme: theme) {
                                editorMode = .edit(note)
                            }
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                            .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                Button(role: .destructive) {
                                    deleteNote(note)
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                            }
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
            .padding(.top, 50)
            .padding(.bottom, 24)
        }
        .sheet(item: $editorMode) { mode in
            let initial: NoteItem? = {
                if case .edit(let n) = mode { return n }
                return nil
            }()
            NoteEditorView(note: initial) { result in
                if let result {
                    if case .edit(let existing) = mode, existing.id == result.id {
                        updateNote(result)
                    } else {
                        notes.insert(result, at: 0)
                        persist()
                    }
                }
                editorMode = nil
            }
        }
        .onAppear { loadNotes() }
    }

    // MARK: - 顶部栏
    private func topBar(theme: AppTheme) -> some View {
        HStack {
            Button { onBack() } label: {
                Image(systemName: "chevron.left").foregroundStyle(theme.textSecondary)
            }
            Text("笔记").font(.headline).foregroundStyle(theme.textPrimary)
            Spacer()
            Button {
                editorMode = .create
            } label: {
                Image(systemName: "plus")
                    .font(.headline)
                    .foregroundStyle(theme.fluidCyan)
            }
        }
        .padding(.horizontal, 16)
    }

    // MARK: - 搜索栏
    private func searchBar(theme: AppTheme) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(theme.textTertiary)
            TextField("搜索笔记", text: $searchQuery)
                .foregroundStyle(theme.textPrimary)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            if !searchQuery.isEmpty {
                Button {
                    searchQuery = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(theme.textTertiary)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .glassSurface(cornerRadius: 16, glassAlpha: 0.15, theme: theme)
        .padding(.horizontal, 16)
    }

    // MARK: - 空状态
    private func emptyState(theme: AppTheme) -> some View {
        VStack(spacing: 8) {
            Image(systemName: "note.text")
                .font(.system(size: 40))
                .foregroundStyle(theme.textTertiary)
            Text("还没有笔记")
                .font(.subheadline)
                .foregroundStyle(theme.textSecondary)
            Text("点击右上角 + 创建第一条笔记")
                .font(.caption)
                .foregroundStyle(theme.textTertiary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 60)
    }

    // MARK: - 持久化
    private func loadNotes() {
        if let saved = Persistence.shared.object([NoteItem].self, for: kNotesKey) {
            notes = saved
        }
    }

    private func persist() {
        Persistence.shared.setObject(notes, for: kNotesKey)
    }

    private func updateNote(_ note: NoteItem) {
        guard let idx = notes.firstIndex(where: { $0.id == note.id }) else { return }
        notes[idx] = note
        persist()
    }

    private func deleteNote(_ note: NoteItem) {
        notes.removeAll { $0.id == note.id }
        persist()
    }
}

// MARK: - 编辑模式
private enum EditorMode: Identifiable {
    case create
    case edit(NoteItem)
    var id: String {
        switch self {
        case .create: return "create"
        case .edit(let n): return "edit_\(n.id.uuidString)"
        }
    }
}

// MARK: - 笔记卡片
private struct NoteCard: View {
    let note: NoteItem
    let theme: AppTheme
    let onTap: () -> Void

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MM/dd HH:mm"
        return f
    }()

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 6) {
                Text(note.title.isEmpty ? "(无标题)" : note.title)
                    .font(.headline)
                    .foregroundStyle(theme.textPrimary)
                    .lineLimit(1)
                    .multilineTextAlignment(.leading)

                Text(note.content.isEmpty ? "(无内容)" : note.content)
                    .font(.subheadline)
                    .foregroundStyle(theme.textSecondary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)

                HStack(spacing: 6) {
                    Image(systemName: "clock")
                        .font(.caption2)
                    Text(Self.dateFormatter.string(from: note.updatedAt))
                        .font(.caption2)
                    Spacer()
                }
                .foregroundStyle(theme.textTertiary)
                .padding(.top, 2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .glassSurface(cornerRadius: 24, glassAlpha: 0.15, theme: theme)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 笔记编辑器
private struct NoteEditorView: View {
    let note: NoteItem?
    let onDone: (NoteItem?) -> Void

    @State private var title: String = ""
    @State private var content: String = ""

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: 0, theme: theme)

            VStack(spacing: 16) {
                HStack {
                    Button {
                        onDone(nil)
                    } label: {
                        Image(systemName: "xmark")
                            .font(.headline)
                            .foregroundStyle(theme.textSecondary)
                    }
                    Spacer()
                    Text(note == nil ? "新建笔记" : "编辑笔记")
                        .font(.headline)
                        .foregroundStyle(theme.textPrimary)
                    Spacer()
                    Button {
                        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !trimmed.isEmpty || !content.isEmpty else { return }
                        let result = NoteItem(
                            id: note?.id ?? UUID(),
                            title: trimmed,
                            content: content,
                            updatedAt: Date()
                        )
                        onDone(result)
                    } label: {
                        Text("保存")
                            .font(.headline)
                            .foregroundStyle(theme.fluidCyan)
                    }
                }
                .padding(.horizontal, 16)

                TextField("标题", text: $title)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(theme.textPrimary)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .padding(12)
                    .glassSurface(cornerRadius: 16, glassAlpha: 0.15, theme: theme)
                    .padding(.horizontal, 16)

                TextEditor(text: $content)
                    .font(.body)
                    .foregroundStyle(theme.textPrimary)
                    .scrollContentBackground(.hidden)
                    .padding(8)
                    .glassSurface(cornerRadius: 24, glassAlpha: 0.15, theme: theme)
                    .padding(.horizontal, 16)
            }
            .padding(.top, 30)
            .padding(.bottom, 24)
        }
        .onAppear {
            if let note {
                title = note.title
                content = note.content
            }
        }
    }
}
