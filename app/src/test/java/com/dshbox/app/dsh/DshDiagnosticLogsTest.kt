package com.dshbox.app.dsh

import com.dshbox.dsh.renderer.api.DshWebCapability
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DshDiagnosticLogsTest {
    @Test fun exportedReportIncludesWorkerOnlyObservations() {
        val report = DshRendererReport(
            engine = DshRendererEngine.SYSTEM_WEBVIEW,
            engineBuild = null,
            phase = DshRendererPhase.PAGE_STOPPED,
            provider = DshSystemWebViewProvider("provider", "version"),
            userAgent = null,
            nativeCapabilities = mapOf(DshWebCapability.WORKER to true),
            effectiveCapabilities = mapOf(DshWebCapability.WORKER to true),
            capabilitiesObserved = true,
            workerCapabilities = mapOf(
                DshWebCapability.WORKER_CLASSIC to true,
                DshWebCapability.PDF_WORKER_API to false,
            ),
            workerDetails = mapOf("MAP_GET_OR_INSERT" to false),
        )

        val text = DshDiagnosticLogs.exportReport(report)

        assertTrue(text.contains("WORKER_CLASSIC: worker=true"))
        assertTrue(text.contains("PDF_WORKER_API: worker=false"))
        assertTrue(text.contains("MAP_GET_OR_INSERT: worker=false"))
    }

    @Test fun tailIsBoundedAndMarksTruncation() {
        val file = Files.createTempFile("dsh-log", ".txt").toFile()
        try {
            file.writeText((1..100).joinToString("\n") { "line-$it" } + "\n")
            val result = DshDiagnosticLogs.readTail(file, lineLimit = 3, byteLimit = 100)
            assertEquals(listOf("line-98", "line-99", "line-100"), result.lines)
            assertTrue(result.truncated)
            assertNull(result.errorType)
            assertTrue(result.exportText().contains("truncated=true"))
            assertFalse(result.exportText().contains(requireNotNull(file.parent)))
        } finally { file.delete() }
    }

    @Test fun snapshotsRefreshWhenTheFileChanges() {
        val file = Files.createTempFile("dsh-log", ".txt").toFile()
        try {
            file.writeText("first\n")
            assertEquals(listOf("first"), DshDiagnosticLogs.readTail(file).lines)
            file.appendText("second\n")
            assertEquals(listOf("first", "second"), DshDiagnosticLogs.readTail(file).lines)
        } finally { file.delete() }
    }

    @Test fun credentialsAndUrlDetailsAreNotExported() {
        val raw = """{"api_key":"private-key","authorization":"Bearer private-auth","token":"private-token"} http://user:pass@localhost:3080/private-file?token=private-query#private-fragment"""
        val safe = DshDiagnosticLogs.redact(raw)
        for (secret in listOf("private-key", "private-auth", "private-token", "private-query", "private-fragment", "private-file", "user:pass")) {
            assertFalse("Leaked $secret", safe.contains(secret))
        }
        assertTrue(safe.contains("localhost:3080"))
        assertFalse(DshDiagnosticLogs.redact("cookie: first=one; second=two").contains("two"))
        assertFalse(DshDiagnosticLogs.redact("""{"content":"private-chat"}""").contains("private-chat"))
        assertFalse(DshDiagnosticLogs.redact("user: private-message").contains("private-message"))
    }

    @Test fun missingFileReportsOnlyErrorClass() {
        val directory = Files.createTempDirectory("dsh-log").toFile()
        try {
            val result = DshDiagnosticLogs.readTail(java.io.File(directory, "missing.log"))
            assertNotNull(result.errorType)
            assertTrue(result.lines.isEmpty())
            assertFalse(result.exportText().contains(directory.absolutePath))
        } finally { directory.delete() }
    }
}
