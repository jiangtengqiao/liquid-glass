package com.liquidglass.desktop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.liquidglass.desktop.system.AnnouncementManager
import com.liquidglass.desktop.system.BetaPioneerManager
import com.liquidglass.desktop.system.LogUploadManager
import com.liquidglass.desktop.system.TranslationManager
import com.liquidglass.desktop.theme.LiquidGlassTheme
import com.liquidglass.desktop.ui.AboutScreen
import com.liquidglass.desktop.ui.BetaPioneerScreen
import com.liquidglass.desktop.ui.FluidBackground
import com.liquidglass.desktop.ui.HomeScreen
import com.liquidglass.desktop.ui.TranslationScreen
import com.liquidglass.desktop.ui.tools.ToolScreen
import com.liquidglass.desktop.ui.tools.ToolType
import java.util.prefs.Preferences
import kotlin.math.PI

/** 侧边栏可导航的屏幕 */
enum class Screen { Home, Translation, About, BetaPioneer }

/**
 * 应用入口：创建窗口并托管 App
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "灵工坊 - LiquidGlass Desktop",
        state = WindowState(
            width = 1200.dp,
            height = 800.dp,
            position = WindowPosition(Alignment.Center)
        )
    ) {
        App()
    }
}

/**
 * 顶级 Composable
 * - 初始化三大系统（AnnouncementManager / LogUploadManager / BetaPioneerManager 的 Desktop 版本）
 * - 侧边栏导航（首页 / 关于 / Beta 先锋）+ 内容区
 * - 流体动画背景
 */
@Composable
fun App() {
    // 初始化三大系统（Desktop 版本）
    val announcementManager = remember { AnnouncementManager() }
    val logUploadManager = remember { LogUploadManager() }
    val betaPioneerManager = remember { BetaPioneerManager() }
    val translationManager = remember { TranslationManager() }

    // 启动时安装全局崩溃处理器
    DisposableEffect(logUploadManager) {
        logUploadManager.installCrashHandler()
        onDispose { /* 进程退出时无需特别清理 */ }
    }

    // 启动时拉取公告
    LaunchedEffect(announcementManager) {
        announcementManager.refresh()
    }

    var currentScreen by remember { mutableStateOf(Screen.Home) }
    var selectedTool by remember { mutableStateOf<ToolType?>(null) }

    // v2.9.3：首次启动询问是否创建桌面快捷方式
    val shortcutPrefs = remember { Preferences.userNodeForPackage(ShortcutPrefs::class.java) }
    var showShortcutDialog by remember {
        mutableStateOf(!shortcutPrefs.getBoolean("shortcut_asked", false))
    }
    var shortcutResult by remember { mutableStateOf<String?>(null) }

    // 流体背景动画驱动时间
    val transition = rememberInfiniteTransition(label = "fluid")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "time"
    )

    MaterialTheme(
        colors = darkColors(
            background = LiquidGlassTheme.backgroundColor,
            surface = LiquidGlassTheme.surfaceColor,
            onBackground = LiquidGlassTheme.onSurfaceColor,
            onSurface = LiquidGlassTheme.onSurfaceColor,
            primary = LiquidGlassTheme.accentPrimary,
            secondary = LiquidGlassTheme.accentSecondary
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LiquidGlassTheme.backgroundColor)
        ) {
            // 流体动画背景
            FluidBackground(time = time, modifier = Modifier.fillMaxSize())

            Row(modifier = Modifier.fillMaxSize()) {
                // 侧边栏导航
                Sidebar(
                    current = currentScreen,
                    onSelect = {
                        currentScreen = it
                        selectedTool = null
                    }
                )

                // 内容区
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(16.dp)
                ) {
                    // 工具详情页优先显示（覆盖在 Home 之上）
                    selectedTool?.let { tool ->
                        ToolScreen(
                            tool = tool,
                            onBack = { selectedTool = null }
                        )
                    } ?: when (currentScreen) {
                        Screen.Home -> HomeScreen(
                            announcementManager = announcementManager,
                            onToolClick = { selectedTool = it }
                        )
                        Screen.Translation -> TranslationScreen(manager = translationManager)
                        Screen.About -> AboutScreen()
                        Screen.BetaPioneer -> BetaPioneerScreen(betaPioneerManager = betaPioneerManager)
                    }
                }
            }

            // v2.9.3：首次启动桌面快捷方式询问弹窗
            if (showShortcutDialog) {
                AlertDialog(
                    onDismissRequest = {
                        shortcutPrefs.putBoolean("shortcut_asked", true)
                        showShortcutDialog = false
                    },
                    title = { Text("创建桌面快捷方式？") },
                    text = {
                        Column {
                            Text("是否在桌面创建 LiquidGlass 的快捷方式，方便日常使用？")
                            shortcutResult?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(it, color = LiquidGlassTheme.onSurfaceMuted, fontSize = 12.sp)
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            val exePath = ProcessHandle.current()
                                .info().command().orElse("")
                            val ok = if (exePath.isNotBlank()) {
                                betaPioneerManager.createDesktopShortcut(exePath, "LiquidGlass")
                            } else false
                            shortcutResult = if (ok) "已创建桌面快捷方式" else "创建失败，可稍后在关于页手动创建"
                            shortcutPrefs.putBoolean("shortcut_asked", true)
                            shortcutPrefs.putBoolean("shortcut_created", ok)
                            showShortcutDialog = false
                        }) { Text("创建") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = {
                            shortcutPrefs.putBoolean("shortcut_asked", true)
                            showShortcutDialog = false
                        }) { Text("暂不") }
                    }
                )
            }
        }
    }
}

/** 侧边栏导航 */
@Composable
private fun Sidebar(
    current: Screen,
    onSelect: (Screen) -> Unit
) {
    Column(
        modifier = Modifier
            .width(200.dp)
            .fillMaxHeight()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "灵工坊",
            color = LiquidGlassTheme.onSurfaceColor,
            style = MaterialTheme.typography.h5,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "LiquidGlass Desktop",
            color = LiquidGlassTheme.onSurfaceMuted,
            style = MaterialTheme.typography.caption
        )
        Spacer(Modifier.height(16.dp))

        navItems.forEach { item ->
            val selected = current == item.screen
            // v2.9.3 修复：Surface(onClick) 的点击区域未被 shape 裁剪，
            // 导致圆角矩形外的尖角区域也响应点击（用户反馈的"尖尖也跟着反馈"）。
            // 改用 Box + clip(RoundedCornerShape) + clickable，确保点击区域严格限制在圆角内。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) LiquidGlassTheme.accentPrimary.copy(alpha = 0.25f) else Color.Transparent)
                    .clickable { onSelect(item.screen) }
            ) {
                Text(
                    text = item.label,
                    color = if (selected) LiquidGlassTheme.onSurfaceColor
                    else LiquidGlassTheme.onSurfaceMuted,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

private data class NavItem(val screen: Screen, val label: String)

private val navItems: List<NavItem> = listOf(
    NavItem(Screen.Home, "首页"),
    NavItem(Screen.Translation, "翻译中心"),
    NavItem(Screen.About, "关于"),
    NavItem(Screen.BetaPioneer, "Beta 先锋")
)

/** 桌面快捷方式 Preferences 的命名空间标记 */
private object ShortcutPrefs
