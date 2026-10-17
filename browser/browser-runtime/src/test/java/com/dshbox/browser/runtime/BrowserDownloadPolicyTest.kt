package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDownloadPolicyTest {
    @Test
    fun acceptsOnlyCredentialFreeHttpAndHttpsUrls() {
        assertEquals("https", BrowserDownloadPolicy.parseHttpUrl("https://example.test/file.pdf")?.scheme)
        assertEquals("http", BrowserDownloadPolicy.parseHttpUrl("http://127.0.0.1:8080/report")?.scheme)
        assertNull(BrowserDownloadPolicy.parseHttpUrl("file:///etc/passwd"))
        assertNull(BrowserDownloadPolicy.parseHttpUrl("blob:https://example.test/id"))
        assertNull(BrowserDownloadPolicy.parseHttpUrl("https://user:password@example.test/file"))
        assertNull(BrowserDownloadPolicy.parseHttpUrl("https://example.test/\nheader"))
        assertNull(BrowserDownloadPolicy.parseHttpUrl("https://example.test/" + "x".repeat(BrowserDownloadPolicy.MAX_URL_LENGTH)))
    }

    @Test
    fun redirectCredentialPolicyUsesOriginAndNeverAllowsHttpsDowngrade() {
        val credentials = BrowserDownloadPolicy.Credentials("sid=secret", "Bearer secret")
        val source = BrowserDownloadPolicy.parseHttpUrl("https://example.test:443/a")!!
        val sameOrigin = BrowserDownloadPolicy.parseHttpUrl("https://EXAMPLE.test/b")!!
        val otherPort = BrowserDownloadPolicy.parseHttpUrl("https://example.test:444/b")!!
        val otherHost = BrowserDownloadPolicy.parseHttpUrl("https://other.test/b")!!
        val downgrade = BrowserDownloadPolicy.parseHttpUrl("http://example.test/b")!!

        assertEquals(credentials, BrowserDownloadPolicy.credentialsForRedirect(source, sameOrigin, credentials))
        assertEquals(BrowserDownloadPolicy.Credentials(null, null), BrowserDownloadPolicy.credentialsForRedirect(source, otherPort, credentials))
        assertEquals(BrowserDownloadPolicy.Credentials(null, null), BrowserDownloadPolicy.credentialsForRedirect(source, otherHost, credentials))
        assertNull(BrowserDownloadPolicy.credentialsForRedirect(source, downgrade, credentials))
        assertTrue(BrowserDownloadPolicy.isHttpsDowngrade(source, downgrade))
    }

    @Test
    fun redirectCredentialsUseEffectivePortInsteadOfEquivalentDefaultPortText() {
        val credentials = BrowserDownloadPolicy.Credentials("sid=secret", null)
        val source = BrowserDownloadPolicy.parseHttpUrl("http://example.test:80/a")!!
        val sameOrigin = BrowserDownloadPolicy.parseHttpUrl("http://EXAMPLE.test/b")!!
        val otherPort = BrowserDownloadPolicy.parseHttpUrl("http://example.test:81/b")!!

        assertEquals(credentials, BrowserDownloadPolicy.credentialsForRedirect(source, sameOrigin, credentials))
        assertEquals(
            BrowserDownloadPolicy.Credentials(null, null),
            BrowserDownloadPolicy.credentialsForRedirect(source, otherPort, credentials),
        )
    }

    @Test
    fun filenamesAreBasenamesAndCannotSelectPathsOrDeviceFiles() {
        val url = BrowserDownloadPolicy.parseHttpUrl("https://example.test/reports/annual%20report.pdf")!!
        assertEquals("annual_report.pdf", BrowserDownloadPolicy.safeFileName(null, url))
        assertEquals("report.pdf", BrowserDownloadPolicy.safeFileName("../../report.pdf", url))
        assertEquals("_CON.txt", BrowserDownloadPolicy.safeFileName("CON.txt", url))
        assertEquals("download", BrowserDownloadPolicy.safeFileName("...", url))
        assertFalse(BrowserDownloadPolicy.safeFileName("folder\\private.txt", url).contains('\\'))
        assertTrue(BrowserDownloadPolicy.safeFileName("a".repeat(300), url).length <= 180)
    }

    @Test
    fun quotaReservationBoundsEntryCountAndTotalBytesWithoutDeletingExistingFiles() {
        assertTrue(BrowserDownloadPolicy.canReserve(10, 100, 1, 100, 100))
        assertFalse(BrowserDownloadPolicy.canReserve(BrowserDownloadPolicy.MAX_PERSISTENT_ENTRIES, 0, 0, 0, 1))
        assertFalse(BrowserDownloadPolicy.canReserve(10, BrowserDownloadPolicy.MAX_TOTAL_BYTES - 1, 0, 0, 2))
        assertFalse(BrowserDownloadPolicy.canReserve(0, 0, 0, 0, BrowserDownloadPolicy.MAX_FILE_BYTES + 1))
        assertFalse(BrowserDownloadPolicy.canReserve(0, -1, 0, 0, 1))
    }

    @Test
    fun transferDeadlineIsAbsoluteAndUsesMonotonicBoundary() {
        val deadline = 300_000L
        assertFalse(BrowserDownloadPolicy.transferDeadlineExpired(deadline, 299_999L))
        assertTrue(BrowserDownloadPolicy.transferDeadlineExpired(deadline, deadline))
        assertTrue(BrowserDownloadPolicy.transferDeadlineExpired(deadline, 300_001L))
    }
}
