package com.liquidglass.app.ui

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

// ─── Data Models ──────────────────────────────────────────────────────────────

data class SavedColor(
    val id: Long = System.currentTimeMillis(),
    val name: String = "",
    val color: Long = 0xFFFF0000,
    val hex: String = "#FF0000",
    val timestamp: Long = System.currentTimeMillis()
)

data class SavedPalette(
    val id: Long = System.currentTimeMillis(),
    val name: String = "",
    val type: String = "complementary",
    val colors: List<Long> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

data class ColorValues(
    val hex: String = "#FF0000",
    val r: Int = 255,
    val g: Int = 0,
    val b: Int = 0,
    val a: Int = 255,
    val h: Float = 0f,
    val s: Float = 1f,
    val v: Float = 1f,
    val hslH: Float = 0f,
    val hslS: Float = 1f,
    val hslL: Float = 0.5f,
    val c: Float = 0f,
    val m: Float = 1f,
    val y: Float = 1f,
    val k: Float = 0f
)

// ─── SharedPreferences Helpers ────────────────────────────────────────────────

private const val PREFS_NAME = "liquid_glass_colors"
private const val SAVED_COLORS_KEY = "saved_colors"
private const val SAVED_PALETTES_KEY = "saved_palettes"

private fun loadSavedColors(context: Context): List<SavedColor> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val json = prefs.getString(SAVED_COLORS_KEY, null) ?: return emptyList()
    val arr = JSONArray(json)
    return (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        SavedColor(
            id = obj.getLong("id"),
            name = obj.getString("name"),
            color = obj.getLong("color"),
            hex = obj.getString("hex"),
            timestamp = obj.getLong("timestamp")
        )
    }
}

private fun saveSavedColors(context: Context, colors: List<SavedColor>) {
    val arr = JSONArray()
    for (c in colors) {
        arr.put(JSONObject().apply {
            put("id", c.id)
            put("name", c.name)
            put("color", c.color)
            put("hex", c.hex)
            put("timestamp", c.timestamp)
        })
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        .putString(SAVED_COLORS_KEY, arr.toString()).apply()
}

private fun loadSavedPalettes(context: Context): List<SavedPalette> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val json = prefs.getString(SAVED_PALETTES_KEY, null) ?: return emptyList()
    val arr = JSONArray(json)
    return (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        SavedPalette(
            id = obj.getLong("id"),
            name = obj.getString("name"),
            type = obj.getString("type"),
            colors = (0 until obj.getJSONArray("colors").length()).map { j ->
                obj.getJSONArray("colors").getLong(j)
            },
            timestamp = obj.getLong("timestamp")
        )
    }
}

private fun saveSavedPalettes(context: Context, palettes: List<SavedPalette>) {
    val arr = JSONArray()
    for (p in palettes) {
        val colorArr = JSONArray()
        for (c in p.colors) colorArr.put(c)
        arr.put(JSONObject().apply {
            put("id", p.id)
            put("name", p.name)
            put("type", p.type)
            put("colors", colorArr)
            put("timestamp", p.timestamp)
        })
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        .putString(SAVED_PALETTES_KEY, arr.toString()).apply()
}

// ─── Color Conversion Utilities ───────────────────────────────────────────────

private fun hsvToColor(hue: Float, saturation: Float, value: Float, alpha: Int = 255): Color {
    val hsv = floatArrayOf(hue, saturation, value)
    val rgb = AndroidColor.HSVToColor(hsv)
    return Color(
        red = AndroidColor.red(rgb) / 255f,
        green = AndroidColor.green(rgb) / 255f,
        blue = AndroidColor.blue(rgb) / 255f,
        alpha = alpha / 255f
    )
}

private fun colorToHsv(color: Color): FloatArray {
    val hsv = floatArrayOf(0f, 0f, 0f)
    AndroidColor.RGBToHSV(
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt(),
        hsv
    )
    return hsv
}

private fun colorToArgb(color: Color): Long {
    val a = (color.alpha * 255).toInt()
    val r = (color.red * 255).toInt()
    val g = (color.green * 255).toInt()
    val b = (color.blue * 255).toInt()
    return ((a.toLong() and 0xFF) shl 24) or
            ((r.toLong() and 0xFF) shl 16) or
            ((g.toLong() and 0xFF) shl 8) or
            (b.toLong() and 0xFF)
}

private fun colorToHex(color: Color, includeAlpha: Boolean = false): String {
    val argb = colorToArgb(color)
    return if (includeAlpha) {
        String.format("#%08X", argb)
    } else {
        String.format("#%06X", argb and 0x00FFFFFF)
    }
}

private fun hexToColor(hex: String): Color? {
    return try {
        val cleanHex = hex.trimStart('#')
        val colorLong = cleanHex.toLong(16)
        when (cleanHex.length) {
            6 -> Color(
                red = ((colorLong shr 16) and 0xFF) / 255f,
                green = ((colorLong shr 8) and 0xFF) / 255f,
                blue = (colorLong and 0xFF) / 255f
            )
            8 -> Color(
                red = ((colorLong shr 16) and 0xFF) / 255f,
                green = ((colorLong shr 8) and 0xFF) / 255f,
                blue = (colorLong and 0xFF) / 255f,
                alpha = ((colorLong shr 24) and 0xFF) / 255f
            )
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

private fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val h: Float
    val s: Float

    if (max == min) {
        h = 0f
        s = 0f
    } else {
        val d = max - min
        s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f)) * 60f
            g -> ((b - r) / d + 2f) * 60f
            else -> ((r - g) / d + 4f) * 60f
        }
    }
    return floatArrayOf(h, s, l)
}

private fun rgbToCmyk(r: Float, g: Float, b: Float): FloatArray {
    val k = 1f - maxOf(r, g, b)
    if (k >= 1f) return floatArrayOf(0f, 0f, 0f, 1f)
    val c = (1f - r - k) / (1f - k)
    val m = (1f - g - k) / (1f - k)
    val y = (1f - b - k) / (1f - k)
    return floatArrayOf(c, m, y, k)
}

private fun buildColorValues(color: Color): ColorValues {
    val r = (color.red * 255).toInt()
    val g = (color.green * 255).toInt()
    val b = (color.blue * 255).toInt()
    val a = (color.alpha * 255).toInt()
    val hex = colorToHex(color, a < 255)
    val hsv = colorToHsv(color)
    val hsl = rgbToHsl(color.red, color.green, color.blue)
    val cmyk = rgbToCmyk(color.red, color.green, color.blue)
    return ColorValues(
        hex = hex,
        r = r, g = g, b = b, a = a,
        h = hsv[0], s = hsv[1], v = hsv[2],
        hslH = hsl[0], hslS = hsl[1], hslL = hsl[2],
        c = cmyk[0], m = cmyk[1], y = cmyk[2], k = cmyk[3]
    )
}

// ─── Palette Generation ──────────────────────────────────────────────────────

private fun generateComplementary(hue: Float, sat: Float, value: Float): List<Color> {
    return listOf(
        hsvToColor(hue, sat, value),
        hsvToColor((hue + 180f) % 360f, sat, value)
    )
}

private fun generateAnalogous(hue: Float, sat: Float, value: Float): List<Color> {
    return listOf(
        hsvToColor((hue + 330f) % 360f, sat, value),
        hsvToColor(hue, sat, value),
        hsvToColor((hue + 30f) % 360f, sat, value)
    )
}

private fun generateTriadic(hue: Float, sat: Float, value: Float): List<Color> {
    return listOf(
        hsvToColor(hue, sat, value),
        hsvToColor((hue + 120f) % 360f, sat, value),
        hsvToColor((hue + 240f) % 360f, sat, value)
    )
}

private fun generateTetradic(hue: Float, sat: Float, value: Float): List<Color> {
    return listOf(
        hsvToColor(hue, sat, value),
        hsvToColor((hue + 60f) % 360f, sat, value),
        hsvToColor((hue + 180f) % 360f, sat, value),
        hsvToColor((hue + 240f) % 360f, sat, value)
    )
}

private fun generateMonochromatic(hue: Float, sat: Float, value: Float): List<Color> {
    return listOf(
        hsvToColor(hue, sat * 0.3f, minOf(value * 1.3f, 1f)),
        hsvToColor(hue, sat * 0.6f, minOf(value * 1.1f, 1f)),
        hsvToColor(hue, sat, value),
        hsvToColor(hue, sat * 0.8f, value * 0.7f),
        hsvToColor(hue, sat * 0.5f, value * 0.4f)
    )
}

private enum class PaletteType(val label: String) {
    COMPLEMENTARY("互补"),
    ANALOGOUS("类似"),
    TRIADIC("三角"),
    TETRADIC("四角"),
    MONOCHROMATIC("单色")
}

// ─── Main Screen ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerScreen(animTime: Float, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    // Current color state
    var currentHue by remember { mutableStateOf(0f) }
    var currentSaturation by remember { mutableStateOf(1f) }
    var currentValue by remember { mutableStateOf(1f) }
    var currentAlpha by remember { mutableStateOf(1f) }

    val currentColor = hsvToColor(currentHue, currentSaturation, currentValue, (currentAlpha * 255).toInt())
    val colorValues = remember(currentColor) { buildColorValues(currentColor) }

    // Tab state
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("色轮", "调色板", "收藏", "色带取色")

    // Palette state
    var selectedPaletteType by remember { mutableStateOf(PaletteType.COMPLEMENTARY) }
    var currentPalette by remember { mutableStateOf(generateComplementary(currentHue, currentSaturation, currentValue)) }
    var paletteColors by remember { mutableStateOf(currentPalette.map { colorToArgb(it) }) }

    // Saved colors
    var savedColors by remember { mutableStateOf(loadSavedColors(context)) }
    var savedPalettes by remember { mutableStateOf(loadSavedPalettes(context)) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showSavePaletteDialog by remember { mutableStateOf(false) }
    var saveColorName by remember { mutableStateOf("") }
    var savePaletteName by remember { mutableStateOf("") }
    var isGridView by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<SavedColor?>(null) }
    var showDeletePaletteDialog by remember { mutableStateOf<SavedPalette?>(null) }

    // Eyedropper
    var eyedropperColor by remember { mutableStateOf(Color.Red) }

    // Update palette when color changes
    LaunchedEffect(currentHue, currentSaturation, currentValue, selectedPaletteType) {
        val palette = when (selectedPaletteType) {
            PaletteType.COMPLEMENTARY -> generateComplementary(currentHue, currentSaturation, currentValue)
            PaletteType.ANALOGOUS -> generateAnalogous(currentHue, currentSaturation, currentValue)
            PaletteType.TRIADIC -> generateTriadic(currentHue, currentSaturation, currentValue)
            PaletteType.TETRADIC -> generateTetradic(currentHue, currentSaturation, currentValue)
            PaletteType.MONOCHROMATIC -> generateMonochromatic(currentHue, currentSaturation, currentValue)
        }
        currentPalette = palette
        paletteColors = palette.map { colorToArgb(it) }
    }

    // Copy helper
    fun copyToClipboard(text: String) {
        clipboardManager.setText(AnnotatedString(text))
    }

    LiquidGlassScaffold(animTime = animTime) {

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // ── Top Bar ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = appTextSecondary())
                }
                Text("颜色选择器", fontSize = 16.sp, color = appTextSecondary(), modifier = Modifier.weight(1f))
            }

            // ── Tab Row ──
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = FluidCyan,
                divider = {}
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                title,
                                fontSize = 13.sp,
                                color = if (selectedTab == index) FluidCyan else TextTertiary,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Tab Content ──
            when (selectedTab) {
                0 -> ColorWheelTab(
                    hue = currentHue,
                    saturation = currentSaturation,
                    value = currentValue,
                    alpha = currentAlpha,
                    colorValues = colorValues,
                    currentColor = currentColor,
                    onHueChanged = { currentHue = it },
                    onSaturationChanged = { currentSaturation = it },
                    onValueChanged = { currentValue = it },
                    onAlphaChanged = { currentAlpha = it },
                    onCopy = { copyToClipboard(it) },
                    onSaveColor = {
                        showSaveDialog = true
                        saveColorName = ""
                    }
                )
                1 -> PaletteTab(
                    currentPalette = currentPalette,
                    paletteColors = paletteColors,
                    selectedPaletteType = selectedPaletteType,
                    paletteTypes = PaletteType.entries,
                    onPaletteTypeChanged = { selectedPaletteType = it },
                    onColorSelected = { color ->
                        val hsv = colorToHsv(color)
                        currentHue = hsv[0]
                        currentSaturation = hsv[1]
                        currentValue = hsv[2]
                        currentAlpha = color.alpha
                    },
                    onSavePalette = {
                        showSavePaletteDialog = true
                        savePaletteName = ""
                    },
                    savedPalettes = savedPalettes,
                    onDeletePalette = { showDeletePaletteDialog = it },
                    onColorLongPress = { color ->
                        val hsv = colorToHsv(color)
                        currentHue = hsv[0]
                        currentSaturation = hsv[1]
                        currentValue = hsv[2]
                        currentAlpha = color.alpha
                        selectedTab = 0
                    }
                )
                2 -> SavedColorsTab(
                    savedColors = savedColors,
                    isGridView = isGridView,
                    onToggleView = { isGridView = !isGridView },
                    onColorClick = { saved ->
                        hexToColor(saved.hex)?.let { color ->
                            val hsv = colorToHsv(color)
                            currentHue = hsv[0]
                            currentSaturation = hsv[1]
                            currentValue = hsv[2]
                            currentAlpha = color.alpha
                            selectedTab = 0
                        }
                    },
                    onDeleteColor = { showDeleteDialog = it },
                    onCopy = { copyToClipboard(it) }
                )
                3 -> EyedropperTab(
                    onColorPicked = { color ->
                        eyedropperColor = color
                        val hsv = colorToHsv(color)
                        currentHue = hsv[0]
                        currentSaturation = hsv[1]
                        currentValue = hsv[2]
                        currentAlpha = color.alpha
                    },
                    eyedropperColor = eyedropperColor
                )
            }
        }
    }

    // ── Dialogs ──
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("保存颜色", color = appTextPrimary()) },
            text = {
                OutlinedTextField(
                    value = saveColorName,
                    onValueChange = { saveColorName = it },
                    label = { Text("颜色名称") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextSecondary,
                        focusedBorderColor = FluidCyan,
                        unfocusedBorderColor = GlassBorder,
                        cursorColor = FluidCyan
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val hex = colorToHex(currentColor)
                    savedColors = savedColors + SavedColor(
                        name = saveColorName.ifBlank { hex },
                        color = colorToArgb(currentColor),
                        hex = hex
                    )
                    saveSavedColors(context, savedColors)
                    showSaveDialog = false
                }) { Text("保存", color = FluidCyan) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("取消", color = appTextSecondary()) }
            },
            containerColor = appBgColor2(),
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showSavePaletteDialog) {
        AlertDialog(
            onDismissRequest = { showSavePaletteDialog = false },
            title = { Text("保存调色板", color = appTextPrimary()) },
            text = {
                Column {
                    OutlinedTextField(
                        value = savePaletteName,
                        onValueChange = { savePaletteName = it },
                        label = { Text("调色板名称") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextSecondary,
                            focusedBorderColor = FluidCyan,
                            unfocusedBorderColor = GlassBorder,
                            cursorColor = FluidCyan
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        currentPalette.forEach { color ->
                            Box(
                                modifier = Modifier.size(24.dp).clip(CircleShape).background(color)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    savedPalettes = savedPalettes + SavedPalette(
                        name = savePaletteName.ifBlank { "${selectedPaletteType.label}配色" },
                        type = selectedPaletteType.name,
                        colors = paletteColors
                    )
                    saveSavedPalettes(context, savedPalettes)
                    showSavePaletteDialog = false
                }) { Text("保存", color = FluidCyan) }
            },
            dismissButton = {
                TextButton(onClick = { showSavePaletteDialog = false }) { Text("取消", color = appTextSecondary()) }
            },
            containerColor = appBgColor2(),
            shape = RoundedCornerShape(16.dp)
        )
    }

    showDeleteDialog?.let { color ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除颜色", color = appTextPrimary()) },
            text = { Text("确定要删除「${color.name.ifBlank { color.hex }}」吗？", color = appTextSecondary()) },
            confirmButton = {
                TextButton(onClick = {
                    savedColors = savedColors.filter { it.id != color.id }
                    saveSavedColors(context, savedColors)
                    showDeleteDialog = null
                }) { Text("删除", color = AccentDanger) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("取消", color = appTextSecondary()) }
            },
            containerColor = appBgColor2(),
            shape = RoundedCornerShape(16.dp)
        )
    }

    showDeletePaletteDialog?.let { palette ->
        AlertDialog(
            onDismissRequest = { showDeletePaletteDialog = null },
            title = { Text("删除调色板", color = appTextPrimary()) },
            text = { Text("确定要删除「${palette.name}」吗？", color = appTextSecondary()) },
            confirmButton = {
                TextButton(onClick = {
                    savedPalettes = savedPalettes.filter { it.id != palette.id }
                    saveSavedPalettes(context, savedPalettes)
                    showDeletePaletteDialog = null
                }) { Text("删除", color = AccentDanger) }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePaletteDialog = null }) { Text("取消", color = appTextSecondary()) }
            },
            containerColor = appBgColor2(),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

// ─── Color Wheel Tab ─────────────────────────────────────────────────────────

@Composable
private fun ColorWheelTab(
    hue: Float,
    saturation: Float,
    value: Float,
    alpha: Float,
    colorValues: ColorValues,
    currentColor: Color,
    onHueChanged: (Float) -> Unit,
    onSaturationChanged: (Float) -> Unit,
    onValueChanged: (Float) -> Unit,
    onAlphaChanged: (Float) -> Unit,
    onCopy: (String) -> Unit,
    onSaveColor: () -> Unit
) {
    var expandedInfo by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
    ) {
        // Color Preview Card
        Box(
            modifier = Modifier.fillMaxWidth().height(100.dp)
                .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.2f, showBorder = true)
                .background(currentColor, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.CenterEnd
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(colorValues.hex, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "R:${colorValues.r} G:${colorValues.g} B:${colorValues.b}",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = { onCopy(colorValues.hex) },
                        modifier = Modifier.size(36.dp)
                            .glassSurface(cornerRadius = 18.dp, glassAlpha = 0.2f, showBorder = false)
                    ) {
                        Icon(Icons.Default.ContentCopy, "复制HEX", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = onSaveColor,
                        modifier = Modifier.size(36.dp)
                            .glassSurface(cornerRadius = 18.dp, glassAlpha = 0.2f, showBorder = false)
                    ) {
                        Icon(Icons.Default.Favorite, "收藏", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // HSV Color Wheel
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            ColorWheel(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                hue = hue,
                saturation = saturation,
                value = value,
                onColorChanged = { h, s, v ->
                    onHueChanged(h)
                    onSaturationChanged(s)
                    onValueChanged(v)
                }
            )

            // Center selector indicator
            val radius = 0.35f
            val angleRad = Math.toRadians(hue.toDouble()).toFloat()
            val dist = saturation * radius
            val cx = 0.5f + dist * cos(angleRad)
            val cy = 0.5f - dist * sin(angleRad)

            Box(
                modifier = Modifier
                    .size(24.dp)
                    .offset(
                        x = (cx * 100).dp - 12.dp,
                        y = (cy * 100).dp - 12.dp
                    )
                    .border(3.dp, Color.White, CircleShape)
                    .border(1.5.dp, Color.Black.copy(alpha = 0.3f), CircleShape)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Sliders
        Column(
            modifier = Modifier.glassSurface(cornerRadius = 16.dp, glassAlpha = 0.1f, showBorder = true)
                .padding(12.dp)
        ) {
            // Brightness slider
            Text("亮度", fontSize = 12.sp, color = appTextSecondary())
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = value,
                onValueChange = onValueChanged,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = currentColor,
                    inactiveTrackColor = GlassBorder
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Alpha slider
            Text("透明度", fontSize = 12.sp, color = appTextSecondary())
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = alpha,
                onValueChange = onAlphaChanged,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = FluidCyan,
                    inactiveTrackColor = GlassBorder
                )
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Color Values Display
        Column(
            modifier = Modifier.glassSurface(cornerRadius = 16.dp, glassAlpha = 0.1f, showBorder = true)
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("颜色值", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = appTextPrimary())
                TextButton(onClick = { expandedInfo = !expandedInfo }) {
                    Text(
                        if (expandedInfo) "收起" else "展开",
                        fontSize = 12.sp,
                        color = FluidCyan
                    )
                }
            }

            ColorValueRow("HEX", colorValues.hex, onCopy)
            ColorValueRow("RGB", "rgb(${colorValues.r}, ${colorValues.g}, ${colorValues.b})", onCopy)
            ColorValueRow("RGBA", "rgba(${colorValues.r}, ${colorValues.g}, ${colorValues.b}, ${colorValues.a})", onCopy)

            if (expandedInfo) {
                ColorValueRow("HSV", "hsv(${colorValues.h.toInt()}°, ${(colorValues.s * 100).toInt()}%, ${(colorValues.v * 100).toInt()}%)", onCopy)
                ColorValueRow("HSL", "hsl(${colorValues.hslH.toInt()}°, ${(colorValues.hslS * 100).toInt()}%, ${(colorValues.hslL * 100).toInt()}%)", onCopy)
                ColorValueRow("CMYK", "cmyk(${(colorValues.c * 100).toInt()}%, ${(colorValues.m * 100).toInt()}%, ${(colorValues.y * 100).toInt()}%, ${(colorValues.k * 100).toInt()}%)", onCopy)
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
private fun ColorValueRow(label: String, value: String, onCopy: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 11.sp, color = appTextTertiary(), modifier = Modifier.width(40.dp))
        Text(
            value,
            fontSize = 12.sp,
            color = appTextPrimary(),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        IconButton(
            onClick = { onCopy(value) },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(Icons.Default.ContentCopy, "复制", tint = appTextTertiary(), modifier = Modifier.size(14.dp))
        }
    }
}

// ─── HSV Color Wheel Canvas ──────────────────────────────────────────────────

@Composable
private fun ColorWheel(
    modifier: Modifier = Modifier,
    hue: Float,
    saturation: Float,
    value: Float,
    onColorChanged: (hue: Float, saturation: Float, value: Float) -> Unit
) {
    var size by remember { mutableStateOf(Size.Zero) }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures { change, _ ->
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val dx = change.position.x - centerX
                val dy = change.position.y - centerY
                val maxRadius = minOf(centerX, centerY) * 0.9f
                val dist = sqrt(dx * dx + dy * dy).coerceAtMost(maxRadius)
                val sat = (dist / maxRadius).coerceIn(0f, 1f)
                val angle = (Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).toFloat() + 360f) % 360f
                onColorChanged(angle, sat, value)
            }
            detectTapGestures { offset ->
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val dx = offset.x - centerX
                val dy = offset.y - centerY
                val maxRadius = minOf(centerX, centerY) * 0.9f
                val dist = sqrt(dx * dx + dy * dy).coerceAtMost(maxRadius)
                val sat = (dist / maxRadius).coerceIn(0f, 1f)
                val angle = (Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).toFloat() + 360f) % 360f
                onColorChanged(angle, sat, value)
            }
        }
    ) {
        size = this.size
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val maxRadius = minOf(centerX, centerY) * 0.9f

        // Draw the color wheel
        drawColorWheel(centerX, centerY, maxRadius, value)

        // Draw outer ring
        drawCircle(
            color = Color.White.copy(alpha = 0.15f),
            radius = maxRadius + 3f,
            center = Offset(centerX, centerY),
            style = Stroke(width = 2f)
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.08f),
            radius = maxRadius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 1.5f)
        )
    }
}

private fun DrawScope.drawColorWheel(cx: Float, cy: Float, radius: Float, value: Float) {
    val segments = 360
    val ringCount = 60

    for (ring in 0 until ringCount) {
        val innerR = radius * ring / ringCount
        val outerR = radius * (ring + 1) / ringCount
        val ringSat = (ring + 0.5f) / ringCount

        for (seg in 0 until segments) {
            val startAngle = seg * 2 * PI / segments
            val endAngle = (seg + 1) * 2 * PI / segments
            val hueAngle = seg.toFloat()

            val path = Path().apply {
                moveTo(
                    cx + innerR * cos(startAngle).toFloat(),
                    cy + innerR * sin(startAngle).toFloat()
                )
                lineTo(
                    cx + outerR * cos(startAngle).toFloat(),
                    cy + outerR * sin(startAngle).toFloat()
                )
                arcTo(
                    rect = androidx.compose.ui.geometry.Rect(
                        cx - outerR, cy - outerR, cx + outerR, cy + outerR
                    ),
                    startAngleDegrees = Math.toDegrees(startAngle).toFloat(),
                    sweepAngleDegrees = 360f / segments,
                    forceMoveTo = false
                )
                lineTo(
                    cx + innerR * cos(endAngle).toFloat(),
                    cy + innerR * sin(endAngle).toFloat()
                )
                arcTo(
                    rect = androidx.compose.ui.geometry.Rect(
                        cx - innerR, cy - innerR, cx + innerR, cy + innerR
                    ),
                    startAngleDegrees = Math.toDegrees(endAngle).toFloat(),
                    sweepAngleDegrees = -360f / segments,
                    forceMoveTo = false
                )
                close()
            }

            drawPath(
                path = path,
                color = hsvToColor(hueAngle, ringSat, value)
            )
        }
    }
}

// ─── Palette Tab ─────────────────────────────────────────────────────────────

@Composable
private fun PaletteTab(
    currentPalette: List<Color>,
    paletteColors: List<Long>,
    selectedPaletteType: PaletteType,
    paletteTypes: List<PaletteType>,
    onPaletteTypeChanged: (PaletteType) -> Unit,
    onColorSelected: (Color) -> Unit,
    onSavePalette: () -> Unit,
    savedPalettes: List<SavedPalette>,
    onDeletePalette: (SavedPalette) -> Unit,
    onColorLongPress: (Color) -> Unit
) {
    var pressedIndex by remember { mutableStateOf(-1) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
    ) {
        // Palette Type Selector
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            paletteTypes.forEach { type ->
                val isSelected = selectedPaletteType == type
                Box(
                    modifier = Modifier
                        .glassSurface(
                            cornerRadius = 20.dp,
                            glassAlpha = if (isSelected) 0.2f else 0.08f,
                            showBorder = isSelected
                        )
                        .clickable { onPaletteTypeChanged(type) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        type.label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Light,
                        color = if (isSelected) FluidCyan else TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Current Palette Swatches
        Box(
            modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 20.dp, glassAlpha = 0.15f, showBorder = true)
                .padding(16.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${selectedPaletteType.label}配色",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = appTextPrimary()
                    )
                    IconButton(
                        onClick = onSavePalette,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Favorite, "保存", tint = FluidPink, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Swatches
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    currentPalette.forEachIndexed { index, color ->
                        val scale by animateFloatAsState(
                            if (pressedIndex == index) 0.9f else 1f,
                            spring(dampingRatio = 0.4f, stiffness = 500f),
                            label = "swatch"
                        )

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .scale(scale)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = {
                                            onColorSelected(color)
                                        },
                                        onLongPress = {
                                            onColorLongPress(color)
                                        }
                                    )
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                colorToHex(color),
                                fontSize = 9.sp,
                                color = appTextTertiary(),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // Saved Palettes
        if (savedPalettes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "保存的调色板",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = appTextPrimary(),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            savedPalettes.forEach { palette ->
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .glassSurface(cornerRadius = 12.dp, glassAlpha = 0.08f, showBorder = true)
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(palette.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = appTextPrimary())
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                palette.colors.forEach { colorLong ->
                                    Box(
                                        modifier = Modifier.size(16.dp).clip(CircleShape)
                                            .background(Color(colorLong.toULong()))
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = { onDeletePalette(palette) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Delete, "删除", tint = AccentDanger, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

// ─── Saved Colors Tab ────────────────────────────────────────────────────────

@Composable
private fun SavedColorsTab(
    savedColors: List<SavedColor>,
    isGridView: Boolean,
    onToggleView: () -> Unit,
    onColorClick: (SavedColor) -> Unit,
    onDeleteColor: (SavedColor) -> Unit,
    onCopy: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "已保存颜色 (${savedColors.size})",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = appTextPrimary()
            )
            IconButton(onClick = onToggleView) {
                Icon(
                    if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                    if (isGridView) "列表" else "网格",
                    tint = FluidCyan,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (savedColors.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "暂无保存的颜色\n在色轮页面点击收藏按钮保存",
                    fontSize = 13.sp,
                    color = appTextTertiary(),
                    textAlign = TextAlign.Center
                )
            }
        } else if (isGridView) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(savedColors) { saved ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { onColorClick(saved) }
                            .pointerInput(saved.id) {
                                detectTapGestures(onLongPress = { onDeleteColor(saved) })
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .glassSurface(cornerRadius = 12.dp, glassAlpha = 0.15f, showBorder = true)
                                .background(
                                    Color(saved.color.toULong()),
                                    RoundedCornerShape(12.dp)
                                )
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            saved.name.ifBlank { saved.hex },
                            fontSize = 10.sp,
                            color = appTextSecondary(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            saved.hex,
                            fontSize = 9.sp,
                            color = appTextTertiary(),
                            maxLines = 1
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(savedColors) { saved ->
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .glassSurface(cornerRadius = 12.dp, glassAlpha = 0.08f, showBorder = true)
                            .clickable { onColorClick(saved) }
                            .padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(saved.color.toULong()))
                                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        saved.name.ifBlank { saved.hex },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = appTextPrimary()
                                    )
                                    Text(saved.hex, fontSize = 11.sp, color = appTextTertiary())
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(
                                    onClick = { onCopy(saved.hex) },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, "复制", tint = appTextTertiary(), modifier = Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = { onDeleteColor(saved) },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(Icons.Default.Delete, "删除", tint = AccentDanger, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Eyedropper Tab ──────────────────────────────────────────────────────────

@Composable
private fun EyedropperTab(
    onColorPicked: (Color) -> Unit,
    eyedropperColor: Color
) {
    var pickedColor by remember { mutableStateOf(eyedropperColor) }
    var gradientImageSize by remember { mutableStateOf(Size.Zero) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
    ) {
        Text(
            "色带取色器",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = appTextPrimary(),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            "点击或拖动下方色带选取颜色",
            fontSize = 12.sp,
            color = appTextTertiary(),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Rainbow gradient for picking
        Box(
            modifier = Modifier.fillMaxWidth().height(160.dp)
                .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.1f, showBorder = true)
                .clip(RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (gradientImageSize.width > 0 && gradientImageSize.height > 0) {
                            val x = (offset.x / gradientImageSize.width).coerceIn(0f, 1f)
                            val y = (offset.y / gradientImageSize.height).coerceIn(0f, 1f)
                            pickedColor = gradientColorFromPosition(x, y)
                            onColorPicked(pickedColor)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        if (gradientImageSize.width > 0 && gradientImageSize.height > 0) {
                            val x = (change.position.x / gradientImageSize.width).coerceIn(0f, 1f)
                            val y = (change.position.y / gradientImageSize.height).coerceIn(0f, 1f)
                            pickedColor = gradientColorFromPosition(x, y)
                            onColorPicked(pickedColor)
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                gradientImageSize = this.size
                val w = size.width
                val h = size.height

                // Full spectrum gradient: hue varies horizontally, brightness vertically
                for (px in 0 until w.toInt() step 3) {
                    val hue = (px / w) * 360f
                    // Top half: full saturation, bottom half: varying brightness
                    for (py in 0 until h.toInt() step 3) {
                        val yRatio = py / h
                        val sat = if (yRatio < 0.5f) 1f - yRatio * 2f * 0.6f else 0.4f + (yRatio - 0.5f) * 2f * 0.6f
                        val bright = if (yRatio < 0.5f) 1f else 1f - (yRatio - 0.5f) * 2f * 0.6f
                        drawRect(
                            color = hsvToColor(hue, sat, bright),
                            topLeft = Offset(px.toFloat(), py.toFloat()),
                            size = Size(3f, 3f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Picked color display
        Row(
            modifier = Modifier.fillMaxWidth()
                .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.15f, showBorder = true)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(pickedColor)
                    .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text("选中颜色", fontSize = 12.sp, color = appTextTertiary())
                Text(colorToHex(pickedColor), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = appTextPrimary())
                val r = (pickedColor.red * 255).toInt()
                val g = (pickedColor.green * 255).toInt()
                val b = (pickedColor.blue * 255).toInt()
                Text("RGB($r, $g, $b)", fontSize = 12.sp, color = appTextSecondary())
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Built-in gradient presets
        Text("预设渐变色带", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = appTextPrimary())
        Spacer(modifier = Modifier.height(8.dp))

        val presetGradients = listOf(
            "暖色" to listOf(Color(0xFFFF0000), Color(0xFFFF8800), Color(0xFFFFEE00)),
            "冷色" to listOf(Color(0xFF00FFFF), Color(0xFF0066FF), Color(0xFF3300FF)),
            "自然" to listOf(Color(0xFF00FF00), Color(0xFF88FF00), Color(0xFF884400)),
            "粉彩" to listOf(Color(0xFFFF88CC), Color(0xFFCC88FF), Color(0xFF88CCFF)),
            "日落" to listOf(Color(0xFFFF4400), Color(0xFFFF0066), Color(0xFF8800AA)),
            "海洋" to listOf(Color(0xFF003366), Color(0xFF006688), Color(0xFF00AA88))
        )

        presetGradients.forEach { (name, colors) ->
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.horizontalGradient(colors)
                    )
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val x = (offset.x / size.width).coerceIn(0f, 1f)
                            val color = lerpColors(colors, x)
                            pickedColor = color
                            onColorPicked(color)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .glassSurface(cornerRadius = 12.dp, glassAlpha = 0.2f, showBorder = false)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

private fun gradientColorFromPosition(x: Float, y: Float): Color {
    val hue = x * 360f
    val sat = if (y < 0.5f) 1f - y * 2f * 0.6f else 0.4f + (y - 0.5f) * 2f * 0.6f
    val bright = if (y < 0.5f) 1f else 1f - (y - 0.5f) * 2f * 0.6f
    return hsvToColor(hue, sat, bright)
}

private fun lerpColors(colors: List<Color>, fraction: Float): Color {
    if (colors.isEmpty()) return Color.Black
    if (colors.size == 1) return colors[0]
    val segmentCount = colors.size - 1
    val scaledFraction = fraction * segmentCount
    val index = scaledFraction.toInt().coerceAtMost(segmentCount - 1)
    val localFrac = scaledFraction - index
    val c1 = colors[index]
    val c2 = colors[index + 1]
    return Color(
        red = c1.red + (c2.red - c1.red) * localFrac,
        green = c1.green + (c2.green - c1.green) * localFrac,
        blue = c1.blue + (c2.blue - c1.blue) * localFrac,
        alpha = c1.alpha + (c2.alpha - c1.alpha) * localFrac
    )
}