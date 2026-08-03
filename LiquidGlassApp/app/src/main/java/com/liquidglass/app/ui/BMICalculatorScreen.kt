package com.liquidglass.app.ui

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

// ────────────────────────────────────────────────────────────────────
// 数据模型
// ────────────────────────────────────────────────────────────────────

data class BmiRecord(
    val bmi: Float,
    val category: String,
    val date: String,
    val weight: Float,
    val height: Float
)

data class BmiResult(
    val bmi: Float,
    val category: String,
    val categoryColor: Color,
    val idealMinWeight: Float,
    val idealMaxWeight: Float
)

data class BodyFatResult(
    val percentage: Float,
    val category: String,
    val categoryColor: Color
)

data class CalorieResult(
    val bmr: Float,
    val dailyCalories: Float,
    val protein: Float,
    val fat: Float,
    val carbs: Float
)

// ────────────────────────────────────────────────────────────────────
// 工具函数
// ────────────────────────────────────────────────────────────────────

private fun getBmiCategory(bmi: Float): Triple<String, Color, Int> = when {
    bmi < 18.5f -> Triple("偏瘦", FluidCyan, 0)
    bmi < 25.0f -> Triple("正常", FluidTeal, 1)
    bmi < 30.0f -> Triple("超重", FluidOrange, 2)
    bmi < 35.0f -> Triple("肥胖 I 级", AccentWarning, 3)
    bmi < 40.0f -> Triple("肥胖 II 级", AccentDanger, 4)
    else -> Triple("肥胖 III 级", FluidPink, 5)
}

private fun calcBmi(weightKg: Float, heightCm: Float): Float {
    if (heightCm <= 0f) return 0f
    val heightM = heightCm / 100f
    return weightKg / (heightM * heightM)
}

private fun calcIdealWeightRange(heightCm: Float): Pair<Float, Float> {
    val h = heightCm / 100f
    return Pair(18.5f * h * h, 24.9f * h * h)
}

private fun calcBodyFatNavy(gender: Boolean, heightCm: Float, neckCm: Float, waistCm: Float, hipCm: Float): Float {
    if (heightCm <= 0f || neckCm <= 0f || waistCm <= 0f) return 0f
    return if (gender) {
        // 男性
        86.010f * log10(waistCm - neckCm) - 70.041f * log10(heightCm) + 36.76f
    } else {
        // 女性
        if (hipCm <= 0f) return 0f
        163.205f * log10(waistCm + hipCm - neckCm) - 97.684f * log10(heightCm) - 78.387f
    }
}

private fun getBodyFatCategory(bf: Float, gender: Boolean): Pair<String, Color> = when {
    gender -> when {
        bf < 6f -> "必需脂肪" to FluidCyan
        bf < 14f -> "运动员" to FluidTeal
        bf < 18f -> "健康" to AccentSuccess
        bf < 25f -> "可接受" to FluidOrange
        bf < 32f -> "超重" to AccentWarning
        else -> "肥胖" to AccentDanger
    }
    else -> when {
        bf < 14f -> "必需脂肪" to FluidCyan
        bf < 21f -> "运动员" to FluidTeal
        bf < 25f -> "健康" to AccentSuccess
        bf < 32f -> "可接受" to FluidOrange
        bf < 40f -> "超重" to AccentWarning
        else -> "肥胖" to AccentDanger
    }
}

private fun calcBmrMifflinStJeor(gender: Boolean, weightKg: Float, heightCm: Float, age: Int): Float {
    return if (gender) {
        10f * weightKg + 6.25f * heightCm - 5f * age + 5f
    } else {
        10f * weightKg + 6.25f * heightCm - 5f * age - 161f
    }
}

private fun getActivityMultiplier(level: Int): Float = when (level) {
    0 -> 1.2f   // 久坐
    1 -> 1.375f // 轻度活动
    2 -> 1.55f  // 中度活动
    3 -> 1.725f // 活跃
    4 -> 1.9f   // 非常活跃
    else -> 1.2f
}

private fun getActivityLabel(level: Int): String = when (level) {
    0 -> "久坐不动"
    1 -> "轻度活动 (1-2天/周)"
    2 -> "中度活动 (3-5天/周)"
    3 -> "活跃 (6-7天/周)"
    4 -> "非常活跃 (每天高强度)"
    else -> "久坐不动"
}

// ────────────────────────────────────────────────────────────────────
// SharedPreferences 持久化
// ────────────────────────────────────────────────────────────────────

private const val PREFS_KEY = "bmi_history"
private const val PREFS_NAME = "bmi_prefs"

private fun loadBmiHistory(context: Context): List<BmiRecord> {
    return try {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(PREFS_KEY, null) ?: return emptyList()
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            BmiRecord(
                bmi = obj.getDouble("bmi").toFloat(),
                category = obj.getString("category"),
                date = obj.getString("date"),
                weight = obj.getDouble("weight").toFloat(),
                height = obj.getDouble("height").toFloat()
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveBmiHistory(context: Context, records: List<BmiRecord>) {
    try {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (r in records) {
            val obj = JSONObject()
            obj.put("bmi", r.bmi.toDouble())
            obj.put("category", r.category)
            obj.put("date", r.date)
            obj.put("weight", r.weight.toDouble())
            obj.put("height", r.height.toDouble())
            arr.put(obj)
        }
        prefs.edit().putString(PREFS_KEY, arr.toString()).apply()
    } catch (_: Exception) {
    }
}

private fun addBmiRecord(context: Context, record: BmiRecord) {
    val history = loadBmiHistory(context).toMutableList()
    history.add(0, record)
    if (history.size > 50) history.removeAt(history.lastIndex)
    saveBmiHistory(context, history)
}

// ────────────────────────────────────────────────────────────────────
// 主屏幕
// ────────────────────────────────────────────────────────────────────

@Composable
fun BMICalculatorScreen(animTime: Float, onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("BMI", "体脂率", "卡路里", "饮水")

    LiquidGlassScaffold(animTime = animTime) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // 顶栏
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = appTextSecondary())
                }
                Text("健康工具", fontSize = 16.sp, color = appTextSecondary(), modifier = Modifier.weight(1f))
            }

            // Tab 栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.12f)
                    .padding(4.dp)
            ) {
                for ((i, tab) in tabs.withIndex()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .then(
                                if (i == selectedTab) Modifier.background(
                                    Brush.linearGradient(listOf(FluidCyan.copy(alpha = 0.25f), FluidPurple.copy(alpha = 0.15f)))
                                ) else Modifier.background(Color.Transparent)
                            )
                            .clickable { selectedTab = i }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            tab,
                            fontSize = 13.sp,
                            fontWeight = if (i == selectedTab) FontWeight.Medium else FontWeight.Light,
                            color = if (i == selectedTab) TextPrimary else TextTertiary
                        )
                    }
                }
            }

            // 内容区
            when (selectedTab) {
                0 -> BmiTabContent(context)
                1 -> BodyFatTabContent()
                2 -> CalorieTabContent()
                3 -> WaterIntakeContent()
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// BMI 计算器 Tab
// ════════════════════════════════════════════════════════════════════

@Composable
private fun BmiTabContent(context: Context) {
    var genderMale by remember { mutableStateOf(true) }
    var ageText by remember { mutableStateOf("25") }
    var heightCm by remember { mutableStateOf("170") }
    var heightFt by remember { mutableStateOf("5") }
    var heightIn by remember { mutableStateOf("7") }
    var heightUnitCm by remember { mutableStateOf(true) }
    var weightKg by remember { mutableStateOf("65") }
    var weightLb by remember { mutableStateOf("143") }
    var weightUnitKg by remember { mutableStateOf(true) }

    var bmiResult by remember { mutableStateOf<BmiResult?>(null) }
    var bmiHistory by remember { mutableStateOf(loadBmiHistory(context)) }
    var showHistory by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    fun getHeightCm(): Float {
        return if (heightUnitCm) {
            heightCm.toFloatOrNull() ?: 0f
        } else {
            val ft = heightFt.toFloatOrNull() ?: 0f
            val inch = heightIn.toFloatOrNull() ?: 0f
            ft * 30.48f + inch * 2.54f
        }
    }

    fun getWeightKg(): Float {
        return if (weightUnitKg) {
            weightKg.toFloatOrNull() ?: 0f
        } else {
            (weightLb.toFloatOrNull() ?: 0f) * 0.453592f
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // 性别选择
        GenderSelector(genderMale = genderMale, onSelect = { genderMale = it })

        Spacer(modifier = Modifier.height(12.dp))

        // 年龄
        GlassInputCard(label = "年龄", value = ageText, onValue = { if (it.length <= 3) ageText = it.filter { c -> c.isDigit() } }, suffix = "岁", keyboardType = KeyboardType.Number)

        Spacer(modifier = Modifier.height(12.dp))

        // 身高
        HeightInputCard(
            heightCm = heightCm, onHeightCm = { heightCm = it },
            heightFt = heightFt, onHeightFt = { heightFt = it },
            heightIn = heightIn, onHeightIn = { heightIn = it },
            unitCm = heightUnitCm, onToggleUnit = { heightUnitCm = it }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 体重
        WeightInputCard(
            weightKg = weightKg, onWeightKg = { weightKg = it },
            weightLb = weightLb, onWeightLb = { weightLb = it },
            unitKg = weightUnitKg, onToggleUnit = { weightUnitKg = it }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 计算按钮
        var pressedCalc by remember { mutableStateOf(false) }
        val calcScale by animateFloatAsState(if (pressedCalc) 0.94f else 1f, spring(dampingRatio = 0.4f, stiffness = 400f), label = "calc")

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .scale(calcScale)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(FluidCyan, FluidPurple)))
                .clickable {
                    pressedCalc = true
                    val h = getHeightCm()
                    val w = getWeightKg()
                    if (h > 0f && w > 0f) {
                        val bmi = calcBmi(w, h)
                        val (cat, color, _) = getBmiCategory(bmi)
                        val (minW, maxW) = calcIdealWeightRange(h)
                        bmiResult = BmiResult(bmi, cat, color, minW, maxW)
                        addBmiRecord(
                            context,
                            BmiRecord(
                                bmi = bmi,
                                category = cat,
                                date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
                                weight = w,
                                height = h
                            )
                        )
                        bmiHistory = loadBmiHistory(context)
                    }
                }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("计算 BMI", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
        LaunchedEffect(pressedCalc) { if (pressedCalc) { delay(150); pressedCalc = false } }

        Spacer(modifier = Modifier.height(16.dp))

        // BMI 结果
        bmiResult?.let { result ->
            BmiResultCard(result = result)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 历史记录
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("BMI 历史记录", fontSize = 14.sp, color = appTextSecondary(), fontWeight = FontWeight.Light)
            TextButton(onClick = { showHistory = !showHistory }) {
                Text(
                    if (showHistory) "收起" else "展开 (${bmiHistory.size})",
                    fontSize = 12.sp,
                    color = FluidCyan
                )
            }
        }

        if (showHistory && bmiHistory.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.10f)
                    .padding(12.dp)
            ) {
                Column {
                    for ((i, record) in bmiHistory.withIndex()) {
                        if (i > 0) Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    String.format("%.1f", record.bmi),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = getBmiCategory(record.bmi).second
                                )
                                Text(record.date, fontSize = 10.sp, color = appTextTertiary())
                            }
                            Text(record.category, fontSize = 13.sp, color = getBmiCategory(record.bmi).second)
                        }
                        if (i < bmiHistory.size - 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = GlassBorder, thickness = 0.5.dp)
                        }
                    }
                }
            }
        } else if (showHistory) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.10f)
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无记录", fontSize = 13.sp, color = appTextTertiary())
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

// ── BMI 子组件 ──────────────────────────────────────────────────────

@Composable
private fun GenderSelector(genderMale: Boolean, onSelect: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GenderButton(
            icon = Icons.Default.Male,
            label = "男",
            selected = genderMale,
            onClick = { onSelect(true) },
            modifier = Modifier.weight(1f)
        )
        GenderButton(
            icon = Icons.Default.Female,
            label = "女",
            selected = !genderMale,
            onClick = { onSelect(false) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun GenderButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, spring(dampingRatio = 0.4f, stiffness = 350f), label = "gen")

    val borderColor by animateColorAsState(
        if (selected) FluidCyan else GlassBorder,
        animationSpec = tween(300),
        label = "border"
    )
    val bgAlpha = if (selected) 0.20f else 0.08f

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .glassSurface(cornerRadius = 16.dp, glassAlpha = bgAlpha)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                pressed = true
                onClick()
            }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon, label,
                tint = if (selected) FluidCyan else TextTertiary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Light,
                color = if (selected) TextPrimary else TextTertiary
            )
        }
    }
    LaunchedEffect(pressed) { if (pressed) { delay(150); pressed = false } }
}

@Composable
private fun GlassInputCard(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    suffix: String,
    keyboardType: KeyboardType = KeyboardType.Number
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.10f)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = appTextSecondary(), modifier = Modifier.width(56.dp))
            TextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Light,
                    color = appTextPrimary(),
                    textAlign = TextAlign.End
                ),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = FluidCyan
                )
            )
            Text(suffix, fontSize = 13.sp, color = appTextTertiary(), modifier = Modifier.width(32.dp))
        }
    }
}

@Composable
private fun HeightInputCard(
    heightCm: String, onHeightCm: (String) -> Unit,
    heightFt: String, onHeightFt: (String) -> Unit,
    heightIn: String, onHeightIn: (String) -> Unit,
    unitCm: Boolean, onToggleUnit: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.10f)
            .padding(12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("身高", fontSize = 13.sp, color = appTextSecondary(), modifier = Modifier.width(56.dp))
                Spacer(modifier = Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .glassSurface(cornerRadius = 10.dp, glassAlpha = 0.08f)
                        .padding(2.dp)
                ) {
                    UnitToggleChip("cm", selected = unitCm, onClick = { onToggleUnit(true) })
                    UnitToggleChip("ft+in", selected = !unitCm, onClick = { onToggleUnit(false) })
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (unitCm) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(56.dp))
                    TextField(
                        value = heightCm,
                        onValueChange = { if (it.length <= 6) onHeightCm(it.filter { c -> c.isDigit() || c == '.' }) },
                        textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Light, color = appTextPrimary(), textAlign = TextAlign.End),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        colors = textFieldTransparentColors(),
                        modifier = Modifier.weight(1f)
                    )
                    Text("cm", fontSize = 13.sp, color = appTextTertiary(), modifier = Modifier.width(32.dp))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(56.dp))
                    TextField(
                        value = heightFt,
                        onValueChange = { if (it.length <= 2) onHeightFt(it.filter { c -> c.isDigit() }) },
                        textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Light, color = appTextPrimary(), textAlign = TextAlign.End),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = textFieldTransparentColors(),
                        modifier = Modifier.weight(1f)
                    )
                    Text("ft", fontSize = 13.sp, color = appTextTertiary(), modifier = Modifier.width(24.dp))
                    TextField(
                        value = heightIn,
                        onValueChange = { if (it.length <= 2) onHeightIn(it.filter { c -> c.isDigit() }) },
                        textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Light, color = appTextPrimary(), textAlign = TextAlign.End),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = textFieldTransparentColors(),
                        modifier = Modifier.weight(1f)
                    )
                    Text("in", fontSize = 13.sp, color = appTextTertiary(), modifier = Modifier.width(24.dp))
                }
            }
        }
    }
}

@Composable
private fun WeightInputCard(
    weightKg: String, onWeightKg: (String) -> Unit,
    weightLb: String, onWeightLb: (String) -> Unit,
    unitKg: Boolean, onToggleUnit: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.10f)
            .padding(12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("体重", fontSize = 13.sp, color = appTextSecondary(), modifier = Modifier.width(56.dp))
                Spacer(modifier = Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .glassSurface(cornerRadius = 10.dp, glassAlpha = 0.08f)
                        .padding(2.dp)
                ) {
                    UnitToggleChip("kg", selected = unitKg, onClick = { onToggleUnit(true) })
                    UnitToggleChip("lb", selected = !unitKg, onClick = { onToggleUnit(false) })
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.width(56.dp))
                TextField(
                    value = if (unitKg) weightKg else weightLb,
                    onValueChange = { v ->
                        val filtered = v.filter { c -> c.isDigit() || c == '.' }
                        if (filtered.length <= 6) {
                            if (unitKg) onWeightKg(filtered) else onWeightLb(filtered)
                        }
                    },
                    textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Light, color = appTextPrimary(), textAlign = TextAlign.End),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    colors = textFieldTransparentColors(),
                    modifier = Modifier.weight(1f)
                )
                Text(if (unitKg) "kg" else "lb", fontSize = 13.sp, color = appTextTertiary(), modifier = Modifier.width(32.dp))
            }
        }
    }
}

@Composable
private fun UnitToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (selected) Modifier.background(FluidCyan.copy(alpha = 0.2f))
                else Modifier.background(Color.Transparent)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 12.sp, color = if (selected) FluidCyan else TextTertiary)
    }
}

@Composable
private fun textFieldTransparentColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    cursorColor = FluidCyan
)

@Composable
private fun BmiResultCard(result: BmiResult) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.15f)
            .padding(20.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            // BMI 数值
            Text("你的 BMI", fontSize = 13.sp, color = appTextTertiary())
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                String.format("%.1f", result.bmi),
                fontSize = 48.sp,
                fontWeight = FontWeight.Thin,
                color = result.categoryColor
            )

            // 类别标签
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(result.categoryColor.copy(alpha = 0.15f))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(result.category, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = result.categoryColor)
            }

            Spacer(modifier = Modifier.height(20.dp))

            // BMI 分类色条
            BmiCategoryBar(currentBmi = result.bmi)

            Spacer(modifier = Modifier.height(20.dp))

            // 理想体重范围
            Text("理想体重范围", fontSize = 12.sp, color = appTextTertiary())
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${String.format("%.1f", result.idealMinWeight)} - ${String.format("%.1f", result.idealMaxWeight)} kg",
                fontSize = 18.sp,
                fontWeight = FontWeight.Light,
                color = FluidTeal
            )
        }
    }
}

@Composable
private fun BmiCategoryBar(currentBmi: Float) {
    val categories = listOf(
        Triple("<18.5", "偏瘦", FluidCyan),
        Triple("18.5-24.9", "正常", FluidTeal),
        Triple("25-29.9", "超重", FluidOrange),
        Triple("30-34.9", "肥胖I", AccentWarning),
        Triple("35-39.9", "肥胖II", AccentDanger),
        Triple("≥40", "肥胖III", FluidPink)
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        // 色条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            for ((_, _, color) in categories) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(color.copy(alpha = 0.5f))
                )
            }
        }

        // 指示器
        val indicatorPos = when {
            currentBmi < 18.5f -> 0f
            currentBmi < 25f -> 1f + (currentBmi - 18.5f) / 6.4f
            currentBmi < 30f -> 2f + (currentBmi - 25f) / 5f
            currentBmi < 35f -> 3f + (currentBmi - 30f) / 5f
            currentBmi < 40f -> 4f + (currentBmi - 35f) / 5f
            else -> 5f + (minOf(currentBmi, 50f) - 40f) / 10f
        }
        val frac = (indicatorPos / 6f).coerceIn(0f, 1f)

        Spacer(modifier = Modifier.height(6.dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            // 三角形指示器
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = frac)
                    .padding(end = 6.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text("▼", fontSize = 12.sp, color = getBmiCategory(currentBmi).second)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// 体脂率计算器 Tab
// ════════════════════════════════════════════════════════════════════

@Composable
private fun BodyFatTabContent() {
    var genderMale by remember { mutableStateOf(true) }
    var neckText by remember { mutableStateOf("38") }
    var waistText by remember { mutableStateOf("80") }
    var hipText by remember { mutableStateOf("95") }
    var heightText by remember { mutableStateOf("170") }

    var bodyFatResult by remember { mutableStateOf<BodyFatResult?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // 性别选择
        GenderSelector(genderMale = genderMale, onSelect = { genderMale = it })

        Spacer(modifier = Modifier.height(12.dp))

        GlassInputCard(label = "颈围", value = neckText, onValue = { if (it.length <= 5) neckText = it.filter { c -> c.isDigit() || c == '.' } }, suffix = "cm", keyboardType = KeyboardType.Decimal)
        Spacer(modifier = Modifier.height(10.dp))
        GlassInputCard(label = "腰围", value = waistText, onValue = { if (it.length <= 5) waistText = it.filter { c -> c.isDigit() || c == '.' } }, suffix = "cm", keyboardType = KeyboardType.Decimal)
        Spacer(modifier = Modifier.height(10.dp))
        if (!genderMale) {
            GlassInputCard(label = "臀围", value = hipText, onValue = { if (it.length <= 5) hipText = it.filter { c -> c.isDigit() || c == '.' } }, suffix = "cm", keyboardType = KeyboardType.Decimal)
            Spacer(modifier = Modifier.height(10.dp))
        }
        GlassInputCard(label = "身高", value = heightText, onValue = { if (it.length <= 5) heightText = it.filter { c -> c.isDigit() || c == '.' } }, suffix = "cm", keyboardType = KeyboardType.Decimal)

        Spacer(modifier = Modifier.height(16.dp))

        var pressedCalc by remember { mutableStateOf(false) }
        val calcScale by animateFloatAsState(if (pressedCalc) 0.94f else 1f, spring(dampingRatio = 0.4f, stiffness = 400f), label = "bfcalc")

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .scale(calcScale)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(FluidTeal, FluidCyan)))
                .clickable {
                    pressedCalc = true
                    val n = neckText.toFloatOrNull() ?: 0f
                    val w = waistText.toFloatOrNull() ?: 0f
                    val h = heightText.toFloatOrNull() ?: 0f
                    val hip = if (!genderMale) hipText.toFloatOrNull() ?: 0f else 0f
                    if (n > 0f && w > 0f && h > 0f && (genderMale || hip > 0f)) {
                        val bf = calcBodyFatNavy(genderMale, h, n, w, hip)
                        val (cat, color) = getBodyFatCategory(bf, genderMale)
                        bodyFatResult = BodyFatResult(bf.coerceIn(0f, 60f), cat, color)
                    }
                }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("计算体脂率", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
        LaunchedEffect(pressedCalc) { if (pressedCalc) { delay(150); pressedCalc = false } }

        Spacer(modifier = Modifier.height(16.dp))

        // 结果
        bodyFatResult?.let { result ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.15f)
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("体脂率", fontSize = 13.sp, color = appTextTertiary())
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        String.format("%.1f%%", result.percentage),
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Thin,
                        color = result.categoryColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(result.categoryColor.copy(alpha = 0.15f))
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(result.category, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = result.categoryColor)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 体脂率参考表
            Text("体脂率参考标准", fontSize = 13.sp, color = appTextSecondary(), fontWeight = FontWeight.Light)
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.10f)
                    .padding(12.dp)
            ) {
                Column {
                    val refs = if (genderMale) listOf(
                        "必需脂肪" to "2-5%" to FluidCyan,
                        "运动员" to "6-13%" to FluidTeal,
                        "健康" to "14-17%" to AccentSuccess,
                        "可接受" to "18-24%" to FluidOrange,
                        "超重" to "25-31%" to AccentWarning,
                        "肥胖" to "32%+" to AccentDanger
                    ) else listOf(
                        "必需脂肪" to "10-13%" to FluidCyan,
                        "运动员" to "14-20%" to FluidTeal,
                        "健康" to "21-24%" to AccentSuccess,
                        "可接受" to "25-31%" to FluidOrange,
                        "超重" to "32-39%" to AccentWarning,
                        "肥胖" to "40%+" to AccentDanger
                    )
                    for ((ref, color) in refs) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(ref.first, fontSize = 12.sp, color = appTextSecondary())
                            Text(ref.second, fontSize = 12.sp, color = color)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

// ════════════════════════════════════════════════════════════════════
// 卡路里计算器 Tab
// ════════════════════════════════════════════════════════════════════

@Composable
private fun CalorieTabContent() {
    var genderMale by remember { mutableStateOf(true) }
    var weightText by remember { mutableStateOf("65") }
    var heightText by remember { mutableStateOf("170") }
    var ageText by remember { mutableStateOf("25") }
    var activityLevel by remember { mutableIntStateOf(1) }

    var calorieResult by remember { mutableStateOf<CalorieResult?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        GenderSelector(genderMale = genderMale, onSelect = { genderMale = it })

        Spacer(modifier = Modifier.height(12.dp))

        GlassInputCard(label = "体重", value = weightText, onValue = { if (it.length <= 5) weightText = it.filter { c -> c.isDigit() || c == '.' } }, suffix = "kg", keyboardType = KeyboardType.Decimal)
        Spacer(modifier = Modifier.height(10.dp))
        GlassInputCard(label = "身高", value = heightText, onValue = { if (it.length <= 5) heightText = it.filter { c -> c.isDigit() || c == '.' } }, suffix = "cm", keyboardType = KeyboardType.Decimal)
        Spacer(modifier = Modifier.height(10.dp))
        GlassInputCard(label = "年龄", value = ageText, onValue = { if (it.length <= 3) ageText = it.filter { c -> c.isDigit() } }, suffix = "岁", keyboardType = KeyboardType.Number)

        Spacer(modifier = Modifier.height(12.dp))

        // 活动水平选择
        Text("活动水平", fontSize = 13.sp, color = appTextSecondary())
        Spacer(modifier = Modifier.height(8.dp))

        val activityLabels = listOf(
            "久坐不动" to "几乎不运动",
            "轻度活动" to "每周1-2天",
            "中度活动" to "每周3-5天",
            "活跃" to "每周6-7天",
            "非常活跃" to "每天高强度"
        )

        for ((i, pair) in activityLabels.withIndex()) {
            val (label, desc) = pair
            val selected = activityLevel == i
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .glassSurface(cornerRadius = 12.dp, glassAlpha = if (selected) 0.15f else 0.06f)
                    .clickable { activityLevel = i }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        Icon(Icons.Default.CheckCircle, null, tint = FluidCyan, modifier = Modifier.size(18.dp))
                    } else {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .border(1.5.dp, GlassBorder, CircleShape)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(label, fontSize = 13.sp, color = if (selected) TextPrimary else TextSecondary)
                        Text(desc, fontSize = 10.sp, color = appTextTertiary())
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        var pressedCalc by remember { mutableStateOf(false) }
        val calcScale by animateFloatAsState(if (pressedCalc) 0.94f else 1f, spring(dampingRatio = 0.4f, stiffness = 400f), label = "calcalc")

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .scale(calcScale)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(FluidOrange, FluidPink)))
                .clickable {
                    pressedCalc = true
                    val w = weightText.toFloatOrNull() ?: 0f
                    val h = heightText.toFloatOrNull() ?: 0f
                    val a = ageText.toIntOrNull() ?: 0
                    if (w > 0f && h > 0f && a > 0) {
                        val bmr = calcBmrMifflinStJeor(genderMale, w, h, a)
                        val daily = bmr * getActivityMultiplier(activityLevel)
                        calorieResult = CalorieResult(
                            bmr = bmr,
                            dailyCalories = daily,
                            protein = daily * 0.30f / 4f,
                            fat = daily * 0.30f / 9f,
                            carbs = daily * 0.40f / 4f
                        )
                    }
                }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("计算卡路里", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
        LaunchedEffect(pressedCalc) { if (pressedCalc) { delay(150); pressedCalc = false } }

        Spacer(modifier = Modifier.height(16.dp))

        calorieResult?.let { result ->
            // BMR & Daily
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.15f)
                    .padding(20.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("基础代谢率 (BMR)", fontSize = 12.sp, color = appTextTertiary())
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "${result.bmr.toInt()} kcal/天",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Thin,
                        color = FluidCyan
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("每日能量需求", fontSize = 12.sp, color = appTextTertiary())
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "${result.dailyCalories.toInt()} kcal/天",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Thin,
                        color = FluidTeal
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "活动水平: ${getActivityLabel(activityLevel)}",
                        fontSize = 11.sp,
                        color = appTextTertiary()
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 宏量营养素
            Text("宏量营养素建议", fontSize = 13.sp, color = appTextSecondary(), fontWeight = FontWeight.Light)
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.10f)
                    .padding(16.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MacroNutrientCard("蛋白质", "${result.protein.toInt()}g", "${(result.protein * 4).toInt()} kcal", FluidCyan)
                    MacroNutrientCard("脂肪", "${result.fat.toInt()}g", "${(result.fat * 9).toInt()} kcal", FluidOrange)
                    MacroNutrientCard("碳水", "${result.carbs.toInt()}g", "${(result.carbs * 4).toInt()} kcal", FluidPurple)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 比例说明
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.08f)
                    .padding(12.dp)
            ) {
                Text(
                    "基于 30%蛋白质 / 30%脂肪 / 40%碳水 的推荐比例。Mifflin-St Jeor 公式计算。",
                    fontSize = 11.sp,
                    color = appTextTertiary()
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun MacroNutrientCard(label: String, amount: String, calories: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(label.first().toString(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(amount, fontSize = 16.sp, fontWeight = FontWeight.Light, color = appTextPrimary())
        Text(calories, fontSize = 10.sp, color = appTextTertiary())
    }
}

// ════════════════════════════════════════════════════════════════════
// 饮水量计算器
// ════════════════════════════════════════════════════════════════════

@Composable
private fun WaterIntakeContent() {
    var weightText by remember { mutableStateOf("65") }
    var waterResult by remember { mutableStateOf<Float?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        GlassInputCard(
            label = "体重",
            value = weightText,
            onValue = { if (it.length <= 5) weightText = it.filter { c -> c.isDigit() || c == '.' } },
            suffix = "kg",
            keyboardType = KeyboardType.Decimal
        )

        Spacer(modifier = Modifier.height(16.dp))

        var pressedCalc by remember { mutableStateOf(false) }
        val calcScale by animateFloatAsState(if (pressedCalc) 0.94f else 1f, spring(dampingRatio = 0.4f, stiffness = 400f), label = "watercalc")

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .scale(calcScale)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(FluidBlue, FluidCyan)))
                .clickable {
                    pressedCalc = true
                    val w = weightText.toFloatOrNull() ?: 0f
                    if (w > 0f) {
                        waterResult = w * 33f
                    }
                }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WaterDrop, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("计算饮水量", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }
        LaunchedEffect(pressedCalc) { if (pressedCalc) { delay(150); pressedCalc = false } }

        Spacer(modifier = Modifier.height(16.dp))

        waterResult?.let { ml ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.15f)
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        Icons.Default.WaterDrop,
                        null,
                        tint = FluidBlue,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("每日建议饮水量", fontSize = 13.sp, color = appTextTertiary())
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${ml.toInt()} ml",
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Thin,
                        color = FluidCyan
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val cups = ml / 250f
                    Text(
                        "约 ${String.format("%.1f", cups)} 杯 (250ml/杯)",
                        fontSize = 14.sp,
                        color = appTextSecondary()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "约 ${String.format("%.1f", ml / 1000f)} 升",
                        fontSize = 12.sp,
                        color = appTextTertiary()
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 饮水建议
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.10f)
                    .padding(16.dp)
            ) {
                Column {
                    Text("💧 饮水建议", fontSize = 14.sp, color = appTextPrimary(), fontWeight = FontWeight.Light)
                    Spacer(modifier = Modifier.height(10.dp))
                    val tips = listOf(
                        "晨起后 1-2 杯水，激活新陈代谢",
                        "运动前中后适量补水",
                        "少量多次，不要等口渴再喝",
                        "饭前半小时饮水有助于消化",
                        "睡前 1 小时减少饮水"
                    )
                    for (tip in tips) {
                        Text(tip, fontSize = 12.sp, color = appTextSecondary(), modifier = Modifier.padding(vertical = 3.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}