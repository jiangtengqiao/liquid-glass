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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.liquidglass.desktop.system.UpdateChecker
import com.liquidglass.desktop.system.UpdateChecker.UpdateInfo
import com.liquidglass.desktop.theme.LiquidGlassTheme
import kotlinx.coroutines.launch
import java.io.File

/**
 * 更新检查对话框
 *
 * 完整流程：检查更新 → 显示版本信息 → 下载（进度+多镜像源+实时日志）→ 安装
 * - 快捷方式选项：桌面 / 开始菜单（下载完成后安装前可选）
 * - 实时下载文件列表：可展开/隐藏，显示镜像源尝试、已下载字节、阶段文案
 * - v2.10.1：安装时立即退出当前应用（释放 exe 文件占用，避免安装损坏）
 *
 * @param onExitApplication 启动安装程序后调用，强制退出当前 JVM
 */
@Composable
fun UpdateDialog(
    onDismiss: () -> Unit,
    onExitApplication: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(true) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var noUpdate by remember { mutableStateOf(false) }
    var checkError by remember { mutableStateOf<String?>(null) }

    // 下载状态
    var downloading by remember { mutableStateOf(false) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(-1L) }
    var stage by remember { mutableStateOf("") }
    var downloadLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    var showLogs by remember { mutableStateOf(true) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    // 快捷方式选项
    var createDesktopShortcut by remember { mutableStateOf(true) }
    var createStartMenuShortcut by remember { mutableStateOf(true) }

    // 启动时自动检查
    LaunchedEffect(Unit) {
        scope.launch {
            try {
                val info = UpdateChecker.checkForUpdate(force = true)
                if (info != null) {
                    updateInfo = info
                } else {
                    noUpdate = true
                }
            } catch (e: Exception) {
                checkError = e.message ?: "检查失败"
            } finally {
                checking = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("检查更新", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when {
                    checking -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 12.dp),
                                color = LiquidGlassTheme.accentSecondary
                            )
                            Text("正在检查更新...", color = LiquidGlassTheme.onSurfaceColor)
                        }
                    }

                    checkError != null -> {
                        Text("检查失败：$checkError", color = LiquidGlassTheme.announcementHigh)
                    }

                    noUpdate -> {
                        Text(
                            "当前已是最新版本（v${UpdateChecker.LOCAL_VERSION}）",
                            color = LiquidGlassTheme.onSurfaceColor
                        )
                    }

                    updateInfo != null -> {
                        val info = updateInfo!!
                        Text(
                            "发现新版本 v${info.version}",
                            color = LiquidGlassTheme.accentSecondary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "当前版本：v${UpdateChecker.LOCAL_VERSION}",
                            color = LiquidGlassTheme.onSurfaceMuted,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "更新内容：",
                            color = LiquidGlassTheme.onSurfaceColor,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            info.releaseNotes,
                            color = LiquidGlassTheme.onSurfaceMuted,
                            fontSize = 12.sp
                        )

                        if (downloading || downloadedFile != null || downloadError != null) {
                            Spacer(Modifier.height(12.dp))

                            // 下载进度
                            if (downloading) {
                                val progress = if (totalBytes > 0) {
                                    (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                                } else 0f
                                LinearProgressIndicator(
                                    progress = progress,
                                    modifier = Modifier.fillMaxWidth().height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = LiquidGlassTheme.accentSecondary,
                                    backgroundColor = LiquidGlassTheme.surfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = if (totalBytes > 0)
                                        "$stage · ${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}"
                                    else "$stage · ${formatBytes(downloadedBytes)}",
                                    color = LiquidGlassTheme.onSurfaceMuted,
                                    fontSize = 11.sp
                                )
                            }

                            // 实时日志（展开/隐藏）
                            Spacer(Modifier.height(8.dp))
                            LogToggle(
                                expanded = showLogs,
                                onToggle = { showLogs = !showLogs }
                            )
                            AnimatedVisibility(
                                visible = showLogs,
                                enter = expandVertically(),
                                exit = shrinkVertically()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(120.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(LiquidGlassTheme.surfaceColor)
                                        .padding(8.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    downloadLogs.forEach { log ->
                                        Text(
                                            text = log,
                                            color = LiquidGlassTheme.onSurfaceMuted,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }

                            downloadError?.let { err ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "下载失败：$err",
                                    color = LiquidGlassTheme.announcementHigh,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // 下载完成后显示快捷方式选项
                        if (downloadedFile != null) {
                            Spacer(Modifier.height(12.dp))
                            Text("安装选项：", color = LiquidGlassTheme.onSurfaceColor, fontSize = 13.sp)
                            Spacer(Modifier.height(4.dp))
                            CheckboxRow(
                                text = "创建桌面快捷方式",
                                checked = createDesktopShortcut,
                                onCheckedChange = { createDesktopShortcut = it }
                            )
                            CheckboxRow(
                                text = "创建开始菜单快捷方式",
                                checked = createStartMenuShortcut,
                                onCheckedChange = { createStartMenuShortcut = it }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                checking -> {
                    OutlinedButton(onClick = onDismiss, enabled = false) { Text("取消") }
                }
                updateInfo != null && downloadedFile == null -> {
                    Button(
                        onClick = {
                            downloading = true
                            downloadError = null
                            downloadLogs = emptyList()
                            downloadedBytes = 0
                            totalBytes = -1
                            scope.launch {
                                val info = updateInfo!!
                                val targetDir = File(System.getProperty("user.home"), ".liquidglass/updates")
                                val file = UpdateChecker.downloadUpdate(
                                    info = info,
                                    targetDir = targetDir,
                                    onProgress = { downloaded, total ->
                                        downloadedBytes = downloaded
                                        totalBytes = total
                                    },
                                    onStage = { s ->
                                        stage = s
                                        downloadLogs = downloadLogs + "[${
                                            java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())
                                        }] $s"
                                    }
                                )
                                downloading = false
                                if (file != null) {
                                    downloadedFile = file
                                } else {
                                    downloadError = "所有镜像源均失败"
                                }
                            }
                        },
                        enabled = !downloading,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = LiquidGlassTheme.accentPrimary,
                            contentColor = Color.White
                        )
                    ) { Text(if (downloading) "下载中..." else "下载更新") }
                }
                downloadedFile != null -> {
                    Button(
                        onClick = {
                            val file = downloadedFile!!
                            // v2.10.1：用 ProcessBuilder 独立进程启动安装程序
                            // 关键修复：必须先 detach 子进程，再退出当前 JVM
                            // 否则当前 exe 被占用，Inno Setup 无法替换文件，导致"已损坏需 repair"
                            try {
                                val pb = ProcessBuilder(file.absolutePath)
                                pb.directory(file.parentFile)
                                pb.redirectErrorStream(true)
                                // 独立进程：子进程不随父进程退出
                                pb.start()
                                // 给子进程 500ms 启动时间
                                Thread.sleep(500)
                            } catch (_: Exception) { }
                            // 立即退出当前应用，释放 exe 文件占用
                            onExitApplication()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = LiquidGlassTheme.green,
                            contentColor = Color.Black
                        )
                    ) { Text("立即安装") }
                }
                else -> {
                    OutlinedButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        },
        dismissButton = {
            if (!downloading) {
                OutlinedButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
private fun LogToggle(expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(200),
        label = "logToggle"
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "›",
            color = LiquidGlassTheme.accentSecondary,
            modifier = Modifier.rotate(rotation)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "下载日志",
            color = LiquidGlassTheme.onSurfaceMuted,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun CheckboxRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = LiquidGlassTheme.accentSecondary,
                uncheckedColor = LiquidGlassTheme.onSurfaceMuted
            )
        )
        Text(
            text = text,
            color = LiquidGlassTheme.onSurfaceColor,
            fontSize = 13.sp
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
