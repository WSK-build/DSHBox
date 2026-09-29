package com.dshbox.app.sandbox

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * DSH 进程日志的**按启动分段**写入器。
 *
 * ## 为什么需要它
 *
 * DSH 进程日志原本是"单文件 + 2MB 轮转"（见 [SandboxProcessRunner] 的 `appendRotated`）：
 * 跨启动连续追加、**没有任何段落边界**。于是"某一次启动"在文件里根本不可定位 ——
 * 面板按固定字节数取尾部时，一个窗口里往往挤着好几次启动（正常一次启动只产出 1~2KB）。
 *
 * 这里改成**按次数循环**：每次启动先写一条标记行开启新的一段，只保留最近 [MAX_SEGMENTS] 段；
 * 单段超过 [MAX_SEGMENT_BYTES] 时**丢段头、留段尾**（崩溃现场通常在末尾）。标记行本身永远保留
 * ——它是分段的依据，也是界面上的红色时间戳分隔（[isMarker]）。
 *
 * ## 与旧策略的关系
 *
 * 只作用于 DSH 这一份日志（`process-dsh.log`）；其余角色日志仍走原来的体积轮转，
 * `.prev` 照旧保留（诊断页导出仍含它）。两条规则刻意分开，互不影响。
 *
 * 纯 `File` 操作，可直接在 JVM 单测里跑。
 */
class BootSegmentedLog(
    private val file: File,
    private val maxSegments: Int = MAX_SEGMENTS,
    private val maxSegmentBytes: Int = MAX_SEGMENT_BYTES,
) {

    /** 当前段已写入的字节数（含标记行），用于判断何时裁段。 */
    private var currentSegmentBytes = 0L

    /**
     * 开启新的一段：写一条标记行，并把总段数收敛到 [maxSegments] 以内。
     *
     * 必须在子进程输出**之前**调用（见 `SandboxProcessRunner.start`），
     * 这样标记行一定排在这次启动的日志前面。
     */
    fun beginBoot() {
        synchronized(file) {
            runCatching {
                file.parentFile?.mkdirs()
                // 先按段收敛（少留一段给新段腾位），再写标记行。
                val kept = splitSegments(readLines())
                    .takeLast(maxSegments - 1)
                    .flatMap { trimSegment(it) }
                val marker = markerText(System.currentTimeMillis())
                rewrite(kept + marker)
                currentSegmentBytes = marker.length + 1L
            }
        }
    }

    /** 追加一行子进程输出；段内容超过上限时裁剪该段（只重写最后一段，前面的已收敛）。 */
    fun append(line: String) {
        synchronized(file) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line + "\n")
                currentSegmentBytes += line.length + 1L
                if (currentSegmentBytes <= maxSegmentBytes) return@runCatching
                val segments = splitSegments(readLines())
                if (segments.isEmpty()) return@runCatching
                val head = segments.dropLast(1).flatten()
                val tail = trimSegment(segments.last())
                rewrite(head + tail)
                currentSegmentBytes = tail.sumOf { it.length + 1L }
            }
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 段内裁剪：**段首的标记行永远保留**，其余从尾部往前按整行收，直到接近 [maxSegmentBytes]。
     *
     * 若单行本身就超过上限（DSH 偶发打印超长行），则把那行**从头部截断**只留尾部 ——
     * 这样既有界，又不会把标记行连带丢掉（丢了标记行，这一段在界面上就不再是独立一段）。
     */
    private fun trimSegment(segment: List<String>): List<String> {
        if (segment.isEmpty()) return segment
        val header = if (isMarker(segment[0])) listOf(segment[0]) else emptyList()
        var bytes = header.sumOf { it.length + 1L }
        val tail = ArrayDeque<String>()
        for (i in segment.indices.reversed()) {
            if (header.isNotEmpty() && i == 0) break
            val size = segment[i].length + 1L
            if (bytes + size > maxSegmentBytes) break
            tail.addFirst(segment[i])
            bytes += size
        }
        if (tail.isEmpty() && segment.size > header.size) {
            val budget = (maxSegmentBytes - bytes).coerceAtLeast(1L).toInt()
            tail.addFirst(segment.last().takeLast(budget))
        }
        return header + tail
    }

    /** 按标记行切段；开头若有标记前内容（历史遗留），它自成第 0 段。 */
    private fun splitSegments(lines: List<String>): List<List<String>> {
        if (lines.isEmpty()) return emptyList()
        val starts = mutableListOf<Int>()
        if (!isMarker(lines[0])) starts += 0
        lines.forEachIndexed { index, line -> if (isMarker(line)) starts += index }
        return starts.mapIndexed { index, start ->
            val end = if (index + 1 < starts.size) starts[index + 1] else lines.size
            lines.subList(start, end)
        }
    }

    private fun readLines(): List<String> {
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
        if (text.isEmpty()) return emptyList()
        return text.split("\n").dropLastWhile { it.isEmpty() }
    }

    private fun rewrite(lines: List<String>) {
        file.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    companion object {
        /** 保留的启动段数（= 面板上"最近 N 次启动"里的 N）。 */
        const val MAX_SEGMENTS = 10

        /** 单段字节上限：超出就丢段头、留段尾。 */
        const val MAX_SEGMENT_BYTES = 50 * 1024

        /** 标记行的固定骨架（界面据此把该行渲染成红色分隔）。 */
        private const val MARKER_HEAD = "===== DSH boot "
        private const val MARKER_TAIL = " ====="

        private val MARKER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /**
         * 完整形状，含时间戳。
         *
         * 之所以连时间戳一起校验：日志里随时可能冒出一行"长得像分隔"的文本，
         * 只比首尾就会把它当成段边界 —— 段边界一旦错，界面上的"哪段是哪次启动"就跟着错。
         */
        private val MARKER_RE =
            Regex("""^===== DSH boot \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} =====$""")

        /** 界面上用于分段的判定：只有我们自己写的标记行才算。 */
        fun isMarker(line: String): Boolean = MARKER_RE.matches(line.trim())

        /** 标记行正文，形如 `===== DSH boot 2026-09-26 18:42:07 =====`。 */
        fun markerText(atMillis: Long): String {
            val at = Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()).format(MARKER_TIME)
            return "$MARKER_HEAD$at$MARKER_TAIL"
        }
    }
}
