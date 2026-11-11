package com.dshbox.dsh.renderer.api

/**
 * 主框架导航策略：宿主按会话形态选择，适配器据此分流主框架导航判定。
 */
enum class DshMainFrameNavigation {
    /**
     * 仅可信 base origin（默认）：跨 origin 的 http(s) 主框架导航取消并上报
     * [DshRendererEvent.ExternalUrlRequested]，由宿主决定外放或接管。
     */
    TrustedOriginOnly,

    /**
     * 任意 https 主框架导航放行（直连会话形态）：https 主框架不再产生
     * [DshRendererEvent.ExternalUrlRequested]；危险 scheme（见
     * [DSH_UNSAFE_MAIN_FRAME_SCHEMES]）一律取消。trustedBaseOrigin 仍承载
     * 令牌、Web 权限与 401 恢复等既有可信判定语义。
     */
    AnyHttps,
}

/**
 * [DshMainFrameNavigation.AnyHttps] 策略下一律取消（且不产生外部请求事件）的
 * 主框架 scheme 集合（小写）：可触达本地资源或脱离渲染引擎执行的 scheme，
 * 各适配器共用同一口径。
 */
val DSH_UNSAFE_MAIN_FRAME_SCHEMES: Set<String> =
    setOf("file", "content", "javascript", "intent", "blob", "data")

/** 主框架导航的裁决动作：适配器按动作放行、取消或上报后取消。 */
sealed interface DshMainFrameDecision {
    /** 交由引擎继续导航（宿主不接管）。 */
    data object Allow : DshMainFrameDecision

    /** 取消导航且不产生宿主事件。 */
    data object Cancel : DshMainFrameDecision

    /** 取消导航并上报外部请求（宿主接管打开目标）。 */
    data object External : DshMainFrameDecision
}

/**
 * 主框架导航判定（纯函数，两个适配器共用同一口径）。
 *
 * 入参语义：
 * - [scheme]：目标地址 scheme（已小写，可为 null）；
 * - [targetTrusted]：目标地址是否命中可信 base origin；
 * - [currentDocumentTrusted]：当前文档是否可信（跨文档判定基准）。
 *
 * 默认策略 [DshMainFrameNavigation.TrustedOriginOnly]：http(s) 目标在当前
 * 文档可信且目标不可信时上报外部请求并取消，其余放行——与既有行为逐字节
 * 一致。[DshMainFrameNavigation.AnyHttps]：危险 scheme 一律取消（不产生外
 * 部请求事件），https 一律放行；其余（如 http）沿用默认策略的外放判定。
 */
fun resolveMainFrameNavigation(
    mainFrameNavigation: DshMainFrameNavigation,
    scheme: String?,
    targetTrusted: Boolean,
    currentDocumentTrusted: Boolean,
): DshMainFrameDecision {
    if (mainFrameNavigation == DshMainFrameNavigation.AnyHttps) {
        if (scheme in DSH_UNSAFE_MAIN_FRAME_SCHEMES) return DshMainFrameDecision.Cancel
        if (scheme == "https") return DshMainFrameDecision.Allow
    }
    if ((scheme == "http" || scheme == "https") &&
        currentDocumentTrusted &&
        !targetTrusted
    ) {
        return DshMainFrameDecision.External
    }
    return DshMainFrameDecision.Allow
}
