package com.liquidglass.desktop.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 桌面版本地音乐扫描器。
 *
 * - 使用 [Files.walkFileTree] 递归扫描指定目录下的 .mp3 文件
 * - 通过 [onProgress] 回调实时反馈进度，UI 可即时显示
 * - 提取文件名作为标题（简单可靠，不依赖第三方 ID3 解析库）
 * - 文件大小估算时长（按 128kbps CBR 近似），仅为 UI 显示，不准确
 *
 * 仅扫描 .mp3：JLayer 核心库仅支持 MP3 解码。FLAC/ape 等格式需引入额外解码器，
 * 暂不支持。
 */
object LocalMusicScanner {

    private val logger = Logger.getLogger("LocalMusicScanner")

    /** 扫描进度回调数据 */
    data class ScanProgress(
        val scanned: Int,        // 已扫描文件数
        val found: Int,          // 已识别音乐数
        val currentPath: String  // 当前正在扫描的路径
    )

    /**
     * 扫描指定目录下所有 mp3 文件，返回 [Song] 列表。
     * 应在 IO 协程中调用。
     *
     * @param rootDir 根目录绝对路径
     * @param onProgress 每扫描到一个 mp3 时回调（在 IO 线程）
     */
    suspend fun scanList(
        rootDir: String,
        onProgress: (ScanProgress) -> Unit = {}
    ): List<Song> = withContext(Dispatchers.IO) {
        val root = try {
            Path.of(rootDir)
        } catch (_: Exception) {
            logger.log(Level.WARNING, "scanList: invalid path: $rootDir")
            return@withContext emptyList()
        }
        if (!Files.isDirectory(root)) {
            logger.log(Level.WARNING, "scanList: not a directory: $rootDir")
            return@withContext emptyList()
        }
        val result = mutableListOf<Song>()
        var scanned = 0

        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                scanned++
                val name = file.fileName.toString().lowercase()
                if (name.endsWith(".mp3")) {
                    val song = fileToSong(file, attrs)
                    if (song != null) {
                        result.add(song)
                        onProgress(ScanProgress(scanned, result.size, file.toString()))
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                return FileVisitResult.CONTINUE
            }
        })
        result
    }

    /**
     * 把 mp3 文件转 [Song]。
     * - 标题：去掉 .mp3 扩展名后的文件名
     * - 艺术家：从文件名 "艺术家 - 标题.mp3" 格式尝试拆分；失败留空
     * - 时长：按文件大小 / 128kbps 估算（仅用于 UI 显示）
     * - streamUrl：用 file.toAbsolutePath() 作为本地播放源
     */
    private fun fileToSong(file: Path, attrs: BasicFileAttributes): Song? {
        val fileName = file.fileName.toString()
        val displayName = fileName.removeSuffix(".mp3").removeSuffix(".MP3")
        var title = displayName
        var artist = ""

        val dashIdx = displayName.indexOf(" - ")
        if (dashIdx > 0 && dashIdx < displayName.length - 3) {
            artist = displayName.substring(0, dashIdx).trim()
            title = displayName.substring(dashIdx + 3).trim()
        }

        // 估算时长：按 128kbps CBR（size_bytes * 8 / 128000 * 1000）
        val sizeBytes = attrs.size()
        val estimatedMs = if (sizeBytes > 0) (sizeBytes * 8L * 1000L) / 128_000L else 0L

        return Song(
            id = "local://${file.toAbsolutePath()}",
            title = if (title.isNotBlank()) title else displayName,
            artist = artist,
            album = "",
            durationMs = estimatedMs,
            coverUrl = "",
            coverUri = "",
            source = Source.LOCAL,
            streamUrl = file.toAbsolutePath().toString(),
            fee = 0
        )
    }
}
