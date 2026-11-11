package com.dshbox.app.lanaccess

import java.net.URI

/**
 * 上游寻址与请求改写的纯函数族（JVM 单测锁定）。
 *
 * 上游永远是本机 DSH 的**回环地址**：**每次转发都现读**当前 DSH 基础地址
 * （端口自适应后重启落新端口，网关不解绑、不重启即可跟随）；host 一律
 * 强制收敛为 `127.0.0.1`（硬边界：局域网访客的转发目的只允许本机，地址
 * 配置漂移到非回环值也不外投）。请求在服务端转发前给 target 补上当前
 * DSH launch token —— DSH 以 `?token=` 鉴权，局域网页面地址栏与网络日志
 * 不携带该 token（在网关侧注入，由转发完成认证）。
 */
object LanUpstream {

    /** 未确证 DSH 地址时的回退端口（与 Constants.DSH_DEFAULT_PORT 同值口径）。 */
    const val DSH_FALLBACK_PORT = 3080

    /** DSH 鉴权 token 的 query 参数名。 */
    const val TOKEN_PARAM = "token"

    /**
     * DSH 浏览器会话 cookie 的**名称前缀**（取证口径：DSH 0.2.1-rc.5 的
     * `@deepseek-ai/dsh-client-connection` `COOKIE_PREFIX` 常量为
     * `dsh-auth-`，完整名称 = 前缀 + `base64url(sha256(authority))`，
     * authority 是 Host 头文本——名称是派生值，网关不可假定完整名，只按
     * 前缀识别「客户端已持有上游会话」）。
     */
    const val DSH_SESSION_COOKIE_PREFIX = "dsh-auth-"

    data class Upstream(
        val host: String,
        val port: Int,
    ) {
        /** Host 头值（默认端口省略口径同 RequestRewriter）。 */
        val hostHeader: String get() = if (port == 80) host else "$host:$port"

        /** 上游 origin 文本。 */
        val originText: String get() = "http://$hostHeader"
    }

    /**
     * 解析当前 DSH 基础地址为上游定位；**host 强制为 `127.0.0.1`**（非回环
     * 来源地址不采信，见类 KDoc），端口取自地址（空白/非法/越界一律回退
     * 默认 [DSH_FALLBACK_PORT]）。**每次请求都调用本函数拿现值**——端口
     * 自适应即可跟随，无需重启网关。
     */
    fun upstreamOf(baseUrl: String?): Upstream {
        val uri = runCatching { URI(baseUrl?.trim() ?: "") }.getOrNull()
        val port = uri?.port?.takeIf { it in 1..65535 }
        return Upstream(
            host = "127.0.0.1",
            port = port ?: DSH_FALLBACK_PORT,
        )
    }

    /**
     * 请求是否已携带 DSH 会话 cookie（任一 Cookie 头内任一子项的名称以
     * [DSH_SESSION_COOKIE_PREFIX] 开头）。比较按 DSH 自身实现的字节级精确
     * 口径（区分大小写——DSH 的 cookie 读取按名称逐段截取，不做大小写
     * 归一）。
     */
    fun hasUpstreamSessionCookie(cookieHeaderValues: List<String>): Boolean =
        cookieHeaderValues.any { headerValue ->
            headerValue.split(';').any { segment ->
                segment.trim().startsWith(DSH_SESSION_COOKIE_PREFIX)
            }
        }

    /**
     * 在 origin-form 请求目标/绝对 URL 上剥除一组 query 参数（值不问——
     * `?k=` 空值同样命中；fragment 原样保留）：转发前剥门禁密钥（`k`）、
     * Location 回写前剥注入令牌（`token`）与门禁密钥（`k`）的硬化出口。
     * 无 query / 无命中原样返回；同名参数若出现多次全部剥除；剥空后
     * `?` 与空 query 一并不留。
     */
    fun stripParams(target: String, names: Set<String>): String {
        val questionAt = target.indexOf('?')
        if (questionAt < 0) return target
        val path = target.substring(0, questionAt)
        val fragment = target.substringAfter('#', "")
        val queryBody = target.substring(questionAt + 1).let {
            if (fragment.isEmpty()) it else it.removeSuffix("#$fragment")
        }
        val kept = queryBody.split('&')
            .filter { it.isNotEmpty() }
            .filterNot { item ->
                // 带值形态（`k=`）与裸键形态（`k`）都按名剥除
                names.any { name -> item.startsWith("$name=") || item == name }
            }
        val fragmentPrefix = if (fragment.isEmpty()) "" else "#$fragment"
        return if (kept.isEmpty()) {
            "$path$fragmentPrefix"
        } else {
            "$path?${kept.joinToString("&")}$fragmentPrefix"
        }
    }

    /**
     * 从 Cookie 头值集合里解析出所有 `dsh-auth-` 前缀**会话 cookie 的完整
     * 名**（去重保序；无 `=` 的裸名段也算一枚 cookie 名）：失效会话自愈回
     * `Max-Age=0` 时按名清除，完整名是派生值（前缀 +
     * base64url(sha256(authority))），网关不可假定，只能从客户端实际携带
     * 的 cookie 里取。
     */
    fun dshSessionCookieNames(cookieHeaderValues: List<String>): List<String> {
        val names = LinkedHashSet<String>()
        cookieHeaderValues.forEach { headerValue ->
            headerValue.split(';').forEach { segment ->
                val trimmed = segment.trim()
                if (trimmed.startsWith(DSH_SESSION_COOKIE_PREFIX)) {
                    // 无 `=` 时 substringBefore 取回整段（即裸 cookie 名）
                    trimmed.substringBefore('=')
                        .takeIf { it.isNotEmpty() }
                        ?.let { names.add(it) }
                }
            }
        }
        return names.toList()
    }

    /** 请求 Cookie 头里是否携带指定**完整名**的 cookie（子项精确名匹配）。 */
    fun hasCookieNamed(cookieHeaderValues: List<String>, cookieName: String): Boolean =
        cookieHeaderValues.any { headerValue ->
            headerValue.split(';').any { segment ->
                val trimmed = segment.trim()
                trimmed == cookieName || trimmed.startsWith("$cookieName=")
            }
        }

    /**
     * 在 origin-form 请求目标上注入当前 token（替换既有 token，保留其它
     * query 参数与路径）。token 空/白时不注入（无认证的 DSH 版本）。
     *
     * **注入条件由调用方把关：仅当请求未携带 DSH 会话 cookie
     * （[hasUpstreamSessionCookie] 为 false）时才调用本函数。**依据（DSH
     * 0.2.1-rc.5 取证）：`authorizeIndex` 对**任何带 token** 的 `GET /`
     * 无条件 303 `./`——匹配 token 的首跳 A 分支回 Set-Cookie 与重定向后
     * 直接 return，不看请求是否已认证；即使把已认证会话的请求再带上
     * token，也落进 B 分支的 303 `./`。若已带会话 cookie 的请求仍注入
     * token，客户端将在 `/ → 303 ./ → /` 间无限重定向，页面永远打不开；
     * 带 cookie 即不注入：DSH 按 cookie 直接放行 200。带 cookie 的兑换
     * 值机只发生在首跳（DSH 自己在 303 里 `Set-Cookie: dsh-auth-*`），
     * 每客户端会话因此自然收敛为一次兑换。
     *
     * 注入发生在**网关内部**：请求头与 URL 均为转发前改写，局域网侧的
     * 页面地址栏与请求资源 URL 均不携带 token。
     */
    fun originFormWithToken(target: String, token: String?): String {
        if (token.isNullOrEmpty()) return target
        val questionAt = target.indexOf('?')
        if (questionAt < 0) return "$target?$TOKEN_PARAM=$token"
        val path = target.substring(0, questionAt)
        val kept = target.substring(questionAt + 1)
            .split('&')
            .filter { it.isNotEmpty() && !it.startsWith("$TOKEN_PARAM=") }
        return "$path?" + (kept + "$TOKEN_PARAM=$token").joinToString("&")
    }

    /**
     * 从入站头部列表剥离网关自有的门禁 cookie 集合（门禁值与防循环标记，
     * ACA 语义：局域网门禁语义只在本机到网关这一跳有意义，不应继续流向
     * DSH 上游）。同名的 DSH 会话 cookie 与其它键保留——上游认证按现状
     * 走。
     */
    fun stripGatewayCookie(values: List<String>, cookieNames: Set<String>): List<String> =
        values.map { headerValue ->
            headerValue.split(';')
                .mapNotNull { pair ->
                    val trimmed = pair.trim()
                    // 丢弃以 "<name>=" 起头或整键名为 "<name>" 的耦合子项
                    val isNamed = cookieNames.any { name ->
                        trimmed.equals(name, ignoreCase = true) ||
                            trimmed.startsWith("$name=")
                    }
                    if (isNamed) null else trimmed.takeIf { it.isNotEmpty() }
                }
                .joinToString("; ")
        }
}
