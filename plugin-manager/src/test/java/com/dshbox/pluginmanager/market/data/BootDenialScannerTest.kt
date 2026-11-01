package com.dshbox.pluginmanager.market.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主启动日志「拒绝加载」行的扫描。
 *
 * 三种行的原文形态取自 DSH 0.2.0-rc.2 宿主源码（dsh-app-boot）：
 * `dsh: disabling profile plugin row "x": …` / `dsh: disabling profile plugin y: …` /
 * `dsh: skipping profile bundle "z": …`。
 */
class BootDenialScannerTest {

    @Test
    fun `停用行按 id 与 name 分别归集`() {
        val text = """
            dsh: warning: something else
            dsh: disabling profile plugin row "some-entry": Plugin p@1.0 is incompatible with dsh 0.2.0-rc.2
            dsh: disabling profile plugin other-pkg: peerDependencies mismatch
        """.trimIndent()
        val denials = BootDenialScanner.scan(text)
        assertEquals(1, denials.deniedRowIds.size)
        assertTrue(denials.deniedRowIds.containsKey("some-entry"))
        assertEquals(1, denials.deniedNames.size)
        assertTrue(denials.deniedNames.containsKey("other-pkg"))
    }

    @Test
    fun `跳过行按包名归集`() {
        val text = "dsh: skipping profile bundle \"@local/dsh-mobile-adapt\": manifest unreadable"
        val denials = BootDenialScanner.scan(text)
        assertEquals(
            "manifest unreadable",
            denials.skippedPackages["@local/dsh-mobile-adapt"],
        )
    }

    @Test
    fun `无关日志不产生命中`() {
        val denials = BootDenialScanner.scan("dsh: server listening on 127.0.0.1:3080\nnode v22.0.0\n")
        assertTrue(denials.isEmpty)
    }

    @Test
    fun `后写覆盖先写保留最近一次处置`() {
        val text = """
            dsh: disabling profile plugin row "a": old reason
            dsh: disabling profile plugin row "a": new reason
        """.trimIndent()
        assertEquals("new reason", BootDenialScanner.scan(text).deniedRowIds["a"])
    }

    @Test
    fun `含冒号的条目 id 按引号成对提取`() {
        // 合成条目的 id 可以是 `cordis:xxx` 形态：[^":]+ 会把它整条漏掉。
        val text = "dsh: disabling profile plugin row \"cordis:web-cache\": peer dsh ^2 not satisfied"
        val denials = BootDenialScanner.scan(text)
        assertEquals("peer dsh ^2 not satisfied", denials.deniedRowIds["cordis:web-cache"])
        assertTrue(denials.deniedNames.isEmpty())
    }

    @Test
    fun `只取最近一次启动分段_旧启动的拒绝不外溢`() {
        // 启动 N 拒绝了 a；启动 N+1（新标记之后）静默通过——扫描必须看不到旧行，
        // 否则已修复的插件会被永久显示为"宿主拒绝加载"。
        val text = """
            ===== DSH boot 2026-10-01 08:00:00 =====
            dsh: disabling profile plugin row "a": peer mismatch
            ===== DSH boot 2026-10-02 09:00:00 =====
            dsh: server listening on 127.0.0.1:3080
        """.trimIndent()
        val denials = BootDenialScanner.scan(text)
        assertTrue(denials.isEmpty)
        // 无标记的旧格式日志退回全文扫描（能力不丢）。
        assertEquals(1, BootDenialScanner.scan("dsh: disabling profile plugin row \"a\": x").deniedRowIds.size)
    }
}
