package interlock.relay.core.exec.a11y

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `trustedDisplay.nodeTree` 那三个词的判据。
 *
 * 这个字段是能力清单里唯一一个"量出来的"而不是"声明出来的"，所以它什么时候必须退回 untested
 * 比它说 present 的时候更要紧：说错了会让助手在一条从没查过的通路上直接放弃。
 */
class TrustedNodeTreeTest {

    @Test
    fun `nobody has looked yet`() {
        assertEquals("untested", TrustedNodeTree().state(aliveDisplayId = 45))
    }

    @Test
    fun `reports what the last look found`() {
        val probe = TrustedNodeTree()
        probe.observe(displayId = 45, treeFound = false)
        assertEquals("last-empty", probe.state(aliveDisplayId = 45))
        probe.observe(displayId = 45, treeFound = true)
        assertEquals("last-seen", probe.state(aliveDisplayId = 45))
    }

    @Test
    fun `a new display invalidates the previous answer`() {
        val probe = TrustedNodeTree()
        probe.observe(displayId = 45, treeFound = false)
        // 换了一块屏是一次新的判定：旧屏上"取不到树"不能替新屏说话。
        assertEquals("untested", probe.state(aliveDisplayId = 46))
    }

    @Test
    fun `no display alive means there is nothing to report`() {
        val probe = TrustedNodeTree()
        probe.observe(displayId = 45, treeFound = true)
        // 那块屏已经被回收：留着上一次的结论，能力清单就会替一块不存在的屏表态。
        assertEquals("untested", probe.state(aliveDisplayId = -1))
    }

    @Test
    fun `a recycled display id does not inherit the old answer`() {
        val probe = TrustedNodeTree()
        probe.observe(displayId = 45, treeFound = false)
        probe.state(aliveDisplayId = -1) // 屏被回收的那一次读
        // 屏号是可以复用的：重建出同一个 45 不能读成"已经查过、那块屏没树"。
        assertEquals("untested", probe.state(aliveDisplayId = 45))
    }
}
