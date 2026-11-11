package com.dshbox.remote.parse

/**
 * DSH Remote 配对解析器：URL 签名 `https://dsh.r2049.cn/app/…` 与
 * `https://dsh.r2049.cn/api/v1/auth/q/<code>`（兑换链接存档中的真实形态）。
 *
 * 账号型入口：登录态由页面自持（无一次性凭据可注入），端点声明为直连
 * 会话——不建回环代理，渲染器直接加载真实入口地址，主框架导航按 AnyHttps
 * 口径放行（本机 DSH 回环链路不受影响）。
 *
 * 载荷守卫：路径按段匹配（`/app` 或 `/app/` 起始；auth 链接为恰一段
 * code 的 `/api/v1/auth/q/<非空 code>`，允许尾斜杠与查询参数），含 userinfo
 * （`user:pass@host`，凭据直嵌形态）的载荷显式拒绝且文案脱敏——签名尾段
 * 之外的值不得静默落成通用通道档案。目标地址非 `https:` 的载荷同样显式
 * 拒绝：直连标记只在 https 载荷上产生，非 https 载荷在解析期拒绝（直连
 * 会话层构造期只接受 https origin，恒失败的档案不在建档期产出）。
 */
class DsHarnessRemoteParser(override val id: String = ParserIds.DS_HARNESS_REMOTE) : PairingParser {

    override fun matches(raw: String): Boolean {
        val uri = UrlParserSupport.parseHttpUrl(raw) ?: return false
        if (uri.host?.lowercase() != DSH_REMOTE_HOST) return false
        return isAppPath(uri.rawPath) || isAuthQPath(uri.rawPath)
    }

    override fun parse(raw: String): ParsedPairing {
        val uri = UrlParserSupport.parseHttpUrl(raw)
            ?: throw PairingParseException(
                "Not a DSH Remote URL: ${SensitiveTextRedactor.redactAndTruncate(raw)}",
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
            deviceName = "DSH Remote",
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

        private const val DSH_REMOTE_HOST = "dsh.r2049.cn"
        private const val APP_PATH_PREFIX = "/app"
        private const val AUTH_Q_PATH_PREFIX = "/api/v1/auth/q/"

        /** 签名路径的段匹配：`/app` 或 `/app/` 起始——替换旧的 contains 宽松
         *  口径（`/login/app`、`/my/app` 等不再被误认为 DSH Remote 入口）。 */
        internal fun isAppPath(rawPath: String?): Boolean {
            val path = rawPath ?: "/"
            return path == APP_PATH_PREFIX || path.startsWith("$APP_PATH_PREFIX/")
        }

        /** 兑换链接签名路径的段匹配：恰一段 code 的 `/api/v1/auth/q/<code>`
         *  （code 非空，允许尾斜杠与查询参数）；`q/<code>/…` 的多余段与
         *  `/api/v1/auth/` 下其它分支不命中——兑换码路径仅实名 code 一段，
         *  粘跑段按钓鱼形态拒绝。 */
        internal fun isAuthQPath(rawPath: String?): Boolean {
            val path = rawPath ?: return false
            if (!path.startsWith(AUTH_Q_PATH_PREFIX)) return false
            // 恰一段 code：尾斜杠形态 `q/<code>/` 满足（末空段是尾斜杠的
            // 约定产物）；`q/<code>/extra` 的粘跑段不算签名
            val remainder = path.removePrefix(AUTH_Q_PATH_PREFIX)
            val segments = remainder.split('/')
            if (segments.size != 1 && !(segments.size == 2 && segments[1].isBlank())) return false
            return segments[0].isNotBlank()
        }
    }
}
