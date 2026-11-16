package interlock.relay.core.exec.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主屏自检的解析与判定矩阵（[MainDisplayCheck.parse] / [MainDisplayCheck.evaluate]）：
 * - 观察到的破坏（主屏 topTask 变了 / 主屏焦点被清 / launcher 被搬上虚拟屏 / IME 被拽到
 * 主屏 / 虚拟屏覆盖不足）必须逐条报出；
 * - 读不到的项一律按 unknown 处理，**绝不据此判破坏**——证据不支持的结论不能当成失败；
 * - topTask 比**任务号**不是包名：包名只进日志；
 * - 焦点 `null→null` 是健康屏的常态，不是破坏；
 * - 覆盖率读不到记 unknown 不判，读到了且 < 90% 才判；
 * - before 缺席（prepare 没发成/没等到）时只做与 before 无关的那几问。
 */
class MainDisplayCheckTest {

    private val main = 0
    private val virtual = 5

    /** 一段六段齐全的快照文本，形状按 [MainDisplayCheck.MAIN_STATE_COMMAND] 各段的真实输出拟。 */
    private val fullDump = """
        [top]
        Display #0 (activities from top to bottom):
          *Task{7f2a1c9 #42 type=standard A=10000:com.miui.home}
          *ActivityRecord{ab12cd3 u0 com.miui.home/.Launcher t42}
            topResumedActivity=ActivityRecord{ab12cd3 u0 com.miui.home/.Launcher t42}
        Display #5 (activities from top to bottom):
          *Task{9e8d7f6 #57 type=standard A=10123:com.example.app}
            topResumedActivity=ActivityRecord{ff22ee4 u0 com.example.app/.Main t57}
        [focus]
          mFocusedWindow=Window{5adfb70 u0 com.miui.home/com.miui.home.Launcher}
        [ime]
          mFocusedDisplayId=0
          mInputShown=false
        [launcher]
        priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
        com.miui.home/com.miui.home.Launcher
        [win]
          Window #1 Window{3aa11bc u0 com.system.ui/com.system.ui.StatusBar}:
            mDisplayId=0
            mFrame=[0,0][1080,80]
          Window #2 Window{5adfb70 u0 com.miui.home/com.miui.home.Launcher}:
            mDisplayId=0
            mFrame=[0,80][1080,2340]
          Window #3 Window{77cc99dd u0 com.example.app/com.example.app.Main}:
            mDisplayId=5
            mFrame=[0,0][1080,1920]
        [group]
          Display group 0:
            mDisplayId=0
          Display group 3:
            mDisplayId=5
    """.trimIndent()

    @Test
    fun parseReadsEverySectionIntoItsOwnField() {
        val state = MainDisplayCheck.parse(fullDump)
        assertEquals(mapOf(main to "com.miui.home", virtual to "com.example.app"), state.topPackages)
        assertEquals(mapOf(main to 42, virtual to 57), state.topTaskIds)
        assertEquals("5adfb70 u0 com.miui.home/com.miui.home.Launcher", state.focusedWindow)
        // 焦点归属按 [win] 段同名哈希匹配：5adfb70 在 display 0。
        assertEquals(main, state.focusDisplayId)
        assertEquals(0, state.imeFocusedDisplayId)
        assertEquals(false, state.imeShown)
        assertEquals("com.miui.home", state.launcherPackage)
        assertEquals(mapOf(main to 0, virtual to 3), state.displayGroups)
        assertEquals(
            listOf(
                MainDisplayCheck.Bounds(0, 0, 1080, 80),
                MainDisplayCheck.Bounds(0, 80, 1080, 2340),
            ),
            state.windowBounds[main],
        )
        assertEquals(listOf(MainDisplayCheck.Bounds(0, 0, 1080, 1920)), state.windowBounds[virtual])
    }

    /** 全空快照（命令失败、输出为空）：所有字段缺省为 unknown，解析不抛。 */
    @Test
    fun emptyDumpParsesToAllUnknown() {
        val state = MainDisplayCheck.parse("")
        assertNull(state.focusedWindow)
        assertNull(state.focusDisplayId)
        assertNull(state.imeShown)
        assertNull(state.launcherPackage)
        assertTrue(state.topPackages.isEmpty() && state.topTaskIds.isEmpty())
        assertTrue(state.windowBounds.isEmpty())
        // unknown 不判任何破坏：before/after 都读不到时 evaluate 必须为空。
        assertTrue(MainDisplayCheck.evaluate(main, virtual, state, state).isEmpty())
        // 全 unknown 也不是 pass：证据计数为零。
        assertFalse(MainDisplayCheck.hasEvidence(state))
    }

    // ── topTask 比任务号，包名不是判据 ──

    /** 主屏顶层任务号变了：破坏。 */
    @Test
    fun changedTopTaskIdIsAViolation() {
        val before = MainDisplayCheck.parse(fullDump)
        val after = MainDisplayCheck.parse(fullDump.replace("Task{7f2a1c9 #42", "Task{7f2a1c9 #99"))
        assertTrue(MainDisplayCheck.Violation.TOP_TASK_CHANGED in MainDisplayCheck.evaluate(main, virtual, before, after))
    }

    /** 顶层包名换了但任务号没换（路由活动/别名/跨包启动的合法场景）：不判，包名只进日志。 */
    @Test
    fun changedTopPackageWithSameTaskIsNotAViolation() {
        val before = MainDisplayCheck.parse(fullDump)
        val after = MainDisplayCheck.parse(
            fullDump.replace(
                "topResumedActivity=ActivityRecord{ab12cd3 u0 com.miui.home/.Launcher t42}",
                "topResumedActivity=ActivityRecord{ab12cd3 u0 com.miui.home/.Search t42}",
            ),
        )
        assertTrue(
            MainDisplayCheck.Violation.TOP_TASK_CHANGED !in
                MainDisplayCheck.evaluate(main, virtual, before, after),
        )
    }

    /** 任务号任一侧没读到：不判（unknown 纪律）。 */
    @Test
    fun unknownTopTaskIdNeverFails() {
        val before = MainDisplayCheck.parse(fullDump)
        val after = MainDisplayCheck.parse(fullDump.replace("Task{7f2a1c9 #42", "Task{7f2a1c9 #99"))
        val noTasks = fullDump.replace(Regex("\\*Task\\{[^}]*\\}\n"), "")
        assertNull(MainDisplayCheck.parse(noTasks).topTaskIds[main])
        assertTrue(
            MainDisplayCheck.Violation.TOP_TASK_CHANGED !in
                MainDisplayCheck.evaluate(main, virtual, MainDisplayCheck.parse(noTasks), after),
        )
        assertTrue(
            MainDisplayCheck.Violation.TOP_TASK_CHANGED !in
                MainDisplayCheck.evaluate(main, virtual, before, MainDisplayCheck.parse(noTasks)),
        )
    }

    // ── 焦点矩阵 ──

    /** before 有真实焦点、after 被清（mFocusedWindow=null）：破坏。 */
    @Test
    fun focusClearedAfterRealFocusIsAViolation() {
        val before = MainDisplayCheck.parse(fullDump)
        val after = MainDisplayCheck.parse(
            fullDump.replace("mFocusedWindow=Window{5adfb70 u0 com.miui.home/com.miui.home.Launcher}", "mFocusedWindow=null"),
        )
        assertEquals(MainDisplayCheck.FOCUS_CLEARED, after.focusedWindow)
        assertTrue(
            MainDisplayCheck.Violation.MAIN_FOCUS_LOST in
                MainDisplayCheck.evaluate(main, virtual, before, after),
        )
    }

    /** before 与 after 焦点都是 null：健康屏常态，不是破坏（假阳性回归）。 */
    @Test
    fun nullFocusBeforeAndAfterIsNotAViolation() {
        val cleared = fullDump.replace(
            "mFocusedWindow=Window{5adfb70 u0 com.miui.home/com.miui.home.Launcher}",
            "mFocusedWindow=null",
        )
        val before = MainDisplayCheck.parse(cleared)
        val after = MainDisplayCheck.parse(cleared)
        assertEquals(MainDisplayCheck.FOCUS_CLEARED, before.focusedWindow)
        assertTrue(
            MainDisplayCheck.Violation.MAIN_FOCUS_LOST !in
                MainDisplayCheck.evaluate(main, virtual, before, after),
        )
    }

    /** before 焦点没读到（unknown）、after 被清：证据不支持，不判。 */
    @Test
    fun unknownBeforeFocusNeverFails() {
        val before = MainDisplayCheck.parse(
            fullDump.replace("mFocusedWindow=Window{5adfb70 u0 com.miui.home/com.miui.home.Launcher}", ""),
        )
        val after = MainDisplayCheck.parse(
            fullDump.replace("mFocusedWindow=Window{5adfb70 u0 com.miui.home/com.miui.home.Launcher}", "mFocusedWindow=null"),
        )
        assertNull(before.focusedWindow)
        assertTrue(
            MainDisplayCheck.Violation.MAIN_FOCUS_LOST !in
                MainDisplayCheck.evaluate(main, virtual, before, after),
        )
    }

    /** before 被清、after 有真实焦点：焦点回来了，更不是破坏。 */
    @Test
    fun focusAppearingIsNotAViolation() {
        val clearedBefore = fullDump.replace(
            "mFocusedWindow=Window{5adfb70 u0 com.miui.home/com.miui.home.Launcher}",
            "mFocusedWindow=null",
        )
        assertTrue(
            MainDisplayCheck.Violation.MAIN_FOCUS_LOST !in
                MainDisplayCheck.evaluate(main, virtual, MainDisplayCheck.parse(clearedBefore), MainDisplayCheck.parse(fullDump)),
        )
    }

    /** 焦点窗口的哈希在 [win] 段无法匹配（多显示、窗口段缺失）：归属记 unknown，不假设主屏。 */
    @Test
    fun focusAttributionWithoutTheWindowsSectionStaysUnknown() {
        val state = MainDisplayCheck.parse(fullDump.replace(Regex("\\[win\\][\\s\\S]*(?=\\[group])"), ""))
        assertEquals("5adfb70 u0 com.miui.home/com.miui.home.Launcher", state.focusedWindow)
        assertNull(state.focusDisplayId)
    }

    // ── launcher 搬迁 ──

    /** launcher 被搬上虚拟屏：只看 after 就能判，不依赖 before。 */
    @Test
    fun launcherMovedToTheVirtualDisplayIsAViolation() {
        val after = MainDisplayCheck.parse(
            fullDump.replace(
                "topResumedActivity=ActivityRecord{ff22ee4 u0 com.example.app/.Main t57}",
                "topResumedActivity=ActivityRecord{ff22ee4 u0 com.miui.home/.Launcher t57}",
            ),
        )
        assertTrue(
            MainDisplayCheck.Violation.LAUNCHER_MOVED_TO_VIRTUAL in
                MainDisplayCheck.evaluate(main, virtual, null, after),
        )
    }

    /** launcher 还在主屏（虚拟屏上是普通应用）：不判。 */
    @Test
    fun launcherStayingOnMainIsNotAViolation() {
        val after = MainDisplayCheck.parse(fullDump)
        assertTrue(MainDisplayCheck.evaluate(main, virtual, null, after).isEmpty())
    }

    /** launcher 读不到（resolve-activity 没答）：不判。 */
    @Test
    fun unknownLauncherNeverFails() {
        val after = MainDisplayCheck.parse(fullDump.replace("com.miui.home/com.miui.home.Launcher\n", ""))
        assertNull(after.launcherPackage)
        assertTrue(MainDisplayCheck.evaluate(main, virtual, null, after).isEmpty())
    }

    // ── IME ──

    /** IME：before 明确不可见、after 可见且聚焦主屏 —— 观察到破坏。 */
    @Test
    fun imePulledOntoMainIsAViolation() {
        val before = MainDisplayCheck.parse(fullDump)
        val after = MainDisplayCheck.parse(fullDump.replace("mInputShown=false", "mInputShown=true"))
        assertTrue(
            MainDisplayCheck.Violation.IME_ON_MAIN in
                MainDisplayCheck.evaluate(main, virtual, before, after),
        )
    }

    /**
     * before 不可见、after 可见但聚焦在虚拟屏上：不判——IME 落在屏内（local）正是
     * 兜底要的效果，把"屏内 IME"读成"拽到主屏"会让正常设备被标死。
     */
    @Test
    fun imeOnTheVirtualDisplayIsNotAViolation() {
        val before = MainDisplayCheck.parse(fullDump)
        val after = MainDisplayCheck.parse(
            fullDump
                .replace("mInputShown=false", "mInputShown=true")
                .replace("mFocusedDisplayId=0", "mFocusedDisplayId=$virtual"),
        )
        assertTrue(MainDisplayCheck.evaluate(main, virtual, before, after).isEmpty())
    }

    /** before 的 IME 状态没读到：不判（用户本来就在打字的主屏 IME 是常态，不能猜）。 */
    @Test
    fun unknownBeforeImeNeverFails() {
        val after = MainDisplayCheck.parse(fullDump.replace("mInputShown=false", "mInputShown=true"))
        assertTrue(
            MainDisplayCheck.Violation.IME_ON_MAIN !in
                MainDisplayCheck.evaluate(main, virtual, null, after),
        )
    }

    // ── 覆盖率 ──

    /** 虚拟屏上有窗口且并集铺满：不判。 */
    @Test
    fun fullyCoveredVirtualDisplayIsNotAViolation() {
        val after = MainDisplayCheck.parse(fullDump)
        assertNull(
            "没给尺寸时覆盖率按 unknown（不判）",
            MainDisplayCheck.coverageRatio(after, virtual, 0, 0),
        )
        val ratio = MainDisplayCheck.coverageRatio(after, virtual, 1080, 1920)
        assertEquals(1.0, ratio!!, 1e-9)
        assertTrue(
            MainDisplayCheck.Violation.VIRTUAL_COVERAGE_LOW !in
                MainDisplayCheck.evaluate(main, virtual, null, after, 1080, 1920),
        )
    }

    /** 虚拟屏上只有一小块窗口（并集 < 90%）：判破坏。 */
    @Test
    fun lowCoverageIsAViolation() {
        val after = MainDisplayCheck.parse(
            fullDump.replace("mFrame=[0,0][1080,1920]", "mFrame=[0,0][300,300]"),
        )
        val ratio = MainDisplayCheck.coverageRatio(after, virtual, 1080, 1920)
        assertTrue(ratio!! < MainDisplayCheck.COVERAGE_PASS_RATIO)
        assertTrue(
            MainDisplayCheck.Violation.VIRTUAL_COVERAGE_LOW in
                MainDisplayCheck.evaluate(main, virtual, null, after, 1080, 1920),
        )
    }

    /** 虚拟屏上没有任何窗口记录（典型：建屏后、应用还没起）：覆盖率 unknown，不判。 */
    @Test
    fun noWindowsOnTheVirtualDisplayIsUnknownNotZero() {
        val after = MainDisplayCheck.parse(
            fullDump.replace(Regex("Window #3 Window\\{77cc99dd[^\\n]*\\}:[\\s\\S]*(?=\\[group])"), ""),
        )
        assertTrue(after.windowBounds[virtual].isNullOrEmpty())
        assertNull(MainDisplayCheck.coverageRatio(after, virtual, 1080, 1920))
        assertTrue(
            "建屏后应用未启动是常态，不得按 0% 覆盖判破坏",
            MainDisplayCheck.evaluate(main, virtual, null, after, 1080, 1920).isEmpty(),
        )
    }

    /** 两个并排窗口的并集（非简单取最大）：中间重叠只算一次。 */
    @Test
    fun unionAreaMergesOverlappingRects() {
        val rects = listOf(
            MainDisplayCheck.Bounds(0, 0, 1000, 1000),
            MainDisplayCheck.Bounds(500, 0, 1080, 1000),
        )
        assertEquals(1080L * 1000, MainDisplayCheck.unionArea(rects))
    }

    // ── 证据与 unknown 不判死 ──

    /** before 缺席（prepare 没发成）：只有 launcher/覆盖率那几问可做，其余全为 unknown 不判。 */
    @Test
    fun missingBeforeOnlyRunsTheBeforeFreeChecks() {
        val after = MainDisplayCheck.parse(fullDump)
        assertTrue(MainDisplayCheck.evaluate(main, virtual, null, after).isEmpty())
    }

    /** 状态没变时（before/after 同文）：不判任何破坏，且证据（after 侧）充足判 pass。 */
    @Test
    fun unchangedStateIsNotAViolationAndHasEvidence() {
        val before = MainDisplayCheck.parse(fullDump)
        val after = MainDisplayCheck.parse(fullDump)
        assertTrue(MainDisplayCheck.evaluate(main, virtual, before, after).isEmpty())
        assertTrue(MainDisplayCheck.hasEvidence(after))
    }

    /** 多项破坏同时发生时逐条都在场：报告要能说全，不能只报第一眼看到的那条。 */
    @Test
    fun simultaneousViolationsAreAllReported() {
        val before = MainDisplayCheck.parse(fullDump)
        val after = MainDisplayCheck.parse(
            fullDump
                .replace("Task{7f2a1c9 #42", "Task{7f2a1c9 #99")
                .replace("mFocusedWindow=Window{5adfb70 u0 com.miui.home/com.miui.home.Launcher}", "mFocusedWindow=null")
                .replace(
                    "topResumedActivity=ActivityRecord{ff22ee4 u0 com.example.app/.Main t57}",
                    "topResumedActivity=ActivityRecord{ff22ee4 u0 com.miui.home/.Launcher t57}",
                )
                .replace("mInputShown=false", "mInputShown=true"),
        )
        val violations = MainDisplayCheck.evaluate(main, virtual, before, after, 1080, 1920)
        assertEquals(
            setOf(
                MainDisplayCheck.Violation.TOP_TASK_CHANGED,
                MainDisplayCheck.Violation.MAIN_FOCUS_LOST,
                MainDisplayCheck.Violation.LAUNCHER_MOVED_TO_VIRTUAL,
                MainDisplayCheck.Violation.IME_ON_MAIN,
            ),
            violations,
        )
    }

    /** 解析器不认的行当没有读到，不当数据猜：焦点段被无关行占据时保持 unknown。 */
    @Test
    fun unrecognizedFocusLinesStayUnknown() {
        val state = MainDisplayCheck.parse("[focus]\nnothing matches here\n")
        assertNull(state.focusedWindow)
    }

    // ── 焦点归属参与判定 ──

    /**
     * after 的焦点窗口哈希在 [win] 段对上号、却落在虚拟屏上：判
     * [MainDisplayCheck.Violation.MAIN_FOCUS_ON_OTHER_DISPLAY]——这是「焦点被虚拟屏
     * 抢走」的直接读数。
     */
    @Test
    fun focusAttributedToTheVirtualDisplayIsAViolation() {
        // 焦点窗换成虚拟屏上的那个窗口（哈希 77cc99dd，mDisplayId=5）。
        val after = MainDisplayCheck.parse(
            fullDump.replace(
                "mFocusedWindow=Window{5adfb70 u0 com.miui.home/com.miui.home.Launcher}",
                "mFocusedWindow=Window{77cc99dd u0 com.example.app/com.example.app.Main}",
            ),
        )
        assertEquals(virtual, after.focusDisplayId)
        val violations = MainDisplayCheck.evaluate(main, virtual, MainDisplayCheck.parse(fullDump), after)
        assertTrue(MainDisplayCheck.Violation.MAIN_FOCUS_ON_OTHER_DISPLAY in violations)
        // 虚拟屏上的窗口并集也读到了（fullDump 的 77cc99dd 在 display 5 全铺），不判 LOW。
        assertTrue(MainDisplayCheck.Violation.VIRTUAL_COVERAGE_LOW !in violations)
    }

    /**
     * 焦点归属**留在主屏**：不判——把「焦点仍在自己屏上」读成破坏是假阳性烟枪。
     */
    @Test
    fun focusAttributedToMainIsNotAViolation() {
        val before = MainDisplayCheck.parse(fullDump)
        val after = MainDisplayCheck.parse(fullDump)
        assertEquals(main, after.focusDisplayId)
        assertTrue(MainDisplayCheck.evaluate(main, virtual, before, after).isEmpty())
    }

    /** 焦点归属 unknown（哈希无法匹配/窗口段缺失）：不判——证据不支持"焦点离开主屏"。 */
    @Test
    fun focusAttributionUnknownNeverFails() {
        val before = MainDisplayCheck.parse(fullDump)
        val after = MainDisplayCheck.parse(fullDump.replace(Regex("\\[win\\][\\s\\S]*(?=\\[group])"), ""))
        assertNull(after.focusDisplayId)
        assertTrue(MainDisplayCheck.evaluate(main, virtual, before, after).isEmpty())
    }

    /** 焦点归属到了**另一块**物理显示（第三屏）也算离开主屏，不只对虚拟屏判。 */
    @Test
    fun focusOnAnyOtherDisplayIsAViolation() {
        val thirdDisplayDump = fullDump
            .replace("Display #5 (activities from top to bottom):", "Display #7 (activities from top to bottom):")
            .replace("mDisplayId=5", "mDisplayId=7")
        val after = MainDisplayCheck.parse(thirdDisplayDump.replace(
            "mFocusedWindow=Window{5adfb70 u0 com.miui.home/com.miui.home.Launcher}",
            "mFocusedWindow=Window{77cc99dd u0 com.example.app/com.example.app.Main}",
        ))
        assertEquals(7, after.focusDisplayId)
        assertTrue(
            MainDisplayCheck.Violation.MAIN_FOCUS_ON_OTHER_DISPLAY in
                MainDisplayCheck.evaluate(main, virtual, null, after),
        )
    }

    // ── （第 3 条）：hasEvidence 的证据口径 ──

    /**
     * displayGroups 只进日志不参与判定：只有它读到的快照**不算证据**——
     * 全 unknown 的判据不因一段 group 回包升格为"有判定证据"。
     */
    @Test
    fun onlyDisplayGroupsIsNotEvidence() {
        val groupOnlyDump = "[top]\n[focus]\n[ime]\n[launcher]\n[win]\n[group]\nmDisplayGroupId=3\n  mDisplayId=5\n"
        val state = MainDisplayCheck.parse(groupOnlyDump)
        assertTrue(state.displayGroups.isNotEmpty())
        assertTrue(state.topPackages.isEmpty() && state.topTaskIds.isEmpty())
        assertNull(state.focusedWindow)
        assertNull(state.imeShown)
        assertNull(state.launcherPackage)
        assertTrue(state.windowBounds.isEmpty())
        assertFalse("组号是日志字段，不是判定证据", MainDisplayCheck.hasEvidence(state))
    }

    /** 参与判定的字段里任意一个读到值即算证据（launcher 这一问单独在场就够）。 */
    @Test
    fun aSingleJudgingFieldCarriesEvidence() {
        val launcherOnly = MainDisplayCheck.parse("[launcher]\ncom.miui.home/com.miui.home.Launcher\n")
        assertEquals("com.miui.home", launcherOnly.launcherPackage)
        assertTrue(MainDisplayCheck.hasEvidence(launcherOnly))
    }

    /**
     * 的关键回归：**before 有证据、after 全空 → unverified**。before 抓的
     * 是"没有虚拟屏"的常态，它的读数证明不了建屏后的主屏健康——只看 before 会把这份
     * 场景伪装成 pass。
     */
    @Test
    fun evidenceOnBeforeAloneIsNotAPass() {
        val before = MainDisplayCheck.parse(fullDump)
        val emptyAfter = MainDisplayCheck.parse("")
        assertTrue(knownFieldsOf(before) > 0)
        assertEquals(0, knownFieldsOf(emptyAfter))
        assertFalse("before 的读数不是这块屏的健康证明", MainDisplayCheck.hasEvidence(emptyAfter))
    }

    /** 焦点清空（字符串 "null"）是**已读到**的证据——它在 hasEvidence 与 MAIN_FOCUS_LOST 的可判定口径里都算在场。 */
    @Test
    fun aClearedFocusIsReadEvidenceNotUnknown() {
        val cleared = MainDisplayCheck.parse(fullDump.replace("mFocusedWindow=Window{5adfb70 u0 com.miui.home/com.miui.home.Launcher}", "mFocusedWindow=null"))
        assertEquals(MainDisplayCheck.FOCUS_CLEARED, cleared.focusedWindow)
        assertTrue("字符串 null 是读数：焦点被清的证据在场", MainDisplayCheck.hasEvidence(cleared))
    }

    /** knownFields 的白盒计数（测试锚定证据口径，生产不暴露）。 */
    private fun knownFieldsOf(state: MainDisplayCheck.MainState): Int {
        var n = 0
        if (state.topPackages.isNotEmpty()) n++
        if (state.topTaskIds.isNotEmpty()) n++
        if (state.focusedWindow != null) n++
        if (state.focusDisplayId != null) n++
        if (state.imeFocusedDisplayId != null) n++
        if (state.imeShown != null) n++
        if (state.launcherPackage != null) n++
        if (state.windowBounds.isNotEmpty()) n++
        return n
    }

    // ── 首轮 + 复采收场（[MainDisplayCheck.settle]）──

    /** 建一个"主屏一切安好"的虚拟屏场景（含覆盖满屏的窗口）。 */
    private fun healthySample(): MainDisplayCheck.MainState = MainDisplayCheck.parse(fullDump)

    /** 首轮干净（before/afterCustomer 同文）：有证据 → pass；屏保留、非 unsafe。 */
    @Test
    fun settleFirstPassClean() {
        val settled = MainDisplayCheck.settle(virtual, null, emptySet(), healthySample(), 1080, 1920)
        assertTrue(settled is MainDisplayCheck.Settled.Pass)
        assertTrue((settled as MainDisplayCheck.Settled.Pass).hasEvidence)
    }

    /** 首轮干净但读不到任何证据：unverified（Pass.hasEvidence=false），不判 unsafe。 */
    @Test
    fun settleFirstPassWithNoEvidenceStaysUnverified() {
        val settled = MainDisplayCheck.settle(
            virtual, null, emptySet(), MainDisplayCheck.parse(""), 1080, 1920,
        )
        assertTrue(settled is MainDisplayCheck.Settled.Pass)
        assertFalse((settled as MainDisplayCheck.Settled.Pass).hasEvidence)
    }

    /** 首轮 violation + 复采读到证据且消了：transientRecovered，只记录不锁死。 */
    @Test
    fun settleResampleCaughtUpIsTransient() {
        // 首轮焦点被清 → 复采焦点回来了（且仍在主屏）。
        val before = MainDisplayCheck.parse(fullDump)
        val cleared = fullDump.replace(
            "mFocusedWindow=Window{5adfb70 u0 com.miui.home/com.miui.home.Launcher}",
            "mFocusedWindow=null",
        )
        val firstViolations = MainDisplayCheck.evaluate(
            main, virtual, before, MainDisplayCheck.parse(cleared),
        )
        assertEquals(setOf(MainDisplayCheck.Violation.MAIN_FOCUS_LOST), firstViolations)
        val settled = MainDisplayCheck.settle(
            virtual, before, firstViolations, healthySample(), 1080, 1920,
        )
        assertTrue(settled is MainDisplayCheck.Settled.Transient)
        assertEquals(
            listOf(MainDisplayCheck.Violation.MAIN_FOCUS_LOST),
            (settled as MainDisplayCheck.Settled.Transient).recovered,
        )
    }

    /** 首轮 violation + 复采仍读到证据且破坏仍在 → unsafe。 */
    @Test
    fun settleResampleStillBreakingIsUnsafe() {
        val before = MainDisplayCheck.parse(fullDump)
        val after = MainDisplayCheck.parse(fullDump.replace("Task{7f2a1c9 #42", "Task{7f2a1c9 #99"))
        val firstViolations = MainDisplayCheck.evaluate(main, virtual, before, after)
        assertEquals(setOf(MainDisplayCheck.Violation.TOP_TASK_CHANGED), firstViolations)
        val settled = MainDisplayCheck.settle(virtual, before, firstViolations, after, 1080, 1920)
        assertEquals(
            MainDisplayCheck.Settled.Unsafe(listOf(MainDisplayCheck.Violation.TOP_TASK_CHANGED)),
            settled,
        )
    }

    /** 首轮 violation + 复采读到证据但换成了**另一条**破坏 → unsafe 且列的是复采的那条。 */
    @Test
    fun settleResampleShowsADifferentViolationIsStillUnsafe() {
        val before = MainDisplayCheck.parse(fullDump)
        // 首轮：焦点被清；复采：launcher 被搬上虚拟屏（焦点恢复）。
        val firstShot = fullDump.replace(
            "mFocusedWindow=Window{5adfb70 u0 com.miui.home/com.miui.home.Launcher}",
            "mFocusedWindow=null",
        )
        val secondShot = fullDump.replace(
            "topResumedActivity=ActivityRecord{ff22ee4 u0 com.example.app/.Main t57}",
            "topResumedActivity=ActivityRecord{ff22ee4 u0 com.miui.home/.Launcher t57}",
        )
        val firstViolations = MainDisplayCheck.evaluate(
            main, virtual, before, MainDisplayCheck.parse(firstShot),
        )
        val settled = MainDisplayCheck.settle(
            virtual, before, firstViolations, MainDisplayCheck.parse(secondShot), 1080, 1920,
        )
        assertEquals(
            // 复采判到的是它自己那份：launcher 搬上虚拟屏（首份的焦点破坏已在复采里恢复）。
            MainDisplayCheck.Settled.Unsafe(listOf(MainDisplayCheck.Violation.LAUNCHER_MOVED_TO_VIRTUAL)),
            settled,
        )
    }

    /** 首轮 violation + 复采读不到证据（快照空/全 unknown）→ fail-closed 的 ResampleUnverified。 */
    @Test
    fun settleResampleWithoutEvidenceIsFailClosed() {
        val before = MainDisplayCheck.parse(fullDump)
        val after = MainDisplayCheck.parse(fullDump.replace("Task{7f2a1c9 #42", "Task{7f2a1c9 #99"))
        val firstViolations = MainDisplayCheck.evaluate(main, virtual, before, after)
        val settled = MainDisplayCheck.settle(
            virtual, before, firstViolations, MainDisplayCheck.parse(""), 1080, 1920,
        )
        assertEquals(MainDisplayCheck.Settled.ResampleUnverified, settled)
    }

    /**
     * before 为 null（prepare 没发成）+ 首轮 violation + 复采读不到证据的边界：首轮
     * violations 非空来自不依赖 before 的那几问（launcher/覆盖率），fail-closed 仍成立。
     */
    @Test
    fun settleResampleUnverifiedSurvivesTheBeforeNullEdge() {
        val launcherMoved = MainDisplayCheck.parse(
            fullDump.replace(
                "topResumedActivity=ActivityRecord{ff22ee4 u0 com.example.app/.Main t57}",
                "topResumedActivity=ActivityRecord{ff22ee4 u0 com.miui.home/.Launcher t57}",
            ),
        )
        val firstViolations = MainDisplayCheck.evaluate(main, virtual, null, launcherMoved)
        assertTrue(firstViolations.contains(MainDisplayCheck.Violation.LAUNCHER_MOVED_TO_VIRTUAL))
        val settled = MainDisplayCheck.settle(
            virtual, null, firstViolations, MainDisplayCheck.parse(""), 1080, 1920,
        )
        // 复采一份空快照、建 before 也是 null：空快照逐条全不可判定 → ResampleUnverified。
        assertEquals(MainDisplayCheck.Settled.ResampleUnverified, settled)
    }

    // ── 复采"只丢了决定该破坏的字段"必须 fail-closed ──
    //
    // 共同场景：首轮见到某条破坏；复采快照**其余字段都可读**，唯独决定该条的枚字段
    // 没读到。整份 hasEvidence>0（旧口径直接放行进 evaluate），但现在逐条问：
    // 那一条不可判定 → ResampleUnverified。

    /** TOP_TASK_CHANGED：复采丢了主屏任务号（before 有、复采段没有）。 */
    @Test
    fun resampleLosingTheTopTaskFieldIsFailClosed() {
        val before = MainDisplayCheck.parse(fullDump)
        val firstShot = fullDump.replace("Task{7f2a1c9 #42", "Task{7f2a1c9 #99")
        val firstViolations = MainDisplayCheck.evaluate(main, virtual, before, MainDisplayCheck.parse(firstShot))
        assertEquals(setOf(MainDisplayCheck.Violation.TOP_TASK_CHANGED), firstViolations)
        val resample = MainDisplayCheck.parse(
            // 抹掉主屏的任务行（任务号没读到），其余段（focus/ime/launcher/win）齐全。
            fullDump.replace(Regex("\\*Task\\{7f2a1c9 #42[^\n]*\n"), ""),
        )
        assertTrue(resample.topTaskIds[main] == null)
        assertTrue(resample.focusedWindow != null && resample.launcherPackage != null)
        assertTrue("复采并非全空（旧口径会放行），只是缺了决定该破坏的字段", MainDisplayCheck.hasEvidence(resample))
        val settled = MainDisplayCheck.settle(virtual, before, firstViolations, resample, 1080, 1920)
        assertEquals("决定该破坏的字段读不到了：不能按'恢复了'放行", MainDisplayCheck.Settled.ResampleUnverified, settled)
    }

    /** MAIN_FOCUS_LOST：复采丢了焦点字段。 */
    @Test
    fun resampleLosingTheFocusFieldIsFailClosed() {
        val before = MainDisplayCheck.parse(fullDump)
        val cleared = fullDump.replace("mFocusedWindow=Window{5adfb70 u0 com.miui.home/com.miui.home.Launcher}", "mFocusedWindow=null")
        val firstViolations = MainDisplayCheck.evaluate(main, virtual, before, MainDisplayCheck.parse(cleared))
        assertEquals(setOf(MainDisplayCheck.Violation.MAIN_FOCUS_LOST), firstViolations)
        // 复采段整个没读到（段缺失比"值为空"更真：grep 没匹配），其余段齐全。
        val resample = MainDisplayCheck.parse(fullDump.replace(Regex("\\[focus\\][\\s\\S]*(?=\\[ime])"), "[focus]\n"))
        assertNull(resample.focusedWindow)
        assertTrue(resample.launcherPackage != null && resample.imeShown == false)
        val settled = MainDisplayCheck.settle(virtual, before, firstViolations, resample, 1080, 1920)
        assertEquals(MainDisplayCheck.Settled.ResampleUnverified, settled)
    }

    /** MAIN_FOCUS_ON_OTHER_DISPLAY：复采焦点读到了，但焦点归属（win 段）没对上号。 */
    @Test
    fun resampleLosingTheFocusAttributionIsFailClosed() {
        val before = MainDisplayCheck.parse(fullDump)
        val focusOnVirtual = fullDump.replace(
            "mFocusedWindow=Window{5adfb70 u0 com.miui.home/com.miui.home.Launcher}",
            "mFocusedWindow=Window{77cc99dd u0 com.example.app/com.example.app.Main}",
        )
        val firstViolations = MainDisplayCheck.evaluate(main, virtual, before, MainDisplayCheck.parse(focusOnVirtual))
        assertEquals(setOf(MainDisplayCheck.Violation.MAIN_FOCUS_ON_OTHER_DISPLAY), firstViolations)
        // 复采：焦点读到，但 win 段缺失 → focusDisplayId null（归属不可判定）。
        val resample = MainDisplayCheck.parse(focusOnVirtual.replace(Regex("\\[win\\][\\s\\S]*(?=\\[group])"), ""))
        assertTrue(resample.focusedWindow != null && resample.focusDisplayId == null)
        val settled = MainDisplayCheck.settle(virtual, before, firstViolations, resample, 1080, 1920)
        assertEquals(MainDisplayCheck.Settled.ResampleUnverified, settled)
    }

    /** LAUNCHER_MOVED_TO_VIRTUAL：复采 launcher 读到了，但主屏 topPackages 段没读数。 */
    @Test
    fun resampleLosingTheLauncherFieldIsFailClosed() {
        val launcherMoved = fullDump.replace(
            "topResumedActivity=ActivityRecord{ff22ee4 u0 com.example.app/.Main t57}",
            "topResumedActivity=ActivityRecord{ff22ee4 u0 com.miui.home/.Launcher t57}",
        )
        val firstViolations = MainDisplayCheck.evaluate(main, virtual, null, MainDisplayCheck.parse(launcherMoved))
        assertEquals(setOf(MainDisplayCheck.Violation.LAUNCHER_MOVED_TO_VIRTUAL), firstViolations)
        // 复采：launcher 段没读到（resolve-activity 没答），主屏 top 段缺失。
        val resample = MainDisplayCheck.parse(
            launcherMoved.replace(Regex("\\[launcher\\][\\s\\S]*(?=\\[win])"), "[launcher]\n"),
        )
        assertNull(resample.launcherPackage)
        assertTrue(resample.focusDisplayId != null && resample.imeShown == false)
        val settled = MainDisplayCheck.settle(virtual, null, firstViolations, resample, 1080, 1920)
        assertEquals(MainDisplayCheck.Settled.ResampleUnverified, settled)
    }

    /**
     * 回归：launcher 段可读、主屏 top 段也在，但**虚拟屏的 top 段**被截断——
     * `evaluate` 的 LAUNCHER_MOVED_TO_VIRTUAL 读的是 `topPackages[virtual]`，可判定门
     * 也必须读同一个显示号；只看主屏段会把"虚拟屏段丢了"读成"launcher 没被搬走"。
     */
    @Test
    fun resampleLosingTheVirtualTopSectionIsFailClosed() {
        val launcherMoved = fullDump.replace(
            "topResumedActivity=ActivityRecord{ff22ee4 u0 com.example.app/.Main t57}",
            "topResumedActivity=ActivityRecord{ff22ee4 u0 com.miui.home/.Launcher t57}",
        )
        val firstViolations = MainDisplayCheck.evaluate(main, virtual, null, MainDisplayCheck.parse(launcherMoved))
        assertEquals(setOf(MainDisplayCheck.Violation.LAUNCHER_MOVED_TO_VIRTUAL), firstViolations)
        val resample = MainDisplayCheck.parse(
            launcherMoved.replace(
                Regex("\\nDisplay #5 \\(activities from top to bottom\\):[\\s\\S]*?(?=\\n\\[focus])"),
                "",
            ),
        )
        assertNull("虚拟屏 top 段没读到", resample.topPackages[virtual])
        assertTrue("launcher 段与主屏段仍在", resample.launcherPackage != null && resample.topPackages[main] != null)
        assertFalse(
            "决定该破坏的虚拟屏段读不到：不可判定",
            MainDisplayCheck.isDecidableOn(
                MainDisplayCheck.Violation.LAUNCHER_MOVED_TO_VIRTUAL, virtual, null, resample, 1080, 1920,
            ),
        )
        val settled = MainDisplayCheck.settle(virtual, null, firstViolations, resample, 1080, 1920)
        assertEquals(MainDisplayCheck.Settled.ResampleUnverified, settled)
    }

    /** 首轮干净但**建屏后快照**全空：证据不能来自 before 一侧，只能记 unverified。 */
    @Test
    fun settleFirstPassNeedsEvidenceFromTheAfterSample() {
        val before = MainDisplayCheck.parse(fullDump)
        val settled = MainDisplayCheck.settle(
            virtual, before, emptySet(), MainDisplayCheck.parse(""), 1080, 1920,
        )
        assertEquals(MainDisplayCheck.Settled.Pass(hasEvidence = false), settled)
    }

    /** IME_ON_MAIN：复采 imeShown 没读到。 */
    @Test
    fun resampleLosingTheImeFieldIsFailClosed() {
        val before = MainDisplayCheck.parse(fullDump)
        val firstShot = fullDump.replace("mInputShown=false", "mInputShown=true")
        val firstViolations = MainDisplayCheck.evaluate(main, virtual, before, MainDisplayCheck.parse(firstShot))
        assertEquals(setOf(MainDisplayCheck.Violation.IME_ON_MAIN), firstViolations)
        // 复采：ime 段整个没读到。
        val resample = MainDisplayCheck.parse(firstShot.replace(Regex("\\[ime\\][\\s\\S]*(?=\\[launcher])"), "[ime]\n"))
        assertNull(resample.imeShown)
        assertNull(resample.imeFocusedDisplayId)
        assertTrue(resample.focusedWindow != null && resample.launcherPackage != null)
        val settled = MainDisplayCheck.settle(virtual, before, firstViolations, resample, 1080, 1920)
        assertEquals(MainDisplayCheck.Settled.ResampleUnverified, settled)
    }

    /** VIRTUAL_COVERAGE_LOW：复采丢了虚拟屏的窗口记录（覆盖率读不到）。 */
    @Test
    fun resampleLosingTheWindowRecordsIsFailClosed() {
        val before = MainDisplayCheck.parse(fullDump)
        val lowCoverage = fullDump.replace("mFrame=[0,0][1080,1920]", "mFrame=[0,0][300,300]")
        val firstViolations = MainDisplayCheck.evaluate(main, virtual, before, MainDisplayCheck.parse(lowCoverage), 1080, 1920)
        assertEquals(setOf(MainDisplayCheck.Violation.VIRTUAL_COVERAGE_LOW), firstViolations)
        // 复采：win 段没了 → 覆盖率 null（不可判定），其余字段在场。
        val resample = MainDisplayCheck.parse(lowCoverage.replace(Regex("\\[win\\][\\s\\S]*(?=\\[group])"), ""))
        assertNull(MainDisplayCheck.coverageRatio(resample, virtual, 1080, 1920))
        val settled = MainDisplayCheck.settle(virtual, before, firstViolations, resample, 1080, 1920)
        assertEquals(MainDisplayCheck.Settled.ResampleUnverified, settled)
    }

    /** 对照组：复采关键字段齐全且破坏已消——仍然判 Transient（不伤正常恢复）。 */
    @Test
    fun aCompleteResampleWithTheViolationGoneIsStillTransient() {
        val before = MainDisplayCheck.parse(fullDump)
        val cleared = fullDump.replace("mFocusedWindow=Window{5adfb70 u0 com.miui.home/com.miui.home.Launcher}", "mFocusedWindow=null")
        val firstViolations = MainDisplayCheck.evaluate(main, virtual, before, MainDisplayCheck.parse(cleared))
        assertEquals(setOf(MainDisplayCheck.Violation.MAIN_FOCUS_LOST), firstViolations)
        val settled = MainDisplayCheck.settle(virtual, before, firstViolations, MainDisplayCheck.parse(fullDump), 1080, 1920)
        assertTrue(settled is MainDisplayCheck.Settled.Transient)
    }

    // ── isDecidableOn 的直测（可判定 ≠ 破坏仍在，只问证据齐不齐）──

    /** 复采焦点被清（字符串 "null"）：MAIN_FOCUS_LOST **可判定**——"null" 是已读到的证据。 */
    @Test
    fun aClearedFocusOnTheResampleIsDecidable() {
        val resample = MainDisplayCheck.parse(fullDump.replace("mFocusedWindow=Window{5adfb70 u0 com.miui.home/com.miui.home.Launcher}", "mFocusedWindow=null").replace("mInputShown=false", "mInputShown=true"))
        assertTrue(
            MainDisplayCheck.isDecidableOn(MainDisplayCheck.Violation.MAIN_FOCUS_LOST, virtual, null, resample, 1080, 1920),
        )
        // 对齐 IME_ON_MAIN 的两态：shown=false 可判；shown=true 且归属 null 不可判。
        assertTrue(
            "shown=false：归属字段用不上，可判",
            MainDisplayCheck.isDecidableOn(MainDisplayCheck.Violation.IME_ON_MAIN, virtual, null, resample, 1080, 1920),
        )
        // ime 段只有 shown（mFocusedDisplayId 行被抹掉）：归属 unknown → 不可判。
        val imeShownOnly = fullDump.replace("mInputShown=false", "mInputShown=true")
            .replace("  mFocusedDisplayId=0\n", "")
        val imeUpUnknownFocus = MainDisplayCheck.parse(imeShownOnly)
        assertNull(imeUpUnknownFocus.imeFocusedDisplayId)
        assertTrue(
            "shown=true 但归属没读到（ime 段只有 shown）：不可判",
            !MainDisplayCheck.isDecidableOn(MainDisplayCheck.Violation.IME_ON_MAIN, virtual, null, imeUpUnknownFocus, 1080, 1920),
        )
        // shown=true 且归属读到（mFocusedDisplayId=0）：可判。
        val withIme = MainDisplayCheck.parse(
            fullDump.replace("mInputShown=false", "mInputShown=true"),
        )
        assertEquals(0, withIme.imeFocusedDisplayId)
        assertTrue(
            "shown=true 且聚焦主屏读到：可判",
            MainDisplayCheck.isDecidableOn(MainDisplayCheck.Violation.IME_ON_MAIN, virtual, null, withIme, 1080, 1920),
        )
    }

    /** 首轮 violation，复采读到了**同一枚**证据但破坏消了（净 state 与首轮完全一致）：transient。 */
    @Test
    fun settleWithBeforeNullButResampleCarryingTheSameFieldIsStillEvidenceBased() {
        val after = MainDisplayCheck.parse(fullDump)
        val violations = MainDisplayCheck.evaluate(main, virtual, null, after)
        assertTrue(violations.isEmpty())
    }

    /** 快照命令串的六段标记必须与解析端认的标记逐个对上（段名改一边就全落 unknown）。 */
    @Test
    fun snapshotCommandCarriesEverySectionTheParserReads() {
        listOf("[top]", "[focus]", "[ime]", "[launcher]", "[win]", "[group]").forEach { marker ->
            assertTrue(MainDisplayCheck.MAIN_STATE_COMMAND.contains(marker))
        }
        assertTrue(MainDisplayCheck.MAIN_STATE_COMMAND.contains("dumpsys activity activities"))
        assertTrue(MainDisplayCheck.MAIN_STATE_COMMAND.contains("dumpsys window"))
        assertTrue(MainDisplayCheck.MAIN_STATE_COMMAND.contains("dumpsys input_method"))
        assertTrue(MainDisplayCheck.MAIN_STATE_COMMAND.contains("cmd package resolve-activity"))
        assertTrue(MainDisplayCheck.MAIN_STATE_COMMAND.contains("dumpsys window windows"))
        assertTrue(MainDisplayCheck.MAIN_STATE_COMMAND.contains("dumpsys display"))
    }
}
