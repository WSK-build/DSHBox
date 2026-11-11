package com.dshbox.app.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * DSH 页签的两项通用偏好（与 [AppThemeState] 同构的轻量方案，共用同一
 * `app_settings` SharedPreferences 文件）。两项生效时机不同：
 *
 * - **本机 DSH 状态栏**（默认关 = 全屏显示，保持既有行为）：开启后本机
 *   DSH 页签不再隐藏系统状态栏，主页面的沉浸判定与内容 insets 随之让位；
 *   该项**拨动即时生效**——`localStatusBarVisible` 是 Compose 状态，主框架
 *   的沉浸判定在重组时读现值。远程会话恒全屏，不受该开关影响。
 * - **DSH 回车键**（默认「换行」）：换行档把裸 Enter 映射为换行（页面
 *   注入层拦截补发，见 `dsh-page-compat.js`），发送档恢复页面既有发送
 *   行为；该项**拨动即时生效**：页面注入层每次按键读取
 *   可变全局 `window.__dshboxEnterNewline`，渲染器的 `setEnterKeyNewline`
 *   经 evaluateJavascript 翻转该全局——本机容器（DshWebViewScreen 的
 *   Compose 观察）与远程会话（SessionRendererHost 表面 update 块）在
 *   设置变化时推送，已建立的页面无需重载。装配期仍按现值写注入配置，
 *   作为首跳初值。
 *   **变体覆盖面：gecko 变体不生效。** gecko 的页面兼容配置由构建期
 *   生成（`dsh-renderer/gecko` 的 GeneratePageCompatConfig），只固化
 *   `trustedOrigin` 一项、不携带本开关，页面注入层于是按"字段缺席"
 *   处理为发送档；渲染器推送 API 的默认实现也是 no-op——无论设置页
 *   如何拨动，gecko 页签的 Enter 恒为页面既有行为。
 *
 * 状态以 `mutableStateOf` 承载：设置页写入即时反映到 UI，主框架与容器在
 * 组合/装配期读取现值即得到持久化值。
 */
object AppDshBehaviorState {
    /** 与 [AppThemeState] / [AppLocaleState] 共用同一 SharedPreferences 文件。 */
    private const val PREFS_NAME = "app_settings"

    private const val KEY_DSH_LOCAL_STATUS_BAR = "dsh_local_status_bar"
    private const val KEY_DSH_ENTER_KEY_NEWLINE = "dsh_enter_key_newline"

    /** 本机 DSH 页签是否显示系统状态栏（false = 全屏沉浸，默认值）。 */
    var localStatusBarVisible by mutableStateOf(false)
        private set

    /** 换行档为 true（裸 Enter 映射换行）；发送档为 false（页面既有行为）。 */
    var enterKeyNewline by mutableStateOf(true)
        private set

    /** 恢复两项持久化值；DshApp.onCreate 调用（先于任何读取方装配）。 */
    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        localStatusBarVisible = prefs.getBoolean(KEY_DSH_LOCAL_STATUS_BAR, false)
        enterKeyNewline = prefs.getBoolean(KEY_DSH_ENTER_KEY_NEWLINE, true)
    }

    fun setLocalStatusBarVisible(context: Context, value: Boolean) {
        localStatusBarVisible = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DSH_LOCAL_STATUS_BAR, value)
            .apply()
    }

    fun setEnterKeyNewline(context: Context, value: Boolean) {
        enterKeyNewline = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DSH_ENTER_KEY_NEWLINE, value)
            .apply()
    }
}
