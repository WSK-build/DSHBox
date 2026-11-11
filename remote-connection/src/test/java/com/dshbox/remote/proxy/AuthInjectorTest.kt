package com.dshbox.remote.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入器状态机测试：首跳携带、兑换检出后停止、fragment 不入上游。
 */
class AuthInjectorTest {

    private fun request(target: String) = ProxyRequest("GET", target, mutableListOf())

    // ---- dsh-bridge ----

    @Test fun bridgeInjectorAppendsAuthUntilCookieObserved() {
        val injector = DshBridgeAuthInjector("t".repeat(48))
        val state = InjectorState()
        val first = request("/")
        injector.transformRequest(first, state)
        assertEquals("/?auth=${"t".repeat(48)}", first.target)

        // 兑换完成信号到达前，后续请求同样携带（并发子资源需要凭据）
        val second = request("/static/app.js")
        injector.transformRequest(second, state)
        assertEquals("/static/app.js?auth=${"t".repeat(48)}", second.target)

        // 上游种下会话 cookie：停止追加，其后 jar 自动注入
        injector.onResponse(listOf("dsh_bridge_auth=sess123; Path=/"), state)
        val third = request("/page")
        injector.transformRequest(third, state)
        assertEquals("/page", third.target)
        assertTrue(state.credentialExchanged)
    }

    @Test fun bridgeInjectorSkipsTargetsAlreadyCarryingAuth() {
        val injector = DshBridgeAuthInjector("t".repeat(48))
        val state = InjectorState()
        val request = request("/login?auth=page-supplied")
        injector.transformRequest(request, state)
        assertEquals("/login?auth=page-supplied", request.target)
    }

    @Test fun bridgeInjectorIgnoresUnrelatedCookies() {
        val injector = DshBridgeAuthInjector("t".repeat(48))
        val state = InjectorState()
        injector.onResponse(listOf("other=1; Path=/"), state)
        assertFalse(state.credentialExchanged)
        val request = request("/")
        injector.transformRequest(request, state)
        assertTrue(request.target.contains("auth="))
    }

    // ---- dsh-full-remote ----

    @Test fun fullRemoteInjectorRewritesOnlyFirstRequestToInviteUrl() {
        val injector = DshFullRemoteInviteInjector("https://gw.example.com/_dsh_reverse_proxy/login?invite=Ab12")
        val state = InjectorState()
        val first = request("/")
        injector.transformRequest(first, state)
        assertEquals("/_dsh_reverse_proxy/login?invite=Ab12", first.target)
        assertTrue(state.documentRequestSeen)

        // 其后透明：会话 cookie 由代理罐在邀请兑换时接住
        val second = request("/dashboard")
        injector.transformRequest(second, state)
        assertEquals("/dashboard", second.target)
    }

    // ---- dsh-remote-web-gateway ----

    @Test fun gatewayInjectorRewritesFirstRequestToPairPath() {
        val injector = DshRemoteWebGatewayPairingInjector("https://gw.trycloudflare.com/pair#s3cret")
        val state = InjectorState()
        val first = request("/")
        injector.transformRequest(first, state)
        // fragment 不入上游：它由会话首次加载地址携带、页面 JS 消费
        assertEquals("/pair", first.target)

        val second = request("/assets/app.js")
        injector.transformRequest(second, state)
        assertEquals("/assets/app.js", second.target)
    }

    @Test fun gatewayInjectorFallsBackToPairPathWhenCredentialMalformed() {
        val injector = DshRemoteWebGatewayPairingInjector("not a url")
        val state = InjectorState()
        val request = request("/")
        injector.transformRequest(request, state)
        assertEquals("/pair", request.target)
    }

    // ---- 选取与降级 ----

    @Test fun forParserMapsCredentialsAndFallsBackToPassThrough() {
        assertTrue(AuthInjectors.forParser("dsh-bridge", "tok") is DshBridgeAuthInjector)
        assertTrue(AuthInjectors.forParser("dsh-full-remote", "url") is DshFullRemoteInviteInjector)
        assertTrue(AuthInjectors.forParser("dsh-remote-web-gateway", "url") is DshRemoteWebGatewayPairingInjector)
        // dsh-pocket / generic-url / 未知解析器：透明直通
        assertEquals(PassThroughInjector, AuthInjectors.forParser("dsh-pocket", null))
        assertEquals(PassThroughInjector, AuthInjectors.forParser("generic-url", null))
        assertEquals(PassThroughInjector, AuthInjectors.forParser("unknown", "x"))
        // 凭据缺失（未存档）：一律降级透明
        assertEquals(PassThroughInjector, AuthInjectors.forParser("dsh-bridge", null))
    }

    @Test fun genericTicketChannelStaysTransparentForInjection() {
        // 一次性票通道（generic）代理透明：票据经会话首次加载地址携带；
        // 消费记账在渲染器宿主的干净首帧提交（不依赖上游 Set-Cookie 信号）
        assertEquals(PassThroughInjector, AuthInjectors.forParser("generic-url", "http://x/pair?ticket=t"))
        // 凭据已消费：同样透明（不再重建带票首载）
        assertEquals(PassThroughInjector, AuthInjectors.forParser("generic-url", "http://x/pair?ticket=t", credentialConsumed = true))
    }

    @Test fun consumedCredentialAndAuthenticatedSessionSkipInjection() {
        // 一次性凭据已消费（兑换完成）：回配对页只会失败，注入器全程透明
        assertEquals(PassThroughInjector, AuthInjectors.forParser("dsh-full-remote", "url", credentialConsumed = true))
        assertEquals(PassThroughInjector, AuthInjectors.forParser("dsh-remote-web-gateway", "url", credentialConsumed = true))
        assertEquals(PassThroughInjector, AuthInjectors.forParser("generic-url", "url", credentialConsumed = true))
        // 重连预检：罐里已持有认证会话 cookie，token 不再重发进上游日志
        assertEquals(PassThroughInjector, AuthInjectors.forParser("dsh-bridge", "tok", sessionAlreadyAuthenticated = true))
        assertEquals(PassThroughInjector, AuthInjectors.forParser("dsh-full-remote", "url", sessionAlreadyAuthenticated = true))
    }

    @Test fun injectorStateMarksAreAtomicUnderContention() {
        val state = InjectorState()
        val winners = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Boolean, Boolean>())
        val threads = (0 until 8).map {
            Thread { winners.add(state.markDocumentRequestSeen()) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        // 并发登记：恰好一个调用胜出（首个文档请求语义）
        assertTrue(winners == setOf(true, false))
        assertTrue(state.documentRequestSeen)
    }
}
