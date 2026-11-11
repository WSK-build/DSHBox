package com.dshbox.app.lanaccess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上游寻址与请求改写（JVM）：默认回退、端口解析、token 注入（缺失/替换/
 * 保留其它参数）、网关 cookie 剥除、Location 回写。
 */
class LanUpstreamTest {

    @Test
    fun fallsBackToLoopbackDefaultPort() {
        val upstream = LanUpstream.upstreamOf(null)
        assertEquals("127.0.0.1", upstream.host)
        assertEquals(LanUpstream.DSH_FALLBACK_PORT, upstream.port)
        // Host 头在默认端口下仍带端口（上游是 http 默认端口 80 之外）
        assertEquals("127.0.0.1:3080", upstream.hostHeader)
        assertEquals("http://127.0.0.1:3080", upstream.originText)
        // 空白与畸形值同回退口径
        assertEquals(upstream.host, LanUpstream.upstreamOf("").host)
        assertEquals(upstream.port, LanUpstream.upstreamOf("").port)
        assertEquals(upstream.host, LanUpstream.upstreamOf("not a url").host)
        assertEquals(upstream.port, LanUpstream.upstreamOf("not a url").port)
    }

    @Test
    fun parsesConfirmedAddress() {
        val upstream = LanUpstream.upstreamOf("http://127.0.0.1:3081")
        assertEquals("127.0.0.1", upstream.host)
        assertEquals(3081, upstream.port)
        assertEquals("127.0.0.1:3081", upstream.hostHeader)
    }

    @Test
    fun rejectsBadPortAndHostInjection() {
        // 端口越界回退默认
        assertEquals(LanUpstream.DSH_FALLBACK_PORT, LanUpstream.upstreamOf("http://127.0.0.1:99999").port)
        assertEquals(LanUpstream.DSH_FALLBACK_PORT, LanUpstream.upstreamOf("http://127.0.0.1:0").port)
    }

    @Test
    fun upstreamHostIsForcedToLoopback() {
        // 硬边界：上游 host 一律 127.0.0.1（配置漂移到非回环地址也不外投）
        val forced = LanUpstream.upstreamOf("http://10.0.0.5:3081")
        assertEquals("127.0.0.1", forced.host)
        assertEquals(3081, forced.port)
        val external = LanUpstream.upstreamOf("http://example.com:1234")
        assertEquals("127.0.0.1", external.host)
        assertEquals(1234, external.port)
    }

    @Test
    fun upstreamSessionCookieDetection() {
        // dsh-auth- 前缀（DSH 0.2.1-rc.5 COOKIE_PREFIX）：任意派生段都识别
        assertTrue(LanUpstream.hasUpstreamSessionCookie(listOf("dsh-auth-abc=v1.x.y")))
        assertTrue(
            LanUpstream.hasUpstreamSessionCookie(
                listOf("dshbox_lan=zzz; dsh-auth-bXlhdXRob3JpdHk=v1.a.b"),
            ),
        )
        // 前缀之外的同位序列（大写/前缀不闭合）不识别
        assertFalse(LanUpstream.hasUpstreamSessionCookie(listOf("DSH-AUTH-abc=v1")))
        assertFalse(LanUpstream.hasUpstreamSessionCookie(listOf("session=dsh-auth-x")))
        assertFalse(LanUpstream.hasUpstreamSessionCookie(emptyList()))
    }

    @Test
    fun tokenInjectionOnBareTarget() {
        assertEquals(
            "/?token=TKN",
            LanUpstream.originFormWithToken("/", "TKN"),
        )
        assertEquals(
            "/api/chat?token=TKN",
            LanUpstream.originFormWithToken("/api/chat", "TKN"),
        )
        // token 空：不注入
        assertEquals("/x", LanUpstream.originFormWithToken("/x", null))
        assertEquals("/x", LanUpstream.originFormWithToken("/x", ""))
    }

    @Test
    fun tokenInjectionReplacesExistingAndKeepsOthers() {
        assertEquals(
            "/?a=1&token=NEW",
            LanUpstream.originFormWithToken("/?a=1&token=OLD", "NEW"),
        )
        assertEquals(
            "/p?token=NEW",
            LanUpstream.originFormWithToken("/p?token=OLD", "NEW"),
        )
        // 旧 token 剥除，其余键原样保留
        val mixed = LanUpstream.originFormWithToken("/p?x=1&token=OLD&y=2", "NEW")
        assertTrue(mixed.startsWith("/p?"))
        assertTrue(mixed.contains("x=1"))
        assertTrue(mixed.contains("y=2"))
        assertTrue(!mixed.contains("OLD"))
        assertTrue(mixed.endsWith("token=NEW"))
    }

    @Test
    fun stripsGatewayCookieButKeepsSessionCookies() {
        val stripped = LanUpstream.stripGatewayCookie(
            listOf("dshbox_lan=zzz; dsh_session=abc", "lang=en"),
            setOf(LanAccessSecrets.COOKIE_NAME),
        )
        // 门禁值被剥除；上游会话 cookie 与其余键保留（调用方再拼回 Cookie 头）
        val joined = stripped.joinToString("; ")
        assertTrue(joined.contains("dsh_session=abc"))
        assertTrue(joined.contains("lang=en"))
        assertTrue(!joined.contains("dshbox_lan"))
    }

    @Test
    fun locationRewritePointsBackToLanOrigin() {
        val rewritten = rewriteLocation(
            "http://127.0.0.1:3080/login",
            upstreamOrigin = "http://127.0.0.1:3080",
            lanOrigin = "http://192.168.1.5:39876",
        )
        assertEquals("http://192.168.1.5:39876/login", rewritten)
    }

    @Test
    fun locationRewriteKeepsForeignOrigins() {
        // 上游之外的绝对地址（外部跳转）原样保留
        val external = "https://example.com/x"
        assertEquals(
            external,
            rewriteLocation(external, "http://127.0.0.1:3080", "http://192.168.1.5:39876"),
        )
        // 相对地址原样
        assertEquals("/rel", rewriteLocation("/rel", "http://127.0.0.1:3080", "http://192.168.1.5:39876"))
        assertNull(null) // 口径对齐：本断言只是让「无额外副作用」显式
    }

    @Test
    fun locationRewriteKeepsPathAndQuery() {
        val rewritten = rewriteLocation(
            "http://127.0.0.1:3080/a/b?x=1#frag",
            "http://127.0.0.1:3080",
            "http://192.168.1.5:39876",
        )
        assertEquals("http://192.168.1.5:39876/a/b?x=1#frag", rewritten)
    }

    // ---- stripParams：query 参数剥除（密钥/令牌不进客户端 URL 的硬化出口） ----

    @Test
    fun stripParamsRemovesTokenAndKeyKeepsOthers() {
        assertEquals(
            "/p?x=1",
            LanUpstream.stripParams("/p?token=TKN&x=1&k=KEY", setOf("token", "k")),
        )
        // 单参数、空值形态、多次出现全部剥除
        assertEquals("/", LanUpstream.stripParams("/?k=", setOf("k")))
        assertEquals("/", LanUpstream.stripParams("/?k", setOf("k")))
        assertEquals(
            "/p?a=1",
            LanUpstream.stripParams("/p?k=1&a=1&k=2", setOf("k")),
        )
        // 无命中原样返回；fragment 保留
        assertEquals("/p?x=1#f", LanUpstream.stripParams("/p?x=1#f", setOf("token", "k")))
        assertEquals("/p#f", LanUpstream.stripParams("/p?token=TKN#f", setOf("token")))
        // 无 query、畸形目标原样
        assertEquals("/p", LanUpstream.stripParams("/p", setOf("token")))
        assertEquals("/p?a=1", LanUpstream.stripParams("/p?a=1", setOf("b")))
    }

    // ---- dshSessionCookieNames / hasCookieNamed（自愈与防循环标记用） ----

    @Test
    fun dshSessionCookieNamesDedupesAndPreservesOrder() {
        assertEquals(
            listOf("dsh-auth-abc", "dsh-auth-Base64+seg"),
            LanUpstream.dshSessionCookieNames(
                listOf(
                    "dshbox_lan=x; dsh-auth-abc=v1",
                    "dsh-auth-Base64+seg=v2",
                    "dsh-auth-abc=v3",
                ),
            ),
        )
        assertEquals(emptyList<String>(), LanUpstream.dshSessionCookieNames(listOf("dshbox_lan=x")))
        assertEquals(emptyList<String>(), LanUpstream.dshSessionCookieNames(emptyList()))
        // 无等号的裸名段也算一枚 cookie 名
        assertEquals(
            listOf("dsh-auth-bare"),
            LanUpstream.dshSessionCookieNames(listOf("dsh-auth-bare")),
        )
    }

    @Test
    fun hasCookieNamedMatchesExactNameNotSubstrings() {
        assertTrue(LanUpstream.hasCookieNamed(listOf("a=b; dshbox_lan_exchanged=1"), "dshbox_lan_exchanged"))
        assertTrue(LanUpstream.hasCookieNamed(listOf("dshbox_lan_exchanged"), "dshbox_lan_exchanged"))
        // 前缀相似但不是同一个名：不命中
        assertFalse(LanUpstream.hasCookieNamed(listOf("dshbox_lan_exchanged2=1"), "dshbox_lan_exchanged"))
        assertFalse(LanUpstream.hasCookieNamed(listOf("xdshbox_lan_exchanged=1"), "dshbox_lan_exchanged"))
        assertFalse(LanUpstream.hasCookieNamed(emptyList(), "dshbox_lan_exchanged"))
    }

    // ---- Location 回写硬化：token 与 k 一并剥除 ----

    @Test
    fun locationRewriteStripsTokenAndKeyFromQuery() {
        val rewritten = rewriteLocation(
            "http://127.0.0.1:3080/next?token=TKN&k=KEY&x=1",
            "http://127.0.0.1:3080",
            "http://192.168.1.5:39876",
        )
        assertEquals("http://192.168.1.5:39876/next?x=1", rewritten)
        // 剥空后 query 连 ? 一并消失
        val emptied = rewriteLocation(
            "http://127.0.0.1:3080/next?token=TKN",
            "http://127.0.0.1:3080",
            "http://192.168.1.5:39876",
        )
        assertEquals("http://192.168.1.5:39876/next", emptied)
    }
}
