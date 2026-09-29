package com.dshbox.app.ui.webview

import android.content.Intent
import android.net.Uri

/**
 * 「用外部浏览器打开 DSH」的两个意图构造器。
 *
 * ## 为什么必须带 token
 * 外部浏览器拿不到 WebView 的 cookie 交换，也没有我们的注入通道，
 * 不带 token 直接开 [com.dshbox.app.common.Constants.DSH_BASE_URL] 会停在鉴权页——
 * 这正是首页该入口"点开不可用"的原因。
 *
 * ## 为什么要套一层 chooser（而不是裸隐式意图）
 * `ACTION_VIEW` + `http://127.0.0.1:3080` 是**隐式**意图：任何声明了
 * `<data android:scheme="http" android:host="127.0.0.1"/>` 的应用都能参与解析。
 * 而 URL 里的 launch token 等同于 DSH 网页端（进而沙箱）的凭据——一旦被别的应用
 * 静默接管，等于把沙箱控制权交出去。chooser 让用户显式选择、也让接管无处藏身。
 *
 * [Intent.FLAG_ACTIVITY_NEW_TASK]：调用方可能是 Service（通知栏入口），
 * 从非 Activity 上下文启动必须带该标志；Activity 上下文带它也无害。
 */
internal fun dshBrowserIntent(base: String, token: String?): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(dshUrlWithToken(base, token)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** 带 [title] 的选择器版本，用于把 token 交给用户显式指定的浏览器。 */
internal fun dshBrowserChooser(base: String, token: String?, title: String): Intent =
    Intent.createChooser(dshBrowserIntent(base, token), title)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
