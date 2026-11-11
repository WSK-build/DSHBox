package com.dshbox.dsh.renderer.api

import java.net.URI
import java.util.Locale

/**
 * Precise DSH loopback trust-origin comparison.
 *
 * A candidate is trusted only when its origin (scheme + canonical host + effective port) exactly
 * equals the configured base origin and the host is a loopback address: the whole IPv4 loopback
 * block 127.0.0.0/8 (the DSH base origin uses 127.0.0.1; the remote-connection module binds device
 * sessions on 127.0.0.2 ~ 127.0.0.11), plus the localhost and [::1] names. Distinct loopback hosts
 * are not collapsed into one origin.
 *
 * 唯一的放宽入口是 [create] 显式传入 [DshMainFrameNavigation.AnyHttps] 且 base
 * origin 为 https：此时取消回环要求（主机按小写归一化，origin 相等口径不变），
 * 供直连会话以真实上游 origin 作为可信基准。默认调用路径保持严格回环。
 */
class DshOriginPolicy private constructor(
    private val baseScheme: String,
    private val baseHost: String,
    private val basePort: Int,
    /**
     * 是否放宽回环主机限制（唯一入口是 [create] 的 AnyHttps 显式配置）：true 时
     * 主机仅做小写归一化，不再要求回环地址；scheme 仍与 base origin 严格相等。
     */
    private val allowNonLoopbackHost: Boolean,
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

    private fun canonicalHost(host: String): String? = when {
        allowNonLoopbackHost -> host.lowercase(Locale.ROOT)
        else -> canonicalLoopbackHost(host)
    }

    private fun effectivePort(uri: URI, scheme: String): Int? {
        val declared = uri.port
        if (declared == -1) return defaultPort(scheme)
        if (declared < 0 || declared > 65535) return null
        return declared
    }

    private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

    companion object {
        /** 既有入口：严格回环口径（等价于 TrustedOriginOnly 策略）。 */
        fun create(base: String): DshOriginPolicy =
            create(base, DshMainFrameNavigation.TrustedOriginOnly)

        /**
         * 显式入口：仅 AnyHttps 且 base origin 为 https 时放宽为允许任意非回环
         * 主机；其余路径保持严格回环，非法配置显式报错。
         */
        fun create(
            base: String,
            mainFrameNavigation: DshMainFrameNavigation,
        ): DshOriginPolicy {
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
            val allowNonLoopback =
                mainFrameNavigation == DshMainFrameNavigation.AnyHttps && scheme == "https"
            val canonical = (if (allowNonLoopback) host.lowercase(Locale.ROOT) else null)
                ?: canonicalLoopbackHost(host)
                ?: throw IllegalArgumentException("Base origin host must be loopback")
            val port = if (uri.port == -1) defaultPortFor(scheme) else uri.port
            if (port < 0 || port > 65535) throw IllegalArgumentException("Base origin port is out of range")
            return DshOriginPolicy(scheme, canonical, port, allowNonLoopback)
        }

        private fun defaultPortFor(scheme: String): Int = if (scheme == "https") 443 else 80

        /**
         * 归一化回环主机名：接受整个 IPv4 回环段 127.0.0.0/8（按字面量逐字节校验，拒绝
         * 带后缀或非法八位组的仿冒主机）以及 localhost 与 [::1]；返回小写字面量，
         * 不同回环主机之间不归并为同一 origin。
         */
        private fun canonicalLoopbackHost(host: String): String? {
            val h = host.removeSurrounding("[", "]").lowercase(Locale.ROOT)
            if (h == "localhost" || h == "::1") return h
            if (isLoopbackIpv4(h)) return h
            return null
        }

        private fun isLoopbackIpv4(h: String): Boolean {
            if (!h.startsWith("127.")) return false
            val octets = h.split('.')
            if (octets.size != 4) return false
            return octets.all { octet ->
                octet.isNotEmpty() && octet.length <= 3 && octet.all { it in '0'..'9' } && octet.toInt() in 0..255
            }
        }
    }
}
