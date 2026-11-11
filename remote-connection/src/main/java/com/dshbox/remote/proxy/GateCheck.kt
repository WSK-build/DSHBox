package com.dshbox.remote.proxy

import com.dshbox.remote.model.DeviceId
import java.security.MessageDigest

/**
 * 回环门禁：防本机其它应用白嫖已认证的代理通道。
 *
 * 门禁 cookie 由 app 侧在会话启动时经渲染器 CookieManager 按设备回环 host
 * 下发（覆盖其全部端点端口）；代理对每个请求校验该 cookie，缺失或值不符
 * 即拒绝；校验后 cookie 不转发上游（门禁语义只在本机回环有意义）。
 *
 * 自愈路径（门禁失效 404 后由会话层重新下发并重试一次）属于显示槽阶段的
 * 会话层逻辑；本类只提供判定与识别，由回环代理管线调用。
 *
 * bypass 模式（[bypass]）：门禁令牌为 null，全部请求放行。用于门禁下发
 * 不可用的渲染引擎（gecko 变体的 cookie 注入路径暂不可用）——该变体以
 * 「无门禁」运行，回环监听本身仍仅本机可达，属已知限制而非安全缺口。
 */
class GateCheck private constructor(private val expectedToken: String?) {

    companion object {
        const val COOKIE_NAME = "dshbox_gate"

        /** 常规模式：携带期望令牌的校验器（构造调用经此重载保持原形态）。 */
        operator fun invoke(expectedToken: String): GateCheck = GateCheck(expectedToken as String?)

        /** 生成新门禁令牌：32 个十六进制字符（128 位随机）。 */
        fun newToken(): String {
            val bytes = ByteArray(TOKEN_LENGTH_BYTES)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** 无门禁校验器：门禁下发不可用的渲染引擎（gecko 变体）的降级路径。 */
        fun bypass(): GateCheck = GateCheck(null)

        private const val TOKEN_LENGTH_BYTES = 16
    }

    /** 门禁判定结果。 */
    enum class GateDecision { VALID, MISSING, INVALID }

    /**
     * 校验请求携带的门禁 cookie（any-match 语义）。
     *
     * 请求可能携带多个同名门禁 cookie（上游响应曾种植错误值、渲染器罐里
     * 新旧值并存的场景）：只要其中任一与期望令牌相等即放行，不能只看第一
     * 个出现的值。令牌比较走 [MessageDigest.isEqual] 常量时间路径，长度
     * 不等时同样安全。
     *
     * @param cookieHeaderValues 请求全部 `Cookie` 头的值（可多头）。
     */
    fun evaluate(cookieHeaderValues: List<String>): GateDecision {
        if (expectedToken == null) return GateDecision.VALID // bypass：无门禁模式全放行
        val gateValues = cookieHeaderValues
            .asSequence()
            .flatMap { it.split(';') }
            .map { it.trim() }
            .filter { it.startsWith("$COOKIE_NAME=") }
            .map { it.substringAfter('=') }
            .toList()
        if (gateValues.isEmpty()) return GateDecision.MISSING
        val expected = expectedToken.toByteArray(Charsets.UTF_8)
        val matched = gateValues.any { candidate ->
            MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), expected)
        }
        return if (matched) GateDecision.VALID else GateDecision.INVALID
    }
}

/** 门禁令牌的设备级存取：令牌入凭据存档，重启后保持稳定（避免旧渲染器 cookie 失效）。 */
class GateTokenStore(private val vault: com.dshbox.remote.session.CredentialVault) {

    /** 读取设备门禁令牌；首次访问生成并持久化。 */
    fun tokenFor(deviceId: DeviceId): String {
        val existing = vault.loadCredentials(deviceId)[CREDENTIAL_KEY]
        if (existing != null) return existing
        val token = GateCheck.newToken()
        vault.saveCredentials(
            deviceId,
            vault.loadCredentials(deviceId) + (CREDENTIAL_KEY to token),
        )
        return token
    }

    private companion object {
        const val CREDENTIAL_KEY = "gateToken"
    }
}
