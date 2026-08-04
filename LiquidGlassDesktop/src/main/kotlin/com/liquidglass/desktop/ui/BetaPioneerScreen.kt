package com.liquidglass.desktop.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.desktop.system.BetaApplication
import com.liquidglass.desktop.system.BetaFeedback
import com.liquidglass.desktop.system.BetaPioneerManager
import com.liquidglass.desktop.system.BetaStatus
import com.liquidglass.desktop.system.PioneerCode
import com.liquidglass.desktop.system.Question
import com.liquidglass.desktop.system.QuestionnaireResult
import com.liquidglass.desktop.theme.LiquidGlassTheme
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** 先锋体验码流程阶段 */
private enum class BetaPhase { Questionnaire, Failed, Passed, Wizard }

/**
 * Beta 先锋页（v2.11.0 完善版）
 *
 * 一个页面涵盖 Beta 先锋计划完整生命周期：
 *  1. 状态徽章（未申请 / 已申请待审核 / 已批准）
 *  2. Beta 通道说明（什么是 Beta 先锋 / 权益 / 义务）
 *  3. Beta 申请表单（昵称、邮箱、使用场景、为何加入）—— 本地保存 + 可选 GitHub Issue
 *  4. Beta 版本日志（从 version.json 拉取 releaseNotes）
 *  5. Beta 反馈入口（Bug 报告 / 功能建议，保存到本地文件）
 *  6. 先锋体验码 & 下载（问卷 → 先锋码 → 验证 → 下载向导）
 *
 * 签名：[BetaPioneerScreen]（无参，由主路由接入）
 */
@Composable
fun BetaPioneerScreen() {
    val manager = remember { BetaPioneerManager() }
    val scope = rememberCoroutineScope()

    // ---- 申请表单状态 ----
    val savedApp = remember { manager.loadApplication() }
    var nickname by remember { mutableStateOf(savedApp?.nickname ?: "") }
    var email by remember { mutableStateOf(savedApp?.email ?: "") }
    var useCase by remember { mutableStateOf(savedApp?.useCase ?: "") }
    var reason by remember { mutableStateOf(savedApp?.reason ?: "") }
    var appMessage by remember { mutableStateOf<String?>(null) }
    var appOk by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf(manager.applicationStatus()) }
    var showTokenEditor by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf(manager.getGithubToken()) }

    // ---- 版本日志状态 ----
    var changelog by remember { mutableStateOf<String?>(null) }
    var changelogLoading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        changelogLoading = true
        changelog = manager.fetchChangelog()
        changelogLoading = false
    }

    // ---- 反馈状态 ----
    var feedbackType by remember { mutableStateOf("Bug 报告") }
    var feedbackContent by remember { mutableStateOf("") }
    var feedbackContact by remember { mutableStateOf("") }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var feedbackOk by remember { mutableStateOf<Boolean?>(null) }
    val feedbackTypes = remember { listOf("Bug 报告", "功能建议", "体验问题", "其他") }

    // ---- 先锋体验码流程状态 ----
    val questions = remember { manager.questions }
    val answers = remember { mutableStateMapOf<Int, Int>() }
    val savedCode = remember { manager.loadSavedCode() }
    var phase by remember { mutableStateOf(if (savedCode != null) BetaPhase.Passed else BetaPhase.Questionnaire) }
    var result by remember { mutableStateOf<QuestionnaireResult?>(null) }
    var generatedCode by remember { mutableStateOf(savedCode) }
    var verifyInput by remember { mutableStateOf("") }
    var verifyMessage by remember { mutableStateOf<String?>(null) }
    var betaInfo by remember { mutableStateOf<String?>(null) }
    var betaInfoRaw by remember { mutableStateOf<com.liquidglass.desktop.system.BetaInfo?>(null) }
    var copied by remember { mutableStateOf(false) }

    fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        copied = true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        // 标题 + 状态徽章
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Beta 先锋计划",
                color = LiquidGlassTheme.onSurfaceColor,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
            Spacer(Modifier.width(10.dp))
            StatusBadge(status = status)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "加入 Beta 先锋，抢先体验新功能，与开发者共建 LiquidGlass",
            color = LiquidGlassTheme.onSurfaceMuted,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))

        // ===== 1. Beta 通道说明 =====
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("Beta 通道说明")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "什么是 Beta 先锋",
                color = LiquidGlassTheme.accentSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Beta 先锋是 LiquidGlass 的早期体验计划成员。在正式版本发布前，先锋成员可优先获取 Beta 版本，" +
                    "率先试用新功能、新交互，并通过反馈帮助产品更快迭代。成为先锋即意味着你愿意用一点点不稳定，" +
                    "换取走在最前沿的体验。",
                color = LiquidGlassTheme.onSurfaceColor,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "先锋权益",
                color = LiquidGlassTheme.accentSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            listOf(
                "优先体验最新 Beta 版本与新功能",
                "专属 17-25 位先锋体验码，用于解锁 Beta 下载",
                "反馈被采纳后署名出现在更新日志",
                "PREMIUM 会员可享 Beta 优先体验通道"
            ).forEach {
                Text("· $it", color = LiquidGlassTheme.onSurfaceColor, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "先锋义务",
                color = LiquidGlassTheme.accentSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            listOf(
                "遇到 Bug 主动通过「Beta 反馈入口」提交",
                "理解 Beta 版本可能存在不稳定与数据丢失风险",
                "不在公开渠道泄露未公开的 Beta 内容",
                "不在生产环境依赖 Beta 版本完成重要工作"
            ).forEach {
                Text("· $it", color = LiquidGlassTheme.onSurfaceColor, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))

        // ===== 2. Beta 申请表单 =====
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("Beta 申请")
            Spacer(Modifier.height(4.dp))
            Text(
                text = "填写下方信息申请成为 Beta 先锋，提交后保存在本地" +
                    "（可选配置 GitHub Token 后同步提交为 Issue）。",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it; appMessage = null },
                label = { Text("昵称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = betaTextFieldColors()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; appMessage = null },
                label = { Text("邮箱") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
                colors = betaTextFieldColors()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = useCase,
                onValueChange = { useCase = it; appMessage = null },
                label = { Text("使用场景（你打算如何使用 LiquidGlass）") },
                modifier = Modifier.fillMaxWidth().height(90.dp),
                colors = betaTextFieldColors()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it; appMessage = null },
                label = { Text("为何想加入 Beta（期望体验哪些新功能）") },
                modifier = Modifier.fillMaxWidth().height(90.dp),
                colors = betaTextFieldColors()
            )

            appMessage?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (appOk == true) LiquidGlassTheme.green.copy(alpha = 0.15f)
                            else LiquidGlassTheme.announcementHigh.copy(alpha = 0.15f)
                        )
                        .padding(10.dp)
                ) {
                    Text(
                        text = msg,
                        color = if (appOk == true) LiquidGlassTheme.green else LiquidGlassTheme.announcementHigh,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val form = BetaApplication(
                            nickname = nickname.trim(),
                            email = email.trim(),
                            useCase = useCase.trim(),
                            reason = reason.trim(),
                            submittedAt = System.currentTimeMillis()
                        )
                        when {
                            form.nickname.length < 2 -> {
                                appOk = false; appMessage = "昵称至少 2 个字符"
                            }
                            !form.email.contains('@') || !form.email.contains('.') -> {
                                appOk = false; appMessage = "邮箱格式不正确"
                            }
                            form.useCase.length < 5 -> {
                                appOk = false; appMessage = "请简短描述使用场景（≥5 字）"
                            }
                            form.reason.length < 5 -> {
                                appOk = false; appMessage = "请说明加入理由（≥5 字）"
                            }
                            else -> {
                                scope.launch {
                                    appMessage = manager.submitApplication(form)
                                    appOk = appMessage?.startsWith("申请已保存") == true
                                    status = manager.applicationStatus()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = LiquidGlassTheme.accentPrimary,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1f).height(42.dp)
                ) { Text("提交申请", fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { showTokenEditor = !showTokenEditor },
                    modifier = Modifier.height(42.dp)
                ) { Text("Token") }
            }

            if (showTokenEditor) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "配置 GitHub Token 后，申请可同步提交为 Issue（仅保存在本机，不上传到服务端）",
                    color = LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 10.sp
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("GitHub Personal Access Token（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = betaTextFieldColors()
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    OutlinedButton(onClick = {
                        manager.setGithubToken(tokenInput)
                        appMessage = "Token 已保存"
                        appOk = true
                        showTokenEditor = false
                    }) { Text("保存 Token") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        manager.setGithubToken("")
                        tokenInput = ""
                        appMessage = "Token 已清除，远程提交将跳过"
                        appOk = true
                    }) { Text("清除") }
                }
            }

            if (status != BetaStatus.NOT_APPLIED) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        manager.resetApplication()
                        nickname = ""; email = ""; useCase = ""; reason = ""
                        appMessage = null; appOk = null
                        status = manager.applicationStatus()
                    }
                ) { Text("重新填写申请") }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ===== 3. Beta 版本日志 =====
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("Beta 版本日志")
            Spacer(Modifier.height(8.dp))
            when {
                changelogLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(16.dp).height(16.dp),
                        strokeWidth = 2.dp,
                        color = LiquidGlassTheme.accentSecondary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在拉取 version.json...", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 12.sp)
                }
                changelog != null -> Text(
                    text = changelog,
                    color = LiquidGlassTheme.onSurfaceColor,
                    fontSize = 12.sp
                )
                else -> Text(
                    text = "暂未获取到版本日志，请检查网络后重试。",
                    color = LiquidGlassTheme.onSurfaceMuted,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                changelogLoading = true
                scope.launch {
                    changelog = manager.fetchChangelog()
                    changelogLoading = false
                }
            }) { Text("刷新") }
        }
        Spacer(Modifier.height(12.dp))

        // ===== 4. Beta 反馈入口 =====
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("Beta 反馈")
            Spacer(Modifier.height(4.dp))
            Text(
                text = "遇到 Bug 或有功能建议？请在这里告诉我们，反馈将保存为本地文件。",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))
            Text("反馈类型", color = LiquidGlassTheme.onSurfaceColor, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                feedbackTypes.forEach { t ->
                    val selected = feedbackType == t
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) LiquidGlassTheme.accentPrimary.copy(alpha = 0.3f)
                                else LiquidGlassTheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                            .border(
                                1.dp,
                                if (selected) LiquidGlassTheme.accentSecondary else LiquidGlassTheme.glassBorder,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { feedbackType = t }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = t,
                            color = if (selected) LiquidGlassTheme.onSurfaceBright else LiquidGlassTheme.onSurfaceMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = feedbackContent,
                onValueChange = { feedbackContent = it; feedbackMessage = null },
                label = { Text("详细描述（复现步骤 / 期望行为 / 建议）") },
                modifier = Modifier.fillMaxWidth().height(110.dp),
                colors = betaTextFieldColors()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = feedbackContact,
                onValueChange = { feedbackContact = it; feedbackMessage = null },
                label = { Text("联系方式（可选，便于跟进）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = betaTextFieldColors()
            )

            feedbackMessage?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = msg,
                    color = if (feedbackOk == true) LiquidGlassTheme.green else LiquidGlassTheme.announcementHigh,
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    when {
                        feedbackContent.trim().length < 5 -> {
                            feedbackOk = false; feedbackMessage = "反馈内容至少 5 个字符"
                        }
                        else -> {
                            val path = manager.saveFeedback(
                                BetaFeedback(
                                    type = feedbackType,
                                    content = feedbackContent.trim(),
                                    contact = feedbackContact.trim(),
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                            if (path != null) {
                                feedbackOk = true
                                feedbackMessage = "反馈已保存：$path"
                                feedbackContent = ""; feedbackContact = ""
                            } else {
                                feedbackOk = false
                                feedbackMessage = "保存失败，请检查磁盘权限"
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = LiquidGlassTheme.accentSecondary,
                    contentColor = Color.White
                ),
                modifier = Modifier.height(40.dp)
            ) { Text("提交反馈") }

            manager.listFeedback().takeIf { it.isNotEmpty() }?.let { list ->
                Spacer(Modifier.height(10.dp))
                Text("历史反馈文件：", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 11.sp)
                list.take(5).forEach { p ->
                    Text("· $p", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ===== 5. 先锋体验码 & 下载 =====
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("先锋体验码")
            Spacer(Modifier.height(4.dp))
            Text(
                text = "完成问卷并通过 55 分阈值即可生成专属先锋体验码，用于解锁 Beta 版本下载。",
                color = LiquidGlassTheme.onSurfaceMuted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(12.dp))

            when (phase) {
                BetaPhase.Questionnaire -> {
                    val progress = answers.size.toFloat() / questions.size.toFloat()
                    Text(
                        text = "进度: ${answers.size} / ${questions.size}",
                        color = LiquidGlassTheme.onSurfaceMuted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = LiquidGlassTheme.accentSecondary,
                        backgroundColor = LiquidGlassTheme.surfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    QuestionnaireSection(
                        questions = questions,
                        answers = answers,
                        canSubmit = answers.size == questions.size,
                        onSubmit = {
                            val r = manager.evaluate(answers)
                            result = r
                            if (r.passed) {
                                generatedCode = manager.generateCode()
                                phase = BetaPhase.Passed
                            } else {
                                phase = BetaPhase.Failed
                            }
                        }
                    )
                }

                BetaPhase.Failed -> result?.let { r ->
                    Text(
                        text = "评分: ${r.score} / 100",
                        color = LiquidGlassTheme.announcementHigh,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "未达到 55 分阈值，无法获取先锋体验码",
                        color = LiquidGlassTheme.onSurfaceColor,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = {
                        answers.clear()
                        result = null
                        phase = BetaPhase.Questionnaire
                    }) { Text("重新填写") }
                }

                BetaPhase.Passed -> generatedCode?.let { code ->
                    PassedSection(
                        code = code,
                        verifyInput = verifyInput,
                        onVerifyInputChange = { verifyInput = it },
                        verifyMessage = verifyMessage,
                        betaInfo = betaInfo,
                        betaInfoRaw = betaInfoRaw,
                        copied = copied,
                        onCopy = { copyToClipboard(code.code) },
                        onVerify = {
                            scope.launch {
                                val raw = manager.verifyAndFetchBetaRaw(verifyInput)
                                if (raw != null) {
                                    betaInfoRaw = raw
                                    betaInfo = buildString {
                                        appendLine("版本号: ${raw.version}")
                                        appendLine("发布日期: ${raw.date}")
                                        appendLine("更新说明: ${raw.notes}")
                                        appendLine("下载链接: ${raw.downloadUrl}")
                                        appendLine("校验值: ${raw.sha256}")
                                        if (raw.sizeBytes > 0) appendLine("体积: ${raw.sizeBytes} 字节")
                                    }
                                    verifyMessage = "验证通过"
                                } else {
                                    betaInfo = null
                                    betaInfoRaw = null
                                    verifyMessage = "先锋码无效或已过期"
                                }
                            }
                        },
                        onClear = {
                            verifyInput = ""
                            verifyMessage = null
                            betaInfo = null
                            betaInfoRaw = null
                        },
                        onDownload = {
                            if (betaInfoRaw != null) phase = BetaPhase.Wizard
                        }
                    )
                }

                BetaPhase.Wizard -> betaInfoRaw?.let { info ->
                    BetaDownloadWizard(
                        betaPioneerManager = manager,
                        betaInfo = info
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { phase = BetaPhase.Passed }) {
                        Text("返回验证页")
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** 小节标题 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = LiquidGlassTheme.onSurfaceColor,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold
    )
}

/** 状态徽章 */
@Composable
private fun StatusBadge(status: BetaStatus) {
    val (text, color) = when (status) {
        BetaStatus.NOT_APPLIED -> "未申请" to LiquidGlassTheme.onSurfaceMuted
        BetaStatus.APPLIED -> "已申请·待审核" to LiquidGlassTheme.accentSecondary
        BetaStatus.APPROVED -> "已批准" to LiquidGlassTheme.gold
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 问卷区 */
@Composable
private fun QuestionnaireSection(
    questions: List<Question>,
    answers: SnapshotStateMap<Int, Int>,
    canSubmit: Boolean,
    onSubmit: () -> Unit
) {
    questions.forEachIndexed { index, q ->
        Text(
            text = "${index + 1}. ${q.text}",
            color = LiquidGlassTheme.onSurfaceColor,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(6.dp))
        q.options.forEachIndexed { oi, opt ->
            val selected = answers[index] == oi
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) LiquidGlassTheme.accentPrimary.copy(alpha = 0.3f)
                        else Color.Transparent
                    )
                    .clickable { answers[index] = oi }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selected) "(*)" else "( )",
                    color = if (selected) LiquidGlassTheme.accentSecondary
                    else LiquidGlassTheme.onSurfaceMuted
                )
                Spacer(Modifier.width(8.dp))
                Text(text = opt, color = LiquidGlassTheme.onSurfaceColor, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    Button(
        onClick = onSubmit,
        enabled = canSubmit,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = LiquidGlassTheme.accentPrimary,
            contentColor = Color.White
        )
    ) { Text("提交问卷") }
}

/** 通过区：显示先锋码 + 验证框 + beta 信息 */
@Composable
private fun PassedSection(
    code: PioneerCode,
    verifyInput: String,
    onVerifyInputChange: (String) -> Unit,
    verifyMessage: String?,
    betaInfo: String?,
    betaInfoRaw: com.liquidglass.desktop.system.BetaInfo?,
    copied: Boolean,
    onCopy: () -> Unit,
    onVerify: () -> Unit,
    onClear: () -> Unit,
    onDownload: () -> Unit
) {
    Text(
        text = "恭喜成为 Beta 先锋",
        color = LiquidGlassTheme.accentSecondary,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp
    )
    Spacer(Modifier.height(8.dp))
    Text(text = "你的专属先锋体验码:", color = LiquidGlassTheme.onSurfaceColor, fontSize = 12.sp)
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = code.code,
            color = LiquidGlassTheme.accentSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (copied) Color(0xFF00E5A0) else LiquidGlassTheme.accentSecondary)
                .clickable { onCopy() }
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (copied) "已复制" else "复制",
                color = Color.White,
                fontSize = 11.sp
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(text = "有效期至: ${code.expiry}", color = LiquidGlassTheme.onSurfaceMuted, fontSize = 11.sp)
    Spacer(Modifier.height(12.dp))

    Text(
        text = "验证先锋码（用于查看 Beta 版本下载信息）",
        color = LiquidGlassTheme.onSurfaceColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(8.dp))
    BasicTextField(
        value = verifyInput,
        onValueChange = onVerifyInputChange,
        textStyle = TextStyle(color = LiquidGlassTheme.onSurfaceColor, fontSize = 13.sp),
        cursorBrush = SolidColor(LiquidGlassTheme.accentSecondary),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(LiquidGlassTheme.surfaceColor)
            .padding(12.dp)
    )
    Spacer(Modifier.height(8.dp))
    Row {
        Button(onClick = onVerify) { Text("验证") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onClear) { Text("清除") }
    }
    verifyMessage?.let { msg ->
        Spacer(Modifier.height(8.dp))
        Text(
            text = msg,
            color = if (betaInfo != null) LiquidGlassTheme.accentSecondary
            else LiquidGlassTheme.announcementHigh,
            fontSize = 12.sp
        )
    }

    betaInfo?.let { info ->
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Beta 版本信息",
            color = LiquidGlassTheme.accentSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(text = info, color = LiquidGlassTheme.onSurfaceColor, fontSize = 12.sp)
        if (betaInfoRaw != null) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onDownload,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = LiquidGlassTheme.accentPrimary,
                    contentColor = Color.White
                )
            ) { Text("打开下载向导") }
        }
    }
}

/** Beta 页输入框配色 */
@Composable
private fun betaTextFieldColors() = androidx.compose.material.TextFieldDefaults.outlinedTextFieldColors(
    textColor = LiquidGlassTheme.onSurfaceColor,
    cursorColor = LiquidGlassTheme.accentSecondary,
    focusedBorderColor = LiquidGlassTheme.accentSecondary,
    unfocusedBorderColor = LiquidGlassTheme.onSurfaceMuted.copy(alpha = 0.4f),
    focusedLabelColor = LiquidGlassTheme.accentSecondary,
    unfocusedLabelColor = LiquidGlassTheme.onSurfaceMuted,
    backgroundColor = Color.Transparent
)
