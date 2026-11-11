package com.dshbox.remote.proxy

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

/**
 * 会话级 TLS 信任：上游为 HTTPS 时的 SSLContext 提供方。
 *
 * 默认使用系统信任；自签上游经会话载入其 CA 证书构建独立信任链，
 * 不污染全局信任。同一会话内所有上游连接共用同一实例。
 */
class TlsTrust private constructor(private val trustManagers: Array<TrustManager>) {

    private val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, trustManagers, null)
    }

    /** 上游连接用的套接字工厂（含证书链校验）。 */
    fun socketFactory(): SSLSocketFactory = sslContext.socketFactory

    /** 建连后的主机名校验（SSLSocket 自身不校验，须显式执行）。 */
    fun verifyHostname(socket: SSLSocket, host: String) {
        val leaf = socket.session.peerCertificates.firstOrNull() as? X509Certificate
            ?: throw CertificateException("Upstream TLS session has no peer certificate")
        if (hostnameMatches(leaf, host.lowercase())) return
        throw CertificateException("Upstream TLS host does not match certificate: $host")
    }

    companion object {
        private const val GENERAL_NAME_DNS = 2
        private const val GENERAL_NAME_IP = 7

        /** 主机名匹配：IP 字面量对 iPAddress SAN，域名对 dNSName SAN（含单层通配），SAN 缺省时回退 CN。 */
        fun hostnameMatches(certificate: X509Certificate, host: String): Boolean {
            val sanNames = certificate.subjectAlternativeNames
                ?.filter { it.size >= 2 }
                ?.map { Pair((it[0] as? Number)?.toInt() ?: -1, it[1]?.toString()?.lowercase() ?: "") }
                .orEmpty()
            if (sanNames.isNotEmpty()) {
                val ipAddresses = sanNames.filter { it.first == GENERAL_NAME_IP }.map { it.second }
                val dnsNames = sanNames.filter { it.first == GENERAL_NAME_DNS }.map { it.second }
                return if (isIpLiteral(host)) {
                    host in ipAddresses
                } else {
                    dnsNames.any { matchesDnsName(host, it) }
                }
            }
            val commonName = certificate.subjectX500Principal.name
                .split(',')
                .map { it.trim() }
                .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.lowercase()
                ?: return false
            return matchesDnsName(host, commonName)
        }

        /** 单层通配（`*.example.com` 的 `*` 恰匹配一个标签）与精确匹配。 */
        fun matchesDnsName(host: String, pattern: String): Boolean {
            if (host == pattern) return true
            if (!pattern.startsWith("*.")) return false
            val suffix = pattern.substring(1)
            if (!host.endsWith(suffix)) return false
            val prefix = host.dropLast(suffix.length)
            return prefix.isNotEmpty() && prefix.all { it != '.' }
        }

        fun isIpLiteral(host: String): Boolean =
            host.contains(':') || Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(host)

        /** 系统默认信任（平台受信 CA）。 */
        fun systemDefault(): TlsTrust {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return TlsTrust(factory.trustManagers)
        }

        /** 会话级 CA 信任：仅信任给定证书链（自签上游的注入口）。 */
        fun custom(caCertificates: List<X509Certificate>): TlsTrust {
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
            caCertificates.forEachIndexed { index, certificate ->
                keyStore.setCertificateEntry("session-ca-$index", certificate)
            }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(keyStore)
            return TlsTrust(factory.trustManagers)
        }

        /** 测试与扩展用：直接给定信任管理器。 */
        fun custom(trustManager: X509TrustManager): TlsTrust = TlsTrust(arrayOf(trustManager))
    }
}
