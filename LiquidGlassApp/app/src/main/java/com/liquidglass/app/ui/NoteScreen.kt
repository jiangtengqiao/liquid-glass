package com.liquidglass.app.ui

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

data class Note(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val colorIndex: Int = 0
)

private val NoteColors = listOf(
    FluidCyan,
    FluidPurple,
    FluidPink,
    FluidBlue,
    FluidTeal,
    FluidOrange
)

private const val PREFS_NAME = "liquid_glass_notes"
private const val NOTES_KEY = "notes"

private fun saveNotes(context: Context, notes: List<Note>) {
    val jsonArray = JSONArray()
    for (note in notes) {
        val obj = JSONObject().apply {
            put("id", note.id)
            put("title", note.title)
            put("content", note.content)
            put("createdAt", note.createdAt)
            put("updatedAt", note.updatedAt)
            put("colorIndex", note.colorIndex)
        }
        jsonArray.put(obj)
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(NOTES_KEY, jsonArray.toString())
        .apply()
}

private fun loadNotes(context: Context): List<Note> {
    val jsonStr = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(NOTES_KEY, null) ?: return emptyList()
    val notes = mutableListOf<Note>()
    try {
        val jsonArray = JSONArray(jsonStr)
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            notes.add(
                Note(
                    id = obj.getString("id"),
                    title = obj.optString("title", ""),
                    content = obj.optString("content", ""),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                    colorIndex = obj.optInt("colorIndex", 0)
                )
            )
        }
    } catch (_: Exception) { }
    return notes
}

private fun formatTimestamp(millis: Long): String {
    val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}

private fun formatDateFull(millis: Long): String {
    val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}

@Composable
fun NoteScreen(animTime: Float, onBack: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var notes by remember { mutableStateOf(loadNotes(context)) }
    var searchQuery by remember { mutableStateOf("") }
    var sortNewestFirst by remember { mutableStateOf(true) }
    var editingNoteId by remember { mutableStateOf<String?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<Note?>(null) }
    val scope = rememberCoroutineScope()

    val filteredNotes = remember(notes, searchQuery, sortNewestFirst) {
        val filtered = if (searchQuery.isBlank()) notes
        else notes.filter { it.title.contains(searchQuery, ignoreCase = true) }
        if (sortNewestFirst) filtered.sortedByDescending { it.updatedAt }
        else filtered.sortedBy { it.updatedAt }
    }

    fun persist(newNotes: List<Note>) {
        notes = newNotes
        saveNotes(context, newNotes)
    }

    if (isCreating || editingNoteId != null) {
        val targetNote = notes.find { it.id == editingNoteId }
        NoteEditor(
            animTime = animTime,
            existingNote = targetNote,
            onSave = { title, content ->
                if (editingNoteId != null) {
                    val idx = notes.indexOfFirst { it.id == editingNoteId }
                    if (idx >= 0) {
                        val updated = notes[idx].copy(
                            title = title,
                            content = content,
                            updatedAt = System.currentTimeMillis()
                        )
                        persist(notes.toMutableList().also { it[idx] = updated })
                    }
                    editingNoteId = null
                } else {
                    val newNote = Note(
                        title = title,
                        content = content,
                        colorIndex = (notes.size % NoteColors.size)
                    )
                    persist(notes + newNote)
                    isCreating = false
                }
                focusManager.clearFocus()
            },
            onCancel = {
                editingNoteId = null
                isCreating = false
                focusManager.clearFocus()
            }
        )
        return
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
                    "便签笔记",
                    fontSize = 16.sp,
                    color = appTextSecondary(),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${notes.size} 篇",
                    fontSize = 12.sp,
                    color = appTextTertiary()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Search bar
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
                    placeholder = { Text("搜索笔记标题...", color = appTextTertiary()) },
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
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
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

            Spacer(modifier = Modifier.height(10.dp))

            // Sort toggle + new note button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    onClick = { sortNewestFirst = !sortNewestFirst },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Icon(
                        if (sortNewestFirst) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                        null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (sortNewestFirst) "最新优先" else "最早优先",
                        fontSize = 12.sp
                    )
                }

                FilledTonalButton(
                    onClick = { isCreating = true },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = AccentPrimary.copy(alpha = 0.15f),
                        contentColor = AccentPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("新建笔记", fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (filteredNotes.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Decorative illustration
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .glassSurface(cornerRadius = 28.dp, glassAlpha = 0.10f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "✧",
                                    fontSize = 28.sp,
                                    color = appTextSecondary()
                                )
                                Text(
                                    "◈",
                                    fontSize = 14.sp,
                                    color = appTextTertiary()
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        if (searchQuery.isNotBlank()) {
                            Text(
                                "未找到匹配的笔记",
                                fontSize = 15.sp,
                                color = appTextSecondary()
                            )
                            Text(
                                "尝试其他关键词",
                                fontSize = 11.sp,
                                color = appTextTertiary()
                            )
                        } else {
                            Text(
                                "还没有笔记",
                                fontSize = 15.sp,
                                color = appTextSecondary()
                            )
                            Text(
                                "点击「新建笔记」记录灵感",
                                fontSize = 11.sp,
                                color = appTextTertiary()
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredNotes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            animTime = animTime,
                            onClick = { editingNoteId = note.id },
                            onLongPress = { showDeleteConfirm = note }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
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
            icon = {
                Icon(Icons.Default.Delete, null, tint = AccentDanger, modifier = Modifier.size(32.dp))
            },
            title = { Text("删除笔记") },
            text = {
                Text("确定要删除「${showDeleteConfirm!!.title.ifEmpty { "无标题" }}」吗？此操作不可撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        persist(notes.filter { it.id != showDeleteConfirm!!.id })
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentDanger)
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun NoteCard(
    note: Note,
    animTime: Float,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val accentColor = NoteColors[note.colorIndex % NoteColors.size]
    var offsetX by remember { mutableStateOf(0f) }
    val dismissThreshold = 200f
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(offsetX.roundToInt(), 0) }
            .pointerInput(note.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (offsetX < -dismissThreshold) {
                            scope.launch {
                                offsetX = -1000f
                                delay(200)
                                onLongPress()
                            }
                        } else {
                            scope.launch {
                                val animatable = Animatable(offsetX)
                                animatable.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 300f))
                                offsetX = animatable.value
                            }
                        }
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        offsetX = (offsetX + dragAmount).coerceIn(-1000f, 0f)
                    }
                )
            }
    ) {
        // Delete background indicator
        if (offsetX < -20f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(18.dp))
                    .background(AccentDanger.copy(alpha = (-offsetX / 500f).coerceIn(0f, 0.5f))),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Default.Delete,
                    "删除",
                    tint = Color.White.copy(alpha = (-offsetX / 200f).coerceIn(0.2f, 1f)),
                    modifier = Modifier.padding(end = 24.dp).size(22.dp)
                )
            }
        }

        // Card content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(cornerRadius = 18.dp, glassAlpha = 0.12f)
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Color accent dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.7f))
                )
                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = note.title.ifEmpty { "无标题" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = appTextPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = formatTimestamp(note.updatedAt),
                    fontSize = 11.sp,
                    color = appTextTertiary()
                )
            }

            if (note.content.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))

                // Render markdown-like content preview
                Text(
                    text = renderMarkdownPreview(note.content),
                    fontSize = 13.sp,
                    color = appTextSecondary(),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun NoteEditor(
    animTime: Float,
    existingNote: Note?,
    onSave: (title: String, content: String) -> Unit,
    onCancel: () -> Unit
) {
    var title by remember(existingNote?.id) { mutableStateOf(existingNote?.title ?: "") }
    var content by remember(existingNote?.id) { mutableStateOf(existingNote?.content ?: "") }
    val focusManager = LocalFocusManager.current
    val isEditing = existingNote != null

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
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, "取消", tint = appTextSecondary())
                }
                Text(
                    if (isEditing) "编辑笔记" else "新建笔记",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = appTextSecondary(),
                    modifier = Modifier.weight(1f)
                )
                // Character count
                Text(
                    "${content.length}",
                    fontSize = 12.sp,
                    color = if (content.length > 2000) AccentWarning else TextTertiary
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = {
                        onSave(title.trim(), content.trim())
                        focusManager.clearFocus()
                    },
                    enabled = title.isNotBlank() || content.isNotBlank(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = AccentPrimary.copy(alpha = 0.15f),
                        contentColor = AccentPrimary,
                        disabledContainerColor = GlassLight,
                        disabledContentColor = TextTertiary
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("保存", fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Title field
            TextField(
                value = title,
                onValueChange = { if (it.length <= 100) title = it },
                placeholder = { Text("标题", color = appTextTertiary()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 18.dp, glassAlpha = 0.12f),
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
                textStyle = LocalTextStyle.current.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Content field
            TextField(
                value = content,
                onValueChange = { if (it.length <= 5000) content = it },
                placeholder = { Text("在此输入笔记内容...\n支持 **加粗** 和 *斜体* 格式", color = appTextTertiary()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .glassSurface(cornerRadius = 18.dp, glassAlpha = 0.12f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AccentPrimary,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 22.sp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Formatting help bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.08f)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.FormatBold,
                    null,
                    tint = appTextTertiary(),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("**文字**", fontSize = 11.sp, color = appTextTertiary())
                Spacer(modifier = Modifier.width(14.dp))
                Icon(
                    Icons.Default.FormatItalic,
                    null,
                    tint = appTextTertiary(),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("*文字*", fontSize = 11.sp, color = appTextTertiary())
                Spacer(modifier = Modifier.weight(1f))
                if (isEditing && existingNote != null) {
                    Text(
                        "创建于 ${formatDateFull(existingNote.createdAt)}",
                        fontSize = 10.sp,
                        color = appTextTertiary()
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun renderMarkdownPreview(text: String) = buildAnnotatedString {
    val pattern = Regex("""(\*\*|__)(.*?)\1|(\*|_)(.*?)\3""")
    var lastIndex = 0
    for (match in pattern.findAll(text)) {
        val start = match.range.first
        if (start > lastIndex) {
            append(text.substring(lastIndex, start))
        }
        if (match.groupValues[1].isNotEmpty()) {
            // Bold: **text** or __text__
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(match.groupValues[2])
            }
        } else {
            // Italic: *text* or _text_
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(match.groupValues[4])
            }
        }
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        append(text.substring(lastIndex))
    }
}

/**
 * Simple spring animation utility for swipe-to-delete
 */
private object AnimationUtils {
    @Composable
    fun animateFloatAsState(
        targetValue: Float,
        animationSpec: AnimationSpec<Float>
    ): State<Float> {
        val animatable = remember { Animatable(targetValue) }
        LaunchedEffect(targetValue) {
            animatable.animateTo(targetValue, animationSpec)
        }
        return animatable.asState()
    }
}