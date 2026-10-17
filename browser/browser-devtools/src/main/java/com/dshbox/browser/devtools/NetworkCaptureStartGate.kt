package com.dshbox.browser.devtools

/** Orders capture initialization against asynchronous Network events. */
internal class NetworkCaptureStartGate {
    private val lock = Any()

    fun <E, T> enableThenStart(enable: () -> E, begin: () -> T): T = synchronized(lock) {
        enable()
        begin()
    }

    fun dispatchEvent(action: () -> Unit) = synchronized(lock, action)
}
