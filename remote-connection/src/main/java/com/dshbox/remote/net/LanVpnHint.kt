package com.dshbox.remote.net

import java.net.URI

/**
 * 面板「局域网连接可能不可达」提示的纯判定（JVM 单测锁定）：VPN 正活跃
 * 且清单里存在以私网地址为 host 的端点（直连端点的目标地址，或代理端点
 * 的上游地址都按 targetUrl 判定）时提示。
 *
 * 判定口径收口在 [PrivateAddress.isPrivateAddressHost]：回环槽位地址
 * （127.x）与公网/隧道域名端点不触发提示——回环不经物理网络，隧道域名的
 * 可达性不受 VPN 局域网劫持影响。
 */
object LanVpnHint {

    /**
     * 是否显示提示：[vpnActive] 为真且任一端点的 targetUrl host 是私网地
     * 址。targetUrl 解析失败或无 host 的端点不参与（无地址证据不提示）。
     */
    fun shouldShow(vpnActive: Boolean, targetUrls: List<String>): Boolean {
        if (!vpnActive) return false
        return targetUrls.any { url ->
            val host = runCatching { URI(url.trim()).host }.getOrNull()
            !host.isNullOrBlank() && PrivateAddress.isPrivateAddressHost(host)
        }
    }
}
