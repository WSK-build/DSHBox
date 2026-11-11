package com.dshbox.remote.parse

/**
 * 配对错误信息安全化：未知/失败载荷进入错误文案前，先按正则抹除
 * token/secret/auth/ticket/code 等敏感键值，再截断展示。
 *
 * 禁止「直接截前 N 字符」——截断后的载荷可能正好携带凭据前缀。
 */
object SensitiveTextRedactor {

    /**
     * 竖线分隔文本（`地址|令牌` 形态）：首个竖线之后的全部内容按凭据遮蔽
     * ——分隔文本的凭据不带键名（`token=` 类正则遮不到），整段遮蔽是唯一
     * 不漏的方向；竖线本身不是合法 URL 字符，含竖线的失败载荷必属分隔形态。
     */
    private val DELIMITED_TAIL = Regex("""\|.*""", setOf(RegexOption.DOT_MATCHES_ALL))

    /**
     * URL userinfo（`scheme://user:pass@host`）整体按敏感处理： userinfo 只
     * 出现于凭据直嵌形态，用户名与口令一并遮蔽；匹配终止于 host 前的最后一个
     * `@`，口令本身含 `@` 时同样覆盖完整。
     */
    private val USERINFO = Regex("""(?i)([a-z][a-z0-9+.-]*://)[^\s/?#]*@""")

    /** URL 查询参数中的敏感键值：`token=…`、`auth=…` 等。 */
    private val QUERY_SECRET = Regex(
        """(?i)(token|secret|auth|ticket|code|password|passwd|pwd|key|pin|nonce|sig|signature)=([^&\s]*)""",
    )

    /**
     * JSON 成员中的敏感键值：`"token": "…"` 等；键名含敏感词的复合键
     * （gatewayToken）与无键名语义的描述符成员（irohCode/caCertificate）
     * 一并按值遮蔽。
     */
    private val JSON_SECRET = Regex(
        """(?i)("(?:token|secret|auth|ticket|code|password|passwd|pwd|key|pin|nonce|sig|signature|gatewayToken|irohCode|caCertificate)"\s*:\s*)("[^"]*"|[^,\s}\]]+)""",
    )

    /** URL fragment 整体按敏感处理（配对票的常见藏身处，如 `/pair#<secret>`）。 */
    private val FRAGMENT = Regex("""#([^\s]*)""")

    /** 脱敏后截断展示用最大长度。 */
    private const val MAX_DISPLAY_CHARS = 200

    private const val MASK = "***"

    fun redact(raw: String): String {
        val withoutDelimitedTail = DELIMITED_TAIL.replace(raw) { "|$MASK" }
        val withoutUserInfo = USERINFO.replace(withoutDelimitedTail) { match ->
            "${match.groupValues[1]}$MASK@"
        }
        val withoutQuerySecrets = QUERY_SECRET.replace(withoutUserInfo) { match ->
            "${match.groupValues[1]}=$MASK"
        }
        val withoutJsonSecrets = JSON_SECRET.replace(withoutQuerySecrets) { match ->
            "${match.groupValues[1]}\"$MASK\""
        }
        return FRAGMENT.replace(withoutJsonSecrets) { match ->
            if (match.groupValues[1].isEmpty()) match.value else "#$MASK"
        }
    }

    /** 脱敏并截断：错误文案与日志的唯一合法出口。 */
    fun redactAndTruncate(raw: String, maxChars: Int = MAX_DISPLAY_CHARS): String {
        val redacted = redact(raw.trim())
        return if (redacted.length <= maxChars) redacted else redacted.take(maxChars) + "…"
    }
}
