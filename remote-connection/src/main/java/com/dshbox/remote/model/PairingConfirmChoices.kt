package com.dshbox.remote.model

import com.dshbox.remote.parse.ParsedPairing

/**
 * 单条端点的确认卡选择：建档确认前用户对该端点选择的两项落档值。
 *
 * - [sessionMode]：端点会话模式（[SessionMode] 存储枚举名）。默认值由
 *   解析产出的端点预填（解析器声明的直连端点直接带 DIRECT；其余端点经
 *   [effectiveSessionMode] 收口——非 https 地址回落代理档）。
 * - [retentionOverride]：端点级保留档覆盖（[RetentionPolicy] 档案编码值）。
 *   null = 继承全局默认（UI 显示「继承(全局档位)」），不做解析期预填。
 *
 * 纯模型（无 Android 依赖），预填推导与回退策略由纯函数给出，JVM 单测锁定。
 */
data class EndpointChoice(
    /** 端点会话模式（[SessionMode] 存储枚举名）。 */
    val sessionMode: String,
    /** 端点级保留档覆盖（[toArchiveValue] 编码值）；null = 继承全局默认档。 */
    val retentionOverride: String?,
)

/**
 * 一次配对载荷的全部端点选择（数组序与解析产出的端点顺序一一对应）：
 * 确认卡确认时随建档一次性提交。
 */
data class PairingConfirmChoices(
    /** 各端点的选择（数组序与解析产出的端点顺序一一对应）。 */
    val endpoints: List<EndpointChoice>,
)

/**
 * 解析产出 → 确认卡默认选择（纯函数）：每条端点的预填值按「载荷声明经
 * 直连口径收口；保留档一律继承全局（null）」推导。UI 的 chips 以此为初
 * 始选中态，用户可改；确认时选择原样落档，收口已在推导时完成。
 */
internal fun defaultConfirmChoices(parsed: ParsedPairing): PairingConfirmChoices =
    PairingConfirmChoices(
        endpoints = parsed.endpoints.map { endpoint ->
            // 载荷声明的模式先按直连口径收敛（非 https 目标回落代理档）后
            // 作为默认档；保留档一律继承全局（retentionOverride = null）
            val declared = endpoint.sessionMode ?: SessionMode.PROXIED.name
            EndpointChoice(
                sessionMode = effectiveSessionMode(endpoint.targetUrl, declared),
                retentionOverride = null,
            )
        },
    )

/**
 * 用户选择与解析端点的数量核对（纯函数，回退策略收口的生产入口）：选择
 * 条数与解析端点数一致时逐条生效；不一致（载荷变化/旧选择复用）时按
 * 「选择前缀 + 解析默认值补齐」处理——多余的选择项丢弃、不足的缺项用解
 * 析默认值填充。null 选择（未收集到确认卡数据）按全部默认值处理。绝不把
 * 错位的选择写到端点上。
 *
 * 控制器的建档入口在解析后、落档前调用本函数补齐选择；建档落地层另有
 * 直连口径收口作第二道防线（两道同用 [effectiveSessionMode] 口径）。
 */
internal fun reconcileConfirmChoices(
    parsed: ParsedPairing,
    choices: PairingConfirmChoices?,
): List<EndpointChoice> {
    val defaults = defaultConfirmChoices(parsed).endpoints
    if (choices == null) return defaults
    val provided = choices.endpoints
    if (provided.size == parsed.endpoints.size) return provided
    // 错位取前缀 + 默认值补齐：多余丢弃（不追加到后面的端点），缺失用默认
    return parsed.endpoints.mapIndexed { index, _ ->
        provided.getOrNull(index) ?: defaults[index]
    }
}
