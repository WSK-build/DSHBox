package com.dshbox.remote.session

/**
 * 会话首次加载地址的解析：一次性配对凭据（配对票 fragment、邀请 URL、
 * ticket query）只在未消费时参与首载——已兑换出的会话 cookie 由代理罐
 * 自动注入，把消费过的票重新带回配对页只会失败。消费后的回落按解析器
 * 区分：网关与 anonRT 配对页回环根；通用通道保留应用入口路径（剥票据
 * query 与 fragment）。
 */
object SessionInitialLoad {

    /** 回环网关类配对页路径。 */
    private const val GATEWAY_PAIR_PATH = "/pair"

    /**
     * 解析端点会话的首次加载完整地址。
     *
     * @param parserId 产出端点的解析器标识。
     * @param loopbackOrigin 代理就绪后的回环 origin。
     * @param credential 端点凭据（完整配对 URL / 邀请 URL / token）；null = 无凭据。
     * @param consumed 凭据是否已消费（兑换完成或判定失效）。
     * @return 完整首载地址；null = 加载裸回环 origin 根。
     */
    fun resolve(parserId: String, loopbackOrigin: String, credential: String?, consumed: Boolean): String? {
        if (credential == null) return null
        return when (parserId) {
            // 配对 secret 只存在于页面地址 fragment：由首载地址携带，页面 JS
            // 读取完成兑换（fragment 不进上游请求、不落明文存档）
            com.dshbox.remote.parse.ParserIds.GATEWAY -> {
                // 已消费的票不再带回配对页：回环根（已兑换出的会话 cookie 由代理罐注入）
                if (consumed) return null
                val uri = runCatching { java.net.URI(credential) }.getOrNull() ?: return null
                val fragment = uri.rawFragment ?: return null
                loopbackOrigin + (uri.rawPath?.takeIf { it.isNotEmpty() } ?: GATEWAY_PAIR_PATH) + "#" + fragment
            }
            // anonRTtty 配对页：票据经首载地址携带（路径 /pair + ticket query
            // 重建到回环 origin）。已消费后回落回环根：无票访问 /pair 不能保证
            // 渲染应用入口，与 gateway 同口径。
            com.dshbox.remote.parse.ParserIds.ANON_RT -> {
                if (consumed) return null
                val uri = runCatching { java.net.URI(credential) }.getOrNull() ?: return null
                if (uri.host.isNullOrBlank()) return null
                rebuildOnLoopback(loopbackOrigin, uri, consumed)
            }
            // 一次性 ticket 在 query 的通用通道：把凭据 URL 的路径与 query
            // 重建到回环 origin 上作为首载地址（渲染器只加载回环 origin；路径
            // 与 query 经代理原样转发上游）。已消费后保留应用入口路径，剥离
            // 票据 query 与 fragment
            com.dshbox.remote.parse.ParserIds.GENERIC -> {
                val uri = runCatching { java.net.URI(credential) }.getOrNull() ?: return null
                if (uri.host.isNullOrBlank()) return null
                rebuildOnLoopback(loopbackOrigin, uri, consumed)
            }
            // dsh-full-remote 的邀请 URL 由注入器改写首请求（非首载地址）；
            // dsh-bridge 的 token 由注入器追加；dsh-pocket 无凭据
            else -> null
        }
    }

    /** 凭据 URL 的路径与（未消费时的）query/fragment 重建到回环 origin 上。 */
    private fun rebuildOnLoopback(loopbackOrigin: String, uri: java.net.URI, consumed: Boolean): String =
        buildString {
            append(loopbackOrigin)
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            if (!consumed) {
                if (uri.rawQuery != null) append('?').append(uri.rawQuery)
                if (uri.rawFragment != null) append('#').append(uri.rawFragment)
            }
        }
}
