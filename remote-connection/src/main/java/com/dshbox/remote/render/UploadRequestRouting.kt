package com.dshbox.remote.render

import com.dshbox.dsh.renderer.api.DshAsyncRequestId
import com.dshbox.dsh.renderer.api.DshSelectedFile

/**
 * 远程页面文件选择请求的分派判定（纯函数，JVM 可测）。
 *
 * 宿主提供了附件来源面板（[com.dshbox.remote.RemoteConnectionHost.pickUploadFiles]
 * 被覆写）时，渲染宿主改走面板通道；面板结果按三态约定分派：
 *
 *  - null = 宿主未提供面板 → 退回既有系统选择器路径（保持既有行为）；
 *  - 空列表 = 用户在面板中取消 → 以「取消」结清该请求（`<input>` 不被锁死）；
 *  - 非空列表 = 选中文件 → 直接回填页面。
 */
internal enum class UploadPanelOutcome {

    /** 退回既有系统选择器路径（宿主未提供来源面板）。 */
    FALLBACK_TO_SYSTEM_PICKER,

    /** 以「取消」结清本次请求。 */
    CANCEL,

    /** 以选中文件回填本次请求。 */
    COMPLETE,
}

internal object UploadRequestRouting {

    /**
     * 来源面板结果 → 分派判定。
     *
     * 空列表与 null 的语义分离是有意的：null 只表达「宿主没有这个能力」，
     * 用户在面板里明确放弃必须结清为取消，而不是再外拉一个系统选择器。
     */
    fun outcomeOf(panelResult: List<DshSelectedFile>?): UploadPanelOutcome = when {
        panelResult == null -> UploadPanelOutcome.FALLBACK_TO_SYSTEM_PICKER
        panelResult.isEmpty() -> UploadPanelOutcome.CANCEL
        else -> UploadPanelOutcome.COMPLETE
    }

    /**
     * 迟到的面板结果/取消是否应结清挂起请求：按请求 id 配对——面板是长挂起
     * 操作，期间新请求可能已顶替旧请求，结清动作必须只作用于发起它的那次
     * 请求，否则会把顶替后的新请求误结清（页面 `<input>` 被锁死）。
     * [pendingRequestId] 为 null（无挂起）或不匹配（已顶替/已回填）一律不结清。
     */
    fun shouldSettle(pendingRequestId: DshAsyncRequestId?, requestId: DshAsyncRequestId): Boolean =
        pendingRequestId == requestId && pendingRequestId != null
}
