package com.dshbox.app.sandbox.online

import com.dshbox.app.sandbox.BundleManager
import com.dshbox.app.sandbox.SandboxConfig
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * DebUnpacker 的端到端小样：测试内现场构造真 .deb（ar + control.tar.gz + data.tar.zst /
 * 裸 tar），验证字段解析、data.tar 经 BundleManager 安全解包、control 成员进 dpkg info/。
 * trixie 的 data.tar 默认 zstd，所以主样例用 zst，另用裸 tar 覆盖无压缩分支。
 */
class DebUnpackerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // TemporaryFolder 在 @Before 才建根目录，manager 等到测试体内再取。
    private val bundleManager: BundleManager by lazy { BundleManager(SandboxConfig(appFilesDir = tmp.root)) }
    private val unpacker: DebUnpacker by lazy { DebUnpacker(bundleManager) }

    private fun tarToBytes(build: (TarArchiveOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also { bos ->
            TarArchiveOutputStream(bos).use(build)
        }.toByteArray()

    private fun addTarFile(tar: TarArchiveOutputStream, path: String, content: String, mode: Int = 0b110100100) {
        val entry = TarArchiveEntry(path)
        entry.size = content.length.toLong()
        entry.mode = mode
        tar.putArchiveEntry(entry)
        tar.write(content.toByteArray())
        tar.closeArchiveEntry()
    }

    private fun gz(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { bos ->
            GzipCompressorOutputStream(bos).use { it.write(bytes) }
        }.toByteArray()

    private fun buildDeb(dataTarBytes: ByteArray, dataTarName: String, essential: Boolean = false): File {
        val control = buildString {
            append("Package: hello\n")
            if (essential) append("Essential: yes\n")
            append("Version: 1.0-1\n")
            append("Priority: required\n")
            append("Architecture: arm64\n")
            append("Depends: libc6 (>= 2.34), foo | bar\n")
            // 多行字段：dpkg 用「行首一个空格」表示续行，解析后必须原样保留该空格，
            // 否则重新输出会被 dpkg 读成新字段名（整个 status 解析会失败）。
            append("Description: greeting program\n")
            append(" Prints a friendly greeting.\n")
            append(" .\n")
            append(" Second paragraph.\n")
        }
        val controlTar = tarToBytes { tar ->
            addTarFile(tar, "./control", control)
            addTarFile(tar, "./md5sums", "hash-1\n")
            addTarFile(tar, "./postinst", "#!/bin/sh\nexit 0\n", mode = 0b111101101)
        }
        val deb = File(tmp.root, "hello.deb")
        FileOutputStream(deb).use { fos ->
            ArArchiveOutputStream(fos).use { ar ->
                fun add(name: String, bytes: ByteArray) {
                    ar.putArchiveEntry(ArArchiveEntry(name, bytes.size.toLong()))
                    ar.write(bytes)
                    ar.closeArchiveEntry()
                }
                add("debian-binary", "2.0\n".toByteArray())
                add("control.tar.gz", gz(controlTar))
                add(dataTarName, dataTarBytes)
            }
        }
        return deb
    }

    private fun helloDataTar(): ByteArray = tarToBytes { tar ->
        addTarFile(tar, "./bin/hello", "#!/bin/sh\necho hi\n", mode = 0b111101101)
        addTarFile(tar, "./usr/share/doc/hello/copyright", "GPL\n")
        addTarFile(tar, "./usr/share/man/man1/hello.1.gz", "man page\n")
    }

    @Test
    fun unpacksDebWithGzipDataTar() {
        // 桌面 JVM 无 zstd 本地库（zstd-jni 的 .so 只随 APK 分发），压缩分支用 gzip 覆盖；
        // zstd 分支与 gzip 走同一 autoDecompress 魔数识别。
        val deb = buildDeb(gz(helloDataTar()), "data.tar.gz")
        val rootfs = File(tmp.root, "rootfs")
        val scratch = File(tmp.root, "scratch")

        val record = unpacker.unpack(deb, rootfs, scratch)

        assertEquals("hello", record.packageName)
        assertEquals("1.0-1", record.version)
        assertEquals("arm64", record.architecture)
        assertEquals("required", record.priority)
        assertFalse(record.essential)
        assertEquals("libc6 (>= 2.34), foo | bar", record.depends)
        // 多行字段：续行的行首空格必须保留，且字段表保留完整 control 字段
        assertEquals(
            "greeting program\n Prints a friendly greeting.\n .\n Second paragraph.",
            record.fields["Description"],
        )
        assertEquals("arm64", record.fields["Architecture"])
        assertEquals(
            listOf("/bin/hello", "/usr/share/doc/hello/copyright", "/usr/share/man/man1/hello.1.gz"),
            record.fileList,
        )
        // data.tar 内容经 BundleManager 安全解包落到 rootfs
        assertEquals("#!/bin/sh\necho hi\n", rootfs.resolve("bin/hello").readText())
        assertTrue(rootfs.resolve("usr/share/doc/hello/copyright").isFile)
        // control 成员按 dpkg 约定进 info/<pkg>.*
        assertEquals("hash-1\n", rootfs.resolve("var/lib/dpkg/info/hello.md5sums").readText())
        assertEquals("#!/bin/sh\nexit 0\n", rootfs.resolve("var/lib/dpkg/info/hello.postinst").readText())
        // 临时裸 tar 已清理
        assertFalse(File(scratch, "control.tar").exists())
        assertFalse(File(scratch, "data.tar").exists())
    }

    @Test
    fun unpacksDebWithPlainDataTarAndEssential() {
        val deb = buildDeb(helloDataTar(), "data.tar", essential = true)
        val rootfs = File(tmp.root, "rootfs2")
        val record = unpacker.unpack(deb, rootfs, File(tmp.root, "scratch2"))
        assertTrue(record.essential)
        assertTrue(rootfs.resolve("bin/hello").isFile)
    }

    @Test
    fun rejectsNonDebAr() {
        val fake = File(tmp.root, "fake.deb")
        FileOutputStream(fake).use { fos ->
            ArArchiveOutputStream(fos).use { ar ->
                ar.putArchiveEntry(ArArchiveEntry("random-file", 2L))
                ar.write("hi".toByteArray())
                ar.closeArchiveEntry()
            }
        }
        try {
            unpacker.unpack(fake, File(tmp.root, "rootfs3"), File(tmp.root, "scratch3"))
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            // 未知成员在 ar 循环即拒绝（防非 .deb 内容混入）
            assertTrue(expected.message!!.contains("unexpected .deb member"))
        }
    }
}
