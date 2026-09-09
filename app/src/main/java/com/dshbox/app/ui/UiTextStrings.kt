package com.dshbox.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dshbox.app.common.UiText

/**
 * [UiText] 的 Compose 渲染扩展——在组合内解析为当前界面语言。
 * 非 Compose 场景（通知/Toast）使用成员函数 `asString(context)`。
 * 注意：Concat 分支用显式 for 循环拼接（lambda 内不可调用 @Composable）。
 */
@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Res -> stringResource(id, *args.toTypedArray())
    is UiText.Raw -> text
    is UiText.Separator -> text
    is UiText.Concat -> {
        val sb = StringBuilder()
        for (part in parts) {
            sb.append(part.asString())
        }
        sb.toString()
    }
}
