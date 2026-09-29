package com.dshbox.app.service

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [OwnPluginProbe] 的三组不变量：**脚本结构**、**判定语义**、**结果到动作的映射**。
 *
 * 三组各挡一类真实缺陷：
 *  - 结构：生产代码发往 guest 的是一段手工拼接的 shell 字符串，语义再对，
 *    拼错一个连接符就全盘失效，而编译器看不见、运行时不报错；
 *  - 语义：「两侧文件同时缺失」时纯哈希比对必然相等（空内容哈希固定），
 *    必须靠存在性检查兜住，否则会跳过安装而插件一个文件都没有；
 *  - 映射：**开关亮着但什么都没装**（假开关）与**关掉之后又被装回来**
 *    都出在这一步 —— 只有"需安装"一个标记允许动用户的 profile。
 */
class OwnPluginProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val profileDir = "/root/projects/.dsh/profiles/web"
    private val pluginDir = "$profileDir/node_modules/@local/mobile-pilot"
    private val stageDir = "/root/projects/.dsh/dshbox/dshbox-plugins/mobile-pilot"
    private val stageInstall = "$stageDir/install.sh"
    private val stagePlugin = "$stageDir/plugin"
    private val profilePackageJson = "$profileDir/package.json"
    private val bundleId = "@local/mobile-pilot"

    /**
     * 参与比对的文件**由调用方给出**，这里刻意用 mobile-pilot 空壳包的组成
     * （只有 `lib/index.js`，**没有** `lib/client.js`）—— 清单若被写死成适配包那一份，
     * 就会出现"清单里有、包里没有"的文件，探测永远判不出「已最新」，
     * 于是每次启动都重装一遍。
     */
    private val fingerprintFiles = listOf("lib/index.js", "package.json", "cordis.patch.yml")

    private val script = OwnPluginProbe.buildScript(
        profileDir = profileDir,
        pluginDir = pluginDir,
        stageInstall = stageInstall,
        stagePlugin = stagePlugin,
        profilePackageJson = profilePackageJson,
        bundleId = bundleId,
        fingerprintFiles = fingerprintFiles,
    )

    // ── 一、脚本结构 ──────────────────────────────────────────────────────

    /** 四个分支的标记必须齐全，否则某条路径会静默变成"没有标记"。 */
    @Test
    fun scriptEmitsAllFourMarkers() {
        assertTrue("缺 PROFILE_MISSING 分支", script.contains(OwnPluginProbe.MARKER_PROFILE_MISSING))
        assertTrue("缺 STAGE_MISSING 分支", script.contains(OwnPluginProbe.MARKER_STAGE_MISSING))
        assertTrue("缺 UP_TO_DATE 分支", script.contains(OwnPluginProbe.MARKER_UP_TO_DATE))
        assertTrue("缺 NEEDS_INSTALL 分支", script.contains(OwnPluginProbe.MARKER_NEEDS_INSTALL))
    }

    /**
     * 否决顺序：profile 不存在 → staged 缺失 → 已最新 → 需安装。
     *
     * 顺序错了两处会走偏：把 staged 缺失排在 profile 之前，DSH 从没跑过时报的就是
     * 「素材没就绪」，排查方向直接跑偏；把「已最新」排在「能不能装」之前，
     * 会在装不成的情况下宣称已最新。
     */
    @Test
    fun vetoOrderIsProfileThenStageThenUpToDateThenInstall() {
        val atProfile = script.indexOf(OwnPluginProbe.MARKER_PROFILE_MISSING)
        val atStage = script.indexOf(OwnPluginProbe.MARKER_STAGE_MISSING)
        val atUpToDate = script.indexOf(OwnPluginProbe.MARKER_UP_TO_DATE)
        val atInstall = script.indexOf(OwnPluginProbe.MARKER_NEEDS_INSTALL)
        assertTrue("四个标记都应出现", listOf(atProfile, atStage, atUpToDate, atInstall).all { it > 0 })
        assertTrue("profile 不存在应最先判", atProfile < atStage)
        assertTrue("staged 缺失应早于「已最新」", atStage < atUpToDate)
        assertTrue("「已最新」应早于「需安装」", atUpToDate < atInstall)
    }

    /**
     * **核心回归**：存在性检查与哈希比对之间必须有 `&&` 连接。
     *
     * 没有它，`test -f X [ "a" = "b" ]` 会被 shell 当成**一条** test 命令，
     * 于是存在性判断会连同哈希比对一起错乱。这个连接符错误真实发生过。
     */
    @Test
    fun existsChecksAreJoinedToHashComparison() {
        val lastCheck = "test -f $stagePlugin/cordis.patch.yml"
        assertTrue("脚本里应有该存在性检查", script.contains(lastCheck))
        assertTrue(
            "存在性检查后必须以 && 接到哈希比对，实得上下文：'" +
                script.substringAfter(lastCheck).take(20) + "'",
            script.substringAfter(lastCheck).startsWith(" && ["),
        )
    }

    /** 存在性检查必须走 `test -f`，不能退化成"能读到就算存在"的 `cat`。 */
    @Test
    fun existenceChecksUseTestDashFNotCat() {
        val checked = Regex("test -f ").findAll(script).count()
        assertEquals(
            "存在性检查数应为 指纹文件数×2 + 1（staged install.sh）",
            fingerprintFiles.size * 2 + 1,
            checked,
        )
    }

    /** 所有指纹文件在两侧都要被显式检查存在性（不能有一侧漏掉）。 */
    @Test
    fun everyFingerprintFileIsExistenceCheckedOnBothSides() {
        fingerprintFiles.forEach { rel ->
            assertTrue("profile 侧缺存在性检查：$rel", script.contains("test -f $pluginDir/$rel"))
            assertTrue("staged 侧缺存在性检查：$rel", script.contains("test -f $stagePlugin/$rel"))
        }
    }

    /**
     * 脚本用的是**调用方给的**清单，不是内建的那一份。
     *
     * 这条挡的是本轮真实踩到的缺陷：把适配包的清单（含 `lib/client.js`）套到没有该文件的
     * 空壳包上，`test -f` 永远不过 → 每次都判需安装 → 每次启动重装一遍。
     */
    @Test
    fun scriptUsesTheCallerSuppliedFingerprintList() {
        fingerprintFiles.forEach { assertTrue("清单里的 $it 没进脚本", script.contains("/$it")) }
        assertFalse(
            "不该出现在清单外的文件（例如空壳包没有的 lib/client.js）",
            script.contains("lib/client.js"),
        )
    }

    /** 哈希比对两侧都要真的 cat 到清单里的全部文件（顺序一致才能比出正确结果）。 */
    @Test
    fun hashComparisonReadsAllFingerprintFilesOnBothSides() {
        val expectedProfile = fingerprintFiles.joinToString(" ") { "$pluginDir/$it" }
        assertTrue("profile 侧 cat 列表不完整", script.contains("cat $expectedProfile | sha256sum"))

        val expectedStage = fingerprintFiles.joinToString(" ") { "$stagePlugin/$it" }
        assertTrue("staged 侧 cat 列表不完整", script.contains("cat $expectedStage | sha256sum"))
    }

    /** bundle 注册检查必须存在——install.sh 会改 package.json，被 uninstall 后必须重装。 */
    @Test
    fun bundleRegistrationIsChecked() {
        assertTrue(
            "缺 bundle 注册检查（bundle 被移除时应重装）",
            script.contains("grep -q '$bundleId' $profilePackageJson"),
        )
    }

    /** 脚本以 fi 收尾，且不留会失败的收尾语句（失败须表现为"没有标记"）。 */
    @Test
    fun scriptEndsWithIfAndHasNoFailingTailStatement() {
        assertTrue("脚本应以 fi 收尾", script.trimEnd().endsWith("fi"))
        assertFalse("不应引入会失败的收尾语句", script.contains("exit 1"))
        assertFalse("不应依赖 || 串联判断", script.contains("|| "))
    }

    // ── 二、判定语义（JVM 复刻，与脚本一一对应）──────────────────────────

    private fun digest(root: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        fingerprintFiles.forEach { md.update(File(root, it).readBytes()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 复刻生产判定：profile 存在 ∧ staged 就绪 ∧ 两侧齐 ∧ 哈希相等 ∧ bundle 注册。 */
    private fun markerFor(
        profile: File,
        stagePlugin: File,
        stageInstall: File,
        profilePackageJson: File,
    ): String = when {
        !profile.isDirectory -> OwnPluginProbe.MARKER_PROFILE_MISSING
        !stageInstall.isFile -> OwnPluginProbe.MARKER_STAGE_MISSING
        else -> {
            val plugin = File(profile, "node_modules/$bundleId")
            val allExist = fingerprintFiles.all {
                File(plugin, it).isFile && File(stagePlugin, it).isFile
            }
            when {
                !allExist -> OwnPluginProbe.MARKER_NEEDS_INSTALL
                digest(plugin) != digest(stagePlugin) -> OwnPluginProbe.MARKER_NEEDS_INSTALL
                !profilePackageJson.isFile -> OwnPluginProbe.MARKER_NEEDS_INSTALL
                !profilePackageJson.readText().contains(bundleId) -> OwnPluginProbe.MARKER_NEEDS_INSTALL
                else -> OwnPluginProbe.MARKER_UP_TO_DATE
            }
        }
    }

    private fun writeFiles(root: File, content: String) {
        fingerprintFiles.forEach { rel ->
            File(root, rel).apply {
                parentFile?.mkdirs()
                writeText(content)
            }
        }
    }

    /** profile 目录、staged install.sh、staged 插件、profile package.json。 */
    private fun setup(): Setup {
        val root = tmp.newFolder("root")
        val profile = File(root, "profiles/web").apply { mkdirs() }
        val stage = File(root, "stage")
        return Setup(
            profile = profile,
            plugin = File(profile, "node_modules/$bundleId"),
            stagePlugin = File(stage, "plugin").apply { mkdirs() },
            stageInstall = File(stage, "install.sh").apply {
                parentFile?.mkdirs()
                writeText("#!/bin/sh\n")
            },
            pkg = File(profile, "package.json").apply {
                writeText("""{"dsh":{"profile":{"bundles":["$bundleId"]}}}""")
            },
        )
    }

    private class Setup(
        val profile: File,
        val plugin: File,
        val stagePlugin: File,
        val stageInstall: File,
        val pkg: File,
    )

    @Test
    fun profileAbsentIsReportedBeforeAnythingElse() {
        val s = setup()
        s.profile.deleteRecursively()
        assertEquals(
            OwnPluginProbe.MARKER_PROFILE_MISSING,
            markerFor(s.profile, s.stagePlugin, s.stageInstall, s.pkg),
        )
    }

    @Test
    fun missingStagedInstallIsReportedAsStageMissing() {
        val s = setup()
        s.stageInstall.delete()
        assertEquals(
            OwnPluginProbe.MARKER_STAGE_MISSING,
            markerFor(s.profile, s.stagePlugin, s.stageInstall, s.pkg),
        )
    }

    /**
     * 插件目录整个不存在（= 新装用户，开关为开）→ 必须判为需安装。
     *
     * 这条正是"开关只是亮着"与"真的装配进去"的分界：旧逻辑把它归入"未就绪、跳过"，
     * 于是新装用户的开关是假开关。
     */
    @Test
    fun absentPluginDirMeansInstallRatherThanSkip() {
        val s = setup()
        writeFiles(s.stagePlugin, "same")
        assertFalse("前提：profile 里还没有这个插件", s.plugin.exists())

        val marker = markerFor(s.profile, s.stagePlugin, s.stageInstall, s.pkg)
        assertEquals(OwnPluginProbe.MARKER_NEEDS_INSTALL, marker)
        assertEquals(
            "这个标记必须落到「装配」而不是「跳过」",
            OwnPluginProbe.Action.ENSURE_INSTALL,
            OwnPluginProbe.actionFor(marker),
        )
    }

    @Test
    fun identicalContentIsUpToDate() {
        val s = setup()
        writeFiles(s.plugin, "same")
        writeFiles(s.stagePlugin, "same")
        assertEquals(
            OwnPluginProbe.MARKER_UP_TO_DATE,
            markerFor(s.profile, s.stagePlugin, s.stageInstall, s.pkg),
        )
    }

    @Test
    fun differentContentNeedsReinstall() {
        val s = setup()
        writeFiles(s.plugin, "old")
        writeFiles(s.stagePlugin, "new")
        assertEquals(
            OwnPluginProbe.MARKER_NEEDS_INSTALL,
            markerFor(s.profile, s.stagePlugin, s.stageInstall, s.pkg),
        )
    }

    @Test
    fun missingFileOnOneSideNeedsReinstall() {
        val s = setup()
        writeFiles(s.plugin, "same")
        writeFiles(s.stagePlugin, "same")
        File(s.plugin, "lib/index.js").delete()
        assertEquals(
            "profile 侧缺文件必须重装",
            OwnPluginProbe.MARKER_NEEDS_INSTALL,
            markerFor(s.profile, s.stagePlugin, s.stageInstall, s.pkg),
        )
    }

    /**
     * **核心回归**：两侧文件**同时缺失** + bundle 仍注册 → 必须重装。
     *
     * 两侧空内容的哈希相等（`e3b0c442…`），加上 bundle 已注册，纯哈希比对会误判
     * 「已最新」而跳过安装 —— 但插件其实空无一物。存在性检查是唯一能堵住它的条件。
     */
    @Test
    fun bothSidesMissingMustNotBeTreatedAsUpToDate() {
        val s = setup()
        s.plugin.mkdirs()
        fingerprintFiles.forEach {
            File(s.plugin, it).delete()
            File(s.stagePlugin, it).delete()
        }
        assertTrue(
            "前提：两侧确实一个文件都没有",
            fingerprintFiles.none { File(s.plugin, it).isFile || File(s.stagePlugin, it).isFile },
        )
        assertTrue("前提：bundle 仍注册（这正是会骗过纯哈希比对的组合）", s.pkg.readText().contains(bundleId))

        assertEquals(
            "两侧全缺必须判为需重装",
            OwnPluginProbe.MARKER_NEEDS_INSTALL,
            markerFor(s.profile, s.stagePlugin, s.stageInstall, s.pkg),
        )
    }

    /** bundle 被移除（用户卸载过）→ 即使内容一致也必须重装。 */
    @Test
    fun removedBundleNeedsReinstall() {
        val s = setup()
        writeFiles(s.plugin, "same")
        writeFiles(s.stagePlugin, "same")
        s.pkg.writeText("""{"dsh":{"profile":{"bundles":[]}}}""")
        assertEquals(
            OwnPluginProbe.MARKER_NEEDS_INSTALL,
            markerFor(s.profile, s.stagePlugin, s.stageInstall, s.pkg),
        )
    }

    /** 空内容哈希确实是固定值（说明「两侧空」为何会骗过纯哈希比对）。 */
    @Test
    fun emptyContentHashIsStableWhichIsWhyExistenceCheckIsRequired() {
        val empty = MessageDigest.getInstance("SHA-256").digest(ByteArray(0))
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "空内容的 sha256 是固定值 —— 两侧都缺文件时纯哈希比对必然相等",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            empty,
        )
    }

    // ── 三、结果 → 动作 ───────────────────────────────────────────────────

    /** 只有"需安装"允许动用户的 profile；其余一律跳过。 */
    @Test
    fun onlyNeedsInstallTriggersAssembly() {
        assertEquals(
            OwnPluginProbe.Action.ENSURE_INSTALL,
            OwnPluginProbe.actionFor(OwnPluginProbe.MARKER_NEEDS_INSTALL),
        )
        assertEquals(OwnPluginProbe.Action.SKIP, OwnPluginProbe.actionFor(OwnPluginProbe.MARKER_UP_TO_DATE))
        assertEquals(OwnPluginProbe.Action.SKIP, OwnPluginProbe.actionFor(OwnPluginProbe.MARKER_PROFILE_MISSING))
        assertEquals(OwnPluginProbe.Action.SKIP, OwnPluginProbe.actionFor(OwnPluginProbe.MARKER_STAGE_MISSING))
        assertEquals(
            "拿不到标记（proot 失败 / guest 异常 / 脚本被中断）时必须放过用户 profile",
            OwnPluginProbe.Action.SKIP,
            OwnPluginProbe.actionFor(null),
        )
    }

    // ── 四、标记解析 ──────────────────────────────────────────────────────

    @Test
    fun parsesEachMarker() {
        listOf(
            OwnPluginProbe.MARKER_PROFILE_MISSING,
            OwnPluginProbe.MARKER_STAGE_MISSING,
            OwnPluginProbe.MARKER_UP_TO_DATE,
            OwnPluginProbe.MARKER_NEEDS_INSTALL,
        ).forEach { assertEquals(it, OwnPluginProbe.markerFrom(it)) }
    }

    /**
     * guest 输出可能带前后空白或 `\r`（PRoot 管道下会出现）。
     * 全等匹配会漏判 → "没有标记" → 保守跳过 → 插件永远不更新。
     */
    @Test
    fun parsesMarkerWithSurroundingWhitespaceAndCarriageReturn() {
        assertEquals(
            "带 \\r 的输出必须仍能识别",
            OwnPluginProbe.MARKER_UP_TO_DATE,
            OwnPluginProbe.markerFrom("${OwnPluginProbe.MARKER_UP_TO_DATE}\r"),
        )
        assertEquals(
            "带前后空白的输出必须仍能识别",
            OwnPluginProbe.MARKER_PROFILE_MISSING,
            OwnPluginProbe.markerFrom("  ${OwnPluginProbe.MARKER_PROFILE_MISSING}  "),
        )
    }

    /** 无关输出（proot 噪声、install.sh 日志）必须返回 null，不能误判为某个状态。 */
    @Test
    fun ignoresUnrelatedLines() {
        assertNull(OwnPluginProbe.markerFrom(""))
        assertNull(OwnPluginProbe.markerFrom("proot warning: can't read /proc/1/root"))
        assertNull(OwnPluginProbe.markerFrom("added 1 package in 2s"))
    }

    /**
     * 清单为空也要能拼出可执行的脚本（`cat` 无参数 → 空内容 → 两侧相等）。
     *
     * 空清单只可能在暂存目录不可读时出现，那时 `STAGE_MISSING` 会先兜住；
     * 这里只保证拼接本身不产生语法上跑不通的脚本。
     */
    @Test
    fun emptyFingerprintListStillYieldsARunnableScript() {
        val s = OwnPluginProbe.buildScript(
            profileDir = profileDir,
            pluginDir = pluginDir,
            stageInstall = stageInstall,
            stagePlugin = stagePlugin,
            profilePackageJson = profilePackageJson,
            bundleId = bundleId,
            fingerprintFiles = emptyList(),
        )
        assertTrue("仍应以 fi 收尾", s.trimEnd().endsWith("fi"))
        assertTrue("仍应含四个标记分支", listOf(
            OwnPluginProbe.MARKER_PROFILE_MISSING,
            OwnPluginProbe.MARKER_STAGE_MISSING,
            OwnPluginProbe.MARKER_UP_TO_DATE,
            OwnPluginProbe.MARKER_NEEDS_INSTALL,
        ).all { s.contains(it) })
    }
}
