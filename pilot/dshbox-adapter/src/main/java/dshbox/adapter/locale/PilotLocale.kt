package dshbox.adapter.locale

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate

/**
 * 取一个按**应用语言**解析字符串的上下文，供界面之外的文案使用。
 *
 * 用户在设置里手选的语言记在语言层（API 33 及以上是系统 LocaleManager，更低版本由
 * appcompat 自己存档），平台只把这份覆盖应用到 Activity 上下文。由 applicationContext
 * 派生的东西不在其中：悬浮确认卡、审批通知、以及页内那张卡为了统一样式而取的应用上下文，
 * 都会退回系统语言，结果是同一个问题在助手页里是中文、在悬浮卡上是英文。
 *
 * 审批的三条通路（[dshbox.adapter.surfaces.ApprovalCardView]、
 * [dshbox.adapter.surfaces.OverlayApprovalPresenter]、
 * [dshbox.adapter.surfaces.PilotApprovalNotification]）都从这里取文案上下文，
 * 保证问用户的那句话在任何一屏上都是同一种语言。
 *
 * 只用于取字符串：视图与窗口仍由原上下文负责，换上下文会让按钮带上 Activity 的主题，
 * 那是样式改动而不是语言修复。语言未手选（跟随系统）时原样返回，不额外造 Resources。
 */
internal fun Context.withAppLanguage(): Context {
    val tags = appLanguageTags(this) ?: return this
    if (resources.configuration.locales.toLanguageTags() == tags) return this
    val override = Configuration(resources.configuration)
    override.setLocales(LocaleList.forLanguageTags(tags))
    return createConfigurationContext(override)
}

/**
 * 手选语言的 tag；没手选（跟随系统）时为 null。
 *
 * delegate 优先。API 33 以下它要等首个 Activity 把 appcompat 的存储初始化好，而通道
 * 可能正是宿主的服务起来的（用户一次都没打开过界面）—— 那时读宿主为服务侧留的那份
 * 镜像（app_settings 的 app_locale_tag，回到前台时会与 delegate 对齐）。少了这一层，
 * 重启之后的第一次询问就会在悬浮卡与通知栏上退回系统语言。
 *
 * internal：core 的 [interlock.relay.core.spi.RelayText] 实现也要报同一份语言标签，
 * 让通道侧文案与三张卡走同一个语言判定，宿主偏好名（app_settings/app_locale_tag）
 * 只留在本模块内，不进 core。
 */
internal fun appLanguageTags(context: Context): String? {
    runCatching { AppCompatDelegate.getApplicationLocales().toLanguageTags() }
        .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
    return runCatching {
        context.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
            .getString(HOST_PREF_LOCALE_TAG, null)
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/** 宿主手选语言的镜像键：它明确写给「界面之外构建通知」这一侧读，本模块只读不写。 */
private const val HOST_PREFS = "app_settings"
private const val HOST_PREF_LOCALE_TAG = "app_locale_tag"
