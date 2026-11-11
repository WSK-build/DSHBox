package com.dshbox.app.sandbox

/**
 * DSH 启动输出 `dsh web: http://host:port/?token=…` 行的解析（端口与
 * token 都从该行取，以输出为准；与请求值不一致也以输出为准）。
 *
 * 纯函数 + 容错：缺端口（`http://130127.0.0.1` 反例）、缺 token、行变形
 * （redact 后、多余空格、CR 结尾、query 带其它参数）都只返回能确证的字段；
 * 什么都不缺才返回非空对象。端口解析按 URL 权威（authority）就不需要完整
 * URL 库——只识别 `http://<host>:<port>` 前缀中显式携带的端口。
 */
object DshWebLaunchLine {

    /** 行首标记（含 `//`，权威段扫描从 scheme 终结处直接开始）。 */
    const val MARKER = "dsh web: http://"

    data class Parsed(
        val port: Int,
        val token: String,
    )

    /**
     * 从一行输出解析端口与 token。
     *
     * - 标记缺失 → null（不是启动 URL 行）；
     * - 权威段无显式 `:port`（`http://127.0.0.1/?token=x`）→ null（端口
     *   是默认 80 的语义，不是我们要的显式值；host/port 必须都来自输出）；
     * - 端口非 1..65535 → null；
     * - token 缺失/空 → null（token 与端口必须同时确证才有效）。
     */
    fun parse(line: String): Parsed? {
        val idx = line.indexOf(MARKER)
        if (idx < 0) return null

        val authorityStart = idx + MARKER.length
        val end = line.length
        val authority = StringBuilder()
        var i = authorityStart
        while (i < end) {
            val c = line[i]
            if (c == '/' || c == '?' || c == '#' || c == ' ' || c == '\t' || c == '\r' || c == '\n') break
            authority.append(c)
            i += 1
        }
        // authority 形如 "host:port"；去 userinfo（容错）后取最后一对冒号后的数字。
        val hostPort = authority.toString()
            .substringAfterLast('@')
        val colon = hostPort.lastIndexOf(':')
        if (colon < 0) return null
        val port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null

        val tokenAt = line.indexOf("token=", i)
        if (tokenAt < 0) return null
        val tokenStart = tokenAt + "token=".length
        // token 结束于 '&'、空白或行尾——同行的尾注/括号注释不属于 token。
        var tokenEnd = line.length
        for (j in tokenStart until line.length) {
            val c = line[j]
            if (c == '&' || c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                tokenEnd = j
                break
            }
        }
        val token = line.substring(tokenStart, tokenEnd).trim().takeIf { it.isNotEmpty() } ?: return null

        return Parsed(port = port, token = token)
    }
}
