package com.dshbox.app.ui.webview

/** token 里需要百分号转义的字符（其余按原样保留，见 [dshUrlWithToken] 的说明）。 */
private const val UNRESERVED_EXTRA = "-_.~"

/**
 * 把 [token] 百分号转义为可安全放进 query 的值。
 *
 * token 目前是 base64url 字符集（A-Za-z0-9_-），走不到转义分支；保留它是为了防止
 * 将来 token 形态变化时，`#` 把后面全部吃成 fragment、`&`/`+` 破坏 query 解析。
 * 只处理 ASCII（token 是 ASCII；非 ASCII 需要按 UTF-8 逐字节编码，这里不做）。
 */
internal fun encodeQueryValue(value: String): String = buildString {
    for (c in value) {
        when {
            c.isLetterOrDigit() || c in UNRESERVED_EXTRA -> append(c)
            c.code < 0x80 -> append('%').append("%02X".format(c.code))
            else -> append(c)
        }
    }
}

/**
 * 给 DSH 基础地址写入**当前**启动 token（`?token=<值>`）。
 *
 * ## 语义是"替换"而不是"追加"
 *
 * DSH 网页端以启动 token 鉴权，而 **token 会随 DSH 重启轮换**。WebView 容器持有的
 * `url` 里烤的是建容器那一刻的旧 token，重启后若这里遇到 `token=` 就原样返回，
 * 那么「手动刷新」与「401 自动重试」两条路径都会拿旧 token 去请求 —— 表现就是
 * 白屏 + 进度条卡住、连手动刷新也不好使（且因 `autoRefreshedForAuth` 已置位而无法自愈）。
 *
 * 因此这里**先剥离已有的 `token=` 再写入新值**（同时保留其它 query 参数），
 * 使「带旧 token 的 URL + 新 token」也能得到正确结果。
 *
 * 本函数是纯函数（不依赖 Android API），便于 JVM 单测锁住上述语义。
 */
internal fun dshUrlWithToken(base: String, token: String?): String {
    if (token.isNullOrEmpty()) return base
    val encoded = encodeQueryValue(token)
    val queryStart = base.indexOf('?')
    if (queryStart < 0) return "$base?token=$encoded"
    val path = base.substring(0, queryStart)
    val kept = base.substring(queryStart + 1)
        .split('&')
        .filter { it.isNotEmpty() && !it.startsWith("token=") }
    return "$path?" + (kept + "token=$encoded").joinToString("&")
}
