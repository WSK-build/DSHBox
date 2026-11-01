package com.dshbox.pluginmanager.layer

import com.dshbox.pluginmanager.contract.PatchSyntax
import java.io.File

/** 从补丁层里扫出来的行信息（只读用途）。 */
data class PatchRowScan(
    /** 形如 `- id: X` 的顶层条目 id。 */
    val ids: List<String>,
    /** 明确写了 `disabled: true` 的 id。 */
    val disabled: List<String>,
    /** 明确写了 `disabled: false` 的 id（强制启用）。 */
    val forcedEnabled: List<String>,
    /** `- insert:` 下插入的 id。 */
    val inserted: List<String>,
    /** 层里出现过的 `name:` 值（顶层行与插入行都算），用于按包名判断引用。 */
    val names: List<String>,
)

/**
 * 补丁层文件的**只读**行扫描。
 *
 * 刻意不做 YAML 解析：文件里可能有我们读不懂的结构（`!!js` 表达式、
 * 深层嵌套），逐行匹配 `- id: X` 与紧随其后的 `disabled:` 足够回答
 * 「这一层说了什么」，且不会因为解析失败而整层读不出来。
 *
 * 用途：展示用户层已有的停用/强制启用状态，以及在做启停前判断该条目是否
 * 已被别处管理。**永不写入**用户层。
 */
object PatchDialect {

    // 行语法全部取自 PatchSyntax，与写入方共用同一套正则。
    private val INSERT_HEAD_RE = PatchSyntax.INSERT_HEAD_BARE
    private val TOP_ENTRY_RE = PatchSyntax.TOP_ENTRY
    private val INSERT_ITEM_RE = PatchSyntax.INSERT_ITEM
    private val DISABLED_TRUE_RE = PatchSyntax.DISABLED_TRUE
    private val DISABLED_FALSE_RE = PatchSyntax.DISABLED_FALSE
    private val NAME_RE = PatchSyntax.NAME_VALUE

    fun scan(file: File): PatchRowScan =
        scan(runCatching { file.readText(Charsets.UTF_8) }.getOrDefault(""))

    fun scan(text: String): PatchRowScan {
        val ids = mutableListOf<String>()
        val disabled = mutableListOf<String>()
        val forced = mutableListOf<String>()
        val inserted = mutableListOf<String>()
        val names = mutableListOf<String>()
        var inInsert = false
        var pending: String? = null

        val lines = text.split('\n')
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trimEnd('\r')

            if (INSERT_HEAD_RE.matches(line)) {
                pending = null
                inInsert = true
                index++
                continue
            }

            if (inInsert) {
                val item = INSERT_ITEM_RE.find(line)
                if (item != null) {
                    inserted.add(PluginLayer.unquote(item.groupValues[1]))
                    index++
                    continue
                }
                NAME_RE.find(line)?.let { match ->
                    val name = PluginLayer.unquote(match.groupValues[1])
                    if (name.isNotEmpty()) names.add(name)
                }
                // 插入块内部的续行（字段、嵌套配置）跳过。
                if (line.startsWith("    ") || line.isBlank() || line.startsWith("#")) {
                    index++
                    continue
                }
                // 插入块结束：当前行按顶层重新处理，不能直接丢弃。
                inInsert = false
                continue
            }

            val top = TOP_ENTRY_RE.find(line)
            if (top != null) {
                val id = PluginLayer.unquote(top.groupValues[1])
                ids.add(id)
                pending = id
                index++
                continue
            }
            NAME_RE.find(line)?.let { match ->
                val name = PluginLayer.unquote(match.groupValues[1])
                if (name.isNotEmpty()) names.add(name)
            }
            when {
                DISABLED_TRUE_RE.matches(line) -> pending?.let { disabled.add(it) }
                DISABLED_FALSE_RE.matches(line) -> pending?.let { forced.add(it) }
            }
            index++
        }
        return PatchRowScan(ids, disabled, forced, inserted, names)
    }
}
