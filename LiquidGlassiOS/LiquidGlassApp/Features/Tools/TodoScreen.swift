import SwiftUI

// ─────────────────────────────────────────────────────────────────
// 待办清单 —— 对应 Android 端 ui/TodoScreen.kt + ui/TodoStore.kt
//
// 关键实现：
//   1. 增删改查：输入框添加、点击行编辑（sheet）、滑动删除、点击圆圈切换完成
//   2. 过滤：全部 / 进行中 / 已完成 三段式筛选（Android 仅展示全部，这里按
//      iOS 端需求补充过滤能力）
//   3. 持久化：完全复用 Persistence.shared（UserDefaults + JSON 编解码），
//      与 NoteScreen 的方案一致；Android 端用 SharedPreferences + JSON
//   4. 视觉：FluidBackground + glassSurface 卡片，深色主题统一
//   5. 不再单独建 TodoStore 类——直接走 Persistence，与 NoteScreen 保持一致
// ─────────────────────────────────────────────────────────────────

/// 持久化键：待办列表 JSON Data（对应 Android TodoStore 的 KEY_LIST）。
private let kTodosKey = "liquid_glass_todos"

// MARK: - 数据模型
/// 单条待办（对应 Android TodoItem）。
/// 用 UUID 作主键（与 NoteScreen 一致），便于 Codable 与稳定 diff。
struct TodoItem: Identifiable, Codable {
    let id: UUID
    var text: String
    var isCompleted: Bool
}

// MARK: - 过滤器
enum TodoFilter: String, CaseIterable, Identifiable {
    case all, active, completed
    var id: String { rawValue }
    var title: String {
        switch self {
        case .all:       return "全部"
        case .active:    return "进行中"
        case .completed: return "已完成"
        }
    }
}

// MARK: - 编辑模式
private enum TodoEditorMode: Identifiable {
    case create
    case edit(TodoItem)
    var id: String {
        switch self {
        case .create:          return "create"
        case .edit(let t):     return "edit_\(t.id.uuidString)"
        }
    }
}

// MARK: - 主视图
struct TodoScreen: View {
    var onBack: () -> Void

    @State private var todos: [TodoItem] = []
    @State private var newText: String = ""
    @State private var currentFilter: TodoFilter = .all
    @State private var editorMode: TodoEditorMode? = nil

    private var completedCount: Int { todos.filter { $0.isCompleted }.count }

    private var filteredTodos: [TodoItem] {
        switch currentFilter {
        case .all:       return todos
        case .active:    return todos.filter { !$0.isCompleted }
        case .completed: return todos.filter { $0.isCompleted }
        }
    }

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: 0, theme: theme)

            VStack(spacing: 14) {
                topBar(theme: theme)
                inputBar(theme: theme)
                filterBar(theme: theme)

                if filteredTodos.isEmpty {
                    Spacer()
                    emptyState(theme: theme)
                    Spacer()
                } else {
                    List {
                        ForEach(filteredTodos) { todo in
                            TodoRow(
                                todo: todo,
                                theme: theme,
                                onToggle: { toggle(todo.id) },
                                onTap:    { editorMode = .edit(todo) }
                            )
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                            .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                Button(role: .destructive) {
                                    delete(todo.id)
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                            }
                        }
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                }
            }
            .padding(.top, 50)
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
        .sheet(item: $editorMode) { mode in
            TodoEditorView(mode: mode) { result in
                if let result {
                    if case .edit(let existing) = mode, existing.id == result.id {
                        update(result)
                    } else {
                        todos.insert(result, at: 0)
                        persist()
                    }
                }
                editorMode = nil
            }
        }
        .onAppear { load() }
    }

    // MARK: - 顶部栏（返回 + 居中标题 + 完成数）
    private func topBar(theme: AppTheme) -> some View {
        ZStack {
            HStack {
                Button { onBack() } label: {
                    Image(systemName: "chevron.left")
                        .foregroundStyle(theme.textSecondary)
                        .frame(width: 30, height: 30)
                }
                Spacer()
                Text("\(completedCount)/\(todos.count)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(theme.textTertiary)
            }
            Text("待办清单")
                .font(.headline)
                .foregroundStyle(theme.textPrimary)
        }
    }

    // MARK: - 输入栏
    private func inputBar(theme: AppTheme) -> some View {
        HStack(spacing: 10) {
            TextField("添加新待办...", text: $newText)
                .foregroundStyle(theme.textPrimary)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .submitLabel(.done)
                .onSubmit { add() }
            Button {
                add()
            } label: {
                Image(systemName: "plus.circle.fill")
                    .font(.title2)
                    .foregroundStyle(theme.accentPrimary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .glassSurface(cornerRadius: 18, glassAlpha: 0.15, theme: theme)
    }

    // MARK: - 过滤栏
    private func filterBar(theme: AppTheme) -> some View {
        HStack(spacing: 0) {
            ForEach(TodoFilter.allCases) { filter in
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) { currentFilter = filter }
                } label: {
                    Text(filter.title)
                        .font(.subheadline.weight(currentFilter == filter ? .semibold : .regular))
                        .foregroundStyle(currentFilter == filter ? theme.textPrimary : theme.textTertiary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                }
                .buttonStyle(.plain)
            }
        }
        .glassSurface(cornerRadius: 16, glassAlpha: 0.12, theme: theme)
    }

    // MARK: - 空状态
    private func emptyState(theme: AppTheme) -> some View {
        VStack(spacing: 8) {
            Image(systemName: "checkmark.circle")
                .font(.system(size: 48))
                .foregroundStyle(theme.textTertiary)
            Text("暂无待办事项")
                .font(.subheadline)
                .foregroundStyle(theme.textSecondary)
            Text("上方输入框添加新任务")
                .font(.caption)
                .foregroundStyle(theme.textTertiary)
        }
    }

    // MARK: - 操作
    private func add() {
        let trimmed = newText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        todos.insert(TodoItem(id: UUID(), text: trimmed, isCompleted: false), at: 0)
        newText = ""
        persist()
    }

    private func toggle(_ id: UUID) {
        guard let idx = todos.firstIndex(where: { $0.id == id }) else { return }
        todos[idx].isCompleted.toggle()
        persist()
    }

    private func delete(_ id: UUID) {
        todos.removeAll { $0.id == id }
        persist()
    }

    private func update(_ todo: TodoItem) {
        guard let idx = todos.firstIndex(where: { $0.id == todo.id }) else { return }
        todos[idx] = todo
        persist()
    }

    // MARK: - 持久化（对应 Android TodoStore.load/save）
    private func load() {
        if let saved = Persistence.shared.object([TodoItem].self, for: kTodosKey) {
            todos = saved
        }
    }

    private func persist() {
        Persistence.shared.setObject(todos, for: kTodosKey)
    }
}

// MARK: - 待办行
private struct TodoRow: View {
    let todo: TodoItem
    let theme: AppTheme
    let onToggle: () -> Void
    let onTap: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onToggle) {
                ZStack {
                    Circle()
                        .fill(todo.isCompleted
                              ? AnyShapeStyle(LinearGradient(colors: [theme.fluidCyan, theme.fluidTeal],
                                                             startPoint: .topLeading,
                                                             endPoint: .bottomTrailing))
                              : AnyShapeStyle(theme.glassLight))
                        .frame(width: 22, height: 22)
                    if todo.isCompleted {
                        Image(systemName: "checkmark")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(.white)
                    }
                }
            }
            .buttonStyle(.plain)

            Text(todo.text)
                .font(.subheadline)
                .foregroundStyle(todo.isCompleted ? theme.textTertiary : theme.textPrimary)
                .strikethrough(todo.isCompleted, color: theme.textTertiary)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)

            Image(systemName: "chevron.right")
                .font(.caption2)
                .foregroundStyle(theme.textTertiary.opacity(0.5))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .glassSurface(cornerRadius: 16, glassAlpha: 0.12, theme: theme)
        .contentShape(Rectangle())
        .onTapGesture { onTap() }
    }
}

// MARK: - 待办编辑器（新建/编辑共用）
private struct TodoEditorView: View {
    let mode: TodoEditorMode
    let onDone: (TodoItem?) -> Void

    @State private var text: String = ""

    private var existing: TodoItem? {
        if case .edit(let t) = mode { return t }
        return nil
    }

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: 0, theme: theme)

            VStack(spacing: 16) {
                HStack {
                    Button { onDone(nil) } label: {
                        Image(systemName: "xmark")
                            .font(.headline)
                            .foregroundStyle(theme.textSecondary)
                    }
                    Spacer()
                    Text(existing == nil ? "新建待办" : "编辑待办")
                        .font(.headline)
                        .foregroundStyle(theme.textPrimary)
                    Spacer()
                    Button {
                        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !trimmed.isEmpty else { return }
                        let result = TodoItem(
                            id: existing?.id ?? UUID(),
                            text: trimmed,
                            isCompleted: existing?.isCompleted ?? false
                        )
                        onDone(result)
                    } label: {
                        Text("保存")
                            .font(.headline)
                            .foregroundStyle(theme.fluidCyan)
                    }
                }
                .padding(.horizontal, 16)

                TextField("待办内容", text: $text)
                    .font(.body)
                    .foregroundStyle(theme.textPrimary)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .submitLabel(.done)
                    .padding(12)
                    .glassSurface(cornerRadius: 16, glassAlpha: 0.15, theme: theme)
                    .padding(.horizontal, 16)

                Spacer()
            }
            .padding(.top, 30)
            .padding(.bottom, 24)
        }
        .onAppear {
            if let existing { text = existing.text }
        }
    }
}
