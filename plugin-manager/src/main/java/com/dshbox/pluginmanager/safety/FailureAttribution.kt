package com.dshbox.pluginmanager.safety

/** 一次失败归因命中的条目。 */
data class FailureHit(
    /** loader 条目 id（失败行里有的话）。 */
    val id: String?,
    /** 包名；可能带子路径（`@scope/pkg/sub`）。 */
    val name: String?,
    val reason: String,
    val kind: Kind,
) {
    enum class Kind {
        /** `failed to <stage> loader entry <id> (<name>): <reason>` —— 最精确的一类。 */
        APPLY,

        /** `N entries did not activate` 之后逐行点名的条目。 */
        DID_NOT_ACTIVATE,

        /** `plugin(s) failed to load: a, b` —— 只有名字，没有 id。 */
        FAILED_TO_LOAD,
    }

    /** 用于展示的标题：有包名用包名，否则用 id。 */
    val title: String get() = name ?: id ?: "(unknown)"

    /** 去重键：同一个插件可能同时以 id 与包名出现两次，归一到一条记录。 */
    internal val dedupeKey: String get() = id ?: name.orEmpty()
}

/**
 * 从 DSH 进程输出里解析「哪个插件加载失败」。
 *
 * DSH 没有结构化的失败事件，失败信息只出现在它的输出文本里，因此这里只能
 * 按已知的三种写法做正则匹配：
 *
 * 1. `failed to <stage> loader entry <id> (<name>): <detail>`
 * 2. `<N> entries did not activate`，随后**顶格**逐行 `<entry>: <reason>`
 * 3. `plugin(s) failed to load: a, b`
 *
 * 三条与失败形态直接相关的纪律（都来自真实日志形态）：
 *
 * - **取链尾**：apply 失败是链式的（`include` → 中间层 → 真正的插件），一行里
 *   可能有多个 `failed to apply loader entry`。必须取**最后一个**匹配——第一个是
 *   `include`，那是整棵插件树的根，停用它等于把全部插件关掉。
 * - **点名行是顶格的**：`did not activate` 之后的条目行不带缩进，缩进行是栈帧。
 * - **在分号处截断**：`failed to load` 那行后面常接一句解释
 *   （`… @scope/beta; Cordis startup failed because …`），不截断会把解释当成包名。
 *
 * 只认**最后一组**失败：失败可能反复发生（重试、链式），旧的那组对应的插件可能
 * 已经修好，拿它去隔离会误伤。真实日志里聚合行（`loader entries failed to apply`）
 * 可能出现在明细之后，因此它只在**完全找不到其它信号**时作为兜底起点。
 */
object FailureAttribution {

    /** 聚合失败的收尾行：仅作兜底起点，不算失败信号本身。 */
    private const val AGGREGATE_MARK = "loader entries failed to apply"

    private val APPLY_RE =
        Regex("""failed to \S+ loader entry (\S+) \((.*?)\): *(.*)$""")
    private val FAILED_TO_LOAD_RE =
        Regex("""plugin\(s\) failed to load: *([^\n;]*)""")
    private val DID_NOT_ACTIVATE_RE =
        Regex("""(\d+) entries did not activate""")

    /** 顶格的点名行：`name: reason`。缩进行是栈，不在这里匹配。 */
    private val NAMED_ENTRY_RE =
        Regex("""^(\S.*?) *: +(.*)$""")

    /** 该行是否是一条失败信号（用于定位"最后一组"）。 */
    fun isFailureLine(line: String): Boolean =
        APPLY_RE.containsMatchIn(line) ||
            FAILED_TO_LOAD_RE.containsMatchIn(line) ||
            DID_NOT_ACTIVATE_RE.containsMatchIn(line)

    /**
     * 解析失败条目。
     *
     * 关键：**按"一次启动尝试"分段**，而不是只看最后一行。DSH 一次失败会打印
     * 多行报告（`failed to import loader entry bad-a …` 与 `failed to apply loader entry bad-b …`
     * 是**同一次**失败里的两条），只取最后一行会漏掉前一条，导致要重启两轮才能救回来
     * （真机上实测：一轮只隔离了一个插件，DSH 仍然起不来，用户等了两三分钟）。
     *
     * 每次尝试都以 `Node.js v…` 结尾（失败退出时 node 会打印版本），这天然就是分段标记：
     * 取**最后一个**这类尾行之前、再往前到上一个尾行之间的内容 = 最近一次尝试的完整报告。
     * 找不到尾行（进程还在跑）时退回"从最后一个失败信号开始"。
     */
    fun parse(lines: List<String>): List<FailureHit> {
        val segment = lastAttemptSegment(lines)
        val hits = mutableListOf<FailureHit>()
        var index = 0
        while (index < segment.size) {
            val line = segment[index].trimEnd('\r')

            // 链式失败取**最内层**。用"apply 形状"的最后一次出现定位，不能按字面
            // 前缀 `failed to ` 切——原因文本本身可能是 `failed to import module 'x'`，
            // 那样切出来的一段不再是 apply 形状，整行会被丢掉（认不出任何插件）。
            val chainFrom = CHAIN_SHAPE_RE.findAll(line).lastOrNull()?.range?.first ?: -1
            val target = if (chainFrom >= 0) line.substring(chainFrom) else line
            APPLY_RE.find(target)?.let { match ->
                hits.add(
                    FailureHit(
                        id = normalizeId(match.groupValues[1]),
                        name = match.groupValues[2].trim().ifBlank { null },
                        reason = match.groupValues[3].trim(),
                        kind = FailureHit.Kind.APPLY,
                    ),
                )
            } ?: FAILED_TO_LOAD_RE.find(line)?.let { match ->
                match.groupValues[1]
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { item ->
                        hits.add(
                            FailureHit(
                                id = null,
                                name = item,
                                reason = "",
                                kind = FailureHit.Kind.FAILED_TO_LOAD,
                            ),
                        )
                    }
            }

            if (DID_NOT_ACTIVATE_RE.containsMatchIn(line)) {
                index = collectNamedEntries(segment, index + 1, hits)
                continue
            }
            index++
        }
        return hits.distinctBy { it.dedupeKey }
    }

    /**
     * 最近一次启动尝试的那段日志。
     *
     * 尾行 = `Node.js v…`（node 退出时打印）。取最后一处尾行**之前**的整段，
     * 起点是再上一处尾行之后（没有就从头）。
     */
    private fun lastAttemptSegment(lines: List<String>): List<String> {
        val trailers = lines.indices.filter { NODE_TRAILER_RE.containsMatchIn(lines[it]) }
        if (trailers.isEmpty()) {
            // 没有尾行：进程可能还在跑，退回到"最后一个失败信号之后"。
            val lastMarker = lines.indexOfLast { isFailureLine(it) }
            return if (lastMarker >= 0) lines.subList(lastMarker, lines.size) else emptyList()
        }
        val lastTrailer = trailers.last()
        val start = trailers.takeIf { it.size >= 2 }?.let { it[it.size - 2] + 1 } ?: 0
        return lines.subList(start, lastTrailer)
    }

    /** 从文本解析（便于单测与从文件读出后调用）。 */
    fun parse(text: String): List<FailureHit> = parse(text.split('\n'))

    /**
     * 日志里已经出现多少次「一次启动尝试结束」。
     *
     * 判定**本次**尝试是否失败必须用它，而不是"日志里有没有失败文本"：旧尝试的失败
     * 文本会一直留在文件里，只看文本等于把历史当成现在——真机上表现为刚起步几秒就
     * 被判失败并抢着重启（一轮还没跑完就再来一轮），以及 DSH 已经起来之后又白隔离一次。
     *
     * 每次尝试结束时 node 都会打印 `Node.js v…`，这个计数天然把"尝试"分开：
     * 计数变大 = 有一次尝试结束了，而它是不是本次，由调用方在启动时记下基线来判断。
     */
    fun trailerCount(text: String): Int = trailerCount(text.split('\n'))

    fun trailerCount(lines: List<String>): Int = lines.count { NODE_TRAILER_RE.containsMatchIn(it) }

    /**
     * 最近一次尝试里的失败原文（诊断用，写进 logcat 便于事后核对"到底是谁坏了"）。
     *
     * 只截前几行：失败报告可能有几十行栈，真正的点名行在最前面。
     */
    fun failureSnippet(text: String, maxLines: Int = 4, maxChars: Int = 600): String {
        val segment = lastAttemptSegment(text.split('\n'))
        val picked = segment.filter { isFailureLine(it) }
            .ifEmpty { segment.filter { it.isNotBlank() } }
        val joined = picked.take(maxLines).joinToString(" ⏎ ") { it.trim().take(180) }
        return if (joined.length > maxChars) joined.take(maxChars) + "…" else joined
    }

    /**
     * 收集 `N entries did not activate` 之后的点名行，返回下一个待处理行号。
     *
     * 顶格的 `<entry>: <reason>` 才算条目；缩进行是前一条的栈帧，跳过；
     * 出现新的失败信号或明显的新段落即停止。
     */
    private fun collectNamedEntries(
        lines: List<String>,
        from: Int,
        into: MutableList<FailureHit>,
    ): Int {
        var cursor = from
        var collected = 0
        while (cursor < lines.size) {
            val candidate = lines[cursor].trimEnd('\r')
            when {
                candidate.isBlank() -> cursor++
                candidate.startsWith(" ") || candidate.startsWith("\t") -> cursor++
                isFailureLine(candidate) -> return cursor
                else -> {
                    val named = NAMED_ENTRY_RE.find(candidate)
                    if (named == null) return cursor
                    val name = named.groupValues[1].trim()
                    val reason = named.groupValues[2].trim()
                    if (name.isEmpty()) return cursor
                    into.add(
                        FailureHit(
                            id = null,
                            name = name,
                            reason = reason,
                            kind = FailureHit.Kind.DID_NOT_ACTIVATE,
                        ),
                    )
                    collected++
                    cursor++
                    // 点名列表不会太长；设上限避免把后续无关内容整段吞掉。
                    if (collected >= MAX_NAMED_ENTRIES) return cursor
                }
            }
        }
        return cursor
    }

    /**
     * 去掉 id 上的引号。
     *
     * DSH 的层语法允许带引号的 id，我们写层时会给非裸标量的 id 加引号，
     * 这里把读到的形态还原成裸值，便于与层里的条目比对。
     */
    private fun normalizeId(raw: String): String {
        val trimmed = raw.trim().trimEnd(':')
        if (trimmed.length >= 2 &&
            ((trimmed.startsWith("'") && trimmed.endsWith("'")) ||
                (trimmed.startsWith("\"") && trimmed.endsWith("\"")))
        ) {
            return trimmed.substring(1, trimmed.length - 1)
        }
        return trimmed
    }

    /** 单次失败最多点名这么多条目，防御异常日志。 */
    private const val MAX_NAMED_ENTRIES = 50

    /** 链式失败的分段形状：只有它出现的位置才算"新的一层"。 */
    private val CHAIN_SHAPE_RE = Regex("""failed to \S+ loader entry""")

    /** 一次启动尝试的尾行（node 退出时打印版本号）。 */
    private val NODE_TRAILER_RE = Regex("""(^|\s)Node\.js v\d""")
}
