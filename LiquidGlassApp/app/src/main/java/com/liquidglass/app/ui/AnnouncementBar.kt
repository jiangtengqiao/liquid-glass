package com.liquidglass.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.app.AnnouncementManager
import com.liquidglass.app.AppRouter
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.delay

/**
 * 公告栏组件：显示在屏幕顶部，展示当前优先级最高的一条公告。
 *
 * 读取 [AnnouncementManager.activeAnnouncements]，按优先级（high > medium > low）取最高一条。
 * 布局：左侧优先级色点 + 横向自动滚动正文 + 可选 actionLabel 跳转按钮 + 可选关闭按钮。
 * 容器使用 [glassSurface] 液态玻璃效果，色点根据优先级着色：
 * high=红、medium=橙、low=青。
 *
 * 无有效公告时本组件不渲染（返回空）。
 */
@Composable
fun AnnouncementBar(modifier: Modifier = Modifier) {
    // 读取当前未关闭且在有效期内的公告，取优先级最高的一条
    val active = AnnouncementManager.activeAnnouncements()
    val top = remember(active) { active.minByOrNull { priorityRank(it.priority) } }

    if (top == null) return

    val accentColor = priorityColor(top.priority)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 18.dp, glassAlpha = 0.18f)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 优先级色点
        Spacer(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accentColor)
        )
        Spacer(Modifier.width(8.dp))

        // 横向自动滚动正文（v2.11.2 提速：等待600ms→300ms，滚动速度x*12→x*8更快）
        val scrollState = rememberScrollState()
        LaunchedEffect(top.id) {
            while (true) {
                delay(600)
                val max = scrollState.maxValue
                if (max > 0) {
                    scrollState.animateScrollTo(
                        value = max,
                        animationSpec = tween(
                            durationMillis = (max * 8).coerceAtLeast(500),
                            easing = LinearEasing
                        )
                    )
                    delay(400)
                    scrollState.animateScrollTo(
                        value = 0,
                        animationSpec = tween(durationMillis = 400, easing = LinearEasing
                        )
                    )
                    delay(400)
                } else {
                    delay(2000)
                }
            }
        }

        val display = if (top.title.isNotEmpty() && top.content.isNotEmpty()) {
            "${top.title}    ${top.content}"
        } else {
            top.title.ifEmpty { top.content }
        }
        Text(
            text = display,
            color = appTextPrimary(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState)
        )

        // actionLabel 跳转按钮（同时需要 actionTarget 才生效）
        if (!top.actionLabel.isNullOrEmpty() && !top.actionTarget.isNullOrEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = top.actionLabel,
                color = accentColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.16f))
                    .clickable {
                        // 通过 AppRouter 路由跳转；目标名统一大写以匹配 Screen 枚举
                        AppRouter.navigate(top.actionTarget.uppercase())
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        // 关闭按钮（仅 dismissible=true 时显示）
        if (top.dismissible) {
            IconButton(
                onClick = { AnnouncementManager.dismiss(top.id) },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭公告",
                    tint = appTextSecondary(),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** 优先级排序权重：数字越小优先级越高 */
private fun priorityRank(priority: String): Int = when (priority.lowercase()) {
    "high" -> 0
    "medium" -> 1
    else -> 2
}

/** 优先级对应颜色：high=红、medium=橙、low=青 */
private fun priorityColor(priority: String): Color = when (priority.lowercase()) {
    "high" -> Color(0xFFFF3B6B)
    "medium" -> Color(0xFFFF8C42)
    else -> Color(0xFF00D4FF)
}
