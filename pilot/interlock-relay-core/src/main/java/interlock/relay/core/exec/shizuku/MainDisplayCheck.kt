package interlock.relay.core.exec.shizuku

/**
 * 主屏自检的纯判据：一段 shell 快照文本的解析，与建屏前后两份状态的比较。
 *
 * 这里**不碰任何 Android 类型**：读侧（`dumpsys` 的落法、执行时机、复采节奏）在
 * `RelayShellService.TrustedDisplay` 里，判定与解析全部收在本对象，JVM 单测可直接穷举。
 *
 * 「读不到」与「读到了但不是那样」是两个答案，所以 [MainState] 的每个字段都是可空或
 * 空映射：null/空 = 这一问没读到（记 `unknown`），**据此不判破坏**——证据不支持的
 * 结论不能当成失败。after 全 unknown 的自检按 `unverified` 收场（[hasEvidence]），
 * 不伪装 pass。
 */
internal object MainDisplayCheck {

    /** 读不到时的"FAIL"，且复采后仍判不出任何证据 → fail-closed 的一支收场码（[Settled.ResampleUnverified]）。 */
    internal const val RESAMPLE_UNVERIFIED = "RESAMPLE_UNVERIFIED"

    /** 用户眼前那块主屏的编号，自检口径写死 0（与服务端 [RelayShellService] 同值）。 */
    private const val MAIN_DISPLAY = 0

    /** 一个窗口的矩形（虚拟屏坐标系），纯数据供覆盖率并集计算。 */
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * 一次快照的全部读数。字段含义见各属性的注释；所有集合/可空字段缺省即「没读到」。
     */
    data class MainState(
        /** 各显示的顶层活动包名（`topResumedActivity`），按 `Display #N` 段归属；只作日志。 */
        val topPackages: Map<Int, String> = emptyMap(),
        /** 各显示段内第一条任务记录的任务号（`*Task{… #N`）；主屏 topTask 的判定用任务号。 */
        val topTaskIds: Map<Int, Int> = emptyMap(),
        /**
         * 主屏焦点窗口：null = 没读到（unknown）；`"null"` = 读到了且 `mFocusedWindow=null`
         * （焦点被清）；其余 = 焦点窗口花括号里的片段（哈希 + 包/组件）。
         */
        val focusedWindow: String? = null,
        /**
         * 焦点窗口归属的显示号（按 [win] 段里同名哈希的窗口对上号）。
         * 无法匹配（多显示、段没读到）时为 null：记 unknown，不假设它属于主屏。
         */
        val focusDisplayId: Int? = null,
        /** 输入法服务声明自己聚焦在哪个显示上（`dumpsys input_method` 的 mFocusedDisplayId）。 */
        val imeFocusedDisplayId: Int? = null,
        /** 输入法此刻是否可见（mInputShown），三态：true / false / null（没读到）。 */
        val imeShown: Boolean? = null,
        /** 本机 launcher 的包名（`cmd package resolve-activity --brief … HOME` 的最后一行）。 */
        val launcherPackage: String? = null,
        /** 各显示所属的显示组（`dumpsys display` 里能对上号的那部分），对不上就缺。 */
        val displayGroups: Map<Int, Int> = emptyMap(),
        /** 各显示上读到的窗口矩形（[win] 段）；覆盖率与焦点归属都用它。 */
        val windowBounds: Map<Int, List<Bounds>> = emptyMap(),
    )

    /**
     * 自检能观察到的不变量破坏。全部都是「建屏前后状态对不上」这一类：
     * 任一项**复采后仍在**才判（复采节奏在服务端），破坏即释放虚拟屏并标记该设备。
     */
    enum class Violation {
        /** 主屏顶层任务变了（比的是任务号，不是包名）。 */
        TOP_TASK_CHANGED,

        /**
         * 主屏焦点窗口被清空（建屏前有真实焦点、建屏后读到 null），
         * 或焦点窗口明确落在了别的显示上（[focusDisplayId] 读到且 != 主屏）。
         */
        MAIN_FOCUS_LOST,

        /** 主屏焦点窗口明确归属到另一块显示上（focusDisplayId 读到且 != 主屏）。 */
        MAIN_FOCUS_ON_OTHER_DISPLAY,

        /** launcher 被搬到了虚拟屏上。 */
        LAUNCHER_MOVED_TO_VIRTUAL,

        /** 输入法被拽到了主屏（建屏前不可见、建屏后可见且聚焦主屏）。 */
        IME_ON_MAIN,

        /** 虚拟屏上读到的窗口覆盖不足（有窗口记录且并集 < 90% 时才判）。 */
        VIRTUAL_COVERAGE_LOW,
    }

    /** 覆盖率的及格线：虚拟屏上窗口并集 ≥ 90% 视为覆盖正常。 */
    internal const val COVERAGE_PASS_RATIO = 0.9

    /**
     * 一次快照命令：六段各带 `[seg]` 标记，段内就地 grep 过滤，控制单次输出规模
     * （用户服务的命令输出有长度上限，整棵 dumpsys 不该过通道）。
     * 解析端 [parse] 认的就是这几个标记。
     */
    internal val MAIN_STATE_COMMAND = listOf(
        "echo [top]",
        "dumpsys activity activities | grep -aE 'Display #[0-9]+ \\(|topResumedActivity=|\\*Task\\{'",
        "echo [focus]",
        "dumpsys window | grep -aE 'mFocusedWindow='",
        "echo [ime]",
        "dumpsys input_method | grep -aE 'mFocusedDisplayId=|mInputShown='",
        "echo [launcher]",
        "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
        "echo [win]",
        "dumpsys window windows | grep -aE 'Window #[0-9]+ Window\\{|mDisplayId=|mFrame='",
        "echo [group]",
        "dumpsys display | grep -aiE 'Display group|mDisplayGroupId|mDisplayId='",
    ).joinToString("; ")

    private val SECTION = Regex("^\\[([a-z]+)]$")
    private val DISPLAY_SECTION = Regex("^Display #(\\d+) \\(")
    private val TOP_ACTIVITY = Regex("^topResumedActivity=ActivityRecord\\{\\S+ u\\d+ ([A-Za-z][A-Za-z0-9_.]*)/")
    private val TASK_RECORD = Regex("\\*Task\\{\\S+ #(\\d+)")
    private val FOCUSED_WINDOW = Regex("mFocusedWindow=(null|Window\\{([^}]*)\\})")
    private val WINDOW_HASH = Regex("^([0-9a-fA-F]+) ")
    private val IME_FOCUSED_DISPLAY = Regex("mFocusedDisplayId=(-?\\d+)")
    private val IME_SHOWN = Regex("mInputShown=(true|false)")
    private val LAUNCHER_COMPONENT = Regex("^([A-Za-z][A-Za-z0-9_.]+)/")
    private val GROUP_LINE = Regex("(?:Display group|mDisplayGroupId=)\\s*(-?\\d+)")
    private val DISPLAY_ID_LINE = Regex("mDisplayId=(\\d+)")
    private val WINDOW_HEADER = Regex("Window #(\\d+) Window\\{([0-9a-fA-F]+)")
    private val WINDOW_FRAME = Regex("mFrame=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]")

    /**
     * 把一段带 `[seg]` 标记的快照文本解析成 [MainState]。命令失败、段缺失、行不认，
     * 都只表现为对应字段缺省（unknown），从不抛出。
     */
    fun parse(dump: String): MainState {
        val sections = HashMap<String, StringBuilder>()
        var current: StringBuilder? = null
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            val marker = SECTION.find(line)
            if (marker != null) {
                current = StringBuilder().also { sections[marker.groupValues[1]] = it }
            } else {
                current?.appendLine(line)
            }
        }
        val top = sections["top"]?.toString().orEmpty()
        // 窗口段先解析：焦点归属（哈希 → 显示号）要拿它匹配，对不上就如实 unknown。
        val windows = parseWindows(sections["win"]?.toString().orEmpty())
        val focus = parseFocusedWindow(sections["focus"]?.toString().orEmpty())
        return MainState(
            topPackages = parseTopPackages(top),
            topTaskIds = parseTopTaskIds(top),
            focusedWindow = focus?.first,
            focusDisplayId = focus?.second?.toIntOrNull(16)?.let { windows.hashToDisplay[it] },
            imeFocusedDisplayId = IME_FOCUSED_DISPLAY.find(sections["ime"]?.toString().orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull(),
            imeShown = parseImeShown(sections["ime"]?.toString().orEmpty()),
            launcherPackage = parseLauncher(sections["launcher"]?.toString().orEmpty()),
            displayGroups = parseGroups(sections["group"]?.toString().orEmpty()),
            windowBounds = windows.boundsByDisplay,
        )
    }

    /**
     * 建屏前后两份状态对一对，返回观察到的不变量破坏（空的 = 没观察到破坏）。
     *
     * [virtualWidth]/[virtualHeight] 是虚拟屏的像素尺寸（服务端自建屏时已知）；
     * 任一非正（尺寸未知）时覆盖率按 unknown 处理，不判。
     */
    fun evaluate(
        mainDisplay: Int,
        virtualDisplay: Int,
        before: MainState?,
        after: MainState,
        virtualWidth: Int = 0,
        virtualHeight: Int = 0,
    ): Set<Violation> {
        val out = LinkedHashSet<Violation>()
        // topTask 比任务号：路由活动/别名会让顶层包名合法地换，任务号换了才是「主屏被换页」。
        val beforeTask = before?.topTaskIds?.get(mainDisplay)
        val afterTask = after.topTaskIds[mainDisplay]
        if (beforeTask != null && afterTask != null && beforeTask != afterTask) out += Violation.TOP_TASK_CHANGED
        val beforeFocus = before?.focusedWindow
        val afterFocus = after.focusedWindow
        // 焦点：只有 before 有**真实**焦点（非空、非 "null"）而 after 明确被清才判；
        // before 是 unknown 或 "null"（本来就空）都不判——null→null 是健康屏的常态。
        if (isRealFocus(beforeFocus) && afterFocus == FOCUS_CLEARED) out += Violation.MAIN_FOCUS_LOST
        // 焦点归属：after 的焦点窗口哈希在 [win] 段对上了号、却落在**别的显示**
        // 上时判——这是「焦点被虚拟屏抢走」的直接读数。归属 unknown（null）时不判：
        // 证据不支持"焦点离开主屏"就不说它离开（多显示/段缺失的无法匹配不能算破坏）。
        val afterFocusDisplay = after.focusDisplayId
        if (afterFocusDisplay != null && afterFocusDisplay != mainDisplay &&
            isRealFocus(afterFocus)
        ) {
            out += Violation.MAIN_FOCUS_ON_OTHER_DISPLAY
        }
        val launcher = after.launcherPackage
        if (launcher != null && after.topPackages[virtualDisplay] == launcher) {
            out += Violation.LAUNCHER_MOVED_TO_VIRTUAL
        }
        // IME：before 明确「不可见」、after 明确「可见且聚焦主屏」才判——用户本来在打字的
        // 主屏 IME 是常态，只有"这次建屏把它带出来"才是破坏。
        if (before?.imeShown == false &&
            after.imeShown == true &&
            after.imeFocusedDisplayId == mainDisplay
        ) {
            out += Violation.IME_ON_MAIN
        }
        // 覆盖率：读不到（无窗口记录、尺寸未知）按 unknown 不判；读到了且 < 90% 才判。
        val ratio = coverageRatio(after, virtualDisplay, virtualWidth, virtualHeight)
        if (ratio != null && ratio < COVERAGE_PASS_RATIO) out += Violation.VIRTUAL_COVERAGE_LOW
        return out
    }

    /** 焦点值是不是一条「真实焦点」：unknown（null）、被清（"null"）与空串都不算。 */
    private fun isRealFocus(focus: String?): Boolean = !focus.isNullOrEmpty() && focus != FOCUS_CLEARED

    /**
     * 这轮自检有没有**任何**已读到的证据。全 unknown 时结论只能写 unverified，
     * 不能把「什么都没读到」说成 pass（[evaluate] 对全 unknown 本来就不判，
     * 这里是让回包与日志也诚实）。
     *
     * 证据只认**建屏后快照**（[after]）——before 快照抓的是「没有虚拟屏」
     * 的常态，它有读数与"这块屏建起来之后主屏健康"无关；只看 before 会让「before 有证据、
     * after 全空」的场景被伪装成 pass。收场与回包的 pass 判据都以 after 的判定字段为准。
     *
     * 证据口径 = **参与判定的字段**（[knownFields]）：topPackages（launcher 检查在用）、
     * topTaskIds、focusedWindow、focusDisplayId、imeFocusedDisplayId、imeShown、
     * launcherPackage、windowBounds。displayGroups（显示组）只进运行日志、不参与任何
     * 判定，不算证据——按它算会把「只读到组号」的快照当成"有判定证据"。
     */
    fun hasEvidence(after: MainState): Boolean = knownFields(after) > 0

    /** 主屏自检的收场档（[settle] 的返回值， PURE，服务器与 JVM 测试同走）。 */
    sealed class Settled {
        /** 没观察到破坏，且**建屏后快照**至少有一项判定证据：pass，屏保留。 */
        data class Pass(val hasEvidence: Boolean) : Settled()

        /** 首轮见到、复采已恢复：放行、只记录（`transientRecovered`），不动标记。 */
        data class Transient(val recovered: List<Violation>) : Settled()

        /** 复采**读不到证据**：按 fail-closed 收场，不能把"没读到"当成"恢复了"。 */
        object ResampleUnverified : Settled()

        /** 复采后破坏仍在：unsafe，当场收屏。 */
        data class Unsafe(val remaining: List<Violation>) : Settled()
    }

    /**
     * 建屏后自检「首轮（[firstViolations]）+ 复采（[afterSample]）」的收场判定，
     * 主屏判据的**唯一**收口。复核：
     *
     * - 首轮干净：复采仍是 unknown-tolerant 的一问，有证据判 pass、没证据如实
     * `unverified`（此时不落 fail-closed 的 unsafe 档——**建屏本来就没观察到破坏**，
     * 普通建屏保持「屏保留、标记不动、不伪装 pass」的既有语义，见
     * [ShizukuBackend.selfCheckAfterCreate] 对 `unverified` 的两分支）；
     * - 首轮有破坏要复采：**先逐条问每个首轮破坏在复采快照上能不能再判一次**
     * （[isDecidableOn] 纯函数）——只要任一条在新快照上不可判定（决定该破坏的那枚
     * 字段复采恰好没读到，而别的字段在场），就不能把「剩下的破坏 = 0」读成"恢复了"，
     * 一律 **fail-closed 的 [Settled.ResampleUnverified]**（
     * 之前只看 [hasEvidence]>0 的整份口径，会让"首轮有破坏、复采丢了决定该破坏的字段、
     * 只剩别的可读字段"的场景被判 Transient）。全部可判定才进 [evaluate]：
     * 复采破坏仍在 → unsafe；破坏已消 → Transient（只记录，不动标记）。整份空快照
     * （[hasEvidence]==0）是上述判据的退化情形，也走 fail-closed。
     */
    fun settle(
        virtualDisplay: Int,
        before: MainState?,
        firstViolations: Set<Violation>,
        afterSample: MainState,
        virtualWidth: Int,
        virtualHeight: Int,
    ): Settled {
        if (firstViolations.isEmpty()) {
            // 首轮干净：remaining 同为空（同一函数同一判据），没有复采项要转翻。
            return Settled.Pass(hasEvidence = hasEvidence(afterSample))
        }
        // 逐条问"复采能不能再判一次这条破坏"。任一条不可判定 →
        // fail-closed（不能让一条读丢了关键字段的快照把"主屏正被带坏"读成"恢复了"）。
        // 整份没有可读字段（hasEvidence==0 退化情形）自然逐条全不可判定，同走一支。
        if (firstViolations.any { !isDecidableOn(it, virtualDisplay, before, afterSample, virtualWidth, virtualHeight) }) {
            return Settled.ResampleUnverified
        }
        val remaining = evaluate(MAIN_DISPLAY, virtualDisplay, before, afterSample, virtualWidth, virtualHeight)
        return if (remaining.isEmpty()) {
            Settled.Transient(firstViolations.toList())
        } else {
            Settled.Unsafe(remaining.toList())
        }
    }

    /**
     * 单条首轮破坏在**复采快照**上能不能再判一次（纯函数 JVM 测试穷举）。
     * 可判定 ≠ 破坏仍在——只问证据是否齐：齐了交回 [evaluate] 裁决，缺了就是"读不到"，
     * 读不到不能被读成"恢复了"。
     *
     * - [Violation.TOP_TASK_CHANGED]：before 与复采的该显示任务号都非空（两条都是判定
     * 输入）；
     * - [Violation.MAIN_FOCUS_LOST]：复采 [MainState.focusedWindow] 非空（字符串 `"null"`
     * 算已读到——那是「焦点被清」这条**证据本身**）；
     * - [Violation.MAIN_FOCUS_ON_OTHER_DISPLAY]：复采 `focusedWindow != null` 且
     * `focusDisplayId != null`（两个都是判定输入）；
     * - [Violation.LAUNCHER_MOVED_TO_VIRTUAL]：复采 `launcherPackage != null` 且
     * **虚拟屏** topPackages 段有读数（`topPackages[virtualDisplay] != null`——这条
     * 破坏要同时看 launcher 与虚拟屏 top 段；只看主屏段会把"虚拟屏段被截断"读成
     * "launcher 没被搬走"，正是 复核抓到的那条 fail-open 缝）；
     * - [Violation.IME_ON_MAIN]：复采 `imeShown == false`，或 `imeShown == true` 且
     * `imeFocusedDisplayId != null`（false 时归属字段用不上，true 时要用它匹配）；
     * - [Violation.VIRTUAL_COVERAGE_LOW]：[coverageRatio] 读得到（有窗口记录且尺寸已知）。
     */
    internal fun isDecidableOn(
        violation: Violation,
        virtualDisplay: Int,
        before: MainState?,
        afterSample: MainState,
        virtualWidth: Int,
        virtualHeight: Int,
    ): Boolean = when (violation) {
        Violation.TOP_TASK_CHANGED ->
            before?.topTaskIds?.get(MAIN_DISPLAY) != null &&
                afterSample.topTaskIds[MAIN_DISPLAY] != null

        Violation.MAIN_FOCUS_LOST -> afterSample.focusedWindow != null

        Violation.MAIN_FOCUS_ON_OTHER_DISPLAY ->
            afterSample.focusedWindow != null && afterSample.focusDisplayId != null

        Violation.LAUNCHER_MOVED_TO_VIRTUAL ->
            afterSample.launcherPackage != null && afterSample.topPackages[virtualDisplay] != null

        Violation.IME_ON_MAIN ->
            afterSample.imeShown == false ||
                (afterSample.imeShown == true && afterSample.imeFocusedDisplayId != null)

        Violation.VIRTUAL_COVERAGE_LOW ->
            coverageRatio(afterSample, virtualDisplay, virtualWidth, virtualHeight) != null
    }

    private fun knownFields(state: MainState?): Int {
        if (state == null) return 0
        return listOf(
            state.topPackages.isNotEmpty(),
            state.topTaskIds.isNotEmpty(),
            state.focusedWindow != null,
            state.focusDisplayId != null,
            state.imeFocusedDisplayId != null,
            state.imeShown != null,
            state.launcherPackage != null,
            state.windowBounds.isNotEmpty(),
        ).count { it }
    }

    /**
     * 虚拟屏上窗口矩形的并集覆盖率。三态：null = 读不到（该显示没有任何窗口记录、
     * 或尺寸未知）——记 unknown，不判；否则返回 并集面积 / 显示面积。
     */
    fun coverageRatio(state: MainState, display: Int, width: Int, height: Int): Double? {
        if (width <= 0 || height <= 0) return null
        val rects = state.windowBounds[display] ?: return null
        if (rects.isEmpty()) return null
        val total = width.toLong() * height.toLong()
        if (total <= 0L) return null
        return unionArea(rects).toDouble() / total
    }

    /**
     * 矩形并集面积（Long，防 1080×2340 级别的乘积溢出）。按 x 带扫描、y 区间合并，
     * 精确并集——矩形数量级是个位数到几十个，无需更快的方法。
     */
    internal fun unionArea(rects: List<Bounds>): Long {
        if (rects.isEmpty()) return 0L
        val xs = rects.flatMap { listOf(it.left, it.right) }.distinct().sorted()
        var area = 0L
        for (k in 0 until xs.lastIndex) {
            val x0 = xs[k]
            val x1 = xs[k + 1]
            if (x1 <= x0) continue
            val ys = rects.filter { it.left <= x0 && it.right >= x1 }
                .map { it.top.toLong() to it.bottom.toLong() }
                .sortedBy { it.first }
            var covered = 0L
            var runStart = 0L
            var runEnd = 0L
            var open = false
            for ((top, bottom) in ys) {
                when {
                    !open -> {
                        runStart = top; runEnd = bottom; open = true
                    }

                    top > runEnd -> {
                        covered += runEnd - runStart
                        runStart = top; runEnd = bottom
                    }

                    else -> runEnd = maxOf(runEnd, bottom)
                }
            }
            if (open) covered += runEnd - runStart
            area += covered * (x1 - x0)
        }
        return area
    }

    private class ParsedWindows(val boundsByDisplay: Map<Int, List<Bounds>>, val hashToDisplay: Map<Int, Int>)

    /**
     * [win] 段：每个 `Window #N Window{hash …}` 开一段，随后的 `mDisplayId=` 与
     * `mFrame=` 归属这一段。哈希同时给焦点归属用（[MainState.focusDisplayId]）。
     */
    private fun parseWindows(win: String): ParsedWindows {
        val bounds = HashMap<Int, MutableList<Bounds>>()
        val hashToDisplay = HashMap<Int, Int>()
        var currentDisplay: Int? = null
        var currentHash: Int? = null
        var currentBounds: Bounds? = null

        fun flush() {
            val display = currentDisplay ?: return
            currentBounds?.let { bounds.getOrPut(display) { mutableListOf() }.add(it) }
            currentHash?.let { hashToDisplay.putIfAbsent(it, display) }
        }

        for (raw in win.lineSequence()) {
            val line = raw.trim()
            val header = WINDOW_HEADER.find(line)
            if (header != null) {
                flush()
                currentDisplay = null
                currentHash = header.groupValues[2].toIntOrNull(16)
                currentBounds = null
                continue
            }
            DISPLAY_ID_LINE.find(line)?.let { currentDisplay = it.groupValues[1].toIntOrNull() }
            WINDOW_FRAME.find(line)?.let { match ->
                currentBounds = Bounds(
                    match.groupValues[1].toIntOrNull() ?: return@let,
                    match.groupValues[2].toIntOrNull() ?: return@let,
                    match.groupValues[3].toIntOrNull() ?: return@let,
                    match.groupValues[4].toIntOrNull() ?: return@let,
                )
            }
        }
        flush()
        return ParsedWindows(bounds, hashToDisplay)
    }

    private fun parseTopPackages(top: String): Map<Int, String> {
        val out = HashMap<Int, String>()
        var display = -1
        for (raw in top.lineSequence()) {
            val line = raw.trim()
            DISPLAY_SECTION.find(line)?.let { display = it.groupValues[1].toInt() }
            if (display >= 0 && !out.containsKey(display)) {
                TOP_ACTIVITY.find(line)?.let { out[display] = it.groupValues[1] }
            }
        }
        return out
    }

    /** 每个显示段里第一条任务记录当它的顶层任务号；没有任务行（或没有段首）就缺。 */
    private fun parseTopTaskIds(top: String): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        var display = -1
        for (raw in top.lineSequence()) {
            val line = raw.trim()
            DISPLAY_SECTION.find(line)?.let { display = it.groupValues[1].toInt() }
            if (display >= 0 && !out.containsKey(display)) {
                TASK_RECORD.find(line)?.let { it.groupValues[1].toIntOrNull()?.let { id -> out[display] = id } }
            }
        }
        return out
    }

    /** 回 (焦点值, 焦点窗口哈希)；哈希给焦点归属匹配用，值 "null" 表示焦点被清。 */
    private fun parseFocusedWindow(focus: String): Pair<String, String>? {
        val match = FOCUSED_WINDOW.find(focus) ?: return null
        if (match.groupValues[1] == "null") return FOCUS_CLEARED to ""
        val token = match.groupValues[2].trim()
        val hash = WINDOW_HASH.find(token)?.groupValues?.get(1)
        return token to (hash ?: "")
    }

    private fun parseImeShown(ime: String): Boolean? = when (IME_SHOWN.find(ime)?.groupValues?.get(1)) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /** `resolve-activity --brief` 的组件行取包名；第一行的 `priority=…` 形状不含 `/`，自然跳过。 */
    private fun parseLauncher(launcher: String): String? =
        launcher.lineSequence()
            .map { it.trim() }
            .filter { !it.contains('=') && it.contains('/') }
            .mapNotNull { LAUNCHER_COMPONENT.find(it)?.groupValues?.get(1) }
            .lastOrNull()

    /**
     * 显示 → 显示组：`dumpsys display` 的段落里「组」与「显示号」先后出现时才对得上号，
     * 对不上就缺——这一项只进日志，不参与判定。
     */
    private fun parseGroups(group: String): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        var currentGroup: Int? = null
        for (raw in group.lineSequence()) {
            val line = raw.trim()
            GROUP_LINE.find(line)?.let { currentGroup = it.groupValues[1].toIntOrNull() }
            DISPLAY_ID_LINE.find(line)?.let { id ->
                val groupNow = currentGroup ?: return@let
                id.groupValues[1].toIntOrNull()?.let { out[it] = groupNow }
            }
        }
        return out
    }

    /** 读到了但值为 null 的焦点窗口，在快照里占这个字面量。 */
    internal const val FOCUS_CLEARED = "null"
}
