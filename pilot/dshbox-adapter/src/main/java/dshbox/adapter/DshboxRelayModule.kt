package dshbox.adapter

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.dshbox.app.common.LogRedactor
import dshbox.adapter.entry.PilotActivity
import dshbox.adapter.locale.appLanguageTags
import dshbox.adapter.locale.withAppLanguage
import dshbox.adapter.surfaces.AskOverlayPresenter
import dshbox.adapter.surfaces.OverlayApprovalPresenter
import dshbox.adapter.surfaces.PilotApprovalNotification
import interlock.relay.core.interlock.InterlockQueue
import interlock.relay.core.log.RunLog
import interlock.relay.core.runtime.RelayConfig
import interlock.relay.core.runtime.RelayRuntime
import interlock.relay.core.spi.RelayApprovalNotificationSurface
import interlock.relay.core.spi.RelayChannelRole
import interlock.relay.core.spi.RelayNotificationChannelSpec
import interlock.relay.core.spi.RelayPathPolicy
import interlock.relay.core.spi.RelayRedactor
import interlock.relay.core.spi.RelaySurfaces
import interlock.relay.core.spi.RelayText

/**
 * dshbox 侧的装配入口：把宿主的既有取值与呈现面交给 core（[RelayConfig]）。
 *
 * core 只持有通用默认值；dshbox 的名字与取值全部在这里注入——挂载点、目录与命令名、
 * 相册目录名、通知渠道 id、文案语言与呈现通路。宿主侧的用户可见行为因此与既有部署
 * 完全一致（guest 看到 `/opt/pilot`、相册里是 `DSHBox`、系统设置里是 `pilot_*` 渠道）。
 *
 * 本模块**永不发布**：这里的所有取值都属于 dshbox 部署，不属于协议面。
 *
 * 用法：宿主在沙盒启动处（`SandboxService`）与助手页入口（[PilotActivity]）调
 * `RelayRuntime.start(applicationContext, dshboxRelayConfig(applicationContext))`。
 * start 幂等，重复调用无副作用；配置采纳「先到先得」，首个 start 带来的 config 生效，
 * 后到的被忽略（即便容器已被别人先 get() 建出，本配置也一定生效）。
 */
fun dshboxRelayConfig(context: Context): RelayConfig {
    val appContext = context.applicationContext
    return RelayConfig(
        text = DshboxRelayText(appContext),
        surfaces = DshboxRelaySurfaces(appContext),
        pathPolicy = DshboxRelayPaths,
        redactor = DshboxRelayRedactor,
    )
}

/**
 * 文案与语言走 dshbox 现有机制：应用内手选语言（[withAppLanguage]）决定每一条词条，
 * [appLanguageTags] 报出同一份语言标签。core 只认这两个取用口，宿主偏好名
 * （app_settings/app_locale_tag）留在语言层内部，不进 core。
 */
private class DshboxRelayText(private val appContext: Context) : RelayText {

    override fun string(resId: Int): String = appContext.withAppLanguage().getString(resId)

    override fun languageTags(): String? = appLanguageTags(appContext)
}

/**
 * dshbox 的路径与命名策略：全部取既有部署值，沙盒侧看到的挂载点、命令名、产物前缀与
 * 相册目录名都保持不变。core 的通用默认值在本装配里一项都不采用——唯一例外是
 * [assetClient]：资产名对 guest 不可见（guest 只见物化后的 `bin/pilot`），
 * 直接用核心资产 `relay/relay-client.cjs`，避免在本模块再留一份会漂移的脚本副本。
 */
private object DshboxRelayPaths : RelayPathPolicy {
    override val guestEntry: String = "/opt/pilot"
    override val hostDirName: String = "pilot"
    override val cliName: String = "pilot"
    override val artifactPrefix: String = "pilot-"

    /** 与 core 默认同值：guest 看不到资产名，用核心资产即可，不复制第二份脚本。 */
    override val assetClient: String = RelayPathPolicy.DEFAULT_ASSET_CLIENT

    /** 媒体库相册目录名：写入落点与占用对账共用这一个值（ Pictures/DSHBox/ 等三处）。 */
    override val mediaAlbumDir: String = "DSHBox"
}

/**
 * dshbox 的呈现面装配。三条通路（悬浮卡、通知栏卡、提问卡）与前台判定都沿用既有实现；
 * 通知渠道用 dshbox 现有 id 与词条，系统设置里看到的分组与既有版本一致。
 *
 * 呈现器按需创建并持有：core 的装配根在构造中途会取悬浮通路与渠道表，故这些入口
 * 不得回头取装配根内的对象——日志口经 [runLogRef] 延迟解析（失败记录都在运行期，
 * 那时装配早已完成），其余状态在各自的取用点上才读装配根。
 */
private class DshboxRelaySurfaces(private val appContext: Context) : RelaySurfaces {

    /** 运行日志在装配根里，呈现器只在记失败日志那一刻才解析它（见类注释）。 */
    private val runLogRef: () -> RunLog = { RelayRuntime.get(appContext).runLog }

    private val overlayPresenter: OverlayApprovalPresenter by lazy {
        OverlayApprovalPresenter(appContext, runLogRef)
    }

    private val approvalNotificationSurface by lazy {
        DshboxApprovalNotificationSurface(
            PilotApprovalNotification(
                appContext,
                hostIntent = { PilotActivity.intent(appContext) },
                runLog = runLogRef,
                // 通知到点把「这一问也关掉」接回队列：与页内卡/悬浮卡到点同一条收尾，
                // 只撤通知会留下一张点下去已经不算数的框。
                onExpired = { requestId -> RelayRuntime.get(appContext).expireApproval(requestId) },
            ),
        )
    }

    private val askPresenter: AskOverlayPresenter by lazy {
        AskOverlayPresenter(
            appContext,
            cardOnScreen = {
                overlayPresenter.isPresented() || RelayRuntime.get(appContext).pendingApproval.value != null
            },
            overlayBlind = { RelayRuntime.get(appContext).overlayBlind() },
        )
    }

    override fun approvalOverlay(): OverlayApprovalPresenter = overlayPresenter

    override fun approvalNotification(): RelayApprovalNotificationSurface = approvalNotificationSurface

    override fun askSurface(): AskOverlayPresenter = askPresenter

    /**
     * 助手页此刻是否可见。读 core 的前台跟踪器：[PilotActivity] 在 onResume/onStop
     * 里维护它，这里只读，不另设第二份事实源。
     */
    override fun uiInForeground(): Boolean =
        runCatching { RelayRuntime.get(appContext).foreground.visible }.getOrDefault(false)

    /** 系统悬浮窗授权页；与悬浮卡呈现器同一条入口，没有宿主自建的引导页。 */
    override fun overlayPermissionIntent(): Intent? = overlayPresenter.requestPermissionIntent()

    /**
     * dshbox 现有渠道：id 与重要度都按既有部署（三条都是 IMPORTANCE_LOW 的静音档），
     * 标题词条取本模块资源；`pilot_approval` 那条审批渠道由通知呈现面自建，不走这里。
     */
    override fun notificationChannels(): List<RelayNotificationChannelSpec> = listOf(
        RelayNotificationChannelSpec(
            RelayChannelRole.FOREGROUND,
            "pilot_channel",
            R.string.pilot_fgs_label,
            NotificationManager.IMPORTANCE_LOW,
        ),
        RelayNotificationChannelSpec(
            RelayChannelRole.PROJECTION,
            "pilot_projection",
            R.string.pilot_projection_channel,
            NotificationManager.IMPORTANCE_LOW,
        ),
        RelayNotificationChannelSpec(
            RelayChannelRole.NOTIFY_POST,
            "pilot.assistant",
            R.string.pilot_notify_channel_name,
            NotificationManager.IMPORTANCE_LOW,
        ),
    )
}

/**
 * core 通知呈现面契约的适配：呈现状态以枚举名上报（与既有运行日志的 `postState`
 * 取值一致），其余五个动作原样转发。
 */
private class DshboxApprovalNotificationSurface(
    private val impl: PilotApprovalNotification,
) : RelayApprovalNotificationSurface {

    override fun canShow(): Boolean = impl.canShow()

    override fun show(request: InterlockQueue.Request, waitingCount: Int) = impl.show(request, waitingCount)

    override fun cancel() = impl.cancel()

    override fun cancelFor(requestId: Int) = impl.cancelFor(requestId)

    override fun lastPostState(): String = impl.lastPostState.name
}

/**
 * 运行日志字段脱敏接 dshbox 的统一日志脱敏规则：core 默认恒等（字段已按事件字典受限），
 * 这里换成与 app 其余日志同一条策略，凭据形状的字段值不进运行日志。
 */
private val DshboxRelayRedactor = RelayRedactor { text -> LogRedactor.redact(text) }
