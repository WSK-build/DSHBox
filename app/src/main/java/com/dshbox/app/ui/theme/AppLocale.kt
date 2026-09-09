package com.dshbox.app.ui.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.LocaleListCompat

/**
 * 应用语言枚举（联合国六语 + 跟随系统）。
 *
 * [nativeName] 是各语言的「自称」，固定不随界面语言翻译——语言选择器里用户在
 * 陌生界面语言下也能认出自己的语言。SYSTEM 项的展示文案走字符串资源。
 */
enum class AppLanguage(val tag: String?, val nativeName: String) {
    SYSTEM(null, ""),
    AR("ar", "العربية"),
    EN("en", "English"),
    ES("es", "Español"),
    FR("fr", "Français"),
    RU("ru", "Русский"),
    ZH("zh", "中文"),
    ;

    companion object {
        /**
         * 按「主语言」匹配——BCP-47 可能带区域子标签（ar-EG、zh-Hans-CN）
         * 或多标签（ar,en，取第一个），均归一为六语枚举；避免误回落 SYSTEM
         * 导致设置页单选高亮与实际生效语言错位。
         */
        fun forTag(tag: String?): AppLanguage {
            val primary = tag?.substringBefore(',')?.substringBefore('-')
            return entries.firstOrNull { it.tag != null && it.tag == primary } ?: AppLanguage.SYSTEM
        }
    }
}

/**
 * 应用语言状态（与 [AppThemeState] 同构的轻量方案）。
 *
 * 语言事实源与生效机制：
 * - 选择/生效统一走 [AppCompatDelegate.setApplicationLocales]：API 33+ 委托系统
 *   LocaleManager（系统设置「应用语言」双向同步）；API 29–32 由 appcompat 经
 *   AppLocalesMetadataHolderService（autoStoreLocales）持久化并重建前台 Activity。
 * - [KEY_LOCALE_TAG] 是手选语言的 SharedPreferences 镜像：API<33 上在首个
 *   Activity 创建前 appcompat 尚未初始化存储（getApplicationLocales 为空），
 *   而前台服务在 Application.onCreate 就可能构建通知，因此通知侧读此镜像。
 *   [refresh] 会在每次回到前台时把 delegate 值回写镜像（覆盖 API 33+ 系统设置
 *   直接改语言的情况），两边不会漂移。
 *
 * 不自建生效开关：恢复「跟随系统」= setApplicationLocales(空 LocaleList)。
 */
object AppLocaleState {
    /** 与 [AppThemeState] 共用同一 SharedPreferences 文件。 */
    const val PREFS_NAME = "app_settings"

    /** 手选语言镜像（BCP-47 tag；空串 = 跟随系统）。前台服务通知读取此值。 */
    const val KEY_LOCALE_TAG = "app_locale_tag"

    /** 上次构建通知所用语言；MainActivity.onResume 比对变化后触发通知刷新。 */
    const val KEY_LAST_NOTIFIED_TAG = "app_locale_notified_tag"

    var current by mutableStateOf(AppLanguage.SYSTEM)
        private set

    fun set(context: Context, lang: AppLanguage) {
        val tag = lang.tag.orEmpty()
        AppCompatDelegate.setApplicationLocales(
            if (lang == AppLanguage.SYSTEM) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(tag)
            },
        )
        prefs(context).edit().putString(KEY_LOCALE_TAG, tag).apply()
        current = lang
    }

    /**
     * 从事实源（delegate 优先，镜像兜底）解析当前语言并同步镜像。
     * DshApp.onCreate 与 MainActivity.onResume 都会调用。
     */
    fun refresh(context: Context) {
        val delegateTags = runCatching { AppCompatDelegate.getApplicationLocales().toLanguageTags() }
            .getOrDefault("")
        val effective = delegateTags.ifEmpty {
            prefs(context).getString(KEY_LOCALE_TAG, null).orEmpty()
        }
        prefs(context).edit().putString(KEY_LOCALE_TAG, effective).apply()
        current = AppLanguage.forTag(effective)
    }

    /** 通知/服务侧读取的手选语言 tag（空串 = 跟随系统，不包 Configuration）。 */
    fun currentTag(context: Context): String =
        prefs(context).getString(KEY_LOCALE_TAG, null).orEmpty()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
