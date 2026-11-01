package com.dshbox.pluginmanager.market.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 停用覆盖层残留行审计的判定规则。
 *
 * 这条规则决定"一键清理"会删掉哪些行。**误判的代价是不对称的**：
 * - 把真残留判成认领 → 漏报，只是日志继续长，用户无感；
 * - 把真实生效的停用行判成残留 → **删掉它等于把用户刻意停用的插件复活**。
 *
 * 所以用例的重点全在"不该删的绝不能删"：已装包认领的行、宿主 loader 行、
 * 我方随包行，三类都必须稳稳保住。
 */
class OverlayAuditTest {

    /**
     * 便捷：所有包都**解析不出额外行 id**——认领只靠包名本身。
     *
     * 刻意不带参数：它表达的是"补丁文件里没有 insert 行"这一种情况，而不是
     * "只认这些名字"。[OverlayAudit.staleRows] 的认领集合永远包含传入的每一个
     * 包名，所以"只认某些名字"不是这个 fixture 能表达的事。
     */
    private fun noDeclaredRows(): (String) -> List<String> = { emptyList() }

    @Test
    fun `无人认领的行判为残留`() {
        val stale = OverlayAudit.staleRows(
            disabledRowIds = listOf("ghost-plugin"),
            installedNames = listOf("installed-plugin"),
            rowIdsOf = noDeclaredRows(),
        )
        assertEquals(listOf("ghost-plugin"), stale)
    }

    @Test
    fun `包名本身认领该行`() {
        val stale = OverlayAudit.staleRows(
            disabledRowIds = listOf("dshmarket"),
            installedNames = listOf("dshmarket"),
            rowIdsOf = noDeclaredRows(),
        )
        assertTrue("已装包名对应的行不是残留", stale.isEmpty())
    }

    @Test
    fun `包补丁文件里声明的条目 id 也算认领——包名不等于条目 id`() {
        // 行 id 允许带子路径（`@scope/pkg:sub-entry`），与包名不同；
        // 只看包名会把真实生效的停用行误判成残留。
        val stale = OverlayAudit.staleRows(
            disabledRowIds = listOf("dsh-plugin-x:side-panel"),
            installedNames = listOf("dsh-plugin-x"),
            rowIdsOf = { name -> if (name == "dsh-plugin-x") listOf("dsh-plugin-x:side-panel") else emptyList() },
        )
        assertTrue("包声明的条目 id 必须被认领", stale.isEmpty())
    }

    @Test
    fun `宿主 loader 合成条目豁免——不归市场清理`() {
        val stale = OverlayAudit.staleRows(
            disabledRowIds = listOf("cordis:group", "cordis:include"),
            installedNames = emptyList(),
            rowIdsOf = noDeclaredRows(),
        )
        assertTrue("loader 条目由宿主管理，不该被清理", stale.isEmpty())
    }

    @Test
    fun `我方随包资产豁免——由装配链管理`() {
        val stale = OverlayAudit.staleRows(
            disabledRowIds = listOf("@local/dsh-mobile-adapt", "@local/mobile-pilot"),
            installedNames = emptyList(),
            rowIdsOf = noDeclaredRows(),
        )
        assertTrue("@local/ 由装配链管理，市场不得清理", stale.isEmpty())
    }

    @Test
    fun `bundles 里的托管条目也参与认领`() {
        // 随包托管条目在 bundles 里但不在 dependencies 里；漏掉这一半输入，
        // 它们对应的停用行会被误判成残留。
        val stale = OverlayAudit.staleRows(
            disabledRowIds = listOf("managed-bundle"),
            installedNames = listOf("managed-bundle"),
            rowIdsOf = noDeclaredRows(),
        )
        assertTrue(stale.isEmpty())
    }

    @Test
    fun `混合场景只挑出真正无人认领的行`() {
        val stale = OverlayAudit.staleRows(
            disabledRowIds = listOf(
                "ghost-a",
                "dshmarket",
                "cordis:group",
                "@local/browser-use",
                "ghost-b",
            ),
            installedNames = listOf("dshmarket"),
            rowIdsOf = noDeclaredRows(),
        )
        assertEquals(listOf("ghost-a", "ghost-b"), stale)
    }

    @Test
    fun `保持原顺序，便于界面稳定展示`() {
        val stale = OverlayAudit.staleRows(
            disabledRowIds = listOf("z-ghost", "a-ghost", "m-ghost"),
            installedNames = emptyList(),
            rowIdsOf = noDeclaredRows(),
        )
        assertEquals(listOf("z-ghost", "a-ghost", "m-ghost"), stale)
    }

    @Test
    fun `空停用行集合返回空——没有可清理的`() {
        val stale = OverlayAudit.staleRows(
            disabledRowIds = emptyList(),
            installedNames = listOf("anything"),
            rowIdsOf = noDeclaredRows(),
        )
        assertTrue(stale.isEmpty())
    }

    @Test
    fun `空包名不参与认领，也不导致重复解析`() {
        val resolved = mutableListOf<String>()
        val stale = OverlayAudit.staleRows(
            disabledRowIds = listOf("real-row"),
            installedNames = listOf("", "   ", "real-row"),
            rowIdsOf = { name -> resolved += name; emptyList() },
        )
        assertTrue("空名不该把行认领走", stale.isEmpty())
        assertEquals("空名必须被跳过，不触发解析", listOf("real-row"), resolved)
    }

    @Test
    fun `同一名字重复出现只解析一次`() {
        val resolved = mutableListOf<String>()
        OverlayAudit.staleRows(
            disabledRowIds = listOf("row"),
            // dependencies 与 bundles 里同名是常态。
            installedNames = listOf("dup", "dup", "dup"),
            rowIdsOf = { name -> resolved += name; emptyList() },
        )
        assertEquals("重复名字不该重复读盘", listOf("dup"), resolved)
    }

    @Test
    fun `包补丁解析不出 id 时该包名本身仍认领——保守不漏删`() {
        // rowIdsForPackage 解析失败返回空列表；此时不能把该包的行判成残留。
        val stale = OverlayAudit.staleRows(
            disabledRowIds = listOf("broken-pkg"),
            installedNames = listOf("broken-pkg"),
            rowIdsOf = { emptyList() },
        )
        assertTrue("解析不出额外 id 不代表该包没有行", stale.isEmpty())
    }

    @Test
    fun `一个包声明的行 id 与另一个包同名时不会漏认领`() {
        // 认领集里同时装着包名与行 id：若用它判重，第二个包会被跳过，
        // 它自己的行 id 进不了认领集 → 真实生效的停用行被当成残留。
        val stale = OverlayAudit.staleRows(
            disabledRowIds = listOf("real-row"),
            installedNames = listOf("owner-a", "owner-b"),
            rowIdsOf = { name ->
                when (name) {
                    "owner-a" -> listOf("owner-b")
                    "owner-b" -> listOf("real-row")
                    else -> emptyList()
                }
            },
        )
        assertTrue("第二个包的行 id 必须被解析", stale.isEmpty())
    }

    @Test
    fun `走真实解析器时作用域 id 与子条目都不会被判成残留`() {
        // 端到端：解析器若按裸标量校验，`@scope/pkg` 会被读丢，这里必红。
        val dir = Files.createTempDirectory("overlayaudit").toFile()
        val pkg = File(dir, "the-package").apply { mkdirs() }
        File(pkg, "cordis.patch.yml").writeText(
            "- insert:\n    - id: '@scope/pkg'\n      name: 'the-package'\n",
            Charsets.UTF_8,
        )
        val stale = OverlayAudit.staleRows(
            disabledRowIds = listOf("@scope/pkg", "@scope/pkg:side-panel"),
            installedNames = listOf("the-package"),
            rowIdsOf = { name ->
                if (name == "the-package") PackageRowIds.forPackage(pkg, emptyList()) else emptyList()
            },
        )
        assertTrue("包声明的 id 与它的子条目都必须被认领", stale.isEmpty())
    }
}
