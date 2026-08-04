package com.liquidglass.desktop.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import javazoom.jl.player.advanced.AdvancedPlayer
import javazoom.jl.player.advanced.PlaybackEvent
import javazoom.jl.player.advanced.PlaybackListener
import java.io.InputStream
import java.lang.reflect.Method
import java.net.URL
import java.util.logging.Level
import java.util.logging.Logger
import java.io.File

/**
 * 桌面版音乐播放控制器（基于 JLayer AdvancedPlayer）。
 *
 * JLayer 1.0.1 正确包名：javazoom.jl.player.advanced.*
 * - AdvancedPlayer(InputStream) 用默认 JavaSoundAudioDevice
 * - setPlayBackListener 设置监听（playBackListener 字段是 protected，不能直接赋值）
 * - close() 停止播放（无 stop() 方法）
 *
 * 进度估算：MP3 帧时长固定（44.1kHz ≈ 26ms），按帧计数换算毫秒。
 * 拖动实现：关闭当前流，重开新流并跳过 startFrame 帧后播放。
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
    private var openedStream: InputStream? = null

    /** 暂停时记录的偏移帧（用于从该帧恢复播放） */
    private var pausedAtFrame: Int = 0

    /** 标志：当前 close 是为 seek / resume 重启而触发，不应被当自然结束 */
    private var isRestarting: Boolean = false

    /** 标志：当前 close 是用户主动暂停触发 */
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

            // 用默认构造（内部自动创建 JavaSoundAudioDevice）
            val p = AdvancedPlayer(stream)
            player = p

            p.setPlayBackListener(object : PlaybackListener() {
                override fun playbackStarted(evt: PlaybackEvent?) {
                    if (!isRestarting) {
                        playStartedAt = System.currentTimeMillis()
                        state = State.PLAYING
                    }
                    // 线路已打开，应用音量
                    applyVolume(p, volume)
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
            })

            // 起始位置 = fromFrame 对应的 ms（用于 resume/seek 后保持累计进度）
            startPositionMs = estimateMsFromFrame(fromFrame)
            // 预设 playStartedAt，避免轮询线程在 playbackStarted 回调前读到 0 值
            playStartedAt = System.currentTimeMillis()

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
            player?.close()
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

    fun changeVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        player?.let { applyVolume(it, volume) }
    }

    /**
     * 通过反射在 AdvancedPlayer 内部的 audio device 上应用音量。
     * AdvancedPlayer 持有 protected AudioDevice audio 字段，
     * JavaSoundAudioDevice 有 setLineGain(float) 或 setVolume(float) 方法。
     */
    private fun applyVolume(p: AdvancedPlayer, vol: Float) {
        try {
            val audioField = AdvancedPlayer::class.java.getDeclaredField("audio")
            audioField.isAccessible = true
            val audioDevice = audioField.get(p) ?: return
            val deviceClass = audioDevice.javaClass
            // 尝试 setVolume
            val setVolume: Method? = try {
                deviceClass.getMethod("setVolume", Float::class.javaPrimitiveType)
            } catch (_: NoSuchMethodException) { null }
            if (setVolume != null) {
                setVolume.invoke(audioDevice, vol)
                return
            }
            // 尝试 setLineGain
            val setLineGain: Method? = try {
                deviceClass.getMethod("setLineGain", Float::class.javaPrimitiveType)
            } catch (_: NoSuchMethodException) { null }
            setLineGain?.invoke(audioDevice, vol)
        } catch (_: Exception) {
            // 接受无音量控制
        }
    }

    /**
     * 帧数 → 毫秒。MP3 一帧 = 1152 samples / sampleRate。
     * 常见 44.1kHz → 26.12ms/帧；用 26ms 估算。
     */
    private fun estimateMsFromFrame(frame: Int): Long {
        return (frame.toLong() * 26L)
    }

    private fun estimateFrameFromMs(ms: Long): Int {
        return (ms / 26L).toInt().coerceAtLeast(0)
    }
}
