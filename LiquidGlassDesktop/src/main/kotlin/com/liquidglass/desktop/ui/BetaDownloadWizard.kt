package com.liquidglass.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.desktop.system.BetaInfo
import com.liquidglass.desktop.system.BetaPioneerManager
import com.liquidglass.desktop.theme.LiquidGlassTheme
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Beta 下载向导
 *
 * 三阶段流程：
 * 1. 选项阶段：用户勾选"创建桌面快捷方式"、"创建开始菜单快捷方式"、"下载后运行安装"
 * 2. 下载阶段：实时显示已下载字节 / 总字节 / 当前阶段文案 / 实时下载文件列表
 *    - 文件列表支持展开 / 隐藏（折叠按钮 + 动画）
 * 3. 完成阶段：显示下载路径、SHA256 校验结果，提供"打开所在目录"、"运行安装包"按钮
 *
 * 设计原则：
 * - 单文件，不引入新依赖
 * - 状态机驱动，避免重复下载与状态错乱
 * - 文件列表用 SnapshotStateList 增量追加，确保 Compose 重组最小化
 */
@Composable
fun BetaDownloadWizard(
    betaPioneerManager: BetaPioneerManager,
    betaInfo: BetaInfo,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    // 选项状态
    var createDesktop by remember { mutableStateOf(true) }
    var createStartMenu by remember { mutableStateOf(true) }
    var runAfterDownload by remember { mutableStateOf(false) }

    // 下载状态
    var phase by remember { mutableStateOf(WizardPhase.Options) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(-1L) }
    var stageText by remember { mutableStateOf("") }
    val logLines = remember { mutableStateListOf<String>() }
    var listExpanded by remember { mutableStateOf(true) }

    // 结果
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var shortcutResults by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Beta 下载向导",
            color = LiquidGlassTheme.accentSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${betaInfo.fileName} · 版本 ${betaInfo.version} · 发布 ${betaInfo.date}",
            color = LiquidGlassTheme.onSurfaceMuted,
            fontSize = 12.sp
        )
        if (betaInfo.sizeBytes > 0) {
            Text(
                text = "体积: ${formatSize(betaInfo.sizeBytes)}",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 11.sp
            )
        }
        Spacer(Modifier.height(12.dp))

        when (phase) {
            WizardPhase.Options -> {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text("安装选项", color = LiquidGlassTheme.onSurfaceColor, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OptionRow(
                        text = "在桌面创建快捷方式",
                        hint = "下载完成后在桌面生成 .lnk",
                        checked = createDesktop,
                        onToggle = { createDesktop = it }
                    )
                    Spacer(Modifier.height(4.dp))
                    OptionRow(
                        text = "在开始菜单创建快捷方式",
                        hint = "添加到「LiquidGlass」程序组",
                        checked = createStartMenu,
                        onToggle = { createStartMenu = it }
                    )
                    Spacer(Modifier.height(4.dp))
                    OptionRow(
                        text = "下载完成后运行安装包",
                        hint = "自动启动安装程序（需要管理员权限）",
                        checked = runAfterDownload,
                        onToggle = { runAfterDownload = it }
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                phase = WizardPhase.Downloading
                                totalBytes = -1L
                                downloadedBytes = 0L
                                logLines.clear()
                                errorMsg = null
                                downloadedFile = null
                                listExpanded = true
                                val targetDir = File(
                                    System.getProperty("user.home"),
                                    ".liquidglass/downloads"
                                )
                                logLines += "[${now()}] 准备下载 ${betaInfo.fileName}"
                                if (betaInfo.sizeBytes > 0) {
                                    logLines += "[${now()}] 预期体积: ${formatSize(betaInfo.sizeBytes)}"
                                }
                                logLines += "[${now()}] 目标目录: ${targetDir.absolutePath}"
                                scope.launch {
                                    val file = betaPioneerManager.downloadBeta(
                                        info = betaInfo,
                                        targetDir = targetDir,
                                        onProgress = { d, t ->
                                            downloadedBytes = d
                                            totalBytes = t
                                        },
                                        onStage = { stage ->
                                            stageText = stage
                                            logLines += "[${now()}] $stage"
                                        }
                                    )
                                    if (file != null) {
                                        downloadedFile = file
                                        logLines += "[${now()}] 已保存: ${file.absolutePath}"
                                        logLines += "[${now()}] 体积: ${formatSize(file.length())}"

                                        // 创建快捷方式
                                        val sb = StringBuilder()
                                        if (createDesktop) {
                                            val ok = betaPioneerManager.createDesktopShortcut(
                                                file.absolutePath,
                                                "LiquidGlass Beta ${betaInfo.version}"
                                            )
                                            sb.appendLine("桌面快捷方式: ${if (ok) "已创建" else "失败"}")
                                            logLines += "[${now()}] 桌面快捷方式: ${if (ok) "已创建" else "失败"}"
                                        }
                                        if (createStartMenu) {
                                            val ok = betaPioneerManager.createStartMenuShortcut(
                                                file.absolutePath,
                                                "LiquidGlass",
                                                "LiquidGlass Beta ${betaInfo.version}"
                                            )
                                            sb.appendLine("开始菜单快捷方式: ${if (ok) "已创建" else "失败"}")
                                            logLines += "[${now()}] 开始菜单快捷方式: ${if (ok) "已创建" else "失败"}"
                                        }
                                        shortcutResults = sb.toString().trim()

                                        // 自动运行
                                        if (runAfterDownload) {
                                            logLines += "[${now()}] 启动安装程序..."
                                            runCatching {
                                                ProcessBuilder(file.absolutePath).start()
                                            }.onFailure {
                                                logLines += "[${now()}] 启动失败: ${it.message}"
                                            }
                                        }
                                        phase = WizardPhase.Done
                                    } else {
                                        errorMsg = stageText.ifBlank { "下载失败，请查看日志" }
                                        phase = WizardPhase.Failed
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = LiquidGlassTheme.accentPrimary,
                                contentColor = Color.White
                            )
                        ) { Text("开始下载") }
                        OutlinedButton(onClick = { /* 取消=留在选项页 */ }) { Text("取消") }
                    }
                }
            }

            WizardPhase.Downloading -> {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text("正在下载", color = LiquidGlassTheme.accentSecondary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stageText.ifBlank { "准备中..." },
                        color = LiquidGlassTheme.onSurfaceColor,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    val percent = if (totalBytes > 0) {
                        (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                    } else 0f
                    LinearProgressIndicator(
                        progress = percent,
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = LiquidGlassTheme.accentSecondary,
                        backgroundColor = LiquidGlassTheme.surfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (totalBytes > 0)
                            "${formatSize(downloadedBytes)} / ${formatSize(totalBytes)} · ${"%d".format((percent * 100).toInt())}%"
                        else "${formatSize(downloadedBytes)} (总量未知)",
                        color = LiquidGlassTheme.onSurfaceMuted,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(8.dp))

                // 实时下载文件列表（可折叠）
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { listExpanded = !listExpanded },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val rotation by animateFloatAsState(
                            targetValue = if (listExpanded) 90f else 0f,
                            animationSpec = tween(180),
                            label = "expandRot"
                        )
                        Text(
                            text = "›",
                            color = LiquidGlassTheme.accentPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            modifier = Modifier.rotate(rotation)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "实时下载日志 (${logLines.size})",
                            color = LiquidGlassTheme.onSurfaceColor,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = if (listExpanded) "点击折叠" else "点击展开",
                            color = LiquidGlassTheme.onSurfaceMuted,
                            fontSize = 11.sp
                        )
                    }
                    AnimatedVisibility(
                        visible = listExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            if (logLines.isEmpty()) {
                                Text("暂无日志", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 11.sp)
                            } else {
                                logLines.forEach { line ->
                                    Text(
                                        text = line,
                                        color = LiquidGlassTheme.onSurfaceMuted,
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            WizardPhase.Done -> {
                downloadedFile?.let { f ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text("下载完成", color = LiquidGlassTheme.green, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("文件: ${f.name}", color = LiquidGlassTheme.onSurfaceColor, fontSize = 13.sp)
                        Text("路径: ${f.absolutePath}", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 11.sp)
                        Text("体积: ${formatSize(f.length())}", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 11.sp)
                        if (betaInfo.sha256.isNotBlank()) {
                            Text("SHA256: ${betaInfo.sha256.take(16)}… 校验通过", color = LiquidGlassTheme.green, fontSize = 11.sp)
                        }
                        if (shortcutResults.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text("快捷方式", color = LiquidGlassTheme.onSurfaceColor, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                            Text(shortcutResults, color = LiquidGlassTheme.onSurfaceMuted, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    runCatching {
                                        val os = System.getProperty("os.name").lowercase()
                                        if (os.contains("windows")) {
                                            ProcessBuilder("explorer.exe", "/select,", f.absolutePath).start()
                                        } else {
                                            ProcessBuilder("xdg-open", f.parentFile?.absolutePath ?: "").start()
                                        }
                                    }
                                }
                            ) { Text("打开所在目录") }
                            Button(
                                onClick = {
                                    runCatching { ProcessBuilder(f.absolutePath).start() }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = LiquidGlassTheme.accentPrimary,
                                    contentColor = Color.White
                                )
                            ) { Text("运行安装包") }
                            OutlinedButton(onClick = {
                                phase = WizardPhase.Options
                                logLines.clear()
                                downloadedFile = null
                            }) { Text("重新下载") }
                        }
                    }
                }
            }

            WizardPhase.Failed -> {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text("下载失败", color = LiquidGlassTheme.announcementHigh, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = errorMsg ?: "未知错误",
                        color = LiquidGlassTheme.onSurfaceColor,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                phase = WizardPhase.Options
                                logLines.clear()
                                errorMsg = null
                            },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = LiquidGlassTheme.accentPrimary,
                                contentColor = Color.White
                            )
                        ) { Text("重试") }
                        if (logLines.isNotEmpty()) {
                            OutlinedButton(onClick = { listExpanded = !listExpanded }) {
                                Text(if (listExpanded) "隐藏日志" else "查看日志")
                            }
                        }
                    }
                    if (logLines.isNotEmpty()) {
                        AnimatedVisibility(
                            visible = listExpanded,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                logLines.forEach { line ->
                                    Text(
                                        text = line,
                                        color = LiquidGlassTheme.onSurfaceMuted,
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 向导阶段 */
private enum class WizardPhase { Options, Downloading, Done, Failed }

/** 复选行（点击整行切换） */
@Composable
private fun OptionRow(
    text: String,
    hint: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (checked) LiquidGlassTheme.accentPrimary.copy(alpha = 0.18f)
                else Color.Transparent
            )
            .clickable { onToggle(!checked) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (checked) LiquidGlassTheme.accentSecondary
                    else LiquidGlassTheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text, color = LiquidGlassTheme.onSurfaceColor, fontSize = 13.sp)
            Text(hint, color = LiquidGlassTheme.onSurfaceMuted, fontSize = 11.sp)
        }
    }
}

private fun now(): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
