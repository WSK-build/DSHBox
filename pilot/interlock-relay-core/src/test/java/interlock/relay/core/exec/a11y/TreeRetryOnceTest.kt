package interlock.relay.core.exec.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 默认屏取树的那一次补读：只补一次，两次都空就如实回空。
 *
 * 真机上 `ui.node` 会在同一块屏、同一时刻读出空值而 `ui.snapshot` 刚成功过，两条走的是同一个
 * 提供者，所以这里能做的只是再问一次。断言只钉"问了几次"：节点对象在 JVM 单测里造不出来，
 * 也不该为了测试去依赖它。
 */
class TreeRetryOnceTest {

    @Test
    fun anEmptyReadIsAskedExactlyOnceMore() {
        var calls = 0
        val provider = DisplayTree.retryOnce {
            calls += 1
            null
        }
        assertNull(provider())
        assertEquals("只补一次，不在这里做成循环", 2, calls)
    }

    @Test
    fun theProviderDoesNotSwallowTheSecondAnswer() {
        var calls = 0
        val provider = DisplayTree.retryOnce {
            calls += 1
            null
        }
        provider()
        provider()
        assertEquals("每次调用最多两次读取，不累计", 4, calls)
    }
}
