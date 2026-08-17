package com.dshbox.app.common

/**
 * Central log redaction. Never log API keys, cookies, authorization headers,
 * passwords or tokens. All log sinks (App/Sandbox/DSH/Plugin/WebView) must
 * pass through here or an equivalent policy.
 */
object LogRedactor {
    private val patterns = listOf(
        Regex("(?i)(api[_-]?key\\s*[:=]\\s*)[A-Za-z0-9_\\-]+") to "$1***",
        Regex("(?i)(authorization\\s*[:=]\\s*)Bearer\\s+[A-Za-z0-9_\\-.]+") to "$1***",
        Regex("(?i)(cookie\\s*[:=]\\s*)[^\\s;]+") to "$1***",
        Regex("(?i)(password\\s*[:=]\\s*)\\S+") to "$1***",
        Regex("(?i)(token\\s*[:=]\\s*)\\S+") to "$1***",
        Regex("sk-[A-Za-z0-9_\\-]+") to "sk-***",
    )

    fun redact(text: String): String = patterns.fold(text) { acc, (regex, replacement) ->
        regex.replace(acc, replacement)
    }
}
