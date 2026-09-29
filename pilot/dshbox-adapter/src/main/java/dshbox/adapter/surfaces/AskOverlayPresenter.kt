package dshbox.adapter.surfaces

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dshbox.adapter.R
import dshbox.adapter.locale.withAppLanguage
import interlock.relay.core.interlock.InterlockPrompt
import interlock.relay.core.interlock.PromptCodes
import interlock.relay.core.interlock.PromptOutcome
import interlock.relay.core.interlock.RelayAskSurface
import interlock.relay.core.runtime.monotonicNow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 用自制悬浮卡把助手的问题当面摆给用户：一句问题 + 2..3 个选项 + 一个「全部驳回」。
 * 提问的码表、结局与呈现契约（[PromptCodes] / [PromptOutcome] / [InterlockPrompt]）
 * 在核心模块的 interlock.relay.core.interlock，本类只做悬浮窗这一种呈现。
 *
 * 存在的理由：助手在前台操作别的应用时，用户看不到 DSH 对话页，在对话页里提问
 * 等于双方空等。这条通路让提问出现在用户正看着的屏幕最上层，答案当场带回。
 *
 * 与审批悬浮卡的关系：两者都是 TYPE_APPLICATION_OVERLAY，**同屏只允许一张**，
 * 而且这是双向的——审批卡在屏（或有一个审批已经排着队）时本卡直接回忙；本卡在屏时
 * 审批那条路自己会让位（`InterlockQueue` 的 `otherCardOnScreen`），不让两张卡叠在一起。
 * 后台可信屏在跑时浮窗会被平台跨屏隐藏，沿用与审批相同的 overlayBlind 判据主动放弃。
 */
class AskOverlayPresenter(
    context: Context,
    /** 是否已有审批占着屏或排着队（审批卡在屏 / 页内卡在屏 / 有审批悬着）。 */
    private val cardOnScreen: () -> Boolean,
    /** 后台可信屏在跑（浮窗会被平台跨屏隐藏）。 */
    private val overlayBlind: () -> Boolean,
) : RelayAskSurface {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)

    /** 屏上此刻是否有本呈现器的卡。审批那条路据此让位，裁决随时可以查。 */
    @Volatile
    private var presented = false

    /** 当前那张卡的根视图与等待席位（只在主线程读写；[withdraw] 经 handler 切到主线程）。 */
    private var activeRoot: View? = null
    private var activeSeat: CompletableDeferred<PromptOutcome>? = null

    /**
     * 收到过撤卡请求而卡还没挂上。
     *
     * 覆盖 buildCard 到 addView 之间那段窗口：用户在"提问卡正在被投递"的瞬间关掉开关时，
     * [withdraw] 找不到 root 就会静默无事，随后卡照常上屏并等满整个窗口——而 [PromptCodes.BUSY_CHANNEL_STOPPED]
     * 承诺的是"卡已撤"。showAndWait 挂上后检查一次这个标记并当场自收。
     */
    @Volatile
    private var withdrawRequested = false

    /** 屏上此刻是否有本呈现器的卡。审批那条路据此让位，裁决随时可以查。 */
    override fun isPresented(): Boolean = presented

    /**
     * 收掉屏上那张卡并把等待当场收场。幂等，可在任意线程调用。
     *
     * 什么时候用：通道/会话已经收束（用户把开关关了、运行时停了）而卡还挂在屏上。
     * 审批侧有同一条纪律（`overlayApprovals.dismissAll()`）：没人再等的那一刻起，
     * 一张占着屏的卡只会让用户对着一个没有收件人的窗口做动作。
     */
    override fun withdraw(cause: String) {
        withdrawRequested = true
        mainHandler.post { withdrawLocked(cause) }
    }

    private fun withdrawLocked(cause: String) {
        // 标记在这里一并清掉：撤卡请求处理完（哪怕当时没有卡）就不该再影响**下一张**卡。
        // 不清的话，进程内一次 stop→start 之后的第一问会被这张"迟到的撤卡"当场收掉，
        // 用户看到一闪而过的卡、助手拿到一个假的"通道已停"。
        withdrawRequested = false
        val root = activeRoot ?: return
        val seat = activeSeat
        activeRoot = null
        activeSeat = null
        presented = false
        runCatching { windowManager()?.removeViewImmediate(root) }
        if (seat != null && !seat.isCompleted) seat.complete(PromptOutcome.Unavailable(cause))
    }

    override suspend fun ask(question: String, options: List<String>, timeoutMs: Long): PromptOutcome {
        if (overlayBlind()) {
            return PromptOutcome.Unavailable(PromptCodes.NO_SURFACE_BLIND)
        }
        if (!Settings.canDrawOverlays(appContext)) {
            return PromptOutcome.Unavailable(PromptCodes.NO_SURFACE_NOT_GRANTED)
        }
        // 同一时刻只问一件事：两张卡同屏会让用户分不清答的是哪一问。
        if (!busy.compareAndSet(false, true)) {
            return PromptOutcome.Unavailable(PromptCodes.BUSY_ANOTHER_QUESTION)
        }
        try {
            if (cardOnScreen()) {
                return PromptOutcome.Unavailable(PromptCodes.BUSY_APPROVAL_CARD)
            }
            // 新的一问从"没有收到撤卡请求"开始：上一次会话留下的标记只能影响它自己那一刻
            // （见 withdrawLocked 的清法与注释）。
            withdrawRequested = false
            return withContext(Dispatchers.Main) { showAndWait(question, options, timeoutMs) }
        } catch (cancellation: CancellationException) {
            // 取消不是"问不了"：把它吞成 Unavailable 会让调用方按"去开权限"处理，
            // 也会破坏协程的取消语义（审批呈现器同规：OverlayApproval 里单列一段重抛）。
            throw cancellation
        } catch (t: Throwable) {
            // 挂不上窗（窗口数上限、权限被撤、错误线程）是宿主侧问题：用户开什么设置都修不好，
            // 因此按 E_INTERNAL 报，不冒充"需要用户动手"。
            return PromptOutcome.Unavailable(
                "${PromptCodes.INTERNAL}/overlay add failed: ${t.message ?: t.javaClass.simpleName}",
            )
        } finally {
            busy.set(false)
        }
    }

    private suspend fun showAndWait(question: String, options: List<String>, timeoutMs: Long): PromptOutcome {
        val wm = windowManager() ?: return PromptOutcome.Unavailable("${PromptCodes.INTERNAL}/no window manager")
        val night = (appContext.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val choice = CompletableDeferred<PromptOutcome>()
        val root = buildCard(question, options, night) { outcome -> if (!choice.isCompleted) choice.complete(outcome) }
        // 窗口与审批卡同一套：满幅容器 + 贴左上，卡片外壳自己带 52dp 顶距与 16dp 左右留白，
        // 于是两张卡出现在屏幕上**同一个位置**。
        // 与审批卡唯一的一处差别：这里加了 FLAG_NOT_TOUCH_MODAL。审批卡的内联等待顶多 8 秒，
        // 这张卡可以停留两分钟——不加它，窗口外的触摸会被整屏吞掉两分钟，用户连别的应用都点不动。
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 可获焦：按钮要能拿到点击，也让"是否真的上了屏"可用焦点自检判定。
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        // 等待期间丢过焦点（被别的窗盖住/锁屏/下拉通知栏）只作诊断信号，不当失败处理：
        // 下拉通知栏这类操作会短暂夺焦，随后还回来，按失败收场会误杀一次正在进行的提问。
        var lostFocus = false

        return try {
            // 屏位复查放在主线程、紧贴 addView 之前：调用方那一次判据（ask 的 cardOnScreen）
            // 与这里之间隔着一次线程切换，审批卡可能正好在这个窗口里挂上；两处读的是同一对
            // 事实，主线程上再读一次把这个 TOCTOU 窗口关掉。
            if (cardOnScreen()) {
                return PromptOutcome.Unavailable(PromptCodes.BUSY_APPROVAL_CARD)
            }
            activeRoot = root
            activeSeat = choice
            wm.addView(root, params)
            presented = true
            if (withdrawRequested) {
                // 撤卡请求落在这张卡挂上之前：当场自收，别让一张没人等的卡留在屏上。
                withdrawRequested = false
                return PromptOutcome.Unavailable(PromptCodes.BUSY_CHANNEL_STOPPED)
            }
            root.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus -> if (!hasFocus) lostFocus = true }
            // 挂上不等于显示出来：前台应用带 HIDE_NON_SYSTEM_OVERLAY_WINDOWS 时（设置一类
            // 系统页默认如此）第三方悬浮窗整屏不显示，而 addView 不报错、绘制回调照样说
            // VISIBLE。判据与审批卡同法 —— 只有焦点能证明这张卡真到了用户眼前（见
            // OverlayApproval.awaitOnScreen 的长注释）。没上屏就当场回「问不了」：
            // 让调用方对着一个看不见的窗等满窗口，会把「没人看见」错报成「没人回答」。
            //
            // 上屏这一段是**一份预算**（RelayAskSurface.ON_SCREEN_BUDGET_MS），两次等待都从它里面扣，
            // 不是各给 4 秒：各扣各的会让这一段走到 8 秒，越过入口 CLI 的 5 秒余量，
            // 正常收尾的回包反而被记成「没有回包」。审批那侧用一个 totalBudget 同扣，同理。
            val onScreenStarted = monotonicNow()
            withTimeoutOrNull(RelayAskSurface.ON_SCREEN_BUDGET_MS) { awaitFrame() }
            val remaining = RelayAskSurface.ON_SCREEN_BUDGET_MS - (monotonicNow() - onScreenStarted)
            if (!awaitOnScreen(root, remaining.coerceAtLeast(0L))) {
                return PromptOutcome.Unavailable(PromptCodes.NO_SURFACE_HIDDEN)
            }
            withTimeoutOrNull(timeoutMs) { choice.await() } ?: PromptOutcome.TimedOut(covered = lostFocus)
        } finally {
            presented = false
            if (activeRoot === root) {
                activeRoot = null
                activeSeat = null
            }
            runCatching { wm.removeViewImmediate(root) }
        }
    }

    private fun windowManager(): WindowManager? = appContext.getSystemService(WindowManager::class.java)

    /**
     * 这张窗是否真的到了用户眼前。与 [OverlayApproval] 同一条判据与同一份理由：
     * 被策略藏掉的窗拿不到焦点，而应用侧其余信号（VISIBLE、surface 画好）都会说谎。
     * 时间封顶而不是帧数封顶：熄屏时 vsync 停住，裸的 awaitFrame() 永不返回。
     */
    private suspend fun awaitOnScreen(root: View, budgetMs: Long): Boolean {
        val focused = withTimeoutOrNull(budgetMs) {
            var seen = false
            while (!seen) {
                if (root.hasWindowFocus()) seen = true else awaitFrame()
            }
            seen
        }
        return focused == true
    }

    /**
     * 卡面：标题一行、问题正文、逐项按钮、底部「重新提问」与「全部驳回」。
     *
     * 与审批卡同源：外壳（圆角 20、1dp 描边、24dp 投影、贴顶 52dp、左右 16dp 留白）与
     * 底部那两个胶囊按钮（半高圆角、按键水波纹、墨黑实底 / 描边中性）都取审批卡那套，
     * 两张卡在屏幕上长得是同一张卡——"问的是同一件事"正是靠这一点读出来的。
     */
    private fun buildCard(
        question: String,
        options: List<String>,
        night: Boolean,
        decide: (PromptOutcome) -> Unit,
    ): View {
        val strings = appContext.withAppLanguage()
        val cardColor = CardPalette.cardColor(night)
        val textColor = CardPalette.textColor(night)
        val subColor = CardPalette.subColor(night)
        val lineColor = CardPalette.lineColor(night)

        val shell = FrameLayout(appContext).apply {
            background = GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = dp(20f)
                setStroke(dp(1f).toInt(), lineColor)
            }
            elevation = dp(24f)
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                topMargin = dp(52f).toInt()
                marginStart = dp(16f).toInt()
                marginEnd = dp(16f).toInt()
            }
        }

        val body = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f).toInt(), dp(22f).toInt(), dp(22f).toInt(), dp(18f).toInt())
        }
        shell.addView(
            body,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )

        body.addView(TextView(appContext).apply {
            text = strings.getString(R.string.pilot_ask_title)
            setTextColor(subColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            letterSpacing = 0.06f
        })
        body.addView(TextView(appContext).apply {
            text = question
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(10f).toInt(), 0, dp(14f).toInt())
        })
        options.forEachIndexed { index, label ->
            body.addView(optionButton(label, textColor, cardColor, lineColor) {
                decide(PromptOutcome.Chosen(index, label))
            }.apply {
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(46f).toInt(),
                )
                lp.topMargin = dp(8f).toInt()
                layoutParams = lp
            })
        }
        // 「重新提问」在上、「全部驳回」在下，两个都用审批卡那套胶囊按钮。
        // 主次与审批卡同规：主动作（重新提问 = 把这件事继续问下去）墨黑实底，
        // 否定动作（全部驳回 = 这一组选项都不行）描边中性，不用颜色暗示"该点哪个"。
        body.addView(
            pillButton(
                textColor = if (night) CardPalette.inkLight else Color.WHITE,
                fillColor = if (night) CardPalette.inkDark else CardPalette.inkLight,
                strokeColor = null,
            ).apply {
                setText(strings.getString(R.string.pilot_ask_reask))
                setOnClickListener { decide(PromptOutcome.Reasked) }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46f).toInt()).apply {
                topMargin = dp(14f).toInt()
            },
        )
        body.addView(
            pillButton(textColor = subColor, fillColor = Color.TRANSPARENT, strokeColor = lineColor).apply {
                setText(strings.getString(R.string.pilot_ask_reject_all))
                setOnClickListener { decide(PromptOutcome.RejectedAll) }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46f).toInt()).apply {
                topMargin = dp(8f).toInt()
            },
        )
        return shell
    }

    /** 与审批卡同款：半高圆角胶囊 + 按键水波纹（波纹色取文字色的半透明档）。 */
    private fun pillButton(textColor: Int, fillColor: Int, strokeColor: Int?): android.widget.Button =
        android.widget.Button(appContext).apply {
            isAllCaps = false
            setTextColor(textColor)
            val shape = pillShape(fillColor, strokeColor)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((textColor and 0x00FFFFFF) or RIPPLE_ALPHA),
                shape,
                pillShape(fillColor, strokeColor),
            )
            stateListAnimator = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }

    private fun pillShape(fillColor: Int, strokeColor: Int?): GradientDrawable =
        GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(23f)
            strokeColor?.let { setStroke(dp(1f).toInt(), it) }
        }

    private fun optionButton(
        label: String,
        textColor: Int,
        fillColor: Int,
        strokeColor: Int,
        onClick: () -> Unit,
    ): TextView = TextView(appContext).apply {
        text = label
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(14f)
            setStroke(dp(1f).toInt(), strokeColor)
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun dp(value: Float): Float = value * appContext.resources.displayMetrics.density

    companion object {
        /**
         * 上屏判定的总预算复用 core 的 [RelayAskSurface.ON_SCREEN_BUDGET_MS]：契约常量只在
         * core 一处定义，宿主实现与入口 CLI 的等待余量（pilot-client.cjs 的
         * ASK_BUDGET_MARGIN_MS）之间那笔对账由 core 的 AskContractTest 钉住。
         *
         * 上限必须小于入口 CLI 的本地等待余量（pilot-client.cjs 的 ASK_BUDGET_MARGIN_MS）：
         * 客户端预算 = timeoutMs + 余量，宿主侧最坏耗时 = 本段 + timeoutMs + 认领延迟。
         * 余量（12000）里给认领延迟留了 5000（idle 轮询上界）、给这一段留了 4000，
         * 其余是回包落盘与抖动。本段一旦超过那个余量，正常收尾的回包会晚于客户端预算，
         * 被误记成「没有回包」。本段是**一份**预算：`awaitFrame` 与 `awaitOnScreen`
         * 两段从它里面同扣（见 showAndWait）。
         */

        /** 按键水波纹的透明度档；与审批卡取同一个值（0x33 = 20%）。 */
        private const val RIPPLE_ALPHA = 0x33000000
    }
}
