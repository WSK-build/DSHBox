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
        // L2：DSH 层 + 其硬链接垫片。两者都可能缺——只导入 Linux 层时没有 DSH 层；
        // 垫片由 app 在启动时落盘，首次 bootstrap 之前可能尚未写入。缺了只是终端里
        // 没有 `dsh` 命令，不该让整个终端退化成受限 shell，所以用 takeIf。
        val dshLayer = File(runtimeCurrent, "dsh").takeIf { it.isDirectory }
        val dshShim = File(appFilesDir, "dshbox").takeIf { it.isDirectory }
        // 工具链缓存挂载点（pnpm store / corepack 缓存）。无条件给出：宿主目录在
        // TerminalCommandFactory 绑定时 mkdirs 创建（首次启动时它还不存在，用 takeIf
        // 会变成"第一次永远不绑"的鸡生蛋问题）。
        val guestCache = File(appFilesDir, "guest-cache")
        // 手机助手挂载点（guest 的 /opt/pilot）。目录名 "pilot" 与沙盒侧的
        // SandboxConfig.pilotEntryDir 同源（都是 filesDir 下的 pilot），因此终端侧
        // 独立推导一次即可，不必为此跨模块去取沙盒配置。与 guestCache 同理**不能**
        // 用 takeIf：宿主目录由 TerminalCommandFactory 绑定时 mkdirs 创建，
        // 用 takeIf 会变成"第一次永远不绑"的鸡生蛋问题。
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
        )
    }
}
