package com.liquidglass.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.animateScrollTo
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liquidglass.desktop.system.AnnouncementManager
import com.liquidglass.desktop.system.AnnouncementPriority
import com.liquidglass.desktop.theme.LiquidGlassTheme
import kotlinx.coroutines.delay

/**
 * 顶部公告栏
 *
 * 读取 AnnouncementManager.activeAnnouncements()：
 * - 优先级颜色 high=#FF3B6B medium=#FF8C42 low=#00D4FF
 * - 横向滚动文字（自动来回滚动）
 * - 关闭按钮
 */
@Composable
fun AnnouncementBar(
    manager: AnnouncementManager,
    modifier: Modifier = Modifier
) {
    val announcements by manager.announcements.collectAsState()
    val dismissed by manager.dismissed.collectAsState()

    // 当前显示的第一条未关闭公告
    val visible = announcements.firstOrNull { it.id !in dismissed } ?: return

    val color = when (visible.priority) {
        AnnouncementPriority.High -> LiquidGlassTheme.announcementHigh
        AnnouncementPriority.Medium -> LiquidGlassTheme.announcementMedium
        AnnouncementPriority.Low -> LiquidGlassTheme.announcementLow
    }

    val scrollState = rememberScrollState()

    // 自动来回滚动
    LaunchedEffect(visible.id, scrollState.maxValue) {
        while (true) {
            delay(600)
            if (scrollState.maxValue > 0) {
                scrollState.animateScrollTo(
                    value = scrollState.maxValue,
                    animationSpec = tween(durationMillis = 8000, easing = LinearEasing)
                )
                delay(1200)
                scrollState.animateScrollTo(
                    value = 0,
                    animationSpec = tween(durationMillis = 8000, easing = LinearEasing)
                )
                delay(1200)
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 优先级标签
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(color)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = visible.priority.label,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(12.dp))

        // 横向滚动文字
        Box(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState)
        ) {
            Text(
                text = "    " + visible.content + "    ",
                color = LiquidGlassTheme.onSurfaceColor
            )
        }

        Spacer(Modifier.width(8.dp))

        // 关闭按钮
        Text(
            text = "x",
            color = LiquidGlassTheme.onSurfaceMuted,
            modifier = Modifier
                .clickable { manager.dismiss(visible.id) }
                .padding(4.dp)
        )
    }
}
