package com.dshbox.app.util

import com.dshbox.app.common.Constants
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `isAppManagedAsset` 的判定单测（审查 P2-5：必须只认工作区根下第一层）。 */
class SandboxFilesTest {

    private val workspace = File("/data/user/0/com.dshbox.app/files/user-data")

    @Test
    fun `matches the asset dir itself and its children`() {
        val dir = File(workspace, ".dsh/dshbox").absolutePath
        assertTrue(isAppManagedAsset(dir, workspace))
        assertTrue(isAppManagedAsset("$dir/bin/dsh", workspace))
        assertTrue(isAppManagedAsset("$dir/mobile-adapt/plugin/lib/client.js", workspace))
    }

    @Test
    fun `does not match sibling dsh dirs outside dshbox`() {
        assertFalse(isAppManagedAsset(File(workspace, ".dsh/profiles/web").absolutePath, workspace))
        assertFalse(isAppManagedAsset(File(workspace, ".dsh/settings.yaml").absolutePath, workspace))
    }

    @Test
    fun `does not match a user-created nested dshbox`() {
        // 用户在自己项目里建的 `.dsh/dshbox` 不该被当成我们的固定资产锁掉
        assertFalse(
            isAppManagedAsset(File(workspace, "myproj/.dsh/dshbox/x").absolutePath, workspace),
        )
    }

    @Test
    fun `does not match a path outside the workspace`() {
        assertFalse(isAppManagedAsset("/root/projects/.dsh/dshbox/bin/dsh", workspace))
    }

    // ---------- guestPathOf：物理路径 → guest 绝对路径 ----------

    private val base = File("/data/user/0/com.dshbox.app/files/runtime/runtime-current/base")
    private val mapper = PathMapper(
        sandboxRoot = base,
        workspaceRoot = workspace,
        nodeLayer = File("/data/user/0/com.dshbox.app/files/runtime/runtime-current/node"),
        dshLayer = File("/data/user/0/com.dshbox.app/files/runtime/runtime-current/dsh"),
    )

    @Test
    fun `workspace files map to root projects`() {
        // 工作区物理根是 user-data，PRoot 把它绑到 guest 的 /root/projects。
        assertEquals("/root/projects/tool.deb", guestPathOf(mapper, File(workspace, "tool.deb")))
        assertEquals(
            "/root/projects/sub/tool.deb",
            guestPathOf(mapper, File(workspace, "sub/tool.deb")),
        )
    }

    @Test
    fun `base layer files map onto the guest root`() {
        assertEquals("/tmp/tool.deb", guestPathOf(mapper, File(base, "tmp/tool.deb")))
    }

    @Test
    fun `bound layers keep their guest mount points`() {
        assertEquals("/usr/local/bin/node", guestPathOf(mapper, File(mapper.nodeLayer, "bin/node")))
        assertEquals(
            "/opt/dshapp/runtime/bin.js",
            guestPathOf(mapper, File(mapper.dshLayer, "bin.js")),
        )
    }

    @Test
    fun `share area files map under the guest share root`() {
        val shareRoot = File("/storage/emulated/0/DSHBox")
        val shareMapper = PathMapper(base, workspace, null, null, shareRoot)
        val guestRoot = "${Constants.SHARE_AREA_GUEST_DIR}/${Constants.SHARE_AREA_DIR_NAME}"
        assertEquals(guestRoot, guestPathOf(shareMapper, shareRoot))
        assertEquals("$guestRoot/a/b.txt", guestPathOf(shareMapper, File(shareRoot, "a/b.txt")))
    }

    @Test
    fun `a lookalike sibling folder is not treated as the share area`() {
        val shareRoot = File("/storage/emulated/0/DSHBox")
        val shareMapper = PathMapper(base, workspace, null, null, shareRoot)
        val guestRoot = "${Constants.SHARE_AREA_GUEST_DIR}/${Constants.SHARE_AREA_DIR_NAME}"
        // 带分隔符边界：`/…/DSHBox2` 不得误命中共享区（否则 .deb 安装会拿到错误 guest 路径）。
        // 断言未映射到共享区，不限定恒等回退的盘符形态，以适配不同 JVM 平台。
        val mapped = guestPathOf(shareMapper, File("/storage/emulated/0/DSHBox2/x.txt"))
        assertFalse("DSHBox2 不得映射为共享区路径", mapped == "$guestRoot/x.txt")
        assertFalse("DSHBox2 不得落在 guest 共享区根下", mapped.startsWith("$guestRoot/"))
    }

    // ---------- isUnderRoot：带分隔符边界的归属判定 ----------

    @Test
    fun `isUnderRoot matches itself and children but not lookalike siblings`() {
        val root = File("/storage/emulated/0/DSHBox")
        assertTrue(isUnderRoot(root, root.absolutePath))
        assertTrue(isUnderRoot(root, File(root, "a/b.txt").absolutePath))
        assertFalse(isUnderRoot(root, File("/storage/emulated/0/DSHBox2/x.txt").absolutePath))
        assertFalse(isUnderRoot(root, File("/storage/emulated/0/Other/x.txt").absolutePath))
    }

    @Test
    fun `isUnderRoot rejects an empty prefix root`() {
        // 根归一化后为空（如 "/"）时必须判 false，否则任意绝对路径恒真。
        assertFalse(isUnderRoot(File("/"), "/data/user/0/com.dshbox.app/files/user-data"))
    }

    // ---------- scanDirectory：目录全保留、文件按上限截断 ----------

    @get:Rule
    val tmp = TemporaryFolder()

    private fun mapperFor(root: File) = PathMapper(
        sandboxRoot = root,
        workspaceRoot = File(root, "user-data"),
        nodeLayer = null,
        dshLayer = null,
    )

    @Test
    fun `cap keeps every file when within the limit`() {
        val files = listOf("a.txt", "b.txt", "c.txt").map { File("/tmp/x/$it") }
        val (kept, truncated) = capDirectoryFiles(files, limit = 10)
        assertEquals(3, kept.size)
        assertFalse(truncated)
    }

    @Test
    fun `cap keeps the first names in order and reports truncation`() {
        // 纯判据：只依赖文件名的排序，不需要真的建文件。
        val files = listOf("e.txt", "d.txt", "c.txt", "b.txt", "a.txt").map { File("/tmp/x/$it") }
        val (kept, truncated) = capDirectoryFiles(files, limit = 3)
        assertTrue(truncated)
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), kept.map { it.name })
    }

    @Test
    fun `scanDirectory returns folders and files with metadata`() {
        val root = tmp.newFolder("rootfs")
        val dir = File(root, "proj").apply { mkdirs() }
        File(dir, "sub").mkdirs()
        File(dir, "note.txt").writeText("hello")
        val listing = scanDirectory(dir, mapperFor(root), isTopLevel = false)
        assertFalse(listing.truncated)
        assertEquals(2, listing.entries.size)
        val folder = listing.entries.first { it.isDirectory }
        val file = listing.entries.first { !it.isDirectory }
        assertEquals("sub", folder.name)
        assertEquals("note.txt", file.name)
        assertEquals(5L, file.size)
        assertTrue(file.logicalPath.endsWith("note.txt"))
    }

    @Test
    fun `scanDirectory on empty folder is empty and not truncated`() {
        val root = tmp.newFolder("rootfs-empty")
        val dir = File(root, "empty").apply { mkdirs() }
        val listing = scanDirectory(dir, mapperFor(root), isTopLevel = false)
        assertTrue(listing.entries.isEmpty())
        assertFalse(listing.truncated)
    }
}
