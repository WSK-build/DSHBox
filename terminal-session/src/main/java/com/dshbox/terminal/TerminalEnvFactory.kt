package com.dshbox.terminal

/**
 * Builds the child process environment.
 *
 * The pty child calls clearenv() before putenv()-ing exactly what we pass in,
 * so both sets below must be self-contained: anything missing (PATH, TERM,
 * LD_LIBRARY_PATH...) shows up as a black screen or "command not found".
 */
object TerminalEnvFactory {

    /** Environment for the proot-wrapped Debian login shell. */
    fun sandboxEnv(paths: TerminalPaths): Array<String> = arrayOf(
        // Host side: required by proot itself.
        "LD_LIBRARY_PATH=${paths.nativeLibDir.absolutePath}",
        "PROOT_LOADER=${paths.prootLoader.absolutePath}",
        "PROOT_TMP_DIR=${paths.prootTmpDir.apply { mkdirs() }.absolutePath}",
        // Guest side: inherited by bash inside the rootfs.
        "HOME=/root",
        "USER=root",
        "LOGNAME=root",
        // PATH 头部是 DSH CLI 的包装脚本目录：`dsh` 不是 npm 全局安装的，而是 app 写进
        // 工作区的一个小包装脚本（见 SandboxService.provisionDshCliShim），因此必须显式
        // 加到 PATH 才能直接敲 `dsh`。
        "PATH=/root/projects/.dsh/dshbox/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin",
        "SHELL=/usr/bin/bash",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "COLORTERM=truecolor",
        "TMPDIR=/tmp",
        "PWD=/root",
        // DSH 官方 CLI 需要的三件事，取值与 app 启动网页端 DSH 时**完全一致**
        // （见 DefaultSandboxManager.buildProotEnv 的层 env 替换表）：
        // profile 在 $DSH_HOME/profiles 下，否则 `dsh` 找不到 web/tui/headless 模板。
        "DSH_HOME=/root/projects/.dsh",
        "DSH_BIN=/opt/dshapp/runtime/node_modules/@deepseek-ai/dsh/lib/bin.js",
        // 非交互式会话没有审批 UI：与 app 一致走完全放行模式，否则 CLI 会卡在等审批。
        "DSH_PERMISSION_MODE=danger-full-access",
    )

    /** Environment for the failsafe Android shell (no clearenv-hostile deps). */
    fun failsafeEnv(paths: TerminalPaths): Array<String> = arrayOf(
        "LD_LIBRARY_PATH=${paths.nativeLibDir.absolutePath}",
        "HOME=${paths.failsafeHome.absolutePath}",
        "PATH=/system/bin:/system/xbin:/vendor/bin",
        "SHELL=/system/bin/sh",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "TMPDIR=${paths.failsafeTmpDir.absolutePath}",
        "PWD=${paths.failsafeHome.absolutePath}",
    )
}
