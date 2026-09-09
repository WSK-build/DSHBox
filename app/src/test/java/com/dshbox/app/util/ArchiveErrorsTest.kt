package com.dshbox.app.util

import com.dshbox.app.R
import com.dshbox.app.common.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.util.zip.ZipException

/**
 * the crash-to-message mapping used by the offline import flows.
 * The exception shapes below are the ones ACTUALLY produced by java.util.zip on
 * corrupted archives, verified empirically on JBR 21 (truncated / CRC-corrupted /
 * encrypted zips); see
 */
class ArchiveErrorsTest {

    @Test
    fun truncatedArchiveMapsToIncompleteMessage() {
        // 实测：截断 zip 在读取条目数据时抛 EOFException("Unexpected end of ZLIB input stream")。
        val result = ArchiveErrors.describe(EOFException("Unexpected end of ZLIB input stream"))
        assertTrue(result is UiText.Res && result.id == R.string.archiveerr_truncated)
    }

    @Test
    fun encryptedZipMapsToEncryptionHint() {
        // 实测：加密 zip 抛 ZipException("encrypted ZIP entry not supported")。
        val result = ArchiveErrors.describe(ZipException("encrypted ZIP entry not supported"))
        assertTrue(result is UiText.Res && result.id == R.string.archiveerr_encrypted)
    }

    @Test
    fun crcCorruptionMapsToCorruptedMessage() {
        // 实测：压缩数据损坏在 closeEntry 的 CRC 校验处抛 ZipException("invalid entry CRC ...")。
        val result = ArchiveErrors.describe(ZipException("invalid entry CRC (expected 0x6c847f2b but got 0xed22312a)"))
        assertTrue(result is UiText.Res && result.id == R.string.archiveerr_zip_corrupted)
        val args = (result as UiText.Res).args
        assertTrue(args.first().toString().contains("invalid entry CRC"))
    }

    @Test
    fun fileOpExceptionMessageIsPassedThrough() {
        // safeResolve 的路径穿越/越界拦截消息本身已面向用户。
        val result = ArchiveErrors.describe(FileOpException("压缩包含非法路径，已拦截：../evil"))
        assertTrue(result is UiText.Raw && result.text == "压缩包含非法路径，已拦截：../evil")
    }

    @Test
    fun plainIoErrorFallsBackToMessage() {
        // 磁盘满等复制期 IOException 直接透传 message。
        val result = ArchiveErrors.describe(IOException("No space left on device"))
        assertTrue(result is UiText.Raw && result.text == "No space left on device")
    }

    @Test
    fun messagelessThrowableFallsBackToClassName() {
        val result = ArchiveErrors.describe(IllegalStateException())
        assertTrue(result is UiText.Raw && result.text == "IllegalStateException")
    }
}
