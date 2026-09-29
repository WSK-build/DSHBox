package com.dshbox.app.sandbox

import android.content.Context
import android.content.SharedPreferences
import com.dshbox.app.common.Constants

/**
 * 「DSH 官方插件」可开关的内置条目表。
 *
 * 宿主用 `--patch` 覆盖层把上游**默认关闭**的官方能力打开或关闭（见 [OfficialPluginOverlay]）；
 * 开关由用户在插件面板里决定，**默认开启**。
 *
 * ## 为什么只剩 `ui-sidebar-browser` 一条
 *
 * 上游 0.2.0 把自动化相关能力（`schedule` / `ui-schedule` / `time-context`）**从 web 组合里
 * 移出**，改为一个默认关闭的可选 bundle（由 DSH 自己的插件管理页开关，选中即写进 profile 的
 * `dsh.profile.bundles`）。覆盖行打在一个已不存在的 id 上只会换来一条 `patch: entry … not found`
 * 警告，开关就成了假开关 —— 所以本表不再收录它们。
 *
 * 留下的这条仍由 web 组合声明，且带一个"非 desktop 即关"的表达式，需要我们这层显式打开
 * （手机端要把右侧栏的浏览器用起来）。
 *
 * 表放在本模块而不是界面模块：`app`（启动期写覆盖层）与 `plugin-manager`（面板读写开关）
 * 都依赖本模块，两边必须看到**同一份** id 与 name —— 否则写出的覆盖行会与界面显示不一致。
 */
object DshOfficialPlugins {

    /**
     * 一条可开关的官方内置条目。
     *
     * [name] 是上游 bundle 中为该 id 声明的提供者包名。覆盖行必须**复述**它：
     * 上游约定"一条 patch 会替换目标行的整个 `config`"，省略 `name` 有让条目失去提供者的风险。
     * 复述得与实际不符时上游会**跳过**该行，所以它同时是一道安全栓。
     */
    data class Entry(val id: String, val name: String)

    /** 全部可开关条目；顺序即面板中的显示顺序。 */
    val ENTRIES: List<Entry> = listOf(
        Entry("ui-sidebar-browser", "@deepseek-ai/dsh-client-ui-sidebar-browser"),
    )

    /** 条目的偏好键（默认 true = 开启）。 */
    fun prefKey(id: String): String = PREF_PREFIX + id

    /** 当前已开启的条目 id 集合。 */
    fun enabledIds(prefs: SharedPreferences): Set<String> =
        ENTRIES.filter { prefs.getBoolean(prefKey(it.id), true) }.map { it.id }.toSet()

    /** 从 `Context` 取偏好后的同一结果。 */
    fun enabledIds(context: Context): Set<String> =
        enabledIds(context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE))

    private const val PREF_PREFIX = "official_plugin_"
}
