package com.liquidglass.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.desktop.system.LoginManager
import com.liquidglass.desktop.system.PaymentManager
import com.liquidglass.desktop.theme.LiquidGlassTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 支付宝收款码付款页
 *
 * 由于开发者未接入支付宝商户认证，无法调用正式支付 API，
 * 这里采用「收款码扫码 + 用户回填交易号」的简化信任流程：
 *  1. 选择套餐（PRO ¥29/年 / PREMIUM ¥99/年）
 *  2. 用支付宝扫描页面展示的收款二维码完成付款
 *  3. 将支付宝交易号填入输入框
 *  4. 点击「我已付款，激活会员」，校验通过后立即激活
 *
 * 签名：[PaymentScreen]（无参，由主路由接入）
 */
@Composable
fun PaymentScreen() {
    // 加载支付宝收款二维码（resources/alipay.png）；缺失时为 null 走占位
    val qrBitmap: ImageBitmap? = remember {
        runCatching {
            Thread.currentThread().contextClassLoader
                ?.getResourceAsStream("alipay.png")
                ?.use { loadImageBitmap(it) }
        }.getOrNull()
    }

    // 当前登录态与会员信息（激活后会刷新）
    var currentUser by remember { mutableStateOf(LoginManager.currentUser()) }
    var selectedTier by remember { mutableStateOf(LoginManager.Membership.PRO) }
    var txId by remember { mutableStateOf("") }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf<Boolean?>(null) }
    val df = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        // 标题
        Text(
            text = "升级会员",
            color = LiquidGlassTheme.onSurfaceColor,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "扫码付款后回填支付宝交易号即可激活会员，即刻解锁全部权益",
            color = LiquidGlassTheme.onSurfaceMuted,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))

        // 当前会员状态
        currentUser?.let { u ->
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when (u.membership) {
                            LoginManager.Membership.PREMIUM -> "👑"
                            LoginManager.Membership.PRO -> "★"
                            LoginManager.Membership.FREE -> "○"
                        },
                        fontSize = 18.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "当前账号：${u.username}",
                            color = LiquidGlassTheme.onSurfaceColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (u.expireAt > 0)
                                "会员：${u.membership.label}，到期 ${df.format(Date(u.expireAt))}"
                            else "会员：${u.membership.label}",
                            color = LiquidGlassTheme.onSurfaceMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 主体：左侧二维码 + 右侧套餐选择
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- 左：支付宝收款码 ----
            GlassCard(modifier = Modifier.weight(1f)) {
                Text(
                    text = "第一步 扫码付款",
                    color = LiquidGlassTheme.accentSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "用支付宝扫描下方收款码，按所选套餐金额付款",
                    color = LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = "支付宝收款码",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .padding(6.dp)
                        )
                    } else {
                        // 资源缺失时的占位框
                        Box(
                            modifier = Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(LiquidGlassTheme.surfaceVariant)
                                .border(1.dp, LiquidGlassTheme.glassBorder, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "请将 alipay.png\n放入安装目录",
                                color = LiquidGlassTheme.onSurfaceMuted,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "付款金额请与所选套餐一致：PRO ¥${PaymentManager.PRO_PRICE_YEAR} / PREMIUM ¥${PaymentManager.PREMIUM_PRICE_YEAR}",
                    color = LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---- 右：套餐选择 ----
            GlassCard(modifier = Modifier.weight(1f)) {
                Text(
                    text = "第二步 选择套餐",
                    color = LiquidGlassTheme.accentSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "选择要升级的会员等级",
                    color = LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))

                PlanCard(
                    tier = LoginManager.Membership.PRO,
                    price = "¥${PaymentManager.PRO_PRICE_YEAR} / 年",
                    perks = listOf("无限翻译", "全部工具解锁", "无广告", "标准 + 热门离线包"),
                    accent = LiquidGlassTheme.accentSecondary,
                    selected = selectedTier == LoginManager.Membership.PRO,
                    onClick = { selectedTier = LoginManager.Membership.PRO }
                )
                Spacer(Modifier.height(8.dp))
                PlanCard(
                    tier = LoginManager.Membership.PREMIUM,
                    price = "¥${PaymentManager.PREMIUM_PRICE_YEAR} / 年",
                    perks = listOf("PRO 全部权益", "VIP 优先客服", "Beta 优先体验", "全部离线语言包"),
                    accent = LiquidGlassTheme.gold,
                    selected = selectedTier == LoginManager.Membership.PREMIUM,
                    onClick = { selectedTier = LoginManager.Membership.PREMIUM }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 第三步：交易号输入 + 激活 ----
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "第三步 回填交易号激活",
                color = LiquidGlassTheme.accentSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "在支付宝「账单」中找到该笔付款，复制交易号（28 位数字）粘贴到下方",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = txId,
                onValueChange = { txId = it; resultMessage = null; resultOk = null },
                label = { Text("支付宝交易号") },
                placeholder = { Text("28 位交易号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = paymentTextFieldColors()
            )
            Spacer(Modifier.height(10.dp))

            resultMessage?.let { msg ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (resultOk == true) LiquidGlassTheme.green.copy(alpha = 0.15f)
                            else LiquidGlassTheme.announcementHigh.copy(alpha = 0.15f)
                        )
                        .padding(10.dp)
                ) {
                    Text(
                        text = msg,
                        color = if (resultOk == true) LiquidGlassTheme.green
                        else LiquidGlassTheme.announcementHigh,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val ok = PaymentManager.activateMembership(selectedTier, txId)
                        resultOk = ok
                        resultMessage = when {
                            ok -> "激活成功！${selectedTier.label} 已生效，有效期 1 年"
                            !PaymentManager.isLoggedIn() -> "激活失败：请先在「账号」页登录平台账号"
                            !PaymentManager.validateTransactionId(txId) -> "交易号格式不正确：需 ≥ 20 位且以数字为主"
                            else -> "激活失败，请检查交易号后重试"
                        }
                        if (ok) {
                            txId = ""
                            currentUser = LoginManager.currentUser()
                        }
                    },
                    enabled = txId.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = LiquidGlassTheme.accentPrimary,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text("我已付款，激活会员", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { txId = ""; resultMessage = null; resultOk = null },
                    modifier = Modifier.height(44.dp)
                ) { Text("清空") }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "说明：本方案基于信任，不做服务端对账；仅校验交易号格式。正式商用将接入支付宝官方支付并服务端验签。",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 10.sp
            )
        }

        // 激活历史
        PaymentManager.activationHistory().takeIf { it.isNotEmpty() }?.let { history ->
            Spacer(Modifier.height(12.dp))
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "激活记录",
                    color = LiquidGlassTheme.onSurfaceColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                history.forEach { line ->
                    Text(
                        text = line,
                        color = LiquidGlassTheme.onSurfaceMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

/**
 * 套餐选择卡片
 */
@Composable
private fun PlanCard(
    tier: LoginManager.Membership,
    price: String,
    perks: List<String>,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.18f) else LiquidGlassTheme.surfaceVariant.copy(alpha = 0.4f))
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) accent else LiquidGlassTheme.glassBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (selected) "◉" else "○",
                    color = if (selected) accent else LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 16.sp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tier.label,
                    color = LiquidGlassTheme.onSurfaceColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = price,
                    color = accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(6.dp))
            perks.forEach { p ->
                Text(
                    text = "· $p",
                    color = LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/** 付款页输入框配色 */
@Composable
private fun paymentTextFieldColors() = androidx.compose.material.TextFieldDefaults.outlinedTextFieldColors(
    textColor = LiquidGlassTheme.onSurfaceColor,
    cursorColor = LiquidGlassTheme.accentSecondary,
    focusedBorderColor = LiquidGlassTheme.accentSecondary,
    unfocusedBorderColor = LiquidGlassTheme.onSurfaceMuted.copy(alpha = 0.4f),
    focusedLabelColor = LiquidGlassTheme.accentSecondary,
    unfocusedLabelColor = LiquidGlassTheme.onSurfaceMuted,
    backgroundColor = Color.Transparent
)
