package com.dshbox.remote.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载文件名解析测试：filename*（RFC 5987）优先、filename 回退、URL 末段兜底、
 * 路径穿越清洗、超长截断与重名追加 ` (n)`。
 */
class DownloadFileNameTest {

    private fun headersOf(vararg pairs: Pair<String, String>): List<ProxyHeader> =
        pairs.map { ProxyHeader(it.first, it.second) }

    private fun resolve(vararg pairs: Pair<String, String>, url: String = "http://up/file"): String =
        DownloadFileName.resolve(headersOf(*pairs), url, fallbackName = "download-42")

    @Test
    fun filenameStarUtf8TakesPriority() {
        val name = resolve(
            "Content-Disposition" to
                "attachment; filename=\"fallback.bin\"; filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf",
        )
        assertEquals("报告.pdf", name)
    }

    @Test
    fun filenamePlainIsFallbackWhenNoStar() {
        assertEquals(
            "report.pdf",
            resolve("Content-Disposition" to "attachment; filename=\"report.pdf\""),
        )
        assertEquals(
            "report.pdf",
            resolve("Content-Disposition" to "attachment; filename=report.pdf"),
        )
    }

    @Test
    fun filenameWithoutDispositionGoesToUrlLastSegment() {
        assertEquals("archive.zip", resolve(url = "https://up.example.com/a/archive.zip"))
        // query 不参与候选名
        assertEquals("archive.zip", resolve(url = "https://up.example.com/a/archive.zip?token=x"))
        // 百分号编码的末段解码
        assertEquals("报 告.pdf", resolve(url = "https://up.example.com/%E6%8A%A5%20%E5%91%8A.pdf"))
    }

    @Test
    fun signedUrlResponseContentDispositionCarriesOriginalName() {
        // COS/OSS 预签名下载把原始文件名放在查询参数里：优先于哈希末段
        val url = "https://smhwb-gz-002.data-tencentsmh.com/a/9f8e7d6c" +
            "?response-content-disposition=attachment%3B%20filename%3D%22%E6%8A%A5%E5%91%8A.pdf%22" +
            "&q-signature=x"
        assertEquals("报告.pdf", resolve(url = url))
        // filename* 同样支持
        val starUrl = "https://up.example.com/abc" +
            "?response-content-disposition=attachment%3B%20filename%2A%3DUTF-8%27%27report.pdf"
        assertEquals("report.pdf", resolve(url = starUrl))
    }

    @Test
    fun headerDispositionWinsOverSignedUrlQuery() {
        val url = "https://up.example.com/abc?response-content-disposition=attachment%3B%20filename%3D%22query.pdf%22"
        assertEquals(
            "header.pdf",
            resolve("Content-Disposition" to "attachment; filename=\"header.pdf\"", url = url),
        )
    }

    @Test
    fun pathTraversalSegmentsAreNeutralized() {
        // ../、绝对路径与 Windows 盘符分隔全部被清洗为普通字符：文件名最终在
        // File(downloadDir, name) 下创建，只要分隔符不存在就无从穿越。
        val slash = resolve(
            "Content-Disposition" to "attachment; filename=\"../../etc/passwd\"",
        )
        assertFalse(slash.contains('/'))
        assertEquals(".._.._etc_passwd", slash)
        val backslash = resolve(
            "Content-Disposition" to "attachment; filename=\"..\\\\windows\\\\evil.exe\"",
        )
        assertFalse(backslash.contains('\\'))
        assertEquals(".._windows_evil.exe", backslash)
    }

    @Test
    fun overlongNamesAreTruncatedByUtf8BytesKeepingExtension() {
        // ASCII 长名：按字节截到 200 以内，扩展名保留
        val long = "x".repeat(250) + ".pdf"
        val name = resolve("Content-Disposition" to "attachment; filename=\"$long\"")
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= DownloadFileName.MAX_BYTE_LENGTH)
        assertTrue(name.endsWith(".pdf"))
    }

    @Test
    fun cjkNamesAreTruncatedByBytesNotCharacters() {
        // 100 个汉字 = 300 字节：按字符数看很短，落盘却会越过文件系统 255
        // 字节上限——必须按 UTF-8 字节截断（上限 200），且文件真的能创建
        val cjk = "报".repeat(100) + ".pdf"
        val name = resolve("Content-Disposition" to "attachment; filename=\"$cjk\"")
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= DownloadFileName.MAX_BYTE_LENGTH)
        assertTrue(name.endsWith(".pdf"))
        assertTrue(name.startsWith("报"))
        val file = java.io.File.createTempFile("name-limit", null).parentFile
        val created = java.io.File(file, name)
        assertTrue(created.createNewFile())
        created.delete()
    }

    @Test
    fun surrogatePairsAreNotSplitByByteTruncation() {
        // emoji 是代理对（一个 code point，4 字节）：截断不得切出半个代理对
        val emoji = "\uD83D\uDE00".repeat(80) + ".bin"
        val name = resolve("Content-Disposition" to "attachment; filename=\"$emoji\"")
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= DownloadFileName.MAX_BYTE_LENGTH)
        // UTF-8 往返一致 = 无半截代理对（半个代理对编不出合法字节序）
        assertEquals(name, String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertTrue(name.endsWith(".bin"))
    }

    @Test
    fun indexedCandidateStaysWithinByteLimitAfterSuffix() {
        // 重名后缀追加在长名上可能越过字节上限：候选名内部按同一字节上限重截
        val name = "报".repeat(90) + ".pdf"
        val candidate = DownloadFileName.indexedCandidate(name, 1)
        assertTrue(candidate.toByteArray(Charsets.UTF_8).size <= DownloadFileName.MAX_BYTE_LENGTH)
        assertTrue(candidate.endsWith(".pdf"))
        assertTrue(candidate.contains(" (1)"))
        // unique() 走同一口径
        val uniqueSecond = DownloadFileName.unique(name) { it == name }
        assertTrue(uniqueSecond.toByteArray(Charsets.UTF_8).size <= DownloadFileName.MAX_BYTE_LENGTH)
    }

    @Test
    fun emptyNamesFallBackToSyntheticName() {
        // 头里没有任何可用名、URL 也没有末段（根路径）→ 兜底名
        assertEquals("download-42", resolve("Content-Length" to "3", url = "https://up.example.com/"))
    }

    @Test
    fun duplicateNamesGetIndexedSuffixBeforeExtension() {
        val seen = mutableSetOf<String>()
        val exists: (String) -> Boolean = { it in seen }
        val first = DownloadFileName.unique("report.pdf", exists)
        seen.add(first)
        val second = DownloadFileName.unique("report.pdf", exists)
        seen.add(second)
        val third = DownloadFileName.unique("report.pdf", exists)
        assertEquals("report.pdf", first)
        assertEquals("report (1).pdf", second)
        assertEquals("report (2).pdf", third)
    }

    @Test
    fun duplicateNamesWithoutExtensionAppendAtEnd() {
        val exists: (String) -> Boolean = { it == "README" }
        assertEquals("README (1)", DownloadFileName.unique("README", exists))
    }

    @Test
    fun uniqueKeepsFreshNameUntouched() {
        assertEquals("fresh.bin", DownloadFileName.unique("fresh.bin") { false })
    }

    @Test
    fun controlCharactersAreCleaned() {
        val name = resolve("Content-Disposition" to "attachment; filename=\"a\u0000b\u0007c.bin\"")
        assertEquals("a_b_c.bin", name)
    }
}
