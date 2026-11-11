package com.dshbox.remote.proxy

import java.net.URI

/**
 * 下载文件名解析（纯函数，JVM 可测）。
 *
 * 优先级：`Content-Disposition` 的 `filename*`（RFC 5987，百分号解码，charset
 * 缺省 UTF-8）→ `filename`（剥引号与转义）→ 签名 URL 查询参数
 * `response-content-disposition` 的同名参数 → URL 末段（去 query/fragment、
 * 百分号解码）→ 兜底名。解析结果一律过 [sanitize]：路径分隔符与控制字符
 * 换成下划线（`../`、绝对路径、Windows 盘符分隔全部失效），再限长。
 */
internal object DownloadFileName {

    /**
     * 文件名的 UTF-8 字节上限（保留扩展名截断）：文件系统常见单名上限 255
     * 字节，为 `.part` 临时后缀与 ` (n)` 重名后缀预留余量后取 200。按字符数
     * 截断不可行——CJK 每字 3 字节，100 个字已 300 字节，落盘会失败。
     */
    const val MAX_BYTE_LENGTH = 200

    /**
     * 从响应头与请求 URL 解析下载文件名。
     *
     * [fallbackName] 供测试注入；生产调用取 `download-<时间戳>`。
     */
    fun resolve(
        responseHeaders: List<ProxyHeader>,
        url: String,
        fallbackName: String = "download-${System.currentTimeMillis()}",
    ): String {
        val disposition = responseHeaders.firstOrNull {
            it.name.equals("Content-Disposition", ignoreCase = true)
        }?.value.orEmpty()
        sanitize(filenameStar(disposition))
            .takeIf { it.isNotEmpty() }
            ?.let { return truncateToByteLimit(it) }
        sanitize(filenamePlain(disposition))
            .takeIf { it.isNotEmpty() }
            ?.let { return truncateToByteLimit(it) }
        // 对象存储签名地址常把文件名放在 response-content-disposition 查询
        // 参数里（COS/OSS 预签名下载），响应头缺失时按同一语法解析
        val queryDisposition = responseContentDisposition(url)
        sanitize(filenameStar(queryDisposition))
            .takeIf { it.isNotEmpty() }
            ?.let { return truncateToByteLimit(it) }
        sanitize(filenamePlain(queryDisposition))
            .takeIf { it.isNotEmpty() }
            ?.let { return truncateToByteLimit(it) }
        sanitize(urlLastSegment(url))
            .takeIf { it.isNotEmpty() }
            ?.let { return truncateToByteLimit(it) }
        return truncateToByteLimit(sanitize(fallbackName))
    }

    /**
     * 重名消解：与既有文件同名时在扩展名前追加 ` (n)`（`report.pdf` →
     * `report (1).pdf`；无扩展名直接尾缀）。[exists] 由调用方注入（磁盘查重
     * 或测试桩），逐个试探保证结果不与任何既有名冲突。
     */
    fun unique(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        var index = 1
        while (true) {
            val candidate = indexedCandidate(name, index)
            if (!exists(candidate)) return candidate
            index += 1
        }
    }

    /**
     * 第 [index] 个重名候选：在扩展名前插入 ` (n)`。主干先按字节预算截断为
     * 后缀与扩展名让位——若先把后缀拼进全名再截尾，` (n)` 会被整段截掉，
     * 候选名退化为同名，重名消解永不收敛。候选名同样受字节上限约束。
     */
    fun indexedCandidate(name: String, index: Int): String {
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
        val base = if (extension.isNotEmpty()) name.substring(0, dot) else name
        val suffix = " ($index)"
        val budget = MAX_BYTE_LENGTH -
            suffix.toByteArray(Charsets.UTF_8).size -
            extension.toByteArray(Charsets.UTF_8).size
        return truncateByBytes(base, budget) + suffix + extension
    }

    /**
     * 路径分隔符与控制字符清洗：`/`、`\` 与 C0 控制段一律换下划线——`../`
     * 与绝对路径就此失去穿越能力；再去首尾空白与结尾的点（Windows 卷上
     * 结尾点是非法名）。空名返回空串，由调用方走下一级回退。
     */
    internal fun sanitize(raw: String): String {
        val cleaned = buildString {
            for (ch in raw) {
                if (ch == '/' || ch == '\\' || ch.code < 0x20 || ch.code == 0x7f) append('_') else append(ch)
            }
        }.trim()
        return cleaned.trimEnd('.').trim()
    }

    /** `filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf` 的 RFC 5987 取值与解码。 */
    private fun filenameStar(disposition: String): String {
        val raw = parameter(disposition, "filename*") ?: return ""
        val parts = raw.split('\'', limit = 3)
        val encoded = parts.lastOrNull().orEmpty()
        val charset = parts.getOrNull(0)?.ifBlank { "UTF-8" } ?: "UTF-8"
        return runCatching {
            String(percentDecode(encoded), charset(charset))
        }.getOrDefault("")
    }

    /** `filename="report.pdf"` / `filename=report.pdf` 的取值（剥引号与反斜杠转义）。 */
    private fun filenamePlain(disposition: String): String {
        val raw = parameter(disposition, "filename") ?: return ""
        val quoted = raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2
        val value = if (quoted) raw.substring(1, raw.length - 1) else raw
        return value.replace("\\\"", "\"").replace("\\\\", "\\")
    }

    /** Content-Disposition 里不分大小写取首个指定参数的原始值。 */
    private fun parameter(disposition: String, name: String): String? {
        var index = 0
        while (true) {
            val found = disposition.indexOf(name, index, ignoreCase = true)
            if (found < 0) return null
            // 参数名必须是完整 token：前一位不能是字母/连字符（防 `xfilename` 误命中）
            if (found > 0 && (disposition[found - 1].isLetterOrDigit() || disposition[found - 1] == '-')) {
                index = found + name.length
                continue
            }
            val after = disposition.indexOf('=', found + name.length)
            if (after < 0) return null
            return disposition.substring(after + 1).substringBefore(';').trim()
        }
    }

    /** URL 查询参数 `response-content-disposition`（预签名下载的原始文件名）。 */
    private fun responseContentDisposition(url: String): String {
        val rawQuery = runCatching { URI(url).rawQuery }.getOrNull() ?: return ""
        val value = rawQuery.split('&').firstOrNull {
            it.substringBefore('=').equals("response-content-disposition", ignoreCase = true)
        }?.substringAfter('=', "") ?: return ""
        return runCatching {
            String(percentDecode(value), Charsets.UTF_8)
        }.getOrDefault("")
    }

    /** URL 末段（去 query/fragment 后作候选名；URI 解析成功时 path 已解码）。 */
    private fun urlLastSegment(url: String): String {
        val parsed = runCatching { URI(url) }.getOrNull()
        if (parsed != null) return parsed.path.orEmpty().substringAfterLast('/')
        return runCatching {
            String(percentDecode(url.substringBefore('?').substringAfterLast('/')), Charsets.UTF_8)
        }.getOrDefault("")
    }

    /** 手写百分号解码（不用 URLDecoder：`+` 在 RFC 5987 里不是空格）。 */
    private fun percentDecode(raw: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(raw.length)
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            if (ch == '%' && index + 2 < raw.length) {
                val hex = raw.substring(index + 1, index + 3)
                val byte = hex.toIntOrNull(16)
                if (byte != null) {
                    out.write(byte)
                    index += 3
                    continue
                }
            }
            out.write(ch.code and 0xFF)
            index += 1
        }
        return out.toByteArray()
    }

    /** 超长截断（按 UTF-8 字节）：有扩展名时保扩展名截主干，无扩展名直接截尾。 */
    private fun truncateToByteLimit(name: String): String {
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_BYTE_LENGTH) return name
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
        val base = if (extension.isEmpty()) name else name.substring(0, dot)
        val budget = MAX_BYTE_LENGTH - extension.toByteArray(Charsets.UTF_8).size
        return truncateByBytes(base, budget) + extension
    }

    /**
     * 按字节预算截断文本：按 code point 逐个消费（代理对整体保留，不切成
     * 半个 emoji），装不下下一个字符即止。
     */
    private fun truncateByBytes(text: String, budget: Int): String {
        val out = StringBuilder()
        var used = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val size = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (used + size > budget) break
            out.appendCodePoint(codePoint)
            used += size
            index += charCount
        }
        return out.toString()
    }
}
