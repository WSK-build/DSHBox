package com.dshbox.app.sandbox

import com.dshbox.app.common.Constants
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 本机 DSH 端口探测：每次启动 DSH 前在 `127.0.0.1` 上从
 * [Constants.DSH_DEFAULT_PORT]（3080）起逐个试绑，取第一个空闲端口。
 *
 * 探测语义（口径锁定在纯函数里便于单测）：
 * - 逐个试绑，上限 [PORT_COUNT] 个（3080..3099）；
 * - 绑定时**打开** SO_REUSEADDR：探测要与 DSH（Node `net.Server`，默认开
 *   `SO_REUSEADDR`）的真实绑定同口径——reuse 关闭时上一轮 DSH 被强杀后
 *   残留在监听端口上的 TIME_WAIT 连接会把探测判成"占用"（EADDRINUSE），
 *   而 Node 本身却能正常绑定该端口，探测因此比真实绑定更严格，每次重启
 *   端口都被往后挤。reuse 打开后 `EADDRINUSE` 只意味着"有进程正占用该
 *   端口（监听或已绑定的连接）"，TIME_WAIT 不再误伤；
 * - 绑定成功即视为空闲，立即 close 释放——真正占端口的是稍后的 DSH；
 * - 全部占用返回 null，由调用方报错（不静默降级）。
 *
 * **不持久化**：每次重启都从 3080 重新探，空出后自动回到 3080。
 */
object DshPortProbe {

    /** 从默认端口 3080 起最多试 20 个端口（3080..3099）。 */
    const val PORT_COUNT = 20

    /**
     * 探测第一个可用端口；绑定尝试抽象为 [tryBind] 以便 JVM 单测注入
     * （真实实现在 [probe]，测试用它模拟占用序列）。
     *
     * @param firstPort 起始端口（默认 3080）
     * @param count 尝试数量（默认 20）
     * @return 第一个可绑定的端口；全部不可用返回 null
     */
    fun firstFree(
        firstPort: Int = Constants.DSH_DEFAULT_PORT,
        count: Int = PORT_COUNT,
        /** 返回 true = 该端口可绑定（空闲）。实现负责 close。 */
        tryBind: (Int) -> Boolean,
    ): Int? {
        require(count >= 0) { "count must be non-negative" }
        for (offset in 0 until count) {
            val port = firstPort + offset
            if (tryBind(port)) return port
        }
        return null
    }

    /**
     * 真实环境的单次绑定：`127.0.0.1` 上的 [ServerSocket] bind（reuse 开，
     * 成功后立即 close）。reuse 打开与 DSH（Node `net.Server`）的监听口径
     * 一致：`EADDRINUSE` 代表"有进程正占用该端口（监听或已绑定的连接）"，
     * TIME_WAIT 残留不再被误判成占用。任何异常按占用处理。
     */
    fun canBindLoopback(port: Int): Boolean = try {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName(Constants.DSH_DEFAULT_HOST), port))
            true
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * 便捷入口：真实绑定语义下的 first-free 探测。全占用返回 null。
     */
    fun probe(
        firstPort: Int = Constants.DSH_DEFAULT_PORT,
        count: Int = PORT_COUNT,
    ): Int? = firstFree(firstPort, count, ::canBindLoopback)

    /**
     * 重试端口序列生成（启动后若因端口占用失败，换下一个端口重试，
     * 最多 3 次）：以 [attempt]（0 起）返回当次的起始探测端口——每次从
     * 上一个失败端口的下一个端口起探。纯函数。
     */
    fun retryFirstPort(requestedPort: Int, attempt: Int): Int =
        requestedPort + attempt

    /**
     * 跨重启端口游标下的探测起点（纯函数便于单测）：
     * [lastRequestedPort] 是上一轮**已请求**（无论结果如何）的端口，
     *本轮从「它 + 1」起探并按 [attempt] 继续线性推进；起点恒裁到
     * 探测区间上界（3080..3099），到 3099 之后**不再回绕**——消费方沿剩余
     * 区间裁剪窗口，3099 起窗口只剩 3099 一个端口，全占用即失败（不静默
     * 回到 3080 形成循环）。
     */
    fun cursorFirstPort(lastRequestedPort: Int, attempt: Int): Int =
        minOf(retryFirstPort(lastRequestedPort + 1, attempt), Constants.DSH_DEFAULT_PORT + PORT_COUNT - 1)
}
