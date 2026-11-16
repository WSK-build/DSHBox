package interlock.relay.core.interlock

import interlock.relay.core.protocol.GrantGap
import interlock.relay.core.protocol.SystemGrantState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * shell 族能力的探测结论（[SystemStateProbe.shizukuProbe]）各场景穷举：
 * **三环齐备就是 GRANTED**——在跑、已授权、执行侧用户服务绑上，三者齐时 shell 那几条
 * 此刻真能跑，闸门不得留它；这一问丢了，三环齐的机器上 secure.settings / appops /
 * pkg.install / app.stop / surface.virtual / sys.shell / sys.shell.full 会被闸门一律挡在
 * MANUAL_ONLY 上（BLOCKER 场景）。缺哪一环才回落 MANUAL_ONLY 并按环缺口：
 * 没跑 → 启动/装它；在跑没授权 → 去 Shizuku 管理器放行；授权齐未绑 → 点重连。
 * 三者的下一步完全不同：把"没授权"说成"服务没开"，人就去启动一个已在跑的服务，
 * 那条路上什么也修不了。
 */
class ShizukuGapTest {

    /** 三环齐备：GRANTED 且无缺口——这条是 BLOCKER 的对应判据，闸门与清单共读一个结论。 */
    @Test
    fun allThreeLinksReadyIsGrantedWithoutGaps() {
        val probe = SystemStateProbe.shizukuProbe(running = true, granted = true, bound = true)
        assertEquals(SystemGrantState.GRANTED, probe.state)
        assertTrue(probe.gaps.isEmpty())
    }

    /** 服务没在跑：MANUAL_ONLY，缺口是"去启动/装它"，与授权/绑定无关。 */
    @Test
    fun notRunningNamesTheServiceNotTheGrant() {
        assertEquals(GrantGap.Shizuku, SystemStateProbe.shizukuProbe(running = false, granted = false, bound = false).gaps.single())
        assertEquals(GrantGap.Shizuku, SystemStateProbe.shizukuProbe(running = false, granted = true, bound = true).gaps.single())
        assertEquals(SystemGrantState.MANUAL_ONLY, SystemStateProbe.shizukuProbe(running = false, granted = true, bound = true).state)
    }

    /** 在跑但没授权：MANUAL_ONLY，缺口是"去 Shizuku 管理器放行"，不是"服务没开"。 */
    @Test
    fun runningButUngrantedNamesTheGrant() {
        assertEquals(GrantGap.ShizukuNotGranted, SystemStateProbe.shizukuProbe(running = true, granted = false, bound = false).gaps.single())
        assertEquals(GrantGap.ShizukuNotGranted, SystemStateProbe.shizukuProbe(running = true, granted = false, bound = true).gaps.single())
        assertEquals(SystemGrantState.MANUAL_ONLY, SystemStateProbe.shizukuProbe(running = true, granted = false, bound = true).state)
    }

    /** 在跑且已授权只差用户服务：MANUAL_ONLY，缺口是"点重连/等自动重连"。 */
    @Test
    fun grantedButUnboundNamesTheUserService() {
        val probe = SystemStateProbe.shizukuProbe(running = true, granted = true, bound = false)
        assertEquals(SystemGrantState.MANUAL_ONLY, probe.state)
        assertEquals(GrantGap.ShizukuNotBound, probe.gaps.single())
    }

    /** 缺环点名（分派前提是三环不齐）：首缺环点名，与上面的结论一一对应。 */
    @Test
    fun gapNamingAgreesWithTheProbeVerdict() {
        assertEquals(GrantGap.Shizuku, SystemStateProbe.shizukuGap(running = false, granted = true, bound = true))
        assertEquals(GrantGap.ShizukuNotGranted, SystemStateProbe.shizukuGap(running = true, granted = false, bound = true))
        assertEquals(GrantGap.ShizukuNotBound, SystemStateProbe.shizukuGap(running = true, granted = true, bound = false))
    }
}
