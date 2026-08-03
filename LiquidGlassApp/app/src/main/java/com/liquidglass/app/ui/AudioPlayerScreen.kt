package com.liquidglass.app.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.app.ResourceManager
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.random.Random

// ──────────────────────────────────────────────
// Sound Type Enum
// ──────────────────────────────────────────────

enum class SoundType(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val description: String,
    val accentColor: Color,
    val audioFileName: String
) {
    RAIN("雨声", Icons.Filled.WaterDrop, "轻柔的雨滴落下", FluidCyan, "rain.wav"),
    OCEAN("海浪", Icons.Filled.Waves, "潮汐起伏的韵律", FluidBlue, "ocean.wav"),
    FOREST("森林", Icons.Filled.Park, "林间鸟鸣与微风", FluidTeal, "forest.wav"),
    THUNDER("雷暴", Icons.Filled.Thunderstorm, "远处滚动的雷鸣", FluidPurple, "thunder.wav"),
    FIREPLACE("壁炉", Icons.Filled.LocalFireDepartment, "柴火噼啪燃烧", FluidOrange, "fireplace.wav"),
    WIND("风声", Icons.Filled.Air, "穿林打叶的清风", FluidCyan, "wind.wav"),
    STREAM("溪流", Icons.Filled.Water, "清泉石上流淌", FluidTeal, "stream.wav"),
    NIGHT("夜晚", Icons.Filled.Bedtime, "夏夜虫鸣交响", FluidPurple, "night.wav"),
    FAN("风扇", Icons.Filled.Toys, "稳定白噪音环绕", FluidBlue, "fan.wav"),
    PIANO("钢琴", Icons.Filled.MusicNote, "舒缓的旋律片段", FluidPink, "piano.wav")
}

// ──────────────────────────────────────────────
// Sound State
// ──────────────────────────────────────────────

data class SoundState(
    val isPlaying: Boolean = false,
    val volume: Float = 0.5f
)

// ──────────────────────────────────────────────
// Sound Engine — manages AudioTrack per sound type
// ──────────────────────────────────────────────

class SoundEngine(private val audioDir: java.io.File? = null) {
    private val sampleRate = 44100
    private val bufferSize = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ) * 2

    private val tracks = mutableMapOf<SoundType, AudioTrack>()
    private val players = mutableMapOf<SoundType, MediaPlayer>()
    private val jobs = mutableMapOf<SoundType, Job>()
    private val volumes = mutableMapOf<SoundType, Float>()
    private var masterVolume = 1f
    private var isActive = true

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun setMasterVolume(vol: Float) {
        masterVolume = vol.coerceIn(0f, 1f)
    }

    fun setVolume(type: SoundType, vol: Float) {
        volumes[type] = vol.coerceIn(0f, 1f)
        val effective = (vol * masterVolume).coerceIn(0f, 1f)
        tracks[type]?.setVolume(effective)
        try { players[type]?.setVolume(effective, effective) } catch (_: Exception) {}
    }

    fun getVolume(type: SoundType): Float = volumes[type] ?: 0.5f

    /**
     * 优先播放下载的音频文件（高保真），找不到则回退到 PCM 实时合成。
     */
    fun start(type: SoundType) {
        if (jobs[type]?.isActive == true || players[type]?.isPlaying == true) return
        val vol = volumes.getOrDefault(type, 0.5f)
        val effective = (vol * masterVolume).coerceIn(0f, 1f)

        // 1) 优先使用资源包中的真实音频文件
        val audioFile = findAudioFile(type)
        if (audioFile != null && audioFile.exists()) {
            try {
                val mp = MediaPlayer().apply {
                    setDataSource(audioFile.absolutePath)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    isLooping = true
                    setVolume(effective, effective)
                    setOnPreparedListener { it.start() }
                    setOnErrorListener { _, _, _ -> true }
                }
                mp.prepare()
                players[type] = mp
                // 占位 job，便于 isPlaying 判断
                jobs[type] = scope.launch { while (isActive && players[type] != null) { delay(200) } }
                return
            } catch (_: Exception) {
                // 文件损坏或不可播放，回退到 PCM 合成
                try { players.remove(type)?.release() } catch (_: Exception) {}
            }
        }

        // 2) 回退：PCM 实时合成
        startPcm(type, effective)
    }

    private fun findAudioFile(type: SoundType): java.io.File? {
        val dir = audioDir ?: return null
        if (!dir.exists()) return null
        // 优先按语义文件名查找（rain.wav 等）
        val named = java.io.File(dir, type.audioFileName)
        if (named.exists() && named.length() > 0) return named
        // 回退：按 SoundType 序号查找（audio_00.wav / au_00.wav）
        val idx = SoundType.entries.indexOf(type)
        val fallbacks = listOf("audio_${"%02d".format(idx)}.wav", "au_${"%02d".format(idx)}.wav")
        for (name in fallbacks) {
            val f = java.io.File(dir, name)
            if (f.exists() && f.length() > 0) return f
        }
        return null
    }

    private fun startPcm(type: SoundType, effective: Float) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track.setVolume(effective)
        track.play()
        tracks[type] = track

        val job = scope.launch {
            val buffer = ShortArray(bufferSize / 2)
            var sampleIndex = 0L
            try {
                while (isActive) {
                    val currentVol = (volumes[type] ?: 0.5f) * masterVolume
                    track.setVolume(currentVol.coerceIn(0f, 1f))

                    generateSamples(type, buffer, sampleIndex, currentVol)
                    sampleIndex += buffer.size
                    track.write(buffer, 0, buffer.size)

                    if (!isActive) break
                }
            } catch (_: Exception) {
                // stream interrupted
            }
        }
        jobs[type] = job
    }

    fun stop(type: SoundType) {
        jobs[type]?.cancel()
        jobs.remove(type)
        tracks[type]?.let { track ->
            try {
                track.stop()
                track.release()
            } catch (_: Exception) {}
        }
        tracks.remove(type)
        players[type]?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
                mp.release()
            } catch (_: Exception) {}
        }
        players.remove(type)
    }

    fun isPlaying(type: SoundType): Boolean =
        jobs[type]?.isActive == true || players[type]?.isPlaying == true

    fun release() {
        isActive = false
        scope.cancel()
        tracks.values.forEach { track ->
            try { track.stop(); track.release() } catch (_: Exception) {}
        }
        tracks.clear()
        players.values.forEach { mp ->
            try { if (mp.isPlaying) mp.stop(); mp.release() } catch (_: Exception) {}
        }
        players.clear()
        jobs.clear()
    }

    // ── Sound generation algorithms ──

    private fun generateSamples(type: SoundType, buffer: ShortArray, startIndex: Long, volume: Float) {
        when (type) {
            SoundType.RAIN -> fillRain(buffer, startIndex, volume)
            SoundType.OCEAN -> fillOcean(buffer, startIndex, volume)
            SoundType.FOREST -> fillForest(buffer, startIndex, volume)
            SoundType.THUNDER -> fillThunder(buffer, startIndex, volume)
            SoundType.FIREPLACE -> fillFireplace(buffer, startIndex, volume)
            SoundType.WIND -> fillWind(buffer, startIndex, volume)
            SoundType.STREAM -> fillStream(buffer, startIndex, volume)
            SoundType.NIGHT -> fillNight(buffer, startIndex, volume)
            SoundType.FAN -> fillFan(buffer, startIndex, volume)
            SoundType.PIANO -> fillPiano(buffer, startIndex, volume)
        }
    }

    private fun random(): Float = Random.nextFloat() * 2f - 1f

    // Rain: filtered white noise with high-frequency emphasis
    private fun fillRain(buffer: ShortArray, startIndex: Long, volume: Float) {
        var prev = 0f
        for (i in buffer.indices) {
            val raw = random()
            prev = prev * 0.88f + raw * 0.12f // low-pass
            val out = (prev * volume * 0.7f * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = out.toShort()
        }
    }

    // Ocean: low-frequency noise with slow amplitude modulation
    private fun fillOcean(buffer: ShortArray, startIndex: Long, volume: Float) {
        var lpf = 0f
        for (i in buffer.indices) {
            val idx = startIndex + i
            val raw = random()
            lpf = lpf * 0.95f + raw * 0.05f
            val mod = 0.6f + 0.4f * sin(idx * 2.0 * PI / sampleRate * 0.25f).toFloat()
            val out = (lpf * mod * volume * 0.6f * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = out.toShort()
        }
    }

    // Forest: filtered noise with chirp-like tones
    private fun fillForest(buffer: ShortArray, startIndex: Long, volume: Float) {
        var lpf = 0f
        for (i in buffer.indices) {
            val idx = startIndex + i
            val raw = random()
            lpf = lpf * 0.94f + raw * 0.06f
            // occasional bird chirp
            val chirpPhase = (idx % (sampleRate * 3L)).toFloat() / sampleRate.toFloat()
            val chirp = if (chirpPhase < 0.12f) {
                sin(chirpPhase * 3000f * PI.toFloat()) * (1f - chirpPhase / 0.12f) * 0.3f
            } else 0f
            val out = ((lpf * 0.7f + chirp) * volume * 0.6f * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = out.toShort()
        }
    }

    // Thunder: low rumble with occasional crashes
    private fun fillThunder(buffer: ShortArray, startIndex: Long, volume: Float) {
        var lpf = 0f
        for (i in buffer.indices) {
            val idx = startIndex + i
            val raw = random()
            lpf = lpf * 0.97f + raw * 0.03f
            // thunder crash every ~8 seconds
            val crashPhase = (idx % (sampleRate * 8L)).toFloat() / sampleRate.toFloat()
            val crash = if (crashPhase < 1.5f) {
                val envelope = (1f - crashPhase / 1.5f) * (1f - crashPhase / 1.5f)
                random() * envelope * 0.8f
            } else 0f
            val out = ((lpf * 0.4f + crash) * volume * 0.65f * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = out.toShort()
        }
    }

    // Fireplace: crackling impulses
    private fun fillFireplace(buffer: ShortArray, startIndex: Long, volume: Float) {
        var lpf = 0f
        for (i in buffer.indices) {
            val idx = startIndex + i
            val raw = random()
            lpf = lpf * 0.93f + raw * 0.07f
            // crackle impulse
            val crackle = if (Random.nextFloat() < 0.008f) random() * 0.6f else 0f
            val pop = if (Random.nextFloat() < 0.003f) random() * 0.9f else 0f
            val out = ((lpf * 0.5f + crackle + pop) * volume * 0.55f * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = out.toShort()
        }
    }

    // Wind: filtered noise with LFO
    private fun fillWind(buffer: ShortArray, startIndex: Long, volume: Float) {
        var lpf = 0f
        for (i in buffer.indices) {
            val idx = startIndex + i
            val raw = random()
            lpf = lpf * 0.96f + raw * 0.04f
            val lfo = 0.5f + 0.5f * sin(idx * 2.0 * PI / sampleRate * 0.6f).toFloat()
            val lfo2 = 0.5f + 0.5f * sin(idx * 2.0 * PI / sampleRate * 0.35f).toFloat()
            val out = (lpf * lfo * lfo2 * volume * 0.55f * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = out.toShort()
        }
    }

    // Stream: high-frequency filtered noise
    private fun fillStream(buffer: ShortArray, startIndex: Long, volume: Float) {
        var prev = 0f
        var prev2 = 0f
        for (i in buffer.indices) {
            val raw = random()
            val hp = raw - prev * 0.85f // high-pass
            prev2 = prev2 * 0.5f + hp * 0.5f
            prev = raw
            val out = (prev2 * volume * 0.5f * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = out.toShort()
        }
    }

    // Night: cricket-like tones
    private fun fillNight(buffer: ShortArray, startIndex: Long, volume: Float) {
        var lpf = 0f
        for (i in buffer.indices) {
            val idx = startIndex + i
            val raw = random()
            lpf = lpf * 0.95f + raw * 0.05f
            // cricket chirp
            val cricketPhase = (idx % (sampleRate / 2)).toFloat() / sampleRate.toFloat()
            val cricket = if (cricketPhase < 0.02f) {
                sin(cricketPhase * 4000f * PI.toFloat()) * 0.4f
            } else 0f
            val out = ((lpf * 0.4f + cricket) * volume * 0.5f * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = out.toShort()
        }
    }

    // Fan: steady low-frequency noise
    private fun fillFan(buffer: ShortArray, startIndex: Long, volume: Float) {
        var lpf = 0f
        for (i in buffer.indices) {
            val raw = random()
            lpf = lpf * 0.99f + raw * 0.01f
            val out = (lpf * volume * 0.5f * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = out.toShort()
        }
    }

    // Piano: simple sine-wave melody
    private fun fillPiano(buffer: ShortArray, startIndex: Long, volume: Float) {
        val notes = floatArrayOf(261.63f, 293.66f, 329.63f, 349.23f, 392.00f, 440.00f, 493.88f, 523.25f)
        val noteLength = sampleRate * 2 // 2 seconds per note
        for (i in buffer.indices) {
            val idx = (startIndex + i).toFloat()
            val noteIdx = ((idx / noteLength).toInt() % notes.size)
            val freq = notes[noteIdx]
            val phase = (idx % noteLength) / noteLength
            val envelope = when {
                phase < 0.05f -> phase / 0.05f
                phase > 0.7f -> (1f - phase) / 0.3f
                else -> 1f
            }
            val sample = sin(idx * 2.0 * PI / sampleRate * freq).toFloat()
            // add harmonics for piano-like timbre
            val harmonic = 0.5f * sin(idx * 2.0 * PI / sampleRate * freq * 2f).toFloat() +
                    0.25f * sin(idx * 2.0 * PI / sampleRate * freq * 3f).toFloat() +
                    0.125f * sin(idx * 2.0 * PI / sampleRate * freq * 4f).toFloat()
            val mixed = (sample * 0.6f + harmonic * 0.4f) * envelope
            val out = (mixed * volume * 0.55f * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = out.toShort()
        }
    }
}

// ──────────────────────────────────────────────
// Timer Options
// ──────────────────────────────────────────────

data class TimerOption(
    val minutes: Int,
    val label: String
)

val timerOptions = listOf(
    TimerOption(15, "15 分钟"),
    TimerOption(30, "30 分钟"),
    TimerOption(60, "60 分钟"),
    TimerOption(90, "90 分钟"),
    TimerOption(120, "120 分钟"),
    TimerOption(0, "无限")
)

// ──────────────────────────────────────────────
// Main Screen
// ──────────────────────────────────────────────

@Composable
fun AudioPlayerScreen(animTime: Float, onBack: () -> Unit) {
    val context = LocalContext.current
    val audioDir = remember { java.io.File(context.filesDir, "resources/raw") }
    val soundEngine = remember { SoundEngine(audioDir) }
    val soundStates = remember {
        mutableStateMapOf<SoundType, SoundState>().apply {
            SoundType.entries.forEach { put(it, SoundState()) }
        }
    }
    var showTimerSelector by remember { mutableStateOf(false) }
    var selectedTimer by remember { mutableStateOf(timerOptions.last()) }
    var timerRemaining by remember { mutableStateOf(0L) }
    var timerJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    // visualizer animation data
    val visualizerBars = remember { mutableStateListOf<Float>().apply { repeat(40) { add(0.05f) } } }
    val targetVisualizer = remember { mutableStateListOf<Float>().apply { repeat(40) { add(0.05f) } } }

    // drive visualizer
    LaunchedEffect(Unit) {
        while (isActive) {
            val anyPlaying = SoundType.entries.any { soundStates[it]?.isPlaying == true }
            for (i in 0 until 40) {
                if (anyPlaying) {
                    val activeCount = SoundType.entries.count { soundStates[it]?.isPlaying == true }
                    val base = 0.08f + activeCount * 0.02f
                    val freq = (i + 1f) / 40f
                    val raw = (sin(animTime * 0.3f + freq * 12f) * 0.4f + 0.5f +
                            cos(animTime * 0.5f + freq * 8f) * 0.25f +
                            sin(animTime * 0.7f + freq * 20f) * 0.15f +
                            randomNoise(i) * 0.2f)
                    targetVisualizer[i] = (raw * 0.9f + 0.1f).coerceIn(0.03f, 1f)
                } else {
                    targetVisualizer[i] = 0.03f
                }
            }
            delay(50)
        }
    }

    // smooth visualizer
    LaunchedEffect(Unit) {
        while (isActive) {
            for (i in 0 until 40) {
                visualizerBars[i] += (targetVisualizer[i] - visualizerBars[i]) * 0.12f
            }
            delay(16)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            soundEngine.release()
            timerJob?.cancel()
        }
    }

    LiquidGlassScaffold(animTime = animTime) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            // ── Top Bar ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = appTextSecondary())
                }
                Text(
                    "环境音效",
                    fontSize = 16.sp,
                    color = appTextSecondary(),
                    fontWeight = FontWeight.Medium
                )
                IconButton(onClick = { showTimerSelector = true }) {
                    Icon(
                        Icons.Default.Timer,
                        "定时",
                        tint = if (timerRemaining > 0) FluidCyan else TextTertiary
                    )
                }
            }

            // ── Timer Indicator ──
            if (timerRemaining > 0) {
                AnimatedVisibility(
                    visible = timerRemaining > 0,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Timer,
                            null,
                            tint = FluidCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "剩余 ${formatTime(timerRemaining)}",
                            fontSize = 11.sp,
                            color = FluidCyan.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Visualizer ──
            AudioVisualizer(
                bars = visualizerBars,
                isPlaying = SoundType.entries.any { soundStates[it]?.isPlaying == true },
                animTime = animTime
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Sound Grid ──
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                items(SoundType.entries.toList()) { sound ->
                    SoundCard(
                        sound = sound,
                        state = soundStates[sound] ?: SoundState(),
                        onToggle = {
                            val current = soundStates[sound] ?: SoundState()
                            if (current.isPlaying) {
                                soundEngine.stop(sound)
                                soundStates[sound] = current.copy(isPlaying = false)
                            } else {
                                soundEngine.start(sound)
                                soundStates[sound] = current.copy(isPlaying = true)
                                // start timer if not already running
                                if (selectedTimer.minutes > 0 && timerJob?.isActive != true) {
                                    startTimer(selectedTimer.minutes, scope) { remaining, finished ->
                                        timerRemaining = remaining
                                        if (finished) {
                                            stopAllSounds(soundEngine, soundStates)
                                        }
                                    }.also { timerJob = it }
                                }
                            }
                        },
                        onVolumeChange = { vol ->
                            val current = soundStates[sound] ?: SoundState()
                            soundEngine.setVolume(sound, vol)
                            soundStates[sound] = current.copy(volume = vol)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── Master Volume ──
            MasterVolumeControl(
                masterVolume = 1f,
                onVolumeChange = { vol ->
                    soundEngine.setMasterVolume(vol)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    // ── Timer Dialog ──
    if (showTimerSelector) {
        TimerSelectorDialog(
            options = timerOptions,
            selected = selectedTimer,
            onSelect = { option ->
                selectedTimer = option
                showTimerSelector = false
                timerJob?.cancel()
                timerRemaining = 0L
                if (option.minutes > 0) {
                    startTimer(option.minutes, scope) { remaining, finished ->
                        timerRemaining = remaining
                        if (finished) {
                            stopAllSounds(soundEngine, soundStates)
                        }
                    }.also { timerJob = it }
                }
            },
            onDismiss = { showTimerSelector = false }
        )
    }
}

// ──────────────────────────────────────────────
// Sound Card
// ──────────────────────────────────────────────

@Composable
fun SoundCard(
    sound: SoundType,
    state: SoundState,
    onToggle: () -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 350f),
        label = "cardScale"
    )

    // playing pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val playIconRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotate"
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .glassSurface(cornerRadius = 20.dp, glassAlpha = if (state.isPlaying) 0.22f else 0.12f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                pressed = true
                onToggle()
            }
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 图标 + 播放指示环（v2.9.1：移除 emoji，改用 Material 图标）
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    imageVector = sound.icon,
                    contentDescription = sound.label,
                    tint = sound.accentColor,
                    modifier = Modifier.size(28.dp)
                )

                // playing ring
                if (state.isPlaying) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = size.width / 2
                        val cy = size.height / 2
                        val r = size.width / 2 - 4f
                        drawCircle(
                            color = sound.accentColor.copy(alpha = pulseAlpha),
                            radius = r,
                            center = Offset(cx, cy),
                            style = Stroke(width = 2f)
                        )
                        drawCircle(
                            color = sound.accentColor.copy(alpha = pulseAlpha * 0.5f),
                            radius = r + 6f,
                            center = Offset(cx, cy),
                            style = Stroke(width = 1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Name
            Text(
                text = sound.label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (state.isPlaying) sound.accentColor else appTextPrimary().copy(alpha = 0.85f)
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Description
            Text(
                text = sound.description,
                fontSize = 10.sp,
                color = if (state.isPlaying) sound.accentColor.copy(alpha = 0.6f) else TextTertiary,
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            // Volume slider (only visible when playing)
            AnimatedVisibility(
                visible = state.isPlaying,
                enter = fadeIn(animationSpec = tween(200)) + expandVertically(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(animationSpec = tween(150))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = state.volume,
                        onValueChange = onVolumeChange,
                        modifier = Modifier.fillMaxWidth().height(20.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = sound.accentColor,
                            activeTrackColor = sound.accentColor.copy(alpha = 0.7f),
                            inactiveTrackColor = sound.accentColor.copy(alpha = 0.15f)
                        ),
                        valueRange = 0f..1f
                    )
                }
            }
        }
    }

    LaunchedEffect(pressed) {
        if (pressed) {
            delay(120)
            pressed = false
        }
    }
}

// ──────────────────────────────────────────────
// Audio Visualizer
// ──────────────────────────────────────────────

@Composable
fun AudioVisualizer(
    bars: List<Float>,
    isPlaying: Boolean,
    animTime: Float
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .glassSurface(cornerRadius = 20.dp, glassAlpha = 0.10f)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isPlaying) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val barCount = bars.size
                val barWidth = w / barCount * 0.6f
                val gap = w / barCount * 0.4f
                val colors = listOf(
                    FluidCyan, FluidBlue, FluidTeal, FluidPurple, FluidPink, FluidOrange
                )

                for (i in 0 until barCount) {
                    val bh = bars[i].coerceIn(0.02f, 1f) * h * 0.85f
                    val x = i * (barWidth + gap)
                    val ci = (i * colors.size / barCount).coerceIn(0, colors.size - 1)
                    val color = colors[ci]

                    // main bar
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(color.copy(alpha = 0.9f), color.copy(alpha = 0.2f)),
                            startY = h - bh,
                            endY = h
                        ),
                        topLeft = Offset(x, h - bh),
                        size = Size(barWidth, bh),
                        cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
                    )

                    // reflection (mirrored bars at top)
                    val refH = bh * 0.3f
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(color.copy(alpha = 0.4f), Color.Transparent),
                            startY = 0f,
                            endY = refH
                        ),
                        topLeft = Offset(x, 0f),
                        size = Size(barWidth, refH),
                        cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
                    )
                }

                // wave overlay
                val wavePath = Path()
                wavePath.moveTo(0f, h / 2)
                for (i in 0..200) {
                    val x = i * w / 200f
                    val bi = (i * bars.size / 200).coerceIn(0, bars.size - 1)
                    val amp = bars[bi] * h * 0.3f
                    val y = h / 2 + sin(x / w * 6f + animTime * 0.02f) * amp
                    wavePath.lineTo(x, y)
                }
                drawPath(wavePath, FluidCyan.copy(alpha = 0.25f), style = Stroke(width = 1.5f))
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.GraphicEq,
                    null,
                    tint = appTextTertiary().copy(alpha = 0.4f),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "选择音效开始播放",
                    fontSize = 12.sp,
                    color = appTextTertiary().copy(alpha = 0.5f)
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// Master Volume Control
// ──────────────────────────────────────────────

@Composable
fun MasterVolumeControl(
    masterVolume: Float,
    onVolumeChange: (Float) -> Unit
) {
    var currentVolume by remember { mutableStateOf(1f) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 16.dp, glassAlpha = 0.12f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (currentVolume == 0f) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
            null,
            tint = appTextSecondary(),
            modifier = Modifier.size(20.dp)
        )
        Slider(
            value = currentVolume,
            onValueChange = {
                currentVolume = it
                onVolumeChange(it)
            },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                thumbColor = FluidCyan,
                activeTrackColor = FluidCyan.copy(alpha = 0.6f),
                inactiveTrackColor = FluidCyan.copy(alpha = 0.12f)
            ),
            valueRange = 0f..1f
        )
        Text(
            "${(currentVolume * 100).toInt()}%",
            fontSize = 12.sp,
            color = appTextSecondary(),
            modifier = Modifier.width(38.dp),
            textAlign = TextAlign.End
        )
    }
}

// ──────────────────────────────────────────────
// Timer Selector Dialog
// ──────────────────────────────────────────────

@Composable
fun TimerSelectorDialog(
    options: List<TimerOption>,
    selected: TimerOption,
    onSelect: (TimerOption) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0D0D1A),
        title = {
            Text(
                "定时关闭",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = appTextPrimary()
            )
        },
        text = {
            Column {
                Text(
                    "设定时长后自动停止播放",
                    fontSize = 12.sp,
                    color = appTextTertiary(),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                options.forEach { option ->
                    val isSelected = option == selected
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) FluidCyan.copy(alpha = 0.12f)
                                else Color.Transparent
                            )
                            .clickable { onSelect(option) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                option.label,
                                fontSize = 14.sp,
                                color = if (isSelected) FluidCyan else TextSecondary
                            )
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    null,
                                    tint = FluidCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = appTextTertiary())
            }
        }
    )
}

// ──────────────────────────────────────────────
// Utility Functions
// ──────────────────────────────────────────────

private fun randomNoise(seed: Int): Float {
    val s = (seed * 127 + 13) % 9973
    return (sin(s * 1.0f) * 0.5f + 0.5f)
}

private fun formatTime(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(mins, secs)
}

private fun startTimer(
    minutes: Int,
    scope: CoroutineScope,
    onTick: (Long, Boolean) -> Unit
): Job {
    val totalSeconds = minutes * 60L
    return scope.launch {
        for (remaining in totalSeconds downTo 0) {
            onTick(remaining, false)
            delay(1000)
        }
        onTick(0, true)
    }
}

private fun stopAllSounds(engine: SoundEngine, states: MutableMap<SoundType, SoundState>) {
    SoundType.entries.forEach { type ->
        engine.stop(type)
        states[type] = SoundState(isPlaying = false, volume = states[type]?.volume ?: 0.5f)
    }
}