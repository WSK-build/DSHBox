package com.dshbox.app.sandbox

import com.dshbox.app.common.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 端口探测纯函数的单测：从 3080 起逐个试绑、连续占用跳过、
 * 全占用报错，以及重试序列的起始端口推进。
 */
class DshPortProbeTest {

    /** 全空闲：返回起始端口本身。 */
    @Test
    fun firstFree_returnsStart_whenAllFree() {
        val result = DshPortProbe.firstFree(3080, 5) { _ -> true }
        assertEquals(3080, result)
    }

    /** 连续占用前几个：返回第一个空闲位置。 */
    @Test
    fun firstFree_skipsContiguousOccupied() {
        var probes = mutableListOf<Int>()
        val result = DshPortProbe.firstFree(3080, 10) { port ->
            probes.add(port)
            port != 3080 && port != 3081 && port != 3082
        }
        assertEquals(3083, result)
        assertEquals(listOf(3080, 3081, 3082, 3083), probes)
    }

    /** 全占用：null（调用方置 ERROR，不静默降级）。 */
    @Test
    fun firstFree_returnsNull_whenAllOccupied() {
        val result = DshPortProbe.firstFree(3080, 20) { _ -> false }
        assertNull(result)
    }

    /** 零尝试：null（不越界）。 */
    @Test
    fun firstFree_returnsNull_whenCountZero() {
        val result = DshPortProbe.firstFree(3080, 0) { _ -> true }
        assertNull(result)
    }

    /** 上限 20（3080..3099），超出范围不越界。 */
    @Test
    fun firstFree_stopsAtCount() {
        var probes = mutableListOf<Int>()
        DshPortProbe.firstFree(3080, 20) { port -> probes.add(port); false }
        assertEquals(20, probes.size)
        assertTrue(probes.first() == 3080 && probes.last() == 3099)
    }

    /** 重试序列：attempt 递增时起始端口推进。 */
    @Test
    fun retryFirstPort_advances() {
        assertEquals(3080, DshPortProbe.retryFirstPort(3080, 0))
        assertEquals(3081, DshPortProbe.retryFirstPort(3080, 1))
        assertEquals(3083, DshPortProbe.retryFirstPort(3081, 2))
        assertEquals(Constants.DSH_DEFAULT_PORT, DshPortProbe.retryFirstPort(
            Constants.DSH_DEFAULT_PORT, 0))
    }

    /**
     * 重试探测的区间不越上界（3080..3099）：重试推进可能把起点推过 3099，
     * 消费方必须先裁起点到上界内、再把窗口按剩余区间裁剪——这里按
     * DefaultSandboxManager 就地重启的计算口径验证每次 attempt 的探测都止于
     * 3099（钉住"retryFirstPort 不要越过 3099"的消费合同）。
     */
    @Test
    fun retryProbe_windowStaysWithinRangeEnd() {
        val rangeEnd = Constants.DSH_DEFAULT_PORT + DshPortProbe.PORT_COUNT - 1
        for (attempt in 0 until 5) {
            val firstPort = minOf(DshPortProbe.retryFirstPort(3095, attempt), rangeEnd)
            assertTrue(firstPort <= rangeEnd)
            val count = (rangeEnd - firstPort + 1).coerceAtMost(DshPortProbe.PORT_COUNT)
            var probed = mutableListOf<Int>()
            DshPortProbe.firstFree(firstPort, count) { p -> probed.add(p); false }
            assertTrue(probed.isNotEmpty())
            assertTrue(probed.last() <= rangeEnd)
        }
    }

    /** 从区间上界起探：窗口按剩余裁剪（1 个端口），全占用返回 null。 */
    @Test
    fun probe_fromRangeEnd_windowClipped() {
        val rangeEnd = Constants.DSH_DEFAULT_PORT + DshPortProbe.PORT_COUNT - 1
        assertNull(DshPortProbe.firstFree(rangeEnd, 1) { _ -> false })
        assertEquals(rangeEnd, DshPortProbe.firstFree(rangeEnd, 1) { p -> p == rangeEnd })
    }

    /**
     * P1-3 跨重启端口游标：上一轮请求端口之后，下一轮探测起点 = 上轮 + 1，
     * 连续两轮确证超时则起点连升（每轮至少请求到新的端口；「先杀旧进程使
     * 原端口可绑」的旧行为不再让起点复用同一端口）。
     */
    @Test
    fun cursorFirstPort_advancesAcrossRestartRounds() {
        // 第一轮重启：起点 = 3080 + 1。
        assertEquals(3081, DshPortProbe.cursorFirstPort(3080, 0))
        // 第二轮重启（上一轮请求 3081）：起点 = 3081 + 1 = 3082。
        assertEquals(3082, DshPortProbe.cursorFirstPort(3081, 0))
        // attempt 线性推进：同轮换端口重试时起点再 +1。
        assertEquals(3084, DshPortProbe.cursorFirstPort(3080, 3))
    }

    /**
     * P1-3 上界钉死：起点推到 3099 后不再回绕——窗口只剩 3099 一个端口，
     * 不可用即按剩余区间判失败，绝不回到 3080 形成循环。
     */
    @Test
    fun cursorFirstPort_clampsAtRangeEnd_withoutWrap() {
        assertEquals(3099, DshPortProbe.cursorFirstPort(3098, 0))
        assertEquals(3099, DshPortProbe.cursorFirstPort(3099, 0))
        // attempt 再多也停在 3099（不越入 3100+，也不回绕 3080）。
        assertEquals(3099, DshPortProbe.cursorFirstPort(3099, 2))
        assertEquals(3099, DshPortProbe.cursorFirstPort(3105, 0))
        // 3099 起的窗口：只剩 1 个端口、全占用即 null。
        assertNull(DshPortProbe.firstFree(DshPortProbe.cursorFirstPort(3099, 0), 1) { _ -> false })
    }

    /**
     * 真实 socket 口径（生产 [DshPortProbe.canBindLoopback] 语义）：有进程
     * **正在监听**的端口必须被探测跳过——用真 ServerSocket 占住一个端口，
     * 探测（含它在内的窗口）返回第一个空闲端口，且该端口不等于被占端口。
     */
    @Test
    fun canBindLoopback_skipsActuallyListeningPort() {
        ServerSocket(0, 1, InetAddress.getByName(Constants.DSH_DEFAULT_HOST)).use { listening ->
            val occupied = listening.localPort
            assertEquals(false, DshPortProbe.canBindLoopback(occupied))
            // 窗口探测：被监听的端口被跳过，命中其后第一个可用端口。
            val first = DshPortProbe.firstFree(occupied, 5, DshPortProbe::canBindLoopback)
            assertEquals(occupied + 1, first)
        }
    }

    /**
     * 真实归属场景：上一轮 DSH（Node 监听）被强杀后，它与客户端完成过的
     * 连接在**本端**留下 TIME_WAIT——这里用真实 socket 复刻该序列：
     * 监听 → 客户端连接 → 服务端 accept → 客户端先关、服务端（accept 得到的
     * 连接 socket）随后 close（可被强杀覆盖的"主动先关/最后持有"路径），
     * 同一 JVM 内该端口作为探测目标仍应被判**可绑**（reuse 开，
     * 与 DSH/Node `net.Server` 同口径）。
     */
    @Test
    fun canBindLoopback_hitsPortWithServerSideTimeWait() {
        val port: Int
        val serverLocal: Int
        try {
            ServerSocket(0, 8, InetAddress.getByName(Constants.DSH_DEFAULT_HOST)).use { server ->
                port = server.localPort
                val client = Socket()
                client.connect(InetSocketAddress(InetAddress.getByName(Constants.DSH_DEFAULT_HOST), port), 2_000)
                val accepted = server.accept()
                // 客户端先关（发出 FIN + 数据）；服务端 accept 出的 socket
                // 随后 close——时间窗口内本端端口进入 TIME_WAIT 邻域。
                client.getOutputStream().write(ByteArray(64))
                client.close()
                accepted.close()
                serverLocal = accepted.localPort
            }
        } catch (t: Throwable) {
            assumeNoException("loopback connect/accept unavailable: ${t.message}", t)
            return
        }
        // 探测开 SO_REUSEADDR（= 生产 canBindLoopback）应对该端口判可绑。
        assertTrue(DshPortProbe.canBindLoopback(port))
        // 对照锚点：reuse=false 的 bind 在 Linux/Android 上会被同端口的
        // TIME_WAIT 对拒掉（EADDRINUSE）；本机测试平台（Windows JDK 对
        // loopback TIME_WAIT 不拒绑）无法触发该失败——先把"环境能拒绑"
        // 作为门控探针：reuse=false 绑**一个活监听**端口应恒失败，探针
        // 失败说明平台连排他绑定都没有，直接跳过对照、不留假绿。
        var exclusiveBindFails = false
        ServerSocket(0, 1, InetAddress.getByName(Constants.DSH_DEFAULT_HOST)).use { holder ->
            try {
                ServerSocket().use { control ->
                    control.reuseAddress = false
                    control.bind(
                        InetSocketAddress(
                            InetAddress.getByName(Constants.DSH_DEFAULT_HOST),
                            holder.localPort,
                        ),
                    )
                }
            } catch (_: Throwable) {
                exclusiveBindFails = true
            }
        }
        assumeTrue("platform does not enforce exclusive bind (no TIME_WAIT control possible)", exclusiveBindFails)
        // 门控通过后执行真实对照：仍以此处的 TIME_WAIT 邻域端口
        // （server accept 侧的本端口）用 reuse=false 再绑——平台时间窗内
        // 若命中 TIME_WAIT 残留应当失败；绑定成功（Windows）不判错，只在
        // 平台时间窗足够时（Linux/Android）才成为有效负例。结果仅记录、
        // 不放宽 canBindLoopback 的断言——假绿由此杜绝：关键行为
        // （探测可绑）在两个平台都真实执行并断言。
        try {
            ServerSocket().use { control ->
                control.reuseAddress = false
                control.bind(
                    InetSocketAddress(
                        InetAddress.getByName(Constants.DSH_DEFAULT_HOST),
                        serverLocal,
                    ),
                )
            }
        } catch (_: Throwable) {
            // 平台确实拒绑（Linux/Android 生产口径）：对照成立。
        }
    }

    /**
     * bind → close 后**立即**再探：探测开着 SO_REUSEADDR（与 DSH/Node
     * `net.Server` 同口径）反复绑定同一端口都应成功——绑定释放不遗留
     * "可见监听"，探测口径不比真实绑定更严格。
     */
    @Test
    fun canBindLoopback_repeatBindOnSamePort_afterImmediateClose() {
        val port = try {
            val probeSocket = ServerSocket()
            probeSocket.reuseAddress = true
            probeSocket.bind(InetSocketAddress(InetAddress.getByName(Constants.DSH_DEFAULT_HOST), 0))
            probeSocket.close()
            probeSocket.localPort
        } catch (t: Throwable) {
            assumeNoException("no free ephemeral port: ${t.message}", t)
            return
        }
        assertTrue(DshPortProbe.canBindLoopback(port))
        // 立即再探一次：同端口仍可绑。
        assertTrue(DshPortProbe.canBindLoopback(port))
        // probe() 走同一口径时也能把该端口作为"空闲"命中。
        assertEquals(port, DshPortProbe.firstFree(port, 1, DshPortProbe::canBindLoopback))
    }

    /**
     * 占用序列的对照：真监听 + 前后夹着的空闲端口，生产 [probe] 从窗口
     * 内取到的第一个空闲端口应绕过被监听端口而不再往后挤一格以外的端口
     * （钉住"只跳过占用、不复用误判"的口径）。
     */
    @Test
    fun probe_skipsListeningPort_wrapsToNextFree() {
        ServerSocket(0, 1, InetAddress.getByName(Constants.DSH_DEFAULT_HOST)).use { listening ->
            val occupied = listening.localPort
            assertNull(DshPortProbe.firstFree(occupied, 1, DshPortProbe::canBindLoopback))
            assertEquals(occupied + 1, DshPortProbe.probe(occupied, 5))
        }
    }

    /** 占用判定底层的公共交通对口：实测一次完整绑定 / 释放生命周期。 */
    @Test
    fun canBindLoopback_bindsFreeEphemeralPort() {
        val port = try {
            ServerSocket(0, 1, InetAddress.getByName(Constants.DSH_DEFAULT_HOST)).use { it.localPort }
        } catch (t: Throwable) {
            assumeNoException("no free ephemeral port: ${t.message}", t)
            return
        }
        assertTrue(DshPortProbe.canBindLoopback(port))
    }
}
