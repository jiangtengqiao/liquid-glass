package com.liquidglass.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.ContactsContract
import android.Manifest
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.liquidglass.app.ResourceManager
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

enum class ResourcePackType { BASE, INTERACTION, PATCH_CORE, INIT_PREMIUM, INSTALL_PATCH, PRELOAD, PREPROCESS, ALL }

@Composable
fun AboutScreen(animTime: Float, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var updateStatus by remember { mutableStateOf("") }
    var updateProgress by remember { mutableStateOf(0f) }
    var isDownloading by remember { mutableStateOf(false) }

    // 法律与公告中心跳转
    var showLegalCenter by remember { mutableStateOf(false) }
    if (showLegalCenter) {
        LegalCenterScreen(animTime = animTime, onBack = { showLegalCenter = false })
        return
    }

    // 资源包状态（安装状态/大小为本地状态，下载完成后刷新）
    var resourceInstalled by remember { mutableStateOf(ResourceManager.isResourcesInstalled(context)) }
    var interactionInstalled by remember { mutableStateOf(ResourceManager.isInteractionInstalled(context)) }
    var patchCoreInstalled by remember { mutableStateOf(ResourceManager.isPatchCoreInstalled(context)) }
    var initPremiumInstalled by remember { mutableStateOf(ResourceManager.isInitPremiumInstalled(context)) }
    var installPatchInstalled by remember { mutableStateOf(ResourceManager.isInstallPatchInstalled(context)) }
    var preloadInstalled by remember { mutableStateOf(ResourceManager.isPreloadInstalled(context)) }
    var preprocessInstalled by remember { mutableStateOf(ResourceManager.isPreprocessInstalled(context)) }
    var resourceSize by remember { mutableStateOf(ResourceManager.getTotalResourceSize(context)) }
    var interactionSize by remember { mutableStateOf(ResourceManager.getTotalInteractionSize(context)) }
    var patchCoreSize by remember { mutableStateOf(ResourceManager.getPatchCoreSize(context)) }
    var initPremiumSize by remember { mutableStateOf(ResourceManager.getInitPremiumSize(context)) }
    var installPatchSize by remember { mutableStateOf(ResourceManager.getInstallPatchSize(context)) }
    var preloadSize by remember { mutableStateOf(ResourceManager.getPreloadSize(context)) }
    var preprocessSize by remember { mutableStateOf(ResourceManager.getPreprocessSize(context)) }

    // 下载进度/状态/速度改为观察 ResourceManager 的全局 StateFlow：
    // 这样即使用户离开 AboutScreen 或切换功能，下载协程仍在 globalDownloadScope 中继续，
    // 重新进入页面时也能恢复显示当前进度（解决下载被取消与进度丢失问题）。
    val resourceDownloadProgress by ResourceManager.downloadProgress.collectAsState()
    val resourceDownloadStatus by ResourceManager.downloadStatus.collectAsState()
    val resourceDownloadSpeed by ResourceManager.downloadSpeed.collectAsState()
    val isDownloadingResource by ResourceManager.isDownloading.collectAsState()
    // currentDownloadingPack 为 ResourcePackType.name 字符串或 null（ALL 下载时）
    val downloadingPackName by ResourceManager.currentDownloadingPack.collectAsState()
    val downloadingPack = downloadingPackName?.let { runCatching { ResourcePackType.valueOf(it) }.getOrNull() }

    // 文件详情刷新触发器：下载完成/重置时自增，驱动卡片重新读取磁盘文件列表
    var fileDetailRefreshTrigger by remember { mutableStateOf(0) }

    val changelog = listOf(
        ChangelogVersion("2.9.0", "2026.07.31", listOf(
            "真实液态玻璃折射库集成：引入 GitHub 高星库 Kyant0/AndroidLiquidGlass（io.github.kyant0:backdrop:2.0.0），实现真实光学 lens 折射 + chromaticAberration 色散 + blur 模糊，替代原自制 glassSurface 纯装饰性 8 层 drawBehind 图层。新增 LiquidGlassScaffold 统一屏幕容器，全 23 个屏幕自动启用真实折射玻璃效果（Apple Liquid Glass 级别）",
            "指南针终极修复(第五次根因)：缺少 remapCoordinateSystem 适配竖直持握坐标系——手机竖直持握时设备 Z 轴朝向用户，直接用 getOrientation 得到的方位角完全错误。修复：remapCoordinateSystem(R, AXIS_X, AXIS_Z, Rremapped) 将设备坐标系转为屏幕竖直坐标系；引入低通滤波(alpha=0.15)平滑方位角抖动；加速度计和磁力计数据也应用低通滤波(alpha=0.8)去高频噪声",
            "音乐歌词字体扩展至9种：原仅4种且 Cursive 在多数国产 ROM 回退为默认字体(视觉无变化)。改用 Typeface.create(familyName) 显式指定系统字体族(窄体/细体/超细/中黑/衬线/衬线等宽/等宽/手写)，全设备可区分。修复锁屏歌词未应用用户选择字体和主题色的 Bug",
            "主题精简至6套精品深色：移除浅色主题 elegant_white 和 ios_liquid_glass(浅色渲染不佳)及纯黑主题 ios_midnight_glass，保留午夜深空/深海蓝调/翡翠森林/落日余晖/玫瑰金粉/星河紫罗兰/iOS水滴琉光共6套精品深色主题",
            "下载系统单源优先策略全盘重写：原实现每个文件用4个镜像源依次尝试，镜像切换时进度归零重启导致进度条反复横跳。修复：主镜像 cors.isteed.cc(实测国内最稳定且不截断)，仅连接完全失败时才切 fallback；同镜像内 Range 续传不切镜像(切镜像会导致 Content-Length 不一致+进度归零)；分块下载显示真实文件名(resources.part00 等)替代原'块N'序号",
            "更新日志超级大版折叠：1.x 版本融合为超级大版1，2.x 版本融合为超级大版2，外层超级大版默认折叠，展开后显示内部大版本组(如2.8/2.6/2.4)，再展开显示小版本"
        )),
        ChangelogVersion("2.8.7", "2026.07.31", listOf(
            "指南针终极修复(第四次)：角度平滑改用Animatable+最短角路径插值，解决穿越正北(359°→1°)时animateFloatAsState走359→180→1长路径导致指针反向旋转358°乱转的Bug。now用旋转矢量最近上报时间戳判断是否活跃(超1.5s走accel+mag兜底)+getRotationMatrixFromVector的try-catch(部分设备values长度异常)",
            "动画无缝循环终极修复：用真实经过时间(withFrameNanos)驱动FluidBackground，时间单调递增永不重启，所有sin/cos自然连续。原infiniteTransition+Restart即使targetValue=20π也只能保证0.1整数倍频率无缝，而0.17/0.25等非谐波频率Restart时仍跳变。移除FluidBackground中%2π取模(同样导致非谐波频率跳变)",
            "多线程下载进度条反复横跳修复：原updateChunkProgress用toMutableList+整体赋值在4路并发下有写-写竞态(线程A读→B读→A写→B写覆盖A→进度回退)。修复：AtomicReference+CAS保证线程安全；downloaded字段max(已有,新值)只增不减防镜像切换回退；200ms节流减少UI重组闪烁"
        )),
        ChangelogVersion("2.8.6", "2026.07.31", listOf(
            "指南针终极修复：找到真正根因——TYPE_ROTATION_VECTOR传感器存在但不报告数据(国产ROM常见驱动问题)时，磁力计根本没注册，导致没有任何fallback可算方位角，指南针永远卡死。修复：磁力计始终注册(不再仅当directionSensor==null才注册)；accel+mag兜底逻辑改为只要还没收到方位角就尝试计算(不再仅当directionSensor==null才计算)。这样即使旋转矢量传感器失效，accel+mag组合也能正常算出方位角",
            "通知美化：用自定义RemoteViews布局替代简陋纯文本通知。渐变深色背景+青色色条+标签徽章(音乐/壁纸/工具/更新)+时间显示+点击查看指引，视觉品质大幅提升",
            "通知可靠性：首条立即推送(0延迟)+IMPORTANCE_HIGH弹横幅+频道ID升级v2+PRIORITY_HIGH+VISIBILITY_PUBLIC+CATEGORY_RECOMMENDATION"
        )),
        ChangelogVersion("2.8.5", "2026.07.31", listOf(
            "指南针核心Bug修复：hasReceivedSensorData被加速度计事件误设为true，导致方向传感器不报告数据时超时检测永不触发，指南针永远卡在'正在等待传感器数据'。改为hasReceivedAzimuth仅在方位角成功计算时设true，超时检测现在能正确触发",
            "指南针重试机制：超时/等待状态显示'点击重试'按钮，用户可点击重新注册传感器而无需退出重进",
            "通知推送修复：首条推荐通知改为立即推送(0延迟)，原2s延迟在协程调度异常时导致首条通知丢失；推荐频道升级为IMPORTANCE_HIGH+PRIORITY_HIGH弹出横幅通知(原DEFAULT不弹横幅用户看不到)；频道ID改为v2避免旧频道importance不可修改",
            "通知点击黑屏根治：AppRouter增加@Volatile backupRoute备份，防止冷启动+加载页+权限门槛多层异步下Compose snapshot丢失pendingRoute；HomeScreen增加LaunchedEffect(Unit)首次组合兜底消费路由",
            "LyricsSettings崩溃修复：updateX函数访问lateinit prefs未加初始化守卫，从通知直接跳音乐页时LyricsSettings未初始化会崩溃；所有updateX加if(!::prefs.isInitialized)守卫，MainActivity.onCreate预初始化LyricsSettings",
            "倒计时漂移修复：原delay(1000);remainingSeconds--每次重组重启delay，实际间隔>1000ms导致长时间倒计时不准；改为SystemClock.elapsedRealtime()真实时间戳计算，delay(200)仅控制刷新频率",
            "秒表计次O(n²)性能修复：原每行计次都从头重新求和计算累计时间，计次多时卡顿；改为前缀和数组预计算O(n)",
            "媒体通知FLAG_ACTIVITY_NEW_TASK修复：NotificationHelper媒体通知和MusicService sessionActivity缺少FLAG_ACTIVITY_NEW_TASK，从Service上下文启动Activity不加此flag可能导致通知点击无法打开App",
            "手电筒修复：亮度滑块无权限时反复拉起系统设置(改为仅提示一次)；退出不还原系统亮度/超时(改为DisposableEffect还原)；切换屏幕工具页签不关闪光灯(改为自动关闭)"
        )),
        ChangelogVersion("2.8.4", "2026.07.31", listOf(
            "通知推送修复：NotificationBootstrap改用LaunchedEffect自身协程（原scope.launch嵌套导致协程泄漏/取消异常）；首条推送10s→2s，间隔8min→3min；bootstrapped改remember避免进程被杀后不重推",
            "通知点击黑屏修复：handleNotificationIntent移到setContent之前执行，确保AppRouter.pendingRoute在首次组合前就设置好；增加Screen枚举名校验防止无效路由",
            "指南针修复增强：新增TYPE_GEOMAGNETIC_ROTATION_VECTOR作为第二优先级（软件融合无需陀螺仪，覆盖更多设备）；增加3秒超时检测（传感器注册成功但不报告数据时提示而非静默等待）；检查registerListener返回值，注册失败立即提示",
            "资源包下载多进度条：分块下载时每个分块独立进度条展示（块1/块2/块3/块4各自推进），不再单个进度条瞎跳；显示已完成块数/总块数；缓存块标记已缓存，失败块标记失败"
        )),
        ChangelogVersion("2.8.3", "2026.07.31", listOf(
            "修复APK打包问题：v2.8.2构建时Gradle daemon在打包阶段崩溃导致编译缓存损坏，后续构建误判任务up-to-date跳过重新编译，APK实际未包含v2.8.1新增的音乐歌词设置页/秒表修复/指南针修复/通知跳转等功能；本次执行clean全量重新编译打包，确保所有功能代码正确编入APK",
            "音乐歌词设置页(7项)：字体更换(默认/衬线/等宽/手写)、字幕主题色更换(液态青/活力橙/樱粉/翡翠绿/明黄/纯白6种鲜明色)、字幕时间偏移自定义(±3s提前/延后)、字号调节、行间距调节、翻译显示开关、背景透明度，全部持久化",
            "秒表修复：基于SystemClock.elapsedRealtime()真实时间戳计算，解决1秒顶真实2秒问题",
            "指南针修复：优先TYPE_ROTATION_VECTOR融合传感器，传感器缺失/精度低时显示提示",
            "通知修复：激活条件bug导致通知从未出现；通知点击直达对应功能页(AppRouter)而非首页；推送频次提升",
            "点击通知进App偶发闪退修复：MediaController release时Service not registered异常全局兜底"
        )),
        ChangelogVersion("2.8.2", "2026.07.31", listOf(
            "修复点击通知进App偶发闪退：MediaController 连接 MusicService 失败/超时后内部 release 调 unbindService 抛 'Service not registered' IllegalArgumentException；该异常从 main Handler callback 抛出无法被业务代码捕获，现全局崩溃捕获器识别并吞掉此良性异常(controller已在释放中，吞掉无副作用)",
            "MediaController 连接失败后支持重连：原 init 防重逻辑 controllerFuture!=null 即跳过，连接失败后永远卡死无法重连；现区分'连接中'与'连接已失败'，失败后清理残留重新创建",
            "release() 防御性 try-catch：Media3 在 service 未注册成功时 releaseFuture 会抛异常，现捕获忽略"
        )),
        ChangelogVersion("2.8.1", "2026.07.31", listOf(
            "修复秒表走太快(1秒顶真实2秒)：原 delay(17);elapsedMs+=17 忽略调度/重组开销导致累计漂移，现基于 SystemClock.elapsedRealtime() 真实时间戳计算，finally 固化暂停累计",
            "修复指南针无法使用：优先 TYPE_ROTATION_VECTOR 融合传感器(单传感器即可算方位角，更稳定)，fallback 到 accel+mag；传感器缺失/磁力计精度低时显示明确提示而非静默失败",
            "修复通知/广告从未出现：NotificationBootstrap 激活条件 showLoading&&permissionsGranted 永远为 false(加载页权限未授予/权限授予后加载已结束)，改为 !showLoading&&permissionsGranted",
            "通知推送频次提升：首条 15s→10s，间隔 15min→8min",
            "通知点击直达对应功能页：新建 AppRouter 全局路由单例，handleNotificationIntent 映射 action/target→Screen，36条推荐通知各携带具体目标功能页(音乐/壁纸/指南针/计算器等)，不再只跳首页",
            "音乐歌词设置页：字体更换(默认/衬线/等宽/手写)、字幕主题色更换(6种鲜明色)、字幕时间偏移自定义(±3s 提前/延后)、字号调节、行间距调节、翻译显示开关、背景透明度(共7项，持久化)"
        )),
        ChangelogVersion("2.8.0", "2026.07.31", listOf(
            "修复单位换算点击单位闪退（彻底方案）：Popup 改 Dialog 独立窗口，脱离 verticalScroll 测量约束；onDismiss 用专用关闭回调替代 toggle，杜绝重组间隙崩溃",
            "权限申请去\"牛头不对马嘴\"：移除 RECORD_AUDIO(无录音功能)/READ_CONTACTS/WRITE_CONTACTS(通讯录仅 Intent 跳系统界面不需权限)三个摆设权限；修正定位描述为指南针/时钟",
            "修复锁屏不弹出(Android 11)：SCREEN_OFF 广播加 WakeLock 强制点亮 + Manifest showWhenLocked/turnScreenOn 属性 + RECEIVER_NOT_EXPORTED 标志",
            "修复更新检测拉取不到最新版本：新增 GitHub 镜像源(gh-proxy/ghfast)，30 分钟节流防频繁请求，App 回前台 ON_RESUME 重新检查",
            "音乐功能拆分为独立入口卡片：搜索/队列/音质/睡眠定时器四大入口横向滚动",
            "音乐搜索分页加载 + 右侧字母导航条(按艺术家首字母分组) + 右下角返回置顶按钮",
            "音质调节(标准/极高/无损) + 睡眠定时器",
            "资源包下载重构：实时文件展示+折叠展开(默认5个)+断点续传(.part.meta 记录URL校验)",
            "取消下载清空 UI 残留状态，避免下次进入看到残留进度"
        )),
        ChangelogVersion("2.6.8", "2026.07.30", listOf(
            "修复点击播放误跳锁屏：移除 FullScreenIntent（屏幕亮着但设备锁屏时会误触发），锁屏跳转完全由 SCREEN_OFF 监听处理",
            "修复滑动解锁后后台被杀：onDismiss 改为启动 MainActivity 回到前台再 finish，避免无前台 Activity 被系统回收",
            "更新日志分级重构：大版本号(2.6.x/2.4.x/2.3.x/1.x)折叠小版本号，默认全部折叠，展开大版本才看到内部小版本列表",
            "补写 v2.6.5-v2.6.7 迭代日志"
        )),
        ChangelogVersion("2.6.7", "2026.07.30", listOf(
            "修复锁屏不弹出：SCREEN_OFF 时直接 startActivity（前台服务豁免 Android 10+ 后台启动限制），比 FullScreenIntent 更可靠",
            "通知审美提升：专辑封面做背景+渐变遮罩+移除丑三角形+纯文字颜色大小区分+阴影提升可读性",
            "进度条可拖动：ProgressBar→SeekBar(max=1000)+青色渐变+圆形thumb，拖动通过 ACTION_SEEK 路由到 seekTo",
            "单位换算 bug 修复：交换不丢输入/货币刷新防崩(optDouble)/切换类别重置输入/移除非空断言/HTTP状态码校验/NaN历史记录过滤"
        )),
        ChangelogVersion("2.6.6", "2026.07.30", listOf(
            "修复锁屏被吞：改用 FullScreenIntent + USE_FULL_SCREEN_INTENT 权限 + 监听 ACTION_SCREEN_OFF",
            "状态栏歌词与播放控制合并为一个通知：RemoteViews 自定义媒体通知，含 3 行歌词(当前行青色高亮放大)+播放按钮+进度条",
            "100ms 高频刷新无延迟：仅在歌词行签名变化时才 notify，避免被系统节流丢帧",
            "锁屏播放器歌词增强：当前行青色 22sp Bold，邻近行白色 0.6 alpha，远行 0.3 alpha"
        )),
        ChangelogVersion("2.6.5", "2026.07.30", listOf(
            "状态栏歌词通知升级为多行实时显示：上一行(淡)+当前行(高亮)+下一行(淡)三行同时展示，BigTextStyle展开",
            "新增全屏锁屏播放器(LockScreenActivity)：showWhenLocked+turnScreenOn直接覆盖系统锁屏，专辑封面模糊全屏背景占满屏幕，深色渐变遮罩保证歌词可读",
            "锁屏多行实时歌词：LazyColumn渲染全部歌词行，当前行高亮放大加粗，自动滚动跟随播放进度居中",
            "锁屏播放控制：上一首/播放暂停/下一首按钮+播放进度条",
            "手势滑动解锁：左右滑动超过600px阈值后渐隐退出返回App界面，未达阈值回弹；底部动画箭头指引(左右往复+透明度呼吸)提示'左右滑动解锁返回应用'",
            "MusicService监听ACTION_SCREEN_OFF：息屏且正在播放时自动启动锁屏Activity，onDestroy注销监听",
            "computeLyricLines返回上/当前/下三行歌词供状态栏与锁屏共用"
        )),
        ChangelogVersion("2.6.4", "2026.07.30", listOf(
            "修复断点续传bug：取消下载不再删除.part/chunk缓存，下次下载自动接着上次未完成部分继续；分块下载(downloadChunked)复用已完整chunk跳过下载，不完整chunk用HTTP Range续传；downloadChunkWithMirrors失败时保留已下载字节供下个镜像续传",
            "新增clearDownloadCache()方法供主动清除缓存（取消≠清除缓存）",
            "performDownload失败时保留.part缓存不再误删，仅清理半成品解压目录",
            "状态栏实时歌词通知：MusicService每500ms根据播放进度计算当前歌词行，独立通知(ID=1004)在状态栏顶部滚动显示歌词+歌曲信息，锁屏可见，播放期间常驻",
            "歌词缓存机制：PlaybackState新增currentLyric字段，UI加载歌词后调用setLyrics()缓存，MusicService通过computeCurrentLyric()实时计算（优先逐字yrc回退逐行lrc）",
            "锁屏封面覆盖：Media3 MediaSession根据artworkUri自动加载封面显示在媒体通知与锁屏（酷狗式全覆盖）",
            "暂停时取消歌词通知避免常驻干扰，Media3媒体通知仍保留显示暂停状态"
        )),
        ChangelogVersion("2.6.3", "2026.07.30", listOf(
            "权限门槛：更新后强制申请所有运行时权限(通知/音频/定位/麦克风/通讯录/拨号/精确闹钟)，未全部授予无法进入应用，提供一键授权按钮与跳转系统设置入口",
            "广告文案池扩充至36条：覆盖音乐/工具/资源包/壁纸/白噪音/生活/主题/更新等全场景，不再单调",
            "广告频率提高：从每90分钟改为每15分钟推送一条，进入主页15秒后推首条",
            "广告不重复轮播：洗牌队列按序弹出，一轮内每条只出现一次，全部播完才重新洗牌",
            "后台媒体通知修复：权限门槛强制POST_NOTIFICATIONS授予，Media3媒体通知(封面/标题/艺人/控制按钮)在切后台时正常显示在通知栏顶部与锁屏"
        )),
        ChangelogVersion("2.6.2", "2026.07.30", listOf(
            "修复关于页下载卡片UI穿模：标题行改用weight(fill=false)避免徽章被挤压截断，标题/描述/状态文本统一加maxLines+Ellipsis",
            "修复全局下载控制台状态行固定高度导致长文本被截：改为自适应高度允许2行显示",
            "音乐后台通知栏/锁屏控件：MusicService增强onTaskRemoved处理，确认Media3 MediaSession自动生成媒体通知(封面/标题/艺人/上一首/暂停/下一首)，切后台或最小化后通知栏仍可控制播放",
            "拔耳机自动暂停：setHandleAudioBecomingNoisy(true)",
            "定时推送通知广告：NotificationHelper新增15条推荐文案池(音乐/工具/资源包/壁纸等)，进入主页25秒后推首条，之后每90分钟随机推一条",
            "通知点击跳转优化：FLAG_CLEAR_TOP确保返回已存在的主Activity实例而非新建"
        )),
        ChangelogVersion("2.6.1", "2026.07.30", listOf(
            "修复单位换算点击单位闪退：自定义下拉在滚动父容器触发无穷高度测量崩溃，改用Material3 DropdownMenu彻底解决",
            "音乐板块多元化扩充：新增私人FM、新歌速递、相似歌曲、DJ电台、推荐MV、新碟上架六大发现区块",
            "播放器控制台状态栏：迷你播放条升级为控制台，含动画均衡器(播放跳动/缓冲脉冲/暂停静止)、流动marquee歌词、实时播放状态文本",
            "下载实时文件显示：解压过程逐文件推送已落盘文件列表，下到哪个文件就显示哪个文件，而非下载完成后一股脑显示",
            "全局下载控制台：资源包管理页置顶显示总进度/状态/速度/实时文件列表，可展开收起，跨页面持久化",
            "暂停/恢复下载：资源包下载支持暂停(保留.part缓存)与恢复(断点续传)，HTTP Range请求接着已下载字节继续",
            "取消下载：彻底丢弃缓存并清理.part临时文件",
            "断点续传缓存：下载未完成被取消后，下次下载自动从上次断点继续，不重复下载已落盘字节",
            "通知系统：Android 13+运行时申请POST_NOTIFICATIONS权限，初始化更新提醒/内容推荐/下载进度三通知频道",
            "更新通知：发现新版本时同步推送系统通知，关掉弹窗后通知栏仍可看到更新提醒",
            "内容推荐通知：进入主页25秒后推送今日推荐(私人FM等)，点击直达音乐播放器",
            "7层下载流程规整：阶段化进度权重分配，每阶段状态文本清晰标注，全局StateFlow跨页面状态持久化",
            "液态玻璃水滴状流态：控制台/均衡器/进度条全面采用液态玻璃渐变与水滴融合质感"
        )),
        ChangelogVersion("2.4.4", "2026.07.30", listOf(
            "全新4层强制层级资源包系统：基础资源包→交互外观包→核心功能补丁包→高级体验初始化包，未下对应补丁包则对应功能不可用",
            "核心功能补丁包(patch-core)：未下则计算器/单位换算/二维码/颜色选择器/密码生成器不可用，首页显示锁标记",
            "高级体验初始化包(init-premium)：未下则音乐/日历/待办/笔记/健康计算/倒计时/指南针不可用，首页显示锁标记",
            "网易云新增手机验证码登录方式：对接 /weapi/sms/captcha/sent 与 /weapi/login/cellphone 接口，支持手机号+短信验证码登录平台账号",
            "登录页新增登录方式Tab切换：扫码登录 / 手机验证码登录，手机登录自动预填上次绑定手机号",
            "首页功能门禁：缺包工具显示锁标记，点击弹出补丁包下载提示与一键下载入口",
            "关于页资源管理升级为4层补丁包独立卡片，每张卡片显示功能解锁范围与下载进度"
        )),
        ChangelogVersion("2.4.3", "2026.07.30", listOf(
            "修复网易云扫码授权完就过期死循环：weapiPost移除手动Cookie header(与OkHttp cookieJar冲突)，fetchAccount失败时检查MUSIC_U cookie兜底存最小用户信息，pollQrStatus ERROR重试2次而非直接显示过期",
            "资源包下载速度优化：buffer从8KB增大到256KB减少系统调用，分块下载改为4路并发充分利用带宽，解决下载只有几十KB/s问题",
            "交互外观包内容充实：从92KB空包替换为13KB/35文件(15套主题+6套粒子预设+4套玻璃效果+4套音效+5套流体预设)",
            "5篇法律协议各扩充约4000字：新增数据安全/跨境传输/Cookie详情/数据泄露应急/账号安全/虚拟物品/仲裁条款/不可抗力/SDK数据收集/家长监护等章节"
        )),
        ChangelogVersion("2.4.2", "2026.07.30", listOf(
            "修复检查更新查找不到新版：jsdelivr CDN 对 gh 仓库缓存最长12h+且 ?t= 时间戳无效，曾导致拿到2.2.0旧缓存(versionCode=5)而误判为已是最新",
            "检查更新源顺序调整：GitHub raw 优先(实时不缓存)，jsdelivr降为兜底",
            "新增脏缓存检测：若源返回versionCode远小于当前版本，判定为CDN旧缓存并切换源",
            "请求头加 Cache-Control: no-cache"
        )),
        ChangelogVersion("2.4.1", "2026.07.30", listOf(
            "修复网易云扫码二维码加载不出来：NetEaseApiClient 改用 nullable context + ensureContext 自愈，消除 LaunchedEffect 竞态导致的 UninitializedPropertyAccessException",
            "新增 ContextProvider 全局上下文，MainActivity 启动时同步预初始化 NetEaseApiClient，彻底消除扫码首请求竞态",
            "QrLoginView 重写：生成 key 失败自动重试3次、网络失败明确提示+重试按钮、不再误显示「二维码已过期」",
            "修复退出登录后 UI 不刷新：登录态提升为 Compose state + loginTick 计数器，顶部栏 VIP 徽章与网易云 Tab 登录/退出即时重组",
            "修复第三方音乐App检测是否安装错误：AndroidManifest 新增 <queries> 声明解决 Android 11+ 包可见性限制，酷狗/汽水/QQ音乐检测现在正确返回",
            "第三方App跳转加固：双重检测(getPackageInfo + getLaunchIntentForPackage) + launchIntent 失败回退网页 + try-catch 兜底",
            "退出登录同步清空播放队列，避免旧账号歌曲继续播放"
        )),
        ChangelogVersion("2.4.0", "2026.07.30", listOf(
            "全新音乐播放器：网易云音乐扫码登录+本地音乐扫描+酷狗/汽水/QQ音乐跳转，Media3后台播放+通知栏+锁屏控件",
            "音乐播放器进阶：播放队列管理(移动/删除/清空/跳播)、循环模式与随机播放、搜索历史+热搜词、逐字歌词与模糊渐变动效",
            "二维码/条形码重写：引入ZXing库替代手写实现，修复URL特殊字符与中文乱码、Code128码表越界崩溃",
            "单位换算修复：货币汇率改为实时API拉取(open.er-api.com)，支持缓存与默认兜底，告别硬编码陈旧汇率",
            "壁纸画廊修复：使用Compose Canvas重渲染预览，所见即所得，预览与实际设置完全一致",
            "待办清单修复：新增TodoStore本地持久化，退出即丢问题彻底解决",
            "日历日程升级：新增事件提醒功能(AlarmManager精确闹钟+通知)，支持5种预设与自定义提醒时间",
            "颜色选择器：「取色」Tab改名为「色带取色」，文案与功能对齐",
            "新增法律与公告中心：隐私政策/用户服务协议/免责声明/第三方与开源组件说明/儿童信息保护与社区行为公告 共5篇长文协议",
            "移除声波可视化模块(用户反馈花架子无用)"
        )),
        ChangelogVersion("2.3.5", "2026.07.30", listOf(
            "修复镜像顺序：ghproxy.net对大文件截断(下载仅670MB根因)，改用cors.isteed.cc与gh-proxy.com优先(实测可完整传输90MB分块)",
            "APK下载镜像同步调整，确保检查更新下载APK也能完整"
        )),
        ChangelogVersion("2.3.4", "2026.07.30", listOf(
            "修复版本号显示错误：关于页版本号现从PackageManager读取，不再硬编码(此前显示2.3.0但检查更新显示2.3.3)",
            "修复资源包下载后仅670MB：ghproxy截断大文件，现改用分块下载(每块90MB)+逐块字节校验+manifest驱动，确保1.5GB完整到达",
            "修复主题切换后文字与背景对比度不足：glassSurface现主题感知，浅色主题用深色玻璃着色，深色主题用白色",
            "新增iOS液态玻璃系列主题：iOS液态玻璃/iOS水滴琉光/iOS午夜玻璃，为苹果端开发预热",
            "新增资源补丁系统：资源初始化补丁(分块)+APK全量补丁(检查更新)，version.json驱动"
        )),
        ChangelogVersion("2.3.3", "2026.07.30", listOf(
            "修复检查更新下载APK报\"failed to connect to github after 20000ms\"：APK下载走镜像(ghproxy/cors/gh-proxy)，GitHub直连降为兜底",
            "修复检查更新永远提示有新版：versionCode不再硬编码，改从PackageManager读取真实版本号",
            "version.json检查走jsdelivr镜像优先（国内可达），超时缩短到6s",
            "APK下载连接超时20s→8s，镜像间快速failover"
        )),
        ChangelogVersion("2.3.2", "2026.07.30", listOf(
            "修复下载报\"failed to connect to github after 20000ms\"：镜像源优先（ghproxy/cors/gh-proxy），GitHub直连降为兜底",
            "连接超时从20s缩短到8s，镜像不通时快速切换下一个源",
            "镜像切换不再等待2秒，整批重试才等待（快速failover）"
        )),
        ChangelogVersion("2.3.1", "2026.07.30", listOf(
            "彻底修复「资源包下载后未应用」：新增 FluidAssetLoader，下载的流体纹理与粒子缓存现在真正驱动渲染",
            "fluid_textures/noise_atlas_*.png 现被 FluidBackground 采样，调制色块位置与半径",
            "fluid_textures/particle_cache_*.bin 现被内存映射为预烘焙 Navier-Stokes 速度场，驱动粒子轨迹",
            "修复音频文件命名不匹配：资源包现使用语义名(rain.wav/ocean.wav 等)匹配 SoundType，10 段全部生效",
            "资源包真正达到约 1.5GB：30 张壁纸 + 10 段白噪音 + 主题 + 23 个流场快照，全部被应用",
            "壁纸设置权限与 OOM 修复：SET_WALLPAPER 权限 + Bitmap 采样解码"
        )),
        ChangelogVersion("2.3.0", "2026.07.30", listOf(
            "全新主题系统：超级无敌淡雅白/深海蓝调/翡翠森林/落日余晖/玫瑰金粉/星河紫罗兰等7套内置主题",
            "修复资源包下载后未应用：音频文件现在真正被播放器使用（MediaPlayer优先，PCM合成回退）",
            "修复壁纸目录不匹配：下载的壁纸现在真正出现在画廊并可设置",
            "交互资源包真正生效：包含可解锁的额外主题（霓虹赛博/薄荷清新/薰衣草之梦等）",
            "壁纸设置修复：SET_WALLPAPER权限+Bitmap采样解码，解决OOM与权限报错",
            "资源包扩充至约1.5GB：30张高清壁纸+10段白噪音+主题配置+流体物理纹理",
            "FluidBackground流体背景全面适配主题色",
            "关于页新增主题切换选择器"
        )),
        ChangelogVersion("2.2.2", "2026.07.30", listOf(
            "修复检查更新因CDN缓存显示旧版本问题",
            "version.json请求加时间戳绕过缓存",
            "GitHub raw优先（实时不缓存），jsdelivr作为备份"
        )),
        ChangelogVersion("2.2.1", "2026.07.30", listOf(
            "热修复：资源包下载报\"download already in progress\"错误",
            "拆分下载锁为基础/交互独立锁，互不阻塞",
            "跳过启动加载页时自动重置下载状态"
        )),
        ChangelogVersion("2.2.0", "2026.07.30", listOf(
            "修复计算器科学模式布局穿模问题",
            "修复检查更新报错：增加jsdelivr CDN多源回退",
            "修复资源包下载失败：增加ghproxy镜像+增大超时",
            "资源包下载支持多源切换：GitHub直链+代理镜像",
            "检查更新使用versionCode整数比较，更可靠",
            "壁纸画廊新增已下载分类，下载后立即生效",
            "时钟/首页/加载页排版穿模全面修复",
            "User-Agent改为标准浏览器格式，提升兼容性"
        )),
        ChangelogVersion("2.1.0", "2026.07.29", listOf(
            "全新品牌：灵工坊·智能工具箱",
            "iOS级液态玻璃UI：8层渲染管线+物理引擎",
            "物理引擎：弹簧物理+Navier-Stokes流体场+Metaball水滴融合",
            "两阶段资源加载：基础资源包→交互资源包",
            "启动加载页：双阶段进度指示器+预览显示",
            "全新应用图标：六边形工坊设计",
            "APK体积优化：从765MB缩减至11MB轻量级",
            "冷启动强制加载：优雅入场退场动画",
            "关于页资源管理器：查看和下载资源包",
            "更新日志折叠展开式显示"
        )),
        ChangelogVersion("2.0.0", "2026.07.29", listOf(
            "新增14个实用工具模块",
            "倒计时秒表·记事本·单位换算",
            "密码生成器·BMI健康计算",
            "壁纸画廊·白噪音播放器",
            "文件管理·二维码生成器",
            "涂鸦画板·指南针水平仪",
            "手电筒·颜色选择器·日历日程",
            "液态玻璃UI效果全面增强"
        )),
        ChangelogVersion("1.1.0", "2026.07.28", listOf(
            "天气秒开：IP定位先行，GPS后台更新",
            "城市搜索：输入任意城市名查天气",
            "世界时钟：10个国际城市实时时钟",
            "液态玻璃UI效果增强：更通透质感",
            "更新机制优化：稳定URL自动更新"
        )),
        ChangelogVersion("1.0.0", "2026.07.28", listOf(
            "液态玻璃UI通透质感全面升级",
            "时钟 + 实时天气 + 7日预报",
            "科学计算器含记忆功能",
            "音乐可视化3种频谱模式",
            "待办清单玻璃滑动管理",
            "定位权限申请 + IP地理定位",
            "内嵌更新检查与下载",
            "创作者信息 + 一键联系"
        ))
    )

    fun refreshResourceStatus() {
        resourceInstalled = ResourceManager.isResourcesInstalled(context)
        interactionInstalled = ResourceManager.isInteractionInstalled(context)
        patchCoreInstalled = ResourceManager.isPatchCoreInstalled(context)
        initPremiumInstalled = ResourceManager.isInitPremiumInstalled(context)
        installPatchInstalled = ResourceManager.isInstallPatchInstalled(context)
        preloadInstalled = ResourceManager.isPreloadInstalled(context)
        preprocessInstalled = ResourceManager.isPreprocessInstalled(context)
        resourceSize = ResourceManager.getTotalResourceSize(context)
        interactionSize = ResourceManager.getTotalInteractionSize(context)
        patchCoreSize = ResourceManager.getPatchCoreSize(context)
        initPremiumSize = ResourceManager.getInitPremiumSize(context)
        installPatchSize = ResourceManager.getInstallPatchSize(context)
        preloadSize = ResourceManager.getPreloadSize(context)
        preprocessSize = ResourceManager.getPreprocessSize(context)
        // 触发文件详情列表刷新（下载完成后磁盘文件已变化）
        fileDetailRefreshTrigger += 1
    }

    /**
     * 通用单包下载：复用同一份进度/速度统计逻辑，按 [packType] 派发到 ResourceManager 对应方法。
     * 进度/状态/速度统一节流到 500ms 一次，避免高频刷新导致文字跳动（修复显示抖动）。
     */
    suspend fun downloadOnePack(
        packType: ResourcePackType,
        force: Boolean,
        weightStart: Float,
        weightEnd: Float,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
        onSpeed: (String) -> Unit
    ): Result<Boolean> {
        var lastBytes = 0L
        var lastUpdate = 0L
        val progressCb: (Long, Long, String) -> Unit = { dl, total, status ->
            val now = System.currentTimeMillis()
            if (total <= 0) {
                // 阶段切换/解压/镜像切换等非下载状态：立即更新状态文本（不涉及进度百分比）
                onStatus(status)
            } else if (now - lastUpdate >= 500) {
                // 下载中：进度/状态/速度统一 500ms 节流，避免文字高频跳动
                onStatus(status)
                onProgress(weightStart + (dl.toFloat() / total.toFloat()) * (weightEnd - weightStart))
                if (lastUpdate > 0) {
                    val elapsed = now - lastUpdate
                    val speed = (dl - lastBytes).toFloat() / (elapsed / 1000f)
                    onSpeed(formatSpeedStr(speed))
                }
                lastBytes = dl
                lastUpdate = now
            }
        }
        return when (packType) {
            ResourcePackType.BASE -> ResourceManager.downloadAndInstall(context, force = force, onProgress = progressCb)
            ResourcePackType.INTERACTION -> ResourceManager.downloadInteractionPack(context, force = force, onProgress = progressCb)
            ResourcePackType.PATCH_CORE -> ResourceManager.downloadPatchCore(context, force = force, onProgress = progressCb)
            ResourcePackType.INIT_PREMIUM -> ResourceManager.downloadInitPremium(context, force = force, onProgress = progressCb)
            ResourcePackType.INSTALL_PATCH -> ResourceManager.downloadInstallPatch(context, force = force, onProgress = progressCb)
            ResourcePackType.PRELOAD -> ResourceManager.downloadPreload(context, force = force, onProgress = progressCb)
            ResourcePackType.PREPROCESS -> ResourceManager.downloadPreprocess(context, force = force, onProgress = progressCb)
            ResourcePackType.ALL -> error("ALL should be handled separately")
        }
    }

    /**
     * 启动资源包下载。使用 ResourceManager.globalDownloadScope 而非 composable scope，
     * 确保用户离开 AboutScreen 或切换功能时下载不被取消。进度/状态写入全局 StateFlow，
     * 重新进入页面时自动恢复显示。
     */
    fun downloadResourcePack(packType: ResourcePackType, force: Boolean = false) {
        if (ResourceManager.isDownloading.value) return
        // 在全局作用域中启动：不受 UI 生命周期影响
        ResourceManager.globalDownloadScope.launch {
            ResourceManager.isDownloading.value = true
            ResourceManager.currentDownloadingPack.value = if (packType == ResourcePackType.ALL) null else packType.name
            ResourceManager.downloadProgress.value = 0f
            ResourceManager.downloadSpeed.value = ""

            when (packType) {
                ResourcePackType.ALL -> {
                    // 七阶段顺序下载，每阶段占约 1/7 进度
                    val stages = listOf(
                        Triple(ResourcePackType.BASE, 0.00f, 0.1429f),
                        Triple(ResourcePackType.INTERACTION, 0.1429f, 0.2858f),
                        Triple(ResourcePackType.PATCH_CORE, 0.2858f, 0.4287f),
                        Triple(ResourcePackType.INIT_PREMIUM, 0.4287f, 0.5716f),
                        Triple(ResourcePackType.INSTALL_PATCH, 0.5716f, 0.7145f),
                        Triple(ResourcePackType.PRELOAD, 0.7145f, 0.8572f),
                        Triple(ResourcePackType.PREPROCESS, 0.8572f, 1.00f)
                    )
                    var failed = false
                    for ((idx, stage) in stages.withIndex()) {
                        val (pt, wStart, wEnd) = stage
                        ResourceManager.currentDownloadingPack.value = pt.name
                        ResourceManager.downloadStatus.value = "阶段${idx + 1}/7: ${packDisplayName(pt)}..."
                        val r = downloadOnePack(pt, force, wStart, wEnd,
                            onStatus = { ResourceManager.downloadStatus.value = it },
                            onProgress = { ResourceManager.downloadProgress.value = it },
                            onSpeed = { ResourceManager.downloadSpeed.value = it }
                        )
                        if (r.isFailure) {
                            ResourceManager.downloadStatus.value = "${packDisplayName(pt)}下载失败: ${r.exceptionOrNull()?.message}"
                            failed = true
                            break
                        }
                        refreshResourceStatus()
                        if (pt == ResourcePackType.INTERACTION) {
                            ThemeManager.loadCustomThemes(context)
                        }
                    }
                    if (!failed) {
                        ResourceManager.downloadStatus.value = "全部7层资源包下载完成，已立即生效"
                        ResourceManager.downloadProgress.value = 1f
                        ResourceManager.notifyResourcesUpdated(context)
                        ThemeManager.loadCustomThemes(context)
                    }
                }
                ResourcePackType.BASE, ResourcePackType.INTERACTION,
                ResourcePackType.PATCH_CORE, ResourcePackType.INIT_PREMIUM,
                ResourcePackType.INSTALL_PATCH, ResourcePackType.PRELOAD,
                ResourcePackType.PREPROCESS -> {
                    ResourceManager.downloadStatus.value = if (force) "正在重新下载${packDisplayName(packType)}..." else "正在下载${packDisplayName(packType)}..."
                    val result = downloadOnePack(packType, force, 0f, 1f,
                        onStatus = { ResourceManager.downloadStatus.value = it },
                        onProgress = { ResourceManager.downloadProgress.value = it },
                        onSpeed = { ResourceManager.downloadSpeed.value = it }
                    )
                    if (result.isFailure) {
                        ResourceManager.downloadStatus.value = "下载失败: ${result.exceptionOrNull()?.message}"
                    } else {
                        ResourceManager.downloadStatus.value = "${packDisplayName(packType)}安装完成，已立即生效"
                        ResourceManager.downloadProgress.value = 1f
                        ResourceManager.notifyResourcesUpdated(context)
                        if (packType == ResourcePackType.INTERACTION) {
                            ThemeManager.loadCustomThemes(context)
                        }
                    }
                }
            }

            refreshResourceStatus()
            delay(2000)
            ResourceManager.downloadStatus.value = ""
            ResourceManager.downloadSpeed.value = ""
            ResourceManager.isDownloading.value = false
            ResourceManager.currentDownloadingPack.value = null
        }
    }

    LiquidGlassScaffold(animTime = animTime) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回", tint = appTextSecondary()) }
                Text("关于", fontSize = 16.sp, color = appTextSecondary(), modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(20.dp))

            // === 创作者信息 ===
            Box(modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 24.dp, glassAlpha = 0.15f).padding(24.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier.size(72.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(FluidCyan, FluidPurple))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("JA", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("JTQ Allen", fontSize = 22.sp, fontWeight = FontWeight.Light, color = appTextPrimary())
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("创作者 \u00B7 全栈开发者", fontSize = 12.sp, color = appTextTertiary())

                    Spacer(modifier = Modifier.height(20.dp))

                    ContactItem(Icons.Default.Phone, "电话 / 微信", "18978332931", onClick = {
                        context.startActivity(Intent(Intent.ACTION_DIAL).apply { data = Uri.parse("tel:18978332931") })
                    })
                    Spacer(modifier = Modifier.height(8.dp))
                    ContactItem(Icons.Default.Email, "QQ邮箱", "3982206481@qq.com", onClick = {
                        context.startActivity(Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("mailto:3982206481@qq.com") })
                    })
                    Spacer(modifier = Modifier.height(8.dp))
                    ContactItem(Icons.Default.Email, "备用邮箱", "jiangtengqiao@qq.com", onClick = {
                        context.startActivity(Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("mailto:jiangtengqiao@qq.com") })
                    })
                    Spacer(modifier = Modifier.height(8.dp))
                    ContactItem(Icons.Default.Email, "Outlook", "jiangtengqiao@outlook.com", onClick = {
                        context.startActivity(Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("mailto:jiangtengqiao@outlook.com") })
                    })

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                val callIntent = Intent(Intent.ACTION_CALL).apply { data = Uri.parse("tel:18978332931") }
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
                                    PackageManager.PERMISSION_GRANTED) {
                                    context.startActivity(callIntent)
                                } else {
                                    val dialIntent = Intent(Intent.ACTION_DIAL).apply { data = Uri.parse("tel:18978332931") }
                                    context.startActivity(dialIntent)
                                }
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentSuccess)
                        ) {
                            Icon(Icons.Default.Phone, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("一键拨号", fontSize = 13.sp)
                        }

                        Button(
                            onClick = {
                                val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                                    type = ContactsContract.RawContacts.CONTENT_TYPE
                                    putExtra(ContactsContract.Intents.Insert.NAME, "JTQ Allen")
                                    putExtra(ContactsContract.Intents.Insert.PHONE, "18978332931")
                                    putExtra(ContactsContract.Intents.Insert.PHONE_TYPE,
                                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                                    putExtra(ContactsContract.Intents.Insert.EMAIL, "3982206481@qq.com")
                                    putExtra(ContactsContract.Intents.Insert.EMAIL_TYPE,
                                        ContactsContract.CommonDataKinds.Email.TYPE_WORK)
                                    putExtra(ContactsContract.Intents.Insert.NOTES, "灵工坊 App 创作者")
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                        ) {
                            Icon(Icons.Default.Contacts, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("存入通讯录", fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // === 主题切换 ===
            ThemePickerSection(context)

            Spacer(modifier = Modifier.height(20.dp))

            // === 资源包管理 ===
            Box(modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 24.dp, glassAlpha = 0.15f).padding(24.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudDownload, null, tint = FluidTeal, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("资源包管理", fontSize = 18.sp, fontWeight = FontWeight.Light, color = appTextPrimary())
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("7层强制层级资源包：未下补丁包则对应功能不可用", fontSize = 11.sp, color = appTextTertiary())
                    Spacer(modifier = Modifier.height(16.dp))

                    // ===== 全局下载控制台：任何下载进行中时置顶显示，跨页面持久化 =====
                    if (isDownloadingResource) {
                        GlobalDownloadConsole(
                            progress = resourceDownloadProgress,
                            status = resourceDownloadStatus,
                            speed = resourceDownloadSpeed,
                            packName = downloadingPackName,
                            onPause = { ResourceManager.pauseDownload() },
                            onResume = { ResourceManager.resumeDownload() },
                            onCancel = { ResourceManager.cancelDownload(context) }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // 阶段1：基础资源包
                    ResourcePackCard(
                        packType = ResourcePackType.BASE,
                        title = "基础资源包",
                        stage = "阶段 1/7",
                        icon = Icons.Default.Wallpaper,
                        gradientColors = listOf(FluidCyan, FluidBlue),
                        installed = resourceInstalled,
                        sizeText = if (resourceInstalled) formatSize(resourceSize) else "约530MB",
                        desc = "壁纸·音频等基础资源",
                        requiredFeatures = null,
                        isDownloadingThis = downloadingPack == ResourcePackType.BASE,
                        isDownloadingAny = isDownloadingResource,
                        downloadProgress = resourceDownloadProgress,
                        downloadStatus = resourceDownloadStatus,
                        downloadSpeed = resourceDownloadSpeed,
                        accentColor = FluidCyan,
                        refreshTrigger = fileDetailRefreshTrigger,
                        onDownload = { downloadResourcePack(ResourcePackType.BASE, force = resourceInstalled) }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 阶段2：交互资源包
                    ResourcePackCard(
                        packType = ResourcePackType.INTERACTION,
                        title = "交互资源包",
                        stage = "阶段 2/7",
                        icon = Icons.Default.Bolt,
                        gradientColors = listOf(FluidPurple, FluidPink),
                        installed = interactionInstalled,
                        sizeText = if (interactionInstalled) formatSize(interactionSize) else "约10MB",
                        desc = "物理引擎·着色器·主题·特效",
                        requiredFeatures = null,
                        isDownloadingThis = downloadingPack == ResourcePackType.INTERACTION,
                        isDownloadingAny = isDownloadingResource,
                        downloadProgress = resourceDownloadProgress,
                        downloadStatus = resourceDownloadStatus,
                        downloadSpeed = resourceDownloadSpeed,
                        accentColor = FluidPurple,
                        refreshTrigger = fileDetailRefreshTrigger,
                        onDownload = { downloadResourcePack(ResourcePackType.INTERACTION, force = interactionInstalled) }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 阶段3：核心功能补丁包（patch-core）
                    ResourcePackCard(
                        packType = ResourcePackType.PATCH_CORE,
                        title = "核心功能补丁包",
                        stage = "阶段 3/7 · 功能门禁",
                        icon = Icons.Default.Build,
                        gradientColors = listOf(FluidOrange, FluidPink),
                        installed = patchCoreInstalled,
                        sizeText = if (patchCoreInstalled) formatSize(patchCoreSize) else "约35KB",
                        desc = "解锁核心工具：未下则以下功能不可用",
                        requiredFeatures = listOf("计算器", "单位换算", "二维码", "颜色选择器", "密码生成器"),
                        isDownloadingThis = downloadingPack == ResourcePackType.PATCH_CORE,
                        isDownloadingAny = isDownloadingResource,
                        downloadProgress = resourceDownloadProgress,
                        downloadStatus = resourceDownloadStatus,
                        downloadSpeed = resourceDownloadSpeed,
                        accentColor = FluidOrange,
                        refreshTrigger = fileDetailRefreshTrigger,
                        onDownload = { downloadResourcePack(ResourcePackType.PATCH_CORE, force = patchCoreInstalled) }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 阶段4：高级体验初始化包（init-premium）
                    ResourcePackCard(
                        packType = ResourcePackType.INIT_PREMIUM,
                        title = "高级体验初始化包",
                        stage = "阶段 4/7 · 功能门禁",
                        icon = Icons.Default.AutoAwesome,
                        gradientColors = listOf(FluidTeal, FluidBlue),
                        installed = initPremiumInstalled,
                        sizeText = if (initPremiumInstalled) formatSize(initPremiumSize) else "约6KB",
                        desc = "解锁高级体验：未下则以下功能不可用",
                        requiredFeatures = listOf("音乐", "日历日程", "待办清单", "记事本", "健康计算", "倒计时秒表", "指南针水平仪"),
                        isDownloadingThis = downloadingPack == ResourcePackType.INIT_PREMIUM,
                        isDownloadingAny = isDownloadingResource,
                        downloadProgress = resourceDownloadProgress,
                        downloadStatus = resourceDownloadStatus,
                        downloadSpeed = resourceDownloadSpeed,
                        accentColor = FluidTeal,
                        refreshTrigger = fileDetailRefreshTrigger,
                        onDownload = { downloadResourcePack(ResourcePackType.INIT_PREMIUM, force = initPremiumInstalled) }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 阶段5：安装包补丁（patch-install）
                    ResourcePackCard(
                        packType = ResourcePackType.INSTALL_PATCH,
                        title = "安装包补丁",
                        stage = "阶段 5/7",
                        icon = Icons.Default.SystemUpdate,
                        gradientColors = listOf(FluidBlue, FluidTeal),
                        installed = installPatchInstalled,
                        sizeText = if (installPatchInstalled) formatSize(installPatchSize) else "约8KB",
                        desc = "APK增量补丁·安装配置",
                        requiredFeatures = null,
                        isDownloadingThis = downloadingPack == ResourcePackType.INSTALL_PATCH,
                        isDownloadingAny = isDownloadingResource,
                        downloadProgress = resourceDownloadProgress,
                        downloadStatus = resourceDownloadStatus,
                        downloadSpeed = resourceDownloadSpeed,
                        accentColor = FluidBlue,
                        refreshTrigger = fileDetailRefreshTrigger,
                        onDownload = { downloadResourcePack(ResourcePackType.INSTALL_PATCH, force = installPatchInstalled) }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 阶段6：预加载包（preload-pack）
                    ResourcePackCard(
                        packType = ResourcePackType.PRELOAD,
                        title = "预加载包",
                        stage = "阶段 6/7",
                        icon = Icons.Default.Speed,
                        gradientColors = listOf(FluidOrange, FluidTeal),
                        installed = preloadInstalled,
                        sizeText = if (preloadInstalled) formatSize(preloadSize) else "约328KB",
                        desc = "预加载缓存·启动加速",
                        requiredFeatures = null,
                        isDownloadingThis = downloadingPack == ResourcePackType.PRELOAD,
                        isDownloadingAny = isDownloadingResource,
                        downloadProgress = resourceDownloadProgress,
                        downloadStatus = resourceDownloadStatus,
                        downloadSpeed = resourceDownloadSpeed,
                        accentColor = FluidOrange,
                        refreshTrigger = fileDetailRefreshTrigger,
                        onDownload = { downloadResourcePack(ResourcePackType.PRELOAD, force = preloadInstalled) }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 阶段7：预处理包（preprocess-pack）
                    ResourcePackCard(
                        packType = ResourcePackType.PREPROCESS,
                        title = "预处理包",
                        stage = "阶段 7/7",
                        icon = Icons.Default.Memory,
                        gradientColors = listOf(FluidPurple, FluidBlue),
                        installed = preprocessInstalled,
                        sizeText = if (preprocessInstalled) formatSize(preprocessSize) else "约272KB",
                        desc = "着色器缓存·预处理规则",
                        requiredFeatures = null,
                        isDownloadingThis = downloadingPack == ResourcePackType.PREPROCESS,
                        isDownloadingAny = isDownloadingResource,
                        downloadProgress = resourceDownloadProgress,
                        downloadStatus = resourceDownloadStatus,
                        downloadSpeed = resourceDownloadSpeed,
                        accentColor = FluidPurple,
                        refreshTrigger = fileDetailRefreshTrigger,
                        onDownload = { downloadResourcePack(ResourcePackType.PREPROCESS, force = preprocessInstalled) }
                    )

                    // 全局下载进度（仅 ALL 或不在某卡片内时显示）—— 使用固定高度避免文字内容变化导致布局跳动
                    if (isDownloadingResource && downloadingPack == null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { resourceDownloadProgress },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = FluidTeal,
                            trackColor = GlassLight
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        // 固定高度 + maxLines=1 + ellipsis：状态文本变化不会导致高度跳动
                        Box(modifier = Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.CenterStart) {
                            Text(
                                text = "${(resourceDownloadProgress * 100).toInt()}% \u00B7 $resourceDownloadStatus",
                                fontSize = 12.sp,
                                color = appTextSecondary(),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        // 速度行固定高度，空字符串时仍占位，避免出现/消失导致跳动
                        Box(modifier = Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.CenterStart) {
                            Text(
                                text = resourceDownloadSpeed,
                                fontSize = 11.sp,
                                color = appTextTertiary(),
                                maxLines = 1
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val allInstalled = resourceInstalled && interactionInstalled && patchCoreInstalled &&
                        initPremiumInstalled && installPatchInstalled && preloadInstalled && preprocessInstalled
                    // 全部下载按钮
                    Button(
                        onClick = { downloadResourcePack(ResourcePackType.ALL, force = allInstalled) },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (allInstalled) GlassMedium else FluidTeal
                        ),
                        enabled = !isDownloadingResource
                    ) {
                        Icon(
                            if (allInstalled) Icons.Default.Refresh else Icons.Default.Download,
                            null, modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            when {
                                isDownloadingResource -> "下载中..."
                                allInstalled -> "重新下载全部7层资源包"
                                else -> "一键下载全部7层资源包"
                            },
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // === 更新日志（分级折叠：大版本号 > 小版本号 > notes） ===
            Box(modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 16.dp, glassAlpha = 0.12f).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, null, tint = FluidCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("更新日志", fontSize = 15.sp, fontWeight = FontWeight.Light, color = appTextPrimary())
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            "${changelog.size} 个版本",
                            fontSize = 10.sp,
                            color = appTextTertiary()
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // v2.9.0 超级大版折叠：先按大版本号分组(如 "2.9" "2.8" "1.0")，
                    // 再按首段融合为超级大版(如 "2" 包含所有 2.x，"1" 包含所有 1.x)
                    val majorGroups = changelog.groupBy { it.version.substringBeforeLast('.') }
                    val superGroups = majorGroups.entries
                        .groupBy { it.key.substringBefore('.') }
                        .map { (superMajor, entries) -> superMajor to entries.map { it.key to it.value } }
                    superGroups.forEachIndexed { index, (superMajor, majorList) ->
                        ChangelogSuperMajorGroup(superMajor = superMajor, majorGroups = majorList)
                        if (index < superGroups.size - 1) {
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // === 法律与公告中心 ===
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(cornerRadius = 24.dp, glassAlpha = 0.15f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showLegalCenter = true }
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(listOf(FluidTeal, FluidCyan))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Gavel,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "法律与公告中心",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Light,
                            color = appTextPrimary()
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "隐私政策 · 服务协议 · 免责声明 · 第三方说明 · 儿童保护",
                            fontSize = 10.sp,
                            color = appTextTertiary()
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

            Spacer(modifier = Modifier.height(20.dp))

            // === 检查更新 ===
            Box(modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 24.dp, glassAlpha = 0.15f).padding(24.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SystemUpdate, null, tint = FluidPurple, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("检查更新", fontSize = 18.sp, fontWeight = FontWeight.Light, color = appTextPrimary())
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("当前版本: v${appVersionName()}", fontSize = 13.sp, color = appTextSecondary())

                    if (updateStatus.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(updateStatus, fontSize = 13.sp,
                            color = when {
                                updateStatus.contains("最新") || updateStatus.contains("已是最新") -> AccentSuccess
                                updateStatus.contains("失败") || updateStatus.contains("错误") -> AccentWarning
                                else -> FluidCyan
                            })
                    }

                    if (isDownloading) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { updateProgress },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = FluidCyan,
                            trackColor = GlassLight
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("${(updateProgress * 100).toInt()}%", fontSize = 12.sp, color = appTextTertiary())
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                checkForUpdate(
                                    context,
                                    onStatus = { updateStatus = it },
                                    onProgress = { updateProgress = it },
                                    onDownloading = { isDownloading = it }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FluidPurple),
                        enabled = !isDownloading
                    ) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isDownloading) "下载中..." else "检查并下载更新", fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // === 应用信息 ===
            Box(modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 24.dp, glassAlpha = 0.12f).padding(20.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("灵工坊 v${appVersionName()}", fontSize = 13.sp, color = appTextSecondary())
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("\u00A9 2026 JTQ Allen. All rights reserved.", fontSize = 10.sp, color = appTextTertiary())
                    Text("Build 1 \u00B7 Android 8.0+", fontSize = 10.sp, color = appTextTertiary())
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

// ======================== 更新日志折叠组件（三级：超级大版 > 大版本 > 小版本 > notes） ========================

data class ChangelogVersion(
    val version: String,
    val date: String,
    val notes: List<String>
)

/**
 * 超级大版折叠组 v2.9.0 —— 最外层折叠层。
 *
 * 将若干大版本融合为一个超级大版：如超级大版 "2" 包含 2.0~2.9 的所有大版本组，
 * 超级大版 "1" 包含 1.0~1.1 的所有大版本组。
 *
 * 三级折叠结构：超级大版(默认折叠) → 大版本组(默认折叠) → 小版本(默认折叠) → notes
 */
@Composable
private fun ChangelogSuperMajorGroup(
    superMajor: String,
    majorGroups: List<Pair<String, List<ChangelogVersion>>>
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "super_major_arrow"
    )
    val totalVersions = majorGroups.sumOf { it.second.size }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.15f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = FluidPurple,
                modifier = Modifier.size(18.dp).rotate(rotation)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "超级大版 v$superMajor",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = FluidPurple
                )
                Text(
                    text = "${majorGroups.size} 个大版本 · $totalVersions 个小版本",
                    fontSize = 10.sp,
                    color = appTextTertiary()
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 200f)
            ) + fadeIn(animationSpec = tween(200)),
            exit = shrinkVertically(
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 200f)
            ) + fadeOut(animationSpec = tween(150))
        ) {
            Column(
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                majorGroups.forEach { (major, versions) ->
                    ChangelogMajorGroup(major = major, versions = versions)
                }
            }
        }
    }
}

/**
 * 大版本折叠组：如 "2.6" 包含 2.6.1~2.6.8。
 * 默认折叠，点击展开后显示内部小版本列表（每个小版本也是折叠卡片）。
 */
@Composable
private fun ChangelogMajorGroup(major: String, versions: List<ChangelogVersion>) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "major_arrow"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 12.dp, glassAlpha = 0.10f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = FluidCyan,
                modifier = Modifier.size(16.dp).rotate(rotation)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "v$major.x",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = FluidCyan
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${versions.size} 个版本",
                fontSize = 10.sp,
                color = appTextTertiary()
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 200f)
            ) + fadeIn(animationSpec = tween(200)),
            exit = shrinkVertically(
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 200f)
            ) + fadeOut(animationSpec = tween(150))
        ) {
            Column(
                modifier = Modifier.padding(start = 6.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                versions.forEach { version ->
                    ChangelogItem(version)
                }
            }
        }
    }
}

@Composable
private fun ChangelogItem(version: ChangelogVersion) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "arrow"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 10.dp, glassAlpha = 0.06f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = FluidCyan.copy(alpha = 0.8f),
                modifier = Modifier.size(14.dp).rotate(rotation)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "v${version.version}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = FluidCyan,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = version.date,
                fontSize = 9.sp,
                color = appTextTertiary()
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 200f)
            ) + fadeIn(animationSpec = tween(200)),
            exit = shrinkVertically(
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 200f)
            ) + fadeOut(animationSpec = tween(150))
        ) {
            Column(
                modifier = Modifier.padding(start = 12.dp, top = 3.dp)
            ) {
                for (note in version.notes) {
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        Text(
                            text = "\u2022",
                            fontSize = 11.sp,
                            color = FluidCyan.copy(alpha = 0.6f),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(
                            text = note,
                            fontSize = 11.sp,
                            color = appTextSecondary(),
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

// ======================== 资源状态行 ========================

@Composable
private fun ResourceStatusRow(
    label: String,
    isInstalled: Boolean,
    sizeText: String,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (isInstalled) color else Color.White.copy(alpha = 0.15f))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            color = appTextPrimary(),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (isInstalled) "\u2713 已安装" else "未安装",
            fontSize = 11.sp,
            color = if (isInstalled) color else TextTertiary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = sizeText,
            fontSize = 10.sp,
            color = appTextTertiary()
        )
    }
}

// ======================== 7层补丁包卡片 ========================

/**
 * 全局下载控制台：置顶显示当前下载总进度、状态、速度，并提供暂停/恢复/取消。
 * 状态来自 ResourceManager 的全局 StateFlow，跨页面持久化（globalDownloadScope）。
 */
@Composable
private fun GlobalDownloadConsole(
    progress: Float,
    status: String,
    speed: String,
    packName: String?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val isPaused by ResourceManager.isPaused.collectAsState()
    val currentFile by ResourceManager.currentFileName.collectAsState()
    val liveFiles by ResourceManager.liveExtractedFiles.collectAsState()
    // 控制台展开/收起（默认展开，便于实时观察）
    var expanded by remember { mutableStateOf(true) }

    // 控制台名称映射
    val packDisplay = packName?.let { runCatching { ResourcePackType.valueOf(it) }.getOrNull() }?.let { packDisplayName(it) } ?: "全部资源包"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        (if (isPaused) AccentWarning else FluidCyan).copy(alpha = 0.15f),
                        (if (isPaused) AccentWarning else FluidPurple).copy(alpha = 0.10f)
                    )
                )
            )
            .padding(14.dp)
    ) {
        // 头部：标题 + 展开/收起
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 脉冲圆点指示下载中
            val infinite = rememberInfiniteTransition(label = "consolePulse")
            val pulse by infinite.animateFloat(
                initialValue = 0.4f, targetValue = 1f,
                animationSpec = infiniteRepeatable(animation = tween(700), repeatMode = RepeatMode.Reverse),
                label = "pulse"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isPaused) AccentWarning else FluidCyan)
                    .alpha(if (isPaused) 1f else pulse)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "下载控制台",
                fontSize = 13.sp,
                color = appTextPrimary(),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (isPaused) "已暂停" else "进行中",
                fontSize = 10.sp,
                color = if (isPaused) AccentWarning else FluidCyan
            )
            Spacer(modifier = Modifier.width(8.dp))
            // 展开/收起按钮
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Icon(
                    Icons.Default.ChevronRight,
                    if (expanded) "收起" else "展开",
                    tint = appTextTertiary(),
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(if (expanded) 90f else 0f)
                )
            }
        }

        // 进度条 + 百分比
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(packDisplay, fontSize = 11.sp, color = appTextSecondary(), modifier = Modifier.weight(1f))
            Text("${(progress * 100).toInt()}%", fontSize = 12.sp, color = if (isPaused) AccentWarning else FluidCyan, fontWeight = FontWeight.Medium)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = if (isPaused) AccentWarning else FluidCyan,
            trackColor = Color.White.copy(alpha = 0.10f)
        )

        // 展开详情：状态文本 + 当前文件 + 实时文件列表 + 控制按钮
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = spring(dampingRatio = 0.7f, stiffness = 200f)) + fadeIn(),
            exit = shrinkVertically(animationSpec = spring(dampingRatio = 0.7f, stiffness = 200f)) + fadeOut()
        ) {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                // 状态文本：自适应高度，允许 2 行防止长状态被截
                Text(
                    text = status,
                    fontSize = 10.sp,
                    color = appTextSecondary(),
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(2.dp))
                // 速度行
                Text(
                    text = if (isPaused) "已暂停 \u00B7 缓存已保留，恢复后接着下载" else speed,
                    fontSize = 10.sp,
                    color = if (isPaused) AccentWarning else appTextTertiary(),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                // 当前正在下载/解压的文件名
                if (currentFile.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isPaused) Icons.Default.Pause else Icons.Default.Downloading,
                            null,
                            tint = if (isPaused) AccentWarning else FluidCyan,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = currentFile,
                            fontSize = 10.sp,
                            color = appTextSecondary(),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (liveFiles.isNotEmpty()) {
                            Text("${liveFiles.size}个文件", fontSize = 9.sp, color = appTextTertiary())
                        }
                    }
                }

                // 实时已落盘文件列表（最近 6 个）
                if (liveFiles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.18f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "实时文件 \u00B7 共 ${liveFiles.size} 个",
                            fontSize = 9.sp,
                            color = appTextTertiary(),
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                        liveFiles.takeLast(6).reversed().forEach { fileInfo ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Check, null, tint = FluidTeal, modifier = Modifier.size(10.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = fileInfo.path,
                                    fontSize = 9.sp,
                                    color = appTextSecondary(),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(formatSize(fileInfo.size), fontSize = 8.sp, color = appTextTertiary())
                            }
                        }
                    }
                }

                // 控制按钮：暂停/恢复 + 取消
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isPaused) FluidCyan.copy(alpha = 0.25f) else AccentWarning.copy(alpha = 0.18f)
                            )
                            .clickable { if (isPaused) onResume() else onPause() }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                null,
                                tint = if (isPaused) FluidCyan else AccentWarning,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (isPaused) "恢复下载" else "暂停下载",
                                fontSize = 11.sp,
                                color = if (isPaused) FluidCyan else AccentWarning,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(AccentDanger.copy(alpha = 0.15f))
                            .clickable { onCancel() }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Close, null, tint = AccentDanger, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("取消", fontSize = 11.sp, color = AccentDanger, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个资源补丁包卡片：显示标题/阶段/图标/安装状态/大小/功能解锁范围/下载进度/文件详情。
 *
 * - [requiredFeatures] 非空时，在卡片下方以胶囊形式列出该包解锁的功能（用于 patch-core / init-premium）
 * - [isDownloadingThis] 为 true 时在卡片内嵌显示当前下载进度条与状态文本
 * - [packType] 用于调用 ResourceManager.getPackageFiles 获取真实文件列表
 * - [refreshTrigger] 自增时重新读取磁盘文件列表（下载完成后刷新）
 *
 * 进度区域使用固定高度 Box 包裹文本，避免速度文本出现/消失导致布局跳动。
 */
@Composable
private fun ResourcePackCard(
    packType: ResourcePackType,
    title: String,
    stage: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    gradientColors: List<Color>,
    installed: Boolean,
    sizeText: String,
    desc: String,
    requiredFeatures: List<String>?,
    isDownloadingThis: Boolean,
    isDownloadingAny: Boolean,
    downloadProgress: Float,
    downloadStatus: String,
    downloadSpeed: String,
    accentColor: Color,
    refreshTrigger: Int,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    // 文件详情展开状态（每张卡片独立）
    var filesExpanded by remember { mutableStateOf(false) }
    // 该包的真实文件列表，展开或刷新触发器变化时重新读取磁盘
    var packageFiles by remember { mutableStateOf<List<ResourceManager.PackageFileInfo>>(emptyList()) }

    LaunchedEffect(filesExpanded, refreshTrigger, installed) {
        if (filesExpanded) {
            packageFiles = ResourceManager.getPackageFiles(context, packType.name)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标方块
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(gradientColors)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // 标题行：标题自适应宽度省略 + 徽章固定不缩
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        fontSize = 14.sp,
                        color = appTextPrimary(),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    // 安装/未安装徽章（固定宽度，不参与压缩）
                    if (installed) {
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                .background(FluidTeal.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) { Text("已安装", fontSize = 9.sp, color = FluidTeal) }
                    } else {
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) { Text("未安装", fontSize = 9.sp, color = appTextTertiary()) }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "$sizeText \u00B7 $desc",
                    fontSize = 11.sp,
                    color = appTextTertiary(),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(stage, fontSize = 10.sp, color = accentColor.copy(alpha = 0.8f), fontWeight = FontWeight.Medium)
            }
            // 独立下载/重下按钮
            if (!isDownloadingAny) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (installed) Color.White.copy(alpha = 0.08f) else accentColor.copy(alpha = 0.2f))
                        .clickable { onDownload() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        if (installed) "重下" else "下载",
                        fontSize = 12.sp,
                        color = if (installed) TextSecondary else accentColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else if (isDownloadingThis) {
                // 正在下载此包：显示加载圈
                CircularProgressIndicator(
                    color = accentColor,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // 功能解锁范围列表（补丁包才有）
        if (requiredFeatures != null && requiredFeatures.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            // 每个特性一个胶囊，横向滚动
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
            ) {
                items(requiredFeatures) { feature ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (installed) FluidTeal.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (installed) Icons.Default.Check else Icons.Default.Lock,
                            null,
                            tint = if (installed) FluidTeal else AccentWarning,
                            modifier = Modifier.size(10.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            feature,
                            fontSize = 10.sp,
                            color = if (installed) FluidTeal else appTextTertiary()
                        )
                    }
                }
            }
        }

        // 该卡片对应包正在下载时显示内嵌进度条 + 实时控制台（固定高度避免文字跳动）
        if (isDownloadingThis && isDownloadingAny) {
            // 控制台状态：实时文件名 / 暂停态 / 实时文件列表
            val liveFiles by ResourceManager.liveExtractedFiles.collectAsState()
            val currentFile by ResourceManager.currentFileName.collectAsState()
            val isPausedNow by ResourceManager.isPaused.collectAsState()

            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { downloadProgress },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                color = if (isPausedNow) AccentWarning else accentColor,
                trackColor = GlassLight
            )
            Spacer(modifier = Modifier.height(4.dp))
            // 状态行：自适应高度，允许 2 行防止长状态被截
            Text(
                text = "${(downloadProgress * 100).toInt()}% \u00B7 $downloadStatus",
                fontSize = 11.sp,
                color = if (isPausedNow) AccentWarning else appTextSecondary(),
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(2.dp))
            // 速度行
            Text(
                text = if (isPausedNow) "已暂停 \u00B7 缓存已保留" else downloadSpeed,
                fontSize = 10.sp,
                color = if (isPausedNow) AccentWarning else appTextTertiary(),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            // ===== 多进度条：分块下载时每个分块独立进度条（解决"一个进度条瞎跳"问题） =====
            val chunkProgresses by ResourceManager.chunkProgresses.collectAsState()
            if (chunkProgresses.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "多线程分块下载",
                            fontSize = 10.sp,
                            color = appTextTertiary(),
                            fontWeight = FontWeight.Medium
                        )
                        val doneCount = chunkProgresses.count { it.status == "done" || it.status == "cached" }
                        Text(
                            "$doneCount/${chunkProgresses.size} 块完成",
                            fontSize = 10.sp,
                            color = if (doneCount == chunkProgresses.size) FluidTeal else accentColor
                        )
                    }
                    chunkProgresses.forEach { chunk ->
                        val chunkProgress = if (chunk.size > 0) {
                            (chunk.downloaded.toFloat() / chunk.size.toFloat()).coerceIn(0f, 1f)
                        } else 0f
                        val chunkColor = when (chunk.status) {
                            "done", "cached" -> FluidTeal
                            "failed" -> AccentWarning
                            else -> if (isPausedNow) AccentWarning else accentColor
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = chunk.name,
                                fontSize = 8.sp,
                                color = appTextTertiary(),
                                modifier = Modifier.width(90.dp)
                            )
                            LinearProgressIndicator(
                                progress = { chunkProgress },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = chunkColor,
                                trackColor = GlassLight
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when (chunk.status) {
                                    "done" -> "完成"
                                    "cached" -> "已缓存"
                                    "failed" -> "失败"
                                    else -> "${(chunkProgress * 100).toInt()}%"
                                },
                                fontSize = 9.sp,
                                color = chunkColor,
                                modifier = Modifier.width(36.dp),
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
            }

            // ===== 实时控制台：当前正在下载/解压的文件名 =====
            if (currentFile.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.08f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isPausedNow) Icons.Default.Pause else Icons.Default.Downloading,
                        null,
                        tint = if (isPausedNow) AccentWarning else accentColor,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = currentFile,
                        fontSize = 10.sp,
                        color = appTextSecondary(),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (liveFiles.isNotEmpty()) {
                        Text(
                            text = "${liveFiles.size}个文件",
                            fontSize = 9.sp,
                            color = appTextTertiary()
                        )
                    }
                }
            }

            // ===== 实时文件列表：逐文件显示已落盘内容（可折叠展开，类 PC 端 exe 安装实时文件展示） =====
            if (liveFiles.isNotEmpty()) {
                // 折叠/展开状态：默认折叠只显示最近 5 个，展开显示全部
                var liveExpanded by remember { mutableStateOf(false) }
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (liveExpanded) 280.dp else 130.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    // 头部行：标题 + 文件数 + 展开/收起切换
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { liveExpanded = !liveExpanded }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            null,
                            tint = accentColor,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "实时文件 \u00B7 共 ${liveFiles.size} 个",
                            fontSize = 10.sp,
                            color = appTextSecondary(),
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (liveExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null,
                            tint = appTextTertiary(),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    // 文件列表：折叠时显示最近 5 个（倒序），展开时显示全部（倒序）
                    val displayList = if (liveExpanded) liveFiles.reversed() else liveFiles.takeLast(5).reversed()
                    displayList.forEach { fileInfo ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Check,
                                null,
                                tint = FluidTeal,
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = fileInfo.path,
                                fontSize = 9.sp,
                                color = appTextSecondary(),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = formatSize(fileInfo.size),
                                fontSize = 8.sp,
                                color = appTextTertiary()
                            )
                        }
                    }
                    // 折叠状态下，若有更多文件显示"还有 N 个，点击展开"
                    if (!liveExpanded && liveFiles.size > 5) {
                        Text(
                            "还有 ${liveFiles.size - 5} 个，点击展开",
                            fontSize = 9.sp,
                            color = accentColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            // ===== 暂停/恢复/取消 控制按钮 =====
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 暂停/恢复按钮
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isPausedNow) accentColor.copy(alpha = 0.25f)
                            else AccentWarning.copy(alpha = 0.18f)
                        )
                        .clickable {
                            if (isPausedNow) ResourceManager.resumeDownload()
                            else ResourceManager.pauseDownload()
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isPausedNow) Icons.Default.PlayArrow else Icons.Default.Pause,
                            null,
                            tint = if (isPausedNow) accentColor else AccentWarning,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            if (isPausedNow) "恢复" else "暂停",
                            fontSize = 11.sp,
                            color = if (isPausedNow) accentColor else AccentWarning,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                // 取消下载按钮（清理 .part 缓存）
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(AccentDanger.copy(alpha = 0.15f))
                        .clickable { ResourceManager.cancelDownload(context) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Close, null, tint = AccentDanger, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("取消", fontSize = 11.sp, color = AccentDanger, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // ===== 文件详情展开区 =====
        Spacer(modifier = Modifier.height(8.dp))
        // 可点击的文件详情头：显示已加载文件数/未加载，点击展开/收起
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { filesExpanded = !filesExpanded }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Folder,
                null,
                tint = if (installed) accentColor else appTextTertiary(),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (installed) {
                    // 已加载时显示文件数（展开后才读取，未展开时显示提示文案）
                    if (packageFiles.isNotEmpty()) "已加载 ${packageFiles.size} 个文件"
                    else "已安装 · 点击查看文件详情"
                } else {
                    "未加载"
                },
                fontSize = 10.sp,
                color = if (installed) accentColor else appTextTertiary(),
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = if (filesExpanded) "收起" else "展开",
                tint = appTextTertiary(),
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (filesExpanded) 90f else 0f)
            )
        }

        // 文件列表（展开时显示，带动画）
        AnimatedVisibility(
            visible = filesExpanded,
            enter = expandVertically(
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 200f)
            ) + fadeIn(animationSpec = tween(200)),
            exit = shrinkVertically(
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 200f)
            ) + fadeOut(animationSpec = tween(150))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 4.dp)
            ) {
                if (!installed) {
                    // 未加载空状态
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CloudOff,
                            null,
                            tint = appTextTertiary(),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "该资源包尚未下载，点击上方\"下载\"按钮加载",
                            fontSize = 10.sp,
                            color = appTextTertiary()
                        )
                    }
                } else if (packageFiles.isEmpty()) {
                    // 已安装但文件列表为空（理论上不应发生，防御性处理）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            null,
                            tint = appTextTertiary(),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("暂无可显示的文件", fontSize = 10.sp, color = appTextTertiary())
                    }
                } else {
                    // 文件列表：每行一个文件，显示相对路径 + 大小
                    packageFiles.forEach { fileInfo ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.InsertDriveFile,
                                null,
                                tint = accentColor.copy(alpha = 0.7f),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = fileInfo.path,
                                fontSize = 10.sp,
                                color = appTextSecondary(),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = formatSize(fileInfo.size),
                                fontSize = 9.sp,
                                color = appTextTertiary()
                            )
                        }
                    }
                }
            }
        }
    }
}

// ======================== 联系方式行 ========================

@Composable
fun ContactItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 14.dp, glassAlpha = 0.10f)
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = FluidCyan, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(label, fontSize = 10.sp, color = appTextTertiary())
            Text(value, fontSize = 14.sp, color = appTextPrimary())
        }
    }
}

// ======================== 主题选择器 ========================

@Composable
private fun ThemePickerSection(context: android.content.Context) {
    val selectedId by ThemeManager.selectedThemeIdState
    val themes = ThemeManager.availableThemes()

    Box(modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 24.dp, glassAlpha = 0.15f).padding(24.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Palette, null, tint = FluidPurple, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("主题切换", fontSize = 18.sp, fontWeight = FontWeight.Light, color = appTextPrimary())
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("大道至简：深色 / 浅色 / 系统自动跟随", fontSize = 11.sp, color = appTextTertiary())
            Spacer(modifier = Modifier.height(16.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)
            ) {
                // v2.9.2: 首位固定"系统自动"选项
                item {
                    AutoThemeCard(
                        selected = selectedId == ThemeManager.THEME_AUTO,
                        onClick = { ThemeManager.setTheme(context, ThemeManager.THEME_AUTO) }
                    )
                }
                items(themes) { theme ->
                    val selected = theme.id == selectedId
                    ThemeCard(
                        theme = theme,
                        selected = selected,
                        onClick = { ThemeManager.setTheme(context, theme.id) }
                    )
                }
            }
        }
    }
}

/** "系统自动"主题卡片 —— 左半黑右半白，表示跟随系统 */
@Composable
private fun AutoThemeCard(selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) FluidCyan else Color.White.copy(alpha = 0.12f)
    Column(
        modifier = Modifier
            .width(92.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(width = if (selected) 2.dp else 1.dp, color = border, shape = RoundedCornerShape(16.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            // 左半深色
            Box(Modifier.fillMaxHeight().fillMaxWidth(0.5f).background(Color(0xFF000000)))
            // 右半浅色
            Box(Modifier.fillMaxHeight().fillMaxWidth().align(Alignment.CenterEnd).background(Color(0xFFF5F5F8)))
            // 中间渐变融合
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF000000), Color(0xFF1A1A22), Color(0xFFEAECF2), Color(0xFFF5F5F8))
                        )
                    )
            )
            // 流体色球
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(FluidCyan, FluidPurple, FluidPink, FluidTeal)
                        )
                    )
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(FluidCyan),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(11.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "系统自动",
            fontSize = 10.sp,
            color = if (selected) FluidCyan else appTextPrimary(),
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1
        )
    }
}

@Composable
private fun ThemeCard(
    theme: AppTheme,
    selected: Boolean,
    onClick: () -> Unit
) {
    val border = if (selected) theme.accentPrimary else Color.White.copy(alpha = 0.12f)
    Column(
        modifier = Modifier
            .width(92.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(width = if (selected) 2.dp else 1.dp, color = border, shape = RoundedCornerShape(16.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 预览色块：背景 + 流体渐变
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(theme.bgDark)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(theme.fluidCyan, theme.fluidPurple, theme.fluidPink, theme.fluidTeal)
                        )
                    )
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(theme.accentPrimary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(11.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            theme.name,
            fontSize = 10.sp,
            color = if (selected) theme.accentPrimary else appTextPrimary(),
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1
        )
    }
}

// ======================== 工具函数 ========================

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

/** 资源包中文名，用于状态文案 */
private fun packDisplayName(pt: ResourcePackType): String = when (pt) {
    ResourcePackType.BASE -> "基础资源包"
    ResourcePackType.INTERACTION -> "交互资源包"
    ResourcePackType.PATCH_CORE -> "核心功能补丁包"
    ResourcePackType.INIT_PREMIUM -> "高级体验初始化包"
    ResourcePackType.INSTALL_PATCH -> "安装包补丁"
    ResourcePackType.PRELOAD -> "预加载包"
    ResourcePackType.PREPROCESS -> "预处理包"
    ResourcePackType.ALL -> "全部资源包"
}

private fun formatSpeedStr(speed: Float): String {
    return when {
        speed < 1024 -> "${speed.toInt()} B/s"
        speed < 1024 * 1024 -> "%.1f KB/s".format(speed / 1024)
        else -> "%.1f MB/s".format(speed / (1024 * 1024))
    }
}

/** 统一从 PackageManager 读取版本名，避免硬编码导致显示与实际不符 */
@Composable
fun appVersionName(): String {
    val context = LocalContext.current
    return remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
}

// ======================== 更新检查 ========================

private suspend fun checkForUpdate(
    context: android.content.Context,
    onStatus: (String) -> Unit,
    onProgress: (Float) -> Unit,
    onDownloading: (Boolean) -> Unit
) {
    onStatus("正在检查更新...")
    withContext(Dispatchers.IO) {
        // 加时间戳绕过CDN缓存，确保拿到最新version.json
        val cacheBust = System.currentTimeMillis()
        // GitHub raw 优先（实时不缓存，永远是最新的）；jsdelivr 降为兜底
        // 原因：jsdelivr 对 gh 仓库缓存最长可达12h+，加 ?t= 也无效，
        // 曾导致检查更新拿到2.2.0旧缓存而查找不到新版
        val urls = listOf(
            "https://raw.githubusercontent.com/jiangtengqiao/liquid-glass/main/version.json?t=$cacheBust",
            "https://cdn.jsdelivr.net/gh/jiangtengqiao/liquid-glass@main/version.json?t=$cacheBust",
            "https://fastly.jsdelivr.net/gh/jiangtengqiao/liquid-glass@main/version.json?t=$cacheBust"
        )

        var versionJson: JSONObject? = null
        var lastError: String? = null

        // 先取当前 versionCode，用于校验源返回的版本是否为脏缓存
        val currentVersionCodePre = try {
            context.packageManager.getPackageInfo(context.packageName, 0).let {
                if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt()
                else @Suppress("DEPRECATION") it.versionCode
            }
        } catch (_: Exception) { 0 }

        for (baseUrl in urls) {
            try {
                val url = URL(baseUrl)
                val conn = url.openConnection() as HttpURLConnection
                // 缩短超时：连不上的源快速切换，避免长时间卡住
                conn.connectTimeout = 6000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                conn.setRequestProperty("Cache-Control", "no-cache")

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val parsed = JSONObject(body)
                    // 脏缓存检测：若源返回的 versionCode 远小于当前版本，
                    // 说明是 CDN 旧缓存（如 jsdelivr 曾返回 2.2.0 而实际已 2.4.1），
                    // 跳过该源继续尝试下一个
                    val remoteVc = parsed.optInt("versionCode", 0)
                    if (currentVersionCodePre > 0 && remoteVc in 1..(currentVersionCodePre - 1)) {
                        lastError = "源返回旧版本 v${parsed.optString("version")} (vc=$remoteVc)，疑似CDN缓存，切换源"
                        continue
                    }
                    versionJson = parsed
                    break
                } else {
                    lastError = "HTTP $responseCode"
                    conn.disconnect()
                }
            } catch (e: Exception) {
                lastError = e.message ?: "未知错误"
                continue
            }
        }

        if (versionJson == null) {
            onStatus("检查更新失败：${lastError ?: "网络不可达"}")
            return@withContext
        }

        try {
            val latestVersion = versionJson.optString("version", "")
            val latestVersionCode = versionJson.optInt("versionCode", 0)
            val downloadUrl = versionJson.optString("downloadUrl", "")
            // 从 PackageManager 读取真实 versionCode，不再硬编码（避免发新版后仍提示旧版）
            val currentVersionCode = try {
                context.packageManager.getPackageInfo(context.packageName, 0).let {
                    if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt()
                    else @Suppress("DEPRECATION") it.versionCode
                }
            } catch (_: Exception) { 0 }
            val currentVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            } catch (_: Exception) { "" }

            if (latestVersion.isEmpty() || downloadUrl.isEmpty()) {
                onStatus("检查更新失败：版本信息异常")
                return@withContext
            }

            // 使用 versionCode 整数比较，更可靠
            if (latestVersionCode > currentVersionCode) {
                onStatus("发现新版本: v$latestVersion")
                onDownloading(true)
                // APK 下载也走镜像（GitHub 直连国内常超时）。downloadUrl 是 github 直连，
                // 这里生成多个镜像 URL 依次尝试，快速 failover。
                val apkUrls = buildApkMirrorUrls(downloadUrl)
                var apkFile: File? = null
                var downloadError: String? = null
                for ((idx, apkUrlStr) in apkUrls.withIndex()) {
                    try {
                        if (idx > 0) onStatus("当前源失败，切换镜像下载 v$latestVersion...")
                        val apkUrl = URL(apkUrlStr)
                        val apkConn = apkUrl.openConnection() as HttpURLConnection
                        apkConn.connectTimeout = 8000
                        apkConn.readTimeout = 120000
                        apkConn.requestMethod = "GET"
                        apkConn.instanceFollowRedirects = true
                        apkConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")

                        val totalSize = apkConn.contentLength.toLong()
                        val inputStream = apkConn.inputStream
                        val outFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
                        val outputStream = FileOutputStream(outFile)

                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (totalSize > 0) {
                                onProgress(downloaded.toFloat() / totalSize)
                            }
                        }
                        outputStream.close()
                        inputStream.close()
                        apkConn.disconnect()
                        apkFile = outFile
                        break
                    } catch (e: Exception) {
                        downloadError = e.message ?: "未知错误"
                        continue
                    }
                }

                if (apkFile != null) {
                    onDownloading(false)
                    onStatus("下载完成，正在打开安装...")
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile),
                            "application/vnd.android.package-archive"
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } else {
                    onStatus("下载失败：所有镜像均不可用（${downloadError ?: "超时"}）")
                    onDownloading(false)
                }
            } else {
                onStatus("已是最新版本 v$currentVersion")
            }
        } catch (e: Exception) {
            onStatus("检查更新失败：${e.message}")
        }
    }
}

/**
 * 根据 GitHub 直连 downloadUrl 生成多个镜像 URL（镜像优先，直连兜底）。
 * 国内直连 GitHub releases 常超时，走 ghproxy/cors/gh-proxy 镜像可稳定下载。
 */
private fun buildApkMirrorUrls(githubUrl: String): List<String> {
    // downloadUrl 形如 https://github.com/owner/repo/releases/download/vX.Y.Z/name.apk
    if (!githubUrl.contains("github.com/")) return listOf(githubUrl)
    val path = githubUrl.substringAfter("https://github.com/")
    return listOf(
        "https://cors.isteed.cc/github.com/$path",
        "https://gh-proxy.com/https://github.com/$path",
        "https://ghproxy.net/https://github.com/$path",
        githubUrl  // GitHub 直连兜底
    )
}
