package com.dshbox.remote.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 私有地址判定契约：三个 IPv4 私网段的段内/段外边界、前导零段拒绝、ULA
 * 与链路本地的 IPv6 口径、IPv4-mapped IPv6 的解包判定、回环排除、公网与
 * 非法串排除、主机名恒 false、带端口与方括号的 host 形态剥离。
 */
class PrivateAddressTest {

    // ---- IPv4 10.0.0.0/8 ----

    @Test fun tenSlashEightBoundaries() {
        assertTrue(PrivateAddress.isPrivateAddressHost("10.0.0.0"))
        assertTrue(PrivateAddress.isPrivateAddressHost("10.0.0.1"))
        assertTrue(PrivateAddress.isPrivateAddressHost("10.255.255.255"))
        assertFalse(PrivateAddress.isPrivateAddressHost("9.255.255.255"))
        assertFalse(PrivateAddress.isPrivateAddressHost("11.0.0.0"))
    }

    // ---- IPv4 172.16.0.0/12 ----

    @Test fun oneSevenTwoSlashTwelveBoundaries() {
        assertTrue(PrivateAddress.isPrivateAddressHost("172.16.0.0"))
        assertTrue(PrivateAddress.isPrivateAddressHost("172.31.255.255"))
        assertTrue(PrivateAddress.isPrivateAddressHost("172.20.1.2"))
        // 段外：172.15.x 与 172.32.x 都不是本段
        assertFalse(PrivateAddress.isPrivateAddressHost("172.15.0.1"))
        assertFalse(PrivateAddress.isPrivateAddressHost("172.32.0.1"))
        // 只有第二段在 16..31 才算，第三段越界仍是公网
        assertFalse(PrivateAddress.isPrivateAddressHost("172.16.256.0"))
    }

    // ---- IPv4 192.168.0.0/16 ----

    @Test fun oneNineTwoSlashSixteenBoundaries() {
        assertTrue(PrivateAddress.isPrivateAddressHost("192.168.0.0"))
        assertTrue(PrivateAddress.isPrivateAddressHost("192.168.1.5"))
        assertTrue(PrivateAddress.isPrivateAddressHost("192.168.255.255"))
        assertFalse(PrivateAddress.isPrivateAddressHost("192.167.0.1"))
        assertFalse(PrivateAddress.isPrivateAddressHost("192.169.0.1"))
    }

    // ---- 回环排除 ----

    @Test fun loopbackExcluded() {
        assertFalse(PrivateAddress.isPrivateAddressHost("127.0.0.1"))
        assertFalse(PrivateAddress.isPrivateAddressHost("127.0.0.2"))
        assertFalse(PrivateAddress.isPrivateAddressHost("127.255.255.254"))
        assertFalse(PrivateAddress.isPrivateAddressHost("::1"))
        assertFalse(PrivateAddress.isPrivateAddressHost("[::1]"))
        assertFalse(PrivateAddress.isPrivateAddressHost("[::1]:8443"))
    }

    // ---- IPv6 ULA fc00::/7 ----

    @Test fun ipv6UlaRange() {
        assertTrue(PrivateAddress.isPrivateAddressHost("fc00::"))
        assertTrue(PrivateAddress.isPrivateAddressHost("fd00:1234:5678::1"))
        assertTrue(PrivateAddress.isPrivateAddressHost("fdff::1"))
        // fc00 前一位（fbff）不在 ULA 段
        assertFalse(PrivateAddress.isPrivateAddressHost("fbff::1"))
        // fe00 起属其它区段
        assertFalse(PrivateAddress.isPrivateAddressHost("fe00::"))
    }

    // ---- IPv6 链路本地 fe80::/10 ----

    @Test fun ipv6LinkLocalRange() {
        assertTrue(PrivateAddress.isPrivateAddressHost("fe80::1"))
        assertTrue(PrivateAddress.isPrivateAddressHost("febf::1"))
        // /10 的上边界是 febf（fe80+0x3ff 的低 10 位全 1）
        assertTrue(PrivateAddress.isPrivateAddressHost("febf:ffff::1"))
        // fea0 系列也在 /10 内
        assertTrue(PrivateAddress.isPrivateAddressHost("fea0::1"))
        // fec0 起不在 /10 内
        assertFalse(PrivateAddress.isPrivateAddressHost("fec0::1"))
    }

    // ---- IPv4 前导零拒绝 ----

    @Test fun leadingZeroOctetsRejected() {
        // `010.0.0.1` 与八进制/ legacy 解析口径有歧义：非法而非 10.x
        assertFalse(PrivateAddress.isPrivateAddressHost("010.0.0.1"))
        assertFalse(PrivateAddress.isPrivateAddressHost("10.0.0.010"))
        assertFalse(PrivateAddress.isPrivateAddressHost("010.0.0.1:8080"))
        // 单个「0」段合法
        assertTrue(PrivateAddress.isPrivateAddressHost("10.0.0.0"))
        // IPv4 穿嵌里的前导零同样拒绝
        assertFalse(PrivateAddress.isPrivateAddressHost("::ffff:010.0.0.1"))
    }

    // ---- IPv4-mapped IPv6（::ffff:a.b.c.d）----

    @Test fun ipv4MappedPrivateIsPrivate() {
        assertTrue(PrivateAddress.isPrivateAddressHost("::ffff:192.168.1.1"))
        assertTrue(PrivateAddress.isPrivateAddressHost("::ffff:10.1.2.3"))
        assertTrue(PrivateAddress.isPrivateAddressHost("::ffff:172.16.0.1"))
        // 全写下等价写法
        assertTrue(PrivateAddress.isPrivateAddressHost("::FFFF:192.168.1.5"))
        assertTrue(PrivateAddress.isPrivateAddressHost("0:0:0:0:0:ffff:c0a8:101"))
        // 带端口与方括号的 host 形态同样剥离后判定
        assertTrue(PrivateAddress.isPrivateAddressHost("[::ffff:192.168.1.1]:3080"))
    }

    @Test fun ipv4MappedPublicExcluded() {
        assertFalse(PrivateAddress.isPrivateAddressHost("::ffff:8.8.8.8"))
        assertFalse(PrivateAddress.isPrivateAddressHost("::ffff:172.32.0.1"))
        // mapped 回环同回环口径排除
        assertFalse(PrivateAddress.isPrivateAddressHost("::ffff:127.0.0.1"))
        // 非 mapped 的低 32 位 IPv4 形态（mapped 前缀不匹配）不支持
        assertFalse(PrivateAddress.isPrivateAddressHost("::1.2.3.4"))
    }

    // ---- IPv6 公网 ----

    @Test fun ipv6PublicExcluded() {
        assertFalse(PrivateAddress.isPrivateAddressHost("2001:db8::1"))
        assertFalse(PrivateAddress.isPrivateAddressHost("2600::1"))
        assertFalse(PrivateAddress.isPrivateAddressHost("::ffff:8.8.8.8"))
    }

    // ---- 主机名与非法串 ----

    @Test fun hostnamesAlwaysFalse() {
        assertFalse(PrivateAddress.isPrivateAddressHost("example.com"))
        assertFalse(PrivateAddress.isPrivateAddressHost("dsh.local"))
        assertFalse(PrivateAddress.isPrivateAddressHost("localhost"))
        assertFalse(PrivateAddress.isPrivateAddressHost("n11-pc.lan:3080"))
    }

    @Test fun invalidInputsFalse() {
        assertFalse(PrivateAddress.isPrivateAddressHost(""))
        assertFalse(PrivateAddress.isPrivateAddressHost("   "))
        assertFalse(PrivateAddress.isPrivateAddressHost("10.0.0"))
        assertFalse(PrivateAddress.isPrivateAddressHost("10.0.0.256"))
        assertFalse(PrivateAddress.isPrivateAddressHost("10.0.0.0.1"))
        assertFalse(PrivateAddress.isPrivateAddressHost("abc.def.ghi.jkl"))
        assertFalse(PrivateAddress.isPrivateAddressHost("10.0.0.0/8"))
        assertFalse(PrivateAddress.isPrivateAddressHost(":::"))
        assertFalse(PrivateAddress.isPrivateAddressHost("1:2:3:4:5:6:7:8:9"))
    }

    // ---- host 形态：带端口 / 方括号 ----

    @Test fun hostWithPortStripped() {
        assertTrue(PrivateAddress.isPrivateAddressHost("192.168.1.5:3080"))
        assertTrue(PrivateAddress.isPrivateAddressHost("10.1.2.3:443"))
        assertFalse(PrivateAddress.isPrivateAddressHost("127.0.0.1:8081"))
        assertFalse(PrivateAddress.isPrivateAddressHost("8.8.8.8:53"))
    }

    @Test fun bracketedIpv6Stripped() {
        assertTrue(PrivateAddress.isPrivateAddressHost("[fd00::1]"))
        assertTrue(PrivateAddress.isPrivateAddressHost("[fe80::1]:3080"))
        assertFalse(PrivateAddress.isPrivateAddressHost("[2001:db8::1]:443"))
    }

    @Test fun leadingTrailingWhitespaceTolerated() {
        assertTrue(PrivateAddress.isPrivateAddressHost(" 192.168.0.1 "))
        assertFalse(PrivateAddress.isPrivateAddressHost(" 2001:db8::1 "))
    }
}
