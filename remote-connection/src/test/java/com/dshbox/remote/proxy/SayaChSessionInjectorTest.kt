package com.dshbox.remote.proxy

import com.dshbox.remote.parse.ParserIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * saya-ch 会话注入器测试：CSRF 头只加在写请求（非 GET/HEAD）上；SAYA_CH
 * 选取不再产生 Bearer 头或 gateway_token 查询参数；会话 cookie 经罐
 * storeAll 后按 origin 取回（含 `__Host-` 前缀名）。
 */
class SayaChSessionInjectorTest {

    private val csrf = "csrf-token-value"

    private fun request(method: String, target: String = "/api/state") =
        ProxyRequest(method, target, mutableListOf())

    @Test fun writeMethodsCarryCsrfHeader() {
        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
            val request = request(method)
            SayaChSessionInjector(csrf).transformRequest(request, InjectorState())
            assertEquals(csrf, request.firstHeader("x-dsh-mobile-csrf"))
        }
    }

    @Test fun readMethodsDoNotCarryCsrfHeader() {
        for (method in listOf("GET", "HEAD")) {
            val request = request(method)
            SayaChSessionInjector(csrf).transformRequest(request, InjectorState())
            assertNull(request.firstHeader("x-dsh-mobile-csrf"))
        }
    }

    @Test fun injectorNeverTouchesBearerGatewayTokenOrTarget() {
        val request = request("POST", "/api/remote.mux?x=1")
        request.setHeader("Authorization", "Bearer existing")
        SayaChSessionInjector(csrf).transformRequest(request, InjectorState())
        // 不注入 Bearer（已有 Authorization 原样保留）、不追加 gateway_token、
        // 不改目标
        assertEquals("Bearer existing", request.firstHeader("Authorization"))
        assertFalse(request.target.contains("gateway_token"))
        assertEquals("/api/remote.mux?x=1", request.target)
        assertEquals(csrf, request.firstHeader("x-dsh-mobile-csrf"))
    }

    @Test fun blankCsrfStaysTransparent() {
        for (blank in listOf("", " ", "\t")) {
            val request = request("POST")
            SayaChSessionInjector(blank).transformRequest(request, InjectorState())
            assertNull(request.firstHeader("x-dsh-mobile-csrf"))
            assertEquals("/api/state", request.target)
        }
    }

    @Test fun forParserSelectsSessionInjectorOnlyWithCsrf() {
        val injector = AuthInjectors.forParser(ParserIds.SAYA_CH, "tok", sayaCsrf = csrf)
        assertTrue(injector is SayaChSessionInjector)
        // 未兑换（无 CSRF）或凭据缺失：透明直通
        assertEquals(PassThroughInjector, AuthInjectors.forParser(ParserIds.SAYA_CH, "tok", sayaCsrf = null))
        assertEquals(PassThroughInjector, AuthInjectors.forParser(ParserIds.SAYA_CH, "tok", sayaCsrf = ""))
        assertEquals(PassThroughInjector, AuthInjectors.forParser(ParserIds.SAYA_CH, null, sayaCsrf = csrf))
    }

    @Test fun consumedCredentialDoesNotDisableCsrfInjection() {
        // 消费标记只表示一次性票已兑换（不再重发票），CSRF 对整个会话持续
        // 必需——SAYA_CH 分支排在消费降级之前
        val injector = AuthInjectors.forParser(
            ParserIds.SAYA_CH,
            "consumed-ticket",
            credentialConsumed = true,
            sayaCsrf = csrf,
        )
        assertTrue(injector is SayaChSessionInjector)
        val request = ProxyRequest("POST", "/api/state", mutableListOf())
        injector.transformRequest(request, InjectorState())
        assertEquals(csrf, request.firstHeader("x-dsh-mobile-csrf"))
        assertNull(request.firstHeader("Authorization"))
        assertFalse(request.target.contains("gateway_token"))
    }

    @Test fun forParserSayaChNeverProducesBearerOrGatewayToken() {
        // 有 CSRF 时也不得出现 Bearer/gateway_token（逐请求模拟一次转发）
        val injector = AuthInjectors.forParser(ParserIds.SAYA_CH, "pairing-token", sayaCsrf = csrf)
        val request = request("POST", "/api/state")
        injector.transformRequest(request, InjectorState())
        assertNull(request.firstHeader("Authorization"))
        assertFalse(request.target.contains("gateway_token"))
        assertEquals(csrf, request.firstHeader("x-dsh-mobile-csrf"))
    }

    @Test fun jarStoresAndReturnsHostPrefixedSessionCookie() {
        val jar = CookieJar()
        val origin = "https://192.168.1.20:3443"
        val dualWrites = jar.storeAll(
            origin,
            listOf("__Host-dsh_ma_session=session-token; Path=/; Secure; HttpOnly; SameSite=Strict"),
        )
        // HttpOnly cookie 只进罐、不双写给渲染器（罐的既定纪律）
        assertTrue(dualWrites.isEmpty())
        assertTrue(jar.containsCookie(origin, "__Host-dsh_ma_session"))
        val cookies = jar.cookiesFor(origin)
        val host = cookies.single { it.name == "__Host-dsh_ma_session" }
        assertEquals("session-token", host.value)
    }

    @Test fun csrfProviderOverridesStaticValueWhenPresent() {
        val injector = SayaChSessionInjector(csrf)
        injector.csrfProvider = { "renewed-csrf" }
        val request = request("POST")
        injector.transformRequest(request, InjectorState())
        assertEquals("renewed-csrf", request.firstHeader("x-dsh-mobile-csrf"))
    }

    @Test fun csrfProviderFallsBackToStaticValueWhenMissing() {
        val injector = SayaChSessionInjector(csrf)
        injector.csrfProvider = { null }
        val request = request("POST")
        injector.transformRequest(request, InjectorState())
        assertEquals(csrf, request.firstHeader("x-dsh-mobile-csrf"))
    }

    @Test fun pathScopedDeviceCookieOnlyMatchesRenewPath() {
        val jar = CookieJar()
        val origin = "https://192.168.1.20:3443"
        val renewPath = "/mobile-access/auth/renew"
        jar.storeAll(
            URI(origin + renewPath),
            listOf("dsh_ma_device=device-token; Path=$renewPath; Max-Age=7776000; Secure; HttpOnly"),
        )
        assertEquals(
            "device-token",
            jar.valueFor(URI(origin + renewPath), "dsh_ma_device"),
        )
        // 路径作用域不外溢到站点根
        assertNull(jar.valueFor(URI("$origin/"), "dsh_ma_device"))
        assertNull(jar.valueFor(URI("$origin/api/v6/open/tdrive/download"), "dsh_ma_device"))
    }
}
