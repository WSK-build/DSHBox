package com.dshbox.app.lanaccess

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * 局域网访问的运行句柄（DSH 卡片「更多」→「局域网访问」子页的数据源）。
 *
 * 状态纪律：
 * - **开关不跨进程重启保持**：进程重开即默认关闭（网关也从没人监听）；
 *   密钥持久化（SharedPreferences——密钥本身就是给局域网访客的门票，
 *   泄漏由用户在页面内重置兜底）。
 * - 仅当 **用户开关打开 且 DSH READY** 时监听。DSH 非 READY（停机/重启
 *   窗口）监听自动停；READY 回场自动重绑——重绑同期（网络/地址不变）
 *   端口不变（39876），局域网访客无需换地址。
 * - 断网/切网/网络能力变化自动重评：Wi-Fi 侧私网 IPv4 拿不到即停并报错
 *   （不回退 0.0.0.0/蜂窝/IPv6），有新地址且开关开着时在新地址上重绑。
 * - DSH 端口变化（重启落新端口）无需重启网关：上游每次请求现取
 *   （[LanUpstream.upstreamOf]）。
 *
 * 观测面（[State]）：UI 读 enabled/bound/lanAddress/devices/connections
 * 与 lastError 重组。设备数按来源 IP 聚合，连接数为诊断口径。
 */
object LanAccessController {

    /** 固定监听端口（高位 39876：避开常见服务段与 3080 家族）。 */
    const val PORT = 39876

    /** 密钥 SharedPreferences 的文件与键。 */
    private const val PREFS_NAME = "lan_access"
    private const val KEY_ACCESS_KEY = "access_key"

    private const val TAG = "LanAccessController"

    /** 网关的对外快照（UI 重组的单一来源）。 */
    data class State(
        /** 用户开关（意图）。进程内默认 false，不持久化。 */
        val enabled: Boolean = false,
        /** 监听就绪（绑定成功）。 */
        val bound: Boolean = false,
        /** 绑定的 Wi-Fi 地址文本。 */
        val lanAddress: String? = null,
        /** 当前访问密钥（懒生成）。 */
        val accessKey: String? = null,
        /** 在线设备数（distinct 来源 IP 数；[connections] 为诊断口径）。 */
        val devices: Int = 0,
        /** 在途连接数（诊断口径：一台设备可能并行多条连接）。 */
        val connections: Int = 0,
        /** 最近一次的异常原因（null = 正常）。 */
        val lastError: String? = null,
        /**
         * 本进程内网关是否**曾经**真正监听过（绑定成功过）：UI 的「已停止」
         * 确认行只在曾在开后停止时出现——进程刚装好、用户还没开启过的状态下
         * 不显示「已停止」（那是首进页面的空态，不是一次停止事件）。
         */
        val everStarted: Boolean = false,
    ) {
        /** 含密钥的完整入口链接（未就绪/未绑定/无密钥时 null）。 */
        val addressWithKey: String?
            get() {
                val address = lanAddress ?: return null
                val key = accessKey ?: return null
                return "http://$address:$PORT/?${LanAccessSecrets.KEY_PARAM}=$key"
            }

        /** 不含密钥的纯地址（已认证设备的续访地址）。 */
        val addressPlain: String?
            get() = lanAddress?.let { "http://$it:$PORT" }
    }

    private val stateFlow = MutableStateFlow(State())
    val state: StateFlow<State> = stateFlow.asStateFlow()

    private val gatewayRef = AtomicReference<LanGateway?>(null)
    private var appContext: Context? = null
    private var dshProvider: DshProvider? = null
    private var dshReady: () -> Boolean = { false }
    private var watcher: NetworkWatcher? = null

    /**
     * 网关在线设备/连接数的可观察回写：每次真实增减后拷贝进状态
     * （设备数走 [State.devices]，连接数留 [State.connections] 诊断）。
     */
    private val onDevicesChanged: (devices: Int, connections: Int) -> Unit = { devices, connections ->
        stateFlow.update { it.copy(devices = devices, connections = connections) }
    }

    /** DSH 运行期地址与 launch token 的取值通道（由宿主装配）。 */
    interface DshProvider {
        /** 当前 DSH 基础地址（`http://host:port`；null 时 [LanUpstream] 回退默认）。 */
        fun baseUrl(): String?

        /** 当前 DSH 进程 launch token（null = 无认证桥）。 */
        fun launchToken(): String?
    }

    /** 密钥存取（SharedPreferences，持久化）。 */
    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 装配（幂等）：读持久化密钥、注册网络回调。在 DshApp.onCreate 调用，
     * 保证别处引用时状态已就绪。
     */
    fun install(context: Context, dshProvider: DshProvider, dshReady: () -> Boolean) {
        if (appContext != null) {
            // 已装配：仅同步三态（READY 判定与 DSH 通道可能由同进程重建）。
            this.dshReady = dshReady
            this.dshProvider = dshProvider
            return
        }
        appContext = context.applicationContext
        this.dshProvider = dshProvider
        this.dshReady = dshReady
        val stored = prefs(context).getString(KEY_ACCESS_KEY, null)
        stateFlow.update { it.copy(accessKey = stored) }
        watcher = NetworkWatcher(appContext!!) { refresh() }
    }

    /**
     * 拆除观测（与 [install] 成对；进程级对象通常无需调用，销毁路径与
     * 测试平台用）：注销两个网络回调并停掉网关。幂等。
     */
    fun teardown() {
        watcher?.close()
        watcher = null
        stopGateway()
    }

    /** 读取（懒生成并持久化）访问密钥。 */
    fun ensureKey(context: Context): String {
        val existing = stateFlow.value.accessKey
        if (existing != null && LanAccessSecrets.isWellFormedKey(existing)) return existing
        val fresh = LanAccessSecrets.generateKey()
        prefs(context).edit().putString(KEY_ACCESS_KEY, fresh).apply()
        stateFlow.update { it.copy(accessKey = fresh) }
        return fresh
    }

    /** 打开/关闭开关。开关状态只写内存态（进程重开即默认关）。 */
    fun setEnabled(enabled: Boolean) {
        stateFlow.update { it.copy(enabled = enabled, lastError = null) }
        refresh()
    }

    /** 重置密钥：旧门禁 cookie 与旧兑换链接同刻失效；启监听时按新密钥重开。 */
    fun resetKey(context: Context) {
        rotateKey(context)
    }

    /**
     * 「全部断开并重置密钥」：生成新密钥并持久化，随后网关按新
     * 密钥重建——旧链接（`?k=<旧密钥>`）与旧门禁 cookie（对旧密钥的
     * HMAC）立即失效，在途连接随重建全部断开；开关保持开启。不做仅断开
     * 不换密钥的变体：停监听即重启、密钥不变时浏览器随即重连，语义无效。
     */
    fun disconnectAll(context: Context) {
        if (!stateFlow.value.enabled) return
        rotateKey(context)
    }

    /** 密钥轮换共用实现：新密钥落库 + 网关按新密钥重建（开关开着时）。 */
    private fun rotateKey(context: Context) {
        val fresh = LanAccessSecrets.generateKey()
        prefs(context).edit().putString(KEY_ACCESS_KEY, fresh).apply()
        stateFlow.update { it.copy(accessKey = fresh, devices = 0, connections = 0) }
        // 旧 cookie 的签名对新密钥必然校验不过；网关重建即完成会话灭失
        // 与在途连接断开（开关保持，监听在重建后随即恢复）。
        if (stateFlow.value.enabled) {
            synchronized(this) { stopGatewayLocked() }
            refresh()
        }
    }

    /** 状态重评（开关+READY+网络三要素合成），网关生命周期依此启停。 */
    fun refresh() {
        if (!stateFlow.value.enabled || !dshReady()) {
            stopGateway()
            return
        }
        val address = runCatching { LanBindAddress.pickCurrent() }.getOrNull()
        if (address == null) {
            stopGateway()
            stateFlow.update {
                it.copy(bound = false, lanAddress = null, lastError = "no_wlan_ipv4")
            }
            return
        }
        startGateway(address)
    }

    private fun startGateway(address: InetAddress) {
        val provider = dshProvider ?: return
        val key = stateFlow.value.accessKey ?: return
        val wanted = "${address.hostAddress}:$PORT"
        synchronized(this) {
            val running = gatewayRef.get()
            if (running != null && stateFlow.value.bound && running.boundHostPort == wanted) {
                return // 已在该地址端口监听
            }
            stopGatewayLocked()
            val built = LanGateway(
                bindAddress = address,
                port = PORT,
                gate = LanAccessGate(key) { System.currentTimeMillis() },
                upstreamProvider = {
                    LanUpstream.upstreamOf(provider.baseUrl()) to provider.launchToken()
                },
                onDevicesChanged = onDevicesChanged,
            )
            try {
                built.start()
                gatewayRef.set(built)
                stateFlow.update {
                    it.copy(
                        bound = true,
                        lanAddress = address.hostAddress,
                        lastError = null,
                        devices = 0,
                        connections = 0,
                        everStarted = true,
                    )
                }
            } catch (e: LanGatewayException) {
                Log.w(TAG, "LAN gateway start failed: ${e.message}")
                stateFlow.update {
                    it.copy(
                        bound = false,
                        lanAddress = address.hostAddress,
                        lastError = "bind_failed",
                    )
                }
            }
        }
    }

    private fun stopGatewayLocked() {
        gatewayRef.getAndSet(null)?.let { gw ->
            runCatching { gw.stop() }
        }
        stateFlow.update { it.copy(bound = false, devices = 0, connections = 0) }
    }

    private fun stopGateway() {
        synchronized(this) { stopGatewayLocked() }
    }
}

/**
 * 网络状态观测：除默认网络回调外，再注册针对 Wi-Fi（TRANSPORT_WIFI）的
 * 专用回调并实现 [ConnectivityManager.NetworkCallback.onLinkPropertiesChanged]
 * ——绑定地址的 IP 变化（DHCP 续租/重连换址）在链路属性里先于默认回调
 * 的能力触发可用，两个回调都汇入同一重评：地址变了按新地址重绑。注册
 * 失败的极端环境只记日志（能力退化），不阻塞开关功能本身。注销随
 * [LanAccessController] 的生命周期（进程级对象，[close] 供销毁路径与
 * 测试平台对齐）。
 */
private class NetworkWatcher(
    context: Context,
    onChange: () -> Unit,
) {

    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var registeredDefault = false
    private var registeredWifi = false

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onChange()
        override fun onLost(network: Network) = onChange()
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: android.net.NetworkCapabilities,
        ) = onChange()
        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: android.net.LinkProperties,
        ) = onChange()
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onChange()
        override fun onLost(network: Network) = onChange()
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: android.net.NetworkCapabilities,
        ) = onChange()
        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: android.net.LinkProperties,
        ) = onChange()
    }

    init {
        if (manager != null) {
            try {
                manager.registerDefaultNetworkCallback(defaultCallback, handler)
                registeredDefault = true
            } catch (e: Exception) {
                Log.w(TAG, "network callback registration failed (ignored): ${e.message}")
            }
            try {
                val request = android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                manager.registerNetworkCallback(request, wifiCallback, handler)
                registeredWifi = true
            } catch (e: Exception) {
                Log.w(TAG, "wifi callback registration failed (ignored): ${e.message}")
            }
        }
    }

    /** 注销两个回调（幂等；生命周期与 [LanAccessController] 一致）。 */
    fun close() {
        val connectivity = manager ?: return
        if (registeredDefault) {
            runCatching { connectivity.unregisterNetworkCallback(defaultCallback) }
            registeredDefault = false
        }
        if (registeredWifi) {
            runCatching { connectivity.unregisterNetworkCallback(wifiCallback) }
            registeredWifi = false
        }
    }

    private companion object {
        const val TAG = "LanAccessWatcher"
    }
}
