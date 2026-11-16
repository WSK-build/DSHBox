package dshbox.adapter

import dshbox.adapter.ui.FullShellGap
import dshbox.adapter.ui.FullShellIdentity
import dshbox.adapter.ui.fullShellGap
import dshbox.adapter.ui.fullShellIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「完全 Shizuku 能力」栏目的纯呈现判据：执行身份按 uid 分档、缺环按「缺哪一环」点名。
 * 设备的执行判定全在内核（档位在闸门、就绪三环在装配根）；这里钉的是快照到呈现档的
 * 映射本身，各个 uid 场景与缺环组合在 JVM 上穷举——设备只需复核内核那侧。
 */
class FullShellPresentationTest {

    /** uid → 身份档：shell（2000）与 root（0）点名，其余报数，读不到如实未知。 */
    @Test
    fun uidMapsToTheRightIdentityTier() {
        assertEquals(FullShellIdentity.SHELL, fullShellIdentity(2000))
        assertEquals(FullShellIdentity.ROOT, fullShellIdentity(0))
        assertEquals(FullShellIdentity.OTHER_UID, fullShellIdentity(20100))
        assertEquals(FullShellIdentity.OTHER_UID, fullShellIdentity(1))
        // -1 是快照的「读不到」哨兵：未知就是未知，不冒充任何具体身份。
        assertEquals(FullShellIdentity.UNKNOWN, fullShellIdentity(-1))
    }

    /** 三环齐备才无缺口；缺口停在最前的那一环，按次序各点各的名。 */
    @Test
    fun gapNamesTheFirstMissingLinkOnly() {
        assertEquals(FullShellGap.NONE, fullShellGap(running = true, granted = true, bound = true))
        assertEquals(FullShellGap.NOT_RUNNING, fullShellGap(running = false, granted = false, bound = false))
        assertEquals(FullShellGap.NOT_RUNNING, fullShellGap(running = false, granted = true, bound = true))
        assertEquals(FullShellGap.NOT_GRANTED, fullShellGap(running = true, granted = false, bound = false))
        assertEquals(FullShellGap.NOT_GRANTED, fullShellGap(running = true, granted = false, bound = true))
        assertEquals(FullShellGap.NOT_BOUND, fullShellGap(running = true, granted = true, bound = false))
    }
}
