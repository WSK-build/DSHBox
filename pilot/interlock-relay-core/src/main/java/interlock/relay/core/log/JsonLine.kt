package interlock.relay.core.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 把若干键值对序列化成一行紧凑 JSON，供记录落盘使用。 */
internal object JsonLine {

    fun of(fields: List<Pair<String, Any?>>): String =
        fields.filter { it.second != null }
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
                quote(key) + ":" + encode(value)
            }

    private fun encode(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        is List<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { encode(it) }
        else -> quote(value.toString())
    }

    /** 转义控制字符与引号，避免手工拼接产出非法 JSON。 */
    private fun quote(text: String): String {
        val builder = StringBuilder(text.length + 8)
        builder.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (ch < ' ') {
                    builder.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(ch)
                }
            }
        }
        return builder.append('"').toString()
    }
}

internal fun timestampFormat(): SimpleDateFormat =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

internal fun dateStamp(date: Date): String =
    SimpleDateFormat("yyyyMMdd", Locale.US).format(date)
