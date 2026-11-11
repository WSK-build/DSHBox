package com.dshbox.remote.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * VPN 活动的观测器：任一活跃网络带 [NetworkCapabilities.TRANSPORT_VPN]
 * 传输时为 true。面板据此与端点目标的私网地址判定组合出「局域网连接可能
 * 不可达」的提示行。
 *
 * 观测在默认网络回调内做全量重算（allNetworks 任一能力含传输即真），回
 * 调注册失败的极端环境（回调机制不可用）按「未检测」处理——只记日志，
 * 恒 false（不显示提示），不阻塞任何功能。
 */
class LanVpnWatcher(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val vpnActiveFlow = MutableStateFlow(currentVpnActive())

    /** 当前是否有活跃网络承载 VPN 传输。 */
    val vpnActive: StateFlow<Boolean> = vpnActiveFlow.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refresh()
        }

        override fun onLost(network: Network) {
            refresh()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            refresh()
        }
    }

    private val registered: Boolean = try {
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        if (manager != null) {
            manager.registerDefaultNetworkCallback(callback, mainHandler)
            true
        } else {
            false
        }
    } catch (e: Exception) {
        Log.w(TAG, "VPN presence callback registration failed; hint stays off")
        false
    }

    private fun refresh() {
        vpnActiveFlow.value = currentVpnActive()
    }

    private fun currentVpnActive(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching {
            manager.allNetworks.any { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrDefault(false)
    }

    /** 注销网络回调（幂等）。 */
    fun close() {
        if (!registered) return
        try {
            val manager = appContext.getSystemService(ConnectivityManager::class.java)
            manager?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "VPN presence callback unregistration failed (ignored)")
        }
    }

    private companion object {
        const val TAG = "LanVpnWatcher"
    }
}
