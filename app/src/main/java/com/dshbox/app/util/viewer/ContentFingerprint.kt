package com.dshbox.app.util.viewer

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 内容指纹（1.2.0 §6.4.3，纯 JVM）：**size + lastModified + 首尾各 64KB 的 SHA-256 摘要**。
 *
 * 外部变更检测的核心：文件的 lastModified 秒级精度不足（编辑器快速连续保存时 mtime
 * 可能不变），由首尾内容摘要补齐——外部修改文件头部或尾部（最常见场景：日志追加、
 * 配置改写）都能检出。
 */
data class ContentFingerprint(
    val size: Long,
    val lastModified: Long,
    /** 头部窗口（≤64KB）的 SHA-256 十六进制摘要；空文件为空串。 */
    val headSha256: String,
    /** 尾部窗口（≤64KB）的 SHA-256 摘要；文件 ≤ [ContentFingerprint.WINDOW] 字节时与 head 相同。 */
    val tailSha256: String,
) {
    /** 与 [other] 严格比对（size/mtime/双摘要任一不同即判定「已被外部修改」）。 */
    fun matches(other: ContentFingerprint?): Boolean {
        if (other == null) return false
        return size == other.size &&
            lastModified == other.lastModified &&
            headSha256 == other.headSha256 &&
            tailSha256 == other.tailSha256
    }

    companion object {

        /** 首/尾摘要窗口：64KB（§6.4.3）。 */
        const val WINDOW = 64 * 1024

        /** 计算文件指纹（IO，调用方负责线程）。文件不可读时返回 null（调用方按错误态处理）。 */
        fun of(file: File): ContentFingerprint? = runCatching {
            if (!file.isFile) return null
            val size = file.length()
            val mtime = file.lastModified()
            if (size <= 0L) return ContentFingerprint(0L, mtime, "", "")
            RandomAccessFile(file, "r").use { raf ->
                val headLen = minOf(WINDOW.toLong(), size).toInt()
                val head = ByteArray(headLen)
                raf.readFully(head)
                val headSha = sha256Hex(head)
                val tailSha = if (size <= WINDOW) {
                    headSha
                } else {
                    raf.seek(size - WINDOW)
                    val tail = ByteArray(WINDOW)
                    raf.readFully(tail)
                    sha256Hex(tail)
                }
                ContentFingerprint(size, mtime, headSha, tailSha)
            }
        }.getOrNull()

        fun sha256Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(data)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
            }
            return sb.toString()
        }
    }
}
