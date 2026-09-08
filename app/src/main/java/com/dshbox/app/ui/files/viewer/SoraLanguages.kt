package com.dshbox.app.ui.files.viewer

import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * 自研高亮的 Sora 适配层（1.2.0 §6.4）。
 *
 * 分词逻辑全部在纯 JVM [com.dshbox.app.util.viewer.highlight.HighlightEngine]；
 * 本文件只做两件事：
 * 1. [RulesAnalyzeManager]：继承 sora `SimpleAnalyzeManager`，把全文按行喂给分词器，
 *    生成 per-line Spans（span 的 column = 该段在行内的**结束列**）；
 * 2. [RulesLanguage]：继承 `EmptyLanguage`，只替换 AnalyzeManager 与中断级别——
 *    补全/格式化/缩进推进全部沿用 Empty 默认实现。
 *
 * 已知限制：每次编辑全量重新分析（SimpleAnalyzeManager 语义），仅用于 ≤2MB 可编辑
 * 文本——超大文件在 UI 层强制只读/降级，不会触达此路径。
 */
internal class RulesLanguage(private val languageId: String) : EmptyLanguage() {

    private val analyzeManager = RulesAnalyzeManager(languageId)

    override fun getAnalyzeManager(): AnalyzeManager = analyzeManager

    override fun getInterruptionLevel(): Int = Language.INTERRUPTION_LEVEL_SLIGHT

    companion object {
        /** TokenType → EditorColorScheme 色号（浅色/深色主题各自解析到实际颜色）。 */
        internal fun colorIdOf(type: com.dshbox.app.util.viewer.highlight.HighlightEngine.TokenType): Int = when (type) {
            com.dshbox.app.util.viewer.highlight.HighlightEngine.TokenType.KEYWORD -> EditorColorScheme.KEYWORD
            com.dshbox.app.util.viewer.highlight.HighlightEngine.TokenType.STRING -> EditorColorScheme.LITERAL
            com.dshbox.app.util.viewer.highlight.HighlightEngine.TokenType.NUMBER -> EditorColorScheme.LITERAL
            com.dshbox.app.util.viewer.highlight.HighlightEngine.TokenType.COMMENT -> EditorColorScheme.COMMENT
            com.dshbox.app.util.viewer.highlight.HighlightEngine.TokenType.ANNOTATION -> EditorColorScheme.ATTRIBUTE_NAME
            com.dshbox.app.util.viewer.highlight.HighlightEngine.TokenType.FUNCTION -> EditorColorScheme.FUNCTION_NAME
            com.dshbox.app.util.viewer.highlight.HighlightEngine.TokenType.OPERATOR -> EditorColorScheme.OPERATOR
            else -> EditorColorScheme.TEXT_NORMAL
        }
    }
}

/** 全文分析器：整段 StringBuilder 按行分词 → MappedSpans。 */
internal class RulesAnalyzeManager(private val languageId: String) : SimpleAnalyzeManager<Unit>() {

    override fun analyze(content: StringBuilder, delegate: Delegate<Unit>): Styles {
        val builder = MappedSpans.Builder(content.length)
        val normalStyle = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)
        val text = content.toString()
        var lineIdx = 0
        var pos = 0
        val n = text.length
        while (pos <= n) {
            val nl = text.indexOf('\n', pos).let { if (it < 0) n else it }
            val line = text.substring(pos, nl)
            var col = 0
            for (token in com.dshbox.app.util.viewer.highlight.HighlightEngine.tokenize(languageId, line)) {
                if (token.start > col) {
                    builder.addIfNeeded(lineIdx, token.start, normalStyle)
                }
                builder.addIfNeeded(lineIdx, token.end, TextStyle.makeStyle(RulesLanguage.colorIdOf(token.type)))
                col = token.end
            }
            if (col < line.length) {
                builder.addIfNeeded(lineIdx, line.length, normalStyle)
            }
            lineIdx++
            if (nl >= n) break
            pos = nl + 1
        }
        builder.addNormalIfNull()
        return Styles(builder.build())
    }
}
