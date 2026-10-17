package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BrowserUploadInspectDiagnosticsTest {
    @Test
    fun invalidRelativePathReportsOnlyFixedStageAndCallbackFailureCannotEscape() = withFixture { userData ->
        val stages = mutableListOf<BrowserUploadInspectStage>()
        assertNull(BrowserUploadPolicy.inspect(userData, "../private-never-log.txt") {
            stages += it
            throw IllegalStateException("callback-private-never-log")
        })
        assertEquals(listOf(BrowserUploadInspectStage.RELATIVE_VALIDATION), stages)
        assertFalse(stages.joinToString(",") { it.logTag }.contains("private-never-log", ignoreCase = true))
    }

    private inline fun withFixture(block: (File) -> Unit) {
        val filesRoot = Files.createTempDirectory("browser-upload-inspect-").toFile()
        val userData = File(filesRoot, "user-data").apply { mkdirs() }
        try {
            block(userData)
        } finally {
            filesRoot.deleteRecursively()
        }
    }
}
