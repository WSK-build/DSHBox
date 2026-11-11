package com.dshbox.remote.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页内外链导航判定测试：同 host（忽略 scheme 与端口）就地打开且路径/query
 * 保留（百分号编码逐字节原样，不解码）、fragment 丢弃；不同 host 外放浏览器；
 * 非 http(s) 与解析失败忽略；带内嵌凭据与超长地址拒开；上游信息缺失一律外放。
 */
class ExternalNavDecisionTest {

    private val loopbackOrigin = "http://127.0.0.2:51000"

    @Test fun sameHostDifferentSchemeAndPortOpensInPlace() {
        // 上游 origin 与链接的 scheme/端口均可不同：host 相同即站内跳转
        val decision = resolveExternalNavigation(
            url = "https://gw.example.com:9999/app/page?x=1&y=2#section",
            upstreamHost = upstreamOriginHost("http://gw.example.com:3081"),
            loopbackOrigin = loopbackOrigin,
        )
        assertEquals(
            ExternalNavDecision.Internal("http://127.0.0.2:51000/app/page?x=1&y=2"),
            decision,
        )
    }

    @Test fun inPlaceTargetKeepsRootPathAndQuery() {
        // 裸 host 无路径：就地目标回落根路径
        assertEquals(
            ExternalNavDecision.Internal("http://127.0.0.2:51000/"),
            resolveExternalNavigation("http://gw.example.com", "gw.example.com", loopbackOrigin),
        )
        // 裸 host 带 query：query 原样保留（不丢弃、不解码）
        assertEquals(
            ExternalNavDecision.Internal("http://127.0.0.2:51000/?x=1&y=2"),
            resolveExternalNavigation("http://gw.example.com?x=1&y=2", "gw.example.com", loopbackOrigin),
        )
    }

    @Test fun inPlaceTargetKeepsPercentEncodedSlashInPath() {
        // %2F（路径内斜杠编码）：解码会把一段路径拆成两级，必须逐字节保留
        assertEquals(
            ExternalNavDecision.Internal("http://127.0.0.2:51000/app%2Fpage"),
            resolveExternalNavigation("http://gw.example.com/app%2Fpage", "gw.example.com", loopbackOrigin),
        )
    }

    @Test fun inPlaceTargetKeepsPercentEncodedAmpersandInQuery() {
        // %26（query 内 & 编码）：解码会把单参数拆成两个参数
        assertEquals(
            ExternalNavDecision.Internal("http://127.0.0.2:51000/s?x=1%26y=2"),
            resolveExternalNavigation("http://gw.example.com/s?x=1%26y=2", "gw.example.com", loopbackOrigin),
        )
    }

    @Test fun inPlaceTargetKeepsPercentEncodedSpace() {
        assertEquals(
            ExternalNavDecision.Internal("http://127.0.0.2:51000/a%20b?name=a%20b"),
            resolveExternalNavigation(
                "http://gw.example.com/a%20b?name=a%20b",
                "gw.example.com",
                loopbackOrigin,
            ),
        )
    }

    @Test fun inPlaceTargetKeepsEncodedHashWithoutTurningIntoFragment() {
        // %23（# 编码）：不得被解码成 fragment 分隔符而丢尾段
        assertEquals(
            ExternalNavDecision.Internal("http://127.0.0.2:51000/a%23b"),
            resolveExternalNavigation("http://gw.example.com/a%23b", "gw.example.com", loopbackOrigin),
        )
    }

    @Test fun inPlaceTargetKeepsUtf8PercentEncodedPath() {
        // UTF-8 百分号编码路径（「中文」）：逐字节保留，不解码成明文
        assertEquals(
            ExternalNavDecision.Internal("http://127.0.0.2:51000/%E4%B8%AD%E6%96%87"),
            resolveExternalNavigation(
                "http://gw.example.com/%E4%B8%AD%E6%96%87",
                "gw.example.com",
                loopbackOrigin,
            ),
        )
    }

    @Test fun urlsWithEmbeddedCredentialsAreRejected() {
        // user:pass@host 形态一律拒开：同 host 也不就地加载（改写会静默丢凭据）
        assertEquals(
            ExternalNavDecision.Ignore,
            resolveExternalNavigation(
                "http://user:pass@gw.example.com/app",
                "gw.example.com",
                loopbackOrigin,
            ),
        )
        assertEquals(
            ExternalNavDecision.Ignore,
            resolveExternalNavigation(
                "https://user@other.example.org/docs",
                "gw.example.com",
                loopbackOrigin,
            ),
        )
    }

    @Test fun overlongUrlsAreRejected() {
        val longQuery = "?x=" + "a".repeat(9_000)
        assertEquals(
            ExternalNavDecision.Ignore,
            resolveExternalNavigation(
                "http://gw.example.com/app$longQuery",
                "gw.example.com",
                loopbackOrigin,
            ),
        )
        // 无上游信息（外放路径）同样拒开
        assertEquals(
            ExternalNavDecision.Ignore,
            resolveExternalNavigation("http://other.example.org$longQuery", null, loopbackOrigin),
        )
    }

    @Test fun loopbackOriginWithTrailingSlashDoesNotDuplicateSeparator() {
        val decision = resolveExternalNavigation(
            url = "http://gw.example.com/status?all=1",
            upstreamHost = "gw.example.com",
            loopbackOrigin = "http://127.0.0.2:51000/",
        )
        assertEquals(
            ExternalNavDecision.Internal("http://127.0.0.2:51000/status?all=1"),
            decision,
        )
    }

    @Test fun differentHostOpensInSystemBrowser() {
        val decision = resolveExternalNavigation(
            url = "https://other.example.org/docs?a=b",
            upstreamHost = "gw.example.com",
            loopbackOrigin = loopbackOrigin,
        )
        assertEquals(
            ExternalNavDecision.ExternalBrowser("https://other.example.org/docs?a=b"),
            decision,
        )
    }

    @Test fun nonHttpSchemesAreIgnored() {
        assertTrue(
            resolveExternalNavigation("dshremote://pair?token=x", "gw.example.com", loopbackOrigin) ==
                ExternalNavDecision.Ignore,
        )
        assertTrue(
            resolveExternalNavigation("ftp://gw.example.com/file", "gw.example.com", loopbackOrigin) ==
                ExternalNavDecision.Ignore,
        )
        assertTrue(
            resolveExternalNavigation("javascript:void(0)", "gw.example.com", loopbackOrigin) ==
                ExternalNavDecision.Ignore,
        )
    }

    @Test fun malformedOrEmptyUrlsAreIgnored() {
        assertTrue(
            resolveExternalNavigation("http://", "gw.example.com", loopbackOrigin) ==
                ExternalNavDecision.Ignore,
        )
        assertTrue(
            resolveExternalNavigation("   ", "gw.example.com", loopbackOrigin) ==
                ExternalNavDecision.Ignore,
        )
        assertTrue(
            resolveExternalNavigation("not a url", "gw.example.com", loopbackOrigin) ==
                ExternalNavDecision.Ignore,
        )
    }

    @Test fun missingUpstreamHostSendsHttpLinksToBrowser() {
        assertEquals(
            ExternalNavDecision.ExternalBrowser("https://gw.example.com:3081/app"),
            resolveExternalNavigation("https://gw.example.com:3081/app", null, loopbackOrigin),
        )
        assertEquals(
            ExternalNavDecision.ExternalBrowser("http://127.0.0.2:51000/x"),
            resolveExternalNavigation("http://127.0.0.2:51000/x", null, loopbackOrigin),
        )
        // 非 http(s) 仍忽略：上游信息缺失不改变非浏览器地址的分流结果
        assertEquals(
            ExternalNavDecision.Ignore,
            resolveExternalNavigation("about:blank", null, loopbackOrigin),
        )
    }

    @Test fun upstreamHostParsingIsCaseInsensitiveAndRejectsInvalidOrigins() {
        assertEquals("gw.example.com", upstreamOriginHost("https://GW.Example.COM:3081"))
        assertEquals(null, upstreamOriginHost(null))
        assertEquals(null, upstreamOriginHost("not a url"))
    }
}
