package com.dshbox.browser.runtime

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver

internal class BrowserUploadApprovalFocusObserver(
    decor: View,
    private val onFocused: () -> Boolean,
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var checkPosted = false
    private var dispatchingFocusChange = false
    internal val isDispatchingFocusChange: Boolean
        get() = dispatchingFocusChange
    private val observer: ViewTreeObserver? = decor.viewTreeObserver.takeIf { it.isAlive }
    private val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        dispatchingFocusChange = true
        try {
            if (hasFocus) postCheck()
        } finally {
            dispatchingFocusChange = false
        }
    }
    private val check = Runnable {
        checkPosted = false
        if (!closed && onFocused()) close()
    }

    init {
        observer?.addOnWindowFocusChangeListener(listener)
    }

    fun requestCheck() {
        postCheck()
    }

    private fun postCheck() {
        if (closed || checkPosted) return
        checkPosted = true
        handler.post(check)
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(check)
        checkPosted = false
        observer?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(listener)
    }
}
