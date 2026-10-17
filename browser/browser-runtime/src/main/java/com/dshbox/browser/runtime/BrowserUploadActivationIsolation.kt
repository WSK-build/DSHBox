package com.dshbox.browser.runtime

import java.util.WeakHashMap

/** Keeps an unconfirmed native chooser gesture isolated to the WebView that dispatched it. */
internal class BrowserUploadActivationIsolation<View : Any, Identity : Any> {
    private val unresolved = WeakHashMap<View, Identity>()

    @Synchronized
    fun isUnresolved(view: View): Boolean = unresolved.containsKey(view)

    @Synchronized
    fun markUnresolved(view: View, identity: Identity) {
        unresolved[view] = identity
    }

    @Synchronized
    fun consumeLateChooser(view: View): Boolean {
        if (!unresolved.containsKey(view)) return false
        unresolved.remove(view)
        return true
    }

    @Synchronized
    fun release(view: View) {
        unresolved.remove(view)
    }
}
