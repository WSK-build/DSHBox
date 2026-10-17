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

    fun resolve(
        appFilesDir: File,
        nativeLibraryDir: String?,
        /** 共享区绑定清单（内建 DSHBox + 用户挂载）；不可由 filesDir 推导，由 app 层注入。 */
        shareBinds: List<Pair<String, File>> = emptyList(),
    ): TerminalPaths? {
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
        // DSH 层与硬链接兼容层可选；缺失时仅省略对应绑定，其余终端环境照常装配。
        val dshLayer = File(runtimeCurrent, "dsh").takeIf { it.isDirectory }
        val dshShim = File(appFilesDir, "dshbox").takeIf { it.isDirectory }
        // 工具链缓存目录在 TerminalCommandFactory 绑定时创建，此处无需检查是否已存在。
        val guestCache = File(appFilesDir, "guest-cache")
        // 手机助手挂载到 /opt/pilot；应用侧目录在 TerminalCommandFactory 绑定时创建。
        val pilotDir = File(appFilesDir, "pilot")

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
            dshDir = dshLayer,
            dshShimDir = dshShim,
            guestCacheDir = guestCache,
            pilotDir = pilotDir,
            workspaceBind = File(appFilesDir, "user-data"),
            prootTmpDir = prootTmpDir,
            failsafeHome = failsafeHome,
            failsafeTmpDir = failsafeTmpDir,
            browserDir = File(appFilesDir, "browser-tools"),
            shareBinds = shareBinds,
        )
    }
}
