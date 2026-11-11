package com.dshbox.remote.parse

/**
 * ZCode 工作区配对解析器：URL 签名 `https://zcode.z.ai/remote/…`。
 *
 * 账号型入口：登录态由页面自持（无一次性凭据可注入），端点声明为直连
 * 会话——不建回环代理，渲染器直接加载真实入口地址，主框架导航按 AnyHttps
 * 口径放行（本机 DSH 回环链路不受影响）。
 *
 * 载荷守卫：路径按段匹配（`/remote` 或 `/remote/` 起始），含 userinfo
 * （`user:pass@host`，凭据直嵌形态）的载荷显式拒绝且文案脱敏——签名尾段
 * 之外的值不得静默落入通用通道建档。目标地址非 `https:` 的载荷同样显式
 * 拒绝：直连标记只在 https 载荷上产生，非 https 载荷在解析期拒绝（直连
 * 会话层构造期只接受 https origin，恒失败的档案不在建档期产出）。
 */
class ZcodeRemoteParser(override val id: String = ParserIds.ZCODE_REMOTE) : PairingParser {

    override fun matches(raw: String): Boolean {
        val uri = UrlParserSupport.parseHttpUrl(raw) ?: return false
        return uri.host?.lowercase() == ZCODE_HOST && isRemotePath(uri.rawPath)
    }

    override fun parse(raw: String): ParsedPairing {
        val uri = UrlParserSupport.parseHttpUrl(raw)
            ?: throw PairingParseException(
                "Not a ZCode remote URL: ${SensitiveTextRedactor.redactAndTruncate(raw)}",
            )
        // 签名命中但内嵌凭据（user:pass@host）：按钓鱼形态显式拒绝，文案
        // 不携带原始载荷（userinfo 已在异常前剥离）
        if (uri.userInfo != null) {
            throw PairingParseException(
                "Pairing URL must not embed credentials: ${SensitiveTextRedactor.redactAndTruncate(raw)}",
            )
        }
        // 直连会话只承载 https 上游（渲染器 AnyHttps 口径与会话层构造期校验
        // 的同一前提）：明文目标拒绝建档，不产出 http+DIRECT 的死档
        if (uri.scheme?.lowercase() != "https") {
            throw PairingParseException(
                "This pairing entry requires an https address: ${SensitiveTextRedactor.redactAndTruncate(raw)}",
            )
        }
        return ParsedPairing(
            parserId = id,
            // 设备默认名固定（产品自称，随档案持久化）：用户可在清单内改名
            deviceName = "ZCode 工作区", // i18n-skip：设备默认名持久化数据，非界面文案
            endpoints = listOf(
                ParsedEndpoint(
                    // 直连入口：完整地址（含路径与 query）即会话首载地址，
                    // 无凭据剥离语义（账号登录态由页面自持）
                    targetUrl = raw.trim(),
                    channelLabel = UrlParserSupport.channelLabelFor(uri),
                    credential = null,
                    sessionMode = DIRECT_SESSION_MODE,
                ),
            ),
        )
    }

    companion object {
        /** 直连会话的存储枚举名（与 [com.dshbox.remote.model.SessionMode.DIRECT] 同名）。 */
        const val DIRECT_SESSION_MODE = "DIRECT"

        private const val ZCODE_HOST = "zcode.z.ai"
        private const val REMOTE_PATH_PREFIX = "/remote"

        /** 签名路径的段匹配：`/remote` 或 `/remote/` 起始——`/remoteX`、
         *  `/console?x=/remote` 一类被误收的形态不再命中。 */
        internal fun isRemotePath(rawPath: String?): Boolean {
            val path = rawPath ?: "/"
            return path == REMOTE_PATH_PREFIX || path.startsWith("$REMOTE_PATH_PREFIX/")
        }
    }
}
