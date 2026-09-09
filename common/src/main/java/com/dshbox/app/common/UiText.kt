package com.dshbox.app.common

import android.content.Context

/**
 * 可本地化的用户可见文案（，多语言适配）。
 *
 * 领域层 / util 层（无 Context 的纯 Kotlin）不再直接产出最终语言的文案，而是
 * 产出 [UiText]：静态或带参的字符串资源引用（[Res]），或确实无法资源化的动态
 * 内容（[Raw]，如第三方异常 message 透传、guest 命令输出）。UI / 通知侧在渲染
 * 点解析为当前语言字符串。
 *
 * 注意：`id` 是**产出方所在模块**的 R.string 资源 ID（Int），本类不感知任何 R
 * 类——common 不依赖各模块。占位符参数一律使用显式编号（%1$s / %1$d）。
 */
sealed class UiText {

    /** 字符串资源引用；[args] 为占位符参数（String / Int /CharSequence 等）。 */
    data class Res(val id: Int, val args: List<Any> = emptyList()) : UiText()

    /** 动态内容透传（第三方异常文本、guest 输出等），不做本地化。 */
    data class Raw(val text: String) : UiText()

    /** 多段拼接（各段各自本地化后按序连接，无分隔符；需要分隔符时把 [Separator] 段插入列表）。 */
    data class Concat(val parts: List<UiText>) : UiText()

    /** 固定分隔符段（配合 [Concat] 使用，如 "；"）。 */
    data class Separator(val text: String) : UiText()

    /** 在渲染点解析为当前语言字符串。 */
    fun asString(context: Context): String = when (this) {
        is Res -> context.getString(id, *args.toTypedArray())
        is Raw -> text
        is Concat -> parts.joinToString(separator = "") { it.asString(context) }
        is Separator -> text
    }

    companion object {
        /** 快捷构造：动态文本（null 归一为空串）。 */
        fun raw(text: String?): UiText = Raw(text.orEmpty())
    }
}
