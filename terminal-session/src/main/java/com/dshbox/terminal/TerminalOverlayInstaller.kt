package com.dshbox.terminal

import android.util.Log
import java.io.File

/**
 * One-shot installer for the terminal tool packages (vim / htop and their
 * dependencies) bundled as .deb files in the APK assets.
 *
 * Flow: the app layer copies the assets into a staging dir inside the rootfs
 * via [AssetBridge]; the guest-side init snippet (see
 * [TerminalCommandFactory.sandboxLoginShell]) unpacks them with `dpkg -x` —
 * deliberately bypassing the dpkg database, whose /var/lib/dpkg writes fail
 * under the fake-root sandbox because the kernel checks the app's REAL uid
 * against on-disk ownership that proot cannot fake.
 *
 * The completion marker lives inside the rootfs (/root/.dsh-overlay-ready):
 * the install runs exactly once per rootfs, self-heals when a different
 * runtime bundle is activated, and is skipped on every later session.
 */
class TerminalOverlayInstaller(
    private val assetBridge: AssetBridge,
    private val assetNames: List<String>,
) {

    /** App-layer bridge so this module never touches android.content.res. */
    fun interface AssetBridge {
        /** Copies APK asset [name] to [target]; returns false when missing. */
        fun copyAssetTo(name: String, target: File): Boolean
    }

    /** Returns the guest-side init snippet when an install is pending, else null. */
    @Synchronized
    fun prepare(paths: TerminalPaths): String? {
        // Self-healing guard: the presence of the actual tools decides — no
        // marker file (a stale marker once produced a no-op install). If a
        // previous run half-failed, this re-runs; extraction is idempotent.
        val vim = File(paths.debianRootfs, "usr/bin/vim")
        val vimBasic = File(paths.debianRootfs, "usr/bin/vim.basic")
        val htop = File(paths.debianRootfs, "usr/bin/htop")
        if ((vim.isFile || vimBasic.isFile) && htop.isFile) return null

        val stage = File(paths.debianRootfs, STAGE_DIR)
        stage.deleteRecursively()
        stage.mkdirs()

        var copied = 0
        for (name in assetNames) {
            val target = File(stage, name)
            val ok = assetBridge.copyAssetTo(name, target) && target.length() > 0
            Log.i(TAG, "asset $name copied=$ok size=${target.length()}")
            if (ok) copied++
        }
        Log.i(TAG, "overlay result: copied=$copied/${assetNames.size}")
        if (copied == 0) {
            stage.deleteRecursively()
            return null
        }
        return INIT_SNIPPET
    }

    companion object {
        private const val TAG = "DshOverlay"
        private const val STAGE_DIR = "root/dshpkgs"

        /** Unpacks every staged .deb straight into / (no dpkg database). Primary
         *  path pipes the deb's data tar through tar with --no-same-owner: under
         *  proot -0 dpkg believes it is root and its chown calls are denied by
         *  the kernel (real-uid checks), so plain `dpkg -x` fails on every
         *  package. `--no-same-owner` never chowns. The screen is cleared before
         *  the notice (also wipes the session-start linker notice some devices
         *  print); the app-side guard re-runs whenever vim/htop are missing. */
        internal const val INIT_SNIPPET =
            "ok=1; for f in /root/dshpkgs/*.deb; do " +
                "dpkg-deb --fsys-tarfile \$f | tar -x --no-same-owner -p -C / || " +
                "dpkg -x \$f / || ok=0; done; " +
                // The vim package ships /usr/bin/vim.basic; /usr/bin/vim is an
                // update-alternatives symlink created by the postinst script,
                // which plain extraction skips. Recreate it manually.
                "if [ -x /usr/bin/vim.basic ]; then ln -sf vim.basic /usr/bin/vim; fi; " +
                "rm -rf /root/dshpkgs; " +
                "clear; echo '[dsh] terminal packages installed (ok='\$ok')'; "
    }
}
