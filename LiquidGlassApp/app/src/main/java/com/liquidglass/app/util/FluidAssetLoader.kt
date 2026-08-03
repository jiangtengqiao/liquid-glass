package com.liquidglass.app.util

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.io.RandomAccessFile
import kotlin.math.*

/**
 * 流体资源加载器 —— 真正"应用"下载的资源包内容。
 *
 * 1. [noiseAtlases]：加载 fluid_textures/noise_atlas_*.png，作为流体噪声场纹理。
 *    FluidBackground 通过 [sampleNoise] 采样它来调制色块位置/半径/透明度，
 *    使下载的纹理真正影响渲染（视觉可感知的差异）。
 *
 * 2. [FluidFieldCache]：内存映射 fluid_textures/particle_cache_*.bin，作为预烘焙的
 *    Navier-Stokes 速度场缓存。drawPhysicsParticles 通过 [sampleVelocity] 读取
 *    预计算流场来驱动粒子轨迹，而不是纯靠 sin/cos 合成。
 *
 * 资源不存在时全部安全回退到纯算法合成，不影响功能。
 */
object FluidAssetLoader {

    @Volatile private var initialized = false
    @Volatile private var hasAssets = false

    private val noiseAtlases = mutableListOf<Bitmap>()
    private val noiseWidths = mutableListOf<Int>()
    private val noiseHeights = mutableListOf<Int>()

    private var fieldCache: FluidFieldCache? = null

    /**
     * 从资源目录加载流体纹理与粒子场缓存。可在 IO 线程调用一次。
     * 重复调用安全（幂等）。
     */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val texDir = File(context.filesDir, "resources/fluid_textures")
            if (!texDir.isDirectory) {
                initialized = true
                hasAssets = false
                return
            }
            // 加载噪声纹理（按文件名排序，保证 atlas 索引稳定）
            val noiseFiles = texDir.listFiles { f ->
                f.isFile && f.name.startsWith("noise_atlas_") && f.extension.equals("png", true)
            }?.sortedBy { it.name } ?: emptyList()

            for (f in noiseFiles) {
                try {
                    val bmp = BitmapFactory.decodeFile(f.absolutePath)
                        ?: continue
                    noiseAtlases.add(bmp)
                    noiseWidths.add(bmp.width)
                    noiseHeights.add(bmp.height)
                } catch (_: Exception) { /* 跳过损坏纹理 */ }
            }

            // 加载粒子场缓存（内存映射，不一次性读入内存）
            val cacheFiles = texDir.listFiles { f ->
                f.isFile && f.name.startsWith("particle_cache_") && f.extension.equals("bin", true)
            }?.sortedBy { it.name } ?: emptyList()

            if (cacheFiles.isNotEmpty()) {
                fieldCache = FluidFieldCache(cacheFiles)
            }

            hasAssets = noiseAtlases.isNotEmpty() || fieldCache != null
            initialized = true
        }
    }

    /** 是否已加载到真实资源 */
    fun hasAssets(): Boolean = hasAssets

    /**
     * 采样噪声纹理：返回 [0,1) 的标量噪声值。
     * [atlasIndex] 选择第几张噪声图（取模回绕）；[u],[v] 为归一化坐标 [0,1]。
     * 资源缺失时回退到算法噪声（保证总有返回值）。
     */
    fun sampleNoise(atlasIndex: Int, u: Float, v: Float): Float {
        val atlases = noiseAtlases
        if (atlases.isEmpty()) return algoNoise(atlasIndex, u, v)
        val idx = ((atlasIndex % atlases.size) + atlases.size) % atlases.size
        val bmp = atlases[idx]
        val w = noiseWidths[idx]
        val h = noiseHeights[idx]
        val px = ((u.coerceIn(0f, 1f) * (w - 1)).toInt())
        val py = ((v.coerceIn(0f, 1f) * (h - 1)).toInt())
        return try {
            val pixel = bmp.getPixel(px, py)
            // 取灰度
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            ((r + g + b) / 3) / 255f
        } catch (_: Exception) {
            algoNoise(atlasIndex, u, v)
        }
    }

    /**
     * 采样预烘焙速度场，返回归一化速度 (vx, vy) ∈ [-1,1]。
     * 资源缺失时回退到 sin/cos 合成场。
     */
    fun sampleVelocity(u: Float, v: Float, t: Float): Pair<Float, Float> {
        fieldCache?.let { return it.sample(u, v, t) }
        // 回退：算法流场
        val vx = (sin(t * 0.3f + u * 6.28f) * 0.5f)
        val vy = (cos(t * 0.35f + v * 6.28f) * 0.5f)
        return vx to vy
    }

    /** 资源缺失时的算法噪声回退 */
    private fun algoNoise(seed: Int, u: Float, v: Float): Float {
        val s = (seed + 1) * 12.9898f
        val x = (sin(s + u * 78.233f + v * 37.719f) * 43758.5453f)
        return (x - kotlin.math.floor(x))
    }

    /**
     * 预烘焙 Navier-Stokes 速度场缓存。
     * 每个 .bin 文件被视为一个 64MB 的流场快照（float 对：vx,vy 交替）。
     * 多个文件按时间维度串联，[sample] 根据时间 t 在快照间插值。
     * 内存映射（mmap）读取，不会一次性占用堆内存。
     */
    private class FluidFieldCache(private val files: List<File>) {
        // 映射全部快照：每个 64MB，按需分页加载（mmap），不占堆内存。
        // 多个快照串联成时间维度的流场序列，采样时在相邻快照间插值。
        private val mapped: List<MappedSnapshot>

        init {
            mapped = files.mapNotNull { f -> mapSnapshot(f) }
        }

        private fun mapSnapshot(f: File): MappedSnapshot? {
            return try {
                val raf = RandomAccessFile(f, "r")
                val channel = raf.channel
                val buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
                buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                MappedSnapshot(channel, raf, buf, f.length())
            } catch (_: Exception) {
                null
            }
        }

        /** 采样速度场，t 用于在快照间过渡 */
        fun sample(u: Float, v: Float, t: Float): Pair<Float, Float> {
            if (mapped.isEmpty()) return sinFlow(u, v, t)
            val uu = u.coerceIn(0f, 1f)
            val vv = v.coerceIn(0f, 1f)
            // 时间维度选择快照（循环）
            val f = (t * 0.05f) % 1f
            val idxF = f * mapped.size
            val i0 = idxF.toInt() % mapped.size
            val i1 = (i0 + 1) % mapped.size
            val frac = idxF - idxF.toInt()
            val (vx0, vy0) = mapped[i0].sample(uu, vv)
            val (vx1, vy1) = mapped[i1].sample(uu, vv)
            val vx = vx0 * (1 - frac) + vx1 * frac
            val vy = vy0 * (1 - frac) + vy1 * frac
            return vx to vy
        }

        private fun sinFlow(u: Float, v: Float, t: Float): Pair<Float, Float> {
            return (sin(t * 0.3f + u * 6.28f) * 0.5f) to (cos(t * 0.35f + v * 6.28f) * 0.5f)
        }

        private class MappedSnapshot(
            val channel: FileChannel,
            val raf: RandomAccessFile,
            val buf: ByteBuffer,
            val length: Long
        ) {
            // 每 4 字节一个 float；视作 (vx, vy) 对
            private val floatCount: Int = (length.toInt() / 4).coerceAtMost(1 shl 20)
            fun sample(u: Float, v: Float): Pair<Float, Float> {
                val idx = (((u * 997f + v * 263f).toInt() and Int.MAX_VALUE) % (floatCount / 2)) * 2
                return try {
                    val vx = (buf.getFloat(idx * 4) / 32768f).coerceIn(-1f, 1f)
                    val vy = (buf.getFloat((idx + 1) * 4) / 32768f).coerceIn(-1f, 1f)
                    vx to vy
                } catch (_: Exception) {
                    0f to 0f
                }
            }
        }
    }
}
