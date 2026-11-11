package com.dshbox.remote.parse

/**
 * 网关、邀请、bridge 与 pocket 四类插件的 URL 签名解析器集，按注册顺序
 * 参与第二级签名路由；签名不命中的裸 URL 落通用 URL 通道。
 */
fun p0UrlParsers(): List<PairingParser> = listOf(
    DshRemoteWebGatewayParser(),
    DshFullRemoteParser(),
    DshBridgeParser(),
    DshPocketParser(),
)
