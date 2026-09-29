package interlock.relay.core.protocol

import android.Manifest
import interlock.relay.core.R

/**
 * 能力注册表。新增能力只需在此追加一行，并在对应后端补一个实现，
 * 界面、通道能力清单与授权记录三处自动生效，不需要修改任何分发逻辑。
 *
 * [TierCeiling] 必须逐条按该能力的可逆性与数据敏感级填写：它决定「始终允许」这一档
 * 能免弹到什么程度，填错一档就会把「新会话仍要确认」升成永久免确认。
 */
object CapabilityRegistry {

    /** 可信虚拟屏下限：Android 13 起 shell 才持有 ADD_TRUSTED_DISPLAY。 */
    const val TRUSTED_DISPLAY_MIN_SDK = 33

    /**
     * 无障碍截图路线的最低版本：免系统确认框的 `takeScreenshot` 自 Android 11 起才存在。
     *
     * 它只是「某一条后端路线」的下限，不是能力级下限——`screen.capture` 的
     * MediaProjection 路线（前台截图/录屏）在模块下限 29 上就可用，只是每次会话要系统
     * 确认框。路线探测按选定的执行面引用本常量（见 SystemStateProbe 的 SCREEN_CAPTURE
     * 前台分支），别再把它当成能力级 minSdk 写回注册表。
     */
    const val SCREENSHOT_MIN_SDK = 30

    /**
     * 节点级 11 条能力（10 条动作加 `ui.snapshot`）允许的两个执行面。
     *
     * 后台那一面靠按屏号取窗口（`getWindowsOnAllDisplays()`，Android 13 起）：屏在而取不到树时
     * 由 `A11yBackend.supports` 拒掉，因此这一条声明在低版本上不会兑现成一次失败的调用，
     * 而是照实降级回前台并带 `degraded`。
     */
    val NODE_SURFACES = setOf(SurfaceKind.FOREGROUND, SurfaceKind.BACKEND_TRUSTED)

    // 文案映射先于描述符列表声明，避免对象初始化时读到未赋值的属性。
    private val titles: Map<CapabilityId, Pair<Int, Int>> = mapOf(
        CapabilityId.UI_SNAPSHOT to (R.string.relay_cap_ui_snapshot_title to R.string.relay_cap_ui_snapshot_summary),
        CapabilityId.SCREEN_CAPTURE to (R.string.relay_cap_screen_capture_title to R.string.relay_cap_screen_capture_summary),
        CapabilityId.SCREEN_OBSERVE to (R.string.relay_cap_screen_observe_title to R.string.relay_cap_screen_observe_summary),
        CapabilityId.SCREEN_RECORD to (R.string.relay_cap_screen_record_title to R.string.relay_cap_screen_record_summary),
        CapabilityId.NOTIFY_READ to (R.string.relay_cap_notify_read_title to R.string.relay_cap_notify_read_summary),
        CapabilityId.UI_TAP to (R.string.relay_cap_ui_tap_title to R.string.relay_cap_ui_tap_summary),
        CapabilityId.UI_SWIPE to (R.string.relay_cap_ui_swipe_title to R.string.relay_cap_ui_swipe_summary),
        CapabilityId.UI_TEXT to (R.string.relay_cap_ui_text_title to R.string.relay_cap_ui_text_summary),
        CapabilityId.UI_KEY to (R.string.relay_cap_ui_key_title to R.string.relay_cap_ui_key_summary),
        CapabilityId.UI_CLICK to (R.string.relay_cap_ui_click_title to R.string.relay_cap_ui_click_summary),
        CapabilityId.UI_LONG_CLICK to (R.string.relay_cap_ui_longclick_title to R.string.relay_cap_ui_longclick_summary),
        CapabilityId.UI_SELECT to (R.string.relay_cap_ui_select_title to R.string.relay_cap_ui_select_summary),
        CapabilityId.UI_DISMISS to (R.string.relay_cap_ui_dismiss_title to R.string.relay_cap_ui_dismiss_summary),
        CapabilityId.UI_SCROLL to (R.string.relay_cap_ui_scroll_title to R.string.relay_cap_ui_scroll_summary),
        CapabilityId.UI_SET_VALUE to (R.string.relay_cap_ui_setvalue_title to R.string.relay_cap_ui_setvalue_summary),
        CapabilityId.UI_WAIT_FOR to (R.string.relay_cap_ui_waitfor_title to R.string.relay_cap_ui_waitfor_summary),
        CapabilityId.UI_NODE to (R.string.relay_cap_ui_node_title to R.string.relay_cap_ui_node_summary),
        CapabilityId.UI_SET_PROGRESS to (R.string.relay_cap_ui_setprogress_title to R.string.relay_cap_ui_setprogress_summary),
        CapabilityId.UI_IME_ACTION to (R.string.relay_cap_ui_imeaction_title to R.string.relay_cap_ui_imeaction_summary),
        CapabilityId.SYS_INTENT to (R.string.relay_cap_sys_intent_title to R.string.relay_cap_sys_intent_summary),
        CapabilityId.SYS_SHELL to (R.string.relay_cap_sys_shell_title to R.string.relay_cap_sys_shell_summary),
        CapabilityId.APP_LAUNCH to (R.string.relay_cap_app_launch_title to R.string.relay_cap_app_launch_summary),
        CapabilityId.APP_STOP to (R.string.relay_cap_app_stop_title to R.string.relay_cap_app_stop_summary),
        CapabilityId.SURFACE_VIRTUAL to (R.string.relay_cap_surface_virtual_title to R.string.relay_cap_surface_virtual_summary),
        CapabilityId.CLIPBOARD_READ to (R.string.relay_cap_clip_read_title to R.string.relay_cap_clip_read_summary),
        CapabilityId.CLIPBOARD_WRITE to (R.string.relay_cap_clip_write_title to R.string.relay_cap_clip_write_summary),
        CapabilityId.CONTACT_READ to (R.string.relay_cap_contact_read_title to R.string.relay_cap_contact_read_summary),
        CapabilityId.CONTACT_WRITE to (R.string.relay_cap_contact_write_title to R.string.relay_cap_contact_write_summary),
        CapabilityId.CALENDAR_READ to (R.string.relay_cap_cal_read_title to R.string.relay_cap_cal_read_summary),
        CapabilityId.CALENDAR_WRITE to (R.string.relay_cap_cal_write_title to R.string.relay_cap_cal_write_summary),
        CapabilityId.LOCATION_READ to (R.string.relay_cap_loc_read_title to R.string.relay_cap_loc_read_summary),
        CapabilityId.MEDIA_READ to (R.string.relay_cap_media_read_title to R.string.relay_cap_media_read_summary),
        CapabilityId.MEDIA_WRITE to (R.string.relay_cap_media_write_title to R.string.relay_cap_media_write_summary),
        CapabilityId.AUDIO_CAPTURE to (R.string.relay_cap_audio_capture_title to R.string.relay_cap_audio_capture_summary),
        CapabilityId.PKG_QUERY to (R.string.relay_cap_pkg_query_title to R.string.relay_cap_pkg_query_summary),
        CapabilityId.PKG_INSTALL to (R.string.relay_cap_app_install_title to R.string.relay_cap_app_install_summary),
        CapabilityId.SECURE_SETTINGS to (R.string.relay_cap_sys_settings_write_title to R.string.relay_cap_sys_settings_write_summary),
        CapabilityId.APPOPS_SET to (R.string.relay_cap_appops_set_title to R.string.relay_cap_appops_set_summary),
        CapabilityId.NOTIFY_POST to (R.string.relay_cap_notify_post_title to R.string.relay_cap_notify_post_summary),
    )

    private val all: List<CapabilityDescriptor> = listOf(
        // ── 观测 ──
        cap(
            CapabilityId.UI_SNAPSHOT, listOf(BackendId.A11Y),
            // 与节点级那 10 条动作同面：读哪一棵树由 DisplayTree 决定，两块屏都读得到。
            // 快照必须与按 nodeId 的回读同源 —— nodeId 是前序下标，不同源就会指错节点，
            // 那是一次看起来成功的错误点击，比直接报错难发现。
            NODE_SURFACES,
            GuideClass.LIST_ONLY, SettingsTarget.ACCESSIBILITY, TierCeiling.ANY,
        ),
        cap(
            CapabilityId.SCREEN_CAPTURE,
            listOf(BackendId.A11Y, BackendId.DIRECT, BackendId.SHIZUKU),
            setOf(SurfaceKind.FOREGROUND, SurfaceKind.BACKEND_TRUSTED, SurfaceKind.OBSERVE_ONLY),
            GuideClass.NO_SETTINGS_PAGE, SettingsTarget.NONE, TierCeiling.SESSION_ONLY,
            // 能力级下限是模块下限 29：MediaProjection 前台路线在本机 Android 10 上就能跑
            // （每次会话要系统确认框）。无障碍那条免确认路线另有自己的下限
            // （SCREENSHOT_MIN_SDK = 30），由路线探测引用，不是这里的口径——
            // 把「某条路线的最低版本」写成「能力的最低版本」，调用方在 Android 10
            // 会把一条可用的投屏路径当成不可用而放弃。
            minSdk = 29,
        ),
        cap(
            CapabilityId.SCREEN_OBSERVE, listOf(BackendId.DIRECT),
            setOf(SurfaceKind.OBSERVE_ONLY),
            GuideClass.NO_SETTINGS_PAGE, SettingsTarget.NONE, TierCeiling.SESSION_ONLY,
            minSdk = 21,
        ),
        cap(
            // 前台那一条要系统投屏确认框；后台那一条的帧来自可信屏自己的持有者，
            // 系统框根本不出现，所以两条通路分属两个后端。
            CapabilityId.SCREEN_RECORD, listOf(BackendId.DIRECT, BackendId.SHIZUKU),
            setOf(SurfaceKind.OBSERVE_ONLY, SurfaceKind.FOREGROUND, SurfaceKind.BACKEND_TRUSTED),
            GuideClass.NO_SETTINGS_PAGE, SettingsTarget.NONE, TierCeiling.SESSION_ONLY,
            minSdk = 21,
        ),
        cap(
            CapabilityId.NOTIFY_READ, listOf(BackendId.DIRECT), emptySet(),
            GuideClass.LIST_ONLY, SettingsTarget.NOTIFICATION_LISTENER, TierCeiling.SESSION_ONLY,
            minSdk = 22,
        ),

        // ── 操作 ──
        cap(
            CapabilityId.UI_TAP, listOf(BackendId.A11Y, BackendId.SHIZUKU),
            setOf(SurfaceKind.FOREGROUND, SurfaceKind.BACKEND_TRUSTED),
            GuideClass.LIST_ONLY, SettingsTarget.ACCESSIBILITY, TierCeiling.ANY,
            minSdk = 24,
        ),
        // 滑动独立成一条：与 ui.tap 共用参数形状时，滑动形状的请求会被静默丢掉多余键、
        // 退化成一次点击并回执成功，助手据此往下走，整条流程都建立在一次错的动作上。
        cap(
            CapabilityId.UI_SWIPE, listOf(BackendId.A11Y, BackendId.SHIZUKU),
            setOf(SurfaceKind.FOREGROUND, SurfaceKind.BACKEND_TRUSTED),
            GuideClass.LIST_ONLY, SettingsTarget.ACCESSIBILITY, TierCeiling.ANY,
            minSdk = 24,
        ),
        // 节点级动作整批（click / longClick / select / dismiss / scroll / setValue /
        // waitFor / setProgress / imeAction / node）只挂 A11Y，但两块屏都服务。
        // 它们与坐标通路的分别是"不产生触摸事件"，因此不会出现"坐标没对上、回执却是成功"；
        // 能落后台屏靠的也不是"没有触摸事件"这一条，而是树可以从 `getWindowsOnAllDisplays()`
        // 里按屏取（Android 13 起，见 `DisplayTree.ofDisplay`）。坐标那四条仍只能作用于默认屏，落后台时换 Shizuku 执行。
        cap(
            CapabilityId.UI_CLICK, listOf(BackendId.A11Y),
            NODE_SURFACES,
            GuideClass.LIST_ONLY, SettingsTarget.ACCESSIBILITY, TierCeiling.ANY,
        ),
        cap(
            CapabilityId.UI_LONG_CLICK, listOf(BackendId.A11Y),
            NODE_SURFACES,
            GuideClass.LIST_ONLY, SettingsTarget.ACCESSIBILITY, TierCeiling.ANY,
        ),
        cap(
            CapabilityId.UI_SELECT, listOf(BackendId.A11Y),
            NODE_SURFACES,
            GuideClass.LIST_ONLY, SettingsTarget.ACCESSIBILITY, TierCeiling.ANY,
        ),
        cap(
            CapabilityId.UI_DISMISS, listOf(BackendId.A11Y),
            NODE_SURFACES,
            GuideClass.LIST_ONLY, SettingsTarget.ACCESSIBILITY, TierCeiling.ANY,
        ),
        cap(
            CapabilityId.UI_SCROLL, listOf(BackendId.A11Y),
            NODE_SURFACES,
            GuideClass.LIST_ONLY, SettingsTarget.ACCESSIBILITY, TierCeiling.ANY,
        ),
        cap(
            CapabilityId.UI_SET_VALUE, listOf(BackendId.A11Y),
            NODE_SURFACES,
            GuideClass.LIST_ONLY, SettingsTarget.ACCESSIBILITY, TierCeiling.ANY,
        ),
        cap(
            CapabilityId.UI_WAIT_FOR, listOf(BackendId.A11Y),
            NODE_SURFACES,
            GuideClass.LIST_ONLY, SettingsTarget.ACCESSIBILITY, TierCeiling.ANY,
        ),
        cap(
            CapabilityId.UI_SET_PROGRESS, listOf(BackendId.A11Y),
            NODE_SURFACES,
            GuideClass.LIST_ONLY, SettingsTarget.ACCESSIBILITY, TierCeiling.ANY,
        ),
        cap(
            CapabilityId.UI_IME_ACTION, listOf(BackendId.A11Y),
            NODE_SURFACES,
            GuideClass.LIST_ONLY, SettingsTarget.ACCESSIBILITY, TierCeiling.ANY,
            minSdk = 30,
        ),
        cap(
            CapabilityId.UI_NODE, listOf(BackendId.A11Y),
            NODE_SURFACES,
            GuideClass.LIST_ONLY, SettingsTarget.ACCESSIBILITY, TierCeiling.ANY,
        ),
        cap(
            CapabilityId.UI_TEXT, listOf(BackendId.A11Y, BackendId.SHIZUKU),
            setOf(SurfaceKind.FOREGROUND, SurfaceKind.BACKEND_TRUSTED),
            GuideClass.LIST_ONLY, SettingsTarget.ACCESSIBILITY, TierCeiling.ANY,
            minSdk = 24,
        ),
        cap(
            CapabilityId.UI_KEY, listOf(BackendId.A11Y, BackendId.SHIZUKU),
            setOf(SurfaceKind.FOREGROUND, SurfaceKind.BACKEND_TRUSTED),
            GuideClass.LIST_ONLY, SettingsTarget.ACCESSIBILITY, TierCeiling.ANY,
        ),
        cap(
            CapabilityId.APP_LAUNCH, listOf(BackendId.DIRECT, BackendId.SHIZUKU),
            setOf(SurfaceKind.FOREGROUND, SurfaceKind.BACKEND_TRUSTED),
            GuideClass.DEEP_LINK_OK, SettingsTarget.APP_DETAILS, TierCeiling.ANY,
        ),
        cap(
            CapabilityId.SYS_INTENT, listOf(BackendId.DIRECT), setOf(SurfaceKind.FOREGROUND),
            GuideClass.NOT_REQUIRED, SettingsTarget.NONE, TierCeiling.ANY,
        ),
        // 上限 ASK_ONLY：这条一次覆盖的就是一批 shell 身份动词，免确认等于把闸门整段让开。
        cap(
            CapabilityId.SYS_SHELL, listOf(BackendId.SHIZUKU), emptySet(),
            GuideClass.NO_SETTINGS_PAGE, SettingsTarget.NONE, TierCeiling.ASK_ONLY,
        ),
        cap(
            CapabilityId.APP_STOP, listOf(BackendId.SHIZUKU), emptySet(),
            GuideClass.NO_SETTINGS_PAGE, SettingsTarget.NONE, TierCeiling.ASK_ONLY,
        ),
        cap(
            CapabilityId.SURFACE_VIRTUAL, listOf(BackendId.SHIZUKU),
            setOf(SurfaceKind.BACKEND_TRUSTED),
            GuideClass.NO_SETTINGS_PAGE, SettingsTarget.NONE, TierCeiling.ANY,
            minSdk = TRUSTED_DISPLAY_MIN_SDK,
        ),

        // ── 数据 ──
        cap(
            CapabilityId.CLIPBOARD_READ, listOf(BackendId.DIRECT),
            setOf(SurfaceKind.FOREGROUND),
            GuideClass.NO_SETTINGS_PAGE, SettingsTarget.NONE, TierCeiling.ASK_ONLY,
        ),
        cap(
            CapabilityId.CLIPBOARD_WRITE, listOf(BackendId.DIRECT),
            setOf(SurfaceKind.FOREGROUND),
            GuideClass.NO_SETTINGS_PAGE, SettingsTarget.NONE, TierCeiling.ASK_ONLY,
        ),
        cap(
            CapabilityId.CONTACT_READ, listOf(BackendId.DIRECT), emptySet(),
            GuideClass.DEEP_LINK_OK, SettingsTarget.APP_DETAILS, TierCeiling.ANY,
            perms = listOf(Manifest.permission.READ_CONTACTS),
        ),
        cap(
            CapabilityId.CONTACT_WRITE, listOf(BackendId.DIRECT), emptySet(),
            GuideClass.DEEP_LINK_OK, SettingsTarget.APP_DETAILS, TierCeiling.ASK_ONLY,
            perms = listOf(Manifest.permission.WRITE_CONTACTS),
        ),
        cap(
            CapabilityId.CALENDAR_READ, listOf(BackendId.DIRECT), emptySet(),
            GuideClass.DEEP_LINK_OK, SettingsTarget.APP_DETAILS, TierCeiling.ANY,
            perms = listOf(Manifest.permission.READ_CALENDAR),
        ),
        cap(
            CapabilityId.CALENDAR_WRITE, listOf(BackendId.DIRECT), emptySet(),
            GuideClass.DEEP_LINK_OK, SettingsTarget.APP_DETAILS, TierCeiling.ASK_ONLY,
            perms = listOf(Manifest.permission.WRITE_CALENDAR),
        ),
        cap(
            CapabilityId.LOCATION_READ, listOf(BackendId.DIRECT), emptySet(),
            GuideClass.DEEP_LINK_OK, SettingsTarget.APP_DETAILS, TierCeiling.ASK_ONLY,
            perms = listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        ),
        cap(
            CapabilityId.MEDIA_READ, listOf(BackendId.DIRECT), emptySet(),
            GuideClass.DEEP_LINK_OK, SettingsTarget.APP_DETAILS, TierCeiling.SESSION_ONLY,
            perms = listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            ),
        ),
        cap(
            CapabilityId.MEDIA_WRITE, listOf(BackendId.DIRECT), emptySet(),
            // 写自己的新条目在 API 29 起零权限：既没有需要用户去的设置页，
            // 也不该给一个"所有文件访问"的引导位（那要 MANAGE_EXTERNAL_STORAGE，本模块不申请）。
            GuideClass.NOT_REQUIRED, SettingsTarget.NONE, TierCeiling.ASK_ONLY,
        ),
        cap(
            CapabilityId.AUDIO_CAPTURE, listOf(BackendId.DIRECT), emptySet(),
            GuideClass.DEEP_LINK_OK, SettingsTarget.APP_DETAILS, TierCeiling.ASK_ONLY,
            perms = listOf(Manifest.permission.RECORD_AUDIO),
        ),

        // ── 系统 ──
        cap(
            CapabilityId.PKG_QUERY, listOf(BackendId.DIRECT), emptySet(),
            GuideClass.NOT_REQUIRED, SettingsTarget.NONE, TierCeiling.ANY,
        ),
        cap(
            CapabilityId.PKG_INSTALL, listOf(BackendId.SHIZUKU), emptySet(),
            GuideClass.DEEP_LINK_OK, SettingsTarget.UNKNOWN_SOURCES, TierCeiling.ASK_ONLY,
            perms = listOf(Manifest.permission.REQUEST_INSTALL_PACKAGES),
        ),
        cap(
            CapabilityId.SECURE_SETTINGS, listOf(BackendId.SHIZUKU), emptySet(),
            GuideClass.NO_SETTINGS_PAGE, SettingsTarget.NONE, TierCeiling.ASK_ONLY,
        ),
        cap(
            CapabilityId.APPOPS_SET, listOf(BackendId.SHIZUKU), emptySet(),
            GuideClass.NO_SETTINGS_PAGE, SettingsTarget.NONE, TierCeiling.ASK_ONLY,
        ),
        cap(
            CapabilityId.NOTIFY_POST, listOf(BackendId.DIRECT), emptySet(),
            GuideClass.DEEP_LINK_OK, SettingsTarget.APP_DETAILS, TierCeiling.ANY,
            perms = listOf(Manifest.permission.POST_NOTIFICATIONS),
            // 发通知本身 29 即可用；POST_NOTIFICATIONS 权限 33 才存在，其版本要求由系统权限门禁表达（未授予时按运行期缺口列出），能力级下限跟随模块。
            minSdk = 29,
        ),
    )

    private fun cap(
        id: CapabilityId,
        backends: List<BackendId>,
        surfaces: Set<SurfaceKind>,
        guide: GuideClass,
        target: SettingsTarget,
        ceiling: TierCeiling,
        perms: List<String> = emptyList(),
        minSdk: Int = 29,
    ) = CapabilityDescriptor(
        id = id,
        titleRes = titles.getValue(id).first,
        summaryRes = titles.getValue(id).second,
        backends = backends,
        surfaces = surfaces,
        guideClass = guide,
        settingsTarget = target,
        ceiling = ceiling,
        runtimePermissions = perms,
        minSdk = minSdk,
    )

    val byId: Map<CapabilityId, CapabilityDescriptor> = all.associateBy { it.id }

    val allDescriptors: List<CapabilityDescriptor> = all

    fun inCategory(category: CapabilityCategory): List<CapabilityDescriptor> =
        all.filter { it.id.category == category }

    fun find(id: CapabilityId): CapabilityDescriptor? = byId[id]

    fun find(wire: String): CapabilityDescriptor? = CapabilityId.fromWire(wire)?.let { byId[it] }
}
