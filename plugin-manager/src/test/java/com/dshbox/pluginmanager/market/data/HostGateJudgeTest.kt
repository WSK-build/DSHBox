package com.dshbox.pluginmanager.market.data

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 严格兼容门禁判定。
 *
 * 这一层的唯一职责是**预测宿主会不会拒绝**，所以每条用例都写成「宿主在同样输入下
 * 会不会拒绝」的形式。它与浏览期提示（[HostCompatJudge]）刻意分开：后者放宽了两处，
 * 拿它预测门禁会把随包的 `^0.1.0` × `0.2.0-rc.2` 判反。
 */
class HostGateJudgeTest {

    /** 拼一份最小清单。[engines] 是**已带引号的 JSON 值**（如 `"^0.1.0"`），便于测试非字符串形态。 */
    private fun manifest(engines: String? = null, peers: Map<String, String>? = null): String {
        val parts = mutableListOf<String>()
        if (engines != null) parts += "\"engines\":{\"dsh\":$engines}"
        if (peers != null) {
            val body = peers.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
            parts += "\"peerDependencies\":{$body}"
        }
        return "{" + parts.joinToString(",") + "}"
    }

    private fun judge(
        text: String?,
        host: String? = HOST_020_RC2,
        version: String? = "1.0.0",
        exemptions: Map<String, List<String>> = emptyMap(),
        mode: GateMode? = GateMode.ENGINES_FIRST,
    ) = HostGateJudge.judge(
        manifestText = text,
        packageName = "demo",
        packageVersion = version,
        hostVersion = host,
        exemptions = exemptions,
        mode = mode,
    )

    companion object {
        const val HOST_020_RC2 = "0.2.0-rc.2"
    }

    // ───────────────── 上界与预发布（这条是整层存在的原因） ─────────────────

    @Test
    fun `兼容区间不会让预发布宿主滑过隐式上界`() {
        // ^0.1.0 的上界是 0.2.0；0.2.0-rc.2 低于 0.2.0，若不按 <0.2.0-0 判就会误判为满足。
        val verdict = judge(manifest(engines = "\"^0.1.0\""))
        assertTrue("宿主实测会拒绝这个组合", verdict.incompatible)
        assertEquals("engines.dsh: ^0.1.0", verdict.requirement)
        assertEquals(GateVerdict.Basis.MANIFEST, verdict.basis)
    }

    @Test
    fun `区间内且高于基线的版本仍然满足`() {
        assertFalse(judge(manifest(engines = "\"^0.1.0\""), host = "0.1.5-rc.1").incompatible)
        assertFalse(judge(manifest(engines = "\"^0.2.0\""), host = "0.2.1-rc.2").incompatible)
    }

    @Test
    fun `明显高于显式上界也拒绝`() {
        assertTrue(judge(manifest(engines = "\"^0.1.0\""), host = "0.3.0").incompatible)
        assertTrue(judge(manifest(engines = "\"<=0.2.0\""), host = "0.3.0").incompatible)
        // 显式写的 `<0.2.0` 按 semver **包含** 0.2.0-rc.2（预发布低于正式版）——
        // 这与 `^`/`~` 推出来的上界不同，后者要按 `<ceil-0` 才挡得住。
        assertFalse(judge(manifest(engines = "\"<0.2.0\""), host = HOST_020_RC2).incompatible)
    }

    // ───────────────── engines 独占（0.2.1 口径） ─────────────────

    @Test
    fun `engines 满足时忽略不满足的 peers`() {
        // 0.2.1 的规则：engines.dsh 存在就它说了算。
        // 注意 engines 用 `^0.2.0-rc.1`：`^0.2.0` 的下界是 0.2.0，而 0.2.0-rc.2 低于它，
        // 那是「不满足」而不是「满足」——预发布只在比较子本身允许时才落进区间。
        val text = manifest(
            engines = "\"^0.2.0-rc.1\"",
            peers = mapOf("@deepseek-ai/dsh-tools" to "^0.1.0"),
        )
        val verdict = judge(text)
        assertFalse("engines 独占，peers 不参与", verdict.incompatible)
    }

    @Test
    fun `没有 engines 时逐个检查 dsh peers`() {
        val text = manifest(peers = mapOf("@deepseek-ai/dsh-tools" to "^0.1.0"))
        val verdict = judge(text)
        assertTrue(verdict.incompatible)
        assertEquals("@deepseek-ai/dsh-tools: ^0.1.0", verdict.requirement)
    }

    @Test
    fun `非 dsh 命名空间的 peer 不参与门禁`() {
        val text = manifest(peers = mapOf("lodash" to "^99.0.0"))
        val verdict = judge(text)
        assertFalse(verdict.incompatible)
        assertEquals(GateVerdict.Basis.UNDECLARED, verdict.basis)
    }

    // ───────────────── PEERS_ONLY（0.2.0 口径） ─────────────────

    @Test
    fun `peers only 模式下 engines 不参与判定`() {
        // 0.2.0 的 app-boot 里 engines 出现 0 次，所以这个声明在它下面不拦人。
        val verdict = judge(manifest(engines = "\"^0.1.0\""), mode = GateMode.PEERS_ONLY)
        assertFalse("0.2.0 不看 engines", verdict.incompatible)
        assertEquals(GateVerdict.Basis.UNDECLARED, verdict.basis)
    }

    @Test
    fun `peers only 模式下 peers 仍然检查`() {
        val text = manifest(
            engines = "\"^0.2.0\"",
            peers = mapOf("@deepseek-ai/dsh-tools" to "^0.1.0"),
        )
        assertTrue(judge(text, mode = GateMode.PEERS_ONLY).incompatible)
    }

    // ───────────────── 空区间 / workspace 协议 ─────────────────

    @Test
    fun `engines 空区间按不满足处理`() {
        val verdict = judge(manifest(engines = "\"\""))
        assertTrue(verdict.incompatible)
        assertTrue(verdict.requirement!!.contains("<empty>"))
    }

    @Test
    fun `peer 的空区间按不满足处理`() {
        val text = manifest(peers = mapOf("@deepseek-ai/dsh-tools" to ""))
        assertTrue(judge(text).incompatible)
    }

    @Test
    fun `peer 的 workspace 协议视作当前运行时`() {
        for (protocol in listOf("workspace:^", "workspace:~", "workspace:*")) {
            val text = manifest(peers = mapOf("@deepseek-ai/dsh-tools" to protocol))
            assertFalse("$protocol 应视为满足", judge(text).incompatible)
        }
    }

    @Test
    fun `engines 上的 workspace 协议不算满足`() {
        // 上游特别注明：workspace: 只对 peer 生效，engines 上的空/非法区间就是不满足。
        val verdict = judge(manifest(engines = "\"workspace:^\""))
        assertTrue(verdict.incompatible)
    }

    // ───────────────── 豁免 ─────────────────

    @Test
    fun `豁免命中则不判不兼容`() {
        val text = manifest(engines = "\"^0.1.0\"")
        val verdict = judge(
            text,
            exemptions = mapOf("demo@1.0.0" to listOf(HOST_020_RC2)),
        )
        assertFalse(verdict.incompatible)
        assertEquals(GateVerdict.Basis.EXEMPTED, verdict.basis)
    }

    @Test
    fun `豁免必须同时匹配包版本与运行时版本`() {
        val text = manifest(engines = "\"^0.1.0\"")
        // 版本不同
        assertTrue(
            judge(text, exemptions = mapOf("demo@2.0.0" to listOf(HOST_020_RC2))).incompatible,
        )
        // 运行时不同
        assertTrue(
            judge(text, exemptions = mapOf("demo@1.0.0" to listOf("0.1.0"))).incompatible,
        )
    }

    @Test
    fun `没有精确版本时无法匹配豁免`() {
        val text = manifest(engines = "\"^0.1.0\"")
        val verdict = judge(text, version = null, exemptions = mapOf("demo@1.0.0" to listOf(HOST_020_RC2)))
        assertTrue(verdict.incompatible)
    }

    // ───────────────── 不判的情形 ─────────────────

    @Test
    fun `清单读不出时不判`() {
        assertEquals(GateVerdict.Basis.UNAVAILABLE, judge(null).basis)
    }

    @Test
    fun `宿主版本读不出时不判`() {
        assertEquals(GateVerdict.Basis.UNAVAILABLE, judge(manifest(engines = "\"^0.1.0\""), host = null).basis)
        assertEquals(GateVerdict.Basis.UNAVAILABLE, judge(manifest(engines = "\"^0.1.0\""), host = "  ").basis)
    }

    @Test
    fun `清单不是合法 JSON 时不判`() {
        assertEquals(GateVerdict.Basis.UNAVAILABLE, judge("{ not json").basis)
    }

    @Test
    fun `engines dsh 不是字符串时不判`() {
        assertEquals(GateVerdict.Basis.UNAVAILABLE, judge(manifest(engines = "123")).basis)
    }

    @Test
    fun `engines 区间读不懂按不满足处理`() {
        // 上游是 `satisfied ? null : unmet`，读不懂也落到 unmet。
        assertTrue(judge(manifest(engines = "\"not-a-range!!\"")).incompatible)
    }

    @Test
    fun `peer 区间读不懂时跳过而不是判不兼容`() {
        // 上游只在明确 false 时才 unmet；null（读不懂）不拦。
        val text = manifest(peers = mapOf("@deepseek-ai/dsh-tools" to "not-a-range!!"))
        val verdict = judge(text)
        assertFalse(verdict.incompatible)
    }

    // ───────────────── 语义探针 ─────────────────

    @Test
    fun `产物含 engines 标记时按 engines 独占口径`() {
        assertEquals(GateMode.ENGINES_FIRST, HostGateJudge.modeFor("... \"engines.dsh\": range ..."))
    }

    @Test
    fun `产物不含标记时只看 peers`() {
        assertEquals(GateMode.PEERS_ONLY, HostGateJudge.modeFor("... peerDependencies ..."))
    }

    @Test
    fun `产物读不出时不猜口径`() {
        assertNull(HostGateJudge.modeFor(null))
    }

    @Test
    fun `口径读不出时判定落为未知，而不是不兼容`() {
        // 两个口径可能给出相反结论：猜错会拒掉可安装的插件。
        val verdict = judge(
            manifest(engines = "\"^0.1.0\""),
            mode = null,
        )
        assertFalse(verdict.incompatible)
        assertEquals(GateVerdict.Basis.UNAVAILABLE, verdict.basis)
    }

    // ───────────────── 口径探针的布局覆盖 ─────────────────

    private fun runtimeWith(entry: String?, text: String?): java.io.File {
        val root = Files.createTempDirectory("gate").toFile()
        if (entry != null && text != null) {
            val file = java.io.File(root, entry)
            file.parentFile?.mkdirs()
            file.writeText(text, Charsets.UTF_8)
        }
        return root
    }

    @Test
    fun `两种打包布局都能探到口径`() {
        // 依赖树在 DSH 层内。
        val nested = runtimeWith("dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js", "\"engines.dsh\": 1")
        assertEquals(GateMode.ENGINES_FIRST, HostGateJudge.detectMode(nested))
        // 依赖树直接在运行层根。
        val flat = runtimeWith("node_modules/@deepseek-ai/dsh-app-boot/lib/index.js", "peerDependencies")
        assertEquals(GateMode.PEERS_ONLY, HostGateJudge.detectMode(flat))
    }

    @Test
    fun `两种布局都读不到时返回 null，由装配点决定兜底`() {
        assertNull(HostGateJudge.detectMode(runtimeWith(null, null)))
    }

    // ───────────────── 豁免文件读取 ─────────────────

    @Test
    fun `读取豁免文件`() {
        val dir = Files.createTempDirectory("exemptions").toFile()
        val file = java.io.File(dir, "compatibility.json")
        file.writeText("""{"demo@1.0.0":["0.2.0-rc.2","0.2.1-rc.1"],"other@2.0.0":[]}""", Charsets.UTF_8)
        val exemptions = HostGateJudge.readExemptions(file)
        assertEquals(listOf("0.2.0-rc.2", "0.2.1-rc.1"), exemptions["demo@1.0.0"])
        assertFalse("空数组不构成豁免", exemptions.containsKey("other@2.0.0"))
    }

    @Test
    fun `豁免文件缺失或损坏时视为无豁免`() {
        assertTrue(HostGateJudge.readExemptions(null).isEmpty())
        assertTrue(HostGateJudge.readExemptions(java.io.File("/definitely/not/here.json")).isEmpty())
        val dir = Files.createTempDirectory("exemptions-bad").toFile()
        val bad = java.io.File(dir, "compatibility.json")
        bad.writeText("{ broken", Charsets.UTF_8)
        assertTrue(HostGateJudge.readExemptions(bad).isEmpty())
    }
}
