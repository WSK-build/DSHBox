package com.dshbox.app.sandbox.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DpkgDbWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun writesStatusAndLists() {
        val rootfs = File(tmp.root, "rootfs")
        val records = listOf(
            DebUnpacker.Record(
                packageName = "base-files",
                version = "13.1",
                architecture = "arm64",
                priority = "required",
                essential = true,
                depends = "base-passwd",
                preDepends = null,
                fileList = listOf("/etc", "/etc/os-release"),
            ),
            DebUnpacker.Record(
                packageName = "hello",
                version = "1.0-1",
                architecture = "arm64",
                priority = "optional",
                essential = false,
                depends = "libc6 (>= 2.34)",
                preDepends = "dash",
                fileList = listOf("/bin/hello"),
            ),
        )

        DpkgDbWriter.write(rootfs, records)

        val status = rootfs.resolve("var/lib/dpkg/status").readText()
        val stanzas = status.split("\n\n").filter { it.isNotBlank() }
        assertEquals(2, stanzas.size)

        val baseFiles = stanzas[0]
        assertTrue(baseFiles.startsWith("Package: base-files\n"))
        assertTrue(baseFiles.contains("Essential: yes\n"))
        assertTrue(baseFiles.contains("Status: install ok installed\n"))
        assertTrue(baseFiles.contains("Priority: required\n"))
        // stanza1 的最后一个字段是 Depends；行尾换行被 stanza 切分吃掉，不带 \n 断言
        assertTrue(baseFiles.endsWith("Depends: base-passwd"))
        assertFalse(baseFiles.contains("Pre-Depends:"))

        val hello = stanzas[1]
        assertTrue(hello.startsWith("Package: hello\n"))
        assertFalse(hello.contains("Essential:"))
        assertTrue(hello.contains("Status: install ok installed\n"))
        assertTrue(hello.contains("Pre-Depends: dash\n"))
        assertTrue(hello.contains("Depends: libc6 (>= 2.34)\n"))

        assertEquals("/etc\n/etc/os-release\n", rootfs.resolve("var/lib/dpkg/info/base-files.list").readText())
        assertEquals("/bin/hello\n", rootfs.resolve("var/lib/dpkg/info/hello.list").readText())
        assertTrue(rootfs.resolve("var/lib/dpkg/updates").isDirectory)
        assertTrue(rootfs.resolve("var/lib/dpkg/info").isDirectory)
    }

    // ---- 字段全量保真（缺 Provides/Multi-Arch 会让 apt 解析不了虚拟包与 :any 依赖）----

    private fun record(pkg: String, fields: Map<String, String>, conffiles: List<String> = emptyList()) =
        DebUnpacker.Record(
            packageName = pkg,
            version = fields["Version"].orEmpty(),
            architecture = fields["Architecture"].orEmpty(),
            priority = fields["Priority"].orEmpty(),
            essential = fields["Essential"] == "yes",
            depends = fields["Depends"],
            preDepends = fields["Pre-Depends"],
            fileList = listOf("/usr/share/doc/$pkg/copyright"),
            fields = fields,
            conffiles = conffiles,
            infoStem = DebUnpacker.infoStemOf(fields),
        )

    @Test
    fun statusKeepsEveryControlField() {
        val rootfs = File(tmp.root, "rootfs-full")
        val fields = linkedMapOf(
            "Package" to "base-files",
            "Essential" to "yes",
            "Priority" to "required",
            "Section" to "admin",
            "Installed-Size" to "345",
            "Maintainer" to "Santiago Vila <sanvila@debian.org>",
            "Architecture" to "arm64",
            // 刻意不用 same：那会让 info 文件名带 `:`，Windows/JVM 建不出来（Android 侧合法）。
            // 命名规则本身由 infoStemFollowsMultiArchSameRule 以纯函数锁定。
            "Multi-Arch" to "foreign",
            "Version" to "13.8+deb13u7",
            "Provides" to "base, usr-is-merged",
            "Pre-Depends" to "awk",
            "Description" to "Debian base system miscellaneous files",
        )
        DpkgDbWriter.write(rootfs, listOf(record("base-files", fields)))
        val status = rootfs.resolve("var/lib/dpkg/status").readText()
        for (key in fields.keys) {
            assertTrue("status 缺字段 $key", status.lineSequence().any { it.startsWith("$key:") })
        }
        // dpkg 惯例：Status 紧跟 Package/Essential 之后
        assertEquals("Essential: yes", status.lines()[1])
        assertEquals("Status: install ok installed", status.lines()[2])
    }

    @Test
    fun conffilesLinesCarryMd5() {
        val rootfs = File(tmp.root, "rootfs-conf")
        rootfs.resolve("etc").mkdirs()
        rootfs.resolve("etc/adduser.conf").writeText("hello\n")
        val fields = linkedMapOf(
            "Package" to "adduser",
            "Architecture" to "all",
            "Version" to "3.152",
            "Description" to "add and remove users and groups",
        )
        DpkgDbWriter.write(rootfs, listOf(record("adduser", fields, conffiles = listOf("/etc/adduser.conf"))))
        val status = rootfs.resolve("var/lib/dpkg/status").readText()
        assertTrue(status.contains("Conffiles:"))
        assertTrue(status.contains(" /etc/adduser.conf b1946ac92492d2347c6235b4d2611184"))
    }

    @Test
    fun conffilesSkippedWhenFileMissing() {
        val rootfs = File(tmp.root, "rootfs-conf-missing")
        val fields = linkedMapOf(
            "Package" to "adduser",
            "Architecture" to "all",
            "Version" to "3.152",
            "Description" to "add and remove users and groups",
        )
        DpkgDbWriter.write(rootfs, listOf(record("adduser", fields, conffiles = listOf("/etc/gone.conf"))))
        assertFalse(rootfs.resolve("var/lib/dpkg/status").readText().contains("Conffiles:"))
    }

    // ---- info 目录命名：Multi-Arch: same → <包>:<架构> ----

    @Test
    fun infoStemFollowsMultiArchSameRule() {
        assertEquals(
            "libacl1:arm64",
            DebUnpacker.infoStemOf(mapOf("Package" to "libacl1", "Architecture" to "arm64", "Multi-Arch" to "same")),
        )
        assertEquals(
            "adduser",
            DebUnpacker.infoStemOf(mapOf("Package" to "adduser", "Architecture" to "all", "Multi-Arch" to "foreign")),
        )
        assertEquals("bash", DebUnpacker.infoStemOf(mapOf("Package" to "bash", "Architecture" to "arm64")))
    }

    @Test
    fun listFileNameFollowsStemForPlainPackage() {
        // 无 Multi-Arch 的包 → `info/<包名>.list`（真正的 `:架构` 落盘只在 Android 侧可验，
        // Windows/JVM 不允许文件名含冒号，故这里只验不带冒号的一侧）。
        val rootfs = File(tmp.root, "rootfs-plain")
        val fields = linkedMapOf(
            "Package" to "bash",
            "Architecture" to "arm64",
            "Version" to "5.2.37-2",
            "Description" to "GNU Bourne Again SHell",
        )
        DpkgDbWriter.write(rootfs, listOf(record("bash", fields)))
        assertTrue(rootfs.resolve("var/lib/dpkg/info/bash.list").isFile)
    }
}
