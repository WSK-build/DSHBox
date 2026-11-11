package com.dshbox.remote.model

/**
 * 设备与端点的数据模型：一台已配对的远程设备可并列多条接入通道（端点），
 * 每条端点可独立选为显示源。此处仅固化结构，赋值与持久化由建档链路（
 * 会话内核（配对解析与设备存档）填充。
 */

/** 设备稳定标识：由配对解析阶段生成，删除设备前终身不变。 */
@JvmInline
value class DeviceId(val value: String)

/** 端点稳定标识：建档时生成，全局唯一不复用（在途建立的存活判定依赖标识与代次，复用会误伤新设备）。 */
@JvmInline
value class EndpointId(val value: String)

/**
 * 一台已配对的远程设备：一次扫码/手动输入产出，可含多条端点。
 *
 * 设备条目是会话隔离的记账单位：登录态（代理 cookie jar 与凭据）随设备存档，
 * 删除设备即一并销毁。
 */
data class Device(
    val id: DeviceId,
    /** 设备显示名：默认取自配对信息，用户可改名。 */
    val name: String,
    /** 并列接入通道：局域网/公网/隧道等多通道共存，各自可设为显示源。 */
    val endpoints: List<Endpoint>,
    /** 添加时间（epoch 毫秒）：清单排序与展示用。 */
    val addedAtMillis: Long,
)

/**
 * 设备的一条接入通道：一个可建立的页面来源。
 *
 * 目标地址可经重新扫码/编辑更新（隧道域名易变是普遍现实），更新不改变
 * 设备条目与端点身份，但自增代次：在途建立捕获旧代次，回填时档案代次已
 * 漂移的建立结果一律废弃（旧建立基于过期目标，不得挂到新档案上）。
 */
data class Endpoint(
    val id: EndpointId,
    /** 通道标签（局域网/公网/隧道等）：来自配对信息或用户备注。 */
    val channelLabel: String,
    /** 目标地址：上游插件页面的基础 URL。 */
    val targetUrl: String,
    /** 产出本端点的配对解析器标识：决定会话的注入器行为。 */
    val parserId: String,
    /** 端点代次：建档时归零，地址编辑自增；在途建立的存活判定依据。 */
    val generation: Long = 0,
    /**
     * 端点级保留档覆盖（[com.dshbox.remote.model.toArchiveValue] 的编码值）：
     * null = 继承全局默认档。
     */
    val retentionOverride: String? = null,
    /**
     * 端点会话模式（[SessionMode] 的存储枚举名）：null = 默认 PROXIED
     * （回环代理承载），DIRECT = 直连（渲染器直接加载真实上游 origin）。
     * 旧档案读入按 null 补齐，不使既有设备掉档。
     */
    val sessionMode: String? = null,
)

/**
 * 端点会话模式：代理承载 or 渲染器直连。
 *
 * 存储为枚举名（档案 v4 起的端点第七字段，空串 = PROXIED）。
 */
enum class SessionMode {
    /** 回环代理承载（默认）：注入器/门禁/下载等代理能力全量可用。 */
    PROXIED,

    /**
     * 直连：不建回环代理，渲染器直接加载真实上游 origin；门禁与代理侧
     * 注入不适用，主框架导航按 AnyHttps 口径放宽。
     */
    DIRECT,
}

/**
 * 配对建档的归属目标：新端点确认时归属到哪台设备。
 *
 * 设备分组只复用既有档案字段（[Device.name]），不新增 schema：
 * - [Existing]：并入既有设备（该设备的凭据/登录态继续沿用）；
 * - [Ungrouped]：并入名称为空串的「无分组」设备（没有则现建一条），
 *   列表中排在所有命名分组之后、不渲染设备头，仅呈现端点行；
 * - [NewGroup]：以给定名称新建设备（排在清单最后），即既有建档行为。
 */
sealed interface PairingTarget {

    /** 并入既有设备。 */
    data class Existing(val deviceId: DeviceId) : PairingTarget

    /** 并入「无分组」设备（name 为空串的档案条目，没有则现建）。 */
    data object Ungrouped : PairingTarget

    /** 新建设备分组（名称可空 = 无分组语义由建档出口兜底为空名条目）。 */
    data class NewGroup(val name: String?) : PairingTarget
}

