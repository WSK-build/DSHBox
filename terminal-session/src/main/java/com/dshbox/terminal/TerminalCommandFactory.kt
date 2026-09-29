package com.dshbox.terminal

import java.io.File

/**
 * Builds the argv passed to the pty child process.
 *
 * JNI.createSubprocess() execs `execvp(shellPath, args)`, so the first entry
 * of every argv here MUST repeat the program path (argv[0]).
 */
object TerminalCommandFactory {

    /**
     * Interactive login shell inside the Debian rootfs.
     *
     * Normal form (overlay already installed) is proot-distro's canonical
     * direct exec of the guest shell: `/usr/bin/bash --login`. The guest bash
     * uses its glibc loader, so no Android linker configuration lookup happens
     * inside the guest (this avoids the "/linkerconfig" WARNING that the host
     * /system/bin/sh wrapper used to trigger).
     *
     * When [initSnippet] is provided (first session after an overlay install)
     * the legacy wrapper form runs the snippet first, then `clear`s the screen
     * before handing over to bash.
     */
    fun sandboxLoginShell(paths: TerminalPaths, initSnippet: String? = null, extraBinds: List<String> = emptyList()): List<String> = buildList {
        add(paths.prootBinary.absolutePath)
        add("--kill-on-exit")
        // Fake root inside the guest: dpkg/apt-style tooling expects euid 0.
        // PRoot intercepts the id syscalls; on-disk ownership is unaffected.
        add("-0")
        add("--rootfs=${paths.debianRootfs.absolutePath}")
        add("--bind=/system")
        add("--bind=/apex")
        add("--bind=/proc")
        add("--bind=/dev")
        // L1 node layer: Node is a separate layer bound at the guest /usr/local
        // (so node/npm on PATH).
        if (paths.nodeDir.isDirectory) add("--bind=${paths.nodeDir.absolutePath}:/usr/local")
        // L2 dsh layer + hardlink shim, bound at the SAME guest paths the app uses
        // to boot the web DSH (`/opt/dshapp/runtime`, `/opt/dshbox`). Without these
        // the terminal is a Debian shell with node but **no dsh**: the official CLI
        // (web / headless / tui / plugin) lives in the dsh layer, and DSH's link()
        // primitives fail on Android's app-data filesystem unless the shim is
        // preloaded. `dsh` itself is provided by a wrapper the app writes into the
        // workspace (see SandboxService.provisionDshCliShim).
        paths.dshDir?.takeIf { it.isDirectory }?.let { add("--bind=${it.absolutePath}:/opt/dshapp/runtime") }
        paths.dshShimDir?.takeIf { it.isDirectory }?.let { add("--bind=${it.absolutePath}:/opt/dshbox") }
        // 工具链缓存挂载点：pnpm 的 store 与 corepack 缓存都指到这里，避免它们按默认位置
        // 落进工作区（可见）或 base 层（不可见且清理扫不到）。宿主目录由 app 侧 mkdirs。
        paths.guestCacheDir?.let {
            it.mkdirs()
            add("--bind=${it.absolutePath}:/opt/dshbox-cache")
        }
        // 手机助手入口：宿主侧目录绑到 guest 的 /opt/pilot。入口脚本 `bin/pilot` 与
        // 信箱目录由宿主在启动时物化（RelayRuntime.start），绑上之后终端里才敲得到
        // `pilot`。首次绑定时目标目录还不存在，故随绑定 mkdirs 创建——宿主尚未铺过
        // 资产时它就是个空目录，不影响会话。
        paths.pilotDir?.let {
            it.mkdirs()
            add("--bind=${it.absolutePath}:/opt/pilot")
        }
        // Shadow kernel-restricted /proc files (Android 16 SELinux denies
        // /proc/stat etc. to untrusted_app) with readable fabricated sources.
        addAll(extraBinds)
        // Silence the guest-side Android linker's one-line WARNING about
        // "/linkerconfig/ld.config.txt" when a host binary boots in the guest
        // view. Only bound where the host actually provides it (Android 11+).
        if (File("/linkerconfig").isDirectory) add("--bind=/linkerconfig")
        add("--bind=${paths.workspaceBind.absolutePath}:/root/projects")
        add("--cwd=/root")
        if (initSnippet != null) {
            add("/system/bin/sh")
            add("-c")
            add("${initSnippet.trimEnd()} exec /usr/bin/bash --login")
        } else {
            add("/usr/bin/bash")
            add("--login")
        }
    }

    /** Minimal Android shell used when the sandbox is unavailable. */
    fun failsafeShell(): List<String> = listOf(
        "/system/bin/sh",
    )
}
