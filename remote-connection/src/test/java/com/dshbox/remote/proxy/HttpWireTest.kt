package com.dshbox.remote.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

/**
 * 响应体落盘基座测试：解块与解压的顺序（先在原始流上解 chunked，再包解压
 * 流）、Content-Length 只对 identity 编码可信、不支持编码显式失败与解压后
 * 总量上限（小上限注入）。
 */
class HttpWireTest {

    private fun headOf(vararg pairs: Pair<String, String>, startLine: String = "HTTP/1.1 200 OK"): HttpWire.HttpHead =
        HttpWire.HttpHead(startLine, pairs.map { ProxyHeader(it.first, it.second) }.toMutableList())

    private fun gzip(raw: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(raw) }
    }.toByteArray()

    private fun deflate(raw: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        DeflaterOutputStream(out).use { it.write(raw) }
    }.toByteArray()

    /** 手工 chunked 帧封装（与线上格式一致：十六进制长度 + CRLF + 块 + CRLF）。 */
    private fun chunked(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (chunk in chunks) {
            out.write(Integer.toHexString(chunk.size).toByteArray(Charsets.ISO_8859_1))
            out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
            out.write(chunk)
            out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        }
        out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private fun drain(head: HttpWire.HttpHead, body: ByteArray, maxBytes: Long = HttpWire.MAX_DOWNLOAD_BYTES): Pair<Long, ByteArray> {
        val output = ByteArrayOutputStream()
        val written = HttpWire.drainBody(ByteArrayInputStream(body), head, output, maxBytes)
        return written to output.toByteArray()
    }

    @Test
    fun gzipBodyWithContentLengthIsDecompressedToIntactBytes() {
        // 压缩响应的 Content-Length 描述压缩字节数：不得拿它限制解压流
        // （旧实现据此静默截断），必须解到 gzip 尾块为止
        val raw = "GZIP-PAYLOAD-0123456789".repeat(20).toByteArray()
        val compressed = gzip(raw)
        val (written, bytes) = drain(
            headOf("Content-Encoding" to "gzip", "Content-Length" to compressed.size.toString()),
            compressed,
        )
        assertEquals(raw.size.toLong(), written)
        assertArrayEquals(raw, bytes)
    }

    @Test
    fun gzipChunkedBodyIsDechunkedOnRawStreamBeforeDecompression() {
        // 顺序不可颠倒：chunk 帧字节进解压器必然失败。先解块再解压
        val raw = "CHUNKED-GZIP-PAYLOAD".repeat(7).toByteArray()
        val compressed = gzip(raw)
        val frames = chunked(
            compressed.copyOfRange(0, 30),
            compressed.copyOfRange(30, compressed.size),
        )
        val (written, bytes) = drain(
            headOf("Content-Encoding" to "gzip", "Transfer-Encoding" to "chunked"),
            frames,
        )
        assertEquals(raw.size.toLong(), written)
        assertArrayEquals(raw, bytes)
    }

    @Test
    fun deflateBodyWithContentLengthIsInflatedToIntactBytes() {
        val raw = "DEFLATE-PAYLOAD-\u00e4\u00f6\u00fc".toByteArray(Charsets.ISO_8859_1)
        val compressed = deflate(raw)
        val (written, bytes) = drain(
            headOf("Content-Encoding" to "deflate", "Content-Length" to compressed.size.toString()),
            compressed,
        )
        assertEquals(raw.size.toLong(), written)
        assertArrayEquals(raw, bytes)
    }

    @Test
    fun identityBodyHonorsContentLength() {
        // 既有行为：无编码直读按 Content-Length 限量
        val raw = "IDENTITY-BYTES".toByteArray()
        val (written, bytes) = drain(headOf("Content-Length" to "7"), raw)
        assertEquals(7L, written)
        assertArrayEquals("IDENTIT".toByteArray(), bytes)
    }

    @Test
    fun unsupportedEncodingFailsExplicitly() {
        // br/zstd 不在 java.util.zip 支持范围（不引入第三方解压依赖）：照原样
        // 落盘会把压缩字节存成原始扩展名的坏文件，必须显式报错走失败页
        val exception = assertThrows(IOException::class.java) {
            drain(headOf("Content-Encoding" to "br"), "BROTLI-BYTES".toByteArray())
        }
        assertTrue(exception.message!!.contains("br"))
    }

    @Test
    fun gzipTruncatedBodyFailsInsteadOfSilentPartialSave() {
        // gzip 尾块缺失 = 上游截断：解压流必须报错，而不是把半包当好文件落盘
        val compressed = gzip("TRUNCATED-PAYLOAD".repeat(5).toByteArray())
        val truncated = compressed.copyOfRange(0, compressed.size - 8)
        assertThrows(IOException::class.java) {
            drain(headOf("Content-Encoding" to "gzip"), truncated)
        }
    }

    @Test
    fun bodyBeyondInjectedLimitFails() {
        // 总量上限可注入：identity 无 CL 读到 EOF 的路径也要受限
        val raw = "x".repeat(100).toByteArray()
        assertThrows(IOException::class.java) {
            drain(headOf("Content-Type" to "application/octet-stream"), raw, maxBytes = 10)
        }
    }

    @Test
    fun decompressedBodyBeyondInjectedLimitFails() {
        // 解压炸弹口径：解压后总量超限即失败（压缩体本身很小）
        val raw = "y".repeat(10_000).toByteArray()
        assertThrows(IOException::class.java) {
            drain(headOf("Content-Encoding" to "gzip"), gzip(raw), maxBytes = 1024)
        }
    }

    @Test
    fun bodyAtLimitExactlyPasses() {
        val raw = "z".repeat(64).toByteArray()
        val (written, bytes) = drain(headOf("Content-Type" to "application/octet-stream"), raw, maxBytes = 64)
        assertEquals(64L, written)
        assertArrayEquals(raw, bytes)
    }

    // ---- 请求体上限（64MB，Content-Length 与 chunked 同口径）与 readHead 缓冲 ----

    @Test
    fun contentLengthBodyBeyondLimitThrowsTooLarge() {
        // 声明超大 Content-Length：超限在读之前就被判出（不真的读体）
        val head = headOf("Content-Length" to "90000000")
        val exception = assertThrows(HttpWire.RequestBodyTooLargeException::class.java) {
            HttpWire.readRequestBody(ByteArrayInputStream(ByteArray(0)), head)
        }
        assertTrue(exception.message!!.contains("size limit"))
    }

    @Test
    fun chunkedBodyBeyondLimitThrowsTooLarge() {
        // 首块声明超限大小：解块计数超上界立即抛错（块体不完备也无妨）。
        val head = headOf("Transfer-Encoding" to "chunked")
        val frames = ByteArrayOutputStream().apply {
            write(chunkedFrame(64L * 1024 * 1024 + 1, ByteArray(8)))
            write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        }.toByteArray()
        assertThrows(HttpWire.RequestBodyTooLargeException::class.java) {
            HttpWire.readRequestBody(ByteArrayInputStream(frames), head)
        }
    }

    @Test
    fun chunkedBodyAccumulatedAcrossChunksThrowsTooLarge() {
        // 多块累计超过上界：累计口径。第一块给足真体（小且真进解块器），
        // 第二块声明超额即抛错（累计含第一块）。
        val head = headOf("Transfer-Encoding" to "chunked")
        val frames = ByteArrayOutputStream().apply {
            write(chunkedFrame(192, ByteArray(192)))
            write(chunkedFrame(64L * 1024 * 1024 + 1, ByteArray(16)))
            write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        }.toByteArray()
        assertThrows(HttpWire.RequestBodyTooLargeException::class.java) {
            HttpWire.readRequestBody(ByteArrayInputStream(frames), head)
        }
    }

    @Test
    fun readHeadAcceptsLargeHeaderLinearly() {
        // 大 Cookie 头（线性缓冲实现下的常规大头部解析，语义不变）
        val cookie = "session=" + "a".repeat(64 * 1024)
        val headText = "GET / HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n"
        val head = HttpWire.readHead(ByteArrayInputStream(headText.toByteArray(Charsets.ISO_8859_1)))
        assertEquals(cookie, head!!.firstHeader("Cookie"))
        assertEquals("GET / HTTP/1.1", head.startLine)
    }

    @Test
    fun readHeadToleratesBareLfTerminator() {
        val headText = "GET / HTTP/1.1\nHost: x\n\n"
        val head = HttpWire.readHead(ByteArrayInputStream(headText.toByteArray(Charsets.ISO_8859_1)))
        assertEquals("GET / HTTP/1.1", head!!.startLine)
    }

    /**
     * 手工封装单条 chunked 块帧（无终止块，不带继承 helper 的语义）：`<hex size>\r\n`
     * + [followingBytes] + `\r\n`。对超限场景：块体不必给满——计数超过上界先抛错。
     */
    private fun chunkedFrame(declaredChunkBytes: Long, followingBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(declaredChunkBytes.toString(16).toByteArray(Charsets.ISO_8859_1))
        out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write(followingBytes)
        out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        for (index in expected.indices) {
            assertEquals("byte @$index", expected[index].toInt(), actual[index].toInt())
        }
    }
}
