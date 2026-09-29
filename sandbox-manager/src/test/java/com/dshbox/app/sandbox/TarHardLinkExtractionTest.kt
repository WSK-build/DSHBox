package com.dshbox.app.sandbox

import com.dshbox.app.common.AppResult
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

/**
 * tar 硬链接的 linkname 是归档根相对路径（POSIX），`/usr/bin/x`、`./usr/bin/x`、
 * `usr/bin/x` 三种写法等价；按链接所在目录解析会静默丢条目（dpkg 的 zipinfo /
 * perl5.40.1 / perlthanks 即为此）。这些用例钉住该行为与越界拒绝。
 */
class TarHardLinkExtractionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // lazy: TemporaryFolder 只在 @Before 里建根目录，类构造期不能碰 tmp.root。
    private val manager: BundleManager by lazy { BundleManager(SandboxConfig(appFilesDir = tmp.root)) }

    private fun tarWith(vararg entries: Pair<String, String?>): File {
        val tarFile = File(tmp.root, "layer-${System.nanoTime()}.tar")
        FileOutputStream(tarFile).use { fos ->
            TarArchiveOutputStream(fos).use { tar ->
                for ((name, spec) in entries) {
                    if (spec == null) {
                        // 内容 = 文件名，便于断言硬链接取到的是目标内容。
                        val bytes = "content-of:$name".toByteArray()
                        val e = TarArchiveEntry(name)
                        e.size = bytes.size.toLong()
                        e.mode = 0b111101101 // 0755
                        tar.putArchiveEntry(e)
                        tar.write(bytes)
                        tar.closeArchiveEntry()
                    } else {
                        val e = TarArchiveEntry(name, TarConstants.LF_LINK)
                        e.linkName = spec
                        e.mode = 0b111101101
                        tar.putArchiveEntry(e)
                        tar.closeArchiveEntry()
                    }
                }
            }
        }
        return tarFile
    }

    private fun extract(tarFile: File): File {
        val dest = File(tmp.root, "rootfs-${System.nanoTime()}")
        val result = manager.extractTarGz(tarFile, dest)
        assertTrue("extract failed: $result", result is AppResult.Success)
        return dest
    }

    @Test
    fun hardLinkWithDotSlashTargetIsRootRelative() {
        // dpkg 的真实形态：target `./usr/bin/unzip`，硬链接名 `usr/bin/zipinfo`。
        val dest = extract(tarWith("usr/bin/unzip" to null, "usr/bin/zipinfo" to "./usr/bin/unzip"))
        assertEquals("content-of:usr/bin/unzip", File(dest, "usr/bin/zipinfo").readText())
    }

    @Test
    fun hardLinkWithBareRelativeTargetIsRootRelative() {
        val dest = extract(tarWith("usr/bin/perl" to null, "usr/bin/perl5.40.1" to "usr/bin/perl"))
        assertEquals("content-of:usr/bin/perl", File(dest, "usr/bin/perl5.40.1").readText())
    }

    @Test
    fun hardLinkWithAbsoluteTargetStillWorks() {
        val dest = extract(tarWith("usr/bin/unzip" to null, "usr/bin/zipinfo" to "/usr/bin/unzip"))
        assertEquals("content-of:usr/bin/unzip", File(dest, "usr/bin/zipinfo").readText())
    }

    @Test
    fun hardLinkTargetAppearingLaterIsResolved() {
        // tar 成员顺序不保证目标在前：收尾补做那一遍必须接住。
        val dest = extract(tarWith("usr/bin/zipinfo" to "./usr/bin/unzip", "usr/bin/unzip" to null))
        assertEquals("content-of:usr/bin/unzip", File(dest, "usr/bin/zipinfo").readText())
    }

    @Test
    fun hardLinkEscapingRootIsRejected() {
        val tarFile = tarWith("usr/bin/unzip" to null, "usr/bin/evil" to "../../etc/passwd")
        val dest = File(tmp.root, "rootfs-escape")
        val result = manager.extractTarGz(tarFile, dest)
        assertTrue("escape must be refused: $result", result is AppResult.Failure)
    }
}
