import SwiftUI

// ─────────────────────────────────────────────────────────────────
// BMI 计算器 · 健康工具 —— 对应 Android 端 ui/BMICalculatorScreen.kt
//
// 关键实现：
//   1. 四个子 Tab：BMI / 体脂率 / 卡路里 / 饮水
//   2. BMI：性别 + 年龄 + 身高(cm/ft+in 可切换) + 体重(kg/lb 可切换)，
//      计算 BMI 与理想体重区间，6 档分类着色 + 色条指示器
//   3. 体脂率：美国海军体脂公式（男女区分，女性需臀围），含参考表
//   4. 卡路里：Mifflin-St Jeor BMR + 5 档活动水平，宏量营养素 30/30/40 分配
//   5. 饮水：体重 × 33ml，附饮水建议
//   6. BMI 历史通过 Persistence.shared（UserDefaults）持久化为 JSON Data
// ─────────────────────────────────────────────────────────────────

private let kBmiHistoryKey = "liquid_glass_bmi_history"

// MARK: - 数据模型
/// 单条 BMI 历史记录（对应 Android BmiRecord）。
private struct BmiRecord: Identifiable, Codable {
    let id: UUID
    let bmi: Double
    let category: String
    let date: String
    let weight: Double
    let height: Double

    init(bmi: Double, category: String, date: String, weight: Double, height: Double) {
        self.id = UUID()
        self.bmi = bmi
        self.category = category
        self.date = date
        self.weight = weight
        self.height = height
    }
}

// MARK: - BMI 历史持久化
private func loadBmiHistory() -> [BmiRecord] {
    Persistence.shared.object([BmiRecord].self, for: kBmiHistoryKey) ?? []
}

private func saveBmiHistory(_ records: [BmiRecord]) {
    Persistence.shared.setObject(records, for: kBmiHistoryKey)
}

private func addBmiRecord(_ record: BmiRecord) {
    var history = loadBmiHistory()
    history.insert(record, at: 0)
    if history.count > 50 { history = Array(history.prefix(50)) }
    saveBmiHistory(history)
}

// MARK: - 计算工具函数（与 Android 端公式一一对应）
private func getBmiCategory(_ bmi: Double) -> (String, Color) {
    switch bmi {
    case ..<18.5:  return ("偏瘦", FluidCyan)
    case ..<25.0:  return ("正常", FluidTeal)
    case ..<30.0:  return ("超重", FluidOrange)
    case ..<35.0:  return ("肥胖 I 级", AccentWarning)
    case ..<40.0:  return ("肥胖 II 级", AccentDanger)
    default:       return ("肥胖 III 级", FluidPink)
    }
}

private func calcBmi(weightKg: Double, heightCm: Double) -> Double {
    guard heightCm > 0 else { return 0 }
    let h = heightCm / 100.0
    return weightKg / (h * h)
}

private func calcIdealWeightRange(heightCm: Double) -> (Double, Double) {
    let h = heightCm / 100.0
    return (18.5 * h * h, 24.9 * h * h)
}

/// 美国海军体脂率公式（gender=true 男, false 女）。
private func calcBodyFatNavy(gender: Bool, heightCm: Double, neckCm: Double,
                             waistCm: Double, hipCm: Double) -> Double {
    guard heightCm > 0, neckCm > 0, waistCm > 0 else { return 0 }
    if gender {
        return 86.010 * log10(waistCm - neckCm) - 70.041 * log10(heightCm) + 36.76
    } else {
        guard hipCm > 0 else { return 0 }
        return 163.205 * log10(waistCm + hipCm - neckCm) - 97.684 * log10(heightCm) - 78.387
    }
}

private func getBodyFatCategory(_ bf: Double, gender: Bool) -> (String, Color) {
    if gender {
        switch bf {
        case ..<6:   return ("必需脂肪", FluidCyan)
        case ..<14:  return ("运动员", FluidTeal)
        case ..<18:  return ("健康", AccentSuccess)
        case ..<25:  return ("可接受", FluidOrange)
        case ..<32:  return ("超重", AccentWarning)
        default:     return ("肥胖", AccentDanger)
        }
    } else {
        switch bf {
        case ..<14:  return ("必需脂肪", FluidCyan)
        case ..<21:  return ("运动员", FluidTeal)
        case ..<25:  return ("健康", AccentSuccess)
        case ..<32:  return ("可接受", FluidOrange)
        case ..<40:  return ("超重", AccentWarning)
        default:     return ("肥胖", AccentDanger)
        }
    }
}

private func calcBmrMifflinStJeor(gender: Bool, weightKg: Double, heightCm: Double, age: Int) -> Double {
    let base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * Double(age)
    return gender ? base + 5.0 : base - 161.0
}

private func activityMultiplier(_ level: Int) -> Double {
    switch level {
    case 0:  return 1.2
    case 1:  return 1.375
    case 2:  return 1.55
    case 3:  return 1.725
    case 4:  return 1.9
    default: return 1.2
    }
}

// MARK: - 结果模型
private struct BmiResult {
    let bmi: Double
    let category: String
    let categoryColor: Color
    let idealMinWeight: Double
    let idealMaxWeight: Double
}

private struct BodyFatResult {
    let percentage: Double
    let category: String
    let categoryColor: Color
}

private struct CalorieResult {
    let bmr: Double
    let dailyCalories: Double
    let protein: Double
    let fat: Double
    let carbs: Double
}

// MARK: - 主屏幕
struct BMICalculatorScreen: View {
    var onBack: () -> Void

    @State private var selectedTab: Int = 0
    private let tabs = ["BMI", "体脂率", "卡路里", "饮水"]

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: 0, theme: theme)

            VStack(spacing: 16) {
                topBar(theme: theme)
                tabBar(theme: theme)

                ScrollView {
                    switch selectedTab {
                    case 0:  bmiTab(theme: theme)
                    case 1:  bodyFatTab(theme: theme)
                    case 2:  calorieTab(theme: theme)
                    default: waterTab(theme: theme)
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
            Text("健康工具").font(.headline).foregroundStyle(theme.textPrimary)
            Spacer()
        }
        .padding(.horizontal, 20)
    }

    // MARK: - Tab 栏
    private func tabBar(theme: AppTheme) -> some View {
        HStack(spacing: 4) {
            ForEach(Array(tabs.enumerated()), id: \.offset) { i, tab in
                Text(tab)
                    .font(.subheadline.weight(i == selectedTab ? .medium : .light))
                    .foregroundStyle(i == selectedTab ? theme.textPrimary : theme.textTertiary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(
                        i == selectedTab
                        ? LinearGradient(colors: [FluidCyan.opacity(0.25), theme.fluidPurple.opacity(0.15)],
                                         startPoint: .leading, endPoint: .trailing)
                        : LinearGradient(colors: [Color.clear], startPoint: .leading, endPoint: .trailing)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .contentShape(Rectangle())
                    .onTapGesture { selectedTab = i }
            }
        }
        .padding(4)
        .glassSurface(cornerRadius: 16, glassAlpha: 0.12, theme: theme)
        .padding(.horizontal, 20)
    }

    // ══════════════════════════════════════════════════════════════
    // MARK: - BMI Tab
    // ══════════════════════════════════════════════════════════════
    private func bmiTab(theme: AppTheme) -> some View {
        VStack(spacing: 12) {
            BmiInputPanel(theme: theme)
        }
        .padding(.horizontal, 20)
    }
}

// MARK: - BMI 输入与结果面板
private struct BmiInputPanel: View {
    let theme: AppTheme

    @State private var genderMale: Bool = true
    @State private var ageText: String = "25"
    @State private var heightCm: String = "170"
    @State private var heightFt: String = "5"
    @State private var heightIn: String = "7"
    @State private var heightUnitCm: Bool = true
    @State private var weightKg: String = "65"
    @State private var weightLb: String = "143"
    @State private var weightUnitKg: Bool = true
    @State private var bmiResult: BmiResult? = nil
    @State private var bmiHistory: [BmiRecord] = loadBmiHistory()
    @State private var showHistory: Bool = false

    private var heightCmValue: Double {
        if heightUnitCm { return Double(heightCm) ?? 0 }
        let ft = Double(heightFt) ?? 0
        let inch = Double(heightIn) ?? 0
        return ft * 30.48 + inch * 2.54
    }

    private var weightKgValue: Double {
        if weightUnitKg { return Double(weightKg) ?? 0 }
        return (Double(weightLb) ?? 0) * 0.453592
    }

    var body: some View {
        VStack(spacing: 12) {
            GenderSelector(genderMale: genderMale, theme: theme) { genderMale = $0 }

            GlassInputCard(label: "年龄", value: $ageText, suffix: "岁", theme: theme)
                .keyboardType(.numberPad)

            HeightInputCard(heightCm: $heightCm, heightFt: $heightFt, heightIn: $heightIn,
                            unitCm: $heightUnitCm, theme: theme)

            WeightInputCard(weightKg: $weightKg, weightLb: $weightLb,
                            unitKg: $weightUnitKg, theme: theme)

            GradientButton(title: "计算 BMI", icon: nil,
                           gradient: [FluidCyan, theme.fluidPurple], theme: theme) {
                let h = heightCmValue
                let w = weightKgValue
                guard h > 0, w > 0 else { return }
                let bmi = calcBmi(weightKg: w, heightCm: h)
                let (cat, color) = getBmiCategory(bmi)
                let (minW, maxW) = calcIdealWeightRange(heightCm: h)
                bmiResult = BmiResult(bmi: bmi, category: cat, categoryColor: color,
                                      idealMinWeight: minW, idealMaxWeight: maxW)
                let formatter = DateFormatter()
                formatter.dateFormat = "yyyy-MM-dd HH:mm"
                addBmiRecord(BmiRecord(bmi: bmi, category: cat,
                                       date: formatter.string(from: Date()),
                                       weight: w, height: h))
                bmiHistory = loadBmiHistory()
            }

            if let result = bmiResult {
                BmiResultCard(result: result, theme: theme)
            }

            // 历史记录
            HStack {
                Text("BMI 历史记录")
                    .font(.subheadline)
                    .foregroundStyle(theme.textSecondary)
                Spacer()
                Button {
                    withAnimation { showHistory.toggle() }
                } label: {
                    Text(showHistory ? "收起" : "展开 (\(bmiHistory.count))")
                        .font(.caption)
                        .foregroundStyle(FluidCyan)
                }
            }
            .padding(.top, 4)

            if showHistory {
                if bmiHistory.isEmpty {
                    Text("暂无记录")
                        .font(.subheadline)
                        .foregroundStyle(theme.textTertiary)
                        .frame(maxWidth: .infinity)
                        .padding(20)
                        .glassSurface(cornerRadius: 16, glassAlpha: 0.10, theme: theme)
                } else {
                    VStack(spacing: 8) {
                        ForEach(Array(bmiHistory.enumerated()), id: \.element.id) { i, record in
                            VStack(spacing: 8) {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(String(format: "%.1f", record.bmi))
                                            .font(.system(size: 16, weight: .medium, design: .rounded))
                                            .foregroundStyle(getBmiCategory(record.bmi).1)
                                        Text(record.date)
                                            .font(.caption2)
                                            .foregroundStyle(theme.textTertiary)
                                    }
                                    Spacer()
                                    Text(record.category)
                                        .font(.subheadline)
                                        .foregroundStyle(getBmiCategory(record.bmi).1)
                                }
                                if i < bmiHistory.count - 1 {
                                    Divider().overlay(theme.glassBorder)
                                }
                            }
                        }
                    }
                    .padding(12)
                    .glassSurface(cornerRadius: 16, glassAlpha: 0.10, theme: theme)
                }
            }

            Spacer(minLength: 12)
        }
    }
}

// MARK: - BMI 结果卡片
private struct BmiResultCard: View {
    let result: BmiResult
    let theme: AppTheme

    var body: some View {
        VStack(spacing: 20) {
            VStack(spacing: 4) {
                Text("你的 BMI")
                    .font(.caption)
                    .foregroundStyle(theme.textTertiary)
                Text(String(format: "%.1f", result.bmi))
                    .font(.system(size: 48, weight: .thin, design: .rounded))
                    .foregroundStyle(result.categoryColor)
            }

            Text(result.category)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(result.categoryColor)
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
                .background(result.categoryColor.opacity(0.15))
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))

            BmiCategoryBar(currentBmi: result.bmi, theme: theme)

            VStack(spacing: 4) {
                Text("理想体重范围")
                    .font(.caption)
                    .foregroundStyle(theme.textTertiary)
                Text("\(String(format: "%.1f", result.idealMinWeight)) - \(String(format: "%.1f", result.idealMaxWeight)) kg")
                    .font(.system(size: 18, weight: .light, design: .rounded))
                    .foregroundStyle(theme.fluidTeal)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .glassSurface(cornerRadius: 20, glassAlpha: 0.15, theme: theme)
    }
}

// MARK: - BMI 分类色条
private struct BmiCategoryBar: View {
    let currentBmi: Double
    let theme: AppTheme

    private let categories: [(String, String, Color)] = [
        ("<18.5", "偏瘦", FluidCyan),
        ("18.5-24.9", "正常", FluidTeal),
        ("25-29.9", "超重", FluidOrange),
        ("30-34.9", "肥胖I", AccentWarning),
        ("35-39.9", "肥胖II", AccentDanger),
        ("≥40", "肥胖III", FluidPink)
    ]

    private var indicatorFraction: Double {
        let pos: Double
        switch currentBmi {
        case ..<18.5:  pos = 0
        case ..<25:    pos = 1 + (currentBmi - 18.5) / 6.4
        case ..<30:    pos = 2 + (currentBmi - 25) / 5
        case ..<35:    pos = 3 + (currentBmi - 30) / 5
        case ..<40:    pos = 4 + (currentBmi - 35) / 5
        default:       pos = 5 + (min(currentBmi, 50) - 40) / 10
        }
        return min(1, max(0, pos / 6.0))
    }

    var body: some View {
        VStack(spacing: 6) {
            GeometryReader { geo in
                ZStack(alignment: .top) {
                    // 色条
                    HStack(spacing: 0) {
                        ForEach(Array(categories.enumerated()), id: \.offset) { _, c in
                            c.2.opacity(0.5)
                                .frame(maxWidth: .infinity)
                                .frame(height: 12)
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))

                    // 三角指示器
                    Text("▼")
                        .font(.caption)
                        .foregroundStyle(getBmiCategory(currentBmi).1)
                        .position(x: geo.size.width * indicatorFraction, y: 0)
                }
            }
            .frame(height: 16)

            HStack(spacing: 0) {
                ForEach(Array(categories.enumerated()), id: \.offset) { i, c in
                    Text(c.0)
                        .font(.system(size: 8))
                        .foregroundStyle(theme.textTertiary)
                        .frame(maxWidth: .infinity)
                }
            }
        }
    }
}

// MARK: - 性别选择
private struct GenderSelector: View {
    let genderMale: Bool
    let theme: AppTheme
    let onSelect: (Bool) -> Void

    var body: some View {
        HStack(spacing: 12) {
            GenderButton(icon: "figure.male", label: "男", selected: genderMale,
                         theme: theme) { onSelect(true) }
            GenderButton(icon: "figure.female", label: "女", selected: !genderMale,
                         theme: theme) { onSelect(false) }
        }
    }
}

private struct GenderButton: View {
    let icon: String
    let label: String
    let selected: Bool
    let theme: AppTheme
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 28))
                    .foregroundStyle(selected ? FluidCyan : theme.textTertiary)
                Text(label)
                    .font(.subheadline.weight(selected ? .medium : .light))
                    .foregroundStyle(selected ? theme.textPrimary : theme.textTertiary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .glassSurface(cornerRadius: 16,
                          glassAlpha: selected ? 0.20 : 0.08,
                          showBorder: selected, theme: theme)
        }
    }
}

// MARK: - 输入卡片
private struct GlassInputCard: View {
    let label: String
    @Binding var value: String
    let suffix: String
    let theme: AppTheme

    var body: some View {
        HStack {
            Text(label)
                .font(.subheadline)
                .foregroundStyle(theme.textSecondary)
                .frame(width: 56, alignment: .leading)
            Spacer()
            TextField("", text: $value)
                .multilineTextAlignment(.trailing)
                .font(.system(size: 18, weight: .light, design: .rounded))
                .foregroundStyle(theme.textPrimary)
                .frame(maxWidth: 120)
            Text(suffix)
                .font(.subheadline)
                .foregroundStyle(theme.textTertiary)
                .frame(width: 32, alignment: .leading)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .glassSurface(cornerRadius: 16, glassAlpha: 0.10, theme: theme)
    }
}

private struct HeightInputCard: View {
    @Binding var heightCm: String
    @Binding var heightFt: String
    @Binding var heightIn: String
    @Binding var unitCm: Bool
    let theme: AppTheme

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Text("身高")
                    .font(.subheadline)
                    .foregroundStyle(theme.textSecondary)
                    .frame(width: 56, alignment: .leading)
                Spacer()
                UnitToggle(options: ["cm", "ft+in"], selected: unitCm ? 0 : 1, theme: theme) {
                    unitCm = ($0 == 0)
                }
            }
            HStack {
                Spacer().frame(width: 56)
                if unitCm {
                    TextField("", text: $heightCm)
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .font(.system(size: 18, weight: .light, design: .rounded))
                        .foregroundStyle(theme.textPrimary)
                    Text("cm").font(.subheadline).foregroundStyle(theme.textTertiary).frame(width: 32, alignment: .leading)
                } else {
                    TextField("", text: $heightFt)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .font(.system(size: 18, weight: .light, design: .rounded))
                        .foregroundStyle(theme.textPrimary)
                    Text("ft").font(.subheadline).foregroundStyle(theme.textTertiary).frame(width: 24, alignment: .leading)
                    TextField("", text: $heightIn)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .font(.system(size: 18, weight: .light, design: .rounded))
                        .foregroundStyle(theme.textPrimary)
                    Text("in").font(.subheadline).foregroundStyle(theme.textTertiary).frame(width: 24, alignment: .leading)
                }
            }
        }
        .padding(12)
        .glassSurface(cornerRadius: 16, glassAlpha: 0.10, theme: theme)
    }
}

private struct WeightInputCard: View {
    @Binding var weightKg: String
    @Binding var weightLb: String
    @Binding var unitKg: Bool
    let theme: AppTheme

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Text("体重")
                    .font(.subheadline)
                    .foregroundStyle(theme.textSecondary)
                    .frame(width: 56, alignment: .leading)
                Spacer()
                UnitToggle(options: ["kg", "lb"], selected: unitKg ? 0 : 1, theme: theme) {
                    unitKg = ($0 == 0)
                }
            }
            HStack {
                Spacer().frame(width: 56)
                TextField("", text: unitKg ? $weightKg : $weightLb)
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.trailing)
                    .font(.system(size: 18, weight: .light, design: .rounded))
                    .foregroundStyle(theme.textPrimary)
                Text(unitKg ? "kg" : "lb")
                    .font(.subheadline)
                    .foregroundStyle(theme.textTertiary)
                    .frame(width: 32, alignment: .leading)
            }
        }
        .padding(12)
        .glassSurface(cornerRadius: 16, glassAlpha: 0.10, theme: theme)
    }
}

private struct UnitToggle: View {
    let options: [String]
    let selected: Int
    let theme: AppTheme
    let onSelect: (Int) -> Void

    var body: some View {
        HStack(spacing: 2) {
            ForEach(Array(options.enumerated()), id: \.offset) { i, opt in
                Text(opt)
                    .font(.caption)
                    .foregroundStyle(i == selected ? FluidCyan : theme.textTertiary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 5)
                    .background(i == selected ? FluidCyan.opacity(0.2) : Color.clear)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .contentShape(Rectangle())
                    .onTapGesture { onSelect(i) }
            }
        }
        .padding(2)
        .glassSurface(cornerRadius: 10, glassAlpha: 0.08, theme: theme)
    }
}

// MARK: - 体脂率 Tab
private extension BMICalculatorScreen {
    func bodyFatTab(theme: AppTheme) -> some View {
        BodyFatTab(theme: theme)
            .padding(.horizontal, 20)
    }
}

private struct BodyFatTab: View {
    let theme: AppTheme

    @State private var genderMale: Bool = true
    @State private var neckText: String = "38"
    @State private var waistText: String = "80"
    @State private var hipText: String = "95"
    @State private var heightText: String = "170"
    @State private var bodyFatResult: BodyFatResult? = nil

    var body: some View {
        VStack(spacing: 12) {
            GenderSelector(genderMale: genderMale, theme: theme) { genderMale = $0 }

            GlassInputCard(label: "颈围", value: $neckText, suffix: "cm", theme: theme)
                .keyboardType(.decimalPad)
            GlassInputCard(label: "腰围", value: $waistText, suffix: "cm", theme: theme)
                .keyboardType(.decimalPad)
            if !genderMale {
                GlassInputCard(label: "臀围", value: $hipText, suffix: "cm", theme: theme)
                    .keyboardType(.decimalPad)
            }
            GlassInputCard(label: "身高", value: $heightText, suffix: "cm", theme: theme)
                .keyboardType(.decimalPad)

            GradientButton(title: "计算体脂率", icon: nil,
                           gradient: [theme.fluidTeal, FluidCyan], theme: theme) {
                let n = Double(neckText) ?? 0
                let w = Double(waistText) ?? 0
                let h = Double(heightText) ?? 0
                let hip = genderMale ? 0.0 : (Double(hipText) ?? 0)
                guard n > 0, w > 0, h > 0, (genderMale || hip > 0) else { return }
                var bf = calcBodyFatNavy(gender: genderMale, heightCm: h, neckCm: n, waistCm: w, hipCm: hip)
                bf = min(60, max(0, bf))
                let (cat, color) = getBodyFatCategory(bf, gender: genderMale)
                bodyFatResult = BodyFatResult(percentage: bf, category: cat, categoryColor: color)
            }

            if let result = bodyFatResult {
                VStack(spacing: 8) {
                    Text("体脂率").font(.caption).foregroundStyle(theme.textTertiary)
                    Text(String(format: "%.1f%%", result.percentage))
                        .font(.system(size: 48, weight: .thin, design: .rounded))
                        .foregroundStyle(result.categoryColor)
                    Text(result.category)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(result.categoryColor)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 6)
                        .background(result.categoryColor.opacity(0.15))
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
                .frame(maxWidth: .infinity)
                .padding(20)
                .glassSurface(cornerRadius: 20, glassAlpha: 0.15, theme: theme)

                // 参考表
                Text("体脂率参考标准")
                    .font(.subheadline)
                    .foregroundStyle(theme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)

                VStack(spacing: 6) {
                    let refs: [(String, String, Color)] = genderMale ? [
                        ("必需脂肪", "2-5%", FluidCyan),
                        ("运动员", "6-13%", FluidTeal),
                        ("健康", "14-17%", AccentSuccess),
                        ("可接受", "18-24%", FluidOrange),
                        ("超重", "25-31%", AccentWarning),
                        ("肥胖", "32%+", AccentDanger)
                    ] : [
                        ("必需脂肪", "10-13%", FluidCyan),
                        ("运动员", "14-20%", FluidTeal),
                        ("健康", "21-24%", AccentSuccess),
                        ("可接受", "25-31%", FluidOrange),
                        ("超重", "32-39%", AccentWarning),
                        ("肥胖", "40%+", AccentDanger)
                    ]
                    ForEach(Array(refs.enumerated()), id: \.offset) { _, ref in
                        HStack {
                            Text(ref.0).font(.caption).foregroundStyle(theme.textSecondary)
                            Spacer()
                            Text(ref.1).font(.caption).foregroundStyle(ref.2)
                        }
                    }
                }
                .padding(12)
                .glassSurface(cornerRadius: 16, glassAlpha: 0.10, theme: theme)
            }

            Spacer(minLength: 12)
        }
    }
}

// MARK: - 卡路里 Tab
private extension BMICalculatorScreen {
    func calorieTab(theme: AppTheme) -> some View {
        CalorieTab(theme: theme)
            .padding(.horizontal, 20)
    }
}

private struct CalorieTab: View {
    let theme: AppTheme

    @State private var genderMale: Bool = true
    @State private var weightText: String = "65"
    @State private var heightText: String = "170"
    @State private var ageText: String = "25"
    @State private var activityLevel: Int = 1
    @State private var calorieResult: CalorieResult? = nil

    private let activities: [(String, String)] = [
        ("久坐不动", "几乎不运动"),
        ("轻度活动", "每周1-2天"),
        ("中度活动", "每周3-5天"),
        ("活跃", "每周6-7天"),
        ("非常活跃", "每天高强度")
    ]

    private var activityLabel: String {
        activities[activityLevel].0 + " (\(activities[activityLevel].1))"
    }

    var body: some View {
        VStack(spacing: 12) {
            GenderSelector(genderMale: genderMale, theme: theme) { genderMale = $0 }

            GlassInputCard(label: "体重", value: $weightText, suffix: "kg", theme: theme)
                .keyboardType(.decimalPad)
            GlassInputCard(label: "身高", value: $heightText, suffix: "cm", theme: theme)
                .keyboardType(.decimalPad)
            GlassInputCard(label: "年龄", value: $ageText, suffix: "岁", theme: theme)
                .keyboardType(.numberPad)

            Text("活动水平")
                .font(.subheadline)
                .foregroundStyle(theme.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)

            VStack(spacing: 4) {
                ForEach(Array(activities.enumerated()), id: \.offset) { i, pair in
                    let selected = activityLevel == i
                    HStack(spacing: 10) {
                        Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(selected ? FluidCyan : theme.glassBorder)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(pair.0)
                                .font(.subheadline)
                                .foregroundStyle(selected ? theme.textPrimary : theme.textSecondary)
                            Text(pair.1)
                                .font(.caption2)
                                .foregroundStyle(theme.textTertiary)
                        }
                        Spacer()
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .glassSurface(cornerRadius: 12,
                                  glassAlpha: selected ? 0.15 : 0.06, theme: theme)
                    .contentShape(Rectangle())
                    .onTapGesture { activityLevel = i }
                }
            }

            GradientButton(title: "计算卡路里", icon: nil,
                           gradient: [FluidOrange, theme.fluidPink], theme: theme) {
                let w = Double(weightText) ?? 0
                let h = Double(heightText) ?? 0
                let a = Int(ageText) ?? 0
                guard w > 0, h > 0, a > 0 else { return }
                let bmr = calcBmrMifflinStJeor(gender: genderMale, weightKg: w, heightCm: h, age: a)
                let daily = bmr * activityMultiplier(activityLevel)
                calorieResult = CalorieResult(
                    bmr: bmr,
                    dailyCalories: daily,
                    protein: daily * 0.30 / 4.0,
                    fat: daily * 0.30 / 9.0,
                    carbs: daily * 0.40 / 4.0
                )
            }

            if let result = calorieResult {
                VStack(spacing: 12) {
                    VStack(spacing: 2) {
                        Text("基础代谢率 (BMR)")
                            .font(.caption).foregroundStyle(theme.textTertiary)
                        Text("\(Int(result.bmr)) kcal/天")
                            .font(.system(size: 28, weight: .thin, design: .rounded))
                            .foregroundStyle(FluidCyan)
                    }
                    VStack(spacing: 2) {
                        Text("每日能量需求")
                            .font(.caption).foregroundStyle(theme.textTertiary)
                        Text("\(Int(result.dailyCalories)) kcal/天")
                            .font(.system(size: 36, weight: .thin, design: .rounded))
                            .foregroundStyle(theme.fluidTeal)
                    }
                    Text("活动水平: \(activityLabel)")
                        .font(.caption2)
                        .foregroundStyle(theme.textTertiary)
                }
                .frame(maxWidth: .infinity)
                .padding(20)
                .glassSurface(cornerRadius: 20, glassAlpha: 0.15, theme: theme)

                Text("宏量营养素建议")
                    .font(.subheadline)
                    .foregroundStyle(theme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)

                HStack(spacing: 0) {
                    MacroNutrientCard(label: "蛋白质", initial: "蛋",
                                      amount: "\(Int(result.protein))g",
                                      calories: "\(Int(result.protein * 4)) kcal",
                                      color: FluidCyan, theme: theme)
                    MacroNutrientCard(label: "脂肪", initial: "脂",
                                      amount: "\(Int(result.fat))g",
                                      calories: "\(Int(result.fat * 9)) kcal",
                                      color: FluidOrange, theme: theme)
                    MacroNutrientCard(label: "碳水", initial: "碳",
                                      amount: "\(Int(result.carbs))g",
                                      calories: "\(Int(result.carbs * 4)) kcal",
                                      color: theme.fluidPurple, theme: theme)
                }
                .padding(16)
                .glassSurface(cornerRadius: 16, glassAlpha: 0.10, theme: theme)

                Text("基于 30%蛋白质 / 30%脂肪 / 40%碳水 的推荐比例。Mifflin-St Jeor 公式计算。")
                    .font(.caption2)
                    .foregroundStyle(theme.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
                    .glassSurface(cornerRadius: 16, glassAlpha: 0.08, theme: theme)
            }

            Spacer(minLength: 12)
        }
    }
}

private struct MacroNutrientCard: View {
    let label: String
    let initial: String
    let amount: String
    let calories: String
    let color: Color
    let theme: AppTheme

    var body: some View {
        VStack(spacing: 6) {
            ZStack {
                Circle().fill(color.opacity(0.15)).frame(width: 36, height: 36)
                Text(initial)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(color)
            }
            Text(amount)
                .font(.system(size: 16, weight: .light, design: .rounded))
                .foregroundStyle(theme.textPrimary)
            Text(calories)
                .font(.caption2)
                .foregroundStyle(theme.textTertiary)
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - 饮水 Tab
private extension BMICalculatorScreen {
    func waterTab(theme: AppTheme) -> some View {
        WaterTab(theme: theme)
            .padding(.horizontal, 20)
    }
}

private struct WaterTab: View {
    let theme: AppTheme

    @State private var weightText: String = "65"
    @State private var waterResult: Double? = nil

    private let tips = [
        "晨起后 1-2 杯水，激活新陈代谢",
        "运动前中后适量补水",
        "少量多次，不要等口渴再喝",
        "饭前半小时饮水有助于消化",
        "睡前 1 小时减少饮水"
    ]

    var body: some View {
        VStack(spacing: 12) {
            GlassInputCard(label: "体重", value: $weightText, suffix: "kg", theme: theme)
                .keyboardType(.decimalPad)

            GradientButton(title: "计算饮水量", icon: "drop.fill",
                           gradient: [theme.fluidBlue, FluidCyan], theme: theme) {
                let w = Double(weightText) ?? 0
                guard w > 0 else { return }
                waterResult = w * 33.0
            }

            if let ml = waterResult {
                VStack(spacing: 4) {
                    Image(systemName: "drop.fill")
                        .font(.system(size: 40))
                        .foregroundStyle(theme.fluidBlue)
                    Text("每日建议饮水量")
                        .font(.caption).foregroundStyle(theme.textTertiary)
                    Text("\(Int(ml)) ml")
                        .font(.system(size: 40, weight: .thin, design: .rounded))
                        .foregroundStyle(FluidCyan)
                    Text("约 \(String(format: "%.1f", ml / 250.0)) 杯 (250ml/杯)")
                        .font(.subheadline).foregroundStyle(theme.textSecondary)
                    Text("约 \(String(format: "%.1f", ml / 1000.0)) 升")
                        .font(.caption).foregroundStyle(theme.textTertiary)
                }
                .frame(maxWidth: .infinity)
                .padding(20)
                .glassSurface(cornerRadius: 20, glassAlpha: 0.15, theme: theme)

                VStack(alignment: .leading, spacing: 6) {
                    Text("💧 饮水建议")
                        .font(.subheadline)
                        .foregroundStyle(theme.textPrimary)
                    ForEach(Array(tips.enumerated()), id: \.offset) { _, tip in
                        Text(tip)
                            .font(.caption)
                            .foregroundStyle(theme.textSecondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .glassSurface(cornerRadius: 16, glassAlpha: 0.10, theme: theme)
            }

            Spacer(minLength: 12)
        }
    }
}

// MARK: - 渐变按钮（与 PasswordGeneratorScreen 一致的视觉规范）
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
            .padding(.vertical, 14)
            .background(
                LinearGradient(colors: gradient, startPoint: .topLeading, endPoint: .bottomTrailing)
            )
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .scaleEffect(pressed ? 0.94 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.5), value: pressed)
        }
        .simultaneousGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in pressed = true }
                .onEnded { _ in pressed = false }
        )
    }
}
