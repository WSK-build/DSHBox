package com.dshbox.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MobileAdaptProbe] 的脚本拼接与标记解析单测（1.3.1 复查 M30）。
 *
 * ## 为什么必须锁「拼接」而不只是「语义」
 *
 * 既有 [MobileAdaptUpToDateProbeTest] 用 JVM 复刻了判定**语义**
 * （存在性 ∧ 哈希相等 ∧ bundle 注册），但生产代码实际发往 guest 的是一段
 * **手工拼接的 shell 字符串**。语义再对，拼错一个连接符就全盘失效，
 * 而这类错误**编译器看不见、运行时也不报错**——只会让判定结果悄悄反转。
 *
 * 本轮就真实踩到过：测试脚手架里漏掉一个 `&&`，把
 * `test -f X` 与 `[ ... ]` 粘成了同一条命令，导致存在性检查形同虚设
 * （表现：本该「已是最新」的场景被判成「需重装」）。生产代码当时是对的，
 * 但足以说明这个位置需要断言兜底。
 *
 * 因此这里断言的是**字符串结构与连接符**，而不是重新实现一遍语义判断。
 */
class MobileAdaptProbeTest {

    private val script = MobileAdaptProbe.buildScript(
        pluginDir = "/root/projects/.dsh/profiles/web/node_modules/@local/dsh-mobile-adapt",
        stageInstall = "/root/projects/.dsh/mobile-adapt/install.sh",
        stagePlugin = "/root/projects/.dsh/mobile-adapt/plugin",
        profilePackageJson = "/root/projects/.dsh/profiles/web/package.json",
    )

    /** 三个分支的标记必须齐全，否则某条路径会静默变成"没有标记"。 */
    @Test
    fun scriptEmitsAllThreeMarkers() {
        assertTrue("缺 NOT_READY 分支", script.contains(MobileAdaptProbe.MARKER_NOT_READY))
        assertTrue("缺 UP_TO_DATE 分支", script.contains(MobileAdaptProbe.MARKER_UP_TO_DATE))
        assertTrue("缺 NEEDS_INSTALL 分支", script.contains(MobileAdaptProbe.MARKER_NEEDS_INSTALL))
    }

    /**
     * **核心回归**：存在性检查与哈希比对之间必须有 `&&` 连接。
     *
     * 没有它，`test -f X [ "a" = "b" ]` 会被 shell 当成**一条** test 命令，
     * 于是存在性判断会连同哈希比对一起错乱。
     * 这是本项目真实踩过的连接符错误（见类注释）。
     */
    @Test
    fun existsChecksAreJoinedToHashComparison() {
        // 最后一个存在性检查（staged 侧 cordis.patch.yml）之后必须紧跟 " && "
        val lastCheck = "test -f /root/projects/.dsh/mobile-adapt/plugin/cordis.patch.yml"
        assertTrue("脚本里应有该存在性检查", script.contains(lastCheck))
        assertTrue(
            "存在性检查后必须以 && 接到哈希比对，实得上下文：'" +
                script.substringAfter(lastCheck).take(20) + "'",
            script.substringAfter(lastCheck).startsWith(" && ["),
        )
    }

    /**
     * 存在性检查必须走 `test -f`，不能退化成"能读到就算存在"的 `cat`。
     *
     * 数量 = 指纹文件数 × 2（两侧）+ 1（未装配检查里的 `test -f $stageInstall`）。
     */
    @Test
    fun existenceChecksUseTestDashFNotCat() {
        val checked = Regex("test -f ").findAll(script).count()
        assertEquals(
            "存在性检查数应为 指纹文件数×2 + 1（staged install.sh）",
            MobileAdaptProbe.FINGERPRINT_FILES.size * 2 + 1,
            checked,
        )
    }

    /** 所有指纹文件在两侧都要被显式检查存在性（不能有一侧漏掉）。 */
    @Test
    fun everyFingerprintFileIsExistenceCheckedOnBothSides() {
        MobileAdaptProbe.FINGERPRINT_FILES.forEach { rel ->
            assertTrue(
                "profile 侧缺存在性检查：$rel",
                script.contains("test -f /root/projects/.dsh/profiles/web/node_modules/@local/dsh-mobile-adapt/$rel"),
            )
            assertTrue(
                "staged 侧缺存在性检查：$rel",
                script.contains("test -f /root/projects/.dsh/mobile-adapt/plugin/$rel"),
            )
        }
    }

    /** 哈希比对两侧都要真的 cat 到三个文件（顺序一致才能比出正确结果）。 */
    @Test
    fun hashComparisonReadsAllFingerprintFilesOnBothSides() {
        val expectedDigest = MobileAdaptProbe.FINGERPRINT_FILES
            .joinToString(" ") { "/root/projects/.dsh/profiles/web/node_modules/@local/dsh-mobile-adapt/$it" }
        assertTrue("profile 侧 cat 列表不完整", script.contains("cat $expectedDigest | sha256sum"))

        val expectedStage = MobileAdaptProbe.FINGERPRINT_FILES
            .joinToString(" ") { "/root/projects/.dsh/mobile-adapt/plugin/$it" }
        assertTrue("staged 侧 cat 列表不完整", script.contains("cat $expectedStage | sha256sum"))
    }

    /** bundle 注册检查必须存在——install.sh 会改 package.json，被 uninstall 后必须重装。 */
    @Test
    fun bundleRegistrationIsChecked() {
        assertTrue(
            "缺 bundle 注册检查（bundle 被移除时应重装）",
            script.contains("grep -q '@local/dsh-mobile-adapt' /root/projects/.dsh/profiles/web/package.json"),
        )
    }

    /** 未装配判断必须先于指纹比对（未装配时不该去 cat 不存在的文件）。 */
    @Test
    fun readinessCheckPrecedesFingerprintCheck() {
        val ready = script.indexOf("test -d ")
        val fingerprint = script.indexOf("cat ", ready)
        assertTrue("就绪检查应在指纹比对之前", ready in 0 until fingerprint)
    }

    /** 脚本以 fi 收尾，且不留会失败的收尾语句（失败须表现为"没有标记"）。 */
    @Test
    fun scriptEndsWithIfAndHasNoFailingTailStatement() {
        assertTrue("脚本应以 fi 收尾", script.trimEnd().endsWith("fi"))
        assertFalse("不应引入会失败的收尾语句", script.contains("exit 1"))
        assertFalse("不应依赖 && / || 串联两条判断", script.contains("|| "))
    }

    // ── 标记解析 ──────────────────────────────────────────────────────────

    /** 三个标记各自能被解析出来。 */
    @Test
    fun parsesEachMarker() {
        assertEquals(
            MobileAdaptProbe.MARKER_NOT_READY,
            MobileAdaptProbe.markerFrom(MobileAdaptProbe.MARKER_NOT_READY),
        )
        assertEquals(
            MobileAdaptProbe.MARKER_UP_TO_DATE,
            MobileAdaptProbe.markerFrom(MobileAdaptProbe.MARKER_UP_TO_DATE),
        )
        assertEquals(
            MobileAdaptProbe.MARKER_NEEDS_INSTALL,
            MobileAdaptProbe.markerFrom(MobileAdaptProbe.MARKER_NEEDS_INSTALL),
        )
    }

    /**
     * guest 输出可能带前后空白或 `\r`（PRoot 管道下实测见过）。
     * 全等匹配会漏判 → "没有标记" → 保守跳过刷新 → 插件永远不更新。
     */
    @Test
    fun parsesMarkerWithSurroundingWhitespaceAndCarriageReturn() {
        assertEquals(
            "带 \\r 的输出必须仍能识别",
            MobileAdaptProbe.MARKER_UP_TO_DATE,
            MobileAdaptProbe.markerFrom("${MobileAdaptProbe.MARKER_UP_TO_DATE}\r"),
        )
        assertEquals(
            "带前后空白的输出必须仍能识别",
            MobileAdaptProbe.MARKER_NOT_READY,
            MobileAdaptProbe.markerFrom("  ${MobileAdaptProbe.MARKER_NOT_READY}  "),
        )
    }

    /** 无关输出（proot 噪声、install.sh 日志）必须返回 null，不能误判为某个状态。 */
    @Test
    fun ignoresUnrelatedLines() {
        assertNull(MobileAdaptProbe.markerFrom(""))
        assertNull(MobileAdaptProbe.markerFrom("proot warning: can't read /proc/1/root"))
        assertNull(MobileAdaptProbe.markerFrom("added 1 package in 2s"))
    }

    /** 指纹文件清单不得悄悄变化——它同时决定两侧的 cat 与 test 列表。 */
    @Test
    fun fingerprintFileListIsStable() {
        assertEquals(
            listOf("lib/client.js", "package.json", "cordis.patch.yml"),
            MobileAdaptProbe.FINGERPRINT_FILES,
        )
    }
}
