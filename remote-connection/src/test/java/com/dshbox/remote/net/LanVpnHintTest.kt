package com.dshbox.remote.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VPN 提示判定契约：VPN 未活跃恒不提示；活跃时仅私网地址端点触发；回
 * 环、隧道域名与解析失败地址不触发。
 */
class LanVpnHintTest {

    @Test fun inactiveVpnNeverHints() {
        assertFalse(
            LanVpnHint.shouldShow(
                vpnActive = false,
                targetUrls = listOf("http://192.168.1.5:3080/app"),
            ),
        )
    }

    @Test fun activeVpnWithPrivateEndpointHints() {
        assertTrue(
            LanVpnHint.shouldShow(
                vpnActive = true,
                targetUrls = listOf("http://10.1.2.3:8080/dsh"),
            ),
        )
        assertTrue(
            LanVpnHint.shouldShow(
                vpnActive = true,
                targetUrls = listOf("https://[fd00::1]:8080/dsh"),
            ),
        )
    }

    @Test fun activeVpnWithLoopbackOnlyDoesNotHint() {
        // 回环槽位不经物理网络：不触发
        assertFalse(
            LanVpnHint.shouldShow(
                vpnActive = true,
                targetUrls = listOf("http://127.0.0.1:51000/app"),
            ),
        )
    }

    @Test fun activeVpnWithHostnameOrPublicDoesNotHint() {
        // 隧道域名/公网地址不受 VPN 局域网劫持影响
        assertFalse(
            LanVpnHint.shouldShow(
                vpnActive = true,
                targetUrls = listOf("https://tunnel.example.com/dsh"),
            ),
        )
        assertFalse(
            LanVpnHint.shouldShow(
                vpnActive = true,
                targetUrls = listOf("https://203.0.113.9/dsh"),
            ),
        )
    }

    @Test fun unparseableUrlDoesNotHint() {
        assertFalse(
            LanVpnHint.shouldShow(
                vpnActive = true,
                targetUrls = listOf("not-a-url"),
            ),
        )
    }

    @Test fun mixedEndpointsHintWhenAnyPrivate() {
        assertTrue(
            LanVpnHint.shouldShow(
                vpnActive = true,
                targetUrls = listOf(
                    "https://tunnel.example.com/dsh",
                    "http://172.20.1.2:3081/app",
                ),
            ),
        )
    }
}
