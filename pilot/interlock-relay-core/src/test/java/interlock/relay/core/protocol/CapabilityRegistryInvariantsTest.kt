package interlock.relay.core.protocol

import interlock.relay.core.interlock.InterlockGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两层闸门（系统权限 × 助手档位）的全部输入都来自这张注册表：能力清单、界面红点与
 * 真放行读的是同一份描述符。注册表里多一条、少一条或某格填错，三处口径立刻分开，
 * 表现就是「能力清单说可用、调用却拿不到后端」这类看不出来源的错误。
 */
class CapabilityRegistryInvariantsTest {

    @Test
    fun everyCapabilityHasExactlyOneDescriptor() {
        assertEquals(CapabilityId.entries.size, CapabilityRegistry.allDescriptors.size)
        assertEquals(CapabilityId.entries.size, CapabilityRegistry.byId.size)
        CapabilityId.entries.forEach { assertNotNull(it.wire, CapabilityRegistry.find(it)) }
        CapabilityRegistry.allDescriptors.forEach { descriptor ->
            assertEquals(descriptor, CapabilityRegistry.find(descriptor.id))
        }
    }

    @Test
    fun wireNamesAreUniqueAndBothLookupsAgree() {
        val wires = CapabilityId.entries.map { it.wire }
        assertEquals("通道标识不允许重复", wires.size, wires.distinct().size)
        wires.forEach { wire ->
            val resolved = CapabilityId.fromWire(wire)
            assertNotNull(wire, resolved)
            assertSame(CapabilityRegistry.find(resolved!!), CapabilityRegistry.find(wire))
        }
    }

    @Test
    fun categoriesPartitionTheRegistry() {
        val bucketed = CapabilityCategory.entries.flatMap { CapabilityRegistry.inCategory(it) }
        assertEquals(bucketed.size, bucketed.map { it.id }.distinct().size)
        CapabilityCategory.entries.forEach { category ->
            assertEquals(
                category.name,
                CapabilityId.entries.count { it.category == category },
                CapabilityRegistry.inCategory(category).size,
            )
        }
    }

    @Test
    fun surfacesOnlyEverNameKnownExecutionModes() {
        val known = SurfaceKind.entries.toSet()
        CapabilityRegistry.allDescriptors.forEach {
            assertTrue(it.id.wire, known.containsAll(it.surfaces))
        }
    }

    /** 「仅观测」按定义不注入输入：一条控制类能力挂上它，界面就会出现一个跑不通的模式。 */
    @Test
    fun injectiveCapabilitiesNeverListTheObserveOnlyMode() {
        CapabilityRegistry.allDescriptors
            .filter { it.id.category == CapabilityCategory.CONTROL }
            .forEach { assertFalse(it.id.wire, SurfaceKind.OBSERVE_ONLY in it.surfaces) }
    }

    /**
     * 三档行为对注册表里每一条能力统一：弹不弹只由档位与会话授权决定，
     * 描述符上保留的上限元数据不参与判定。少钉一种，就会出现「某一类能力
     * 选了完全访问仍被别处压回询问」的第二套规则。
     */
    @Test
    fun tierAloneDecidesApprovalForEveryCapability() {
        CapabilityRegistry.allDescriptors.forEach { descriptor ->
            val id = descriptor.id.wire
            assertTrue(
                "$id 询问审批档没有会话授权时必须弹框",
                InterlockGate.requiresApproval(AccessTier.ASK, sessionGranted = false),
            )
            assertFalse(
                "$id 询问审批档有会话授权时免弹",
                InterlockGate.requiresApproval(AccessTier.ASK, sessionGranted = true),
            )
            assertFalse(
                "$id 完全访问档免弹",
                InterlockGate.requiresApproval(AccessTier.ALWAYS, sessionGranted = false),
            )
        }
    }

    @Test
    fun backendsAreListedOnceAndNeverEmpty() {
        CapabilityRegistry.allDescriptors.forEach {
            assertTrue(it.id.wire, it.backends.isNotEmpty())
            assertEquals(it.id.wire, it.backends.size, it.backends.distinct().size)
        }
    }

    /**
     * 模块最低可运行版本是 29（本模块 `build.gradle.kts` 的 `defaultConfig.minSdk`，
     * 也是注册表与描述符两处默认值）。低于它的只允许是「记录平台接口从哪一版起存在」
     * 对应条目逐项验证；新增条目也不得将该版本值作为放行判据。
     */
    @Test
    fun minSdkNeverDropsBelowTheModuleFloorUnannounced() {
        val apiLandings = mapOf(
            CapabilityId.SCREEN_OBSERVE to 21,
            CapabilityId.SCREEN_RECORD to 21,
            CapabilityId.NOTIFY_READ to 22,
            CapabilityId.UI_TAP to 24,
            CapabilityId.UI_SWIPE to 24,
            CapabilityId.UI_TEXT to 24,
        )
        CapabilityRegistry.allDescriptors.forEach { descriptor ->
            val landed = apiLandings[descriptor.id]
            if (landed == null) {
                assertTrue(descriptor.id.wire, descriptor.minSdk >= MODULE_MIN_SDK)
            } else {
                assertEquals(descriptor.id.wire, landed, descriptor.minSdk)
            }
        }
    }

    /**
     * 注册表自己给出的版本号门槛必须与引用它们的口径一致，否则声明与判据各一套：
     * `SCREENSHOT_MIN_SDK` 是**无障碍截图路线**的最低版本（路线探测引用），
     * 不再当能力级下限——`screen.capture` 的 MediaProjection 路线在模块下限上就能跑；
     * `TRUSTED_DISPLAY_MIN_SDK` 仍是 `surface.virtual` 的能力级下限。
     */
    @Test
    fun namedSdkFloorsAreAppliedToTheCapabilitiesTheyName() {
        assertEquals(
            MODULE_MIN_SDK,
            CapabilityRegistry.find(CapabilityId.SCREEN_CAPTURE)?.minSdk,
        )
        assertEquals(CapabilityRegistry.SCREENSHOT_MIN_SDK, 30)
        assertEquals(CapabilityRegistry.TRUSTED_DISPLAY_MIN_SDK, CapabilityRegistry.find(CapabilityId.SURFACE_VIRTUAL)?.minSdk)
    }

    private companion object {
        const val MODULE_MIN_SDK = 29
    }

    /**
     * 唯一的成员过滤函数 [CapabilityRegistry.ordinaryMembers]：成员资格只看描述符上的
     * `privileged` 位，`globalTierEligible` 只是它的派生属性。40 条普通能力全入；
     * 完全 Shizuku（`sys.shell.full`）是唯一的特权能力，被它排除——能力列表、首页
     * 「可用能力」计数、全局档位选择器与批量拨档消费的都是这一个集合。
     */
    @Test
    fun ordinaryMembersFollowsThePrivilegedFlagAlone() {
        val registry = CapabilityRegistry.allDescriptors
        val members = CapabilityRegistry.ordinaryMembers(registry)
        assertEquals(40, members.size)
        assertTrue(members.none { it.id == CapabilityId.SYS_SHELL_FULL })
        assertEquals(
            (CapabilityId.entries.toSet() - CapabilityId.SYS_SHELL_FULL),
            members.map { it.id }.toSet(),
        )
        // 全局档位成员位与特权位是同一条判据的两种读法：逐条等价，不允许第二套成员位。
        registry.forEach { descriptor ->
            assertEquals(descriptor.id.wire, !descriptor.privileged, descriptor.globalTierEligible)
        }
        assertEquals(
            members.map { it.id }.toSet(),
            registry.filter { it.globalTierEligible }.map { it.id }.toSet(),
        )

        // 把一条普通能力标成特权，成员集恰好少它一个；资格是身份不是状态，
        // 档位拨成禁止、系统权限缺失都不在函数输入里，自然不改变成员集。
        val exited = registry.first { it.id != CapabilityId.SYS_SHELL_FULL }.copy(privileged = true)
        val mixed = registry.map { if (it.id == exited.id) exited else it }
        val mixedMembers = CapabilityRegistry.ordinaryMembers(mixed)
        assertEquals(members.size - 1, mixedMembers.size)
        assertFalse(mixedMembers.any { it.id == exited.id })
    }

    /**
     * 设备状态的描述符口径：只读一组基础数据、无 Shizuku（唯一后端 DIRECT）、
     * 运行期权限表为空——唯一的外部依赖 `ACCESS_NETWORK_STATE` 是安装时普通权限，
     * 声明即授予；列进 runtimePermissions 会让探测把这条能力报成「缺权限待授」，
     * 而系统侧没有任何要授的内容（能力清单的「缺权限」红点会永远点不掉）。
     */
    @Test
    fun deviceStatusNeedsNoShizukuAndNoRuntimePermission() {
        val descriptor = CapabilityRegistry.allDescriptors.single { it.id == CapabilityId.DEVICE_STATUS }
        assertEquals(listOf(BackendId.DIRECT), descriptor.backends)
        assertTrue(descriptor.runtimePermissions.isEmpty())
    }
}
