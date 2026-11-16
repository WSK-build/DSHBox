package interlock.relay.core.interlock

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import interlock.relay.core.protocol.CapabilityDescriptor
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.CapabilityRegistry
import interlock.relay.core.protocol.GrantGap
import interlock.relay.core.protocol.ProbeResult
import interlock.relay.core.protocol.SurfaceKind
import interlock.relay.core.protocol.SystemGrantState
import interlock.relay.core.exec.a11y.RelayAccessibilityService
import interlock.relay.core.exec.direct.MediaKinds
import interlock.relay.core.exec.direct.RelayNotificationListener

/**
 * 「服务没绑上」在用户侧分成的两种现场。必须分开，因为两者要做的动作完全不同。
 *
 * 冷启动那一刻 `RelayAccessibilityService.current()` 必为 null，只看绑定状态分不出
 * 这两档；能分开的只有 Settings 里那份名单，见 [SystemStateProbe.accessibilityListedInSettings]。
 */
enum class AccessibilityGap {
    /** 名单里已经没有本应用：权限被真的关掉了，必须回设置页重新打开。 */
    REVOKED,

    /** 名单里仍有本应用、只是服务没绑上：应用被系统关掉了，重开应用即可，不必动设置页。 */
    NOT_BOUND,
}

/**
 * 系统权限侧状态探测。只读，不发起任何能力调用，也不尝试「顺手开启」。
 *
 * 六态的划分依据是用户能做些什么：能弹系统授权框、只能去设置页手开、
 * 系统每次调用都要确认一次、条件只由前台与输入焦点决定（系统既不给框也没有设置页）、
 * 以及本机策略导致根本做不到。
 */
class SystemStateProbe(
    context: Context,
    /**
     * 同一份 Shizuku 快照的拆开读数：在跑（[available]，binder 存活）、已授权
     * （[grantedVerified]）、执行侧用户服务绑上（[userBound]）。三者**合起来**才是
     * shell 身份真执行的前提（[shizukuProbe] 三环齐 → GRANTED，缺哪环按环缺口点名），
     * 不再需要"合成后的一句"——单独的合成参数是死入口（不参与任何判定，删去）。
     * SurfacePolicy 那一侧的合成判据由装配根另行提供，不经本探测。
     */
    private val available: () -> Boolean = { false },
    private val grantedVerified: () -> Boolean = { false },
    private val trustedDisplayAvailable: () -> Boolean = { false },
    /** 执行侧用户服务此刻是否绑上（装配根从 Shizuku 后端读，缺省按未绑）。 */
    private val userBound: () -> Boolean = { false },
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {

    private val appContext = context.applicationContext

    private val appOps by lazy {
        appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    }

    /**
     * 依赖无障碍的能力在探测时发现服务没绑上就说一声，界面才有东西可呈现：
     * 缺这条口子时用户那边什么都看不见，只有信箱里多了一条错误码。
     *
     * 挂在这里而不是挂在错误码上，有两个原因。一是缺口判定本身就在此处，
     * 闸门与能力清单都从这一处走；二是执行期那条路报的不是 `E_GATE_SYSTEM_MISSING`
     * 而是 [interlock.relay.core.exec.a11y.A11yBackend] 的 `E_BACKEND_UNAVAILABLE`
     * （可重试），只盯前者就会整档漏掉「名单还在、服务没绑」这一类。
     * 回调可能在任意线程上触发，切线程由接收方负责。
     */
    @Volatile
    var onAccessibilityGap: ((CapabilityId, AccessibilityGap) -> Unit)? = null

    /** 不带路线的探测：整表按「有哪条路就用哪条」的旧口径回答，见 [probe] 的路线重载。 */
    fun probe(descriptor: CapabilityDescriptor): ProbeResult = probe(descriptor, null)

    /**
     * 按**本次实际要走的执行路线**探测系统权限。
     *
     * 同一条能力在不同路线上需要的系统条件不同：坐标四条落可信屏时走的是 shell 的
     * `input`，不需要无障碍；截屏落前台要的是无障碍取帧（API 30 起）或系统投屏确认框。
     * 只按能力名回答会让「用户选了前台、可信屏也在」这种组合报出一条根本用不到的
     * 权限状态——能力清单与闸门都按选定路线问，口径才与真实执行一致。
     *
     * [route] 为 null 时保持整表口径（按「存在哪条路」回答），供没有裁决结果的场景使用；
     * 与路线无关的能力（节点级十一条、shell 族、数据读取等）无论 [route] 是否为 null
     * 都落到整表。
     */
    fun probe(descriptor: CapabilityDescriptor, route: SurfaceKind?): ProbeResult {
        if (route != null) routeState(descriptor.id, route)?.let { return it }
        return probeAllRoutes(descriptor)
    }

    /** 按选定路线能直接定的那几条；与路线无关的能力回 null，落到整表。 */
    private fun routeState(id: CapabilityId, route: SurfaceKind): ProbeResult? = when (id) {
        // 坐标四条：可信屏上走 `input -d`，不需要无障碍；前台路才问无障碍。
        CapabilityId.UI_TAP,
        CapabilityId.UI_SWIPE,
        CapabilityId.UI_TEXT,
        CapabilityId.UI_KEY,
        -> if (route == SurfaceKind.BACKEND_TRUSTED) granted() else accessibilityState(id)

        // 截屏：可信屏的帧来自那块屏自己的持有者，系统框不出现；前台路要么无障碍取帧
        // （免确认那条路线自 Android 11 起，见 SCREENSHOT_MIN_SDK），要么就要系统投屏确认；
        // 仅观测那条面永远取的是投屏帧，照旧要确认。
        CapabilityId.SCREEN_CAPTURE -> when (route) {
            SurfaceKind.BACKEND_TRUSTED -> granted()
            SurfaceKind.FOREGROUND ->
                // 免系统框的取帧出自已绑定的服务（取帧方法挂在服务实例上），所以这里的判据
                // 只认运行时绑定：名单在、服务没绑时那条路根本不可用，真实可走的只剩投屏
                // 确认 —— accessibilityEnabled() 会把这种现场放行成免确认，与实调不符。
                if (RelayAccessibilityService.current() != null &&
                    sdkInt >= CapabilityRegistry.SCREENSHOT_MIN_SDK
                ) {
                    granted()
                } else {
                    ProbeResult(SystemGrantState.SESSION_CONSENT, emptyList())
                }

            else -> ProbeResult(SystemGrantState.SESSION_CONSENT, emptyList())
        }

        // 录屏：后台那一条的帧同样出自那块屏的持有者；落回用户眼前这块屏（前台或仅观测）
        // 就要那一次系统确认。
        CapabilityId.SCREEN_RECORD ->
            if (route == SurfaceKind.BACKEND_TRUSTED) {
                granted()
            } else {
                ProbeResult(SystemGrantState.SESSION_CONSENT, emptyList())
            }

        else -> null
    }

    private fun probeAllRoutes(descriptor: CapabilityDescriptor): ProbeResult = when (descriptor.id) {
        // 依赖 shell 身份：**三环齐备就是 GRANTED**（在跑+已授权+用户服务绑上=shell 那几条
        // 此刻真能跑），缺哪一环才回落 MANUAL_ONLY 并按环缺口点名——服务没在跑、在跑
        // 但没授权、授权齐了但用户服务没绑上，三者的下一步完全不同（启动它 / 在管理器里
        // 放行 / 点重连）；合成一句"服务没开"会把没授权的场景说成缺启动，让人去找一个
        // 不存在的开关。少了第一问（就绪就放行），三环齐的机器上 shell 族六条会被闸门
        // 一律挡在 MANUAL_ONLY 上。
        CapabilityId.SECURE_SETTINGS,
        CapabilityId.APPOPS_SET,
        CapabilityId.PKG_INSTALL,
        CapabilityId.APP_STOP,
        CapabilityId.SURFACE_VIRTUAL,
        CapabilityId.SYS_SHELL,
        // 完全 Shizuku 走同一条用户服务：缺口分环与 sys.shell 共用同一判据，
        // 清单与闸门才不会各说一套。
        CapabilityId.SYS_SHELL_FULL,
        -> shizukuProbe()
        // 节点级操作只有无障碍这一条路：`performAction` 挂在节点树上，两块屏上的树都只能
        // 从这个服务里取（可信屏那棵走 getWindowsOnAllDisplays）。所以它们不像 ui.tap
        // 那样能在没有无障碍时靠可信屏兜底 —— 缺无障碍就是缺无障碍。
        CapabilityId.UI_SNAPSHOT,
        CapabilityId.UI_NODE,
        CapabilityId.UI_WAIT_FOR,
        CapabilityId.UI_SET_PROGRESS,
        CapabilityId.UI_IME_ACTION,
        CapabilityId.UI_CLICK,
        CapabilityId.UI_LONG_CLICK,
        CapabilityId.UI_SELECT,
        CapabilityId.UI_DISMISS,
        CapabilityId.UI_SCROLL,
        CapabilityId.UI_SET_VALUE,
        -> accessibilityState(descriptor.id)

        // 点击、滑动、输入与按键在可信屏上走的是 `input -d`，不需要无障碍服务；
        // 只有真屏那一条路才要求它。少了这一问，后台模式会被一个它根本用不到的权限
        // 挡在门外——表现就是「后台操控完全不起作用」，而屏幕上什么都没发生。
        // 节点级那十一条不在此列：两块屏的节点树都出自无障碍服务。
        // 先问那块屏在不在：它在时这条能力根本不需要无障碍，缺无障碍也不算它的缺口。
        CapabilityId.UI_TAP,
        CapabilityId.UI_SWIPE,
        CapabilityId.UI_TEXT,
        CapabilityId.UI_KEY,
        -> if (trustedDisplayAvailable()) granted() else accessibilityState(descriptor.id)

        // 三条通路里只有投屏那一条要系统框：无障碍开着就直接取帧，可信屏活着时取的是那块屏
        // 自己的帧、系统框根本不出现（`ShizukuBackend.displayCapture` → 服务端取帧）。
        // 只问无障碍会把后一种现场说成"每次会话确认"，而那一次确认并不存在 —— 与
        // 下面 `SCREEN_RECORD` 同一个判法。
        CapabilityId.SCREEN_CAPTURE ->
            if (accessibilityEnabled() || trustedDisplayAvailable()) granted()
            else ProbeResult(SystemGrantState.SESSION_CONSENT, emptyList())

        // 录屏落后台屏时取的是那块屏自己的帧，系统投屏确认框根本不出现；
        // 只有落回用户眼前这块屏时才要那一次确认。与 ui.tap 那四条同一个判法。
        CapabilityId.SCREEN_RECORD ->
            if (trustedDisplayAvailable()) granted() else ProbeResult(SystemGrantState.SESSION_CONSENT, emptyList())

        CapabilityId.SCREEN_OBSERVE,
        -> ProbeResult(SystemGrantState.SESSION_CONSENT, emptyList())

        CapabilityId.CLIPBOARD_READ,
        CapabilityId.CLIPBOARD_WRITE,
        // 剪贴板无设置页，且受前台与输入焦点约束，公开 API 未提供任何后台读取路径。
        // 这不是「系统每次弹自己的框」—— 那道框根本不存在，所以单列一态，
        // 免得能力详情页在同一句「每次会话确认」下说出一件做不到的事。
        -> ProbeResult(SystemGrantState.FOREGROUND_FOCUS, emptyList())

        CapabilityId.NOTIFY_READ ->
            if (notificationListenerEnabled()) granted() else ProbeResult(SystemGrantState.MANUAL_ONLY, listOf(GrantGap.NotificationListener))

        CapabilityId.APP_LAUNCH,
        CapabilityId.PKG_QUERY,
        CapabilityId.MEDIA_WRITE,
        // 语义快捷入口走的是系统公开 action：系统既没有对应的开关，也没有授权框可弹。
        CapabilityId.SYS_INTENT,
        -> granted()

        CapabilityId.AUDIO_CAPTURE ->
            if (microphoneBlockedByPolicy()) {
                ProbeResult(SystemGrantState.UNAVAILABLE_ON_DEVICE, emptyList())
            } else {
                runtimeState(descriptor)
            }

        CapabilityId.CONTACT_READ,
        CapabilityId.CONTACT_WRITE,
        CapabilityId.CALENDAR_READ,
        CapabilityId.CALENDAR_WRITE,
        CapabilityId.LOCATION_READ,
        CapabilityId.NOTIFY_POST,
        -> runtimeState(descriptor)

        // 媒体读取**不按权限并集**回答：探测问的是「这条能力至少有一类现在读不读得到」，
        // 图片/视频/音频三类各自只看自己的权限，一类可读就算系统侧可达。按并集回答，
        // 「用户没给音频权限」会把完全可读的相册一起判成不可用——那正是能力清单
        // 「缺权限」红点与真实调用脱节的场景。缺的是哪一类，由调用时按 kind 校验再报。
        CapabilityId.MEDIA_READ -> mediaProbe(::granted, sdkInt)

        // 设备状态：唯一的外部依赖 ACCESS_NETWORK_STATE 是安装时**普通**（normal）权限，
        // 声明即授予，注册表没把它列进 runtimePermissions（列了就会被报成「缺权限待授」，
        // 而系统侧没有任何要授的内容）。运行期权限表为空，这条能力系统侧无任何门槛。
        CapabilityId.DEVICE_STATUS -> runtimeState(descriptor)
    }

    private fun granted() = ProbeResult(SystemGrantState.GRANTED, emptyList())

    /**
     * shell 族能力的探测结论：三环齐备 → GRANTED（此刻真能跑，闸门不留它）；
     * 缺哪环 → MANUAL_ONLY 加上那一环的缺口，纯分环见伴生 [shizukuProbe]。
     * 在跑/已授权/绑了三个独立事实直接喂进纯函数，不在壳里把"没就绪"合成一句。
     */
    private fun shizukuProbe(): ProbeResult = SystemStateProbe.shizukuProbe(
        available(), grantedVerified(), userBound(),
    )

    /** 需要运行期权限的能力：已授予则放行，未授予则列出还缺哪几条。 */
    private fun runtimeState(descriptor: CapabilityDescriptor): ProbeResult =
        runtimeProbe(descriptor, ::granted)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 依赖无障碍的能力的统一出口：服务绑着就放行，没绑则报缺口并把现场交给
     * [onAccessibilityGap]。
     *
     * 缺口只在这里判一次：能力清单、闸门与界面提示读的是同一个结论，
     * 别处再算一遍就会出现「能力清单说可用、界面喊缺权限、调用却正常」三套口径。
     */
    private fun accessibilityState(id: CapabilityId): ProbeResult {
        val gap = accessibilityGap() ?: return granted()
        onAccessibilityGap?.invoke(id, gap)
        // 「名单还在、服务没绑」不挡调用：权限本身没丢，只差进程活着。挡下来会让
        // 应用在后台被带走的那段空档里整批能力报缺系统权限，而正确的下一步是重开应用。
        return if (gap == AccessibilityGap.NOT_BOUND) {
            granted()
        } else {
            ProbeResult(SystemGrantState.MANUAL_ONLY, listOf(GrantGap.Accessibility))
        }
    }

    /**
     * 无障碍服务是否可用。**先看运行时绑定，再看 Settings 键**：
     *
     * 那个键在部分厂商 ROM 上会留空或滞后：服务已绑定、`ui.*` 全部能跑，键里却查不到
     * 本应用。只读键会让能力清单与面板把可用判成不可用，助手按能力清单办事就永远不调它。
     */
    fun accessibilityEnabled(): Boolean =
        RelayAccessibilityService.current() != null || accessibilityListedInSettings()

    /**
     * **只看** Settings 里那份「已启用无障碍服务」名单，完全不看运行时绑定。
     *
     * 与 [accessibilityEnabled] 分开存在，是因为合成后的那一个布尔表达不了「权限被撤销」与
     * 「还没绑上」的区别，而这两件事用户要做的完全不同。该名单在部分厂商 ROM 上会留空或
     * 滞后（见 [accessibilityEnabled]），所以它单独成立时只作「权限还在」的证据，
     * 不足以用来放行一次调用。
     */
    fun accessibilityListedInSettings(): Boolean =
        settingsKeyNamesSelf(SETTINGS_ACCESSIBILITY_SERVICES)

    /** 无障碍此刻的缺口；服务已绑定则为 null。 */
    fun accessibilityGap(): AccessibilityGap? = when {
        RelayAccessibilityService.current() != null -> null
        accessibilityListedInSettings() -> AccessibilityGap.NOT_BOUND
        else -> AccessibilityGap.REVOKED
    }

    /** 通知监听同上：绑定状态是权威信号，Settings 键只作后备。 */
    fun notificationListenerEnabled(): Boolean =
        RelayNotificationListener.Holder.isConnected() ||
            settingsKeyNamesSelf(SETTINGS_ENABLED_LISTENERS)

    /** 那条以冒号分隔的组件名单里有没有本应用。键读不到即视为没有。 */
    private fun settingsKeyNamesSelf(key: String): Boolean =
        Settings.Secure.getString(appContext.contentResolver, key)
            ?.split(':')
            .orEmpty()
            .any { it.contains(appContext.packageName, ignoreCase = true) }

    /**
     * 用户可在系统层面整体关闭麦克风，此时采集会静默失败。
     * 以应用操作模式为判定依据；该判定只反映本应用的受限状态，不等同于全局开关。
     *
     * 该取值器已标记废弃，替换形式要求 Android 14 以上；本模块下限更低，
     * 且旧形式在各版本行为一致，继续用而不加版本分支。
     */
    @Suppress("DEPRECATION")
    private fun microphoneBlockedByPolicy(): Boolean = runCatching {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_RECORD_AUDIO,
            Process.myUid(),
            appContext.packageName,
        )
    }.getOrDefault(AppOpsManager.MODE_ALLOWED) == AppOpsManager.MODE_IGNORED

        internal companion object {
            const val SETTINGS_ACCESSIBILITY_SERVICES = "enabled_accessibility_services"
            const val SETTINGS_ENABLED_LISTENERS = "enabled_notification_listeners"

            /**
             * 探测结论的**纯判据**，收在伴生对象里供 JVM 单测直接穷举：设备构造
             * [SystemStateProbe] 需要 Context 与无障碍服务，JVM 够不着；而「哪些能力此刻
             * 该报什么状态」恰恰是能力清单红点与真实调用对齐的关键，必须可测。
             */

            /**
             * shell 族能力的探测结论，纯判据供 JVM 单测穷举：**三环齐备就是 GRANTED**——
             * 在跑（[running]）、已授权（[granted]）、执行侧用户服务绑上（[bound]）三者齐时
             * shell 身份此刻真能跑，闸门不得留它；缺哪一环才回落 MANUAL_ONLY 并按环缺口：
             * 没跑 → [GrantGap.Shizuku]（去启动），在跑没授权 → [GrantGap.ShizukuNotGranted]
             * （去管理器放行），授权齐未绑 → [GrantGap.ShizukuNotBound]（点重连）。
             */
            internal fun shizukuProbe(running: Boolean, granted: Boolean, bound: Boolean): ProbeResult {
                if (running && granted && bound) return ProbeResult(SystemGrantState.GRANTED, emptyList())
                return ProbeResult(SystemGrantState.MANUAL_ONLY, listOf(shizukuGap(running, granted, bound)))
            }

            /**
             * 缺环点名（[shizukuProbe] 落 MANUAL_ONLY 时用）：三环不齐是分派前提，
             * 首个缺席的环点名——合成一句"服务没开"会把没授权的场景说成缺启动。
             */
            internal fun shizukuGap(running: Boolean, granted: Boolean, bound: Boolean): GrantGap = when {
                !running -> GrantGap.Shizuku
                !granted -> GrantGap.ShizukuNotGranted
                !bound -> GrantGap.ShizukuNotBound
                else -> GrantGap.Shizuku
            }


        /**
         * 媒体读取的探测结论（口径见上面的分支注释）：一类可读即 GRANTED；完全不可读时
         * 缺口按各类**主**权限回答，替代候选（选定照片）不进清单。
         */
        internal fun mediaProbe(granted: (String) -> Boolean, sdkInt: Int): ProbeResult =
            if (MediaKinds.anyKindReadable(granted, sdkInt)) {
                ProbeResult(SystemGrantState.GRANTED, emptyList())
            } else {
                ProbeResult(SystemGrantState.RUNTIME_ASKABLE, MediaKinds.allMissing(granted, sdkInt).map { GrantGap.RuntimePermission(it) })
            }

        /**
         * 运行期权限表驱动的结论：表里**没有**的（如设备状态——它唯一的外部依赖
         * `ACCESS_NETWORK_STATE` 是安装时普通权限，声明即授予）一律 GRANTED；
         * 列进表里的才按「缺哪几条」回答。
         */
        internal fun runtimeProbe(descriptor: CapabilityDescriptor, granted: (String) -> Boolean): ProbeResult {
            val missing = descriptor.runtimePermissions.filterNot(granted)
            return if (missing.isEmpty()) {
                ProbeResult(SystemGrantState.GRANTED, emptyList())
            } else {
                ProbeResult(SystemGrantState.RUNTIME_ASKABLE, missing.map { GrantGap.RuntimePermission(it) })
            }
        }
    }
}
