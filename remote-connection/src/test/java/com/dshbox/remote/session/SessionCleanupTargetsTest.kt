package com.dshbox.remote.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清理目标计算契约：删除设备聚合回环 cookie URL、已记账端点的回环 origin
 * 与直连端点的上游 origin/cookie URL（scheme 随端点真实地址），直连再追
 * 加注册域与同族站点；未记账端口端点不产 origin；槽位首次分配仅清新 IP
 * 的 cookie URL；各自去重。注册域推导、cookie 过期变体生成逐条锁定。
 */
class SessionCleanupTargetsTest {

    @Test fun deviceRemovalAggregatesLoopbackAndPorts() {
        // 设备绑定 127.0.0.3，端点 0 端口 51000 已记账、端点 1 未记账
        val (origins, cookieUrls) = SessionCleanupTargets.forDeviceRemoval(
            targetUrls = listOf("http://192.168.1.5:3080/app", "http://192.168.1.5:3081/app"),
            sessionModes = listOf("PROXIED", "PROXIED"),
            boundIp = "127.0.0.3",
            portFor = { index -> if (index == 0) 51000 else null },
        )
        assertEquals(listOf("http://127.0.0.3:51000"), origins)
        assertEquals(listOf("http://127.0.0.3/"), cookieUrls)
    }

    @Test fun deviceRemovalAddsDirectUpstreamTargets() {
        // 直连端点带真实 https 上游：origin 与 cookie URL 各进清单（含显式
        // 端口的 origin、cookie URL 不带端口）
        val (origins, cookieUrls) = SessionCleanupTargets.forDeviceRemoval(
            targetUrls = listOf("https://zcode.z.ai:8443/remote/abc"),
            sessionModes = listOf("DIRECT"),
            boundIp = null, // 直连设备也可能从未占槽位
            portFor = { _ -> null },
        )
        // 精确 host（含显式端口）之外追加注册域与同族站点
        assertEquals(
            listOf("https://zcode.z.ai:8443", "https://z.ai", "https://chat.z.ai", "https://open.bigmodel.cn", "https://bigmodel.cn"),
            origins,
        )
        assertEquals(
            listOf("https://zcode.z.ai/", "https://z.ai/", "https://chat.z.ai/", "https://open.bigmodel.cn/", "https://bigmodel.cn/"),
            cookieUrls,
        )
    }

    @Test fun deviceRemovalAddsRegisteredAndSiblingSites() {
        // z.ai 族直连端点：注册域与同族站点按 https 口径追加（z.ai、
        // chat.z.ai、open.bigmodel.cn、bigmodel.cn；注册域 z.ai 与精确 host
        // 项去重后 origin 列表仍含全部目标）
        val (origins, cookieUrls) = SessionCleanupTargets.forDeviceRemoval(
            targetUrls = listOf("https://zcode.z.ai/remote/abc"),
            sessionModes = listOf("DIRECT"),
            boundIp = null,
            portFor = { _ -> null },
        )
        assertEquals(
            listOf(
                "https://zcode.z.ai",
                "https://z.ai",
                "https://chat.z.ai",
                "https://open.bigmodel.cn",
                "https://bigmodel.cn",
            ),
            origins,
        )
        assertEquals(
            listOf(
                "https://zcode.z.ai/",
                "https://z.ai/",
                "https://chat.z.ai/",
                "https://open.bigmodel.cn/",
                "https://bigmodel.cn/",
            ),
            cookieUrls,
        )
    }

    @Test fun deviceRemovalBigModelFamilyAndDedupe() {
        // bigmodel.cn 族与重复端点：同族追加去重（族内互叠的 host 不产生重
        // 复项；origin 顺序按首次出现记入）
        val (origins, cookieUrls) = SessionCleanupTargets.forDeviceRemoval(
            targetUrls = listOf(
                "https://open.bigmodel.cn/api",
                "https://bigmodel.cn/console",
                "https://zcode.z.ai/remote/abc",
            ),
            sessionModes = listOf("DIRECT", "DIRECT", "DIRECT"),
            boundIp = null,
            portFor = { _ -> null },
        )
        assertEquals(
            listOf(
                "https://open.bigmodel.cn",
                "https://bigmodel.cn",
                "https://zcode.z.ai",
                "https://z.ai",
                "https://chat.z.ai",
            ),
            origins,
        )
        assertEquals(
            listOf(
                "https://open.bigmodel.cn/",
                "https://bigmodel.cn/",
                "https://zcode.z.ai/",
                "https://z.ai/",
                "https://chat.z.ai/",
            ),
            cookieUrls,
        )
    }

    @Test fun nonFamilyRegisteredDomainAddsRegisteredSiteOnly() {
        // 非 z.ai/bigmodel.cn 族：只加注册域本身，不加任何第三方身份域
        val (origins, _) = SessionCleanupTargets.forDeviceRemoval(
            targetUrls = listOf("https://portal.example.com/remote"),
            sessionModes = listOf("DIRECT"),
            boundIp = null,
            portFor = { _ -> null },
        )
        assertEquals(listOf("https://portal.example.com", "https://example.com"), origins)
        assertFalse(origins.any { it.contains("google.com") })
    }

    @Test fun thirdPartyIdentityDomainsNotAppended() {
        // 上游是第三方身份域（google 族，理论形态）：同族追加规则不为任何
        // 域外名册扩充第三方身份域——目标自身注册域（这里即 google.com 的
        // 追加项）不属本断言范围，断言只在「无 google 之外的追加」
        val (origins, _) = SessionCleanupTargets.forDeviceRemoval(
            targetUrls = listOf("https://accounts.google.com/signin"),
            sessionModes = listOf("DIRECT"),
            boundIp = null,
            portFor = { _ -> null },
        )
        // 仅原样 host 与其注册域（google.com）——无任何别的追加
        assertEquals(
            listOf("https://accounts.google.com", "https://google.com"),
            origins.toSet().toList(),
        )
    }

    @Test fun directHttpTargetUsesHttpCookieUrl() {
        // http 直连端点：cookie URL 用真实 scheme（http），Secure/Http 口
        // 径一致；http 不产注册域追加（同族仅 https 口径）
        val (_, cookieUrls) = SessionCleanupTargets.forDeviceRemoval(
            targetUrls = listOf("http://192.168.1.5:3080/app"),
            sessionModes = listOf("DIRECT"),
            boundIp = null,
            portFor = { _ -> null },
        )
        assertEquals(listOf("http://192.168.1.5/"), cookieUrls)
    }

    @Test fun directTargetsDedupeAndDropNonHttp() {
        // 多端点同上游 host：去重；非 http(s) 的直连地址视为无上游
        val (origins, cookieUrls) = SessionCleanupTargets.forDeviceRemoval(
            targetUrls = listOf(
                "https://zcode.z.ai/remote/abc",
                "https://zcode.z.ai:443/remote/xyz", // 缺省端口归一后同 origin
                "file:///tmp/x",
            ),
            sessionModes = listOf("DIRECT", "DIRECT", "DIRECT"),
            boundIp = "127.0.0.2",
            portFor = { _ -> null },
        )
        // 两个 https 端点缺省端口归一后同源：origin 与 cookie URL 都只一项
        // （回环 cookie URL 恒 http；https 直连端点用真实 scheme）；注册域
        // 追加一组
        assertEquals(
            listOf("https://zcode.z.ai", "https://z.ai", "https://chat.z.ai", "https://open.bigmodel.cn", "https://bigmodel.cn"),
            origins,
        )
        assertEquals(
            listOf("http://127.0.0.2/", "https://zcode.z.ai/", "https://z.ai/", "https://chat.z.ai/", "https://open.bigmodel.cn/", "https://bigmodel.cn/"),
            cookieUrls,
        )
    }

    @Test fun mixedModesAggregateAllTargets() {
        // 回环槽位 + 代理端点 + 直连端点并存：回环 cookie URL 与两个端点
        // 产出合并（各端点 origin 独立记账）
        val (origins, cookieUrls) = SessionCleanupTargets.forDeviceRemoval(
            targetUrls = listOf(
                "http://192.168.1.5:3080/app",
                "https://zcode.z.ai/remote/abc",
            ),
            sessionModes = listOf("PROXIED", "DIRECT"),
            boundIp = "127.0.0.3",
            portFor = { index -> if (index == 0) 51000 else null },
        )
        // 直连端点仍带注册域与同族追加；代理端点仅回环 origin
        assertEquals(
            listOf("http://127.0.0.3:51000", "https://zcode.z.ai", "https://z.ai", "https://chat.z.ai", "https://open.bigmodel.cn", "https://bigmodel.cn"),
            origins,
        )
        assertEquals(
            listOf("http://127.0.0.3/", "https://zcode.z.ai/", "https://z.ai/", "https://chat.z.ai/", "https://open.bigmodel.cn/", "https://bigmodel.cn/"),
            cookieUrls,
        )
    }

    @Test fun slotFirstBindTargetsNewIpHostOnly() {
        val (origins, cookieUrls) = SessionCleanupTargets.forSlotFirstBind("127.0.0.4")
        assertTrue(origins.isEmpty())
        assertEquals(listOf("http://127.0.0.4/"), cookieUrls)
    }

    @Test fun nonLoopboundIpNotSelected() {
        // 防御：异常簿记（绑定给了非回环地址）不产生 cookie URL 目标
        val (_, cookieUrls) = SessionCleanupTargets.forDeviceRemoval(
            targetUrls = emptyList(),
            sessionModes = emptyList(),
            boundIp = "10.0.0.1",
            portFor = { _ -> 51000 },
        )
        assertTrue(cookieUrls.isEmpty())
    }

    @Test fun loopbackDetection() {
        assertTrue(SessionCleanupTargets.isLoopbackHost("127.0.0.2"))
        assertFalse(SessionCleanupTargets.isLoopbackHost("10.0.0.2"))
        assertFalse(SessionCleanupTargets.isLoopbackHost("128.0.0.2"))
        assertFalse(SessionCleanupTargets.isLoopbackHost("127.0.0"))
    }

    @Test fun upstreamOriginNormalization() {
        assertEquals(
            "https://zcode.z.ai" to "https://zcode.z.ai/",
            SessionCleanupTargets.upstreamTargetOf("https://ZCODE.z.AI/remote/abc"),
        )
        assertEquals(
            "http://192.168.1.5:3080" to "http://192.168.1.5/",
            SessionCleanupTargets.upstreamTargetOf("http://192.168.1.5:3080/app"),
        )
        assertNull(SessionCleanupTargets.upstreamTargetOf("http:///path-only"))
        assertNull(SessionCleanupTargets.upstreamTargetOf(null))
        assertNull(SessionCleanupTargets.upstreamTargetOf("  "))
    }

    // ---- 注册域推导 ----

    @Test fun registeredDomainFromTwoThreeAndFourLabelHosts() {
        // 两标签（注册域自身）、三标签与更深子域：末两标签
        assertEquals("z.ai", SessionCleanupTargets.registeredDomainOf("z.ai"))
        assertEquals("z.ai", SessionCleanupTargets.registeredDomainOf("zcode.z.ai"))
        assertEquals("bigmodel.cn", SessionCleanupTargets.registeredDomainOf("open.bigmodel.cn"))
        assertEquals("example.com", SessionCleanupTargets.registeredDomainOf("a.b.c.example.com"))
        // 大小写归一
        assertEquals("z.ai", SessionCleanupTargets.registeredDomainOf("ZCODE.Z.AI"))
    }

    @Test fun registeredDomainNullForIpSingleLabelAndBrackets() {
        // 点分 IPv4、方括号 IPv6（防御兜底）、单标签、空串与 null：无注册域
        assertNull(SessionCleanupTargets.registeredDomainOf("192.168.1.5"))
        assertNull(SessionCleanupTargets.registeredDomainOf("127.0.0.2"))
        assertNull(SessionCleanupTargets.registeredDomainOf("[fe80::1]"))
        assertNull(SessionCleanupTargets.registeredDomainOf("intranet"))
        assertNull(SessionCleanupTargets.registeredDomainOf(""))
        assertNull(SessionCleanupTargets.registeredDomainOf("  "))
        assertNull(SessionCleanupTargets.registeredDomainOf(null))
    }

    @Test fun registeredDomainStripsPortBeforeLabelSplit() {
        // 权威形 host:port（与 cookieDomainCandidates 同口径）：端口段不进
        // 标签切分——带端口的 host 仍能取到注册域
        assertEquals("example.com", SessionCleanupTargets.registeredDomainOf("portal.example.com:8443"))
        assertEquals("z.ai", SessionCleanupTargets.registeredDomainOf("zcode.z.ai:443"))
    }

    @Test fun registeredDomainNullForBracketedIpv6WithPort() {
        // 方括号 IPv6 的完整 authority 形态：IPv6 字面量无注册域（端口在
        // 方括号之后，一并按方括号归一拒绝）
        assertNull(SessionCleanupTargets.registeredDomainOf("[fe80::1]:8080"))
    }

    @Test fun registeredDomainFromTargetHttpsOnly() {
        // https 直连：产注册域；http 与非 http(s)：不产
        assertEquals(
            listOf("z.ai"),
            SessionCleanupTargets.registeredDomainsFromTarget("https://zcode.z.ai/remote/abc"),
        )
        assertTrue(SessionCleanupTargets.registeredDomainsFromTarget("http://lan.example.com/app").isEmpty())
        assertTrue(SessionCleanupTargets.registeredDomainsFromTarget("https://192.168.1.5/").isEmpty())
        assertTrue(SessionCleanupTargets.registeredDomainsFromTarget("file:///tmp/x").isEmpty())
        assertTrue(SessionCleanupTargets.registeredDomainsFromTarget(null).isEmpty())
    }

    // ---- 同族追加规则 ----

    @Test fun siblingSitesForZaiAndBigmodelCn() {
        for (domain in listOf("z.ai", "bigmodel.cn")) {
            assertEquals(
                listOf("z.ai", "chat.z.ai", "open.bigmodel.cn", "bigmodel.cn"),
                SessionCleanupTargets.siblingSitesFor(domain),
            )
        }
    }

    @Test fun siblingSitesEmptyForOtherDomains() {
        // 第三方身份域与其余域：不追加任何站点
        assertTrue(SessionCleanupTargets.siblingSitesFor("google.com").isEmpty())
        assertTrue(SessionCleanupTargets.siblingSitesFor("accounts.google.com").isEmpty())
        assertTrue(SessionCleanupTargets.siblingSitesFor("example.com").isEmpty())
        assertTrue(SessionCleanupTargets.siblingSitesFor("zcode.z.ai").isEmpty())
    }

    // ---- cookie 域候选生成 ----

    @Test fun domainCandidatesForHostIsExactHostOnly() {
        // 多标签 host：仅精确 host（Domain=<host>）；父域候选（`.parent`）
        // 刻意不产——父域级通配会误伤无关站点，同族站点已作为独立精确目标
        assertEquals(
            listOf("zcode.z.ai"),
            SessionCleanupTargets.cookieDomainCandidates("https://zcode.z.ai/"),
        )
        assertEquals(
            listOf("lan.example.com"),
            SessionCleanupTargets.cookieDomainCandidates("http://lan.example.com/app"),
        )
    }

    @Test fun domainCandidatesNeverParentDomainForTwoAndThreeLabelHosts() {
        // 两段与三段 host 一律不产父域候选：`.com`、`.z.ai` 均不出现
        assertEquals(
            listOf("example.com"),
            SessionCleanupTargets.cookieDomainCandidates("https://example.com/"),
        )
        assertFalse(
            SessionCleanupTargets.cookieDomainCandidates("https://example.com/")
                .any { it.startsWith(".") },
        )
        assertFalse(
            SessionCleanupTargets.cookieDomainCandidates("https://chat.z.ai/")
                .any { it.startsWith(".") },
        )
    }

    @Test fun domainCandidatesEmptyForSingleLabelAndIp() {
        // 单标签 host 与回环字面 IP：无域候选
        assertTrue(SessionCleanupTargets.cookieDomainCandidates("http://localhost/").isEmpty())
        assertTrue(SessionCleanupTargets.cookieDomainCandidates("http://127.0.0.2/").isEmpty())
        assertTrue(SessionCleanupTargets.cookieDomainCandidates("https://192.168.1.5/").isEmpty())
        assertTrue(SessionCleanupTargets.cookieDomainCandidates("https://intranet/d/").isEmpty())
    }

    @Test fun domainCandidatesEmptyForBracketsAndMalformed() {
        // 方括号 IPv6 与畸形 URL 不产域候选
        assertTrue(SessionCleanupTargets.cookieDomainCandidates("https://[fe80::1]/").isEmpty())
        assertTrue(SessionCleanupTargets.cookieDomainCandidates("not-a-url").isEmpty())
    }

    // ---- cookie 过期变体生成 ----

    @Test fun hostPrefixedCookieExpiredWithSecurePathOnly() {
        // __Host- 名：Secure; Path=/（此类 cookie 的强制属性；带 Domain 的
        // 写法会被拒收、清不掉）+ host-only 分区变体（分区 cookie 同样合法
        // 存在于 __Host- 前缀，须一并覆盖）
        assertEquals(
            listOf(
                "__Host-session=; Max-Age=0; Path=/; Secure",
                "__Host-session=; Max-Age=0; Path=/; Secure; SameSite=None; Partitioned",
            ),
            SessionCleanupTargets.cookieExpireValuesFor("__Host-session", "https://zcode.z.ai/"),
        )
    }

    @Test fun securePrefixedCookieExpiredWithSecureAndTwoDomainVariants() {
        // __Secure- 名：根 Path 一次 + 两种 Domain 变体（全部带 Secure）
        // + host-only 分区变体
        assertEquals(
            listOf(
                "__Secure-gate=; Max-Age=0; Path=/; Secure",
                "__Secure-gate=; Max-Age=0; Path=/; Secure; Domain=zcode.z.ai",
                "__Secure-gate=; Max-Age=0; Path=/; Secure; Domain=.zcode.z.ai",
                "__Secure-gate=; Max-Age=0; Path=/; Secure; SameSite=None; Partitioned",
            ),
            SessionCleanupTargets.cookieExpireValuesFor("__Secure-gate", "https://zcode.z.ai/"),
        )
    }

    @Test fun plainCookieExpiredBothSecureStatesWithDomainVariants() {
        // 普通名：不带/带 Secure 各一次 + 两种 Domain 变体（不带 Domain 写
        // 不掉域 cookie，域 cookie 的 Secure 态只能由 Domain 写法命中）
        // + host-only 分区变体；带 Domain 的分区 cookie 不合法，分区变体
        // 恒 host-only 一种
        assertEquals(
            listOf(
                "sid=; Max-Age=0; Path=/",
                "sid=; Max-Age=0; Path=/; Secure",
                "sid=; Max-Age=0; Path=/; Secure; Domain=lan.example.com",
                "sid=; Max-Age=0; Path=/; Secure; Domain=.lan.example.com",
                "sid=; Max-Age=0; Path=/; Domain=lan.example.com",
                "sid=; Max-Age=0; Path=/; Domain=.lan.example.com",
                "sid=; Max-Age=0; Path=/; Secure; SameSite=None; Partitioned",
            ),
            SessionCleanupTargets.cookieExpireValuesFor("sid", "http://lan.example.com/app"),
        )
    }

    @Test fun cookieExpirationForIpSiteHasNoDomainVariants() {
        // IP 站点：__Host- 根 Path + 分区变体；__Secure- 无 Domain 变体
        // （仍含分区变体）；普通名两条根 Path 变体 + 分区变体（IP 无域归
        // 属，Domain 写法只会写废；分区变体恒 host-only，不受影响）
        assertEquals(
            listOf(
                "__Host-c=; Max-Age=0; Path=/; Secure",
                "__Host-c=; Max-Age=0; Path=/; Secure; SameSite=None; Partitioned",
            ),
            SessionCleanupTargets.cookieExpireValuesFor("__Host-c", "https://192.168.1.5/"),
        )
        assertEquals(
            listOf(
                "__Secure-c=; Max-Age=0; Path=/; Secure",
                "__Secure-c=; Max-Age=0; Path=/; Secure; SameSite=None; Partitioned",
            ),
            SessionCleanupTargets.cookieExpireValuesFor("__Secure-c", "https://192.168.1.5/"),
        )
        assertEquals(
            listOf(
                "c=; Max-Age=0; Path=/",
                "c=; Max-Age=0; Path=/; Secure",
                "c=; Max-Age=0; Path=/; Secure; SameSite=None; Partitioned",
            ),
            SessionCleanupTargets.cookieExpireValuesFor("c", "https://192.168.1.5/"),
        )
    }

    @Test fun cookieExpirationForSingleLabelHostHasNoDomainVariants() {
        // 单标签 host 无域候选：两条根 Path 变体 + 分区变体
        assertEquals(
            listOf(
                "c=; Max-Age=0; Path=/",
                "c=; Max-Age=0; Path=/; Secure",
                "c=; Max-Age=0; Path=/; Secure; SameSite=None; Partitioned",
            ),
            SessionCleanupTargets.cookieExpireValuesFor("c", "http://intranet/"),
        )
    }

    @Test fun loopbackCookieExpirationStaysPlain() {
        // 回环槽位 cookie（127.0.0.x）无域候选：按普通名两态收口（回环代理
        // 恒 http、无 __Host- 需求，但域归属为空不产生 Domain 写法）+ 分区
        // 变体（写经 http URL 时 Secure 键不命中，无害兜底）
        assertEquals(
            listOf(
                "gate=; Max-Age=0; Path=/",
                "gate=; Max-Age=0; Path=/; Secure",
                "gate=; Max-Age=0; Path=/; Secure; SameSite=None; Partitioned",
            ),
            SessionCleanupTargets.cookieExpireValuesFor("gate", "http://127.0.0.2/"),
        )
    }

    @Test fun cookieExpirationAlwaysIncludesHostOnlyPartitionedVariant() {
        // 分区（CHIPS）cookie 覆盖统一收口：普通名 / __Host- / __Secure- 均恰
        // 带一条 host-only 分区串；分区写法恒 host-only（带 Domain 的分区
        // cookie 不合法，不产 Domain 分区变体），其余变体不含分区属性
        val tail = " Path=/; Secure; SameSite=None; Partitioned"
        val cases = listOf("sid", "__Host-session", "__Secure-gate")
        for (name in cases) {
            val values = SessionCleanupTargets.cookieExpireValuesFor(
                name, "https://zwork.z.ai/",
            )
            assertEquals("$name=; Max-Age=0;$tail", values.last())
            assertEquals(1, values.count { it.endsWith(tail) })
            // 带 Domain 变体的名字其 Domain 变体一律不带分区属性
            assertFalse(values.dropLast(1).any { it.contains("Partitioned") })
        }
        // __Host- 无 Domain 变体：根 Path 变体 + 分区变体共两条
        assertEquals(
            2,
            SessionCleanupTargets.cookieExpireValuesFor(
                "__Host-session", "https://zwork.z.ai/",
            ).size,
        )
    }
}
