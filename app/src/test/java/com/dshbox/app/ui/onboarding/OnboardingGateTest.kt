package com.dshbox.app.ui.onboarding

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.UiText
import com.dshbox.app.runtime.OnlineImportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新手引导展示判定与层状态机的纯 JVM 测试。
 *
 * 这里钉的是「谁该看到引导」：判定只看两层运行环境是否真正就绪——两层都就绪不弹，
 * 任一层未就绪（含 profile 缺失的半成品）即弹，APK 内置随包层时一律不弹。标志维度的
 * 移除是刻意的：用户跳过后若未装完，下次启动必须重新展示，否则半成品残局无人可解。
 */
class OnboardingGateTest {

    @Test
    fun showsWhenNeitherLayerReady() {
        // 两层都未就绪（全新用户或半成品）→ 展示引导。
        assertTrue(
            OnboardingGate.shouldShow(
                runtimeReady = false,
                nodeReady = false,
                bundledRuntimeAvailable = false,
            ),
        )
    }

    @Test
    fun showsWhenOnlyDebianReady() {
        // 只装好 Debian 层：重启后仍应展示，让用户接着装 node 层。
        assertTrue(
            OnboardingGate.shouldShow(
                runtimeReady = true,
                nodeReady = false,
                bundledRuntimeAvailable = false,
            ),
        )
    }

    @Test
    fun showsWhenOnlyNodeReady() {
        // 只有 node 层在位（Debian 层缺失或为半成品）→ 仍需展示。
        assertTrue(
            OnboardingGate.shouldShow(
                runtimeReady = false,
                nodeReady = true,
                bundledRuntimeAvailable = false,
            ),
        )
    }

    @Test
    fun showsForHalfInstalledRuntime() {
        // 半成品场景：层目录已在位但 profile 缺失，严格判定 runtimeReady=false
        // → 展示引导，用户重装即可（半成品会被 body 替换覆盖）。
        assertTrue(
            OnboardingGate.shouldShow(
                runtimeReady = false,
                nodeReady = false,
                bundledRuntimeAvailable = false,
            ),
        )
    }

    @Test
    fun hidesWhenBothLayersReady() {
        // 两层都就绪 → 环境可用，不展示引导。
        assertFalse(
            OnboardingGate.shouldShow(
                runtimeReady = true,
                nodeReady = true,
                bundledRuntimeAvailable = false,
            ),
        )
    }

    @Test
    fun bundledRuntimeSuppressesOnboardingEvenWhenLayersMissing() {
        // 新装发布包：首帧两层探测必然未就绪但 APK 内置随包层
        // → 不展示，避免误弹引导并触发打断随包安装的在线全量重装。
        assertFalse(
            OnboardingGate.shouldShow(
                runtimeReady = false,
                nodeReady = false,
                bundledRuntimeAvailable = true,
            ),
        )
    }

    @Test
    fun bundledRuntimeSuppressesOnboardingWhenOnlyDebianReady() {
        // 随包维度只做「否决」：随包层可用时即便 Debian 层已就绪也不展示。
        assertFalse(
            OnboardingGate.shouldShow(
                runtimeReady = true,
                nodeReady = false,
                bundledRuntimeAvailable = true,
            ),
        )
    }

    private fun state(
        running: Boolean = false,
        result: AppResult<Unit>? = null,
    ) = OnlineImportState(running = running, result = result)

    private val failure = AppResult.Failure(AppError("E_TEST", "boom"))

    @Test
    fun nextStatusFileSystemFactWinsOverRunning() {
        // 文件系统事实优先：层已就位即 DONE，即便 manager 仍在途。
        assertEquals(
            OnboardingLayerStatus.DONE,
            nextLayerStatus(
                current = OnboardingLayerStatus.INSTALLING,
                installed = true,
                state = state(running = true),
            ),
        )
    }

    @Test
    fun nextStatusKeepsDoneWhenNothingInFlight() {
        // 无在途、无失败、未装：当前为 DONE 的层按现实现回落 PENDING。
        assertEquals(
            OnboardingLayerStatus.PENDING,
            nextLayerStatus(
                current = OnboardingLayerStatus.DONE,
                installed = false,
                state = state(),
            ),
        )
    }

    @Test
    fun nextStatusFailureResultOverridesSkipped() {
        // 失败判定置于当前状态判定之前：即便当前是 SKIPPED，只要结果流带 Failure 即置 FAILED。
        assertEquals(
            OnboardingLayerStatus.FAILED,
            nextLayerStatus(
                current = OnboardingLayerStatus.SKIPPED,
                installed = false,
                state = state(result = failure),
            ),
        )
    }

    @Test
    fun nextStatusKeepsPendingWhenNothingInFlight() {
        // 无在途也无失败：当前为 PENDING 时保持原值（探测完成后的跃迁由 startInstall 决定）。
        assertEquals(
            OnboardingLayerStatus.PENDING,
            nextLayerStatus(
                current = OnboardingLayerStatus.PENDING,
                installed = false,
                state = state(),
            ),
        )
    }
}
