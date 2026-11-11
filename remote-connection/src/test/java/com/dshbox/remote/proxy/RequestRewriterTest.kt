package com.dshbox.remote.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestRewriterTest {

    private val rewriter = RequestRewriter(
        loopbackOrigin = "http://127.0.0.5:8601",
        upstreamOrigin = "http://192.168.1.5:3081",
    )

    @Test fun outboundHostRewritesToUpstream() {
        assertEquals("192.168.1.5:3081", rewriter.outboundHost())
        val defaultPort = RequestRewriter("http://127.0.0.5:8601", "http://192.168.1.5:80")
        assertEquals("192.168.1.5", defaultPort.outboundHost())
    }

    @Test fun originRewritesToUpstreamOrigin() {
        assertEquals(
            "http://192.168.1.5:3081",
            rewriter.rewriteOrigin("http://127.0.0.5:8601"),
        )
    }

    @Test fun missingOriginIsRemoved() {
        assertNull(rewriter.rewriteOrigin(null))
        assertNull(rewriter.rewriteOrigin(""))
    }

    @Test fun refererKeepsPathButRewritesOrigin() {
        assertEquals(
            "http://192.168.1.5:3081/dsh/page?q=1",
            rewriter.rewriteReferer("http://127.0.0.5:8601/dsh/page?q=1"),
        )
        assertNull(rewriter.rewriteReferer(null))
    }

    @Test fun locationSameOriginRewritesToLoopback() {
        assertEquals(
            "http://127.0.0.5:8601/landing?next=2#top",
            rewriter.rewriteLocation("http://192.168.1.5:3081/landing?next=2#top"),
        )
    }

    @Test fun locationDifferentHostIsUntouched() {
        val external = "http://other.example.com/landing"
        assertEquals(external, rewriter.rewriteLocation(external))
    }

    @Test fun locationDifferentPortIsUntouched() {
        val other = "http://192.168.1.5:9999/landing"
        assertEquals(other, rewriter.rewriteLocation(other))
    }

    @Test fun locationRelativeIsUntouched() {
        assertEquals("/landing", rewriter.rewriteLocation("/landing"))
        assertEquals("/landing?x=1", rewriter.rewriteLocation("/landing?x=1"))
    }

    @Test fun httpsUpstreamLocationRewritesToHttpLoopback() {
        val tls = RequestRewriter("http://127.0.0.3:8602", "https://remote.example.com")
        assertEquals(
            "http://127.0.0.3:8602/after",
            tls.rewriteLocation("https://remote.example.com/after"),
        )
    }

    @Test fun cspStripsUpgradeInsecureRequests() {
        assertEquals(
            "default-src 'self'; img-src *",
            rewriter.stripUpgradeInsecure("default-src 'self'; upgrade-insecure-requests; img-src *"),
        )
        assertEquals(
            "default-src 'self'",
            rewriter.stripUpgradeInsecure("upgrade-insecure-requests; default-src 'self'"),
        )
        assertEquals(
            "default-src 'self'",
            rewriter.stripUpgradeInsecure("default-src 'self'"),
        )
        assertEquals("", rewriter.stripUpgradeInsecure("upgrade-insecure-requests"))
    }

    @Test fun cspDirectiveMatchIsCaseInsensitive() {
        assertEquals("a", rewriter.stripUpgradeInsecure("a; Upgrade-Insecure-Requests"))
    }

    @Test fun originsAreNormalized() {
        // 默认端口归一：回环与上游 origin 文本不带冗余端口
        val rewriter = RequestRewriter("http://127.0.0.2:80", "http://intranet.local")
        assertEquals("http://intranet.local", rewriter.upstreamOrigin)
        assertEquals("http://127.0.0.2", rewriter.loopbackOrigin)
    }

    @Test fun invalidOriginsAreRejected() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RequestRewriter("http://127.0.0.5:8601", "ftp://x")
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RequestRewriter("not a uri", "http://192.168.1.5:3081")
        }
    }

    @Test fun httpsUpstreamHostKeepsDefaultPortImplicit() {
        val tls = RequestRewriter("http://127.0.0.5:8601", "https://remote.example.com:443")
        assertEquals("remote.example.com", tls.outboundHost())
    }

    @Test fun loopbackOriginAlwaysHttp() {
        assertFalse(rewriter.loopbackOrigin.startsWith("https"))
        assertTrue(rewriter.upstreamOrigin.startsWith("http"))
    }
}
