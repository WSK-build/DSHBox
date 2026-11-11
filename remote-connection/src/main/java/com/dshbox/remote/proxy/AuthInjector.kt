package com.dshbox.remote.proxy

import com.dshbox.remote.parse.ParserIds
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 会话的鉴权注入器：把端点凭据带进上游请求（按产出端点的配对解析器决定
 * 注入行为：query 追加、引导 URL 改写或透明直通）。
 *
 * 注入发生在门禁校验与入站 Cookie 剥离之后、出站头改写之前：注入器只操作
 * 请求目标与请求头，凭据明文只存在于注入器会话状态与凭据存档中，零日志。
 * 响应侧经 [onResponse] 观察上游 Set-Cookie（凭据兑换完成的信号，供「检出
 * 会话 cookie 后停止注入」类状态机收敛）。
 */
interface AuthInjector {

    /**
     * 改写一个即将转发上游的请求。
     *
     * @param request 请求上下文（目标与可变头列表）。
     * @param state 会话级注入器状态（文档请求记账与凭据兑换信号）。
     */
    fun transformRequest(request: ProxyRequest, state: InjectorState)

    /**
     * 上游响应的观察入口（改写入站头阶段调用）：[upstreamSetCookies] 为本
     * 响应的原始 Set-Cookie 头。默认空实现——只有需要「兑换完成后停止注入」
     * 的注入器覆写。
     */
    fun onResponse(upstreamSetCookies: List<String>, state: InjectorState) {}
}

/** 无注入的通用实现：dsh-pocket、SnowfallC、mrRisega 等通用 URL 通道使用。 */
object PassThroughInjector : AuthInjector {

    override fun transformRequest(request: ProxyRequest, state: InjectorState) {
        // 透明转发：不触碰请求头与目标。
    }
}

/**
 * 按解析器标识选取注入器。凭据缺失时透明直通；一次性票已消费时不再重发
 * 凭据（但 saya-ch 仍按端点会话注入 CSRF，消费标记不等于完全透明）。
 */
object AuthInjectors {

    /** dsh-bridge 的会话 cookie 名：兑换完成的观察信号。 */
    const val BRIDGE_AUTH_COOKIE = "dsh_bridge_auth"

    /** dsh-remote-web-gateway 的配对页路径（会话首次加载地址由它构成）。 */
    const val GATEWAY_PAIR_PATH = "/pair"

    /**
     * 选取注入器。
     *
     * 一次性票解析器（gateway/full-remote/generic/anonRT/saya-ch）的票在
     * 兑换完成后标记消费；消费标记只表示「不再重发票」，不得禁用兑换产物
     * 的注入——saya-ch 的 CSRF 分支因此排在消费降级之前（写请求持续需要）。
     *
     * @param credential 端点凭据（完整配对 URL / 邀请 URL / 一次性票）；null = 无凭据。
     * @param credentialConsumed 一次性凭据是否已消费（消费后回配对页只会失败）。
     * @param sessionAlreadyAuthenticated 会话罐里已持有该上游的认证会话
     *   cookie（重连场景）：不再重发凭据（token 不进上游日志），由罐注入。
     * @param sayaCsrf saya-ch 通道兑换出的 CSRF 令牌（写请求的
     *   `x-dsh-mobile-csrf` 头值）；null/空白 = 未兑换，注入器透明。
     */
    fun forParser(
        parserId: String,
        credential: String?,
        credentialConsumed: Boolean = false,
        sessionAlreadyAuthenticated: Boolean = false,
        sayaCsrf: String? = null,
    ): AuthInjector = when {
        credential == null -> PassThroughInjector
        // saya-ch / dsh-mobile 网关：认证走会话 cookie（兑换后由代理罐统一
        // 注入），注入器只补写请求的 CSRF 头（写操作强制）。必须排在一次性
        // 凭据的消费降级之前：consumed 只表示不再重发一次性票（兑换已完成），
        // 不是「会话无效」——CSRF 对整个会话持续必需，缺了写请求全部 403。
        // 无 CSRF（未兑换/缺档）时透明直通。
        parserId == ParserIds.SAYA_CH ->
            if (!sayaCsrf.isNullOrBlank()) SayaChSessionInjector(sayaCsrf) else PassThroughInjector
        credentialConsumed -> PassThroughInjector
        parserId == ParserIds.BRIDGE ->
            if (sessionAlreadyAuthenticated) PassThroughInjector else DshBridgeAuthInjector(credential)
        parserId == ParserIds.FULL_REMOTE ->
            if (sessionAlreadyAuthenticated) PassThroughInjector else DshFullRemoteInviteInjector(credential)
        parserId == ParserIds.GATEWAY ->
            if (sessionAlreadyAuthenticated) PassThroughInjector else DshRemoteWebGatewayPairingInjector(credential)
        // dsh-Remote scheme / ChongYep 的长期 Bearer 令牌：有凭据即随会话逐
        // 请求注入。两者为长期令牌（非一次性票，不进消费型解析器集合），
        // credentialConsumed 恒为 false，不做兑换观察
        parserId == ParserIds.REMOTE_SCHEME || parserId == ParserIds.CHONG_YEP ->
            BearerTokenInjector(credential)
        // dsh-pocket / generic-url 等：代理透明（一次性 ticket 走会话首次
        // 加载地址携带；消费记账在渲染器宿主的干净首帧提交，见渲染层）
        else -> PassThroughInjector
    }
}

/**
 * dsh-bridge 注入器：请求目标追加 `?auth=<token>`，检出上游 Set-Cookie 种
 * 下会话 cookie（兑换完成）后停止追加，其后由代理罐自动注入。
 *
 * 停止前对全部请求追加（而非仅首个文档请求）：兑换完成前的并发子资源请求
 * 同样需要凭据，上游按请求校验，缺凭据的请求会被拒；目标已自带 auth 参数
 * （键名精确匹配）时不再追加（页面自身携带的凭据优先）。
 */
class DshBridgeAuthInjector(private val token: String) : AuthInjector {

    override fun transformRequest(request: ProxyRequest, state: InjectorState) {
        if (state.credentialExchanged) return
        if (QueryParamSupport.hasQueryParam(request.target, AUTH_PARAM)) return
        request.target = QueryParamSupport.appendQueryParam(request.target, AUTH_PARAM, token)
    }

    override fun onResponse(upstreamSetCookies: List<String>, state: InjectorState) {
        if (upstreamSetCookies.any { it.startsWith("${AuthInjectors.BRIDGE_AUTH_COOKIE}=") }) {
            state.markCredentialExchanged()
        }
    }

    private companion object {
        const val AUTH_PARAM = "auth"
    }
}

/** 请求目标的查询参数工具（注入器共用）：键存在判定与参数追加。 */
internal object QueryParamSupport {

    /** 目标 query 是否携带同名键（键名精确匹配，值不为空）。 */
    fun hasQueryParam(target: String, name: String): Boolean =
        target.substringAfter('?', "")
            .substringBefore('#')
            .split('&')
            .filter { it.contains('=') }
            .any { it.substringBefore('=').equals(name, ignoreCase = true) }

    /** 请求目标追加查询参数（已有 query 用 & 连接；请求目标无 fragment）。 */
    fun appendQueryParam(target: String, name: String, value: String): String =
        "$target${if (target.contains('?')) "&" else "?"}$name=$value"
}

/**
 * dsh-full-remote 注入器：首个文档请求改用存档的完整邀请 URL（目标路径与
 * query 取自凭据），其后透明——会话 cookie 由代理罐在邀请兑换时接住。
 */
class DshFullRemoteInviteInjector(private val inviteUrl: String) : AuthInjector {

    override fun transformRequest(request: ProxyRequest, state: InjectorState) {
        if (state.documentRequestSeen) return
        if (!state.markDocumentRequestSeen()) return
        val uri = runCatching { URI(inviteUrl) }.getOrNull() ?: return
        request.target = buildString {
            append(uri.rawPath ?: "/")
            if (uri.rawQuery != null) append('?').append(uri.rawQuery)
        }
    }
}

/**
 * dsh-remote-web-gateway 注入器：首个文档请求改写为配对页路径（取自凭据）。
 *
 * 已接受的链路例外：配对 secret 只存在于页面地址 fragment，由页面 JS 读取
 * 完成兑换——fragment 不进上游请求（HTTP 语义也不携带），会话首次加载地址
 * 携带它（显示槽从会话句柄取初始加载地址），代理与存档均不透传明文。
 */
class DshRemoteWebGatewayPairingInjector(private val pairingUrl: String) : AuthInjector {

    override fun transformRequest(request: ProxyRequest, state: InjectorState) {
        if (state.documentRequestSeen) return
        if (!state.markDocumentRequestSeen()) return
        val uri = runCatching { URI(pairingUrl) }.getOrNull()
        val path = uri
            ?.takeIf { it.scheme?.lowercase() in listOf("http", "https") && !it.host.isNullOrBlank() }
            ?.rawPath
            ?.takeIf { it.isNotEmpty() && it.startsWith("/") }
        request.target = path ?: AuthInjectors.GATEWAY_PAIR_PATH
    }
}

/**
 * Bearer 令牌注入器（dsh-Remote scheme / ChongYep 手输令牌）：全部请求的
 * Authorization 头置为 `Bearer <token>`（覆盖渲染器侧已带的同名头）。令牌
 * 为长期凭据：上游按请求校验、缺头即拒，不做「兑换完成后停止」的收敛。
 */
class BearerTokenInjector(private val token: String) : AuthInjector {

    override fun transformRequest(request: ProxyRequest, state: InjectorState) {
        request.setHeader(AUTHORIZATION_HEADER, "$BEARER_SCHEME$token")
    }

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_SCHEME = "Bearer "
    }
}

/**
 * dsh-mobile 网关会话注入器（saya-ch 通道）：会话 cookie 由代理罐统一注入
 * （兑换产物在建会话时写入罐），注入器只负责 CSRF——网关对写请求（非
 * GET/HEAD）强制 `x-dsh-mobile-csrf` 头（值 = 兑换出的 csrfToken），读请求
 * 不设。不注入 Bearer、不追加查询参数、不改请求目标；CSRF 为空保持透明。
 */
class SayaChSessionInjector(csrfToken: String) : AuthInjector {

    @Volatile
    private var csrfToken: String = csrfToken

    /** native-renew 成功后更新 CSRF，后续请求立即使用新值。 */
    fun updateCsrfToken(token: String) {
        csrfToken = token
    }

    /**
     * 运行期 CSRF 来源（入参为请求目标）：页面自身触发的续期
     * （`/mobile-access/auth/renew`）会换新 CSRF，罐内 cookie 先于本注入器
     * 的静态值收敛。提供后每次写请求按罐内现值取值，取不到才退回静态值。
     */
    @Volatile
    var csrfProvider: ((String) -> String?)? = null

    override fun transformRequest(request: ProxyRequest, state: InjectorState) {
        val token = csrfProvider?.invoke(request.target)?.takeIf { it.isNotBlank() } ?: csrfToken
        if (token.isBlank()) return
        if (request.method == "GET" || request.method == "HEAD") return
        request.setHeader(CSRF_HEADER, token)
    }

    private companion object {
        const val CSRF_HEADER = "x-dsh-mobile-csrf"
    }
}

/** 代理内转发中的请求视图：目标与头列表可被注入器与改写器原地修改。 */
class ProxyRequest(
    val method: String,
    /** 请求目标（origin-form，如 `/path?q=1`）。 */
    var target: String,
    /** 请求头：保序、允许重复（Set-Cookie/Cookie 等多头语义）。 */
    val headers: MutableList<ProxyHeader>,
) {
    /** 读取首个同名头的值。 */
    fun firstHeader(name: String): String? =
        headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    /** 设置同名头的唯一值：替换全部已有同名头（不存在则追加）。 */
    fun setHeader(name: String, value: String) {
        headers.removeAll { it.name.equals(name, ignoreCase = true) }
        headers.add(ProxyHeader(name, value))
    }

    /** 删除全部同名头。 */
    fun removeHeaders(name: String) {
        headers.removeAll { it.name.equals(name, ignoreCase = true) }
    }
}

/** 一个请求/响应头。 */
data class ProxyHeader(val name: String, val value: String)

/** 会话级注入器状态：随代理实例持有；跨连接线程并发访问，全部原子。 */
class InjectorState {

    private val documentSeen = AtomicBoolean(false)

    private val credentialExchangedFlag = AtomicBoolean(false)

    /** 是否已注入过首个文档请求（「首个文档请求注入、其后透明」类行为记账）。 */
    val documentRequestSeen: Boolean get() = documentSeen.get()

    /** 凭据已兑换完成（上游已种下会话 cookie）：注入器据此停止携带凭据。 */
    val credentialExchanged: Boolean get() = credentialExchangedFlag.get()

    /** 登记首个文档请求：仅首个并发调用胜出（CAS），胜出方执行注入。 */
    fun markDocumentRequestSeen(): Boolean = documentSeen.compareAndSet(false, true)

    /** 登记凭据兑换完成。 */
    fun markCredentialExchanged() {
        credentialExchangedFlag.set(true)
    }
}
