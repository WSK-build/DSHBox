package interlock.relay.core.exec.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 建屏 flags 按 SDK 与 trusted 条件生成（[RelayShellService.TrustedDisplay.createFlags]）
 * 的逐位断言。位值来自 framework 常量表（dexdump 抄出），这里同时把位值钉死，
 * 常量被人改动时测试先红——flags 是跨系统服务的二进制契约，不许"顺手改个值"。
 */
class DisplayFlagsTest {

    private object F {
        const val PUBLIC = 1
        const val OWN_CONTENT_ONLY = 8
        const val SUPPORTS_TOUCH = 1 shl 6
        const val ROTATES_WITH_CONTENT = 1 shl 7
        const val DESTROY_CONTENT_ON_REMOVAL = 256
        const val SHOULD_SHOW_SYSTEM_DECORATIONS = 512
        const val TRUSTED = 1024
        const val OWN_DISPLAY_GROUP = 2048
        const val ALWAYS_UNLOCKED = 4096
        const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        const val OWN_FOCUS = 1 shl 14
        const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16
    }

    private fun flags(sdk: Int, trusted: Boolean): Int =
        RelayShellService.TrustedDisplay.createFlags(sdk, trusted)

    /** 基础集（各 SDK、无论 trusted 都在）：含  补进来的 SUPPORTS_TOUCH/ROTATES_WITH_CONTENT。 */
    private val BASE = F.PUBLIC or F.OWN_CONTENT_ONLY or F.DESTROY_CONTENT_ON_REMOVAL or
        F.SHOULD_SHOW_SYSTEM_DECORATIONS or F.SUPPORTS_TOUCH or F.ROTATES_WITH_CONTENT

    private val TRUSTED_SET = F.TRUSTED or F.OWN_DISPLAY_GROUP or F.ALWAYS_UNLOCKED

    /** trusted 屏在 Android 12 及以下：基础集 + trusted 三位，还没有 13+ 的那一位。 */
    @Test
    fun sdk29TrustedHasBasePlusTrustedBits() {
        assertEquals(BASE or TRUSTED_SET, flags(29, true))
        assertEquals(0, flags(29, true) and F.TOUCH_FEEDBACK_DISABLED)
        assertEquals(0, flags(29, true) and F.OWN_FOCUS)
        assertEquals(0, flags(29, true) and F.STEAL_TOP_FOCUS_DISABLED)
    }

    /** Android 13 起（SDK 33）：trusted 屏补 TOUCH_FEEDBACK_DISABLED，仍没有 14+ 两位。 */
    @Test
    fun sdk33TrustedAddsTouchFeedbackDisabled() {
        assertEquals(BASE or TRUSTED_SET or F.TOUCH_FEEDBACK_DISABLED, flags(33, true))
        assertEquals(0, flags(33, true) and F.OWN_FOCUS)
        assertEquals(0, flags(33, true) and F.STEAL_TOP_FOCUS_DISABLED)
    }

    /** Android 14 起（SDK 34）且 trusted：再补 OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED。 */
    @Test
    fun sdk34And35TrustedAddOwnFocusBits() {
        val expected = BASE or TRUSTED_SET or F.TOUCH_FEEDBACK_DISABLED or
            F.OWN_FOCUS or F.STEAL_TOP_FOCUS_DISABLED
        assertEquals(expected, flags(34, true))
        assertEquals(expected, flags(35, true))
    }

    /** 非 trusted 屏：trusted 三位与 14+ 两位都不给——OWN_FOCUS 只属于 trusted 屏。 */
    @Test
    fun untrustedScreensNeverGetTrustedOrFocusBits() {
        listOf(29, 33, 34, 35).forEach { sdk ->
            val value = flags(sdk, false)
            assertEquals("sdk=$sdk", if (sdk >= 33) BASE or F.TOUCH_FEEDBACK_DISABLED else BASE, value)
            assertEquals(0, value and F.TRUSTED)
            assertEquals(0, value and F.OWN_DISPLAY_GROUP)
            assertEquals(0, value and F.ALWAYS_UNLOCKED)
            assertEquals(0, value and F.OWN_FOCUS)
            assertEquals(0, value and F.STEAL_TOP_FOCUS_DISABLED)
        }
    }

    /**
     * SHOULD_SHOW_SYSTEM_DECORATIONS 保留：不按"它可疑"删掉，留给设备。
     * 在它被证实是根因之前，这条测试不许有人以"清理"为名动它。
     */
    @Test
    fun systemDecorationsFlagStaysUntilTheOnDeviceAB() {
        listOf(29, 33, 34, 35).forEach { sdk ->
            assertTrue(flags(sdk, true) and F.SHOULD_SHOW_SYSTEM_DECORATIONS != 0)
            assertTrue(flags(sdk, false) and F.SHOULD_SHOW_SYSTEM_DECORATIONS != 0)
        }
    }

    /** 隐藏调用失败时的窗口模式回退命令：命令串形状收在纯函数里。 */
    @Test
    fun windowingFallbackCommandTargetsTheDisplayInFullscreenMode() {
        assertEquals(
            "wm set-display-windowing-mode -d 5 1",
            DisplayPolicy.windowingFallbackCommand(5),
        )
        assertEquals(
            "wm set-display-windowing-mode -d 0 1",
            DisplayPolicy.windowingFallbackCommand(0),
        )
    }

    /** 位值本身钉死：dexdump 抄来的常量不许被"重排"改写。 */
    @Test
    fun bitValuesMatchTheFrameworkTable() {
        assertEquals(64, F.SUPPORTS_TOUCH)
        assertEquals(128, F.ROTATES_WITH_CONTENT)
        assertEquals(8192, F.TOUCH_FEEDBACK_DISABLED)
        assertEquals(16384, F.OWN_FOCUS)
        assertEquals(65536, F.STEAL_TOP_FOCUS_DISABLED)
        assertFalse(F.OWN_FOCUS == F.TOUCH_FEEDBACK_DISABLED)
    }
}
