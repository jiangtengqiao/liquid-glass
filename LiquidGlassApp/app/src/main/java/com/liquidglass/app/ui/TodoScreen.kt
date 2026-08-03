package com.liquidglass.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.delay

data class TodoItem(val id: Long = System.currentTimeMillis(), val text: String, val isCompleted: Boolean = false)

@Composable
fun TodoScreen(animTime: Float, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 进入页面从本地恢复，避免退出即丢
    var todos by remember { mutableStateOf(TodoStore.load(context)) }
    var newText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    // 任何变更都落盘
    fun persist(list: List<TodoItem>) { todos = list; TodoStore.save(context, list) }
    fun add() {
        val t = newText.trim()
        if (t.isNotEmpty()) { persist(todos + TodoItem(text = t)); newText = ""; focusManager.clearFocus() }
    }
    fun toggle(id: Long) { persist(todos.map { if (it.id == id) it.copy(isCompleted = !it.isCompleted) else it }) }
    fun delete(id: Long) { persist(todos.filter { it.id != id }) }

    LiquidGlassScaffold(animTime = animTime) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回", tint = appTextSecondary()) }
                Text("待办清单", fontSize = 16.sp, color = appTextSecondary(), modifier = Modifier.weight(1f))
                Text("${todos.count { it.isCompleted }}/${todos.size}", fontSize = 12.sp, color = appTextTertiary())
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 输入框
            Row(
                modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 18.dp, glassAlpha = 0.15f)
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = newText, onValueChange = { newText = it },
                    placeholder = { Text("添加新待办...", color = appTextTertiary()) },
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        cursorColor = AccentPrimary, focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    singleLine = true
                )
                IconButton(onClick = { add() }) {
                    Icon(Icons.Default.AddCircle, "添加", tint = AccentPrimary, modifier = Modifier.size(26.dp))
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (todos.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CheckCircle, null, tint = appTextTertiary(), modifier = Modifier.size(56.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("暂无待办事项", fontSize = 14.sp, color = appTextTertiary())
                        Text("上方输入框添加新任务", fontSize = 11.sp, color = appTextTertiary().copy(alpha = 0.5f))
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    items(todos, key = { it.id }) { todo ->
                        TodoRow(todo = todo, onToggle = { toggle(todo.id) }, onDelete = { delete(todo.id) })
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
fun TodoRow(todo: TodoItem, onToggle: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 16.dp, glassAlpha = 0.12f)
            .clickable { onToggle() }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(22.dp).clip(CircleShape)
                .then(if (todo.isCompleted) Modifier.background(Brush.linearGradient(listOf(FluidCyan, FluidTeal)))
                else Modifier.background(GlassLight)),
            contentAlignment = Alignment.Center
        ) {
            if (todo.isCompleted) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            todo.text, fontSize = 14.sp,
            color = if (todo.isCompleted) TextTertiary else TextPrimary,
            textDecoration = if (todo.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )

        IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Default.Delete, "删除", tint = AccentDanger.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
        }
    }
}