package developer.access.root

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 认证失败限流。
 *
 * ## 作用边界
 *
 * 限流防的是**在线猜测**：攻击者反复用错误访问码连服务端，服务端逐次加长等待。
 * 它**不防离线爆破**——抓到一次握手即可本地穷举密钥空间，完全不碰服务端。
 * 离线爆破由**密钥长度**（128 bit）挡，限流只是补充。
 *
 * ## 两级处置
 *
 * 1. 连续失败 [threshold] 次 → 指数退避（封顶 [maxBackoffMs]）；
 * 2. 持续失败到 [lockoutThreshold] 次 → **拒绝**，直到用户重新生成访问码。
 *    只退避不拒绝等于给了无限次在线尝试（每次窗口过后仍可再试），
 *    因此设这一级：持续失败必须拒绝，并提示用户重新生成访问码。
 *
 * ## 状态
 *
 * 计数与退避时长是**内存态**：执行器重启即清零。这不构成绕过——
 * 重启不改变密钥强度，也不给攻击者额外的猜测机会（重试次数并未因此增加）。
 */
class AuthLimiter(
    /** 连续失败到该次数后开始退避。 */
    private val threshold: Int = 10,
    /** 首次退避的时长（毫秒），之后按失败次数指数增长。 */
    private val baseBackoffMs: Long = 1_000L,
    /** 退避时长上限（毫秒）。 */
    private val maxBackoffMs: Long = 60_000L,
    /** 单个对端连续失败到该次数后永久拒绝，直到重新生成访问码。 */
    private val lockoutThreshold: Int = 50,
) {
    private val failures = ConcurrentHashMap<String, AtomicInteger>()
    private val lockedUntil = ConcurrentHashMap<String, Long>()
    private val lockedOut = AtomicBoolean(false)

    /** 是否已进入"持续失败"的拒绝态（只能由重新生成访问码解除）。 */
    fun isLockedOut(): Boolean = lockedOut.get()

    /**
     * 当前是否处于退避窗口内。
     *
     * @param peer 对端标识。
     * @param nowMs 当前时刻。
     */
    fun isLocked(peer: String, nowMs: Long): Boolean {
        val until = lockedUntil[peer] ?: return false
        if (nowMs < until) return true
        lockedUntil.remove(peer)
        return false
    }

    /**
     * 记录一次认证失败，并在达到阈值后进入退避。
     *
     * 连续失败达到 [lockoutThreshold] 时置永久拒绝标志（由 [reset] 解除），
     * 此时返回 [LOCKED_OUT] 供调用方给出"请重新生成访问码"的提示。
     *
     * @param peer 对端标识。
     * @param nowMs 当前时刻。
     * @return 退避剩余毫秒数（0 表示未进入退避）；[LOCKED_OUT] 表示已永久拒绝。
     */
    fun recordFailure(peer: String, nowMs: Long): Long {
        val count = failures.computeIfAbsent(peer) { AtomicInteger() }.incrementAndGet()
        if (count >= lockoutThreshold) {
            lockedOut.set(true)
            return LOCKED_OUT
        }
        if (count < threshold) return 0L
        val exponent = (count - threshold).coerceAtMost(16)
        val backoff = (baseBackoffMs shl exponent).coerceAtMost(maxBackoffMs)
        lockedUntil[peer] = nowMs + backoff
        return backoff
    }

    /** 认证成功后清空该对端的失败计数与退避。 */
    fun recordSuccess(peer: String) {
        failures.remove(peer)
        lockedUntil.remove(peer)
    }

    /** 清空全部状态；重新生成访问码时调用，同时解除永久拒绝。 */
    fun reset() {
        failures.clear()
        lockedUntil.clear()
        lockedOut.set(false)
    }

    companion object {
        /** [recordFailure] 的返回值：已进入"持续失败"的永久拒绝态。 */
        const val LOCKED_OUT: Long = -1L
    }
}
