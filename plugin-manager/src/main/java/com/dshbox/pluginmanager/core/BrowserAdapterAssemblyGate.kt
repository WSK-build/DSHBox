package com.dshbox.pluginmanager.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes Browser package staging, profile probing, install, and removal in this app process. */
object BrowserAdapterAssemblyGate {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
