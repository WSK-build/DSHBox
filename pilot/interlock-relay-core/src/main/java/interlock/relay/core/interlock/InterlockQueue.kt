package interlock.relay.core.interlock

import interlock.relay.core.runtime.monotonicNow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * 悬浮确认窗这一条通路。抽成接口是为了让裁决逻辑能在纯 JVM 下被穷举验证：
 * [OverlayApprovalPresenter] 要 Context 与 WindowManager，而「四条通路怎么挑、
 * 挑错了怎么回落」这件事本身与窗口无关。
 *
 * 契约：
 * - [isPresented] 必须是**真实的在场信号**（窗真的挂在屏上或被留下），不能是只写不读的字段；
 * - [withdraw] 幂等，且必须在 [InterlockQueue] 改判离开这条通路之前完成，
 *   否则会出现「页内卡与悬浮卡同时在屏上」这种同一件事有两处可点的局面。
 */
interface OverlayApprovalChannel {

    /** 系统是否允许本应用画悬浮窗。没这条授权时本通路直接出局。 */
    fun canShow(): Boolean

    /** 挂一张卡并等答案。问不到人时回 [InterlockChoice.UNABLE_TO_SHOW]，让队列换一条路。 */
    suspend fun present(prompt: ApprovalPrompt): InterlockChoice

    /** 此刻屏上（或被留下）是否真有本通路的卡。裁决随时可以查。 */
    fun isPresented(): Boolean

    /** 收掉屏上那张与被留下的那张。可在任意线程调用，幂等。 */
    fun withdraw()

    /** 被留下的卡上那一下点击的接收方。 */
    var onParkedChoice: ((ApprovalPrompt, InterlockChoice) -> Unit)?

    /** 窗还挂着却已经看不见了（被系统藏、被别的可获焦窗盖过焦点）。 */
    var onHidden: (() -> Unit)?
}

/**
 * 此刻**唯一活动**的呈现者。
 *
 * 顺序是「离用户正在看的东西最近」到最远：助手页在前台时页内卡最直接（它就在屏幕上、
 * 不需要任何额外动作），其后是悬浮卡，再其后是通知栏。两条通路同时可点时，用户分不清
 * 自己点的是哪一件事，助手那边也会收到两次答复 —— 所以同一件审批在屏上永远只有一处可点。
 */
enum class InterlockChannel {
    /** 助手页内的确认框。本模块在前台且用户确实看得见它时才成立。 */
    PAGE,

    /** 悬浮卡。助手在别的应用里操作时唯一还能当场问到人的通路。 */
    OVERLAY,

    /** 通知栏上那一下下拉。 */
    NOTIFICATION,

    /** 三条都不成立：这一问**谁也问不到**。 */
    NONE,
    ;

    val wire: String get() = name.lowercase()
}

/**
 * 裁决为 [InterlockChannel.NONE] 时，挡住路的具体原因。
 *
 * 这两档各自点名一条用户真能做的动作。用户点完那一下就再问不出人，而只回一句
 * `E_GATE_NO_FOREGROUND` 的话，助手既不知道该去开通知，也不知道自己在后台模式里。
 */
enum class ApprovalDeadEnd {
    /** 通知被关（或投递链路坏）：通知栏那条路上一个可点的东西都没有。 */
    NOTIFICATIONS_BLOCKED,

    /** 后台/虚拟屏模式在跑：本应用悬浮窗必然被平台跨屏藏起来，连挂都不该挂。 */
    BACKGROUND_OVERLAY_SKIPPED,

    /** 屏上正挂着提问卡（自制悬浮卡）：这一问改走通知栏，不去叠第二张卡。
     *  通知也不可用时**当场拒**而不是排到提问卡后面等：等待队列排的是"同一批里下一个审批"，
     *  把提问卡（最长 120 秒）也算进等待会占着调用方的预算却给不出任何承诺——调用方按
     *  `E_GATE_NO_FOREGROUND` 稍后重问，比赌一个说不清的等待更诚实。 */
    QUESTION_CARD_ON_SCREEN,
}

/**
 * 确认框队列。通道服务端在无界面状态下也要能跑，因此授权请求先入队，
 * 由界面与通知栏取走并回填结果；队列空转时由裁决层的超时兜底为「拒绝」。
 *
 * 每一个被呈现出来的问题都是队列里的一个 [Request]：悬浮卡、通知栏、页内确认框
 * 都是同一张 [Request] 的不同呈现方式，答的是同一件事、只判一次。**哪一条路在用**
 * 由本队列裁决（见 [InterlockQueue.arbitrateLocked]），不交给装配根或界面各自决定 ——
 * 各自决定就会出现两处同时可点。
 *
 * 裁决只在**状态迁移**时重算：新问题建档、悬浮呈现失败回落、悬浮焦点丢失、
 * 前台可见性变化、虚拟屏就绪翻转、问题了结/过期、等待队列提升。同一个状态里
 * 反复重算（例如等待环每一轮重发同一问）不会再发一次呈现指令，通知因此不会每 500ms 重发一次。
 *
 * 已有一个问题正占着展示位时，接下来的不同问题按先后进入有界的等待队列
 * （见 [CAPACITY]），展示位空出后按 FIFO 提升；等待中的问题不向任何通路呈现。
 * 队列也满时新问题当场回「还没轮到」，不无限积压。
 *
 * [onRouting] 把裁决结果推给装配根：它据此撤通知或挂通知，**不再自行按
 * `foreground.visible` 决定挂不挂**。改判离开悬浮通路时，本队列先收卡再发裁决。
 */
class InterlockQueue(
    private val isForegroundCapable: () -> Boolean,
    private val onRouting: (Presentation) -> Unit = {},
    private val overlay: OverlayApprovalChannel? = null,
    /**
     * 悬浮窗这一条此刻必然看不见，连挂都不挂：可信虚拟屏在跑时那块屏上的应用带着
     * HIDE_NON_SYSTEM_OVERLAY_WINDOWS，平台会因此跨屏隐藏本应用的悬浮窗。
     * 只回状态、不动界面。
     */
    private val overlayBlind: () -> Boolean = { false },
    /** 通知栏那条通路问得到人吗：用户在系统里关了通知时，那条路上没有任何可点的东西。 */
    private val canNotify: () -> Boolean = { false },
    /**
     * 屏上是否有**别家**的卡（当前只有提问卡这一种）。有的话这条通路此刻不挂新卡：
     * 两张 TYPE_APPLICATION_OVERLAY 同屏时用户分不清答的是哪一问，而且后挂的那张会夺走焦点，
     * 把先挂的那张变成"在但看不见"——提问卡没有第二条通路，被盖住就只剩等到超时。
     * 此时审批改走通知栏（不占屏，两条互不遮挡）。
     */
    private val otherCardOnScreen: () -> Boolean = { false },
    /**
     * 一个待决项有了终局结论时，把**全部**呈现者都收掉。
     *
     * 这是「点哪都算数、只算一次」的另一半：定案之后，屏上不许还留着别处的入口，
     * 页内卡、悬浮卡、通知三样必须一起消失，否则用户还能在第二处点出第二个结论。
     */
    private val onRevokePresenters: (Request) -> Unit = {},
    /** 必须是单调时钟：过点的那一张框还能挡住后面的问题，用墙钟就能靠调时间绕过去。 */
    private val now: () -> Long = ::monotonicNow,
    /** 单次调用愿意为「等人点」让出的通道时间。与 [InterlockBroker] 的窗口同源，测试要能压短。 */
    private val inlineWaitMs: Long = INLINE_APPROVAL_WAIT_MS,
) : InterlockPresenter {

    @Volatile
    private var pending: Request? = null

    /** 等展示位的那几张：FIFO，数量上界见 [CAPACITY]。只在 [InterlockQueue] 的锁里读写。 */
    private val waiting = ArrayDeque<Request>()

    private val ids = AtomicInteger(sessionIdSeed())

    /** 只读快照：此刻的展示位与排在后面的问题数。供路由重算与页内展示读，不驱动任何状态。 */
    data class PendingSnapshot(val displayed: Request?, val waitingCount: Int)

    /**
     * 一次裁决的结果。[via] 是**唯一**该向用户呈现的地方，其余呈现者一律收掉。
     *
     * [deadEnds] 只在 [via] 为 [InterlockChannel.NONE] 时有意义，列出的是
     * 挡住路的具体原因，供回包文案点名用户该开哪个开关。
     */
    data class Presentation(
        val request: Request?,
        val via: InterlockChannel,
        val waitingCount: Int,
        val deadEnds: Set<ApprovalDeadEnd> = emptySet(),
    )

    /** 裁决去重键。同一组值重复出现就说明状态没迁移，不必再发一次呈现指令。 */
    private data class RoutingKey(
        val requestId: Int,
        val via: InterlockChannel,
        val waiting: Int,
    )

    private var lastRoutingKey: RoutingKey? = null

    /**
     * 展示位上那一个问题的悬浮通路已经试过一次且没接住（窗挂上了却没上屏、被系统盖掉焦点）。
     * 按 id 记而不是按布尔记：换了展示位上的问题就要重新给悬浮机会。
     */
    private var overlayExhaustedFor: Int? = null

    /**
     * 刚刚定案的那几问。用于挡住「已经答完/已过期/已取消」之后仍旧飘来的一下点击
     * （最典型的是被留下又被收掉的悬浮卡上那次慢抬手）：它不能再进迟到缓冲，
     * 否则下一次同参数重试会替用户认下一个他没点的结论。
     */
    private val settled = ArrayDeque<ApprovalPrompt>()

    /** 上一轮裁决为 NONE 时点名出来的原因。装配根在回包里引用它；换了状态就清掉。 */
    private var deadEndReason: String? = null

    /**
     * [acquire] 的三种结果。
     * 不是数据类的密封类：把「排队」与「满员」暴露成可构造的值会让调用方绕过容量闸。
     */
    private sealed interface Slot {
        /** 已在展示位上：[via] 是这一问该走的那条路，这一趟就地等它的答案。 */
        data class Displayed(val request: Request, val via: InterlockChannel) : Slot

        /** 排进等待队列：等展示位空出后提升，这一趟先等一段时间，等不到回「还在等」。 */
        data class Queued(val request: Request) : Slot

        /** 等待队列也满了：这一问连挂上的位置都没有。 */
        data object Full : Slot
    }

    /**
     * 用户答了、但那一刻没有调用在等的答案。
     *
     * 单次调用只为等人点让出几秒，到点就先回「还在等」。那之后用户点下的「允许 / 拒绝」
     * 如果只交给已经走开的那次调用，等于白点：下一次同参数的重试会看到一张新的框，
     * 「拒绝」更会被绕成第二次询问。悬浮卡与页内那张框的迟到点击都存到这里，由重试取走。
     */
    private val lateAnswers = ParkedAnswerMailbox(now)

    init {
        // 悬浮卡被「留下」之后那一下点击由呈现器捕获，答案交回这里存，判据只有一处。
        overlay?.onParkedChoice = { prompt, choice -> recordLateAnswer(prompt, choice) }
        // 窗还挂着却已经看不见：这一条路对本问不再成立，改判走别的路。幂等由呈现器保证。
        overlay?.onHidden = { onOverlayHidden() }
    }

    /**
     * 悬浮窗在屏却拿不到焦点（被系统跨屏藏掉，或被别的可获焦窗盖过）。
     *
     * 这是「看起来有路、其实没有」的一种：不动这一条，裁决会一直以为悬浮还能用，
     * 于是既不挂通知也不回退到页内，用户面对的就是一张点不到的窗。改判时先把卡收掉。
     */
    private fun onOverlayHidden() = synchronized(this) {
        val request = pending ?: return@synchronized
        overlayExhaustedFor = request.id
        if (overlay?.isPresented() == true) overlay?.withdraw()
        // 不 force：改判本身就会换掉去重键（OVERLAY → 别的路），因此这一次一定会发出去。
        // 反过来，界面每次失焦都报一次的那条信号若强发，会让日志里堆满"并没有迁移"的记录。
        emitRoutingLocked(force = false)
    }

    /**
     * 外部可见性变化后请队列重算一次裁决：前台来了/走了、可信虚拟屏就绪与否翻转了。
     *
     * 幂等：状态没变就不发任何呈现指令。触发它的地方（界面进退、界面节拍）在没有迁移的
     * 情况下每秒都可能叫一次，硬发会让通知每轮重投、日志每轮记一条假的迁移。
     */
    fun reroute() = synchronized(this) { emitRoutingLocked(force = false) }

    /**
     * 通知栏那条卡到点自收时的回调。通知到点与页内卡/悬浮卡到点是同一件事：
     * **实体关闭 + 撤销全部呈现者**，不能只把通知收掉而把待决项留着。
     */
    fun expireIfCurrent(requestId: Int): Boolean = synchronized(this) {
        val request = pending ?: return@synchronized false
        if (request.id != requestId) return@synchronized false
        rememberSettledLocked(request.prompt)
        onRevokePresenters(request)
        if (request.awaiting) request.resolve(InterlockChoice.EXPIRED)
        closeSlotLocked(request)
        true
    }

    /** 上一轮「谁也问不到」的点名文案。没有这回事时回 null。装配根在回包里引用它。 */
    fun lastDeadEndReason(): String? = synchronized(this) { deadEndReason }

    /** 记下「没有调用在等」的那一次点击，等下一次同参数的重试来取。 */
    internal fun recordLateAnswer(prompt: ApprovalPrompt, choice: InterlockChoice) {
        synchronized(this) {
            if (!choice.isAnswer) return@synchronized
            val live = pending
            if (live != null && live.matches(prompt)) {
                // 展示位上正挂着同一件事，而这一问已经有人在等（等待环重发了它）：
                // 这一答直接交回那一次调用，**不能**再存一份给下一次 —— 那会让一次点击
                // 批两次执行。这条路若只走缓冲，答案就会蒸发。
                if (live.awaiting) {
                    live.resolve(choice.downgradedFor(live.prompt))
                    rememberSettledLocked(live.prompt)
                    onRevokePresenters(live)
                    closeSlotLocked(live)
                } else {
                    recordLate(live.prompt, choice)
                }
                return@synchronized
            }
            recordLate(prompt, choice)
        }
    }

    /** 一次待决请求。不是数据类：生成的 copy() 会把内部句柄暴露成公开构造器。 */
    class Request internal constructor(
        /** 这张框的编号。通知栏那三个按钮带着它回来，回填时据此认人。 */
        val id: Int,
        val prompt: ApprovalPrompt,
        private val deferred: CompletableDeferred<InterlockChoice>,
    ) {
        suspend fun await(): InterlockChoice = deferred.await()

        /** 这一刻有没有调用在等这个答案。没有等的答案要存起来留给重试，不能让点击白点。 */
        @Volatile
        var awaiting = false

        fun resolve(choice: InterlockChoice) {
            deferred.complete(choice)
        }

        /** 已经答了就当场把答案取回（用于让开通道那一瞬的补收），没答回 null。 */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        fun peekAnswered(): InterlockChoice? = deferred.takeIf { it.isCompleted }?.getCompleted()

        /** 重试要不要接上这一张：同一能力、同一个对象、同一份参数明细才算同一件事。 */
        fun matches(other: ApprovalPrompt): Boolean = prompt.sameQuestionAs(other)
    }

    /**
     * 建档或复用：让这一问在队列里有个位置。
     *
     * 先清场（过点的展示位、等待队列里到点未提升的），再按三种情况落位：
     * 同一问题正占着展示位就复用那一张；同一问题已在等待队列里就复用那一张；
     * 都不是才新办一张——展示位空着直接上展示位，否则进等待队列，满了回 [Slot.Full]。
     * 等待队列里的过点项在这里一并清掉：到点未提升的那一问以 EXPIRED 收场，
     * 不让一张死了的框占着位置、也不让它提升后挡住后面的问题。
     *
     * `awaiting` 必须在这个同步块里置位：回填答案的那一侧靠它判断「有人等」还是「没人等」，
     * 若留到出锁之后再设，点击正好落在两者之间，就会既唤醒一次调用、又往缓冲里存一份 ——
     * 一次点击批了两次执行。
     *
     * 复用同一张展示位**不重发**呈现指令：等待环每 500ms 重发同一问时，重发会让通知
     * 每轮都换一次，用户刚要划掉就又弹回来。「让那条通知重新出现」由 [reroute] 这类
     * 真正的状态迁移事件负责。
     */
    private fun acquire(prompt: ApprovalPrompt): Slot = synchronized(this) {
        val displayedBefore = pending
        pruneExpiredLocked()
        // 展示位变了（清了过点的、或提升了队头）就重算一次裁决：换了问题就是换了呈现者。
        fun emitIfDisplayMoved() {
            if (pending !== displayedBefore) emitRoutingLocked(force = true)
        }
        val current = pending
        if (current != null && current.matches(prompt)) {
            current.awaiting = true
            return@synchronized Slot.Displayed(current, currentRouteLocked())
        }
        // 同一问题已经在排队：复用那一张，本趟跟着它一起等提升与答复。
        waiting.firstOrNull { it.matches(prompt) }?.let { queued ->
            queued.awaiting = true
            emitIfDisplayMoved()
            return@synchronized Slot.Queued(queued)
        }
        val created = Request(ids.incrementAndGet(), prompt, CompletableDeferred()).also {
            it.awaiting = true
        }
        val slot = when {
            pending == null -> {
                pending = created
                // 换了展示位上的问题，悬浮机会跟着重给一次。
                overlayExhaustedFor = null
                // 裁决在这里发一次（走 [emitIfDisplayMoved]），不再单独发：同一件事
                // 发两遍会让日志里出现两条迁移记录，也让调用方多收一次呈现指令。
                Slot.Displayed(created, currentRouteLocked())
            }

            waiting.size < capacityLeft() -> {
                waiting.addLast(created)
                Slot.Queued(created)
            }

            else -> {
                emitIfDisplayMoved()
                return@synchronized Slot.Full
            }
        }
        emitIfDisplayMoved()
        slot
    }

    /**
     * 唯一活动呈现者的裁决。纯判定，不动界面。
     *
     * 顺序即优先级：助手页在前台时页内卡最直接；否则悬浮卡（助手在别的应用里也能问）；
     * 再否则通知栏。三条都不成立时是 [InterlockChannel.NONE] —— 这不是「再等等」，
     * 是「这一问谁也问不到」，必须立刻回，不能占着通道等用户自己找回来。
     *
     * 悬浮那一支分两段：屏上已经有卡时恒成立（此时页面可见性变化也不该把它从用户眼前抢走
     * 再换一张同样的），否则要求「没被盲掉 + 有授权 + 本问还没试砸过」。
     */
    private fun currentRouteLocked(): InterlockChannel {
        val request = pending ?: return InterlockChannel.NONE
        if (isForegroundCapable()) return InterlockChannel.PAGE
        // 被盲掉时连"屏上已经有卡"也不算数：那块屏上的应用带着跨屏隐藏标记，平台会把这张
        // 收起来，用户在别处看得见它也点不着它。留着它当通路，等于对着一个点不到的东西
        // 宣布"这一问有人接"—— 改判要走到通知栏去，撤卡由 [emitRoutingLocked] 顺手做掉。
        val blind = overlayBlind()
        val up = !blind && overlay != null && overlay.isPresented()
        val usable = !blind && overlay != null && overlay.canShow() &&
            overlayExhaustedFor != request.id && !otherCardOnScreen()
        return when {
            up || usable -> InterlockChannel.OVERLAY
            canNotify() -> InterlockChannel.NOTIFICATION
            else -> InterlockChannel.NONE
        }
    }

    /** 裁决为 NONE 时点名挡住路的那几条。 */
    private fun deadEndsLocked(): Set<ApprovalDeadEnd> = buildSet {
        if (!canNotify()) add(ApprovalDeadEnd.NOTIFICATIONS_BLOCKED)
        if (overlayBlind()) add(ApprovalDeadEnd.BACKGROUND_OVERLAY_SKIPPED)
        // 提问卡占着屏、通知又不可用时，挡住路的就是这张卡：不说清，读日志的人只会看到
        // 「no_surface」而不知道"答完那一问就好了"。
        if (otherCardOnScreen() && currentRouteLocked() == InterlockChannel.NONE) {
            add(ApprovalDeadEnd.QUESTION_CARD_ON_SCREEN)
        }
    }

    /**
     * 发一次裁决。[force] 只给真正的状态迁移事件用（[reroute]、焦点丢失、悬浮失败回落），
     * 等待环的重复重发靠去重键自然被滤掉。
     *
     * 离开悬浮通路时**先收卡再发指令**：反过来会出现「裁决已经说该走页内了，屏上还压着
     * 一张悬浮卡」，那一瞬屏上有两处可点，正是这一层要消灭的局面。
     */
    private fun emitRoutingLocked(force: Boolean) {
        val request = pending
        val via = currentRouteLocked()
        val key = RoutingKey(request?.id ?: NONE_REQUEST_ID, via, waiting.size)
        if (!force && key == lastRoutingKey) return
        val wasOverlay = lastRoutingKey?.via == InterlockChannel.OVERLAY
        lastRoutingKey = key
        if (request != null) {
            deadEndReason = if (via == InterlockChannel.NONE) {
                reasonFor(deadEndsLocked())
            } else {
                null
            }
        } else {
            deadEndReason = null
        }
        if (wasOverlay && via != InterlockChannel.OVERLAY) overlay?.withdraw()
        onRouting(Presentation(request, via, waiting.size, if (via == InterlockChannel.NONE) deadEndsLocked() else emptySet()))
    }

    /** 清掉过点的部分：展示位上那一张直接收，等待队列里到点未提升的以 EXPIRED 收场。 */
    private fun pruneExpiredLocked() {
        pending?.takeIf { it.prompt.deadlineAtMs - now() <= 0L }?.let {
            // 展示位上那张自己到点了：与悬浮卡/通知到点同一条规则 —— 实体关闭 + 撤销全部呈现者。
            rememberSettledLocked(it.prompt)
            onRevokePresenters(it)
            if (it.awaiting) it.resolve(InterlockChoice.EXPIRED)
            pending = null
        }
        if (waiting.isNotEmpty()) {
            val expired = mutableListOf<Request>()
            waiting.removeAll { request ->
                val dead = request.prompt.deadlineAtMs - now() <= 0L
                if (dead) expired.add(request)
                dead
            }
            expired.forEach { if (it.awaiting) it.resolve(InterlockChoice.EXPIRED) }
        }
        fillDisplaySlotLocked()
    }

    /** 展示位空着就按 FIFO 补上一张还可答的等待请求。不发回调：由调用方按最终状态发一次。 */
    private fun fillDisplaySlotLocked() {
        if (pending == null) pending = takeNextWaitingLocked()
    }

    /** 从等待队列按先后取下一张还可答的；到点未提升的那几张就地移除，仍有人在等的以 EXPIRED 收场。 */
    private fun takeNextWaitingLocked(): Request? {
        while (waiting.isNotEmpty()) {
            val next = waiting.removeFirst()
            if (next.prompt.deadlineAtMs - now() > 0L) return next
            if (next.awaiting) next.resolve(InterlockChoice.EXPIRED)
        }
        return null
    }

    /** 等待队列还收得下几张。 */
    private fun capacityLeft(): Int = CAPACITY - 1

    /**
     * 记下「这一问已经有结论了」。
     *
     * 这一步是迟到点击的最后一道闸：定案之后还飘来的一下点击（被留下又被收掉的悬浮卡上
     * 那次慢抬手最常见）既不能唤醒谁、也不能进缓冲 —— 进了缓冲就会替下一次同参数的重试
     * 认下一个用户没点过的结论，「拒绝」尤其危险。
     */
    private fun rememberSettledLocked(prompt: ApprovalPrompt) {
        settled.addLast(prompt)
        while (settled.size > SETTLED_MEMORY) settled.removeFirst()
    }

    private fun wasSettledLocked(prompt: ApprovalPrompt): Boolean = settled.any { it.sameQuestionAs(prompt) }

    private fun close(request: Request) = synchronized(this) {
        if (pending !== request) return@synchronized
        rememberSettledLocked(request.prompt)
        onRevokePresenters(request)
        closeSlotLocked(request)
    }

    /** 摘掉展示位上那一张并按 FIFO 提升。发一次裁决说清换成了谁。 */
    private fun closeSlotLocked(request: Request) {
        if (pending !== request) return
        pending = takeNextWaitingLocked()
        emitRoutingLocked(force = true)
    }

    override suspend fun present(prompt: ApprovalPrompt): InterlockChoice {
        // 先认「这一问已经有过判决」：上一次调用让开通道之后用户才抬手，结果存在这里 ——
        // 答了就照那句走，被丢掉的那一类则落成终态，不再重新问第二遍。
        when (val late = lateAnswers.take(prompt)) {
            null -> {}
            LateVerdict.Discarded -> return InterlockChoice.EXPIRED
            is LateVerdict.Answered -> return late.choice
        }
        // 三条通路一条都不成立时如实回「问不了」，并且不往队列里挂任何东西：
        // 挂上去也没有任何一条路能把问题带到用户眼前。这次没有建档，因此不走撤销广播。
        val deadEnds = noRouteAtAll()
        if (deadEnds != null) {
            synchronized(this) { deadEndReason = reasonFor(deadEnds) }
            return InterlockChoice.UNABLE_TO_SHOW
        }
        // 每一个被呈现的问题都在队列里建档；悬浮窗只是展示位上的那一张的第一条呈现通路。
        when (val slot = acquire(prompt)) {
            Slot.Full -> return InterlockChoice.WAITING_TURN
            is Slot.Queued -> {
                // 等待中的问题不向任何通路呈现（悬浮窗、通知栏、页内都只认展示位那一张）。
                // 等一小段：展示位若在这段时间里空出，这一问会被提升并被答；
                // 等不到就回「还在等」，它留在等待队列里，重试接得上。
                return waitForAnswer(slot.request)
            }

            is Slot.Displayed -> {
                val request = slot.request
                // 悬浮通路：它不要求本模块在前台，助手在别的应用里操作时同样问得到用户。
                // 但「优先」不等于「只此一条」：窗挂上了却没上屏时它自己回「问不了」，
                // 那时把本问标记成试过一次，重算裁决改走通知栏或页内 —— 这一问
                // 仍然是同一张 Request，不重开授权。
                if (slot.via == InterlockChannel.OVERLAY) {
                    when (val choice = overlay?.present(prompt)) {
                        null, InterlockChoice.UNABLE_TO_SHOW -> {
                            synchronized(this) {
                                overlayExhaustedFor = request.id
                                emitRoutingLocked(force = true)
                            }
                        }

                        InterlockChoice.AWAITING -> {
                            // 卡被「留下」：调用方让开通道，框还挂在屏上继续等。
                            // 「有人在等」的标记必须还回去：留下的卡上的点击走迟到缓冲，
                            // 留着这个标记的话，回填侧会把那一下点击交给早已走开的这一趟，
                            // 答案就蒸发了。
                            synchronized(this) { request.awaiting = false }
                            return InterlockChoice.AWAITING
                        }

                        else -> {
                            // 悬浮窗当场问到了答案（或这张框到点自收）：同一件事在这里收档，
                            // 展示位空出后按 FIFO 提升下一张等待中的问题。
                            close(request)
                            return choice
                        }
                    }
                }
                // 悬浮没接住、而通知栏与页内也问不到人：这条 Request 没有任何呈现者了。
                // 留着它会挡住后面的不同问题，如实收场——先还回「有人在等」的标记，
                // 否则一次迟到的点击会被回填侧交给早已走开的这一趟，答案就蒸发了。
                if (!isForegroundCapable() && !canNotify()) {
                    synchronized(this) {
                        request.awaiting = false
                        rememberSettledLocked(request.prompt)
                        deadEndReason = reasonFor(deadEndsLocked())
                    }
                    close(request)
                    return InterlockChoice.UNABLE_TO_SHOW
                }
                return waitForAnswer(request)
            }
        }
    }

    /**
     * 建档之前的粗判：三条通路一条都不成立时回挡住路的那几条，都成立时回 null。
     *
     * 与 [currentRouteLocked] 刻意分开：这一处在**还没建档**时调用，不该读展示位。
     */
    private fun noRouteAtAll(): Set<ApprovalDeadEnd>? {
        val notify = canNotify()
        // 屏上挂着提问卡时，悬浮那一条此刻不成立——判据与 [currentRouteLocked] 同源，
        // 两处若不一致，here 放行建档、那里又没有通路，问题就会卡在展示位上没人能答。
        val overlayOk = !overlayBlind() && overlay?.canShow() == true && !otherCardOnScreen()
        if (isForegroundCapable() || overlayOk || notify) return null
        return buildSet {
            if (!notify) add(ApprovalDeadEnd.NOTIFICATIONS_BLOCKED)
            if (overlayBlind()) add(ApprovalDeadEnd.BACKGROUND_OVERLAY_SKIPPED)
            if (otherCardOnScreen()) add(ApprovalDeadEnd.QUESTION_CARD_ON_SCREEN)
        }
    }

    /**
     * 只等一小段：等用户点不是等一个后端，占着通道等满确认窗口会让无关调用一起排队。
     * 没等到就回「还在等」，请求留在队列里，重试接得上。
     *
     * 等待上限按**剩余截止**收窄：确认窗口总长由 [ApprovalPrompt.deadlineAtMs] 定，
     * 悬浮那几段已经把一部分时间花掉了，这一段只能花剩下的，否则单次 `present` 的总时长
     * 会超过既有承诺（调用方那边的确认窗口也就形同虚设）。
     */
    private suspend fun waitForAnswer(request: Request): InterlockChoice {
        val budget = minOf(inlineWaitMs, request.prompt.deadlineAtMs - now()).coerceAtLeast(0L)
        val choice = try {
            withTimeoutOrNull(budget) { request.await() }
        } finally {
            // 超时、被裁决层取消、被信箱取消都走到这里：等的一方不在了就必须说出去，
            // 否则回填那一侧会一直以为「有人在等」，用户后来点的答案既不唤醒谁也不落缓冲。
            synchronized(this) { request.awaiting = false }
        }
        if (choice == null) {
            // 让开通道的这一瞬用户可能刚好点下：已经答了就当场取走，别把它留给下一次重试。
            request.peekAnswered()?.let { close(request); return it }
            return InterlockChoice.AWAITING
        }
        close(request)
        return choice
    }

    /**
     * 回填结果。[requestId] 不是 [ANY_REQUEST] 时只认那一张：通知栏上那一下可能迟到，
     * 而那一刻展示位上挂着的也许是另一件事。
     *
     * 只认展示位上的那一张：等待中的问题不向任何通路呈现，通知与页内也只可能问起
     * 展示位上这一件。等待中的那几张要等提升之后才有编号可认。
     *
     * 上限为「每次询问」的能力没有「本会话内允许」这个选项，呈现侧漏判时这里必须降级为
     * 「仅本次」，否则一次误点就把每次询问升成永久免弹。返回是否真的答了某一张还在等的框。
     */
    fun resolveCurrent(choice: InterlockChoice, requestId: Int = ANY_REQUEST): Boolean = synchronized(this) {
        val request = pending ?: return@synchronized false
        if (requestId != ANY_REQUEST && request.id != requestId) return@synchronized false
        val effective = choice.downgradedFor(request.prompt)
        // 先记终局，再落答案。[recordLate] 里那道「这一问已经有结论了」的闸要挡的就是
        // 紧接着的这一写：留在这个次序上，调用方已经让开通道时这一答会进缓冲，而下一次
        // 同参数重试会直接读到它 —— 一次点击批两次执行。
        rememberSettledLocked(request.prompt)
        // 「有没有人在等」与「答案归谁」必须在这一个同步块里定下来：分两步走时点击正好
        // 落在两步之间，就会既唤醒这次调用、又往缓冲里存一份 —— 一次点击批两次执行。
        if (request.awaiting) request.resolve(effective) else recordLate(request.prompt, effective)
        // 答了就收档：等的那一侧可能早已回「还在等」走开，这里不等它来清。
        // 任何入口的答复都是终局 —— 撤销全部呈现者，用户在第二处不该还能点出第二个结论。
        onRevokePresenters(request)
        closeSlotLocked(request)
        true
    }

    /** 把一次「没人在等」的点击存起来；不该缓冲的目标在这里挡掉，挡掉的那一类记下终态。 */
    private fun recordLate(prompt: ApprovalPrompt, choice: InterlockChoice) {
        // 只存真答过的：界面那张卡到点会自己回一个 EXPIRED，那一刻没有调用在等的话，
        // 存下来就是让下一次同参数重试替用户"认下"一次他从未见过的超时。
        if (!choice.isAnswer) return
        // 这一问已经有结论了：迟到的点击不认，也不存 —— 存了就是替下一次重试改写历史。
        if (wasSettledLocked(prompt)) return
        if (prompt.volatileTarget) {
            // 既不认迟到答案、又没有会话档可退的那几条：把"这一答被丢掉了"也记下来。
            // 不记就等于让重试重新问一张，而抬手慢的人会一直落在"答了但不算数"里。
            if (prompt.terminatesWhenAnswerDropped) {
                lateAnswers.putDiscarded(prompt, prompt.deadlineAtMs)
            }
            return
        }
        lateAnswers.put(prompt, choice.downgradedFor(prompt), prompt.deadlineAtMs)
    }

    /**
     * 此刻的呈现快照：展示位上那一张，以及排在等待队列里的问题数。
     *
     * 过点的清理由这里一并做掉（并照常撤销全部呈现者、发一次裁决）：只读入口不代劳的话，
     * 读到的是一个自己都答不出声的等待项，界面会拿它去算「还有几项待答」。
     */
    fun pendingSnapshot(): PendingSnapshot = synchronized(this) {
        pruneExpiredLocked()
        emitRoutingLocked(force = false)
        PendingSnapshot(displayed = pending, waitingCount = waiting.size)
    }

    /** 界面销毁或用户离开时清空，避免残留的等待方永远挂起。 */
    fun cancelAll() {
        val current: Request?
        val queued: List<Request>
        synchronized(this) {
            // 清缓冲必须和摘待决项在同一把锁里：迟到的那一下点击走的是同一把锁的
            // `recordLateAnswer`，两步分开时它会插到"清空"与"摘待决项"之间 —— 于是这一答
            // 在清空之后落进来，而装配根不会重建，下一次会话第一条同参数调用就把这件
            // 用户早已不再能反悔的事执行掉。
            lateAnswers.clear()
            current = pending
            pending = null
            queued = waiting.toList()
            waiting.clear()
            if (current != null || queued.isNotEmpty()) {
                (queued + listOfNotNull(current)).forEach { rememberSettledLocked(it.prompt) }
                queued.forEach { onRevokePresenters(it) }
                current?.let { onRevokePresenters(it) }
                emitRoutingLocked(force = true)
            }
        }
        current?.resolve(InterlockChoice.DENY)
        queued.forEach { if (it.awaiting) it.resolve(InterlockChoice.DENY) }
    }

    companion object {
        /**
         * 同时可答的确认问题总数上界：1 张在展示位上，其余在等待队列里按先后排。
         *
         * 上界是保护而不是功能：审批是给一个人看的，排到第四个问题说明前三个都迟迟没答，
         * 再积压只会把「等哪个问题」搅成一团。超出的那一问当场回「还没轮到」
         * （[InterlockChoice.WAITING_TURN]），调用方退避片刻再来，位置到时自然腾出。
         */
        private const val CAPACITY = 3

        /** 去重键里代表「没有待决项」的编号。真实编号恒为正（见 [sessionIdSeed]）。 */
        private const val NONE_REQUEST_ID = 0

        /**
         * 记着最近多少问的「已有结论」。挡住的是定案之后飘来的那一下点击，
         * 而那种点击总是紧跟着那一次定案 arrive —— 留 4 条足够，越界只会让上一轮会话的
         * 旧卡点击落进来。
         */
        private const val SETTLED_MEMORY = 4

        /**
         * 每次进程启动取一个正的随机基数。
         *
         * 编号不从 1 起：通知活在系统那边，比本进程活得久。上一次会话留下的那条带着它自己的
         * `request_id`，进程被杀后重开，新会话的第一条如果又是 1，用户在旧通知上点的那一下
         * 就可能答到新会话挂上的另一件事上 —— 旧那条的截止时刻未必已过，那道闸挡不住。
         * 新问挂上时旧通知会被替换或收掉，可用的窗口只有几毫秒，但这里要的是"答错一张框"
         * 这件事根本没有路径，而不是概率很小。0 留给广播侧当"编号丢了"的哨兵，故取正数。
         */
        private fun sessionIdSeed(): Int =
            java.util.concurrent.ThreadLocalRandom.current().nextInt(1, Int.MAX_VALUE / 4)

        /**
         * 一次调用愿意为「等人点」让出的通道时间。
         *
         * 确认框的总时长由 `deadlineAtMs` 管（用户仍有整段窗口可答），这里只是单次调用的
         * 等待上限：信箱同一时刻只处理一条请求，等 60 秒等于让后面所有无关调用一起排队。
         */
        private const val INLINE_APPROVAL_WAIT_MS = 8_000L

        /**
         * 「答的是此刻挂着的那一张」。
         *
         * 通知栏那一下与界面里那张框都带着自己的编号回来，只有说不清编号的调用才用它。
         */
        const val ANY_REQUEST = -1

        /**
         * 「谁也问不到」时点名该做哪一步。错误码不变（`E_GATE_NO_FOREGROUND` 说的是事实），
         * 这里补的是动作。
         */
        internal fun reasonFor(deadEnds: Set<ApprovalDeadEnd>): String = buildString {
            append("no_surface")
            if (ApprovalDeadEnd.NOTIFICATIONS_BLOCKED in deadEnds) {
                append("; turn on notifications for this app in system settings")
            }
            if (ApprovalDeadEnd.BACKGROUND_OVERLAY_SKIPPED in deadEnds) {
                append("; end background mode or open the assistant page to answer")
            }
            if (ApprovalDeadEnd.QUESTION_CARD_ON_SCREEN in deadEnds) {
                append("; a question card is on screen - answer it first and this can be asked again")
            }
            if (deadEnds.isEmpty()) append("; no presenter is available right now")
        }
    }
}
