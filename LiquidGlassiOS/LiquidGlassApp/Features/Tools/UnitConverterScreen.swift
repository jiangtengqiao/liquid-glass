import SwiftUI

// ─────────────────────────────────────────────────────────────────
// 单位换算 —— 对应 Android 端 ui/UnitConverterScreen.kt
//
// 关键实现：
//   1. 多类别单位（长度/重量/温度/面积/体积/速度/数据/时间/压力），
//      每个单位用 toBase 系数定义，通用线性转换 value * from.toBase / to.toBase
//   2. 温度单独走 convertTemperature（摄氏/华氏/开尔文非线性转换）
//   3. 交换按钮：交换 from/to 并记录历史；类别切换时重置输入与索引
//   4. 数字键盘 + 退格 + 清除；自定义遮罩弹层选单位（对应 Android Dialog）
//   5. fmtResult 处理 NaN/Inf/极值/科学计数法，去尾零
//   注：Android 端的「货币」类别依赖实时汇率网络拉取（CurrencyRateStore），
//       iOS 端未引入该网络层，故未移植该单一类别；其余类别完整保留。
// ─────────────────────────────────────────────────────────────────

// MARK: - 单位类别
enum UnitCategory: String, CaseIterable, Identifiable {
    case length, weight, temperature, area, volume, speed, data, time, pressure

    var id: String { rawValue }

    var label: String {
        switch self {
        case .length: return "长度"
        case .weight: return "重量"
        case .temperature: return "温度"
        case .area: return "面积"
        case .volume: return "体积"
        case .speed: return "速度"
        case .data: return "数据"
        case .time: return "时间"
        case .pressure: return "压力"
        }
    }
}

// MARK: - 单位定义
struct UnitDef: Identifiable, Equatable {
    let id = UUID()
    let name: String
    let symbol: String
    let toBase: Double
}

// MARK: - 各类别单位列表（系数与 Android 端完全一致）
private let lengthUnits: [UnitDef] = [
    UnitDef(name: "毫米", symbol: "mm", toBase: 0.001),
    UnitDef(name: "厘米", symbol: "cm", toBase: 0.01),
    UnitDef(name: "米", symbol: "m", toBase: 1.0),
    UnitDef(name: "千米", symbol: "km", toBase: 1000.0),
    UnitDef(name: "英寸", symbol: "inch", toBase: 0.0254),
    UnitDef(name: "英尺", symbol: "foot", toBase: 0.3048),
    UnitDef(name: "码", symbol: "yard", toBase: 0.9144),
    UnitDef(name: "英里", symbol: "mile", toBase: 1609.344)
]

private let weightUnits: [UnitDef] = [
    UnitDef(name: "毫克", symbol: "mg", toBase: 0.000001),
    UnitDef(name: "克", symbol: "g", toBase: 0.001),
    UnitDef(name: "千克", symbol: "kg", toBase: 1.0),
    UnitDef(name: "吨", symbol: "ton", toBase: 1000.0),
    UnitDef(name: "盎司", symbol: "oz", toBase: 0.028349523125),
    UnitDef(name: "磅", symbol: "lb", toBase: 0.45359237),
    UnitDef(name: "英石", symbol: "stone", toBase: 6.35029318)
]

private let temperatureUnits: [UnitDef] = [
    UnitDef(name: "摄氏度", symbol: "°C", toBase: 1.0),
    UnitDef(name: "华氏度", symbol: "°F", toBase: 1.0),
    UnitDef(name: "开尔文", symbol: "K", toBase: 1.0)
]

private let areaUnits: [UnitDef] = [
    UnitDef(name: "平方毫米", symbol: "mm²", toBase: 0.000001),
    UnitDef(name: "平方厘米", symbol: "cm²", toBase: 0.0001),
    UnitDef(name: "平方米", symbol: "m²", toBase: 1.0),
    UnitDef(name: "平方千米", symbol: "km²", toBase: 1000000.0),
    UnitDef(name: "公顷", symbol: "hectare", toBase: 10000.0),
    UnitDef(name: "英亩", symbol: "acre", toBase: 4046.8564224),
    UnitDef(name: "平方英尺", symbol: "sq ft", toBase: 0.09290304),
    UnitDef(name: "平方英寸", symbol: "sq inch", toBase: 0.00064516)
]

private let volumeUnits: [UnitDef] = [
    UnitDef(name: "毫升", symbol: "ml", toBase: 0.001),
    UnitDef(name: "升", symbol: "L", toBase: 1.0),
    UnitDef(name: "立方米", symbol: "m³", toBase: 1000.0),
    UnitDef(name: "加仑(美)", symbol: "gal(US)", toBase: 3.785411784),
    UnitDef(name: "加仑(英)", symbol: "gal(UK)", toBase: 4.54609),
    UnitDef(name: "夸脱", symbol: "qt", toBase: 0.946352946),
    UnitDef(name: "品脱", symbol: "pt", toBase: 0.473176473),
    UnitDef(name: "杯", symbol: "cup", toBase: 0.2365882365),
    UnitDef(name: "液盎司", symbol: "fl oz", toBase: 0.0295735295625),
    UnitDef(name: "汤匙", symbol: "tbsp", toBase: 0.0147867648),
    UnitDef(name: "茶匙", symbol: "tsp", toBase: 0.0049289216)
]

private let speedUnits: [UnitDef] = [
    UnitDef(name: "米/秒", symbol: "m/s", toBase: 1.0),
    UnitDef(name: "千米/时", symbol: "km/h", toBase: 0.27777777778),
    UnitDef(name: "英里/时", symbol: "mph", toBase: 0.44704),
    UnitDef(name: "节", symbol: "knot", toBase: 0.51444444444),
    UnitDef(name: "马赫", symbol: "mach", toBase: 343.0)
]

private let dataUnits: [UnitDef] = [
    UnitDef(name: "比特", symbol: "bit", toBase: 1.0),
    UnitDef(name: "字节", symbol: "byte", toBase: 8.0),
    UnitDef(name: "千字节", symbol: "KB", toBase: 8192.0),
    UnitDef(name: "兆字节", symbol: "MB", toBase: 8388608.0),
    UnitDef(name: "吉字节", symbol: "GB", toBase: 8589934592.0),
    UnitDef(name: "太字节", symbol: "TB", toBase: 8796093022208.0),
    UnitDef(name: "拍字节", symbol: "PB", toBase: 9007199254740992.0)
]

private let timeUnits: [UnitDef] = [
    UnitDef(name: "毫秒", symbol: "ms", toBase: 0.001),
    UnitDef(name: "秒", symbol: "s", toBase: 1.0),
    UnitDef(name: "分钟", symbol: "min", toBase: 60.0),
    UnitDef(name: "小时", symbol: "hour", toBase: 3600.0),
    UnitDef(name: "天", symbol: "day", toBase: 86400.0),
    UnitDef(name: "周", symbol: "week", toBase: 604800.0),
    UnitDef(name: "月", symbol: "month", toBase: 2629800.0),
    UnitDef(name: "年", symbol: "year", toBase: 31557600.0)
]

private let pressureUnits: [UnitDef] = [
    UnitDef(name: "帕斯卡", symbol: "Pa", toBase: 1.0),
    UnitDef(name: "千帕", symbol: "kPa", toBase: 1000.0),
    UnitDef(name: "兆帕", symbol: "MPa", toBase: 1000000.0),
    UnitDef(name: "巴", symbol: "bar", toBase: 100000.0),
    UnitDef(name: "标准大气压", symbol: "atm", toBase: 101325.0),
    UnitDef(name: "毫米汞柱", symbol: "mmHg", toBase: 133.322368),
    UnitDef(name: "磅/平方英寸", symbol: "psi", toBase: 6894.75729)
]

private func getUnits(_ category: UnitCategory) -> [UnitDef] {
    switch category {
    case .length: return lengthUnits
    case .weight: return weightUnits
    case .temperature: return temperatureUnits
    case .area: return areaUnits
    case .volume: return volumeUnits
    case .speed: return speedUnits
    case .data: return dataUnits
    case .time: return timeUnits
    case .pressure: return pressureUnits
    }
}

// MARK: - 转换逻辑
private func convert(value: Double, from: UnitDef, to: UnitDef, category: UnitCategory) -> Double {
    if category == .temperature {
        return convertTemperature(value: value, from: from.symbol, to: to.symbol)
    }
    // 防御空单位兜底：toBase 为 0 或占位符时直接返回原值，避免除零
    if from.toBase == 0.0 || to.toBase == 0.0 ||
        from.symbol == "—" || to.symbol == "—" {
        return value
    }
    let baseValue = value * from.toBase
    return baseValue / to.toBase
}

private func convertTemperature(value: Double, from: String, to: String) -> Double {
    // 先转为摄氏度
    let celsius: Double
    switch from {
    case "°F": celsius = (value - 32.0) * 5.0 / 9.0
    case "K":  celsius = value - 273.15
    default:   celsius = value
    }
    // 再从摄氏度转为目标
    switch to {
    case "°F": return celsius * 9.0 / 5.0 + 32.0
    case "K":  return celsius + 273.15
    default:   return celsius
    }
}

/// 格式化结果（对应 Android fmtResult）：
/// NaN→"错误"，Inf→"±∞"，极值/极小值→科学计数法，普通值去尾零。
private func fmtResult(_ v: Double) -> String {
    if v.isNaN { return "错误" }
    if v.isInfinite { return v > 0 ? "∞" : "-∞" }
    if abs(v) >= 1e15 || (abs(v) < 1e-12 && v != 0.0) {
        return String(format: "%.6e", v)
    }
    var s = String(format: "%.10f", v)
    while s.hasSuffix("0") { s.removeLast() }
    if s.hasSuffix(".") { s.removeLast() }
    return s.count > 16 ? String(format: "%.8e", v) : s
}

// MARK: - 视图模型
final class UnitConverterViewModel: ObservableObject {
    @Published var category: UnitCategory = .length
    @Published var fromUnitIndex: Int = 0
    @Published var toUnitIndex: Int = 1
    @Published var inputValue: String = "1"
    @Published var showFromPicker: Bool = false
    @Published var showToPicker: Bool = false
    @Published var history: [String] = []

    var units: [UnitDef] { getUnits(category) }
    var safeUnits: [UnitDef] {
        units.isEmpty ? [UnitDef(name: "—", symbol: "—", toBase: 1.0)] : units
    }
    var fromUnit: UnitDef { safeUnits[min(fromUnitIndex, safeUnits.count - 1)] }
    var toUnit: UnitDef { safeUnits[min(toUnitIndex, safeUnits.count - 1)] }

    var inputDouble: Double? { Double(inputValue) }

    var result: Double? {
        guard let d = inputDouble else { return nil }
        return convert(value: d, from: fromUnit, to: toUnit, category: category)
    }

    var resultText: String {
        if inputValue.isEmpty { return "0" }
        if let r = result { return fmtResult(r) }
        return "—"
    }

    // MARK: 输入操作
    func onDigit(_ d: String) {
        if inputValue == "0" {
            inputValue = d
        } else if inputValue.count < 15 {
            inputValue += d
        }
    }

    func onDecimal() {
        if !inputValue.contains(".") {
            inputValue += "."
        }
    }

    func onBackspace() {
        if inputValue.count > 1 {
            inputValue.removeLast()
        } else {
            inputValue = "0"
        }
    }

    func onClear() {
        inputValue = "0"
    }

    func onSwap() {
        let fromIdx = fromUnitIndex
        let toIdx = toUnitIndex
        // 添加历史记录（基于交换前的换算结果）+ 保留原输入值
        if let d = inputDouble, let r = result, r.isFinite {
            let record = "\(fmtResult(d)) \(fromUnit.symbol) = \(resultText) \(toUnit.symbol)"
            history.append(record)
            if history.count > 10 { history = Array(history.suffix(10)) }
        }
        fromUnitIndex = toIdx
        toUnitIndex = fromIdx
    }

    func onCategoryChange(_ cat: UnitCategory) {
        let newUnits = getUnits(cat)
        let safeNew = newUnits.isEmpty ? [UnitDef(name: "—", symbol: "—", toBase: 1.0)] : newUnits
        category = cat
        fromUnitIndex = 0
        toUnitIndex = min(1, safeNew.count - 1)
        inputValue = "0"
    }

    func clearHistory() {
        history.removeAll()
    }
}

// MARK: - 主视图
struct UnitConverterScreen: View {
    var onBack: () -> Void

    @StateObject private var vm = UnitConverterViewModel()

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: 0, theme: theme)

            VStack(spacing: 12) {
                topBar(theme: theme)
                categoryTabs(theme: theme)

                ScrollView {
                    VStack(spacing: 10) {
                        fromCard(theme: theme)
                        swapButton(theme: theme)
                        toCard(theme: theme)
                        formulaCard(theme: theme)
                        numberPad(theme: theme)
                        if !vm.history.isEmpty {
                            historySection(theme: theme)
                        }
                    }
                    .padding(.bottom, 24)
                }
            }
            .padding(.top, 50)
            .padding(.horizontal, 20)

            // 单位选择弹层（对应 Android Dialog）
            if vm.showFromPicker {
                unitPickerOverlay(isFrom: true, theme: theme)
            }
            if vm.showToPicker {
                unitPickerOverlay(isFrom: false, theme: theme)
            }
        }
    }

    // MARK: - 顶部栏
    private func topBar(theme: AppTheme) -> some View {
        HStack {
            Button { onBack() } label: {
                Image(systemName: "chevron.left").foregroundStyle(theme.textSecondary)
            }
            Text("单位换算").font(.headline).foregroundStyle(theme.textPrimary)
            Spacer()
            if !vm.history.isEmpty {
                Button {
                    vm.clearHistory()
                } label: {
                    Image(systemName: "trash.slash")
                        .font(.subheadline)
                        .foregroundStyle(theme.textTertiary)
                }
            }
        }
    }

    // MARK: - 类别标签（横向滚动）
    private func categoryTabs(theme: AppTheme) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(UnitCategory.allCases) { cat in
                    let selected = vm.category == cat
                    Button {
                        vm.onCategoryChange(cat)
                    } label: {
                        Text(cat.label)
                            .font(.subheadline.weight(selected ? .medium : .light))
                            .foregroundStyle(selected ? theme.fluidCyan : theme.textTertiary)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .glassSurface(cornerRadius: 12,
                                          glassAlpha: selected ? 0.18 : 0.06,
                                          theme: theme)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.vertical, 4)
        }
    }

    // MARK: - From 输入区
    private func fromCard(theme: AppTheme) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("从").font(.caption2).foregroundStyle(theme.textTertiary)
            HStack(alignment: .center, spacing: 8) {
                Text(vm.inputValue)
                    .font(.system(size: 32, weight: .thin).monospacedDigit())
                    .foregroundStyle(theme.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                Spacer(minLength: 4)
                unitSelectorButton(unit: vm.fromUnit,
                                   expanded: vm.showFromPicker,
                                   isFrom: true,
                                   theme: theme)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .glassSurface(cornerRadius: 20, glassAlpha: 0.15, theme: theme)
    }

    // MARK: - 交换按钮
    private func swapButton(theme: AppTheme) -> some View {
        Button {
            vm.onSwap()
        } label: {
            Image(systemName: "arrow.up.arrow.down")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(theme.fluidCyan)
                .frame(width: 40, height: 40)
                .glassSurface(cornerRadius: 12, glassAlpha: 0.12, theme: theme)
        }
        .buttonStyle(SwapButtonStyle())
    }

    // MARK: - To 结果区
    private func toCard(theme: AppTheme) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("到").font(.caption2).foregroundStyle(theme.textTertiary)
            HStack(alignment: .center, spacing: 8) {
                Text(vm.resultText)
                    .font(.system(size: 32, weight: .thin).monospacedDigit())
                    .foregroundStyle(vm.result != nil ? theme.fluidCyan : theme.textTertiary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                Spacer(minLength: 4)
                unitSelectorButton(unit: vm.toUnit,
                                   expanded: vm.showToPicker,
                                   isFrom: false,
                                   theme: theme)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .glassSurface(cornerRadius: 20, glassAlpha: 0.15, theme: theme)
    }

    // MARK: - 单位选择触发按钮
    private func unitSelectorButton(unit: UnitDef, expanded: Bool,
                                    isFrom: Bool, theme: AppTheme) -> some View {
        Button {
            if isFrom {
                vm.showFromPicker.toggle()
                vm.showToPicker = false
            } else {
                vm.showToPicker.toggle()
                vm.showFromPicker = false
            }
        } label: {
            HStack(spacing: 4) {
                Text(unit.symbol)
                    .font(.subheadline.weight(.light))
                    .foregroundStyle(theme.fluidCyan)
                Image(systemName: expanded ? "chevron.up" : "chevron.down")
                    .font(.caption2)
                    .foregroundStyle(theme.textTertiary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .glassSurface(cornerRadius: 10, glassAlpha: 0.10, theme: theme)
        }
        .buttonStyle(.plain)
    }

    // MARK: - 换算公式详情卡
    @ViewBuilder
    private func formulaCard(theme: AppTheme) -> some View {
        if vm.inputDouble != nil, vm.result != nil {
            let oneResult = convert(value: 1.0, from: vm.fromUnit, to: vm.toUnit, category: vm.category)
            let formula = "1 \(vm.fromUnit.symbol) = \(fmtResult(oneResult)) \(vm.toUnit.symbol)"
            Text(formula)
                .font(.caption)
                .foregroundStyle(theme.textTertiary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .glassSurface(cornerRadius: 14, glassAlpha: 0.08, theme: theme)
        }
    }

    // MARK: - 数字键盘
    private func numberPad(theme: AppTheme) -> some View {
        VStack(spacing: 5) {
            // 清除按钮行（与 Android 端一致：左侧 C 占 1/3，右侧 2/3 留空）
            HStack(spacing: 5) {
                NumPadButton(label: "C", color: AccentDanger, theme: theme) { vm.onClear() }
                Color.clear.frame(maxWidth: .infinity, maxHeight: 60)
                Color.clear.frame(maxWidth: .infinity, maxHeight: 60)
            }
            // 数字行
            padRow(["7", "8", "9"], theme: theme)
            padRow(["4", "5", "6"], theme: theme)
            padRow(["1", "2", "3"], theme: theme)
            HStack(spacing: 5) {
                NumPadButton(label: ".", color: theme.textPrimary, theme: theme) { vm.onDecimal() }
                NumPadButton(label: "0", color: theme.textPrimary, theme: theme) { vm.onDigit("0") }
                NumPadButton(label: "delete.left", color: theme.textSecondary, theme: theme) { vm.onBackspace() }
            }
        }
    }

    private func padRow(_ labels: [String], theme: AppTheme) -> some View {
        HStack(spacing: 5) {
            ForEach(labels, id: \.self) { label in
                NumPadButton(label: label, color: theme.textPrimary, theme: theme) {
                    vm.onDigit(label)
                }
            }
        }
    }

    // MARK: - 换算历史
    private func historySection(theme: AppTheme) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("换算历史")
                .font(.caption)
                .foregroundStyle(theme.textTertiary)
                .padding(.bottom, 2)
            ForEach(Array(vm.history.enumerated()), id: \.offset) { _, record in
                Text(record)
                    .font(.caption)
                    .foregroundStyle(theme.textSecondary)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .glassSurface(cornerRadius: 10, glassAlpha: 0.06, theme: theme)
            }
        }
        .padding(.top, 8)
    }

    // MARK: - 单位选择弹层
    private func unitPickerOverlay(isFrom: Bool, theme: AppTheme) -> some View {
        ZStack {
            // 半透明遮罩
            Color.black.opacity(0.55)
                .ignoresSafeArea()
                .onTapGesture {
                    if isFrom { vm.showFromPicker = false } else { vm.showToPicker = false }
                }

            VStack(alignment: .leading, spacing: 4) {
                Text("选择单位")
                    .font(.caption)
                    .foregroundStyle(theme.textTertiary)
                    .padding(.horizontal, 14)
                    .padding(.top, 12)

                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(Array(safeUnits.enumerated()), id: \.element.id) { idx, unit in
                            let current = isFrom ? vm.fromUnit : vm.toUnit
                            let isSelected = unit == current
                            Button {
                                if isFrom {
                                    vm.fromUnitIndex = idx
                                    vm.showFromPicker = false
                                } else {
                                    vm.toUnitIndex = idx
                                    vm.showToPicker = false
                                }
                            } label: {
                                HStack {
                                    Text(unit.name)
                                        .font(.subheadline.weight(isSelected ? .medium : .regular))
                                        .foregroundStyle(isSelected ? theme.fluidCyan : theme.textSecondary)
                                    Spacer()
                                    Text(unit.symbol)
                                        .font(.caption)
                                        .foregroundStyle(theme.textTertiary)
                                }
                                .padding(.horizontal, 14)
                                .padding(.vertical, 10)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .frame(maxHeight: 380)
            .padding(.bottom, 8)
            .padding(.horizontal, 24)
            .background(theme.bgDark2.opacity(0.98),
                        in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .glassSurface(cornerRadius: 16, glassAlpha: 0.30, theme: theme)
        }
        .transition(.opacity)
    }

    /// safeUnits 仅在弹层展开时使用，避免空类别时取值越界。
    private var safeUnits: [UnitDef] { vm.safeUnits }
}

// MARK: - 数字键盘按钮
private struct NumPadButton: View {
    let label: String
    let color: Color
    let theme: AppTheme
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Group {
                if label == "delete.left" {
                    Image(systemName: "delete.left")
                        .font(.title3)
                } else {
                    Text(label)
                        .font(.system(size: label.count > 1 ? 14 : 22, weight: .light))
                }
            }
            .foregroundStyle(color)
            .frame(maxWidth: .infinity)
            .frame(height: 60)
            .glassSurface(cornerRadius: 14, glassAlpha: 0.10, theme: theme)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 交换按钮弹性按压样式
private struct SwapButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.85 : 1.0)
            .animation(.spring(response: 0.25, dampingFraction: 0.4), value: configuration.isPressed)
    }
}
