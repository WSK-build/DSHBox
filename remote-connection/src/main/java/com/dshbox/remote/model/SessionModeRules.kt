package com.dshbox.remote.model

/**
 * 会话模式的唯一准入口径（纯逻辑，无 Android 依赖，JVM 单测锁定）：直连
 * 要求 https，其余原样。
 *
 * 编辑对话框随行收敛、重配对/扫码重配对的对齐层、确认收尾都经此函数取
 * 有效档位，阻止「非 https 地址 + 直连」组合落入档案——直连会话层构造期
 * 会显式拒绝明文端点，该组合一旦落档端点恒处失败态且无出路。
 *
 * [selected] 为直连且 [address]（忽略大小写、去前后空白后）非 https 前缀
 * 时，回落到代理档；其余取值原样返回。空串与未知值直通（调用方语义：
 * 未选择或非档位值不做任何纠偏）。
 */
internal fun effectiveSessionMode(address: String, selected: String): String {
    if (selected != SessionMode.DIRECT.name) return selected
    val tls = address.trim().startsWith("https://", ignoreCase = true)
    return if (tls) {
        selected
    } else {
        SessionMode.PROXIED.name
    }
}
