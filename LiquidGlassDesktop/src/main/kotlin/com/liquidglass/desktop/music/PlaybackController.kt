package com.liquidglass.desktop.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import javazoom.jlayer.player.JavaSoundAudioDevice
import javazoom.jlayer.player.advanced.AdvancedPlayer
import javazoom.jlayer.player.advanced.PlaybackEvent
import javazoom.jlayer.player.advanced.PlaybackListener
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 桌面版音乐播放控制器（基于 JLayer AdvancedPlayer）。
 *
 * 设计目标：
 *  - 单实例，全局唯一播放器
 *  - 支持在线 URL 与本地文件路径双源
 *  - Compose State 直驱：state / positionMs / durationMs / volume
 *  - 播放/暂停/恢复/停止/拖动/音量/完成回调
 *  - 自动无缝衔接下一首（通过 [onComplete] 回调由 MusicScreen 控制）
 *
 * 进度估算：MP3 帧时长固定（44.1kHz ≈ 26.12ms），按帧计数换算毫秒；
 * 若调用方传入 [durationMs]，则按比例钳制避免超出。
 *
 * 拖动实现：关闭当前流，重开新流并跳过 [startFrame] 帧后播放——JLayer 不支持原地 seek。
 */
class PlaybackController {

    enum class State { IDLE, PLAYING, PAUSED, ERROR }

    var state by mutableStateOf(State.IDLE)
        private set

    /** 当前播放位置（毫秒） */
    var positionMs by mutableLongStateOf(0L)
        private set

    /** 当前曲目总时长（毫秒），由 [play] 调用方传入 */
    var durationMs by mutableLongStateOf(0L)
        private set

    /** 音量 0.0~1.0 */
    var volume by mutableFloatStateOf(0.7f)

    /** 当前曲目流来源（URL 或文件路径） */
    var currentSource by mutableStateOf<String?>(null)
        private set

    /** 当前是否本地文件 */
    var isLocal by mutableStateOf(false)
        private set

    /** 播放完成回调（用于 MusicScreen 自动播下一首） */
    var onComplete: (() -> Unit)? = null

    private val logger = Logger.getLogger("PlaybackController")

    private var player: AdvancedPlayer? = null
    private var audioDevice: JavaSoundAudioDevice? = null
    private var openedStream: InputStream? = null

    /** 暂停时记录的偏移帧（用于从该帧恢复播放） */
    private var pausedAtFrame: Int = 0

    /** 标志：当前 stop 是为 seek / resume 重启而触发，不应被当自然结束 */
    private var isRestarting: Boolean = false

    /** 标志：当前 stop 是用户主动暂停触发 */
    private var isUserPause: Boolean = false

    /** 播放起始墙钟时间（ms），用于估算 positionMs */
    private var playStartedAt: Long = 0L
    /** 播放起始位置偏移（ms），用于 resume/seek 后保持累计进度 */
    private var startPositionMs: Long = 0L

    /**
     * 开始播放一首曲目。
     *
     * @param source 在线 URL 或本地文件绝对路径
     * @param local true=本地文件，false=在线 URL
     * @param duration 曲目时长（ms），用于 UI 进度条与剩余时间显示；未知传 0
     */
    fun play(source: String, local: Boolean, duration: Long = 0L) {
        stopInternal()
        currentSource = source
        isLocal = local
        durationMs = duration
        positionMs = 0L
        pausedAtFrame = 0
        startPositionMs = 0L
        state = State.PLAYING
        startPlayback(fromFrame = 0)
    }

    private fun startPlayback(fromFrame: Int) {
        val src = currentSource ?: run {
            state = State.ERROR
            return
        }
        try {
            val stream: InputStream = if (isLocal) {
                File(src).inputStream()
            } else {
                URL(src).openStream()
            }
            openedStream = stream

            val device = JavaSoundAudioDevice()
            applyVolume(device, volume)
            audioDevice = device

            val p = AdvancedPlayer(stream, device)
            player = p

            p.playBackListener = object : PlaybackListener() {
                override fun playbackStarted(evt: PlaybackEvent?) {
                    // 线路已打开，再次应用音量确保生效
                    applyVolume(device, volume)
                    if (!isRestarting) {
                        playStartedAt = System.currentTimeMillis()
                        state = State.PLAYING
                    }
                }

                override fun playbackFinished(evt: PlaybackEvent?) {
                    if (isRestarting || isUserPause) {
                        // 重启或暂停路径：状态已由调用方设置，不覆盖
                        return
                    }
                    // 自然结束
                    state = State.IDLE
                    positionMs = 0L
                    pausedAtFrame = 0
                    startPositionMs = 0L
                    onComplete?.invoke()
                }
            }

            // 起始位置 = fromFrame 对应的 ms（用于 resume/seek 后保持累计进度）
            startPositionMs = estimateMsFromFrame(fromFrame)

            // 启动后台线程播放，避免阻塞 UI
            Thread {
                try {
                    if (fromFrame > 0) {
                        p.play(fromFrame, Int.MAX_VALUE)
                    } else {
                        p.play()
                    }
                } catch (_: Exception) {
                    if (!isRestarting && !isUserPause) {
                        state = State.ERROR
                    }
                }
            }.apply {
                isDaemon = true
                name = "JLayer-Playback"
                start()
            }

            // 位置轮询线程：基于墙钟时间估算 positionMs
            Thread {
                while (state == State.PLAYING && player === p) {
                    try {
                        Thread.sleep(200)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (state != State.PLAYING) break
                    val elapsed = System.currentTimeMillis() - playStartedAt
                    var pos = startPositionMs + elapsed
                    if (durationMs > 0 && pos > durationMs) pos = durationMs
                    positionMs = pos
                }
            }.apply {
                isDaemon = true
                name = "JLayer-Position"
                start()
            }

        } catch (e: Exception) {
            logger.log(Level.SEVERE, "startPlayback failed: ${e.message}")
            state = State.ERROR
        }
    }

    fun pause() {
        if (state != State.PLAYING) return
        // 记录当前 ms → 帧偏移，供 resume 跳过
        pausedAtFrame = estimateFrameFromMs(positionMs)
        isUserPause = true
        try {
            player?.stop()
        } catch (_: Exception) { }
        isUserPause = false
        state = State.PAUSED
        // 关闭流（恢复时重开）
        try { openedStream?.close() } catch (_: Exception) { }
        openedStream = null
        player = null
    }

    fun resume() {
        if (state != State.PAUSED) return
        state = State.PLAYING
        startPlayback(fromFrame = pausedAtFrame)
    }

    fun stop() {
        stopInternal()
        positionMs = 0L
        pausedAtFrame = 0
        startPositionMs = 0L
    }

    private fun stopInternal() {
        isRestarting = true
        try {
            player?.close()
        } catch (_: Exception) { }
        try {
            openedStream?.close()
        } catch (_: Exception) { }
        player = null
        openedStream = null
        isRestarting = false
        state = State.IDLE
    }

    /**
     * 拖动到指定毫秒位置。
     *
     * 实现：关闭当前播放，按帧估算跳过点，重开流播放。
     */
    fun seekTo(targetMs: Long) {
        if (currentSource == null) return
        val wasPlaying = state == State.PLAYING
        val wasPaused = state == State.PAUSED
        if (!wasPlaying && !wasPaused) return

        val targetFrame = estimateFrameFromMs(targetMs)
        isRestarting = true
        try {
            player?.close()
            openedStream?.close()
        } catch (_: Exception) { }
        player = null
        openedStream = null
        isRestarting = false

        positionMs = targetMs

        if (wasPlaying) {
            state = State.PLAYING
            startPlayback(fromFrame = targetFrame)
        } else {
            // 暂停状态下拖动：保留暂停态
            pausedAtFrame = targetFrame
            state = State.PAUSED
        }
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        audioDevice?.let { applyVolume(it, volume) }
    }

    /** 在 [JavaSoundAudioDevice] 上应用音量；某些 JLayer 版本无 setVolume 方法时静默失败 */
    private fun applyVolume(device: JavaSoundAudioDevice, vol: Float) {
        try {
            val m = JavaSoundAudioDevice::class.java.getMethod("setVolume", Float::class.javaPrimitiveType)
            m.invoke(device, vol)
        } catch (_: NoSuchMethodException) {
            // 老版本 JLayer 没有该方法；尝试 setLineGain
            try {
                val m = JavaSoundAudioDevice::class.java.getMethod("setLineGain", Float::class.javaPrimitiveType)
                m.invoke(device, vol)
            } catch (_: Exception) { /* 接受无音量控制 */ }
        } catch (_: Exception) { /* 接受无音量控制 */ }
    }

    /**
     * 帧数 → 毫秒。MP3 一帧 = 1152 samples / sampleRate。
     * 常见 44.1kHz → 26.12ms/帧；48kHz → 24ms/帧。
     * 我们用 26ms 作为默认估算（绝大多数网易云在线音频都是 44.1kHz）。
     */
    private fun estimateMsFromFrame(frame: Int): Long {
        return (frame.toLong() * 26L)
    }

    private fun estimateFrameFromMs(ms: Long): Int {
        return (ms / 26L).toInt().coerceAtLeast(0)
    }
}
