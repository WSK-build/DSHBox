package com.dshbox.remote.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 强制外放目标解析契约（页面打开请求口径）：一律系统浏览器外放、绝不就地
 * 加载、不比对其与上游是否同站；代理会话的回环目标先还原真实上游 origin
 * （保留 path/query/fragment 的原始编码），上游缺失时回环目标丢弃；跨源
 * http(s) 原文外放；非 http(s)、带内嵌凭据、超长或解析失败一律丢弃（null）。
 */
class ForcedExternalTargetTest {

    private val loopbackOrigin = "http://127.0.0.2:51000"
    private val upstreamOrigin = "https://gw.example.com:3081"

    @Test fun loopbackTargetMapsToUpstreamOrigin() {
        // 代理同源目标即本会话回环地址：scheme/host/端口三者一致判定为回环，
        // 还原为上游 origin + 原路径外放
        assertEquals(
            "https://gw.example.com:3081/app/page?x=1&y=2#top",
            resolveForcedExternalTarget(
                "http://127.0.0.2:51000/app/page?x=1&y=2#top",
                loopbackOrigin,
                upstreamOrigin,
            ),
        )
    }

    @Test fun loopbackRootPathDefaultsToSlash() {
        assertEquals(
            "https://gw.example.com:3081/",
            resolveForcedExternalTarget("http://127.0.0.2:51000", loopbackOrigin, upstreamOrigin),
        )
    }

    @Test fun loopbackPortVariantsAllRemapByHost() {
        // 门禁 cookie 按回环 host 下发：回环 host 的任一端口形态都是本套间
        // 的同源目标，一律按 host 判定还原上游
        assertEquals(
            "https://gw.example.com:3081/other",
            resolveForcedExternalTarget(
                "http://127.0.0.2:51001/other",
                loopbackOrigin,
                upstreamOrigin,
            ),
        )
        // 不同回环主机（另一端点的 127.0.0.3 也是真实可打开的回环地址，系统
        // 浏览器若连该地址同样无门禁 cookie）同样按"回环 host"统一还原
        assertEquals(
            "https://gw.example.com:3081/app",
            resolveForcedExternalTarget(
                "http://127.0.0.3:51000/app",
                "http://127.0.0.3:51000",
                upstreamOrigin,
            ),
        )
    }

    @Test fun loopbackPathQueryKeptRawPercentEncoding() {
        // path/query/fragment 的百分号编码原样保留（解码会破坏语义字节），
        // fragment 也不丢（系统浏览器新窗口无跨文档凭据问题）
        assertEquals(
            "https://gw.example.com:3081/app%2Fpage?s=%26q&t=%23f",
            resolveForcedExternalTarget(
                "http://127.0.0.2:51000/app%2Fpage?s=%26q&t=%23f",
                loopbackOrigin,
                upstreamOrigin,
            ),
        )
    }

    @Test fun sameSiteUpstreamTargetStillExternal() {
        // 与上游同 host 的目标不再就地加载（既有 In-site 分流的坏点）：强制
        // 外放、地址原文
        assertEquals(
            "http://gw.example.com/should-not-load-in-place",
            resolveForcedExternalTarget(
                "http://gw.example.com/should-not-load-in-place",
                loopbackOrigin,
                "http://gw.example.com:3081",
            ),
        )
        // host 相同、scheme 不同的目标也一样外放
        assertEquals(
            "https://gw.example.com:3081/app",
            resolveForcedExternalTarget("https://gw.example.com:3081/app", loopbackOrigin, upstreamOrigin),
        )
    }

    @Test fun crossSiteHttpKeptAsIs() {
        assertEquals(
            "http://news.example.org/daily?a=1",
            resolveForcedExternalTarget("http://news.example.org/daily?a=1", loopbackOrigin, upstreamOrigin),
        )
        // 首尾空白清除（不改变原文语义）：地址体保留
        assertEquals(
            "https://news.example.org/",
            resolveForcedExternalTarget("  https://news.example.org/  ", loopbackOrigin, upstreamOrigin),
        )
    }

    @Test fun directHttpAcceptedAsIs() {
        // 直连 http 上游的真实地址：无回环目标，原文外放
        assertEquals(
            "http://10.1.2.3:8080/direct/page",
            resolveForcedExternalTarget("http://10.1.2.3:8080/direct/page", loopbackOrigin, upstreamOrigin),
        )
    }

    @Test fun upstreamOriginUnparseableDropsLoopbackTarget() {
        // 上游 origin 缺失或解析失败 → 无从判定代理拓扑，回环目标对应无门
        // 禁 cookie 的回环地址（系统浏览器只会得到代理 404），丢弃
        assertNull(resolveForcedExternalTarget("http://127.0.0.2:51000/app", loopbackOrigin, null))
        assertNull(resolveForcedExternalTarget("http://127.0.0.2:51000/app", loopbackOrigin, "not a url"))
    }

    @Test fun upstreamOriginMissingCrossSiteTargetKeptAsIs() {
        // 上游缺失但目标是跨站真实地址：与上游无关，原文外放不受影响
        assertEquals(
            "http://news.example.org/daily?a=1",
            resolveForcedExternalTarget("http://news.example.org/daily?a=1", loopbackOrigin, null),
        )
    }

    @Test fun directTopologySameHostDifferentPortKeptAsIs() {
        // 直连拓扑（loopbackOrigin == upstreamOrigin，如 https://zcode.z.ai）：
        // 同 host 异端口目标是真实地址，原文外放、端口保留（不映射上游 origin）
        assertEquals(
            "https://zcode.z.ai:8443/console",
            resolveForcedExternalTarget(
                "https://zcode.z.ai:8443/console",
                "https://zcode.z.ai",
                "https://zcode.z.ai",
            ),
        )
    }

    @Test fun directTopologySameHostDifferentSchemeKeptAsIs() {
        // 直连拓扑同 host 异 scheme：目的地本就真实可达，不做任何改写
        assertEquals(
            "http://zcode.z.ai/x",
            resolveForcedExternalTarget("http://zcode.z.ai/x", "https://zcode.z.ai", "https://zcode.z.ai"),
        )
    }

    @Test fun directTopologySameHostSameSchemeSamePortKeptAsIs() {
        // 直连拓扑完全同源的目标：同样原文外放（强制外放语义本就不就地加载）
        assertEquals(
            "https://zcode.z.ai/console",
            resolveForcedExternalTarget("https://zcode.z.ai/console", "https://zcode.z.ai", "https://zcode.z.ai"),
        )
    }

    @Test fun directTopologyUppercaseHostKeptAsIs() {
        // 直连拓扑 host 大小写不敏感（URI 规范化已小写）：目标是真实地址，
        // 原文外放不变形（不做大小写改写）
        assertEquals(
            "https://Host.Example/console",
            resolveForcedExternalTarget("https://Host.Example/console", "https://zcode.z.ai", "https://zcode.z.ai"),
        )
    }

    @Test fun directTopologyExplicitDefaultPortKeptAsIs() {
        // 直连拓扑显式默认端口（:443）目标：原文外放、端口保留不删除
        assertEquals(
            "https://host:443/x",
            resolveForcedExternalTarget("https://host:443/x", "https://zcode.z.ai", "https://zcode.z.ai"),
        )
    }

    @Test fun directTopologyNonHttpTargetStillDropped() {
        // 直连拓扑不改变非 http(s) 目标的丢弃口径
        assertNull(resolvedNonHttpDirect())
        assertNull(resolvedNonHttpDirectWithPort())
    }

    private fun resolvedNonHttpDirect(): String? =
        resolveForcedExternalTarget("dshremote://pair?token=x", "https://zcode.z.ai", "https://zcode.z.ai")

    private fun resolvedNonHttpDirectWithPort(): String? =
        resolveForcedExternalTarget("ftp://zcode.z.ai:21/file", "https://zcode.z.ai", "https://zcode.z.ai")

    @Test fun nonHttpTargetsDropped() {
        assertNull(resolveForcedExternalTarget("dshremote://pair?token=x", loopbackOrigin, upstreamOrigin))
        assertNull(resolveForcedExternalTarget("ftp://gw.example.com/file", loopbackOrigin, upstreamOrigin))
        assertNull(resolveForcedExternalTarget("javascript:void(0)", loopbackOrigin, upstreamOrigin))
        assertNull(resolveForcedExternalTarget("about:blank", loopbackOrigin, upstreamOrigin))
        assertNull(resolveForcedExternalTarget("file:///etc/passwd", loopbackOrigin, upstreamOrigin))
        // 协议相对形态 无 scheme 亦拒绝（绝对化由页面侧完成）
        assertNull(resolveForcedExternalTarget("//evil/path", loopbackOrigin, upstreamOrigin))
    }

    @Test fun embeddedCredentialsDropped() {
        // user:pass@host 是钓鱼形态：绝不带出应用，外放也不许
        assertNull(resolveForcedExternalTarget("https://user:pass@evil.example/", loopbackOrigin, upstreamOrigin))
        assertNull(resolveForcedExternalTarget("http://user@evil.example/", loopbackOrigin, upstreamOrigin))
    }

    @Test fun oversizedTargetDropped() {
        val path = "a".repeat(8_192)
        assertNull(
            resolveForcedExternalTarget("https://evil.example/$path", loopbackOrigin, upstreamOrigin),
        )
        val oversizedLoopback = "http://127.0.0.2:51000/" + "b".repeat(8_192)
        assertNull(resolveForcedExternalTarget(oversizedLoopback, loopbackOrigin, upstreamOrigin))
    }

    @Test fun unparseableOrEmptyDropped() {
        assertNull(resolveForcedExternalTarget("", loopbackOrigin, upstreamOrigin))
        assertNull(resolveForcedExternalTarget("   ", loopbackOrigin, upstreamOrigin))
        assertNull(resolveForcedExternalTarget("http://", loopbackOrigin, upstreamOrigin))
        assertNull(resolveForcedExternalTarget("not a url", loopbackOrigin, upstreamOrigin))
    }

    @Test fun malformedLoopbackOriginFallsThroughAsRaw() {
        // 回环 origin 非法时无源可比对，整体不映射（目标原样外放）：防误归因
        assertEquals(
            "http://127.0.0.2:51000/app",
            resolveForcedExternalTarget("http://127.0.0.2:51000/app", "not a url", upstreamOrigin),
        )
    }

    @Test fun neverLoadsInPlace() {
        // 里程碑完成（各情形成立目标都必须可返回字符串或 null，无就地形态）
        val returned = resolveForcedExternalTarget("http://127.0.0.2:51000/a", loopbackOrigin, upstreamOrigin)
        assertTrue(returned != null && !returned.startsWith(loopbackOrigin))
        assertFalse(returned == null)
        // 直连拓扑同 host 目标也走"MUST 返回字符串或 null"的外放语义
        val direct = resolveForcedExternalTarget("https://zcode.z.ai:8443/c", "https://zcode.z.ai", "https://zcode.z.ai")
        assertTrue(direct == "https://zcode.z.ai:8443/c")
    }
}
