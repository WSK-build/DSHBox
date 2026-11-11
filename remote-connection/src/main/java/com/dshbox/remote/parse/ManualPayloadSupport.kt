package com.dshbox.remote.parse

/**
 * 手动输入/重配对载荷的输入口径：对话框确认门禁与「整段载荷优先」的合成
 * 规则。非 http(s) 形态（JSON 配对描述符、自定义 scheme、竖线分隔文本）不
 * 在此拆解——原样交由 [ParserRegistry] 两级路由解析，解析失败以脱敏文案
 * 显式报错。
 */
internal object ManualPayloadSupport {

    /** 任意非空 scheme 的载荷前缀（dshremote://、dshmobile:// 等）。 */
    private val SCHEME_PREFIX = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://""")

    /**
     * 确认门禁：非空且形似可路由载荷——scheme 前缀、JSON 对象、竖线分隔
     * 文本三者之一（替代原先的 http 前缀判定，使自定义 scheme 与 JSON
     * 描述符可经手动输入建档；无形态特征的纯文本仍在门禁处拦下）。
     */
    fun isPlausiblePayload(raw: String): Boolean {
        val payload = raw.trim()
        if (payload.isEmpty()) return false
        return payload.startsWith("{") ||
            payload.contains('|') ||
            SCHEME_PREFIX.containsMatchIn(payload)
    }

    /**
     * 整段载荷优先合成：地址栏内容含竖线或非 http(s) 形态时原样提交
     * （令牌栏忽略——令牌可能是载荷自身的组成部分，拼接只会破坏载荷）；
     * 仅当地址为 http(s) 且无内嵌凭据（userinfo）时，非空令牌栏以 `?auth=`
     * 追加（dsh-bridge 手输形态）。
     */
    fun compose(address: String, token: String): String {
        val trimmed = address.trim()
        if (trimmed.isEmpty() || trimmed.contains('|')) return trimmed
        val uri = UrlParserSupport.parseHttpUrl(trimmed) ?: return trimmed
        val credential = token.trim()
        if (credential.isEmpty()) return trimmed
        if (uri.userInfo != null) return trimmed
        return "$trimmed${if (trimmed.contains('?')) "&" else "?"}auth=$credential"
    }
}
