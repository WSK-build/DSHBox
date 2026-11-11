package com.dshbox.remote.proxy

import com.dshbox.remote.parse.ParserIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bearer 令牌注入器测试（dsh-Remote scheme / ChongYep 长期令牌）：全部请求
 * 设置并覆盖已有 Authorization 头；选取映射与长期令牌不进消费集合的口径。
 */
class BearerTokenInjectorTest {

    @Test fun everyRequestCarriesBearerAuthorization() {
        val injector = BearerTokenInjector("long-term-token")
        val state = InjectorState()
        for (target in listOf("/", "/static/app.js", "/api/state?x=1")) {
            val request = ProxyRequest("GET", target, mutableListOf())
            injector.transformRequest(request, state)
            assertEquals("Bearer long-term-token", request.firstHeader("Authorization"))
        }
    }

    @Test fun existingAuthorizationHeaderIsOverridden() {
        val injector = BearerTokenInjector("long-term-token")
        val state = InjectorState()
        val request = ProxyRequest(
            "GET",
            "/",
            mutableListOf(ProxyHeader("Authorization", "Basic dXNlcjpwYXNz")),
        )
        injector.transformRequest(request, state)
        // setHeader 覆盖语义：不残留旧凭据、不出现重复头
        assertEquals(listOf("Bearer long-term-token"), request.headers.map { it.value })
        assertEquals("Bearer long-term-token", request.firstHeader("authorization"))
    }

    @Test fun forParserMapsLongTermTokenParsersToBearer() {
        assertTrue(
            AuthInjectors.forParser(ParserIds.REMOTE_SCHEME, "tok") is BearerTokenInjector,
        )
        assertTrue(
            AuthInjectors.forParser(ParserIds.CHONG_YEP, "tok") is BearerTokenInjector,
        )
        // 凭据缺失（未存档）：降级透明
        assertEquals(PassThroughInjector, AuthInjectors.forParser(ParserIds.REMOTE_SCHEME, null))
        assertEquals(PassThroughInjector, AuthInjectors.forParser(ParserIds.CHONG_YEP, null))
    }

    @Test fun longTermTokenParsersStayOutOfConsumableSet() {
        // 长期令牌不是一次性票：不进权威消费集合（401 是普通认证失败，不误导重配对）。
        // saya-ch 的 gatewayToken 是一次性配对票，已进集合（其注入器在消费降级
        // 之前分支，CSRF 不受消费标记禁用）
        assertFalse(ParserIds.REMOTE_SCHEME in ParserIds.CONSUMABLE_CREDENTIAL_PARSER_IDS)
        assertFalse(ParserIds.CHONG_YEP in ParserIds.CONSUMABLE_CREDENTIAL_PARSER_IDS)
        assertTrue(ParserIds.SAYA_CH in ParserIds.CONSUMABLE_CREDENTIAL_PARSER_IDS)
        // anonRTtty 的票据与通用票据同生命周期：属于消费型
        assertTrue(ParserIds.ANON_RT in ParserIds.CONSUMABLE_CREDENTIAL_PARSER_IDS)
    }
}
