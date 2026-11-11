package com.dshbox.app.lanaccess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密钥生成/形态与门禁 cookie 签名校验（JVM 纯函数锁口径）：
 * - 生成值 URL-safe（base64url 无 padding）、形态合法、两次生成不同（随机熵）；
 * - cookie 值在同一密钥下稳定、不同密钥下不同；
 * - 常量时间 any-match 校验：等值过、异值不过、空集不过、长度不等不过；
 * - 密钥轮换（「全部断开并重置密钥」）：旧 cookie 的签名在新密钥的
 *   校验里必然不过——轮换即旧门禁会话同步失效，载体为门禁裁决口径。
 */
class LanAccessSecretsTest {

    @Test
    fun generatedKeyIsUrlSafeAndUnique() {
        val first = LanAccessSecrets.generateKey()
        val second = LanAccessSecrets.generateKey()
        assertTrue(first.isNotEmpty())
        // base64url 无 padding：不允许出现 + / = 字符
        assertFalse(first.contains('+'))
        assertFalse(first.contains('/'))
        assertFalse(first.contains('='))
        assertTrue(LanAccessSecrets.isWellFormedKey(first))
        assertTrue(LanAccessSecrets.isWellFormedKey(second))
        assertNotEquals(first, second)
    }

    @Test
    fun malformedKeysAreRejected() {
        assertFalse(LanAccessSecrets.isWellFormedKey(""))
        // 非法 base64url 字符
        assertFalse(LanAccessSecrets.isWellFormedKey("!!!!!!!1!!!!!!!****"))
        // 过短（< 20 字符）
        assertFalse(LanAccessSecrets.isWellFormedKey("abcdefgh"))
    }

    @Test
    fun cookieValueIsStablePerKeyAndDistinctAcrossKeys() {
        val keyA = LanAccessSecrets.generateKey()
        val keyB = LanAccessSecrets.generateKey()
        assertEquals(LanAccessSecrets.cookieValueFor(keyA), LanAccessSecrets.cookieValueFor(keyA))
        assertNotEquals(LanAccessSecrets.cookieValueFor(keyA), LanAccessSecrets.cookieValueFor(keyB))
        // 签名形态仍是 URL-safe
        val cookie = LanAccessSecrets.cookieValueFor(keyA)
        assertFalse(cookie.contains('+'))
        assertFalse(cookie.contains('='))

        assertEquals(
            listOf(LanAccessSecrets.cookieValueFor(keyA)).size,
            1,
        )
        assertTrue(LanAccessSecrets.verifyCookie(keyA, listOf(cookie)))
    }

    @Test
    fun verifyCookieIsAnyMatchAndConstantTimeSafe() {
        val key = LanAccessSecrets.generateKey()
        val good = LanAccessSecrets.cookieValueFor(key)
        val bad = LanAccessSecrets.cookieValueFor(LanAccessSecrets.generateKey())

        assertTrue(LanAccessSecrets.verifyCookie(key, listOf(bad, good)))
        assertTrue(LanAccessSecrets.verifyCookie(key, listOf(good)))
        assertFalse(LanAccessSecrets.verifyCookie(key, listOf(bad)))
        assertFalse(LanAccessSecrets.verifyCookie(key, emptyList()))
        // 长度不等的安全路径（不抛异常、返回 false）
        assertFalse(LanAccessSecrets.verifyCookie(key, listOf("x")))
        // 同名多头 cookie 值串（含分号之外的分隔已由调用方拆开）
        assertFalse(LanAccessSecrets.verifyCookie(key, listOf("$good;other")))
    }

    /** 密钥轮换口径：旧密钥签出的 cookie 在新密钥的门禁里一律不过。 */
    @Test
    fun rotatedKeyRejectsCookieSignedByOldKey() {
        val oldKey = LanAccessSecrets.generateKey()
        val newKey = LanAccessSecrets.generateKey()
        val oldCookie = LanAccessSecrets.cookieValueFor(oldKey)
        val newGate = LanAccessGate(newKey) { 1_000L }
        // 旧 cookie 续访：401（Rejected）
        assertEquals(
            LanAccessGate.Decision.Rejected,
            newGate.decide("192.168.1.7", null, listOf(oldCookie)),
        )
        // 旧 `?k=` 链接（兑换路径）：同样 401
        assertEquals(
            LanAccessGate.Decision.Rejected,
            newGate.decide("192.168.1.7", oldKey, emptyList()),
        )
        // 新密钥兑换（Login → 种新 cookie → 新密钥自己的门禁里 Pass）
        val login = newGate.decide("192.168.1.7", newKey, emptyList())
        assertTrue(login is LanAccessGate.Decision.Login)
        val pass = newGate.decide(
            "192.168.1.7",
            null,
            listOf((login as LanAccessGate.Decision.Login).cookieValue),
        )
        assertEquals(LanAccessGate.Decision.Pass, pass)
    }
}
