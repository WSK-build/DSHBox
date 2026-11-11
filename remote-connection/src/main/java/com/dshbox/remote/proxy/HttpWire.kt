package com.dshbox.remote.proxy

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * HTTP 报文头与请求体的线级读写：代理管线的解析基座。
 *
 * 头部按 `CRLFCRLF`（容忍裸 `LF`）截取；请求体支持 Content-Length 与
 * chunked（解块后以 Content-Length 转发上游）。响应体有两条路径：普通响应
 * 原样透传给渲染器（[relay]，不做解析）；下载分支经 [drainBody] 落盘——
 * 先在原始流上解 chunked 帧，再按 Content-Encoding 包解压（gzip/deflate），
 * 其余编码显式报错，解压后总量受 [MAX_DOWNLOAD_BYTES] 约束。
 */
object HttpWire {

    private const val HEAD_LIMIT_BYTES = 128 * 1024
    private const val BODY_LIMIT_BYTES = 64L * 1024 * 1024

    /**
     * 请求体超过尺寸上限：Content-Length 与 chunked 两条路径共用同一上限
     * （[BODY_LIMIT_BYTES]）。调用方按需回 413 并关闭连接。
     */
    class RequestBodyTooLargeException(message: String) : IOException(message)

    /**
     * 单个下载落盘的解压后总量上限：防御解压炸弹与上游虚报把磁盘吃满。
     * 只作用于下载分支（[drainBody]），超限抛错走既有失败页并删半包。
     */
    const val MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024

    /** 一个已解析的报文头：起始行 + 保序头列表。 */
    data class HttpHead(var startLine: String, val headers: MutableList<ProxyHeader>) {

        /**
         * 请求行的目标改写（注入器与改写器产出最终转发目标后写回）：
         * 方法与协议版本保持不变，仅替换 origin-form 目标。
         */
        fun rewriteRequestTarget(newTarget: String) {
            val parts = startLine.split(' ')
            startLine = buildString {
                append(parts.getOrElse(0) { "GET" }).append(' ').append(newTarget).append(' ')
                append(parts.getOrElse(2) { "HTTP/1.1" })
            }
        }

        fun firstHeader(name: String): String? =
            headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

        fun headerValues(name: String): List<String> =
            headers.filter { it.name.equals(name, ignoreCase = true) }.map { it.value }

        fun setHeader(name: String, value: String) {
            headers.removeAll { it.name.equals(name, ignoreCase = true) }
            headers.add(ProxyHeader(name, value))
        }

        fun removeHeaders(name: String) {
            headers.removeAll { it.name.equals(name, ignoreCase = true) }
        }

        /** 序列化为线上字节（不含 body）。 */
        fun serialize(): ByteArray = buildString {
            append(startLine).append("\r\n")
            for (header in headers) {
                append(header.name).append(": ").append(header.value).append("\r\n")
            }
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * 读取一个报文头；流关闭/格式错误返回 null。
     *
     * 缓冲按**可增长字节数组**线性累积（每字节一次写入、容量倍增摊销），
     * 不再每次 `toByteArray()` 全量拷贝——旧实现是逐字节 O(n²) 的拷贝热点，
     * 大头部（长 Cookie/长授权头）下显著放慢解析。
     */
    fun readHead(input: InputStream): HttpHead? {
        var buffer = ByteArray(256)
        var size = 0
        while (true) {
            val current = input.read()
            if (current < 0) return null
            if (size >= HEAD_LIMIT_BYTES) throw IOException("HTTP head exceeds size limit")
            if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            buffer[size++] = current.toByte()
            if (endsWithHeadTerminator(buffer, size)) break
        }
        return parseHead(buffer.copyOf(size)) ?: return null
    }

    private fun endsWithHeadTerminator(bytes: ByteArray, n: Int): Boolean {
        if (n >= 4 &&
            bytes[n - 4] == '\r'.code.toByte() && bytes[n - 3] == '\n'.code.toByte() &&
            bytes[n - 2] == '\r'.code.toByte() && bytes[n - 1] == '\n'.code.toByte()
        ) {
            return true
        }
        return n >= 2 && bytes[n - 2] == '\n'.code.toByte() && bytes[n - 1] == '\n'.code.toByte()
    }

    internal fun parseHead(bytes: ByteArray): HttpHead? {
        val text = String(bytes, Charsets.ISO_8859_1)
        val normalized = text.replace("\r\n", "\n")
        val lines = normalized.split('\n').dropLastWhile { it.isEmpty() }
        if (lines.size < 2) return null
        val startLine = lines[0].trim()
        if (startLine.isEmpty()) return null
        val headers = ArrayList<ProxyHeader>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers.add(ProxyHeader(line.substring(0, colon).trim(), line.substring(colon + 1).trim()))
        }
        return HttpHead(startLine, headers)
    }

    /**
     * 请求体读取结果：null 表示无 body。Content-Length 与 chunked 两条路径
     * 受同一上限（[BODY_LIMIT_BYTES]）约束，超限抛 [RequestBodyTooLargeException]
     * ——调用方可据此回 413 并收口连接（此前 Content-Length 超限只是裸异常
     * 静默断连，chunked 甚至完全无上限）。
     */
    fun readRequestBody(input: InputStream, head: HttpHead): ByteArray? {
        val contentLength = head.firstHeader("Content-Length")?.toLongOrNull()
        if (contentLength != null && contentLength > 0) {
            if (contentLength > BODY_LIMIT_BYTES) {
                throw RequestBodyTooLargeException("Request body exceeds size limit: $contentLength")
            }
            return readFully(input, contentLength)
        }
        val transferEncoding = head.firstHeader("Transfer-Encoding")
        if (transferEncoding != null && transferEncoding.contains("chunked", ignoreCase = true)) {
            return dechunk(input)
        }
        return null
    }

    fun readFully(input: InputStream, length: Long): ByteArray {
        val out = ByteArrayOutputStream(length.toInt())
        val buffer = ByteArray(16 * 1024)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("Unexpected EOF while reading body")
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    /** chunked 解块：返回去帧后的完整 body；累计超过 [BODY_LIMIT_BYTES] 抛 [RequestBodyTooLargeException]。 */
    fun dechunk(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val sizeLine = readLine(input) ?: throw IOException("Unexpected EOF in chunked body")
            val size = sizeLine.trim().substringBefore(';').toIntOrNull(16)
                ?: throw IOException("Malformed chunk size: $sizeLine")
            if (size == 0) {
                // 终止块之后可能还有 trailer 头。此处只读一行丢弃即收尾是安全
                // 的固化行为：本代理为单请求单连接模型（响应写完即关连接），
                // 剩余未读的 trailer 字节随连接一并废弃，不会被解析为下一个
                // 报文——不存在借 trailer 夹带走私报文的攻击面。
                readLine(input)
                return out.toByteArray()
            }
            total += size
            if (total > BODY_LIMIT_BYTES) {
                throw RequestBodyTooLargeException("Chunked request body exceeds size limit")
            }
            out.write(readFully(input, size.toLong()))
            readLine(input) // 块尾 CRLF
        }
    }

    /** 读取一行（至 LF）；流结束返回 null。 */
    fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1)
            if (b == '\n'.code) break
            out.write(b)
        }
        return out.toString(Charsets.ISO_8859_1).trimEnd('\r')
    }

    /** 把字节流原样搬运到输出（逐块写 + 即刷，SSE 不缓冲）。 */
    fun relay(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            output.write(buffer, 0, read)
            output.flush()
        }
    }

    /**
     * 把响应体落到 [output]：Content-Encoding 为 gzip / deflate 时边解压边写；
     * 返回落盘字节数（解压后）。[maxBytes] 为解压后总量上限（超限抛 [IOException]，
     * 由调用方走失败页并删半包），生产取 [MAX_DOWNLOAD_BYTES]，测试可注入小值。
     *
     * 下载分支专用（响应体不再透传给渲染器，需要完整落盘）；与 [readRequestBody]
     * 不同，这里不整包缓冲，边读边写以支撑大文件。处理顺序固定：
     *
     * 1. `Transfer-Encoding: chunked` 先在**原始流**上解块（[ChunkedInputStream]，
     *    流式不整包缓冲）；
     * 2. 再按 Content-Encoding 包解压流——顺序不可颠倒：chunk 帧字节进解压器
     *    必然失败；
     * 3. 长度限制只对无编码直读可信：压缩编码的 Content-Length 描述的是压缩
     *    字节数，拿它限制解压流会把大文件静默截断——有编码（或 chunked）时一律
     *    读到 EOF（解压流有 gzip 尾块校验、解块流有终止块，终止语义完整）；
     *    仅 identity 编码按 Content-Length 限量读取。
     *
     * gzip/deflate 之外的编码（如 br/zstd）显式报错——不引入第三方解压依赖，
     * 照原样落盘会把压缩字节存成原始扩展名的坏文件。
     */
    fun drainBody(
        input: InputStream,
        head: HttpHead,
        output: OutputStream,
        maxBytes: Long = MAX_DOWNLOAD_BYTES,
    ): Long {
        val chunked = head.firstHeader("Transfer-Encoding")
            ?.contains("chunked", ignoreCase = true) == true
        val encoding = head.firstHeader("Content-Encoding")?.trim()?.lowercase()
            ?.takeIf { it.isNotEmpty() && it != "identity" }
        var body: InputStream = input
        if (chunked) body = ChunkedInputStream(body)
        when (encoding) {
            null -> Unit
            "gzip" -> body = java.util.zip.GZIPInputStream(body)
            "deflate" -> body = java.util.zip.InflaterInputStream(body)
            else -> throw IOException("Unsupported content encoding for download: $encoding")
        }
        val length = if (encoding == null && !chunked) {
            head.firstHeader("Content-Length")?.toLongOrNull()
        } else {
            null
        }
        val buffered = if (output is java.io.BufferedOutputStream) output else java.io.BufferedOutputStream(output)
        val written = drainLength(body, buffered, length, maxBytes)
        buffered.flush()
        return written
    }

    private fun drainLength(input: InputStream, output: OutputStream, length: Long?, maxBytes: Long): Long {
        var total = 0L
        val buffer = ByteArray(16 * 1024)
        if (length != null) {
            if (length > maxBytes) throw IOException("Download body exceeds size limit: $length")
            var remaining = length
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("Unexpected EOF while draining body")
                output.write(buffer, 0, read)
                total += read
                remaining -= read
            }
            return total
        }
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            if (total + read > maxBytes) throw IOException("Download body exceeds size limit: > $maxBytes")
            output.write(buffer, 0, read)
            total += read
        }
    }

    /**
     * chunked 传输编码的流式解块器：包在原始流上按帧解码，读满终止块即 EOF
     * （trailer 随连接废弃，单请求单连接模型）。解块后的流可继续被解压流包装。
     */
    private class ChunkedInputStream(private val input: InputStream) : InputStream() {

        private var remainingInChunk = 0L
        private var finished = false

        override fun read(): Int {
            val single = ByteArray(1)
            val read = read(single, 0, 1)
            return if (read < 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (finished) return -1
            if (remainingInChunk == 0L && !enterNextChunk()) return -1
            val read = input.read(buffer, offset, minOf(length.toLong(), remainingInChunk).toInt())
            if (read < 0) throw IOException("Unexpected EOF in chunked body")
            remainingInChunk -= read
            if (remainingInChunk == 0L) readLine(input) // 块尾 CRLF
            return read
        }

        /** 推进到下一块；返回 false 表示已消费终止块（流结束）。 */
        private fun enterNextChunk(): Boolean {
            val sizeLine = readLine(input) ?: throw IOException("Unexpected EOF in chunked body")
            val size = sizeLine.trim().substringBefore(';').toLongOrNull(16)
                ?: throw IOException("Malformed chunk size: $sizeLine")
            if (size == 0L) {
                // 终止块之后可能还有 trailer 头，读一行丢弃即收尾：剩余字节随
                // 连接废弃，不会被解析为下一个报文（与 dechunk 同一口径）。
                readLine(input)
                finished = true
                return false
            }
            remainingInChunk = size
            return true
        }
    }

    /** 写出起始行为 404 的极简响应并收尾。 */
    fun writeNotFound(output: OutputStream) {
        val body = "Not Found".toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 404 Not Found\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }
}
