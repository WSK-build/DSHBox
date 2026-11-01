package com.dshbox.pluginmanager.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **宿主契约哨兵**：钉住本模块依赖的每一条宿主事实。
 *
 * ## 这套测试的用途（不是普通单测）
 *
 * 普通单测验证模块逻辑；这套验证**对宿主的假设是否仍成立**。
 * 上游改一个词、换一个字段名、挪一个目录，这里就红——**这是设计目的，不是脆弱**。
 *
 * 升级宿主时，正确的流程是：
 * 1. 跑这套测试 → 红的部分就是"契约变了，需要重新核对上游"；
 * 2. 逐条对照宿主源码确认新形态；
 * 3. 改 [HostContract] / [HostSignals] / [HostCapabilities] 一处，再跑全量测试。
 *
 * 因此**每一条断言都写明了它对应的上游来源**——红了就知道去哪儿查。
 *
 * ## 与其他测试的关系
 *
 * 本文件只断言**契约常量本身**（字面量、正则行为、能力判定），不碰业务逻辑；
 * 业务行为由各自的测试覆盖（如 `BootDenialScannerTest` 验证解析结果）。
 */
class HostContractSentinelTest {

    // ───────────────── 命名空间与包标识 ─────────────────

    @Test
    fun `官方命名空间与宿主包名是契约的核心`() {
        // 来源：宿主所有官方包都在 @deepseek-ai/ 下；主包名用于版本/peer/布局解析。
        assertEquals("@deepseek-ai/", HostContract.OFFICIAL_NAMESPACE)
        assertEquals("@deepseek-ai/dsh", HostContract.HOST_PACKAGE)
        // 判定行为
        assertTrue(HostContract.isOfficial("@deepseek-ai/dsh-tools"))
        assertFalse(HostContract.isOfficial("dshmarket"))
        assertFalse(HostContract.isOfficial(null))
    }

    @Test
    fun `loader 合成条目命名空间是 cordis 冒号`() {
        // 来源：loader 自身产生的条目（include/loader/group/base…）以 `cordis:` 为前缀；
        // 停用它们会破坏加载链本身，因此必须能被识别。
        assertEquals("cordis:", HostContract.LOADER_NAMESPACE)
        assertTrue(HostContract.isLoaderEntry("cordis:include"))
        assertFalse(HostContract.isLoaderEntry("dsh-market"))
    }

    @Test
    fun `我方随包资产命名空间是 at local`() {
        // 来源：我方三个随包插件（dsh-mobile-adapt / mobile-pilot / browser-use）都在
        // `@local/` 下；它们**不属于第三方**，也受保护不被自动隔离。
        assertEquals("@local/", HostContract.LOCAL_NAMESPACE)
        assertTrue(HostContract.isLocalAsset("@local/dsh-mobile-adapt"))
        assertFalse(HostContract.isLocalAsset("@deepseek-ai/dsh"))
    }

    @Test
    fun `宿主 peer 声明覆盖同发布线的全部包`() {
        // 来源：peer 声明可能是 @deepseek-ai/dsh 本身或 @deepseek-ai/dsh-* 子包。
        assertTrue(HostContract.HOST_PEER_RE.containsMatchIn("@deepseek-ai/dsh"))
        assertTrue(HostContract.HOST_PEER_RE.containsMatchIn("@deepseek-ai/dsh-settings"))
        // 边界：不能把 @deepseek-ai/dshx 这类不同包误收（`-` 或结束才算）。
        assertFalse(HostContract.HOST_PEER_RE.containsMatchIn("@deepseek-ai/dshx"))
    }

    // ───────────────── profile 结构与字段 ─────────────────

    @Test
    fun `profile 文件名与字段路径是契约`() {
        // 来源：宿主 profile 目录。
        assertEquals("package.json", HostContract.PROFILE_MANIFEST)
        assertEquals("cordis.patch.yml", HostContract.USER_PATCH)
        assertEquals("pnpm-workspace.yaml", HostContract.PNPM_WORKSPACE)
        assertEquals("compatibility.json", HostContract.COMPATIBILITY)
        assertEquals("node_modules", HostContract.NODE_MODULES)
        assertEquals("dependencies", HostContract.FIELD_DEPENDENCIES)
        // bundle 层栈：dsh.profile.bundles（顺序即层优先级）。
        assertEquals(listOf("dsh", "profile", "bundles"), HostContract.FIELD_PROFILE_BUNDLES)
    }

    @Test
    fun `插件清单字段名是契约`() {
        // 来源：`dsh` 是"这是 DSH 插件"的唯一标志；bundle/client/patch 是入口声明。
        assertEquals("dsh", HostContract.FIELD_DSH)
        assertEquals("bundle", HostContract.FIELD_BUNDLE)
        assertEquals("client", HostContract.FIELD_CLIENT)
        assertEquals("patch", HostContract.FIELD_PATCH)
        assertEquals(listOf("main", "exports"), HostContract.ENTRY_FIELDS)
        assertEquals(listOf("import", "require", "default", "browser", "node"), HostContract.EXPORT_ENTRY_KEYS)
    }

    // ───────────────── 运行层布局 ─────────────────

    @Test
    fun `运行层布局与版本记录位置是契约`() {
        // 来源：DSH 层落在 runtime-current/dsh；版本权威记录在 .dshbox/version。
        assertEquals("runtime/runtime-current", HostContract.RUNTIME_CURRENT)
        assertEquals("dsh", HostContract.DSH_LAYER)
        assertEquals(".dshbox/version", HostContract.VERSION_FILE)
    }

    @Test
    fun `guest 侧布局是契约`() {
        // 来源：user-data 绑定在 guest 的 /root/projects，.dsh 是它的 home。
        assertEquals("/root/projects/.dsh", HostContract.GUEST_DSH_HOME)
        assertEquals("/root/projects/.dsh/profiles/web", HostContract.GUEST_WEB_PROFILE)
    }

    // ───────────────── 补丁方言 ─────────────────

    @Test
    fun `补丁行语法能认出宿主认可的两种写法`() {
        // 来源：宿主的 patch 方言——`- id: X` + 缩进 2 格的 disabled；`- insert:` + 4 格子项。
        assertTrue(PatchSyntax.TOP_ENTRY.matches("- id: some-entry"))
        assertTrue(PatchSyntax.INSERT_ITEM.matches("    - id: inserted-entry"))
        assertTrue(PatchSyntax.FIELD_DISABLED.matches("  disabled: true"))
        assertTrue(PatchSyntax.FIELD_DISABLED.matches("  disabled: false"))
        assertTrue(PatchSyntax.INSERT_HEAD.matches("- insert:"))
        assertTrue(PatchSyntax.INLINE_INSERT.matches("- insert: [ { id: x } ]"))
    }

    @Test
    fun `可写 id 形态与裸标量判定是契约`() {
        // 来源：宿主自身的合成输出里，@scope/pkg 这类 id 带单引号；裸标量只允许
        // [A-Za-z0-9_.-]。写层必须遵守，否则破坏 YAML 结构。
        assertTrue(PatchSyntax.isValidId("dsh-market"))
        assertTrue(PatchSyntax.isValidId("@local/mobile-pilot"))
        assertTrue("子条目 id 可写，但必须加引号", PatchSyntax.isValidId("dsh-plugin-x:side-panel"))
        assertFalse("空格不是可写形态", PatchSyntax.isValidId("has space"))
        assertFalse("换行不是可写形态", PatchSyntax.isValidId("a\nb"))
        assertFalse("引号不是可写形态", PatchSyntax.isValidId("a'b"))
        // 裸标量 vs 需引号
        assertEquals("plain", PatchSyntax.renderId("plain"))
        assertEquals("'@scope/pkg'", PatchSyntax.renderId("@scope/pkg"))
        assertEquals("'dsh-plugin-x:side-panel'", PatchSyntax.renderId("dsh-plugin-x:side-panel"))
    }

    @Test
    fun `约定补丁文件名与空层写法是契约`() {
        // 来源：loader 会去探包根的 cordis.patch.yml；空层必须写成 `[]`（空文件会让
        // 宿主解析失败）。
        assertEquals("cordis.patch.yml", PatchSyntax.CONVENTIONAL_PATCH)
        assertEquals("[]\n", PatchSyntax.EMPTY_LAYER)
    }

    // ───────────────── 宿主输出文本（最脆的一层） ─────────────────

    @Test
    fun `装配失败归因串的三个形态`() {
        // 来源：cordis-plugin-loader 的 `failed to <stage> loader entry <id> (<name>): <detail>`；
        // 0.2.x 的 `N entries did not activate`；以及只有名字的 `plugin(s) failed to load:`。
        val apply = HostSignals.LOADER_ENTRY_FAILURE.find(
            "failed to apply loader entry bad-a (@scope/bad-a): boom",
        )
        assertEquals("bad-a", apply?.groupValues?.get(1))
        assertEquals("@scope/bad-a", apply?.groupValues?.get(2))
        assertEquals("boom", apply?.groupValues?.get(3))

        assertTrue(HostSignals.ENTRIES_NOT_ACTIVATED.containsMatchIn("3 entries did not activate"))
        assertTrue(HostSignals.PLUGIN_FAILED_TO_LOAD.containsMatchIn("plugin(s) failed to load: a, b"))
        assertEquals("loader entries failed to apply", HostSignals.LOADER_AGGREGATE)
    }

    @Test
    fun `0_2_x 的装配拒绝与 bundle 跳过两种行`() {
        // 来源：dsh-app-boot 的 prepareProfileEntries / reportSkippedBundles。
        val rowDenied = HostSignals.PLUGIN_DISABLED_ROW.find(
            """dsh: disabling profile plugin row "some-entry": incompatible""",
        )
        assertEquals("some-entry", rowDenied?.groupValues?.get(1))

        // 关键边界：条目 id 允许含冒号（cordis:xxx），必须按引号成对提取。
        val colonId = HostSignals.PLUGIN_DISABLED_ROW.find(
            """dsh: disabling profile plugin row "cordis:web-cache": peer ^2""",
        )
        assertEquals("cordis:web-cache", colonId?.groupValues?.get(1))

        val nameDenied = HostSignals.PLUGIN_DISABLED_NAME.find(
            "dsh: disabling profile plugin other-pkg: peer mismatch",
        )
        assertEquals("other-pkg", nameDenied?.groupValues?.get(1))

        val skipped = HostSignals.BUNDLE_SKIPPED.find(
            """dsh: skipping profile bundle "@local/x": manifest unreadable""",
        )
        assertEquals("@local/x", skipped?.groupValues?.get(1))
    }

    @Test
    fun `补丁层告警与进程分段标记`() {
        // 来源：宿主 patch 应用告警 `patch: entry <id> not found`（不是失败，只是那行没生效）；
        // node 退出打印 `Node.js v…` 用作一次启动尝试的分段标记。
        assertEquals("missing-row", HostSignals.PATCH_ENTRY_NOT_FOUND.find("patch: entry missing-row not found")?.groupValues?.get(1))
        assertTrue(HostSignals.NODE_TRAILER.containsMatchIn("Node.js v22.19.0"))
        assertTrue(HostSignals.BOOT_MARKER.matches("===== DSH boot 2026-10-14 09:00:00 ====="))
    }

    @Test
    fun `安装链失败文本的各个形态`() {
        // 来源：pnpm 与宿主 dsh-plugin-manager 的输出。
        assertTrue(HostSignals.PNPM_MISSING.containsMatchIn("pnpm not found on PATH"))
        assertTrue(HostSignals.IGNORED_BUILDS.containsMatchIn("Ignored build scripts: esbuild"))
        assertTrue(HostSignals.INCOMPATIBLE.containsMatchIn("dsh: installation rejected: Plugin x@1.0 is incompatible with dsh 0.2.0-rc.2"))
        assertTrue(HostSignals.NOT_FOUND.containsMatchIn("ERR_PNPM_FETCH_404"))
        assertTrue(HostSignals.TRANSIENT.containsMatchIn("ECONNRESET"))
        assertTrue(HostSignals.TIMEOUT.containsMatchIn("ERR_PNPM_FETCH_TIMEOUT"))
        assertEquals("ERR_PNPM_UNEXPECTED_STORE", HostSignals.ERR_PNPM_CODE.find("x ERR_PNPM_UNEXPECTED_STORE y")?.value)
    }

    // ───────────────── 能力判定 ─────────────────

    @Test
    fun `能力判定只看本地痕迹，与宿主版本无关`() {
        // 版本不参与：同一组痕迹给出同一组能力，调用签名里也没有版本参数。
        val bare = HostCapabilities.detect(hasCompatibilityFile = false, hasPluginManagerPackage = false)
        assertFalse("无豁免文件即无门禁", bare.enforcesVersionCompatibility)
        assertFalse("跳过式容错无本地痕迹可判", bare.skipsFailedEntries)
        assertFalse("无官方插件命令", bare.hasOfficialPluginCommand)
        assertTrue("数组声明一律按数组读", bare.acceptsPatchArray)

        val full = HostCapabilities.detect(hasCompatibilityFile = true, hasPluginManagerPackage = true)
        assertTrue(full.enforcesVersionCompatibility)
        assertTrue(full.hasOfficialPluginCommand)
    }

    @Test
    fun `保守与最宽能力集是探测的两个端点`() {
        // CONSERVATIVE：探测不到宿主时用（不假装支持）。
        val conservative = HostCapabilities.Capabilities.CONSERVATIVE
        assertFalse(conservative.acceptsPatchArray)
        assertFalse(conservative.hasOfficialPluginCommand)
        // WIDEST：未接线探测时的默认（保证重构不改行为），生产路径不得使用。
        val widest = HostCapabilities.Capabilities.WIDEST
        assertTrue(widest.acceptsPatchArray)
        assertTrue(widest.hasOfficialPluginCommand)
    }

    @Test
    fun `启动标记形态与写入侧一致`() {
        // 写入侧（sandbox-manager）按同一格式写，读取侧靠这条正则切段；
        // 任一侧改动都会让"最后一次启动"判错。
        assertTrue(HostSignals.BOOT_MARKER.matches("===== DSH boot 2026-10-30 22:19:54 ====="))
    }
}
