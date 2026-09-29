package interlock.relay.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统采集弹框那一页的销毁判定。
 *
 * 配置重建（转屏、深色切换、语言切换）也会走一遍 onDestroy，但那不是用户在拒绝：
 * 在那里回填取消，等于替用户编造一个他从未做出的「拒绝」，等待方会拿它当真。
 * 只有这一页真的在结束（isFinishing）且不是配置重建时，才允许回一个「取消」。
 */
class RelayConsentActivityRecreateTest {

    /** 用户按了返回、或系统回收这一页：等待方必须立刻知道，回填取消。 */
    @Test
    fun aRealFinishReportsCancel() {
        assertTrue(
            RelayConsentActivity.shouldReportCancelOnDestroy(
                isFinishing = true,
                changingConfigurations = false,
            ),
        )
    }

    /** 配置重建不回填：重建实例重新登记回调并续接同一次握手。 */
    @Test
    fun aConfigurationRebuildDoesNotReportCancel() {
        assertFalse(
            RelayConsentActivity.shouldReportCancelOnDestroy(
                isFinishing = false,
                changingConfigurations = true,
            ),
        )
        assertFalse(
            "两个标志同时为真时更不能替用户认下取消",
            RelayConsentActivity.shouldReportCancelOnDestroy(
                isFinishing = true,
                changingConfigurations = true,
            ),
        )
    }

    /** 活着的页面自然不回填任何东西。 */
    @Test
    fun aLivePageReportsNothing() {
        assertFalse(
            RelayConsentActivity.shouldReportCancelOnDestroy(
                isFinishing = false,
                changingConfigurations = false,
            ),
        )
    }
}
