package com.dshbox.pluginmanager.layer

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 层里的一个「改/停用已有条目」。 */
data class LayerPatchEntry(
    val id: String,
    val name: String? = null,
    val disabled: Boolean? = null,
    /** 其余原样保留的行（如 `config:` 块），逐行含缩进。 */
    val extraLines: List<String> = emptyList(),
)

/**
 * 层里的一个「插入新条目」。
 *
 * 插入块整体按原始行保留：我们只需要知道它的 id（用于避让与去重），
 * 不需要理解它的字段，因此原样回写即可，避免改写别人的配置。
 *
 * @property opaque 内联写法（`- insert: [ {...} ]`）无法逐字段理解，整体按原样保留，
 *   此时 [id] 为空——我们不去猜它的内容，也绝不动它。
 */
data class LayerInsertEntry(
    val id: String,
    /** 含 `    - id: …` 与后续缩进行的原始行。 */
    val rawBlock: List<String>,
    val opaque: Boolean = false,
)

/** 层文件解析结果。 */
data class LayerDocument(
    val inserts: List<LayerInsertEntry>,
    val patches: List<LayerPatchEntry>,
    /**
     * 是否完整解析。为 false 时**禁止写回**：文件里有我们读不懂的结构，
     * 重写会把它抹掉——宁可不动它（唯一例外是显式调用 [PluginLayer.backupAndReset]）。
     */
    val parseOk: Boolean,
    val raw: String,
    /** 读取失败的原因（文件存在但读不出来时非空）。 */
    val readError: String? = null,
)

/**
 * 「我们自己的层」文件读写。
 *
 * 这是本模块唯一的写入入口，纪律（来自真机验证，不得放宽）：
 *
 * 1. **整个文件永远只有一个顶层 YAML 序列**。若先有 `[]` 再追加条目，会形成
 *    第二个顶层节点，DSH 解析时抛 `YAMLException` 并**静默丢弃整层**。
 *    所以写入永远是「整体重写」而不是「追加」。
 * 2. 改/停用已有条目用 `- id: …` + `disabled: true|false`；新建条目用
 *    `- insert:` 列表。两者不能混写。
 * 3. id 只允许安全字符集；**不是裸标量形态的 id 会自动加单引号**
 *    （DSH 自己的合成输出里，`@scope/pkg` 这类 id 就是带引号的），
 *    这样作用域包名也能被停用。
 * 4. 解析不完整时拒绝写入（见 [LayerDocument.parseOk]）；**读取失败同样拒绝写入**——
 *    把"读不出来"当成"空层"会让我们把整份停用行抹掉，那是本模块最不该犯的错。
 */
class PluginLayer(private val file: File) {

    companion object {
        /** 空层正文（禁用本层的合法写法）。 */
        const val EMPTY = "[]\n"

        /** 可安全写入的 id 字符集：不含空格、引号、冒号、换行，避免改变 YAML 结构。 */
        private val ID_RE = Regex("^[A-Za-z0-9_./@+-]{1,200}$")

        /** 裸标量形态（不需要引号）。 */
        private val PLAIN_ID_RE = Regex("^[A-Za-z0-9_.-]+$")

        private val INSERT_HEAD_RE = Regex("^- insert: *(.*)$")
        private val TOP_ENTRY_RE = Regex("^- id: *(.+?) *$")
        private val INSERT_ITEM_RE = Regex("^ {4}- id: *(.+?) *$")
        private val NAME_RE = Regex("^ {2}name: *(.*?) *$")
        private val DISABLED_RE = Regex("^ {2}disabled: *(true|false) *$")

        /** 我们能安全写入的 id 形态。 */
        fun isValidId(id: String): Boolean = ID_RE.matches(id)

        /** 按需加引号：非裸标量形态一律单引号包裹。 */
        fun renderId(id: String): String = if (PLAIN_ID_RE.matches(id)) id else "'$id'"

        /** YAML 单引号标量里的 `'` 要写成 `''`，否则会破坏整份文档。 */
        fun escapeSingleQuoted(value: String): String = value.replace("'", "''")

        /** 去掉 YAML 引号（单/双），用于把读到的值还原成裸串。 */
        fun unquote(value: String): String {
            val trimmed = value.trim()
            if (trimmed.length >= 2) {
                if (trimmed.startsWith("'") && trimmed.endsWith("'")) {
                    return trimmed.substring(1, trimmed.length - 1).replace("''", "'")
                }
                if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                    return trimmed.substring(1, trimmed.length - 1)
                }
            }
            return trimmed
        }
    }

    private val writeLock = Any()

    fun exists(): Boolean = file.isFile && file.length() > 0L

    /**
     * 在写锁内「读 → 改 → 写」整份补丁列表。
     *
     * **所有写入都必须走这里**：本模块至少有三个写者（安全模式的守卫、绝对安全模式、
     * 市场的启停），它们各自"读出来改一改再整体写回"。没有单一临界区的话，
     * 后写者会基于旧快照覆盖前写者刚落的行——层里少一条停用行，
     * 而内存里的隔离记录还在，界面说"已跳过"、实际却启用着。
     *
     * @param transform 收到当前补丁列表，返回「新的补丁列表」与「要带回的结果」
     * @return transform 的第二个返回值；解析不完整、id 非法或落盘失败时返回 null
     */
    fun <T> withPatches(transform: (List<LayerPatchEntry>) -> Pair<List<LayerPatchEntry>, T>): T? =
        synchronized(writeLock) {
            val doc = read()
            if (!doc.parseOk) return@synchronized null
            val (patches, result) = transform(doc.patches)
            if (patches.any { !isValidId(it.id) }) return@synchronized null
            if (!write(doc.copy(patches = patches))) return@synchronized null
            result
        }

    /**
     * 读取并解析。
     *
     * 三种结果必须区分：文件不存在（空层、可写）、解析成功（可写）、
     * **文件存在但读不出来**（`parseOk = false`，禁止写回）。
     */
    fun read(): LayerDocument {
        if (!file.exists()) return LayerDocument(emptyList(), emptyList(), parseOk = true, raw = "")
        val raw = try {
            file.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            return LayerDocument(
                inserts = emptyList(),
                patches = emptyList(),
                parseOk = false,
                raw = "",
                readError = t.message ?: t::class.java.simpleName,
            )
        }
        return parse(raw)
    }

    /**
     * 把 [doc] 写回。**只在解析完整时调用**；写前再校验一次 id 合法性。
     *
     * 写入是「同目录临时文件 → 原子替换」：不先删原文件，避免留下
     * "这一刻层文件不存在"的窗口（DSH 会因此拿不到我们的停用行）。
     */
    fun write(doc: LayerDocument, inserts: List<LayerInsertEntry> = doc.inserts): Boolean =
        synchronized(writeLock) { writeLocked(doc, inserts) }

    private fun writeLocked(doc: LayerDocument, inserts: List<LayerInsertEntry>): Boolean {
        if (!doc.parseOk) return false
        val patches = doc.patches
        if (patches.any { !isValidId(it.id) }) return false
        if (inserts.any { !it.opaque && !isValidId(it.id) }) return false
        return runCatching {
            val text = render(inserts, patches)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp-" + System.nanoTime())
            tmp.writeText(text, Charsets.UTF_8)
            moveInto(tmp)
        }.getOrDefault(false)
    }

    /** 原子替换；不支持原子移动的文件系统上退化为普通替换。 */
    private fun moveInto(tmp: File): Boolean {
        val target = file.toPath()
        val source = tmp.toPath()
        return try {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            true
        } catch (t: AtomicMoveNotSupportedException) {
            runCatching {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                true
            }.getOrElse {
                runCatching { tmp.delete() }
                false
            }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            false
        }
    }

    /**
     * 逃生路径：层文件已损坏（解析不完整/读不出来）时，先备份再重置为空层。
     *
     * 只在**明确判定损坏**时调用。损坏的层会让 DSH 静默丢弃整层，此时
     * "什么都不做"等于让所有隔离失效，所以必须有一条能把它拉回可用状态的路径；
     * 备份保留原文件（`.broken-<时间戳>`）供事后查看。
     */
    fun backupAndReset(): Boolean {
        val doc = read()
        if (doc.parseOk) return true
        // 备份必须先成功：重置会把原内容覆盖掉，备份失败时它是唯一的副本。
        val backupOk = runCatching {
            if (file.exists()) {
                val backup = File(file.parentFile, file.name + ".broken-" + System.currentTimeMillis())
                file.copyTo(backup, overwrite = true)
            }
            true
        }.getOrDefault(false)
        if (!backupOk) return false
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(EMPTY, Charsets.UTF_8)
        }.isSuccess
    }

    /** 渲染成单个顶层序列。 */
    fun render(inserts: List<LayerInsertEntry>, patches: List<LayerPatchEntry>): String {
        if (inserts.isEmpty() && patches.isEmpty()) return EMPTY
        val sb = StringBuilder()
        if (inserts.isNotEmpty()) {
            sb.append("- insert:\n")
            inserts.forEach { entry -> entry.rawBlock.forEach { sb.append(it).append('\n') } }
        }
        patches.forEach { entry ->
            sb.append("- id: ").append(renderId(entry.id)).append('\n')
            entry.name?.let {
                sb.append("  name: '").append(escapeSingleQuoted(it)).append("'\n")
            }
            entry.disabled?.let { sb.append("  disabled: ").append(it).append('\n') }
            entry.extraLines.forEach { sb.append(it).append('\n') }
        }
        return sb.toString()
    }

    /**
     * 解析层正文。
     *
     * 逐行状态机：`- insert:` 之后按 4 空格缩进收集插入块（内联写法整体当作
     * 不透明块保留）；顶层 `- id:` 起一个补丁条目，其后的 2 空格缩进行归它。
     * **插入块结束后必须把当前行按顶层重新处理**——否则紧跟插入块的第一条
     * 停用行会被整条吞掉（这是真实踩过的坑）。
     */
    fun parse(raw: String): LayerDocument {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "[]") {
            return LayerDocument(emptyList(), emptyList(), parseOk = true, raw = raw)
        }
        val inserts = mutableListOf<LayerInsertEntry>()
        val patches = mutableListOf<LayerPatchEntry>()
        var parseOk = true
        var inInsert = false
        var currentInsertId: String? = null
        var currentInsertOpaque = false
        var currentInsertLines: MutableList<String>? = null
        var currentPatch: LayerPatchEntry? = null

        fun closeInsert() {
            val lines = currentInsertLines
            // 块状写法只可能收子行；一个子行都没有（`- insert:` 后面什么都没有）
            // 就不必产出条目，避免渲染出空的插入块。
            if (lines != null && lines.isNotEmpty()) {
                inserts.add(
                    LayerInsertEntry(
                        id = currentInsertId.orEmpty(),
                        rawBlock = lines.toList(),
                        opaque = currentInsertOpaque,
                    ),
                )
            }
            currentInsertId = null
            currentInsertOpaque = false
            currentInsertLines = null
        }

        fun closePatch() {
            currentPatch?.let { patches.add(it) }
            currentPatch = null
        }

        fun handleTopLevel(line: String) {
            val top = TOP_ENTRY_RE.find(line)
            if (top != null) {
                closePatch()
                val id = unquote(top.groupValues[1])
                if (!isValidId(id)) {
                    parseOk = false
                    return
                }
                currentPatch = LayerPatchEntry(id = id)
                return
            }
            when {
                line.startsWith("  ") -> {
                    val patch = currentPatch
                    if (patch == null) {
                        if (line.isNotBlank()) parseOk = false
                        return
                    }
                    val name = NAME_RE.find(line)
                    val disabled = DISABLED_RE.find(line)
                    currentPatch = when {
                        name != null -> patch.copy(name = unquote(name.groupValues[1]))
                        disabled != null -> patch.copy(disabled = disabled.groupValues[1] == "true")
                        else -> patch.copy(extraLines = patch.extraLines + line)
                    }
                }

                line.isBlank() || line.startsWith("#") -> Unit

                else -> parseOk = false
            }
        }

        val lines = raw.split('\n')
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trimEnd('\r')
            val head = INSERT_HEAD_RE.find(line)
            if (head != null) {
                closePatch()
                closeInsert()
                inInsert = true
                currentInsertId = null
                // 块状写法：头行由 render 统一产出，这里只收子行；
                // 内联写法（`- insert: [ {...} ]`）整块原样保留，连同头行一起收。
                val inlineTail = head.groupValues[1].isNotBlank()
                currentInsertLines = mutableListOf<String>().apply {
                    if (inlineTail) add(line)
                }
                currentInsertOpaque = inlineTail
                index++
                continue
            }
            if (inInsert) {
                val item = INSERT_ITEM_RE.find(line)
                if (item != null) {
                    closeInsert()
                    val id = unquote(item.groupValues[1])
                    if (!isValidId(id)) {
                        parseOk = false
                        index++
                        continue
                    }
                    currentInsertId = id
                    currentInsertLines = mutableListOf(line)
                    index++
                    continue
                }
                // 插入块内部的续行（字段、嵌套配置）原样收下。
                if (line.startsWith(" ") || line.isBlank() || line.startsWith("#")) {
                    currentInsertLines?.add(line)
                    index++
                    continue
                }
                // 既不是插入项也不是续行：插入块到此结束，**当前行按顶层重新处理**。
                closeInsert()
                inInsert = false
                continue
            }
            handleTopLevel(line)
            index++
        }
        closeInsert()
        closePatch()
        return LayerDocument(inserts, patches, parseOk, raw)
    }

    /**
     * 读-改-写的便捷入口：设置某条目的停用状态（不存在则新建该条目）。
     *
     * 返回 false 表示没有写入（解析不完整/读失败、id 非法或落盘失败）——
     * 调用方必须把它当失败处理，不能假设已生效。
     */
    fun setDisabled(id: String, disabled: Boolean): Boolean {
        if (!isValidId(id)) return false
        return withPatches { patches ->
            val existing = patches.firstOrNull { it.id == id }
            val next = if (existing == null) {
                patches + LayerPatchEntry(id = id, disabled = disabled)
            } else {
                patches.map { if (it.id == id) it.copy(disabled = disabled) else it }
            }
            next to true
        } ?: false
    }

    /** 移除某个停用/补丁条目（不触碰插入块）。 */
    fun removePatchEntry(id: String): Boolean = withPatches { patches ->
        val next = patches.filterNot { it.id == id }
        next to true
    } ?: false

    /**
     * 删掉整份层文件。
     *
     * 只给**独占这一层**的场景用（绝对安全模式的层：开 = 写行，关 = 删文件）。
     * 用在共享层上会把别人的行一起删掉。
     */
    fun erase(): Boolean = synchronized(writeLock) {
        runCatching {
            file.parentFile?.listFiles()
                ?.filter { it.name.startsWith(file.name + ".tmp-") }
                ?.forEach { it.delete() }
            !file.exists() || file.delete()
        }.getOrDefault(false)
    }

    /** 当前被本层停用的 id 集合。 */
    fun disabledIds(): Set<String> =
        read().patches.filter { it.disabled == true }.map { it.id }.toSet()
}
