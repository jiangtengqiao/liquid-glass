import SwiftUI

// ─────────────────────────────────────────────────────────────────
// 计算器 —— 对应 Android 端 ui/CalculatorScreen.kt
//
// 关键实现：
//   1. 链式计算：连续输入运算符时自动结算中间结果
//      （5 + 3 × → 输入 × 时先算 5+3=8 作为新首操作数）
//   2. = 后按运算符：从结果继续计算（shouldResetDisplay 控制下次数字输入清屏）
//   3. ± 符号切换、% 百分比（÷100）、C 清除、. 小数输入
//   4. 格式化：整数去小数点，浮点保留有效位去尾零；除零返回"错误"
// ─────────────────────────────────────────────────────────────────

struct CalculatorScreen: View {
    var onBack: () -> Void

    @State private var display: String = "0"
    @State private var firstOperand: Double?
    @State private var operatorSymbol: String?
    @State private var shouldResetDisplay: Bool = false
    @State private var expression: String = ""

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: 0, theme: theme)

            VStack(spacing: 16) {
                topBar(theme: theme)

                displayArea(theme: theme)

                Spacer(minLength: 0)

                buttonGrid(theme: theme)
            }
            .padding(.top, 50)
            .padding(.bottom, 24)
            .padding(.horizontal, 16)
        }
    }

    // MARK: - 顶部栏
    private func topBar(theme: AppTheme) -> some View {
        HStack {
            Button { onBack() } label: {
                Image(systemName: "chevron.left").foregroundStyle(theme.textSecondary)
            }
            Text("计算器").font(.headline).foregroundStyle(theme.textPrimary)
            Spacer()
        }
        .padding(.horizontal, 0)
    }

    // MARK: - 显示区
    private func displayArea(theme: AppTheme) -> some View {
        VStack(alignment: .trailing, spacing: 8) {
            Text(expression.isEmpty ? " " : expression)
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(theme.textTertiary)
                .frame(maxWidth: .infinity, alignment: .trailing)
            Text(display)
                .font(.system(size: 56, weight: .bold, design: .rounded).monospacedDigit())
                .foregroundStyle(theme.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.4)
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(20)
        .glassSurface(cornerRadius: 24, theme: theme)
    }

    // MARK: - 按钮网格
    private func buttonGrid(theme: AppTheme) -> some View {
        VStack(spacing: 12) {
            // 第 1 行: 7 8 9 ÷
            HStack(spacing: 12) {
                CalcButton(title: "7", color: theme.textPrimary, theme: theme) { inputDigit("7") }
                CalcButton(title: "8", color: theme.textPrimary, theme: theme) { inputDigit("8") }
                CalcButton(title: "9", color: theme.textPrimary, theme: theme) { inputDigit("9") }
                CalcButton(title: "÷", color: FluidOrange, theme: theme) { inputOperator("÷") }
            }
            // 第 2 行: 4 5 6 ×
            HStack(spacing: 12) {
                CalcButton(title: "4", color: theme.textPrimary, theme: theme) { inputDigit("4") }
                CalcButton(title: "5", color: theme.textPrimary, theme: theme) { inputDigit("5") }
                CalcButton(title: "6", color: theme.textPrimary, theme: theme) { inputDigit("6") }
                CalcButton(title: "×", color: FluidOrange, theme: theme) { inputOperator("×") }
            }
            // 第 3 行: 1 2 3 −
            HStack(spacing: 12) {
                CalcButton(title: "1", color: theme.textPrimary, theme: theme) { inputDigit("1") }
                CalcButton(title: "2", color: theme.textPrimary, theme: theme) { inputDigit("2") }
                CalcButton(title: "3", color: theme.textPrimary, theme: theme) { inputDigit("3") }
                CalcButton(title: "−", color: FluidOrange, theme: theme) { inputOperator("−") }
            }
            // 第 4 行: 0 . = +
            HStack(spacing: 12) {
                CalcButton(title: "0", color: theme.textPrimary, theme: theme) { inputDigit("0") }
                CalcButton(title: ".", color: theme.textPrimary, theme: theme) { inputDecimal() }
                CalcButton(title: "=", color: FluidCyan, theme: theme) { inputEquals() }
                CalcButton(title: "+", color: FluidOrange, theme: theme) { inputOperator("+") }
            }
            // 第 5 行: C ± % (第 4 列留空以保持网格对齐)
            HStack(spacing: 12) {
                CalcButton(title: "C", color: AccentDanger, theme: theme) { inputClear() }
                CalcButton(title: "±", color: theme.fluidPurple, theme: theme) { inputToggleSign() }
                CalcButton(title: "%", color: theme.fluidPurple, theme: theme) { inputPercent() }
                Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    // MARK: - 计算逻辑
    private func inputDigit(_ d: String) {
        if shouldResetDisplay || display == "0" {
            display = d
            shouldResetDisplay = false
        } else {
            if display.count < 15 {
                display += d
            }
        }
    }

    private func inputDecimal() {
        if shouldResetDisplay {
            display = "0."
            shouldResetDisplay = false
        } else if !display.contains(".") {
            display += "."
        }
    }

    private func inputOperator(_ op: String) {
        let current = displayValue()
        if let first = firstOperand, let prevOp = operatorSymbol, !shouldResetDisplay {
            // 链式计算：先结算中间结果，再以结果为新首操作数
            let result = calculate(first, current, prevOp)
            firstOperand = result
            operatorSymbol = op
            display = formatResult(result)
            expression = "\(formatResult(result)) \(op)"
            shouldResetDisplay = true
        } else if firstOperand == nil {
            // 首次输入运算符或 = 后继续：以当前显示为首操作数
            firstOperand = current
            operatorSymbol = op
            expression = "\(formatResult(current)) \(op)"
            shouldResetDisplay = true
        } else {
            // 仅切换运算符（shouldResetDisplay 为 true，未输入新操作数）
            operatorSymbol = op
            if let first = firstOperand {
                expression = "\(formatResult(first)) \(op)"
            }
        }
    }

    private func inputEquals() {
        guard let first = firstOperand, let op = operatorSymbol else { return }
        let current = displayValue()
        let result = calculate(first, current, op)
        expression = "\(formatResult(first)) \(op) \(formatResult(current)) ="
        display = formatResult(result)
        firstOperand = nil
        operatorSymbol = nil
        shouldResetDisplay = true
    }

    private func inputClear() {
        display = "0"
        firstOperand = nil
        operatorSymbol = nil
        shouldResetDisplay = false
        expression = ""
    }

    private func inputToggleSign() {
        if display == "0" || display == "错误" { return }
        if display.hasPrefix("-") {
            display = String(display.dropFirst())
        } else {
            display = "-" + display
        }
    }

    private func inputPercent() {
        let current = displayValue()
        display = formatResult(current / 100.0)
        shouldResetDisplay = true
    }

    // MARK: - 运算与格式化
    private func displayValue() -> Double {
        var s = display
        // 去除尾部小数点（如 "5."），保证 Double 能解析
        if s.hasSuffix(".") { s = String(s.dropLast()) }
        return Double(s) ?? 0
    }

    private func calculate(_ a: Double, _ b: Double, _ op: String) -> Double {
        switch op {
        case "+": return a + b
        case "−": return a - b
        case "×": return a * b
        case "÷": return b == 0 ? .infinity : a / b
        default: return b
        }
    }

    private func formatResult(_ value: Double) -> String {
        if value.isNaN || value.isInfinite { return "错误" }
        if value.truncatingRemainder(dividingBy: 1) == 0 {
            return String(format: "%.0f", value)
        }
        // 最多 10 位有效数字，自动去除尾随零
        return String(format: "%.10g", value)
    }
}

// MARK: - 计算器按钮子视图
private struct CalcButton: View {
    let title: String
    let color: Color
    let theme: AppTheme
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.title2.weight(.semibold))
                .foregroundStyle(color)
                .frame(maxWidth: .infinity)
                .frame(height: 72)
                .glassSurface(cornerRadius: 16, glassAlpha: 0.15, theme: theme)
        }
    }
}
