package com.dshbox.remote.parse

/**
 * 扁平 JSON 对象的最小字段读取器：服务于 JSON 配对描述符（顶层成员值限定
 * 字符串与数字/布尔字面量，描述符契约内不存在嵌套结构与数组）。
 *
 * 选型说明：Android 平台自带 org.json 可承担解析，但 JVM 单测环境需为其
 * 额外引入测试期依赖；描述符字段固定且简单，这里以严格的最小实现保持零
 * 新增依赖。结构非法（缺引号、缺冒号、悬挂逗号、非法转义、嵌套对象）一律
 * 返回 null，由调用方按不匹配处理。
 */
internal object FlatJsonReader {

    /** 读取顶层成员（保序，同名成员后者覆盖）；非对象或结构非法返回 null。 */
    fun read(raw: String): Map<String, String>? {
        val text = raw.trim()
        if (text.length < 2 || !text.startsWith("{") || !text.endsWith("}")) return null
        val members = LinkedHashMap<String, String>()
        val end = text.length - 1 // 闭合花括号的下标
        var index = 1
        while (true) {
            index = skipWhitespace(text, index, end)
            if (index == end) return members // 到达闭合括号：成员读取结束（空对象为空映射）
            if (text[index] != '"') return null
            val key = readString(text, index) ?: return null
            index = skipWhitespace(text, key.second, end)
            if (index >= end || text[index] != ':') return null
            index = skipWhitespace(text, index + 1, end)
            if (index >= end) return null
            val value: Pair<String, Int> = if (text[index] == '"') {
                readString(text, index) ?: return null
            } else {
                // 裸字面量（数字/布尔/null）：读到最近的分隔符为止
                val stop = text.indexOfAny(charArrayOf(',', '}'), index)
                if (stop < 0 || stop > end) return null
                val literal = text.substring(index, stop).trim()
                if (literal.isEmpty()) return null
                Pair(literal, stop)
            }
            members[key.first] = value.first
            index = skipWhitespace(text, value.second, end)
            when {
                // 到达闭合括号：成员读取结束
                index == end -> return members
                text[index] == ',' -> index += 1
                else -> return null
            }
        }
    }

    /** 从 text[start] 的引号起读取字符串字面量（处理转义）；返回 (值, 结束后下标)。 */
    private fun readString(text: String, start: Int): Pair<String, Int>? {
        val builder = StringBuilder()
        var index = start + 1
        while (index < text.length) {
            when (val character = text[index]) {
                '"' -> return Pair(builder.toString(), index + 1)
                '\\' -> {
                    if (index + 1 >= text.length) return null
                    when (val escaped = text[index + 1]) {
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        '/' -> builder.append('/')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            if (index + 5 >= text.length) return null
                            val code = text.substring(index + 2, index + 6).toIntOrNull(16) ?: return null
                            builder.append(code.toChar())
                            index += 4
                        }
                        else -> return null
                    }
                    index += 2
                }
                else -> {
                    builder.append(character)
                    index += 1
                }
            }
        }
        return null // 字符串未闭合
    }

    private fun skipWhitespace(text: String, from: Int, end: Int): Int {
        var index = from
        while (index < end && text[index].isWhitespace()) index += 1
        return index
    }
}
