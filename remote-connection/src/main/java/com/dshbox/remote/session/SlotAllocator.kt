package com.dshbox.remote.session

import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.model.SlotBinding
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 设备槽位与端点端口的分配器。
 *
 * 隔离模型：每台设备首次建立会话时绑定一个空闲回环 IP 槽位（终身不变），
 * 每个端点在该槽位上拥有一个稳定端口（首次会话由系统在绑定时刻分配并写入
 * 端点档案，此后复用）。槽位不自动驱逐：池满显式失败；槽位回收只发生在
 * 设备删除，回收的 IP 下次分配给新设备时启用全新随机端口（历史端口进入
 * 永久退役名单），保证旧设备孤儿 storage 所在的 origin 永不可达。
 *
 * 分配表随 [KeyValueStore] 持久化，与设备存档同层。
 */
class SlotAllocator(
    private val store: KeyValueStore,
    private val portPicker: PortPicker = PortPicker.Ephemeral,
) {

    /**
     * 并发约定：绑定/回收/端口记录是跨多条键值的读-改-写，以下方法统一以
     * 共享的存储对象为锁串行化——同一存储可能同时存在多个分配器实例（宿主
     * 重建门面），互斥必须跨实例生效，故不取分配器实例自身为锁。
     */

    /** 绑定设备槽位：已有绑定终身复用；否则取第一个空闲槽位。池满抛 [SlotAllocationException.PoolExhausted]。 */
    fun bindDevice(deviceId: DeviceId): SlotBinding = synchronized(store) {
        loopbackIpFor(deviceId)?.let { return SlotBinding(deviceId, it) }
        val usedIps = store.keys()
            .filter { it.startsWith(SLOT_KEY_PREFIX) }
            .mapNotNull { store.getString(it) }
            .toSet()
        val ip = loopbackSlotPool.firstOrNull { it !in usedIps }
            ?: throw SlotAllocationException.PoolExhausted(
                "Loopback slot pool is exhausted; remove unused devices first",
            )
        store.putString(SLOT_KEY_PREFIX + deviceId.value, ip)
        SlotBinding(deviceId, ip)
    }

    /** 设备当前绑定的槽位 IP；未绑定返回 null。 */
    fun loopbackIpFor(deviceId: DeviceId): String? = synchronized(store) {
        store.getString(SLOT_KEY_PREFIX + deviceId.value)
    }

    /** 端点档案中的稳定端口；首次会话尚未分配返回 null（由会话在绑定成功后经 [recordPort] 写入）。 */
    fun portForEndpoint(deviceId: DeviceId, endpointId: EndpointId): Int? = synchronized(store) {
        store.getString(PORT_KEY_PREFIX + deviceId.value + '.' + endpointId.value)?.toIntOrNull()
    }

    /** 把首次会话实际绑定成功的端口写入端点档案，此后终身复用。 */
    fun recordPort(deviceId: DeviceId, endpointId: EndpointId, port: Int) {
        synchronized(store) {
            require(port in 1..65535) { "Port must be a valid TCP port" }
            store.putString(PORT_KEY_PREFIX + deviceId.value + '.' + endpointId.value, port.toString())
        }
    }

    /**
     * 删除设备时的槽位回收：该设备全部端点端口进入其槽位 IP 的退役名单，
     * 分配表条目清除。退役端口在该 IP 下次分配时被 [PortPicker] 显式排除。
     */
    fun releaseDevice(deviceId: DeviceId) {
        synchronized(store) {
            val ip = loopbackIpFor(deviceId) ?: return
            val portKeys = store.keys().filter { it.startsWith(PORT_KEY_PREFIX + deviceId.value + '.') }
            val retired = retiredPorts(ip).toMutableSet()
            for (key in portKeys) {
                store.getString(key)?.toIntOrNull()?.let(retired::add)
                store.remove(key)
            }
            if (retired.isNotEmpty()) {
                store.putString(RETIRED_KEY_PREFIX + ip, retired.joinToString(","))
            }
            store.remove(SLOT_KEY_PREFIX + deviceId.value)
        }
    }

    /** 指定槽位 IP 的退役端口集合：再分配时不得复用。 */
    fun retiredPorts(loopbackIp: String): Set<Int> = synchronized(store) {
        store.getString(RETIRED_KEY_PREFIX + loopbackIp)
            ?.split(',')
            ?.mapNotNull(String::toIntOrNull)
            ?.toSet()
            ?: emptySet()
    }

    private companion object {
        const val SLOT_KEY_PREFIX = "slot.device."
        const val PORT_KEY_PREFIX = "slot.port."
        const val RETIRED_KEY_PREFIX = "slot.retired."
    }
}

/** 设备会话槽位池：设备绑 IP 终身不变，池满显式失败、不自动驱逐。 */
val loopbackSlotPool: List<String> = (2..11).map { "127.0.0.$it" }

/**
 * 端口选取器：为端点首次会话选取一个未退役的可用随机端口。
 *
 * 生产实现临时绑定系统分配的临时端口以验证可用性；若与退役名单冲突则
 * 换端口重试，重试额度用尽仍失败则显式报错（不静默降级）。
 */
fun interface PortPicker {

    /** 在指定回环 IP 上选取一个不属于 [exclude] 的可用端口；失败抛 [SlotAllocationException.PortPickFailed]。 */
    fun pick(loopbackIp: String, exclude: Set<Int>): Int

    /** 生产实现：系统临时端口分配 + 退役名单排除。 */
    companion object Ephemeral : PortPicker {
        private const val MAX_ATTEMPTS = 8

        override fun pick(loopbackIp: String, exclude: Set<Int>): Int {
            var lastFailure: Exception? = null
            repeat(MAX_ATTEMPTS) {
                try {
                    ServerSocket().use { socket ->
                        socket.bind(InetSocketAddress(loopbackIp, 0))
                        val port = socket.localPort
                        if (port !in exclude) return port
                    }
                } catch (e: Exception) {
                    lastFailure = e
                }
            }
            throw SlotAllocationException.PortPickFailed(
                "No bindable port available on $loopbackIp outside retired set",
                lastFailure,
            )
        }
    }
}

/** 槽位分配失败：池满显式失败，不自动驱逐；端口选取失败不静默降级。 */
sealed class SlotAllocationException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 槽位池已满（>10 台设备），引导用户删除不再使用的设备。 */
    class PoolExhausted(message: String) : SlotAllocationException(message)

    /** 端口选取失败：回环地址不可绑定或退役名单排除了全部可用端口。 */
    class PortPickFailed(message: String, cause: Throwable? = null) : SlotAllocationException(message, cause)
}
