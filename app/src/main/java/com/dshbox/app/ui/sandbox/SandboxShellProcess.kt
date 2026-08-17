package com.dshbox.app.ui.sandbox

import android.content.Context
import java.io.File

/**
 * Starts the sandbox shell process (proot + Debian bash) for a terminal
 * session. Kept separate from the UI so the process construction stays
 * reviewable; the sandbox manager runs the same proot rootfs for DSH itself.
 */
internal fun createSandboxShell(context: Context): Process {

    val filesDir = context.filesDir.absolutePath
    val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
    val rootfs = "$filesDir/runtime/runtime-current/debian"
    val workspace = "$filesDir/user-data"
    val prootBinary = "$nativeLibraryDir/libproot.so"

    val processBuilder = ProcessBuilder(
        prootBinary,
        "--rootfs=$rootfs",
        "--bind=/system",
        "--bind=/apex",
        "--bind=/proc",
        "--bind=/dev",
        "--bind=$workspace:/root/projects",
        "--cwd=/root",
        "--kill-on-exit",
        "/system/bin/sh", "-c",
        "exec /usr/bin/bash",
    )
    processBuilder.redirectErrorStream(true)

    val prootTmpDir = File(filesDir, "runtime/runtime-current/tmp").apply { mkdirs() }.absolutePath
    val bundledLoader = File(nativeLibraryDir, "libproot-loader.so")
    val fallbackLoader = File(filesDir, "runtime/runtime-current/android-side/libexec/proot/loader")
    val prootLoader = if (bundledLoader.isFile) bundledLoader.absolutePath else fallbackLoader.absolutePath

    processBuilder.environment()["LD_LIBRARY_PATH"] = nativeLibraryDir
    processBuilder.environment()["PROOT_TMP_DIR"] = prootTmpDir
    processBuilder.environment()["PROOT_LOADER"] = prootLoader
    // Neutral prompt regardless of any inherited environment PS1.
    processBuilder.environment()["PS1"] = "dsh@dsh-sandbox:\\w\\$ "

    return processBuilder.start()
}
