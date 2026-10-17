package com.dshbox.browser.runtime

import java.io.File
import java.util.Locale

internal data class BrowserUploadSource(
    val id: String,
    val relativePath: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val file: File,
    val modifiedAtMillis: Long,
    val sha256: String,
    val fileIdentity: String = "",
)

internal enum class BrowserUploadInspectStage(val logTag: String) {
    RELATIVE_VALIDATION("relative_validation"),
    ROOT_OPEN("root_open"),
    PARENT_WALK("parent_walk"),
    SOURCE_ATTRIBUTES("source_attributes"),
    SOURCE_IDENTITY("source_identity"),
    SOURCE_OPEN("source_open"),
    SOURCE_SNAPSHOT("source_snapshot"),
    SOURCE_HASH("source_hash"),
    FINAL_SNAPSHOT("final_snapshot"),
}

internal object BrowserUploadPolicy {
    const val MAX_FILES = 10
    const val MAX_FILE_BYTES = 16L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
    const val GRANT_TTL_MILLIS = 2 * 60 * 1000L
    const val URI_TTL_MILLIS = 5 * 60 * 1000L
    const val ARTIFACT_TTL_MILLIS = 10 * 60 * 1000L

    private val deniedNames = setOf(
        ".dsh", ".git", ".ssh", ".config", ".android", ".gradle", ".npm", ".aws", ".kube",
        ".npmrc", ".pypirc", ".netrc", "user-data", "node_modules", "credentials", "credential",
        "secrets", "secret", "id_rsa", "id_ed25519", "id_ecdsa", "authorized_keys",
    )
    private val deniedExtensions = setOf("pem", "key", "p12", "pfx", "jks", "keystore", "mobileprovision", "asc")
    private val mimeByExtension = mapOf(
        "txt" to "text/plain", "csv" to "text/csv", "tsv" to "text/tab-separated-values", "json" to "application/json",
        "xml" to "application/xml", "html" to "text/html", "htm" to "text/html", "css" to "text/css", "js" to "text/javascript",
        "md" to "text/markdown", "pdf" to "application/pdf", "zip" to "application/zip", "gz" to "application/gzip",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
        "svg" to "image/svg+xml", "mp3" to "audio/mpeg", "mp4" to "video/mp4", "wav" to "audio/wav",
        "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )

    fun isSafeName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower !in deniedNames && !Regex("^\\.env(?:\\.|$)").containsMatchIn(lower) &&
            !Regex("(?:^|[._-])(?:credentials?|secrets?|tokens?)(?:[._-]|$)").containsMatchIn(lower) &&
            lower.substringAfterLast('.', "") !in deniedExtensions &&
            !Regex("^(?:keystore|credentials|secrets)(?:\\.|$)").containsMatchIn(lower)
    }

    fun validateRelativePath(value: String): List<String>? {
        if (value.isBlank() || value.length > 1024 || value.any { it == '\u0000' || it == '\\' || it.isISOControl() }) return null
        if (value.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(value)) return null
        val parts = value.split('/')
        if (parts.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." || !isSafeName(it) }) return null
        return parts
    }

    fun inspect(workspace: File, relativePath: String, onFailure: (BrowserUploadInspectStage) -> Unit = {}): BrowserUploadSource? {
        if (workspace.name != "user-data") {
            runCatching { onFailure(BrowserUploadInspectStage.ROOT_OPEN) }
            return null
        }
        val filesRoot = workspace.parentFile ?: run {
            runCatching { onFailure(BrowserUploadInspectStage.ROOT_OPEN) }
            return null
        }
        return BrowserUploadFileAccess.inspectProjectFile(filesRoot, relativePath, onFailure)
    }

    fun mimeTypeForExtension(extension: String): String = mimeByExtension[extension.lowercase(Locale.ROOT)] ?: "application/octet-stream"

    fun accepts(mime: String, name: String, acceptTypes: List<String>): Boolean = acceptTypes.isEmpty() || acceptTypes.any { raw ->
        val type = raw.trim().lowercase(Locale.ROOT)
        type == "*/*" || type == mime.lowercase(Locale.ROOT) ||
            type.endsWith("/*") && mime.substringBefore('/').equals(type.substringBefore('/'), true) ||
            type.startsWith(".") && name.lowercase(Locale.ROOT).endsWith(type)
    }
}
