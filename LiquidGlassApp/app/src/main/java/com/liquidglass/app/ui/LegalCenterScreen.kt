package com.liquidglass.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

/**
 * 法律与公告中心 — 协议列表页
 *
 * 展示本应用 5 篇法律/公告协议，点击进入 LegalDocViewerScreen 阅读全文。
 */
@Composable
fun LegalCenterScreen(animTime: Float, onBack: () -> Unit) {
    var selectedType by remember { mutableStateOf<LegalType?>(null) }

    // 选中某篇 -> 进入阅读页
    selectedType?.let { type ->
        LegalDocViewerScreen(
            type = type,
            animTime = animTime,
            onBack = { selectedType = null }
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
            // 顶栏
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = appTextSecondary())
                }
                Text(
                    "法律与公告中心",
                    fontSize = 16.sp,
                    color = appTextSecondary(),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            ) {
                // 标题区
                Text(
                    "法律与公告中心",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Thin,
                    color = appTextPrimary().copy(alpha = 0.95f),
                    letterSpacing = 4.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Legal & Announcement Center",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Light,
                    color = appTextTertiary(),
                    letterSpacing = 3.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 生效信息卡
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.10f)
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Verified,
                            null,
                            tint = AccentSuccess,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "全部协议已生效",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = appTextPrimary()
                            )
                            Text(
                                "生效日期：${LegalDocuments.EFFECTIVE_DATE}",
                                fontSize = 10.sp,
                                color = appTextTertiary()
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 协议列表
                LegalDocuments.allItems.forEach { item ->
                    LegalDocCard(item = item, onClick = { selectedType = item.type })
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 联系信息
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.08f)
                        .padding(14.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.ContactMail,
                                null,
                                tint = FluidCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "联系我们",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = appTextPrimary()
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "开发者：${LegalDocuments.DEVELOPER}",
                            fontSize = 11.sp,
                            color = appTextSecondary()
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "邮箱：${LegalDocuments.CONTACT_EMAIL}",
                            fontSize = 11.sp,
                            color = appTextSecondary()
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "项目主页：jiangtengqiao.github.io/liquid-glass",
                            fontSize = 11.sp,
                            color = appTextTertiary()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "本中心所有协议均具有法律效力。\n继续使用本应用即视为您已阅读并同意全部协议。",
                    fontSize = 10.sp,
                    color = appTextTertiary(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun LegalDocCard(
    item: LegalItem,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "legalCardScale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.14f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                pressed = true
                onClick()
            }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            item.accentColor.copy(alpha = 0.20f),
                            item.accentColor.copy(alpha = 0.08f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.title,
                tint = item.accentColor.copy(alpha = 0.95f),
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = appTextPrimary()
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                item.subtitle,
                fontSize = 10.sp,
                color = appTextTertiary(),
                maxLines = 2
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "生效：${item.effectiveDate}",
                fontSize = 9.sp,
                color = appTextTertiary().copy(alpha = 0.7f)
            )
        }

        Icon(
            Icons.Default.ChevronRight,
            null,
            tint = appTextTertiary(),
            modifier = Modifier.size(20.dp)
        )
    }
}
