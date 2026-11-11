package com.dshbox.remote.parse

/**
 * 配对载荷的形态分类（两级路由的第一级）。
 */
enum class PayloadForm {
    /** http/https 链接：进入第二级签名路由。 */
    URL,

    /** 自定义 scheme（dshremote:// 等）。 */
    CUSTOM_SCHEME,

    /** JSON 描述符。 */
    JSON,

    /** 竖线分隔文本。 */
    DELIMITED_TEXT,

    /** 无法识别的形态：显式报错（输出经脱敏）。 */
    UNKNOWN,
}

/** 配对解析产出的一个接入通道。 */
data class ParsedEndpoint(
    /** 上游目标地址（基础 URL）。 */
    val targetUrl: String,
    /** 通道标签（局域网/公网/隧道等）。 */
    val channelLabel: String,
    /** 随载荷出现的端点凭据：只入凭据存档，明文零日志零落盘。 */
    val credential: String?,
    /**
     * 随载荷出现的会话级 CA 证书（Base64 原文，如自签上游的配对描述符）：
     * 只入凭据存档，会话建立时解码为该会话的 TLS 信任；无则 null。
     */
    val caCertificate: String? = null,
    /**
     * 端点会话模式（[com.dshbox.remote.model.SessionMode] 的存储枚举名）：
     * null = 默认 PROXIED；解析器声明的直连端点（DIRECT）不建回环代理。
     */
    val sessionMode: String? = null,
    /**
     * 配对实例标识（saya-ch 通道）：URL 配对链接的 `instance` 参数或 JSON
     * 描述符的 `instanceId`。自签上游用它核对下发 CA 证书的 SHA-256 指纹
     * （归一化比对）；公开证书入口可能缺失（null）。
     */
    val pairingInstance: String? = null,
)

/** 一次配对解析的产出：一台设备与它的 ≥1 条端点。 */
data class ParsedPairing(
    /** 产出本结果的解析器标识：决定会话的注入器行为。 */
    val parserId: String,
    /** 设备显示名（默认名，用户可改）。 */
    val deviceName: String,
    val endpoints: List<ParsedEndpoint>,
)

/**
 * 配对解析器接口：把扫码/手动输入的原始载荷解析为设备档案。
 *
 * 各插件解析器每插件一文件实现本接口并注册进 [ParserRegistry]。
 */
interface PairingParser {
    /** 解析器标识（如插件名）：注册唯一，写进端点档案。 */
    val id: String

    /** 该解析器是否消费此载荷（第二级路由判定）。 */
    fun matches(raw: String): Boolean

    /** 解析载荷；不可解析时抛 [PairingParseException]。 */
    fun parse(raw: String): ParsedPairing
}

/** 配对解析失败：message 中的载荷片段必须已经 [SensitiveTextRedactor] 脱敏。 */
class PairingParseException(message: String) : Exception(message)
