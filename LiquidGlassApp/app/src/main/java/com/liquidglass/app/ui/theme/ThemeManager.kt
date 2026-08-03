package com.liquidglass.app.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import java.io.File

/**
 * 应用主题数据模型。
 *
 * [isLight] 为 true 时表示浅色主题（淡雅白等），玻璃层与文字色需要反转，
 * 否则文字会不可见。FluidBackground 等组件会据此调整渲染参数。
 */
data class AppTheme(
    val id: String,
    val name: String,
    val description: String,
    val isLight: Boolean,
    val bgDark: Color,
    val bgDark2: Color,
    val glassClear: Color,
    val glassLight: Color,
    val glassMedium: Color,
    val glassBorder: Color,
    val glassHighlight: Color,
    val glassBright: Color,
    val fluidCyan: Color,
    val fluidPurple: Color,
    val fluidPink: Color,
    val fluidBlue: Color,
    val fluidTeal: Color,
    val fluidOrange: Color,
    val accentPrimary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color
) {
    val fluidGradient: List<Color> get() = listOf(fluidCyan, fluidPurple, fluidPink, fluidBlue, fluidTeal)
}

/**
 * 内置主题。v2.9.2 大道至简：仅保留纯黑、纯白两套。
 * "系统自动"由 ThemeManager 根据系统深浅色模式动态选择，不在此列表中。
 */
object Themes {
    /** 纯黑主题 —— 大道至简，液态玻璃在纯黑背景上折射最通透 */
    val dark = AppTheme(
        id = "dark",
        name = "深色",
        description = "纯黑背景，通透液态玻璃",
        isLight = false,
        bgDark = Color(0xFF000000),
        bgDark2 = Color(0xFF0A0A0F),
        glassClear = Color(0x10FFFFFF),
        glassLight = Color(0x18FFFFFF),
        glassMedium = Color(0x22FFFFFF),
        glassBorder = Color(0x28FFFFFF),
        glassHighlight = Color(0x35FFFFFF),
        glassBright = Color(0x50FFFFFF),
        fluidCyan = Color(0xFF00D4FF),
        fluidPurple = Color(0xFF7B5CFC),
        fluidPink = Color(0xFFFF3B8B),
        fluidBlue = Color(0xFF3366FF),
        fluidTeal = Color(0xFF00E5A0),
        fluidOrange = Color(0xFFFF6B35),
        accentPrimary = Color(0xFF5B9AFF),
        textPrimary = Color(0xFFF0F0F5),
        textSecondary = Color(0x99FFFFFF),
        textTertiary = Color(0x55FFFFFF)
    )

    /** 纯白主题 —— 大道至简，浅色背景下玻璃用深色叠加 */
    val light = AppTheme(
        id = "light",
        name = "浅色",
        description = "纯白背景，淡雅液态玻璃",
        isLight = true,
        bgDark = Color(0xFFF5F5F8),
        bgDark2 = Color(0xFFEAECF2),
        glassClear = Color(0x10000000),
        glassLight = Color(0x18000000),
        glassMedium = Color(0x22000000),
        glassBorder = Color(0x28000000),
        glassHighlight = Color(0x35000000),
        glassBright = Color(0x40000000),
        fluidCyan = Color(0xFF00A8D8),
        fluidPurple = Color(0xFF6B4CE0),
        fluidPink = Color(0xFFE02B7B),
        fluidBlue = Color(0xFF2B56E0),
        fluidTeal = Color(0xFF00B585),
        fluidOrange = Color(0xFFE0562B),
        accentPrimary = Color(0xFF2B7AE0),
        textPrimary = Color(0xFF1A1A22),
        textSecondary = Color(0x991A1A22),
        textTertiary = Color(0x551A1A22)
    )

    val all: List<AppTheme> = listOf(dark, light)

    fun byId(id: String): AppTheme = all.firstOrNull { it.id == id } ?: dark
}

/**
 * 主题管理器：持久化用户选择 + 加载资源包中的自定义主题。
 *
 * v2.9.2 大道至简：支持三种选择 —— 系统自动(auto) / 深色(dark) / 浅色(light)。
 * 选择"系统自动"时，根据系统深浅色模式动态切换，由 [onSystemDarkModeChanged] 驱动。
 *
 * 当前实际生效的主题通过 [currentThemeState] 暴露为可观察状态，
 * UI 组件读取 [LocalAppTheme] 即可响应切换。
 */
object ThemeManager {
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_THEME_ID = "theme_id"

    /** 系统自动 —— 跟随系统深浅色模式 */
    const val THEME_AUTO = "auto"

    /** 用户选择的主题 id（auto / dark / light / 自定义主题 id） */
    val selectedThemeIdState = mutableStateOf(THEME_AUTO)

    val currentThemeState = mutableStateOf(Themes.dark)
    val customThemesState = mutableStateOf<List<AppTheme>>(emptyList())

    val currentTheme: AppTheme get() = currentThemeState.value
    val selectedThemeId: String get() = selectedThemeIdState.value

    /** 当前系统是否深色模式（由 LiquidGlassTheme 通过 isSystemInDarkTheme 同步） */
    private var systemDarkMode = true

    /**
     * 可选主题选项列表（用于主题选择器 UI）。
     * 始终包含"系统自动"，再追加内置 dark/light，最后追加资源包自定义主题。
     */
    fun availableThemes(): List<AppTheme> =
        listOf(Themes.dark, Themes.light) + customThemesState.value

    /** 是否为"系统自动"模式 */
    fun isAutoMode(): Boolean = selectedThemeIdState.value == THEME_AUTO

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadCustomThemes(context)
        val savedId = prefs.getString(KEY_THEME_ID, THEME_AUTO) ?: THEME_AUTO
        selectedThemeIdState.value = savedId
        resolveCurrentTheme()
    }

    /**
     * 按 id 设置主题。传入 [ThemeManager.THEME_AUTO] 则切换为系统自动模式。
     */
    fun setTheme(context: Context, themeId: String) {
        selectedThemeIdState.value = themeId
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME_ID, themeId).apply()
        resolveCurrentTheme()
    }

    /** 兼容旧 API：按 AppTheme 对象设置 */
    fun setTheme(context: Context, theme: AppTheme) = setTheme(context, theme.id)

    /**
     * 系统深浅色模式变化时调用（由 LiquidGlassTheme 的 isSystemInDarkTheme 驱动）。
     * 仅在"系统自动"模式下才需要重新解析实际主题。
     */
    fun onSystemDarkModeChanged(isDark: Boolean) {
        val changed = isDark != systemDarkMode
        systemDarkMode = isDark
        if (changed && isAutoMode()) {
            resolveCurrentTheme()
        }
    }

    /** 根据当前 selectedThemeId + systemDarkMode 解析出实际生效的 AppTheme */
    private fun resolveCurrentTheme() {
        val id = selectedThemeIdState.value
        currentThemeState.value = when {
            id == THEME_AUTO -> if (systemDarkMode) Themes.dark else Themes.light
            id == Themes.dark.id -> Themes.dark
            id == Themes.light.id -> Themes.light
            else -> availableThemes().firstOrNull { it.id == id } ?: Themes.dark
        }
    }

    /**
     * 从交互资源包 + 基础资源包的 themes/ 目录加载自定义主题 JSON。
     * JSON 格式见 generate_resource_pack.py 的主题生成部分。
     */
    fun loadCustomThemes(context: Context) {
        val result = mutableListOf<AppTheme>()
        val files = mutableListOf<File>()
        files += com.liquidglass.app.ResourceManager.getInteractionThemeFiles(context)
        files += com.liquidglass.app.ResourceManager.getThemeFiles(context)
        for (f in files) {
            parseThemeJson(f)?.let { result.add(it) }
        }
        customThemesState.value = result.distinctBy { it.id }
    }

    private fun parseThemeJson(file: File): AppTheme? {
        return try {
            val json = JSONObject(file.readText())
            val id = json.optString("id", file.nameWithoutExtension)
            val isLight = json.optBoolean("isLight", false)
            AppTheme(
                id = id,
                name = json.optString("name", id),
                description = json.optString("description", ""),
                isLight = isLight,
                bgDark = parseColor(json, "bgDark", if (isLight) 0xFFF4F5F8 else 0xFF08080F),
                bgDark2 = parseColor(json, "bgDark2", if (isLight) 0xFFEAECF2 else 0xFF0D0D1A),
                glassClear = parseColor(json, "glassClear", 0x10FFFFFF),
                glassLight = parseColor(json, "glassLight", 0x18FFFFFF),
                glassMedium = parseColor(json, "glassMedium", 0x22FFFFFF),
                glassBorder = parseColor(json, "glassBorder", 0x28FFFFFF),
                glassHighlight = parseColor(json, "glassHighlight", 0x35FFFFFF),
                glassBright = parseColor(json, "glassBright", 0x50FFFFFF),
                fluidCyan = parseColor(json, "fluidCyan", 0xFF00D4FF),
                fluidPurple = parseColor(json, "fluidPurple", 0xFF7B5CFC),
                fluidPink = parseColor(json, "fluidPink", 0xFFFF3B8B),
                fluidBlue = parseColor(json, "fluidBlue", 0xFF3366FF),
                fluidTeal = parseColor(json, "fluidTeal", 0xFF00E5A0),
                fluidOrange = parseColor(json, "fluidOrange", 0xFFFF6B35),
                accentPrimary = parseColor(json, "accentPrimary", 0xFF5B9AFF),
                textPrimary = parseColor(json, "textPrimary", if (isLight) 0xFF222630 else 0xFFF0F0F5),
                textSecondary = parseColor(json, "textSecondary", if (isLight) 0x99222630 else 0x99FFFFFF),
                textTertiary = parseColor(json, "textTertiary", if (isLight) 0x55222630 else 0x55FFFFFF)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseColor(json: JSONObject, key: String, default: Long): Color {
        val s = json.optString(key, "")
        if (s.isEmpty()) return Color(default.toInt())
        return try {
            val v = s.removePrefix("#").toLong(16)
            Color(v.toInt())
        } catch (_: Exception) {
            Color(default.toInt())
        }
    }
}

/** CompositionLocal：UI 组件通过 LocalAppTheme.current 读取当前主题色 */
val LocalAppTheme = compositionLocalOf { Themes.all.first() }

@Composable
@ReadOnlyComposable
fun currentAppTheme(): AppTheme = LocalAppTheme.current

// ── 主题色 Composable 访问器 ──────────────────────────────────────
// 供各屏幕替换静态 BgDark / TextPrimary 等使用，确保切换主题时即时生效。
// 用法：Modifier.background(appBgColor())  /  color = appTextPrimary()

@Composable
@ReadOnlyComposable
fun appBgColor(): Color = LocalAppTheme.current.bgDark

@Composable
@ReadOnlyComposable
fun appBgColor2(): Color = LocalAppTheme.current.bgDark2

@Composable
@ReadOnlyComposable
fun appTextPrimary(): Color = LocalAppTheme.current.textPrimary

@Composable
@ReadOnlyComposable
fun appTextSecondary(): Color = LocalAppTheme.current.textSecondary

@Composable
@ReadOnlyComposable
fun appTextTertiary(): Color = LocalAppTheme.current.textTertiary

@Composable
@ReadOnlyComposable
fun appAccentPrimary(): Color = LocalAppTheme.current.accentPrimary
