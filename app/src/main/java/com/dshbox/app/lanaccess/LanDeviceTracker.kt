package com.dshbox.app.lanaccess

/**
 * 局域网在线设备账（可单测载体）：按客户端**来源 IP** 聚合在线量。
 *
 * 口径（浏览器行为决定）：
 * - [deviceCount]：distinct 活跃来源 IP 数 = **设备数**（UI 口径）。浏览器
 *   对同一页面会并行开多条连接、且本网关每请求一连接，直接数连接会让一台
 *   设备报成多条。
 * - [connectionCount]：在途连接数 = 各 IP 活跃连接数之和（**诊断口径**，
 *   一台设备可并行多条连接）。
 *
 * 并发纪律：入账/出账/清账都在同一把锁内原子完成，计数恒为非负——
 * - 同一 IP 的多条连接记同一份账（设备数不增，连接数逐条平账）；
 * - 出账对**未在册**的 IP 是空操作（[clear] 之后的迟到出账既不会把账减成
 *   负数，也不会凭空重建条目）；
 * - [clear] 清空在册设备与连接（网关停止/密钥轮换用）。
 */
class LanDeviceTracker(
    /** 最后活跃时刻的时钟（测试可注入固定时钟）。 */
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {

    /** 单个 IP 的活跃账（活跃连接数与最后活跃时刻）。 */
    private class Session {
        var activeConnections: Int = 0
        var lastActiveMillis: Long = 0L
    }

    /** 账目的互斥门（入账/出账/清账/读账共用）。 */
    private val lock = Any()

    /** 在册设备账（IP → 活跃账）；只在 [lock] 内访问。 */
    private val sessions = LinkedHashMap<String, Session>()

    /** 在线设备数（distinct 活跃来源 IP 数；UI 口径）。 */
    val deviceCount: Int
        get() = synchronized(lock) { sessions.size }

    /** 在途连接数（诊断口径：一台设备可并行多条连接）。 */
    val connectionCount: Int
        get() = synchronized(lock) { sessions.values.sumOf { it.activeConnections } }

    /**
     * 设备数与连接数的**同刻**快照（两本账在同一把锁内读取，跨读不撕裂）——
     * 观察者线程据此判断账目自洽（连接数 ≥ 设备数 ≥ 0）。
     */
    fun snapshot(): Counts = synchronized(lock) {
        Counts(
            devices = sessions.size,
            connections = sessions.values.sumOf { it.activeConnections },
        )
    }

    /** 一份在线量快照（设备数与连接数）。 */
    data class Counts(val devices: Int, val connections: Int)

    /** 设备账快照（观察面：IP → 活跃连接数）。 */
    val sessionCounts: Map<String, Int>
        get() = synchronized(lock) { sessions.mapValues { it.value.activeConnections } }

    /** 最后活跃时刻快照（观察面：IP → 最后活跃时刻毫秒）。 */
    val lastActiveMillisByIp: Map<String, Long>
        get() = synchronized(lock) { sessions.mapValues { it.value.lastActiveMillis } }

    /**
     * 入账：一条来自 [clientIp] 的连接开始。同一 IP 的多条连接记同一份账
     * （设备数不增、连接数 +1），最后活跃时刻刷新。
     */
    fun onConnect(clientIp: String) {
        synchronized(lock) {
            val session = sessions.getOrPut(clientIp) { Session() }
            session.activeConnections += 1
            session.lastActiveMillis = clockMillis()
        }
    }

    /**
     * 出账：一条来自 [clientIp] 的连接结束。活跃连接数 -1，归零即整个 IP
     * 出账（设备数 -1）。未在册的 IP 是空操作——计数因此恒非负。
     */
    fun onDisconnect(clientIp: String) {
        synchronized(lock) {
            val session = sessions[clientIp] ?: return
            session.activeConnections -= 1
            if (session.activeConnections <= 0) sessions.remove(clientIp)
        }
    }

    /** 清账（网关停止/密钥轮换）：在册设备与连接一并归零。 */
    fun clear() {
        synchronized(lock) { sessions.clear() }
    }
}
