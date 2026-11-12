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
        // PATH 首位是应用生成的 DSH CLI 包装脚本目录（既有约定，`dsh` 由此命中）；
        // 能力模块追加的目录紧随其后。
        "PATH=${buildGuestPath(paths)}",
        "SHELL=/usr/bin/bash",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "COLORTERM=truecolor",
        // 登录 shell 跳过 `/etc/profile`（见 TerminalCommandFactory 的 `--noprofile`）：
        // 那一行 `PATH="..."` 会把上面注入的目录（含能力的 `/opt/elevation/bin`）整段覆盖，
        // 而基础镜像不可改。跳过 profile 后提示符由这里给出，取值与 profile 原值一致。
        "PS1=\\u@\\h:\\w\\$ ",
        "TMPDIR=/tmp",
        "PWD=/root",
        // DSH 官方 CLI 需要的三件事，取值与 app 启动网页端 DSH 时**完全一致**
        // （见 DefaultSandboxManager.buildProotEnv 的层 env 替换表）：
        // profile 在 $DSH_HOME/profiles 下，否则 `dsh` 找不到 web/tui/headless 模板。
        "DSH_HOME=${paths.dshHomeGuest}",
        "DSH_BIN=/opt/dshapp/runtime/node_modules/@deepseek-ai/dsh/lib/bin.js",
        // 非交互式会话没有审批 UI：与 app 一致走完全放行模式，否则 CLI 会卡在等审批。
        "DSH_PERMISSION_MODE=danger-full-access",
    )

    /**
     * 构造 guest PATH：CLI 包装脚本目录保持**首位**（`android-root` 等 shim 在此，
     * 不会被能力目录遮蔽），其后是能力模块追加的目录，最后是系统路径。
     *
     * 追加目录来自宿主注入（[TerminalPaths.extraPathEntries]），本模块不解释其语义。
     */
    private fun buildGuestPath(paths: TerminalPaths): String {
        val head = paths.dshboxBinGuest
        val extra = paths.extraPathEntries.filter { it.isNotBlank() }
        val tail = listOf(
            "/usr/local/sbin", "/usr/local/bin", "/usr/sbin", "/usr/bin", "/sbin", "/bin",
            "/system/bin", "/system/xbin",
        )
        return (listOf(head) + extra + tail).joinToString(":")
    }

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
