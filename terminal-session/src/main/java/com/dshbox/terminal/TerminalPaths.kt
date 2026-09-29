package com.dshbox.terminal

import java.io.File

/**
 * Filesystem locations the terminal session layer needs to spawn a shell.
 *
 * Plain data only: the session module never imports sandbox-manager types,
 * so the app layer maps its sandbox configuration onto this bundle.
 */
data class TerminalPaths(
    /** Host-side proot binary (must live under nativeLibraryDir on targetSdk 29+). */
    val prootBinary: File,
    /** proot loader helper used for 32-bit guests; required by proot env. */
    val prootLoader: File,
    /** Directory holding libproot.so/libtalloc.so/libandroid-shmem.so. */
    val nativeLibDir: File,
    /** Debian base-layer rootfs directory passed as --rootfs. */
    val debianRootfs: File,
    /** L1 node layer, bound at the guest /usr/local (Node/npm live here). */
    val nodeDir: File,
    /**
     * L2 DSH 层，绑定到 guest 的 `/opt/dshapp/runtime`。
     *
     * 与 app 启动网页端 DSH 时**同一个挂载点**，因此终端里跑的 `dsh`（官方 CLI：
     * web / headless / tui / plugin）与网页端用的是同一份 DSH、同一个 profile。
     * 缺层时为 null（例如只导入了 Linux 层），此时终端只是没有 dsh 命令。
     */
    val dshDir: File? = null,
    /**
     * 「Android 硬链接兼容垫片」宿主目录，绑定到 guest 的 `/opt/dshbox`。
     *
     * DSH 用 `link()` 作为"不可覆盖地发布文件"的原语（会话持久化、写工具、附件发布、
     * 插件安装），而 Android 的 app 数据分区拒绝硬链接 —— 终端里跑 `dsh` 必须与 app
     * 启动网页端一样预加载该垫片，否则这些链路会失败。见 `dshbox/link-shim.mjs`。
     */
    val dshShimDir: File? = null,
    /**
     * 「工具链缓存」宿主目录，绑定到 guest 的 `/opt/dshbox-cache`。
     *
     * 装 pnpm（`dsh plugin`）时用：pnpm 的 store 与 corepack 缓存默认都往 `$HOME` 写，
     * 前者落 base 层（不可见、清理扫不到）、后者落工作区；引到这个挂载点则两者都出工作区。
     */
    val guestCacheDir: File? = null,
    /**
     * 「手机助手」宿主目录，绑定到 guest 的 `/opt/pilot`。
     *
     * 与 app 启动网页端 DSH、执行 guest 命令时**同一个挂载点**：入口脚本
     * `bin/pilot`、能力清单与信箱目录都由宿主在启动时物化，绑上之后终端里才有
     * `pilot` 命令。缺目录时只是终端里没有这条命令，不该让整个终端退化。
     */
    val pilotDir: File? = null,
    /** Host directory bound to /root/projects inside the guest. */
    val workspaceBind: File,
    /** PROOT_TMP_DIR for the terminal role; created on demand. */
    val prootTmpDir: File,
    /** Writable home for the failsafe Android shell. */
    val failsafeHome: File,
    /** Writable TMPDIR for the failsafe Android shell. */
    val failsafeTmpDir: File,
)
