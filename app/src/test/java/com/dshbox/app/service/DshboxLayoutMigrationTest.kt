package com.dshbox.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [DshboxLayoutMigration] 的不变量。
 *
 * 这段代码只在升级时跑一次，却决定**用户状态能不能活下来**：三份层文件里装着"官方插件开关"
 * 与"哪些插件被停用"（后者是安全机制，丢了会把此前被隔离的坏插件重新加载）。
 * 因此这里锁的不是"搬没搬"，而是三条要命的边界：
 *
 *  - 新路径已存在时**绝不覆盖**（否则第二次启动会把已迁移的内容顶回旧版本）；
 *  - 旧目录里若有用户自己放的东西，**不许连带删掉**；
 *  - 暂存目录（无状态）才允许递归删。
 */
class DshboxLayoutMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun root(): File = tmp.newFolder("dshbox")

    private fun write(root: File, rel: String, text: String) {
        File(root, rel).apply {
            parentFile?.mkdirs()
            writeText(text)
        }
    }

    /** 迁移表必须与规划文档一致——路径写错一个字，用户状态就搬不过去。 */
    @Test
    fun moveTableMatchesThePlannedLayout() {
        assertEquals(
            listOf(
                "features/feature-overlay.yml" to
                    "dsh-official-plugin/dsh-official-plugin-overlay.yml",
                "plugin-manager/plugin-overlay.yml" to
                    "plugin-market/market-and-safe-mode-overlay.yml",
                "plugin-manager/absolute-overlay.yml" to "safe-mode/absolute-safe-mode.yml",
                "boot-log-previous.txt" to "safe-mode/boot-log-previous.txt",
            ),
            DshboxLayoutMigration.FILE_MOVES,
        )
        assertEquals(listOf("features", "plugin-manager"), DshboxLayoutMigration.LEGACY_DIRS)
        assertEquals(listOf("mobile-adapt", "mobile-pilot"), DshboxLayoutMigration.LEGACY_STAGE_DIRS)
    }

    @Test
    fun movesEveryLayerFileAndRemovesTheLegacyDirs() {
        val root = root()
        DshboxLayoutMigration.FILE_MOVES.forEach { (from, _) -> write(root, from, "内容:$from") }

        val report = DshboxLayoutMigration.run(root)

        assertEquals(DshboxLayoutMigration.FILE_MOVES.map { it.second }, report.moved)
        assertTrue(report.failed.isEmpty())
        // 内容一字不差地到了新位置（父目录按需创建）
        DshboxLayoutMigration.FILE_MOVES.forEach { (from, to) ->
            assertEquals("内容:$from", File(root, to).readText())
            assertFalse("旧文件应已搬走：$from", File(root, from).exists())
        }
        assertEquals(listOf("features", "plugin-manager"), report.removedDirs)
        assertFalse(File(root, "features").exists())
        assertFalse(File(root, "plugin-manager").exists())
    }

    /**
     * **核心回归**：新路径已存在时不动旧文件、也不覆盖新内容。
     *
     * 反过来（旧覆盖新）会在第二次启动把已经迁移好的内容顶回旧版本 —— 这类故障没有任何
     * 报错，只表现为"用户的改动莫名其妙丢了"。
     */
    @Test
    fun neverOverwritesAnExistingTarget() {
        val root = root()
        for ((from, to) in DshboxLayoutMigration.FILE_MOVES) {
            write(root, from, "旧:$from")
            write(root, to, "新:$to")
        }

        val report = DshboxLayoutMigration.run(root)

        assertTrue("不应搬任何文件", report.moved.isEmpty())
        assertEquals(DshboxLayoutMigration.FILE_MOVES.map { it.second }, report.skipped)
        for ((_, to) in DshboxLayoutMigration.FILE_MOVES) {
            assertEquals("新内容必须原样保留：$to", "新:$to", File(root, to).readText())
        }
    }

    /**
     * 新路径已存在但内容**相同** → 旧的那份只是陈旧副本，删掉它（旧目录才可能被清空）。
     *
     * 这个场景是实测遇到的：应用启动时先写了一次新路径，迁移再跑时新文件已存在，
     * 于是旧的 `features/` 目录永远留在设备上。仅当内容逐字节相同才删，不同则保留待人工判断。
     */
    @Test
    fun discardsStaleLegacyCopyWhenContentIsIdentical() {
        val root = root()
        write(root, "features/feature-overlay.yml", "同样的内容")
        write(root, "dsh-official-plugin/dsh-official-plugin-overlay.yml", "同样的内容")

        val report = DshboxLayoutMigration.run(root)

        assertEquals(listOf("dsh-official-plugin/dsh-official-plugin-overlay.yml"), report.discarded)
        assertTrue(report.keptDivergent.isEmpty())
        assertFalse("陈旧副本应被删掉", File(root, "features/feature-overlay.yml").exists())
        assertFalse("旧目录随之清空", File(root, "features").exists())
    }

    /** 新路径已存在且内容**不同** → 保留旧文件并报告，绝不替用户决定丢哪一份。 */
    @Test
    fun keepsLegacyCopyWhenContentDiffers() {
        val root = root()
        write(root, "features/feature-overlay.yml", "旧内容")
        write(root, "dsh-official-plugin/dsh-official-plugin-overlay.yml", "新内容")

        val report = DshboxLayoutMigration.run(root)

        assertTrue(report.discarded.isEmpty())
        assertEquals(listOf("dsh-official-plugin/dsh-official-plugin-overlay.yml"), report.keptDivergent)
        assertTrue("旧文件必须保留", File(root, "features/feature-overlay.yml").isFile())
        assertTrue("旧目录因此非空，不许删", File(root, "features").isDirectory)
        assertEquals("新内容不许被覆盖", "新内容",
            File(root, "dsh-official-plugin/dsh-official-plugin-overlay.yml").readText())
    }

    @Test
    fun secondRunIsANoOp() {
        val root = root()
        DshboxLayoutMigration.FILE_MOVES.forEach { (from, _) -> write(root, from, "x") }

        val first = DshboxLayoutMigration.run(root)
        val second = DshboxLayoutMigration.run(root)

        assertTrue(first.changed)
        assertFalse("第二次不应再改任何东西", second.changed)
        assertTrue(second.moved.isEmpty() && second.removedDirs.isEmpty() && second.failed.isEmpty())
    }

    /** 旧目录里还有别的东西（用户放的）→ 目录删不掉，必须保留。 */
    @Test
    fun keepsLegacyDirWhenItStillHasOtherContent() {
        val root = root()
        write(root, "features/feature-overlay.yml", "x")
        write(root, "plugin-manager/用户自己放的文件.txt", "别删我")

        val report = DshboxLayoutMigration.run(root)

        assertFalse("features 非空……应为空才对", File(root, "features").exists())
        assertTrue("plugin-manager 里还有别的文件，必须保留", File(root, "plugin-manager").isDirectory)
        assertTrue("被保留下来的东西不许被动", File(root, "plugin-manager/用户自己放的文件.txt").isFile)
        assertEquals(listOf("features"), report.removedDirs)
    }

    /** 暂存目录没有状态（每次启动重铺），允许递归删。 */
    @Test
    fun removesLegacyStageDirsRecursively() {
        val root = root()
        write(root, "mobile-adapt/install.sh", "x")
        write(root, "mobile-adapt/plugin/package.json", "{}")
        write(root, "mobile-pilot/install.sh", "x")

        val report = DshboxLayoutMigration.run(root)

        assertFalse(File(root, "mobile-adapt").exists())
        assertFalse(File(root, "mobile-pilot").exists())
        assertTrue(report.removedDirs.containsAll(listOf("mobile-adapt", "mobile-pilot")))
    }

    /** 没有旧布局的机器（新装用户）：什么都不做，也不报失败。 */
    @Test
    fun freshInstallDoesNothing() {
        val report = DshboxLayoutMigration.run(root())

        assertFalse(report.changed)
        assertTrue(report.moved.isEmpty() && report.skipped.isEmpty())
        assertTrue(report.removedDirs.isEmpty() && report.failed.isEmpty())
    }

    /** 旧文件不存在时不动新路径（避免把"只有新布局"的机器当成待迁移）。 */
    @Test
    fun skipsWhenOldFileIsAbsentEvenIfTargetExists() {
        val root = root()
        write(root, "safe-mode/absolute-safe-mode.yml", "新")

        val report = DshboxLayoutMigration.run(root)

        assertEquals("新", File(root, "safe-mode/absolute-safe-mode.yml").readText())
        assertFalse(report.moved.contains("safe-mode/absolute-safe-mode.yml"))
    }
}
