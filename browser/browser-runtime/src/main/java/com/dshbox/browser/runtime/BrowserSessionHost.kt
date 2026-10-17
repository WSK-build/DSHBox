package com.dshbox.browser.runtime

import android.content.Context
import android.os.Looper
import android.view.ViewGroup
import android.view.View
import android.webkit.WebView
import com.dshbox.browser.api.BrowserClock
import com.dshbox.browser.api.BrowserCloseReason
import com.dshbox.browser.api.BrowserIdlePolicy
import com.dshbox.browser.api.BrowserLifecycleController
import com.dshbox.browser.api.BrowserOwner
import com.dshbox.browser.api.BrowserSnapshot

/**
 * Main-thread owner of a Browser session that outlives any single Activity window. The WebView is
 * created lazily with the application context and is only reparented on mount, so remounting an
 * activity reuses the same page instead of reloading it. This host never retains an Activity; the
 * window seam it exposes reports "no focus" while headless so foreground-gated actions stay
 * refused. All public methods must be called on the main thread.
 */
internal class BrowserSessionHost(
    context: Context,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
    idleTimeoutMillis: Long = BrowserIdlePolicy.DEFAULT_IDLE_TIMEOUT_MILLIS,
    private val pageFactory: (Context) -> WebView,
    initialSessionGeneration: Long = 0L,
) {
    private val appContext: Context = context.applicationContext ?: context
    private val lifecycle = BrowserLifecycleController(
        clock = BrowserClock { clock() },
        initialIdleTimeoutMillis = idleTimeoutMillis,
        initialSessionGeneration = initialSessionGeneration,
    )
    private var surface: WebView? = null
    private var mountContainer: ViewGroup? = null
    private var mountedWindow: BrowserSessionWindowHost? = null
    private val headlessWindow: BrowserSessionWindowHost = HeadlessSessionWindowHost(appContext)
    private val liveWindow: BrowserSessionWindowHost = LiveSessionWindowHost()
    private var surfaceConsumer: BrowserSessionSurfaceConsumer? = null
    private var destroyed = false

    /**
     * Invoked on the main thread immediately before any reparent. Return false to abort the move
     * (for example while a virtual-display capture still holds the WebView). Wired by the gateway
     * to the capture coordinator's synchronous cancel-and-restore.
     */
    var beforeReparent: (() -> Boolean)? = null

    /**
     * Stable window seam for command execution. The returned instance is constant for the session
     * lifetime and resolves the current window on every access, so an executor constructed once
     * while headless starts reporting the activity window after mount and reverts to headless on
     * unmount. Do not cache the resolved activity host directly.
     */
    fun window(): BrowserSessionWindowHost = liveWindow

    private inner class LiveSessionWindowHost : BrowserSessionWindowHost {
        private val delegate: BrowserSessionWindowHost
            get() = mountedWindow ?: headlessWindow

        override val context: Context get() = delegate.context

        override fun hasWindowFocus(): Boolean = delegate.hasWindowFocus()

        override val displayRotationDegrees: Int get() = delegate.displayRotationDegrees

        override val displayDensity: Float get() = delegate.displayDensity
    }

    fun snapshot(): BrowserSnapshot = lifecycle.snapshot()

    /** The single lifecycle owner for this session; shared with command execution. */
    val lifecycleController: BrowserLifecycleController get() = lifecycle

    /** Live surface if one has been created, otherwise null. Never creates a page. */
    fun surfaceOrNull(): WebView? = surface

    fun isMounted(): Boolean = mountContainer != null

    fun hasSurface(): Boolean = surface != null

    /** Moves the session to ACTIVE for the given owner, creating nothing until a surface is used. */
    fun open(owner: BrowserOwner): BrowserSnapshot {
        requireMainThread()
        check(!destroyed) { "session already closed" }
        return lifecycle.open(owner)
    }

    /**
     * Returns the live session surface, creating it on first use. Reuses the same WebView across
     * mounts so page identity, task state and navigation are preserved.
     */
    fun surface(): WebView {
        requireMainThread()
        check(!destroyed) { "session already closed" }
        surface?.let { return it }
        val created = pageFactory(appContext)
        val metrics = appContext.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        created.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        created.layout(0, 0, width, height)
        surface = created
        surfaceConsumer?.onSessionSurfaceAttached(created)
        return created
    }

    /** Reparents the existing surface into a Browser activity container without reloading. */
    fun mount(container: ViewGroup, window: BrowserSessionWindowHost): Boolean {
        requireMainThread()
        if (destroyed) return false
        if (beforeReparent?.invoke() == false) return false
        return mountInternal(container, window)
    }

    /** Mounts without re-invoking beforeReparent (used after a guarded surface switch). */
    private fun mountInternal(container: ViewGroup, window: BrowserSessionWindowHost): Boolean {
        val view = surface()
        if (mountContainer === container && view.parent === container) {
            mountedWindow = window
            return true
        }
        (view.parent as? ViewGroup)?.removeView(view)
        mountContainer = container
        mountedWindow = window
        container.addView(
            view,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        return true
    }

    /** Detaches the surface from its visible container while keeping the page alive. */
    fun unmount(): Boolean {
        requireMainThread()
        if (beforeReparent?.invoke() == false) return false
        mountedWindow = null
        val view = surface ?: run { mountContainer = null; return true }
        val container = mountContainer
        mountContainer = null
        if (container != null && view.parent === container) {
            container.removeView(view)
        } else {
            (view.parent as? ViewGroup)?.removeView(view)
        }
        return true
    }

    fun attachSurfaceConsumer(consumer: BrowserSessionSurfaceConsumer?) {
        requireMainThread()
        if (destroyed) return
        surfaceConsumer = consumer
        if (consumer != null) surface?.let(consumer::onSessionSurfaceAttached)
    }

    /**
     * Makes an existing page surface active; if a window is mounted the same container is
     * reparented to the new surface without creating or reloading any page.
     */
    fun setActiveSurface(view: WebView): Boolean {
        requireMainThread()
        if (destroyed) return false
        if (surface === view) return true
        if (beforeReparent?.invoke() == false) return false
        val container = mountContainer
        val window = mountedWindow
        (surface?.parent as? ViewGroup)?.removeView(surface)
        surface = view
        surfaceConsumer?.onSessionSurfaceAttached(view)
        if (container != null && window != null) mountInternal(container, window)
        return true
    }

    /** Applies the current idle deadline, closing the session when it has elapsed. */
    fun expireIfNeeded(): BrowserSnapshot {
        requireMainThread()
        return lifecycle.expireIfNeeded(clock())
    }

    fun updateIdleTimeout(timeoutMillis: Long): BrowserSnapshot {
        requireMainThread()
        return lifecycle.updateIdleTimeout(timeoutMillis)
    }

    /** Closes the session, detaches any consumer and destroys the surface on the main thread. */
    fun close(reason: BrowserCloseReason): BrowserSnapshot {
        requireMainThread()
        val snapshot = lifecycle.close(reason)
        teardownSurface()
        return snapshot
    }

    private fun teardownSurface() {
        // Terminal disposal: the virtual-display capture has already released its display (even a
        // failed restore just leaves the WebView parented), so detach from whatever parent remains
        // and destroy it without the normal reparent guard. This completes the session so a new one
        // can be created; the capture already reported RESTORE_FAILED to its own caller.
        val view = surface
        mountContainer = null
        mountedWindow = null
        (view?.parent as? ViewGroup)?.removeView(view)
        surface = null
        surfaceConsumer?.onSessionSurfaceDetached()
        if (view != null) runCatching { view.destroy() }
        destroyed = true
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "BrowserSessionHost must be used on the main thread"
        }
    }

    companion object {
        fun idleTimeoutFromPreferences(context: Context): Long =
            BrowserRuntimeSettings.preferences(context)
                .getInt(BrowserRuntimeSettings.KEY_IDLE_MINUTES, BrowserRuntimeSettings.DEFAULT_IDLE_MINUTES)
                .toLong()
                .times(60_000L)
                .coerceIn(BrowserIdlePolicy.MIN_IDLE_TIMEOUT_MILLIS, BrowserIdlePolicy.MAX_IDLE_TIMEOUT_MILLIS)
    }
}

/** Process-level handle to the active background session, scoped to the :browser process. */
internal object BrowserSessionHostRegistry {
    @Volatile
    private var active: BrowserSessionHost? = null

    fun install(host: BrowserSessionHost) {
        active = host
    }

    fun current(): BrowserSessionHost? = active

    fun clear(host: BrowserSessionHost) {
        if (active === host) active = null
    }
}
