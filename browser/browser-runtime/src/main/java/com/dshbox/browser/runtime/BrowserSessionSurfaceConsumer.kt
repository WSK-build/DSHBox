package com.dshbox.browser.runtime

import android.webkit.WebView

/**
 * Minimal integration point for the background virtual-surface capture component. The consumer
 * receives the live session WebView so it can render the same page off-screen; it must not
 * reparent the view or reload its URL. Callbacks run on the main thread.
 */
internal interface BrowserSessionSurfaceConsumer {
    /** The live session surface is available; the consumer may attach an off-screen render. */
    fun onSessionSurfaceAttached(surface: WebView)

    /** The session surface is about to be destroyed or the session is closing. */
    fun onSessionSurfaceDetached()
}
