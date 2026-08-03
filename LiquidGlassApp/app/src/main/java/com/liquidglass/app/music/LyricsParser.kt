package com.liquidglass.app.music

/**
 * 歌词解析器。
 *
 * - LRC（逐行）：`[mm:ss.xx]歌词` → [LyricLine]
 * - YRC（网易云逐字）：`[start,dur](t,dur,0)字(t,dur,0)字...` → [YrcLine]
 * - 翻译：tlyric / ytlrc 也是 LRC 格式，按时间戳对齐到主歌词
 *
 * 逐字渲染优先用 yrc；无 yrc 时回退 lrc 逐行滚动。
 */
object LyricsParser {

    private val LRC_LINE = Regex("""\[(\d+):(\d+(?:\.\d+)?)\](.*)""")
    private val YRC_HEADER = Regex("""^\[(\d+),(\d+)\]""")
    private val YRC_CHAR = Regex("""\((\d+),(\d+),\d+\)([^\(\[]*)""")

    /** 解析逐行 LRC + 翻译（按时间戳合并） */
    fun parseLrc(lrcText: String, translationText: String = ""): List<LyricLine> {
        val transMap = mutableMapOf<Long, String>()
        if (translationText.isNotBlank()) {
            for (line in translationText.lines()) {
                val m = LRC_LINE.find(line) ?: continue
                val ms = timeStrToMs(m.groupValues[1], m.groupValues[2])
                val content = m.groupValues[3].trim()
                if (content.isNotEmpty()) transMap[ms] = content
            }
        }
        val result = mutableListOf<LyricLine>()
        for (line in lrcText.lines()) {
            val m = LRC_LINE.find(line) ?: continue
            val ms = timeStrToMs(m.groupValues[1], m.groupValues[2])
            val content = m.groupValues[3].trim()
            if (content.isEmpty()) continue
            // 翻译对齐：精确匹配，否则取最近的 ±200ms 内
            val trans = transMap[ms] ?: findNearest(transMap, ms, 200L)
            result.add(LyricLine(ms, content, trans ?: ""))
        }
        return result.sortedBy { it.timeMs }
    }

    /** 解析网易云逐字 YRC + 翻译 */
    fun parseYrc(yrcText: String, translationText: String = ""): List<YrcLine> {
        // 翻译仍按逐行 LRC 对齐到每行的起始时间
        val transMap = mutableMapOf<Long, String>()
        if (translationText.isNotBlank()) {
            for (line in translationText.lines()) {
                val m = LRC_LINE.find(line) ?: continue
                val ms = timeStrToMs(m.groupValues[1], m.groupValues[2])
                val content = m.groupValues[3].trim()
                if (content.isNotEmpty()) transMap[ms] = content
            }
        }
        val result = mutableListOf<YrcLine>()
        for (line in yrcText.lines()) {
            val header = YRC_HEADER.find(line) ?: continue
            val lineStart = header.groupValues[1].toLong()
            val lineDur = header.groupValues[2].toLong()
            val chars = mutableListOf<YrcChar>()
            for (cm in YRC_CHAR.findAll(line)) {
                val cs = cm.groupValues[1].toLong()
                val cd = cm.groupValues[2].toLong()
                val ct = cm.groupValues[3]
                if (ct.isNotEmpty()) chars.add(YrcChar(cs, cd, ct))
            }
            if (chars.isEmpty()) continue
            val trans = transMap[lineStart] ?: findNearest(transMap, lineStart, 300L)
            result.add(YrcLine(lineStart, lineDur, chars, trans ?: ""))
        }
        return result.sortedBy { it.startMs }
    }

    private fun timeStrToMs(min: String, sec: String): Long {
        val m = min.toLong()
        val s = sec.toDouble()
        return (m * 60000 + s * 1000).toLong()
    }

    private fun findNearest(map: Map<Long, String>, target: Long, toleranceMs: Long): String? {
        var best: String? = null
        var bestDelta = Long.MAX_VALUE
        for ((ms, content) in map) {
            val delta = Math.abs(ms - target)
            if (delta <= toleranceMs && delta < bestDelta) {
                bestDelta = delta
                best = content
            }
        }
        return best
    }
}
