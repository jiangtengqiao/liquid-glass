package com.liquidglass.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.unit.dp
import com.liquidglass.desktop.system.BetaPioneerManager
import com.liquidglass.desktop.system.PioneerCode
import com.liquidglass.desktop.system.QuestionnaireResult
import com.liquidglass.desktop.theme.LiquidGlassTheme
import kotlinx.coroutines.launch

/** Beta 先锋流程阶段 */
private enum class BetaPhase { Questionnaire, Failed, Passed }

/**
 * Beta 先锋体验码申请页
 *
 * - 问卷界面（8 道题，单选）
 * - 提交后显示评分结果
 * - 通过后显示生成的 17-25 位先锋体验码
 * - 先锋码输入验证框
 * - 验证通过后显示 beta 版本信息和下载链接
 */
@Composable
fun BetaPioneerScreen(
    betaPioneerManager: BetaPioneerManager,
    modifier: Modifier = Modifier
) {
    val questions = remember { betaPioneerManager.questions }
    val answers = remember { mutableStateMapOf<Int, Int>() }

    var phase by remember { mutableStateOf(BetaPhase.Questionnaire) }
    var result by remember { mutableStateOf<QuestionnaireResult?>(null) }
    var generatedCode by remember { mutableStateOf<PioneerCode?>(null) }

    // 验证相关状态
    var verifyInput by remember { mutableStateOf("") }
    var verifyMessage by remember { mutableStateOf<String?>(null) }
    var betaInfo by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Beta 先锋体验计划",
            color = LiquidGlassTheme.onSurfaceColor,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.h6
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "完成问卷并通过 55 分阈值即可生成专属先锋体验码",
            color = LiquidGlassTheme.onSurfaceMuted
        )
        Spacer(Modifier.height(16.dp))

        when (phase) {
            BetaPhase.Questionnaire -> QuestionnaireSection(
                questions = questions,
                answers = answers,
                canSubmit = answers.size == questions.size,
                onSubmit = {
                    val r = betaPioneerManager.evaluate(answers)
                    result = r
                    if (r.passed) {
                        generatedCode = betaPioneerManager.generateCode()
                        phase = BetaPhase.Passed
                    } else {
                        phase = BetaPhase.Failed
                    }
                }
            )

            BetaPhase.Failed -> result?.let { r ->
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "评分: ${r.score} / 100",
                        color = LiquidGlassTheme.announcementHigh,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "未达到 55 分阈值，无法获取先锋体验码",
                        color = LiquidGlassTheme.onSurfaceColor
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = {
                        answers.clear()
                        result = null
                        phase = BetaPhase.Questionnaire
                    }) {
                        Text("重新填写")
                    }
                }
            }

            BetaPhase.Passed -> generatedCode?.let { code ->
                PassedSection(
                    code = code,
                    verifyInput = verifyInput,
                    onVerifyInputChange = { verifyInput = it },
                    verifyMessage = verifyMessage,
                    betaInfo = betaInfo,
                    onVerify = {
                        scope.launch {
                            val info = betaPioneerManager.verifyAndFetchBeta(verifyInput)
                            if (info != null) {
                                betaInfo = info
                                verifyMessage = "验证通过"
                            } else {
                                betaInfo = null
                                verifyMessage = "先锋码无效或已过期"
                            }
                        }
                    },
                    onClear = {
                        verifyInput = ""
                        verifyMessage = null
                        betaInfo = null
                    }
                )
            }
        }
    }
}

/** 问卷区 */
@Composable
private fun QuestionnaireSection(
    questions: List<com.liquidglass.desktop.system.Question>,
    answers: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Int>,
    canSubmit: Boolean,
    onSubmit: () -> Unit
) {
    questions.forEachIndexed { index, q ->
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "${index + 1}. ${q.text}",
                color = LiquidGlassTheme.onSurfaceColor
            )
            Spacer(Modifier.height(8.dp))
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
                    Text(
                        text = opt,
                        color = LiquidGlassTheme.onSurfaceColor
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    Button(
        onClick = onSubmit,
        enabled = canSubmit,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = LiquidGlassTheme.accentPrimary,
            contentColor = Color.White
        )
    ) {
        Text("提交问卷")
    }
}

/** 通过区：显示先锋码 + 验证框 + beta 信息 */
@Composable
private fun PassedSection(
    code: PioneerCode,
    verifyInput: String,
    onVerifyInputChange: (String) -> Unit,
    verifyMessage: String?,
    betaInfo: String?,
    onVerify: () -> Unit,
    onClear: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "恭喜成为 Beta 先锋",
            color = LiquidGlassTheme.accentSecondary,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "你的专属先锋体验码:",
            color = LiquidGlassTheme.onSurfaceColor
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = code.code,
            color = LiquidGlassTheme.accentSecondary,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "有效期至: ${code.expiry}",
            color = LiquidGlassTheme.onSurfaceMuted
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "请妥善保管，用于下载 Beta 版本",
            color = LiquidGlassTheme.onSurfaceMuted
        )
    }
    Spacer(Modifier.height(16.dp))

    // 验证框
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "验证先锋码（用于查看 Beta 版本下载信息）",
            color = LiquidGlassTheme.onSurfaceColor,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = verifyInput,
            onValueChange = onVerifyInputChange,
            textStyle = TextStyle(color = LiquidGlassTheme.onSurfaceColor),
            cursorBrush = SolidColor(LiquidGlassTheme.accentSecondary),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(LiquidGlassTheme.surfaceColor)
                .padding(12.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = onVerify) {
                Text("验证")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onClear) {
                Text("清除")
            }
        }
        verifyMessage?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = msg,
                color = if (betaInfo != null) LiquidGlassTheme.accentSecondary
                else LiquidGlassTheme.announcementHigh
            )
        }
    }

    betaInfo?.let { info ->
        Spacer(Modifier.height(12.dp))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Beta 版本信息",
                color = LiquidGlassTheme.accentSecondary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = info,
                color = LiquidGlassTheme.onSurfaceColor
            )
        }
    }
}
