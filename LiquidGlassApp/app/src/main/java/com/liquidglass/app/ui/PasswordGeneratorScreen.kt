package com.liquidglass.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import java.util.UUID

// ─── Password Generator State ────────────────────────────────────────────────

data class PasswordState(
    val length: Int = 16,
    val includeUppercase: Boolean = true,
    val includeLowercase: Boolean = true,
    val includeNumbers: Boolean = true,
    val includeSymbols: Boolean = true,
    val excludeSimilar: Boolean = false,
    val generatedPassword: String = "",
    val history: List<String> = emptyList()
)

// ─── Random Tools State ──────────────────────────────────────────────────────

data class RandomToolsState(
    // Random Number
    val rnMin: String = "1",
    val rnMax: String = "100",
    val rnResult: String = "",
    val rnHistory: List<String> = emptyList(),
    // Dice
    val diceCount: Int = 2,
    val diceResults: List<Int> = emptyList(),
    val diceTotal: Int = 0,
    val isRolling: Boolean = false,
    // Coin
    val coinResult: String = "",
    val isFlipping: Boolean = false,
    val coinAngle: Float = 0f,
    // Random Color
    val randomColor: Color = Color.White,
    val randomColorHex: String = "#FFFFFF",
    // UUID
    val generatedUuid: String = "",
    val uuidHistory: List<String> = emptyList()
)

// ─── Main Screen ─────────────────────────────────────────────────────────────

@Composable
fun PasswordGeneratorScreen(animTime: Float, onBack: () -> Unit) {
    var mainTab by remember { mutableIntStateOf(0) } // 0=Password, 1=RandomTools
    var pwdState by remember { mutableStateOf(PasswordState()) }
    var rtState by remember { mutableStateOf(RandomToolsState()) }
    var randomSubTab by remember { mutableIntStateOf(0) } // 0=Number, 1=Dice, 2=Coin, 3=Color, 4=UUID
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun copyToClipboard(text: String, label: String = "已复制") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    // ── Password Generation Logic ──
    fun generatePassword() {
        val uppercase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val lowercase = "abcdefghijklmnopqrstuvwxyz"
        val numbers = "0123456789"
        val symbols = "!@#$%^&*()-_=+[]{}|;:,.<>?/`~"
        val similarChars = "il1ILo0O|"

        var charPool = ""
        if (pwdState.includeUppercase) charPool += uppercase
        if (pwdState.includeLowercase) charPool += lowercase
        if (pwdState.includeNumbers) charPool += numbers
        if (pwdState.includeSymbols) charPool += symbols

        if (charPool.isEmpty()) {
            pwdState = pwdState.copy(generatedPassword = "请选择至少一种字符类型")
            return
        }

        if (pwdState.excludeSimilar) {
            charPool = charPool.filter { it !in similarChars }
        }

        if (charPool.isEmpty()) {
            pwdState = pwdState.copy(generatedPassword = "排除相似字符后无可用字符")
            return
        }

        val sb = StringBuilder()
        // Ensure at least one of each selected type
        val mandatory = mutableListOf<Char>()
        if (pwdState.includeUppercase) {
            val pool = if (pwdState.excludeSimilar) uppercase.filter { it !in similarChars } else uppercase
            if (pool.isNotEmpty()) mandatory.add(Random.nextInt(pool.length).let { pool[it] })
        }
        if (pwdState.includeLowercase) {
            val pool = if (pwdState.excludeSimilar) lowercase.filter { it !in similarChars } else lowercase
            if (pool.isNotEmpty()) mandatory.add(Random.nextInt(pool.length).let { pool[it] })
        }
        if (pwdState.includeNumbers) {
            val pool = if (pwdState.excludeSimilar) numbers.filter { it !in similarChars } else numbers
            if (pool.isNotEmpty()) mandatory.add(Random.nextInt(pool.length).let { pool[it] })
        }
        if (pwdState.includeSymbols) {
            val pool = if (pwdState.excludeSimilar) symbols.filter { it !in similarChars } else symbols
            if (pool.isNotEmpty()) mandatory.add(Random.nextInt(pool.length).let { pool[it] })
        }

        for (i in mandatory.indices) {
            sb.append(mandatory[i])
        }
        for (i in sb.length until pwdState.length) {
            sb.append(charPool[Random.nextInt(charPool.length)])
        }
        // Shuffle
        val chars = sb.toMutableList()
        chars.shuffle(Random)
        val result = chars.joinToString("")

        val newHistory = (listOf(result) + pwdState.history).take(20)
        pwdState = pwdState.copy(generatedPassword = result, history = newHistory)
    }

    // ── Password Strength ──
    fun passwordStrength(pwd: String): Pair<String, Color> {
        if (pwd.isEmpty() || pwd.startsWith("请选择") || pwd.startsWith("排除")) return "" to Color.Transparent
        var score = 0
        if (pwd.length >= 8) score++
        if (pwd.length >= 12) score++
        if (pwd.length >= 16) score++
        if (pwd.length >= 24) score++
        if (pwd.any { it.isUpperCase() }) score++
        if (pwd.any { it.isLowerCase() }) score++
        if (pwd.any { it.isDigit() }) score++
        if (pwd.any { !it.isLetterOrDigit() }) score++
        val uniqueCount = pwd.toSet().size
        if (uniqueCount >= pwd.length * 0.6) score++
        if (uniqueCount >= pwd.length * 0.8) score++

        return when {
            score <= 3 -> "弱" to AccentDanger
            score <= 5 -> "中等" to AccentWarning
            score <= 7 -> "强" to FluidCyan
            else -> "非常强" to AccentSuccess
        }
    }

    fun strengthBarProgress(pwd: String): Float {
        if (pwd.isEmpty() || pwd.startsWith("请选择") || pwd.startsWith("排除")) return 0f
        var score = 0
        if (pwd.length >= 8) score++
        if (pwd.length >= 12) score++
        if (pwd.length >= 16) score++
        if (pwd.length >= 24) score++
        if (pwd.any { it.isUpperCase() }) score++
        if (pwd.any { it.isLowerCase() }) score++
        if (pwd.any { it.isDigit() }) score++
        if (pwd.any { !it.isLetterOrDigit() }) score++
        val uniqueCount = pwd.toSet().size
        if (uniqueCount >= pwd.length * 0.6) score++
        if (uniqueCount >= pwd.length * 0.8) score++
        return (score / 10f).coerceIn(0f, 1f)
    }

    // ── Random Tools Logic ──
    fun generateRandomNumber() {
        val min = rtState.rnMin.toIntOrNull() ?: 1
        val max = rtState.rnMax.toIntOrNull() ?: 100
        val actualMin = minOf(min, max)
        val actualMax = maxOf(min, max)
        val result = Random.nextInt(actualMin, actualMax + 1)
        val label = "$actualMin-$actualMax"
        rtState = rtState.copy(
            rnResult = "$result ($label)",
            rnHistory = (listOf("$result") + rtState.rnHistory).take(20)
        )
    }

    fun rollDice() {
        scope.launch {
            rtState = rtState.copy(isRolling = true)
            // Animate through random values
            for (i in 0 until 12) {
                val tmp = (0 until rtState.diceCount).map { Random.nextInt(1, 7) }
                rtState = rtState.copy(diceResults = tmp, diceTotal = tmp.sum())
                delay(60)
            }
            val final = (0 until rtState.diceCount).map { Random.nextInt(1, 7) }
            rtState = rtState.copy(diceResults = final, diceTotal = final.sum(), isRolling = false)
        }
    }

    fun flipCoin() {
        scope.launch {
            rtState = rtState.copy(isFlipping = true, coinResult = "", coinAngle = 0f)
            val targetResult = if (Random.nextBoolean()) "正面" else "反面"
            val totalFlips = 10
            for (i in 1..totalFlips) {
                val progress = i.toFloat() / totalFlips
                val angle = progress * 360f * 3 // 3 full rotations
                rtState = rtState.copy(coinAngle = angle)
                delay(40)
            }
            rtState = rtState.copy(isFlipping = false, coinResult = targetResult, coinAngle = 0f)
        }
    }

    fun generateRandomColor() {
        val r = Random.nextInt(256)
        val g = Random.nextInt(256)
        val b = Random.nextInt(256)
        val color = Color(r, g, b)
        val hex = String.format("#%02X%02X%02X", r, g, b)
        rtState = rtState.copy(randomColor = color, randomColorHex = hex)
        copyToClipboard(hex, "颜色代码")
    }

    fun generateUuid() {
        val uuid = UUID.randomUUID().toString()
        rtState = rtState.copy(
            generatedUuid = uuid,
            uuidHistory = (listOf(uuid) + rtState.uuidHistory).take(20)
        )
    }

    // ── UI ──────────────────────────────────────────────────────────────────

    LiquidGlassScaffold(animTime = animTime) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // ── Top Bar ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = appTextSecondary())
                }
                Text("密码·随机工具", fontSize = 16.sp, color = appTextSecondary(), modifier = Modifier.weight(1f))
            }

            // ── Main Tab Row ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MainTabButton("密码生成", selected = mainTab == 0, onClick = { mainTab = 0 })
                MainTabButton("随机工具", selected = mainTab == 1, onClick = { mainTab = 1 })
            }

            // ── Content ──
            when (mainTab) {
                0 -> PasswordGeneratorTab(
                    state = pwdState,
                    onLengthChange = { pwdState = pwdState.copy(length = it) },
                    onToggleUppercase = { pwdState = pwdState.copy(includeUppercase = !pwdState.includeUppercase) },
                    onToggleLowercase = { pwdState = pwdState.copy(includeLowercase = !pwdState.includeLowercase) },
                    onToggleNumbers = { pwdState = pwdState.copy(includeNumbers = !pwdState.includeNumbers) },
                    onToggleSymbols = { pwdState = pwdState.copy(includeSymbols = !pwdState.includeSymbols) },
                    onToggleExcludeSimilar = { pwdState = pwdState.copy(excludeSimilar = !pwdState.excludeSimilar) },
                    onGenerate = { generatePassword() },
                    onCopy = { copyToClipboard(pwdState.generatedPassword, "密码") },
                    strength = passwordStrength(pwdState.generatedPassword),
                    strengthProgress = strengthBarProgress(pwdState.generatedPassword)
                )
                1 -> RandomToolsTab(
                    state = rtState,
                    subTab = randomSubTab,
                    onSubTabChange = { randomSubTab = it },
                    onRnMinChange = { rtState = rtState.copy(rnMin = it) },
                    onRnMaxChange = { rtState = rtState.copy(rnMax = it) },
                    onRnGenerate = { generateRandomNumber() },
                    onDiceCountChange = { rtState = rtState.copy(diceCount = it) },
                    onRollDice = { rollDice() },
                    onFlipCoin = { flipCoin() },
                    onGenerateColor = { generateRandomColor() },
                    onGenerateUuid = { generateUuid() },
                    onCopyUuid = { copyToClipboard(rtState.generatedUuid, "UUID") },
                    onCopyRn = { copyToClipboard(rtState.rnResult.split("(").first().trim(), "随机数") },
                    onCopyColor = { copyToClipboard(rtState.randomColorHex, "颜色代码") }
                )
            }
        }
    }
}

// ── Main Tab Button ──────────────────────────────────────────────────────────

@Composable
fun RowScope.MainTabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val bgAlpha by animateFloatAsState(if (selected) 0.18f else 0.06f, label = "tabAlpha")
    val textColor by animateColorAsState(if (selected) FluidCyan else TextTertiary, label = "tabColor")
    val borderColor by animateColorAsState(
        if (selected) FluidCyan.copy(alpha = 0.4f) else Color.Transparent,
        label = "tabBorder"
    )

    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .glassSurface(cornerRadius = 14.dp, glassAlpha = bgAlpha, showBorder = selected)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, color = textColor)
    }
}

// ── Password Generator Tab ──────────────────────────────────────────────────

@Composable
fun PasswordGeneratorTab(
    state: PasswordState,
    onLengthChange: (Int) -> Unit,
    onToggleUppercase: () -> Unit,
    onToggleLowercase: () -> Unit,
    onToggleNumbers: () -> Unit,
    onToggleSymbols: () -> Unit,
    onToggleExcludeSimilar: () -> Unit,
    onGenerate: () -> Unit,
    onCopy: () -> Unit,
    strength: Pair<String, Color>,
    strengthProgress: Float
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        // ── Length Slider ──
        SectionCard(title = "密码长度：${state.length}") {
            Column {
                Slider(
                    value = state.length.toFloat(),
                    onValueChange = { onLengthChange(it.toInt()) },
                    valueRange = 4f..64f,
                    steps = 59,
                    colors = SliderDefaults.colors(
                        thumbColor = FluidCyan,
                        activeTrackColor = FluidCyan,
                        inactiveTrackColor = GlassMedium
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("4", fontSize = 10.sp, color = appTextTertiary())
                    Text("64", fontSize = 10.sp, color = appTextTertiary())
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Character Options ──
        SectionCard(title = "字符类型") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ToggleOption("大写字母 (A-Z)", state.includeUppercase, onToggleUppercase,
                    icon = Icons.Default.TextFields, color = FluidCyan)
                ToggleOption("小写字母 (a-z)", state.includeLowercase, onToggleLowercase,
                    icon = Icons.Default.TextFields, color = FluidPurple)
                ToggleOption("数字 (0-9)", state.includeNumbers, onToggleNumbers,
                    icon = Icons.Default.Pin, color = FluidTeal)
                ToggleOption("符号 (!@#\$%^&*等)", state.includeSymbols, onToggleSymbols,
                    icon = Icons.Default.Code, color = FluidPink)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Exclude Similar ──
        ToggleOption(
            "排除相似字符 (il1ILo0O|)",
            state.excludeSimilar,
            onToggleExcludeSimilar,
            icon = Icons.Default.VisibilityOff,
            color = AccentWarning,
            standalone = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Generate Button ──
        var btnPressed by remember { mutableStateOf(false) }
        val btnScale by animateFloatAsState(
            if (btnPressed) 0.92f else 1f,
            spring(dampingRatio = 0.35f, stiffness = 500f),
            label = "genBtn"
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .scale(btnScale)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(listOf(FluidCyan, FluidPurple, FluidPink))
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    btnPressed = true
                    onGenerate()
                }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("生成密码", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        LaunchedEffect(btnPressed) { if (btnPressed) { delay(100); btnPressed = false } }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Generated Password Display ──
        if (state.generatedPassword.isNotEmpty() && !state.generatedPassword.startsWith("请选择") && !state.generatedPassword.startsWith("排除")) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.16f)
                    .padding(16.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, null, tint = FluidCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            state.generatedPassword,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = appTextPrimary(),
                            modifier = Modifier.weight(1f),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = onCopy,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, "复制", tint = FluidCyan, modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // ── Strength Indicator ──
                    if (strength.first.isNotEmpty()) {
                        val barColor = animateColorAsState(strength.second, label = "barColor")
                        val barProgress by animateFloatAsState(strengthProgress, tween(400), label = "barProgress")

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("强度：", fontSize = 12.sp, color = appTextTertiary())
                            Text(strength.first, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = barColor.value)
                            Spacer(modifier = Modifier.width(12.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(GlassMedium)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(barProgress)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(AccentDanger, AccentWarning, FluidCyan, AccentSuccess)
                                            )
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Password History ──
        if (state.history.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            SectionCard(title = "生成历史 (最近${state.history.size}条)") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((index, item) in state.history.withIndex()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glassSurface(cornerRadius = 10.dp, glassAlpha = 0.06f)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${index + 1}",
                                fontSize = 11.sp,
                                color = appTextTertiary(),
                                modifier = Modifier.width(24.dp)
                            )
                            Text(
                                item,
                                fontSize = 13.sp,
                                color = appTextSecondary(),
                                modifier = Modifier.weight(1f),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                maxLines = 1
                            )
                            val context = LocalContext.current
                            IconButton(
                                onClick = {
                                    val clipboard = context
                                        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("密码", item))
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, "复制", tint = appTextTertiary(), modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ── Random Tools Tab ─────────────────────────────────────────────────────────

@Composable
fun RandomToolsTab(
    state: RandomToolsState,
    subTab: Int,
    onSubTabChange: (Int) -> Unit,
    onRnMinChange: (String) -> Unit,
    onRnMaxChange: (String) -> Unit,
    onRnGenerate: () -> Unit,
    onDiceCountChange: (Int) -> Unit,
    onRollDice: () -> Unit,
    onFlipCoin: () -> Unit,
    onGenerateColor: () -> Unit,
    onGenerateUuid: () -> Unit,
    onCopyUuid: () -> Unit,
    onCopyRn: () -> Unit,
    onCopyColor: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        // ── Sub Tab Row ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SubTabChip("随机数", Icons.Default.Tag, subTab == 0, { onSubTabChange(0) })
            SubTabChip("骰子", Icons.Default.Casino, subTab == 1, { onSubTabChange(1) })
            SubTabChip("抛硬币", Icons.Default.AllInclusive, subTab == 2, { onSubTabChange(2) })
            SubTabChip("随机颜色", Icons.Default.Palette, subTab == 3, { onSubTabChange(3) })
            SubTabChip("UUID", Icons.Default.Fingerprint, subTab == 4, { onSubTabChange(4) })
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (subTab) {
            0 -> RandomNumberTab(state, onRnMinChange, onRnMaxChange, onRnGenerate, onCopyRn)
            1 -> DiceRollTab(state, onDiceCountChange, onRollDice)
            2 -> CoinFlipTab(state, onFlipCoin)
            3 -> RandomColorTab(state, onGenerateColor, onCopyColor)
            4 -> UuidGeneratorTab(state, onGenerateUuid, onCopyUuid)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ── Sub Tab Chip ─────────────────────────────────────────────────────────────

@Composable
fun SubTabChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    val bgAlpha by animateFloatAsState(if (selected) 0.16f else 0.06f, label = "chipAlpha")
    val txtColor by animateColorAsState(if (selected) FluidCyan else TextTertiary, label = "chipColor")

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .glassSurface(cornerRadius = 20.dp, glassAlpha = bgAlpha, showBorder = selected)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = txtColor, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = txtColor)
    }
}

// ── Random Number Tab ────────────────────────────────────────────────────────

@Composable
fun RandomNumberTab(
    state: RandomToolsState,
    onMinChange: (String) -> Unit,
    onMaxChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onCopy: () -> Unit
) {
    SectionCard(title = "随机数生成") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("最小值", fontSize = 11.sp, color = appTextTertiary())
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = state.rnMin,
                        onValueChange = { if (it.all { c -> c.isDigit() || c == '-' } && it.length <= 10) onMinChange(it) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = glassTextFieldColors(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("最大值", fontSize = 11.sp, color = appTextTertiary())
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = state.rnMax,
                        onValueChange = { if (it.all { c -> c.isDigit() || c == '-' } && it.length <= 10) onMaxChange(it) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = glassTextFieldColors(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            var btnPressed by remember { mutableStateOf(false) }
            val btnScale by animateFloatAsState(if (btnPressed) 0.92f else 1f, spring(dampingRatio = 0.35f, stiffness = 500f), label = "rnBtn")

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(btnScale)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(FluidTeal, FluidCyan)))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        btnPressed = true
                        onGenerate()
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("生成随机数", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            LaunchedEffect(btnPressed) { if (btnPressed) { delay(100); btnPressed = false } }

            if (state.rnResult.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.14f)
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.rnResult, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                            color = FluidTeal, modifier = Modifier.weight(1f))
                        IconButton(onClick = onCopy, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.ContentCopy, "复制", tint = FluidTeal, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }

    // History
    if (state.rnHistory.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        SectionCard(title = "随机数历史") {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (item in state.rnHistory) {
                    Box(
                        modifier = Modifier
                            .glassSurface(cornerRadius = 10.dp, glassAlpha = 0.06f)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(item, fontSize = 13.sp, color = appTextSecondary())
                    }
                }
            }
        }
    }
}

// ── Dice Roll Tab ────────────────────────────────────────────────────────────

@Composable
fun DiceRollTab(
    state: RandomToolsState,
    onDiceCountChange: (Int) -> Unit,
    onRoll: () -> Unit
) {
    SectionCard(title = "骰子投掷") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Dice count selector
            Text("骰子数量：${state.diceCount}", fontSize = 13.sp, color = appTextSecondary())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in 1..6) {
                    val selected = state.diceCount == n
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .glassSurface(cornerRadius = 12.dp, glassAlpha = if (selected) 0.18f else 0.06f, showBorder = selected)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onDiceCountChange(n) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "$n",
                            fontSize = 18.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) FluidCyan else TextSecondary
                        )
                    }
                }
            }

            // Roll button
            var btnPressed by remember { mutableStateOf(false) }
            val btnScale by animateFloatAsState(if (btnPressed) 0.92f else 1f, spring(dampingRatio = 0.35f, stiffness = 500f), label = "diceBtn")

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(btnScale)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(FluidPurple, FluidPink)))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        btnPressed = true
                        onRoll()
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (state.isRolling) "投掷中..." else "🎲 投掷骰子",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            LaunchedEffect(btnPressed) { if (btnPressed) { delay(100); btnPressed = false } }

            // Dice Results Display
            if (state.diceResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for ((i, result) in state.diceResults.withIndex()) {
                        val diceChar = when (result) {
                            1 -> "⚀"; 2 -> "⚁"; 3 -> "⚂"; 4 -> "⚃"; 5 -> "⚄"; 6 -> "⚅"
                            else -> "$result"
                        }
                        val diceColor = when (result) {
                            1 -> AccentDanger; 2 -> AccentWarning; 3 -> FluidTeal; 4 -> FluidCyan; 5 -> FluidPurple; 6 -> FluidPink
                            else -> TextPrimary
                        }
                        Text(
                            diceChar,
                            fontSize = 36.sp,
                            color = diceColor,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.14f)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "合计：${state.diceTotal}",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = FluidPurple
                    )
                }
            }
        }
    }
}

// ── Coin Flip Tab ────────────────────────────────────────────────────────────

@Composable
fun CoinFlipTab(
    state: RandomToolsState,
    onFlip: () -> Unit
) {
    SectionCard(title = "抛硬币") {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Coin visual
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .rotate(state.coinAngle)
                    .background(
                        Brush.linearGradient(
                            listOf(FluidCyan, FluidPurple)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (state.coinAngle == 0f && !state.isFlipping) {
                    Text(
                        text = if (state.coinResult == "正面") "H" else if (state.coinResult == "反面") "T" else "?",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Result text
            if (state.coinResult.isNotEmpty()) {
                Text(
                    text = state.coinResult,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (state.coinResult == "正面") FluidCyan else FluidPink
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Flip button
            var btnPressed by remember { mutableStateOf(false) }
            val btnScale by animateFloatAsState(if (btnPressed) 0.92f else 1f, spring(dampingRatio = 0.35f, stiffness = 500f), label = "coinBtn")

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(btnScale)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(FluidCyan, FluidPurple)))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        btnPressed = true
                        onFlip()
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (state.isFlipping) "抛掷中..." else "🪙 抛硬币",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            LaunchedEffect(btnPressed) { if (btnPressed) { delay(100); btnPressed = false } }
        }
    }
}

// ── Random Color Tab ─────────────────────────────────────────────────────────

@Composable
fun RandomColorTab(
    state: RandomToolsState,
    onGenerate: () -> Unit,
    onCopy: () -> Unit
) {
    SectionCard(title = "随机颜色") {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Color preview swatch
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.12f)
                    .background(state.randomColor)
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                // inner glow
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(state.randomColor)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    state.randomColorHex,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = appTextPrimary(),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.ContentCopy, "复制", tint = FluidCyan, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // RGB breakdown
            val r = (state.randomColor.red * 255).toInt()
            val g = (state.randomColor.green * 255).toInt()
            val b = (state.randomColor.blue * 255).toInt()
            Text(
                "RGB($r, $g, $b)",
                fontSize = 13.sp,
                color = appTextTertiary(),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(16.dp))

            var btnPressed by remember { mutableStateOf(false) }
            val btnScale by animateFloatAsState(if (btnPressed) 0.92f else 1f, spring(dampingRatio = 0.35f, stiffness = 500f), label = "colorBtn")

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(btnScale)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFFFF0000), Color(0xFFFF8800), Color(0xFFFFFF00),
                                Color(0xFF00FF00), Color(0xFF0088FF), Color(0xFF8800FF)
                            )
                        )
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        btnPressed = true
                        onGenerate()
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("🎨 生成随机颜色", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            LaunchedEffect(btnPressed) { if (btnPressed) { delay(100); btnPressed = false } }
        }
    }
}

// ── UUID Generator Tab ───────────────────────────────────────────────────────

@Composable
fun UuidGeneratorTab(
    state: RandomToolsState,
    onGenerate: () -> Unit,
    onCopy: () -> Unit
) {
    SectionCard(title = "UUID 生成器") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            var btnPressed by remember { mutableStateOf(false) }
            val btnScale by animateFloatAsState(if (btnPressed) 0.92f else 1f, spring(dampingRatio = 0.35f, stiffness = 500f), label = "uuidBtn")

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(btnScale)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(FluidBlue, FluidPurple)))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        btnPressed = true
                        onGenerate()
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("生成 UUID v4", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            LaunchedEffect(btnPressed) { if (btnPressed) { delay(100); btnPressed = false } }

            if (state.generatedUuid.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.14f)
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.generatedUuid,
                            fontSize = 14.sp,
                            color = appTextPrimary(),
                            modifier = Modifier.weight(1f),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.ContentCopy, "复制", tint = FluidPurple, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    // UUID History
    if (state.uuidHistory.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        SectionCard(title = "UUID 历史") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((index, uuid) in state.uuidHistory.withIndex()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassSurface(cornerRadius = 10.dp, glassAlpha = 0.06f)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${index + 1}",
                            fontSize = 11.sp,
                            color = appTextTertiary(),
                            modifier = Modifier.width(24.dp)
                        )
                        Text(
                            uuid,
                            fontSize = 12.sp,
                            color = appTextSecondary(),
                            modifier = Modifier.weight(1f),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            maxLines = 1
                        )
                        val context = LocalContext.current
                        IconButton(
                            onClick = {
                                val clipboard = context
                                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("UUID", uuid))
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, "复制", tint = appTextTertiary(), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

// ── Reusable Components ──────────────────────────────────────────────────────

@Composable
fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.12f)
            .padding(16.dp)
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = appTextSecondary())
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

@Composable
fun ToggleOption(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    standalone: Boolean = false
) {
    val container = if (standalone) {
        Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.10f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onToggle() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 12.dp, glassAlpha = 0.06f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onToggle() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    }

    Row(
        modifier = container,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (checked) color else TextTertiary, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(label, fontSize = 13.sp, color = if (checked) TextPrimary else TextSecondary, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = color,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = GlassMedium
            ),
            modifier = Modifier.scale(0.8f)
        )
    }
}

@Composable
fun glassTextFieldColors(): TextFieldColors {
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextSecondary,
        focusedBorderColor = FluidCyan.copy(alpha = 0.5f),
        unfocusedBorderColor = GlassBorder,
        focusedContainerColor = GlassClear,
        unfocusedContainerColor = GlassClear,
        cursorColor = FluidCyan
    )
}