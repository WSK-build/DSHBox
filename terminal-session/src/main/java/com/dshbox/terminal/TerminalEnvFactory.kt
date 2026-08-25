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
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin",
        "SHELL=/usr/bin/bash",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "COLORTERM=truecolor",
        "TMPDIR=/tmp",
        "PWD=/root",
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
