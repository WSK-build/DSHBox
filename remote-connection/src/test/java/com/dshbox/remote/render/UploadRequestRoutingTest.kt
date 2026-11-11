package com.dshbox.remote.render

import com.dshbox.dsh.renderer.api.DshAsyncRequestId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 远程页文件选择请求的分派判定测试：宿主来源面板结果的三态分派（null=宿主
 * 未提供 → 系统选择器回退；空=用户取消 → 结清取消；非空=选中 → 回填）。
 *
 * Android UI 链路（面板交互、系统选择器回调、渲染器回填）无法在 JVM 覆盖，
 * 这里锁定判定收口；宿主匿名对象转调协调器的接线属 UI 装配，不在 JVM 覆盖。
 */
class UploadRequestRoutingTest {

    @Test
    fun nullResultMeansHostHasNoPanelAndFallsBackToSystemPicker() {
        assertEquals(
            UploadPanelOutcome.FALLBACK_TO_SYSTEM_PICKER,
            UploadRequestRouting.outcomeOf(null),
        )
    }

    @Test
    fun emptyResultMeansUserCancelledAndSettlesAsCancel() {
        assertEquals(
            UploadPanelOutcome.CANCEL,
            UploadRequestRouting.outcomeOf(emptyList()),
        )
    }

    @Test
    fun nonEmptyResultCompletesTheRequest() {
        // COMPLETE 分派只看「列表非空」，元素永不被解引用；JVM 单测无法构造
        // DshSelectedFile（android.net.Uri 的 stub 方法在单测里不可调用），
        // 用未检查 cast 造一个占位非空列表，不为测试放宽生产签名。
        @Suppress("UNCHECKED_CAST")
        val nonEmpty = listOf<Any?>(Unit) as List<com.dshbox.dsh.renderer.api.DshSelectedFile>
        assertEquals(
            UploadPanelOutcome.COMPLETE,
            UploadRequestRouting.outcomeOf(nonEmpty),
        )
    }

    @Test
    fun settleMatchesOnlyTheRequestThatOwnsThePendingSlot() {
        // 结清必须带请求 id 配对：面板/系统选择器是长挂起操作，期间新请求
        // 可能已顶替旧请求——只有发起结清的那次请求仍在挂起位才允许结清
        val first = DshAsyncRequestId(1)
        val second = DshAsyncRequestId(2)
        assertEquals(true, UploadRequestRouting.shouldSettle(first, first))
        assertEquals(false, UploadRequestRouting.shouldSettle(second, first))
    }

    @Test
    fun settleWithoutPendingRequestIsIgnored() {
        // 无挂起（已回填/已结清）时迟到的不结清
        assertEquals(false, UploadRequestRouting.shouldSettle(null, DshAsyncRequestId(3)))
    }
}
