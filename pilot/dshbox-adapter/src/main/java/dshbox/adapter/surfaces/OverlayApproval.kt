package dshbox.adapter.surfaces

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import interlock.relay.core.protocol.SettingsTarget
import interlock.relay.core.log.LogEvent
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.log.RunLog
import interlock.relay.core.runtime.monotonicNow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import interlock.relay.core.interlock.ApprovalPrompt
import interlock.relay.core.interlock.InterlockChoice
import interlock.relay.core.interlock.InterlockPresenter
import interlock.relay.core.interlock.InterlockQueue
import interlock.relay.core.interlock.OverlayApprovalChannel
import interlock.relay.core.interlock.SettingsIntents

/**
 * 悬浮确认窗呈现器：用 [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] 把 [ApprovalCardView]
 * 压在**当前一切界面之上**，因此助手在别的应用里操作时也能当场问到用户。
 *
 * 这条通路的必要性：确认框若只能由本模块界面呈现，助手在别的应用里操作时本模块必然
 * 不在前台，所有「询问审批」档的能力会一律回 E_GATE_NO_FOREGROUND。
 *
 * 呈现条件只有一个：用户授予过「显示在其他应用上层」。没这条授权时本类不做任何退化
 * 尝试，直接回 [InterlockChoice.UNABLE_TO_SHOW]，由 [InterlockQueue] 走下一条通路——
 * 静默画一个看不见的窗比弹不出来更糟。
 *
 * 但「有授权」不等于「看得见」：平台会在**任一屏**上出现带 HIDE_NON_SYSTEM_OVERLAY_WINDOWS
 * 的窗口时跨屏隐藏第三方悬浮窗。应用侧从窗口可见性回调里看不出差别（隐藏时仍收到 VISIBLE、
 * surface 也画好），唯一的在场证据是拿不到焦点，于是 [showAndWait] 用焦点自检并在失败时回
 * [InterlockChoice.UNABLE_TO_SHOW]，由 [InterlockQueue] 换一条问得到人的路，而不是让调用方
 * 对着看不见的窗等到超时。
 *
 * 这一层只管"窗"：挂窗、判在场、排队、收放。卡片长什么样在 [ApprovalCardView]，
 * 因为助手页内那条支路要挂的是同一张卡 —— 样式只允许有一处。
 */
class OverlayApprovalPresenter(
    context: Context,
    /**
     * 运行日志的**延迟**取用口。装配根（core 的 RelayContainer）在构造中途就会向呈现面
     * 要这一条通路，而日志口在装配根里此刻还没建好；呈现器只在失败记录那两处用到它，
     * 到那时装配早已完成，所以这里收 lambda、用时取值，避免装配期互相等。
     */
    private val runLog: () -> RunLog,
) : InterlockPresenter, OverlayApprovalChannel {

    private val appContext = context.applicationContext

    /** 同一时刻只画一个窗：并发调用排队，后来的显示为「还有 N 项待确认」。 */
    private val windowLock = Mutex()
    private val waiting = AtomicInteger(0)

    /**
     * 此刻屏上（或被留下）是否真有本通路的卡。
     *
     * 这是**真信号**：[showAndWait] 自检过焦点才把它置真，收窗一律置假。
     * 队列的裁决随时查它来决定这一问该走哪条路 —— 早先这里是个只写不读的 `visible` 字段，
     * 记下了却没人问，于是「屏上有没有卡」这件事在裁决时无从得知。
     */
    @Volatile
    private var onScreen = false

    /** 当前那张窗的收放者；被「留下」的那张（调用方已回「还在等」）也走它收。 */
    @Volatile
    private var parked: WindowOwner? = null

    /** 此刻屏上那张卡的计数器。窗撤掉后要置空，迟到的排队数不该画到旧卡上。 */
    @Volatile
    private var activeCard: ApprovalCardView? = null

    /**
     * 正在屏上等答案的那张窗。撤通道时必须走它的 [WindowOwner.retire]：直接摘视图会让这张窗
     * 在负责它的那次调用眼里仍然"开着"，于是被挂成「留下等用户答」的那一张 —— 此后每一条
     * 询问都在 [showAndWait] 开头立刻拿到「还在等」，而屏上早就没有窗了，通知栏与助手页
     * 两条通路也一并被这个占位挡住。
     */
    @Volatile
    private var current: WindowOwner? = null

    /**
     * 卡片被「留下」之后用户点下的答案要交给谁。
     *
     * 留下时那次调用已经回「还在等」走了，答案在这条通路上没有接收方；交给队列存着，
     * 由下一次同参数的重试取走。「什么算同一件事、什么时候作废」的判据只在队列一处。
     */
    @Volatile
    override var onParkedChoice: ((ApprovalPrompt, InterlockChoice) -> Unit)? = null

    /**
     * 窗还挂着却已经看不见了（被系统跨屏藏掉、或焦点被别的可获焦窗盖过）。
     *
     * 与「挂上没挂上」是两件事：加窗不报错、绘制回调也说 VISIBLE，用户面前却什么都没有。
     * 队列据此把本问改判到通知栏或页内，必要时收掉这张点不到的卡。幂等由本类保证。
     */
    @Volatile
    override var onHidden: (() -> Unit)? = null

    /**
     * 这条通路是不是已经收了。
     *
     * 撤通道时收窗是 post 到主线程的，而用户那次点击也可能排在同一个队列里：不设这道闸，
     * 点击会在队列清空之后把答案又写回去，那份答案能活过这次重启，被下一个会话的
     * 第一条同参数调用直接花掉。
     */
    @Volatile
    private var closed = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 系统是否已允许本应用画悬浮窗。 */
    override fun canShow(): Boolean = Settings.canDrawOverlays(appContext)

    /** 去系统里授予悬浮窗授权。 */
    fun requestPermissionIntent(): Intent =
        SettingsIntents.build(SettingsTarget.OVERLAY, appContext)
            ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", appContext.packageName, null))

    /** 通道停止或界面销毁时收起残留窗口，避免留下一个没人应答的浮窗。可在任意线程调用。 */
    fun dismissAll() = withdraw()

    /**
     * 收掉屏上那张与被留下的那张。可在任意线程调用，幂等。
     *
     * 队列改判离开这条通路时先走这里：裁决已经说该走页内或通知了，屏上还压着一张悬浮卡，
     * 用户面前就有两处可点。同一个方法也服务撤通道 —— 两边的收放要求一致。
     */
    override fun withdraw() {
        closed = true
        onScreen = false
        val owner = parked ?: current ?: return
        mainHandler.post { owner.retire() }
    }

    /** 此刻屏上（或被留下）是否真有本通路的卡。裁决随时可查，不碰窗口。 */
    override fun isPresented(): Boolean = onScreen

    override suspend fun present(prompt: ApprovalPrompt): InterlockChoice {
        if (!canShow()) return InterlockChoice.UNABLE_TO_SHOW
        waiting.incrementAndGet()
        paintCounter()
        var queued = true
        return try {
            windowLock.withLock {
                waiting.decrementAndGet()
                queued = false
                paintCounter()
                // 窗口与 View 只能在主线程操作：调用方是通道的分发线程，
                // 直接 addView 会在建 Handler 时抛异常，表现为"弹不出来"。
                withContext(Dispatchers.Main) { showAndWait(prompt) }
            }
        } finally {
            // 排在锁上时被取消（上一条问题占着窗，而这一次调用的确认窗口到点）也要把
            // 计数还回去：漏一次，之后每一张卡上都永远挂着一条虚假的「还有 N 项待确认」。
            if (queued) waiting.decrementAndGet()
            if (waiting.get() < 0) waiting.set(0)
        }
    }

    private suspend fun showAndWait(prompt: ApprovalPrompt): InterlockChoice {
        val wm = appContext.getSystemService(WindowManager::class.java)
            ?: return InterlockChoice.UNABLE_TO_SHOW
        // 上一张还留在屏上时不再画第二张：两张叠着，用户分不清自己点的是哪一件事。
        // 回「还在等」而不是「问不了」：用户确实被一个问题占着，重试才是对的下一步。
        val left = parked
        if (left != null) {
            // 留下的那张已经过点时不该再挡路：收掉它继续往下问。否则后面每一条不同的调用
            // 都只拿到「还在等」，而通知栏那条通路根本没机会挂出来。
            if (left.isPastDeadline()) left.retire() else return InterlockChoice.AWAITING
        }
        // 走到这里说明这一次真的会挂一张窗：那条闸是「本通道已收」的意思，放在函数开头
        // 复位会让撤通道之后才醒过来的那一次呈现重新开门 —— 它挂不上新窗，却会把旧会话
        // 留下的那张卡上的点击放行到新会话刚清空的缓冲里。
        closed = false
        val choice = CompletableDeferred<InterlockChoice>()
        lateinit var owner: WindowOwner
        // 这张卡是不是已经被「留下」：留下时调用方已经回「还在等」走了，那之后用户点的
        // 那一下没有人在等，只能存进缓冲，交给下一次同参数的重试取走。
        var handedOff = false
        val card = ApprovalCardView(appContext, prompt, isNight()) { selection ->
            if (!choice.isCompleted) choice.complete(selection)
            if (handedOff && !closed) onParkedChoice?.invoke(prompt, selection)
            // 迟到的点击也要收窗：那时调用方早已拿到「还在等」走开，没人替这张窗负责。
            owner.retire()
        }
        // 根布局不画遮罩：这张窗只把卡片压在最上面，底下那一屏照样看得见。
        // 满幅与可获焦是两件事，本窗不承诺卡片之外那部分的点击归属 —— 要连"点得动"一起
        // 给，得把窗收成卡片大小，而那样入场的位移会被窗口自己的 surface 裁掉。
        val root = FocusAwareLayout(appContext)
        root.addView(card.view)
        owner = WindowOwner(wm, root, card)
        current = owner
        // 挂上之后失焦 = 窗还在、系统却已经不把它当最前面的可获焦窗了：那它多半已经不在
        // 用户眼前（被跨屏策略藏起来，或被别的窗盖过）。视图可见性回调在藏起来时照报 VISIBLE，
        // 焦点是这里唯一能问出真相的信号。撤卡与改判交给队列，幂等由本窗保证。
        root.onFocusLost = { owner.reportHiddenOnce() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 可获焦：让两个按钮拿到点击与无障碍焦点；不加 FLAG_NOT_TOUCHABLE。
            // 也不加 FLAG_BLUR_BEHIND：模糊是整屏效果的，卡片本身有投影与描边，不需要靠糊背景来突出。
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        // 一次呈现的总时长承诺：[INLINE_APPROVAL_WAIT_MS] 与「到这一问的截止时刻」里取小的。
        // 下面两段等待都从这一份里扣，而不是各扣各的 —— 否则「等上屏 4 秒 + 等答复 8 秒」
        // 会让单次 `present` 走到 12 秒，调用方那边的确认窗口形同虚设。
        val startedAt = monotonicNow()
        val totalBudget = minOf(INLINE_APPROVAL_WAIT_MS, prompt.deadlineAtMs - startedAt).coerceAtLeast(0L)
        return try {
            wm.addView(root, params)
            onScreen = true
            activeCard = card
            // 动效要在窗真的挂上之后再起：帧回调与尺寸都由那块窗自己的那次布局决定。
            card.start()
            val onScreenBudget = minOf(ON_SCREEN_BUDGET_MS, totalBudget)
            // 这一帧只是给窗完成第一次布局的时间，同样要落在预算里：熄屏时 vsync 停住，
            // 裸的 awaitFrame() 永不返回，而这段跑在主线程上。
            withTimeoutOrNull(onScreenBudget) { awaitFrame() }
            if (!awaitOnScreen(root, minOf(ON_SCREEN_BUDGET_MS, totalBudget - (monotonicNow() - startedAt)))) {
                // 窗挂上了却被系统整屏藏掉：前台应用带 HIDE_NON_SYSTEM_OVERLAY_WINDOWS 时
                // （设置一类的系统页默认如此），第三方悬浮窗一律不显示，而 addView 不报错、
                // 绘制回调也说 VISIBLE。宁可现在回「问不了」，也不要让用户对着看不见的窗等到
                // 一个确认窗口——那条调用会带着 E_AWAITING_CONSENT 失败，看起来像没人肯答。
                reportHidden(prompt)
                onScreen = false
                owner.retire()
                return InterlockChoice.UNABLE_TO_SHOW
            }
            onScreen = true
            paintCounter()
            // 两段等待都从这一次确认的总窗口里扣：截止时刻先到时不能接着等 —— 那时这张卡
            // 再留着也没人能答，占着的却是调用方的通道时间。
            val answerBudget = (totalBudget - (monotonicNow() - startedAt)).coerceAtLeast(0L)
            val waited = withTimeoutOrNull(answerBudget) {
                choice.await()
            }
            if (waited == null) {
                if (prompt.deadlineAtMs - monotonicNow() <= 0L) {
                    owner.retire()
                    return InterlockChoice.EXPIRED
                }
                // 让开通道，窗留着：用户还能答，重试会接上同一张框。
                // 到点仍没人答就自己收——留一条没人应答的浮窗等于把用户钉在原地。
                // 但窗已经在这段等待里被收掉时不能再挂成 parked：那之后每一次确认都会
                // 立刻拿到「还在等」，直到通道停 —— 一张没人负责的窗不该占着这个位置。
                if (!owner.isOpen()) {
                    // 窗已经不在的那一刻，用户的那一下可能正好落在收窗之前：答案只在
                    // 这个 deferred 里，没进缓冲也没人等。直接把它交回去，别让它蒸发 ——
                    // 丢一次「拒绝」就等于把同一件事再问一遍，而那正是这套通路要避免的。
                    return if (choice.isCompleted) choice.getCompleted() else InterlockChoice.UNABLE_TO_SHOW
                }
                owner.parkUntil(prompt.deadlineAtMs)
                parked = owner
                handedOff = true
                return InterlockChoice.AWAITING
            }
            owner.retire()
            waited
        } catch (c: CancellationException) {
            // 裁决层的确认窗口到点会取消这里。这不是"窗画不出来"：窗确实弹过，
            // 只是没人应答。吞掉取消会让超时被记成通道故障，也会破坏协程的取消语义。
            owner.retire()
            throw c
        } catch (t: Throwable) {
            // 加不上窗（授权被撤、窗口数上限等）时不假装呈现过：交回下一条通路。
            owner.retire()
            // 除 message 之外还带栈顶方法名：建窗、动画、回收三步都可能在错误线程上抛出，
            // 只有 message 分不清是哪一步，而三者的修法不同。运行日志按行读，只带一帧。
            runLog().warn(
                LogSubsystem.GATE,
                LogEvent.OVERLAY_UNAVAILABLE,
                "cap" to prompt.capability.wire,
                "cause" to (t.message ?: t.javaClass.simpleName),
                "at" to (t.stackTrace.firstOrNull()?.methodName ?: "no-frame"),
            )
            InterlockChoice.UNABLE_TO_SHOW
        }
    }

    /**
     * 这张窗是否真的到了用户眼前。
     *
     * 判据是焦点：本窗可获焦，且加上去时它是屏上最靠前的可获焦窗，所以系统真把它显示出来
     * 时一定拿得到焦点；被策略藏掉的窗进不了焦点判定。除此之外应用侧没有可用的在场信号：
     * 被藏掉时视图仍收到 VISIBLE、surface 也照样画好。
     *
     * 上限用时间而不是帧数：同一句"等一帧"在 120Hz 上是 8ms、省电档的 30Hz 上是 33ms，
     * 按帧数封顶就把一次判定做成了机型相关。整段包在超时里也是这个原因 —— 熄屏时 vsync
     * 停住，裸的 awaitFrame() 永不返回，主线程上这张窗会变成一条没有下文的等待。
     */
    private suspend fun awaitOnScreen(root: android.view.View, budgetMs: Long): Boolean {
        val focused = withTimeoutOrNull(budgetMs) {
            var seen = false
            while (!seen) {
                if (root.hasWindowFocus()) seen = true else awaitFrame()
            }
            seen
        }
        return focused == true
    }

    private fun reportHidden(prompt: ApprovalPrompt) {
        runLog().warn(
            LogSubsystem.GATE,
            LogEvent.OVERLAY_UNAVAILABLE,
            "cap" to prompt.capability.wire,
            "cause" to "hidden-by-platform",
            "at" to "no-focus",
        )
    }

    private fun paintCounter() {
        activeCard?.setPendingCount(waiting.get())
    }

    /**
     * 一张悬浮窗的收放：幂等，且可被「留下」——被留下的那张由截止时刻或迟到的点击负责收。
     * 只能在主线程调用（窗口操作本身也只能在主线程）。
     */
    private inner class WindowOwner(
        private val wm: WindowManager,
        private val root: FrameLayout,
        private val card: ApprovalCardView,
    ) {

        private var closed = false
        private var parkDeadlineMs = 0L
        private var hiddenReported = false
        private val deadlineRunnable = Runnable { onParkDeadline() }

        /** 这张窗还在不在屏上：被截止或撤通道收掉之后，它就不该再被挂成「等着用户答」的那一张。 */
        fun isOpen(): Boolean = !closed

        /**
         * 失焦后报一次「这张卡已经不在用户眼前了」。
         *
         * 幂等，且撤窗之后不再报：系统把焦点收回来的顺序不保证，`retire()` 摘窗也会触发
         * 一次焦点变化。反复回调会让队列在同一状态上反复改判，通知也就跟着反复重发。
         */
        fun reportHiddenOnce() {
            if (closed || hiddenReported) return
            hiddenReported = true
            onScreen = false
            onHidden?.invoke()
        }

        fun parkUntil(deadlineAtMs: Long) {
            parkDeadlineMs = deadlineAtMs
            mainHandler.postDelayed(deadlineRunnable, (deadlineAtMs - monotonicNow()).coerceAtLeast(0L))
        }

        /** 这张留下的卡是不是已经过了自己可答的截止时刻。 */
        fun isPastDeadline(): Boolean = parkDeadlineMs != 0L && monotonicNow() >= parkDeadlineMs

        /**
         * 「留下」那张卡的到点回收。
         *
         * 截止时刻是单调时钟（`elapsedRealtime`），`postDelayed` 走的是 `uptimeMillis`，
         * 而前者 = 后者 + 累计休眠时长，只会走得更快 —— 所以唤醒只可能迟到，不可能早到，
         * 醒来这一刻就是过点了，直接收。真正挡住在"唤醒迟到"这段时间里抬手的人不是这里，
         * 是答案侧：`recordLate` 给那一下点击盖的是这张卡自己的截止戳，过期即作废。
         * 过期的卡留在屏上只会多骗一次抬手，不会多批一件事。
         */
        private fun onParkDeadline() {
            if (closed) return
            retire()
        }

        fun retire() {
            if (closed) return
            closed = true
            mainHandler.removeCallbacks(deadlineRunnable)
            if (parked === this) parked = null
            if (current === this) current = null
            onScreen = false
            if (activeCard === card) activeCard = null
            // 先停动画再摘窗：无限重复的那两条在视图撤下后照样被帧回调驱动，
            // 而它们抓着的就是这张已经不存在的卡。
            card.stop()
            runCatching { wm.removeViewImmediate(root) }
        }
    }

    /**
     * 只为把「失焦」这件事报出去的根布局。
     *
     * `View` 没有对外的焦点监听注册口（`onWindowFocusChanged` 是可覆写的钩子），
     * 而「窗还挂着却已经不在用户眼前」在本实现里只有焦点这一个信号：
     * 被跨屏策略藏起来时视图仍收到 VISIBLE、surface 照样画好，只有焦点拿不到。
     */
    private class FocusAwareLayout(context: Context) : FrameLayout(context) {
        @Volatile
        var onFocusLost: (() -> Unit)? = null

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (!hasWindowFocus) onFocusLost?.invoke()
        }
    }

    private fun isNight(): Boolean =
        (appContext.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private companion object {
        /** 与 [InterlockQueue] 同一档：单次调用愿意为等人点让出的通道时间。 */
        const val INLINE_APPROVAL_WAIT_MS = 8_000L

        /**
         * 等焦点到位的时间上限。焦点由系统在服务端排完一次可见性后异步下发，这一档只用来
         * 兜住"永远等不到"的那种情况。
         *
         * 定在 4 秒：焦点回调可能在 1 秒量级之后才发回来，上限过短会把**看得见**的窗误判成
         * 藏掉（卡已在屏上、`mCurrentFocus` 已指向它，只是回调未到）。两个方向的代价不对称：
         * 误判成隐藏时用户仍会收到通知栏那一条，只是多问一次；误判成可见时是一个看不见的
         * 窗把人问到超时。
         *
         * 这 4 秒从这一次确认的**总窗口**里扣，用户可答的时间随之变短；而「每条问题只付一次」
         * 只在落得下备选通路时成立 —— 悬浮窗看不见、通知被关闭、助手页不在前台时，
         * `InterlockQueue.present()` 在挂上待决项之前就返回，每一条 ASK 档调用都要重付。
         * 那正是回 `E_GATE_NO_FOREGROUND` 的组合：本来就没有人能答的时候。
         */
        const val ON_SCREEN_BUDGET_MS = 4000L
    }
}
