package com.dshbox.pluginmanager.market.data

import com.dshbox.pluginmanager.contract.HostSignals

/**
 * 宿主启动日志里「拒绝加载」行的扫描。
 *
 * 0.2.x 的宿主把装配失败分成两类落到 stderr（随进程日志一并落盘）：
 *
 * 1. **兼容性拒绝**：合成期把 peer 不满足的行置停用——
 *    `dsh: disabling profile plugin row "<id>": <reason>`（有 id 时，id 允许
 *    含冒号如 `cordis:xxx`，按引号成对提取），
 *    否则 `dsh: disabling profile plugin <name>: <reason>`；
 * 2. **bundle 跳过**：bundle 包解析不了（目录缺失/清单损坏）——
 *    `dsh: skipping profile bundle "<pkg>": <reason>`（每次启动只报一次）。
 *
 * 这两类是"装着但宿主不加载"的唯一磁盘可观测信号：不消费它们，界面对
 * "重启后生效"的承诺就可能是假的（宿主永远拒绝它）。
 *
 * ## 只看最近一次启动
 *
 * 旧行会留在日志里，而宿主对**已修复**的插件恰恰不再输出任何行——按全文
 * 扫描会把"已经修好"说成"宿主仍然拒绝"。进程日志按启动分段（写入侧在每次
 * 启动前写 `===== DSH boot <时间> =====` 标记），扫描只取**最后一个标记之后**
 * 的内容；找不到标记（极旧日志）才退回全文。
 */
object BootDenialScanner {

    /** 一次扫描的结果（均为归一后的裸值，无引号）。 */
    data class BootDenials(
        /** 被兼容性门禁置停用的条目 id（`row "<id>"` 形态）。 */
        val deniedRowIds: Map<String, String>,
        /** 被兼容性门禁置停用的名字（无 id 的 label 形态）。 */
        val deniedNames: Map<String, String>,
        /** 被跳过的 bundle：包名 → 原因。 */
        val skippedPackages: Map<String, String>,
    ) {
        val isEmpty: Boolean get() = deniedRowIds.isEmpty() && deniedNames.isEmpty() && skippedPackages.isEmpty()
    }

    // 全部取自契约层（[HostSignals]）：宿主输出文本是最脆的契约，收敛到一处
    // 便于上游变更时集中核对（哨兵测试钉住每个形态）。
    private val BOOT_MARKER_RE = HostSignals.BOOT_MARKER
    private val DENY_ROW_RE = HostSignals.PLUGIN_DISABLED_ROW
    private val DENY_NAME_RE = HostSignals.PLUGIN_DISABLED_NAME
    private val SKIP_RE = HostSignals.BUNDLE_SKIPPED

    /** 最近一次启动的分段正文：最后一个启动标记之后的内容；无标记时原文返回。 */
    fun lastBootSegment(text: String): String {
        val lines = text.split('\n')
        val lastMarker = lines.indexOfLast { BOOT_MARKER_RE.matches(it.trim()) }
        if (lastMarker < 0) return text
        return lines.subList(lastMarker, lines.size).joinToString("\n")
    }

    /** 扫描（自动限定在最近一次启动的分段内）。 */
    fun scan(text: String): BootDenials = scan(text, latestBootOnly = true)

    /** 全量扫描；[latestBootOnly] 为 false 时连更早的启动段一起扫（仅供单测对照）。 */
    fun scan(text: String, latestBootOnly: Boolean): BootDenials {
        val body = if (latestBootOnly) lastBootSegment(text) else text
        val deniedRows = LinkedHashMap<String, String>()
        val deniedNames = LinkedHashMap<String, String>()
        val skipped = LinkedHashMap<String, String>()
        body.split('\n').forEach { raw ->
            val line = raw.trimEnd('\r')
            DENY_ROW_RE.find(line)?.let { match ->
                deniedRows[match.groupValues[1].trim()] = match.groupValues[2].trim()
                return@forEach
            }
            DENY_NAME_RE.find(line)?.let { match ->
                deniedNames[match.groupValues[1].trim()] = match.groupValues[2].trim()
                return@forEach
            }
            SKIP_RE.find(line)?.let { match ->
                skipped[match.groupValues[1].trim()] = match.groupValues[2].trim()
            }
        }
        return BootDenials(deniedRows, deniedNames, skipped)
    }
}
