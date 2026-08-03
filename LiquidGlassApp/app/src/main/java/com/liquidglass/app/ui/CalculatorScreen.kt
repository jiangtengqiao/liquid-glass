package com.liquidglass.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.*

data class CalcState(
    val display: String = "0",
    val expression: String = "",
    val firstOperand: Double? = null,
    val operator: String? = null,
    val shouldResetDisplay: Boolean = false,
    val isScientific: Boolean = false,
    val isRadians: Boolean = true,
    val history: List<String> = emptyList(),
    val memory: Double = 0.0,
    val hasMemory: Boolean = false,
    val isSecondFunc: Boolean = false
)

@Composable
fun CalculatorScreen(animTime: Float, onBack: () -> Unit) {
    var state by remember { mutableStateOf(CalcState()) }

    fun onDigit(d: String) {
        if (d == "(" || d == ")") {
            state = if (state.shouldResetDisplay) {
                state.copy(display = d, shouldResetDisplay = false)
            } else {
                if (state.display == "0") state.copy(display = d)
                else state.copy(display = state.display + d)
            }
            return
        }
        state = if (state.shouldResetDisplay || state.display == "0") {
            state.copy(display = d, shouldResetDisplay = false)
        } else {
            if (state.display.length < 15) state.copy(display = state.display + d) else state
        }
    }

    fun onOperator(op: String) {
        val cur = state.display.toDoubleOrNull() ?: return
        val first = state.firstOperand
        state = if (first == null) {
            state.copy(firstOperand = cur, operator = op, shouldResetDisplay = true,
                expression = "${state.display} $op ")
        } else {
            val result = calc(first, cur, state.operator!!)
            val hist = state.history + "${fmt(first)} ${state.operator} ${fmt(cur)} = ${fmt(result)}"
            state.copy(firstOperand = result, operator = op, display = fmt(result),
                shouldResetDisplay = true, expression = "${fmt(result)} $op ", history = hist.takeLast(20))
        }
    }

    fun onEquals() {
        val cur = state.display.toDoubleOrNull() ?: return
        val first = state.firstOperand ?: return
        val op = state.operator ?: return
        val result = calc(first, cur, op)
        val hist = state.history + "${fmt(first)} $op ${fmt(cur)} = ${fmt(result)}"
        state = CalcState(display = fmt(result), expression = "${fmt(first)} $op ${fmt(cur)} =",
            history = hist.takeLast(20), isScientific = state.isScientific, isRadians = state.isRadians,
            memory = state.memory, hasMemory = state.hasMemory)
    }

    fun onClear() { state = state.copy(display = "0", expression = "", firstOperand = null, operator = null, shouldResetDisplay = false) }
    fun onAllClear() { state = CalcState(isScientific = state.isScientific, isRadians = state.isRadians, memory = state.memory, hasMemory = state.hasMemory) }
    fun onDelete() {
        state = if (state.display.length > 1 && !state.shouldResetDisplay) state.copy(display = state.display.dropLast(1))
        else state.copy(display = "0")
    }
    fun onDecimal() {
        if (!state.display.contains(".") && !state.shouldResetDisplay) state = state.copy(display = state.display + ".")
    }
    fun onToggleSign() {
        state = if (state.display.startsWith("-")) state.copy(display = state.display.drop(1))
        else if (state.display != "0") state.copy(display = "-${state.display}") else state
    }
    fun onPercent() {
        val cur = state.display.toDoubleOrNull() ?: return
        state = state.copy(display = fmt(cur / 100.0))
    }

    // 科学计算函数
    fun onUnaryOp(op: (Double) -> Double, opName: String) {
        val cur = state.display.toDoubleOrNull() ?: return
        val result = op(cur)
        val hist = state.history + "$opName(${state.display}) = ${fmt(result)}"
        state = state.copy(display = fmt(result), expression = "$opName(${state.display}) = ${fmt(result)}",
            history = hist.takeLast(20))
    }

    fun onConstant(value: String, label: String) {
        val hist = state.history + "$label = $value"
        state = state.copy(display = value, shouldResetDisplay = true, history = hist.takeLast(20))
    }

    // 记忆功能
    fun onMemoryClear() { state = state.copy(memory = 0.0, hasMemory = false) }
    fun onMemoryRecall() {
        if (state.hasMemory) state = state.copy(display = fmt(state.memory), shouldResetDisplay = true)
    }
    fun onMemoryAdd() {
        val cur = state.display.toDoubleOrNull() ?: return
        state = state.copy(memory = state.memory + cur, hasMemory = true, shouldResetDisplay = true)
    }
    fun onMemorySub() {
        val cur = state.display.toDoubleOrNull() ?: return
        state = state.copy(memory = state.memory - cur, hasMemory = true, shouldResetDisplay = true)
    }
    fun onMemoryStore() {
        val cur = state.display.toDoubleOrNull() ?: return
        state = state.copy(memory = cur, hasMemory = true, shouldResetDisplay = true)
    }

    // 复制结果
    fun onCopy() {
        state = state.copy(history = state.history + "已复制: ${state.display}")
    }

    LiquidGlassScaffold(animTime = animTime) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 8.dp)) {
            // 顶栏
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回", tint = appTextSecondary()) }
                Text("计算器", fontSize = 16.sp, color = appTextSecondary(), modifier = Modifier.weight(1f))
                // 记忆指示器
                if (state.hasMemory) {
                    Text("M", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = FluidCyan,
                        modifier = Modifier.padding(end = 8.dp))
                }
                // 科学/基础切换
                TextButton(onClick = { state = state.copy(isScientific = !state.isScientific) }) {
                    Text(if (state.isScientific) "基础" else "科学", fontSize = 12.sp,
                        color = if (state.isScientific) FluidCyan else TextTertiary)
                }
                if (state.isScientific) {
                    TextButton(onClick = { state = state.copy(isRadians = !state.isRadians) }) {
                        Text(if (state.isRadians) "RAD" else "DEG", fontSize = 12.sp, color = FluidPurple)
                    }
                }
                // 复制按钮
                IconButton(onClick = { onCopy() }) {
                    Icon(Icons.Default.ContentCopy, "复制", tint = appTextTertiary(), modifier = Modifier.size(18.dp))
                }
            }

            // 历史记录
            if (state.history.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                ) {
                    for (h in state.history.takeLast(3)) {
                        Box(
                            modifier = Modifier
                                .glassSurface(cornerRadius = 8.dp, glassAlpha = 0.06f)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(h, fontSize = 9.sp, color = appTextTertiary())
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.01f))

            // 显示区
            Box(
                modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 24.dp, glassAlpha = 0.15f)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                    Text(state.expression, fontSize = 13.sp, color = appTextTertiary(), maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(state.display, fontSize = 32.sp, fontWeight = FontWeight.Thin, color = appTextPrimary(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 记忆按钮行
            if (state.hasMemory || state.isScientific) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val memBtns = listOf(
                        "MC" to AccentDanger to { onMemoryClear() },
                        "MR" to FluidCyan to { onMemoryRecall() },
                        "M+" to AccentSuccess to { onMemoryAdd() },
                        "M-" to AccentWarning to { onMemorySub() },
                        "MS" to FluidPurple to { onMemoryStore() }
                    )
                    for ((pair, action) in memBtns) {
                        val (label, color) = pair
                        SciCalcButton(
                            label = label, color = color,
                            modifier = Modifier.weight(1f),
                            onClick = action
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // 科学计算按键行
            if (state.isScientific) {
                val sciBtnRows = if (state.isSecondFunc) {
                    listOf(
                        listOf("asin" to FluidCyan, "acos" to FluidCyan, "atan" to FluidCyan, "sinh" to FluidPurple, "cosh" to FluidPurple),
                        listOf("∛" to FluidTeal, "x³" to FluidTeal, "10ˣ" to FluidTeal, "2ˣ" to FluidPurple, "abs" to FluidPurple),
                        listOf("π" to FluidPink, "e" to FluidPink, "(" to TextSecondary, ")" to TextSecondary, "EXP" to AccentWarning)
                    )
                } else {
                    listOf(
                        listOf("sin" to FluidCyan, "cos" to FluidCyan, "tan" to FluidCyan, "log" to FluidPurple, "ln" to FluidPurple),
                        listOf("√" to FluidTeal, "x²" to FluidTeal, "xⁿ" to FluidTeal, "1/x" to FluidPurple, "n!" to FluidPurple),
                        listOf("π" to FluidPink, "e" to FluidPink, "(" to TextSecondary, ")" to TextSecondary, "EXP" to AccentWarning)
                    )
                }

                val sciActions = mapOf<String, () -> Unit>(
                    "sin" to { onUnaryOp({ v -> if (state.isRadians) sin(v) else sin(Math.toRadians(v)) }, "sin") },
                    "cos" to { onUnaryOp({ v -> if (state.isRadians) cos(v) else cos(Math.toRadians(v)) }, "cos") },
                    "tan" to { onUnaryOp({ v -> if (state.isRadians) tan(v) else tan(Math.toRadians(v)) }, "tan") },
                    "asin" to { onUnaryOp({ v -> if (state.isRadians) asin(v) else Math.toDegrees(asin(v)) }, "asin") },
                    "acos" to { onUnaryOp({ v -> if (state.isRadians) acos(v) else Math.toDegrees(acos(v)) }, "acos") },
                    "atan" to { onUnaryOp({ v -> if (state.isRadians) atan(v) else Math.toDegrees(atan(v)) }, "atan") },
                    "sinh" to { onUnaryOp({ v -> sinh(v) }, "sinh") },
                    "cosh" to { onUnaryOp({ v -> cosh(v) }, "cosh") },
                    "log" to { onUnaryOp({ v -> log10(v) }, "log") },
                    "ln" to { onUnaryOp({ v -> ln(v) }, "ln") },
                    "√" to { onUnaryOp({ v -> sqrt(v) }, "√") },
                    "∛" to { onUnaryOp({ v -> cbrt(v) }, "∛") },
                    "x²" to { onUnaryOp({ v -> v * v }, "sqr") },
                    "x³" to { onUnaryOp({ v -> v * v * v }, "cube") },
                    "xⁿ" to { onOperator("^") },
                    "10ˣ" to { onUnaryOp({ v -> 10.0.pow(v) }, "10^") },
                    "2ˣ" to { onUnaryOp({ v -> 2.0.pow(v) }, "2^") },
                    "1/x" to { onUnaryOp({ v -> 1.0 / v }, "1/") },
                    "abs" to { onUnaryOp({ v -> abs(v) }, "abs") },
                    "n!" to { onUnaryOp({ v ->
                        if (v < 0 || v != v.toInt().toDouble()) Double.NaN
                        else { var r = 1.0; var n = v.toInt(); while (n > 1) { r *= n; n-- }; r }
                    }, "fact") },
                    "π" to { onConstant(Math.PI.toString(), "π") },
                    "e" to { onConstant(Math.E.toString(), "e") },
                    "(" to { onDigit("(") },
                    ")" to { onDigit(")") },
                    "EXP" to { onOperator("E") }
                )

                for (row in sciBtnRows) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for ((label, color) in row) {
                            SciCalcButton(
                                label = label, color = color,
                                modifier = Modifier.weight(1f),
                                onClick = { sciActions[label]?.invoke() }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                }

                // 第二功能切换
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { state = state.copy(isSecondFunc = !state.isSecondFunc) }) {
                        Text(if (state.isSecondFunc) "1st" else "2nd", fontSize = 11.sp,
                            color = if (state.isSecondFunc) FluidCyan else TextTertiary)
                    }
                }
            }

            // 基础按键
            val btns = listOf(
                listOf("AC" to AccentDanger, "⌫" to TextSecondary, "%" to TextSecondary, "÷" to AccentPrimary),
                listOf("7" to TextPrimary, "8" to TextPrimary, "9" to TextPrimary, "×" to AccentPrimary),
                listOf("4" to TextPrimary, "5" to TextPrimary, "6" to TextPrimary, "−" to AccentPrimary),
                listOf("1" to TextPrimary, "2" to TextPrimary, "3" to TextPrimary, "+" to AccentPrimary),
                listOf("±" to TextSecondary, "0" to TextPrimary, "." to TextPrimary, "=" to TextPrimary)
            )

            val actions = mapOf(
                "AC" to { onAllClear() }, "⌫" to { onDelete() }, "%" to { onPercent() },
                "÷" to { onOperator("/") }, "×" to { onOperator("*") }, "−" to { onOperator("-") },
                "+" to { onOperator("+") }, "=" to { onEquals() }, "." to { onDecimal() },
                "±" to { onToggleSign() }
            )

            for (row in btns) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((label, color) in row) {
                        CalcButton(
                            label = label, textColor = color, isEquals = label == "=",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                actions[label]?.invoke() ?: run { if (label in "0123456789") onDigit(label) }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
            }

            Spacer(modifier = Modifier.weight(0.01f))
        }
    }
}

@Composable
fun CalcButton(label: String, textColor: Color, isEquals: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.35f, stiffness = 500f), label = "btn")

    Box(
        modifier = modifier.aspectRatio(if (isEquals) 1.4f else 1.6f).scale(scale).clip(RoundedCornerShape(16.dp))
            .then(
                if (isEquals) Modifier.background(
                    Brush.linearGradient(listOf(FluidCyan, FluidPurple))
                )
                else Modifier.glassSurface(cornerRadius = 16.dp, glassAlpha = 0.10f, showBorder = true)
            )
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                pressed = true; onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = if (label.length > 1) 13.sp else 17.sp,
            fontWeight = if (isEquals) FontWeight.Bold else FontWeight.Light,
            color = if (isEquals) Color.White else textColor, textAlign = TextAlign.Center)
    }
    LaunchedEffect(pressed) { if (pressed) { delay(100); pressed = false } }
}

@Composable
fun SciCalcButton(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.85f else 1f, spring(dampingRatio = 0.3f, stiffness = 500f), label = "sci")

    Box(
        modifier = modifier.aspectRatio(1.6f).scale(scale).clip(RoundedCornerShape(12.dp))
            .glassSurface(cornerRadius = 12.dp, glassAlpha = 0.08f, showBorder = true)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                pressed = true; onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = if (label.length >= 4) 10.sp else 12.sp,
            fontWeight = FontWeight.Light, color = color, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
    LaunchedEffect(pressed) { if (pressed) { delay(100); pressed = false } }
}

private fun calc(a: Double, b: Double, op: String): Double = when (op) {
    "+" -> a + b
    "−", "-" -> a - b
    "×", "*" -> a * b
    "÷", "/" -> if (b != 0.0) a / b else Double.NaN
    "^" -> a.pow(b)
    "E" -> a * 10.0.pow(b)
    else -> b
}

private fun fmt(v: Double): String {
    if (v.isNaN()) return "错误"
    if (v.isInfinite()) return if (v > 0) "∞" else "-∞"
    if (abs(v) >= 1e15 || (abs(v) < 1e-10 && v != 0.0)) return String.format("%.6e", v)
    return if (v == v.roundToInt().toDouble() && abs(v) < 1e12) v.roundToInt().toString()
    else String.format("%.10f", v).trimEnd('0').trimEnd('.')
}