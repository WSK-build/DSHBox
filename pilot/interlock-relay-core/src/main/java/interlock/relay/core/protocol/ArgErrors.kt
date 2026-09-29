package interlock.relay.core.protocol

import org.json.JSONObject

/**
 * 参数错误的措辞集中在这里。助手的错误码只有「用法错」与「用户拒绝」两类，
 * 而它区分两者的依据就是 reason 文本 —— 同一次拼错在三条后端的键表里必须拿到
 * 同一句话，否则它会学到"换个通路再试一次"。
 */
object ArgErrors {

    /** 想指定落点的写法。它们都不是参数，而是策略：由用户的执行模式偏好决定。 */
    private val DISPLAY_TARGETING_KEYS = setOf("display", "displayId", "surface", "display_id")

    /**
     * 未知键。[allowed] 给出时把这一条能力真正接受的键一并列出：只回
     * `unknown arg: keyword` 分不出"名字写错"与"这条能力没有这个入参"，调用方只能
     * 逐个拼写重试；同一份契约里有的键不合类型时会列出可接受取值，两种严格度不该并存。
     * 落点类键名单独处理——那不是一个可以改对的名字。
     */
    fun unknown(key: String, allowed: Collection<String>? = null): String = if (key in DISPLAY_TARGETING_KEYS) {
        "unknown arg: $key — which display a call runs on is decided by the user's surface " +
            "preference, not by the caller. Read capabilities.json " +
            "backend.shizuku.trustedDisplay {alive,displayId} to see whether a virtual display " +
            "exists, and each response's surface/degraded fields to see where it actually ran."
    } else {
        val accepted = allowed?.filter { it !in DISPLAY_TARGETING_KEYS }?.sorted()
        if (accepted.isNullOrEmpty()) "unknown arg: $key"
        else "unknown arg: $key — this call takes: ${accepted.joinToString(", ")}"
    }

    /**
     * 数值键不合类型时的措辞，带上**实际收到的值与类型**（字符串按 40 字符截断）。
     * 只说「要是个数字」分不出是调用方传错还是传输层把数字变成了字符串，
     * 而这两者的下一步完全不同：改脚本，还是去查通道。
     */
    fun notNumber(key: String, raw: Any?): String {
        val got = when {
            raw == null || raw === JSONObject.NULL -> "nothing"
            raw is String -> "\"${raw.take(40)}\" (string)"
            else -> "$raw (${raw.javaClass.simpleName})"
        }
        return "$key must be a number, got $got"
    }
}
