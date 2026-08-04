package com.liquidglass.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.desktop.system.LoginManager
import com.liquidglass.desktop.theme.LiquidGlassTheme
import kotlinx.coroutines.launch

/**
 * 系统级登录板块
 *
 * 平台通用账号（区别于网易云音乐登录）：
 * - 登录/注册切换
 * - 用户名/邮箱 + 密码
 * - 会员等级显示（FREE/PRO/PREMIUM）
 * - 已登录态展示用户信息 + 退出登录
 * - 会员权益说明
 */
@Composable
fun LoginScreen() {
    val scope = rememberCoroutineScope()
    val currentUser = remember { LoginManager.currentUser() }
    var isLoggedIn by remember { mutableStateOf(currentUser != null) }
    var userInfo by remember { mutableStateOf(currentUser) }

    // 表单状态
    var mode by remember { mutableStateOf("login") } // login | register
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部品牌
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                LiquidGlassTheme.accentSecondary,
                                LiquidGlassTheme.accentPrimary
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("◆", color = LiquidGlassTheme.onAccent, fontSize = 28.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "灵工坊账号",
                color = LiquidGlassTheme.onSurfaceColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "一个账号，畅享所有功能",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(24.dp))

            if (isLoggedIn && userInfo != null) {
                // ===== 已登录态 =====
                LoggedInView(
                    user = userInfo!!,
                    onLogout = {
                        LoginManager.logout()
                        isLoggedIn = false
                        userInfo = null
                        username = ""; email = ""; password = ""
                    }
                )
            } else {
                // ===== 未登录态：登录/注册表单 =====
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp
                ) {
                    // 模式切换
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ModeTab(
                            text = "登录",
                            selected = mode == "login",
                            onClick = { mode = "login"; errorMsg = null }
                        )
                        ModeTab(
                            text = "注册",
                            selected = mode == "register",
                            onClick = { mode = "register"; errorMsg = null }
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it; errorMsg = null },
                        label = { Text(if (mode == "register") "用户名" else "用户名或邮箱") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = textFieldColors()
                    )
                    if (mode == "register") {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it; errorMsg = null },
                            label = { Text("邮箱") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth(),
                            colors = textFieldColors()
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; errorMsg = null },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        colors = textFieldColors()
                    )

                    errorMsg?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = err,
                            color = LiquidGlassTheme.announcementHigh,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            loading = true; errorMsg = null
                            scope.launch {
                                val result = if (mode == "login") {
                                    LoginManager.login(username, password)
                                } else {
                                    LoginManager.register(username, email, password)
                                }
                                loading = false
                                when (result) {
                                    is LoginManager.LoginResult.Success -> {
                                        userInfo = result.user
                                        isLoggedIn = true
                                    }
                                    is LoginManager.LoginResult.Error -> {
                                        errorMsg = result.message
                                    }
                                }
                            }
                        },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = LiquidGlassTheme.accentPrimary,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            if (loading) "处理中..." else if (mode == "login") "登录" else "注册并登录",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                MembershipBenefitsView()
            }
        }
    }
}

@Composable
private fun LoggedInView(
    user: LoginManager.UserInfo,
    onLogout: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
        // 用户头像 + 用户名
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                LiquidGlassTheme.accentSecondary,
                                LiquidGlassTheme.accentPrimary
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = user.username.take(1).uppercase(),
                    color = LiquidGlassTheme.onAccent,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    user.username,
                    color = LiquidGlassTheme.onSurfaceColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    user.email.ifBlank { "未绑定邮箱" },
                    color = LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 12.sp
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // 会员等级
        val membershipColor = when (user.membership) {
            LoginManager.Membership.PREMIUM -> Color(0xFFFFD700)
            LoginManager.Membership.PRO -> LiquidGlassTheme.accentSecondary
            LoginManager.Membership.FREE -> LiquidGlassTheme.onSurfaceMuted
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(membershipColor.copy(alpha = 0.15f))
                .border(1.dp, membershipColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (user.membership) {
                        LoginManager.Membership.PREMIUM -> "👑"
                        LoginManager.Membership.PRO -> "★"
                        LoginManager.Membership.FREE -> "○"
                    },
                    fontSize = 18.sp
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        user.membership.label,
                        color = membershipColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (user.expireAt > 0) {
                            "到期时间：${java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(user.expireAt))}"
                        } else if (user.membership == LoginManager.Membership.FREE) {
                            "升级会员解锁更多权益"
                        } else {
                            "永久有效"
                        },
                        color = LiquidGlassTheme.onSurfaceMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.weight(1f).height(40.dp)
            ) { Text("退出登录") }
        }
    }
}

@Composable
private fun MembershipBenefitsView() {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
        Text(
            "会员权益",
            color = LiquidGlassTheme.onSurfaceColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        BenefitRow("○", "免费版", "每日 1000 字翻译、基础工具")
        Spacer(Modifier.height(6.dp))
        BenefitRow("★", "专业版 PRO", "无限翻译、所有工具解锁、无广告")
        Spacer(Modifier.height(6.dp))
        BenefitRow("👑", "高级版 PREMIUM", "PRO 权益 + 优先客服 + Beta 优先体验")
    }
}

@Composable
private fun BenefitRow(icon: String, title: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, color = LiquidGlassTheme.onSurfaceColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = LiquidGlassTheme.onSurfaceMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ModeTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) LiquidGlassTheme.accentPrimary.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            color = if (selected) LiquidGlassTheme.onSurfaceColor else LiquidGlassTheme.onSurfaceMuted,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun textFieldColors() = androidx.compose.material.TextFieldDefaults.outlinedTextFieldColors(
    textColor = LiquidGlassTheme.onSurfaceColor,
    cursorColor = LiquidGlassTheme.accentSecondary,
    focusedBorderColor = LiquidGlassTheme.accentSecondary,
    unfocusedBorderColor = LiquidGlassTheme.onSurfaceMuted.copy(alpha = 0.4f),
    focusedLabelColor = LiquidGlassTheme.accentSecondary,
    unfocusedLabelColor = LiquidGlassTheme.onSurfaceMuted,
    backgroundColor = Color.Transparent
)
