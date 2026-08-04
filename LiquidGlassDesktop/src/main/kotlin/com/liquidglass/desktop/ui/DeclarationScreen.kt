package com.liquidglass.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.desktop.theme.LiquidGlassTheme

/**
 * 声明内容片段类型
 *  - [DeclPart.Heading]  小节子标题
 *  - [DeclPart.Paragraph] 普通段落
 *  - [DeclPart.Bullet]    列表项
 */
private sealed class DeclPart {
    data class Heading(val text: String) : DeclPart()
    data class Paragraph(val text: String) : DeclPart()
    data class Bullet(val text: String) : DeclPart()
}

/**
 * 平台声明页
 *
 * 汇总 LiquidGlass Desktop 的全部法律声明，分章节玻璃态卡片展示：
 *  服务说明 / 用户行为规范 / 知识产权 / 隐私政策 / 免责声明 /
 *  会员服务条款 / 争议解决 / 联系方式
 *
 * 签名：[DeclarationScreen]（无参，由主路由接入）
 */
@Composable
fun DeclarationScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 顶部标题
        Text(
            text = "平台声明",
            color = LiquidGlassTheme.onSurfaceColor,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "使用 LiquidGlass Desktop 即视为你已阅读并同意以下全部条款。请仔细阅读。",
            color = LiquidGlassTheme.onSurfaceMuted,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))

        DeclarationChapter("一、服务说明", serviceParts())
        DeclarationChapter("二、用户行为规范", conductParts())
        DeclarationChapter("三、知识产权", ipParts())
        DeclarationChapter("四、隐私政策", privacyParts())
        DeclarationChapter("五、免责声明", disclaimerParts())
        DeclarationChapter("六、会员服务条款", membershipParts())
        DeclarationChapter("七、争议解决", disputeParts())
        DeclarationChapter("八、联系方式", contactParts())

        Spacer(Modifier.height(8.dp))
        Text(
            text = "本声明最后更新于 2026-08-04，版本 v2.11.0。" +
                "LiquidGlass 保留在法律允许范围内对本声明的最终解释权。",
            color = LiquidGlassTheme.onSurfaceMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}

/** 单个声明章节（玻璃态卡片） */
@Composable
private fun DeclarationChapter(title: String, parts: List<DeclPart>) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = LiquidGlassTheme.accentSecondary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        parts.forEach { part ->
            when (part) {
                is DeclPart.Heading -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = part.text,
                        color = LiquidGlassTheme.onSurfaceBright,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                }
                is DeclPart.Paragraph -> {
                    Text(
                        text = part.text,
                        color = LiquidGlassTheme.onSurfaceColor,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 3.dp)
                    )
                }
                is DeclPart.Bullet -> {
                    Text(
                        text = "· ${part.text}",
                        color = LiquidGlassTheme.onSurfaceColor,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

// ===================== 各章节正文 =====================

private fun serviceParts() = listOf(
    DeclPart.Paragraph(
        "LiquidGlass Desktop（以下简称「本应用」，品牌名「灵工坊」）是一款运行于桌面操作系统的智能工具箱，" +
            "集成文本翻译、音乐播放、实用工具、Beta 先锋体验等功能，旨在为用户提供一站式的效率与娱乐体验。"
    ),
    DeclPart.Paragraph(
        "本应用由独立开发者开发并维护，当前通过 GitHub 开源仓库发布与分发，安装包以 Windows EXE 形式提供。" +
            "本应用并非企业级商业软件，部分功能依赖第三方公共接口与开源镜像服务。"
    ),
    DeclPart.Paragraph(
        "本应用提供基础免费功能与付费会员功能（PRO / PREMIUM）。免费功能存在每日翻译字数、工具权限等限制；" +
            "会员功能在付费激活后解锁相应权益，具体权益对比详见应用内「会员权益对比」页。"
    ),
    DeclPart.Heading("服务变更与中断"),
    DeclPart.Bullet("开发者有权根据实际需要对服务内容进行调整、升级或停止部分功能，并将通过应用内公告或更新日志告知。"),
    DeclPart.Bullet("因网络故障、第三方服务不可用、系统维护等原因导致服务中断，开发者不承担赔偿责任。"),
    DeclPart.Bullet("本应用不保证持续可用、不保证无错误，亦不保证满足用户的一切使用目的。")
)

private fun conductParts() = listOf(
    DeclPart.Heading("合法使用"),
    DeclPart.Paragraph(
        "你应仅在法律法规允许的范围内使用本应用，不得将本应用用于任何违法、侵权或损害他人合法权益的目的。" +
            "你对自己在使用本应用过程中的全部行为及后果独立承担法律责任。"
    ),
    DeclPart.Heading("禁止行为"),
    DeclPart.Bullet("对应用进行逆向工程、反编译、反汇编，或试图获取源代码（已开源部分遵循其对应开源协议）。"),
    DeclPart.Bullet("破解、绕过或篡改会员校验、翻译额度、先锋码验证等任何授权或计费机制。"),
    DeclPart.Bullet("滥用翻译接口、音乐接口、更新接口等网络服务，包括但不限于高频请求、爬取、压力测试。"),
    DeclPart.Bullet("通过本应用传播违法、有害、骚扰、诽谤、淫秽或侵犯他人知识产权的内容。"),
    DeclPart.Bullet("将本应用用于开发或辅助开发与本应用构成直接竞争的产品。"),
    DeclPart.Heading("账号安全"),
    DeclPart.Paragraph(
        "你应妥善保管平台账号与密码，因账号保管不善导致的损失由你自行承担。" +
            "发现账号被盗用或异常登录，应立即通过「账号」页退出登录并联系开发者。"
    )
)

private fun ipParts() = listOf(
    DeclPart.Heading("软件著作权"),
    DeclPart.Paragraph(
        "本应用的软件代码、界面设计、图标、文案、更新日志等成果，其知识产权归开发者或相应权利人所有，" +
            "受中华人民共和国著作权法及相关国际条约保护。"
    ),
    DeclPart.Heading("开源组件"),
    DeclPart.Paragraph(
        "本应用使用了 Kotlin、JetBrains Compose Multiplatform、OkHttp、JLayer、org.json 等开源组件，" +
            "相关组件的著作权归各自所有者所有，遵循其对应的开源许可协议（Apache 2.0、MIT 等）。"
    ),
    DeclPart.Heading("第三方内容"),
    DeclPart.Bullet("音乐内容来源于网易云音乐公开接口与用户本地音乐文件，版权归原始权利人所有。"),
    DeclPart.Bullet("翻译结果由第三方翻译引擎或本地语言包生成，本应用不对生成内容主张著作权。"),
    DeclPart.Heading("用户内容"),
    DeclPart.Paragraph(
        "你通过本应用提交的 Beta 申请、反馈等内容，你仍享有其著作权。" +
            "你授予开发者一项免费的、非排他的许可，用于处理你的申请、改进产品以及在更新日志中署名致谢（如反馈被采纳）。"
    )
)

private fun privacyParts() = listOf(
    DeclPart.Heading("数据收集"),
    DeclPart.Bullet("账号信息：注册 / 登录时提交的用户名、邮箱，用于身份识别与会员关联。"),
    DeclPart.Bullet("使用偏好：主题、快捷方式、翻译历史、搜索历史等，仅保存在本机 Preferences 中。"),
    DeclPart.Bullet("运行日志：崩溃日志（含异常堆栈、应用版本、操作系统信息），用于问题排查。"),
    DeclPart.Bullet("付款记录：支付宝交易号（截断展示）、激活时间与到期时间，仅保存在本机。"),
    DeclPart.Heading("数据使用"),
    DeclPart.Paragraph(
        "所收集数据仅用于提供与改进本应用的功能，包括身份验证、权益发放、问题定位与产品迭代。" +
            "开发者承诺不以任何方式将你的个人信息出售给第三方。"
    ),
    DeclPart.Heading("数据存储"),
    DeclPart.Bullet("本地存储：账号、偏好、激活态、反馈文件均存储于本机（用户目录与系统 Preferences）。"),
    DeclPart.Bullet("远端存储：账号信息（用户名、邮箱、加密密码、会员等级）存储于 GitHub 仓库的 users.json，" +
        "用于跨设备登录校验；该文件为公开可读，请勿使用与其他站点相同的密码。"),
    DeclPart.Heading("数据共享"),
    DeclPart.Paragraph(
        "除下列情况外，开发者不会向任何第三方共享你的个人信息：" +
            "（1）经你单独同意；（2）为完成 Beta 申请远程提交而向你配置的 GitHub 仓库创建 Issue；" +
            "（3）法律法规要求或行政、司法机关依法要求。"
    ),
    DeclPart.Heading("数据安全"),
    DeclPart.Paragraph(
        "开发者采取合理的技术与管理措施保护你的数据安全，但因 users.json 公开存储、本机存储未加密等客观限制，" +
            "无法保证数据绝对安全。你应自行承担在本机保管数据的风险。"
    ),
    DeclPart.Heading("你的权利"),
    DeclPart.Bullet("查询、更正：可在「账号」页查看与修改个人资料。"),
    DeclPart.Bullet("删除：退出登录会清除本机账号信息；反馈文件可在本机自行删除。"),
    DeclPart.Bullet("撤回授权：停止使用并卸载应用即视为撤回全部授权。")
)

private fun disclaimerParts() = listOf(
    DeclPart.Heading("音乐版权"),
    DeclPart.Paragraph(
        "本应用的音乐功能通过网易云音乐公开接口获取歌曲信息与播放地址，相关音频、歌词、封面等内容的版权" +
            "归原始权利人所有。本应用仅为播放工具，不存储、不分发音乐作品，不承担因用户使用音乐功能产生的版权责任。" +
            "如权利人认为权益受损，请联系相应音乐服务平台。本地音乐播放功能由用户自行提供音频文件，" +
            "用户须确保拥有合法的来源与使用权限。"
    ),
    DeclPart.Heading("翻译准确性"),
    DeclPart.Paragraph(
        "本应用的翻译结果由机器翻译引擎或本地语言包生成，可能存在语义偏差、专业术语错误或不通顺之处。" +
            "翻译结果仅供学习、参考与初步理解之用，不适用于法律、医学、工程等专业领域，" +
            "亦不作为任何正式文件的依据。因依赖翻译结果产生的任何后果，由用户自行承担。"
    ),
    DeclPart.Heading("第三方服务"),
    DeclPart.Paragraph(
        "本应用依赖 GitHub、jsDelivr、gh-proxy、网易云音乐、支付宝等第三方服务。" +
            "第三方服务的可用性、稳定性、安全性由其各自的服务条款约束，本应用不对第三方服务的中断、" +
            "数据泄露、内容合规性等承担责任。使用第三方服务即视为你同意其服务条款与隐私政策。"
    ),
    DeclPart.Heading("Beta 版本风险"),
    DeclPart.Paragraph(
        "Beta 版本为未正式发布的体验版本，可能存在功能缺陷、数据丢失、崩溃等不稳定情况。" +
            "用户应在充分知情的前提下自愿加入 Beta 先锋计划，并自行做好数据备份。" +
            "开发者不对因使用 Beta 版本造成的任何直接或间接损失承担责任。"
    ),
    DeclPart.Heading("付款信任流程"),
    DeclPart.Paragraph(
        "由于开发者暂未接入支付宝商户认证，会员激活采用「收款码扫码 + 用户回填交易号」的简化信任流程，" +
            "仅对交易号做格式校验，不与服务端对账。该流程存在被滥用的可能，开发者保留对异常激活账号进行核查与回收的权利。"
    )
)

private fun membershipParts() = listOf(
    DeclPart.Heading("会员等级与权益"),
    DeclPart.Bullet("FREE 免费版：基础翻译额度与工具，含广告。"),
    DeclPart.Bullet("PRO 专业版（¥29/年）：无限翻译、全部工具、无广告、标准离线包。"),
    DeclPart.Bullet("PREMIUM 高级版（¥99/年）：PRO 全部权益 + VIP 优先客服 + Beta 优先体验 + 全部离线包。"),
    DeclPart.Heading("付款与激活"),
    DeclPart.Paragraph(
        "会员通过支付宝收款码付款后回填交易号激活，有效期 1 年。" +
            "若激活时原会员尚未过期，新有效期将在原到期时间基础上叠加计算，避免用户损失剩余时长。"
    ),
    DeclPart.Heading("退款政策"),
    DeclPart.Paragraph(
        "会员为数字虚拟服务，激活后原则上不支持无理由退款。" +
            "如因开发者原因导致会员功能无法使用，可联系开发者协商处理。" +
            "因用户自身原因（误购、未先登录即付款等）导致的激活失败，开发者协助排查但不保证退款。"
    ),
    DeclPart.Heading("服务变更与终止"),
    DeclPart.Bullet("开发者有权调整会员权益与价格，已激活会员在有效期内不受价格调整影响。"),
    DeclPart.Bullet("本应用不提供自动续费，会员到期后默认降级为免费版，不影响账号内本地数据。"),
    DeclPart.Bullet("若用户严重违反本声明，开发者有权在不事先通知的情况下回收会员权益。")
)

private fun disputeParts() = listOf(
    DeclPart.Heading("适用法律"),
    DeclPart.Paragraph(
        "本声明的订立、生效、解释与争议解决均适用中华人民共和国法律（不含香港、澳门及台湾地区法律）。"
    ),
    DeclPart.Heading("协商优先"),
    DeclPart.Paragraph(
        "因本应用或本声明产生的任何争议，双方应首先本着友好互信的原则协商解决。" +
            "协商不成的，可通过消费者协会、互联网纠纷调解平台等第三方渠道调解。"
    ),
    DeclPart.Heading("管辖法院"),
    DeclPart.Paragraph(
        "协商或调解不成的，任何一方均有权向开发者所在地有管辖权的人民法院提起诉讼。"
    )
)

private fun contactParts() = listOf(
    DeclPart.Heading("开发者联系方式"),
    DeclPart.Bullet("GitHub 仓库：jiangtengqiao/liquid-glass"),
    DeclPart.Bullet("问题反馈：应用内「Beta 先锋」页的反馈入口，或通过 GitHub Issues 提交。"),
    DeclPart.Bullet("账号与会员问题：通过 GitHub Issues 留言，并附上用户名与问题描述。"),
    DeclPart.Heading("反馈响应"),
    DeclPart.Paragraph(
        "开发者为个人维护，可能无法即时响应所有反馈，但会尽力在合理时间内处理 Bug 报告与功能建议。" +
            "感谢你的理解与支持。"
    )
)
