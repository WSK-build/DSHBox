package com.dshbox.terminal

import java.io.File

/**
 * Resolves [TerminalPaths] from the app's file layout.
 *
 * The layout mirrors the runtime bundle convention used by the sandbox:
 * `<filesDir>/runtime/runtime-current/{debian,android-side}` with bundled
 * proot binaries preferred from the APK's nativeLibraryDir.
 */
object TerminalPathsResolver {

    fun resolve(appFilesDir: File, nativeLibraryDir: String?): TerminalPaths? {
        val runtimeCurrent = File(File(appFilesDir, "runtime"), "runtime-current")
        // Layered layout: base is the L0 rootfs (Node is a separate L1 layer
        // bound at /usr/local). Backward-compat: if a legacy single "debian"
        // rootfs exists, fall back to it as the rootfs.
        val baseLayer = File(runtimeCurrent, "base")
        val legacyDebian = File(runtimeCurrent, "debian")
        val rootfs = when {
            baseLayer.isDirectory -> baseLayer
            legacyDebian.isDirectory -> legacyDebian
            else -> return null
        }
        val nodeLayer = File(runtimeCurrent, "node")

        val androidSide = File(runtimeCurrent, "android-side")
        val nativeLib = nativeLibraryDir?.let(::File)

        fun bundledOrFallback(name: String, fallback: File): File {
            val bundled = nativeLib?.let { File(it, name) }
            return if (bundled != null && bundled.isFile) bundled else fallback
        }

        val prootBinary = bundledOrFallback("libproot.so", File(androidSide, "bin/proot"))
        if (!prootBinary.isFile) return null

        val libDir = nativeLib
            ?.takeIf { File(it, "libandroid-shmem.so").isFile }
            ?: File(androidSide, "lib")

        val prootTmpDir = File(File(appFilesDir, "runtime"), "tmp/terminal")
        val failsafeHome = File(appFilesDir, "home").apply { mkdirs() }
        val failsafeTmpDir = File(failsafeHome, "tmp").apply { mkdirs() }

        return TerminalPaths(
            prootBinary = prootBinary,
            prootLoader = bundledOrFallback("libproot-loader.so", File(androidSide, "libexec/proot/loader")),
            nativeLibDir = libDir,
            debianRootfs = rootfs,
            nodeDir = nodeLayer,
            workspaceBind = File(appFilesDir, "user-data"),
            prootTmpDir = prootTmpDir,
            failsafeHome = failsafeHome,
            failsafeTmpDir = failsafeTmpDir,
        )
    }
}
