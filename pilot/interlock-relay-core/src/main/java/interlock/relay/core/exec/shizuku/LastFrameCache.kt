package interlock.relay.core.exec.shizuku

import interlock.relay.core.runtime.monotonicNow
import java.io.File

/**
 * 可信虚拟屏「上一帧」的缓存。
 *
 * 服务端一次只递一张新帧：那块屏停止重绘时 `capture` 取不到帧，直接回复是一条
 * `E_BACKEND_UNAVAILABLE`，而录屏那一条通路对同一件事的做法是重复上一帧。这里让截图也
 * 走同一口径 —— 取不到新帧时把上一次成功编码的那一份再给一次，并在回包里标明它不是此刻。
 *
 * 三条边界：
 * - 只存**已编码**的那一份（本通路是无损 PNG，实测一块 1080x2400 的屏约 760KB），不存
 *   Bitmap（同样尺寸按 ARGB_8888 算是 10.4MB 一份）。
 * - 换过一块屏就作废：`displayId` 与缓存里那个不一致时既不读也不写。拿上一块屏的画面
 *   给这一块屏说话，正是这条通路最不该犯的错误。
 * - 取用时间只在内存里，所以进程重启后即便文件还在也不会被端出去 —— 那一刻它有多旧已不可知。
 */
class LastFrameCache(
    private val file: File,
    private val now: () -> Long = ::monotonicNow,
    private val maxBytes: Long = MAX_BYTES,
) {

    private var displayId = -1
    private var takenAtMs = 0L

    /** 一次成功的取帧。写不进去不该让这次调用失败，只是下一次静置时没有旧帧可给。 */
    fun remember(display: Int, bytes: ByteArray): Boolean {
        if (bytes.size.toLong() > maxBytes) {
            clear()
            return false
        }
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }.fold(
            onSuccess = {
                displayId = display
                takenAtMs = now()
                true
            },
            onFailure = { clear(); false },
        )
    }

    /** 取回 `(字节, 已经过了多久)`；没有可给的旧帧时返回 null，调用方据此回到不可达。 */
    fun peek(display: Int): Pair<ByteArray, Long>? {
        if (displayId != display || takenAtMs == 0L || !file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        // 单调时钟在重启时归零，而进程内存里的 takenAtMs 与之同源，所以差值不会为负；
        // 真读到负值说明这条路径被跨过一次时钟重置，宁可不给。
        val age = now() - takenAtMs
        return if (age < 0L) null else bytes to age
    }

    fun clear() {
        displayId = -1
        takenAtMs = 0L
        runCatching { file.delete() }
    }

    private companion object {
        /** 与一帧无损 PNG 同量级；超了就说明这块屏给的东西不该被留在本地。 */
        const val MAX_BYTES = 8L * 1024 * 1024
    }
}
