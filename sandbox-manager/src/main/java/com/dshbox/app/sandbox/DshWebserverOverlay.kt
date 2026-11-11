package com.dshbox.app.sandbox

import com.dshbox.app.common.Constants
import java.io.File

/**
 * webserver host/port 覆盖层—— 宿主每次启动 DSH 前整份重写的 `--patch`
 * 配置层，把本机 DSH 的 webserver 绑定到探测出的端口。
 *
 * 目标条目（取证见 [Constants.DSH_WEBSERVER_ENTRY_ID] 的注释）：
 * `- id: webserver, name: '@deepseek-ai/dsh-host-webserver'`。patch 覆盖行的
 * 语义是"替换目标行的整个 config"（与 [DshOfficialPlugins.Entry] 的 KDoc 同一
 * 约定），所以本层必须**完整复述要保的键**：官方行的 config 还带 tls /
 * compression / compressionLevel / compressionThresholdBytes，漏掉它们就把
 * 压缩等配置整个抹了。此处按官方 web profile 的写法复述这些默认值。
 *
 * `disabled` 不写：该条目本就是启用的（上游 web profile 的 layer-2 行无
 * disabled），省略即维持默认。
 *
 * 落点（宿主侧）：[Constants.DSH_WEBSERVER_OVERLAY_RELATIVE_PATH]（相对
 * user-data，即 `user-data/.dsh/dshbox/webserver-port/`）——guest 内
 * `/root/projects` 绑定的正是 user-data，因此这一落点与
 * [Constants.DSH_WEBSERVER_OVERLAY_GUEST_PATH] 一一对应：文件写下去
 * DSH 一定读得到。
 *
 * **合并写入**：调用方传入既有 patch 文件的已有行（若在该文件里追加过其它
 * 条目），重写时把非 webserver id 的行原样保留在后——`--patch` 列表里同一
 * 文件一条两行也合法（按 id 逐键覆盖）。已有功能层 / 插件停用层走各自
 * 独立文件，天然不会被本层覆盖或挤掉。
 */
object DshWebserverOverlay {

    /**
     * 宿主侧落点：`userDataDir / .dsh / dshbox / webserver-port / …`。
     * 相对 user-data 的路径取 [Constants.DSH_WEBSERVER_OVERLAY_RELATIVE_PATH]，
     * 保证与 guest 侧 [Constants.DSH_WEBSERVER_OVERLAY_GUEST_PATH]
     * （绑定在 `/root/projects` 之下）是同一个文件。
     */
    fun hostFile(userDataDir: File): File =
        File(userDataDir, Constants.DSH_WEBSERVER_OVERLAY_RELATIVE_PATH)

    /**
     * 渲染本层正文：webserver 覆盖行在前，[carryOverLines]（既有文件里非
     * webserver 的行，调用方读旧文件传入）原样接在后。
     *
     * 整个文件恒为单个顶层 YAML 序列（同 [OfficialPluginOverlay.render] 的
     * 教训：先 `[]` 再追加会形成第二个顶层节点、DSH 解析时静默丢弃整层），
     * 因此永远是整体重写、carry-over 追加在序列尾。
     */
    internal fun render(host: String, port: Int, carryOverLines: List<String> = emptyList()): String {
        require(port in 1..65535) { "port out of range: $port" }
        val sb = StringBuilder()
        sb.append("- id: ").append(Constants.DSH_WEBSERVER_ENTRY_ID).append('\n')
        sb.append("  name: ").append(quoted(Constants.DSH_WEBSERVER_ENTRY_NAME)).append('\n')
        sb.append("  config:\n")
        sb.append("    host: ").append(quoted(host)).append('\n')
        sb.append("    port: ").append(port).append('\n')
        // 官方 web profile 行的其余 config 键，patch 替换整个 config 时必须
        // 一并复述，否则该行的这些配置被抹成空。
        sb.append("    tls: null\n")
        sb.append("    compression: gzip\n")
        sb.append("    compressionLevel: 1\n")
        sb.append("    compressionThresholdBytes: 1024\n")
        for (line in carryOverLines) {
            // 保留原始行（含前导缩进——续行缩进是 YAML 结构的一部分），
            // 只做行尾裁剪；纯空白整行丢弃。
            val trimmedEnd = line.trimEnd()
            if (trimmedEnd.isBlank()) continue
            sb.append(trimmedEnd).append('\n')
        }
        return sb.toString()
    }

    /**
     * 按 [host]/[port] 重写覆盖层。合并语义：旧文件里**非本层目标 id** 的行
     * （历史追加）保留在尾；不存在的旧文件等价于空 carry-over。
     *
     * @return 写入成功与否
     */
    fun refresh(userDataDir: File, host: String, port: Int): Boolean = runCatching {
        val file = hostFile(userDataDir)
        file.parentFile?.mkdirs()
        val carryOver = if (file.isFile) {
            file.readLines(Charsets.UTF_8).filter { line ->
                // 保留与本层目标 id 无关的既有行。本层自己写过的行（条目头与
                // name/config 续行）整体重写，不 carry over。
                val t = line.trim()
                t.isNotEmpty() && isCarryOverRow(line)
            }
        } else emptyList()
        file.writeText(render(host, port, carryOver), Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /**
     * 旧文件中的行是否为"外部追加行"（carry-over）：本层写出的行以
     * `- id: webserver` 开头的条目头与它的 name/config 续行都会被整体重写
     * （判 false），其余非空行原样保留（未来若有别的写入方在这份文件追加
     * 条目，合并时不丢）。缩进白名单行（如 `- name: 'x'`）若是外部条目的
     * 子行不匹配本层键名，即按外部行保留——判定按"键名 + 属本层"双条件。
     */
    internal fun isCarryOverRow(rawLine: String): Boolean {
        val t = rawLine.trim()
        // 空白整行不算 carry-over（重写后丢弃——调用方已按带空过滤，这里补一致口径）。
        if (t.isEmpty()) return false
        // 本层自己的条目头：整体重写，不 carry over。
        if (t.startsWith("- id: ${Constants.DSH_WEBSERVER_ENTRY_ID}")) return false
        // 本层条目的续行（name/config/host/port/tls/compression 段）：
        // 重写时随条目头一起重新生成，判为 false；只有**不带缩进**的同键名
        // 顶层行（不是本层写法——本层续行都带缩进）才不会被误保留为结构重复。
        val ownKeyRow = t.startsWith("name: ${Constants.DSH_WEBSERVER_ENTRY_NAME}") ||
            t.startsWith("name: '${Constants.DSH_WEBSERVER_ENTRY_NAME}'") ||
            t.startsWith("name: \"${Constants.DSH_WEBSERVER_ENTRY_NAME}\"") ||
            t.startsWith("config:") ||
            t.startsWith("host:") ||
            t.startsWith("port:") ||
            t.startsWith("tls:") ||
            t.startsWith("compression:") ||
            t.startsWith("compressionLevel:") ||
            t.startsWith("compressionThresholdBytes:")
        if (ownKeyRow && rawLine.startsWith(" ")) return false

        // 非 own-key 行：外部行（原样保留）。
        return true
    }

    /** YAML 单引号标量；值内的单引号按 YAML 规则写成两个。 */
    private fun quoted(value: String): String = "'" + value.replace("'", "''") + "'"
}
