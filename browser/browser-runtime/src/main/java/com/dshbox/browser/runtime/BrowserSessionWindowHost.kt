package com.dshbox.browser.runtime

import android.app.Activity
import android.content.Context

/**
 * Window/context seam used by command execution. It replaces the direct dependency on a concrete
 * {@link Activity} so a session can run without a visible window while still reporting the display
 * geometry that visual-action identity checks require. Implementations must be queried on the main
 * thread and must never retain an Activity beyond its window lifetime.
 */
internal interface BrowserSessionWindowHost {
    /** Session context. Headless sessions use the application context, never an Activity. */
    val context: Context

    /** Whether a user-visible window currently holds focus for foreground-gated actions. */
    fun hasWindowFocus(): Boolean

    /** Current display rotation in degrees, matching Android visual identity checks. */
    val displayRotationDegrees: Int

    /** Current display density, used for visual action identity checks. */
    val displayDensity: Float
        get() = context.resources.displayMetrics.density
}

/** Window seam backed by a mounted Browser activity. */
@Suppress("DEPRECATION")
internal class ActivitySessionWindowHost(private val activity: Activity) : BrowserSessionWindowHost {
    override val context: Context get() = activity

    override fun hasWindowFocus(): Boolean = activity.hasWindowFocus()

    override val displayRotationDegrees: Int
        get() = runCatching { activity.windowManager.defaultDisplay.rotation * 90 }.getOrDefault(0)
}

/** Window seam for a session that currently has no mounted activity. */
internal class HeadlessSessionWindowHost(override val context: Context) : BrowserSessionWindowHost {
    override fun hasWindowFocus(): Boolean = false

    override val displayRotationDegrees: Int = 0
}
