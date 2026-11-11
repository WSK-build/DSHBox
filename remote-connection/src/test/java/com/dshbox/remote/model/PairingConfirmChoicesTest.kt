package com.dshbox.remote.model

import com.dshbox.remote.parse.ChannelKind
import com.dshbox.remote.parse.ParsedEndpoint
import com.dshbox.remote.parse.ParsedPairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 确认卡默认选择与回退策略（纯函数，JVM 单测锁定）：账号型解析器端点
 * （声明的直连）预填直连；http 端点（载荷声明经直连口径收口）回落代理；
 * 保留档默认一律继承全局（null null null）；选择与解析端点数量错位时前缀
 * 生效 + 默认值补齐，绝不把错位选择写到端点上。
 */
class PairingConfirmChoicesTest {

    /** 账号型解析器形态：https 目标 + 声明直连（DSH Remote/ZCode 工作区）。 */
    private fun directHttpsPairing(
        targetUrl: String = "https://dsh.example.com/app/home",
        sessionMode: String? = "DIRECT",
        extraEndpoint: ParsedEndpoint? = null,
    ) = ParsedPairing(
        parserId = "dsh-harness-remote",
        deviceName = "DSH Remote",
        endpoints = buildList {
            add(ParsedEndpoint(targetUrl, ChannelKind.PUBLIC.name, null, sessionMode = sessionMode))
            extraEndpoint?.let { add(it) }
        },
    )

    /** 通用通道形态：http 目标，载荷不声明会话模式（建档默认代理档）。 */
    private fun httpGenericPairing(targetUrl: String = "http://192.168.1.5:3080/app") =
        ParsedPairing(
            parserId = "generic-url",
            deviceName = "192.168.1.5",
            endpoints = listOf(ParsedEndpoint(targetUrl, ChannelKind.LAN.name, null)),
        )

    // ---- 默认选择推导 ----

    @Test fun httpsAccountParserDefaultsToDirect() {
        val choices = defaultConfirmChoices(directHttpsPairing())
        assertEquals(1, choices.endpoints.size)
        assertEquals("DIRECT", choices.endpoints.single().sessionMode)
        // 保留档默认继承全局：null 不做解析期预填（UI 显示继承全局档位）
        assertNull(choices.endpoints.single().retentionOverride)
    }

    @Test fun httpEndpointDefaultsToProxied() {
        // http 目标声明直连（恶意/畸形载荷形态）：直连口径收口回落代理档，
        // 「非 https + 直连」死档不进确认卡预填
        val choices = defaultConfirmChoices(
            directHttpsPairing("http://192.168.1.5:3080/app", "DIRECT"),
        )
        assertEquals("PROXIED", choices.endpoints.single().sessionMode)
    }

    @Test fun nullDeclarationResolvesProxied() {
        // 通用通道不声明模式：默认代理档（不凭 https 目标擅自切直连）
        val httpsGeneric = ParsedPairing(
            parserId = "generic-url",
            deviceName = "portal",
            endpoints = listOf(
                ParsedEndpoint("https://portal.example.com/page", ChannelKind.PUBLIC.name, null),
            ),
        )
        assertEquals(
            "PROXIED",
            defaultConfirmChoices(httpsGeneric).endpoints.single().sessionMode,
        )
    }

    @Test fun multiEndpointDefaultsAreIndependentlyDerived() {
        // 多端点载荷逐项独立推导：https 直连与 http 代理并存
        val choices = defaultConfirmChoices(
            directHttpsPairing(
                extraEndpoint = ParsedEndpoint(
                    "http://192.168.1.5:8787/",
                    ChannelKind.LAN.name,
                    "shared-token",
                    sessionMode = "DIRECT", // 畸形声明：非 https 回落
                ),
            ),
        )
        assertEquals(2, choices.endpoints.size)
        assertEquals("DIRECT", choices.endpoints[0].sessionMode)
        assertEquals("PROXIED", choices.endpoints[1].sessionMode)
        choices.endpoints.forEach { assertNull(it.retentionOverride) }
    }

    // ---- 数量错位回退 ----

    @Test fun reconcilePassesThroughWhenCountMatches() {
        val pairing = directHttpsPairing()
        val provided = listOf(EndpointChoice("PROXIED", "IMMEDIATE"))
        val result = reconcileConfirmChoices(pairing, PairingConfirmChoices(provided))
        assertEquals(provided, result)
    }

    @Test fun reconcileDropsSurplusChoicesWithoutOverflowing() {
        // 多余选择丢弃：不追加到后面的端点（前缀语义）
        val pairing = directHttpsPairing()
        val surplus = listOf(
            EndpointChoice("PROXIED", "IMMEDIATE"),
            EndpointChoice("DIRECT", "FOREVER"),
        )
        val result = reconcileConfirmChoices(pairing, PairingConfirmChoices(surplus))
        assertEquals(1, result.size)
        assertEquals(EndpointChoice("PROXIED", "IMMEDIATE"), result.single())
    }

    @Test fun reconcileFillsMissingWithParsedDefaults() {
        // 选择不足（载荷比选择多一段）：缺项按解析默认值补齐（直连档/继承）
        val pairing = directHttpsPairing(
            extraEndpoint = ParsedEndpoint(
                "https://mirror.example.com/app",
                ChannelKind.PUBLIC.name,
                null,
                sessionMode = "DIRECT",
            ),
        )
        val partial = listOf(EndpointChoice("PROXIED", "RETAIN:5"))
        val result = reconcileConfirmChoices(pairing, PairingConfirmChoices(partial))
        assertEquals(2, result.size)
        assertEquals(EndpointChoice("PROXIED", "RETAIN:5"), result[0])
        assertEquals(EndpointChoice("DIRECT", null), result[1])
    }

    @Test fun reconcileNullChoicesFallsBackToDefaults() {
        val pairing = httpGenericPairing()
        assertEquals(
            defaultConfirmChoices(pairing).endpoints,
            reconcileConfirmChoices(pairing, null),
        )
    }

    @Test fun reconcileMismatchedCountsUseProvidedPrefixThenParsedDefaults() {
        // 2 端点载荷配 1 条错位选择：前缀生效（端点 0 用选择、端点 1 补默认）；
        // 与「全部回默认」的回退口径不同——已提供前缀生效 + 缺项默认补齐
        val pairing = directHttpsPairing(
            extraEndpoint = ParsedEndpoint(
                "http://192.168.1.5:8787/",
                ChannelKind.LAN.name,
                null,
            ),
        )
        val result = reconcileConfirmChoices(
            pairing,
            PairingConfirmChoices(listOf(EndpointChoice("PROXIED", "FOREVER"))),
        )
        assertEquals(2, result.size)
        assertEquals(EndpointChoice("PROXIED", "FOREVER"), result[0])
        assertEquals("PROXIED", result[1].sessionMode) // http 端点默认代理
        assertNull(result[1].retentionOverride)
    }
}
