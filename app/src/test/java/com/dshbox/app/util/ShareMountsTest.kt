package com.dshbox.app.util

import com.dshbox.app.common.Constants
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享区挂载清单：编解码、别名归一、绑定清单、SAF 文档 id 转换，以及
 * 终端与沙箱层 guest 路径一致性的跨模块契约（纯 JVM）。
 */
class ShareMountsTest {

    @Test
    fun `codec round trips and tolerates broken lines`() {
        val mounts = listOf(
            ShareMount("Download", "/storage/emulated/0/Download"),
            ShareMount("Doc", "/storage/emulated/0/Documents"),
        )
        assertEquals(mounts, ShareMountsCodec.decode(ShareMountsCodec.encode(mounts)))

        val text = "\nbrokenline\n\t/only-path\nname-without-path\t\nGood\t/storage/emulated/0/Good\n"
        assertEquals(
            listOf(ShareMount("Good", "/storage/emulated/0/Good")),
            ShareMountsCodec.decode(text),
        )
        // CRLF 改写（外部编辑清单）不得让路径尾部带入 \r，否则该挂载会被静默隐藏。
        assertEquals(
            listOf(ShareMount("Win", "/storage/emulated/0/Win")),
            ShareMountsCodec.decode("Win\t/storage/emulated/0/Win\r\n"),
        )
    }

    @Test
    fun `decode sanitizes names and rejects implausible paths`() {
        // 清单可能被外部改写：别名归一 + 拒绝相对路径与含上跳段的路径（纵深防御）。
        val text = "bad/name\t/storage/emulated/0/Ok\n" +
            "Up\t/storage/emulated/0/../data\n" +
            "Rel\trelative/path\n"
        assertEquals(
            listOf(ShareMount("bad-name", "/storage/emulated/0/Ok")),
            ShareMountsCodec.decode(text),
        )
        assertTrue(ShareMountsCodec.isPlausibleMountPath("/storage/emulated/0/Download"))
        assertFalse(ShareMountsCodec.isPlausibleMountPath("../etc"))
        assertFalse(ShareMountsCodec.isPlausibleMountPath("/storage/emulated/0/../data"))
        // 根路径没有可用的挂载段，必须拒绝（纵深防御）。
        assertFalse(ShareMountsCodec.isPlausibleMountPath("/"))
    }

    @Test
    fun `uniqueName sanitizes and avoids reserved and duplicate names`() {
        val existing = listOf(ShareMount("Download", "/x"))
        assertEquals("Documents", ShareMountsCodec.uniqueName("Documents", existing))
        assertEquals("Download-2", ShareMountsCodec.uniqueName("Download", existing))
        // 内建 DSHBox 是保留名；非法字符归一为 '-'。
        assertEquals("DSHBox-2", ShareMountsCodec.uniqueName("DSHBox", existing))
        assertEquals("a-b", ShareMountsCodec.uniqueName("a:b", existing))
        assertEquals("mount", ShareMountsCodec.uniqueName("///", existing))
    }

    @Test
    fun `share binds keep built-in first and skip missing dirs`() {
        val tmp = Files.createTempDirectory("share-binds").toFile()
        val builtIn = File(tmp, "DSHBox").apply { mkdirs() }
        val mount = File(tmp, "Download").apply { mkdirs() }
        val missing = File(tmp, "Missing")

        val binds = shareBindsOf(
            builtIn,
            listOf(ShareMount("Download", mount.absolutePath), ShareMount("Missing", missing.absolutePath)),
        )
        assertEquals(listOf(Constants.SHARE_AREA_DIR_NAME to builtIn, "Download" to mount), binds)

        // 内建目录缺失（未授权 / 未创建）时只剩用户挂载。
        assertEquals(
            listOf("Download" to mount),
            shareBindsOf(null, listOf(ShareMount("Download", mount.absolutePath))),
        )
        // 同路径重复项只保留首个（列表 key 与绑定目标都需唯一）。
        assertEquals(
            listOf("Download" to mount),
            shareBindsOf(null, listOf(ShareMount("Download", mount.absolutePath), ShareMount("Download-2", mount.absolutePath))),
        )
    }

    @Test
    fun `share binds dedupe physically identical mount paths`() {
        val tmp = Files.createTempDirectory("share-binds-alias").toFile()
        val mount = File(tmp, "Download").apply { mkdirs() }

        // 同一物理目录的不同写法（尾随 `.` 段 / 重复分隔符）：规范路径归一后只保留首个落点，
        // 避免外部改写清单产生重复落点（列表重复项与 guest 重复绑定）。
        val dotAlias = File(mount, ".").absolutePath
        assertEquals(
            listOf("Download" to mount),
            shareBindsOf(
                null,
                listOf(ShareMount("Download", mount.absolutePath), ShareMount("Download-2", dotAlias)),
            ),
        )
        val doubledSeparator = mount.absolutePath.replace(File.separator, File.separator + File.separator)
        assertEquals(
            listOf("Download" to mount),
            shareBindsOf(
                null,
                listOf(ShareMount("Download", mount.absolutePath), ShareMount("Download-2", doubledSeparator)),
            ),
        )

        // 大小写别名：仅在大小写不敏感的文件系统（Windows / macOS）上才是物理同一路径，
        // 故先判定平台是否归一，再断言去重；大小写敏感平台保留两个落点属预期行为。
        val caseAlias = mount.absolutePath.uppercase()
        if (normalizedSharePath(File(caseAlias)) == normalizedSharePath(mount)) {
            assertEquals(
                listOf("Download" to mount),
                shareBindsOf(
                    null,
                    listOf(ShareMount("Download", mount.absolutePath), ShareMount("Download-2", caseAlias)),
                ),
            )
        }
    }

    @Test
    fun `listing sanitizer drops reserved names and duplicate name or path`() {
        val mounts = listOf(
            ShareMount(Constants.SHARE_AREA_DIR_NAME, "/storage/emulated/0/DSHBox"),
            ShareMount("Download", "/storage/emulated/0/Download"),
            ShareMount("Download", "/storage/emulated/0/Other"),
            ShareMount("Dupe", "/storage/emulated/0/Download"),
            ShareMount("Music", "/storage/emulated/0/Music"),
        )
        // 保留名被剔除；重名条目（第二个 Download）与重路径条目（Dupe）都只保留首个，
        // 保证列表 key 与绑定目标唯一。
        assertEquals(
            listOf(
                ShareMount("Download", "/storage/emulated/0/Download"),
                ShareMount("Music", "/storage/emulated/0/Music"),
            ),
            ShareMountsStore.sanitizeMountsForListing(mounts, Constants.SHARE_AREA_DIR_NAME),
        )
    }

    @Test
    fun `document ids map to public storage paths`() {
        val primary = "/storage/emulated/0"
        assertEquals("$primary/Download", ShareArea.hostPathOfDocumentId("primary:Download", primary))
        assertEquals(primary, ShareArea.hostPathOfDocumentId("primary:", primary))
        assertEquals("/storage/1234-5678/Music", ShareArea.hostPathOfDocumentId("1234-5678:Music", primary))
        // 第三方 DocumentsProvider（非外部存储卷）必须拒绝。
        assertNull(ShareArea.hostPathOfDocumentId("com.google.drive:root", primary))
        assertNull(ShareArea.hostPathOfDocumentId("nocolon", primary))
    }

    @Test
    fun `document ids reject traversal and empty path segments`() {
        val primary = "/storage/emulated/0"
        // 段级校验：上跳段、当前目录段与空段整体拒绝，避免逃出卷根。
        assertNull(ShareArea.hostPathOfDocumentId("primary:a/../b", primary))
        assertNull(ShareArea.hostPathOfDocumentId("primary:a//b", primary))
        assertNull(ShareArea.hostPathOfDocumentId("primary:./x", primary))
        assertNull(ShareArea.hostPathOfDocumentId("primary:a/./b", primary))
        assertNull(ShareArea.hostPathOfDocumentId("primary:..", primary))
        // 合法用例不受影响（含多级子路径与前后斜杠容错）。
        assertEquals("$primary/Download/sub", ShareArea.hostPathOfDocumentId("primary:Download/sub", primary))
        assertEquals("$primary/Download", ShareArea.hostPathOfDocumentId("primary:/Download/", primary))
        assertEquals(primary, ShareArea.hostPathOfDocumentId("primary:", primary))
    }

    @Test
    fun `document ids prefer the volume table then fall back to generic volume ids`() {
        val primary = "/storage/emulated/0"
        // 卷表命中优先于通用回退：OEM 卷 ID 与 UUID 形态之外的名字都能解析到真实目录。
        val volumes = mapOf(
            "1234-5678" to "/mnt/media_rw/1234-5678",
            "oem_sd" to "/storage/oem_sd",
        )
        assertEquals(
            "/mnt/media_rw/1234-5678/Music",
            ShareArea.hostPathOfDocumentId("1234-5678:Music", primary, volumes),
        )
        assertEquals(
            "/storage/oem_sd/DCIM",
            ShareArea.hostPathOfDocumentId("oem_sd:DCIM", primary, volumes),
        )
        // 大小写不敏感：primary 与卷表键都忽略大小写匹配。
        assertEquals(
            "$primary/Download",
            ShareArea.hostPathOfDocumentId("PRIMARY:Download", primary, volumes),
        )
        assertEquals(
            "/storage/oem_sd/DCIM",
            ShareArea.hostPathOfDocumentId("OEM_SD:DCIM", primary, volumes),
        )
        // 卷表未命中、但卷 ID 形态合规：回退 /storage/<volume>（保留旧行为）。
        assertEquals(
            "/storage/ABCD-1234/Music",
            ShareArea.hostPathOfDocumentId("ABCD-1234:Music", primary, volumes),
        )
        // 第三方 DocumentsProvider（含 '.'）：既不在卷表也不合规，必须拒绝。
        assertNull(ShareArea.hostPathOfDocumentId("com.google.drive:root", primary, volumes))
    }

    @Test
    fun `writable probe detects a writable directory and cleans the probe file`() {
        val writable = Files.createTempDirectory("share-writable").toFile()
        assertTrue(ShareArea.isWritableDir(writable))
        // 探测文件不残留（前缀命中即为残留）。
        assertTrue(writable.listFiles().orEmpty().none { it.name.startsWith(".dshbox-probe-") })

        // 不存在 / 非目录一律不可写。
        assertFalse(ShareArea.isWritableDir(File(writable, "missing")))
        assertFalse(ShareArea.isWritableDir(File(writable, "file.txt").apply { writeText("x") }))
    }

    @Test
    fun `writable probe leaves a user file with the probe name untouched`() {
        val dir = Files.createTempDirectory("share-probe-keep").toFile()
        // 与探测前缀同名的用户文件：探测必须另建随机名，不能删除 / 覆盖该文件。
        val userFile = File(dir, ".dshbox-probe-user").apply { writeText("keep") }
        assertTrue(ShareArea.isWritableDir(dir))
        assertTrue("用户同名文件必须原样保留", userFile.exists())
        assertEquals("keep", userFile.readText())
    }

    @Test
    fun `mount file write is atomic and reports success`() {
        val dir = Files.createTempDirectory("share-save").toFile()
        val target = File(dir, "share-mounts.txt")
        val text = ShareMountsCodec.encode(listOf(ShareMount("Download", "/storage/emulated/0/Download")))

        assertTrue(writeMountsFile(target, text))
        assertEquals(text, target.readText())
        // 临时文件在成功路径上不残留。
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(ShareMountsStore.TMP_SUFFIX) })

        // 覆盖写入（既有文件）同样成功且内容更新。
        val next = ShareMountsCodec.encode(emptyList())
        assertTrue(writeMountsFile(target, next))
        assertEquals(next, target.readText())
    }

    @Test
    fun `mount file write reports failure and keeps tmp when parent is unusable`() {
        val dir = Files.createTempDirectory("share-save-fail").toFile()
        // 目标名被一个非空目录占位：改名无法覆盖目录，回退直写抛异常，应如实返回 false。
        val target = File(dir, "share-mounts.txt").apply { mkdirs() }
        File(target, "inner.txt").writeText("x")

        assertFalse(writeMountsFile(target, "data"))
        // 失败路径保留临时文件（便于排查写不进去的具体原因），不会被无条件删除。
        assertTrue(File(dir, "share-mounts.txt" + ShareMountsStore.TMP_SUFFIX).exists())
        // 占位目录未被破坏。
        assertTrue(target.isDirectory)
    }

    @Test
    fun `namespace entries keep available targets and surface unreachable mounts`() {
        val tmp = Files.createTempDirectory("share-namespace").toFile()
        val builtIn = File(tmp, "DSHBox").apply { mkdirs() }
        val live = File(tmp, "Download").apply { mkdirs() }
        val gone = File(tmp, "Gone")

        val entries = shareNamespaceEntriesOf(
            builtIn,
            listOf(ShareMount("Download", live.absolutePath), ShareMount("Gone", gone.absolutePath)),
        )
        assertEquals(
            listOf(Constants.SHARE_AREA_DIR_NAME, "Download", "Gone"),
            entries.map { it.first.name },
        )
        assertEquals(listOf(true, true, false), entries.map { it.second })
        // 不可达条目带 unavailable 标记，逻辑路径用其宿主路径（供取消挂载按名匹配）。
        val unavailable = entries.last().first
        assertTrue(unavailable.unavailable)
        assertEquals(gone.absolutePath, unavailable.logicalPath)
        // 内建目录缺失时仍只列可用的用户挂载 + 不可达项。
        val noBuiltIn = shareNamespaceEntriesOf(
            null,
            listOf(ShareMount("Download", live.absolutePath), ShareMount("Gone", gone.absolutePath)),
        )
        assertEquals(listOf("Download", "Gone"), noBuiltIn.map { it.first.name })
    }

    @Test
    fun `namespace entries keep every alias even when a bind is deduplicated`() {
        val tmp = Files.createTempDirectory("share-namespace-dedupe").toFile()
        val builtIn = File(tmp, "DSHBox").apply { mkdirs() }
        val live = File(tmp, "Download").apply { mkdirs() }
        // 同一物理目录的另一种写法（尾随 `.` 段）：目录确实存在，但会被 shareBindsOf
        // 按物理同一性去重丢弃。
        val dotAlias = File(live, ".").absolutePath

        val mounts = listOf(ShareMount("Download", live.absolutePath), ShareMount("Download-2", dotAlias))
        // 前置条件：绑定清单确实丢掉了第二条（本用例的立足点）。
        assertEquals(listOf("Download" to live), shareBindsOf(null, mounts))

        val entries = shareNamespaceEntriesOf(builtIn, mounts)
        // 每个别名都必须有一条可操作的行：被去重的条目补一条 unavailable 虚拟条目，
        // 否则它会同时缺席可用列表与不可达列表，用户再也无法取消该挂载。
        assertEquals(
            listOf(Constants.SHARE_AREA_DIR_NAME, "Download", "Download-2"),
            entries.map { it.first.name },
        )
        assertEquals(listOf(true, true, false), entries.map { it.second })
        // 被去重条目仍能按 name 命中挂载清单（行菜单据此显示「取消挂载」），
        // 逻辑路径沿用其宿主路径。
        val dropped = entries.last().first
        assertTrue(dropped.unavailable)
        assertEquals(dotAlias, dropped.logicalPath)
        assertTrue(mounts.any { it.name == dropped.name })
    }

    @Test
    fun `namespace crumbs keep namespace then landing then subpath`() {
        val tmp = Files.createTempDirectory("share-crumbs").toFile()
        val builtIn = File(tmp, "DSHBox").apply { mkdirs() }
        val sub = File(builtIn, "sub").apply { mkdirs() }
        val namespace = File("/root/share")
        val entries = listOf(Constants.SHARE_AREA_DIR_NAME to builtIn)
        val label = "shared  /root/share"

        assertEquals(listOf(label to namespace), shareCrumbsOf(namespace, namespace, label, entries))
        assertEquals(
            listOf(label to namespace, Constants.SHARE_AREA_DIR_NAME to builtIn),
            shareCrumbsOf(builtIn, namespace, label, entries),
        )
        assertEquals(
            listOf(label to namespace, Constants.SHARE_AREA_DIR_NAME to builtIn, "sub" to sub),
            shareCrumbsOf(sub, namespace, label, entries),
        )
        // 不在任何落点下（如落点被外部删除）：只保留命名空间段。
        assertEquals(
            listOf(label to namespace),
            shareCrumbsOf(File(tmp, "Gone"), namespace, label, entries),
        )
    }

    @Test
    fun `namespace landing uses the deepest matching entry`() {
        val tmp = Files.createTempDirectory("share-deep").toFile()
        val outer = File(tmp, "Outer").apply { mkdirs() }
        val inner = File(outer, "Inner").apply { mkdirs() }
        val sub = File(inner, "sub").apply { mkdirs() }
        // 嵌套条目（外部改写清单造成）：落点判定与面包屑同取最深匹配。
        val entries = listOf("Outer" to outer, "Inner" to inner)

        assertEquals(inner, shareBaseDirectoryOf(sub, entries))
        assertEquals(inner, shareBaseDirectoryOf(inner, entries))
        assertEquals(outer, shareBaseDirectoryOf(outer, entries))
    }

    @Test
    fun `alias lookup uses the same deepest match as landing detection`() {
        val tmp = Files.createTempDirectory("share-alias").toFile()
        val dir = File(tmp, "Download").apply { mkdirs() }
        // 正常清单经展示过滤后路径唯一；重复目标用于验证反查与落点判定一致
        // （都取条目集合内最后一个命中项），避免清单被外部改写时两处分叉。
        val entries = listOf("Download" to dir, "Download-2" to dir)
        assertEquals("Download-2", shareAliasOf(dir, entries))
        assertNull(shareAliasOf(File(tmp, "Other"), entries))
    }

    @Test
    fun `namespace navigation stays inside the namespace`() {
        val tmp = Files.createTempDirectory("share-nav").toFile()
        val builtIn = File(tmp, "DSHBox").apply { mkdirs() }
        val other = File(tmp, "Other").apply { mkdirs() }
        val sub = File(builtIn, "sub").apply { mkdirs() }
        val namespace = File("/root/share")
        val entries = listOf(Constants.SHARE_AREA_DIR_NAME to builtIn)

        assertEquals(builtIn, shareBaseDirectoryOf(sub, entries))
        assertEquals(builtIn, shareBaseDirectoryOf(builtIn, entries))
        assertNull(shareBaseDirectoryOf(other, entries))

        // 落点内部逐级上溯；到达落点根或不在落点内一律回到命名空间，不会逃到公共存储。
        assertEquals(builtIn, shareNamespaceParentOf(sub, namespace, entries))
        assertEquals(namespace, shareNamespaceParentOf(builtIn, namespace, entries))
        assertEquals(namespace, shareNamespaceParentOf(other, namespace, entries))
    }

    @Test
    fun `terminal login shell binds the share area at the sandbox guest path`() {
        val tmp = Files.createTempDirectory("share-term").toFile()
        val builtIn = File(tmp, "DSHBox").apply { mkdirs() }
        val paths = com.dshbox.terminal.TerminalPaths(
            prootBinary = File(tmp, "proot"),
            prootLoader = File(tmp, "loader"),
            nativeLibDir = File(tmp, "lib"),
            debianRootfs = File(tmp, "base").apply { mkdirs() },
            nodeDir = File(tmp, "node"),
            workspaceBind = File(tmp, "user-data"),
            prootTmpDir = File(tmp, "tmp"),
            failsafeHome = File(tmp, "home"),
            failsafeTmpDir = File(tmp, "htmp"),
            shareBinds = shareBindsOf(builtIn, emptyList()),
        )
        val command = com.dshbox.terminal.TerminalCommandFactory.sandboxLoginShell(paths)
        val expected = "--bind=${builtIn.absolutePath}:${Constants.SHARE_AREA_GUEST_DIR}/${Constants.SHARE_AREA_DIR_NAME}"
        assertTrue("终端必须与沙箱层绑到同一 guest 路径：$expected", expected in command)
    }
}
