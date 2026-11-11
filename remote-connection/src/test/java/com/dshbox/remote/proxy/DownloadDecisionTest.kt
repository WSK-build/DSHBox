package com.dshbox.remote.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载判定测试：attachment 一票通过、页面/数据类型拦下、文件类型放行，
 * 以及主框架导航请求的近似判定（下载分支必须同时满足两个判定）。
 */
class DownloadDecisionTest {

    private fun headersOf(vararg pairs: Pair<String, String>): List<ProxyHeader> =
        pairs.map { ProxyHeader(it.first, it.second) }

    @Test
    fun contentDispositionAttachmentWinsEvenWithoutContentType() {
        assertTrue(
            DownloadDecision.isDownload(
                headersOf("Content-Disposition" to "attachment; filename=\"data.bin\""),
            ),
        )
    }

    @Test
    fun attachmentWinsEvenForHtmlContentType() {
        assertTrue(
            DownloadDecision.isDownload(
                headersOf(
                    "Content-Type" to "text/html; charset=utf-8",
                    "Content-Disposition" to "attachment; filename=\"page.html\"",
                ),
            ),
        )
    }

    @Test
    fun plainHtmlIsNeverDownloadedEvenWith200() {
        // 状态码不参与纯函数判定（由调用处把守 2xx）；这里验证 HTML 文档不会被误判
        assertFalse(
            DownloadDecision.isDownload(headersOf("Content-Type" to "text/html; charset=utf-8")),
        )
    }

    @Test
    fun fileTypesAreDownloaded() {
        for (type in listOf(
            "application/octet-stream",
            "application/zip",
            "application/pdf",
            "image/png",
            "image/jpeg",
            "text/csv",
        )) {
            assertTrue(
                "type=$type",
                DownloadDecision.isDownload(headersOf("Content-Type" to type)),
            )
        }
    }

    @Test
    fun pageAndDataFormatsAreRelayed() {
        for (type in listOf(
            "text/html",
            "application/xhtml+xml",
            "application/json",
            "application/ld+json",
            "application/vnd.api+json",
            "text/css",
            "text/javascript",
            "application/javascript",
            "application/x-javascript",
            "application/xml",
            "text/event-stream",
        )) {
            assertFalse(
                "type=$type",
                DownloadDecision.isDownload(headersOf("Content-Type" to type)),
            )
        }
    }

    @Test
    fun missingContentTypeWithoutAttachmentIsRelayed() {
        assertFalse(DownloadDecision.isDownload(headersOf("Content-Length" to "12")))
    }

    @Test
    fun inlineDispositionIsNotDownloadSignal() {
        assertFalse(
            DownloadDecision.isDownload(
                headersOf(
                    "Content-Type" to "text/html",
                    "Content-Disposition" to "inline; filename=\"page.html\"",
                ),
            ),
        )
    }

    @Test
    fun navigationRequestsWithFetchDestDocumentAreDetected() {
        assertTrue(
            DownloadDecision.isNavigationRequest(
                headersOf("Sec-Fetch-Dest" to "document", "Accept" to "*/*"),
            ),
        )
    }

    @Test
    fun navigationRequestsViaAcceptHtmlFallback() {
        assertTrue(
            DownloadDecision.isNavigationRequest(
                headersOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                ),
            ),
        )
    }

    @Test
    fun subresourceRequestsAreNotNavigation() {
        for (headers in listOf(
            headersOf("Sec-Fetch-Dest" to "image", "Accept" to "image/avif,image/webp,*/*"),
            headersOf("Accept" to "*/*"),
            headersOf("Sec-Fetch-Dest" to "empty", "Accept" to "application/json"),
            emptyList<ProxyHeader>(),
        )) {
            assertFalse(DownloadDecision.isNavigationRequest(headers))
        }
    }
}
