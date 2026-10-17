package com.dshbox.browser.runtime

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator

/** Shows recent authorized AI activity; this display never renews the Browser session. */
internal class BrowserAiIndicatorView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF383B41.toInt()
        textSize = 12f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x503F83F8
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
    }
    private val spinner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BLUE
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private var orbitRotation = 0f
    private var busy = false
    private var animator: ValueAnimator? = null
    private var eligible = false
    private var inFlight = false
    private var activityUntil = 0L
    private var accentColor = COLOR_BLUE
    private val expireActivity = Runnable { refreshActivity() }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setControlState(allowed: Boolean, operationInFlight: Boolean) {
        val wasInFlight = inFlight
        eligible = allowed
        inFlight = allowed && operationInFlight
        if (!allowed) activityUntil = 0L
        else if (inFlight || wasInFlight) activityUntil = SystemClock.elapsedRealtime() + ACTIVITY_WINDOW_MILLIS
        refreshActivity()
    }

    fun noteToolActivity() {
        if (!eligible) return
        activityUntil = SystemClock.elapsedRealtime() + ACTIVITY_WINDOW_MILLIS
        refreshActivity()
    }

    fun setTheme(ink: Int, accent: Int, dark: Boolean) {
        label.color = ink
        accentColor = accent
        ring.color = if (dark) 0x705A8FD8 else 0x503F83F8
        spinner.color = accent
        invalidate()
    }

    private fun refreshActivity() {
        removeCallbacks(expireActivity)
        val remaining = activityUntil - SystemClock.elapsedRealtime()
        setBusy(eligible && (inFlight || remaining > 0L))
        if (eligible && !inFlight && remaining > 0L) postDelayed(expireActivity, remaining)
    }

    private fun setBusy(isBusy: Boolean) {
        if (busy == isBusy) return
        busy = isBusy
        if (isBusy) {
            animator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 1_300L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    orbitRotation = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            animator?.cancel()
            animator = null
            orbitRotation = 0f
            spinner.shader = null
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        val cx = width / 2f
        val radius = (minOf(width, height) / 2f - 5f * density).coerceAtLeast(1f)
        canvas.drawText("AI", cx, cy - (label.ascent() + label.descent()) / 2f, label)
        if (busy) {
            val circle = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawCircle(cx, cy, radius, ring)
            spinner.shader = SweepGradient(
                cx, cy,
                intArrayOf(withAlpha(accentColor, 0), withAlpha(accentColor, 110), accentColor, withAlpha(accentColor, 0)),
                floatArrayOf(0f, 0.28f, 0.58f, 1f),
            )
            canvas.save()
            canvas.rotate(orbitRotation, cx, cy)
            canvas.drawArc(circle, -90f, 145f, false, spinner)
            canvas.restore()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshActivity()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(expireActivity)
        animator?.cancel()
        animator = null
        busy = false
        spinner.shader = null
        super.onDetachedFromWindow()
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private companion object {
        const val ACTIVITY_WINDOW_MILLIS = 30_000L
        const val COLOR_BLUE = 0xFF3F83F8.toInt()
    }
}
