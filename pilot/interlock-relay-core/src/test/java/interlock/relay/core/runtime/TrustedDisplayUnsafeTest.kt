package interlock.relay.core.runtime

import interlock.relay.core.exec.shizuku.ShizukuBackend
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「可信屏不安全」（自检判过"建屏破坏主屏"）在**调用面**上的完整语义
 * （回归矩阵）：
 *
 * - unsafe 只封**自动后台路由**（supports 的后台落点、装配根的 ready/建屏准备），
 * 不封 surface.virtual 的 create/release/query——它们是复检与解除标记的活通路，
 * 封了就是不可解除的死锁（早期实现的阻断场景）；
 * - 后台落点封死必须覆盖「release 失败、屏还残留」的场景：displayAlive=true 也要 false；
 * - unsafe 可解除：建屏+自检通过、release 成功、通道收摊、进程重启各有一个纯判据。
 */
class TrustedDisplayUnsafeTest {

    private val backgroundCaps = listOf(
        CapabilityId.APP_LAUNCH,
        CapabilityId.UI_TAP,
        CapabilityId.UI_SWIPE,
        CapabilityId.UI_TEXT,
        CapabilityId.UI_KEY,
        CapabilityId.SCREEN_CAPTURE,
        CapabilityId.SCREEN_RECORD,
    )

    /** 建屏/收屏/问屏（surface.virtual）是显式恢复通路：unsafe 与否、屏在与不在都可调。 */
    @Test
    fun surfaceVirtualStaysCallableWhileUnsafe() {
        listOf(true, false).forEach { unsafe ->
            listOf(true, false).forEach { alive ->
                assertTrue(
                    "unsafe=$unsafe alive=$alive: create/release/query 必须可达",
                    ShizukuBackend.trustedSurfaceSupported(
                        CapabilityId.SURFACE_VIRTUAL, SurfaceKind.BACKEND_TRUSTED, alive, unsafe,
                    ),
                )
            }
        }
    }

    /**
     * unsafe 封死全部后台落点——**含 release 失败、屏还残留**（alive=true）的场景：
     * 后台调用一律回前台，不许继续用坏屏。
     */
    @Test
    fun backgroundCapabilitiesAreBlockedWhileUnsafeEvenIfTheScreenSurvived() {
        backgroundCaps.forEach { cap ->
            assertTrue(
                "$cap: 屏残留 + unsafe 也必须封",
                !ShizukuBackend.trustedSurfaceSupported(cap, SurfaceKind.BACKEND_TRUSTED, displayAlive = true, unsafeMarked = true),
            )
            assertTrue(
                "$cap: 屏不在 + unsafe 同样封（现状一致）",
                !ShizukuBackend.trustedSurfaceSupported(cap, SurfaceKind.BACKEND_TRUSTED, displayAlive = false, unsafeMarked = true),
            )
        }
    }

    /** 没有 unsafe 时回到原判据：后台落点只看屏在不在。 */
    @Test
    fun withoutUnsafeThePreviousAliveRuleStands() {
        backgroundCaps.forEach { cap ->
            assertTrue(
                ShizukuBackend.trustedSurfaceSupported(cap, SurfaceKind.BACKEND_TRUSTED, displayAlive = true, unsafeMarked = false),
            )
            assertFalse(
                ShizukuBackend.trustedSurfaceSupported(cap, SurfaceKind.BACKEND_TRUSTED, displayAlive = false, unsafeMarked = false),
            )
        }
    }

    /** 非 trusted 执行面、表外能力：真值表不越界。 */
    @Test
    fun theTruthTableStaysInsideTheTrustedSurface() {
        assertFalse(
            ShizukuBackend.trustedSurfaceSupported(
                CapabilityId.SURFACE_VIRTUAL, SurfaceKind.FOREGROUND, displayAlive = true, unsafeMarked = false,
            ),
        )
        assertFalse(
            ShizukuBackend.trustedSurfaceSupported(
                CapabilityId.SYS_SHELL, SurfaceKind.BACKEND_TRUSTED, displayAlive = true, unsafeMarked = false,
            ),
        )
    }

    /**
     * unsafe **不是**调用侧 fail-fast 的一条（早期阻断的修法备案）：
     * [RelayCoordinator.earlyVerdict] 只有档位与"系统拒绝建屏"两支——把 unsafe 也做成
     * 这里的分派就会拦下显式 create/release，标记永远清不掉。本用例锁死这个形状：
     * 表内判据穷举完，unsafe 的失败只能出自后端自检出口（E_SURFACE_TRUSTED_UNSAFE）。
     */
    @Test
    fun unsafeIsNotACallSideVerdict() {
        assertNull(RelayCoordinator.earlyVerdict(CapabilityId.SURFACE_VIRTUAL, tierDenied = false, surfaceDenied = false))
        // denied 仍按原样分派（与 unsafe 无关的两支）。
        assertEquals(
            RelayError.SURFACE_TRUSTED_DENIED,
            RelayCoordinator.earlyVerdict(CapabilityId.SURFACE_VIRTUAL, tierDenied = false, surfaceDenied = true),
        )
        assertEquals(
            RelayError.GATE_HOST_DENIED,
            RelayCoordinator.earlyVerdict(CapabilityId.SURFACE_VIRTUAL, tierDenied = true, surfaceDenied = false),
        )
    }

    /** release 回执解除 unsafe 的判据：0/1 解除；收屏失败（负）与本地放弃（占位码）不解除。 */
    @Test
    fun releaseClearsTheMarkOnlyWhenTheScreenIsGoneForSure() {
        assertTrue(ShizukuBackend.releaseClearsUnsafe(1))
        assertTrue(ShizukuBackend.releaseClearsUnsafe(0))
        assertFalse("收屏失败：屏可能还在，标记必须继续挡着", ShizukuBackend.releaseClearsUnsafe(-1))
        assertFalse(ShizukuBackend.releaseClearsUnsafe(ShizukuBackend.RELAY_CODE_GAVE_UP))
    }

    /** unsafe 失败回包 data 的纯构造：selfCheckFailed 机器判读位 + display + violations。 */
    @Test
    fun theUnsafeFailureDataCarriesTheMachineReadableFields() {
        val data = ShizukuBackend.unsafeFailureData(5, listOf("TOP_TASK_CHANGED", "MAIN_FOCUS_LOST"))
        assertEquals(true, data.getBoolean("selfCheckFailed"))
        assertEquals(5, data.getInt("display"))
        assertEquals(2, data.getJSONArray("violations").length())
        assertEquals("TOP_TASK_CHANGED", data.getJSONArray("violations").get(0))
    }

    /**
     * 「优先后台」缺环判定（[RelayContainer.backgroundBlockFor]）的 unsafe 段：
     * **屏还活着也必须显示 DISPLAY_UNSAFE**（release 失败的残留屏不是健康屏），
     * unsafe 优先于 ready 与 Shizuku 三环之后的那几档。
     */
    @Test
    fun backgroundBlockShowsUnsafeEvenWhenTheResidualScreenIsAlive() {
        fun block(unsafe: Boolean, ready: Boolean): RelayContainer.BackgroundSurfaceBlock? =
            RelayContainer.backgroundBlockFor(
                backgroundPreferred = true,
                versionOk = true,
                unsafe = unsafe,
                ready = ready,
                installed = true,
                shizukuAvailable = true,
                granted = true,
                denied = false,
            )
        assertEquals(
            RelayContainer.BackgroundSurfaceBlock.DISPLAY_UNSAFE,
            block(unsafe = true, ready = false),
        )
        assertEquals(
            "M4：残留屏（release 失败）也归 DISPLAY_UNSAFE，不被『屏在』读成健康",
            RelayContainer.BackgroundSurfaceBlock.DISPLAY_UNSAFE,
            block(unsafe = true, ready = true),
        )
        assertNull(block(unsafe = false, ready = true))
        assertEquals(
            RelayContainer.BackgroundSurfaceBlock.DISPLAY_NOT_CREATED,
            block(unsafe = false, ready = false),
        )
        // 与执行模式无关时整条判定不启（保持原有口径）。
        assertNull(
            RelayContainer.backgroundBlockFor(
                backgroundPreferred = false,
                versionOk = true,
                unsafe = true,
                ready = false,
                installed = true,
                shizukuAvailable = true,
                granted = true,
                denied = false,
            ),
        )
        // 版本不够仍是第一优先。
        assertEquals(
            RelayContainer.BackgroundSurfaceBlock.ANDROID_VERSION,
            RelayContainer.backgroundBlockFor(
                backgroundPreferred = true,
                versionOk = false,
                unsafe = true,
                ready = false,
                installed = true,
                shizukuAvailable = true,
                granted = true,
                denied = false,
            ),
        )
    }

    /**
     * 三处读同源：`trustedDisplay.unsafe`（清单 backend.shizuku 字段）、
     * 后台各行的 usable/degradeReason（supports 真值表）与界面缺环判定读的都是
     * ShizukuBackend 的同一个标记。[RelayContainer] 本体要 Context 装不起来，装配级
     * 断言走它的**共用判据层**：模式门禁（[RelayCoordinator.modeRequiredFor]）、拒绝
     * （[RelayContainer.trustedDisplayRefusal]）与 unsafe 真值表在三处读同一份事实。
     */
    @Test
    fun theManifestFieldTheRowsAndTheRouterShareOneCriterion() {
        // 清单管理动作豁免模式门禁（surface.virtual 行不被 modeRequired 封死 →
        // unsafe 时 still 可调）：与调用侧同一张表。
        assertNull(
            "surface.virtual 是管理动作，requiresMode 不封它（清单侧同样用这张表）",
            RelayCoordinator.modeRequiredFor(CapabilityId.SURFACE_VIRTUAL, SurfaceKind.BACKEND_TRUSTED),
        )
        // 其它单面能力照旧被卡死——豁免只属于 surface.virtual。
        assertEquals(
            SurfaceKind.BACKEND_TRUSTED,
            RelayCoordinator.modeRequiredFor(CapabilityId.APP_LAUNCH, SurfaceKind.BACKEND_TRUSTED),
        )
        // unsafe 不进 degradeReason 表（清单行不降级）、不进调用侧 fail-fast：
        assertNull(
            RelayContainer.degradeReasonFor(systemRefused = false, modeRequired = false, decisionReason = null),
        )
        assertNull(RelayCoordinator.earlyVerdict(CapabilityId.SURFACE_VIRTUAL, tierDenied = false, surfaceDenied = false))
        // 后台落点与界面缺环按 unsafe 封死——同一标记的另两处读法互洽。
        assertTrue(
            !ShizukuBackend.trustedSurfaceSupported(
                CapabilityId.APP_LAUNCH, SurfaceKind.BACKEND_TRUSTED, displayAlive = true, unsafeMarked = true,
            ),
        )
        assertEquals(
            RelayContainer.BackgroundSurfaceBlock.DISPLAY_UNSAFE,
            RelayContainer.backgroundBlockFor(
                backgroundPreferred = true,
                versionOk = true,
                unsafe = true,
                ready = true,
                installed = true,
                shizukuAvailable = true,
                granted = true,
                denied = false,
            ),
        )
    }

    /**
     * 三条读路同源：unsafe 的同一份事实在 supports 真值表、ready/缺环判定与
     * degradeReason 表上的口径必须互洽——清单行不因 unsafe 降级（create 要可达），
     * 后台落点与界面缺环按 unsafe 封死。
     */
    @Test
    fun theManifestRowAndTheBackgroundRoutingAgreeOnTheSameMark() {
        // 清单：unsafe 不进 degradeReason 表（surface.virtual 保持 usable）——
        // 没有其它因子时既不注入 unsafe 码，也不产生 degradeReason。
        assertNull(
            RelayContainer.degradeReasonFor(
                systemRefused = false,
                modeRequired = false,
                decisionReason = null,
            ),
        )
        // 调用侧 fail-fast：unsafe 不拦。
        assertNull(RelayCoordinator.earlyVerdict(CapabilityId.SURFACE_VIRTUAL, tierDenied = false, surfaceDenied = false))
        // 后台落点：unsafe 封死（含残留屏）。
        assertTrue(
            !ShizukuBackend.trustedSurfaceSupported(
                CapabilityId.APP_LAUNCH, SurfaceKind.BACKEND_TRUSTED, displayAlive = true, unsafeMarked = true,
            ),
        )
        // 界面缺环：unsafe 优先于 ready（残留屏也不说成健康）。
        assertEquals(
            RelayContainer.BackgroundSurfaceBlock.DISPLAY_UNSAFE,
            RelayContainer.backgroundBlockFor(
                backgroundPreferred = true,
                versionOk = true,
                unsafe = true,
                ready = true,
                installed = true,
                shizukuAvailable = true,
                granted = true,
                denied = false,
            ),
        )
    }
}
