package com.dshbox.app.util.viewer

import com.dshbox.app.util.viewer.FileTypeClassifier.Confidence
import com.dshbox.app.util.viewer.FileTypeClassifier.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件类型识别器单测（1.2.0 §10.1）：每格式最小魔数样本集、文本/二进制嗅探边界、
 * BOM、zip 与 docx 二级区分、扩展名兜底。
 */
class FileTypeClassifierTest {

    private fun classify(name: String, vararg headBytes: Int, size: Long = headBytes.size.toLong()) =
        FileTypeClassifier.classify(name, headBytes.map { it.toByte() }.toByteArray(), size)

    private fun classifyText(name: String, text: String) =
        FileTypeClassifier.classify(name, text.toByteArray(Charsets.UTF_8), text.length.toLong())

    // ---------------- 魔数（一级） ----------------

    @Test
    fun magicImageFormats() {
        assertEquals("png", classify("a.png", 0x89, 0x50, 0x4E, 0x47).subType)
        assertEquals("jpeg", classify("a.jpg", 0xFF, 0xD8, 0xFF).subType)
        assertEquals("gif", classify("a.gif", *("GIF89a".map { it.code }).toIntArray()).subType)
        // RIFF....WEBP
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".map { it.code }
        assertEquals("webp", classify("a.webp", *webp.toIntArray()).subType)
        assertEquals("bmp", classify("a.bmp", 0x42, 0x4D).subType)
        assertEquals(Confidence.HIGH, classify("a.png", 0x89, 0x50, 0x4E, 0x47).confidence)
    }

    @Test
    fun magicIsoBmffBrands() {
        fun ftyp(brand: String): IntArray = ("....ftyp$brand").map { it.code }.toIntArray()
        assertEquals("heif", classify("a.heic", *ftyp("heic")).subType)
        assertEquals("avif", classify("a.avif", *ftyp("avif")).subType)
        // 返工修正 #9：音视频品牌 → AUDIO_VIDEO（否则大视频落 HexViewer）
        assertEquals("mp4", classify("a.mp4", *ftyp("mp42")).subType)
        assertEquals(FileKind.AUDIO_VIDEO, classify("a.mp4", *ftyp("mp42")).kind)
        assertEquals(FileKind.AUDIO_VIDEO, classify("a.m4a", *ftyp("M4A ")).kind)
        assertEquals("mov", classify("a.mov", *ftyp("qt  ")).subType)
    }

    @Test
    fun magicDocumentAndArchive() {
        assertEquals(FileKind.PDF, classify("a.pdf", *("%PDF-1.7".map { it.code }).toIntArray()).kind)
        val pk = intArrayOf(0x50, 0x4B, 0x03, 0x04)
        assertEquals(FileKind.ARCHIVE, classify("a.zip", *pk).kind)
        // ZIP 族二级区分（§6.2/§6.3）：OOXML 文档 → OFFICE（M3 抽文本）；
        // 返工修正 #2：jar/apk/aar/war/epub 应用容器归 ARCHIVE（M3 ArchiveViewer），不归 OFFICE
        assertEquals(FileKind.OFFICE, classify("a.docx", *pk).kind)
        assertEquals(FileKind.OFFICE, classify("a.xlsx", *pk).kind)
        assertEquals(FileKind.ARCHIVE, classify("a.apk", *pk).kind)
        assertEquals(FileKind.ARCHIVE, classify("a.jar", *pk).kind)
        assertEquals(FileKind.ARCHIVE, classify("a.epub", *pk).kind)
        assertEquals("zip", classify("a.zip", *pk).subType)
        assertEquals("docx", classify("a.docx", *pk).subType)
        assertEquals("apk", classify("a.apk", *pk).subType)
        // gzip / zstd / 7z / rar
        assertEquals(FileKind.ARCHIVE, classify("a.gz", 0x1F, 0x8B).kind)
        assertEquals("tar.zst", classify("x.tar", 0x28, 0xB5, 0x2F, 0xFD, 0, 0).subType)
        assertEquals("7z", classify("a.7z", 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C).subType)
        assertEquals("rar", classify("a.rar", *("Rar!\u001A\u0007\u0000".map { it.code }).toIntArray()).subType)
    }

    @Test
    fun magicTarNeedsUstarAt257() {
        val head = ByteArray(600)
        "ustar".toByteArray().copyInto(head, 257)
        assertEquals("tar", FileTypeClassifier.classify("a.tar", head, 100_000).subType)
        // 短样本无 ustar：落文本嗅探
        assertEquals(FileKind.TEXT, FileTypeClassifier.classify("a", ByteArray(0), 0).kind)
    }

    @Test
    fun magicBinaryKinds() {
        // ELF 架构识别（返工二批 #11）：LE + e_machine=0xB7 → aarch64
        val elfArm = intArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xB7, 0x00)
        assertEquals("elf-aarch64", classify("a.so", *elfArm).subType)
        val elfX64 = intArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x3E, 0x00)
        assertEquals("elf-x86_64", classify("a.so", *elfX64).subType)
        assertEquals("elf", classify("a.so", 0x7F, 0x45, 0x4C, 0x46).subType)
        assertEquals("java-class", classify("A.class", 0xCA, 0xFE, 0xBA, 0xBE).subType)
        assertEquals("dex", classify("a.dex", *("dex\n035".map { it.code }).toIntArray()).subType)
        assertEquals("sqlite", classify("a.db", *("SQLite format 3\u0000".map { it.code }).toIntArray()).subType)
        assertEquals(FileKind.AUDIO_VIDEO, classify("a.mp3", 0xFF, 0xFB).kind)
        assertEquals(FileKind.AUDIO_VIDEO, classify("a.mkv", 0x1A, 0x45, 0xDF, 0xA3).kind)
        assertEquals(FileKind.AUDIO_VIDEO, classify("a.ogg", *("OggS".map { it.code }).toIntArray()).kind)
    }

    // ---------------- 文本嗅探（二级） ----------------

    @Test
    fun textSniffing() {
        // 普通文本（无扩展名脚本、Makefile 等，§6.2）
        assertEquals(FileKind.TEXT, classifyText("Makefile", "#!/bin/sh\necho hi\n").kind)
        assertEquals(FileKind.TEXT, classifyText("Dockerfile", "FROM debian\n").kind)
        // GBK 高位字节不算二进制特征
        val gbk = byteArrayOf(0xC4.toByte(), 0xE3.toByte(), 0x0A) // 「你\n」GBK
        assertEquals(FileKind.TEXT, FileTypeClassifier.classify("a.txt", gbk, 3).kind)
        // NUL → 二进制 → HEX
        assertEquals(FileKind.HEX, FileTypeClassifier.classify("a", byteArrayOf(0x01, 0x02, 0x00, 0x03), 4).kind)
        // 控制字节比例 > 5% → 二进制
        val controls = ByteArray(100) { if (it % 19 == 0) 0x01 else 0x41 } // 6% > 5% 阈值
        assertEquals(FileKind.HEX, FileTypeClassifier.classify("a", controls, 100).kind)
    }

    @Test
    fun bomForcesTextKind() {
        // UTF-8 BOM 文本
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hello".toByteArray()
        assertEquals(FileKind.TEXT, FileTypeClassifier.classify("a.txt", utf8Bom, utf8Bom.size.toLong()).kind)
        // §6.2：UTF-16 BOM 直接定文本（编码判定由 TextEncoding 承接）——
        // 虽然 UTF-16 内容含 NUL，但 BOM 优先于嗅探
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x68, 0x00, 0x69, 0x00)
        assertEquals(FileKind.TEXT, FileTypeClassifier.classify("a.txt", utf16, utf16.size.toLong()).kind)
        val utf16be = byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x00, 0x68, 0x00, 0x69)
        assertEquals(FileKind.TEXT, FileTypeClassifier.classify("a.txt", utf16be, utf16be.size.toLong()).kind)
    }

    // ---------------- 扩展名兜底（三级） ----------------

    @Test
    fun extensionFallback() {
        // 代码语言映射（高亮选择）
        assertEquals("python", FileTypeClassifier.highlightLanguageOf("py"))
        assertEquals("json", FileTypeClassifier.highlightLanguageOf("json"))
        assertEquals("yaml", FileTypeClassifier.highlightLanguageOf("yml"))
        assertEquals("shell", FileTypeClassifier.highlightLanguageOf("bash"))
        assertEquals("javascript", FileTypeClassifier.highlightLanguageOf("ts"))
        assertEquals("java", FileTypeClassifier.highlightLanguageOf("kt"))
        assertEquals("java", FileTypeClassifier.highlightLanguageOf("java"))
        assertEquals(null, FileTypeClassifier.highlightLanguageOf("rb"))
        // Markdown / Web 标记归 MARKUP
        assertEquals(FileKind.MARKUP, classifyText("a.md", "# t").kind)
        assertEquals(FileKind.MARKUP, classifyText("a.svg", "<svg/>").kind)
        // 空文件按扩展名归类（可编辑）
        assertEquals(FileKind.TEXT, FileTypeClassifier.classify("a.py", ByteArray(0), 0).kind)
        assertEquals(Confidence.LOW, FileTypeClassifier.classify("a.py", ByteArray(0), 0).confidence)
    }
}
