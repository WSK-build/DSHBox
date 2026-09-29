package com.dshbox.app.sandbox.online

/**
 * 解析 nodejs.org 官方发布（及其镜像同格式）的 `SHASUMS256.txt`：
 * 每行 `<sha256>` + 两个空格（或 ` *`）+ `<文件名>`。在线 node 层装配用
 * 它做上游锚定校验（下载的 tarball 必须与该清单一致）。
 */
object Shasums256 {

    private val LINE = Regex("^([0-9a-fA-F]{64})\\s{1,2}[ *]?(.+)$")

    /** 文件名（如 `node-v24.19.0-linux-arm64.tar.xz`）→ 小写 sha256。 */
    fun parse(text: String): Map<String, String> =
        text.lineSequence()
            .mapNotNull { line ->
                val m = LINE.find(line.trim()) ?: return@mapNotNull null
                m.groupValues[2].trim() to m.groupValues[1].lowercase()
            }
            .toMap()
}
