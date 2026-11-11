package com.dshbox.remote.parse

/**
 * 配对解析器标识常量：会话工厂与注入器按它选取行为；字符串只在此定义。
 */
object ParserIds {
    const val GATEWAY = "dsh-remote-web-gateway"
    const val FULL_REMOTE = "dsh-full-remote"
    const val BRIDGE = "dsh-bridge"
    const val POCKET = "dsh-pocket"
    const val GENERIC = "generic-url"

    /** dsh-Remote 的自定义配对 scheme（多端点 server 列表 + Bearer 令牌）。 */
    const val REMOTE_SCHEME = "dsh-remote-scheme"

    /** anonRTtty 的 ticket 配对页（票据经会话首载地址携带，生命周期同通用票据）。 */
    const val ANON_RT = "anonrt-remote-mode"

    /** ChongYep 的手输令牌通道（地址|令牌 竖线分隔，长期 Bearer 凭据）。 */
    const val CHONG_YEP = "chongyep-remote"

    /** saya-ch / dsh-mobile 的配对通道（URL 配对链接 / JSON 描述符，自签或公网 HTTPS）。 */
    const val SAYA_CH = "saya-ch-remote"

    /** ZCode 工作区的账号型直连入口（https://zcode.z.ai/remote/…）。 */
    const val ZCODE_REMOTE = "zcode-remote"

    /** DSH Remote 的账号型直连入口（https://dsh.r2049.cn/app/…）。 */
    const val DS_HARNESS_REMOTE = "ds-harness-remote"

    /**
     * 凭据为一次性票的解析器权威集合：未消费凭据集合、消费提交门控与消费
     * 记账共用此口径（长期令牌类解析器不得进入，401 是普通认证失败）。
     * saya-ch 的 gatewayToken 是一次性配对票（native-pair 单次兑换），在列；
     * 兑换产物（会话 cookie/CSRF）的注入不受消费标记禁用（SAYA_CH 分支排在
     * 消费降级之前，见 AuthInjectors.forParser）。
     */
    val CONSUMABLE_CREDENTIAL_PARSER_IDS = setOf(GATEWAY, FULL_REMOTE, GENERIC, ANON_RT, SAYA_CH)
}

/**
 * 端点通道标签（存储用枚举名）：清单档案只存枚举名，展示经界面资源映射
 * 到六语文案。旧档案中的中文标签在读取时归一化回枚举名。
 */
enum class ChannelKind {
    /** 局域网明文入口。 */
    LAN,

    /** 公网 HTTPS 入口。 */
    PUBLIC,

    /** 隧道域名入口（地址随会话易变）。 */
    TUNNEL,

    /** 未识别形态的通用 URL 通道。 */
    URL,
}
