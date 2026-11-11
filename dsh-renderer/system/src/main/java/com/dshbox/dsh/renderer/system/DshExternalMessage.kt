package com.dshbox.dsh.renderer.system

import java.net.URI

/**
 * 页面外链宿主通道消息的解析与校验（纯函数，JVM 单测锁定）。
 *
 * 页面侧兼容脚本把页面请求的外部 http(s) 地址装进 JSON 帧消息投给宿主
 * （`{"type":"open-external","url":"…"}`）；此处的校验决定该消息能否转成
 * 外部打开事件。判定口径：绝对 http(s) 地址、无内嵌凭据（user:pass@host）、
 * 长度不超上限；其余一律返回 null，调用方只记一条不含地址的告警。
 */
internal object DshExternalMessage {

    /** 消息类型字段与页面侧约定一致（[DshHostMessageFrame.type]）。 */
    private const val EXPECTED_TYPE = "open-external"

    /** 目标地址长度上限（与本机浏览器外链口径一致）：超过即拒开。 */
    const val MAX_URL_CHARS = 8_192

    /**
     * 解析网格消息原文：结构非法（非 JSON / 类型不符 / url 字段缺失）、
     * 非 http(s) 绝对地址、带内嵌凭据或超长地址一律 null。
     */
    fun parse(raw: Any?): String? {
        val text = raw as? String ?: return null
        val frame = parseFrame(text) ?: return null
        val url = frame.url ?: return null
        return parseUrl(url)
    }

    /** 结构层解析：JSON 对象、类型字段与 url 字段类型校验后仅取 url 文本。 */
    private fun parseFrame(text: String): DshHostMessageFrame? {
        if (text.length > MAX_MESSAGE_CHARS) return null
        val value = runCatching { DshHostBridgeJson.parse(text) }.getOrNull() ?: return null
        if (value.type != EXPECTED_TYPE) return null
        return value
    }

    /**
     * 地址层校验：绝对 http(s)（scheme 必须显式出现，相对地址无 scheme）、
     * host 非空、无 userInfo（user:pass@host 是钓鱼形态，不外放）、长度上限。
     */
    private fun parseUrl(raw: String): String? {
        if (raw.isBlank() || raw.length > MAX_URL_CHARS) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        if (uri.userInfo != null) return null
        return raw
    }

    private const val MAX_MESSAGE_CHARS = MAX_URL_CHARS + 64
}

/** 外链消息帧的最小模型（类型字段与 url 文本；多余字段忽略）。 */
internal data class DshHostMessageFrame(val type: String?, val url: String?)

/**
 * 外链消息 JSON 解析的最小实现：只承载双字段扁平对象，不需要完整 JSON 能力
 * （解析自持，不依赖运行期 JSON 库，JVM 单测可直接覆盖）。
 *
 * 只识别双字段扁平对象：`{"type":"open-external","url":"https://…"}`。字段
 * 顺序无关；字符串经标准 JSON 转义（`\"` `\\` `\/` `\uXXXX` 等）还原；其它
 * 值类型（数字/布尔/null/嵌套）按缺字段处理。重复键（`type`、`url` 或任意
 * 未知名出现两次及以上）一律整条拒绝（与"结构严格"口径一致，不做后写覆盖）。
 * 对陷阱输入直接 null，不做前后缀截断：`{"type":...}{"url":...}`、
 * `{"type":...} trailing` 均整条拒绝（多余非空白尾巴即结构非法）。
 */
internal object DshHostBridgeJson {

    fun parse(text: String): DshHostMessageFrame? {
        var index = 0
        skipWhitespace(text, index)?.let { index = it }
        if (index >= text.length || text[index] != '{') return null
        index += 1
        var type: String? = null
        var url: String? = null
        val seenKeys = HashSet<String>()
        index = skipWhitespace(text, index) ?: return null
        if (index < text.length && text[index] == '}') {
            // 空对象 = 两个关键字段都缺失，交由调用方按缺字段拒绝
            index += 1
        } else {
            while (true) {
                val key = readString(text, index) ?: return null
                index = key.second
                // 重复键即结构非法（含 type/url 与未知名），整条拒绝
                if (!seenKeys.add(key.first)) return null
                val afterColon = skipWhitespace(text, key.second) ?: return null
                if (afterColon >= text.length || text[afterColon] != ':') return null
                index = skipWhitespace(text, afterColon + 1) ?: return null
                if (index >= text.length || text[index] != '"') return null
                val value = readString(text, index) ?: return null
                when (key.first) {
                    "type" -> type = value.first
                    "url" -> url = value.first
                }
                index = skipWhitespace(text, value.second) ?: return null
                when {
                    index < text.length && text[index] == ',' -> {
                        index = skipWhitespace(text, index + 1) ?: return null
                        if (index >= text.length || text[index] != '"') return null
                    }
                    index < text.length && text[index] == '}' -> {
                        index += 1
                        break
                    }
                    else -> return null
                }
            }
        }
        // 尾部只允许空白：多余非空白尾巴 = 结构非法，整条拒绝
        if (skipWhitespace(text, index) != null) return null
        return DshHostMessageFrame(type = type, url = url)
    }

    /** 跳过空白返回首个非空白下标；纯空白返回 null。 */
    private fun skipWhitespace(text: String, from: Int): Int? {
        var index = from.coerceAtLeast(0)
        while (index < text.length) {
            val character = text[index]
            if (character == ' ' || character == '\t' || character == '\n' || character == '\r') {
                index += 1
            } else {
                return index
            }
        }
        return null
    }

    /** 从 [from]（须指向起始引号）读一个 JSON 字符串，返回值文本与结束下标。 */
    private fun readString(text: String, from: Int): Pair<String, Int>? {
        if (from < 0 || from >= text.length || text[from] != '"') return null
        val value = StringBuilder()
        var index = from + 1
        while (index < text.length) {
            val character = text[index]
            when {
                character == '"' -> return value.toString() to (index + 1)
                character == '\\' -> {
                    if (index + 1 >= text.length) return null
                    when (val escape = text[index + 1]) {
                        '"' -> value.append('"')
                        '\\' -> value.append('\\')
                        '/' -> value.append('/')
                        'b' -> value.append('\b')
                        'f' -> value.append('\u000C')
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'u' -> {
                            if (index + 6 > text.length) return null
                            val digits = text.substring(index + 2, index + 6)
                            val codePoint = digits.toIntOrNull(16) ?: return null
                            value.append(codePoint.toChar())
                            index += 6
                            continue
                        }
                        else -> return null
                    }
                    index += 2
                }
                character.code < 0x20 -> return null
                else -> value.append(character).also { index += 1 }
            }
        }
        return null
    }
}
