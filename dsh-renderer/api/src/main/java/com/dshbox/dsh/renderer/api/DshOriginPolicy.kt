package com.dshbox.dsh.renderer.api

import java.net.URI
import java.util.Locale

/**
 * Precise DSH loopback trust-origin comparison.
 *
 * A candidate is trusted only when its origin (scheme + canonical host + effective port) exactly
 * equals the configured base origin and the host is a loopback address the configuration explicitly
 * allows (127.0.0.1 / localhost / [::1]). Distinct loopback hosts are not collapsed into one origin.
 */
class DshOriginPolicy private constructor(
    private val baseScheme: String,
    private val baseHost: String,
    private val basePort: Int,
) {
    fun isTrusted(candidate: String): Boolean {
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return false
        if (!uri.isAbsolute) return false
        if (!uri.rawSchemeSpecificPart.startsWith("//")) return false
        val scheme = (uri.scheme ?: return false).lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        if (uri.userInfo != null) return false
        val host = uri.host ?: return false
        val canonical = canonicalHost(host) ?: return false
        val port = effectivePort(uri, scheme) ?: return false
        return scheme == baseScheme && canonical == baseHost && port == basePort
    }

    private fun canonicalHost(host: String): String? {
        val h = host.removeSurrounding("[", "]").lowercase(Locale.ROOT)
        return when (h) {
            "127.0.0.1", "localhost", "::1" -> h
            else -> null
        }
    }

    private fun effectivePort(uri: URI, scheme: String): Int? {
        val declared = uri.port
        if (declared == -1) return defaultPort(scheme)
        if (declared < 0 || declared > 65535) return null
        return declared
    }

    private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

    companion object {
        fun create(base: String): DshOriginPolicy {
            val uri = runCatching { URI(base) }.getOrElse { e ->
                throw IllegalArgumentException("Base origin is not a valid URI", e)
            }
            if (!uri.isAbsolute) throw IllegalArgumentException("Base origin must be absolute")
            if (!uri.rawSchemeSpecificPart.startsWith("//")) {
                throw IllegalArgumentException("Base origin must be hierarchical")
            }
            val scheme = (uri.scheme
                ?: throw IllegalArgumentException("Base origin must declare a scheme")).lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") {
                throw IllegalArgumentException("Base origin must use http or https")
            }
            if (uri.userInfo != null) throw IllegalArgumentException("Base origin must not carry user info")
            val host = uri.host ?: throw IllegalArgumentException("Base origin must declare a host")
            val canonical = host.removeSurrounding("[", "]").lowercase(Locale.ROOT)
            if (canonical != "127.0.0.1" && canonical != "localhost" && canonical != "::1") {
                throw IllegalArgumentException("Base origin host must be loopback")
            }
            val port = if (uri.port == -1) defaultPortFor(scheme) else uri.port
            if (port < 0 || port > 65535) throw IllegalArgumentException("Base origin port is out of range")
            return DshOriginPolicy(scheme, canonical, port)
        }

        private fun defaultPortFor(scheme: String): Int = if (scheme == "https") 443 else 80
    }
}
