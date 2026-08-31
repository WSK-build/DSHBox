package com.dshbox.app.util

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

    fun describe(t: Throwable): String {
        if (t is FileOpException) return t.message ?: "压缩包无法解压"
        if (t is java.io.EOFException) return "包不完整或已截断（多为下载/传输中断所致）"
        if (t is java.util.zip.ZipException) {
            val msg = t.message.orEmpty()
            return when {
                msg.contains("encrypt", ignoreCase = true) -> "不支持加密的 zip 包，请解密后重新打包"
                else -> "zip 包损坏：${t.message ?: "内容无法解析"}"
            }
        }
        return t.message ?: t.javaClass.simpleName
    }
}
