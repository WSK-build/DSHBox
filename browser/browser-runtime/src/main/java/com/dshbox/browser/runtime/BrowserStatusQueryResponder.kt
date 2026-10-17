package com.dshbox.browser.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.dshbox.browser.api.BrowserContract

/** Answers broker reconnect queries for a live session even when no Browser activity is mounted. */
internal class BrowserStatusQueryResponder(
    private val context: Context,
    private val publish: () -> Unit,
) : AutoCloseable {
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (registered && intent.action == BrowserContract.ACTION_QUERY_STATUS) publish()
        }
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (registered) return
        val filter = IntentFilter(BrowserContract.ACTION_QUERY_STATUS)
        val permission = BrowserContract.internalPermission(context.packageName)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, permission, Handler(Looper.getMainLooper()), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter, permission, Handler(Looper.getMainLooper()))
        }
        registered = true
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!registered) return
        registered = false
        context.unregisterReceiver(receiver)
    }
}
