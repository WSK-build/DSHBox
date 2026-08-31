package com.dshbox.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.util.zip.ZipException

/**
 * 1.1.0 (M11): the crash-to-message mapping used by the offline import flows.
 * The exception shapes below are the ones ACTUALLY produced by java.util.zip on
 * corrupted archives, verified empirically on JBR 21 (truncated / CRC-corrupted /
 * encrypted zips); see MODIFICATION_LOG.md M11.
 */
class ArchiveErrorsTest {

    @Test
    fun truncatedArchiveMapsToIncompleteMessage() {
        // 实测：截断 zip 在读取条目数据时抛 EOFException("Unexpected end of ZLIB input stream")。
        val msg = ArchiveErrors.describe(EOFException("Unexpected end of ZLIB input stream"))
        assertEquals("包不完整或已截断（多为下载/传输中断所致）", msg)
    }

    @Test
    fun encryptedZipMapsToEncryptionHint() {
        // 实测：加密 zip 抛 ZipException("encrypted ZIP entry not supported")。
        val msg = ArchiveErrors.describe(ZipException("encrypted ZIP entry not supported"))
        assertEquals("不支持加密的 zip 包，请解密后重新打包", msg)
    }

    @Test
    fun crcCorruptionMapsToCorruptedMessage() {
        // 实测：压缩数据损坏在 closeEntry 的 CRC 校验处抛 ZipException("invalid entry CRC ...")。
        val msg = ArchiveErrors.describe(ZipException("invalid entry CRC (expected 0x6c847f2b but got 0xed22312a)"))
        assertTrue(msg, msg.startsWith("zip 包损坏："))
        assertTrue(msg, msg.contains("invalid entry CRC"))
    }

    @Test
    fun fileOpExceptionMessageIsPassedThrough() {
        // safeResolve 的路径穿越/越界拦截消息本身已面向用户。
        val msg = ArchiveErrors.describe(FileOpException("压缩包含非法路径，已拦截：../evil"))
        assertEquals("压缩包含非法路径，已拦截：../evil", msg)
    }

    @Test
    fun plainIoErrorFallsBackToMessage() {
        // 磁盘满等复制期 IOException 直接透传 message。
        val msg = ArchiveErrors.describe(IOException("No space left on device"))
        assertEquals("No space left on device", msg)
    }

    @Test
    fun messagelessThrowableFallsBackToClassName() {
        assertEquals("IllegalStateException", ArchiveErrors.describe(IllegalStateException()))
    }
}
