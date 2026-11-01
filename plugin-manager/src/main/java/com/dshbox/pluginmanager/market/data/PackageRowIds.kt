package com.dshbox.pluginmanager.market.data

import com.dshbox.pluginmanager.contract.HostContract
import com.dshbox.pluginmanager.contract.PatchSyntax
import com.dshbox.pluginmanager.layer.PluginLayer
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个包在补丁层里**自己的**条目 id。
 *
 * 移植自 dshmarket 的 `rowIdsForPackage`（`src/patch.ts:266`）。用它的真源码在本机 dsh
 * 上实测：包名 `dshmarket` 的条目 id 是 `dsh-market`、`dsh-drop-caret` 是 `drop-caret`、
 * `dsh-webview-clipboard` 是 `webview-clipboard` —— **包名不能当条目 id**。
 * 写一条打不中的行时 DSH 只打印 `patch: entry "X" not found` 然后照常加载那个插件，
 * 于是界面显示"已停用"、实际仍在运行（实机与本机 dsh 均可复现）。
 *
 * 只取这个包**自己 insert 的行**：bundle patch 里也可能有配置其它插件的
 * 行，例如给官方的 `attachment-local` 加配置；把 `disabled: true` 写到那种行上，
 * 会把附件和模型一起停掉）。
 *
 * 解析不出任何 id 时返回空列表，调用方**必须**据此拒绝操作——退回"用包名当 id"
 * 就是上面那个 bug。
 */
object PackageRowIds {

    // 行语法全部取自 PatchSyntax：本文件、PluginLayer、PatchDialect 必须共用同一套。
    // 读侧用 LAYER_ID（宽），不能用 PLAIN_ID（窄）：后者会把 `@scope/pkg` 这类
    // 真实存在的条目 id 读丢，让残留行审计误删真实生效的停用行。
    private val ROW_ID_RE = PatchSyntax.LAYER_ID
    private val INSERT_ROW_RE = PatchSyntax.INSERT_ITEM
    private val INLINE_INSERT_RE = PatchSyntax.INLINE_INSERT
    private val INSERT_HEAD_RE = PatchSyntax.INSERT_HEAD_BARE
    private val TOP_ROW_RE = PatchSyntax.TOP_ROW_START
    private val TOP_ENTRY_RE = PatchSyntax.TOP_ENTRY
    private val PATCH_NAME_RE = PatchSyntax.NAME_VALUE

    /**
     * 解析一个包拥有的行 id。
     *
     * @param packageDir 包在 profile 里的安装目录
     * @param declaredPatches `dsh.bundle.patch` 的声明值（相对包目录，可能是 `./x.yml`）
     */
    fun forPackage(packageDir: File, declaredPatches: List<String>): List<String> {
        val files = patchFiles(packageDir, declaredPatches)
        val ids = LinkedHashSet<String>()
        files.forEach { file -> ids.addAll(insertedIds(file)) }
        return ids.toList()
    }

    /**
     * 包的清单里声明的 `dsh.bundle.patch`，统一成列表。
     *
     * @param acceptsArray 宿主是否接受数组声明；为 false 时数组一律视为无声明。
     */
    fun declaredPatchFiles(manifestText: String, acceptsArray: Boolean = true): List<String> = runCatching {
        val dsh = JSONObject(manifestText.trimStart('\uFEFF')).optJSONObject(HostContract.FIELD_DSH)
        when (val bundle = dsh?.opt(HostContract.FIELD_BUNDLE)) {
            is JSONObject -> when (val patch = bundle.opt(HostContract.FIELD_PATCH)) {
                is String -> patch.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
                is JSONArray -> if (!acceptsArray) emptyList() else {
                    (0 until patch.length()).mapNotNull { index ->
                        (patch.opt(index) as? String)?.takeIf { value -> value.isNotBlank() }
                    }
                }
                else -> emptyList()
            }
            // 字符串形态：只有明显是 yml 时才当补丁路径，其余不猜。
            is String -> bundle.takeIf { it.endsWith(".yml") || it.endsWith(".yaml") }?.let { listOf(it) }
                ?: emptyList()
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

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
                PatchSyntax.inlineId(inline.groupValues[1])?.let { id ->
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
            INSERT_ROW_RE.find(line)?.groupValues?.get(1)?.let { PatchSyntax.unquote(it) }?.let { id ->
                if (ROW_ID_RE.matches(id)) ids.add(id)
            }
        }
        return ids.toList()
    }

    /**
     * 补丁正文里由**别人**拥有的顶层行 id（不是本包 insert 的那些）。
     *
     * 用于判断包自己的补丁是否会动到邻居的条目。
     */
    fun foreignRowIds(text: String): List<String> {
        val own = insertedIds(text).toSet()
        val foreign = LinkedHashSet<String>()
        var inInsert = false
        text.split('\n').forEach { raw ->
            val line = raw.trimEnd('\r')
            if (INSERT_HEAD_RE.matches(line) || INLINE_INSERT_RE.containsMatchIn(line)) {
                inInsert = true
                return@forEach
            }
            if (TOP_ROW_RE.containsMatchIn(line)) inInsert = false
            if (inInsert) return@forEach
            TOP_ENTRY_RE.find(line)?.let { match ->
                val id = PluginLayer.unquote(match.groupValues[1])
                if (id !in own) foreign.add(id)
            }
        }
        return foreign.toList()
    }

    /** 补丁正文里按名引用到的包名。 */
    fun patchTargetNames(patchFile: File): List<String> =
        runCatching { patchFile.readText(Charsets.UTF_8) }.getOrNull()?.let(::patchTargetNames)
            ?: emptyList()

    /** 补丁正文里按名引用到的包名（顶层行与插入行的 `name:` 都算）。 */
    fun patchTargetNames(text: String): List<String> {
        val names = LinkedHashSet<String>()
        text.split('\n').forEach { raw ->
            val line = raw.trimEnd('\r')
            PATCH_NAME_RE.find(line)?.let { match ->
                val name = PluginLayer.unquote(match.groupValues[1])
                if (name.isNotBlank()) names.add(name)
            }
        }
        return names.toList()
    }

    /** 声明位置与约定位置都要读：有些包只在根放 cordis.patch.yml，loader 同样会探它。 */
    fun patchFiles(packageDir: File, declaredPatches: List<String>): List<File> =
        buildList {
            declaredPatches.forEach { add(resolve(packageDir, it)) }
            add(File(packageDir, CONVENTIONAL_PATCH))
        }.distinct()

    internal const val CONVENTIONAL_PATCH = PatchSyntax.CONVENTIONAL_PATCH

    private fun resolve(packageDir: File, declared: String): File {
        val cleaned = declared.trim().removePrefix("file://").removePrefix("./")
        val file = File(cleaned)
        return if (file.isAbsolute) file else File(packageDir, cleaned)
    }
}
