package com.liquidglass.app.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.EnumMap
import kotlin.math.abs

// ─────────────────────────────────────────────────────────────────
// 二维码 / 条形码生成 — 基于 ZXing（业界标准库）
//
// 替代此前手写实现。手写版有两个致命缺陷：
//  1. 只支持 alphanumeric 模式，URL 中的 ? & = 及中文 indexOf 返回 -1 → 生成乱码二维码扫不出
//  2. Code128B 码表只有 100 项，访问索引 104/106 越界崩溃
// ZXing 自动选择最佳编码模式（数字/字母/字节/汉字），URL/中文/特殊字符全部正确，且码表完整。
// ─────────────────────────────────────────────────────────────────

/**
 * 用 ZXing 生成二维码 BitMatrix。
 * 自动选择编码模式，支持 URL、中文、特殊字符、emoji 等。
 */
fun encodeQrMatrix(text: String): BitMatrix {
    val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
    hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
    hints[EncodeHintType.ERROR_CORRECTION] = com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
    hints[EncodeHintType.MARGIN] = 1
    return MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
}

/**
 * 用 ZXing 生成 Code128 条形码 BitMatrix。
 * 支持 ASCII 可打印字符（32-126），不再有码表越界问题。
 */
fun encodeCode128Matrix(text: String): BitMatrix {
    val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
    hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
    hints[EncodeHintType.MARGIN] = 1
    return MultiFormatWriter().encode(text, BarcodeFormat.CODE_128, 0, 0, hints)
}

// ─────────────────────────────────────────────────────────────────
// History data model & persistence
// ─────────────────────────────────────────────────────────────────

data class QrHistoryItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String,
    val type: String = "qr", // "qr" or "barcode"
    val timestamp: Long = System.currentTimeMillis()
)

private const val QR_PREFS = "liquid_glass_qr_history"
private const val QR_HISTORY_KEY = "qr_history"

private fun saveQrHistory(context: Context, items: List<QrHistoryItem>) {
    val arr = JSONArray()
    for (item in items) {
        arr.put(JSONObject().apply {
            put("id", item.id)
            put("content", item.content)
            put("type", item.type)
            put("timestamp", item.timestamp)
        })
    }
    context.getSharedPreferences(QR_PREFS, Context.MODE_PRIVATE)
        .edit().putString(QR_HISTORY_KEY, arr.toString()).apply()
}

private fun loadQrHistory(context: Context): List<QrHistoryItem> {
    val json = context.getSharedPreferences(QR_PREFS, Context.MODE_PRIVATE)
        .getString(QR_HISTORY_KEY, null) ?: return emptyList()
    val items = mutableListOf<QrHistoryItem>()
    try {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            items.add(QrHistoryItem(
                id = obj.getString("id"),
                content = obj.optString("content", ""),
                type = obj.optString("type", "qr"),
                timestamp = obj.optLong("timestamp", 0)
            ))
        }
    } catch (_: Exception) {}
    return items
}

// ─────────────────────────────────────────────────────────────────
// Bitmap helpers
// ─────────────────────────────────────────────────────────────────

private fun drawQrToBitmap(matrix: BitMatrix, moduleSize: Int, padding: Int = 4): Bitmap {
    val width = matrix.width
    val height = matrix.height
    val totalSize = maxOf(width, height) * moduleSize + padding * 2
    val bitmap = Bitmap.createBitmap(totalSize, totalSize, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.BLACK

    for (r in 0 until height) {
        for (c in 0 until width) {
            if (matrix[c, r]) {
                val left = (padding + c * moduleSize).toFloat()
                val top = (padding + r * moduleSize).toFloat()
                canvas.drawRect(left, top, left + moduleSize, top + moduleSize, paint)
            }
        }
    }
    return bitmap
}

private fun drawBarcodeToBitmap(matrix: BitMatrix, barHeight: Int, barWidth: Int = 3, padding: Int = 10): Bitmap {
    val width = matrix.width
    val totalWidth = width * barWidth + padding * 2
    val totalHeight = barHeight + padding * 2
    val bitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.BLACK

    for (c in 0 until width) {
        if (matrix[c, 0]) {
            val left = (padding + c * barWidth).toFloat()
            canvas.drawRect(left, padding.toFloat(), left + barWidth, (padding + barHeight).toFloat(), paint)
        }
    }
    return bitmap
}

private fun saveBitmapToGallery(context: Context, bitmap: Bitmap, fileName: String): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LiquidGlass")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                val os: OutputStream? = context.contentResolver.openOutputStream(uri)
                os?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                os?.close()
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                true
            } else false
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val file = java.io.File(dir, fileName)
            val os = java.io.FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
            os.flush()
            os.close()
            MediaStore.Images.Media.insertImage(context.contentResolver, file.absolutePath, fileName, null)
            true
        }
    } catch (e: Exception) {
        false
    }
}

private fun shareBitmap(context: Context, bitmap: Bitmap) {
    try {
        val file = java.io.File(context.cacheDir, "share_qr.png")
        val os = java.io.FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
        os.flush()
        os.close()
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享二维码"))
    } catch (_: Exception) {
        Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
    }
}

// ─────────────────────────────────────────────────────────────────
// QRCodeScreen Composable
// ─────────────────────────────────────────────────────────────────

@Composable
fun QRCodeScreen(animTime: Float, onBack: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var currentTab by remember { mutableIntStateOf(0) } // 0=QR, 1=Barcode, 2=History
    var qrText by remember { mutableStateOf("") }
    var barcodeText by remember { mutableStateOf("") }

    var qrMatrix by remember { mutableStateOf<BitMatrix?>(null) }
    var barcodeMatrix by remember { mutableStateOf<BitMatrix?>(null) }
    var generatedContent by remember { mutableStateOf("") }

    var history by remember { mutableStateOf(loadQrHistory(context)) }
    var showDeleteConfirm by remember { mutableStateOf<QrHistoryItem?>(null) }
    var showClearAllConfirm by remember { mutableStateOf(false) }

    fun persistHistory(items: List<QrHistoryItem>) {
        history = items
        saveQrHistory(context, items)
    }

    fun addToHistory(content: String, type: String) {
        val item = QrHistoryItem(content = content, type = type)
        val updated = listOf(item) + history
        persistHistory(updated.take(50))
    }

    fun generateQr() {
        if (qrText.isBlank()) return
        focusManager.clearFocus()
        // ZXing 编码在 IO 线程执行，避免长文本阻塞主线程
        scope.launch {
            try {
                val matrix = withContext(Dispatchers.Default) { encodeQrMatrix(qrText) }
                qrMatrix = matrix
                generatedContent = qrText
                addToHistory(qrText, "qr")
            } catch (e: Exception) {
                Toast.makeText(context, "生成失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun generateBarcode() {
        if (barcodeText.isBlank()) return
        focusManager.clearFocus()
        scope.launch {
            try {
                val matrix = withContext(Dispatchers.Default) { encodeCode128Matrix(barcodeText) }
                barcodeMatrix = matrix
                generatedContent = barcodeText
                addToHistory(barcodeText, "barcode")
            } catch (e: Exception) {
                Toast.makeText(context, "生成失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun saveQrImage() {
        val matrix = qrMatrix ?: return
        val bitmap = drawQrToBitmap(matrix, 20, 16)
        val success = saveBitmapToGallery(context, bitmap, "QR_${System.currentTimeMillis()}.png")
        Toast.makeText(context, if (success) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
    }

    fun saveBarcodeImage() {
        val matrix = barcodeMatrix ?: return
        val bitmap = drawBarcodeToBitmap(matrix, 300, 3, 10)
        val success = saveBitmapToGallery(context, bitmap, "Barcode_${System.currentTimeMillis()}.png")
        Toast.makeText(context, if (success) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
    }

    fun shareQrImage() {
        val matrix = qrMatrix ?: return
        val bitmap = drawQrToBitmap(matrix, 20, 16)
        shareBitmap(context, bitmap)
    }

    fun shareBarcodeImage() {
        val matrix = barcodeMatrix ?: return
        val bitmap = drawBarcodeToBitmap(matrix, 300, 3, 10)
        shareBitmap(context, bitmap)
    }

    fun regenerateFromHistory(item: QrHistoryItem) {
        if (item.type == "qr") {
            qrText = item.content
            currentTab = 0
            scope.launch {
                kotlinx.coroutines.delay(100)
                generateQr()
            }
        } else {
            barcodeText = item.content
            currentTab = 1
            scope.launch {
                kotlinx.coroutines.delay(100)
                generateBarcode()
            }
        }
    }

    LiquidGlassScaffold(animTime = animTime) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = appTextSecondary())
                }
                Text(
                    "二维码工具",
                    fontSize = 16.sp,
                    color = appTextSecondary(),
                    modifier = Modifier.weight(1f)
                )
                if (qrMatrix != null || barcodeMatrix != null) {
                    IconButton(onClick = {
                        if (currentTab == 0) shareQrImage() else shareBarcodeImage()
                    }) {
                        Icon(Icons.Default.Share, "分享", tint = appTextSecondary(), modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = {
                        if (currentTab == 0) saveQrImage() else saveBarcodeImage()
                    }) {
                        Icon(Icons.Default.SaveAlt, "保存", tint = appTextSecondary(), modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tab bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.10f)
                    .padding(4.dp)
            ) {
                val tabs = listOf("二维码", "条形码", "历史记录")
                val icons = listOf(Icons.Default.QrCode, Icons.Default.ViewList, Icons.Default.History)
                for (i in tabs.indices) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .then(
                                if (currentTab == i) Modifier.background(
                                    androidx.compose.ui.graphics.Brush.linearGradient(
                                        listOf(FluidCyan.copy(alpha = 0.2f), FluidPurple.copy(alpha = 0.2f))
                                    )
                                )
                                else Modifier
                            )
                            .clickable { currentTab = i }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                icons[i], null,
                                tint = if (currentTab == i) FluidCyan else TextTertiary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                tabs[i],
                                fontSize = 12.sp,
                                color = if (currentTab == i) FluidCyan else TextTertiary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Content area
            when (currentTab) {
                0 -> QrGeneratorTab(
                    qrText = qrText,
                    onQrTextChange = { qrText = it },
                    onGenerate = { generateQr() },
                    qrMatrix = qrMatrix,
                    generatedContent = generatedContent
                )
                1 -> BarcodeGeneratorTab(
                    barcodeText = barcodeText,
                    onBarcodeTextChange = { barcodeText = it },
                    onGenerate = { generateBarcode() },
                    barcodeMatrix = barcodeMatrix,
                    generatedContent = generatedContent
                )
                2 -> HistoryTab(
                    history = history,
                    onRegenerate = { regenerateFromHistory(it) },
                    onDelete = { showDeleteConfirm = it },
                    onClearAll = { showClearAllConfirm = true }
                )
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            containerColor = appBgColor2(),
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("删除记录") },
            text = { Text("确定要删除这条记录吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        persistHistory(history.filter { it.id != showDeleteConfirm!!.id })
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

    // Clear all confirmation
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            containerColor = appBgColor2(),
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("清空历史") },
            text = { Text("确定要清空所有历史记录吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        persistHistory(emptyList())
                        showClearAllConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentDanger)
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearAllConfirm = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) { Text("取消") }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// QR Generator Tab
// ─────────────────────────────────────────────────────────────────

@Composable
private fun QrGeneratorTab(
    qrText: String,
    onQrTextChange: (String) -> Unit,
    onGenerate: () -> Unit,
    qrMatrix: BitMatrix?,
    generatedContent: String
) {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        // Input field
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(cornerRadius = 18.dp, glassAlpha = 0.12f)
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            TextField(
                value = qrText,
                onValueChange = { if (it.length <= 200) onQrTextChange(it) },
                placeholder = { Text("输入网址或文本内容...", color = appTextTertiary(), fontSize = 14.sp) },
                modifier = Modifier.fillMaxWidth(),
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onGenerate(); focusManager.clearFocus() })
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Generate button
        Button(
            onClick = { onGenerate(); focusManager.clearFocus() },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = FluidCyan.copy(alpha = 0.2f),
                contentColor = FluidCyan,
                disabledContainerColor = GlassLight,
                disabledContentColor = TextTertiary
            ),
            enabled = qrText.isNotBlank()
        ) {
            Icon(Icons.Default.QrCode, null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("生成二维码", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }

        // QR Code display
        if (qrMatrix != null) {
            Spacer(modifier = Modifier.height(20.dp))

            // Content label
            Text(
                generatedContent,
                fontSize = 12.sp,
                color = appTextTertiary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))

            // QR Code canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 24.dp, glassAlpha = 0.15f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                QrCodeCanvas(
                    matrix = qrMatrix,
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .aspectRatio(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionButton(
                    icon = Icons.Default.SaveAlt,
                    label = "保存",
                    color = FluidTeal,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val bitmap = drawQrToBitmap(qrMatrix, 20, 16)
                        val success = saveBitmapToGallery(context, bitmap, "QR_${System.currentTimeMillis()}.png")
                        Toast.makeText(context, if (success) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                    }
                )
                ActionButton(
                    icon = Icons.Default.Share,
                    label = "分享",
                    color = FluidPurple,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val bitmap = drawQrToBitmap(qrMatrix, 20, 16)
                        shareBitmap(context, bitmap)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

// ─────────────────────────────────────────────────────────────────
// Barcode Generator Tab
// ─────────────────────────────────────────────────────────────────

@Composable
private fun BarcodeGeneratorTab(
    barcodeText: String,
    onBarcodeTextChange: (String) -> Unit,
    onGenerate: () -> Unit,
    barcodeMatrix: BitMatrix?,
    generatedContent: String
) {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        // Input field
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(cornerRadius = 18.dp, glassAlpha = 0.12f)
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            TextField(
                value = barcodeText,
                onValueChange = { if (it.length <= 100) onBarcodeTextChange(it) },
                placeholder = { Text("输入条形码内容（支持字母数字符号）...", color = appTextTertiary(), fontSize = 14.sp) },
                modifier = Modifier.fillMaxWidth(),
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onGenerate(); focusManager.clearFocus() })
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Generate button
        Button(
            onClick = { onGenerate(); focusManager.clearFocus() },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = FluidPurple.copy(alpha = 0.2f),
                contentColor = FluidPurple,
                disabledContainerColor = GlassLight,
                disabledContentColor = TextTertiary
            ),
            enabled = barcodeText.isNotBlank()
        ) {
            Icon(Icons.Default.ViewList, null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("生成条形码", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }

        // Barcode display
        if (barcodeMatrix != null) {
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                generatedContent,
                fontSize = 12.sp,
                color = appTextTertiary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 24.dp, glassAlpha = 0.15f)
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                BarcodeCanvas(
                    matrix = barcodeMatrix,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionButton(
                    icon = Icons.Default.SaveAlt,
                    label = "保存",
                    color = FluidTeal,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val bitmap = drawBarcodeToBitmap(barcodeMatrix, 300, 3, 10)
                        val success = saveBitmapToGallery(context, bitmap, "Barcode_${System.currentTimeMillis()}.png")
                        Toast.makeText(context, if (success) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                    }
                )
                ActionButton(
                    icon = Icons.Default.Share,
                    label = "分享",
                    color = FluidPurple,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val bitmap = drawBarcodeToBitmap(barcodeMatrix, 300, 3, 10)
                        shareBitmap(context, bitmap)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

// ─────────────────────────────────────────────────────────────────
// History Tab
// ─────────────────────────────────────────────────────────────────

@Composable
private fun HistoryTab(
    history: List<QrHistoryItem>,
    onRegenerate: (QrHistoryItem) -> Unit,
    onDelete: (QrHistoryItem) -> Unit,
    onClearAll: () -> Unit
) {
    if (history.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
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
                        Icons.Default.History,
                        null,
                        tint = appTextTertiary(),
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("暂无历史记录", fontSize = 15.sp, color = appTextSecondary())
                Text("生成二维码或条形码后将在此显示", fontSize = 11.sp, color = appTextTertiary())
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            // Clear all button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onClearAll) {
                    Text("清空全部", fontSize = 12.sp, color = AccentDanger)
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(history, key = { it.id }) { item ->
                    HistoryItemCard(
                        item = item,
                        onTap = { onRegenerate(item) },
                        onLongPress = { onDelete(item) }
                    )
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun HistoryItemCard(
    item: QrHistoryItem,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    var showDelete by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.10f)
            .clickable { onTap() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type icon
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (item.type == "qr") FluidCyan.copy(alpha = 0.15f)
                        else FluidPurple.copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (item.type == "qr") Icons.Default.QrCode else Icons.Default.ViewList,
                    null,
                    tint = if (item.type == "qr") FluidCyan else FluidPurple,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.content,
                    fontSize = 14.sp,
                    color = appTextPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatQrTimestamp(item.timestamp),
                    fontSize = 11.sp,
                    color = appTextTertiary()
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Delete button
            IconButton(
                onClick = { onLongPress() },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    "删除",
                    tint = AccentDanger.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Reusable components
// ─────────────────────────────────────────────────────────────────

@Composable
private fun QrCodeCanvas(
    matrix: BitMatrix,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val matrixW = matrix.width
        val matrixH = matrix.height
        val moduleSize = minOf(size.width, size.height) / maxOf(matrixW, matrixH).toFloat()
        val totalW = moduleSize * matrixW
        val totalH = moduleSize * matrixH
        val offsetX = (size.width - totalW) / 2f
        val offsetY = (size.height - totalH) / 2f

        // 白色背景（确保浅色主题下二维码也能扫描）
        drawRect(
            color = Color.White,
            topLeft = Offset(offsetX, offsetY),
            size = Size(totalW, totalH)
        )

        // 绘制模块 — ZXing BitMatrix 直接判定
        for (r in 0 until matrixH) {
            for (c in 0 until matrixW) {
                if (matrix[c, r]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(offsetX + c * moduleSize, offsetY + r * moduleSize),
                        size = Size(moduleSize, moduleSize)
                    )
                }
            }
        }
    }
}

@Composable
private fun BarcodeCanvas(
    matrix: BitMatrix,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val matrixW = matrix.width
        val barWidth = size.width / matrixW.toFloat()

        // 白色背景
        drawRect(
            color = Color.White,
            topLeft = Offset.Zero,
            size = Size(size.width, size.height)
        )

        // Code128 一维码：BitMatrix 仅 1 行像素，按列绘制黑条
        for (c in 0 until matrixW) {
            if (matrix[c, 0]) {
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(c * barWidth, 0f),
                    size = Size(barWidth, size.height)
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 400f),
        label = "actionScale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.10f)
            .clickable {
                pressed = true
                onClick()
            }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, fontSize = 13.sp, color = color)
        }
    }

    LaunchedEffect(pressed) {
        if (pressed) {
            kotlinx.coroutines.delay(100)
            pressed = false
        }
    }
}

private fun formatQrTimestamp(millis: Long): String {
    val sdf = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(millis))
}