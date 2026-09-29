package com.dshbox.pluginmanager.safety

import com.dshbox.pluginmanager.core.GuestCommandResult
import com.dshbox.pluginmanager.core.GuestCommandRunner
import com.dshbox.pluginmanager.layer.HostInfrastructure
import com.dshbox.pluginmanager.layer.PluginLayer
import com.dshbox.pluginmanager.safemode.AbsoluteSafeMode
import com.dshbox.pluginmanager.safemode.ComposedEntry
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归因、隔离状态与绝对安全模式的回归测试。
 *
 * 这里的用例大多来自真实失败日志形态（链式 apply、顶格点名、聚合收尾行），
 * 改动归因或层写入前先看它们：认错插件会把整棵插件树停用，
 * 而误判"读不出来"会静默清空隔离清单。
 */
class SafetyModeTest {

    private class FakeGuest(private val lines: List<String>, private val ok: Boolean = true) :
        GuestCommandRunner {
        override suspend fun run(command: String, onLine: (String) -> Unit): GuestCommandResult {
            lines.forEach(onLine)
            return GuestCommandResult(ok, lines)
        }
    }

    private fun tempFile(name: String = "f.json"): File =
        File(Files.createTempDirectory("pm-test").toFile(), name)

    private fun safeMode(
        guest: GuestCommandRunner = FakeGuest(emptyList()),
        layer: PluginLayer = PluginLayer(tempFile("overlay.yml")),
    ): AbsoluteSafeMode = AbsoluteSafeMode(guest, layer, profile = "web")

    // ------------------------------------------------------------ 归因

    @Test
    fun `同一次失败的多行报告要全部认出`() {
        // 真机样本：一次失败里 bad-a 与 bad-b 各占一行。只取"最后一行"会漏掉 bad-a，
        // 于是 DSH 重启后仍然起不来，用户要多等一整轮（实测两三分钟）。
        val text = """
            Error: dsh: plugin tree failed to load: failed to apply loader entry include (cordis:include): loader entries failed to apply
            AggregateError: loader entries failed to apply
            Error: failed to import loader entry bad-a (bad-a): BOOM-A: import 阶段故意抛错
            Error: failed to apply loader entry bad-b (bad-b): BOOM-B: apply 阶段故意抛错
            Node.js v24.19.0
        """.trimIndent()
        val hits = FailureAttribution.parse(text)
        // 三条都要认出来：链根 include（随后被"受保护"规则滤掉）+ 两个真正的坏插件。
        assertEquals(listOf("include", "bad-a", "bad-b"), hits.map { it.id })
        assertTrue(
            "链根属于 loader 的合成条目，必须被判为受保护、不得写入层",
            HostInfrastructure.isProtected("include", "cordis:include", emptySet()),
        )
        assertFalse(HostInfrastructure.isProtected("bad-a", "bad-a", emptySet()))
    }

    @Test
    fun `只解析最近一次启动尝试的失败`() {
        // 每次尝试都以 `Node.js v…` 结尾，用它分段：旧的失败不该再被拿来隔离。
        val text = """
            failed to apply loader entry old-one (old-one): boom
            Node.js v24.19.0
            failed to import loader entry new-one (new-one): boom2
            Node.js v24.19.0
        """.trimIndent()
        assertEquals(listOf("new-one"), FailureAttribution.parse(text).map { it.id })
    }

    @Test
    fun `解析最精确的 apply 失败行`() {
        val hits = FailureAttribution.parse(
            listOf(
                "[plugin] dshmarket@1.58.0 ok",
                "failed to apply loader entry some-broken (some-broken-plugin): Cannot find module 'x'",
            ),
        )
        assertEquals(1, hits.size)
        assertEquals("some-broken", hits[0].id)
        assertEquals("some-broken-plugin", hits[0].name)
        assertEquals("Cannot find module 'x'", hits[0].reason)
        assertEquals(FailureHit.Kind.APPLY, hits[0].kind)
    }

    @Test
    fun `链式失败取链尾而不是 include`() {
        // 真实形态：整棵树挂在唯一一个 cordis:include 上，失败从它开始逐层点名。
        val line = "failed to apply loader entry include (cordis:include): " +
            "failed to apply loader entry group (wrapping): " +
            "failed to apply loader entry dsh-demo-broken (dsh-demo-broken-plugin): boom"
        val hits = FailureAttribution.parse(listOf(line))
        assertEquals(1, hits.size)
        // 取第一个会得到 include —— 停用它等于关掉全部插件。
        assertEquals("dsh-demo-broken", hits[0].id)
        assertEquals("dsh-demo-broken-plugin", hits[0].name)
    }

    @Test
    fun `收尾行计数把「这一次尝试」和上一次分开`() {
        // 判定"本次启动失败"必须用这个计数：日志里旧尝试的失败文本一直在，
        // 只看文本会在起步几秒就误判失败（真机上表现为刚开跑就被重启、
        // 以及 DSH 已经起来之后又白隔离一次）。
        val text = """
            failed to import loader entry bad-a (bad-a): BOOM-A
            Node.js v24.19.0
            failed to apply loader entry bad-b (bad-b): BOOM-B
            Node.js v24.19.0
        """.trimIndent()
        assertEquals(2, FailureAttribution.trailerCount(text))
        // 尚未收尾（正在启动）的尝试：计数不变 = 不算失败。
        assertEquals(
            2,
            FailureAttribution.trailerCount(text + "\nstarting web server on 3080"),
        )
    }

    @Test
    fun `失败原文摘要给的是点名行`() {
        val text = """
            failed to import loader entry bad-a (bad-a): BOOM-A
            Node.js v24.19.0
            something else
            failed to apply loader entry bad-b (bad-b): BOOM-B
            Node.js v24.19.0
        """.trimIndent()
        val snippet = FailureAttribution.failureSnippet(text)
        assertTrue(snippet, snippet.contains("bad-b"))
        assertTrue(snippet, !snippet.contains("bad-a"))
    }

    @Test
    fun `原因文本里含 failed to 也能认出插件`() {
        // 曾用字面前缀 `failed to ` 切链尾：原因里出现同样短语时，切出来的一段
        // 不再是 apply 形状，整行被丢掉——守卫会报"认不出插件"，一次都不隔离。
        val hits = FailureAttribution.parse(
            listOf("failed to apply loader entry dsh-broken (dsh-broken-plugin): failed to import module 'x'"),
        )
        assertEquals(1, hits.size)
        assertEquals("dsh-broken", hits[0].id)
        assertEquals("failed to import module 'x'", hits[0].reason)
    }

    @Test
    fun `点名行里的命名空间不丢且受保护`() {
        // 按第一个冒号切分会得到 `cordis`，于是 `cordis:` 保护失效、
        // 还会把 `cordis` 当成条目 id 写进层。
        val text = """
            1 entries did not activate
            cordis:include: failed to resolve service y
        """.trimIndent()
        val hits = FailureAttribution.parse(text)
        assertEquals(listOf("cordis:include"), hits.map { it.name })
        assertTrue(HostInfrastructure.isProtected("x", hits[0].name, emptySet()))
    }

    @Test
    fun `只有最后一次失败的那组才算数`() {
        val hits = FailureAttribution.parse(
            listOf(
                "failed to apply loader entry old-one (old-plugin): boom",
                "loader entries failed to apply",
                "…重启后…",
                "failed to apply loader entry new-one (new-plugin): boom2",
            ),
        )
        assertEquals(listOf("new-one"), hits.map { it.id })
    }

    @Test
    fun `聚合收尾行出现在明细之后也能解析出插件`() {
        val hits = FailureAttribution.parse(
            listOf(
                "failed to apply loader entry broken-x (broken-x-plugin): nope",
                "AggregateError: loader entries failed to apply",
            ),
        )
        assertEquals(listOf("broken-x"), hits.map { it.id })
    }

    @Test
    fun `解析 failed to load 列表并在分号处截断`() {
        val hits = FailureAttribution.parse(
            listOf(
                "plugin(s) failed to load: alpha, @scope/beta; " +
                    "Cordis startup failed because these plugin(s) could not be resolved (x)",
            ),
        )
        assertEquals(listOf("alpha", "@scope/beta"), hits.map { it.name })
        assertEquals(listOf(null, null), hits.map { it.id })
    }

    @Test
    fun `解析 did not activate 的顶格点名行`() {
        // 条目行是**顶格**的，缩进行是栈帧——按缩进匹配会一个都认不出来。
        val text = """
            [loader] booting
            2 entries did not activate
            blamed-plugin: Error: Cannot find module 'left-pad'
                at Module._resolveFilename (node:internal/modules/cjs/loader:1)
            needs-service: pending (waiting for service: nonexistent)
            [loader] done
        """.trimIndent()
        val hits = FailureAttribution.parse(text)
        assertEquals(listOf("blamed-plugin", "needs-service"), hits.map { it.name })
        assertEquals("Error: Cannot find module 'left-pad'", hits[0].reason)
        assertEquals(FailureHit.Kind.DID_NOT_ACTIVATE, hits[1].kind)
    }

    @Test
    fun `id 命中与包名命中各自的去重键`() {
        // 归因层只能按 `id ?: name` 去重；"包名 → 条目 id"的对齐发生在写层之前
        // （resolveTargets 会查一次合成配置做映射），这里只钉住归因层的行为。
        val applyHit = FailureAttribution.parse(
            listOf("failed to apply loader entry broken-x (broken-x-plugin): nope"),
        ).single()
        assertEquals("broken-x", applyHit.dedupeKey)

        val namedHit = FailureAttribution.parse(
            listOf("2 entries did not activate", "broken-x-plugin: pending"),
        ).single()
        assertEquals("broken-x-plugin", namedHit.dedupeKey)
    }

    @Test
    fun `认不出失败时返回空列表`() {
        assertTrue(FailureAttribution.parse(listOf("[loader] ok", "[plugin] x ok")).isEmpty())
    }

    // -------------------------------------------------- 宿主基础设施判定

    @Test
    fun `受保护判定基于包名且判不出就保护`() {
        // 安装层里实际有什么：这条集合由 HostPackages 在设备上扫描得到。
        val installed = setOf("@deepseek-ai/dsh-settings", "cordis", "cosmokit")
        // loader 合成条目（cordis: 命名空间）
        assertTrue(HostInfrastructure.isProtected("include", "cordis:include", installed))
        // 官方命名空间（永不陈旧的前缀规则）
        assertTrue(HostInfrastructure.isProtected("hmr", "@deepseek-ai/cordis-plugin-hmr", installed))
        // 安装层里实际存在、但名字不带官方前缀的包（如 cordis）——靠推导才保护得到
        assertTrue(HostInfrastructure.isProtected("cordis", "cordis", installed))
        // **判不出包名就不动手**：宁可少关一个，也不能误关官方条目
        assertTrue(HostInfrastructure.isProtected("whatever", null, installed))
        // 第三方插件（共享前缀字样也不受影响）
        assertFalse(HostInfrastructure.isProtected("pomodoro-timer", "@community/pomodoro-timer", installed))
        assertFalse(HostInfrastructure.isProtected("web-search-anysearch", "@anysearch/anysearch-dsh", installed))
    }

    @Test
    fun `第三方判据排除官方与我方资产`() {
        val installed = setOf("cordis")
        assertFalse(HostInfrastructure.isThirdParty("@deepseek-ai/dsh-base", installed))
        assertFalse(HostInfrastructure.isThirdParty("cordis", installed))
        assertFalse(HostInfrastructure.isThirdParty("@local/dsh-mobile-adapt", installed))
        assertTrue(HostInfrastructure.isThirdParty("@anysearch/anysearch-dsh", installed))
        assertFalse("判不出包名时不得当成第三方", HostInfrastructure.isThirdParty(null, installed))
    }

    // ------------------------------------------------------------ 状态

    @Test
    fun `隔离状态可落盘并读回`() {
        val store = GuardStore(tempFile("guard.json"))
        val record = IsolationRecord(
            id = "some-broken",
            name = "some-broken-plugin",
            reason = "Cannot find module 'x'",
            isolatedAtMs = 1_700_000_000_000L,
        )
        val saved = store.save(
            GuardState(
                safetyMode = true,
                absoluteMode = false,
                isolated = listOf(record),
                roundsUsed = 1,
                bootStartedAtMs = 42L,
            ),
        )
        assertTrue("状态必须落盘成功", saved)
        val loaded = store.load()
        assertTrue(store.loadOk())
        assertEquals(listOf(record), loaded.isolated)
        assertEquals(1, loaded.roundsUsed)
        assertEquals(42L, loaded.bootStartedAtMs)
    }

    @Test
    fun `状态文件损坏时回退到默认值并标记不可信`() {
        val file = tempFile("broken.json")
        file.writeText("{ not json", Charsets.UTF_8)
        val store = GuardStore(file)
        val state = store.load()
        assertTrue(state.safetyMode)
        assertTrue(state.isolated.isEmpty())
        // 不可信时**拒绝写入**：用空状态覆盖会清空隔离清单。
        assertFalse(store.loadOk())
        val mutation = store.mutate { it.copy(roundsUsed = 5) }
        assertFalse(mutation.saved)
        assertEquals("{ not json", file.readText(Charsets.UTF_8))
    }

    @Test
    fun `轮次读改写不丢更新`() {
        val store = GuardStore(tempFile("guard.json"))
        store.save(GuardState(roundsUsed = 1))
        store.mutate { it.copy(roundsUsed = it.roundsUsed + 1) }
        assertEquals(2, store.load().roundsUsed)
    }

    // -------------------------------------------------- 绝对安全模式

    @Test
    fun `解析合成配置并挑出第三方条目`() {
        val parsed = safeMode().parseComposedEntries(
            listOf(
                "# == @deepseek-ai/dsh-base",
                "- id: timer",
                "  name: '@deepseek-ai/cordis-plugin-timer'",
                "- id: web-search-anysearch",
                "  name: '@anysearch/anysearch-dsh'",
                "- id: '@local/dsh-mobile-adapt'",
                "  name: '@local/dsh-mobile-adapt'",
                "- insert:",
                "    - id: pilot-mcp",
                "      name: '@deepseek-ai/dsh-mcp-client'",
            ),
        )
        assertEquals(4, parsed.entries.size)
        assertTrue(parsed.unnamedIds.isEmpty())
        assertEquals(listOf("web-search-anysearch"), safeMode().thirdPartyIds(parsed.entries))
    }

    @Test
    fun `config 块里的模型 id 不会被当成插件条目`() {
        // 真机上踩过：`llm-pi-ai` 的模型清单是 `- id: <模型名>`（含冒号、缩进很深），
        // 按任意缩进匹配会把它们当成插件条目，进而让整次操作因"id 不可写"失败。
        val parsed = safeMode().parseComposedEntries(
            listOf(
                "- id: llm-pi-ai",
                "  name: '@deepseek-ai/dsh-llm-pi-ai'",
                "  config:",
                "    providers:",
                "      openrouter:",
                "        models:",
                "          - id: inclusionai/ling-3.0-flash-fin:free",
                "            name: Ling 3.0 Flash Fin (free)",
                "          - id: mimo-v2.5:free",
                "            name: mimo-v2.5:free",
                "- id: web-search-anysearch",
                "  name: '@anysearch/anysearch-dsh'",
            ),
        )
        assertEquals(listOf("llm-pi-ai", "web-search-anysearch"), parsed.entries.map { it.id })
        assertTrue(parsed.unnamedIds.isEmpty())
    }

    @Test
    fun `config 块里的 name 不会被当成包名`() {
        val parsed = safeMode().parseComposedEntries(
            listOf(
                "- id: foo",
                "  config:",
                "    name: phone",
            ),
        )
        // `name:` 与 `- id:` 不同级 → 不当包名，且要报成未命名条目。
        assertTrue(parsed.entries.isEmpty())
        assertEquals(listOf("foo"), parsed.unnamedIds)
    }

    @Test
    fun `写入停用行后层里能看到，关闭时整层删掉`() {
        val layer = PluginLayer(tempFile("absolute-overlay.yml"))
        val safe = safeMode(layer = layer)
        val applied = safe.apply(listOf("web-search-anysearch"))
        assertTrue(applied is com.dshbox.app.common.AppResult.Success)
        assertEquals(setOf("web-search-anysearch"), layer.disabledIds())
        // 关闭 = 删文件（这一层由绝对安全模式独占），不是逐行回滚。
        assertTrue(safe.clear() is com.dshbox.app.common.AppResult.Success)
        assertFalse(layer.exists())
        assertTrue(layer.disabledIds().isEmpty())
    }

    @Test
    fun `写绝对层不会碰到插件管理层里的停用行`() {
        // 历史故障的回归测试：守卫把坏插件隔离（写插件管理层的 `disabled: true`），
        // 随后关闭绝对安全模式，旧实现按过期快照把那一行翻回 `false`——
        // 界面显示"已隔离"，DSH 却每次启动都加载那个崩溃插件起不来。
        val guardLayer = PluginLayer(tempFile("plugin-overlay.yml"))
        val absoluteLayer = PluginLayer(tempFile("absolute-overlay.yml"))
        assertTrue(guardLayer.setDisabled("bad-b", true))

        val safe = safeMode(layer = absoluteLayer)
        val applied = safe.apply(listOf("bad-b", "dsh-univer-office"))
        assertTrue(applied is com.dshbox.app.common.AppResult.Success)
        assertTrue(safe.clear() is com.dshbox.app.common.AppResult.Success)

        // 守卫的隔离行原封不动。
        assertEquals(setOf("bad-b"), guardLayer.disabledIds())
        assertFalse(absoluteLayer.exists())
    }

    @Test
    fun `绝对层为空时不留下空文件`() {
        val layer = PluginLayer(tempFile("absolute-overlay.yml"))
        val safe = safeMode(layer = layer)
        assertTrue(safe.apply(emptyList()) is com.dshbox.app.common.AppResult.Success)
        assertFalse(layer.exists())
    }

    @Test
    fun `非法的 dump-config 输出不会写出任何东西`() {
        val layer = PluginLayer(tempFile("overlay.yml"))
        val safe = safeMode(layer = layer)
        val parsed = safe.parseComposedEntries(listOf("garbage", "no entries here"))
        assertTrue(parsed.entries.isEmpty())
        assertTrue(safe.apply(emptyList()) is com.dshbox.app.common.AppResult.Success)
        assertFalse(layer.exists())
    }

    @Test
    fun `独占层损坏时备份后重置再写入`() {
        // 这一层只归绝对安全模式所有，别人不会写它。因此内容读不懂时**不能**像共享层
        // 那样"拒绝写入"——那会让绝对安全模式因为一个坏文件彻底失效，而它正是
        // "起不来时的最后一条路"。做法是先备份原文件（留证据）再重置。
        val file = tempFile("absolute-overlay.yml")
        file.writeText("[]\n- id: whatever\n", Charsets.UTF_8) // 两个顶层节点 = 解析不完整
        val layer = PluginLayer(file)
        val safe = safeMode(layer = layer)
        val applied = safe.apply(listOf("x"))
        assertTrue(applied is com.dshbox.app.common.AppResult.Success)
        assertEquals(setOf("x"), layer.disabledIds())
        val backup = file.parentFile!!.listFiles()!!.firstOrNull {
            it.name.startsWith("absolute-overlay.yml.broken-")
        }
        assertTrue("原内容必须留备份", backup != null)
        assertEquals("[]\n- id: whatever\n", backup!!.readText(Charsets.UTF_8))
    }
}
