package com.dshbox.pluginmanager.layer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 层文件读写的回归测试。
 *
 * 这些用例守的是几条**真机上验证过、绝不能放宽**的约束：单顶层序列、
 * 插入块原样保留、解析不完整时拒绝写入。改这个类之前先看它们。
 */
class PluginLayerTest {

    private fun tempLayer(): Pair<PluginLayer, File> {
        val dir = Files.createTempDirectory("layer-test").toFile()
        val file = File(dir, "plugin-overlay.yml")
        return PluginLayer(file) to file
    }

    @Test
    fun `空层渲染为方括号`() {
        val (layer, _) = tempLayer()
        assertEquals("[]\n", layer.render(emptyList(), emptyList()))
    }

    @Test
    fun `停用条目渲染为单个顶层序列`() {
        val (layer, _) = tempLayer()
        val text = layer.render(
            emptyList(),
            listOf(LayerPatchEntry(id = "some-plugin", disabled = true)),
        )
        assertEquals("- id: some-plugin\n  disabled: true\n", text)
        // 关键：全文只有一个顶层序列，不能出现 `[]` 之后再跟条目的写法。
        assertFalse(text.contains("[]"))
    }

    @Test
    fun `写入后再读出得到同样的条目`() {
        val (layer, _) = tempLayer()
        assertTrue(layer.setDisabled("alpha", true))
        assertTrue(layer.setDisabled("beta", true))
        val disabled = layer.disabledIds()
        assertEquals(setOf("alpha", "beta"), disabled)
    }

    @Test
    fun `解除停用写回 false 而不是删行`() {
        val (layer, file) = tempLayer()
        layer.setDisabled("alpha", true)
        layer.setDisabled("alpha", false)
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.contains("- id: alpha"))
        assertTrue(text.contains("disabled: false"))
        assertTrue(layer.disabledIds().isEmpty())
    }

    @Test
    fun `插入块原样保留`() {
        val (layer, file) = tempLayer()
        val insert = LayerInsertEntry(
            id = "phone-control",
            rawBlock = listOf(
                "    - id: phone-control",
                "      name: '@deepseek-ai/dsh-mcp-client'",
                "      config:",
                "        serverName: phone",
            ),
        )
        assertTrue(layer.write(LayerDocument(listOf(insert), emptyList(), true, "")))
        // 再写一个停用条目，插入块必须一字不差地还在。
        assertTrue(layer.setDisabled("alpha", true))
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.contains("    - id: phone-control"))
        assertTrue(text.contains("        serverName: phone"))
        assertEquals("phone-control", layer.read().inserts.single().id)
    }

    @Test
    fun `解析不完整时拒绝写入`() {
        val (layer, file) = tempLayer()
        // 顶层出现读不懂的结构（缩进异常的映射），必须判定为不可解析。
        file.parentFile?.mkdirs()
        file.writeText("not-a-sequence: true\n", Charsets.UTF_8)
        assertFalse(layer.read().parseOk)
        assertFalse(layer.setDisabled("alpha", true))
        // 原内容一字未改。
        assertEquals("not-a-sequence: true\n", file.readText(Charsets.UTF_8))
    }

    @Test
    fun `非法 id 拒绝写入`() {
        val (layer, _) = tempLayer()
        assertFalse(layer.setDisabled("bad id", true))
        assertFalse(layer.setDisabled("'quoted'", true))
        assertFalse(PluginLayer.isValidId("a b"))
        assertTrue(PluginLayer.isValidId("@scope/pkg"))
    }

    @Test
    fun `id 去引号还原`() {
        assertEquals("@local/dsh-mobile-adapt", PluginLayer.unquote("'@local/dsh-mobile-adapt'"))
        assertEquals("plain", PluginLayer.unquote("plain"))
        assertEquals("with space", PluginLayer.unquote("\"with space\""))
    }

    @Test
    fun `作用域包名的 id 自动加引号且能读回`() {
        val (layer, file) = tempLayer()
        assertTrue(layer.setDisabled("@local/dsh-mobile-adapt", true))
        val text = file.readText(Charsets.UTF_8)
        // DSH 自己的合成输出里这类 id 也是带引号的形态。
        assertTrue(text.contains("- id: '@local/dsh-mobile-adapt'"))
        assertEquals(setOf("@local/dsh-mobile-adapt"), layer.disabledIds())
    }

    @Test
    fun `插入块之后的停用行不会被吞掉`() {
        val (layer, _) = tempLayer()
        val insert = LayerInsertEntry(
            id = "phone-control",
            rawBlock = listOf(
                "    - id: phone-control",
                "      name: '@deepseek-ai/dsh-mcp-client'",
            ),
        )
        assertTrue(layer.write(LayerDocument(listOf(insert), emptyList(), true, "")))
        // 紧跟插入块写一条停用行：解析必须把这一行按顶层处理，且不判为损坏。
        assertTrue(layer.setDisabled("web-search-anysearch", true))
        val doc = layer.read()
        assertTrue("插入块后的顶层行不应导致解析失败", doc.parseOk)
        assertEquals(listOf("phone-control"), doc.inserts.map { it.id })
        assertEquals(setOf("web-search-anysearch"), layer.disabledIds())
    }

    @Test
    fun `用户层的行扫描能认出停用与插入`() {
        val text = """
            - insert:
                - id: pilot-mcp
                  name: '@deepseek-ai/dsh-mcp-client'
            - id: hmr
              disabled: true
            - id: llm
              disabled: false
        """.trimIndent()
        val scan = PatchDialect.scan(text)
        assertEquals(listOf("pilot-mcp"), scan.inserted)
        assertEquals(listOf("hmr"), scan.disabled)
        assertEquals(listOf("llm"), scan.forcedEnabled)
        assertEquals(listOf("hmr", "llm"), scan.ids)
    }

    @Test
    fun `并发写入不丢行`() {
        val (layer, _) = tempLayer()
        // 三个写者（守卫 / 绝对安全模式 / 市场启停）可能同时动手；
        // 读-改-写必须在同一临界区里，否则后写者会覆盖前写者刚落的行。
        val threads = (1..6).map { index ->
            Thread { layer.setDisabled("plugin-$index", true) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals((1..6).map { "plugin-$it" }.toSet(), layer.disabledIds())
    }

    @Test
    fun `读失败判定为不可解析并拒绝写入`() {
        val dir = Files.createTempDirectory("layer-dir").toFile()
        // 用一个目录冒充层文件：exists() 为真、readText 抛异常。
        val fake = File(dir, "overlay.yml")
        assertTrue(fake.mkdirs())
        val layer = PluginLayer(fake)
        val doc = layer.read()
        assertFalse("读失败必须判定为不可解析", doc.parseOk)
        assertNotNull(doc.readError)
        // 关键：此时绝不能以"空层"为基础重写（那会抹掉全部停用行）。
        assertFalse(layer.setDisabled("x", true))
    }

    @Test
    fun `name 里的单引号被转义且能读回`() {
        val (layer, file) = tempLayer()
        val insert = LayerInsertEntry(id = "pc", rawBlock = listOf("    - id: pc"))
        assertTrue(layer.write(LayerDocument(listOf(insert), emptyList(), true, "")))
        val doc = layer.read()
        val withName = doc.copy(
            patches = listOf(LayerPatchEntry(id = "quoted", name = "it's ok", disabled = true)),
        )
        assertTrue(layer.write(withName))
        // 单引号标量里的 ' 必须写成 ''，否则整份文档非法。
        assertTrue(file.readText(Charsets.UTF_8).contains("name: 'it''s ok'"))
        assertEquals("it's ok", layer.read().patches.first { it.id == "quoted" }.name)
    }

    @Test
    fun `内联 insert 写法整体保留且不阻塞写入`() {
        val (layer, file) = tempLayer()
        file.parentFile?.mkdirs()
        file.writeText("- insert: [ { id: a, name: 'pkg/one' } ]\n", Charsets.UTF_8)
        assertTrue("内联写法不应被判定为损坏", layer.read().parseOk)
        assertTrue(layer.setDisabled("some-plugin", true))
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.contains("- insert: [ { id: a, name: 'pkg/one' } ]"))
        assertEquals(setOf("some-plugin"), layer.disabledIds())
    }

    @Test
    fun `空层带尾随空白仍按空层处理`() {
        val (layer, file) = tempLayer()
        file.parentFile?.mkdirs()
        file.writeText("[]   \n", Charsets.UTF_8)
        assertTrue(layer.read().parseOk)
        assertTrue(layer.setDisabled("x", true))
        assertEquals(setOf("x"), layer.disabledIds())
    }

    @Test
    fun `损坏层可备份并重置`() {
        val (layer, file) = tempLayer()
        file.parentFile?.mkdirs()
        file.writeText("[]\n- id: x\n", Charsets.UTF_8)
        assertFalse(layer.read().parseOk)
        assertTrue(layer.backupAndReset())
        assertTrue(layer.read().parseOk)
        assertEquals(PluginLayer.EMPTY, file.readText(Charsets.UTF_8))
        // 原内容留在备份里，便于事后查看。
        val backups = file.parentFile!!.listFiles { f -> f.name.contains(".broken-") } ?: emptyArray()
        assertEquals(1, backups.size)
        assertEquals("[]\n- id: x\n", backups[0].readText(Charsets.UTF_8))
    }
}
