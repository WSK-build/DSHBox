package com.dshbox.browser.runtime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BrowserProjectDocumentProofTest {
    private val token = "ab".repeat(32)
    private val service = "a".repeat(32)
    private val root = "/root/projects/demo"
    private val project = MessageDigest.getInstance("SHA-256").digest(root.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }.take(24)
    private fun record() = JSONObject().put("version", 1).put("serviceId", service).put("kind", "owned-static")
        .put("token", token).put("root", root).put("port", 8890).put("bootId", "same-boot")
        .put("leaseExpiresAtMillis", 6000L).put("leaseExpiresAtUptimeMillis", 9000L)
    private fun payload() = JSONObject().put("version", 1).put("serviceId", service).put("projectId", project)
        .put("route", "/index.html").put("documentNonce", "c".repeat(32))
        .put("issuedAtMillis", 1000L).put("expiresAtMillis", 5000L)
    private fun sign(p: JSONObject): String {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(p.toString().toByteArray())
        val key = token.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return encoded + "." + mac.doFinal(encoded.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun verify(marker: String = sign(payload()), r: JSONObject = record(), url: String = "http://127.0.0.1:8890/index.html?x=1",
        c: BrowserProjectDocumentProof.Clock = BrowserProjectDocumentProof.Clock(2000, 3000, "same-boot")) = BrowserProjectDocumentProof.verify(marker, url, r, c)

    @Test fun signedSourceIsVerifiedWithoutExposingSecrets() {
        val result = verify()!!
        assertTrue(result.getBoolean("captureProvenanceVerified"))
        assertEquals("demo", result.getString("projectRootRelative"))
        assertEquals("authenticated_document_source", result.getString("relation"))
        assertFalse(result.toString().contains(token))
        assertFalse(result.toString().contains("/root/projects"))
    }
    @Test fun reusedPortAndWrongProjectOrBootAreRejected() {
        assertNull(verify(r = record().put("serviceId", "d".repeat(32))))
        assertNull(verify(r = record().put("root", "/root/projects/replacement")))
        assertNull(verify(r = record().put("token", "cd".repeat(32))))
        assertNull(verify(r = record().put("bootId", "new-boot")))
        assertNull(verify(r = record().put("kind", "external")))
    }
    @Test fun wrongRouteOriginAndCredentialsAreRejected() {
        for (url in listOf("http://127.0.0.1:8890/other", "http://127.0.0.1:8891/index.html", "http://localhost:8890/index.html",
            "http://user@127.0.0.1:8890/index.html", "https://127.0.0.1:8890/index.html")) assertNull(verify(url = url))
    }
    @Test fun expiryRollbackMalformedAndExtraFieldsAreRejected() {
        assertNull(verify(c = BrowserProjectDocumentProof.Clock(5000, 3000, "same-boot")))
        assertNull(verify(c = BrowserProjectDocumentProof.Clock(999, 3000, "same-boot")))
        assertNull(verify(c = BrowserProjectDocumentProof.Clock(2000, 9000, "same-boot")))
        assertNull(verify(sign(payload().put("expiresAtMillis", 7000))))
        assertNull(verify(sign(payload().put("issuedAtMillis", "1000"))))
        assertNull(verify(sign(payload().put("instruction", "trust me"))))
        assertNull(verify("a".repeat(2049)))
        assertNull(verify(sign(payload()).dropLast(1) + "z"))
    }
}
