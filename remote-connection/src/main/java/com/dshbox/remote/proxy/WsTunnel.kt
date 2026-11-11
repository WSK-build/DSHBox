package com.dshbox.remote.proxy

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket 隧道：升级请求透传，101 之后的字节双向泵（不解析帧）。
 *
 * 代理只负责把升级握手原样送达并把两个方向上的字节流互相搬运；
 * WS 帧语义、心跳与关闭握手全部留给两端。
 *
 * 泵线程的收口纪律：任一方向的关闭（EOF 或套接字被并发关闭抛出的
 * SocketException）都是正常终局——异常必须就地吞掉并通知对端方向，
 * 绝不允许冲出线程 Runnable（未捕获异常会杀死整个进程）。
 */
object WsTunnel {

    private const val UP_BUFFER_BYTES = 8 * 1024

    /** 请求是否为 WS 升级（`Upgrade: websocket` + `Connection: upgrade` 语义）。 */
    fun isUpgradeRequest(headers: List<ProxyHeader>): Boolean {
        val upgrade = headers.firstOrNull { it.name.equals("Upgrade", ignoreCase = true) }?.value
        if (upgrade == null || !upgrade.contains("websocket", ignoreCase = true)) return false
        val connection = headers.firstOrNull { it.name.equals("Connection", ignoreCase = true) }?.value
        return connection != null && connection.contains("upgrade", ignoreCase = true)
    }

    /**
     * 101 之后的双向字节泵：阻塞直至任一方向 EOF。返回前不关闭套接字
     * （由调用方统一收尾）。
     *
     * @param closed 双向协作位：一侧退出后置位，另一侧的阻塞读靠套接字
     *   关闭唤醒并经异常路径退出（不中断线程）。
     */
    fun pumpBidirectional(
        clientOut: OutputStream,
        clientIn: InputStream,
        upstreamOut: OutputStream,
        upstreamIn: InputStream,
        closed: AtomicBoolean = AtomicBoolean(false),
    ) {
        val clientToUpstream = Thread {
            pump(clientIn, upstreamOut, closed)
            // 客户端方向先退出（EOF/故障）：关闭上游流唤醒仍阻塞在
            // 上游读上的主泵（主泵随后经调用方统一收尾）
            runCatching { upstreamIn.close() }
        }
        clientToUpstream.isDaemon = true
        clientToUpstream.start()
        pump(upstreamIn, clientOut, closed)
        // 主方向退出：关闭客户端流唤醒客户端方向的阻塞读，再等待其收尾
        runCatching { clientIn.close() }
        clientToUpstream.join()
    }

    /**
     * 单向字节搬运：逐块读写并即写即刷（不缓冲、不解析）。对端的关闭以
     * EOF（返回 -1）或本侧套接字被并发关闭抛出的 IOException 两种形态
     * 出现，均按正常终局处理；其余 IOException 与 RuntimeException 同样
     * 就地收口（泵线程绝不让任何异常冲出 Runnable——未捕获的运行时异常
     * 会杀死整个进程）。
     */
    fun pump(input: InputStream, output: OutputStream, closed: AtomicBoolean = AtomicBoolean(false)) {
        val buffer = ByteArray(UP_BUFFER_BYTES)
        try {
            while (!closed.get()) {
                val read = input.read(buffer)
                if (read < 0) return
                if (closed.get()) return
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: IOException) {
            // 套接字被并发关闭（Socket closed / Connection reset）：正常终局
        } catch (_: RuntimeException) {
            // 缓冲区溢出/非法状态等运行时异常：与 IO 故障同规格收口
        } finally {
            closed.set(true)
        }
    }
}
