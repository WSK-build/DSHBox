package com.dshbox.app.util.viewer

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * ArchiveBrowser 单测（纯 JVM）：JDK/commons-compress 可构造的 zip/tar 族样本，
 * 断言条目枚举、层级、嵌套、空包、损坏包不崩、加密位识别与条目内容流（1.2.0 §6.8）。
 * tar.zst 在 JVM 测试环境无原生库：断言收敛为 Error（不崩），真机（仅 arm64）另行验证。
 */
class ArchiveBrowserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------- 样本构造 ----------------

    private fun newZip(entries: Map<String, String>, file: File) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 1700000000000L })
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    /** patchCENFlag：把第一个中央目录条目的 general purpose bit0（加密）置位。 */
    private fun patchCenEncryptedFlag(file: File) {
        val bytes = file.readBytes()
        for (i in 0 until bytes.size - 4) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte() &&
                bytes[i + 2] == 0x01.toByte() && bytes[i + 3] == 0x02.toByte()
            ) {
                bytes[i + 8] = (bytes[i + 8].toInt() or 0x01).toByte()
                file.writeBytes(bytes)
                return
            }
        }
        error("CEN signature not found")
    }

    /** tar 族样本：name→content；content 为 null 表示目录条目。 */
    private fun newTar(
        entries: Map<String, String?>,
        file: File,
        wrap: (FileOutputStream) -> java.io.OutputStream = { it },
    ) {
        TarArchiveOutputStream(
            BufferedOutputStream(wrap(FileOutputStream(file))),
        ).use { tar ->
            entries.forEach { (name, content) ->
                val entry = if (content == null) {
                    TarArchiveEntry("$name/")
                } else {
                    TarArchiveEntry(name).apply { size = content.toByteArray(Charsets.UTF_8).size.toLong() }
                }
                tar.putArchiveEntry(entry)
                if (content != null) tar.write(content.toByteArray(Charsets.UTF_8))
                tar.closeArchiveEntry()
            }
        }
    }

    private fun entryOf(snapshot: ArchiveBrowser.Snapshot, path: String): ArchiveBrowser.Entry =
        snapshot.entries.first { it.path == path }

    // ---------------- 端到端命名链（M3 返工 B 回归） ----------------
    // 此前 formatOf 直测传 ("tar","tar.gz") 等理想参数、browse 直传 Format 枚举，
    // 「文件名 → classify → formatOf」真实链路无一例覆盖，.tar.gz/.tar.zst/.tar.bz2/
    // .tzst 四类主流命名全部落信息卡仍 147 例全绿——本组用例固化完整链路。

    private val GZIP_MAGIC = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00)
    private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    /** 真实链路：分类器（名称+魔数）→ formatOf。 */
    private fun formatViaClassifier(name: String, head: ByteArray): ArchiveBrowser.Format? {
        val type = FileTypeClassifier.classify(name, head, head.size.toLong())
        return ArchiveBrowser.formatOf(type.extension, type.subType)
    }

    @Test
    fun endToEndNaming_compoundTarContainersResolve() {
        assertEquals(
            "a.tar.gz 应承接为 TAR_GZ（返工 B 前为 null → 信息卡）",
            ArchiveBrowser.Format.TAR_GZ,
            formatViaClassifier("a.tar.gz", GZIP_MAGIC),
        )
        assertEquals(
            ArchiveBrowser.Format.TAR_ZST,
            formatViaClassifier("a.tar.zst", ZSTD_MAGIC),
        )
        assertEquals(
            ArchiveBrowser.Format.TAR_BZ2,
            formatViaClassifier("a.tar.bz2", "BZh9".toByteArray(Charsets.US_ASCII)),
        )
        // tzst 惯例命名（zstd 手册推荐的 tar+zstd 双扩展）
        assertEquals(ArchiveBrowser.Format.TAR_ZST, formatViaClassifier("a.tzst", ZSTD_MAGIC))
    }

    @Test
    fun endToEndNaming_shortNamesUnchanged() {
        assertEquals(ArchiveBrowser.Format.TAR_GZ, formatViaClassifier("a.tgz", GZIP_MAGIC))
        assertEquals(
            ArchiveBrowser.Format.TAR_BZ2,
            formatViaClassifier("a.tbz2", "BZh9".toByteArray(Charsets.US_ASCII)),
        )
        assertEquals(ArchiveBrowser.Format.ZIP, formatViaClassifier("a.zip", byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
    }

    @Test
    fun endToEndNaming_classifierExtensionCarriesContainer() {
        // FileType.extension 需携带容器语义（ArchiveViewer 以 extension+subType 查 formatOf）
        val t = FileTypeClassifier.classify("a.tar.gz", GZIP_MAGIC, GZIP_MAGIC.size.toLong())
        assertEquals("tar.gz", t.extension)
        assertEquals("tar.gz", t.subType)
        assertEquals("tar.zst", FileTypeClassifier.classify("a.tar.zst", ZSTD_MAGIC, 4).extension)
        assertEquals("tar.zst", FileTypeClassifier.classify("a.tzst", ZSTD_MAGIC, 4).subType)
    }

    @Test
    fun endToEndNaming_nonTarContainersStillRejected() {
        // 纯 gzip/zstd/bzip2 非 tar 打包不承接（信息卡兜底）——复合命名不得误开
        assertNull(formatViaClassifier("a.gz", GZIP_MAGIC))
        assertNull(formatViaClassifier("a.zst", ZSTD_MAGIC))
        assertNull(formatViaClassifier("a.bz2", "BZh9".toByteArray(Charsets.US_ASCII)))
        assertNull(formatViaClassifier("backup.gz", GZIP_MAGIC))
    }

    @Test
    fun endToEndNaming_realTarGzFileBrowse() {
        // 真实文件全链路：按文件名推导格式并成功枚举
        val f = tmp.newFile("bundle.tar.gz")
        newTar(linkedMapOf("inner/leaf.txt" to "payload"), f) {
            GzipCompressorOutputStream(BufferedOutputStream(it))
        }
        val format = formatViaClassifier(f.name, FileTypeClassifier.readHead(f, 512))
        assertEquals(ArchiveBrowser.Format.TAR_GZ, format)
        val result = ArchiveBrowser.browse(f, format!!)
        assertTrue(result is ArchiveBrowser.Result.Ok)
        assertEquals("inner/leaf.txt", (result as ArchiveBrowser.Result.Ok).snapshot.entries.single().path)
    }

    @Test
    fun endToEndNaming_realTarZstNamedFileConverges() {
        // x86_64 JVM 无 zstd 原生库：.tar.zst 真实命名 + 真实链路仍收敛 Error 不崩
        val f = tmp.newFile("bundle.tar.zst")
        f.writeBytes(ZSTD_MAGIC + ByteArray(32))
        val format = formatViaClassifier(f.name, FileTypeClassifier.readHead(f, 512))
        assertEquals(ArchiveBrowser.Format.TAR_ZST, format)
        assertTrue(ArchiveBrowser.browse(f, format!!) is ArchiveBrowser.Result.Error)
    }

    // ---------------- formatOf（纯函数） ----------------

    @Test
    fun formatOfMapsZipFamilyByExtensionAndSubType() {
        assertEquals(ArchiveBrowser.Format.ZIP, ArchiveBrowser.formatOf("zip", null))
        assertEquals(ArchiveBrowser.Format.ZIP, ArchiveBrowser.formatOf("apk", null))
        assertEquals(ArchiveBrowser.Format.ZIP, ArchiveBrowser.formatOf("epub", null))
        assertEquals(ArchiveBrowser.Format.ZIP, ArchiveBrowser.formatOf("bin", "zip"))
    }

    @Test
    fun formatOfMapsTarFamily() {
        assertEquals(ArchiveBrowser.Format.TAR, ArchiveBrowser.formatOf("tar", "tar"))
        assertEquals(ArchiveBrowser.Format.TAR_GZ, ArchiveBrowser.formatOf("tgz", null))
        assertEquals(ArchiveBrowser.Format.TAR_GZ, ArchiveBrowser.formatOf("tar", "tar.gz"))
        assertEquals(ArchiveBrowser.Format.TAR_BZ2, ArchiveBrowser.formatOf("tbz2", null))
        assertEquals(ArchiveBrowser.Format.TAR_BZ2, ArchiveBrowser.formatOf("tar", "tar.bz2"))
        assertEquals(ArchiveBrowser.Format.TAR_ZST, ArchiveBrowser.formatOf("tar", "tar.zst"))
    }

    @Test
    fun formatOfRejectsUnsupportedContainers() {
        // 7z/RAR 不支持（计划 D5）；gzip/zstd/bzip2/xz 非 tar 打包不承接 → 信息卡兜底
        assertNull(ArchiveBrowser.formatOf("7z", "7z"))
        assertNull(ArchiveBrowser.formatOf("rar", "rar"))
        assertNull(ArchiveBrowser.formatOf("gz", "gzip"))
        assertNull(ArchiveBrowser.formatOf("zst", "zstd"))
        assertNull(ArchiveBrowser.formatOf("bz2", "bzip2"))
        assertNull(ArchiveBrowser.formatOf("xz", "xz"))
        assertNull(ArchiveBrowser.formatOf("exe", null))
    }

    // ---------------- ZIP ----------------

    @Test
    fun zipListsEntriesWithHierarchy() {
        val f = tmp.newFile("a.zip")
        newZip(
            mapOf(
                "a.txt" to "hello",
                "dir/b.txt" to "world",
                "dir/sub/c.txt" to "nested",
            ),
            f,
        )
        val result = ArchiveBrowser.browse(f, ArchiveBrowser.Format.ZIP)
        assertTrue(result is ArchiveBrowser.Result.Ok)
        val snap = (result as ArchiveBrowser.Result.Ok).snapshot
        assertEquals(3, snap.entries.size)
        assertFalse(snap.hasEncrypted)
        assertFalse(snap.truncated)

        val a = entryOf(snap, "a.txt")
        assertEquals(0, a.depth)
        assertEquals("", a.parentPath)
        assertEquals("hello".length.toLong(), a.size)
        assertEquals(1700000000000L, a.lastModified)

        val c = entryOf(snap, "dir/sub/c.txt")
        assertEquals(2, c.depth)
        assertEquals("dir/sub", c.parentPath)
        assertEquals("c.txt", c.name)
        assertEquals("nested".length.toLong(), c.size)
    }

    @Test
    fun zipEmptyArchiveYieldsEmptySnapshot() {
        val f = tmp.newFile("empty.zip")
        ZipOutputStream(f.outputStream()).use { /* no entries */ }
        val result = ArchiveBrowser.browse(f, ArchiveBrowser.Format.ZIP)
        assertTrue(result is ArchiveBrowser.Result.Ok)
        assertEquals(0, (result as ArchiveBrowser.Result.Ok).snapshot.entries.size)
    }

    @Test
    fun zipCorruptedReturnsErrorNotCrash() {
        val good = tmp.newFile("good.zip")
        newZip(mapOf("x.txt" to "x"), good)
        val bad = tmp.newFile("bad.zip")
        val bytes = good.readBytes()
        bad.writeBytes(bytes.copyOf(bytes.size / 2)) // 截断
        val result = ArchiveBrowser.browse(bad, ArchiveBrowser.Format.ZIP)
        assertTrue(result is ArchiveBrowser.Result.Error)
        assertNotNull((result as ArchiveBrowser.Result.Error).message)
    }

    @Test
    fun zipGarbageBytesReturnsErrorNotCrash() {
        val f = tmp.newFile("garbage.zip")
        f.writeBytes(ByteArray(64) { it.toByte() })
        assertTrue(ArchiveBrowser.browse(f, ArchiveBrowser.Format.ZIP) is ArchiveBrowser.Result.Error)
    }

    @Test
    fun zipEncryptedFlagIsDetectedForListing() {
        val f = tmp.newFile("encrypted.zip")
        newZip(
            mapOf(
                "plain.txt" to "readable",
                "secret.txt" to "hidden",
            ),
            f,
        )
        // 仅给 secret.txt 打 CEN 加密位（不真实加密——枚举层只认标志）
        val bytes = f.readBytes()
        val secret = "secret.txt".toByteArray(Charsets.UTF_8)
        var patched = false
        var i = 0
        while (i < bytes.size - 46) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte() &&
                bytes[i + 2] == 0x01.toByte() && bytes[i + 3] == 0x02.toByte()
            ) {
                val nameLen = (bytes[i + 28].toInt() and 0xFF) or ((bytes[i + 29].toInt() and 0xFF) shl 8)
                if (nameLen == secret.size) {
                    var match = true
                    for (k in secret.indices) {
                        if (bytes[i + 46 + k] != secret[k]) {
                            match = false
                            break
                        }
                    }
                    if (match) {
                        bytes[i + 8] = (bytes[i + 8].toInt() or 0x01).toByte()
                        patched = true
                    }
                }
            }
            i++
        }
        assertTrue(patched)
        f.writeBytes(bytes)

        val result = ArchiveBrowser.browse(f, ArchiveBrowser.Format.ZIP)
        assertTrue("JDK 拒绝加密标志包时应回退流式枚举仍返回 Ok", result is ArchiveBrowser.Result.Ok)
        val snap = (result as ArchiveBrowser.Result.Ok).snapshot
        assertTrue(snap.hasEncrypted)
        assertTrue(entryOf(snap, "secret.txt").isEncrypted)
        assertFalse(entryOf(snap, "plain.txt").isEncrypted)
    }

    @Test
    fun zipOpenEntryStreamRoundtrip() {
        val f = tmp.newFile("open.zip")
        newZip(mapOf("dir/data.txt" to "内容内容"), f)
        val stream = ArchiveBrowser.openEntryStream(f, ArchiveBrowser.Format.ZIP, "dir/data.txt")
        assertNotNull(stream)
        assertEquals("内容内容", stream!!.bufferedReader(Charsets.UTF_8).readText())
        stream.close()
    }

    @Test
    fun zipExportAllRewritesEntries() {
        val f = tmp.newFile("src.zip")
        newZip(
            mapOf(
                "one.txt" to "first",
                "d/two.txt" to "second",
            ),
            f,
        )
        val out = tmp.newFile("out.zip")
        ZipOutputStream(out.outputStream()).use { zip ->
            val count = ArchiveBrowser.exportAllToZip(f, ArchiveBrowser.Format.ZIP, zip, cancelCheck = { })
            assertEquals(2, count)
        }
        ZipFile(out).use { zf ->
            assertEquals("first", zf.getInputStream(zf.getEntry("one.txt")).bufferedReader().readText())
            assertEquals("second", zf.getInputStream(zf.getEntry("d/two.txt")).bufferedReader().readText())
        }
    }

    // ---------------- TAR 族 ----------------

    @Test
    fun tarPlainBrowseAndOpen() {
        val f = tmp.newFile("a.tar")
        newTar(
            linkedMapOf(
                "one.txt" to "hello",
                "d" to null,
                "d/two.txt" to "world",
            ),
            f,
        )
        val result = ArchiveBrowser.browse(f, ArchiveBrowser.Format.TAR)
        assertTrue(result is ArchiveBrowser.Result.Ok)
        val snap = (result as ArchiveBrowser.Result.Ok).snapshot
        assertEquals(3, snap.entries.size)
        assertTrue(entryOf(snap, "d").isDirectory)
        assertEquals("hello".length.toLong(), entryOf(snap, "one.txt").size)

        val stream = ArchiveBrowser.openEntryStream(f, ArchiveBrowser.Format.TAR, "d/two.txt")
        assertEquals("world", stream!!.bufferedReader().readText())
        stream.close()
    }

    @Test
    fun tarGzBrowseAndOpen() {
        val f = tmp.newFile("a.tar.gz")
        newTar(
            linkedMapOf("deep/nested/file.txt" to "gz-content"),
            f,
        ) { GzipCompressorOutputStream(BufferedOutputStream(it)) }
        val result = ArchiveBrowser.browse(f, ArchiveBrowser.Format.TAR_GZ)
        assertTrue(result is ArchiveBrowser.Result.Ok)
        val snap = (result as ArchiveBrowser.Result.Ok).snapshot
        assertEquals(1, snap.entries.size)
        assertEquals(2, entryOf(snap, "deep/nested/file.txt").depth)
        assertEquals("gz-content", ArchiveBrowser.openEntryStream(f, ArchiveBrowser.Format.TAR_GZ, "deep/nested/file.txt")!!
            .bufferedReader().readText())
    }

    @Test
    fun tarBz2Browse() {
        val f = tmp.newFile("a.tar.bz2")
        newTar(
            linkedMapOf("bz.txt" to "bz2-content"),
            f,
        ) { BZip2CompressorOutputStream(BufferedOutputStream(it)) }
        val result = ArchiveBrowser.browse(f, ArchiveBrowser.Format.TAR_BZ2)
        assertTrue(result is ArchiveBrowser.Result.Ok)
        assertEquals("bz.txt", (result as ArchiveBrowser.Result.Ok).snapshot.entries.single().path)
    }

    @Test
    fun tarEmptyArchiveYieldsEmptySnapshot() {
        val f = tmp.newFile("empty.tar")
        newTar(linkedMapOf(), f)
        val result = ArchiveBrowser.browse(f, ArchiveBrowser.Format.TAR)
        assertTrue(result is ArchiveBrowser.Result.Ok)
        assertEquals(0, (result as ArchiveBrowser.Result.Ok).snapshot.entries.size)
    }

    @Test
    fun tarCorruptedReturnsErrorNotCrash() {
        val good = tmp.newFile("good.tar")
        newTar(linkedMapOf("x.txt" to "x"), good)
        val bad = tmp.newFile("bad.tar")
        val bytes = good.readBytes()
        bad.writeBytes(bytes.copyOf(100)) // 截断在 512 块中间
        assertTrue(ArchiveBrowser.browse(bad, ArchiveBrowser.Format.TAR) is ArchiveBrowser.Result.Error)
    }

    @Test
    fun tarZstConvergesToErrorWithoutNativeLib() {
        // JVM 测试环境（x86_64）无 zstd 原生库：UnsatisfiedLinkError 必须收敛为 Error
        //（真机上若库存在但内容损坏同样收敛为 Error——两种路径均不崩）
        val f = tmp.newFile("a.tar.zst")
        f.writeBytes(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(), 1, 2, 3, 4))
        val result = ArchiveBrowser.browse(f, ArchiveBrowser.Format.TAR_ZST)
        assertTrue(result is ArchiveBrowser.Result.Error)
        // 内容流同样不外抛
        assertEquals(null, ArchiveBrowser.openEntryStream(f, ArchiveBrowser.Format.TAR_ZST, "x.txt"))
    }

    @Test
    fun tarOpenMissingEntryReturnsNull() {
        val f = tmp.newFile("a.tar")
        newTar(linkedMapOf("one.txt" to "hello"), f)
        assertEquals(null, ArchiveBrowser.openEntryStream(f, ArchiveBrowser.Format.TAR, "missing.txt"))
    }

    @Test
    fun tarExportAllSinglePassRewritesEntries() {
        val f = tmp.newFile("src.tar.gz")
        newTar(
            linkedMapOf(
                "one.txt" to "first",
                "d/two.txt" to "second",
            ),
            f,
        ) { GzipCompressorOutputStream(BufferedOutputStream(it)) }
        val out = tmp.newFile("out.zip")
        ZipOutputStream(out.outputStream()).use { zip ->
            assertEquals(2, ArchiveBrowser.exportAllToZip(f, ArchiveBrowser.Format.TAR_GZ, zip, cancelCheck = { }))
        }
        ZipFile(out).use { zf ->
            assertEquals("first", zf.getInputStream(zf.getEntry("one.txt")).bufferedReader().readText())
            assertEquals("second", zf.getInputStream(zf.getEntry("d/two.txt")).bufferedReader().readText())
        }
    }

    // ---------------- 名称归一 ----------------

    @Test
    fun normalizeNameUnifiesSeparatorsAndDots() {
        assertEquals("a/b/c", ArchiveBrowser.normalizeName("./a//b/c"))
        assertEquals("a/b", ArchiveBrowser.normalizeName("/a/b/"))
        assertEquals("a/b", ArchiveBrowser.normalizeName("a\\b"))
        assertEquals("", ArchiveBrowser.normalizeName("./"))
    }
    
    // ---------------- 中文条目名（P2：GBK/UTF-8） ----------------

    @Test
    fun zipChineseNamesGbkDecoded() {
        // 国内 Windows 压缩软件：GBK 编码条目名（bit11 未置位）——browse 应显示正确中文
        val f = tmp.newFile("中文包.zip")
        ZipArchiveOutputStream(java.io.FileOutputStream(f)).use { zip ->
            zip.setEncoding("GBK")
            val e = ZipArchiveEntry("中文文件.txt")
            zip.putArchiveEntry(e)
            zip.write("hi".toByteArray(Charsets.UTF_8))
            zip.closeArchiveEntry()
        }
        val result = ArchiveBrowser.browse(f, ArchiveBrowser.Format.ZIP)
        assertTrue(result is ArchiveBrowser.Result.Ok)
        val snap = (result as ArchiveBrowser.Result.Ok).snapshot
        assertEquals("中文文件.txt", snap.entries.single().path)
        assertEquals("中文文件.txt", snap.entries.single().name)
        assertEquals("GBK", snap.charsetLabel)
    }

    @Test
    fun zipChineseNamesUtf8FlagStillWorks() {
        // UTF-8 flag（bit11）置位的条目：仍按 UTF-8 解码（不因默认 GBK 误伤）
        val f = tmp.newFile("utf8名.zip")
        newZip(mapOf("中文目录/utf8文件.txt" to "x"), f)
        val result = ArchiveBrowser.browse(f, ArchiveBrowser.Format.ZIP)
        assertTrue(result is ArchiveBrowser.Result.Ok)
        val snap = (result as ArchiveBrowser.Result.Ok).snapshot
        assertEquals("中文目录/utf8文件.txt", snap.entries.single().path)
        assertEquals("UTF-8", snap.charsetLabel)
    }

    // tarChineseNamesGbkDecoded 已移除（第三轮审查实证修正）：TAR 名统一 UTF-8，
    // GBK 编码 tar 为已知限制（commons 解 GBK 字节产出问号 U+003F，无标志位可依，
    // 判定不可靠；沙盒 `tar czf` 主场景为 UTF-8）。已知行为：GBK tar 条目名显示问号、
    // 不崩溃、可枚举——真机清单观察点。1.2.x 评估 UI 编码切换入口。

   @Test
    fun zipChineseNamesUtf8WithoutFlagNotMistakenForGbk() {
        // 第三轮审查：bit11 未置位但内容为 UTF-8（部分 Linux 工具/旧工具）。
        // 启发式必须不误判：UTF-8 解无 U+FFFD → 保持 UTF-8，条目名正确。
        val f = tmp.newFile("noflag-utf8.zip")
        ZipArchiveOutputStream(java.io.FileOutputStream(f)).use { zip ->
            zip.setEncoding("UTF-8")
            zip.setUseLanguageEncodingFlag(false) // 不置 bit11
            val e = ZipArchiveEntry("中文目录/文件.txt")
            zip.putArchiveEntry(e)
            zip.write("x".toByteArray(Charsets.UTF_8))
            zip.closeArchiveEntry()
        }
        val result = ArchiveBrowser.browse(f, ArchiveBrowser.Format.ZIP)
        assertTrue(result is ArchiveBrowser.Result.Ok)
        val snap = (result as ArchiveBrowser.Result.Ok).snapshot
        assertEquals("中文目录/文件.txt", snap.entries.single().path)
        assertEquals("UTF-8", snap.charsetLabel)
    }

    @Test
    fun tarChineseNamesUtf8DefaultMainScenario() {
        // 沙盒内 `tar czf` 打出的 UTF-8 中文名包为 DSHBox 主场景（第三轮审查）：
        // 默认 UTF-8 解码必须正确，且不被 GBK 启发误伤（UTF-8 解无 U+FFFD）。
        val f = tmp.newFile("utf8-中文.tar")
        TarArchiveOutputStream(
            java.io.BufferedOutputStream(java.io.FileOutputStream(f)),
            "UTF-8",
        ).use { tar ->
            val entry = TarArchiveEntry("中文目录/中文文件.txt").apply {
                size = "x".toByteArray(Charsets.UTF_8).size.toLong()
            }
            tar.putArchiveEntry(entry)
            tar.write("x".toByteArray(Charsets.UTF_8))
            tar.closeArchiveEntry()
        }
        val result = ArchiveBrowser.browse(f, ArchiveBrowser.Format.TAR)
        assertTrue(result is ArchiveBrowser.Result.Ok)
        val snap = (result as ArchiveBrowser.Result.Ok).snapshot
        assertEquals("中文目录/中文文件.txt", snap.entries.single().path)
        assertEquals("UTF-8", snap.charsetLabel)
    }
}
