package com.liquidglass.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * 歌词显示设置 —— 全局可观察 + SharedPreferences 持久化。
 *
 * 由 [LyricsSettingsPage] 写入，[LrcLyricsView]/[YrcLyricsView] 读取并应用。
 * 覆盖：字体、主题色、时间偏移、字号、行间距、翻译开关、背景透明度。
 */
object LyricsSettings {

    private const val PREFS = "lyrics_settings"
    private const val K_FONT = "font"
    private const val K_COLOR = "color"
    private const val K_OFFSET = "offset_ms"
    private const val K_FONT_SIZE = "font_size"
    private const val K_LINE_SPACING = "line_spacing"
    private const val K_TRANSLATION = "show_translation"
    private const val K_BG_OPACITY = "bg_opacity"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fontFamily = LyricsFont.entries.getOrElse(prefs.getInt(K_FONT, 0)) { LyricsFont.DEFAULT }
        themeColor = LyricsThemeColor.entries.getOrElse(prefs.getInt(K_COLOR, 0)) { LyricsThemeColor.CYAN }
        timeOffsetMs = prefs.getLong(K_OFFSET, 0L)
        fontSize = prefs.getFloat(K_FONT_SIZE, 17f)
        lineSpacing = prefs.getFloat(K_LINE_SPACING, 14f)
        showTranslation = prefs.getBoolean(K_TRANSLATION, true)
        bgOpacity = prefs.getFloat(K_BG_OPACITY, 0.3f)
    }

    /** 字体家族 */
    var fontFamily by mutableStateOf(LyricsFont.DEFAULT)
        private set
    /** 主题色（活动行高亮色） */
    var themeColor by mutableStateOf(LyricsThemeColor.CYAN)
        private set
    /** 歌词时间偏移（ms，正值延后显示，负值提前显示） */
    var timeOffsetMs by mutableStateOf(0L)
        private set
    /** 活动行字号（sp） */
    var fontSize by mutableStateOf(17f)
        private set
    /** 行间距（dp） */
    var lineSpacing by mutableStateOf(14f)
        private set
    /** 是否显示翻译 */
    var showTranslation by mutableStateOf(true)
        private set
    /** 歌词背景透明度（0~1） */
    var bgOpacity by mutableStateOf(0.3f)
        private set

    fun updateFont(v: LyricsFont) {
        fontFamily = v
        if (!::prefs.isInitialized) return
        prefs.edit().putInt(K_FONT, v.ordinal).apply()
    }
    fun updateColor(v: LyricsThemeColor) {
        themeColor = v
        if (!::prefs.isInitialized) return
        prefs.edit().putInt(K_COLOR, v.ordinal).apply()
    }
    fun updateOffset(v: Long) {
        timeOffsetMs = v
        if (!::prefs.isInitialized) return
        prefs.edit().putLong(K_OFFSET, v).apply()
    }
    fun updateFontSize(v: Float) {
        fontSize = v
        if (!::prefs.isInitialized) return
        prefs.edit().putFloat(K_FONT_SIZE, v).apply()
    }
    fun updateLineSpacing(v: Float) {
        lineSpacing = v
        if (!::prefs.isInitialized) return
        prefs.edit().putFloat(K_LINE_SPACING, v).apply()
    }
    fun updateShowTranslation(v: Boolean) {
        showTranslation = v
        if (!::prefs.isInitialized) return
        prefs.edit().putBoolean(K_TRANSLATION, v).apply()
    }
    fun updateBgOpacity(v: Float) {
        bgOpacity = v
        if (!::prefs.isInitialized) return
        prefs.edit().putFloat(K_BG_OPACITY, v).apply()
    }

    /** 应用时间偏移后的播放位置（供歌词行匹配使用） */
    fun adjustedPosition(positionMs: Long): Long = (positionMs + timeOffsetMs).coerceAtLeast(0L)
}

/** 歌词字体选项 —— v2.9.0 扩充至 9 种，使用 Android 系统内置字体族保证全设备生效。
 *  原 4 种中 Cursive 在多数国产 ROM 上回退为默认字体（视觉无变化），
 *  现改用 Typeface.create(familyName) 显式指定系统字体族，全设备可区分。 */
enum class LyricsFont(val label: String, val family: FontFamily) {
    DEFAULT("默认", FontFamily.Default),
    SANS_CONDENSED("窄体", FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))),
    SANS_LIGHT("细体", FontFamily(Typeface.create("sans-serif-light", Typeface.NORMAL))),
    SANS_THIN("超细", FontFamily(Typeface.create("sans-serif-thin", Typeface.NORMAL))),
    SANS_MEDIUM("中黑", FontFamily(Typeface.create("sans-serif-medium", Typeface.NORMAL))),
    SERIF("衬线", FontFamily.Serif),
    SERIF_MONO("衬线等宽", FontFamily(Typeface.create("serif-monospace", Typeface.NORMAL))),
    MONOSPACE("等宽", FontFamily.Monospace),
    CASUAL("手写", FontFamily.Cursive)
}

/** 歌词主题色选项（鲜明色） */
enum class LyricsThemeColor(val label: String, val color: Color) {
    CYAN("液态青", Color(0xFF22D3EE)),
    ORANGE("活力橙", Color(0xFFFF8A3D)),
    PINK("樱粉", Color(0xFFFF5C8A)),
    GREEN("翡翠绿", Color(0xFF34D399)),
    YELLOW("明黄", Color(0xFFFBBF24)),
    WHITE("纯白", Color(0xFFFFFFFF))
}
