package com.liquidglass.desktop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.liquidglass.desktop.system.AnnouncementManager
import com.liquidglass.desktop.system.BetaPioneerManager
import com.liquidglass.desktop.system.LogUploadManager
import com.liquidglass.desktop.theme.LiquidGlassTheme
import com.liquidglass.desktop.ui.AboutScreen
import com.liquidglass.desktop.ui.BetaPioneerScreen
import com.liquidglass.desktop.ui.FluidBackground
import com.liquidglass.desktop.ui.HomeScreen
import kotlin.math.PI

/** 侧边栏可导航的屏幕 */
enum class Screen { Home, About, BetaPioneer }

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
                    onSelect = { currentScreen = it }
                )

                // 内容区
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(16.dp)
                ) {
                    when (currentScreen) {
                        Screen.Home -> HomeScreen(announcementManager = announcementManager)
                        Screen.About -> AboutScreen()
                        Screen.BetaPioneer -> BetaPioneerScreen(betaPioneerManager = betaPioneerManager)
                    }
                }
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
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = if (selected) LiquidGlassTheme.accentPrimary.copy(alpha = 0.25f)
                else Color.Transparent,
                onClick = { onSelect(item.screen) }
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
    NavItem(Screen.About, "关于"),
    NavItem(Screen.BetaPioneer, "Beta 先锋")
)
