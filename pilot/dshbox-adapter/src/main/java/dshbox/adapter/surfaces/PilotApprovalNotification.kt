package dshbox.adapter.surfaces

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dshbox.adapter.R
import interlock.relay.core.log.LogEvent
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.log.RunLog
import interlock.relay.core.runtime.monotonicNow
import dshbox.adapter.locale.withAppLanguage
import interlock.relay.core.interlock.InterlockChoice
import interlock.relay.core.interlock.InterlockQueue

/**
 * 一次资格判定的输入。全部是布尔量：JVM 测试不必碰 Android 类型就能穷举这张表。
 */
internal data class PostEligibility(
    /** 上一次投递失败后闩住的标记还挂没挂着。 */
    val broken: Boolean,
    /** 失败标记的退烧间隔是否已过。 */
    val cooldownElapsed: Boolean,
    /** 应用总开关是否允许通知。 */
    val notificationsEnabled: Boolean,
    /** 「确认请求」这一条渠道是否被用户单独降成了静音。 */
    val channelMuted: Boolean,
    /** 系统通知服务是否还取得到。 */
    val systemPresent: Boolean,
)

/**
 * 资格判定的纯函数：够格回 ELIGIBLE，缺任何一环都是 UNAVAILABLE。
 * 「够格」只说系统允许投递，不含任何「用户会看见」的承诺。
 */
internal fun postStateAfterEligibility(e: PostEligibility): PilotApprovalNotification.PostState = when {
    !e.systemPresent -> PilotApprovalNotification.PostState.UNAVAILABLE
    e.broken && !e.cooldownElapsed -> PilotApprovalNotification.PostState.UNAVAILABLE
    e.notificationsEnabled && !e.channelMuted -> PilotApprovalNotification.PostState.ELIGIBLE
    else -> PilotApprovalNotification.PostState.UNAVAILABLE
}

/**
 * 一次真投递的结局的纯函数。
 *
 * 挂上也只能回「已投递、可见性未证实」：渠道重要性、横幅开关、免打扰、锁屏细节
 * 全部发生在系统侧，这里拿不到任何看见与否的证据。挂失败才是「这条路问不到人」。
 */
internal fun postStateAfterPost(posted: Boolean): PilotApprovalNotification.PostState =
    if (posted) PilotApprovalNotification.PostState.POSTED_VISIBILITY_UNVERIFIED
    else PilotApprovalNotification.PostState.UNAVAILABLE

/**
 * 通知栏上的「允许 / 拒绝」。
 *
 * 它补的是悬浮窗看不见的那一段：任一屏上出现带 HIDE_NON_SYSTEM_OVERLAY_WINDOWS 的窗口时，
 * 平台会跨屏隐藏本应用的悬浮窗——前台是系统设置一类页面时必现，可信虚拟屏在跑时同样藏。
 * 这一档不用「把本模块界面推到前台」作兜底：那条同样问得到人，但盖掉用户正在看的画面，
 * 且答完之后的第一次无障碍写操作会被判给自家窗口，这次确认等于作废。下拉通知栏不打断
 * 任何人，答案一样有效。
 *
 * 一条待决请求只有一条通知：编号固定，重复呈现是替换而不是叠一条。同一件事在通知栏里
 * 排三行时，用户答的是哪一次说不清，而三个答复只会落地一次。
 */
class PilotApprovalNotification(
    context: Context,
    /**
     * 点开通知正文的落点。由装配根给：通知这一层只负责把问题摊到人眼前，
     * 不认识界面类，也就不该持有它们的引用。
     */
    private val hostIntent: () -> Intent,
    /**
     * 运行日志的**延迟**取用口。装配根在构造中途就会向呈现面要这条通路，而日志口
     * 在装配根里此刻还没建好；通知只在挂失败那一处记日志，到那时装配早已完成，
     * 所以这里收 lambda、用时取值（与悬浮审批卡同规）。
     */
    private val runLog: () -> RunLog,
    /**
     * 通知到点时的出口。通知这一层只负责"到点把通知收掉"，而**收掉通知不等于这一问结束**：
     * 页内卡与悬浮卡到点都会把待决项一并关掉并撤销全部呈现者，只通知自己撤的话，
     * 屏幕上就会留下一张点下去已经不算数的框。由装配根把它接回队列。
     */
    private val onExpired: (Int) -> Unit = {},
) {

    /**
     * 通知这一路的呈现状态。状态机与判据抽成纯函数（见 [postStateAfterEligibility]、
     * [postStateAfterPost]），可在无设备环境下验证。
     */
    enum class PostState {
        /** 够格挂：系统允许投递，只是还没真挂过。可用不等于可见。 */
        ELIGIBLE,

        /**
         * 保留档：只有拿到可观察的呈现证据（用户点开、前台卡片等）才允许升到这里。
         * 通知通路本身永远到不了这一档——投递成功最多证明「进了通知栏」。
         */
        POSTED,

        /**
         * 已投递到通知栏，但**是否被用户看见未证实**：横幅、静音、免打扰、锁屏
         * 都由系统与用户说了算，应用侧没有任何回调能证明那张卡亮到过人眼前。
         * 通知投递成功最多只能报这一档，不得当成「用户看见了」。
         */
        POSTED_VISIBILITY_UNVERIFIED,

        /** 这条通路此刻问不到人：权限、渠道或投递失败。下一步是页内或另一条通路。 */
        UNAVAILABLE,
    }

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前这条通知代表哪一张待决请求；null 表示通知栏上干净。 */
    @Volatile
    private var shownId: Int? = null

    /** 建那次渠道名所用的语言；null 表示这条渠道还没由本模块建过。 */
    @Volatile
    private var channelTag: String? = null

    /**
     * 上一次真去挂的时候有没有挂上。
     *
     * [canShow] 说的是「系统允许我挂」，不等于「这条通知到了用户眼前」：渠道可能被用户在
     * 系统里删掉、图可能超出 binder 缓冲，这些都不抛 SecurityException。那种时刻这条通路
     * 一个可点的东西都没有，继续当它可用就等于这一问既问不到人、也不落到下一条通路。
     * 下一次挂成功即复位 —— 每问一次都会重新走一遍这里，不需要额外的恢复动作。
     */
    @Volatile
    private var deliveryBroken = false

    /** 上一次挂失败的时刻，用来给那个标记退烧。 */
    @Volatile
    private var lastFailureAtMs = 0L

    /**
     * 这条通路最近一次呈现状态。供页内与路由读：可用≠可见，
     * 投递成功最多记「已投递、可见性未证实」，只有挂失败才记「问不到人」。
     */
    @Volatile
    var lastPostState: PostState = PostState.ELIGIBLE
        private set

    /**
     * 通知这条通路问得到人吗。
     *
     * 只看应用总开关不够：用户可以只关掉「确认请求」这一条渠道而留着别的，那时通知栏上
     * 没有任何可点的东西，通路却会被当成可用 —— 于是这一问被挂到一条看不见也点不动的
     * 通知上等到超时，而界面内那张框本来是能问到的。渠道还没建过时算可用：建它是 [show]
     * 的第一步，不能因为还没问过人就说这条路走不通。
     */
    fun canShow(): Boolean = runCatching {
        // 标记必须自带过期/复位：show() 只在有新问题要挂时才会被叫，而挂不上的那一次之后
        // `canShow()` 已经回 false —— 通道线程于是连 acquire 都不做，再没有第二次机会去
        // 复位它；否则一次偶发失败就会把这条通路关到进程结束，且无法自愈。
        val nowMs = monotonicNow()
        val cooldownElapsed = nowMs - lastFailureAtMs > RETRY_AFTER_MS
        if (deliveryBroken && cooldownElapsed) deliveryBroken = false
        val state = postStateAfterEligibility(
            PostEligibility(
                broken = deliveryBroken,
                cooldownElapsed = cooldownElapsed,
                notificationsEnabled = manager.areNotificationsEnabled(),
                channelMuted = channelMuted(),
                systemPresent = systemNotificationManager() != null,
            ),
        )
        // 够格未挂 → ELIGIBLE：这一档说的是「可以投递」，不是「已经挂上」，更不是「看得见」。
        lastPostState = state
        state == PostState.ELIGIBLE
    }.getOrDefault(false)

    private fun systemNotificationManager(): NotificationManager? =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun channelMuted(): Boolean =
        systemNotificationManager()?.getNotificationChannel(CHANNEL_ID)?.importance ==
            NotificationManager.IMPORTANCE_NONE

    /**
     * 挂通知。调用方是通道线程，而撤窗回调排在主线程 —— 两侧都动 `shownId`，所以整段搬到
     * 主线程去跑：让通道线程先 notify 再改状态的话，中间插进来的那一次到点回收会擦掉刚挂上
     * 的这条，之后通知栏上什么都没有、`shownId` 却还说挂着，这一问只能等到超时。
     *
     * [waitingCount] 是排在后面还没轮上的问题数：只把这一问摊出来，用户会以为这就是全部，
     * 答完发现还有下一张时，节奏就从"一次问清楚"退化成"一次一次挤"。为 0 时不写这一行。
     */
    fun show(request: InterlockQueue.Request, waitingCount: Int = 0) {
        mainHandler.post { showOnMain(request, waitingCount) }
    }

    private fun showOnMain(request: InterlockQueue.Request, waitingCount: Int) {
        val remainingMs = request.prompt.deadlineAtMs - monotonicNow()
        if (remainingMs <= 0L) {
            // 已经没有可答的时间就别挂：一条点「允许」也不会被执行的死按钮，
            // 比通知栏里少一行更耗信任。只收自己那一条 —— 此刻栏上挂着的可能早已
            // 是别的新问题，无条件收会把它一起擦掉。
            cancelOnMain(request.id)
            return
        }
        // 整段兜住：除了权限被收回的 SecurityException，构造与投递也可能抛别的（渠道被
        // 系统删掉、大图超出 binder 缓冲）。这条通路报过可用却没真挂上时，用户面前没有
        // 任何可点的东西，而队列还以为问得到人 —— 收掉并留一行日志，比静默等到超时好查。
        val posted = try {
            ensureChannel()
            manager.notify(ID, build(request, waitingCount))
            true
        } catch (t: SecurityException) {
            false
        } catch (t: IllegalArgumentException) {
            false
        }
        if (!posted) {
            // 先记时刻再置标记：`canShow()` 在通道线程上读这两个值，它若正好插在两者之间，
            // 看到的就是"已闩住 + 上一次失败在零时刻"，于是当场把闩解掉 —— 第一次失败
            // 反而不生效，这一问又去撞同一条挂不上的通知。
            lastFailureAtMs = monotonicNow()
            deliveryBroken = true
            // 挂失败 → UNAVAILABLE：这条路此刻问不到人，页内那张框是下一步。
            lastPostState = postStateAfterPost(posted = false)
            runLog().warn(
                LogSubsystem.GATE,
                LogEvent.NOTIFY_UNAVAILABLE,
                "cap" to request.prompt.capability.wire,
                "cause" to "post-failed",
            )
            // 就地收，且只收自己那一条：`cancel()` 会另排一次主线程任务，而它排在
            // 已经入队的下一次 `showOnMain` 之后 —— 那一次挂的是另一件新问题，
            // 无条件收会把它刚露面的一次机会一起擦掉。
            cancelOnMain(request.id)
            // 队列是先问过「这条通路可用吗」、再异步挂的，所以这一问已经承诺给了通知栏。
            // 挂不上就得把它交回去：否则调用方要对着一条没人看得见的通知等满 8 秒，
            // 而审计里记下的是「在等用户答」。
            request.resolve(InterlockChoice.UNABLE_TO_SHOW)
            return
        }
        deliveryBroken = false
        // 挂上 ≠ 看见：横幅、静音、免打扰、锁屏都由系统与用户说了算，投递成功
        // 最多只能记「已投递、可见性未证实」，页内与回包不得把它当成可视化确认。
        lastPostState = postStateAfterPost(posted = true)
        shownId = request.id
        scheduleCancel(request.prompt.deadlineAtMs)
    }

    /**
     * 收通知。永远排到主线程队列里，不就地跑：`show` 是投递的，就地收的那一次会插到
     * 还在队里等跑的 `showOnMain` 前面 —— 于是「这一问已经答了」先被执行，之后那条通知
     * 又被挂回去，用户面前留一颗能点却什么都不办的死按钮，最长挂到截止时刻。
     */
    fun cancel() {
        mainHandler.post { cancelOnMain(InterlockQueue.ANY_REQUEST) }
    }

    /**
     * 只收属于 [requestId] 的那一条。
     *
     * 通知的编号是固定的，栏上永远只有一行：拿一条旧广播的失败去无条件收，会擦掉此刻
     * 正挂着的那一条新问题 —— 那是它唯一的露面机会，而队列还以为问得到人。
     */
    fun cancelFor(requestId: Int) {
        mainHandler.post { cancelOnMain(requestId) }
    }

    private fun cancelOnMain(requestId: Int) {
        if (requestId != InterlockQueue.ANY_REQUEST && shownId != null && shownId != requestId) return
        mainHandler.removeCallbacks(expireRunnable)
        shownId = null
        runCatching { manager.cancel(ID) }
    }

    /**
     * 到点自己收。
     *
     * 通知与界面里的确认框不一样，没有任何人替它画倒计时、也没有「到点自己关掉」这一步：
     * 挂在那儿就是永远可点。而确认窗口一过，助手早走了，那一下点的是一件已经不存在的操作。
     * 所以撤窗的时刻由这里负责，不指望下一次调用顺手把它换掉——那一次调用可能永远不来。
     */
    private fun scheduleCancel(deadlineAtMs: Long) {
        mainHandler.removeCallbacks(expireRunnable)
        mainHandler.postDelayed(expireRunnable, (deadlineAtMs - monotonicNow()).coerceAtLeast(0L))
    }

    /**
     * 到点撤窗。
     *
     * 截止时刻是单调时钟（`elapsedRealtime`），而 `postDelayed` 走 `uptimeMillis`，
     * 前者 = 后者 + 累计休眠时长，只会走得更快 —— 唤醒只可能迟到，不可能早到，
     * 所以醒来这一刻就是过点了，直接收，不再"复核后补一次剩余量"（那一支永远走不到）。
     * 就地收而不是排 `cancel()`：这一跑已经在主线程队列里，再排一次会落到下一次
     * `showOnMain` 之后，把刚挂上的那条新问题一起擦掉。
     * 挂在那儿答不了的死按钮比通知栏里少一行更耗信任。
     *
     * 收通知的同时还要**关掉这一问**：页内卡与悬浮卡到点都会撤掉全部呈现者，通知只撤自己
     * 的话，待决项还挂着，用户在页内看到的那张框就成了点下去不算数的死按钮。
     */
    private val expireRunnable: Runnable = Runnable {
        val id = shownId ?: return@Runnable
        cancelOnMain(InterlockQueue.ANY_REQUEST)
        onExpired(id)
    }

    private fun build(request: InterlockQueue.Request, waitingCount: Int): android.app.Notification {
        val prompt = request.prompt
        // 应用上下文不带用户在设置里手选的语言，直接读会让通知栏上那条与助手页里的
        // 确认框各说一种话。这条通知每问一次重建，所以取的是当下这一份语言。
        val strings = appContext.withAppLanguage()
        val title = strings.getString(R.string.pilot_approval_notify_title, strings.getString(prompt.titleRes))
        val detail = StringBuilder()
        prompt.targetLabel?.let {
            detail.append(strings.getString(R.string.pilot_approval_target)).append('\n')
                .append(it).append('\n')
        }
        prompt.paramDetail?.let {
            detail.append(strings.getString(R.string.pilot_approval_detail)).append('\n')
                .append(it).append('\n')
        }
        if (waitingCount > 0) {
            detail.append(strings.getString(R.string.pilot_overlay_waiting, waitingCount)).append('\n')
        }
        if (detail.isEmpty()) detail.append(strings.getString(prompt.summaryRes))
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            // 通知图标随核心资源走（ic_relay_notification 归核心模块），适配层不自带 drawable。
            .setSmallIcon(interlock.relay.core.R.drawable.ic_relay_notification)
            .setContentTitle(title)
            // 折叠态那一行就是对象名：用户下拉时先要认出这是在对哪个应用、哪个键做主，
            // 只写能力标题等于让他对任意对象背书。没有对象时退到能力说明。
            .setContentText(prompt.targetLabel ?: strings.getString(prompt.summaryRes))
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail.trimEnd()))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(PendingIntent.getActivity(
                appContext, request.id, hostIntent(),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .setAutoCancel(true)
            // 同一条问题的重复呈现只换内容不再响一次：助手每次重试都会走到这里，
            // 而用户可能正因为没在看手机才没答上第一遍。
            .setOnlyAlertOnce(true)
            // 锁屏上只留一句不含对象与参数的事实：写入内容里可能是地址、联系人名或一段文本。
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(interlock.relay.core.R.drawable.ic_relay_notification)
                .setContentTitle(strings.getString(R.string.pilot_approval_notify_public))
                .build())
            .addAction(0, strings.getString(R.string.pilot_overlay_allow), answer(request, PilotApprovalReceiver.ACTION_ALLOW))
            .apply {
                // 上限为「每次询问」的能力拿不到会话档，通知里也不能给这个口子。
                if (prompt.allowsSessionGrant) {
                    addAction(
                        0,
                        strings.getString(R.string.pilot_approval_allow_session),
                        answer(request, PilotApprovalReceiver.ACTION_ALLOW_SESSION),
                    )
                }
            }
            .addAction(0, strings.getString(R.string.pilot_approval_deny), answer(request, PilotApprovalReceiver.ACTION_DENY))
            .build()
    }

    private fun answer(request: InterlockQueue.Request, action: String): PendingIntent {
        val intent = Intent(appContext, PilotApprovalReceiver::class.java)
            .setAction(action)
            .putExtra(PilotApprovalReceiver.EXTRA_REQUEST_ID, request.id)
            .putExtra(PilotApprovalReceiver.EXTRA_DEADLINE, request.prompt.deadlineAtMs)
        // 请求编号当 PendingIntent 的请求码：不同问题的答复各自独立，
        // 而同一问题重贴时更新那一份就够了。
        return PendingIntent.getBroadcast(
            appContext, request.id, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ensureChannel() {
        val strings = appContext.withAppLanguage()
        val system = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        // 渠道名只在建的时候落一次：用户换了语言之后名字还停在旧那一档，而这条渠道
        // 在系统设置里长期存在。语言变了就再建一次 —— 同 id 再建只更新名字，不丢历史。
        val tag = strings.resources.configuration.locales.toLanguageTags()
        if (system.getNotificationChannel(CHANNEL_ID) != null && channelTag == tag) return
        channelTag = tag
        // 独立于那条常驻通道通知的高优先级渠道：通道那条是 IMPORTANCE_LOW 的静音条，
        // 而一次要人答的问题必须能抬头。
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                strings.getString(R.string.pilot_approval_notify_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "pilot_approval"

        /** 一次偶发失败之后重新给这条通路机会的间隔。 */
        private const val RETRY_AFTER_MS = 30_000L

        /** 与那条前台服务通知（0x5043）分开：两条说的是不同的事，互相覆盖会把通道条弄丢。 */
        const val ID = 0x5041
    }
}
