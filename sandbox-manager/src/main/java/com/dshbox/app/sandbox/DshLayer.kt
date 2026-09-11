package com.dshbox.app.sandbox

import android.util.Log
import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.UiText
import com.dshbox.app.sandbox.R
import com.dshbox.app.common.Versions
import java.io.File

/**
 * Manages the standalone DSH layer at `runtime-current/dsh`.
 *
 * DSH is a SEPARATE product from the runtime body (base/node/android-side): it
 * is owned by the APK baseline + the app's "Update DSH" flow, never by the
 * runtime bundle import. This class only ever touches `runtime-current/dsh`
 * (and `runtime-current/previous/dsh`); it MUST NOT touch user-data or
 * user-data/.dsh (hard red line).
 *
 * On-device layout (assembled into the guest as the PRoot DSH layer):
 *   runtime-current/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js
 *   runtime-current/dsh/.dshbox/version
 *
 * **DSH 源码永不被改写**（1.3.1 起）：本类只做「解包 / 校验 / 原子换入 / 记录版本」。
 * 曾经的 Android 硬链接兼容补丁会就地改写 DSH 的 JS 文件，但补丁锚点必须与上游
 * 逐字节匹配，上游一重构就整块静默跳过；现在该职责已移交**运行期垫片**
 * （启动时 `--import` 预加载，见 [SandboxProcessRunner.buildProotDshCommand]），
 * 因此本类对任何版本的 DSH 都保持形态无关。
 */
class DshLayer(
    private val runtimeDir: File,
    private val bundleManager: BundleManager,
) {
    companion object {
        private const val TAG = "DshLayer"
        const val VERSION_FILE = ".dshbox/version"
        const val PROFILE_VERSION_FILE = "package.json"
        const val DSHPK_GUEST_PATH = "node_modules/@deepseek-ai/dsh/lib/bin.js"
    }

    /** The live DSH layer dir (bound into the guest at /opt/dshapp/runtime). */
    fun dshDir(): File = File(runtimeDir, "dsh")

    /** The previous DSH layer held before a successful replace (single copy). */
    fun previousDshDir(): File = File(File(runtimeDir, "previous"), "dsh")

    /** True when a complete DSH layer is installed. */
    fun isInstalled(): Boolean {
        val bin = File(dshDir(), DSHPK_GUEST_PATH)
        val ver = File(dshDir(), VERSION_FILE)
        return bin.isFile || ver.isFile
    }

    /** Current installed DSH version (from `.dshbox/version`, else package.json), or null. */
    fun installedVersion(): String? {
        val vf = File(dshDir(), VERSION_FILE)
        if (vf.isFile) return vf.readText().trim().takeIf { it.isNotEmpty() }
        return versionFromPackage(dshDir())
    }

    /**
     * Installs (or replaces) the DSH layer from [bundle] (a tar.gz / tar.zst /
     * plain tar of the DSH runtime content) with version arbitration:
     *   - if [newVersion] is provided, the currently installed version is newer
     *     (or equal) and [allowDowngrade] is false, keep the installed copy
     *     (installed-newer wins) -> changed=false;
     *   - otherwise the bundle is extracted into a STAGING directory first
     *     (1.1.0, M4): the staged tree is shape-validated (bin.js present) and
     *     its version discovered BEFORE the live layer is touched; only then the
     *     old layer moves to previous/dsh (single copy) and staging renames into
     *     dsh/. A corrupt or WRONG file (e.g. a runtime zip's base.tar.zst picked
     *     by mistake) can therefore never leave a half-extracted dsh/ behind —
     *     the 1.0.0 code extracted straight into dsh/ and its failure path could
     *     not roll back a partial extraction.
     *
     * Only [dshDir], [previousDshDir] and the staging dir are touched. Returns
     * whether a change was applied and the resulting version.
     */
    suspend fun installFromBundle(
        bundle: File,
        expectedSha256: String?,
        newVersion: String?,
        allowDowngrade: Boolean = false,
    ): AppResult<DshUpdateOutcome> {
        if (!bundle.isFile) {
            return AppResult.Failure(AppError("DSH_BUNDLE_NOT_FOUND", "dsh bundle not found: ${bundle.absolutePath}"))
        }
        val current = installedVersion()
        if (!allowDowngrade && current != null && newVersion != null && compareVersions(current, newVersion) >= 0) {
            // Installed is the same or newer; keep it (APK/newer must not downgrade).
            Log.i(TAG, "dsh update skipped: installed $current >= incoming $newVersion")
            return AppResult.Success(DshUpdateOutcome(version = current, changed = false))
        }
        if (expectedSha256 != null && !bundleManager.verifySha256(bundle, expectedSha256)) {
            return AppResult.Failure(AppError("DSH_SHA256_MISMATCH", "dsh bundle failed SHA-256 verification"))
        }

        val previous = previousDshDir()
        val dsh = dshDir()
        val staging = File(runtimeDir, "dsh-staging")
        try {
            // Stage 1: extract + validate AWAY from the live layer.
            staging.deleteRecursively()
            staging.mkdirs()
            when (val r = bundleManager.extractTarGz(bundle, staging)) {
                is AppResult.Failure -> return r
                is AppResult.Success -> Unit
            }
            if (!File(staging, DSHPK_GUEST_PATH).isFile) {
                staging.deleteRecursively()
                return AppResult.Failure(
                    AppError(
                        "DSH_BUNDLE_INVALID",
                        "Selected file is not a valid DSH layer package (missing node_modules/@deepseek-ai/dsh/lib/bin.js)",
                        userMessage = UiText.Res(R.string.dsh_bundle_invalid),
                    ),
                )
            }
            val discovered = versionFromPackage(staging)
            // Stage 2: atomic swap old <-> new (same filesystem, rename first).
            if (previous.exists()) previous.deleteRecursively()
            if (dsh.exists()) {
                // Old object -> previous (guaranteed single previous copy).
                if (!dsh.renameTo(previous)) {
                    Log.w(TAG, "could not move dsh to previous; deleting old instead")
                    dsh.deleteRecursively()
                }
            }
            if (!staging.renameTo(dsh)) {
                if (!staging.copyRecursively(dsh, overwrite = true)) {
                    // Restore the previous layer so the next boot still works.
                    if (!dsh.exists() && previous.exists()) previous.renameTo(dsh)
                    return AppResult.Failure(AppError("DSH_INSTALL_FAILED", "Cannot place new DSH layer (both rename and copy failed)",
                        userMessage = UiText.Res(R.string.dsh_install_failed)))
                }
                staging.deleteRecursively()
            }
            // Stage 3: version record.
            //
            // Android 硬链接兼容**不在这里做**（1.3.1 起）：曾经的做法是改写 DSH 的
            // JS 源码（link -> rename/copyFile），但补丁锚点必须与上游逐字节匹配，
            // 上游每次重构（插入一个 import、拆分发布点）都会让整块补丁静默跳过。
            // 现在改由**运行期垫片**承担：启动 DSH 时以 `--import` 预加载
            // link-shim.mjs 替换 node:fs/promises 的 link，DSH 源码一个字节都不改。
            // 见 SandboxProcessRunner.buildProotDshCommand / Constants.DSH_LINK_SHIM_GUEST_PATH。
            //
            // ⚠️ 垫片的已知限制（勿误以为全量兜底）：它**只替换异步的
            // `node:fs/promises`.link**，不覆盖 `fs.linkSync` 与回调版 `fs.link`。
            // 当前 DSH 的三条链路（会话/写工具/附件）均使用异步具名导入，故够用；
            // 若上游改用同步版，会在真机上重新出现硬链接 EACCES 且**无任何告警**。
            // 排查入口：在解出的层里 `grep -rn "linkSync" node_modules/@deepseek-ai/<包>/lib/`。
            // 完整边界说明见 link-shim.mjs 顶部注释与 DSH_COMPAT_NOTES.md #1。
            val version = newVersion?.takeIf { it.isNotBlank() } ?: discovered ?: "unknown"
            runCatching {
                val vf = File(dsh, VERSION_FILE)
                vf.parentFile?.mkdirs()
                vf.writeText(version)
            }.onFailure { Log.w(TAG, "write dsh version file failed: ${it.message}") }
            Log.i(TAG, "dsh layer updated to $version")
            return AppResult.Success(DshUpdateOutcome(version = installedVersion(), changed = true))
        } catch (t: Throwable) {
            Log.e(TAG, "dsh install failed: ${t.message}", t)
            // Best-effort restore of the previous layer so the next boot still works.
            if (!dsh.exists() && previous.exists()) runCatching { previous.renameTo(dsh) }
            return AppResult.Failure(AppError("DSH_INSTALL_FAILED", "dsh install failed: ${t.message}"))
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun versionFromPackage(dshDirRoot: File): String? {
        // prefer the REAL product version from
        // node_modules/@deepseek-ai/dsh/package.json; the layer-root package.json
        // is only a build stub. Both are parsed BOM-tolerantly — the shipped stub
        // starts with a UTF-8 BOM (EF BB BF) and Android's org.json throws on it,
        // which made every offline import record version "unknown" (and the next
        // boot then re-provisioned the bundled layer OVER the user's import).
        val candidates = listOf(
            File(dshDirRoot, "node_modules/@deepseek-ai/dsh/package.json"),
            File(dshDirRoot, PROFILE_VERSION_FILE),
        )
        for (pkg in candidates) {
            if (!pkg.isFile) continue
            val v = runCatching {
                val text = pkg.readText().trimStart('\uFEFF')
                org.json.JSONObject(text).optString("version").takeIf { it.isNotEmpty() }
            }.getOrElse {
                Log.w(TAG, "cannot read ${pkg.name} version: ${it.message}")
                null
            }
            if (v != null) return v
        }
        return null
    }

    /**
     * Best-effort semantic-ish version comparison. Kept for source compatibility;
     * the implementation lives in common [Versions] (1.1.0, M6 — was duplicated
     * here and in RuntimeUpdateManager with identical bodies).
     */
    fun compareVersions(a: String, b: String): Int = Versions.compare(a, b)
}

/** Outcome of a DSH update attempt. */
data class DshUpdateOutcome(
    val version: String?,
    val changed: Boolean,
)
