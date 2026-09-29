package interlock.relay.core.protocol

import androidx.annotation.StringRes

/**
 * 能力分类，决定界面上的分组顺序。
 *
 * [DANGEROUS] 单独成组并排在最后：这一组里每一条一次覆盖的都是"一批系统状态"，而不是
 * 一件看得见的动作。分组本身也是一句提示 —— 它与其余各组用同一种行式呈现，用户就不会
 * 把它们当成同一风险等级的东西。
 */
enum class CapabilityCategory { OBSERVE, CONTROL, DATA, SYSTEM, DANGEROUS }

/** 执行能力调用的三条通路。三者互不可见，跨后端的公共判定一律上收到表面层与裁决层。 */
enum class BackendId { DIRECT, A11Y, SHIZUKU }

/** 一次调用实际使用的执行模式。由宿主裁决后作为参数下发，后端不得自选。 */
enum class SurfaceKind(val wire: String) {
    FOREGROUND("foreground"),
    BACKEND_TRUSTED("trusted-display"),
    OBSERVE_ONLY("observe-only"),
    ;

    companion object {
        /** 通道协议用 [wire]，枚举名只用于代码内部，两套字面量不允许互相冒充。 */
        fun fromWire(value: String): SurfaceKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * 系统权限侧状态。六态而非两态：能否运行期弹窗、只能跳设置手动开、系统根本没有设置页、
 * 本机因设备策略不可用，以及**根本没有授权可给、只受前台与输入焦点约束**，是几类对用户
 * 完全不同的情况 —— 把它们并成一态会让界面在同一句「每次会话确认」下说出做不到的话。
 */
enum class SystemGrantState {
    GRANTED,
    RUNTIME_ASKABLE,
    MANUAL_ONLY,
    SESSION_CONSENT,
    /** 剪贴板那一类：没有系统弹框、也没有设置页可跳，公开 API 未提供后台读取路径。 */
    FOREGROUND_FOCUS,
    UNAVAILABLE_ON_DEVICE,
}

/**
 * 一条能力此刻「还缺什么」。界面上的红点与「·某某未授予」全部从这里来，
 * 不在界面里按能力 id 再判一遍——那会让能力清单、圆点与提示三处口径各走各的。
 */
sealed interface GrantGap {
    /** 无障碍服务没开：`ui.*` 与免确认截图的判据。 */
    data object Accessibility : GrantGap

    /** 通知监听没开。 */
    data object NotificationListener : GrantGap

    /** Shizuku 服务没在跑：shell 身份那几条的判据。 */
    data object Shizuku : GrantGap

    /** 某条运行时权限没授予。 */
    data class RuntimePermission(val permission: String) : GrantGap
}

/** 探测结果：状态本身，加上「没到 GRANTED 时用户该去开什么」。 */
data class ProbeResult(val state: SystemGrantState, val gaps: List<GrantGap>)

/** 助手授权档位。 */
enum class AccessTier { DENIED, ASK, ALWAYS }

/**
 * 授权档位上限。必须三档，少一档就会把「新会话仍要确认」静默升成「永久免确认」。
 *
 * - [ANY] 「始终允许」即长期免弹；
 * - [SESSION_ONLY] 「始终允许」仅在本助手会话内免弹，新会话仍需确认；
 * - [ASK_ONLY] 永不免弹：行为不可逆或涉及第三方应用数据。
 */
enum class TierCeiling { ANY, SESSION_ONLY, ASK_ONLY }

/**
 * 界面引导形态，按「系统设置能不能直接跳到这一项」划分：
 * [DEEP_LINK_OK] 有直达页；[LIST_ONLY] 只能跳到应用列表，要用户自己找；
 * [NO_SETTINGS_PAGE] 系统根本没有对应设置页，只能给文字步骤；
 * [NOT_REQUIRED] 无需用户动作（例如仅依赖 `<queries>` 声明的应用可见性），界面只显示状态不放跳转。
 */
enum class GuideClass { DEEP_LINK_OK, LIST_ONLY, NO_SETTINGS_PAGE, NOT_REQUIRED }

/**
 * 跳转目标。此处只存语义，真实的 Intent 常量在裁决层用 [android.provider.Settings] 的
 * 公开常量解析，避免在本层出现无法编译校验的字符串字面量。
 */
enum class SettingsTarget {
    NONE,
    APP_DETAILS,
    ACCESSIBILITY,
    NOTIFICATION_LISTENER,
    OVERLAY,
    WRITE_SETTINGS,
    BATTERY_EXEMPT,
    UNKNOWN_SOURCES,
    ALL_FILES,
}

/**
 * 助手可调用能力的封闭集合。[wire] 是通道协议里的稳定标识，不随界面文案变化。
 *
 * 集合内不含「跑任意 shell 命令」这类万能入口：它的权限并集覆盖其余全部能力，
 * 一次确认即替所有闸门背书。shell 身份只作为其它能力的内部实现手段存在。
 */
enum class CapabilityId(val wire: String, val category: CapabilityCategory) {
    UI_SNAPSHOT("ui.snapshot", CapabilityCategory.OBSERVE),
    SCREEN_CAPTURE("screen.capture", CapabilityCategory.OBSERVE),
    SCREEN_OBSERVE("screen.observe", CapabilityCategory.OBSERVE),
    SCREEN_RECORD("screen.record", CapabilityCategory.OBSERVE),
    NOTIFY_READ("notify.read", CapabilityCategory.OBSERVE),

    UI_TAP("ui.tap", CapabilityCategory.CONTROL),
    UI_SWIPE("ui.swipe", CapabilityCategory.CONTROL),
    UI_TEXT("ui.text", CapabilityCategory.CONTROL),
    UI_KEY("ui.key", CapabilityCategory.CONTROL),
    UI_CLICK("ui.click", CapabilityCategory.CONTROL),
    UI_LONG_CLICK("ui.longClick", CapabilityCategory.CONTROL),
    UI_SELECT("ui.select", CapabilityCategory.CONTROL),
    UI_DISMISS("ui.dismiss", CapabilityCategory.CONTROL),
    UI_SCROLL("ui.scroll", CapabilityCategory.CONTROL),
    UI_SET_VALUE("ui.setValue", CapabilityCategory.CONTROL),
    UI_WAIT_FOR("ui.waitFor", CapabilityCategory.CONTROL),
    UI_NODE("ui.node", CapabilityCategory.OBSERVE),
    UI_SET_PROGRESS("ui.setProgress", CapabilityCategory.CONTROL),
    UI_IME_ACTION("ui.imeAction", CapabilityCategory.CONTROL),
    APP_LAUNCH("app.launch", CapabilityCategory.CONTROL),
    APP_STOP("app.stop", CapabilityCategory.CONTROL),
    SURFACE_VIRTUAL("surface.virtual", CapabilityCategory.CONTROL),

    CLIPBOARD_READ("clip.read", CapabilityCategory.DATA),
    CLIPBOARD_WRITE("clip.write", CapabilityCategory.DATA),
    CONTACT_READ("contact.read", CapabilityCategory.DATA),
    CONTACT_WRITE("contact.write", CapabilityCategory.DATA),
    CALENDAR_READ("cal.read", CapabilityCategory.DATA),
    CALENDAR_WRITE("cal.write", CapabilityCategory.DATA),
    LOCATION_READ("loc.read", CapabilityCategory.DATA),
    MEDIA_READ("media.read", CapabilityCategory.DATA),
    MEDIA_WRITE("media.write", CapabilityCategory.DATA),
    AUDIO_CAPTURE("audio.capture", CapabilityCategory.DATA),

    PKG_QUERY("pkg.query", CapabilityCategory.SYSTEM),
    PKG_INSTALL("app.install", CapabilityCategory.SYSTEM),
    SECURE_SETTINGS("sys.settings.write", CapabilityCategory.SYSTEM),
    APPOPS_SET("appops.set", CapabilityCategory.SYSTEM),
    SYS_INTENT("sys.intent", CapabilityCategory.SYSTEM),
    SYS_SHELL("sys.shell", CapabilityCategory.DANGEROUS),
    NOTIFY_POST("notify.post", CapabilityCategory.SYSTEM),
    ;

    companion object {
        fun fromWire(value: String): CapabilityId? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * 能力的静态元信息。界面、通道能力清单与授权记录三处共用同一份，不允许各存一套。
 *
 * [minSdk] 承载平台版本下限，界面与判定都读它，不允许在别处再写一遍版本号。
 */
data class CapabilityDescriptor(
    val id: CapabilityId,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    /** 后端偏好顺序，按声明顺序尝试，取第一个支持当前模式的实现。 */
    val backends: List<BackendId>,
    /** 该能力可运行的执行模式。为空表示与执行模式无关。 */
    val surfaces: Set<SurfaceKind>,
    val guideClass: GuideClass,
    val settingsTarget: SettingsTarget,
    val ceiling: TierCeiling,
    /** 需运行期申请的系统的权限名；为空表示不由系统运行时权限门禁约束。 */
    val runtimePermissions: List<String>,
    val minSdk: Int = 29,
)

/** 某一能力在某一刻的完整状态。 */
data class CapabilityState(
    val descriptor: CapabilityDescriptor,
    val system: SystemGrantState,
    val tier: AccessTier,
)

/** 判定结果。[reason] 为可展示的错误码，[settingsTarget] 与 [guideClass] 决定界面上给什么引导。 */
data class GateDecision(
    val allowed: Boolean,
    val reason: String? = null,
    val retryable: Boolean = false,
    val guideClass: GuideClass? = null,
    val settingsTarget: SettingsTarget = SettingsTarget.NONE,
)
