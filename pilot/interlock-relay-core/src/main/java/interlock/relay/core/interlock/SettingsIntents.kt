package interlock.relay.core.interlock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import interlock.relay.core.protocol.SettingsTarget

/**
 * 把跳转语义解析为真实 Intent。所有 action 一律引用 [Settings] 的公开常量，
 * 不使用字符串字面量，避免拼出系统里并不存在的 action。
 *
 * 返回 null 表示该目标在本机没有可直达的设置页，调用方必须退化为纯文字说明，
 * 不能给一个点了没反应的按钮。
 */
object SettingsIntents {

    /**
     * 是否支持带包名直达。不支持的目标必须在界面上给出「在列表中找到本应用」的分步说明：
     * 无障碍与通知使用权只能落到功能列表页，Android 11 起悬浮窗授权也不再支持按包名直达。
     */
    fun deepLinksToApp(target: SettingsTarget): Boolean = when (target) {
        SettingsTarget.APP_DETAILS,
        SettingsTarget.WRITE_SETTINGS,
        SettingsTarget.BATTERY_EXEMPT,
        SettingsTarget.UNKNOWN_SOURCES,
        SettingsTarget.ALL_FILES,
        -> true

        SettingsTarget.OVERLAY -> Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        SettingsTarget.ACCESSIBILITY,
        SettingsTarget.NOTIFICATION_LISTENER,
        SettingsTarget.NONE,
        -> false
    }

    fun build(target: SettingsTarget, context: Context): Intent? {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        val action = when (target) {
            SettingsTarget.APP_DETAILS -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            SettingsTarget.ACCESSIBILITY -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            SettingsTarget.NOTIFICATION_LISTENER -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            SettingsTarget.OVERLAY -> Settings.ACTION_MANAGE_OVERLAY_PERMISSION
            SettingsTarget.WRITE_SETTINGS -> Settings.ACTION_MANAGE_WRITE_SETTINGS
            SettingsTarget.BATTERY_EXEMPT -> Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            SettingsTarget.UNKNOWN_SOURCES -> Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
            SettingsTarget.ALL_FILES ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                } else {
                    null
                }

            SettingsTarget.NONE -> null
        } ?: return null

        // 列表页类目标不接受 package 数据，带上会被忽略或抛异常。
        val acceptsPackageData = deepLinksToApp(target)
        return Intent(action).apply {
            if (acceptsPackageData) data = packageUri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 厂商「自启动 / 后台运行白名单」页的候选入口，按本机品牌排在最前。
     *
     * 平台没有公开 action，但各家权限管理器的组件名是业界公开在用的，所以这里给一份候选
     * 让调用方**依次试**：谁真起得来就用谁，全起不来才退化成文字步骤。不在渲染时先用
     * `resolveActivity` 过滤 —— 厂商 ROM 的应用可见性过滤会对自家系统页面给假阴性，
     * 那样会把本来跳得过去的入口整行删掉（与 `KeepAliveRow` 同一判据）。
     *
     * 只把人送到开关前，不替他翻：那一页改的是系统里的白名单，动它必须是用户在系统页里
     * 自己点的手。候选表按品牌分组而不是猜一个"最可能"的：同一厂商在不同 ROM 版本里
     * 改过名（vivo 的 permissionmanager 与 iqoo.secure、OPPO 的 coloros 与 oplus）。
     */
    fun vendorAutoStartCandidates(context: Context): List<Intent> {
        val mine = (Build.BRAND + " " + Build.MANUFACTURER).lowercase()
        return VENDOR_AUTOSTART
            .sortedByDescending { (group, _) -> group.any { mine.contains(it) } }
            .flatMap { (_, pages) -> pages.map { (pkg, cls) -> componentIntent(pkg, cls) } }
    }

    private fun componentIntent(packageName: String, component: String): Intent =
        Intent().setClassName(packageName, component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private val VENDOR_AUTOSTART: List<Pair<List<String>, List<Pair<String, String>>>> = listOf(
        listOf("vivo", "iqoo") to listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.PurviewTabActivity",
        ),
        listOf("oppo", "realme", "oneplus", "coloros") to listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        ),
        listOf("xiaomi", "redmi", "poco", "mi") to listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        listOf("huawei", "honor") to listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        listOf("samsung") to listOf(
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        ),
    )

    /**
     * 运行时权限的系统入口。
     *
     * 公开 SDK 里**没有**「直达某一条权限」的 action：AOSP 的
     * MANAGE_APP_PERMISSIONS 与它的两个 EXTRA 都是 @hide，而本文件的规定是只用
     * [Settings] 的公开常量。因此这里落到本应用的应用信息页，权限分组就在该页内，
     * 用户从那里点到具体一条。
     *
     * 本模块不能自行撤销已获得的运行时权限（撤销需要 signature 级的
     * REVOKE_RUNTIME_PERMISSIONS），所以界面上「关闭」这一侧只能是跳系统，不是本地翻牌。
     */
    fun permissionEntry(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 「忽略电池优化」那条开关所在的全应用列表页。
     *
     * 与 [SettingsTarget.BATTERY_EXEMPT] 那条按包名直达的确认页分开给：直达页在部分厂商
     * ROM 上被改掉了，列表页是它唯一的兜底入口。两者都不在系统里创建任何改动，
     * 只是把用户送到那条开关前。
     */
    fun batteryOptimizationList(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 应用通知设置页：Android 8 起可用，以下退回应用详情页。 */
    fun appNotificationSettings(context: Context): Intent = Intent(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Settings.ACTION_APP_NOTIFICATION_SETTINGS
        } else {
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        },
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            data = Uri.fromParts("package", context.packageName, null)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
