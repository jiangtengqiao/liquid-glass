package com.liquidglass.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.liquidglass.app.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ──────────────────────────────────────────────
// Data
// ──────────────────────────────────────────────

enum class FileType {
    FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, CODE, APK, UNKNOWN
}

enum class SortMode {
    NAME, SIZE, DATE, TYPE
}

data class FileInfo(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val fileType: FileType
)

// ──────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val size = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "%.1f %s".format(size, units[digitGroups.coerceIn(0, units.lastIndex)])
}

private fun formatDate(millis: Long): String {
    val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}

private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "heic", "heif", "ico", "tiff")
private val videoExtensions = setOf("mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "3gp", "m4v", "ts")
private val audioExtensions = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus", "mid", "midi")
private val documentExtensions = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "odt", "ods", "odp", "md", "log"
)
private val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "tbz", "iso", "dmg")
private val codeExtensions = setOf(
    "kt", "java", "py", "js", "ts", "html", "htm", "css", "scss", "less", "json", "xml", "yaml", "yml",
    "c", "cpp", "h", "hpp", "cs", "rb", "go", "rs", "swift", "sh", "bat", "sql", "gradle", "properties", "toml"
)

private fun getFileType(file: File): FileType {
    if (file.isDirectory) return FileType.FOLDER
    val ext = file.extension.lowercase()
    return when {
        ext in imageExtensions -> FileType.IMAGE
        ext in videoExtensions -> FileType.VIDEO
        ext in audioExtensions -> FileType.AUDIO
        ext in documentExtensions -> FileType.DOCUMENT
        ext in archiveExtensions -> FileType.ARCHIVE
        ext in codeExtensions -> FileType.CODE
        ext == "apk" -> FileType.APK
        else -> FileType.UNKNOWN
    }
}

private fun getFileTypeIcon(fileType: FileType): ImageVector = when (fileType) {
    FileType.FOLDER -> Icons.Filled.Folder
    FileType.IMAGE -> Icons.Filled.Image
    FileType.VIDEO -> Icons.Filled.Videocam
    FileType.AUDIO -> Icons.Filled.MusicNote
    FileType.DOCUMENT -> Icons.Filled.Description
    FileType.ARCHIVE -> Icons.Filled.Archive
    FileType.CODE -> Icons.Filled.Code
    FileType.APK -> Icons.Filled.Android
    FileType.UNKNOWN -> Icons.Filled.InsertDriveFile
}

private fun getFileTypeColor(fileType: FileType): Color = when (fileType) {
    FileType.FOLDER -> FluidCyan
    FileType.IMAGE -> FluidPink
    FileType.VIDEO -> FluidPurple
    FileType.AUDIO -> FluidOrange
    FileType.DOCUMENT -> AccentPrimary
    FileType.ARCHIVE -> AccentWarning
    FileType.CODE -> FluidTeal
    FileType.APK -> FluidTeal
    FileType.UNKNOWN -> TextSecondary
}

private fun getFileTypeMime(fileType: FileType): String = when (fileType) {
    FileType.FOLDER -> "*/*"
    FileType.IMAGE -> "image/*"
    FileType.VIDEO -> "video/*"
    FileType.AUDIO -> "audio/*"
    FileType.DOCUMENT -> "application/*"
    FileType.ARCHIVE -> "application/zip"
    FileType.CODE -> "text/plain"
    FileType.APK -> "application/vnd.android.package-archive"
    FileType.UNKNOWN -> "*/*"
}

// ──────────────────────────────────────────────
// Storage Info
// ──────────────────────────────────────────────

private data class StorageInfo(
    val totalBytes: Long,
    val availableBytes: Long,
    val usedBytes: Long
)

private fun getStorageInfo(context: Context, path: String): StorageInfo {
    return try {
        val stat = StatFs(path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong
        val total = blockSize * totalBlocks
        val available = blockSize * availableBlocks
        val used = total - available
        StorageInfo(total, available, used)
    } catch (_: Exception) {
        StorageInfo(0, 0, 0)
    }
}

// ──────────────────────────────────────────────
// Main Screen
// ──────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileManagerScreen(animTime: Float, onBack: () -> Unit) {
    val context = LocalContext.current

    val externalDir = remember {
        Environment.getExternalStorageDirectory().absolutePath
    }

    var currentPath by remember { mutableStateOf(externalDir) }
    var files by remember { mutableStateOf(listFiles(currentPath)) }
    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var isSearchActive by remember { mutableStateOf(false) }
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf(setOf<String>()) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<FileInfo?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<FileInfo?>(null) }
    var showPropertiesDialog by remember { mutableStateOf<FileInfo?>(null) }

    val storageInfo = remember(currentPath) { getStorageInfo(context, currentPath) }

    val sortedFiles = remember(files, sortMode, searchQuery) {
        val filtered = if (searchQuery.isBlank()) files
        else files.filter { it.name.contains(searchQuery, ignoreCase = true) }
        val sorted = when (sortMode) {
            SortMode.NAME -> filtered.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            SortMode.SIZE -> filtered.sortedWith(compareBy({ !it.isDirectory }, { it.size }))
            SortMode.DATE -> filtered.sortedWith(compareBy({ !it.isDirectory }, { it.lastModified }))
            SortMode.TYPE -> filtered.sortedWith(compareBy({ !it.isDirectory }, { it.fileType.ordinal }, { it.name.lowercase() }))
        }
        sorted
    }

    fun refreshFiles() {
        files = listFiles(currentPath)
        selectedFiles = emptySet()
        multiSelectMode = false
    }

    fun navigateTo(path: String) {
        currentPath = path
        searchQuery = ""
        isSearchActive = false
        refreshFiles()
    }

    fun navigateUp() {
        val parent = File(currentPath).parentFile
        if (parent != null && parent.canRead()) {
            navigateTo(parent.absolutePath)
        }
    }

    // Permission launcher for MANAGE_EXTERNAL_STORAGE (Android 11+)
    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        refreshFiles()
    }

    // Permission launcher for READ_EXTERNAL_STORAGE (Android 10-)
    val readStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) refreshFiles()
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                manageStorageLauncher.launch(intent)
            }
        } else {
            readStorageLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    // ── UI ──────────────────────────────────────

    LiquidGlassScaffold(animTime = animTime) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            // ▸ Top bar with back button
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = appTextSecondary())
                }
                Text(
                    "文件管理",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = appTextSecondary(),
                    modifier = Modifier.weight(1f)
                )
                if (multiSelectMode) {
                    Text(
                        "已选 ${selectedFiles.size} 项",
                        fontSize = 13.sp,
                        color = AccentPrimary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    TextButton(onClick = {
                        multiSelectMode = false
                        selectedFiles = emptySet()
                    }) {
                        Text("取消", color = appTextSecondary(), fontSize = 13.sp)
                    }
                }
                // Sort button
                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.Default.Sort, "排序", tint = appTextSecondary())
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        val sortModes = listOf(
                            SortMode.NAME to "按名称",
                            SortMode.SIZE to "按大小",
                            SortMode.DATE to "按日期",
                            SortMode.TYPE to "按类型"
                        )
                        sortModes.forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (mode == sortMode) {
                                            Icon(
                                                Icons.Default.Check,
                                                null,
                                                tint = AccentPrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text(label, fontSize = 14.sp)
                                    }
                                },
                                onClick = {
                                    sortMode = mode
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ▸ Search bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 18.dp, glassAlpha = 0.12f)
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Search,
                    null,
                    tint = appTextTertiary(),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索文件...", color = appTextTertiary()) },
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = AccentPrimary,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { })
                )
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { searchQuery = "" },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Clear, "清除", tint = appTextTertiary(), modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ▸ Breadcrumb navigation
            BreadcrumbBar(
                currentPath = currentPath,
                onNavigate = { navigateTo(it) },
                onNavigateUp = { navigateUp() }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ▸ File list
            if (sortedFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .glassSurface(cornerRadius = 24.dp, glassAlpha = 0.10f),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (searchQuery.isNotEmpty()) Icons.Default.SearchOff else Icons.Default.FolderOpen,
                                null,
                                tint = appTextTertiary(),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            if (searchQuery.isNotEmpty()) "未找到匹配的文件" else "此目录为空",
                            fontSize = 15.sp,
                            color = appTextSecondary()
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(sortedFiles, key = { it.file.absolutePath }) { fileInfo ->
                        FileItem(
                            fileInfo = fileInfo,
                            isSelected = selectedFiles.contains(fileInfo.file.absolutePath),
                            multiSelectMode = multiSelectMode,
                            onTap = {
                                if (multiSelectMode) {
                                    selectedFiles = if (selectedFiles.contains(fileInfo.file.absolutePath)) {
                                        selectedFiles - fileInfo.file.absolutePath
                                    } else {
                                        selectedFiles + fileInfo.file.absolutePath
                                    }
                                } else {
                                    if (fileInfo.isDirectory) {
                                        navigateTo(fileInfo.file.absolutePath)
                                    } else {
                                        openFile(context, fileInfo)
                                    }
                                }
                            },
                            onLongPress = {
                                if (!multiSelectMode) {
                                    multiSelectMode = true
                                    selectedFiles = setOf(fileInfo.file.absolutePath)
                                }
                            },
                            onShare = { shareFile(context, fileInfo) },
                            onRename = { showRenameDialog = fileInfo },
                            onDelete = { showDeleteConfirm = fileInfo },
                            onCopyPath = {
                                copyToClipboard(context, fileInfo.file.absolutePath)
                                Toast.makeText(context, "路径已复制", Toast.LENGTH_SHORT).show()
                            },
                            onProperties = { showPropertiesDialog = fileInfo }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }

        // ▸ Storage info card — bottom overlay
        StorageInfoCard(
            storageInfo = storageInfo,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp, vertical = 8.dp)
        )
    }

    // ── Dialogs ─────────────────────────────────

    // Rename dialog
    if (showRenameDialog != null) {
        RenameDialog(
            fileInfo = showRenameDialog!!,
            onDismiss = { showRenameDialog = null },
            onConfirm = { newName ->
                val file = showRenameDialog!!.file
                val newFile = File(file.parent, newName)
                if (file.renameTo(newFile)) {
                    refreshFiles()
                    Toast.makeText(context, "重命名成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "重命名失败", Toast.LENGTH_SHORT).show()
                }
                showRenameDialog = null
            }
        )
    }

    // Delete confirmation
    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            containerColor = appBgColor2(),
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            icon = { Icon(Icons.Default.Delete, null, tint = AccentDanger, modifier = Modifier.size(32.dp)) },
            title = { Text("删除文件") },
            text = {
                Text("确定要删除「${showDeleteConfirm!!.name}」吗？此操作不可撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val deleted = showDeleteConfirm!!.file.deleteRecursively()
                        if (deleted) {
                            refreshFiles()
                            Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
                        }
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentDanger)
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) { Text("取消") }
            }
        )
    }

    // Properties dialog
    if (showPropertiesDialog != null) {
        PropertiesDialog(
            fileInfo = showPropertiesDialog!!,
            onDismiss = { showPropertiesDialog = null }
        )
    }
}

// ──────────────────────────────────────────────
// Breadcrumb Bar
// ──────────────────────────────────────────────

@Composable
private fun BreadcrumbBar(
    currentPath: String,
    onNavigate: (String) -> Unit,
    onNavigateUp: () -> Unit
) {
    val pathParts = remember(currentPath) {
        val parts = mutableListOf<String>()
        var f = File(currentPath)
        while (true) {
            parts.add(0, f.absolutePath)
            val parent = f.parentFile ?: break
            f = parent
        }
        parts
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.10f)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onNavigateUp,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(Icons.Default.ArrowUpward, "上级目录", tint = appTextSecondary(), modifier = Modifier.size(16.dp))
        }
        Spacer(modifier = Modifier.width(4.dp))

        Icon(
            Icons.Default.FolderOpen,
            null,
            tint = FluidCyan.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))

        if (pathParts.size <= 2) {
            Text(
                text = pathParts.lastOrNull() ?: "/",
                fontSize = 12.sp,
                color = appTextSecondary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            val displayParts = pathParts.drop(1)
            displayParts.forEachIndexed { index, part ->
                val name = File(part).name.ifEmpty { "/" }
                if (index < displayParts.lastIndex) {
                    Text(
                        text = "$name  ›  ",
                        fontSize = 12.sp,
                        color = appTextTertiary(),
                        maxLines = 1,
                        modifier = Modifier.clickable { onNavigate(part) }
                    )
                } else {
                    Text(
                        text = name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = appTextSecondary(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
// File Item
// ──────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileItem(
    fileInfo: FileInfo,
    isSelected: Boolean,
    multiSelectMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyPath: () -> Unit,
    onProperties: () -> Unit
) {
    var showContextMenu by remember { mutableStateOf(false) }
    val accentColor = getFileTypeColor(fileInfo.fileType)
    val bgAlpha = if (isSelected) 0.20f else 0.08f

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(cornerRadius = 14.dp, glassAlpha = bgAlpha)
                .combinedClickable(
                    onClick = onTap,
                    onLongClick = onLongPress
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Multi-select checkbox
            if (multiSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onTap() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = AccentPrimary,
                        uncheckedColor = TextTertiary,
                        checkmarkColor = Color.White
                    ),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            // File icon
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getFileTypeIcon(fileInfo.fileType),
                    contentDescription = null,
                    tint = accentColor.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Name + metadata
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileInfo.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = appTextPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!fileInfo.isDirectory) {
                        Text(
                            text = formatFileSize(fileInfo.size),
                            fontSize = 11.sp,
                            color = appTextTertiary()
                        )
                        Text(
                            text = " · ",
                            fontSize = 11.sp,
                            color = appTextTertiary()
                        )
                    }
                    Text(
                        text = formatDate(fileInfo.lastModified),
                        fontSize = 11.sp,
                        color = appTextTertiary()
                    )
                }
            }

            // Context menu button
            if (!multiSelectMode) {
                Box {
                    IconButton(
                        onClick = { showContextMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            "更多",
                            tint = appTextTertiary(),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showContextMenu,
                        onDismissRequest = { showContextMenu = false }
                    ) {
                        if (!fileInfo.isDirectory) {
                            DropdownMenuItem(
                                text = { Text("打开", fontSize = 14.sp) },
                                onClick = { showContextMenu = false; onTap() },
                                leadingIcon = { Icon(Icons.Default.OpenInNew, null, tint = appTextSecondary(), modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text("分享", fontSize = 14.sp) },
                                onClick = { showContextMenu = false; onShare() },
                                leadingIcon = { Icon(Icons.Default.Share, null, tint = appTextSecondary(), modifier = Modifier.size(18.dp)) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("重命名", fontSize = 14.sp) },
                            onClick = { showContextMenu = false; onRename() },
                            leadingIcon = { Icon(Icons.Default.Edit, null, tint = appTextSecondary(), modifier = Modifier.size(18.dp)) }
                        )
                        DropdownMenuItem(
                            text = { Text("删除", fontSize = 14.sp) },
                            onClick = { showContextMenu = false; onDelete() },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = AccentDanger, modifier = Modifier.size(18.dp)) }
                        )
                        DropdownMenuItem(
                            text = { Text("复制路径", fontSize = 14.sp) },
                            onClick = { showContextMenu = false; onCopyPath() },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null, tint = appTextSecondary(), modifier = Modifier.size(18.dp)) }
                        )
                        DropdownMenuItem(
                            text = { Text("属性", fontSize = 14.sp) },
                            onClick = { showContextMenu = false; onProperties() },
                            leadingIcon = { Icon(Icons.Default.Info, null, tint = appTextSecondary(), modifier = Modifier.size(18.dp)) }
                        )
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
// Storage Info Card
// ──────────────────────────────────────────────

@Composable
private fun StorageInfoCard(
    storageInfo: StorageInfo,
    modifier: Modifier = Modifier
) {
    val usedPercent = if (storageInfo.totalBytes > 0) {
        (storageInfo.usedBytes.toFloat() / storageInfo.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.16f)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "存储空间",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = appTextSecondary()
                )
                Text(
                    text = "${(usedPercent * 100).toInt()}% 已使用",
                    fontSize = 11.sp,
                    color = appTextTertiary()
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            // Progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(GlassLight)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(usedPercent)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(FluidCyan, AccentPrimary)
                            )
                        )
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "可用",
                        fontSize = 10.sp,
                        color = appTextTertiary()
                    )
                    Text(
                        text = formatFileSize(storageInfo.availableBytes),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = AccentSuccess
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "已用",
                        fontSize = 10.sp,
                        color = appTextTertiary()
                    )
                    Text(
                        text = formatFileSize(storageInfo.usedBytes),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = appTextSecondary()
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "总计",
                        fontSize = 10.sp,
                        color = appTextTertiary()
                    )
                    Text(
                        text = formatFileSize(storageInfo.totalBytes),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = appTextSecondary()
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
// Rename Dialog
// ──────────────────────────────────────────────

@Composable
private fun RenameDialog(
    fileInfo: FileInfo,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newName by remember(fileInfo) { mutableStateOf(fileInfo.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = appBgColor2(),
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("重命名") },
        text = {
            Column {
                Text("请输入新名称：", fontSize = 13.sp, color = appTextSecondary())
                Spacer(modifier = Modifier.height(10.dp))
                TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = GlassLight,
                        unfocusedContainerColor = GlassClear,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = AccentPrimary,
                        focusedIndicatorColor = AccentPrimary,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newName.isNotBlank() && newName != fileInfo.name) {
                        onConfirm(newName.trim())
                    } else {
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.textButtonColors(contentColor = AccentPrimary)
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
            ) { Text("取消") }
        }
    )
}

// ──────────────────────────────────────────────
// Properties Dialog
// ──────────────────────────────────────────────

@Composable
private fun PropertiesDialog(
    fileInfo: FileInfo,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = appBgColor2(),
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        icon = {
            Icon(
                getFileTypeIcon(fileInfo.fileType),
                null,
                tint = getFileTypeColor(fileInfo.fileType),
                modifier = Modifier.size(32.dp)
            )
        },
        title = { Text(fileInfo.name, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                PropertyRow("路径", fileInfo.file.parent ?: "/")
                PropertyRow("类型", if (fileInfo.isDirectory) "文件夹" else fileInfo.fileType.name)
                if (!fileInfo.isDirectory) {
                    PropertyRow("大小", formatFileSize(fileInfo.size))
                }
                PropertyRow("修改时间", formatDate(fileInfo.lastModified))
                if (!fileInfo.isDirectory) {
                    PropertyRow("扩展名", fileInfo.file.extension.ifEmpty { "无" })
                }
                PropertyRow("可读", if (fileInfo.file.canRead()) "是" else "否")
                PropertyRow("可写", if (fileInfo.file.canWrite()) "是" else "否")
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = AccentPrimary)
            ) { Text("关闭") }
        }
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$label：",
            fontSize = 13.sp,
            color = appTextTertiary(),
            modifier = Modifier.width(64.dp)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = appTextPrimary(),
            modifier = Modifier.weight(1f)
        )
    }
}

// ──────────────────────────────────────────────
// File Operations
// ──────────────────────────────────────────────

private fun listFiles(path: String): List<FileInfo> {
    val dir = File(path)
    if (!dir.exists() || !dir.isDirectory || !dir.canRead()) return emptyList()
    val children = dir.listFiles() ?: return emptyList()
    return children.map { file ->
        FileInfo(
            file = file,
            name = file.name,
            isDirectory = file.isDirectory,
            size = if (file.isFile) file.length() else 0L,
            lastModified = file.lastModified(),
            fileType = getFileType(file)
        )
    }
}

private fun openFile(context: Context, fileInfo: FileInfo) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            fileInfo.file
        )
        val mimeType = getFileTypeMime(fileInfo.fileType)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            // Fallback: try opening with a generic intent
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (fallbackIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(fallbackIntent)
            } else {
                Toast.makeText(context, "无法打开此文件", Toast.LENGTH_SHORT).show()
            }
        }
    } catch (e: Exception) {
        try {
            // Fallback: try with Uri.fromFile for older devices
            val uri = Uri.fromFile(fileInfo.file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                Toast.makeText(context, "无法打开此文件", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(context, "无法打开此文件", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun shareFile(context: Context, fileInfo: FileInfo) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            fileInfo.file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = getFileTypeMime(fileInfo.fileType)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 ${fileInfo.name}"))
    } catch (e: Exception) {
        try {
            val uri = Uri.fromFile(fileInfo.file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
            }
            context.startActivity(Intent.createChooser(intent, "分享 ${fileInfo.name}"))
        } catch (_: Exception) {
            Toast.makeText(context, "无法分享此文件", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText("file_path", text)
    clipboard.setPrimaryClip(clip)
}