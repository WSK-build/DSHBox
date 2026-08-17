package com.dshbox.app.di

import android.content.Context
import com.dshbox.app.bridge.BridgeRouter
import com.dshbox.app.bridge.api.BridgeApi
import com.dshbox.app.sandbox.DefaultSandboxManager
import com.dshbox.app.sandbox.SandboxConfig

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
        return AppContainer(context, sandboxConfig, sandboxManager, bridgeRouter)
    }
}
