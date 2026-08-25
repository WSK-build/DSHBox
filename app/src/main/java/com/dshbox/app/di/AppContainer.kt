package com.dshbox.app.di

import android.content.Context
import com.dshbox.app.bridge.BridgeRouter
import com.dshbox.app.runtime.RuntimeUpdateManager
import com.dshbox.app.sandbox.SandboxConfig
import com.dshbox.app.sandbox.SandboxManager
import com.dshbox.terminal.DshTerminalManager

/**
 * Minimal manual DI container. Replaced by Hilt only if the project grows.
 */
class AppContainer(
    val appContext: Context,
    val sandboxConfig: SandboxConfig,
    val sandboxManager: SandboxManager,
    val bridgeRouter: BridgeRouter,
    val dshTerminalManager: DshTerminalManager,
    val runtimeUpdateManager: RuntimeUpdateManager,
)
