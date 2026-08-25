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
    /** Host directory bound to /root/projects inside the guest. */
    val workspaceBind: File,
    /** PROOT_TMP_DIR for the terminal role; created on demand. */
    val prootTmpDir: File,
    /** Writable home for the failsafe Android shell. */
    val failsafeHome: File,
    /** Writable TMPDIR for the failsafe Android shell. */
    val failsafeTmpDir: File,
)
