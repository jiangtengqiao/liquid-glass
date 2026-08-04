package com.liquidglass.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.desktop.system.LanguagePack
import com.liquidglass.desktop.system.LoginManager
import com.liquidglass.desktop.system.MemberTier
import com.liquidglass.desktop.system.TranslateLanguage
import com.liquidglass.desktop.system.TranslationHistory
import com.liquidglass.desktop.system.TranslationManager
import com.liquidglass.desktop.system.TranslationResult
import com.liquidglass.desktop.system.UsageStats
import com.liquidglass.desktop.theme.LiquidGlassTheme
import kotlinx.coroutines.launch

/** 翻译页 Tab */
private enum class TranslateTab(val label: String) {
    Translate("翻译"),
    History("历史"),
    Packs("离线包"),
    Membership("会员")
}

/**
 * 翻译主界面
 *
 * - 顶部 Tab 切换：翻译 / 历史 / 离线包 / 会员
 * - 翻译 Tab：源语言/目标语言选择 + 文本输入 + 译文展示 + 备选译文
 * - 历史 Tab：最近翻译列表 + 收藏切换 + 清空
 * - 离线包 Tab：可下载语言包列表 + 下载/删除 + 进度
 * - 会员 Tab：当前等级 + 用量 + 激活码输入
 */
@Composable
fun TranslationScreen(
    manager: TranslationManager,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(TranslateTab.Translate) }

    Column(modifier = modifier.fillMaxSize()) {
        // 标题
        Text(
            text = "翻译中心",
            color = LiquidGlassTheme.onSurfaceColor,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Tab 栏
        TabRow(
            selectedTabIndex = tab.ordinal,
            backgroundColor = LiquidGlassTheme.surfaceColor.copy(alpha = 0.5f),
            contentColor = LiquidGlassTheme.accentPrimary
        ) {
            TranslateTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = { Text(t.label, color = if (tab == t) LiquidGlassTheme.accentPrimary else LiquidGlassTheme.onSurfaceMuted) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // 内容
        Box(modifier = Modifier.fillMaxSize()) {
            when (tab) {
                TranslateTab.Translate -> TranslateTab(manager, scope)
                TranslateTab.History -> HistoryTab(manager)
                TranslateTab.Packs -> PacksTab(manager, scope)
                TranslateTab.Membership -> MembershipTab(manager, scope)
            }
        }
    }
}

// ---- 翻译 Tab ----

@Composable
private fun TranslateTab(manager: TranslationManager, scope: kotlinx.coroutines.CoroutineScope) {
    var fromLang by remember { mutableStateOf(TranslateLanguage.AUTO) }
    var toLang by remember { mutableStateOf(TranslateLanguage.EN) }
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<TranslationResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(manager.todayUsage()) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        // 语言选择
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("源语言:", color = LiquidGlassTheme.onSurfaceMuted)
                Spacer(Modifier.width(8.dp))
                LanguageDropdown(selected = fromLang) { fromLang = it }
                Spacer(Modifier.width(16.dp))
                // 交换按钮
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(50))
                        .background(LiquidGlassTheme.surfaceVariant)
                        .clickable {
                            if (fromLang != TranslateLanguage.AUTO && toLang != TranslateLanguage.AUTO) {
                                val tmp = fromLang; fromLang = toLang; toLang = tmp
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Text("⇄", color = LiquidGlassTheme.accentSecondary, fontSize = 18.sp) }
                Spacer(Modifier.width(16.dp))
                Text("目标:", color = LiquidGlassTheme.onSurfaceMuted)
                Spacer(Modifier.width(8.dp))
                LanguageDropdown(selected = toLang) { toLang = it }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "今日已用: " +
                    if (usage.unlimited) "${usage.used} 字 · 无限额度"
                    else "${usage.used} / ${usage.quota} 字 · 剩余 ${usage.remaining}" +
                    " · ${manager.currentTier().display}",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(12.dp))

        // 输入框
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text("输入要翻译的内容", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                textStyle = TextStyle(
                    color = LiquidGlassTheme.onSurfaceColor,
                    fontSize = 15.sp
                ),
                cursorBrush = SolidColor(LiquidGlassTheme.accentSecondary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 240.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(LiquidGlassTheme.surfaceColor)
                    .padding(12.dp)
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "字符数: ${input.length}",
                    color = LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically)
                )
                OutlinedButton(onClick = { input = ""; result = null }) { Text("清空") }
                Button(
                    onClick = {
                        if (input.isNotBlank() && !loading) {
                            loading = true
                            scope.launch {
                                result = manager.translate(input, fromLang, toLang)
                                usage = manager.todayUsage()
                                loading = false
                            }
                        }
                    },
                    enabled = input.isNotBlank() && !loading,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = LiquidGlassTheme.accentPrimary,
                        contentColor = Color.White
                    )
                ) { Text(if (loading) "翻译中..." else "翻译") }
            }
        }
        Spacer(Modifier.height(12.dp))

        // 译文展示
        result?.let { r ->
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (r.offline) LiquidGlassTheme.green.copy(alpha = 0.3f)
                                else LiquidGlassTheme.accentSecondary.copy(alpha = 0.3f)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (r.offline) "离线" else "在线",
                            color = if (r.offline) LiquidGlassTheme.green
                            else LiquidGlassTheme.accentSecondary,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    r.detected?.let { d ->
                        Text("检测: $d", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = r.target,
                    color = LiquidGlassTheme.onSurfaceBright,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                if (r.alternatives.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("备选译文", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    r.alternatives.forEach { alt ->
                        Text("· $alt", color = LiquidGlassTheme.onSurfaceColor, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageDropdown(selected: TranslateLanguage, onSelect: (TranslateLanguage) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(LiquidGlassTheme.surfaceVariant)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(selected.display, color = LiquidGlassTheme.onSurfaceColor, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Text("▾", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 10.sp)
        }
        androidx.compose.material.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            TranslateLanguage.entries.forEach { lang ->
                androidx.compose.material.DropdownMenuItem(
                    onClick = { onSelect(lang); expanded = false }
                ) {
                    Text(
                        text = lang.display,
                        color = if (selected == lang) LiquidGlassTheme.accentSecondary
                        else LiquidGlassTheme.onSurfaceColor,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

// ---- 历史 Tab ----

@Composable
private fun HistoryTab(manager: TranslationManager) {
    var list by remember { mutableStateOf(manager.loadHistory()) }
    var showFavOnly by remember { mutableStateOf(false) }
    val displayed = if (showFavOnly) list.filter { it.favorite } else list

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { showFavOnly = !showFavOnly }) {
                Text(if (showFavOnly) "显示全部" else "仅看收藏")
            }
            OutlinedButton(onClick = {
                manager.clearHistory()
                list = manager.loadHistory()
            }) { Text("清空历史") }
        }
        Spacer(Modifier.height(8.dp))

        if (displayed.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无历史记录", color = LiquidGlassTheme.onSurfaceMuted)
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                displayed.forEach { h ->
                    GlassCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = h.source,
                                    color = LiquidGlassTheme.onSurfaceMuted,
                                    fontSize = 13.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = h.target,
                                    color = LiquidGlassTheme.onSurfaceBright,
                                    fontSize = 14.sp,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "${h.from} → ${h.to} · ${formatTime(h.timestamp)}",
                                    color = LiquidGlassTheme.onSurfaceMuted,
                                    fontSize = 10.sp
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (h.favorite) LiquidGlassTheme.announcementMedium.copy(alpha = 0.4f)
                                        else LiquidGlassTheme.surfaceVariant
                                    )
                                    .clickable { list = manager.toggleFavorite(h.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (h.favorite) "★" else "☆",
                                    color = if (h.favorite) LiquidGlassTheme.announcementMedium
                                    else LiquidGlassTheme.onSurfaceMuted
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- 离线包 Tab ----

@Composable
private fun PacksTab(manager: TranslationManager, scope: kotlinx.coroutines.CoroutineScope) {
    var packs by remember { mutableStateOf<List<LanguagePack>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val downloading = remember { androidx.compose.runtime.mutableStateMapOf<String, com.liquidglass.desktop.system.DownloadProgress>() }
    val logExpanded = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val tier = manager.currentTier()

    LaunchedEffect(Unit) {
        loading = true
        packs = manager.availablePacks()
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 说明
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "离线语言包",
                color = LiquidGlassTheme.onSurfaceColor,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "下载后可在无网络环境下翻译单词与短语。多镜像源（jsDelivr / GitHub raw / gh-proxy / ghfast）自动切换，热门包对所有用户免费。",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "当前等级: ${tier.display} · 已安装 ${packs.count { it.installed }} / ${packs.size}",
                color = LiquidGlassTheme.accentSecondary,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(8.dp))

        if (loading) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("加载语言包列表...", color = LiquidGlassTheme.onSurfaceMuted)
            }
        } else {
            packs.forEach { pack ->
                val key = "${pack.fromCode}-${pack.toCode}"
                GlassCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = pack.name,
                                    color = LiquidGlassTheme.onSurfaceColor,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                                Spacer(Modifier.width(8.dp))
                                // 分级标签
                                val (labelText, labelColor) = when {
                                    pack.hot -> "热门免费" to LiquidGlassTheme.green
                                    pack.premium -> "高级" to LiquidGlassTheme.gold
                                    else -> "标准" to LiquidGlassTheme.accentSecondary
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(labelColor.copy(alpha = 0.25f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(labelText, color = labelColor, fontSize = 10.sp)
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            val info = buildString {
                                append("${pack.fromCode.uppercase()} → ${pack.toCode.uppercase()}")
                                if (pack.entryCount > 0) append(" · ${pack.entryCount} 词条")
                                if (pack.sizeBytes > 0) append(" · ${formatSize(pack.sizeBytes)}")
                                if (pack.installed) append(" · 已安装")
                            }
                            Text(info, color = LiquidGlassTheme.onSurfaceMuted, fontSize = 11.sp)

                            // 下载进度
                            downloading[key]?.let { p ->
                                Spacer(Modifier.height(6.dp))
                                // 进度条
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(LiquidGlassTheme.surfaceVariant)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth((p.percent / 100f).coerceIn(0f, 1f))
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(
                                                when {
                                                    p.failed -> LiquidGlassTheme.announcementHigh
                                                    p.finished -> LiquidGlassTheme.green
                                                    else -> LiquidGlassTheme.accentSecondary
                                                }
                                            )
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = buildString {
                                        append("镜像: ${p.mirrorName}（${p.mirrorIndex + 1}/${p.mirrorCount}）")
                                        if (!p.finished && !p.failed) {
                                            append(" · ${p.percent}%")
                                            append(" · ${p.downloadedText}")
                                            if (p.total > 0) append(" / ${p.totalText}")
                                            append(" · ${p.speedText}")
                                        }
                                    },
                                    color = if (p.failed) LiquidGlassTheme.announcementHigh
                                    else if (p.finished) LiquidGlassTheme.green
                                    else LiquidGlassTheme.accentSecondary,
                                    fontSize = 11.sp
                                )
                                Spacer(Modifier.height(4.dp))
                                // 日志展开/隐藏
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { logExpanded[key] = !(logExpanded[key] ?: false) }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (logExpanded[key] == true) "▾ 隐藏日志" else "▸ 下载日志",
                                        color = LiquidGlassTheme.onSurfaceMuted,
                                        fontSize = 10.sp
                                    )
                                }
                                if (logExpanded[key] == true) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = p.log,
                                        color = LiquidGlassTheme.onSurfaceMuted,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        if (pack.installed) {
                            OutlinedButton(onClick = {
                                manager.deletePack(pack.fromCode, pack.toCode)
                                scope.launch { packs = manager.availablePacks() }
                            }) { Text("删除") }
                        } else {
                            val canDownload = tier.canDownloadPack(pack)
                            Button(
                                onClick = {
                                    scope.launch {
                                        val ok = manager.downloadPack(pack) { p ->
                                            downloading[key] = p
                                        }
                                        if (ok) {
                                            packs = manager.availablePacks()
                                            // 保留 finished 状态短暂展示后清除
                                        }
                                    }
                                },
                                enabled = canDownload && !downloading.containsKey(key)
                            ) {
                                Text(
                                    when {
                                        downloading.containsKey(key) -> "下载中"
                                        pack.hot -> "免费下载"
                                        pack.premium && tier != MemberTier.Premium -> "需高级版"
                                        tier == MemberTier.Free -> "需专业版"
                                        else -> "下载"
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- 会员 Tab ----

@Composable
private fun MembershipTab(manager: TranslationManager, scope: kotlinx.coroutines.CoroutineScope) {
    var tier by remember { mutableStateOf(manager.currentTier()) }
    var usage by remember { mutableStateOf(manager.todayUsage()) }
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var generatedPro by remember { mutableStateOf<String?>(null) }
    var generatedPrem by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 平台账号状态
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text("平台账号", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            val user = LoginManager.currentUser()
            if (user != null) {
                Text(
                    text = user.username,
                    color = LiquidGlassTheme.onSurfaceColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "会员等级: ${user.membership.label}" +
                        (if (user.expireAt > 0L)
                            " · 到期 ${formatTime(user.expireAt)}" else " · 永久"),
                    color = LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 11.sp
                )
            } else {
                Text(
                    text = "未登录",
                    color = LiquidGlassTheme.announcementMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "会员等级由平台账号决定，未登录按免费版处理。请前往「账号」页登录。",
                    color = LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 11.sp
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // 当前等级
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text("当前翻译等级", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                text = tier.display,
                color = if (tier == MemberTier.Premium) LiquidGlassTheme.gold
                else LiquidGlassTheme.accentSecondary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            // 用量进度（无限会员不显示进度条）
            if (usage.unlimited) {
                Text(
                    text = "无限额度 · 今日已翻译 ${usage.used} 字",
                    color = LiquidGlassTheme.green,
                    fontSize = 12.sp
                )
            } else {
                val percent = if (usage.quota > 0) usage.used.toFloat() / usage.quota else 0f
                Text("今日用量", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(LiquidGlassTheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(percent.coerceIn(0f, 1f))
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (percent > 0.9f) LiquidGlassTheme.announcementHigh
                                else LiquidGlassTheme.accentSecondary
                            )
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${usage.used} / ${usage.quota} 字 (剩余 ${usage.remaining})",
                    color = LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 11.sp
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // 等级权益对比
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text("等级权益", color = LiquidGlassTheme.onSurfaceColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            MemberTier.entries.forEach { t ->
                val isCurrent = t == tier
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isCurrent) LiquidGlassTheme.accentPrimary.copy(alpha = 0.2f)
                            else Color.Transparent
                        )
                        .padding(vertical = 6.dp, horizontal = 8.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = t.display,
                                color = if (isCurrent) LiquidGlassTheme.accentSecondary
                                else LiquidGlassTheme.onSurfaceColor,
                                fontWeight = FontWeight.Bold
                            )
                            if (isCurrent) {
                                Spacer(Modifier.width(8.dp))
                                Text("当前", color = LiquidGlassTheme.accentSecondary, fontSize = 11.sp)
                            }
                        }
                        Text(
                            text = "每日额度 ${t.dailyQuotaText} · 单次上限 ${t.maxArticleCharsText}" +
                                " · 离线包" + when {
                                    t == MemberTier.Premium -> "（全部）"
                                    t == MemberTier.Pro -> "（标准+热门）"
                                    else -> "（仅热门）"
                                } +
                                (if (t.canTranslateArticle()) " · 文章翻译" else "") +
                                (if (t.canDownloadPremiumPacks()) " · 高级语言包" else ""),
                            color = LiquidGlassTheme.onSurfaceMuted,
                            fontSize = 11.sp
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
        Spacer(Modifier.height(12.dp))

        // 激活码输入（离线演示用；正式会员等级以平台账号为准）
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text("激活码（离线演示）", color = LiquidGlassTheme.onSurfaceColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "正式会员等级由平台账号决定。下方激活码仅供离线演示/内测分发使用，登录平台账号后生效优先级以账号为准。",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                value = code,
                onValueChange = { code = it.uppercase() },
                textStyle = TextStyle(color = LiquidGlassTheme.onSurfaceColor, fontSize = 14.sp),
                cursorBrush = SolidColor(LiquidGlassTheme.accentSecondary),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(LiquidGlassTheme.surfaceColor)
                    .padding(12.dp)
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val ok = manager.activateTier(code, MemberTier.Pro)
                        val ok2 = !ok && manager.activateTier(code, MemberTier.Premium)
                        if (ok || ok2) {
                            tier = manager.currentTier()
                            usage = manager.todayUsage()
                            message = "激活码已保存（本地演示）。当前生效等级以平台账号为准：${tier.display}"
                            code = ""
                        } else {
                            message = "激活码无效（专业版需 LG-PRO- 前缀，高级版需 LG-PREM- 前缀）"
                        }
                    },
                    enabled = code.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = LiquidGlassTheme.accentPrimary,
                        contentColor = Color.White
                    )
                ) { Text("激活") }
                OutlinedButton(onClick = { code = ""; message = null }) { Text("清除") }
            }
            message?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(msg, color = LiquidGlassTheme.accentSecondary, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))

        // 生成演示激活码（内测/分发用）
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text("生成演示激活码", color = LiquidGlassTheme.onSurfaceColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "用于内测分发，正式商用请对接支付系统",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { generatedPro = manager.generateActivationCode(MemberTier.Pro) }) {
                    Text("生成专业版")
                }
                OutlinedButton(onClick = { generatedPrem = manager.generateActivationCode(MemberTier.Premium) }) {
                    Text("生成高级版")
                }
            }
            generatedPro?.let { c ->
                Spacer(Modifier.height(6.dp))
                Text("专业版: $c", color = LiquidGlassTheme.green, fontSize = 12.sp)
            }
            generatedPrem?.let { c ->
                Spacer(Modifier.height(4.dp))
                Text("高级版: $c", color = LiquidGlassTheme.accentSecondary, fontSize = 12.sp)
            }
        }
    }
}

// ---- 辅助 ----

private fun formatTime(ts: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    return String.format(
        "%02d-%02d %02d:%02d",
        c[java.util.Calendar.MONTH] + 1,
        c[java.util.Calendar.DAY_OF_MONTH],
        c[java.util.Calendar.HOUR_OF_DAY],
        c[java.util.Calendar.MINUTE]
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
