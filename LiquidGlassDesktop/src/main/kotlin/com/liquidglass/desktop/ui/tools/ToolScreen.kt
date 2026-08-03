package com.liquidglass.desktop.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.liquidglass.desktop.ui.GlassCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

/**
 * 工具详情宿主：玻璃标题栏 + 按类型分派具体工具实现。
 * 桌面端保留：计算器 / 倒计时 / 待办 / 记事本 / 密码生成器 / 单位换算；
 * 其余工具在桌面端逐步移植（当前显示建设中占位）。
 */
@Composable
fun ToolScreen(tool: ToolType, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 玻璃标题栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onBack) { Text("返回") }
            Spacer(Modifier.width(12.dp))
            Text(
                text = tool.label,
                style = MaterialTheme.typography.h6,
                color = Color(tool.color)
            )
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when (tool) {
                ToolType.Calculator -> CalculatorTool()
                ToolType.Countdown -> CountdownTool()
                ToolType.Todo -> TodoTool()
                ToolType.Note -> NoteTool()
                ToolType.Password -> PasswordTool()
                ToolType.Converter -> ConverterTool()
                else -> BuildingPlaceholder(tool.label)
            }
        }
    }
}

@Composable
private fun BuildingPlaceholder(name: String) {
    GlassCard(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("「$name」桌面端建设中，敬请期待", color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
        }
    }
}

// ===================== 科学计算器 =====================

@Composable
private fun CalculatorTool() {
    var expression by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    val keys = listOf(
        "7", "8", "9", "/",
        "4", "5", "6", "*",
        "1", "2", "3", "-",
        "0", ".", "C", "+",
        "(", ")", "=", "⌫",
    )
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(expression.ifBlank { "0" }, style = MaterialTheme.typography.h5)
                Text(result, style = MaterialTheme.typography.h6, color = MaterialTheme.colors.primary)
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            keys.chunked(4).forEach { rowKeys ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowKeys.forEach { k ->
                        Button(
                            onClick = {
                                when (k) {
                                    "C" -> { expression = ""; result = "" }
                                    "⌫" -> expression = expression.dropLast(1)
                                    "=" -> result = runCatching {
                                        val expr = expression.replace("×", "*").replace("÷", "/")
                                        val r = evaluateSimple(expr)
                                        r
                                    }.getOrElse { "错误" }
                                    else -> expression += k
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(k) }
                    }
                }
            }
        }
    }
}

private fun evaluateSimple(expr: String): String {
    // 极简四则运算求值（支持 + - * / 与括号，无第三方库）
    val tokens = expr.replace(" ", "")
    fun parse(s: String, i: Int): Pair<Double, Int> {
        var idx = i
        fun skip() { while (idx < s.length && s[idx] == ' ') idx++ }
        skip()
        var sign = 1.0
        if (idx < s.length && (s[idx] == '+' || s[idx] == '-')) {
            if (s[idx] == '-') sign = -1.0
            idx++
            skip()
        }
        var v = 0.0
        if (idx < s.length && s[idx] == '(') {
            val (inner, ni) = parse(s, idx + 1)
            v = inner; idx = ni
        } else {
            val start = idx
            while (idx < s.length && (s[idx].isDigit() || s[idx] == '.')) idx++
            v = s.substring(start, idx).toDoubleOrNull() ?: 0.0
        }
        while (idx < s.length) {
            when (s[idx]) {
                '+' -> { val (r, ni) = parse(s, idx + 1); v += r; idx = ni }
                '-' -> { val (r, ni) = parse(s, idx + 1); v -= r; idx = ni }
                '*' -> { val (r, ni) = parse(s, idx + 1); v *= r; idx = ni }
                '/' -> { val (r, ni) = parse(s, idx + 1); v /= r; idx = ni }
                ')' -> return v * sign to idx + 1
                else -> idx++
            }
        }
        return v * sign to idx
    }
    return runCatching {
        val (v, _) = parse(tokens, 0)
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.6f".format(v).trimEnd('0').trimEnd('.')
    }.getOrElse { "错误" }
}

// ===================== 倒计时 =====================

@Composable
private fun CountdownTool() {
    var seconds by remember { mutableStateOf("300") }
    var remaining by remember { mutableStateOf(0L) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "%02d:%02d".format(remaining / 60, remaining % 60),
                    style = MaterialTheme.typography.h1,
                    color = Color(0xFF3366FF)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = seconds,
                    onValueChange = { seconds = it.filter(Char::isDigit).take(6) },
                    label = { Text("倒计时秒数") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (!running) {
                            remaining = seconds.toLongOrNull() ?: 0L
                            running = true
                            scope.launch {
                                while (remaining > 0) {
                                    delay(1000)
                                    remaining--
                                }
                                running = false
                            }
                        }
                    }) { Text(if (running) "计时中…" else "开始") }
                    OutlinedButton(onClick = { running = false; remaining = 0 }) { Text("重置") }
                }
            }
        }
    }
}

// ===================== 待办清单 =====================

@Composable
private fun TodoTool() {
    var input by remember { mutableStateOf("") }
    var todos by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("新任务") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                if (input.isNotBlank()) {
                    todos = todos + (input.trim() to false)
                    input = ""
                }
            }) { Text("添加") }
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            todos.forEachIndexed { i, (text, done) ->
                Row(
                    Modifier.fillMaxWidth().clickable { todos = todos.mapIndexed { j, t -> if (j == i) t.first to !t.second else t } },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (done) "✓ " else "○ ", color = if (done) Color(0xFF00E5A0) else Color.Gray)
                    Text(text, textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                    Spacer(Modifier.weight(1f))
                    Text("删除", color = Color(0xFFFF6B35), modifier = Modifier.clickable { todos = todos.filterIndexed { j, _ -> j != i } })
                }
            }
            if (todos.isEmpty()) Text("暂无任务，添加一个吧", color = Color.Gray)
        }
    }
}

// ===================== 记事本 =====================

@Composable
private fun NoteTool() {
    val file = remember { File(System.getProperty("user.home"), "LiquidGlass-notes.txt") }
    var text by remember { mutableStateOf(runCatching { file.readText() }.getOrDefault("")) }
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; saved = false },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                runCatching { file.writeText(text) }
                saved = true
            }) { Text("保存到本地") }
            if (saved) Text("已保存", color = Color(0xFF00E5A0))
        }
    }
}

// ===================== 密码生成器 =====================

@Composable
private fun PasswordTool() {
    var length by remember { mutableStateOf(16) }
    var includeSymbols by remember { mutableStateOf(true) }
    var generated by remember { mutableStateOf("") }
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789" +
        (if (includeSymbols) "!@#\$%^&*()-_=+[]{};:,.?" else "")
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            Text(
                text = generated.ifBlank { "点击生成密码" },
                style = MaterialTheme.typography.h6,
                modifier = Modifier.padding(12.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("长度 $length")
            Button(onClick = { length = (length - 1).coerceAtLeast(8) }) { Text("-") }
            Button(onClick = { length = (length + 1).coerceAtMost(64) }) { Text("+") }
        }
        Button(onClick = { includeSymbols = !includeSymbols }) {
            Text(if (includeSymbols) "包含符号: 开" else "包含符号: 关")
        }
        Button(onClick = {
            generated = (1..length).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        }) { Text("生成") }
    }
}

// ===================== 单位换算 =====================

@Composable
private fun ConverterTool() {
    val units = listOf(
        "长度" to listOf("米" to 1.0, "千米" to 1000.0, "厘米" to 0.01, "毫米" to 0.001, "英尺" to 0.3048, "英寸" to 0.0254),
        "重量" to listOf("千克" to 1.0, "克" to 0.001, "吨" to 1000.0, "磅" to 0.45359237, "盎司" to 0.0283495231),
        "温度" to null,
    )
    var category by remember { mutableStateOf(0) }
    var from by remember { mutableStateOf(0) }
    var to by remember { mutableStateOf(1) }
    var value by remember { mutableStateOf("1") }
    var result by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            units.forEachIndexed { i, (name, _) ->
                Button(onClick = { category = i; from = 0; to = 1; result = "" }) { Text(name) }
            }
        }
        val table = units[category].second
        if (table != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column {
                    Text("从")
                    table.forEachIndexed { i, (name, _) ->
                        Text(name, color = if (i == from) Color(0xFF00E5A0) else Color.Gray,
                            modifier = Modifier.clickable { from = i })
                    }
                }
                Column {
                    Text("到")
                    table.forEachIndexed { i, (name, _) ->
                        Text(name, color = if (i == to) Color(0xFF3366FF) else Color.Gray,
                            modifier = Modifier.clickable { to = i })
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(value = value, onValueChange = { value = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("数值") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Button(onClick = {
                val v = value.toDoubleOrNull() ?: 0.0
                result = "%.4f".format(v * table[from].second / table[to].second)
            }) { Text("换算") }
            if (result.isNotBlank()) Text("结果: $result", style = MaterialTheme.typography.h5)
        } else {
            Text("温度换算（摄氏/华氏/开氏）", color = Color.Gray)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = value, onValueChange = { value = it.filter { c -> c.isDigit() || c == '-' || c == '.' } },
                    label = { Text("摄氏温度") }, singleLine = true)
                Button(onClick = {
                    val c = value.toDoubleOrNull() ?: 0.0
                    result = "华氏 %.2f°F  开氏 %.2fK".format(c * 9 / 5 + 32, c + 273.15)
                }) { Text("转换") }
            }
            if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.h5)
        }
    }
}
