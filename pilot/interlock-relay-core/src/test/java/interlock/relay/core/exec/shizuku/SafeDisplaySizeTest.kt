package interlock.relay.core.exec.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 大屏/平板的安全建屏尺寸（[ShizukuBackend.safeDisplaySize]）各场景的穷举。
 *
 * 尺寸判据：物理屏短边与长边各自夹进 320..2560；长边越界时按
 * 「短边原样做高、长边截到上限」生成覆盖尺寸。该尺寸下建屏后 release
 * 可恢复系统手势，避免整屏缩放造成的变形。本测试固定判据本身。
 */
class SafeDisplaySizeTest {

    private val keepExisting = ShizukuBackend.SafeDisplaySize.KEEP_EXISTING
    private val noneSafe = ShizukuBackend.SafeDisplaySize.NONE_SAFE

    private fun overrideSize(sizing: ShizukuBackend.SafeDisplaySize): Pair<Int, Int> {
        val overridden = sizing as ShizukuBackend.SafeDisplaySize.Override
        return overridden.width to overridden.height
    }

    /**
     * 实际场景（用户平板物理 3200×2136）：横用传 (2136, 3200)、竖用传反序
     * (3200, 2136) 都落 2560×2136——两参内部按大小归一，方向不写死，
     * 一个场景一处断言，不另立重复用例。
     */
    @Test
    fun tabletPanelClipsLongSideToShortOneRegardlessOfOrder() {
        assertEquals(2560 to 2136, overrideSize(ShizukuBackend.safeDisplaySize(2136, 3200)))
        assertEquals(2560 to 2136, overrideSize(ShizukuBackend.safeDisplaySize(3200, 2136)))
    }

    /**
     * 物理尺寸读取侧的 seam（[ShizukuBackend.physicalScreenSides] 依赖框架 Window/Display
     * 服务，JVM 不可实跑）：这里钉的是判据消费端同一份契约——生产传给判据的那对数
     * 无论方向**总是 (短边, 长边)**，因此反向入参（先长后短）必须与正向入参同判。
     */
    @Test
    fun shortFirstLongSecondContractIsDirectionAgnostic() {
        // (短, 长) 与 (长, 短) 两个场景同判：读取侧常态保证最小者在前。
        assertEquals(noneSafe, ShizukuBackend.safeDisplaySize(2561, 3000))
        assertEquals(noneSafe, ShizukuBackend.safeDisplaySize(3000, 2561))
    }

    /** 手机常规屏（1080×2400）：两边都在可建范围内，不强制改尺寸。 */
    @Test
    fun phoneSizedPanelsKeepExistingBehaviour() {
        assertEquals(keepExisting, ShizukuBackend.safeDisplaySize(1080, 2400))
        assertEquals(keepExisting, ShizukuBackend.safeDisplaySize(1080, 1920))
    }

    /** 两边都越上限（3000×3000）：任何截法都不成比例，无安全尺寸。 */
    @Test
    fun bothSidesBeyondTheCapMeansNoSafeSize() {
        assertEquals(noneSafe, ShizukuBackend.safeDisplaySize(3000, 3000))
        assertEquals(noneSafe, ShizukuBackend.safeDisplaySize(2561, 3000))
        assertEquals(noneSafe, ShizukuBackend.safeDisplaySize(2561, 2561))
    }

    /**
     * 非正方形的临界场景（3000×2561）：短边 2561 也越上限，无安全尺寸——
     * 短边是判据的"另一侧不越界"门，反两种传参都得到同一结论。
     */
    @Test
    fun nonSquareBothSidesBeyondTheCapMeansNoSafeSize() {
        assertEquals(noneSafe, ShizukuBackend.safeDisplaySize(3000, 2561))
        assertEquals(noneSafe, ShizukuBackend.safeDisplaySize(2561, 3000))
    }

    /** 判据的边界：2560 恰在上限（按可建算），2561 就越界。 */
    @Test
    fun boundaryAtTheCap() {
        assertEquals(keepExisting, ShizukuBackend.safeDisplaySize(2560, 2560))
        assertEquals(noneSafe, ShizukuBackend.safeDisplaySize(2561, 2561))
        // 长边 2561、短边 2560：截出的仍是 2560×2560。
        assertEquals(2560 to 2560, overrideSize(ShizukuBackend.safeDisplaySize(2560, 2561)))
    }

    /** 读不到物理尺寸（0）不替设备下结论：沿用请求/缺省。 */
    @Test
    fun unknownPhysicalSizeKeepsExisting() {
        assertEquals(keepExisting, ShizukuBackend.safeDisplaySize(0, 0))
        assertEquals(keepExisting, ShizukuBackend.safeDisplaySize(0, 3200))
    }

    /** 上限常量与显示服务端的夹紧上限（RelayShellService.TrustedDisplay.MAX_SIDE）同值。 */
    @Test
    fun capMatchesTheDisplayServiceClamp() {
        // MAX_SIDE 是 private；用反射读，保证两处字面量不因一改一漏而分岔。
        val serviceMax = RelayShellService.TrustedDisplay::class.java
            .getDeclaredField("MAX_SIDE")
            .apply { isAccessible = true }
            .get(null) as Int
        assertEquals(serviceMax, ShizukuBackend.MAX_SAFE_SIDE)
    }

    /** 收屏回包里"输入状态"的无法确认档字面量（布尔档类型另有三档映射测试）。 */
    @Test
    fun inputStateFieldExposesTheUnknownLiteral() {
        // 字面量即协议位；ReleaseInputStateFieldTest 钉三档映射与类型互斥。
        assertTrue(ShizukuBackend.INPUT_STATE_UNKNOWN == "unknown")
    }

    /** 建屏拒绝回包里 `sizePolicy` 的"无安全尺寸"结构化取值是协议面字面量。 */
    @Test
    fun sizePolicyLiteralForNoSafeSize() {
        assertTrue(ShizukuBackend.SIZE_POLICY_NONE_SAFE == "none_safe")
    }
}
