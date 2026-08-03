package com.liquidglass.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.app.music.NetEaseApiClient
import com.liquidglass.app.ui.HomeScreen
import com.liquidglass.app.ui.LoadingScreen
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 最早期注册全局上下文，供 NetEaseApiClient 等单例自愈
        ContextProvider.init(this)
        NetEaseApiClient.init(this)
        // 预初始化 MusicControllerManager：MediaController 是异步连接，
        // 在 onCreate 同步触发，避免进入播放页后点歌时 controller 仍为 null 导致"点歌没反应/闪退"
        com.liquidglass.app.music.MusicControllerManager.init(this)
        // 预初始化 LyricsSettings：避免从通知直接跳音乐页时 LyricsSettings 未初始化导致
        // updateX() 函数访问 lateinit prefs 崩溃
        com.liquidglass.app.ui.LyricsSettings.init(this)
        enableEdgeToEdge()
        ThemeManager.init(this)
        // 初始化通知频道：更新提醒 / 内容推荐 / 下载进度
        NotificationHelper.initChannels(this)

        // 通知点击跳转：必须在 setContent 之前解析 Intent，
        // 确保 AppRouter.pendingRoute 在首次组合前就设置好，
        // HomeScreen 组合时立即消费路由完成跳转（解决"点击通知黑屏/不跳转"）。
        handleNotificationIntent(intent)

        setContent {
            LiquidGlassTheme {
                // 崩溃日志弹窗（优先显示）
                var lastCrashLog by remember { mutableStateOf<String?>(null) }
                // 在首次组合时检查崩溃日志文件
                LaunchedEffect(Unit) {
                    val crashFile = java.io.File(filesDir, "crash/crash_log.txt")
                    if (crashFile.exists()) {
                        lastCrashLog = crashFile.readText()
                        crashFile.delete()
                    }
                }
                lastCrashLog?.let { log ->
                    AlertDialog(
                        onDismissRequest = { lastCrashLog = null },
                        title = { Text("上次崩溃日志") },
                        text = {
                            Text(
                                text = log.take(3000),
                                fontSize = 10.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                // 复制到剪贴板
                                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("crash_log", log))
                                android.widget.Toast.makeText(this@MainActivity, "崩溃日志已复制到剪贴板", android.widget.Toast.LENGTH_LONG).show()
                                lastCrashLog = null
                            }) { Text("复制并关闭") }
                        },
                        dismissButton = {
                            TextButton(onClick = { lastCrashLog = null }) { Text("关闭") }
                        }
                    )
                }

                // 每次冷启动都显示加载页面（不可跳过）
                // 使用 rememberSaveable 确保旋转屏时不重复显示
                var showLoading by rememberSaveable { mutableStateOf(true) }
                // 权限门槛：未授予所有必需权限时拦截 HomeScreen
                var permissionsGranted by rememberSaveable { mutableStateOf(false) }

                AnimatedContent(
                    targetState = showLoading,
                    transitionSpec = {
                        // 从加载页到主页面的丝滑过渡
                        (fadeIn(animationSpec = tween(600, easing = EaseOutCubic)) +
                         scaleIn(initialScale = 0.95f, animationSpec = tween(600, easing = EaseOutCubic)))
                            .togetherWith(fadeOut(animationSpec = tween(400, easing = EaseInCubic)) +
                                          scaleOut(targetScale = 1.1f, animationSpec = tween(400, easing = EaseInCubic)))
                    },
                    label = "mainTransition"
                ) { loading ->
                    if (loading) {
                        LoadingScreen(
                            onComplete = { showLoading = false }
                        )
                    } else if (!permissionsGranted) {
                        // 权限门槛：未授予所有必需权限前不允许进入主功能
                        PermissionGate(onAllGranted = { permissionsGranted = true })
                    } else {
                        HomeScreen()
                    }
                }

                // 加载页结束后自动检查更新，发现新版本则弹窗
                UpdateDialog(showLoading = showLoading)

                // 权限授予后启动周期性内容推荐通知
                // 修复"通知从未出现"：原 activate = showLoading && permissionsGranted 永远为 false
                // （加载页 showLoading=true 时权限未授予；权限授予后 showLoading 已变 false）。
                // 正确条件：加载完成(!showLoading) 且 权限已授予(permissionsGranted)。
                NotificationBootstrap(activate = !showLoading && permissionsGranted)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    /** 解析通知携带的 action/target，写入 AppRouter 触发 HomeScreen 跳转到对应功能页。 */
    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.getStringExtra("action") ?: return
        // 优先用 target（推荐通知携带具体功能页路由名）
        val target = intent.getStringExtra("target")
        val route = when {
            target != null -> target
            action == "check_update" -> "ABOUT"
            action == "open_music" || action == "open_feature" -> "MUSIC"
            else -> return
        }
        // 确保 route 是有效的 Screen 枚举名，避免 valueOf 抛异常
        try {
            com.liquidglass.app.ui.Screen.valueOf(route)
        } catch (_: IllegalArgumentException) {
            return
        }
        AppRouter.navigate(route)
    }
}

// ======================== 权限门槛 ========================

/**
 * 更新后强制申请所有运行时权限的门槛页。
 *
 * 必需权限（按 Android 版本裁剪）——仅保留有真实功能支撑的权限，避免"申请了但用不上"
 * 造成权限与功能牛头不对马嘴：
 * - POST_NOTIFICATIONS（Android 13+）：通知栏/媒体控件/推荐广告
 * - READ_MEDIA_AUDIO（Android 13+）/ READ_EXTERNAL_STORAGE（Android 12-）：本地音乐扫描
 * - ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION：指南针/时钟定位
 * - CALL_PHONE：关于页拨打客服电话（ACTION_CALL）
 * - SCHEDULE_EXACT_ALARM（Android 12+）：日历精确提醒
 *
 * 已移除的"摆设"权限：
 * - RECORD_AUDIO：无任何录音/语音功能，纯粹多余
 * - READ_CONTACTS / WRITE_CONTACTS：通讯录入口仅用 Intent 跳系统插入界面，不需权限
 *
 * 未全部授予时显示权限清单与"一键授权"按钮，授予后才能进入 HomeScreen。
 * 某些权限（如 SCHEDULE_EXACT_ALARM / REQUEST_INSTALL_PACKAGES）需跳系统设置页单独开启，
 * 会引导用户前往；返回后重新检测。
 */
@Composable
private fun PermissionGate(onAllGranted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 需运行时申请的权限清单（按版本裁剪）：每一项都对应真实功能调用
    val runtimePermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.SCHEDULE_EXACT_ALARM)
            }
        }
    }

    // 当前各权限授予状态
    var permissionStates by remember {
        mutableStateOf(runtimePermissions.associateWith { isGranted(context, it) })
    }
    // 一次申请一批（系统对话框最多支持批量，Multi-permission contract）
    var pendingBatch by remember { mutableStateOf<List<String>>(emptyList()) }

    val multiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // 更新状态
        permissionStates = permissionStates.mapValues { (perm, _) ->
            result[perm] ?: isGranted(context, perm)
        }
        pendingBatch = emptyList()
    }

    // 单权限（如某些设备批量失败时逐个重试）
    val singleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        permissionStates = permissionStates.mapValues { (perm, _) -> isGranted(context, perm) }
    }

    val allGranted = permissionStates.values.all { it }

    // 自动触发：未全部授予时立即弹出批量申请
    LaunchedEffect(permissionStates.size) {
        if (!allGranted && pendingBatch.isEmpty()) {
            val denied = permissionStates.filterValues { !it }.keys.toList()
            if (denied.isNotEmpty()) {
                pendingBatch = denied
                multiLauncher.launch(denied.toTypedArray())
            }
        }
    }

    if (allGranted) {
        // 全部授予：放行
        LaunchedEffect(Unit) { onAllGranted() }
        return
    }

    // 权限清单 UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(appBgColor())
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            // 液态玻璃盾牌图标
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(FluidCyan, FluidPurple)))
                    .padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Security, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "权限授权",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = appTextPrimary()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "为正常使用全部功能，需授予以下权限。\n未授权将无法进入应用。",
                fontSize = 12.sp,
                color = appTextTertiary(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))

            // 权限清单
            permissionStates.forEach { (perm, granted) ->
                PermissionRow(
                    name = permissionDisplayName(perm),
                    desc = permissionDesc(perm),
                    granted = granted
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(16.dp))
            // 一键授权按钮
            Button(
                onClick = {
                    val denied = permissionStates.filterValues { !it }.keys.toList()
                    if (denied.isNotEmpty()) {
                        multiLauncher.launch(denied.toTypedArray())
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(14.dp)),
                colors = ButtonDefaults.buttonColors(containerColor = FluidCyan)
            ) {
                Icon(Icons.Default.VerifiedUser, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("一键授权全部权限", color = Color.White, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            // 跳转系统设置（针对需要手动开启的权限，如精确闹钟/安装未知来源）
            Text(
                "若部分权限弹窗未出现，点击下方前往系统设置手动开启 →",
                fontSize = 11.sp,
                color = appTextTertiary(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }) {
                Text("前往应用设置", color = FluidPurple, fontSize = 12.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PermissionRow(name: String, desc: String, granted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (granted) FluidTeal.copy(alpha = 0.10f)
                else Color.White.copy(alpha = 0.06f)
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (granted) Icons.Default.CheckCircle else Icons.Default.Lock,
            null,
            tint = if (granted) FluidTeal else AccentWarning,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, color = appTextPrimary(), fontWeight = FontWeight.Medium)
            Text(desc, fontSize = 10.sp, color = appTextTertiary())
        }
        Text(
            if (granted) "已授权" else "未授权",
            fontSize = 10.sp,
            color = if (granted) FluidTeal else AccentWarning
        )
    }
}

private fun isGranted(context: android.content.Context, perm: String): Boolean =
    context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

private fun permissionDisplayName(perm: String): String = when (perm) {
    Manifest.permission.POST_NOTIFICATIONS -> "通知权限"
    Manifest.permission.READ_MEDIA_AUDIO -> "音频文件读取"
    Manifest.permission.READ_EXTERNAL_STORAGE -> "存储读取"
    Manifest.permission.ACCESS_FINE_LOCATION -> "精确定位"
    Manifest.permission.ACCESS_COARSE_LOCATION -> "大致定位"
    Manifest.permission.CALL_PHONE -> "拨打电话"
    Manifest.permission.SCHEDULE_EXACT_ALARM -> "精确闹钟"
    else -> perm.substringAfterLast('.')
}

private fun permissionDesc(perm: String): String = when (perm) {
    Manifest.permission.POST_NOTIFICATIONS -> "媒体控制台/锁屏控件/更新与推荐通知"
    Manifest.permission.READ_MEDIA_AUDIO -> "扫描本地音乐文件"
    Manifest.permission.READ_EXTERNAL_STORAGE -> "扫描本地音乐与读取下载资源"
    Manifest.permission.ACCESS_FINE_LOCATION -> "指南针/时钟 GPS 定位"
    Manifest.permission.ACCESS_COARSE_LOCATION -> "指南针/时钟网络定位"
    Manifest.permission.CALL_PHONE -> "关于页拨打客服电话"
    Manifest.permission.SCHEDULE_EXACT_ALARM -> "日历日程精确提醒"
    else -> "应用所需权限"
}

/**
 * 内容推荐通知（广告）周期推送。
 *
 * - 由 PermissionGate 通过 [activate] 激活（所有必需权限已授予）
 * - 激活后立即推首条（0延迟，确保用户能看到通知出现），之后每 3 分钟推一条
 * - 不重复轮播：洗牌一个队列，按序取，全部播完才重新洗牌
 * - App 在内存中保持推送；进程被杀则停止
 */
@Composable
private fun NotificationBootstrap(activate: Boolean) {
    val context = LocalContext.current
    // 防重：同次会话内只启动一次周期推荐（非 rememberSaveable：进程被杀后应重新推送）
    var bootstrapped by remember { mutableStateOf(false) }

    LaunchedEffect(activate) {
        if (activate && !bootstrapped) {
            bootstrapped = true
            // 首条立即推送（0延迟），确保用户进入主页后立刻能看到通知
            // 修复"通知从未出现"：原 delay(2000) 在某些设备上协程调度延迟更大，
            // 且若用户快速切屏 LaunchedEffect 可能被取消导致首条通知丢失
            NotificationHelper.pushNextRecommend(context)
            // 之后每 3 分钟推一条
            while (true) {
                delay(3 * 60 * 1000L)
                NotificationHelper.pushNextRecommend(context)
            }
        }
    }
}

/**
 * 启动更新检查弹窗。在 [showLoading] 变为 false（进入主页）后触发一次检查；
 * 发现新版本时弹出模态对话框，支持带进度条下载并自动调起安装。
 *
 * 进度回调由 UpdateChecker 限频到 500ms 一次，弹窗布局固定（标题/正文/进度条/按钮
 * 均占位稳定），避免旧版逐次 read 刷新状态文本导致的“显示跳动”。
 */
@Composable
private fun UpdateDialog(showLoading: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    // 实际执行检查的函数：force=true 跳过节流，force=false 由 UpdateChecker 内部 30min 节流
    suspend fun doCheck(force: Boolean) {
        val info = withContext(Dispatchers.IO) {
            UpdateChecker.checkForUpdate(context, force = force)
        }
        if (info != null) {
            updateInfo = info
            downloadProgress = 0f
            downloadError = null
            showDialog = true
            // 同步推送一条系统通知：即使用户关掉弹窗，通知栏仍能看到更新提醒
            NotificationHelper.notifyUpdate(
                context,
                info.version,
                info.releaseNotes.ifBlank { "发现新版本，点击查看详情" }
            )
        }
    }

    // 加载页结束后异步检查一次更新（首次启动用 force=true 跳过节流，确保启动必检）
    LaunchedEffect(showLoading) {
        if (!showLoading) {
            doCheck(force = true)
        }
    }

    // App 从后台回到前台时重新检查更新（依赖 UpdateChecker 内部 30min 节流，避免频繁请求）
    // 修复"不退出程序或退出重进有时检测不到更新"：原实现只在启动时检查一次，
    // 用户长时间挂起 App 后即使新版本上线也不会再检测；监听 ON_RESUME 后每隔 30min 自动复检
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { doCheck(force = false) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (!showDialog) return
    val info = updateInfo ?: return

    AlertDialog(
        onDismissRequest = {
            // 下载进行中不允许点外部关闭，避免半成品文件状态混乱
            if (!isDownloading) showDialog = false
        },
        title = {
            Text(
                text = "发现新版本 v${info.version}",
                color = FluidCyan,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Column {
                Text(
                    text = info.releaseNotes.ifBlank { "暂无更新说明" },
                    color = TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                if (downloadError != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "下载失败：$downloadError",
                        color = AccentDanger,
                        fontSize = 13.sp
                    )
                }
                if (isDownloading) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = FluidCyan,
                        trackColor = GlassMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "正在下载... ${(downloadProgress * 100).toInt()}%",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isDownloading) return@TextButton
                    isDownloading = true
                    downloadError = null
                    downloadProgress = 0f
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            UpdateChecker.downloadApk(context, info.downloadUrl) { p ->
                                downloadProgress = p
                            }
                        }
                        isDownloading = false
                        if (file != null) {
                            UpdateChecker.installApk(context, file)
                            showDialog = false
                        } else {
                            downloadError = "所有镜像均不可用，请稍后重试"
                        }
                    }
                },
                enabled = !isDownloading
            ) {
                Text("立即更新", color = FluidCyan, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = { showDialog = false },
                enabled = !isDownloading
            ) {
                Text("稍后", color = TextSecondary)
            }
        },
        containerColor = BgDark2,
        shape = RoundedCornerShape(20.dp)
    )
}
