package com.dshbox.app.util.viewer.highlight

import com.dshbox.app.util.viewer.highlight.HighlightEngine.TokenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自研高亮引擎回归（返工修正 #1 锁定）：未命中字符（标识符/普通词）必须归 NORMAL，
 * 不得兜成 OPERATOR——否则 `int count = 42;` 里变量名与运算符同色。
 */
class HighlightEngineTest {

    private fun types(languageId: String, line: String) =
        HighlightEngine.tokenize(languageId, line).filter { it.type != TokenType.NORMAL }

    @Test
    fun unmatchedIdentifiersAreNormalNotOperator() {
        val tokens = HighlightEngine.tokenize("java", "count")
        assertEquals(listOf(TokenType.NORMAL), tokens.map { it.type })
        // 类型名/变量名序列（空格两侧各自成 NORMAL token）
        val multi = HighlightEngine.tokenize("java", "String name")
        assertTrue(multi.all { it.type == TokenType.NORMAL })
        // 连续未命中字符（无空格）被合并为一个 NORMAL token
        assertEquals(listOf(TokenType.NORMAL), HighlightEngine.tokenize("java", "Stringname").map { it.type })
        assertEquals(1, HighlightEngine.tokenize("java", "Stringname").size)
    }

    @Test
    fun javaLineTokenTypes() {
        // `int count = 42;`：int→KEYWORD、count→NORMAL、=→OPERATOR、42→NUMBER、;→OPERATOR
        val t = HighlightEngine.tokenize("java", "int count = 42;")
        val pairs = t.map { it.type }
        assertEquals(TokenType.KEYWORD, pairs[0])
        assertTrue(pairs.contains(TokenType.NORMAL))
        assertTrue(pairs.contains(TokenType.NUMBER))
        assertTrue(pairs.contains(TokenType.OPERATOR))
        // 未出现注释/字符串
        assertTrue(!pairs.contains(TokenType.COMMENT))
        assertTrue(!pairs.contains(TokenType.STRING))
    }

    @Test
    fun commentsStringsNumbersKeepTypes() {
        // 注释优先于关键字（// 不被拆为运算符）
        val c = types("javascript", "//const")
        assertEquals(listOf(TokenType.COMMENT), c.map { it.type })
        // 字符串
        val s = types("json", "\"hello world\"")
        assertEquals(listOf(TokenType.STRING), s.map { it.type })
        // 数字
        val n = types("python", "42")
        assertEquals(listOf(TokenType.NUMBER), n.map { it.type })
        // shell 变量
        val v = types("shell", "\$HOME")
        assertEquals(listOf(TokenType.ANNOTATION), v.map { it.type })
    }

    @Test
    fun yamlKeysAreAnnotationNotOperator() {
        val t = HighlightEngine.tokenize("yaml", "key: value")
        val kinds = t.map { it.type }
        assertTrue(kinds.contains(TokenType.ANNOTATION)) // 键名
        assertTrue(kinds.contains(TokenType.OPERATOR))   // 冒号
        assertTrue(kinds.contains(TokenType.NORMAL))     // 值
    }

    @Test
    fun unsupportedLanguageYieldsEmpty() {
        assertEquals(emptyList<HighlightEngine.Token>(), HighlightEngine.tokenize("ruby", "def x; end"))
    }
}
