package com.dshbox.remote.parse

import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.Locale

/**
 * saya-ch / dsh-mobile 配对解析器：JSON 配对描述符（iroh 形态，见下）与
 * 非 iroh 通道的 URL 配对链接（LAN/公网 HTTPS，`/mobile-access/pair` 路径）。
 *
 * JSON 描述符 `{version:1, type:"dsh-mobile-iroh-pairing", endpointId, origin,
 * localPort, irohCode, gatewayToken, caCertificate, instanceId, expiresAt}`
 * （≤10 分钟有效期的自签 CA HTTPS 形态）：origin 挂在 `*.iroh.localhost` 下，
 * 是手机端内嵌 iroh 传输的名义地址——本工程没有该传输栈，**parse 显式拒绝**
 * 并引导改用局域网/Cloudflare 等普通 HTTPS 模式重新生成。
 *
 * URL 配对链接（本工程支持的形态）：`https://<host>[:port]/mobile-access/pair
 * #instance=<指纹>&token=<43字符base64url>`，token 为一次性配对票（进消费
 * 型集合，native-pair 单次兑换出会话 cookie + CSRF）。
 *
 * 校验链：type/version 签名 → 必备字段齐全 → origin 为 https（origin 缺省
 * 端口时以 localPort 补齐）→ iroh 域名显式拒绝 → expiresAt 未过期 →
 * caCertificate 可解码为 X.509 证书且其 SHA-256 指纹与 instanceId 一致。
 *
 * 字段去向：token 为一次性票，入加密存档（兑换后标记消费，票据不再重发）；
 * caCertificate 以 Base64 原文入加密存档（会话建立时解码为该会话的 TLS 信任，
 * 随设备删除销毁）；irohCode 为 6 位人眼比对码（iroh 传输层校验码，本工程
 * 丢弃，不入任何存档）；expiresAt 仅在解析期校验，不落档。
 */
class SayaChJsonParser(override val id: String = ParserIds.SAYA_CH) : PairingParser {

    override fun matches(raw: String): Boolean {
        val payload = raw.trim()
        if (payload.startsWith("{")) return isJsonDescriptor(payload)
        return isUrlPairingLink(payload)
    }

    override fun parse(raw: String): ParsedPairing {
        val payload = raw.trim()
        if (payload.startsWith("{")) return parseJsonDescriptor(payload)
        return parseUrlPairingLink(payload)
    }

    private fun isJsonDescriptor(payload: String): Boolean {
        val fields = FlatJsonReader.read(payload) ?: return false
        return fields[TYPE_KEY] == PAIRING_TYPE && fields[VERSION_KEY] == VERSION
    }

    private fun parseJsonDescriptor(payload: String): ParsedPairing {
        val fields = FlatJsonReader.read(payload)
        if (fields == null || fields[TYPE_KEY] != PAIRING_TYPE || fields[VERSION_KEY] != VERSION) {
            throw PairingParseException(
                "Not a dsh-mobile pairing descriptor: ${SensitiveTextRedactor.redactAndTruncate(payload)}",
            )
        }
        val endpointId = required(fields, ENDPOINT_ID_KEY)
        val gatewayToken = required(fields, GATEWAY_TOKEN_KEY)
        val instanceId = required(fields, INSTANCE_ID_KEY)
        val irohCode = required(fields, IROH_CODE_KEY) // 人眼比对码：契约必备，呈现前丢弃
        val expiresAt = required(fields, EXPIRES_AT_KEY)

        // iroh 描述符显式拒绝：origin 的 host 挂在 `*.iroh.localhost` 下
        // （endpointId 派生域名，含 localPort=25443 形态），是手机端内嵌
        // iroh 传输的名义地址——没有该传输栈就不可达，产出端点只会停留在
        // 加载态。明确报错引导改用局域网/Cloudflare 等普通 HTTPS 模式重新
        // 生成配对码。host 先小写再比对（URI.host 保留原始大小写）。
        val origin = required(fields, ORIGIN_KEY)
        val originHost = runCatching { URI(origin.trim()).host }.getOrNull()?.lowercase(Locale.ROOT)
        val normalizedOriginHost = originHost?.trimEnd('.')
        if (normalizedOriginHost != null &&
            (normalizedOriginHost == IROH_HOST_SUFFIX.removePrefix(".") ||
                normalizedOriginHost.endsWith(IROH_HOST_SUFFIX))
        ) {
            throw PairingParseException(IROH_UNSUPPORTED_MESSAGE)
        }

        // 凭据/证书按契约白名单校验：字段内的换行（描述符转义或复制引入）
        // 不得穿透到加密存档（行式编码会被破坏，殃及同设备其它端点条目）与
        // HTTP 序列化（注入汇点）
        validateGatewayToken(gatewayToken)
        val caCertificate = normalizeCaCertificate(required(fields, CA_CERTIFICATE_KEY))

        val targetUrl = originTargetUrl(origin, fields[LOCAL_PORT_KEY])
        ensureNotExpired(expiresAt)
        verifyCaFingerprint(caCertificate, instanceId)

        return ParsedPairing(
            parserId = id,
            deviceName = endpointId,
            endpoints = listOf(
                ParsedEndpoint(
                    targetUrl = targetUrl,
                    channelLabel = UrlParserSupport.channelLabelForHost(hostOf(targetUrl)),
                    credential = gatewayToken,
                    caCertificate = caCertificate,
                    pairingInstance = fields[INSTANCE_ID_KEY]?.takeIf { it.isNotBlank() },
                ),
            ),
        )
    }

    /** 必备成员缺失（或空白）即显式报错；成员键名不是敏感值，可出现在文案。 */
    private fun required(fields: Map<String, String>, name: String): String =
        fields[name]?.takeIf { it.isNotBlank() }
            ?: throw PairingParseException("Pairing descriptor is missing \"$name\"")

    private fun illegalCharacters(name: String) = PairingParseException(
        "Pairing descriptor field \"$name\" contains illegal characters",
    )

    /**
     * gatewayToken 白名单：base64url 字符集（`[A-Za-z0-9_-]`，43 字符无
     * 填充形态）。字符集不含 CR/LF 与 URL 元字符（`+/=` 等）——存档行式
     * 编码与 query 注入形态天然安全（配对票经 native-pair 兑换，注入层不再
     * 以 query 形态携带该值）。
     */
    private fun validateGatewayToken(token: String) {
        if (!GATEWAY_TOKEN_CHARSET.matches(token)) {
            throw illegalCharacters(GATEWAY_TOKEN_KEY)
        }
    }

    /**
     * caCertificate 白名单：Base64 字符集（`[A-Za-z0-9+/=]`），容忍 MIME
     * 折行——先剥离 CR/LF 再校验剩余字符，剥离后的值入存档（会话建立时
     * 按同一解码口径还原）。其余任何字符（含空格与控制符）拒绝。
     */
    private fun normalizeCaCertificate(base64: String): String {
        val folded = base64.replace("\r", "").replace("\n", "")
        if (!CA_CERTIFICATE_CHARSET.matches(folded)) {
            throw illegalCharacters(CA_CERTIFICATE_KEY)
        }
        return folded
    }

    /**
     * origin → 上游目标地址：必须为带主机的 https origin；目标取 origin 本身
     * （剥离路径与 query——描述符的 origin 即源头标识，页面路径由上游根承载）。
     * origin 未携带端口时以 localPort 补齐（描述符契约的网关端口）；两者皆缺
     * 无法构成可访问 origin，显式报错。
     */
    private fun originTargetUrl(origin: String, localPortRaw: String?): String {
        val uri = runCatching { URI(origin.trim()) }.getOrNull()
        val host = uri?.host
        if (uri == null || !uri.scheme.equals("https", ignoreCase = true) || host.isNullOrBlank()) {
            throw PairingParseException("Pairing descriptor origin must be an https origin")
        }
        val port = uri.port.takeIf { it != -1 }
            ?: localPortRaw?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw PairingParseException("Pairing descriptor has no usable port")
        return buildString {
            append("https://").append(host.lowercase(Locale.ROOT))
            if (port != HTTPS_DEFAULT_PORT) append(':').append(port)
        }
    }

    /**
     * 有效期校验：epoch 毫秒数字或 ISO-8601 时间戳均可（描述符实现两类形态
     * 均有先例，宽容解析、过期即拒）。
     */
    private fun ensureNotExpired(raw: String) {
        val expiresAtMillis = raw.trim().toLongOrNull()
            ?: runCatching { Instant.parse(raw.trim()).toEpochMilli() }.getOrNull()
            ?: throw PairingParseException("Pairing descriptor expiry is not parseable")
        if (expiresAtMillis <= System.currentTimeMillis()) {
            throw PairingParseException("Pairing descriptor has expired")
        }
    }

    /**
     * CA 指纹校验：caCertificate（Base64 编码的 DER）解码为 X.509 证书，其
     * SHA-256 摘要与 instanceId 比对（十六进制大小写与 Base64/Base64URL 编码
     * 形态均接受——指纹编码口径随描述符实现而异，比对的是同一摘要）。
     */
    private fun verifyCaFingerprint(caBase64: String, instanceId: String) {
        // 指纹可解码性与形态接受集收口在 SayaChFingerprintAcceptSet（单一来源）
        val decodable = runCatching {
            SayaChFingerprintAcceptSet.acceptedForms(caBase64).isNotEmpty()
        }.getOrDefault(false)
        if (!decodable) {
            throw PairingParseException("Pairing descriptor CA certificate is not decodable")
        }
        if (!SayaChFingerprintAcceptSet.matches(caBase64, instanceId)) {
            throw PairingParseException("Pairing descriptor fingerprint mismatch")
        }
    }

    private fun hostOf(targetUrl: String): String? = runCatching { URI(targetUrl).host }.getOrNull()

    /**
     * dsh-mobile 非 iroh 通道（LAN/公网 HTTPS）的配对链接形态：
     * `https://<host>[:port]/mobile-access/pair#instance=<64hex>&token=<43字符>`
     * （电脑端「生成配对二维码/复制配对链接」产出；query 承载同键为宽容形态）。
     * 网关无 CA 内嵌——公开证书入口走系统信任，LAN 入口由 native-pair 兑换
     * 流程另行处理，本解析器只负责建端点档案与携带配对票。
     */
    private fun isUrlPairingLink(payload: String): Boolean {
        val uri = parsePairingUrl(payload) ?: return false
        return pairingParams(uri).containsKey(TOKEN_PARAM)
    }

    private fun parseUrlPairingLink(payload: String): ParsedPairing {
        val uri = parsePairingUrl(payload)
            ?: throw PairingParseException(
                "Not a dsh-mobile pairing link: ${SensitiveTextRedactor.redactAndTruncate(payload)}",
            )
        val params = pairingParams(uri)
        val token = params[TOKEN_PARAM]?.takeIf { it.isNotBlank() }
            ?: throw PairingParseException(
                "dsh-mobile pairing link is missing \"$TOKEN_PARAM\": ${SensitiveTextRedactor.redactAndTruncate(payload)}",
            )
        // token 复用 JSON 描述符的 base64url 白名单（含 CR/LF 拒绝）：票据随
        // 端点入加密存档，行式编码按行拆键值——换行会殃及同设备其它条目；
        // base64url 字符集不含 URL 元字符，query 注入汇点天然安全
        validateGatewayToken(token)
        val scheme = uri.scheme ?: "https"
        val host = uri.host?.lowercase(Locale.ROOT)
            ?: throw PairingParseException(
                "dsh-mobile pairing link has no host: ${SensitiveTextRedactor.redactAndTruncate(payload)}",
            )
        // URL 形态也可能是 iroh 模式（host 挂在 *.iroh.localhost）：同 JSON
        // 描述符一样明确拒绝，避免 CA 引导阶段 DNS UnknownHost 导致空白页
        val irohHost = host.trimEnd('.')
        if (irohHost == IROH_HOST_SUFFIX.removePrefix(".") || irohHost.endsWith(IROH_HOST_SUFFIX)) {
            throw PairingParseException(IROH_UNSUPPORTED_MESSAGE)
        }
        val targetUrl = buildString {
            append(scheme).append("://").append(host)
            if (uri.port != -1) append(':').append(uri.port)
        }
        return ParsedPairing(
            parserId = id,
            deviceName = host,
            endpoints = listOf(
                ParsedEndpoint(
                    targetUrl = targetUrl,
                    channelLabel = UrlParserSupport.channelLabelForHost(host),
                    credential = token,
                    pairingInstance = params[INSTANCE_PARAM]?.takeIf { it.isNotBlank() },
                ),
            ),
        )
    }

    /** 配对链接合法性：https、路径恰为 `/mobile-access/pair`、无用户信息。 */
    private fun parsePairingUrl(payload: String): URI? {
        val uri = runCatching { URI(payload) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        if ((uri.rawPath ?: "") != PAIR_PATH) return null
        return uri
    }

    /** 配对参数读取：fragment（`#k=v&k2=v2`）优先，缺失时回退 query。 */
    private fun pairingParams(uri: URI): Map<String, String> {
        val component = uri.rawFragment?.takeIf { it.isNotBlank() } ?: uri.rawQuery ?: return emptyMap()
        return component.split('&')
            .mapNotNull { entry ->
                val key = entry.substringBefore('=')
                if (key.isEmpty()) return@mapNotNull null
                key to entry.substringAfter('=', "")
            }
            .toMap()
    }

    private companion object {
        const val PAIRING_TYPE = "dsh-mobile-iroh-pairing"
        const val VERSION = "1"
        const val HTTPS_DEFAULT_PORT = 443

        /** gatewayToken 的 base64url 字符集（无填充形态）。 */
        val GATEWAY_TOKEN_CHARSET = Regex("""[A-Za-z0-9_-]+""")

        /** caCertificate 剥离 MIME 折行后的 Base64 字符集。 */
        val CA_CERTIFICATE_CHARSET = Regex("""[A-Za-z0-9+/=]+""")

        const val TYPE_KEY = "type"
        const val VERSION_KEY = "version"
        const val ENDPOINT_ID_KEY = "endpointId"
        const val ORIGIN_KEY = "origin"
        const val LOCAL_PORT_KEY = "localPort"
        const val IROH_CODE_KEY = "irohCode"
        const val GATEWAY_TOKEN_KEY = "gatewayToken"
        const val CA_CERTIFICATE_KEY = "caCertificate"
        const val INSTANCE_ID_KEY = "instanceId"
        const val EXPIRES_AT_KEY = "expiresAt"

        /** iroh 描述符的派生域名后缀与明确拒绝文案。 */
        const val IROH_HOST_SUFFIX = ".iroh.localhost"
        const val IROH_UNSUPPORTED_MESSAGE =
            "该配对码使用 iroh 传输，当前版本不支持；请在电脑端改用局域网/Cloudflare 等模式重新生成"

        /** 非 iroh 通道配对链接的固定路径与参数键。 */
        const val PAIR_PATH = "/mobile-access/pair"
        const val TOKEN_PARAM = "token"
        const val INSTANCE_PARAM = "instance"
    }
}

/**
 * CA 指纹比对口径（单一来源）：解析层 verifyCaFingerprint 与会话层
 * SayaChHttpTransport.fingerprintMatches 共用同一接受集（hex 大小写 /
 * Base64 / Base64URL 四形态），避免两处复制漂移。
 */
internal object SayaChFingerprintAcceptSet {

    /** caBase64（Base64 DER）SHA-256 摘要的全部可接受编码形态；不可解码抛异常。 */
    fun acceptedForms(caBase64: String): Set<String> {
        val der = Base64.getMimeDecoder().decode(caBase64.trim())
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(der.inputStream()) as X509Certificate
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        val hexLower = digest.joinToString("") { "%02x".format(it) }
        return setOf(
            hexLower,
            hexLower.uppercase(Locale.ROOT),
            Base64.getEncoder().encodeToString(digest),
            Base64.getUrlEncoder().withoutPadding().encodeToString(digest),
        )
    }

    /** expected（任意形态）是否命中 caBase64 的指纹接受集；null/空白恒否。 */
    fun matches(caBase64: String, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return false
        return expected.trim() in acceptedForms(caBase64)
    }
}
