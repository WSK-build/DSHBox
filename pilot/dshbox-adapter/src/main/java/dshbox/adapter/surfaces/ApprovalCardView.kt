package dshbox.adapter.surfaces

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dshbox.adapter.R
import interlock.relay.core.runtime.monotonicNow
import dshbox.adapter.locale.withAppLanguage
import interlock.relay.core.interlock.ApprovalPrompt
import interlock.relay.core.interlock.InterlockChoice
import interlock.relay.core.interlock.RiskLevel

/**
 * 确认卡本身：一块浮在画面之上的玻璃卡片，不画背景遮罩、不吃卡片以外的点击。
 *
 * 为什么单独成一个类而不是留在悬浮窗呈现器里：同一张卡现在有两个宿主 ——
 * 悬浮窗（助手在别的应用里操作时）与助手页内（该页就在前台时，它比悬浮卡先被选中）。
 * 两边各写一份样式，早晚会走样成两张不同的卡，而"问的是同一件事"恰恰要求它们看起来
 * 是同一件事。所以样式只有这一处，宿主只负责把它挂上去。
 *
 * 配色与本模块主题同源，且**不使用主色**：「允许」是墨黑实底、「拒绝」是描边中性，
 * 避免用颜色替用户暗示该点哪个。
 */
internal class ApprovalCardView(
    context: Context,
    private val prompt: ApprovalPrompt,
    night: Boolean,
    onChoose: (InterlockChoice) -> Unit,
) {

    private val appContext = context.applicationContext

    /**
     * 卡片文案的解析上下文。视图仍由 [appContext] 造，只有读字符串走这一层：
     * 应用上下文不带用户在设置里手选的语言，直接读会让页内框与悬浮卡各说一种话。
     */
    private val strings = appContext.withAppLanguage()

    // 色值与提问卡共用一处（同包 CardPalette）：两张卡是"同一件事"的两种呈现，
    // 各写一份迟早会走样成两张不同的卡。
    private val cardColor = CardPalette.cardColor(night)
    private val textColor = CardPalette.textColor(night)
    private val subColor = CardPalette.subColor(night)
    private val lineColor = CardPalette.lineColor(night)

    /** 卡片外壳。它的 LayoutParams 自带贴顶与左右留白，宿主给一块满幅容器即可，别再补边距。 */
    val view: FrameLayout

    private val shell: FrameLayout
    private val cardBody: LinearLayout
    private val sheen: View
    private lateinit var counterLabel: TextView
    private lateinit var countdownBar: View
    private val running = mutableListOf<android.animation.ValueAnimator>()
    private var stopped = false

    init {
        // 外壳只负责玻璃质感（圆角、描边、投影）与光扫裁剪，内容排在 cardBody 里。
        shell = FrameLayout(appContext).apply {
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
        val pad = dp(22f).toInt()
        cardBody = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, (pad * 0.82f).toInt())
        }
        shell.addView(
            cardBody,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        sheen = View(appContext).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.TRANSPARENT,
                    if (night) Color.parseColor("#40FFFFFF") else Color.parseColor("#80FFFFFF"),
                    Color.TRANSPARENT,
                ),
            )
        }
        // 光扫带的高度只能在卡片量出来之后赋值：在 WRAP_CONTENT 的外壳里给 MATCH_PARENT
        // 会退化成整屏高，把卡片撑成一块灰色大板。
        shell.addView(sheen, FrameLayout.LayoutParams(0, 0))

        cardBody.addView(headRow())
        cardBody.addView(
            TextView(appContext).apply {
                text = strings.getString(R.string.pilot_overlay_asking, strings.getString(prompt.titleRes))
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(10f).toInt(), 0, 0)
            },
        )

        // 「每次询问」只有在用户看得见问的是哪个对象时才有意义。页内框与悬浮窗两条呈现
        // 路径必须给出同样的对象与参数明细：只写标题等于让用户替任意对象背书。
        prompt.targetLabel?.let { target ->
            cardBody.addView(textLine(strings.getString(R.string.pilot_approval_target), subColor, 11f, 14f))
            cardBody.addView(textLine(target, textColor, 15f, 2f))
        }
        prompt.paramDetail?.let { detail ->
            cardBody.addView(textLine(strings.getString(R.string.pilot_approval_detail), subColor, 11f, 10f))
            cardBody.addView(textLine(detail, textColor, 15f, 2f))
        }
        // 卡上只留风险这一句：能力说明与能力详情页那行是同一句话，问一次念两遍；
        // 会话内第几次询问是内部诊断量，不参与用户判断要不要答应。
        cardBody.addView(textLine(strings.getString(prompt.risk.labelRes()), subColor, 11f, 12f))

        cardBody.addView(countdownTrack())
        cardBody.addView(actionRow(night, onChoose))
        // 两条呈现路径必须给同一组答案：悬浮窗若只给「允许一次」，等于在最常用的场合
        // （助手在别的应用里操作）把会话档藏起来，同一条能力每次都要再问一遍。
        // 上限为 ASK_ONLY 的能力本来就拿不到会话档（`allowsSessionGrant` 为假），
        // 所以这里不会给出一条绕过永久免弹通道的按钮。
        if (prompt.allowsSessionGrant) {
            cardBody.addView(
                pillButton(textColor = subColor, fillColor = Color.TRANSPARENT, strokeColor = lineColor).apply {
                    setText(strings.getString(R.string.pilot_approval_allow_session))
                    setOnClickListener { onChoose(InterlockChoice.ALLOW_SESSION) }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(46f).toInt(),
                ).apply { topMargin = dp(8f).toInt() },
            )
        }
        view = shell
    }

    /** 卡片挂上宿主之后调一次：入场动效、表面光扫与倒计时线都要等量到真实尺寸才画得对。 */
    fun start() {
        playEntrance()
        startSheen()
        startCountdown(prompt.deadlineAtMs)
    }

    /** 后面还排着几张要问：这一行没有后来者时整条隐去，显示"还有 0 项待确认"是无意义的噪声。 */
    fun setPendingCount(pending: Int) {
        counterLabel.post {
            if (pending > 0) {
                counterLabel.visibility = View.VISIBLE
                counterLabel.text = strings.getString(R.string.pilot_overlay_waiting, pending)
            } else {
                counterLabel.visibility = View.GONE
            }
        }
    }

    private fun headRow(): LinearLayout {
        val head = LinearLayout(appContext).apply { orientation = LinearLayout.HORIZONTAL }
        val mark = View(appContext).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(subColor) }
            layoutParams = LinearLayout.LayoutParams(dp(6f).toInt(), dp(6f).toInt()).apply {
                marginEnd = dp(7f).toInt(); topMargin = dp(4f).toInt()
            }
        }
        // 呼吸点：告诉用户这条通道是活的、正在等，而不是一个静态标题。
        runAnimator(
            android.animation.ObjectAnimator.ofFloat(mark, "alpha", 1f, 0.35f).apply {
                duration = 950L
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
            },
        )
        head.addView(mark)
        head.addView(
            TextView(appContext).apply {
                setText(strings.getString(R.string.pilot_page_overview))
                setTextColor(subColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                letterSpacing = 0.06f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        counterLabel = TextView(appContext).apply {
            setTextColor(subColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = ""
        }
        head.addView(
            counterLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return head
    }

    private fun countdownTrack(): View {
        val track = FrameLayout(appContext).apply {
            setBackgroundColor(lineColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2f).toInt()).apply {
                topMargin = dp(16f).toInt()
            }
        }
        val bar = View(appContext).apply {
            setBackgroundColor(subColor)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        track.addView(bar)
        countdownBar = bar
        return track
    }

    private fun actionRow(night: Boolean, onChoose: (InterlockChoice) -> Unit): LinearLayout {
        val actions = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18f).toInt(), 0, 0)
        }
        actions.addView(
            pillButton(textColor = subColor, fillColor = Color.TRANSPARENT, strokeColor = lineColor).apply {
                setText(strings.getString(R.string.pilot_approval_deny))
                setOnClickListener { onChoose(InterlockChoice.DENY) }
            },
            LinearLayout.LayoutParams(0, dp(46f).toInt(), 1f).apply { marginEnd = dp(5f).toInt() },
        )
        actions.addView(
            // 「允许」只放行这一次调用；会话档在下面单独给，跨会话的免确认仍归档位设置。
            pillButton(
                textColor = if (night) INK_LIGHT else Color.WHITE,
                fillColor = if (night) INK_DARK else INK_LIGHT,
                strokeColor = null,
            ).apply {
                setText(strings.getString(R.string.pilot_overlay_allow))
                setOnClickListener { onChoose(InterlockChoice.ALLOW_ONCE) }
            },
            LinearLayout.LayoutParams(0, dp(46f).toInt(), 1f).apply { marginStart = dp(5f).toInt() },
        )
        return actions
    }

    /**
     * 窗里的一行文字。标签与值各占一行而不是用冒号拼起来：六种语言里"标签：值"的那个
     * 分隔符写法不同，硬写一个全角冒号会在非中文界面里长出一个不属于任何语言的字符。
     */
    private fun textLine(content: CharSequence, color: Int, sizeSp: Float, topDp: Float): TextView =
        TextView(appContext).apply {
            text = content
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setPadding(0, dp(topDp).toInt(), 0, 0)
            // 两行封顶：对象名与参数明细在上游已截短，再长也不该把按钮挤出卡片。
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    private fun pillButton(textColor: Int, fillColor: Int, strokeColor: Int?): Button =
        Button(appContext).apply {
            isAllCaps = false
            setTextColor(textColor)
            val shape = pillShape(fillColor, strokeColor)
            // 按键水波：遮罩用胶囊本身，否则水波会溢出圆角；波纹色取文字色的半透明档，
            // 深底按钮显浅色波、描边按钮显深色波，不必按主题各配一遍。
            background = RippleDrawable(
                ColorStateList.valueOf((textColor and 0x00FFFFFF) or RIPPLE_ALPHA),
                shape,
                pillShape(fillColor, strokeColor),
            )
            stateListAnimator = null
        }

    private fun pillShape(fillColor: Int, strokeColor: Int?): GradientDrawable =
        GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(23f)
            strokeColor?.let { setStroke(dp(1f).toInt(), it) }
        }

    /**
     * 入场只做缩放与位移，**不做淡入**。
     *
     * 不用 alpha 起手：一张 alpha=0 的悬浮窗在系统侧各项状态都正常（`dumpsys window` 里
     * 有 surface、`mViewVisibility=0x0`、`isReadyForDisplay()=true`），屏幕上却没有一个像素，
     * 而 `addView` 成功所以不留任何失败日志 —— 用户看不见它，调用方却被它问到超时。
     * 动画万一没跑，留下的也必须是一张 0.94 倍、偏上 18dp 的**可见**卡片：
     * 宁可入场生硬，也不能问不到人。
     */
    private fun playEntrance() {
        shell.scaleX = 0.94f
        shell.scaleY = 0.94f
        shell.translationY = -dp(18f)
        shell.post {
            shell.animate()
                .scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(360L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .start()
        }
    }

    /**
     * 表面光扫：一条比卡片窄的渐变带缓慢扫过，不做成频闪。
     *
     * 必须在卡片挂上并完成一次布局之后再量尺寸——`post` 在 View 尚未 attach 时只是排队，
     * 那一刻卡片高度还是 0，按 0 算就把动画整个跳过了。
     */
    private fun startSheen() {
        shell.post {
            if (shell.width <= 0 || cardBody.height <= 0) {
                shell.post { startSheenOnce() }
            } else {
                startSheenOnce()
            }
        }
    }

    private fun startSheenOnce() {
        // 这两处都在主线程的 post / postDelayed 里跑：装饰动画不该有带走宿主进程的本事，
        // 而审批卡一没，后台能力就只剩「等不到人应答」。
        runCatching {
            val w = shell.width.toFloat()
            if (w <= 0f || cardBody.height <= 0) return
            sheen.layoutParams = sheen.layoutParams.apply {
                width = dp(150f).toInt()
                height = cardBody.height
            }
            sheen.requestLayout()
            val animator = android.animation.ObjectAnimator.ofFloat(sheen, "translationX", -dp(150f), w, w)
            animator.duration = 4600L
            animator.interpolator = android.view.animation.LinearInterpolator()
            animator.repeatCount = android.animation.ValueAnimator.INFINITE
            runAnimator(animator)
        }
    }

    /**
     * 启动倒计时线。时长取 [ApprovalPrompt.deadlineAtMs] 到现在的**剩余**量：卡是排队后才挂上的，
     * 裁决层的撤卡时刻早在排队时就定下了，从挂上那一刻按整段窗口画会多承诺一段时间。
     */
    private fun startCountdown(deadlineAtMs: Long) {
        val remainingMs = deadlineAtMs - monotonicNow()
        if (remainingMs <= 0L) {
            // 已经没有可承诺的时间：把线画空，满格条会让用户以为还早。
            countdownBar.scaleX = 0f
            return
        }
        val animator = android.animation.ObjectAnimator.ofFloat(countdownBar, "scaleX", 1f, 0f)
        animator.duration = remainingMs
        animator.interpolator = android.view.animation.LinearInterpolator()
        // 60 毫秒后卡片可能已被撤掉：同一趟主线程上的未捕获异常带走的是整个宿主。
        countdownBar.postDelayed({ runCatching { runAnimator(animator) } }, 60L)
    }

    /**
     * 起一条动画并记下它。卡片撤下时 [stop] 会挨个叫停 —— 无限重复的动画由主线程的帧回调
     * 驱动，跟视图有没有挂上窗口无关：不叫停就等于每撤一张卡留下两条永远跑着的动画，
     * 它们各自抓着卡片，卡片抓着整棵已经撤下的视图树。
     */
    private fun runAnimator(animator: android.animation.ValueAnimator) {
        if (stopped) return
        running += animator
        animator.start()
    }

    /**
     * 撤卡前叫停：入场、光扫、呼吸点与倒计时线都收在这一个出口。
     *
     * 两条通路各自负责一半 —— 悬浮窗在 `removeViewImmediate` 之前调，页内那张在
     * Compose 释放这个原生视图时调。漏掉一处不会当场出错，只会把动画留在主线程上。
     */
    fun stop() {
        if (stopped) return
        stopped = true
        shell.animate().cancel()
        val pending = running.toList()
        running.clear()
        pending.forEach { runCatching { it.cancel() } }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, appContext.resources.displayMetrics,
    )

    private companion object {
        /** 与 PilotTheme 的正文色同值：确认卡上的「允许」用墨黑实底，不用主色。 */
        val INK_LIGHT = CardPalette.inkLight
        val INK_DARK = CardPalette.inkDark

        /** 水波的不透明度档：按下去看得见、松手后不留痕。 */
        const val RIPPLE_ALPHA = 0x33000000
    }
}

/**
 * 风险档的用户可读词条。核心侧（InterlockBroker.kt）的取词扩展是模块私有，
 * 适配层自持同一份映射，键值与核心的默认文案一一对应。
 */
private fun RiskLevel.labelRes(): Int = when (this) {
    RiskLevel.LOW -> R.string.pilot_risk_low
    RiskLevel.MEDIUM -> R.string.pilot_risk_medium
    RiskLevel.HIGH -> R.string.pilot_risk_high
}
