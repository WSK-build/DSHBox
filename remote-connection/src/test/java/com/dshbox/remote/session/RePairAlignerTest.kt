package com.dshbox.remote.session

import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.parse.ParsedEndpoint
import com.dshbox.remote.parse.ParsedPairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重新配对端点对齐测试：触发端点按 host 匹配载荷端点、同设备同解析器的
 * 同 host 兄弟端点一并更新（凭据/CA/地址/代次）、host 不命中的兄弟端点不
 * 动、全部无变化返回无变更信号。
 */
class RePairAlignerTest {

    private val epLan = EndpointId("ep-lan")
    private val epWan = EndpointId("ep-wan")
    private val epOther = EndpointId("ep-other")

    private fun endpoint(id: EndpointId, targetUrl: String, parserId: String, generation: Long = 0) =
        Endpoint(id = id, channelLabel = "LAN", targetUrl = targetUrl, parserId = parserId, generation = generation)

    private fun pairing(vararg endpoints: ParsedEndpoint, parserId: String = "p") =
        ParsedPairing(parserId = parserId, deviceName = "d", endpoints = endpoints.toList())

    private fun parsed(targetUrl: String, credential: String? = null, ca: String? = null) =
        ParsedEndpoint(targetUrl = targetUrl, channelLabel = "LAN", credential = credential, caCertificate = ca)

    @Test fun triggeredEndpointIsMatchedByHostNotByOrder() {
        // 载荷端点顺序与档案不同：触发端点按 host 命中对应载荷端点
        val endpoints = listOf(
            endpoint(epLan, "http://192.168.1.5:8787/", "p"),
            endpoint(epWan, "http://gw.example.com:8787/", "p"),
        )
        val outcome = RePairAligner.align(
            endpoints = endpoints,
            triggeredId = epWan,
            pairing = pairing(
                parsed("http://192.168.1.5:8788/", "new-token"),
                parsed("http://gw.example.com:8788/", "new-token"),
            ),
            currentCredential = { if (it == epLan) "old-token" else "old-token" },
            currentCaCertificate = { null },
        )!!
        val lan = outcome.endpoints.first { it.id == epLan }
        val wan = outcome.endpoints.first { it.id == epWan }
        // 触发端点对齐到其 host 对应的载荷端点（gw.example.com），而非首个
        assertEquals("http://gw.example.com:8788/", wan.targetUrl)
        assertEquals(1L, wan.generation)
        // 兄弟端点同 host 命中：一并更新（旧令牌不静默失效）
        assertEquals("http://192.168.1.5:8788/", lan.targetUrl)
        assertEquals(1L, lan.generation)
        assertTrue("new-token" in outcome.credentialUpdates.values)
        assertEquals(setOf(epLan, epWan), outcome.resetConsumed)
    }

    @Test fun unmatchedSiblingHostStaysUntouched() {
        val endpoints = listOf(
            endpoint(epLan, "http://192.168.1.5:8787/", "p"),
            endpoint(epWan, "http://offline.example.com:8787/", "p"),
        )
        val outcome = RePairAligner.align(
            endpoints = endpoints,
            triggeredId = epLan,
            pairing = pairing(parsed("http://192.168.1.5:8788/", "new-token")),
            currentCredential = { "old-token" },
            currentCaCertificate = { null },
        )!!
        assertEquals("http://192.168.1.5:8788/", outcome.endpoints.first { it.id == epLan }.targetUrl)
        // host 不在载荷中：兄弟端点地址与代次保留
        val wan = outcome.endpoints.first { it.id == epWan }
        assertEquals("http://offline.example.com:8787/", wan.targetUrl)
        assertEquals(0L, wan.generation)
        assertEquals(setOf(epLan), outcome.credentialUpdates.keys)
    }

    @Test fun siblingOfDifferentParserIsNeverTouched() {
        val endpoints = listOf(
            endpoint(epLan, "http://192.168.1.5:8787/", "p"),
            endpoint(epOther, "http://192.168.1.5:8787/", "other-parser"),
        )
        val outcome = RePairAligner.align(
            endpoints = endpoints,
            triggeredId = epLan,
            pairing = pairing(parsed("http://192.168.1.5:8788/", "new-token"), parserId = "p"),
            currentCredential = { "old-token" },
            currentCaCertificate = { null },
        )!!
        // 同 host 但解析器不同类：隐式重分类不存在
        assertEquals(0L, outcome.endpoints.first { it.id == epOther }.generation)
        assertEquals(setOf(epLan), outcome.credentialUpdates.keys)
    }

    @Test fun caCertificateChangeAloneIsARePair() {
        val endpoints = listOf(endpoint(epLan, "https://192.168.1.20:4433", "saya-ch-remote"))
        val outcome = RePairAligner.align(
            endpoints = endpoints,
            triggeredId = epLan,
            pairing = pairing(parsed("https://192.168.1.20:4433", "same-token", ca = "new-ca"), parserId = "saya-ch-remote"),
            currentCredential = { "same-token" },
            currentCaCertificate = { "old-ca" },
        )!!
        // CA 变更即重新配对：代次自增、解析器对齐、CA 写入
        assertEquals(1L, outcome.endpoints.single().generation)
        assertEquals(mapOf(epLan to "new-ca"), outcome.caCertificateUpdates)
        // 凭据未变：不重复写入、不清消费标记
        assertTrue(outcome.credentialUpdates.isEmpty())
        assertTrue(outcome.resetConsumed.isEmpty())
    }

    @Test fun bareAddressEditKeepsParserAndConsumedFlag() {
        val endpoints = listOf(endpoint(epLan, "http://192.168.1.5:8787/", "p"))
        val outcome = RePairAligner.align(
            endpoints = endpoints,
            triggeredId = epLan,
            pairing = pairing(parsed("http://192.168.1.5:8788/")),
            currentCredential = { "old-token" },
            currentCaCertificate = { null },
        )!!
        // 裸地址编辑：地址与代次更新，解析器/通道/凭据/消费标记保留
        val updated = outcome.endpoints.single()
        assertEquals("http://192.168.1.5:8788/", updated.targetUrl)
        assertEquals("p", updated.parserId)
        assertEquals(1L, updated.generation)
        assertTrue(outcome.credentialUpdates.isEmpty())
        assertTrue(outcome.resetConsumed.isEmpty())
    }

    @Test fun noChangeSignalsUnchanged() {
        val endpoints = listOf(endpoint(epLan, "http://192.168.1.5:8787/", "p"))
        val outcome = RePairAligner.align(
            endpoints = endpoints,
            triggeredId = epLan,
            pairing = pairing(parsed("http://192.168.1.5:8787/", "old-token")),
            currentCredential = { "old-token" },
            currentCaCertificate = { null },
        )
        assertNull(outcome)
    }

    @Test fun emptyPairingOrMissingTriggeredEndpointYieldNull() {
        val endpoints = listOf(endpoint(epLan, "http://192.168.1.5:8787/", "p"))
        assertNull(
            RePairAligner.align(endpoints, epLan, pairing(), currentCredential = { null }, currentCaCertificate = { null }),
        )
        assertNull(
            RePairAligner.align(
                endpoints, EndpointId("ghost"), pairing(parsed("http://x/")),
                currentCredential = { null }, currentCaCertificate = { null },
            ),
        )
    }

    @Test fun directEndpointRePairCarriesParsedSessionMode() {
        // ZCode 地址重配对（载荷声明 DIRECT）：直连标记随档案透传，不得静默
        // 掉回默认代理档
        val direct = Endpoint(
            id = epLan,
            channelLabel = "PUBLIC",
            targetUrl = "https://zcode.z.ai/remote/old",
            parserId = "zcode-remote",
            sessionMode = "DIRECT",
        )
        val outcome = RePairAligner.align(
            endpoints = listOf(direct),
            triggeredId = epLan,
            pairing = pairing(
                ParsedEndpoint(
                    targetUrl = "https://zcode.z.ai/remote/new",
                    channelLabel = "PUBLIC",
                    credential = null,
                    sessionMode = "DIRECT",
                ),
            ),
            currentCredential = { null },
            currentCaCertificate = { null },
        )!!
        val updated = outcome.endpoints.single()
        assertEquals("https://zcode.z.ai/remote/new", updated.targetUrl)
        assertEquals("DIRECT", updated.sessionMode)
        assertEquals(1L, updated.generation)
    }

    @Test fun plainlyRePairedEndpointKeepsExistingSessionMode() {
        // 普通 URL 载荷（sessionMode = null）重配对既有非直连档案：保持档案
        // 原有模式——null 不覆盖，防止普通重配对误清既有模式
        val proxied = Endpoint(
            id = epLan,
            channelLabel = "LAN",
            targetUrl = "http://192.168.1.5:8787/",
            parserId = "p",
            sessionMode = "PROXIED",
        )
        val outcome = RePairAligner.align(
            endpoints = listOf(proxied),
            triggeredId = epLan,
            pairing = pairing(parsed("http://192.168.1.5:8788/")),
            currentCredential = { null },
            currentCaCertificate = { null },
        )!!
        assertEquals("PROXIED", outcome.endpoints.single().sessionMode)
        assertEquals(1L, outcome.endpoints.single().generation)
    }

    @Test fun existingDirectEndpointWithPlainHttpPayloadConvergesToProxied() {
        // 既有直连端点 + 普通 http 载荷重配对：存量 `http + DIRECT` 死档
        // 在对齐期被修复为代理档（会话层拒绝的档案不再是死胡同），变更集合
        // 含该端点
        val direct = Endpoint(
            id = epLan,
            channelLabel = "LAN",
            targetUrl = "http://192.168.1.5:8787/",
            parserId = "p",
            sessionMode = "DIRECT",
        )
        val outcome = RePairAligner.align(
            endpoints = listOf(direct),
            triggeredId = epLan,
            pairing = pairing(parsed("http://192.168.1.5:8788/")),
            currentCredential = { null },
            currentCaCertificate = { null },
        )!!
        assertEquals("PROXIED", outcome.endpoints.single().sessionMode)
        assertEquals(setOf(epLan), outcome.changedEndpointIds)
        assertEquals(1L, outcome.endpoints.single().generation)
    }

    @Test fun legacyHttpDirectArchiveIsRepairedBySameUrlReconfirmation() {
        // 存量 `http + DIRECT` 档案 + 同 URL 重确认（地址/凭据/CA 均未变）：
        // 模式修复本身构成变更——对齐不返回 null，结果代理档
        val deadArchive = Endpoint(
            id = epLan,
            channelLabel = "LAN",
            targetUrl = "http://192.168.1.5:8787/",
            parserId = "p",
            sessionMode = "DIRECT",
        )
        val outcome = RePairAligner.align(
            endpoints = listOf(deadArchive),
            triggeredId = epLan,
            pairing = pairing(parsed("http://192.168.1.5:8787/", "old-token")),
            currentCredential = { "old-token" },
            currentCaCertificate = { null },
        )
        assertNotNull(outcome)
        assertEquals("PROXIED", outcome!!.endpoints.single().sessionMode)
        assertEquals(setOf(epLan), outcome.changedEndpointIds)
        assertTrue(outcome.credentialUpdates.isEmpty())
        assertTrue(outcome.resetConsumed.isEmpty())
    }

    @Test fun requestedDirectSelectionOverridesMissingPayloadMode() {
        // 显式选择优先于载荷空值：https 载荷未声明模式，用户选直连 → 直连
        val outcome = RePairAligner.align(
            endpoints = listOf(endpoint(epLan, "https://old.example.com/", "p")),
            triggeredId = epLan,
            pairing = pairing(parsed("https://new.example.com/")),
            currentCredential = { null },
            currentCaCertificate = { null },
            requestedSessionMode = "DIRECT",
        )!!
        assertEquals("DIRECT", outcome.endpoints.single().sessionMode)
        assertEquals(1L, outcome.endpoints.single().generation)
    }

    @Test fun requestedProxiedSelectionOverridesPayloadDirect() {
        // 显式选择优先于载荷声明：https 账号型载荷声明直连，用户选代理档
        // → 代理档（账号型载荷不会静默把用户的选择拉回直连）
        val outcome = RePairAligner.align(
            endpoints = listOf(endpoint(epLan, "https://old.example.com/remote", "zcode-remote")),
            triggeredId = epLan,
            pairing = pairing(
                ParsedEndpoint(
                    targetUrl = "https://new.example.com/remote",
                    channelLabel = "PUBLIC",
                    credential = null,
                    sessionMode = "DIRECT",
                ),
                parserId = "zcode-remote",
            ),
            currentCredential = { null },
            currentCaCertificate = { null },
            requestedSessionMode = "PROXIED",
        )!!
        assertEquals("PROXIED", outcome.endpoints.single().sessionMode)
        assertEquals(1L, outcome.endpoints.single().generation)
    }

    @Test fun plainConfirmationOnNullModeArchiveIsNotAChange() {
        // 会话模式字段为 null 的端点（generic 建档、v3 旧档案，默认按代理档
        // 生效）空确认：编辑框透传的显式 PROXIED 对上默认口径不构成变更——
        // 无变更返回 null，代次不动、无会话释放的受体
        val nullMode = Endpoint(
            id = epLan,
            channelLabel = "LAN",
            targetUrl = "https://tunnel.example.com/",
            parserId = "generic-url",
            sessionMode = null,
        )
        val outcome = RePairAligner.align(
            endpoints = listOf(nullMode),
            triggeredId = epLan,
            pairing = pairing(parsed("https://tunnel.example.com/", "old-token")),
            currentCredential = { "old-token" },
            currentCaCertificate = { null },
            requestedSessionMode = "PROXIED",
        )
        assertNull(outcome)
    }

    @Test fun requestedDirectOnExistingHttpsDirectArchiveIsNotAChange() {
        // 既有 https 直连档案空确认：显式 DIRECT 收口后与既有直连一致，无变更
        val direct = Endpoint(
            id = epLan,
            channelLabel = "PUBLIC",
            targetUrl = "https://zcode.z.ai/remote/abc",
            parserId = "zcode-remote",
            sessionMode = "DIRECT",
        )
        val outcome = RePairAligner.align(
            endpoints = listOf(direct),
            triggeredId = epLan,
            pairing = pairing(parsed("https://zcode.z.ai/remote/abc")),
            currentCredential = { null },
            currentCaCertificate = { null },
            requestedSessionMode = "DIRECT",
        )
        assertNull(outcome)
    }

    @Test fun siblingSyncUpdateIgnoresRequestedSessionMode() {
        // 兄弟端点同步更新不受显式选择影响（requestedSessionMode 只作用于
        // 触发端点）：同 https 载荷重配对，兄弟端点按载荷/既有口径保持
        val endpoints = listOf(
            endpoint(epLan, "https://old.example.com/", "p"),
            endpoint(epWan, "https://gw.example.com/", "p", generation = 3),
        )
        val outcome = RePairAligner.align(
            endpoints = endpoints.map { it.copy(sessionMode = "DIRECT") },
            triggeredId = epLan,
            pairing = pairing(
                parsed("https://new.example.com/"),
                parsed("https://gw.example.com/"),
            ),
            currentCredential = { null },
            currentCaCertificate = { null },
            requestedSessionMode = "PROXIED",
        )!!
        // 触发端点按显式选择落代理档，兄弟端点按既有直连标记保持（模式不在
        // 其上不构成变更：地址/凭据/CA 均未动时兄弟端点不进变更集合、代次不动）
        assertEquals("PROXIED", outcome.endpoints.first { it.id == epLan }.sessionMode)
        assertEquals("DIRECT", outcome.endpoints.first { it.id == epWan }.sessionMode)
        assertEquals(3L, outcome.endpoints.first { it.id == epWan }.generation)
        assertEquals(setOf(epLan), outcome.changedEndpointIds)
    }
}
