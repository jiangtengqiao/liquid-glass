import SwiftUI
import UIKit

// ─────────────────────────────────────────────────────────────────
// 密码生成器 · 随机工具 —— 对应 Android 端 ui/PasswordGeneratorScreen.kt
//
// 关键实现：
//   1. 密码生成：长度滑块 4~64、大写/小写/数字/符号开关、排除相似字符；
//      保证每种已选类型至少出现 1 字符，再随机填充后洗牌（与 Android 一致）
//   2. 密码强度：依据长度/字符种类/唯一度打分，分档着色 + 进度条
//   3. 随机工具子页：随机数 / 骰子 / 抛硬币 / 随机颜色 / UUID
//      骰子与硬币用 Task + async sleep 驱动逐帧动画（对应 Android 的协程 + delay）
//   4. 复制到剪贴板：UIPasteboard.general.string（对应 Android ClipboardManager）
//   5. 历史：密码 / 随机数 / UUID 历史通过 Persistence.shared（UserDefaults）持久化
// ─────────────────────────────────────────────────────────────────

// MARK: - 持久化键
private let kPasswordHistoryKey     = "liquid_glass_password_history"
private let kRandomNumberHistoryKey = "liquid_glass_random_number_history"
private let kUuidHistoryKey         = "liquid_glass_uuid_history"

// MARK: - 密码生成器视图模型
final class PasswordGeneratorViewModel: ObservableObject {
    @Published var length: Int = 16
    @Published var includeUppercase = true
    @Published var includeLowercase = true
    @Published var includeNumbers = true
    @Published var includeSymbols = true
    @Published var excludeSimilar = false
    @Published var generatedPassword: String = ""
    @Published var history: [String] = []

    private static let uppercase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private static let lowercase = "abcdefghijklmnopqrstuvwxyz"
    private static let numbers = "0123456789"
    private static let symbols = "!@#$%^&*()-_=+[]{}|;:,.<>?/`~"
    private static let similarChars = "il1ILo0O|"

    init() {
        history = Persistence.shared.stringArray(for: kPasswordHistoryKey)
    }

    /// 当前生成结果是否为提示文案（非真实密码），用于控制强度条显示。
    var isPlaceholder: Bool {
        generatedPassword.hasPrefix("请选择") || generatedPassword.hasPrefix("排除")
    }

    private var strengthScore: Int {
        guard !generatedPassword.isEmpty, !isPlaceholder else { return 0 }
        var score = 0
        let pwd = generatedPassword
        if pwd.count >= 8 { score += 1 }
        if pwd.count >= 12 { score += 1 }
        if pwd.count >= 16 { score += 1 }
        if pwd.count >= 24 { score += 1 }
        if pwd.contains(where: { $0.isUppercase }) { score += 1 }
        if pwd.contains(where: { $0.isLowercase }) { score += 1 }
        if pwd.contains(where: { $0.isNumber }) { score += 1 }
        if pwd.contains(where: { !$0.isLetter && !$0.isNumber }) { score += 1 }
        let unique = Set(pwd).count
        if Double(unique) >= Double(pwd.count) * 0.6 { score += 1 }
        if Double(unique) >= Double(pwd.count) * 0.8 { score += 1 }
        return score
    }

    var strengthLabel: String {
        guard !generatedPassword.isEmpty, !isPlaceholder else { return "" }
        switch strengthScore {
        case 0...3:  return "弱"
        case 4...5:  return "中等"
        case 6...7:  return "强"
        default:     return "非常强"
        }
    }

    var strengthColor: Color {
        guard !generatedPassword.isEmpty, !isPlaceholder else { return .clear }
        switch strengthScore {
        case 0...3:  return AccentDanger
        case 4...5:  return AccentWarning
        case 6...7:  return FluidCyan
        default:     return AccentSuccess
        }
    }

    var strengthProgress: Double {
        guard !generatedPassword.isEmpty, !isPlaceholder else { return 0 }
        return min(1, max(0, Double(strengthScore) / 10.0))
    }

    func generatePassword() {
        let uppercase = Self.uppercase
        let lowercase = Self.lowercase
        let numbers = Self.numbers
        let symbols = Self.symbols
        let similarChars = Self.similarChars

        var charPool = ""
        if includeUppercase { charPool += uppercase }
        if includeLowercase { charPool += lowercase }
        if includeNumbers { charPool += numbers }
        if includeSymbols { charPool += symbols }

        if charPool.isEmpty {
            generatedPassword = "请选择至少一种字符类型"
            return
        }

        if excludeSimilar {
            charPool = String(charPool.filter { !similarChars.contains($0) })
        }
        if charPool.isEmpty {
            generatedPassword = "排除相似字符后无可用字符"
            return
        }

        // 每种已选类型至少 1 字符
        var chars: [Character] = []
        if includeUppercase {
            let pool = excludeSimilar ? String(uppercase.filter { !similarChars.contains($0) }) : uppercase
            if let c = pool.randomElement() { chars.append(c) }
        }
        if includeLowercase {
            let pool = excludeSimilar ? String(lowercase.filter { !similarChars.contains($0) }) : lowercase
            if let c = pool.randomElement() { chars.append(c) }
        }
        if includeNumbers {
            let pool = excludeSimilar ? String(numbers.filter { !similarChars.contains($0) }) : numbers
            if let c = pool.randomElement() { chars.append(c) }
        }
        if includeSymbols {
            let pool = excludeSimilar ? String(symbols.filter { !similarChars.contains($0) }) : symbols
            if let c = pool.randomElement() { chars.append(c) }
        }

        let poolArray = Array(charPool)
        while chars.count < length {
            if let c = poolArray.randomElement() { chars.append(c) } else { break }
        }
        chars.shuffle()
        let result = String(chars)

        generatedPassword = result
        history = Array(([result] + history).prefix(20))
        Persistence.shared.setStringArray(history, for: kPasswordHistoryKey)
    }
}

// MARK: - 随机工具视图模型
final class RandomToolsViewModel: ObservableObject {
    // 随机数
    @Published var rnMin: String = "1"
    @Published var rnMax: String = "100"
    @Published var rnResult: String = ""
    @Published var rnHistory: [String] = []
    // 骰子
    @Published var diceCount: Int = 2
    @Published var diceResults: [Int] = []
    @Published var diceTotal: Int = 0
    @Published var isRolling: Bool = false
    // 硬币
    @Published var coinResult: String = ""
    @Published var isFlipping: Bool = false
    @Published var coinAngle: Double = 0
    // 随机颜色
    @Published var randomColorR: Int = 255
    @Published var randomColorG: Int = 255
    @Published var randomColorB: Int = 255
    @Published var randomColorHex: String = "#FFFFFF"
    // UUID
    @Published var generatedUuid: String = ""
    @Published var uuidHistory: [String] = []

    init() {
        rnHistory = Persistence.shared.stringArray(for: kRandomNumberHistoryKey)
        uuidHistory = Persistence.shared.stringArray(for: kUuidHistoryKey)
    }

    var randomColor: Color {
        Color(
            .sRGB,
            red:   Double(randomColorR) / 255.0,
            green: Double(randomColorG) / 255.0,
            blue:  Double(randomColorB) / 255.0
        )
    }

    // MARK: 随机数
    func generateRandomNumber() {
        let min = Int(rnMin) ?? 1
        let max = Int(rnMax) ?? 100
        let actualMin = Swift.min(min, max)
        let actualMax = Swift.max(min, max)
        let result = Int.random(in: actualMin...actualMax)
        rnResult = "\(result) (\(actualMin)-\(actualMax))"
        rnHistory = Array(([String(result)] + rnHistory).prefix(20))
        Persistence.shared.setStringArray(rnHistory, for: kRandomNumberHistoryKey)
    }

    // MARK: 骰子（逐帧动画）
    @MainActor
    func rollDice() {
        guard !isRolling else { return }
        isRolling = true
        Task { @MainActor in
            for _ in 0..<12 {
                let tmp = (0..<diceCount).map { _ in Int.random(in: 1...6) }
                diceResults = tmp
                diceTotal = tmp.reduce(0, +)
                try? await Task.sleep(nanoseconds: 60_000_000)
            }
            let final = (0..<diceCount).map { _ in Int.random(in: 1...6) }
            diceResults = final
            diceTotal = final.reduce(0, +)
            isRolling = false
        }
    }

    // MARK: 抛硬币（旋转动画）
    @MainActor
    func flipCoin() {
        guard !isFlipping else { return }
        isFlipping = true
        coinResult = ""
        coinAngle = 0
        let target = Bool.random() ? "正面" : "反面"
        let totalFlips = 10
        Task { @MainActor in
            for i in 1...totalFlips {
                let progress = Double(i) / Double(totalFlips)
                coinAngle = progress * 360 * 3
                try? await Task.sleep(nanoseconds: 40_000_000)
            }
            coinAngle = 0
            coinResult = target
            isFlipping = false
        }
    }

    // MARK: 随机颜色
    func generateRandomColor() {
        let r = Int.random(in: 0..<256)
        let g = Int.random(in: 0..<256)
        let b = Int.random(in: 0..<256)
        randomColorR = r
        randomColorG = g
        randomColorB = b
        randomColorHex = String(format: "#%02X%02X%02X", r, g, b)
        UIPasteboard.general.string = randomColorHex
    }

    // MARK: UUID
    func generateUuid() {
        let uuid = UUID().uuidString
        generatedUuid = uuid
        uuidHistory = Array(([uuid] + uuidHistory).prefix(20))
        Persistence.shared.setStringArray(uuidHistory, for: kUuidHistoryKey)
    }
}

// MARK: - 主屏幕
struct PasswordGeneratorScreen: View {
    var onBack: () -> Void

    @State private var mainTab: Int = 0       // 0=密码, 1=随机工具
    @State private var randomSubTab: Int = 0  // 0=数 1=骰 2=币 3=色 4=UUID
    @StateObject private var pwdVM = PasswordGeneratorViewModel()
    @StateObject private var rtVM = RandomToolsViewModel()

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: 0, theme: theme)

            VStack(spacing: 16) {
                topBar(theme: theme)
                mainTabRow(theme: theme)

                ScrollView {
                    if mainTab == 0 {
                        passwordTab(theme: theme)
                    } else {
                        randomToolsTab(theme: theme)
                    }
                }
            }
            .padding(.top, 50)
            .padding(.bottom, 24)
        }
    }

    // MARK: - 顶部栏
    private func topBar(theme: AppTheme) -> some View {
        HStack {
            Button { onBack() } label: {
                Image(systemName: "chevron.left").foregroundStyle(theme.textSecondary)
            }
            Text("密码·随机工具").font(.headline).foregroundStyle(theme.textPrimary)
            Spacer()
        }
        .padding(.horizontal, 20)
    }

    // MARK: - 主 Tab 行
    private func mainTabRow(theme: AppTheme) -> some View {
        HStack(spacing: 8) {
            MainTabButton(label: "密码生成", selected: mainTab == 0, theme: theme) { mainTab = 0 }
            MainTabButton(label: "随机工具", selected: mainTab == 1, theme: theme) { mainTab = 1 }
        }
        .padding(.horizontal, 20)
    }

    // MARK: - 密码生成 Tab
    private func passwordTab(theme: AppTheme) -> some View {
        VStack(spacing: 12) {
            // 长度滑块
            SectionCard(title: "密码长度：\(pwdVM.length)", theme: theme) {
                VStack(spacing: 6) {
                    Slider(
                        value: Binding(
                            get: { Double(pwdVM.length) },
                            set: { pwdVM.length = Int($0) }
                        ),
                        in: 4...64,
                        step: 1,
                        tint: FluidCyan
                    )
                    HStack {
                        Text("4").font(.caption2).foregroundStyle(theme.textTertiary)
                        Spacer()
                        Text("64").font(.caption2).foregroundStyle(theme.textTertiary)
                    }
                }
            }

            // 字符类型
            SectionCard(title: "字符类型", theme: theme) {
                VStack(spacing: 4) {
                    ToggleOption(label: "大写字母 (A-Z)", checked: pwdVM.includeUppercase,
                                 icon: "textformat", color: FluidCyan, theme: theme) {
                        pwdVM.includeUppercase.toggle()
                    }
                    ToggleOption(label: "小写字母 (a-z)", checked: pwdVM.includeLowercase,
                                 icon: "textformat", color: theme.fluidPurple, theme: theme) {
                        pwdVM.includeLowercase.toggle()
                    }
                    ToggleOption(label: "数字 (0-9)", checked: pwdVM.includeNumbers,
                                 icon: "number", color: theme.fluidTeal, theme: theme) {
                        pwdVM.includeNumbers.toggle()
                    }
                    ToggleOption(label: "符号 (!@#$%^&*等)", checked: pwdVM.includeSymbols,
                                 icon: "chevron.left.forwardslash.chevron.right", color: theme.fluidPink, theme: theme) {
                        pwdVM.includeSymbols.toggle()
                    }
                }
            }

            // 排除相似字符
            ToggleOption(label: "排除相似字符 (il1ILo0O|)", checked: pwdVM.excludeSimilar,
                         icon: "eye.slash", color: AccentWarning, theme: theme, standalone: true) {
                pwdVM.excludeSimilar.toggle()
            }

            // 生成按钮
            GradientButton(title: "生成密码", icon: "key.fill",
                           gradient: [FluidCyan, theme.fluidPurple, theme.fluidPink], theme: theme) {
                pwdVM.generatePassword()
            }
            .padding(.top, 4)

            // 生成结果
            if !pwdVM.generatedPassword.isEmpty && !pwdVM.isPlaceholder {
                generatedPasswordCard(theme: theme)
            }

            // 历史
            if !pwdVM.history.isEmpty {
                SectionCard(title: "生成历史 (最近\(pwdVM.history.count)条)", theme: theme) {
                    VStack(spacing: 6) {
                        ForEach(Array(pwdVM.history.enumerated()), id: \.offset) { index, item in
                            historyRow(index: index + 1, item: item, theme: theme)
                        }
                    }
                }
            }

            Spacer(minLength: 12)
        }
        .padding(.horizontal, 20)
    }

    private func generatedPasswordCard(theme: AppTheme) -> some View {
        VStack(spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "lock.fill").foregroundStyle(FluidCyan)
                Text(pwdVM.generatedPassword)
                    .font(.system(size: 18, weight: .medium, design: .monospaced))
                    .foregroundStyle(theme.textPrimary)
                    .lineLimit(2)
                    .minimumScaleFactor(0.6)
                Spacer(minLength: 0)
                Button {
                    UIPasteboard.general.string = pwdVM.generatedPassword
                } label: {
                    Image(systemName: "doc.on.doc")
                        .foregroundStyle(FluidCyan)
                        .frame(width: 36, height: 36)
                }
            }

            // 强度指示
            if !pwdVM.strengthLabel.isEmpty {
                HStack(spacing: 12) {
                    Text("强度：").font(.caption).foregroundStyle(theme.textTertiary)
                    Text(pwdVM.strengthLabel)
                        .font(.caption.weight(.bold))
                        .foregroundStyle(pwdVM.strengthColor)
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule()
                                .fill(theme.glassMedium)
                                .frame(height: 8)
                            Capsule()
                                .fill(
                                    LinearGradient(
                                        colors: [AccentDanger, AccentWarning, FluidCyan, AccentSuccess],
                                        startPoint: .leading, endPoint: .trailing
                                    )
                                )
                                .frame(width: geo.size.width * pwdVM.strengthProgress, height: 8)
                        }
                    }
                    .frame(height: 8)
                }
            }
        }
        .padding(16)
        .glassSurface(cornerRadius: 20, glassAlpha: 0.16, theme: theme)
    }

    private func historyRow(index: Int, item: String, theme: AppTheme) -> some View {
        HStack(spacing: 8) {
            Text("\(index)")
                .font(.caption2)
                .foregroundStyle(theme.textTertiary)
                .frame(width: 24, alignment: .leading)
            Text(item)
                .font(.system(size: 13, design: .monospaced))
                .foregroundStyle(theme.textSecondary)
                .lineLimit(1)
                .truncationMode(.tail)
            Spacer(minLength: 0)
            Button {
                UIPasteboard.general.string = item
            } label: {
                Image(systemName: "doc.on.doc")
                    .font(.caption2)
                    .foregroundStyle(theme.textTertiary)
                    .frame(width: 28, height: 28)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .glassSurface(cornerRadius: 10, glassAlpha: 0.06, theme: theme)
    }

    // MARK: - 随机工具 Tab
    private func randomToolsTab(theme: AppTheme) -> some View {
        VStack(spacing: 12) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    SubTabChip(label: "随机数", icon: "number", selected: randomSubTab == 0, theme: theme) { randomSubTab = 0 }
                    SubTabChip(label: "骰子", icon: "die.face.5", selected: randomSubTab == 1, theme: theme) { randomSubTab = 1 }
                    SubTabChip(label: "抛硬币", icon: "circle", selected: randomSubTab == 2, theme: theme) { randomSubTab = 2 }
                    SubTabChip(label: "随机颜色", icon: "paintpalette", selected: randomSubTab == 3, theme: theme) { randomSubTab = 3 }
                    SubTabChip(label: "UUID", icon: "fingerprint", selected: randomSubTab == 4, theme: theme) { randomSubTab = 4 }
                }
            }

            switch randomSubTab {
            case 0: randomNumberSection(theme: theme)
            case 1: diceSection(theme: theme)
            case 2: coinSection(theme: theme)
            case 3: randomColorSection(theme: theme)
            default: uuidSection(theme: theme)
            }

            Spacer(minLength: 12)
        }
        .padding(.horizontal, 20)
    }

    // MARK: 随机数
    private func randomNumberSection(theme: AppTheme) -> some View {
        VStack(spacing: 12) {
            SectionCard(title: "随机数生成", theme: theme) {
                VStack(spacing: 12) {
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("最小值").font(.caption2).foregroundStyle(theme.textTertiary)
                            GlassTextField(text: $rtVM.rnMin, theme: theme)
                        }
                        VStack(alignment: .leading, spacing: 4) {
                            Text("最大值").font(.caption2).foregroundStyle(theme.textTertiary)
                            GlassTextField(text: $rtVM.rnMax, theme: theme)
                        }
                    }
                    .keyboardType(.numberPad)

                    GradientButton(title: "生成随机数", icon: nil,
                                   gradient: [theme.fluidTeal, FluidCyan], theme: theme) {
                        rtVM.generateRandomNumber()
                    }

                    if !rtVM.rnResult.isEmpty {
                        HStack {
                            Text(rtVM.rnResult)
                                .font(.system(size: 28, weight: .bold, design: .rounded))
                                .foregroundStyle(theme.fluidTeal)
                            Spacer()
                            Button {
                                UIPasteboard.general.string = rtVM.rnResult.split(separator: "(").first
                                    .map { String($0).trimmingCharacters(in: .whitespaces) } ?? ""
                            } label: {
                                Image(systemName: "doc.on.doc").foregroundStyle(theme.fluidTeal).frame(width: 40, height: 40)
                            }
                        }
                        .padding(16)
                        .glassSurface(cornerRadius: 14, glassAlpha: 0.14, theme: theme)
                    }
                }
            }

            if !rtVM.rnHistory.isEmpty {
                SectionCard(title: "随机数历史", theme: theme) {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(Array(rtVM.rnHistory.enumerated()), id: \.offset) { _, item in
                                Text(item)
                                    .font(.subheadline)
                                    .foregroundStyle(theme.textSecondary)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .glassSurface(cornerRadius: 10, glassAlpha: 0.06, theme: theme)
                            }
                        }
                    }
                }
            }
        }
    }

    // MARK: 骰子
    private func diceSection(theme: AppTheme) -> some View {
        SectionCard(title: "骰子投掷", theme: theme) {
            VStack(spacing: 12) {
                Text("骰子数量：\(rtVM.diceCount)")
                    .font(.subheadline).foregroundStyle(theme.textSecondary)

                HStack(spacing: 8) {
                    ForEach(1...6, id: \.self) { n in
                        let selected = rtVM.diceCount == n
                        Text("\(n)")
                            .font(.system(size: 18, weight: selected ? .bold : .regular))
                            .foregroundStyle(selected ? FluidCyan : theme.textSecondary)
                            .frame(maxWidth: .infinity)
                            .frame(height: 48)
                            .glassSurface(cornerRadius: 12,
                                          glassAlpha: selected ? 0.18 : 0.06,
                                          showBorder: selected, theme: theme)
                            .onTapGesture { rtVM.diceCount = n }
                    }
                }

                GradientButton(title: rtVM.isRolling ? "投掷中..." : "投掷骰子", icon: "die.face.5.fill",
                               gradient: [theme.fluidPurple, theme.fluidPink], theme: theme) {
                    rtVM.rollDice()
                }

                if !rtVM.diceResults.isEmpty {
                    HStack(spacing: 8) {
                        ForEach(Array(rtVM.diceResults.enumerated()), id: \.offset) { _, result in
                            Text(diceChar(result))
                                .font(.system(size: 36))
                                .foregroundStyle(diceColor(result, theme: theme))
                        }
                    }
                    .padding(.top, 4)

                    Text("合计：\(rtVM.diceTotal)")
                        .font(.system(size: 20, weight: .bold, design: .rounded))
                        .foregroundStyle(theme.fluidPurple)
                        .frame(maxWidth: .infinity)
                        .padding(12)
                        .glassSurface(cornerRadius: 14, glassAlpha: 0.14, theme: theme)
                }
            }
        }
    }

    private func diceChar(_ n: Int) -> String {
        switch n {
        case 1: return "⚀"; case 2: return "⚁"; case 3: return "⚂"
        case 4: return "⚃"; case 5: return "⚄"; case 6: return "⚅"
        default: return "\(n)"
        }
    }

    private func diceColor(_ n: Int, theme: AppTheme) -> Color {
        switch n {
        case 1: return AccentDanger
        case 2: return AccentWarning
        case 3: return theme.fluidTeal
        case 4: return FluidCyan
        case 5: return theme.fluidPurple
        case 6: return theme.fluidPink
        default: return theme.textPrimary
        }
    }

    // MARK: 抛硬币
    private func coinSection(theme: AppTheme) -> some View {
        SectionCard(title: "抛硬币", theme: theme) {
            VStack(spacing: 16) {
                ZStack {
                    Circle()
                        .fill(LinearGradient(colors: [FluidCyan, theme.fluidPurple],
                                             startPoint: .topLeading, endPoint: .bottomTrailing))
                        .frame(width: 120, height: 120)
                        .rotation3DEffect(.degrees(rtVM.coinAngle),
                                          axis: (x: 0, y: 1, z: 0))
                    if rtVM.coinAngle == 0 && !rtVM.isFlipping {
                        Text(coinLetter)
                            .font(.system(size: 48, weight: .bold))
                            .foregroundStyle(Color.white.opacity(0.8))
                    }
                }

                if !rtVM.coinResult.isEmpty {
                    Text(rtVM.coinResult)
                        .font(.system(size: 24, weight: .bold, design: .rounded))
                        .foregroundStyle(rtVM.coinResult == "正面" ? FluidCyan : theme.fluidPink)
                }

                GradientButton(title: rtVM.isFlipping ? "抛掷中..." : "抛硬币", icon: "circle.fill",
                               gradient: [FluidCyan, theme.fluidPurple], theme: theme) {
                    rtVM.flipCoin()
                }
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var coinLetter: String {
        if rtVM.coinResult == "正面" { return "H" }
        if rtVM.coinResult == "反面" { return "T" }
        return "?"
    }

    // MARK: 随机颜色
    private func randomColorSection(theme: AppTheme) -> some View {
        SectionCard(title: "随机颜色", theme: theme) {
            VStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(rtVM.randomColor)
                    .frame(width: 100, height: 100)
                    .glassSurface(cornerRadius: 20, glassAlpha: 0.12, theme: theme)

                HStack(spacing: 8) {
                    Text(rtVM.randomColorHex)
                        .font(.system(size: 22, weight: .bold, design: .monospaced))
                        .foregroundStyle(theme.textPrimary)
                    Button {
                        UIPasteboard.general.string = rtVM.randomColorHex
                    } label: {
                        Image(systemName: "doc.on.doc").foregroundStyle(FluidCyan).frame(width: 36, height: 36)
                    }
                }

                Text("RGB(\(rtVM.randomColorR), \(rtVM.randomColorG), \(rtVM.randomColorB))")
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(theme.textTertiary)

                GradientButton(title: "生成随机颜色", icon: "paintpalette.fill",
                               gradient: [Color(hex: 0xFF0000), Color(hex: 0xFF8800), Color(hex: 0xFFFF00),
                                          Color(hex: 0x00FF00), Color(hex: 0x0088FF), Color(hex: 0x8800FF)],
                               theme: theme) {
                    rtVM.generateRandomColor()
                }
                .padding(.top, 4)
            }
            .frame(maxWidth: .infinity)
        }
    }

    // MARK: UUID
    private func uuidSection(theme: AppTheme) -> some View {
        VStack(spacing: 12) {
            SectionCard(title: "UUID 生成器", theme: theme) {
                VStack(spacing: 12) {
                    GradientButton(title: "生成 UUID v4", icon: "fingerprint",
                                   gradient: [theme.fluidBlue, theme.fluidPurple], theme: theme) {
                        rtVM.generateUuid()
                    }

                    if !rtVM.generatedUuid.isEmpty {
                        HStack(spacing: 8) {
                            Text(rtVM.generatedUuid)
                                .font(.system(size: 14, design: .monospaced))
                                .foregroundStyle(theme.textPrimary)
                            Spacer(minLength: 0)
                            Button {
                                UIPasteboard.general.string = rtVM.generatedUuid
                            } label: {
                                Image(systemName: "doc.on.doc").foregroundStyle(theme.fluidPurple).frame(width: 36, height: 36)
                            }
                        }
                        .padding(14)
                        .glassSurface(cornerRadius: 14, glassAlpha: 0.14, theme: theme)
                    }
                }
            }

            if !rtVM.uuidHistory.isEmpty {
                SectionCard(title: "UUID 历史", theme: theme) {
                    VStack(spacing: 6) {
                        ForEach(Array(rtVM.uuidHistory.enumerated()), id: \.offset) { index, uuid in
                            HStack(spacing: 8) {
                                Text("\(index + 1)")
                                    .font(.caption2)
                                    .foregroundStyle(theme.textTertiary)
                                    .frame(width: 24, alignment: .leading)
                                Text(uuid)
                                    .font(.system(size: 12, design: .monospaced))
                                    .foregroundStyle(theme.textSecondary)
                                    .lineLimit(1)
                                Spacer(minLength: 0)
                                Button {
                                    UIPasteboard.general.string = uuid
                                } label: {
                                    Image(systemName: "doc.on.doc")
                                        .font(.caption2)
                                        .foregroundStyle(theme.textTertiary)
                                        .frame(width: 28, height: 28)
                                }
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .glassSurface(cornerRadius: 10, glassAlpha: 0.06, theme: theme)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - 主 Tab 按钮
private struct MainTabButton: View {
    let label: String
    let selected: Bool
    let theme: AppTheme
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.subheadline.weight(selected ? .medium : .regular))
                .foregroundStyle(selected ? FluidCyan : theme.textTertiary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .glassSurface(cornerRadius: 14,
                              glassAlpha: selected ? 0.18 : 0.06,
                              showBorder: selected, theme: theme)
        }
    }
}

// MARK: - 子 Tab Chip
private struct SubTabChip: View {
    let label: String
    let icon: String
    let selected: Bool
    let theme: AppTheme
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: icon).font(.caption)
                Text(label).font(.caption)
            }
            .foregroundStyle(selected ? FluidCyan : theme.textTertiary)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .glassSurface(cornerRadius: 20,
                          glassAlpha: selected ? 0.16 : 0.06,
                          showBorder: selected, theme: theme)
        }
    }
}

// MARK: - 区块卡片
private struct SectionCard<Content: View>: View {
    let title: String
    let theme: AppTheme
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.subheadline)
                .foregroundStyle(theme.textSecondary)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .glassSurface(cornerRadius: 20, glassAlpha: 0.12, theme: theme)
    }
}

// MARK: - 开关选项行
private struct ToggleOption: View {
    let label: String
    let checked: Bool
    let icon: String
    let color: Color
    let theme: AppTheme
    var standalone: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: icon)
                    .foregroundStyle(checked ? color : theme.textTertiary)
                Text(label)
                    .font(.subheadline)
                    .foregroundStyle(checked ? theme.textPrimary : theme.textSecondary)
                Spacer()
                Toggle("", isOn: Binding(
                    get: { checked },
                    set: { _ in action() }
                ))
                .labelsHidden()
                .tint(color)
                .scaleEffect(0.8)
            }
            .padding(.horizontal, standalone ? 16 : 12)
            .padding(.vertical, standalone ? 12 : 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .glassSurface(cornerRadius: standalone ? 14 : 12,
                          glassAlpha: standalone ? 0.10 : 0.06, theme: theme)
        }
    }
}

// MARK: - 渐变按钮
private struct GradientButton: View {
    let title: String
    let icon: String?
    let gradient: [Color]
    let theme: AppTheme
    let action: () -> Void

    @State private var pressed = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let icon { Image(systemName: icon) }
                Text(title).font(.headline)
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(
                LinearGradient(colors: gradient, startPoint: .topLeading, endPoint: .bottomTrailing)
            )
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .scaleEffect(pressed ? 0.92 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.5), value: pressed)
        }
        .simultaneousGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in pressed = true }
                .onEnded { _ in pressed = false }
        )
    }
}

// MARK: - 玻璃文本框
private struct GlassTextField: View {
    @Binding var text: String
    let theme: AppTheme

    var body: some View {
        TextField("", text: $text)
            .textInputAutocapitalization(.never)
            .font(.system(size: 16))
            .foregroundStyle(theme.textPrimary)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .glassSurface(cornerRadius: 12, glassAlpha: 0.08, theme: theme)
    }
}
