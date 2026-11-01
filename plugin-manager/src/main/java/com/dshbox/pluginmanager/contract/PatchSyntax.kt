package com.dshbox.pluginmanager.contract

/**
 * 宿主补丁层方言的契约：`cordis.patch.yml` 与 `--patch` 层的行语法、可写形态。
 *
 * 写入方与读取方必须用同一套正则，否则会出现"写得进、读不出"。
 * 方言规则：层文件是单个顶层 YAML 序列；改/停用已有条目用 `- id:` + 两格 `disabled:`；
 * 新建条目用 `- insert:` + 四格 `- id:` 子项。
 */
object PatchSyntax {

    // ───────────────── 行语法（读写共用） ─────────────────

    /** 顶层 `- id: <值>`，用于改或停用已有条目。 */
    val TOP_ENTRY = Regex("""^- id: *(.+?) *$""")

    /** `- insert:` 头，可能带内联内容；写入方用它整体保留内联部分。 */
    val INSERT_HEAD = Regex("""^- insert: *(.*)$""")

    /** 裸 `- insert:` 头（行尾无内容）。与 [INSERT_HEAD] 语义不同，不可互换。 */
    val INSERT_HEAD_BARE = Regex("""^- insert: *$""")

    /** 块状插入里的子条目行（缩进四格）。 */
    val INSERT_ITEM = Regex("""^ {4}- id: *(.+?) *$""")

    /** 内联插入 `- insert: [ { id: X } ]`。 */
    val INLINE_INSERT = Regex("""^- insert: *\[(.*)$""")

    /** 内联插入里的 id：裸标量、单引号、双引号三种写法。 */
    val INLINE_ID = Regex("""\bid: *('([^']*)'|"([^"]*)"|([^'",}\s\]]+))""")

    /**
     * 从内联插入的正文里取 id（已去引号）。
     *
     * 不能只认裸标量：作用域包名的条目 id 必须带引号写，只认裸标量会把它读丢。
     */
    fun inlineId(value: String): String? {
        val match = INLINE_ID.find(value) ?: return null
        return listOf(match.groupValues[2], match.groupValues[3], match.groupValues[4])
            .firstOrNull { it.isNotEmpty() }
    }

    /** 顶层另一个 patch 条目行：插入块到此结束。 */
    val TOP_ROW_START = Regex("""^- """)

    /** 缩进两格的字段行。 */
    val FIELD_NAME = Regex("""^ {2}name: *(.*?) *$""")
    val FIELD_DISABLED = Regex("""^ {2}disabled: *(true|false) *$""")

    /** 读取方用的严格 disabled 行：只回答这一行写了什么，不含捕获组。 */
    val DISABLED_TRUE = Regex("""^ {2}disabled: *true *$""")
    val DISABLED_FALSE = Regex("""^ {2}disabled: *false *$""")

    /** 任意缩进的 `name:` 值，用于按包名引用扫描。 */
    val NAME_VALUE = Regex("""^\s*-?\s*name:\s*['"]?([^'"\s]+)""")

    // ───────────────── 可写形态 ─────────────────

    /**
     * 能安全写进层的 id 字符集：不含空格、引号、换行。
     *
     * 含冒号是必须的：子条目 id 形如 `pkg:sub`，宿主自己也会合成这类 id。
     * 这类 id 一律由 [renderId] 加单引号写（`- id: 'pkg:sub'`），不会破坏 YAML。
     */
    private val WRITABLE_ID = Regex("^[-A-Za-z0-9_./@+:]{1,200}$")

    /** 裸标量形态，不需要引号。 */
    val PLAIN_ID = Regex("^[A-Za-z0-9_.-]+$")

    /**
     * 读取侧认的 id 形态：只要层里能出现就算数（不含引号、空白、逗号与括号）。
     *
     * 必须比 [PLAIN_ID] 宽：作用域包名、子条目 id（`pkg:sub`）都不符合裸标量，
     * 用 [PLAIN_ID] 解析会把它们读丢，进而让残留行审计把真实生效的停用行判成孤儿。
     */
    val LAYER_ID = Regex("""^[^'"\s,{}\[\]]+$""")

    /** 空层正文，禁用本层的合法写法。 */
    const val EMPTY_LAYER = "[]\n"

    /** 是否可安全写入的 id 形态。 */
    fun isValidId(id: String): Boolean = WRITABLE_ID.matches(id)

    /** 按需加引号：非裸标量形态一律单引号包裹。 */
    fun renderId(id: String): String = if (PLAIN_ID.matches(id)) id else "'$id'"

    /** YAML 单引号标量里的 `'` 必须写成 `''`，否则破坏整份文档。 */
    fun escapeSingleQuoted(value: String): String = value.replace("'", "''")

    /** 去掉 YAML 引号（单或双）。 */
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

    /** 包内的约定补丁文件名；未声明 `dsh.bundle.patch` 时 loader 也会探它。 */
    const val CONVENTIONAL_PATCH = "cordis.patch.yml"
}
