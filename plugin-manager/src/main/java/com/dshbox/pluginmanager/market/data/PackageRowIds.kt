package com.dshbox.pluginmanager.market.data

import java.io.File
import org.json.JSONObject

/**
 * 一个包在补丁层里**自己的**条目 id。
 *
 * 移植自 dshmarket 的 `rowIdsForPackage`（`src/patch.ts:266`）。用它的真源码在本机 dsh
 * 上实测：包名 `dshmarket` 的条目 id 是 `dsh-market`、`dsh-drop-caret` 是 `drop-caret`、
 * `dsh-webview-clipboard` 是 `webview-clipboard` —— **包名不能当条目 id**。
 * 写一条打不中的行时 DSH 只打印 `patch: entry "X" not found` 然后照常加载那个插件，
 * 于是界面显示"已停用"、实际仍在运行（真机与本机 dsh 都复现过）。
 *
 * 只取这个包**自己 insert 的行**：bundle patch 里也可能有配置其它插件的
 * 行，例如给官方的 `attachment-local` 加配置；把 `disabled: true` 写到那种行上，
 * 会把附件和模型一起停掉）。
 *
 * 解析不出任何 id 时返回空列表，调用方**必须**据此拒绝操作——退回"用包名当 id"
 * 就是上面那个 bug。
 */
object PackageRowIds {

    /** 能安全写进层的行 id 形态：只写裸标量（与上游 `ROW_ID_RE` 一致）。 */
    private val ROW_ID_RE = Regex("^[A-Za-z0-9_.-]+$")

    /** 块状插入里的条目行：4 空格缩进（对齐上游 `readUserPatchState`）。 */
    private val INSERT_ROW_RE = Regex("""^ {4}- id: *([A-Za-z0-9_.-]+) *$""")

    /** 内联插入 `- insert: [ { id: X } ]`。 */
    private val INLINE_INSERT_RE = Regex("""^- insert: *\[(.*)$""")
    private val INLINE_ID_RE = Regex("""\bid: *([A-Za-z0-9_.-]+)""")

    /** `- insert:` 头（块状）。 */
    private val INSERT_HEAD_RE = Regex("""^- insert: *$""")

    /** 顶层「另一个 patch 条目」行：插入块到这里结束。 */
    private val TOP_ROW_RE = Regex("""^- """)

    private const val CONVENTIONAL_PATCH = "cordis.patch.yml"

    /**
     * 解析一个包拥有的行 id。
     *
     * @param packageDir 包在 profile 里的安装目录
     * @param declaredPatch `dsh.bundle.patch` 的声明值（相对包目录，可能是 `./x.yml`）
     */
    fun forPackage(packageDir: File, declaredPatch: String?): List<String> {
        // 两个来源都读，和上游一致：声明的位置 + 约定位置（有些包只在根放
        // cordis.patch.yml，不声明 dsh.bundle.patch，loader 同样会去探它）。
        val files = buildList {
            declaredPatch?.let { add(resolve(packageDir, it)) }
            add(File(packageDir, CONVENTIONAL_PATCH))
        }.distinct()
        val ids = LinkedHashSet<String>()
        files.forEach { file -> ids.addAll(insertedIds(file)) }
        return ids.toList()
    }

    /** 包的清单里声明的 `dsh.bundle.patch`；没有就返回 null。 */
    fun declaredPatchOf(manifestText: String): String? = runCatching {
        val dsh = JSONObject(manifestText.trimStart('\uFEFF')).optJSONObject("dsh")
        when (val bundle = dsh?.opt("bundle")) {
            is JSONObject -> (bundle.opt("patch") as? String)?.takeIf { it.isNotBlank() }
            // 字符串形态：只有明显是 yml 时才当补丁路径，其余不猜。
            is String -> bundle.takeIf { it.endsWith(".yml") || it.endsWith(".yaml") }
            else -> null
        }
    }.getOrNull()

    /** 读文件取插入行 id；文件缺失/读不出来返回空（不猜）。 */
    fun insertedIds(patchFile: File): List<String> =
        runCatching { patchFile.readText(Charsets.UTF_8) }.getOrNull()?.let(::insertedIds)
            ?: emptyList()

    /**
     * 按行扫描补丁正文取插入行 id。
     *
     * 刻意不做 YAML 解析：文件里可能有我们这版不认识的结构，而我们要的只是
     * "这个包 insert 了哪些 id"这一件事。
     */
    fun insertedIds(text: String): List<String> {
        val ids = LinkedHashSet<String>()
        var inInsert = false
        text.split('\n').forEach { raw ->
            val line = raw.trimEnd('\r')
            INLINE_INSERT_RE.find(line)?.let { inline ->
                INLINE_ID_RE.find(inline.groupValues[1])?.groupValues?.get(1)?.let { id ->
                    if (ROW_ID_RE.matches(id)) ids.add(id)
                }
                inInsert = false
                return@forEach
            }
            if (INSERT_HEAD_RE.matches(line)) {
                inInsert = true
                return@forEach
            }
            // 顶层又起了一个 patch 条目 → 插入块结束（否则会把邻居的行算成自己的）。
            if (TOP_ROW_RE.containsMatchIn(line)) inInsert = false
            if (!inInsert) return@forEach
            INSERT_ROW_RE.find(line)?.groupValues?.get(1)?.let { id ->
                if (ROW_ID_RE.matches(id)) ids.add(id)
            }
        }
        return ids.toList()
    }

    private fun resolve(packageDir: File, declared: String): File {
        val cleaned = declared.trim().removePrefix("file://").removePrefix("./")
        val file = File(cleaned)
        return if (file.isAbsolute) file else File(packageDir, cleaned)
    }
}
