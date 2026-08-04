package com.liquidglass.desktop.music

/**
 * 音乐播放器统一数据模型（桌面版，从安卓端移植）。
 * 网易云与本地音乐都映射成 [Song]，播放器只认 [Song]。
 */

data class Song(
    val id: String,                 // 网易云歌曲id 或 本地uri字符串
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val coverUrl: String = "",      // 网易云封面URL
    val coverUri: String = "",      // 本地封面 uri 字符串（本地音乐用）
    val source: Source = Source.NETEASE,
    val streamUrl: String = "",     // 可直接播放的 URL（网易云：song/url 接口返回；本地：文件 uri）
    val fee: Int = 0                // 0 免费可播 / 1 VIP / 4 数字专辑 / 8 试听
) {
    val isVipOnly: Boolean get() = fee == 1
    val isPlayable: Boolean get() = fee == 0 || fee == 8
}

enum class Source { NETEASE, LOCAL }

/** 搜索结果列表 */
data class SearchResult(val songs: List<Song>, val total: Int)

/** 一行 LRC 歌词 */
data class LyricLine(
    val timeMs: Long,
    val content: String,
    val translation: String = ""
)

/** 逐字歌词一个字 */
data class YrcChar(
    val startMs: Long,
    val durationMs: Long,
    val content: String
)

/** 一行逐字歌词 */
data class YrcLine(
    val startMs: Long,
    val durationMs: Long,
    val chars: List<YrcChar>,
    val translation: String = ""
)

/** 解析后的歌词：优先用逐字，回退逐行 */
data class Lyrics(
    val yrcLines: List<YrcLine> = emptyList(),   // 非空则用逐字渲染
    val lrcLines: List<LyricLine> = emptyList()  // 逐字为空时用
) {
    val hasYrc: Boolean get() = yrcLines.isNotEmpty()
}

/** 歌单 */
data class Playlist(
    val id: String,
    val name: String,
    val coverUrl: String,
    val trackCount: Int,
    val creator: String
)

/** 歌单分类（含子分类） */
data class PlaylistCategory(
    val id: String,
    val name: String,
    val subCategories: List<Pair<String, String>> = emptyList()
)

/** 网易云账号信息 */
data class UserAccount(
    val userId: Long,
    val nickname: String,
    val avatarUrl: String,
    val vipType: Int           // 0 普通 / 11+ VIP
) {
    val isVip: Boolean get() = vipType > 0
}

/** 二维码登录轮询状态 */
enum class QrLoginState { WAITING, SCANNED, CONFIRMED, EXPIRED, ERROR }

data class QrLoginResult(
    val state: QrLoginState,
    val cookies: String = ""   // CONFIRMED 时带 cookie
)

/**
 * 手机验证码登录流程状态机。
 *
 * 流程：IDLE → SENDING_CODE → CODE_SENT → LOGGING_IN → SUCCESS
 *        └────────────────→ SMS_FAILED ──┘     └────→ LOGIN_FAILED
 *
 * - CODE_SENT：短信已发送，等待用户输入验证码
 * - SMS_FAILED：发送短信失败（手机号格式错误 / 频率限制 / 号码未绑定网易云账号）
 * - LOGIN_FAILED：验证码错误或已过期
 * - BOUND_MISMATCH：该手机号与平台账号绑定手机号不一致
 */
enum class PhoneLoginState {
    IDLE,
    SENDING_CODE,
    CODE_SENT,
    LOGGING_IN,
    SUCCESS,
    SMS_FAILED,
    LOGIN_FAILED,
    BOUND_MISMATCH,
    ERROR
}

data class PhoneLoginResult(
    val state: PhoneLoginState,
    val message: String = ""    // 失败原因等用户可读消息
)
