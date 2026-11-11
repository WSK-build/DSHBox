package com.dshbox.app.ui.home

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 「版本更新要点」卡的展示状态（与 [com.dshbox.app.ui.theme.AppDshBehaviorState]
 * 同构的轻量方案，共用同一 `app_settings` SharedPreferences 文件）。两个值：
 *
 * - **开关**（默认开）：设置 → 通用里可关闭；关闭后本版本与后续版本都不再
 *   展示该卡。
 * - **上次展示的 versionCode**：卡片在 `enabled && lastShown < 当前 versionCode`
 *   时展示（新安装 lastShown 缺省 0，同样展示一次）；点「关闭」把当前
 *   versionCode 写回，同一版本不再弹第二次。
 *
 * 版本要点文案随 APK 打包（strings.xml 随版本更新），读取方只做上述两个
 * 判定，不感知文案内容。
 */
object AppUpdateHighlightsState {
    /** 与其余应用级偏好共用同一 SharedPreferences 文件。 */
    private const val PREFS_NAME = "app_settings"

    private const val KEY_ENABLED = "update_highlights_enabled"
    private const val KEY_LAST_SHOWN_VERSION_CODE = "update_highlights_last_shown_version_code"

    /** 「版本更新要点」卡开关（默认开）。 */
    var enabled by mutableStateOf(true)
        private set

    /** 上次展示该卡的版本号（缺省 0 = 新安装/从未展示）。 */
    var lastShownVersionCode by mutableStateOf(0L)
        private set

    /** 恢复两项持久化值；DshApp.onCreate 调用（先于任何读取方装配）。 */
    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, true)
        lastShownVersionCode = prefs.getLong(KEY_LAST_SHOWN_VERSION_CODE, 0L)
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, value)
            .apply()
    }

    /** 记录「本版本已展示」（点关闭时调用）：同一版本不再展示。 */
    fun markShownForCurrentVersion(context: Context, versionCode: Long) {
        lastShownVersionCode = versionCode
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SHOWN_VERSION_CODE, versionCode)
            .apply()
    }
}
