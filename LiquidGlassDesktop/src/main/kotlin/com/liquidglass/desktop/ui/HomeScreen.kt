package com.liquidglass.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.liquidglass.desktop.system.AnnouncementManager
import com.liquidglass.desktop.ui.tools.ToolType
import com.liquidglass.desktop.theme.LiquidGlassTheme
import kotlinx.coroutines.delay

private val tools: List<ToolType> = ToolType.entries

/**
 * 主界面：工具卡片网格（3 列），每张卡片用 GlassCard 包裹
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
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 色块图标占位（Desktop 端暂用色块代替图标资源）
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(tool.color))
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = tool.label,
                color = LiquidGlassTheme.onSurfaceColor,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "点击进入",
                color = LiquidGlassTheme.onSurfaceMuted,
                style = MaterialTheme.typography.caption
            )
        }
    }
}
