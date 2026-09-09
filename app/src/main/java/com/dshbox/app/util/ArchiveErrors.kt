package com.dshbox.app.util

import com.dshbox.app.R
import com.dshbox.app.common.UiText

/**
 * 把解压/复制所选包时的异常翻译为用户可读的失败原因（1.1.0，M11）。
 *
 * 文案依据 JVM 实测（JBR 21，复刻 ZipInputStream 读取循环）：
 * - 截断 zip（传输中断最常见）      -> java.io.EOFException: Unexpected end of ZLIB input stream
 * - 压缩数据损坏                    -> java.util.zip.ZipException: invalid entry CRC (…)
 * - 加密 zip                        -> java.util.zip.ZipException: encrypted ZIP entry not supported
 * - 路径穿越等业务拦截              -> FileOpException（消息本身已面向用户，直接透传）
 * - 其余（磁盘满、提供器中断等）    -> 透传 message / 异常类名
 */
object ArchiveErrors {

    fun describe(t: Throwable): UiText {
        if (t is FileOpException) return t.uiText ?: (t.message?.let { UiText.raw(it) } ?: UiText.Res(R.string.archiveerr_default))
        if (t is java.io.EOFException) return UiText.Res(R.string.archiveerr_truncated)
        if (t is java.util.zip.ZipException) {
            val msg = t.message.orEmpty()
            if (msg.contains("encrypt", ignoreCase = true)) return UiText.Res(R.string.archiveerr_encrypted)
            return UiText.Res(R.string.archiveerr_zip_corrupted, listOf(t.message ?: ""))
        }
        return UiText.raw(t.message ?: t.javaClass.simpleName)
    }
}
