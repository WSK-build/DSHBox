package com.dshbox.app.dsh

import com.dshbox.app.common.LogRedactor
import java.io.File
import java.io.RandomAccessFile
import java.net.URI

internal data class DshLogWindow(
    val sourceName: String,
    val capturedAtMillis: Long,
    val lines: List<String>,
    val truncated: Boolean,
    val errorType: String?,
) {
    fun exportText(): String = buildString {
        append("===== ").append(sourceName).append(" =====\n")
        append("snapshotEpochMillis=").append(capturedAtMillis)
            .append("; tailLines=").append(lines.size)
            .append("; truncated=").append(truncated).append('\n')
        errorType?.let { append("readError=").append(it).append('\n') }
        append(lines.joinToString("\n")).append('\n')
    }
}

/** Bounded snapshots of existing process logs; never reads configuration or conversation stores. */
internal object DshDiagnosticLogs {
    fun exportReport(report: DshRendererReport): String = buildString {
        append("===== DSH renderer observations (not business readiness) =====\n")
        append("engine=").append(report.engine).append('\n')
        append("engineBuild=").append(report.engineBuild ?: "unknown").append('\n')
        append("phase=").append(report.phase).append('\n')
        append("reportUpdatedEpochMillis=").append(report.updatedAtMillis ?: "unknown").append('\n')
        append("systemWebViewProvider=").append(redact(report.provider?.packageName.orEmpty())).append('\n')
        append("systemWebViewProviderVersion=").append(redact(report.provider?.versionName.orEmpty())).append('\n')
        append("userAgent=").append(redact(report.userAgent.orEmpty())).append('\n')
        for (capability in com.dshbox.dsh.renderer.api.DshWebCapability.entries) {
            append(capability).append(": native=")
                .append(report.nativeCapabilities?.get(capability) ?: "uncollected")
                .append("; effective=").append(report.effectiveCapabilities[capability] ?: "uncollected")
                .append('\n')
        }
        append("===== Worker-only observations =====\n")
        val worker = report.workerCapabilities
        if (worker == null) {
            append("workerProbe=uncollected\n")
        } else {
            for (capability in com.dshbox.dsh.renderer.api.DshWebCapability.entries) {
                worker[capability]?.let {
                    append(capability).append(": worker=").append(it).append('\n')
                }
            }
            for ((name, value) in report.workerDetails.toSortedMap()) {
                append(name).append(": worker=").append(value).append('\n')
            }
        }
    }

    fun readTail(file: File, lineLimit: Int = 150, byteLimit: Int = 256 * 1024): DshLogWindow {
        require(lineLimit > 0 && byteLimit > 0)
        val capturedAt = System.currentTimeMillis()
        return try {
            RandomAccessFile(file, "r").use { input ->
                val length = input.length()
                val start = (length - byteLimit).coerceAtLeast(0)
                input.seek(start)
                val bytes = ByteArray((length - start).toInt())
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                var text = bytes.decodeToString(endIndex = count)
                if (start > 0) text = text.substringAfter('\n', "")
                val allLines = text.lineSequence().toMutableList()
                if (allLines.lastOrNull() == "") allLines.removeAt(allLines.lastIndex)
                val retained = allLines.takeLast(lineLimit)
                DshLogWindow(file.name, capturedAt, retained.map(::redact),
                    start > 0 || allLines.size > retained.size || count < bytes.size, null)
            }
        } catch (failure: Exception) {
            DshLogWindow(file.name, capturedAt, emptyList(), false, failure.javaClass.simpleName)
        }
    }

    fun redact(text: String): String {
        if (PRIVATE_PAYLOAD.containsMatchIn(text)) return "[omitted: conversation/configuration payload]"
        val hidden = COOKIE.replace(LogRedactor.redact(text)) { it.groupValues[1] + "***" }
        val credentials = SECRET.replace(hidden) { it.groupValues[1] + "***" }
        return URL.replace(credentials) { match ->
            runCatching {
                val uri = URI(match.value)
                if (uri.host == null) "<url>" else URI(uri.scheme, null, uri.host, uri.port, "/…", null, null).toString()
            }.getOrDefault("<url>")
        }
    }

    private val COOKIE = Regex("""(?i)(["']?(?:set-cookie|cookie)["']?\s*[:=]\s*)[^\r\n]+""")
    private val SECRET = Regex("""(?i)(["']?(?:api[_-]?key|authorization|password|launch[_-]?token|token|secret)["']?\s*[:=]\s*)(?:Bearer\s+[^\s,;}]+|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s,;}]+)""")
    private val URL = Regex("""https?://[^\s"'<>]+""")
    private val PRIVATE_PAYLOAD = Regex("""(?i)(?:["']?(?:messages|prompt|content|providers|model[_-]?config)["']?\s*[:=]|^\s*(?:user|assistant|tool)\s*:)""")
}
