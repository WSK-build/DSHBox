package interlock.relay.core.spi

import androidx.annotation.StringRes
import interlock.relay.core.interlock.InterlockQueue
import interlock.relay.core.interlock.OverlayApprovalChannel
import interlock.relay.core.interlock.PromptCodes
import interlock.relay.core.interlock.RelayAskSurface
import interlock.relay.core.R

/**
 * 宿主呈现面的装配口。core 的审批队列、提问通路与常驻通知都不直接持有任何界面类：
 * 页内卡、悬浮卡、通知栏卡三处呈现与提问悬浮卡全部由宿主注入，缺省一律视为不可用。
 *
 * 缺省 [NoSurfaces] 下，确认类调用没有可呈现的面，按既有语义回 `E_GATE_NO_FOREGROUND`。
 */
interface RelaySurfaces {

    /**
     * 审批悬浮卡通路（core 的 [OverlayApprovalChannel] 契约）。null 表示宿主不提供
     * 悬浮卡，队列裁决不再考虑这一条路。
     */
    fun approvalOverlay(): OverlayApprovalChannel?

    /**
     * 审批通知栏呈现面（core 的 [RelayApprovalNotificationSurface] 契约）。null 表示
     * 宿主不提供通知栏卡，队列裁决不再考虑这一条路。
     */
    fun approvalNotification(): RelayApprovalNotificationSurface?

    /**
     * 提问呈现面（core 的 [RelayAskSurface]，即 [interlock.relay.core.interlock.InterlockPrompt]
     * 契约加撤卡与在场查询）。null 表示宿主不提供提问卡，ask 一律回
     * [PromptCodes.INTERNAL_NO_CHANNEL]。
     */
    fun askSurface(): RelayAskSurface?

    /** 宿主界面此刻是否在前台：页内卡的成立条件之一。 */
    fun uiInForeground(): Boolean

    /**
     * 「显示在其他应用上层」系统授权页的宿主入口意图；宿主有自己的授权引导页时给出，
     * 缺省为 null（调用方退化为文字指引）。
     */
    fun overlayPermissionIntent(): android.content.Intent? = null

    /**
     * core 需要的通知渠道配置。core 侧会建渠道的三个使用方（常驻前台服务、屏幕采集
     * 前台服务、notify.post）按 [RelayNotificationChannelSpec.role] 各取一条；
     * 多出来的条目 core 会在语言变化时顺手把渠道名对回当前语言。
     */
    fun notificationChannels(): List<RelayNotificationChannelSpec>

    /** 缺省实现：三处呈现面都不可用，前台恒为否，渠道取 core 默认值。 */
    object NoSurfaces : RelaySurfaces {
        override fun approvalOverlay(): OverlayApprovalChannel? = null
        override fun approvalNotification(): RelayApprovalNotificationSurface? = null
        override fun askSurface(): RelayAskSurface? = null
        override fun uiInForeground(): Boolean = false
        override fun overlayPermissionIntent(): android.content.Intent? = null
        override fun notificationChannels(): List<RelayNotificationChannelSpec> =
            RelayNotificationChannelSpec.defaults()
    }
}

/** 通知渠道在 core 内的角色。使用方按角色取自己的那条配置。 */
enum class RelayChannelRole {
    /** 通道运行期那条常驻前台服务。 */
    FOREGROUND,

    /** 承载屏幕采集会话的前台服务。 */
    PROJECTION,

    /** notify.post 发出的普通通知。 */
    NOTIFY_POST,
}

/**
 * 一条通知渠道的配置：id、标题词条与重要度。标题词条由宿主给出（可指向宿主自己的
 * 资源）；id 与重要度决定系统设置里那条渠道的形态。
 */
data class RelayNotificationChannelSpec(
    val role: RelayChannelRole,
    val id: String,
    @StringRes val titleRes: Int,
    val importance: Int,
) {
    companion object {
        /** core 默认渠道：id 与标题词条都取 core 资源与既有缺省值。 */
        fun defaults(): List<RelayNotificationChannelSpec> = listOf(
            RelayNotificationChannelSpec(
                RelayChannelRole.FOREGROUND,
                DEFAULT_FOREGROUND_ID,
                R.string.relay_fgs_label,
                android.app.NotificationManager.IMPORTANCE_LOW,
            ),
            RelayNotificationChannelSpec(
                RelayChannelRole.PROJECTION,
                DEFAULT_PROJECTION_ID,
                R.string.relay_projection_channel,
                android.app.NotificationManager.IMPORTANCE_LOW,
            ),
            RelayNotificationChannelSpec(
                RelayChannelRole.NOTIFY_POST,
                DEFAULT_NOTIFY_POST_ID,
                R.string.relay_notify_channel_name,
                android.app.NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )

        const val DEFAULT_FOREGROUND_ID = "relay_channel"
        const val DEFAULT_PROJECTION_ID = "relay_projection"
        const val DEFAULT_NOTIFY_POST_ID = "relay_notify"
    }
}

/**
 * 审批通知栏呈现面契约。core 在两处驱动它：队列裁决落地时挂/撤（[show]/[cancel]），
 * 待决项有了终局结论时按编号收（[cancelFor]）；[canShow] 参与「通知栏这条路问不问得到人」
 * 的裁决，[lastPostState] 只作诊断读数（可用不等于可见）。
 */
interface RelayApprovalNotificationSurface {
    /** 系统是否允许本应用在这条渠道上投递。 */
    fun canShow(): Boolean

    /** 挂上（或替换）这条待决请求的通知。[waitingCount] 是排在后面还没轮上的问题数。 */
    fun show(request: InterlockQueue.Request, waitingCount: Int)

    /** 收掉通知栏上那条卡（无论属于哪一问）。 */
    fun cancel()

    /** 只收属于 [requestId] 的那一条。 */
    fun cancelFor(requestId: Int)

    /** 这条通路最近一次的呈现状态名。投递成功最多是「已投递、可见性未证实」。 */
    fun lastPostState(): String
}
