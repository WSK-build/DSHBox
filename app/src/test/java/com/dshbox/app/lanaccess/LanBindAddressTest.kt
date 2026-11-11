package com.dshbox.app.lanaccess

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * 监听地址选择的口径（纯判定，JVM）：Wi-Fi 侧网卡名 + 私网 IPv4 + 非回环
 * 才可绑定；回环/蜂窝/公网/非 wlan 命名一律拒绝。
 */
class LanBindAddressTest {

    private fun ipOf(host: String): InetAddress = InetAddress.getByName(host)

    @Test
    fun acceptsPrivateWlanAddress() {
        assertTrue(
            LanBindAddress.isBindableWlanIp4("wlan0", ipOf("192.168.1.22"), up = true),
        )
        assertTrue(
            LanBindAddress.isBindableWlanIp4("wlp3s0", ipOf("10.0.4.8"), up = true),
        )
        assertTrue(
            LanBindAddress.isBindableWlanIp4("swlan0", ipOf("172.16.9.1"), up = true),
        )
    }

    @Test
    fun rejectsLoopbackCellularAndPublicAndDown() {
        // 回环
        assertFalse(LanBindAddress.isBindableWlanIp4("lo", ipOf("127.0.0.1"), up = true))
        // wlan 名下也明确排除回环
        assertFalse(LanBindAddress.isBindableWlanIp4("wlan0", ipOf("127.0.0.1"), up = true))
        // 蜂窝/其它传输的命名
        assertFalse(LanBindAddress.isBindableWlanIp4("rmnet0", ipOf("10.66.1.2"), up = true))
        assertFalse(LanBindAddress.isBindableWlanIp4("eth0", ipOf("192.168.100.5"), up = true))
        // 公网地址（即使挂在 wlan 名上）
        assertFalse(LanBindAddress.isBindableWlanIp4("wlan0", ipOf("8.8.8.8"), up = true))
        // 接口 down
        assertFalse(LanBindAddress.isBindableWlanIp4("wlan0", ipOf("192.168.1.1"), up = false))
    }

    @Test
    fun privateRangesMatchExactly() {
        assertTrue(LanBindAddress.isPrivateIpv4(byteArrayOf(10.toByte(), 0, 0, 3)))
        assertTrue(LanBindAddress.isPrivateIpv4(byteArrayOf(172.toByte(), 16, 0, 1)))
        assertTrue(LanBindAddress.isPrivateIpv4(byteArrayOf(172.toByte(), 31, 255.toByte(), 255.toByte())))
        assertTrue(LanBindAddress.isPrivateIpv4(byteArrayOf(192.toByte(), 168.toByte(), 7, 7)))
        // 172 越界段（172.32 非私网）与 192 非 .168 二段
        assertFalse(LanBindAddress.isPrivateIpv4(byteArrayOf(172.toByte(), 32, 0, 1)))
        assertFalse(LanBindAddress.isPrivateIpv4(byteArrayOf(172.toByte(), 15, 0, 1)))
        assertFalse(LanBindAddress.isPrivateIpv4(byteArrayOf(192.toByte(), 167.toByte(), 0, 1)))
        assertFalse(LanBindAddress.isPrivateIpv4(byteArrayOf(11, 0, 0, 1)))
    }

    @Test
    fun wifiLikeNameScope() {
        assertTrue(LanBindAddress.isWifiLikeName("wlan0"))
        assertTrue(LanBindAddress.isWifiLikeName("WLAN2"))
        assertTrue(LanBindAddress.isWifiLikeName("wlp2s0"))
        assertTrue(LanBindAddress.isWifiLikeName("swlan0"))
        assertFalse(LanBindAddress.isWifiLikeName("rmnet_data0"))
        assertFalse(LanBindAddress.isWifiLikeName("lo"))
        assertFalse(LanBindAddress.isWifiLikeName("eth0"))
    }
}
