package com.dshbox.pluginmanager.market.data

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 一条卸载意图：remove 之前记下它拥有的行 id，进程中断后据此结算。 */
data class PendingRowOp(
    val name: String,
    val rows: List<String>,
)

/**
 * 卸载中断的行日志（对齐上游 pending-rows 的机制）。
 *
 * 卸载先删包、后清它拥有的行——行 id 必须在 remove 之前解析，因为 remove 会把
 * 它要读的补丁文件一起删掉。两步之间进程中断（用户杀掉 app 是常态），行就比包
 * 活得久。约定：remove 之前把意图落盘，下次卸载入口按磁盘事实结算——包已不在
 * → 行是孤儿，从层里移除；包还在 → remove 根本没发生，行保持原样。
 *
 * 刻意**不与上游共用 `pending-rows.json`**：同路径下两侧 schema 互不兼容，任何
 * 一方整文件重写都会静默毁掉对方的待结算记录。本方文件只承载我方覆盖层的行。
 *
 * 写入**尽力而为**：存储不可用时静默放弃（与上游一致——下次启动没有东西可
 * 结算），绝不让日志失败阻断卸载本身。
 */
class PendingRowJournal(private val file: File) {

    private val lock = Any()

    fun list(): List<PendingRowOp> = synchronized(lock) { read() }

    /** 记录一次 remove 即将孤儿化的行；同包重复卸载按 upsert 处理。 */
    fun begin(name: String, rows: List<String>): Unit = synchronized(lock) {
        if (name.isBlank() || rows.isEmpty()) return
        write(read().filterNot { it.name == name } + PendingRowOp(name, rows))
    }

    /** 结算完成，移除该包的记录；记录本就不存在时不写盘。 */
    fun finish(name: String): Unit = synchronized(lock) {
        val current = read()
        if (current.none { it.name == name }) return
        write(current.filterNot { it.name == name })
    }

    private fun read(): List<PendingRowOp> = runCatching {
        if (!file.isFile) return emptyList()
        val root = JSONObject(file.readText())
        if (root.optInt("version") != STATE_VERSION) return emptyList()
        val array = root.optJSONArray("operations") ?: return emptyList()
        (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val name = entry.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val rows = entry.optJSONArray("rows")?.let { array2 ->
                (0 until array2.length()).mapNotNull { array2.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            PendingRowOp(name, rows)
        }
    }.getOrDefault(emptyList())

    private fun write(operations: List<PendingRowOp>) {
        runCatching {
            val root = JSONObject().put("version", STATE_VERSION)
            val array = JSONArray()
            operations.forEach { op ->
                array.put(JSONObject().put("name", op.name).put("rows", JSONArray(op.rows)))
            }
            root.put("operations", array)
            // 原子写：临时文件 + REPLACE_EXISTING 移动（File.renameTo 在目标已存在时
            // 是否成功依平台而定），读方不会见到半行。
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp-" + System.nanoTime())
            tmp.writeText(root.toString(), Charsets.UTF_8)
            java.nio.file.Files.move(
                tmp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            // 写成功后清掉此前失败留下的 tmp，避免中断残留越积越多。
            file.parentFile?.listFiles()
                ?.filter { it.name.startsWith(file.name + ".tmp-") }
                ?.forEach { it.delete() }
        }
    }

    private companion object {
        const val STATE_VERSION = 1
    }
}

/** 结算结果：孤儿行的归属与"remove 根本没发生"的记录。 */
data class PendingRowSettleResult(
    val orphaned: List<PendingRowOp>,
    val intact: List<PendingRowOp>,
)

object PendingRowSettle {

    /**
     * 按安装清单结算中断的卸载意图（对齐上游 repairOrphanedRows 的判定）。
     *
     * 包已不在安装清单 → 它的行成为孤儿；包还在 → 行保持原样，只丢记录。
     * 空行集与空名不参与结算。
     */
    fun settle(operations: List<PendingRowOp>, installedNames: Set<String>): PendingRowSettleResult {
        val orphaned = mutableListOf<PendingRowOp>()
        val intact = mutableListOf<PendingRowOp>()
        operations.forEach { op ->
            when {
                op.name.isBlank() || op.rows.isEmpty() -> Unit
                op.name in installedNames -> intact.add(op)
                else -> orphaned.add(op)
            }
        }
        return PendingRowSettleResult(orphaned, intact)
    }
}
