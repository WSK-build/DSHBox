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
        // 工具链缓存统一绑定到 /opt/dshbox-cache，应用侧目录在绑定前创建。
        paths.guestCacheDir?.let {
            it.mkdirs()
            add("--bind=${it.absolutePath}:/opt/dshbox-cache")
        }
        // 手机助手目录绑定到 /opt/pilot，入口脚本及信箱由 RelayRuntime.start 生成。
        // 绑定前创建应用侧目录；资产尚未生成时允许绑定空目录。
        paths.pilotDir?.let {
            it.mkdirs()
            add("--bind=${it.absolutePath}:/opt/pilot")
        }
        paths.browserDir?.let {
            if (it.isDirectory || it.mkdirs()) {
                add("--bind=${it.absolutePath}:/opt/browser")
            }
        }
        // 共享区：内建 DSHBox 与用户挂载 → guest `/root/share/<别名>`（与网页端 DSH、
        // guest 命令同一组挂载点）。目录缺失的条目跳过；挂载点先在 base 镜像里预建，
        // 不依赖 PRoot 补建多级目录。
        val shareBinds = paths.shareBinds.filter { (name, dir) -> name.isNotBlank() && dir.isDirectory }
        if (shareBinds.isNotEmpty()) {
            val guestShareRoot = File(File(paths.debianRootfs, "root"), "share").apply { mkdirs() }
            shareBinds.forEach { (name, dir) ->
                File(guestShareRoot, name).mkdirs()
                add("--bind=${dir.absolutePath}:/root/share/$name")
            }
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
