package com.dshbox.remote.session

import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.model.SessionMode
import com.dshbox.remote.model.effectiveSessionMode
import com.dshbox.remote.parse.ParsedEndpoint
import com.dshbox.remote.parse.ParsedPairing
import java.net.URI

/**
 * 重新配对的端点对齐：把新载荷的端点集按主机匹配对齐到既有档案，消除
 * 「多端点载荷只更新触发端点、兄弟端点旧凭据静默失效」的问题。
 *
 * 对齐规则：
 * - 触发端点按 host 匹配载荷中对应端点（无 host 命中时沿用首个载荷端点，
 *   兼容地址整体变更的重配对/编辑语义）；
 * - 同设备其余与载荷解析器同类的端点，host 命中载荷某端点时一并更新
 *   （地址/凭据/CA/通道标签随载荷、代次自增）——同一解析器产出的多通道
 *   端点共享同一份长期凭据，只更新触发端点会让兄弟端点的旧凭据静默失效；
 * - 解析器/通道标签对齐只在凭据真变更时发生（裸地址编辑保留原分类）；
 * - 会话模式按口径取值：显式用户选择（[requestedSessionMode] 非空）优先
 *   于载荷，只作用于触发端点；无显式选择时按 `载荷声明 ?: 端点既有` 取
 *   值；结果一律经 [effectiveSessionMode] 收口（直连要求 https）——存量
 *   `非 https + 直连` 死档在此被修复为代理档，会话层拒绝的档案不再是
 *   死胡同；
 * - 全部候选均无变化返回 null（无变更信号，不是错误）。
 */
internal object RePairAligner {

    /** 对齐产出：更新后的端点清单与需写入存档的凭据/CA、需清除的消费标记。 */
    class Outcome(
        val endpoints: List<Endpoint>,
        /** 发生变更（地址/凭据/CA 任一）的端点标识集合：触发端点 + 同步更新的兄弟端点。 */
        val changedEndpointIds: Set<EndpointId>,
        /** 凭据真变更的端点 → 新凭据（存档按端点键写入）。 */
        val credentialUpdates: Map<EndpointId, String>,
        /** CA 证书变更的端点 → 新证书（Base64 原文）。 */
        val caCertificateUpdates: Map<EndpointId, String>,
        /** 凭据更新即重新配对：消费标记需清除的端点。 */
        val resetConsumed: Set<EndpointId>,
    )

    /**
     * 计算对齐结果。
     *
     * @param endpoints 既有档案端点。
     * @param triggeredId 触发重配对的端点。
     * @param pairing 新载荷解析产出。
     * @param currentCredential 端点当前存档凭据读取。
     * @param currentCaCertificate 端点当前存档 CA 证书读取。
     * @param requestedSessionMode 用户显式会话模式选择（编辑对话框为准入
     *   入口；null = 无显式选择），只作用于触发端点。
     */
    fun align(
        endpoints: List<Endpoint>,
        triggeredId: EndpointId,
        pairing: ParsedPairing,
        currentCredential: (EndpointId) -> String?,
        currentCaCertificate: (EndpointId) -> String?,
        requestedSessionMode: String? = null,
    ): Outcome? {
        if (pairing.endpoints.isEmpty()) return null
        val triggered = endpoints.firstOrNull { it.id == triggeredId } ?: return null
        // 触发端点按 host 匹配载荷端点；无命中沿用首个（地址整体变更语义）
        val triggeredParsed = pairing.endpoints.firstOrNull { parsed ->
            hostOf(parsed.targetUrl) != null && hostOf(parsed.targetUrl) == hostOf(triggered.targetUrl)
        } ?: pairing.endpoints.first()
        val candidates = LinkedHashMap<EndpointId, ParsedEndpoint>()
        candidates[triggeredId] = triggeredParsed
        // 兄弟端点：与载荷解析器同类且 host 命中载荷某端点才一并更新
        endpoints.filter { it.id != triggeredId && it.parserId == pairing.parserId }
            .forEach { sibling ->
                val matched = pairing.endpoints.firstOrNull { parsed ->
                    hostOf(parsed.targetUrl) != null && hostOf(parsed.targetUrl) == hostOf(sibling.targetUrl)
                }
                if (matched != null) candidates[sibling.id] = matched
            }

        var changed = false
        val changedEndpointIds = LinkedHashSet<EndpointId>()
        val credentialUpdates = LinkedHashMap<EndpointId, String>()
        val caCertificateUpdates = LinkedHashMap<EndpointId, String>()
        val resetConsumed = HashSet<EndpointId>()
        val updatedEndpoints = endpoints.map { endpoint ->
            val parsed = candidates[endpoint.id] ?: return@map endpoint
            val credentialChanged = parsed.credential != null &&
                parsed.credential != currentCredential(endpoint.id)
            val caChanged = parsed.caCertificate != null &&
                parsed.caCertificate != currentCaCertificate(endpoint.id)
            val addressChanged = parsed.targetUrl != endpoint.targetUrl
            // 会话模式口径：显式用户选择优先于载荷，且只作用于触发端点；无
            // 显式选择时按 `载荷声明 ?: 端点既有` 取值（载荷声明直连标记的
            // 账号型解析器端点，重配对不得静默掉回代理档；普通 URL 载荷与
            // 裸地址编辑保持端点既有模式）；结果一律经
            // [effectiveSessionMode] 收口（直连要求 https）——既有 `非
            // https + 直连` 死档在同 URL 重确认时即被修复为代理档，会话层
            // 拒绝的档案不再落入死胡同。
            val declaredMode = if (endpoint.id == triggeredId && requestedSessionMode != null) {
                requestedSessionMode
            } else {
                parsed.sessionMode ?: endpoint.sessionMode
            }
            val nextMode = declaredMode?.let { effectiveSessionMode(parsed.targetUrl, it) }
            // 变更判定与有效既有模式比较：档案的会话模式字段可为 null（generic
            // 建档、v3 旧档案且均按默认代理档生效），编辑框透传的非空模式对上
            // 该默认值不构成变更——「什么也不改直接确认」不得把 null→PROXIED
            // 判成模式改动进而推进代次、释放会话
            val previousMode = endpoint.sessionMode ?: SessionMode.PROXIED.name
            val modeChanged = nextMode != null && nextMode != previousMode
            if (!credentialChanged && !caChanged && !addressChanged && !modeChanged) return@map endpoint
            changed = true
            changedEndpointIds.add(endpoint.id)
            if (credentialChanged) {
                credentialUpdates[endpoint.id] = parsed.credential!!
                resetConsumed.add(endpoint.id)
            }
            if (caChanged) caCertificateUpdates[endpoint.id] = parsed.caCertificate!!
            // 凭据/CA 真变更才是重新配对（解析器/通道对齐）；裸地址编辑保留原分类
            val realigned = credentialChanged || caChanged
            endpoint.copy(
                targetUrl = parsed.targetUrl,
                parserId = if (realigned) pairing.parserId else endpoint.parserId,
                channelLabel = if (realigned) parsed.channelLabel else endpoint.channelLabel,
                sessionMode = nextMode,
                generation = endpoint.generation + 1,
            )
        }
        if (!changed) return null
        return Outcome(
            updatedEndpoints,
            changedEndpointIds,
            credentialUpdates,
            caCertificateUpdates,
            resetConsumed,
        )
    }

    /** 目标地址的 host（小写）；不可解析返回 null（不参与 host 匹配）。 */
    private fun hostOf(targetUrl: String): String? =
        runCatching { URI(targetUrl.trim()).host?.lowercase() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
}
