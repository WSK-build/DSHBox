package com.dshbox.dsh.renderer.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DshOriginPolicyTest {
    private val policy = DshOriginPolicy.create("http://127.0.0.1:3080")

    @Test fun sameOriginWithPathAndQueryIsTrusted() {
        assertTrue(policy.isTrusted("http://127.0.0.1:3080/dsh/workspace?token=x"))
    }

    @Test fun fragmentDoesNotAffectOrigin() {
        assertTrue(policy.isTrusted("http://127.0.0.1:3080/page#section"))
    }

    @Test fun differentPortIsRejected() {
        assertFalse(policy.isTrusted("http://127.0.0.1:3081/"))
    }

    @Test fun differentSchemeIsRejected() {
        assertFalse(policy.isTrusted("https://127.0.0.1:3080/"))
    }

    @Test fun differentLoopbackHostIsNotSameOrigin() {
        assertFalse(policy.isTrusted("http://localhost:3080/"))
    }

    @Test fun userInfoIsRejected() {
        assertFalse(policy.isTrusted("http://user@127.0.0.1:3080/"))
    }

    @Test fun maliciousSuffixHostIsRejected() {
        assertFalse(policy.isTrusted("http://127.0.0.1.example.com:3080/"))
        assertFalse(policy.isTrusted("http://127.0.0.1.com:3080/"))
    }

    @Test fun embeddedCredentialsSuffixIsRejected() {
        assertFalse(policy.isTrusted("http://127.0.0.1@evil.example:3080/"))
    }

    @Test fun ipv6LoopbackWithBracketsIsTrustedOnlyForMatchingBase() {
        val ipv6 = DshOriginPolicy.create("http://[::1]:3080")
        assertTrue(ipv6.isTrusted("http://[::1]:3080/path"))
        assertFalse(ipv6.isTrusted("http://127.0.0.1:3080/"))
        assertFalse(ipv6.isTrusted("http://localhost:3080/"))
    }

    @Test fun defaultPortNormalization() {
        val http80 = DshOriginPolicy.create("http://127.0.0.1")
        assertTrue(http80.isTrusted("http://127.0.0.1:80/"))
        assertFalse(http80.isTrusted("http://127.0.0.1:3080/"))
        val https443 = DshOriginPolicy.create("https://localhost")
        assertTrue(https443.isTrusted("https://localhost:443/"))
    }

    @Test fun nonLoopbackHostIsRejected() {
        assertFalse(policy.isTrusted("http://192.168.1.1:3080/"))
        assertFalse(policy.isTrusted("http://10.0.0.1:3080/"))
        assertFalse(policy.isTrusted("http://example.com:3080/"))
    }

    @Test fun opaqueUriIsRejected() {
        assertFalse(policy.isTrusted("mailto:127.0.0.1:3080"))
    }

    @Test fun relativeUriIsRejected() {
        assertFalse(policy.isTrusted("/dsh/page"))
        assertFalse(policy.isTrusted("page?x=1"))
    }

    @Test fun missingHostIsRejected() {
        assertFalse(policy.isTrusted("http://:3080/"))
    }

    @Test fun unparseableIsRejected() {
        assertFalse(policy.isTrusted("http://[invalid:3080/"))
    }

    @Test fun baseWithInvalidSchemeThrows() {
        assertThrows(IllegalArgumentException::class.java) { DshOriginPolicy.create("ftp://127.0.0.1:3080") }
    }

    @Test fun baseWithNonLoopbackThrows() {
        assertThrows(IllegalArgumentException::class.java) { DshOriginPolicy.create("http://8.8.8.8:3080") }
    }

    @Test fun baseWithUserInfoThrows() {
        assertThrows(IllegalArgumentException::class.java) { DshOriginPolicy.create("http://u@127.0.0.1:3080") }
    }

    @Test fun baseWithOpaqueSchemeThrows() {
        assertThrows(IllegalArgumentException::class.java) { DshOriginPolicy.create("mailto:127.0.0.1") }
    }

    @Test fun baseUnparseableThrows() {
        assertThrows(IllegalArgumentException::class.java) { DshOriginPolicy.create("not a uri \u0000") }
    }

    @Test fun baseMissingHostThrows() {
        assertThrows(IllegalArgumentException::class.java) { DshOriginPolicy.create("http://:3080") }
    }

    @Test fun upperCaseSchemeAndHostIsSameOrigin() {
        val base = DshOriginPolicy.create("http://localhost:3080")
        assertTrue(base.isTrusted("HTTP://LOCALHOST:3080/"))
        assertTrue(base.isTrusted("http://localhost:3080/"))
        val upperBase = DshOriginPolicy.create("HTTP://LOCALHOST:3080")
        assertTrue(upperBase.isTrusted("http://localhost:3080/"))
    }

    @Test fun candidatePort65536IsRejected() {
        assertFalse(policy.isTrusted("http://127.0.0.1:65536/"))
    }

    @Test fun basePort65536Throws() {
        assertThrows(IllegalArgumentException::class.java) { DshOriginPolicy.create("http://127.0.0.1:65536") }
    }

    @Test fun candidateNegativePortIsRejected() {
        assertFalse(policy.isTrusted("http://127.0.0.1:-2/"))
        assertFalse(policy.isTrusted("http://127.0.0.1:-1/"))
    }

    @Test fun candidateEmptyPortNormalizesToDefaultAndDiffersFromBase() {
        // Empty port parses to undefined (-1) which java.net.URI treats as the scheme default
        // (http -> 80), not as an error. Against base 3080 that default 80 is not equal, so reject.
        assertFalse(policy.isTrusted("http://127.0.0.1:/"))
        val http80 = DshOriginPolicy.create("http://127.0.0.1")
        assertTrue(http80.isTrusted("http://127.0.0.1:/"))
    }

    @Test fun percentEncodedHostIsRejected() {
        assertFalse(policy.isTrusted("http://%31%32%37%2e%30%2e%30%2e%31:3080/"))
    }

    @Test fun networkPathReferenceIsNotTrusted() {
        // No scheme present -> java.net.URI.isAbsolute() is false -> rejected.
        assertFalse(policy.isTrusted("//localhost:3080/"))
    }

    @Test fun nonDefaultPortIsNotEqual() {
        assertFalse(policy.isTrusted("http://127.0.0.1:80/"))
    }
}
