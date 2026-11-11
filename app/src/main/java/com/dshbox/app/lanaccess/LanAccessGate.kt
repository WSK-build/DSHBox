package com.dshbox.app.lanaccess

import java.util.concurrent.ConcurrentHashMap

/**
 * 局域网门禁决策器（纯逻辑，JVM 单测锁定）：把一枚请求的三类证据
 * （query 密钥、门禁 cookie、来源 IP 的失败计数）裁决为一条指令。
 *
 * 决策矩阵（先入口兑换、后 cookie 续访，与浏览器首次进入路径一致）：
 * - 携带 `?k=<key>`：与密钥**常量时间**比较。
 *   - 相符 → [Decision.Login]（种 cookie + 302 回 `/`）；
 *   - 不符 → 记失败并 [Decision.Rejected]（401 简单页，不含任何 DSH 内容）。
 * - 无 `?k`：携带 [LanAccessSecrets.COOKIE_NAME] cookie 且签名对 →
 *   [Decision.Pass]（转发上游）；
 *   否则记失败并拒绝（401）。
 * - 同一 IP 连续失败达 [MAX_CONSECUTIVE_FAILURES] 次 → 进入
 *   [RATE_LOCK_MILLIS] 的锁定窗口（[Decision.RateLimited]，60s 内直接的
 *   兑换与续访请求一律拒绝，不给撞库面）。窗口内不累计新失败。
 *
 * 成功（Login 或有效 Pass）即清零该 IP 失败计数——「10 次连续」语义。
 */
class LanAccessGate(private val key: String, private val clockMillis: () -> Long) {

    /** 门禁裁决指令。 */
    sealed class Decision {
        /** `?k` 兑换成功：种 cookie 后 302 回根。 */
        data class Login(val cookieValue: String) : Decision()

        /** 已认证：直接转发上游。 */
        data object Pass : Decision()

        /** 未认证：401 简单页（含一次失败记账的语义）。 */
        data object Rejected : Decision()

        /** 锁定窗口内：不动计数，直接拒绝（延迟感由网关统一 does）。 */
        data object RateLimited : Decision()
    }

    /** 每个 IP 一份失败账：连续失败次数与窗口起点。 */
    private class FailureLedger {
        var count = 0
        var lockedUntil = 0L
    }

    private val ledger = ConcurrentHashMap<String, FailureLedger>()

    /** 当前处于锁定窗口的 IP 数（诊断/测试口径）。 */
    val lockedIpCount: Int
        get() = ledger.count { it.value.lockedUntil > clockMillis() }

    /**
     * 对一次请求裁决。cookie 值载荷由调用方（网关管线）从请求头解析；
     * [queryKeyValue] 为 null 表示请求未携带 `?k=`。
     */
    fun decide(
        clientIp: String,
        queryKeyValue: String?,
        cookieValues: List<String>,
        nowMillis: Long = clockMillis(),
    ): Decision {
        val account = ledger.computeIfAbsent(clientIp) { FailureLedger() }
        synchronized(account) {
            val beforeLock = nowMillis < account.lockedUntil
            if (beforeLock) {
                // 锁定窗口内：不再触发新的失败记账（窗口不做延期滚结）。
                return Decision.RateLimited
            }
            if (account.lockedUntil != 0L) {
                // 锁定已过：复位账目，重新累计。
                account.lockedUntil = 0L
                account.count = 0
            }

            // 1. 入口兑换：带 `?k=` 的请求一律按兑换路径先行（已认证访客
            //    重复访问带钥匙地址也重发 cookie，幂等无副作用）。
            if (queryKeyValue != null) {
                if (matchesKey(queryKeyValue)) {
                    account.count = 0
                    return Decision.Login(LanAccessSecrets.cookieValueFor(key))
                }
                return recordFailure(account, nowMillis)
            }

            // 2. cookie 续访。
            if (LanAccessSecrets.verifyCookie(key, cookieValues)) {
                account.count = 0
                return Decision.Pass
            }
            return recordFailure(account, nowMillis)
        }
    }

    /** 密钥常量时间比较（长度不等等价于不等，与 GateCheck 同口径）。 */
    private fun matchesKey(candidate: String): Boolean =
        java.security.MessageDigest.isEqual(
            candidate.toByteArray(Charsets.UTF_8),
            key.toByteArray(Charsets.UTF_8),
        )

    /** 记一次失败：达到上界即落锁定窗口，本次请求仍按拒绝处理。 */
    private fun recordFailure(account: FailureLedger, nowMillis: Long): Decision {
        account.count += 1
        if (account.count >= MAX_CONSECUTIVE_FAILURES) {
            account.lockedUntil = nowMillis + RATE_LOCK_MILLIS
            account.count = 0
        }
        return Decision.Rejected
    }

    /** 丢弃全部失败账（网关停止/密钥重置时清理）。 */
    fun reset() {
        ledger.clear()
    }

    companion object {
        /** 同 IP 连续失败达到上界后的锁定时长（毫秒，60s）。 */
        const val RATE_LOCK_MILLIS = 60_000L

        /** 锁定阈值（连续失败 10 次）。 */
        const val MAX_CONSECUTIVE_FAILURES = 10
    }
}
