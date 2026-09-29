package com.dshbox.app.di

import android.content.Context
import android.os.PowerManager
import com.dshbox.app.R
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
            // npm 下载缓存宿主目录（guest 侧 bind 为 /root/.npm），
            // 随「应用缓存」可一键清理，不再占 base/root/.npm 的红线区空间。
            appCacheDir = context.cacheDir,
        )
        // 设备交互状态（唤醒且亮屏）交给健康循环：休眠期间 guest 会被整体冻结，
        // 端口在听但 HTTP 不应答，此时的探测失败是暂时状态，不该判为 DSH 宕机。
        val powerManager = context.getSystemService(PowerManager::class.java)
        val sandboxManager = DefaultSandboxManager(
            sandboxConfig,
            isDeviceInteractive = { powerManager?.isInteractive ?: true },
        )
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
            // 常用小工具（层里没有、也不该为此撑大 base 层）：JSON 处理、SQLite CLI、
            // 打补丁、二进制排障（strings/objdump/readelf）、简易编辑器。
            // 依赖闭包由 Debian trixie 索引解析得出（含 libjq1/libonig5/binutils 系列），
            // 逐个 sha256 校验后随 APK 分发。
            "jq_1.7.1-6+deb13u3_arm64.deb",
            "libjq1_1.7.1-6+deb13u3_arm64.deb",
            "libonig5_6.9.9-1+b1_arm64.deb",
            "libjansson4_2.14-2+b3_arm64.deb",
            "sqlite3_3.46.1-7+deb13u2_arm64.deb",
            "patch_2.8-2_arm64.deb",
            "nano_8.4-1+deb13u1_arm64.deb",
            "binutils_2.44-3_arm64.deb",
            "binutils-common_2.44-3_arm64.deb",
            "binutils-aarch64-linux-gnu_2.44-3_arm64.deb",
            "libbinutils_2.44-3_arm64.deb",
            "libgprofng0_2.44-3_arm64.deb",
            "libsframe1_2.44-3_arm64.deb",
            "libctf0_2.44-3_arm64.deb",
            "libctf-nobfd0_2.44-3_arm64.deb",
            // 包管理器侧：属主虚拟化（fakeroot 的 tcp 变体 + 其库）。
            // 伪造 root 下维护脚本的 chown/useradd 会被内核拒绝，导致带维护脚本的包必然半途而废；
            // 用 fakeroot 在虚拟层完成这些操作即可装完（sysv 变体依赖 SysV IPC，PRoot 下不可用，
            // 因此只用 tcp 变体）。解包不跑维护脚本，所以这两个 deb 直接落盘即可。
            "fakeroot_1.37.1.1-1_arm64.deb",
            "libfakeroot_1.37.1.1-1_arm64.deb",
        )
        val overlayInstaller = com.dshbox.terminal.TerminalOverlayInstaller(
            assetBridge = { name, target ->
                try {
                    context.assets.open("terminal-packages/$name").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    true
                } catch (t: Throwable) {
                    android.util.Log.e("DshBoxAssets", "asset copy failed: $name", t)
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
            // 会话标题由 app 层资源化生成（终端 1 / 受限 1 → Terminal 1 / Failsafe 1）。
            titleFormatter = { kind, order ->
                val res = when (kind) {
                    com.dshbox.terminal.DshTerminalManager.Kind.SANDBOX -> R.string.terminal_session_sandbox
                    com.dshbox.terminal.DshTerminalManager.Kind.FAILSAFE -> R.string.terminal_session_failsafe
                }
                context.getString(res, order)
            },
        )
        val runtimeUpdateManager = RuntimeUpdateManager(context, sandboxManager)
        val onlineRuntimeImportManager = com.dshbox.app.runtime.OnlineRuntimeImportManager(
            context, sandboxConfig, sandboxManager,
        )
        return AppContainer(context, sandboxConfig, sandboxManager, bridgeRouter, dshTerminalManager, runtimeUpdateManager, onlineRuntimeImportManager)
    }
}
