package interlock.relay.core.exec.stream

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 面板输入的专用工人池（默认 2 线程）。
 *
 * 生命周期必须与 [interlock.relay.core.runtime.RelayContainer] 的 stop/start 解耦：
 * 容器在 `RelayRuntime` 里是可复用的（STOPPED→STARTING 不重建容器），stop() 关掉池
 * 之后下一次 execute 必须能重建——否则重启沙箱后每个 `/input` 都会 RejectedExecution
 * 并被端点当成连接失败。stop 与新请求竞争时只如实回失败，不掐连接。
 *
 * `block` 里允许跑阻塞事务；协程侧只挂起等待。端点 `withTimeoutOrNull` 取消的是
 * 等待，worker 继续跑完、结果丢弃（远端事务可能已提交，回话按"结局未知"）。
 */
internal class PanelInputWorkers(
    private val threads: Int = 2,
    private val name: String = "relay-panel-input",
) {
    private val lock = Any()
    private var pool: ExecutorService? = null

    private fun pool(): ExecutorService = synchronized(lock) {
        val existing = pool
        if (existing != null && !existing.isShutdown) {
            existing
        } else {
            Executors.newFixedThreadPool(threads) { runnable ->
                Thread(runnable, name).apply { isDaemon = true }
            }.also { pool = it }
        }
    }

    suspend fun execute(block: () -> Pair<Boolean, String>): Pair<Boolean, String> =
        suspendCancellableCoroutine { cont ->
            val ready = try {
                pool()
            } catch (broken: Throwable) {
                if (cont.isActive) {
                    cont.resumeWith(Result.success(false to ("panel input worker unavailable: " +
                        broken.javaClass.simpleName)))
                }
                return@suspendCancellableCoroutine
            }
            val task = Runnable {
                val outcome = try {
                    block()
                } catch (broken: Throwable) {
                    false to ("panel input error: " + broken.javaClass.simpleName)
                }
                if (cont.isActive) cont.resumeWith(Result.success(outcome))
            }
            try {
                ready.execute(task)
            } catch (_: RejectedExecutionException) {
                // stop 与请求竞争：worker 已关。下一次请求（重启后）会重建池。
                if (cont.isActive) {
                    cont.resumeWith(Result.success(false to "panel input worker pool is shut down"))
                }
            }
        }

    /** 通道停机：关当前池；下一次 execute 自动重建（容器重启复用）。 */
    fun shutdown() {
        synchronized(lock) {
            pool?.shutdownNow()
            pool = null
        }
    }
}
