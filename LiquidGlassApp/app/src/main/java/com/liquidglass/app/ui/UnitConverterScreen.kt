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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

// ── 单位类别 ──────────────────────────────────────────────────
enum class UnitCategory(val label: String, val labelEn: String) {
    LENGTH("长度", "Length"),
    WEIGHT("重量", "Weight"),
    TEMPERATURE("温度", "Temperature"),
    AREA("面积", "Area"),
    VOLUME("体积", "Volume"),
    SPEED("速度", "Speed"),
    DATA("数据", "Data"),
    TIME("时间", "Time"),
    CURRENCY("货币", "Currency"),
    PRESSURE("压力", "Pressure")
}

// ── 单位定义 ──────────────────────────────────────────────────
data class UnitDef(
    val name: String,
    val symbol: String,
    val toBase: Double // 乘以此系数得到基准单位值
)

// ── 转换器状态 ────────────────────────────────────────────────
data class ConvState(
    val category: UnitCategory = UnitCategory.LENGTH,
    val fromUnitIndex: Int = 0,
    val toUnitIndex: Int = 1,
    val inputValue: String = "1",
    val showFromPicker: Boolean = false,
    val showToPicker: Boolean = false,
    val history: List<String> = emptyList()
)

// ── 各单位类别的单位列表 ───────────────────────────────────────
val lengthUnits = listOf(
    UnitDef("毫米", "mm", 0.001),
    UnitDef("厘米", "cm", 0.01),
    UnitDef("米", "m", 1.0),
    UnitDef("千米", "km", 1000.0),
    UnitDef("英寸", "inch", 0.0254),
    UnitDef("英尺", "foot", 0.3048),
    UnitDef("码", "yard", 0.9144),
    UnitDef("英里", "mile", 1609.344)
)

val weightUnits = listOf(
    UnitDef("毫克", "mg", 0.000001),
    UnitDef("克", "g", 0.001),
    UnitDef("千克", "kg", 1.0),
    UnitDef("吨", "ton", 1000.0),
    UnitDef("盎司", "oz", 0.028349523125),
    UnitDef("磅", "lb", 0.45359237),
    UnitDef("英石", "stone", 6.35029318)
)

val temperatureUnits = listOf(
    UnitDef("摄氏度", "°C", 1.0),
    UnitDef("华氏度", "°F", 1.0),
    UnitDef("开尔文", "K", 1.0)
)

val areaUnits = listOf(
    UnitDef("平方毫米", "mm²", 0.000001),
    UnitDef("平方厘米", "cm²", 0.0001),
    UnitDef("平方米", "m²", 1.0),
    UnitDef("平方千米", "km²", 1000000.0),
    UnitDef("公顷", "hectare", 10000.0),
    UnitDef("英亩", "acre", 4046.8564224),
    UnitDef("平方英尺", "sq ft", 0.09290304),
    UnitDef("平方英寸", "sq inch", 0.00064516)
)

val volumeUnits = listOf(
    UnitDef("毫升", "ml", 0.001),
    UnitDef("升", "L", 1.0),
    UnitDef("立方米", "m³", 1000.0),
    UnitDef("加仑(美)", "gal(US)", 3.785411784),
    UnitDef("加仑(英)", "gal(UK)", 4.54609),
    UnitDef("夸脱", "qt", 0.946352946),
    UnitDef("品脱", "pt", 0.473176473),
    UnitDef("杯", "cup", 0.2365882365),
    UnitDef("液盎司", "fl oz", 0.0295735295625),
    UnitDef("汤匙", "tbsp", 0.0147867648),
    UnitDef("茶匙", "tsp", 0.0049289216)
)

val speedUnits = listOf(
    UnitDef("米/秒", "m/s", 1.0),
    UnitDef("千米/时", "km/h", 0.27777777778),
    UnitDef("英里/时", "mph", 0.44704),
    UnitDef("节", "knot", 0.51444444444),
    UnitDef("马赫", "mach", 343.0)
)

val dataUnits = listOf(
    UnitDef("比特", "bit", 1.0),
    UnitDef("字节", "byte", 8.0),
    UnitDef("千字节", "KB", 8192.0),
    UnitDef("兆字节", "MB", 8388608.0),
    UnitDef("吉字节", "GB", 8589934592.0),
    UnitDef("太字节", "TB", 8796093022208.0),
    UnitDef("拍字节", "PB", 9007199254740992.0)
)

val timeUnits = listOf(
    UnitDef("毫秒", "ms", 0.001),
    UnitDef("秒", "s", 1.0),
    UnitDef("分钟", "min", 60.0),
    UnitDef("小时", "hour", 3600.0),
    UnitDef("天", "day", 86400.0),
    UnitDef("周", "week", 604800.0),
    UnitDef("月", "month", 2629800.0),
    UnitDef("年", "year", 31557600.0)
)

// 货币汇率由 CurrencyRateStore 动态提供（实时拉取 + 24h 缓存 + 默认兜底）

val pressureUnits = listOf(
    UnitDef("帕斯卡", "Pa", 1.0),
    UnitDef("千帕", "kPa", 1000.0),
    UnitDef("兆帕", "MPa", 1000000.0),
    UnitDef("巴", "bar", 100000.0),
    UnitDef("标准大气压", "atm", 101325.0),
    UnitDef("毫米汞柱", "mmHg", 133.322368),
    UnitDef("磅/平方英寸", "psi", 6894.75729)
)

fun getUnits(category: UnitCategory): List<UnitDef> = when (category) {
    UnitCategory.LENGTH -> lengthUnits
    UnitCategory.WEIGHT -> weightUnits
    UnitCategory.TEMPERATURE -> temperatureUnits
    UnitCategory.AREA -> areaUnits
    UnitCategory.VOLUME -> volumeUnits
    UnitCategory.SPEED -> speedUnits
    UnitCategory.DATA -> dataUnits
    UnitCategory.TIME -> timeUnits
    UnitCategory.CURRENCY -> CurrencyRateStore.current.value
    UnitCategory.PRESSURE -> pressureUnits
}

// ── 转换逻辑 ──────────────────────────────────────────────────
fun convert(
    value: Double,
    fromUnit: UnitDef,
    toUnit: UnitDef,
    category: UnitCategory
): Double {
    if (category == UnitCategory.TEMPERATURE) {
        return convertTemperature(value, fromUnit.symbol, toUnit.symbol)
    }
    // 防御空单位兜底：toBase 为 0 或占位符时直接返回原值，避免除零/无意义转换
    if (fromUnit.toBase == 0.0 || toUnit.toBase == 0.0 ||
        fromUnit.symbol == "—" || toUnit.symbol == "—") {
        return value
    }
    // 通用线性转换: value * fromUnit.toBase / toUnit.toBase
    val baseValue = value * fromUnit.toBase
    return baseValue / toUnit.toBase
}

fun convertTemperature(value: Double, from: String, to: String): Double {
    // 先转为摄氏度
    val celsius = when (from) {
        "°F" -> (value - 32.0) * 5.0 / 9.0
        "K" -> value - 273.15
        else -> value
    }
    // 再从摄氏度转为目标
    return when (to) {
        "°F" -> celsius * 9.0 / 5.0 + 32.0
        "K" -> celsius + 273.15
        else -> celsius
    }
}

// 格式化结果
fun fmtResult(v: Double): String {
    if (v.isNaN()) return "错误"
    if (v.isInfinite()) return if (v > 0) "∞" else "-∞"
    if (abs(v) >= 1e15 || (abs(v) < 1e-12 && v != 0.0))
        return String.format("%.6e", v)
    // 合理范围：显示最多10位小数，去掉末尾零
    val formatted = String.format("%.10f", v).trimEnd('0').trimEnd('.')
    return if (formatted.length > 16) String.format("%.8e", v) else formatted
}

// ── 主屏幕 ────────────────────────────────────────────────────
@Composable
fun UnitConverterScreen(animTime: Float, onBack: () -> Unit) {
    var state by remember { mutableStateOf(ConvState()) }
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 货币汇率：Compose 可观察，刷新后自动重组
    val currencyRates by CurrencyRateStore.current
    val rateStatus by CurrencyRateStore.status

    // units 依赖 category；货币类别还依赖 currencyRates（刷新后随之更新）
    val units = remember(state.category, currencyRates) { getUnits(state.category) }
    val safeUnits = if (units.isEmpty()) listOf(UnitDef("—", "—", 1.0)) else units
    val fromUnit = remember(state.fromUnitIndex, state.category, currencyRates) {
        safeUnits[state.fromUnitIndex.coerceIn(0, safeUnits.lastIndex)]
    }
    val toUnit = remember(state.toUnitIndex, state.category, currencyRates) {
        safeUnits[state.toUnitIndex.coerceIn(0, safeUnits.lastIndex)]
    }

    // 进入货币 Tab：先用缓存展示，再后台刷新最新汇率
    LaunchedEffect(state.category) {
        if (state.category == UnitCategory.CURRENCY) {
            CurrencyRateStore.loadCache(context)
            CurrencyRateStore.refresh(context)
        }
    }

    // 计算转换结果
    val inputDouble = state.inputValue.toDoubleOrNull()
    val result = if (inputDouble != null) {
        convert(inputDouble, fromUnit, toUnit, state.category)
    } else null

    val resultText = when {
        state.inputValue.isBlank() -> "0"
        result != null -> fmtResult(result)
        else -> "—"
    }

    // 数字输入
    fun onDigit(d: String) {
        if (state.inputValue == "0" && d != ".") {
            state = state.copy(inputValue = d)
        } else if (state.inputValue.length < 15) {
            state = state.copy(inputValue = state.inputValue + d)
        }
    }
    fun onDecimal() {
        if (!state.inputValue.contains(".")) {
            state = state.copy(inputValue = state.inputValue + ".")
        }
    }
    fun onBackspace() {
        state = if (state.inputValue.length > 1) {
            state.copy(inputValue = state.inputValue.dropLast(1))
        } else {
            state.copy(inputValue = "0")
        }
    }
    fun onClear() {
        state = state.copy(inputValue = "0")
    }
    fun onSwap() {
        val fromIdx = state.fromUnitIndex
        val toIdx = state.toUnitIndex
        // 添加历史记录 + 交换后保留原输入值（不再强制重置为 "1"）
        if (inputDouble != null && result != null && result.isFinite()) {
            val record = "${fmtResult(inputDouble)} ${fromUnit.symbol} = ${resultText} ${toUnit.symbol}"
            state = state.copy(
                fromUnitIndex = toIdx,
                toUnitIndex = fromIdx,
                inputValue = state.inputValue,  // 保留原输入
                history = (state.history + record).takeLast(10)
            )
        } else {
            state = state.copy(fromUnitIndex = toIdx, toUnitIndex = fromIdx)
        }
    }
    fun onCategoryChange(cat: UnitCategory) {
        val newUnits = getUnits(cat)
        val safeNewUnits = if (newUnits.isEmpty()) listOf(UnitDef("—", "—", 1.0)) else newUnits
        // 切换类别时重置输入值，避免跨类别残留（如货币的 7.25 残留到长度类别）
        state = state.copy(
            category = cat,
            fromUnitIndex = 0,
            toUnitIndex = (1).coerceAtMost(safeNewUnits.lastIndex),
            inputValue = "0"
        )
    }

    LiquidGlassScaffold(animTime = animTime) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp)
        ) {
            // ── 顶栏 ──────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = appTextSecondary())
                }
                Text(
                    "单位换算",
                    fontSize = 16.sp,
                    color = appTextSecondary(),
                    modifier = Modifier.weight(1f)
                )
                // 清除历史
                if (state.history.isNotEmpty()) {
                    IconButton(onClick = { state = state.copy(history = emptyList()) }) {
                        Icon(Icons.Default.DeleteSweep, "清除历史", tint = appTextTertiary(), modifier = Modifier.size(20.dp))
                    }
                }
            }

            // ── 类别标签 ──────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (cat in UnitCategory.entries) {
                    val selected = state.category == cat
                    CategoryTab(
                        label = cat.label,
                        selected = selected,
                        onClick = { onCategoryChange(cat) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ── 主内容区域（可滚动） ──────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
            ) {
                // ── From 输入区 ───────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.15f)
                        .padding(16.dp)
                ) {
                    Column {
                        Text("从", fontSize = 11.sp, color = appTextTertiary())
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // 输入值显示
                            Text(
                                text = state.inputValue,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Thin,
                                color = appTextPrimary(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            // 单位选择器
                            UnitSelector(
                                unit = fromUnit,
                                expanded = state.showFromPicker,
                                units = safeUnits,
                                onToggle = { state = state.copy(showFromPicker = !state.showFromPicker, showToPicker = false) },
                                onDismiss = { state = state.copy(showFromPicker = false) },
                                onSelect = { idx ->
                                    state = state.copy(fromUnitIndex = idx, showFromPicker = false)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ── 交换按钮 ──────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    var swapPressed by remember { mutableStateOf(false) }
                    val swapScale by animateFloatAsState(
                        if (swapPressed) 0.85f else 1f,
                        spring(dampingRatio = 0.35f, stiffness = 500f),
                        label = "swap"
                    )

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .scale(swapScale)
                            .clip(RoundedCornerShape(12.dp))
                            .glassSurface(cornerRadius = 12.dp, glassAlpha = 0.12f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                swapPressed = true
                                onSwap()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.SwapVert,
                            "交换",
                            tint = FluidCyan,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    LaunchedEffect(swapPressed) {
                        if (swapPressed) { delay(100); swapPressed = false }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ── To 结果区 ─────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.15f)
                        .padding(16.dp)
                ) {
                    Column {
                        Text("到", fontSize = 11.sp, color = appTextTertiary())
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = resultText,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Thin,
                                color = if (result != null) FluidCyan else TextTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            UnitSelector(
                                unit = toUnit,
                                expanded = state.showToPicker,
                                units = safeUnits,
                                onToggle = { state = state.copy(showToPicker = !state.showToPicker, showFromPicker = false) },
                                onDismiss = { state = state.copy(showToPicker = false) },
                                onSelect = { idx ->
                                    state = state.copy(toUnitIndex = idx, showToPicker = false)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ── 换算公式详情卡 ────────────────────────────
                if (inputDouble != null && result != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.08f)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        val formula = buildString {
                            append("1 ${fromUnit.symbol} = ")
                            val oneResult = convert(1.0, fromUnit, toUnit, state.category)
                            append("${fmtResult(oneResult)} ${toUnit.symbol}")
                        }
                        Text(
                            formula,
                            fontSize = 12.sp,
                            color = appTextTertiary(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // 货币汇率来源提示（仅货币类别显示）
                    if (state.category == UnitCategory.CURRENCY) {
                        val (hint, hintColor) = when (rateStatus) {
                            CurrencyRateStore.Status.LOADING -> "正在更新汇率…" to appTextTertiary()
                            CurrencyRateStore.Status.OK -> "汇率已更新（实时）" to FluidCyan
                            CurrencyRateStore.Status.STALE -> "使用缓存汇率，点击刷新" to FluidOrange
                            CurrencyRateStore.Status.ERROR -> "网络失败，使用默认汇率，点击重试" to AccentDanger
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .glassSurface(cornerRadius = 10.dp, glassAlpha = 0.06f)
                                .clickable { scope.launch { CurrencyRateStore.refresh(context) } }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Sync, null, tint = hintColor, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(hint, fontSize = 11.sp, color = hintColor)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // ── 数字键盘 ──────────────────────────────────
                NumberPad(
                    onDigit = { onDigit(it) },
                    onDecimal = { onDecimal() },
                    onBackspace = { onBackspace() },
                    onClear = { onClear() }
                )

                // ── 换算历史 ──────────────────────────────────
                if (state.history.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "换算历史",
                        fontSize = 12.sp,
                        color = appTextTertiary(),
                        modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
                    )
                    for ((i, record) in state.history.withIndex()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glassSurface(cornerRadius = 10.dp, glassAlpha = 0.06f)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                record,
                                fontSize = 12.sp,
                                color = appTextSecondary(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// ── 类别标签 ──────────────────────────────────────────────────
@Composable
private fun CategoryTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bgAlpha by animateFloatAsState(
        if (selected) 0.18f else 0.06f,
        tween(200),
        label = "tabBg"
    )
    val textColor by animateColorAsState(
        if (selected) FluidCyan else TextTertiary,
        tween(200),
        label = "tabText"
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .glassSurface(cornerRadius = 12.dp, glassAlpha = bgAlpha)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Light,
            color = textColor
        )
    }
}

// ── 单位选择器 ────────────────────────────────────────────────
// 关键修复（v2）：早期用 DropdownMenu 在 verticalScroll 父容器中触发
// "infinity maximum height constraints" 闪退；后改用 Popup 仍偶发崩溃——
// 根因有二：
//   1) Popup 跟随父 Box 在 scroll 容器中的位置计算，滚动/重组间隙位置可能
//      落到屏幕外或负坐标，触发测量/定位异常；
//   2) onDismissRequest 用 onToggle()（toggle 语义），dismiss 被触发两次时
//      会把已关闭的弹层重新打开，重组间隙引发状态错乱。
// 现改用 Dialog：独立窗口，完全脱离父容器测量约束；onDismissRequest 走
// 专用 onDismiss 回调（仅关闭，不 toggle），从根上杜绝崩溃。
// heightIn(max) 限制列表高度，单位多时可滚动，少时自适应不撑屏。
@Composable
private fun UnitSelector(
    unit: UnitDef,
    expanded: Boolean,
    units: List<UnitDef>,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    // 触发按钮
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .glassSurface(cornerRadius = 10.dp, glassAlpha = 0.10f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onToggle() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                unit.symbol,
                fontSize = 14.sp,
                fontWeight = FontWeight.Light,
                color = FluidCyan
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                null,
                tint = appTextTertiary(),
                modifier = Modifier.size(18.dp)
            )
        }
    }

    if (expanded) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false
            )
        ) {
            // 安全快照：units 在重组间隙可能为空（如货币加载中），用兜底防止越界
            val safeItems = remember(units) {
                units.ifEmpty { listOf(UnitDef("—", "—", 1.0)) }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(appBgColor2().copy(alpha = 0.98f))
                    .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.30f)
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .padding(vertical = 6.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "选择单位",
                        fontSize = 12.sp,
                        color = appTextTertiary(),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                    for ((i, u) in safeItems.withIndex()) {
                        val isSelected = u == unit
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (i < safeItems.size) onSelect(i)
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    u.name,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                    color = if (isSelected) FluidCyan else TextSecondary
                                )
                                Text(
                                    u.symbol,
                                    fontSize = 12.sp,
                                    color = appTextTertiary()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 数字键盘 ──────────────────────────────────────────────────
@Composable
private fun NumberPad(
    onDigit: (String) -> Unit,
    onDecimal: () -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit
) {
    val btnRows = listOf(
        listOf("7", "8", "9"),
        listOf("4", "5", "6"),
        listOf("1", "2", "3"),
        listOf(".", "0", "⌫")
    )

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        // 清除按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            NumPadButton(
                label = "C",
                color = AccentDanger,
                modifier = Modifier.weight(1f),
                onClick = onClear
            )
            // 占位，使清除按钮居中
            Spacer(modifier = Modifier.weight(2f))
        }

        for (row in btnRows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                for (label in row) {
                    when (label) {
                        "⌫" -> NumPadButton(
                            label = label,
                            color = appTextSecondary(),
                            modifier = Modifier.weight(1f),
                            onClick = onBackspace
                        )
                        "." -> NumPadButton(
                            label = label,
                            color = appTextPrimary(),
                            modifier = Modifier.weight(1f),
                            onClick = onDecimal
                        )
                        else -> NumPadButton(
                            label = label,
                            color = appTextPrimary(),
                            modifier = Modifier.weight(1f),
                            onClick = { onDigit(label) }
                        )
                    }
                }
            }
        }
    }
}

// ── 数字键盘按钮 ──────────────────────────────────────────────
@Composable
private fun NumPadButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        spring(dampingRatio = 0.35f, stiffness = 500f),
        label = "numpad"
    )

    Box(
        modifier = modifier
            .aspectRatio(0.85f)
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.10f, showBorder = true)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                pressed = true
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = if (label.length > 1) 14.sp else 22.sp,
            fontWeight = FontWeight.Light,
            color = color,
            textAlign = TextAlign.Center
        )
    }

    LaunchedEffect(pressed) {
        if (pressed) { delay(100); pressed = false }
    }
}