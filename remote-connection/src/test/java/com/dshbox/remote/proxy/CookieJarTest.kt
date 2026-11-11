package com.dshbox.remote.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class CookieJarTest {

    private val jar = CookieJar()
    private val origin = "http://192.168.1.5:3081"

    @Test fun storeAndQueryByOrigin() {
        val dual = jar.storeAll(origin, listOf("sid=abc; Path=/; HttpOnly"))
        assertTrue(dual.isEmpty()) // HttpOnly 只进罐不透传
        val cookies = jar.cookiesFor(origin)
        assertEquals(1, cookies.size)
        assertEquals("sid", cookies[0].name)
        assertEquals("abc", cookies[0].value)
        assertTrue(cookies[0].httpOnly)
    }

    @Test fun cookieReplacesSameKeyAndKeepsLatestValue() {
        jar.storeAll(origin, listOf("sid=old; Path=/"))
        jar.storeAll(origin, listOf("sid=new; Path=/"))
        val cookies = jar.cookiesFor(origin)
        assertEquals(1, cookies.size)
        assertEquals("new", cookies[0].value)
    }

    @Test fun expiredCookieIsPurged() {
        val past = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(1_000), java.time.ZoneOffset.UTC),
        )
        jar.storeAll(origin, listOf("sid=old; Path=/; Expires=$past"))
        assertTrue(jar.cookiesFor(origin).isEmpty())
        // 存入后再设过期也清除
        jar.storeAll(origin, listOf("sid=live; Path=/; Max-Age=3600"))
        jar.storeAll(origin, listOf("sid=live; Path=/; Expires=$past"))
        assertTrue(jar.cookiesFor(origin).isEmpty())
    }

    @Test fun maxAgeZeroDeletes() {
        jar.storeAll(origin, listOf("sid=live; Path=/"))
        jar.storeAll(origin, listOf("sid=deleted; Path=/; Max-Age=0"))
        assertTrue(jar.cookiesFor(origin).isEmpty())
    }

    @Test fun domainScopeMatchingWithCrossSubdomain() {
        jar.storeAll("http://app.example.com:8080", listOf("sid=1; Path=/; Domain=example.com"))
        assertEquals(1, jar.cookiesFor("http://app.example.com:8080").size)
        assertEquals(1, jar.cookiesFor("http://example.com:8080").size)
        assertEquals(1, jar.cookiesFor("http://api.example.com:8080").size)
        // 不覆盖兄弟域与更深层前缀伪造
        assertEquals(0, jar.cookiesFor("http://notexample.com:8080").size)
        assertEquals(0, jar.cookiesFor("http://app.api.example.com.evil.com:8080").size)
    }

    @Test fun cookieWithoutDomainIsHostOnly() {
        jar.storeAll("http://app.example.com:8080", listOf("sid=1; Path=/"))
        assertEquals(1, jar.cookiesFor("http://app.example.com:8080").size)
        assertEquals(0, jar.cookiesFor("http://api.example.com:8080").size)
        assertEquals(0, jar.cookiesFor("http://example.com:8080").size)
    }

    @Test fun domainAttributeOnIpHostIsTreatedHostOnly() {
        jar.storeAll(origin, listOf("sid=1; Path=/; Domain=192.168.1.5"))
        assertEquals(1, jar.cookiesFor(origin).size)
        assertEquals(0, jar.cookiesFor("http://192.168.1.6:3081").size)
    }

    @Test fun domainAttributeMismatchRejectsCookie() {
        jar.storeAll("http://app.example.com:8080", listOf("sid=1; Path=/; Domain=other.com"))
        assertTrue(jar.cookiesFor("http://app.example.com:8080").isEmpty())
    }

    @Test fun pathMatching() {
        jar.storeAll("http://host:9000", listOf("a=1; Path=/api", "b=2; Path=/", "c=3; Path=/api/v1"))
        val root = jar.cookiesFor("http://host:9000/").map { it.name }
        assertEquals(listOf("b"), root)
        val api = jar.cookiesFor("http://host:9000/api").map { it.name }
        assertEquals(setOf("a", "b"), api.toSet())
        // /apiX 不是 /api 的子路径
        val apiSibling = jar.cookiesFor("http://host:9000/apiX").map { it.name }
        assertEquals(setOf("b"), apiSibling.toSet())
        val deep = jar.cookiesFor("http://host:9000/api/v1/users").map { it.name }.toSet()
        assertEquals(setOf("a", "b", "c"), deep)
    }

    @Test fun defaultPathIsRequestDirectory() {
        jar.storeAll("http://host:9000/room/index.html", listOf("a=1"))
        val cookie = jar.cookiesFor("http://host:9000/room/next").single()
        assertEquals("/room", cookie.path)
    }

    @Test fun queryReturnedByLongerPathFirst() {
        jar.storeAll("http://host:9000", listOf("root=r; Path=/", "sub=s; Path=/api"))
        val cookies = jar.cookiesFor("http://host:9000/api")
        assertEquals(listOf("sub", "root"), cookies.map { it.name })
    }

    @Test fun perDeviceIsolationByDistinctInstances() {
        val jarA = CookieJar()
        val jarB = CookieJar()
        jarA.storeAll(origin, listOf("sid=a; Path=/"))
        jarB.storeAll(origin, listOf("sid=b; Path=/"))
        assertEquals("a", jarA.cookiesFor(origin).single().value)
        assertEquals("b", jarB.cookiesFor(origin).single().value)
    }

    @Test fun dualWriteStripsDomainAndSecureButKeepsExpires() {
        val future = "Wed, 01 Jan 2031 00:00:00 GMT"
        val dual = jar.storeAll(
            origin,
            listOf("pref=dark; Path=/cfg; Domain=192.168.1.5; Secure; Expires=$future"),
        )
        assertEquals(1, dual.size)
        val header = dual[0]
        assertTrue(header.startsWith("pref=dark"))
        assertTrue(header.contains("Path=/cfg"))
        assertTrue(header.contains("Expires="))
        assertFalse(header.contains("Domain", ignoreCase = true))
        assertFalse(header.contains("Secure", ignoreCase = true))
    }

    @Test fun sessionCookiesSurviveSerialization() {
        jar.storeAll(origin, listOf("sid=abc; Path=/; HttpOnly", "tmp=gone; Path=/; Max-Age=0"))
        val restored = CookieJar()
        restored.load(jar.serialize())
        val cookies = restored.cookiesFor(origin)
        assertEquals(listOf("sid"), cookies.map { it.name })
        assertEquals("abc", cookies[0].value)
        assertTrue(cookies[0].httpOnly)
        assertNull(cookies[0].expiresAtMillis)
    }

    @Test fun persistentCookiesKeepExpiryThroughSerialization() {
        jar.storeAll(origin, listOf("sid=abc; Path=/; Expires=Wed, 01 Jan 2031 00:00:00 GMT"))
        val restored = CookieJar()
        restored.load(jar.serialize())
        val cookie = restored.cookiesFor(origin).single()
        assertTrue(cookie.expiresAtMillis!! > System.currentTimeMillis())
    }

    @Test fun serializationRoundTripWithSpecialCharacters() {
        jar.storeAll(origin, listOf("weird=a|b\\c=d; Path=/"))
        val restored = CookieJar()
        restored.load(jar.serialize())
        assertEquals("a|b\\c=d", restored.cookiesFor(origin).single().value)
    }

    @Test fun unparseableSetCookieIsIgnored() {
        jar.storeAll(origin, listOf("nonsense", "", "=novalue"))
        assertTrue(jar.cookiesFor(origin).isEmpty())
    }

    @Test fun malformedOriginThrowsExplicitly() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            jar.cookiesFor("not a uri")
        }
    }

    @Test fun changeListenerFiresOnStore() {
        var fired = 0
        jar.onChangeListener = { fired += 1 }
        jar.storeAll(origin, listOf("sid=abc; Path=/; HttpOnly"))
        assertEquals(1, fired)
    }

    @Test fun tldLevelDomainAttributeIsRejectedFromAnyHost() {
        // Public Suffix 防护：不含点的 Domain 声明（TLD/单标签）一律拒绝，
        // 否则域匹配会把任意同后缀主机放进作用域
        jar.storeAll("http://app.example.com:8080", listOf("sid=1; Path=/; Domain=com"))
        jar.storeAll("http://app.example.org:8080", listOf("sid=1; Path=/; Domain=org"))
        jar.storeAll("http://192.168.1.5:3081", listOf("sid=1; Path=/; Domain=cn"))
        jar.storeAll("http://site.cn:8080", listOf("sid=1; Path=/; Domain=.cn"))
        assertTrue(jar.cookiesFor("http://app.example.com:8080").isEmpty())
        assertTrue(jar.cookiesFor("http://app.example.org:8080").isEmpty())
        assertTrue(jar.cookiesFor("http://192.168.1.5:3081").isEmpty())
        assertTrue(jar.cookiesFor("http://site.cn:8080").isEmpty())
    }

    @Test fun dottedDomainAttributeIsAcceptedForCoveringHost() {
        // 正常跨子域声明不受 Public Suffix 防护影响
        jar.storeAll("http://api.example.com:8080", listOf("sid=1; Path=/; Domain=example.com"))
        assertEquals(1, jar.cookiesFor("http://api.example.com:8080").size)
        assertEquals(1, jar.cookiesFor("http://example.com:8080").size)
    }

    @Test fun domainAttributeRejectedWhenHostNotCovered() {
        // 既有回归：Domain 声明与请求主机不构成覆盖关系时拒绝
        jar.storeAll("http://notexample.com:8080", listOf("sid=1; Path=/; Domain=evil.com"))
        assertTrue(jar.cookiesFor("http://notexample.com:8080").isEmpty())
    }

    @Test fun loadDropsReservedGateCookie() {
        // 旧快照可能携带门禁令牌：恢复时过滤保留名，防门禁令牌被注入上游
        jar.storeAll(origin, listOf("sid=abc; Path=/"))
        val gateLine = listOf("dshbox_gate", "leaked-token", "192.168.1.5", "/", "", "1", "1")
            .joinToString("\u001f")
        val snapshot = "dshbox-cookie-jar-v1\n$gateLine\n" +
            jar.serialize().removePrefix("dshbox-cookie-jar-v1\n")
        val restored = CookieJar()
        restored.load(snapshot)
        val cookies = restored.cookiesFor(origin)
        assertEquals(setOf("sid"), cookies.map { it.name }.toSet())
        assertFalse(restored.serialize().contains("leaked-token"))
    }
}
