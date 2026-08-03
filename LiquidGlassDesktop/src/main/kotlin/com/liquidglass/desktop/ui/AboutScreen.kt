package com.liquidglass.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liquidglass.desktop.theme.LiquidGlassTheme

// ---- 更新日志数据模型（三级折叠：超级大版 -> 大版本 -> 小版本） ----

/** 超级大版本 */
private data class MegaVersion(val title: String, val majors: List<MajorVersion>)

/** 大版本 */
private data class MajorVersion(val title: String, val minors: List<MinorVersion>)

/** 小版本 */
private data class MinorVersion(val title: String, val items: List<String>)

/**
 * 关于页
 *
 * - 版本信息 v2.9.1 Desktop
 * - 更新日志：三级折叠（超级大版 -> 大版本 -> 小版本）
 *   1.x 与 2.x 各为一个超级大版
 * - v2.9.1 条目：Desktop 端首发、液态玻璃 KMPLiquidGlass、公告栏、日志上传、Beta 先锋码系统、emoji 全清除、主题精简
 */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val changelog = remember { buildChangelog() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        // 版本信息卡片
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "灵工坊 LiquidGlass",
                color = LiquidGlassTheme.onSurfaceColor,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.h5
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Desktop 版本 v2.9.1",
                color = LiquidGlassTheme.accentSecondary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "液态玻璃智能工具箱 - 跨平台桌面端",
                color = LiquidGlassTheme.onSurfaceMuted
            )
        }
        Spacer(Modifier.height(16.dp))

        Text(
            text = "更新日志",
            color = LiquidGlassTheme.onSurfaceColor,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.h6
        )
        Spacer(Modifier.height(8.dp))

        changelog.forEach { mega ->
            MegaVersionItem(mega)
        }
    }
}

/** 超级大版本折叠项 */
@Composable
private fun MegaVersionItem(mega: MegaVersion) {
    var expanded by remember { mutableStateOf(true) }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (expanded) "v " else "> ",
                color = LiquidGlassTheme.accentPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = mega.title,
                color = LiquidGlassTheme.onSurfaceColor,
                fontWeight = FontWeight.Bold
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(start = 16.dp)) {
                mega.majors.forEach { major -> MajorVersionItem(major) }
            }
        }
    }
}

/** 大版本折叠项 */
@Composable
private fun MajorVersionItem(major: MajorVersion) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (expanded) "v " else "> ",
                color = LiquidGlassTheme.accentSecondary
            )
            Text(
                text = major.title,
                color = LiquidGlassTheme.onSurfaceColor
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(start = 16.dp)) {
                major.minors.forEach { minor -> MinorVersionItem(minor) }
            }
        }
    }
}

/** 小版本折叠项 */
@Composable
private fun MinorVersionItem(minor: MinorVersion) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (expanded) "v " else "> ",
                color = LiquidGlassTheme.onSurfaceMuted
            )
            Text(
                text = minor.title,
                color = LiquidGlassTheme.onSurfaceMuted
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(start = 16.dp)) {
                minor.items.forEach { item ->
                    Text(
                        text = "- $item",
                        color = LiquidGlassTheme.onSurfaceMuted,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/** 构建更新日志：1.x 与 2.x 各为一个超级大版 */
private fun buildChangelog(): List<MegaVersion> = listOf(
    MegaVersion(
        title = "2.x 超级大版 - 液态玻璃纪元",
        majors = listOf(
            MajorVersion(
                title = "v2.9 大版本",
                minors = listOf(
                    MinorVersion(
                        title = "v2.9.1",
                        items = listOf(
                            "Desktop 端首发发布",
                            "集成液态玻璃 KMPLiquidGlass 库",
                            "新增顶部公告栏系统",
                            "新增日志上传系统（CrashHandler）",
                            "新增 Beta 先锋码系统",
                            "全应用 emoji 清除",
                            "主题精简为午夜深空"
                        )
                    )
                )
            )
        )
    ),
    MegaVersion(
        title = "1.x 超级大版 - 初创纪元",
        majors = listOf(
            MajorVersion(
                title = "v1.0 大版本",
                minors = listOf(
                    MinorVersion(
                        title = "v1.0.0",
                        items = listOf(
                            "Android 端首发",
                            "液态玻璃效果初版",
                            "工具卡片首页",
                            "公告系统初版"
                        )
                    )
                )
            )
        )
    )
)
