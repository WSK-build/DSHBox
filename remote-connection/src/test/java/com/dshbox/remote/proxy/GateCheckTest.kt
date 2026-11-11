package com.dshbox.remote.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GateCheckTest {

    private val gate = GateCheck("0123456789abcdef0123456789abcdef")

    @Test fun validGateCookiePasses() {
        assertEquals(
            GateCheck.GateDecision.VALID,
            gate.evaluate(listOf("other=1; dshbox_gate=0123456789abcdef0123456789abcdef; extra=2")),
        )
        assertEquals(
            GateCheck.GateDecision.VALID,
            gate.evaluate(listOf("dshbox_gate=0123456789abcdef0123456789abcdef")),
        )
    }

    @Test fun missingGateCookieIsRejected() {
        assertEquals(GateCheck.GateDecision.MISSING, gate.evaluate(emptyList()))
        assertEquals(GateCheck.GateDecision.MISSING, gate.evaluate(listOf("other=1")))
    }

    @Test fun wrongGateValueIsRejected() {
        assertEquals(GateCheck.GateDecision.INVALID, gate.evaluate(listOf("dshbox_gate=deadbeef")))
    }

    @Test fun anyMatchingGateCookiePassesAmongPlantedOnes() {
        // 上游种植的错误值与渲染器罐里的正确值并存：任一相等即放行（any-match），
        // 不能只看第一个出现的同名 cookie
        assertEquals(
            GateCheck.GateDecision.VALID,
            gate.evaluate(listOf("dshbox_gate=deadbeef; dshbox_gate=0123456789abcdef0123456789abcdef")),
        )
        assertEquals(
            GateCheck.GateDecision.VALID,
            gate.evaluate(listOf("dshbox_gate=0123456789abcdef0123456789abcdef; dshbox_gate=deadbeef")),
        )
        // 多个 Cookie 头分散携带时同样按 any-match 判定
        assertEquals(
            GateCheck.GateDecision.VALID,
            gate.evaluate(listOf("dshbox_gate=deadbeef", "sid=1; dshbox_gate=0123456789abcdef0123456789abcdef")),
        )
    }

    @Test fun onlyPlantedGateValueIsRejected() {
        // 只种植了错误值（无论几个）不构成通过
        assertEquals(
            GateCheck.GateDecision.INVALID,
            gate.evaluate(listOf("dshbox_gate=deadbeef; dshbox_gate=cafebabe")),
        )
        assertEquals(
            GateCheck.GateDecision.INVALID,
            gate.evaluate(listOf("dshbox_gate=cafebabe", "dshbox_gate=deadbeef")),
        )
    }

    @Test fun constantTimeComparisonPreservesDecisionSemantics() {
        // 常量时间比较（MessageDigest.isEqual）下判定语义不变：长度不等的
        // 候选值安全判否，不抛异常；相等判是
        assertEquals(
            GateCheck.GateDecision.INVALID,
            gate.evaluate(listOf("dshbox_gate=short")),
        )
        assertEquals(
            GateCheck.GateDecision.INVALID,
            gate.evaluate(listOf("dshbox_gate=")),
        )
        assertEquals(
            GateCheck.GateDecision.VALID,
            gate.evaluate(listOf("dshbox_gate=0123456789abcdef0123456789abcdef")),
        )
    }

    @Test fun gateCookieNameIsExactMatch() {
        // 前缀相似的 cookie 不构成门禁
        assertEquals(GateCheck.GateDecision.MISSING, gate.evaluate(listOf("xdshbox_gate=1")))
    }

    @Test fun newTokenIs32Hex() {
        val token = GateCheck.newToken()
        assertEquals(32, token.length)
        assertTrue(token.all { it in "0123456789abcdef" })
        assertTrue(GateCheck.newToken() != GateCheck.newToken())
    }

    @Test fun bypassModePassesEverything() {
        // gecko 降级路径：门禁下发不可用时的 per-session bypass——全部放行，
        // 回环监听本身仍仅本机可达（引擎 cookie 注入缺失的已知限制）
        val bypass = GateCheck.bypass()
        assertEquals(GateCheck.GateDecision.VALID, bypass.evaluate(emptyList()))
        assertEquals(GateCheck.GateDecision.VALID, bypass.evaluate(listOf("dshbox_gate=wrong")))
        assertEquals(GateCheck.GateDecision.VALID, bypass.evaluate(listOf("sid=1")))
    }

    @Test fun gateCookieIsStrippedFromForwardedRequest() {
        // 校验后剥离门禁 cookie 的语义在代理层覆盖：入站全部 Cookie 头被剥除
        // （见 LoopbackProxy 管线第 2 步），门禁值不外泄上游。
        val head = HttpWire.parseHead(
            ("GET / HTTP/1.1\r\n" +
                "Cookie: dshbox_gate=0123456789abcdef0123456789abcdef\r\n" +
                "Cookie: sid=1\r\n" +
                "\r\n").toByteArray(Charsets.ISO_8859_1),
        )!!
        assertEquals(GateCheck.GateDecision.VALID, gate.evaluate(head.headerValues("Cookie")))
        head.removeHeaders("Cookie")
        assertTrue(head.headerValues("Cookie").isEmpty())
    }
}
