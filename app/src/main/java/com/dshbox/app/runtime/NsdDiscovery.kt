package com.dshbox.app.runtime

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 无线调试端口的 mDNS 发现。
 *
 * ## 为什么必须在 App 侧做
 *
 * 配对端口与连接端口由设备通过 mDNS 广播（`_adb-tls-pairing._tcp` /
 * `_adb-tls-connect._tcp`），而端口每次开启无线调试都会变。`NsdManager` 是
 * Android 系统 API，**只有 App 侧能用**——沙箱里的 adb 虽然也有 mDNS 能力，
 * 但本通道的设计是让用户只抄 6 位码，地址由应用自动填入。
 *
 * ## 为什么是"常驻会话 + 缓存"，而不是每次现开一次发现
 *
 * 早先的实现每次 `discover()` 都 `discoverServices` 一次、拿到结果再 `stop`：
 *
 * - `NsdManager` 对同一服务类型**同一时刻只允许一个发现会话**，而一次发现最长要等
 *   6 秒。面板的自动重试每 2 秒发起一次，后一次必然撞上 `FAILURE_ALREADY_ACTIVE`，
 *   被当成"没发现"——表现就是"点好几次才刷新，甚至一直不刷新"。
 * - 每次都是全新会话，`onServiceLost` 无人接收，端口变了也无从得知，只能重新
 *   stop/start；而 stop 是异步的，紧接着 start 又会撞车。
 *
 * 现在每个服务类型只开**一个**常驻会话，结果缓存在内存里：
 *
 * - `discover()` 先看缓存，命中即返回（毫秒级），未命中才等下一次回调；
 * - `onServiceFound` 持续更新缓存（端口变化是新的服务实例，会重新广播并解析）；
 * - `onServiceLost` 清掉对应缓存，避免把过期端口当现状返回；
 * - 同一时刻只有一个会话，不会再出现"自己和自己抢"的 ALREADY_ACTIVE。
 *
 * 会话由 [stop] 显式关闭（配对向导收起时调用）。
 */
class NsdDiscovery(private val context: Context) {

    private class Session(val type: String) {
        var listener: NsdManager.DiscoveryListener? = null
        var running = false

        /** 最近一次解析成功的地址（`host:port`）；`onServiceLost` 会清空。 */
        var latest: String? = null

        /** 正在等结果的一方；一次回调唤醒全部。 */
        val waiters = mutableListOf<CompletableDeferred<String>>()
    }

    private val lock = Mutex()
    private val sessions = mutableMapOf<String, Session>()

    /** 发现一个服务实例并解析出地址；超时或失败返回 null。 */
    suspend fun discover(serviceType: String, timeoutMs: Long = DISCOVERY_TIMEOUT_MS): String? {
        val session = lock.withLock {
            sessions.getOrPut(serviceType) { Session(serviceType) }.also { start(it) }
        }
        session.latest?.let { return it }
        val waiter = CompletableDeferred<String>()
        synchronized(session) { session.waiters += waiter }
        return try {
            withTimeoutOrNull(timeoutMs) { waiter.await() }
        } finally {
            synchronized(session) { session.waiters -= waiter }
        }
    }

    /** 停止某类服务的常驻发现（配对向导收起时调用）。 */
    suspend fun stop(serviceType: String) {
        val session = lock.withLock { sessions.remove(serviceType) } ?: return
        session.running = false
        session.latest = null
        session.listener?.let { listener ->
            runCatching {
                context.getSystemService(NsdManager::class.java)?.stopServiceDiscovery(listener)
            }
        }
    }

    /** 启动常驻会话；已在跑则不动。调用方持有 [lock]。 */
    private fun start(session: Session) {
        if (session.running) return
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                runCatching { nsd.resolveService(info, resolver(nsd, session)) }
                    .onFailure { android.util.Log.w(TAG, "resolve rejected", it) }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                // 端口变化/弹窗关闭都会让旧实例消失：清缓存，别把过期地址当现状。
                val stale = "${info.host?.hostAddress ?: ""}:${info.port}"
                synchronized(session) {
                    if (session.latest == stale) session.latest = null
                }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                android.util.Log.w(TAG, "start discovery failed: $errorCode")
                session.running = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        runCatching { nsd.discoverServices(session.type, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess {
                session.listener = listener
                session.running = true
            }
            .onFailure { android.util.Log.w(TAG, "discoverServices rejected", it) }
    }

    private fun resolver(nsd: NsdManager, session: Session) = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
            // 解析失败多半是并发节流：把会话停掉重开一次，交给下一次 discover 触发。
            android.util.Log.w(TAG, "resolve failed: $errorCode")
            val listener = session.listener ?: return
            runCatching { nsd.stopServiceDiscovery(listener) }
            session.running = false
            session.listener = null
        }

        override fun onServiceResolved(info: NsdServiceInfo) {
            val host = info.host?.hostAddress
            val port = info.port
            if (host.isNullOrBlank() || port <= 0) return
            val address = "$host:$port"
            val waiters = synchronized(session) {
                session.latest = address
                session.waiters.toList().also { session.waiters.clear() }
            }
            waiters.forEach { it.complete(address) }
        }
    }

    companion object {
        private const val TAG = "NsdDiscovery"

        /** 配对/连接服务的 mDNS 类型（见）。 */
        const val PAIRING_SERVICE = "_adb-tls-pairing._tcp"
        const val CONNECT_SERVICE = "_adb-tls-connect._tcp"

        /** 发现超时：无线调试页面打开后广播通常在 1 秒内可见。 */
        private const val DISCOVERY_TIMEOUT_MS = 6_000L
    }
}
