package com.dshbox.dsh.renderer.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主框架导航判定纯函数的两策略口径：默认策略与既有行为逐字节一致，
 * AnyHttps 放行 https 主框架、一律拦截危险 scheme。
 */
class DshMainFrameNavigationTest {

    // ---------- TrustedOriginOnly（默认 = 既有行为） ----------

    @Test fun trustedOriginOnlyCrossOriginHttpsIsExternal() {
        assertEquals(
            DshMainFrameDecision.External,
            resolveMainFrameNavigation(
                DshMainFrameNavigation.TrustedOriginOnly,
                scheme = "https",
                targetTrusted = false,
                currentDocumentTrusted = true,
            ),
        )
    }

    @Test fun trustedOriginOnlyCrossOriginHttpIsExternal() {
        assertEquals(
            DshMainFrameDecision.External,
            resolveMainFrameNavigation(
                DshMainFrameNavigation.TrustedOriginOnly,
                scheme = "http",
                targetTrusted = false,
                currentDocumentTrusted = true,
            ),
        )
    }

    @Test fun trustedOriginOnlySameOriginIsAllowed() {
        assertEquals(
            DshMainFrameDecision.Allow,
            resolveMainFrameNavigation(
                DshMainFrameNavigation.TrustedOriginOnly,
                scheme = "https",
                targetTrusted = true,
                currentDocumentTrusted = true,
            ),
        )
    }

    @Test fun trustedOriginOnlyCrossOriginFromUntrustedDocumentIsAllowed() {
        // 现状口径：当前文档不可信时不上报外部请求（宿主未接管过该文档）
        assertEquals(
            DshMainFrameDecision.Allow,
            resolveMainFrameNavigation(
                DshMainFrameNavigation.TrustedOriginOnly,
                scheme = "https",
                targetTrusted = false,
                currentDocumentTrusted = false,
            ),
        )
    }

    @Test fun trustedOriginOnlyDoesNotInterceptUnsafeSchemes() {
        // 既有行为：非 http(s) scheme 不经外放判定（internalSchemes 之外的
        // scheme 交由引擎既有处理）
        for (scheme in DSH_UNSAFE_MAIN_FRAME_SCHEMES) {
            assertEquals(
                DshMainFrameDecision.Allow,
                resolveMainFrameNavigation(
                    DshMainFrameNavigation.TrustedOriginOnly,
                    scheme = scheme,
                    targetTrusted = false,
                    currentDocumentTrusted = true,
                ),
            )
        }
    }

    @Test fun trustedOriginOnlyNullSchemeIsAllowed() {
        assertEquals(
            DshMainFrameDecision.Allow,
            resolveMainFrameNavigation(
                DshMainFrameNavigation.TrustedOriginOnly,
                scheme = null,
                targetTrusted = false,
                currentDocumentTrusted = true,
            ),
        )
    }

    // ---------- AnyHttps（直连会话） ----------

    @Test fun anyHttpsAllowsAnyHttpsMainFrame() {
        assertEquals(
            DshMainFrameDecision.Allow,
            resolveMainFrameNavigation(
                DshMainFrameNavigation.AnyHttps,
                scheme = "https",
                targetTrusted = false,
                currentDocumentTrusted = true,
            ),
        )
        assertEquals(
            DshMainFrameDecision.Allow,
            resolveMainFrameNavigation(
                DshMainFrameNavigation.AnyHttps,
                scheme = "https",
                targetTrusted = true,
                currentDocumentTrusted = true,
            ),
        )
    }

    @Test fun anyHttpsCancelsUnsafeSchemesWithoutExternalEvent() {
        for (scheme in DSH_UNSAFE_MAIN_FRAME_SCHEMES) {
            assertEquals(
                "scheme=$scheme",
                DshMainFrameDecision.Cancel,
                resolveMainFrameNavigation(
                    DshMainFrameNavigation.AnyHttps,
                    scheme = scheme,
                    targetTrusted = false,
                    currentDocumentTrusted = true,
                ),
            )
        }
    }

    @Test fun anyHttpsHttpMainFrameStillGoesThroughExternalDecision() {
        // http 不在放行之列：沿用默认外放判定（跨 origin 上报、同 origin 放行）
        assertEquals(
            DshMainFrameDecision.External,
            resolveMainFrameNavigation(
                DshMainFrameNavigation.AnyHttps,
                scheme = "http",
                targetTrusted = false,
                currentDocumentTrusted = true,
            ),
        )
        assertEquals(
            DshMainFrameDecision.Allow,
            resolveMainFrameNavigation(
                DshMainFrameNavigation.AnyHttps,
                scheme = "http",
                targetTrusted = true,
                currentDocumentTrusted = true,
            ),
        )
    }

    @Test fun anyHttpsNullSchemeIsAllowed() {
        assertEquals(
            DshMainFrameDecision.Allow,
            resolveMainFrameNavigation(
                DshMainFrameNavigation.AnyHttps,
                scheme = null,
                targetTrusted = false,
                currentDocumentTrusted = true,
            ),
        )
    }

    @Test fun unsafeSchemeSetCoversDangerousLocalAndScriptChannels() {
        assertEquals(
            setOf("file", "content", "javascript", "intent", "blob", "data"),
            DSH_UNSAFE_MAIN_FRAME_SCHEMES,
        )
    }
}
