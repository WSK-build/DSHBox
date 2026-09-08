package com.dshbox.app.util.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内容指纹单测（1.2.0 §10.1）：同内容同摘要、首/尾 64KB 变化可检出、size/mtime 敏感。
 */
class ContentFingerprintTest {

    private fun tempFile(bytes: ByteArray, mtime: Long? = null): File =
        File.createTempFile("fpref", ".bin").apply {
            writeBytes(bytes)
            mtime?.let { setLastModified(it) }
            deleteOnExit()
        }

    @Test
    fun sameContentSameDigest() {
        val a = tempFile(ByteArray(1000) { it.toByte() })
        val b = tempFile(ByteArray(1000) { it.toByte() })
        val fa = ContentFingerprint.of(a)!!
        val fb = ContentFingerprint.of(b)!!
        // 不同 mtime 的同内容文件：摘要一致（mtime 属于指纹的一部分，由 matches 结合比较）
        assertEquals(fa.headSha256, fb.headSha256)
        assertEquals(fa.tailSha256, fb.tailSha256)
        assertTrue(fa.matches(fa))
    }

    @Test
    fun headChangeDetected() {
        val bytes = ByteArray(200_000) { 0x41 } // 200KB
        val f = tempFile(bytes)
        val before = ContentFingerprint.of(f)!!
        bytes[10] = 0x42 // 头部 64KB 内变化
        f.writeBytes(bytes)
        val after = ContentFingerprint.of(f)!!
        assertFalse(before.matches(after))
        assertTrue(before.headSha256 != after.headSha256)
    }

    @Test
    fun tailChangeDetected() {
        // 300KB：尾部变化落在最后 64KB 窗口（§6.4.3：日志追加等场景）
        val bytes = ByteArray(300_000) { 0x41 }
        val f = tempFile(bytes)
        val before = ContentFingerprint.of(f)!!
        bytes[299_000] = 0x42
        f.writeBytes(bytes)
        val after = ContentFingerprint.of(f)!!
        assertFalse(before.matches(after))
        assertTrue(before.tailSha256 != after.tailSha256)
    }

    @Test
    fun sizeAndMtimeSensitivity() {
        val f = tempFile(ByteArray(10) { 1 })
        val before = ContentFingerprint.of(f)!!
        // 追加内容（size 变化）
        f.appendBytes(ByteArray(5))
        val afterAppend = ContentFingerprint.of(f)!!
        assertFalse(before.matches(afterAppend))
        // 仅 mtime 变化（touch，内容不变）
        val f2 = tempFile(ByteArray(10) { 1 }, mtime = 1_000_000)
        val fp2 = ContentFingerprint.of(f2)!!
        f2.setLastModified(2_000_000)
        val fp2b = ContentFingerprint.of(f2)!!
        assertFalse(fp2.matches(fp2b))
    }

    @Test
    fun smallFileHeadEqualsTail() {
        val f = tempFile(ByteArray(32) { 7 })
        val fp = ContentFingerprint.of(f)!!
        assertEquals(fp.headSha256, fp.tailSha256)
    }

    @Test
    fun emptyFileAndMissingFile() {
        val empty = tempFile(ByteArray(0))
        val fp = ContentFingerprint.of(empty)!!
        assertEquals(0L, fp.size)
        assertEquals("", fp.headSha256)
        assertTrue(fp.matches(fp))
        assertNull(ContentFingerprint.of(File("/nonexistent/path/x.bin")))
    }
}
