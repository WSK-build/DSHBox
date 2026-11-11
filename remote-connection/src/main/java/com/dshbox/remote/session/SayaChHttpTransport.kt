package com.dshbox.remote.session

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * saya-ch 通道的 HTTP 传输（native-pair 兑换与 CA 引导）：生产 [SayaChPairTransport]。
 *
 * 两个请求都面向 dsh-mobile 网关（`/mobile-access/…`），TLS 策略刻意不同：
 * - [fetchCa]（CA 引导）：LAN 自签网关经 `GET /mobile-access/ca.cer` 下发 DER
 *   证书——该请求发生在信任锚确立之前，TLS **trust-all + 关闭主机名校验**
   * （仅此一次、仅此请求，隔离在专用 factory/verifier 上，不影响其它请求）；
 *   下发结果的真伪由调用方的 CA 指纹核对兜底。404 返回 null（公开证书入口的
 *   合法形态）；网络/握手失败抛 [SayaChHttpException]（可读原因，不含 token）。
 * - [postNativePair]：POST `/mobile-access/auth/native-pair` 兑换一次性票；
 *   TLS 信任锚 = 引导/存档得到的 CA（null 时系统信任，公网入口形态），正常
 *   证书与主机名校验全程开启。
 *
 * 本类不做凭据落档、不做指纹核对（核对是调用方职责，失败语义在会话层）；
 * 只负责把 HTTP 细节与 [SayaChPairExchange] 的纯逻辑对接。
 */
open class SayaChHttpTransport(
    /** 连接/读取超时（毫秒）。 */
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) : SayaChPairTransport {

    /** 引导请求失败（非 404）：网络/握手问题的可读包装。 */
    class SayaChHttpException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * CA 引导：GET `origin/mobile-access/ca.cer`，TLS trust-all + 不校验主机
     * 名（仅此引导请求，见类注释）；返回 Base64(DER)。404 → null（公开证书
     * 入口）；其余网络/握手失败抛 [SayaChHttpException]（与「无 CA」语义区分）。
     */
    open fun fetchCa(origin: String): String? {
        val connection = openBootstrap(origin.trim().trimEnd('/') + CA_PATH)
        try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (status !in 200..299) {
                throw SayaChHttpException("CA bootstrap rejected: HTTP $status")
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) {
                throw SayaChHttpException("CA bootstrap returned an empty body")
            }
            return Base64.getEncoder().encodeToString(bytes)
        } catch (error: SSLException) {
            throw SayaChHttpException("CA bootstrap TLS failure (${error.javaClass.simpleName})", error)
        } catch (error: java.net.UnknownHostException) {
            throw SayaChHttpException(
                "无法解析配对主机：请检查手机网络，或二维码里的地址是否仍有效",
                error,
            )
        } catch (error: IOException) {
            if (error is SayaChHttpException) throw error
            throw SayaChHttpException("CA bootstrap network failure (${error.javaClass.simpleName})", error)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * native-pair 兑换：POST `origin/mobile-access/auth/native-pair`，body 由
     * [SayaChPairExchange.requestJson] 构造；TLS 信任锚 = [caBase64] 解码出的
     * CA（null 用系统信任）；响应交给 [SayaChPairExchange.parse]。
     */
    open fun postNativePair(origin: String, token: String, caBase64: String?): SayaChPairResult {
        val url = origin.trim().trimEnd('/') + SayaChPairExchange.NATIVE_PAIR_PATH
        val body = SayaChPairExchange.requestJson(token)
        val connection = open(url, caBase64)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            // 网关对 POST 强制同源校验（Origin 必须匹配描述符 origin），
            // HttpURLConnection 不会自动带 Origin；缺失会被 403 forbidden 拒绝
            connection.setRequestProperty("Origin", origin.trim().trimEnd('/'))
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val headers = connection.headerFields
                .filterKeys { it != null }
                .flatMap { (name, values) -> values.map { (name as String) to it } }
            val text = if (status in 200..299) {
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            }
            return SayaChPairExchange.parse(status, headers, text)
        } finally {
            connection.disconnect()
        }
    }

    /** native-renew：用 90 天 deviceToken 换新 sessionToken/csrfToken。 */
    open fun renew(origin: String, deviceToken: String, caBase64: String?): SayaChPairResult {
        val url = origin.trim().trimEnd('/') + RENEW_PATH
        val body = "{\"deviceToken\":\"" + deviceToken.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"
        val connection = open(url, caBase64)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Origin", origin.trim().trimEnd('/'))
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val headers = connection.headerFields
                .filterKeys { it != null }
                .flatMap { (name, values) -> values.map { (name as String) to it } }
            val text = if (status in 200..299) {
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            }
            return SayaChPairExchange.parse(status, headers, text)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * [SayaChPairTransport] 实现：仅服务单测注入（生产兑换走 [postNativePair]），
     * 用系统默认信任——不做 CA 引导语义。
     */
    override fun post(url: String, jsonBody: String): SayaChHttpResponse {
        val connection = open(url, caBase64 = null)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val headers = connection.headerFields
                .filterKeys { it != null }
                .flatMap { (name, values) -> values.map { (name as String) to it } }
            val text = if (status in 200..299) {
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            }
            return SayaChHttpResponse(status, headers, text)
        } finally {
            connection.disconnect()
        }
    }

    /** 引导专用连接：trust-all factory + 放行全部主机名（仅 ca.cer 一次）。 */
    private fun openBootstrap(url: String): HttpsURLConnection {
        val connection = open(url, caBase64 = null)
        connection.sslSocketFactory = SayaChTls.bootstrapSocketFactory()
        connection.hostnameVerifier = SayaChTls.BOOTSTRAP_HOSTNAME_VERIFIER
        return connection
    }

    private fun open(url: String, caBase64: String?): HttpsURLConnection {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = false
        if (caBase64 != null) {
            connection.sslSocketFactory = SayaChTls.socketFactory(caBase64)
        }
        return connection
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000
        const val CA_PATH = "/mobile-access/ca.cer"
        private const val RENEW_PATH = "/mobile-access/auth/native-renew"

        /**
         * CA 指纹比对：接受集收口在解析层 [com.dshbox.remote.parse.SayaChFingerprintAcceptSet]
         * （hex 大小写 / Base64 / Base64URL 四形态，单一来源）。
         */
        fun fingerprintMatches(caBase64: String, expected: String?): Boolean =
            com.dshbox.remote.parse.SayaChFingerprintAcceptSet.matches(caBase64, expected)
    }
}

/** 兼容旧测试引用：指纹比对已收口到解析层接受集（单一来源），此处仅透传。 */
internal object SayaChFingerprint {
    fun matches(caBase64: String, expected: String?): Boolean =
        com.dshbox.remote.parse.SayaChFingerprintAcceptSet.matches(caBase64, expected)
}

/** saya-ch 通道的 TLS 组装：仅含指定 CA 的独立信任链 / 引导专用 trust-all。 */
internal object SayaChTls {

    /** 用 Base64(DER) CA 构建只含该 CA 信任链的 SSLSocketFactory。 */
    fun socketFactory(caBase64: String): javax.net.ssl.SSLSocketFactory {
        val certificate = certificateOf(caBase64)
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("saya-ca", certificate)
        }
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(keyStore)
        val trustManager = trustManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().single()
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustManager), SecureRandom())
        return context.socketFactory
    }

    /**
     * CA 引导专用 factory：trust-all（仅 [SayaChHttpTransport.fetchCa] 一次；
     * 下发材料必须经调用方指纹核对才可信任）。
     */
    fun bootstrapSocketFactory(): javax.net.ssl.SSLSocketFactory {
        val trustAll = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        return context.socketFactory
    }

    /** 引导专用主机名校验：全部放行（仅 ca.cer 一次，理由同上）。 */
    val BOOTSTRAP_HOSTNAME_VERIFIER = HostnameVerifier { _, _ -> true }

    private fun certificateOf(caBase64: String): X509Certificate {
        val der = Base64.getMimeDecoder().decode(caBase64.trim())
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(der.inputStream()) as X509Certificate
    }
}
