package com.liquidglass.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.app.ResourceManager
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen {
    HOME, CLOCK, CALCULATOR, TODO, ABOUT,
    COUNTDOWN, NOTE, UNIT_CONVERTER, PASSWORD_GEN, BMI,
    GALLERY, AUDIO_PLAYER, FILE_MANAGER, QR_CODE, DRAWING,
    COMPASS, FLASHLIGHT, COLOR_PICKER, CALENDAR, MUSIC,
    LEGAL_CENTER
}

data class ToolItem(
    val screen: Screen,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val gradientColors: List<Color>
)

@Composable
fun HomeScreen() {
    var currentScreen by remember { mutableStateOf(Screen.HOME) }

    // 通知点击跳转：观察 AppRouter.pendingRoute，变化时跳转到对应功能页并消费。
    // 解决"通知点击只跳首页/黑屏不跳转"——现可直达音乐/关于/指南针等具体功能页。
    // 修复黑屏：增加 LaunchedEffect(Unit) 首次组合时也检查 pendingRoute，
    // 防止冷启动时 route 在 HomeScreen 组合前设置但因 snapshot 传播时序丢失。
    val pendingRoute by com.liquidglass.app.AppRouter.pendingRoute
    LaunchedEffect(pendingRoute) {
        val route = com.liquidglass.app.AppRouter.consumeRoute()
        route?.let {
            runCatching { Screen.valueOf(it) }.getOrNull()?.let { screen ->
                currentScreen = screen
            }
        }
    }
    // 首次组合时也检查一次（兜底冷启动场景）
    LaunchedEffect(Unit) {
        val route = com.liquidglass.app.AppRouter.consumeRoute()
        route?.let {
            runCatching { Screen.valueOf(it) }.getOrNull()?.let { screen ->
                currentScreen = screen
            }
        }
    }

    // 动画无缝循环终极修复（v2.8.7）：
    // 原方案 infiniteTransition + RepeatMode.Restart 在到达 targetValue 后瞬间跳回 0，
    // 即使 targetValue=20π 也只能保证"频率为 0.1 整数倍"的 sin/cos 项无缝，
    // 而 drawFluidBlobs/drawGlowCircles 等用了 0.17/0.25/0.12 等非谐波频率，
    // Restart 时这些项会产生肉眼可见的跳变 → 动画割裂。
    // 终极方案：用真实经过时间驱动（withFrameNanos），时间单调递增永不重启，
    // 所有 sin/cos 自然连续。取模 1000π 防浮点精度下降（1000π 是任意 0.001
    // 整数倍频率的整数周期，所有绘制频率均满足）。
    val animTime by produceState(0f) {
        val startNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val elapsedSec = (now - startNanos) / 1_000_000_000f
                value = (elapsedSec * 0.5f) % 3141.5927f // 1000π
            }
        }
    }

    val dropletAnimator = rememberDropletAnimator()

    LiquidGlassScaffold(animTime = animTime, droplets = dropletAnimator.droplets) {
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                when {
                    targetState == Screen.HOME ->
                        (fadeIn(animationSpec = tween(400, easing = EaseOutCubic)) +
                         scaleIn(initialScale = 0.92f, animationSpec = tween(400, easing = EaseOutCubic)) +
                         slideInHorizontally(animationSpec = tween(400, easing = EaseOutCubic)) { -it / 8 })
                            .togetherWith(fadeOut(animationSpec = tween(250, easing = EaseInCubic)) +
                                          scaleOut(targetScale = 1.05f, animationSpec = tween(250, easing = EaseInCubic)))
                    initialState == Screen.HOME ->
                        (fadeIn(animationSpec = tween(350, easing = EaseOutCubic)) +
                         scaleIn(initialScale = 1.05f, animationSpec = tween(350, easing = EaseOutCubic)) +
                         slideInHorizontally(animationSpec = tween(350, easing = EaseOutCubic)) { it / 6 })
                            .togetherWith(fadeOut(animationSpec = tween(200, easing = EaseInCubic)) +
                                          scaleOut(targetScale = 0.92f, animationSpec = tween(200, easing = EaseInCubic)))
                    else ->
                        (fadeIn(animationSpec = tween(350, easing = EaseOutCubic)) +
                         slideInHorizontally(animationSpec = tween(350, easing = EaseOutCubic)) { it / 5 })
                            .togetherWith(fadeOut(animationSpec = tween(200, easing = EaseInCubic)) +
                                          slideOutHorizontally(animationSpec = tween(200, easing = EaseInCubic)) { -it / 5 })
                }
            },
            label = "screen"
        ) { screen ->
            when (screen) {
                Screen.HOME -> HomeContent(
                    animTime = animTime,
                    onNavigate = { currentScreen = it },
                    onAddDroplet = { x, y -> dropletAnimator.addDroplet(x, y) }
                )
                Screen.CLOCK -> ClockScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
                Screen.CALCULATOR -> CalculatorScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
                Screen.TODO -> TodoScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
                Screen.ABOUT -> AboutScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
                Screen.COUNTDOWN -> CountdownTimerScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
                Screen.NOTE -> NoteScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
                Screen.UNIT_CONVERTER -> UnitConverterScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
                Screen.PASSWORD_GEN -> PasswordGeneratorScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
                Screen.BMI -> BMICalculatorScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
                Screen.GALLERY -> GalleryScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
                Screen.AUDIO_PLAYER -> AudioPlayerScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
                Screen.FILE_MANAGER -> FileManagerScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
                Screen.QR_CODE -> QRCodeScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
                Screen.DRAWING -> DrawingScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
                Screen.COMPASS -> CompassScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
                Screen.FLASHLIGHT -> FlashlightScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
                Screen.COLOR_PICKER -> ColorPickerScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
                Screen.CALENDAR -> CalendarScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
                Screen.MUSIC -> MusicScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
                Screen.LEGAL_CENTER -> LegalCenterScreen(animTime = animTime, onBack = { currentScreen = Screen.HOME })
            }
        }
    }
}

@Composable
fun HomeContent(
    animTime: Float,
    onNavigate: (Screen) -> Unit,
    onAddDroplet: (Float, Float) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 功能门禁弹窗状态：被点击的缺包工具信息
    var lockedTool by remember { mutableStateOf<ToolItem?>(null) }
    // 下载进度（弹窗内）
    var patchDownloadProgress by remember { mutableStateOf(0f) }
    var patchDownloadStatus by remember { mutableStateOf("") }
    var patchDownloading by remember { mutableStateOf(false) }
    // 触发卡片重组的"安装状态tick"——下载完成后 +1 让所有卡片重新读取门禁状态
    var unlockTick by remember { mutableStateOf(0) }

    val tools = listOf(
        // 第1行：核心工具
        ToolItem(Screen.CLOCK, Icons.Default.AccessTime, "时钟·天气", "实时定位与预报", listOf(FluidCyan, FluidBlue)),
        ToolItem(Screen.CALCULATOR, Icons.Default.Calculate, "计算器", "科学计算模式", listOf(FluidPurple, FluidPink)),
        ToolItem(Screen.MUSIC, Icons.Default.LibraryMusic, "音乐", "网易云·本地·多平台", listOf(FluidPink, FluidCyan)),
        ToolItem(Screen.TODO, Icons.Default.CheckCircle, "待办清单", "勾选·删除·自动保存", listOf(FluidBlue, FluidPurple)),
        // 第2行：时间工具
        ToolItem(Screen.COUNTDOWN, Icons.Default.Timer, "倒计时秒表", "计时与计次", listOf(FluidOrange, FluidPink)),
        ToolItem(Screen.CALENDAR, Icons.Default.CalendarMonth, "日历日程", "事件管理与提醒", listOf(FluidCyan, FluidTeal)),
        // 第3行：生产工具
        ToolItem(Screen.NOTE, Icons.Default.EditNote, "记事本", "轻量笔记管理", listOf(FluidTeal, FluidBlue)),
        ToolItem(Screen.UNIT_CONVERTER, Icons.Default.SwapHoriz, "单位换算", "10类单位转换", listOf(FluidPurple, FluidCyan)),
        ToolItem(Screen.PASSWORD_GEN, Icons.Default.Key, "密码生成器", "随机工具集合", listOf(FluidBlue, FluidTeal)),
        ToolItem(Screen.QR_CODE, Icons.Default.QrCode, "二维码", "生成与识别", listOf(FluidPink, FluidPurple)),
        // 第4行：健康生活
        ToolItem(Screen.BMI, Icons.Default.FavoriteBorder, "健康计算", "BMI·体脂·卡路里", listOf(FluidPink, FluidOrange)),
        ToolItem(Screen.AUDIO_PLAYER, Icons.Default.MusicNote, "白噪音", "助眠·专注·放松", listOf(FluidTeal, FluidPurple)),
        // 第5行：创意工具
        ToolItem(Screen.GALLERY, Icons.Default.Wallpaper, "壁纸画廊", "程序化艺术壁纸", listOf(FluidCyan, FluidPurple)),
        ToolItem(Screen.DRAWING, Icons.Default.Draw, "涂鸦画板", "自由绘画创作", listOf(FluidOrange, FluidPink)),
        ToolItem(Screen.COLOR_PICKER, Icons.Default.Palette, "颜色选择器", "取色与配色方案", listOf(FluidPurple, FluidPink)),
        ToolItem(Screen.FILE_MANAGER, Icons.Default.Folder, "文件管理", "浏览与管理文件", listOf(FluidBlue, FluidCyan)),
        // 第6行：传感器工具
        ToolItem(Screen.COMPASS, Icons.Default.Explore, "指南针水平仪", "方向与水平检测", listOf(FluidTeal, FluidBlue)),
        ToolItem(Screen.FLASHLIGHT, Icons.Default.FlashlightOn, "手电筒", "闪光灯与屏幕工具", listOf(FluidOrange, FluidCyan)),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(44.dp))

        // v2.9.1 顶部公告栏
        AnnouncementBar()

        // 标题区域
        Text(
            text = "灵工坊",
            fontSize = 36.sp,
            fontWeight = FontWeight.Thin,
            color = appTextPrimary().copy(alpha = 0.9f),
            letterSpacing = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "LING GONG FANG · SMART TOOLBOX",
            fontSize = 11.sp,
            fontWeight = FontWeight.Light,
            color = appTextTertiary(),
            letterSpacing = 5.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 工具卡片网格 - 2列滚动
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            items(tools.size) { index ->
                val tool = tools[index]
                // unlockTick 作为依赖：补丁包下载完成后触发重组，重新读取门禁状态
                val locked = remember(unlockTick) {
                    !ResourceManager.isFeatureUnlocked(context, tool.screen.name)
                }
                ToolCard(
                    tool = tool,
                    locked = locked,
                    requiredPatchName = if (locked) ResourceManager.requiredPatchName(tool.screen.name) else null,
                    onClick = {
                        if (locked) {
                            // 缺包：弹出下载提示而非导航
                            lockedTool = tool
                            patchDownloadProgress = 0f
                            patchDownloadStatus = ""
                        } else {
                            onAddDroplet(
                                (0.15f + (index % 2) * 0.7f),
                                (0.15f + (index / 2) * 0.15f)
                            )
                            onNavigate(tool.screen)
                        }
                    }
                )
            }
        }

        // 底部关于按钮
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.12f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onNavigate(Screen.ABOUT) }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = appTextSecondary(), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("关于 · 更新 · 创作者", fontSize = 13.sp, color = appTextSecondary())
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            "JTQ Allen © 2026 · 18个工具模块",
            fontSize = 10.sp,
            color = appTextTertiary(),
            modifier = Modifier.padding(bottom = 16.dp)
        )
    }

    // 功能门禁下载弹窗
    val pendingTool = lockedTool
    if (pendingTool != null) {
        PatchDownloadDialog(
            tool = pendingTool,
            progress = patchDownloadProgress,
            status = patchDownloadStatus,
            downloading = patchDownloading,
            onDismiss = {
                if (!patchDownloading) lockedTool = null
            },
            onDownload = {
                if (patchDownloading) return@PatchDownloadDialog
                val patchName = ResourceManager.requiredPatchName(pendingTool.screen.name)
                scope.launch {
                    patchDownloading = true
                    patchDownloadProgress = 0f
                    val cb: (Long, Long, String) -> Unit = { dl, total, status ->
                        patchDownloadStatus = status
                        if (total > 0) patchDownloadProgress = dl.toFloat() / total.toFloat()
                    }
                    val result = when (patchName) {
                        "核心功能补丁包" -> ResourceManager.downloadPatchCore(context, onProgress = cb)
                        "高级体验初始化包" -> ResourceManager.downloadInitPremium(context, onProgress = cb)
                        else -> Result.failure(IllegalStateException("未知补丁包"))
                    }
                    if (result.isSuccess) {
                        patchDownloadStatus = "${patchName}安装完成，功能已解锁"
                        patchDownloadProgress = 1f
                        unlockTick++  // 触发所有卡片重组，锁标记消失
                        delay(1200)
                        lockedTool = null
                    } else {
                        patchDownloadStatus = "下载失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                    }
                    patchDownloading = false
                }
            },
            onGoToAbout = {
                if (!patchDownloading) {
                    lockedTool = null
                    onNavigate(Screen.ABOUT)
                }
            }
        )
    }
}

@Composable
fun ToolCard(
    tool: ToolItem,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    requiredPatchName: String? = null,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 350f),
        label = "scale"
    )
    val pressPhysics = rememberPressPhysics()
    LaunchedEffect(pressed) {
        while (true) {
            pressPhysics.update(pressed)
            kotlinx.coroutines.delay(16)
        }
    }

    Box(
        modifier = modifier
            .scale(scale)
            .aspectRatio(1f)
            .glassSurface(cornerRadius = 24.dp, glassAlpha = 0.18f, pressDepth = pressPhysics.pressDepth)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                pressed = true
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 图标背景
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = if (locked) listOf(AccentWarning.copy(alpha = 0.18f), AccentDanger.copy(alpha = 0.18f))
                            else tool.gradientColors.map { it.copy(alpha = 0.15f) }
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (locked) Icons.Default.Lock else tool.icon,
                    contentDescription = tool.title,
                    tint = if (locked) AccentWarning else tool.gradientColors.first().copy(alpha = 0.85f),
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = tool.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (locked) appTextPrimary().copy(alpha = 0.5f) else appTextPrimary().copy(alpha = 0.9f)
            )

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = if (locked) (requiredPatchName ?: "未解锁") else tool.subtitle,
                fontSize = 10.sp,
                color = if (locked) AccentWarning else appTextTertiary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 右上角锁标记徽章
        if (locked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(AccentWarning.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "需下载补丁包",
                    tint = Color.White,
                    modifier = Modifier.size(11.dp)
                )
            }
        }
    }

    LaunchedEffect(pressed) {
        if (pressed) {
            delay(150)
            pressed = false
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// 功能门禁下载弹窗：点击缺包工具时弹出，含一键下载补丁包
// ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatchDownloadDialog(
    tool: ToolItem,
    progress: Float,
    status: String,
    downloading: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onGoToAbout: () -> Unit
) {
    val patchName = when (tool.screen) {
        Screen.CALCULATOR, Screen.UNIT_CONVERTER, Screen.QR_CODE, Screen.COLOR_PICKER, Screen.PASSWORD_GEN ->
            "核心功能补丁包"
        Screen.MUSIC, Screen.CALENDAR, Screen.TODO, Screen.NOTE, Screen.BMI, Screen.COUNTDOWN, Screen.COMPASS ->
            "高级体验初始化包"
        else -> "资源补丁包"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.clip(RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            // 头部：锁图标 + 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                        .background(AccentWarning.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Lock, null, tint = AccentWarning, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("功能未解锁", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = appTextPrimary())
                    Text("「${tool.title}」需要 $patchName", fontSize = 12.sp, color = appTextTertiary())
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                "该工具依赖 $patchName 才能使用。$patchName 未安装时，相关功能全部不可用。\n点击下方按钮立即下载并解锁，下载完成后即可正常使用。",
                fontSize = 12.sp,
                color = appTextSecondary(),
                lineHeight = 18.sp
            )

            // 下载进度
            if (downloading || progress > 0f) {
                Spacer(modifier = Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = FluidCyan,
                    trackColor = GlassLight
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (status.isNotEmpty()) "${(progress * 100).toInt()}% · $status" else "${(progress * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = appTextSecondary(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 操作按钮
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onGoToAbout,
                    enabled = !downloading,
                    modifier = Modifier.weight(1f)
                ) { Text("资源管理", fontSize = 13.sp, color = appTextTertiary()) }
                Button(
                    onClick = onDownload,
                    enabled = !downloading,
                    modifier = Modifier.weight(1.4f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FluidCyan)
                ) {
                    if (downloading) {
                        CircularProgressIndicator(
                            color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("下载中...", fontSize = 13.sp)
                    } else if (progress >= 1f) {
                        Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("已解锁", fontSize = 13.sp)
                    } else {
                        Icon(Icons.Default.Download, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("下载 $patchName", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}