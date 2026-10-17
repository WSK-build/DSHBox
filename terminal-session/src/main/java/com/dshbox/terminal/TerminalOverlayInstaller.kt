package com.dshbox.terminal

import android.util.Log
import java.io.File
import java.nio.file.Files

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
 * The presence of the unpacked tools decides: the install runs when any of them
 * is missing (a fresh rootfs, or one assembled before a tool was added) and is
 * skipped on every later session.
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
        // Probe actual tools rather than a marker file; retry incomplete extraction.
        val vim = present(paths.debianRootfs, "usr/bin/vim")
        val vimBasic = present(paths.debianRootfs, "usr/bin/vim.basic")
        val htop = present(paths.debianRootfs, "usr/bin/htop")
        // vi 为 vim 的替代名，缺失时重新装配 overlay。
        val vi = present(paths.debianRootfs, "usr/bin/vi")
        // 常用工具任一缺失即重新装配 overlay。
        val toolsOk = TOOL_PROBES.all { present(paths.debianRootfs, it) }
        // PATH 片段参与装配完整性检查，缺失时重新生成。
        val pathSnippet = present(paths.debianRootfs, PATH_SNIPPET_REL)
        if ((vim || vimBasic) && htop && vi && toolsOk && pathSnippet) return null

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

        /**
         * 层内条目是否在位。不能用 `isFile()`：`update-alternatives` 建的链接
         * （`/usr/bin/vi -> /etc/alternatives/vi`）目标写的是 guest 绝对路径，
         * 宿主侧解析会落到宿主根上而必然落空，判定恒假会让 overlay 每次会话重跑。
         * 链接本身在位即算在位。
         */
        private fun present(root: File, rel: String): Boolean {
            val f = File(root, rel)
            return f.exists() || Files.isSymbolicLink(f.toPath())
        }

        /** 落到 guest rootfs 里的 PATH 片段（终端 `bash --login` 时由 /etc/profile.d 加载）。 */
        private const val PATH_SNIPPET_REL = "etc/profile.d/zz-dshbox-path.sh"

        /** 常用工具在位性探测，任一缺失时重新装配 overlay。 */
        private val TOOL_PROBES = listOf(
            "usr/bin/jq",
            "usr/bin/sqlite3",
            "usr/bin/patch",
            "usr/bin/strings",
            "usr/bin/nano",
            // 属主虚拟化工具与服务启停策略文件均参与装配完整性检查。
            "usr/bin/fakeroot-tcp",
            "usr/sbin/policy-rc.d",
        )

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
                // vim 的 postinst 还会用 update-alternatives 注册四个替代名（vi/editor/ex/view）。
                // 直接解包不执行 postinst，需在此注册替代名。
                // 注册同时会把记录写进 /var/lib/dpkg/alternatives/，让 dpkg 侧账本也完整。
                "for a in vi editor ex view; do " +
                "update-alternatives --install /usr/bin/\$a \$a /usr/bin/vim.basic 30 " +
                ">/dev/null 2>&1; done; " +
                "rm -rf /root/dshpkgs; " +
                // 登录 shell 的 /etc/profile 重置 PATH 后加载 profile.d，
                // 在该目录追加 DSH CLI 路径，避免被默认 PATH 覆盖。
                "mkdir -p /etc/profile.d; " +
                "printf '%s\n' 'export PATH=\"/root/projects/.dsh/dshbox/bin:\$PATH\"' " +
                "> /etc/profile.d/zz-dshbox-path.sh; " +
                // 容器通行做法：policy-rc.d 返回 101，使维护脚本里的服务启停被跳过。
                // 当前环境没有 systemd，跳过服务启停，避免维护脚本中断包事务。
                "mkdir -p /usr/sbin; " +
                "printf '%s\n' '#!/bin/sh' 'exit 101' > /usr/sbin/policy-rc.d; " +
                "chmod 755 /usr/sbin/policy-rc.d; " +
                "clear; echo '[dsh] terminal packages installed (ok='\$ok')'; "
    }
}
