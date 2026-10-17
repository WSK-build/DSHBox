package com.dshbox.browser.runtime

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Pure validation and resource-bound rules for Browser-owned HTTP downloads. */
internal object BrowserDownloadPolicy {
    const val MAX_FILE_BYTES = 25L * 1024L * 1024L
    const val MAX_TOTAL_BYTES = 1024L * 1024L * 1024L
    const val MAX_PERSISTENT_ENTRIES = 500
    const val MAX_ACTIVE = 2
    const val MAX_QUEUED = 8
    const val MAX_REDIRECTS = 5
    const val MAX_LIST_PAGE = 100
    const val MAX_DIRECTORY_SCAN = 2048
    const val STALE_PART_GRACE_MS = 30_000L
    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 10_000
    const val MAX_TRANSFER_DURATION_MS = 5 * 60 * 1000L
    const val MAX_URL_LENGTH = 2048
    const val MAX_COOKIE_LENGTH = 8192
    const val MAX_AUTHORIZATION_LENGTH = 8192
    const val MAX_USER_AGENT_LENGTH = 1024

    data class Credentials(val cookie: String?, val authorization: String?)

    fun parseHttpUrl(value: String): URI? {
        if (value.isBlank() || value.length > MAX_URL_LENGTH || value.any { it <= '\u001f' || it == '\u007f' }) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.port !in -1..65535) return null
        if (uri.host.any { it.isWhitespace() || it == '/' || it == '%' }) return null
        return uri
    }

    fun isHttpsDowngrade(from: URI, to: URI): Boolean =
        from.scheme.equals("https", ignoreCase = true) && to.scheme.equals("http", ignoreCase = true)

    fun sameOrigin(left: URI, right: URI): Boolean =
        left.scheme.equals(right.scheme, ignoreCase = true) &&
            left.host.equals(right.host, ignoreCase = true) && effectivePort(left) == effectivePort(right)

    /** Credentials are permanently cleared by the caller after the first cross-origin redirect. */
    fun credentialsForRedirect(from: URI, to: URI, credentials: Credentials): Credentials? {
        if (isHttpsDowngrade(from, to)) return null
        return if (sameOrigin(from, to)) credentials else Credentials(cookie = null, authorization = null)
    }

    fun validHeader(value: String?, maximum: Int): Boolean =
        value == null || (value.length <= maximum && value.none { it < ' ' || it == '\u007f' })

    fun transferDeadlineExpired(deadlineElapsedMillis: Long, nowElapsedMillis: Long): Boolean =
        nowElapsedMillis >= deadlineElapsedMillis

    fun safeFileName(suggestedName: String?, url: URI): String {
        val fromUrl = url.rawPath.orEmpty().substringAfterLast('/').let { raw ->
            runCatching { URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }.getOrDefault(raw)
        }
        val source = suggestedName?.takeIf { it.isNotBlank() } ?: fromUrl.ifBlank { "download" }
        val basename = source.substringAfterLast('/').substringAfterLast('\\')
        var clean = buildString(minOf(basename.length, 180)) {
            basename.codePoints().limit(180).forEach { cp ->
                val ch = String(Character.toChars(cp))
                if (cp == ' '.code) append('_')
                else if (Character.isISOControl(cp) || cp > 0x7e ||
                    ch !in SAFE_FILENAME_CHARS
                ) append('_')
                else append(ch)
            }
        }.trim().trim('.').replace(Regex("\\.{2,}"), ".")
        if (clean.isBlank() || clean == "." || clean == "..") clean = "download"
        val deviceName = clean.substringBefore('.').uppercase(Locale.ROOT)
        if (deviceName in WINDOWS_DEVICE_NAMES) clean = "_$clean"
        return clean.take(180).ifBlank { "download" }
    }

    fun canReserve(existingEntries: Int, existingBytes: Long, reservedEntries: Int, reservedBytes: Long, requestBytes: Long): Boolean {
        if (existingEntries < 0 || existingBytes < 0 || reservedEntries < 0 || reservedBytes < 0) return false
        if (requestBytes !in 1..MAX_FILE_BYTES) return false
        if (existingEntries.toLong() + reservedEntries >= MAX_PERSISTENT_ENTRIES) return false
        if (existingBytes > MAX_TOTAL_BYTES || reservedBytes > MAX_TOTAL_BYTES - existingBytes) return false
        return requestBytes <= MAX_TOTAL_BYTES - existingBytes - reservedBytes
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    private val WINDOWS_DEVICE_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )
    private val SAFE_FILENAME_CHARS = setOf(
        "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "_", "-", ".",
    )
}
