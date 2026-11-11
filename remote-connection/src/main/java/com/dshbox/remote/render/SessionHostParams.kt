package com.dshbox.remote.render

/**
 * 渲染器宿主的组装参数（显示槽按会话代次生成一次的快照）。
 *
 * @param initialLoadUrl 首次加载完整地址；null = 加载裸回环 origin 根。
 * @param sessionUsesConsumableCredential 会话是否以一次性凭据启动（消费门控前提）。
 * @param pairingCredentialPending 端点凭据是否未消费（401/403 失败引导判定；
 *   作为宿主可变属性的初值，组装后的变化经 update 推进）。
 * @param initialLoadCarriesTicket 首载地址是否携带未消费一次性票据（刷新与
 *   重建的回落分流依据）。
 */
internal data class SessionHostParams(
    val initialLoadUrl: String?,
    val sessionUsesConsumableCredential: Boolean,
    val pairingCredentialPending: Boolean,
    /**
     * 首载地址是否携带未消费一次性票据（gateway fragment / generic query）：
     * 仅票据型首载参与「首帧成功后回落裸 origin」的降级——已消费端点的路径
     * 型首载（generic 应用入口）在刷新与崩溃重建时保持不变。
     */
    val initialLoadCarriesTicket: Boolean,
)

/**
 * 渲染器宿主参数的唯一组装点（纯函数，JVM 契约测试锁定）：控制器提供的
 * 首载地址与一次性凭据会话标志在此合成——full-remote 的注入式凭据会话
 * 组合为「无首载地址 + 消费门控前提成立」；与回环 origin 相同的首载地址
 * 视为无独立首载。
 */
internal fun buildSessionHostParams(
    loopbackOrigin: String,
    controllerInitialLoadUrl: String?,
    sessionUsesConsumableCredential: Boolean,
    pairingCredentialPending: Boolean,
): SessionHostParams = SessionHostParams(
    initialLoadUrl = controllerInitialLoadUrl?.takeIf { it != loopbackOrigin },
    sessionUsesConsumableCredential = sessionUsesConsumableCredential,
    pairingCredentialPending = pairingCredentialPending,
    // 票据型首载 = 会话以未消费一次性凭据启动且存在独立首载地址（消费后
    // 的 generic 路径型首载与 full-remote 的无首载都不参与降级）
    initialLoadCarriesTicket = sessionUsesConsumableCredential && controllerInitialLoadUrl != null,
)
