package com.dshbox.remote.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话模式口径：地址非 https 时直连选择回落代理档，非 https + 直连
 * 组合不得落档（SessionModeRules 的具体语义）。
 */
class SessionModeRulesTest {

    @Test fun httpsAddressKeepsDirectSelection() {
        assertEquals(
            SessionMode.DIRECT.name,
            effectiveSessionMode("https://dsh.r2049.cn/app/abc", SessionMode.DIRECT.name),
        )
    }

    @Test fun httpAddressFallsBackToProxied() {
        assertEquals(
            SessionMode.PROXIED.name,
            effectiveSessionMode("http://dsh.r2049.cn/app/abc", SessionMode.DIRECT.name),
        )
    }

    @Test fun proxiedSelectionPassesThroughOnNonTlsAddress() {
        assertEquals(
            SessionMode.PROXIED.name,
            effectiveSessionMode("http://example.com", SessionMode.PROXIED.name),
        )
    }

    @Test fun caseInsensitiveHttpsMatching() {
        assertEquals(
            SessionMode.DIRECT.name,
            effectiveSessionMode("HTTPS://example.com/app", SessionMode.DIRECT.name),
        )
        // 明文 scheme 的大写变体（HTTP://）仍非 https：直连选择回落
        assertEquals(
            SessionMode.PROXIED.name,
            effectiveSessionMode("HTTP://example.com/app", SessionMode.DIRECT.name),
        )
    }

    @Test fun surroundingWhitespaceTrimmedBeforeMatching() {
        assertEquals(
            SessionMode.DIRECT.name,
            effectiveSessionMode("  https://example.com/app  ", SessionMode.DIRECT.name),
        )
        assertEquals(
            SessionMode.PROXIED.name,
            effectiveSessionMode("\thttp://example.com/app\n", SessionMode.DIRECT.name),
        )
    }

    @Test fun schemelessAddressFallsBack() {
        assertEquals(
            SessionMode.PROXIED.name,
            effectiveSessionMode("example.com/app", SessionMode.DIRECT.name),
        )
    }

    @Test fun blankSelectedPassesThrough() {
        // 空串未选择按原样返回（返回值与输入一致，无档位写入）
        assertEquals("", effectiveSessionMode("http://example.com", ""))
        assertEquals("", effectiveSessionMode("https://example.com", ""))
    }

    @Test fun unexpectedSelectedValuePassesThrough() {
        assertEquals("LEGACY", effectiveSessionMode("http://example.com", "LEGACY"))
        assertEquals("LEGACY", effectiveSessionMode("https://example.com", "LEGACY"))
    }
}
