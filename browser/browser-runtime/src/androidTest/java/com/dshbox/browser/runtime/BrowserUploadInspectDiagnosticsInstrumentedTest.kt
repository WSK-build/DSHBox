package com.dshbox.browser.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.SecureDirectoryStream
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BrowserUploadInspectDiagnosticsInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun missingParentAndMissingSourceHaveDistinctFixedStages() = withFixture { filesRoot, userData ->
        requireSecureDirectoryStreams(filesRoot, userData)
        val parentStages = mutableListOf<BrowserUploadInspectStage>()
        assertNull(BrowserUploadPolicy.inspect(userData, "absent-parent/report.txt", parentStages::add))
        assertEquals(listOf(BrowserUploadInspectStage.PARENT_WALK), parentStages)

        val qa = File(userData, "qa").apply { mkdir() }
        requireSecureDirectoryStreams(qa)
        val sourceStages = mutableListOf<BrowserUploadInspectStage>()
        assertNull(BrowserUploadPolicy.inspect(userData, "qa/private-never-log.txt", sourceStages::add))
        assertEquals(listOf(BrowserUploadInspectStage.SOURCE_ATTRIBUTES), sourceStages)
        assertNoSensitiveText("absent-parent/private-never-log.txt", parentStages + sourceStages)
    }

    @Test
    fun successfulInspectionDoesNotReportFailureAndCallbackFailureCannotEscape() = withFixture { filesRoot, userData ->
        requireSecureDirectoryStreams(filesRoot, userData)
        val qa = File(userData, "qa").apply { mkdir() }
        requireSecureDirectoryStreams(qa)
        File(qa, "qa-report.txt").writeText("browser upload test", Charsets.UTF_8)

        val stages = mutableListOf<BrowserUploadInspectStage>()
        val inspected = BrowserUploadPolicy.inspect(userData, "qa/qa-report.txt", stages::add)
        assertNotNull(inspected)
        assertTrue(stages.isEmpty())
        assertEquals(19L, inspected!!.size)
        assertEquals(expectedHash("browser upload test"), inspected.sha256)

        val callbackStages = mutableListOf<BrowserUploadInspectStage>()
        assertNull(BrowserUploadPolicy.inspect(userData, "qa/missing.txt") {
            callbackStages += it
            throw IllegalStateException("callback-private-never-log")
        })
        assertEquals(listOf(BrowserUploadInspectStage.SOURCE_ATTRIBUTES), callbackStages)
        assertNoSensitiveText("callback-private-never-log", callbackStages)
    }

    @Test
    fun canonicalRootSucceedsWhileAliasRootIsRejected() = withFixture { filesRoot, userData ->
        val qa = File(userData, "qa").apply { mkdir() }
        requireSecureDirectoryStreams(filesRoot, userData, qa)
        File(qa, "qa-report.txt").writeText("browser upload test", Charsets.UTF_8)
        val relativePath = "qa/qa-report.txt"

        assertNotNull(BrowserUploadPolicy.inspect(userData, relativePath))

        val aliasRoot = File(filesRoot.parentFile, "browser-upload-alias-${UUID.randomUUID()}")
        try {
            val aliasCreated = runCatching {
                Files.createSymbolicLink(aliasRoot.toPath(), filesRoot.toPath())
                true
            }.getOrDefault(false)
            assertTrue("stage=alias_fixture_created", aliasCreated)

            val aliasStages = mutableListOf<BrowserUploadInspectStage>()
            assertNull(BrowserUploadPolicy.inspect(File(aliasRoot, "user-data"), relativePath, aliasStages::add))
            assertEquals(listOf(BrowserUploadInspectStage.ROOT_OPEN), aliasStages)
            assertNoSensitiveText("browser-upload-alias", aliasStages)
        } finally {
            runCatching { Files.deleteIfExists(aliasRoot.toPath()) }
        }
    }

    private fun requireSecureDirectoryStreams(vararg directories: File) {
        directories.forEach { directory ->
            Files.newDirectoryStream(directory.toPath()).use { stream ->
                assertTrue("stage=test_fixture_sds", stream is SecureDirectoryStream<*>)
            }
        }
    }

    private fun assertNoSensitiveText(sensitive: String, stages: List<BrowserUploadInspectStage>) {
        val diagnostic = stages.joinToString(",") { it.logTag }
        assertFalse("stage=diagnostic_redaction", diagnostic.contains(sensitive, ignoreCase = true))
        assertTrue("stage=diagnostic_allowlist", stages.all { it in BrowserUploadInspectStage.values() })
    }

    private fun expectedHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private inline fun withFixture(block: (File, File) -> Unit) {
        val filesRoot = File(context.filesDir.canonicalFile, "browser-upload-inspect-${UUID.randomUUID()}")
        val userData = File(filesRoot, "user-data")
        try {
            assertTrue("stage=fixture_created", userData.mkdirs())
            block(filesRoot, userData)
        } finally {
            filesRoot.deleteRecursively()
        }
    }
}
