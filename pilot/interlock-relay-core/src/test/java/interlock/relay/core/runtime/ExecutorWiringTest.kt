package interlock.relay.core.runtime

import interlock.relay.core.exec.BackendCall
import interlock.relay.core.exec.BackendResult
import interlock.relay.core.exec.RelayBackend
import interlock.relay.core.protocol.BackendId
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.CapabilityRegistry
import interlock.relay.core.protocol.SurfaceKind
import interlock.relay.core.spi.RelayExecutor
import interlock.relay.core.surface.BackendDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行装配口（[RelayExecutor]）的接线测试。
 *
 * 容器本身要 Context，纯 JVM 里装不起来，所以接线被抽成 [configuredBackends] 单独验证。
 * 这里钉三件事：缺省时内建表原样可用、注入了装配口就一定被调用、以及**分发器只认返回表**——
 * 摘掉一条后端，能力不会从内建那份悄悄补回来。
 */
class ExecutorWiringTest {

    private val builtIn: Map<BackendId, RelayBackend> = mapOf(
        BackendId.DIRECT to FakeBackend(),
        BackendId.A11Y to FakeBackend(),
        BackendId.SHIZUKU to FakeBackend(),
    )

    @Test
    fun absentExecutorKeepsTheBuiltInTableUntouched() {
        val table = configuredBackends(null, builtIn)

        assertEquals(setOf(BackendId.DIRECT, BackendId.A11Y, BackendId.SHIZUKU), table.keys)
        assertSame(builtIn[BackendId.A11Y], table[BackendId.A11Y])
    }

    @Test
    fun injectedExecutorIsAlwaysConsulted() {
        var seen: Map<BackendId, RelayBackend>? = null
        val policy = RelayExecutor { table ->
            seen = table
            table
        }

        val result = configuredBackends(policy, builtIn)

        assertEquals("装配口必须拿到完整的内建表", builtIn.keys, seen?.keys)
        assertSame("返回的那张表就是可用集，不能被再复制或改写", seen, result)
    }

    @Test
    fun removedBackendDisappearsFromTheConfiguredTable() {
        val table = configuredBackends(RelayExecutor { it - BackendId.SHIZUKU }, builtIn)

        assertEquals(setOf(BackendId.DIRECT, BackendId.A11Y), table.keys)
        assertFalse(BackendId.SHIZUKU in table)
    }

    @Test
    fun replacedBackendIsTheHostInstance() {
        val mine = FakeBackend()

        val table = configuredBackends(RelayExecutor { it + (BackendId.DIRECT to mine) }, builtIn)

        assertSame(mine, table[BackendId.DIRECT])
        assertSame("只换一条，其余保持内建那份", builtIn[BackendId.A11Y], table[BackendId.A11Y])
    }

    @Test
    fun dispatcherServesFromTheConfiguredTableNotTheBuiltInOne() {
        val descriptor = CapabilityRegistry.allDescriptors.first { BackendId.A11Y in it.backends }
        val surface = SurfaceKind.FOREGROUND

        // 只有无障碍一条后端时它承担这条能力；这也是后面"摘掉"要观察的基准。
        val onlyA11y: Map<BackendId, RelayBackend> = mapOf(BackendId.A11Y to FakeBackend())
        assertTrue(BackendDispatcher(onlyA11y).canServe(descriptor, surface))
        assertSame(onlyA11y[BackendId.A11Y], BackendDispatcher(onlyA11y).pick(descriptor, surface))

        // 装配口把这张表清空：分发器必须承认"没有后端能承担"，而不是回落到内建三件套。
        val emptied = configuredBackends(RelayExecutor { emptyMap() }, onlyA11y)
        assertFalse(BackendDispatcher(emptied).canServe(descriptor, surface))
        assertNull(BackendDispatcher(emptied).pick(descriptor, surface))
    }

    /** 只为装配表存在：这些用例都不触发执行，任何一次调用都说明接线错了。 */
    private class FakeBackend : RelayBackend {
        override suspend fun execute(call: BackendCall): BackendResult =
            error("装配测试不应触发执行")

        override fun supports(capability: CapabilityId, surface: SurfaceKind): Boolean = true

        override fun available(): Boolean = true
    }
}
