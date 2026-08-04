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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
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

    // v2.9.4：启动时自动检查更新，发现新版本弹窗提醒（每个版本只提醒一次）
    var showUpdateDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val info = com.liquidglass.desktop.system.UpdateChecker.checkForUpdate(force = false)
        if (info != null) {
            // 检查是否已提醒过该版本
            val notifiedVersion = shortcutPrefs.get("notified_update_version", "")
            if (notifiedVersion != info.version) {
                showUpdateDialog = true
                shortcutPrefs.put("notified_update_version", info.version)
            }
        }
    }

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

            // v2.9.4：启动时发现新版本的更新弹窗
            if (showUpdateDialog) {
                com.liquidglass.desktop.ui.UpdateDialog(
                    onDismiss = { showUpdateDialog = false }
                )
            }
        }
    }
}

/** 侧边栏导航 - Material3 NavigationRail 风格 */
@Composable
private fun Sidebar(
    current: Screen,
    onSelect: (Screen) -> Unit
) {
    Column(
        modifier = Modifier
            .width(220.dp)
            .fillMaxHeight()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 品牌区
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
        Spacer(Modifier.height(20.dp))

        navItems.forEach { item ->
            val selected = current == item.screen
            var hovered by remember { mutableStateOf(false) }

            // 选中态：左侧竖条 + 玻璃背景；hover 态：轻微背景
            val bgColor = when {
                selected -> LiquidGlassTheme.accentPrimary.copy(alpha = 0.22f)
                hovered -> LiquidGlassTheme.surfaceVariant.copy(alpha = 0.6f)
                else -> Color.Transparent
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(bgColor)
                    .clickable { onSelect(item.screen) }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                when (event.type) {
                                    PointerEventType.Enter -> hovered = true
                                    PointerEventType.Exit -> hovered = false
                                    else -> {}
                                }
                            }
                        }
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 图标（unicode 字符，避免引入图标库依赖）
                    Text(
                        text = item.icon,
                        color = if (selected) LiquidGlassTheme.accentSecondary
                        else LiquidGlassTheme.onSurfaceMuted,
                        fontSize = 18.sp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = item.label,
                        color = if (selected) LiquidGlassTheme.onSurfaceColor
                        else LiquidGlassTheme.onSurfaceMuted,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

private data class NavItem(val screen: Screen, val label: String, val icon: String)

private val navItems: List<NavItem> = listOf(
    NavItem(Screen.Home, "首页", "⌂"),
    NavItem(Screen.Translation, "翻译中心", "文"),
    NavItem(Screen.About, "关于", "ⓘ"),
    NavItem(Screen.BetaPioneer, "Beta 先锋", "★")
)

/** 桌面快捷方式 Preferences 的命名空间标记 */
private object ShortcutPrefs
