import SwiftUI
import UserNotifications

// ─────────────────────────────────────────────────────────────────
// 日历日程 —— 对应 Android 端 ui/CalendarScreen.kt
//
// 关键实现：
//   1. 月视图网格：周一起始，含上/下月切换、年份快选、选中日期、今日高亮、事件小圆点
//   2. 日程 / 概览 双视图：
//      - 日程 = 选中日期的事件列表
//      - 概览 = 最近提醒 + 今日 + 本周 + 即将到来
//   3. 事件增删改：JSON 持久化到 UserDefaults（Persistence.shared），
//      与 Android SharedPreferences JSON 方案一致
//   4. 提醒：iOS 用 UNUserNotificationCenter 调度本地通知（对应 Android
//      AlarmManager + ReminderReceiver）。事件 reminderTime 为触发绝对时间；
//      添加 / 编辑带提醒的事件时请求通知授权并调度；删除 / 编辑先取消再重排。
//      进入页面时 rescheduleAll 重建通知（对应 Android LaunchedEffect）
//   5. 提醒预设：不提醒 / 事件时间 / 5·15·30 分钟前 / 1 小时前 / 1 天前 / 自定义
// ─────────────────────────────────────────────────────────────────

/// 持久化键：日历事件列表 JSON Data。
private let kCalendarEventsKey = "liquid_glass_calendar_events"

// MARK: - 数据模型
/// 单条日历事件（对应 Android CalendarEvent）。
/// reminderTime 为提醒触发绝对时间；nil 表示不提醒。
struct CalendarEvent: Identifiable, Codable {
    let id: UUID
    var title: String
    var time: String = ""        // "HH:mm"
    var description: String = ""
    var colorTag: Int = 0
    var isAllDay: Bool = false
    var date: String = ""        // "yyyy-MM-dd"
    var reminderTime: Date? = nil
}

// MARK: - 提醒预设（对应 Android ReminderPresets）
// offsetMin: nil = 不提醒，-1 = 自定义，其它 = 提前分钟数
private struct ReminderPreset: Identifiable {
    let id: Int
    let label: String
    let offsetMin: Int?
}

private func reminderPresets() -> [ReminderPreset] {
    [
        .init(id: 0, label: "不提醒", offsetMin: nil),
        .init(id: 1, label: "事件时间", offsetMin: 0),
        .init(id: 2, label: "5 分钟前", offsetMin: 5),
        .init(id: 3, label: "15 分钟前", offsetMin: 15),
        .init(id: 4, label: "30 分钟前", offsetMin: 30),
        .init(id: 5, label: "1 小时前", offsetMin: 60),
        .init(id: 6, label: "1 天前", offsetMin: 1440),
        .init(id: 7, label: "自定义…", offsetMin: -1)
    ]
}

// MARK: - 事件颜色组（对应 Android EventColors）
private func eventColors(_ theme: AppTheme) -> [Color] {
    [theme.fluidCyan, theme.fluidPurple, theme.fluidPink,
     theme.fluidBlue, theme.fluidTeal, theme.fluidOrange]
}

// MARK: - 日期 / 提醒辅助
private let isoDateFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "yyyy-MM-dd"
    f.locale = Locale(identifier: "en_US_POSIX")
    return f
}()

private let reminderDisplayFormatter: DateFormatter = {
    let f = DateFormatter()
    f.locale = Locale(identifier: "zh_CN")
    return f
}()

private let fullDayFormatter: DateFormatter = {
    let f = DateFormatter()
    f.locale = Locale(identifier: "zh_CN")
    f.dateFormat = "M月d日 EEEE"
    return f
}()

private let eventDateLabelFormatter: DateFormatter = {
    let f = DateFormatter()
    f.locale = Locale(identifier: "zh_CN")
    f.dateFormat = "M月d日 EEE"
    return f
}()

private func isoString(_ date: Date) -> String {
    isoDateFormatter.string(from: date)
}

/// 格式化提醒时间用于 UI 展示（同年省略年份）。
private func formatReminderTime(_ date: Date) -> String {
    let cal = Calendar.current
    let sameYear = cal.component(.year, from: date) == cal.component(.year, from: Date())
    reminderDisplayFormatter.dateFormat = sameYear ? "M月d日 HH:mm" : "yyyy年M月d日 HH:mm"
    return reminderDisplayFormatter.string(from: date)
}

/// ISO 日期 → "M月d日"。
private func formatDateShort(_ isoDate: String) -> String {
    guard let d = isoDateFormatter.date(from: isoDate) else { return isoDate }
    let cal = Calendar.current
    return "\(cal.component(.month, from: d))月\(cal.component(.day, from: d))日"
}

/// 计算事件基准时间。全天事件或无时间取当天 09:00。
private func computeEventBaseDate(dateStr: String, timeStr: String, isAllDay: Bool) -> Date? {
    guard let day = isoDateFormatter.date(from: dateStr) else { return nil }
    let cal = Calendar.current
    let h: Int, m: Int
    if isAllDay || timeStr.isEmpty {
        h = 9; m = 0
    } else {
        let parts = timeStr.split(separator: ":")
        if parts.count == 2, let hh = Int(parts[0]), let mm = Int(parts[1]) {
            h = hh; m = mm
        } else {
            h = 9; m = 0
        }
    }
    var comps = cal.dateComponents([.year, .month, .day], from: day)
    comps.hour = h
    comps.minute = m
    return cal.date(from: comps)
}

/// 由预设推导提醒时间；offsetMin = -1 交由调用方自定义处理。
private func reminderFromPreset(_ preset: ReminderPreset, base: Date) -> Date? {
    guard let off = preset.offsetMin else { return nil } // 不提醒
    return base.addingTimeInterval(TimeInterval(-off) * 60)
}

/// 根据已有事件的 reminderTime 反推预设 id；匹配不上返回末位（自定义）。
private func matchPresetId(event: CalendarEvent?, defaultDate: String) -> Int {
    let presets = reminderPresets()
    guard let event = event, event.reminderTime != nil else { return 0 }
    guard let base = computeEventBaseDate(
        dateStr: event.date.isEmpty ? defaultDate : event.date,
        timeStr: event.time,
        isAllDay: event.isAllDay) else {
        return presets.last!.id
    }
    for preset in presets.dropLast() { // 跳过末位"自定义"
        if let expected = reminderFromPreset(preset, base: base),
           expected == event.reminderTime {
            return preset.id
        }
    }
    return presets.last!.id
}

/// 月视图网格：周一起始，返回 [Date?]，nil 为空格。
private func daysInMonthGrid(monthStart: Date) -> [Date?] {
    var cal = Calendar.current
    cal.firstWeekday = 2 // 周一
    let range = cal.range(of: .day, in: .month, for: monthStart) ?? 1..<32
    let weekday = cal.component(.weekday, from: monthStart) // 1=周一 ... 7=周日
    var days: [Date?] = []
    for _ in 0..<(weekday - 1) { days.append(nil) }
    for day in range {
        if let d = cal.date(byAdding: .day, value: day - 1, to: monthStart) {
            days.append(d)
        }
    }
    while days.count % 7 != 0 { days.append(nil) }
    return days
}

// MARK: - 通知调度（对应 Android ReminderScheduler + ReminderReceiver）
private enum NotificationScheduler {
    static func requestAuthorization(completion: ((Bool) -> Void)? = nil) {
        UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
                DispatchQueue.main.async { completion?(granted) }
            }
    }

    /// 调度单个事件提醒；触发时间已过去则跳过。
    static func schedule(event: CalendarEvent) {
        guard let trigger = event.reminderTime, trigger > Date() else { return }
        let content = UNMutableNotificationContent()
        content.title = event.title.isEmpty ? "日程提醒" : event.title
        var parts: [String] = []
        if !event.date.isEmpty { parts.append(formatDateShort(event.date)) }
        if !event.isAllDay && !event.time.isEmpty { parts.append(event.time) }
        content.body = parts.isEmpty ? "到点了，别错过" : "时间：\(parts.joined(separator: " "))"
        content.sound = .default
        let interval = max(1.0, trigger.timeIntervalSinceNow)
        let req = UNNotificationRequest(
            identifier: event.id.uuidString,
            content: content,
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false)
        )
        UNUserNotificationCenter.current().add(req)
    }

    static func cancel(eventId: String) {
        UNUserNotificationCenter.current()
            .removePendingNotificationRequests(withIdentifiers: [eventId])
    }

    /// 重建所有事件提醒（App 重启后调用以恢复）。
    static func rescheduleAll(events: [CalendarEvent]) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: events.map { $0.id.uuidString })
        for e in events { schedule(event: e) } // schedule 内部过滤过期
    }
}

// MARK: - 视图模型
final class CalendarViewModel: ObservableObject {
    @Published var events: [CalendarEvent] = []
    @Published var monthStart: Date          // 当月第一天
    @Published var selectedDate: Date        // 选中日期（已归一到当天 0 点）
    @Published var viewMode: Int = 0         // 0=日程，1=概览

    /// 周一起始的日历，用于月份导航与周区间计算。
    private let cal: Calendar = {
        var c = Calendar.current
        c.firstWeekday = 2
        return c
    }()

    var today: Date { cal.startOfDay(for: Date()) }

    init() {
        let now = Date()
        let startOfMonth = Calendar.current.date(
            from: Calendar.current.dateComponents([.year, .month], from: now)) ?? now
        self.monthStart = startOfMonth
        self.selectedDate = cal.startOfDay(for: now)
        load()
    }

    // MARK: 持久化
    func load() {
        if let saved = Persistence.shared.object([CalendarEvent].self, for: kCalendarEventsKey) {
            events = saved
        }
    }

    func persist() {
        Persistence.shared.setObject(events, for: kCalendarEventsKey)
    }

    // MARK: 月份导航
    func goPrevMonth() {
        monthStart = cal.date(byAdding: .month, value: -1, to: monthStart) ?? monthStart
    }
    func goNextMonth() {
        monthStart = cal.date(byAdding: .month, value: 1, to: monthStart) ?? monthStart
    }
    func jumpToYear(_ year: Int) {
        var c = cal.dateComponents([.year, .month], from: monthStart)
        c.year = year
        if let d = cal.date(from: c) { monthStart = d }
    }

    func selectDate(_ date: Date) {
        selectedDate = cal.startOfDay(for: date)
    }

    var daysInGrid: [Date?] { daysInMonthGrid(monthStart: monthStart) }

    var monthYearTitle: String {
        let y = cal.component(.year, from: monthStart)
        let m = cal.component(.month, from: monthStart)
        return "\(y)年\(m)月"
    }

    var currentYear: Int { cal.component(.year, from: monthStart) }

    var eventsByDate: [String: [CalendarEvent]] {
        Dictionary(grouping: events, by: { $0.date })
    }

    var selectedDateStr: String { isoString(selectedDate) }
    private var todayStr: String { isoString(today) }

    /// 按全天优先、再按时间升序。
    private func sortDayEvents(_ list: [CalendarEvent]) -> [CalendarEvent] {
        list.sorted { a, b in
            if a.isAllDay != b.isAllDay { return a.isAllDay && !b.isAllDay }
            return a.time < b.time
        }
    }

    var selectedDateEvents: [CalendarEvent] {
        sortDayEvents(events.filter { $0.date == selectedDateStr })
    }

    var todayEvents: [CalendarEvent] {
        sortDayEvents(events.filter { $0.date == todayStr })
    }

    var weekEvents: [CalendarEvent] {
        guard let weekInterval = cal.dateInterval(of: .weekOfYear, for: today) else { return [] }
        let startStr = isoString(weekInterval.start)
        let endStr = isoString(weekInterval.end.addingTimeInterval(-1))
        return events.filter { !$0.date.isEmpty && $0.date >= startStr && $0.date <= endStr }
            .sorted { ($0.date + $0.time) < ($1.date + $1.time) }
    }

    var upcomingEvents: [CalendarEvent] {
        events.filter { !$0.date.isEmpty && $0.date > todayStr }
            .sorted { ($0.date + $0.time) < ($1.date + $1.time) }
    }

    /// 未来 7 天内且已设提醒的事件。
    var reminderEvents: [CalendarEvent] {
        let now = Date()
        let sevenDaysLater = cal.date(byAdding: .day, value: 7, to: today) ?? today
        let endStr = isoString(sevenDaysLater)
        return events.filter { e in
            guard let rt = e.reminderTime, rt > now, !e.date.isEmpty else { return false }
            return e.date >= todayStr && e.date <= endStr
        }.sorted { ($0.reminderTime ?? .distantFuture) < ($1.reminderTime ?? .distantFuture) }
    }

    // MARK: 增删改
    func saveEvent(_ event: CalendarEvent, isEditing: Bool) {
        if isEditing, let idx = events.firstIndex(where: { $0.id == event.id }) {
            NotificationScheduler.cancel(eventId: events[idx].id.uuidString)
            events[idx] = event
        } else {
            events.append(event)
        }
        persist()
        if event.reminderTime != nil {
            NotificationScheduler.requestAuthorization { _ in
                NotificationScheduler.schedule(event: event)
            }
        }
    }

    func deleteEvent(_ event: CalendarEvent) {
        NotificationScheduler.cancel(eventId: event.id.uuidString)
        events.removeAll { $0.id == event.id }
        persist()
    }

    func rescheduleAll() {
        NotificationScheduler.rescheduleAll(events: events)
    }
}

// MARK: - 主视图
struct CalendarScreen: View {
    var onBack: () -> Void

    @StateObject private var vm = CalendarViewModel()
    @State private var editorMode: EditorMode? = nil
    @State private var deleteTarget: CalendarEvent? = nil

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: 0, theme: theme)

            VStack(spacing: 14) {
                topBar(theme: theme)
                monthNav(theme: theme)
                weekdayHeader(theme: theme)
                calendarGrid(theme: theme)
                viewModeTabs(theme: theme)
                contentList(theme: theme)
            }
            .padding(.top, 50)
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
        .sheet(item: $editorMode) { mode in
            let initial: CalendarEvent? = {
                if case .edit(let e) = mode { return e }
                return nil
            }()
            EventEditorView(existing: initial, defaultDate: vm.selectedDateStr) { result in
                if let result {
                    vm.saveEvent(result, isEditing: initial != nil)
                }
                editorMode = nil
            }
        }
        .alert("删除日程",
               isPresented: Binding(get: { deleteTarget != nil },
                                    set: { if !$0 { deleteTarget = nil } })) {
            Button("取消", role: .cancel) { deleteTarget = nil }
            Button("删除", role: .destructive) {
                if let e = deleteTarget { vm.deleteEvent(e) }
                deleteTarget = nil
            }
        } message: {
            if let e = deleteTarget {
                Text("确定要删除「\(e.title.isEmpty ? "无标题" : e.title)」吗？")
            }
        }
        .onAppear { vm.rescheduleAll() }
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
            Text("日历")
                .font(.headline)
                .foregroundStyle(theme.textPrimary)
            Spacer()
            Button {
                editorMode = .create
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "plus").font(.system(size: 13, weight: .semibold))
                    Text("添加").font(.system(size: 13, weight: .medium))
                }
                .foregroundStyle(theme.accentPrimary)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .glassSurface(cornerRadius: 14, glassAlpha: 0.15, theme: theme)
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: 月份导航
    private func monthNav(theme: AppTheme) -> some View {
        HStack {
            Button { vm.goPrevMonth() } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(theme.textSecondary)
                    .frame(width: 32, height: 32)
            }
            Spacer()
            Menu {
                ForEach(yearOptions(), id: \.self) { y in
                    Button {
                        vm.jumpToYear(y)
                    } label: {
                        if y == vm.currentYear {
                            Label("\(y)年", systemImage: "checkmark")
                        } else {
                            Text("\(y)年")
                        }
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    Text(vm.monthYearTitle)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(theme.textPrimary)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 10))
                        .foregroundStyle(theme.textTertiary)
                }
            }
            Spacer()
            Button { vm.goNextMonth() } label: {
                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(theme.textSecondary)
                    .frame(width: 32, height: 32)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .glassSurface(cornerRadius: 18, glassAlpha: 0.12, theme: theme)
    }

    private func yearOptions() -> [Int] {
        let cy = Calendar.current.component(.year, from: Date())
        return Array((cy - 5)...(cy + 5))
    }

    // MARK: 星期表头
    private func weekdayHeader(theme: AppTheme) -> some View {
        let days = ["一", "二", "三", "四", "五", "六", "日"]
        return HStack(spacing: 0) {
            ForEach(Array(days.enumerated()), id: \.offset) { idx, d in
                Text(d)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(idx >= 5 ? AccentWarning.opacity(0.6) : theme.textTertiary)
                    .frame(maxWidth: .infinity)
            }
        }
    }

    // MARK: 日历网格
    private func calendarGrid(theme: AppTheme) -> some View {
        let days = vm.daysInGrid
        let rows = stride(from: 0, to: days.count, by: 7).map { Array(days[$0..<$0 + 7]) }
        return VStack(spacing: 6) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack(spacing: 0) {
                    ForEach(Array(row.enumerated()), id: \.offset) { _, day in
                        dayCell(day: day, theme: theme)
                    }
                }
            }
        }
    }

    private func dayCell(day: Date?, theme: AppTheme) -> some View {
        Group {
            if let day = day {
                let isToday = Calendar.current.isDateInToday(day)
                let isSelected = Calendar.current.isDate(day, inSameDayAs: vm.selectedDate)
                let dayEvents = vm.eventsByDate[isoString(day)] ?? []
                let wd = Calendar.current.component(.weekday, from: day) // 1=周日 ... 7=周六
                let isWeekend = (wd == 1 || wd == 7)
                let colors = eventColors(theme)
                let uniqueTags = Array(Set(dayEvents.map { $0.colorTag })).sorted().prefix(3)
                Button { vm.selectDate(day) } label: {
                    VStack(spacing: 2) {
                        Text("\(Calendar.current.component(.day, from: day))")
                            .font(.system(size: 14, weight: (isToday || isSelected) ? .bold : .regular))
                            .foregroundStyle(
                                isSelected ? theme.accentPrimary :
                                isToday ? Color.white :
                                isWeekend ? AccentWarning.opacity(0.7) :
                                theme.textPrimary
                            )
                        if !uniqueTags.isEmpty {
                            HStack(spacing: 2) {
                                ForEach(Array(uniqueTags), id: \.self) { cIdx in
                                    Circle()
                                        .fill(colors[cIdx % colors.count])
                                        .frame(width: 4, height: 4)
                                }
                            }
                        } else {
                            Circle().fill(Color.clear).frame(width: 4, height: 4)
                        }
                    }
                    .frame(height: 44)
                    .frame(maxWidth: .infinity)
                    .background(
                        Group {
                            if isSelected {
                                RoundedRectangle(cornerRadius: 12)
                                    .fill(theme.accentPrimary.opacity(0.28))
                            } else if isToday {
                                RoundedRectangle(cornerRadius: 12)
                                    .fill(theme.accentPrimary.opacity(0.18))
                            } else {
                                Color.clear
                            }
                        }
                    )
                    .overlay(
                        isSelected
                            ? RoundedRectangle(cornerRadius: 12).stroke(theme.glassBorder, lineWidth: 1)
                            : nil
                    )
                }
                .buttonStyle(.plain)
            } else {
                Color.clear.frame(height: 44).frame(maxWidth: .infinity)
            }
        }
    }

    // MARK: 视图模式切换
    private func viewModeTabs(theme: AppTheme) -> some View {
        HStack(spacing: 4) {
            tabChip("日程", selected: vm.viewMode == 0, theme: theme) { vm.viewMode = 0 }
            tabChip("概览", selected: vm.viewMode == 1, theme: theme) { vm.viewMode = 1 }
        }
        .padding(3)
        .glassSurface(cornerRadius: 14, glassAlpha: 0.08, theme: theme)
    }

    private func tabChip(_ text: String, selected: Bool, theme: AppTheme,
                         action: @escaping () -> Void) -> some View {
        Button {
            withAnimation(.easeInOut(duration: 0.2)) { action() }
        } label: {
            Text(text)
                .font(.system(size: 13, weight: selected ? .semibold : .regular))
                .foregroundStyle(selected ? theme.textPrimary : theme.textTertiary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background(
                    Group {
                        if selected {
                            RoundedRectangle(cornerRadius: 11).fill(theme.glassMedium)
                        } else {
                            Color.clear
                        }
                    }
                )
        }
        .buttonStyle(.plain)
    }

    // MARK: 内容列表
    @ViewBuilder
    private func contentList(theme: AppTheme) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if vm.viewMode == 0 {
                    selectedDaySection(theme: theme)
                } else {
                    overviewSection(theme: theme)
                }
            }
            .padding(.bottom, 30)
        }
    }

    private func selectedDaySection(theme: AppTheme) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(fullDayFormatter.string(from: vm.selectedDate))
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(theme.textSecondary)
            if vm.selectedDateEvents.isEmpty {
                emptyEventsCard("暂无日程", theme: theme)
            } else {
                ForEach(vm.selectedDateEvents) { e in
                    EventCard(event: e, theme: theme) { editorMode = .edit(e) }
                        .contextMenu {
                            Button(role: .destructive) { deleteTarget = e } label: {
                                Label("删除", systemImage: "trash")
                            }
                        }
                }
            }
        }
    }

    @ViewBuilder
    private func overviewSection(theme: AppTheme) -> some View {
        // 最近提醒
        if !vm.reminderEvents.isEmpty {
            sectionHeader("最近提醒", color: AccentWarning, icon: "bell.badge.fill", theme: theme)
            ForEach(Array(vm.reminderEvents.prefix(3))) { e in
                EventCard(event: e, theme: theme) { editorMode = .edit(e) }
                    .contextMenu {
                        Button(role: .destructive) { deleteTarget = e } label: {
                            Label("删除", systemImage: "trash")
                        }
                    }
            }
        }

        // 今日
        sectionHeader("今日", color: theme.accentPrimary, icon: "sun.max.fill", theme: theme)
        if vm.todayEvents.isEmpty {
            emptyHint("暂无今日日程", theme: theme)
        } else {
            ForEach(vm.todayEvents) { e in
                EventCard(event: e, theme: theme) { editorMode = .edit(e) }
                    .contextMenu {
                        Button(role: .destructive) { deleteTarget = e } label: {
                            Label("删除", systemImage: "trash")
                        }
                    }
            }
        }

        // 本周
        sectionHeader("本周", color: theme.fluidTeal, icon: "calendar", theme: theme)
        if vm.weekEvents.isEmpty {
            emptyHint("暂无本周日程", theme: theme)
        } else {
            ForEach(vm.weekEvents) { e in
                EventCard(event: e, theme: theme) { editorMode = .edit(e) }
                    .contextMenu {
                        Button(role: .destructive) { deleteTarget = e } label: {
                            Label("删除", systemImage: "trash")
                        }
                    }
            }
        }

        // 即将到来
        sectionHeader("即将到来", color: theme.fluidPurple, icon: "arrow.forward.circle.fill", theme: theme)
        if vm.upcomingEvents.isEmpty {
            emptyHint("暂无未来日程", theme: theme)
        } else {
            ForEach(Array(vm.upcomingEvents.prefix(20))) { e in
                EventCard(event: e, theme: theme) { editorMode = .edit(e) }
                    .contextMenu {
                        Button(role: .destructive) { deleteTarget = e } label: {
                            Label("删除", systemImage: "trash")
                        }
                    }
            }
        }
    }

    private func sectionHeader(_ title: String, color: Color, icon: String,
                               theme: AppTheme) -> some View {
        HStack(spacing: 6) {
            Image(systemName: icon).font(.system(size: 13))
            Text(title).font(.system(size: 13, weight: .medium))
        }
        .foregroundStyle(color)
        .padding(.top, 4)
    }

    private func emptyHint(_ text: String, theme: AppTheme) -> some View {
        Text(text)
            .font(.system(size: 12))
            .foregroundStyle(theme.textTertiary)
            .padding(.leading, 4)
    }

    private func emptyEventsCard(_ text: String, theme: AppTheme) -> some View {
        Text(text)
            .font(.system(size: 13))
            .foregroundStyle(theme.textTertiary)
            .frame(maxWidth: .infinity)
            .frame(height: 80)
            .glassSurface(cornerRadius: 16, glassAlpha: 0.08, theme: theme)
    }
}

// MARK: - 编辑模式
private enum EditorMode: Identifiable {
    case create
    case edit(CalendarEvent)
    var id: String {
        switch self {
        case .create: return "create"
        case .edit(let e): return "edit_\(e.id.uuidString)"
        }
    }
}

// MARK: - 事件卡片
private struct EventCard: View {
    let event: CalendarEvent
    let theme: AppTheme
    let onTap: () -> Void

    var body: some View {
        let accent = eventColors(theme)[event.colorTag % eventColors(theme).count]
        Button(action: onTap) {
            HStack(spacing: 10) {
                RoundedRectangle(cornerRadius: 2)
                    .fill(accent.opacity(0.8))
                    .frame(width: 3, height: 36)

                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        if event.isAllDay {
                            Text("全天")
                                .font(.system(size: 10, weight: .medium))
                                .foregroundStyle(theme.fluidTeal)
                                .padding(.horizontal, 5).padding(.vertical, 1)
                                .background(theme.fluidTeal.opacity(0.15),
                                            in: RoundedRectangle(cornerRadius: 4))
                        } else if !event.time.isEmpty {
                            Text(event.time)
                                .font(.system(size: 12))
                                .foregroundStyle(theme.textTertiary)
                        }
                        Text(event.title.isEmpty ? "无标题" : event.title)
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(theme.textPrimary)
                            .lineLimit(1)
                        if event.reminderTime != nil {
                            Image(systemName: "bell.fill")
                                .font(.system(size: 11))
                                .foregroundStyle(AccentWarning)
                        }
                        Spacer(minLength: 0)
                    }
                    if !event.description.isEmpty {
                        Text(event.description)
                            .font(.system(size: 12))
                            .foregroundStyle(theme.textSecondary)
                            .lineLimit(2)
                    }
                }

                Spacer(minLength: 4)
                Text(dateLabel)
                    .font(.system(size: 10))
                    .foregroundStyle(theme.textTertiary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .glassSurface(cornerRadius: 14, glassAlpha: 0.10, theme: theme)
        }
        .buttonStyle(.plain)
    }

    private var dateLabel: String {
        guard let d = isoDateFormatter.date(from: event.date) else { return event.date }
        return eventDateLabelFormatter.string(from: d)
    }
}

// MARK: - 事件编辑器
private struct EventEditorView: View {
    let existing: CalendarEvent?
    let defaultDate: String
    let onDone: (CalendarEvent?) -> Void

    @State private var title: String = ""
    @State private var time: String = ""
    @State private var description: String = ""
    @State private var colorTag: Int = 0
    @State private var isAllDay: Bool = false
    @State private var dateStr: String = ""
    @State private var reminderTime: Date? = nil
    @State private var reminderPresetId: Int = 0
    @State private var showDatePicker: Bool = false
    @State private var showCustomReminder: Bool = false

    private let presets = reminderPresets()

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: 0, theme: theme)

            VStack(spacing: 14) {
                editorTopBar(theme: theme)
                ScrollView {
                    VStack(spacing: 12) {
                        titleField(theme: theme)
                        dateField(theme: theme)
                        if !isAllDay { timeField(theme: theme) }
                        allDayRow(theme: theme)
                        reminderSection(theme: theme)
                        descriptionField(theme: theme)
                        colorTagRow(theme: theme)
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 40)
                }
            }
            .padding(.top, 40)
        }
        .onAppear { loadInitial() }
        .sheet(isPresented: $showDatePicker) {
            let initial = isoDateFormatter.date(from: dateStr) ?? Date()
            DatePickerSheetView(title: "选择日期",
                                initial: initial,
                                displayedComponents: [.date],
                                theme: theme) { newDate in
                dateStr = isoDateFormatter.string(from: newDate)
                showDatePicker = false
            }
        }
        .sheet(isPresented: $showCustomReminder) {
            let initial = reminderTime
                ?? computeEventBaseDate(dateStr: dateStr, timeStr: time, isAllDay: isAllDay)
                ?? Date()
            DatePickerSheetView(title: "自定义提醒时间",
                                initial: initial,
                                displayedComponents: [.date, .hourAndMinute],
                                theme: theme) { newDate in
                reminderTime = newDate
                reminderPresetId = presets.last!.id // 标记为自定义
                showCustomReminder = false
            }
        }
    }

    // MARK: 顶栏
    private func editorTopBar(theme: AppTheme) -> some View {
        HStack {
            Button { onDone(nil) } label: {
                Image(systemName: "xmark")
                    .font(.headline)
                    .foregroundStyle(theme.textSecondary)
            }
            Spacer()
            Text(existing == nil ? "新建日程" : "编辑日程")
                .font(.headline)
                .foregroundStyle(theme.textPrimary)
            Spacer()
            Button { save() } label: {
                Text("保存")
                    .font(.headline)
                    .foregroundStyle(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                      ? theme.textTertiary : theme.fluidCyan)
            }
            .disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(.horizontal, 20)
    }

    // MARK: 字段
    private func titleField(theme: AppTheme) -> some View {
        TextField("标题", text: $title)
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(theme.textPrimary)
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            .padding(12)
            .glassSurface(cornerRadius: 14, glassAlpha: 0.10, theme: theme)
    }

    private func dateField(theme: AppTheme) -> some View {
        Button { showDatePicker = true } label: {
            HStack {
                Text(dateStr.isEmpty ? "选择日期" : dateStr)
                    .font(.system(size: 14))
                    .foregroundStyle(dateStr.isEmpty ? theme.textTertiary : theme.textPrimary)
                Spacer()
                Image(systemName: "calendar")
                    .foregroundStyle(theme.textTertiary)
            }
            .padding(12)
            .glassSurface(cornerRadius: 14, glassAlpha: 0.10, theme: theme)
        }
        .buttonStyle(.plain)
    }

    private func timeField(theme: AppTheme) -> some View {
        TextField("时间 (如 14:30)", text: $time)
            .font(.system(size: 14))
            .foregroundStyle(theme.textPrimary)
            .keyboardType(.numbersAndPunctuation)
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            .padding(12)
            .glassSurface(cornerRadius: 14, glassAlpha: 0.10, theme: theme)
    }

    private func allDayRow(theme: AppTheme) -> some View {
        HStack {
            Text("全天事件")
                .font(.system(size: 13))
                .foregroundStyle(theme.textSecondary)
            Spacer()
            Toggle("", isOn: $isAllDay)
                .labelsHidden()
                .tint(theme.fluidTeal)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .glassSurface(cornerRadius: 14, glassAlpha: 0.10, theme: theme)
    }

    private func reminderSection(theme: AppTheme) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: "bell.fill")
                    .font(.system(size: 13))
                    .foregroundStyle(AccentWarning)
                Text("提醒")
                    .font(.system(size: 13))
                    .foregroundStyle(theme.textSecondary)
                Spacer()
                if let rt = reminderTime {
                    Text(formatReminderTime(rt))
                        .font(.system(size: 11))
                        .foregroundStyle(theme.fluidCyan)
                }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(presets) { p in
                        let selected = p.id == reminderPresetId
                        Button { handlePresetTap(p) } label: {
                            Text(p.label)
                                .font(.system(size: 11, weight: selected ? .semibold : .regular))
                                .foregroundStyle(selected ? theme.accentPrimary : theme.textSecondary)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .background(
                                    RoundedRectangle(cornerRadius: 12)
                                        .fill(selected ? theme.accentPrimary.opacity(0.18)
                                              : theme.glassBorder.opacity(0.12))
                                )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .padding(12)
        .glassSurface(cornerRadius: 14, glassAlpha: 0.10, theme: theme)
    }

    private func descriptionField(theme: AppTheme) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("描述（可选）")
                .font(.system(size: 12))
                .foregroundStyle(theme.textTertiary)
            TextEditor(text: $description)
                .font(.system(size: 13))
                .foregroundStyle(theme.textPrimary)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 70)
        }
        .padding(12)
        .glassSurface(cornerRadius: 14, glassAlpha: 0.10, theme: theme)
    }

    private func colorTagRow(theme: AppTheme) -> some View {
        let colors = eventColors(theme)
        return HStack(spacing: 8) {
            Text("颜色标签")
                .font(.system(size: 13))
                .foregroundStyle(theme.textSecondary)
            ForEach(Array(colors.enumerated()), id: \.offset) { idx, c in
                Circle()
                    .fill(c.opacity(0.5))
                    .frame(width: 22, height: 22)
                    .overlay(
                        Circle().stroke(c, lineWidth: colorTag == idx ? 2 : 0)
                    )
                    .onTapGesture { colorTag = idx }
            }
        }
        .padding(12)
        .glassSurface(cornerRadius: 14, glassAlpha: 0.10, theme: theme)
    }

    // MARK: 逻辑
    private func loadInitial() {
        if let e = existing {
            title = e.title
            time = e.time
            description = e.description
            colorTag = e.colorTag
            isAllDay = e.isAllDay
            dateStr = e.date.isEmpty ? defaultDate : e.date
            reminderTime = e.reminderTime
            reminderPresetId = matchPresetId(event: e, defaultDate: defaultDate)
        } else {
            dateStr = defaultDate
            reminderPresetId = 0
        }
    }

    private func handlePresetTap(_ preset: ReminderPreset) {
        if preset.offsetMin == -1 {
            showCustomReminder = true
            return
        }
        guard let base = computeEventBaseDate(dateStr: dateStr, timeStr: time, isAllDay: isAllDay) else {
            reminderTime = nil
            reminderPresetId = 0
            return
        }
        reminderTime = reminderFromPreset(preset, base: base)
        reminderPresetId = preset.id
    }

    private func save() {
        let trimmedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedTitle.isEmpty else { return }
        let event = CalendarEvent(
            id: existing?.id ?? UUID(),
            title: trimmedTitle,
            time: time.trimmingCharacters(in: .whitespacesAndNewlines),
            description: description.trimmingCharacters(in: .whitespacesAndNewlines),
            colorTag: colorTag,
            isAllDay: isAllDay,
            date: dateStr,
            reminderTime: reminderTime
        )
        onDone(event)
    }
}

// MARK: - 日期选择器 Sheet
private struct DatePickerSheetView: View {
    let title: String
    let initial: Date
    let displayedComponents: DatePickerComponents
    let theme: AppTheme
    let onConfirm: (Date) -> Void

    @State private var date: Date
    @Environment(\.dismiss) private var dismiss

    init(title: String, initial: Date, displayedComponents: DatePickerComponents,
         theme: AppTheme, onConfirm: @escaping (Date) -> Void) {
        self.title = title
        self.initial = initial
        self.displayedComponents = displayedComponents
        self.theme = theme
        self.onConfirm = onConfirm
        self._date = State(initialValue: initial)
    }

    var body: some View {
        ZStack {
            FluidBackground(animTime: 0, theme: theme)
            VStack(spacing: 16) {
                HStack {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                            .font(.headline)
                            .foregroundStyle(theme.textSecondary)
                    }
                    Spacer()
                    Text(title)
                        .font(.headline)
                        .foregroundStyle(theme.textPrimary)
                    Spacer()
                    Button { onConfirm(date) } label: {
                        Text("确定")
                            .font(.headline)
                            .foregroundStyle(theme.fluidCyan)
                    }
                }
                .padding(.horizontal, 20)

                DatePicker("", selection: $date, in: dateRange,
                           displayedComponents: displayedComponents)
                    .datePickerStyle(.graphical)
                    .labelsHidden()
                    .tint(theme.fluidCyan)
                    .padding(.horizontal, 16)
                Spacer()
            }
            .padding(.top, 40)
        }
    }

    /// 提醒时间允许选过去的时间（用户可自定义任意时刻）；日期选择不限范围。
    private var dateRange: ClosedRange<Date> {
        Calendar.current.date(byAdding: .year, value: -10, to: Date())!
            ...
        Calendar.current.date(byAdding: .year, value: 10, to: Date())!
    }
}
