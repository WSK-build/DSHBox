package com.dshbox.app.di

import android.content.Context
import com.dshbox.app.bridge.BridgeRouter
import com.dshbox.app.bridge.api.BridgeApi
import com.dshbox.app.runtime.RuntimeUpdateManager
import com.dshbox.app.sandbox.DefaultSandboxManager
import com.dshbox.app.sandbox.SandboxConfig
import com.dshbox.terminal.DshTerminalManager

/**
 * Creates the MVP graph. BridgeApi is stubbed in Phase 0/1; the first working
 * implementation should live in the sandbox-manager module and be wired here.
 */
object ServiceLocator {
    fun createAppContainer(context: Context): AppContainer {
        val sandboxConfig = SandboxConfig(
            appFilesDir = context.filesDir,
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
        )
        val sandboxManager = DefaultSandboxManager(sandboxConfig)
        val noopBridge = object : BridgeApi {
            override suspend fun getCurrentWorkspace(): String = "/root/projects"
            override suspend fun setCurrentWorkspace(path: String) = Unit
            override suspend fun listWorkspaces(): List<String> = emptyList<String>()
            override suspend fun createWorkspace(path: String) = Unit
            override suspend fun listDirectory(path: String): List<com.dshbox.app.bridge.model.FileEntry> = emptyList()
            override suspend fun stat(path: String) =
                com.dshbox.app.bridge.model.FileEntry(path, path, true, null, null)
            override suspend fun readText(path: String) =
                com.dshbox.app.bridge.model.FileContent(path, "")
            override suspend fun writeText(path: String, content: String) = Unit
            override suspend fun createDirectory(path: String) = Unit
            override suspend fun delete(path: String) = Unit
            override suspend fun move(from: String, to: String) = Unit
            override suspend fun copy(from: String, to: String) = Unit
            override suspend fun execute(request: com.dshbox.app.bridge.model.CommandRequest) =
                com.dshbox.app.bridge.model.CommandResult(null, "", "bridge not implemented", timedOut = false)
            override suspend fun cancel(processId: Long) = Unit
            override suspend fun listProcesses(): List<Long> = emptyList<Long>()
            override suspend fun killProcess(processId: Long) = Unit
            override suspend fun showNotification(title: String, body: String) = Unit
            override suspend fun clipboardRead(): String = ""
            override suspend fun clipboardWrite(text: String) = Unit
        }
        val bridgeRouter = BridgeRouter(delegate = noopBridge, expectedDshToken = "")
        val overlayAssets = listOf(
            "vim_9.1.1230-2_arm64.deb",
            "vim-common_9.1.1230-2_all.deb",
            "vim-runtime_9.1.1230-2_all.deb",
            "xxd_9.1.1230-2_arm64.deb",
            "htop_3.4.1-5_arm64.deb",
            "libgpm2_1.20.7-11+b2_arm64.deb",
            "libsodium23_1.0.18-1+deb13u1_arm64.deb",
        )
        val overlayInstaller = com.dshbox.terminal.TerminalOverlayInstaller(
            assetBridge = { name, target ->
                try {
                    context.assets.open("terminal-packages/$name").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    true
                } catch (t: Throwable) {
                    android.util.Log.e("DshOverlay", "asset copy failed: $name", t)
                    false
                }
            },
            assetNames = overlayAssets,
        )
        val dshTerminalManager = DshTerminalManager(
            pathsProvider = {
                com.dshbox.terminal.TerminalPathsResolver.resolve(
                    appFilesDir = sandboxConfig.appFilesDir,
                    nativeLibraryDir = sandboxConfig.nativeLibraryDir,
                )
            },
            overlayInstaller = overlayInstaller,
        )
        val runtimeUpdateManager = RuntimeUpdateManager(context, sandboxManager)
        return AppContainer(context, sandboxConfig, sandboxManager, bridgeRouter, dshTerminalManager, runtimeUpdateManager)
    }
}
