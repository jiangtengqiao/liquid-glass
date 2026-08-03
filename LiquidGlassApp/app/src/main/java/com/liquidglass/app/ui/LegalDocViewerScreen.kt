package com.liquidglass.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * 单篇协议阅读页
 *
 * - 按"\n"将协议正文切为段落，逐段渲染
 * - 自动识别章节标题（第X章/第X条/附录/《》），用强样式呈现
 * - 支持字号调节、返回顶部、阅读进度
 */
@Composable
fun LegalDocViewerScreen(
    type: LegalType,
    animTime: Float,
    onBack: () -> Unit
) {
    val item = LegalDocuments.allItems.first { it.type == type }
    val content = LegalDocuments.getByType(type)

    // 字号档位：11 / 12 / 13 / 14 / 15 / 16 sp
    var fontLevel by remember { mutableStateOf(2) }
    val baseSize = 11f + fontLevel
    val titleSize = baseSize + 6f
    val headingSize = baseSize + 3f
    val subHeadingSize = baseSize + 1f

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 阅读进度
    val progress by remember {
        derivedStateOf {
            val first = listState.firstVisibleItemIndex
            val total = content.split("\n\n").size
            if (total == 0) 0f
            else (first.toFloat() / total).coerceIn(0f, 1f)
        }
    }

    val paragraphs = remember(content) {
        content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    LiquidGlassScaffold(animTime = animTime) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // 顶栏
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = appTextSecondary())
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = appTextPrimary(),
                        maxLines = 1
                    )
                    Text(
                        "生效：${item.effectiveDate}",
                        fontSize = 9.sp,
                        color = appTextTertiary()
                    )
                }
                // 字号缩小
                IconButton(onClick = { fontLevel = max(0, fontLevel - 1) }) {
                    Icon(
                        Icons.Default.TextDecrease,
                        "缩小字号",
                        tint = if (fontLevel > 0) appTextSecondary() else appTextTertiary().copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                // 字号放大
                IconButton(onClick = { fontLevel = min(5, fontLevel + 1) }) {
                    Icon(
                        Icons.Default.TextIncrease,
                        "放大字号",
                        tint = if (fontLevel < 5) appTextSecondary() else appTextTertiary().copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 进度条
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = item.accentColor,
                trackColor = item.accentColor.copy(alpha = 0.12f),
            )

            // 正文
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 标题区
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            item.accentColor.copy(alpha = 0.25f),
                                            item.accentColor.copy(alpha = 0.08f)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                tint = item.accentColor,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            item.title,
                            fontSize = (titleSize + 6f).sp,
                            fontWeight = FontWeight.Thin,
                            color = appTextPrimary(),
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "生效日期：${item.effectiveDate}",
                            fontSize = 10.sp,
                            color = appTextTertiary()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            item.subtitle,
                            fontSize = (baseSize - 1).sp,
                            color = appTextSecondary()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .height(1.dp)
                                .fillMaxWidth()
                                .background(item.accentColor.copy(alpha = 0.3f))
                        )
                    }
                }

                // 正文段落
                items(paragraphs.size) { idx ->
                    val para = paragraphs[idx]
                    LegalParagraph(
                        text = para,
                        baseSize = baseSize,
                        headingSize = headingSize,
                        subHeadingSize = subHeadingSize,
                        accentColor = item.accentColor
                    )
                }

                // 末尾
                item {
                    Spacer(modifier = Modifier.height(40.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.08f)
                            .padding(14.dp)
                    ) {
                        Column {
                            Text(
                                "— 文档结束 —",
                                fontSize = 11.sp,
                                color = appTextTertiary(),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "本协议具有法律效力。如有疑问请联系：${LegalDocuments.CONTACT_EMAIL}",
                                fontSize = 9.sp,
                                color = appTextTertiary().copy(alpha = 0.7f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(60.dp))
                }
            }
        }

        // 返回顶部悬浮按钮
        AnimatedVisibility(
            visible = listState.firstVisibleItemIndex > 2,
            enter = fadeIn() + scaleIn(initialScale = 0.7f),
            exit = fadeOut() + scaleOut(targetScale = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .glassSurface(cornerRadius = 22.dp, glassAlpha = 0.20f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { scope.launch { listState.animateScrollToItem(0) } },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.VerticalAlignTop,
                    "返回顶部",
                    tint = item.accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 段落渲染：根据文本特征判定是文档标题/章节标题/条目/列表项/正文，分别套用样式。
 */
@Composable
private fun LegalParagraph(
    text: String,
    baseSize: Float,
    headingSize: Float,
    subHeadingSize: Float,
    accentColor: Color
) {
    val isDocTitle = text.startsWith("《") && text.endsWith("》")
    val isChapter = text.matches(Regex("^第[一二三四五六七八九十百零0-9]+[章节部分编].*"))
    val isItem = text.matches(Regex("^第[一二三四五六七八九十百零0-9]+条.*"))
    val isAppendix = text.startsWith("附录") || text.startsWith("第") && text.contains("附录")
    val isListItem = text.startsWith("- ") || text.startsWith("• ")

    when {
        isDocTitle -> {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text,
                fontSize = (headingSize + 2).sp,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        isChapter || isAppendix -> {
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accentColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text,
                    fontSize = headingSize.sp,
                    fontWeight = FontWeight.Bold,
                    color = appTextPrimary()
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
        }
        isItem -> {
            Text(
                text,
                fontSize = subHeadingSize.sp,
                fontWeight = FontWeight.SemiBold,
                color = appTextPrimary().copy(alpha = 0.95f)
            )
        }
        isListItem -> {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp, end = 8.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.7f))
                )
                Text(
                    text.removePrefix("- ").removePrefix("• "),
                    fontSize = baseSize.sp,
                    color = appTextSecondary()
                )
            }
        }
        else -> {
            Text(
                text,
                fontSize = baseSize.sp,
                color = appTextSecondary().copy(alpha = 0.92f),
                lineHeight = (baseSize + 6).sp
            )
        }
    }
}
