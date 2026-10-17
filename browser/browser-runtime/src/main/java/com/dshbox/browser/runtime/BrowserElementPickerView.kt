package com.dshbox.browser.runtime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View

/** Native touch surface that exposes only the user's explicit tap or drag bounds. */
internal class BrowserElementPickerView(
    context: Context,
    private val onSelectionGesture: (RectF) -> Unit,
    private val onDoubleTap: (RectF) -> Unit,
    private val onForwardedTouch: (MotionEvent) -> Unit,
    private val onForwardingStarted: () -> Unit,
    private val onForwardingFinished: () -> Unit,
) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3483F5.toInt()
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 2.2f
    }
    private val focusFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x333483F5 }
    private var downX = 0f
    private var downY = 0f
    private var selection = RectF()
    private var priorSelection = RectF()
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var elementMode = true
    private var forwarding = false
    private var pendingDown: MotionEvent? = null
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.browser_picker_element)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!selection.isEmpty) {
            canvas.drawRect(selection, focusFill)
            canvas.drawRect(selection, stroke)
        }
    }

    fun showSelection(bounds: RectF) {
        selection = RectF(bounds)
        invalidate()
    }

    fun clearSelection() {
        selection = RectF()
        invalidate()
    }

    fun setElementMode(enabled: Boolean) {
        elementMode = enabled
        resetTouchRoute()
    }

    fun setThemeColors(accent: Int) {
        stroke.color = accent
        focusFill.color = (accent and 0x00FFFFFF) or 0x33000000
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetTouchRoute()
                downX = event.x.coerceIn(0f, width.toFloat())
                downY = event.y.coerceIn(0f, height.toFloat())
                priorSelection = RectF(selection)
                pendingDown = MotionEvent.obtain(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (forwarding) {
                    onForwardedTouch(event)
                    return true
                }
                if (BrowserPickerGesturePolicy.shouldForward(
                        elementMode = elementMode,
                        pointerDown = false,
                        deltaX = event.x - downX,
                        deltaY = event.y - downY,
                        touchSlop = touchSlop,
                    )
                ) {
                    beginForwarding(event)
                    return true
                }
                if (!elementMode) updateSelection(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!forwarding && BrowserPickerGesturePolicy.shouldForward(
                        elementMode = elementMode,
                        pointerDown = true,
                        deltaX = 0f,
                        deltaY = 0f,
                        touchSlop = touchSlop,
                    )
                ) {
                    beginForwarding(event)
                } else if (forwarding) {
                    onForwardedTouch(event)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (forwarding) {
                    onForwardedTouch(event)
                    onForwardingFinished()
                    resetTouchRoute()
                    return true
                }
                performClick()
                val now = event.eventTime
                val tap = kotlin.math.abs(event.x - downX) <= touchSlop &&
                    kotlin.math.abs(event.y - downY) <= touchSlop
                if (tap && priorSelection.contains(downX, downY)) {
                    val isDoubleTap = now - lastTapAt in 1..doubleTapTimeout &&
                        kotlin.math.hypot((event.x - lastTapX).toDouble(), (event.y - lastTapY).toDouble()) <= touchSlop * 2
                    selection = RectF(priorSelection)
                    invalidate()
                    if (isDoubleTap) {
                        lastTapAt = 0L
                        onDoubleTap(RectF(priorSelection))
                    } else {
                        lastTapAt = now
                        lastTapX = event.x
                        lastTapY = event.y
                    }
                    resetTouchRoute()
                    return true
                }
                updateSelection(event.x, event.y)
                val bounds = RectF(selection)
                onSelectionGesture(bounds)
                if (tap) {
                    lastTapAt = now
                    lastTapX = event.x
                    lastTapY = event.y
                } else {
                    lastTapAt = 0L
                }
                resetTouchRoute()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (forwarding) onForwardedTouch(event)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (forwarding) {
                    onForwardedTouch(event)
                    onForwardingFinished()
                } else {
                    selection = RectF()
                    invalidate()
                }
                resetTouchRoute()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        resetTouchRoute()
        super.onDetachedFromWindow()
    }

    private fun updateSelection(x: Float, y: Float) {
        selection.set(
            minOf(downX, x.coerceIn(0f, width.toFloat())),
            minOf(downY, y.coerceIn(0f, height.toFloat())),
            maxOf(downX, x.coerceIn(0f, width.toFloat())),
            maxOf(downY, y.coerceIn(0f, height.toFloat())),
        )
        invalidate()
    }

    private fun beginForwarding(trigger: MotionEvent) {
        if (forwarding) return
        forwarding = true
        selection = RectF()
        invalidate()
        onForwardingStarted()
        pendingDown?.let(onForwardedTouch)
        pendingDown?.recycle()
        pendingDown = null
        onForwardedTouch(trigger)
    }

    private fun resetTouchRoute() {
        forwarding = false
        pendingDown?.recycle()
        pendingDown = null
    }
}

/** Pure routing rule shared by the touch view and its JVM tests. */
internal object BrowserPickerGesturePolicy {
    fun shouldForward(
        elementMode: Boolean,
        pointerDown: Boolean,
        deltaX: Float,
        deltaY: Float,
        touchSlop: Float,
    ): Boolean {
        if (pointerDown) return true
        if (!elementMode) return false
        return deltaX * deltaX + deltaY * deltaY > touchSlop * touchSlop
    }
}
