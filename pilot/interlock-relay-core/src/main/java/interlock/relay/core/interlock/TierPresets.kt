package interlock.relay.core.interlock

import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.CapabilityId

/**
 * 五条目全局配置的内核侧：默认配置映射与全局状态机。
 *
 * 全局配置的**应用范围只有普通能力 40 条**（[GLOBAL_SCOPE]）：完全 Shizuku（`sys.shell.full`）
 * 是独立特权开关，不随任何全局条目应用——它的两态由特权模式页单独拨；执行模式偏好
 * （前台/优先后台）也不在档位之内，预设一律不碰。
 *
 * 默认配置（新用户装好即得）只写普通能力档位：
 * - 完全访问：`device.status`、`pkg.query`、`app.launch`、`surface.virtual`、
 * `ui.snapshot`、`ui.node`、`ui.waitFor`、`ui.tap`、`ui.click`、`ui.swipe`、`ui.scroll`；
 * - 询问审批：屏幕采集/监看/录制、通知读写、`ui` 的长按/选择/清除/设值/进度/输入法/文本/按键、
 * `app.stop`、`sys.intent`、剪贴板/联系人/日历/位置/媒体/录音、安装、写安全设置、appops；
 * - 禁止：`sys.shell`（封闭动词表那条，要开必须用户自己拨）。
 *
 * `sys.shell.full` 在设计表里同属「禁止」一行，但那是它自己的默认态（见
 * `TierStore.defaultTier`），不是全局配置写出来的——它根本不在 [GLOBAL_SCOPE] 里。
 */
object TierPresets {

    /** 默认配置的「完全访问」档。 */
    private val DEFAULT_ALWAYS = setOf(
        CapabilityId.DEVICE_STATUS,
        CapabilityId.PKG_QUERY,
        CapabilityId.APP_LAUNCH,
        CapabilityId.SURFACE_VIRTUAL,
        CapabilityId.UI_SNAPSHOT,
        CapabilityId.UI_NODE,
        CapabilityId.UI_WAIT_FOR,
        CapabilityId.UI_TAP,
        CapabilityId.UI_CLICK,
        CapabilityId.UI_SWIPE,
        CapabilityId.UI_SCROLL,
    )

    /** 默认配置的「询问审批」档。 */
    private val DEFAULT_ASK = setOf(
        CapabilityId.SCREEN_CAPTURE,
        CapabilityId.SCREEN_OBSERVE,
        CapabilityId.SCREEN_RECORD,
        CapabilityId.NOTIFY_READ,
        CapabilityId.NOTIFY_POST,
        CapabilityId.UI_LONG_CLICK,
        CapabilityId.UI_SELECT,
        CapabilityId.UI_DISMISS,
        CapabilityId.UI_SET_VALUE,
        CapabilityId.UI_SET_PROGRESS,
        CapabilityId.UI_IME_ACTION,
        CapabilityId.UI_TEXT,
        CapabilityId.UI_KEY,
        CapabilityId.APP_STOP,
        CapabilityId.SYS_INTENT,
        CapabilityId.CLIPBOARD_READ,
        CapabilityId.CLIPBOARD_WRITE,
        CapabilityId.CONTACT_READ,
        CapabilityId.CONTACT_WRITE,
        CapabilityId.CALENDAR_READ,
        CapabilityId.CALENDAR_WRITE,
        CapabilityId.LOCATION_READ,
        CapabilityId.MEDIA_READ,
        CapabilityId.MEDIA_WRITE,
        CapabilityId.AUDIO_CAPTURE,
        CapabilityId.PKG_INSTALL,
        CapabilityId.SECURE_SETTINGS,
        CapabilityId.APPOPS_SET,
    )

    /** 默认配置的「禁止」档：只有 `sys.shell` 一条在全局范围内。 */
    private val DEFAULT_DENIED = setOf(CapabilityId.SYS_SHELL)

    /**
     * 全局配置的应用范围：普通能力成员的 id 集合。特权能力不在内；成员资格的
     * 唯一判据仍是注册表的 `privileged` 位（见 CapabilityRegistry.ordinaryMembers），
     * 两者的相等由单测钉住——默认配置表漏掉或多出一条都会在这里被拦下。
     */
    val GLOBAL_SCOPE: Set<CapabilityId> = DEFAULT_ALWAYS + DEFAULT_ASK + DEFAULT_DENIED

    /** 内置默认配置：40 条普通能力各自的档位。不可变；编辑器拿它当初始模板。 */
    val DEFAULT: Map<CapabilityId, AccessTier> = buildMap {
        DEFAULT_ALWAYS.forEach { put(it, AccessTier.ALWAYS) }
        DEFAULT_ASK.forEach { put(it, AccessTier.ASK) }
        DEFAULT_DENIED.forEach { put(it, AccessTier.DENIED) }
    }

    /**
     * 全局选择器的**迁移判据**（老升级场景没有持久化身份时，按 40 条现值推导一次
     * 身份用，见 [ActivePresetStore.resolve]）。判据次序即条目次序：
     * 1. 40 条同一档 → 全部禁止 / 全部询问审批 / 全部完全访问；
     * 2. 与 [TierPresets.DEFAULT] 全等 → 默认配置；
     * 3. 与已保存自定义全等 → 常用自定义预设；
     * 4. 其余（含与任何已知预设都不等的场景）→ 混合：手动改过、不属于任何一个
     * 预设条目的状态如实说混合，不伪装成某个预设。
     *
     * 正常运行时选择器读的是单独持久化的身份（[ActivePreset]），不再走本判据；
     * 本判据只在迁移那一刻生效。
     *
     * 纯判据：JVM 单测穷举各场景；界面只按结果取词。
     */
    fun globalState(
        current: Map<CapabilityId, AccessTier>,
        custom: Map<CapabilityId, AccessTier>?,
    ): GlobalConfigState {
        val tiers = GLOBAL_SCOPE.map { id -> current[id] ?: return GlobalConfigState.MIXED }
        tiers.distinct().singleOrNull()?.let { tier ->
            return when (tier) {
                AccessTier.DENIED -> GlobalConfigState.ALL_DENIED
                AccessTier.ASK -> GlobalConfigState.ALL_ASK
                AccessTier.ALWAYS -> GlobalConfigState.ALL_ALWAYS
            }
        }
        if (tiers == GLOBAL_SCOPE.map { DEFAULT.getValue(it) }) return GlobalConfigState.DEFAULT
        if (custom != null && tiers == GLOBAL_SCOPE.map { custom.getValue(it) }) {
            return GlobalConfigState.CUSTOM
        }
        return GlobalConfigState.MIXED
    }
}

/** 全局选择器的五个条目状态。前三个「全部×」是均匀档；后三个按持久化身份得出。 */
enum class GlobalConfigState {
    ALL_DENIED,
    ALL_ASK,
    ALL_ALWAYS,
    DEFAULT,
    CUSTOM,

    /** 不再对应任何预设身份：单独改过某一条的场景。 */
    MIXED,
}
