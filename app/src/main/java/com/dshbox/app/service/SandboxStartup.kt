package com.dshbox.app.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.dshbox.app.runtime.DeveloperAccessBootstrap
import com.dshbox.app.sandbox.SandboxManager
import dshbox.adapter.dshboxRelayConfig
import interlock.relay.core.runtime.RelayRuntime
import interlock.relay.core.spi.RelayPathPolicy
import interlock.relay.core.storage.RelayPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "SandboxStartup"

/** 资产就绪屏障的上限：正常路径只写几个小文件；超时仍放行，避免拖死 onboarding。 */
private const val RELAY_ASSETS_TIMEOUT_MS = 10_000L
private const val RELAY_ASSETS_POLL_MS = 50L

/**
 * 启动沙箱的唯一入口：先把助手通道铺好，再起沙箱。
 *
 * guest 侧把宿主通道当成 `/opt/pilot`；DSH 的 mobile-pilot 插件在加载时
 * 通过 `resolvePack` 找这个挂载点，找不到就跳过实时流路由。通道晚于 DSH
 * 启动时，面板会一直 404，直到 DSH 重启。`SandboxService` 和 onboarding 的
 * [com.dshbox.app.runtime.OnlineRuntimeImportManager] 都必须走这里，避免两条
 * 启动路径再次漂移。
 *
 * `RelayRuntime.start()` 只发起装配、立即返回；这里再等 `bin/pilot` 与
 * `capabilities.json` 落盘，确保随后启动的 DSH 在 apply 时能解析到挂载包。
 * 超时仍继续启动沙箱：预览可能降级，但不能因此把用户卡在 onboarding。
 *
 * [beforeSandbox] 用于保留 `SandboxService` 原有的开发者模式复检顺序；它在
 * 通道资产就绪之后、沙箱启动之前执行。
 */
internal suspend fun startSandboxWithRelay(
    context: Context,
    sandboxManager: SandboxManager,
    beforeSandbox: suspend () -> Unit = {},
) = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val config = dshboxRelayConfig(appContext)
    val paths = RelayPaths(
        appContext.filesDir,
        config.pathPolicy ?: RelayPathPolicy.BuiltIn,
    )
    runStartupSequence(
        startRelay = { RelayRuntime.start(appContext, config) },
        awaitRelayAssets = { awaitRelayAssets(paths) },
        beforeSandbox = beforeSandbox,
        startSandbox = { sandboxManager.startSandbox() },
    )
}

/**
 * 只保证助手通道资产就绪，不动沙箱。给 `restartSandbox` 这类“沙箱由别处重启”
 * 的路径用：通道若已被 stop，重启前必须先重新铺 `/opt/pilot`。
 */
internal suspend fun ensureRelayReady(context: Context): Boolean = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val config = dshboxRelayConfig(appContext)
    val paths = RelayPaths(
        appContext.filesDir,
        config.pathPolicy ?: RelayPathPolicy.BuiltIn,
    )
    RelayRuntime.start(appContext, config)
    awaitRelayAssets(paths)
}

/** 启动顺序的唯一实现；拆出来让 JVM 单测能锁死 relay → assets → before → sandbox。 */
internal suspend fun runStartupSequence(
    startRelay: () -> Unit,
    awaitRelayAssets: suspend () -> Boolean,
    startSandbox: suspend () -> Unit,
    beforeSandbox: suspend () -> Unit = {},
): Boolean {
    startRelay()
    val assetsReady = awaitRelayAssets()
    beforeSandbox()
    startSandbox()
    return assetsReady
}

private suspend fun awaitRelayAssets(paths: RelayPaths): Boolean {
    val deadline = SystemClock.elapsedRealtime() + RELAY_ASSETS_TIMEOUT_MS
    while (true) {
        if (paths.clientScript.isFile && paths.capabilitiesFile.isFile) return true
        if (SystemClock.elapsedRealtime() >= deadline) {
            Log.w(
                TAG,
                "relay assets not ready after ${RELAY_ASSETS_TIMEOUT_MS}ms; " +
                    "starting sandbox anyway (preview may degrade until DSH restarts)",
            )
            return false
        }
        delay(RELAY_ASSETS_POLL_MS)
    }
}

/**
 * 运行环境层被替换后复检开发者模式。与 `SandboxService` 原有逻辑同形；
 * onboarding 也调用，避免“层换了、adb wrapper 没了”只在服务路径被发现。
 */
internal suspend fun recheckDeveloperAccess(
    context: Context,
    sandboxManager: SandboxManager,
    runtimeDir: File,
) {
    runCatching {
        DeveloperAccessBootstrap(context.applicationContext, sandboxManager, runtimeDir)
            .ensureAdbReady()
    }.onFailure { Log.w(TAG, "developer access recheck before sandbox start failed", it) }
}
