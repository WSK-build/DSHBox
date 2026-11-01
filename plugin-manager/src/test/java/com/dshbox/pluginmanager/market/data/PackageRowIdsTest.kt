package com.dshbox.pluginmanager.market.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 包名 → 层条目 id 的解析（移植上游 dshmarket `rowIdsForPackage`）。
 *
 * 这些用例固定的是实机/本机都验证过的三条事实：
 * 1. 条目 id 与包名可以不同（`dshmarket` → `dsh-market`）；
 * 2. 只认这个包**自己 insert 的行**，改别人的行不算；
 * 3. 解析不出来就返回空，调用方据此拒绝——绝不退回"用包名当 id"。
 */
class PackageRowIdsTest {

    private fun tempDir(): File = Files.createTempDirectory("rowids").toFile()

    private fun pkg(dir: File, manifest: String? = null, patch: String? = null): File {
        val root = File(dir, "the-package").apply { mkdirs() }
        manifest?.let { File(root, "package.json").writeText(it, Charsets.UTF_8) }
        patch?.let { File(root, "cordis.patch.yml").writeText(it, Charsets.UTF_8) }
        return root
    }

    @Test
    fun `声明在 dsh_bundle_patch 里的插入行就是它的条目 id`() {
        // dshmarket 的真实形态：包名 dshmarket，条目 id dsh-market。
        val dir = tempDir()
        val root = pkg(
            dir,
            manifest = """{ "name": "dshmarket", "version": "1.58.0",
                             "dsh": { "bundle": { "patch": "./cordis.patch.yml" } } }""",
            patch = "- insert:\n    - id: dsh-market\n      name: 'dshmarket'\n",
        )
        assertEquals(
            listOf("dsh-market"),
            PackageRowIds.forPackage(root, PackageRowIds.declaredPatchFiles(File(root, "package.json").readText())),
        )
    }

    @Test
    fun `约定位置 cordis_patch_yml 没有声明也要读`() {
        val dir = tempDir()
        val root = pkg(
            dir,
            manifest = """{ "name": "dsh-drop-caret" }""",
            patch = "- insert:\n    - id: drop-caret\n      name: 'dsh-drop-caret'\n",
        )
        assertEquals(listOf("drop-caret"), PackageRowIds.forPackage(root, emptyList()))
    }

    @Test
    fun `不把邻居的行算成自己的`() {
        // bundle patch 也会配置别的插件（例如给官方 attachment-local 加配置）。
        // 把 disabled: true 写到那种行上会把附件和模型一起停掉。
        val dir = tempDir()
        val root = pkg(
            dir,
            patch = """
                - insert:
                    - id: mine
                      name: 'the-package'
                - id: attachment-local
                  config:
                    watch: true
                - id: another-neighbour
                  disabled: true
            """.trimIndent() + "\n",
        )
        assertEquals(listOf("mine"), PackageRowIds.forPackage(root, emptyList()))
    }

    @Test
    fun `插入块在下一个顶层条目处结束`() {
        val dir = tempDir()
        val root = pkg(
            dir,
            patch = """
                - insert:
                    - id: first
                      name: 'the-package'
                - id: neighbour
                  config:
                    nested: 1
                - insert:
                    - id: second
                      name: 'the-package'
            """.trimIndent() + "\n",
        )
        assertEquals(listOf("first", "second"), PackageRowIds.forPackage(root, emptyList()))
    }

    @Test
    fun `内联 insert 也能取到 id`() {
        val dir = tempDir()
        val root = pkg(dir, patch = "- insert: [ { id: inline-one, name: 'the-package' } ]\n")
        assertEquals(listOf("inline-one"), PackageRowIds.forPackage(root, emptyList()))
    }

    @Test
    fun `没有补丁文件时返回空而不是拿包名兜底`() {
        val dir = tempDir()
        val root = pkg(dir, manifest = """{ "name": "client-only-plugin" }""")
        assertTrue(PackageRowIds.forPackage(root, emptyList()).isEmpty())
    }

    @Test
    fun `只有 yml 形态的字符串声明才当补丁路径`() {
        assertEquals(
            listOf("./cordis.patch.yml"),
            PackageRowIds.declaredPatchFiles("""{ "dsh": { "bundle": "./cordis.patch.yml" } }"""),
        )
        // 入口形态的字符串（例如 ./dist/bundle.js）不是补丁，不能拿来当路径解析。
        assertTrue(
            PackageRowIds.declaredPatchFiles("""{ "dsh": { "bundle": "./dist/bundle.js" } }""").isEmpty(),
        )
        assertTrue(
            PackageRowIds.declaredPatchFiles("""{ "dsh": { "client": "x" } }""").isEmpty(),
        )
    }

    @Test
    fun `声明为数组的补丁路径全部生效`() {
        // 宿主 0.1.7 起 dsh.bundle.patch 允许字符串数组。
        assertEquals(
            listOf("./a.patch.yml", "b.yml"),
            PackageRowIds.declaredPatchFiles(
                """{ "dsh": { "bundle": { "patch": ["./a.patch.yml", "b.yml"] } } }""",
            ),
        )
    }

    @Test
    fun `邻居行被单独识别为外来行`() {
        val dir = tempDir()
        val root = pkg(
            dir,
            patch = """
                - insert:
                    - id: mine
                      name: 'the-package'
                - id: another-neighbour
                  disabled: true
            """.trimIndent() + "\n",
        )
        assertEquals(
            listOf("mine"),
            PackageRowIds.forPackage(root, emptyList()),
        )
        assertEquals(
            listOf("another-neighbour"),
            PackageRowIds.foreignRowIds(File(root, "cordis.patch.yml").readText()),
        )
    }

    @Test
    fun `补丁目标名收集含插入行的 name`() {
        val dir = tempDir()
        val root = pkg(
            dir,
            patch = "- insert:\n    - id: mine\n      name: 'the-package'\n",
        )
        // name: 值是行声明的提供者；载体判定按它找挂载目标（自身名由调用方过滤）。
        assertEquals(
            listOf("the-package"),
            PackageRowIds.patchTargetNames(File(root, "cordis.patch.yml").readText()),
        )
    }

    @Test
    fun `带引号的作用域条目 id 必须读得出来`() {
        // 作用域包名的 id 不满足裸标量形态，只能带引号写。读取侧曾按裸标量校验，
        // 结果把它读丢 → 残留行审计判它无人认领 → 一键清理删掉真实停用的行。
        val dir = tempDir()
        val root = pkg(
            dir,
            patch = "- insert:\n    - id: '@scope/pkg'\n      name: 'the-package'\n",
        )
        assertEquals(listOf("@scope/pkg"), PackageRowIds.forPackage(root, emptyList()))
    }

    @Test
    fun `子条目 id 与内联带引号 id 也要读得出来`() {
        // 两个子场景各用独立目录：共用会互相覆盖补丁文件，断言顺序一换就失效。
        val blockRoot = pkg(
            tempDir(),
            patch = "- insert:\n    - id: '@scope/pkg:side-panel'\n      name: 'the-package'\n",
        )
        assertEquals(listOf("@scope/pkg:side-panel"), PackageRowIds.forPackage(blockRoot, emptyList()))

        val singleQuoted = pkg(
            tempDir(),
            patch = "- insert: [ { id: '@scope/other', name: 'the-package' } ]\n",
        )
        assertEquals(listOf("@scope/other"), PackageRowIds.forPackage(singleQuoted, emptyList()))

        val doubleQuoted = pkg(
            tempDir(),
            patch = "- insert: [ { id: \"@scope/third\", name: 'the-package' } ]\n",
        )
        assertEquals(listOf("@scope/third"), PackageRowIds.forPackage(doubleQuoted, emptyList()))
    }
}
