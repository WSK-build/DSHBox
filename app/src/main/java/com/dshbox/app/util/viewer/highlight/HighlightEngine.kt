package com.dshbox.app.util.viewer.highlight

/**
 * 自研语法高亮引擎（1.2.0 §6.4，纯 JVM，零第三方依赖）。
 *
 * 骨架 = 「规则表 + 单遍扫描分词器」；Sora 适配层见 ui/files/viewer/SoraLanguages.kt
 * （SimpleAnalyzeManager 子类按行取 [tokenize] 结果生成 Spans）。
 *
 * 首发覆盖 6 种语言（§6.4）：json、yaml、shell、python、javascript、java(+kotlin 共用
 * C 系规则)。规则为「逐位置 lookingAt」的行内正则——跨行结构（多行注释/三引号字符串/
 * 多行模板串）不做跨行状态跟踪，M2 已知限制（记 MODIFICATION_LOG；textmate 1.2.x 评估）。
 *
 * 已知限制（按设计接受）：行内正则无法识别「行中间开始的多行注释」，例如
 * `code /* comment */` 会把 `code` 后整行按注释处理；换行即恢复正常分词，不污染后续行。
 */
object HighlightEngine {

    /** 词法类型（与 UI 配色映射解耦，保持纯 JVM）。 */
    enum class TokenType { NORMAL, KEYWORD, STRING, COMMENT, NUMBER, OPERATOR, ANNOTATION, FUNCTION }

    data class Token(val start: Int, val end: Int, val type: TokenType)

    /** 规则：按数组顺序优先匹配（注释/字符串优先于关键字，防 `//` 被拆成两个运算符）。 */
    private data class Rule(val type: TokenType, val pattern: Regex)

    private fun rule(type: TokenType, pattern: String) = Rule(type, Regex(pattern))

    // ---------------- 语言规则表（每种 20–40 行，§6.4） ----------------

    private val JSON = listOf(
        rule(TokenType.COMMENT, "//[^\n]*"),
        rule(TokenType.STRING, "\"(?:[^\"\\\\]|\\\\.)*\""),
        rule(TokenType.KEYWORD, "\\b(?:true|false|null)\\b"),
        rule(TokenType.NUMBER, "-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b"),
        rule(TokenType.OPERATOR, "[{}\\[\\],:]"),
    )

    private val YAML = listOf(
        rule(TokenType.COMMENT, "#[^\n]*"),
        rule(TokenType.STRING, "\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'"),
        rule(TokenType.KEYWORD, "\\b(?:true|false|null|yes|no|on|off)\\b"),
        rule(TokenType.ANNOTATION, "-?[ \\t]*[A-Za-z_][\\w.\\-/]*(?=:)"), // 键名（matchAt 锚定当前位，无需 ^）
        rule(TokenType.NUMBER, "-?\\b\\d+(?:\\.\\d+)?\\b"),
        rule(TokenType.OPERATOR, "[-:>|*&]"),
    )

    private val SHELL = listOf(
        rule(TokenType.COMMENT, "#[^\n]*"),
        rule(TokenType.STRING, "\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'"),
        rule(TokenType.ANNOTATION, "\\$\\{[^}]*\\}|\\$[A-Za-z_][\\w]*|\\$[0-9@#?*!]"),
        rule(TokenType.KEYWORD, "\\b(?:if|then|elif|else|fi|for|while|until|do|done|case|esac|in|function|select|time|return|exit|local|export|readonly|declare|unset|shift|source|alias|break|continue|eval|exec|set|trap|wait)\\b"),
        rule(TokenType.NUMBER, "\\b\\d+\\b"),
        rule(TokenType.OPERATOR, "[|&;<>()`~=!+\\-]"),
    )

    private val PYTHON = listOf(
        rule(TokenType.COMMENT, "#[^\n]*"),
        rule(TokenType.STRING, "[rbfu]{0,2}\"\"\"[^\n]*|'''[^\n]*|[rbfu]{0,2}\"(?:[^\"\\\\]|\\\\.)*\"|[rbfu]{0,2}'[^']*'"),
        rule(TokenType.ANNOTATION, "@[A-Za-z_][\\w.]*"),
        rule(TokenType.KEYWORD, "\\b(?:def|class|if|elif|else|for|while|in|not|and|or|is|import|from|as|return|with|try|except|finally|lambda|None|True|False|self|cls|raise|yield|global|nonlocal|assert|del|pass|break|continue|async|await)\\b"),
        rule(TokenType.NUMBER, "\\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b"),
        rule(TokenType.FUNCTION, "\\b[A-Za-z_][\\w]*(?=\\s*\\()"),
        rule(TokenType.OPERATOR, "[+\\-*/%=<>!&|^~:,.(){}\\[\\]]"),
    )

    private val JAVASCRIPT = listOf(
        rule(TokenType.COMMENT, "//[^\n]*|/\\*(?:[^*]|\\*(?!/))*\\*/"),
        rule(TokenType.STRING, "`(?:[^`\\\\]|\\\\.)*`|\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'"),
        rule(TokenType.KEYWORD, "\\b(?:var|let|const|function|return|if|else|for|while|do|break|continue|new|this|typeof|instanceof|in|of|switch|case|default|throw|try|catch|finally|class|extends|super|import|export|from|as|async|await|yield|delete|void|null|true|false|undefined|NaN|static|get|set)\\b"),
        rule(TokenType.NUMBER, "\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b"),
        rule(TokenType.FUNCTION, "\\b[A-Za-z_$][\\w$]*(?=\\s*\\()"),
        rule(TokenType.OPERATOR, "[+\\-*/%=<>!&|^~?:;,{}()\\[\\].]"),
    )

    private val JAVA = listOf(
        rule(TokenType.COMMENT, "//[^\n]*|/\\*(?:[^*]|\\*(?!/))*\\*/"),
        rule(TokenType.STRING, "\"\"\"[^\n]*|\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'"),
        rule(TokenType.ANNOTATION, "@[A-Za-z_][\\w.]*"),
        rule(TokenType.KEYWORD, "\\b(?:public|private|protected|class|interface|enum|record|extends|implements|static|final|abstract|void|int|long|short|byte|char|boolean|float|double|import|package|return|new|this|super|if|else|for|while|do|switch|case|default|break|continue|try|catch|finally|throw|throws|null|true|false|instanceof|transient|volatile|synchronized|native|assert|var|fun|val|when|object|data|sealed|open|override|lateinit|internal|companion|init|constructor|suspend|inline|operator|out|in|by|is|as)\\b"),
        rule(TokenType.NUMBER, "\\b(?:0[xX][0-9a-fA-F_]+[lL]?|\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[fFdDlL]?)\\b"),
        rule(TokenType.FUNCTION, "\\b[A-Za-z_$][\\w$]*(?=\\s*\\()"),
        rule(TokenType.OPERATOR, "[+\\-*/%=<>!&|^~?:;,{}()\\[\\].]"),
    )

    private val LANGUAGES: Map<String, List<Rule>> = mapOf(
        "json" to JSON,
        "yaml" to YAML,
        "shell" to SHELL,
        "python" to PYTHON,
        "javascript" to JAVASCRIPT,
        "java" to JAVA,
    )

    /** 支持高亮的语言 id（与 FileTypeClassifier.LANG_BY_EXT 对齐）。 */
    val supportedLanguages: Set<String> get() = LANGUAGES.keys

    // ---------------- 分词（单遍扫描，逐位置匹配） ----------------

    /**
     * 对单行文本分词（纯函数）。返回按 start 升序、互不重叠的 token 列表；
     * 未被任何规则命中的字符归入 NORMAL（由适配层补齐空隙）。
     */
    fun tokenize(languageId: String, line: String): List<Token> {
        val rules = LANGUAGES[languageId] ?: return emptyList()
        val tokens = ArrayList<Token>()
        var pos = 0
        val n = line.length
        while (pos < n) {
            val c = line[pos]
            if (c == ' ' || c == '\t') {
                pos++
                continue
            }
            var matched: Token? = null
            for (r in rules) {
                val m = r.pattern.matchAt(line, pos) ?: continue
                if (m.value.isEmpty()) continue
                matched = Token(pos, pos + m.value.length, r.type)
                break
            }
            val t = matched
            if (t != null) {
                tokens += t
                pos = t.end
            } else {
                // 未命中（标识符/普通词等）归 NORMAL——标点/运算符已由各语言的
                // OPERATOR 规则覆盖，不得在此兜成 OPERATOR（否则变量名与 +*/= 同色）
                val len = Character.charCount(line.codePointAt(pos))
                tokens += Token(pos, pos + len, TokenType.NORMAL)
                pos += len
            }
        }
        return mergeAdjacent(tokens)
    }

    /** 合并相邻同类型 token（含未命中单字符归并），减少 Span 数量。 */
    private fun mergeAdjacent(tokens: List<Token>): List<Token> {
        if (tokens.isEmpty()) return tokens
        val out = ArrayList<Token>(tokens.size)
        var cur = tokens.first()
        for (i in 1 until tokens.size) {
            val next = tokens[i]
            cur = if (cur.end == next.start && cur.type == next.type) {
                cur.copy(end = next.end)
            } else {
                out += cur
                next
            }
        }
        out += cur
        return out
    }
}
