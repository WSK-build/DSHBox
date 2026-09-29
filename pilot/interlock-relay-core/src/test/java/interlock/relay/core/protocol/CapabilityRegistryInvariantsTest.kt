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
     * 档位上限的三档语义逐条对照：`ASK_ONLY` 永不免弹、`SESSION_ONLY` 只在本会话内免弹、
     * `ANY` 的「始终允许」长期免弹。上限填歪等于把不可逆能力升成永久免确认。
     */
    @Test
    fun everyCeilingMeansWhatTheTierSemanticsSay() {
        CapabilityRegistry.allDescriptors.forEach { descriptor ->
            val ceiling = descriptor.ceiling
            val id = descriptor.id.wire
            assertTrue("$id 的「询问审批」档在没有会话授权时必须弹框", InterlockGate.requiresApproval(AccessTier.ASK, ceiling, sessionGranted = false))
            when (ceiling) {
                TierCeiling.ASK_ONLY -> assertTrue(
                    "$id 永不免弹",
                    InterlockGate.requiresApproval(AccessTier.ALWAYS, ceiling, sessionGranted = true),
                )

                TierCeiling.SESSION_ONLY -> {
                    assertFalse(InterlockGate.requiresApproval(AccessTier.ALWAYS, ceiling, sessionGranted = true))
                    assertTrue(InterlockGate.requiresApproval(AccessTier.ALWAYS, ceiling, sessionGranted = false))
                }

                TierCeiling.ANY -> assertFalse(InterlockGate.requiresApproval(AccessTier.ALWAYS, ceiling, sessionGranted = false))
            }
        }
    }

    /** 以 shell 身份执行的那四条能力，免弹上限固定为 ASK_ONLY：降一档等于开一条无确认的系统写通路。 */
    @Test
    fun privilegedShellCapabilitiesStayAskOnly() {
        listOf(
            CapabilityId.SECURE_SETTINGS,
            CapabilityId.APPOPS_SET,
            CapabilityId.APP_STOP,
            CapabilityId.PKG_INSTALL,
        ).forEach { assertEquals(it.wire, TierCeiling.ASK_ONLY, CapabilityRegistry.find(it)?.ceiling) }
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
     * 那几条，逐个钉住；新增一条就要先确认它没被当成放行判据。
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
}
