package com.liquidglass.desktop.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.desktop.system.AnnouncementManager
import com.liquidglass.desktop.ui.tools.ToolType
import com.liquidglass.desktop.theme.LiquidGlassTheme
import kotlinx.coroutines.delay

private val tools: List<ToolType> = ToolType.entries

/**
 * 主界面：工具卡片网格（3 列），每张卡片用 GlassCard 包裹
 *
 * 交互修复（v2.10.0）：
 * - clickable 的 ripple 溢出圆角 → 改用 scale 动画 + 无 indication
 * - hover 时卡片轻微上浮 + 图标放大
 * - 图标背景改为渐变圆，色彩更丰富
 */
@Composable
fun HomeScreen(
    announcementManager: AnnouncementManager,
    onToolClick: (ToolType) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var toast by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部公告栏
            AnnouncementBar(manager = announcementManager, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))

            // 工具卡片网格
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(tools.size) { index ->
                    ToolCard(tool = tools[index]) {
                        onToolClick(tools[index])
                    }
                }
            }
        }

        // 底部 Toast 提示
        toast?.let { msg ->
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = msg,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }

    // 1.5 秒后自动消失
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(1500)
            toast = null
        }
    }
}

@Composable
private fun ToolCard(tool: ToolType, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }

    // hover 上浮 + 按压缩放
    val cardScale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.95f
            hovered -> 1.04f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "cardScale"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (hovered) 1.15f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "iconScale"
    )

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .scale(cardScale)
            .pointerInput(tool) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter -> hovered = true
                            PointerEventType.Exit -> { hovered = false; pressed = false }
                            PointerEventType.Press -> pressed = true
                            PointerEventType.Release -> {
                                if (pressed) {
                                    pressed = false
                                    onClick()
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 图标 + 渐变背景圆
            val iconColor = Color(tool.color)
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .scale(iconScale)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                iconColor.copy(alpha = if (hovered) 0.45f else 0.3f),
                                iconColor.copy(alpha = 0.1f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tool.icon,
                    fontSize = 26.sp,
                    color = Color.White
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = tool.label,
                color = LiquidGlassTheme.onSurfaceColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = tool.desc,
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}
