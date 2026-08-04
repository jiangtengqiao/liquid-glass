package com.liquidglass.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.desktop.system.LoginManager
import com.liquidglass.desktop.system.MemberTier
import com.liquidglass.desktop.theme.LiquidGlassTheme

/**
 * 会员权益对比页
 *
 * 展示 FREE / PRO / PREMIUM 三级会员的权益对比表格：
 * - 翻译额度（每日字数、单次字数）
 * - 工具使用权限
 * - 离线包下载权限
 * - 广告
 * - 优先客服
 * - Beta 优先体验
 * - 价格（月/年）
 *
 * 当前生效等级会高亮显示。会员等级来源：[LoginManager] 平台账号。
 */
@Composable
fun MemberCompareScreen() {
    // 当前生效等级（与 TranslationManager.currentTier 保持一致的判定逻辑）
    val user = LoginManager.currentUser()
    val valid = user != null && (user.expireAt == 0L || user.expireAt > System.currentTimeMillis())
    val currentTier = when {
        valid && user!!.membership == LoginManager.Membership.PREMIUM -> MemberTier.Premium
        valid && user.membership == LoginManager.Membership.PRO -> MemberTier.Pro
        else -> MemberTier.Free
    }

    // 三级会员权益数据
    data class TierInfo(
        val tier: MemberTier,
        val priceMonth: String,
        val priceYear: String,
        val dailyQuota: String,
        val singleQuota: String,
        val tools: String,
        val offlinePack: String,
        val ads: String,
        val prioritySupport: String,
        val betaAccess: String,
        val headerColor: Color
    )
    val tiers = listOf(
        TierInfo(
            tier = MemberTier.Free,
            priceMonth = "免费",
            priceYear = "免费",
            dailyQuota = "5,000 字/日",
            singleQuota = "2,000 字/次",
            tools = "基础工具",
            offlinePack = "热门包",
            ads = "有广告",
            prioritySupport = "普通客服",
            betaAccess = "—",
            headerColor = LiquidGlassTheme.onSurfaceMuted
        ),
        TierInfo(
            tier = MemberTier.Pro,
            priceMonth = "¥29/月",
            priceYear = "¥288/年",
            dailyQuota = "50,000 字/日",
            singleQuota = "10,000 字/次",
            tools = "全部工具",
            offlinePack = "标准 + 热门包",
            ads = "无广告",
            prioritySupport = "优先客服",
            betaAccess = "—",
            headerColor = LiquidGlassTheme.accentSecondary
        ),
        TierInfo(
            tier = MemberTier.Premium,
            priceMonth = "¥59/月",
            priceYear = "¥588/年",
            dailyQuota = "无限",
            singleQuota = "无限",
            tools = "全部工具",
            offlinePack = "全部语言包",
            ads = "无广告",
            prioritySupport = "VIP 优先客服",
            betaAccess = "优先体验",
            headerColor = LiquidGlassTheme.gold
        )
    )

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        // 标题
        Text(
            text = "会员权益对比",
            color = LiquidGlassTheme.onSurfaceColor,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "升级会员解锁更高翻译额度、离线语言包与优先服务。会员等级由平台账号决定。",
            color = LiquidGlassTheme.onSurfaceMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            // 表头：权益项 | FREE | PRO | PREMIUM
            BenefitRow(
                label = "权益",
                values = tiers.map { it.tier.display },
                valueColors = tiers.map { it.headerColor },
                bold = true,
                isHeader = true,
                highlightIndex = tiers.indexOfFirst { it.tier == currentTier }
            )
            Spacer(Modifier.height(4.dp))
            // 价格（月）
            BenefitRow(
                label = "月费",
                values = tiers.map { it.priceMonth },
                highlightIndex = tiers.indexOfFirst { it.tier == currentTier }
            )
            BenefitRow(
                label = "年费",
                values = tiers.map { it.priceYear },
                highlightIndex = tiers.indexOfFirst { it.tier == currentTier }
            )
            Spacer(Modifier.height(8.dp))

            // 翻译额度
            SectionHeader("翻译额度")
            BenefitRow(
                label = "每日字数",
                values = tiers.map { it.dailyQuota },
                highlightIndex = tiers.indexOfFirst { it.tier == currentTier }
            )
            BenefitRow(
                label = "单次提交",
                values = tiers.map { it.singleQuota },
                highlightIndex = tiers.indexOfFirst { it.tier == currentTier }
            )
            Spacer(Modifier.height(8.dp))

            // 功能权限
            SectionHeader("功能权限")
            BenefitRow(
                label = "工具使用",
                values = tiers.map { it.tools },
                highlightIndex = tiers.indexOfFirst { it.tier == currentTier }
            )
            BenefitRow(
                label = "离线包下载",
                values = tiers.map { it.offlinePack },
                highlightIndex = tiers.indexOfFirst { it.tier == currentTier }
            )
            BenefitRow(
                label = "广告",
                values = tiers.map { it.ads },
                valueColors = tiers.map {
                    if (it.ads.startsWith("无")) LiquidGlassTheme.green else LiquidGlassTheme.announcementMedium
                },
                highlightIndex = tiers.indexOfFirst { it.tier == currentTier }
            )
            Spacer(Modifier.height(8.dp))

            // 服务
            SectionHeader("服务")
            BenefitRow(
                label = "优先客服",
                values = tiers.map { it.prioritySupport },
                highlightIndex = tiers.indexOfFirst { it.tier == currentTier }
            )
            BenefitRow(
                label = "Beta 优先体验",
                values = tiers.map { it.betaAccess },
                valueColors = tiers.map {
                    if (it.betaAccess == "优先体验") LiquidGlassTheme.gold
                    else LiquidGlassTheme.onSurfaceMuted
                },
                highlightIndex = tiers.indexOfFirst { it.tier == currentTier }
            )
        }
        Spacer(Modifier.height(12.dp))

        // 当前等级提示
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "你当前的等级",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = currentTier.display,
                color = when (currentTier) {
                    MemberTier.Premium -> LiquidGlassTheme.gold
                    MemberTier.Pro -> LiquidGlassTheme.accentSecondary
                    MemberTier.Free -> LiquidGlassTheme.onSurfaceColor
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (user != null)
                    "已登录平台账号：${user.username}（${user.membership.label}）"
                else "未登录平台账号，按免费版处理。请前往「账号」页登录或升级。",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 11.sp
            )
        }
    }
}

/** 分区小标题 */
@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(
        text = text,
        color = LiquidGlassTheme.accentPrimary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

/**
 * 单行权益对比：左侧标签 + 三列数值
 *
 * @param label 权益项名称
 * @param values 三级会员对应的数值（顺序：Free / Pro / Premium）
 * @param valueColors 各列文字颜色（可选，默认主文字色）
 * @param bold 是否加粗
 * @param isHeader 是否为表头行
 * @param highlightIndex 当前等级列序号（-1 表示无高亮）
 */
@Composable
private fun BenefitRow(
    label: String,
    values: List<String>,
    valueColors: List<Color> = List(values.size) { LiquidGlassTheme.onSurfaceColor },
    bold: Boolean = false,
    isHeader: Boolean = false,
    highlightIndex: Int = -1
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧标签列
        Box(
            modifier = Modifier.weight(1.1f),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = label,
                color = if (isHeader) LiquidGlassTheme.onSurfaceColor else LiquidGlassTheme.onSurfaceMuted,
                fontSize = if (isHeader) 13.sp else 12.sp,
                fontWeight = if (bold || isHeader) FontWeight.Bold else FontWeight.Normal
            )
        }
        // 三列数值
        values.forEachIndexed { i, v ->
            val isCurrent = i == highlightIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isCurrent) LiquidGlassTheme.accentPrimary.copy(alpha = 0.18f)
                        else Color.Transparent
                    )
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = v,
                    color = valueColors.getOrElse(i) { LiquidGlassTheme.onSurfaceColor },
                    fontSize = if (isHeader) 13.sp else 11.sp,
                    fontWeight = if (isHeader || isCurrent) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
