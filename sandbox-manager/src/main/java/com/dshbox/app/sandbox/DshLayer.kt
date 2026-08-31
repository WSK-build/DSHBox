package com.dshbox.app.sandbox

import android.util.Log
import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
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

        /** Convert literal "\t" placeholders (raw strings) to real tabs so blocks match the bundled JS. */
        private fun tabs(raw: String): String = raw.replace("\\t", "\t")

        val SESSION_IMPORT_OLD =
            """import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from "node:fs/promises";"""
        val SESSION_IMPORT_NEW =
            """import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rename, rm, stat, truncate } from "node:fs/promises";"""
        val SESSION_BLOCK_OLD = tabs("""\t\ttry {
\t\t\tawait link(tmp, finalPath);
\t\t\tlinked = true;
\t\t} finally {
\t\t\t/* v8 ignore next -- link failure is the TOCTOU/IO race guarded above; not reachable in test */
\t\t\tif (!linked) await rm(tmp, { force: true });
\t\t}""")
        val SESSION_BLOCK_NEW = tabs("""\t\ttry {
\t\t\tawait link(tmp, finalPath);
\t\t\tlinked = true;
\t\t} catch (error) {
\t\t\t// Android app-data filesystems deny hard links. Fall back to rename:
\t\t\t// rejectExistingLog already guarantees the target does not exist.
\t\t\tif (error && (error.code === "EACCES" || error.code === "EPERM" || error.code === "ENOTSUP" || error.code === "ENOSYS")) {
\t\t\t\tawait rename(tmp, finalPath);
\t\t\t\tlinked = true;
\t\t\t} else {
\t\t\t\tthrow error;
\t\t\t}
\t\t} finally {
\t\t\t/* v8 ignore next -- link failure is the TOCTOU/IO race guarded above; not reachable in test */
\t\t\tif (!linked) await rm(tmp, { force: true });
\t\t}""")

        val FS_BLOCK_OLD = tabs("""\t\tif (createIfAbsent !== void 0) try {
\t\t\tawait linkFile(tempPath, absolutePath);
\t\t} catch (error) {
\t\t\tawait throwGuardedCreateFailure(error, absolutePath, createIfAbsent.displayPath, inspectPublicationTarget);
\t\t}""")
        val FS_BLOCK_NEW = tabs("""\t\tif (createIfAbsent !== void 0) try {
\t\t\tawait linkFile(tempPath, absolutePath);
\t\t} catch (error) {
\t\t\t// Android app-data filesystems deny hard links (fs-local). Fall back
\t\t\t// to rename() when the target is still absent (no-replace intent
\t\t\t// preserved); otherwise keep the guarded collision handling.
\t\t\tif (error && (error.code === "EACCES" || error.code === "EPERM" || error.code === "ENOTSUP" || error.code === "ENOSYS")) {
\t\t\t\tlet existing = null;
\t\t\t\ttry {
\t\t\t\t\texisting = await inspectPublicationTarget(absolutePath);
\t\t\t\t} catch (inspectError) {
\t\t\t\t\tif (!isENOENT(inspectError) && !isENOTDIR(inspectError)) throw inspectError;
\t\t\t\t}
\t\t\t\tif (existing === null) await rename(tempPath, absolutePath);
\t\t\t\telse await throwGuardedCreateFailure(error, absolutePath, createIfAbsent.displayPath, inspectPublicationTarget);
\t\t\t} else {
\t\t\t\tawait throwGuardedCreateFailure(error, absolutePath, createIfAbsent.displayPath, inspectPublicationTarget);
\t\t\t}
\t\t}""")

        val ATT_BLOCK_OLD = tabs("""\t\ttry {
\t\t\tawait link(temporary, target);
\t\t} catch (error) {
\t\t\t/* v8 ignore next -- Private same-filesystem directories make EEXIST the only recoverable link race. */
\t\t\tif (!(error instanceof Error && "code" in error && error.code === "EEXIST")) throw error;
\t\t\tif (digest\$1(new Uint8Array(await readFile(target))) !== sha256) throw new AttachmentError("Stored attachment failed integrity verification.", "ATTACHMENT_CORRUPT");
\t\t}""")
        val ATT_BLOCK_NEW = tabs("""\t\ttry {
\t\t\tawait link(temporary, target);
\t\t} catch (error) {
\t\t\t// Android app-data filesystems deny hard links (attachment). Fall back to rename.
\t\t\tif (error instanceof Error && "code" in error && (error.code === "EACCES" || error.code === "EPERM" || error.code === "ENOTSUP" || error.code === "ENOSYS")) {
\t\t\t\tawait rename(temporary, target);
\t\t\t} else if (!(error instanceof Error && "code" in error && error.code === "EEXIST")) {
\t\t\t\tthrow error;
\t\t\t} else {
\t\t\t\t/* v8 ignore next -- Private same-filesystem directories make EEXIST the only recoverable link race. */
\t\t\t\tif (digest\$1(new Uint8Array(await readFile(target))) !== sha256) throw new AttachmentError("Stored attachment failed integrity verification.", "ATTACHMENT_CORRUPT");
\t\t\t}
\t\t}""")
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
                        "所选文件不是有效的 DSH 层包（缺少 node_modules/@deepseek-ai/dsh/lib/bin.js）",
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
                    return AppResult.Failure(AppError("DSH_INSTALL_FAILED", "无法将新 DSH 层就位（rename/copy 均失败）"))
                }
                staging.deleteRecursively()
            }
            // Stage 3: Android compat (defense in depth) + version record.
            // DSH publishes files via fs.link() which Android app-data filesystems
            // deny (EACCES); apply the link()->rename() fallback to every layer
            // (idempotent; covers online/npm-updated layers, not just the embedded
            // baseline that is pre-patched at build time).
            applyAndroidDshPatch(dsh)
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

    /**
     * Post-extraction Android compatibility patch (mirrors runtime-bundle/scripts/patch_dsh_android.js).
     * DSH publishes files with fs.link() which Android app-data filesystems deny (EACCES); this
     * makes those paths fall back to rename(). Idempotent (marker-checked) + shape-skipping so a
     * newer/older DSH is never broken. Applied to EVERY DSH layer (embedded baseline + online update)
     * for defense in depth.
     */
    private fun applyAndroidDshPatch(dsh: File) {
        val pkgDir = File(dsh, "node_modules/@deepseek-ai")
        val appliedMarker = "Android app-data filesystems deny hard links"
        // 1. dsh-session-persistence-jsonl — session publish via link().
        patchJs(
            File(pkgDir, "dsh-session-persistence-jsonl/lib/index.js"),
            appliedMarker,
            SESSION_IMPORT_OLD, SESSION_IMPORT_NEW, SESSION_BLOCK_OLD, SESSION_BLOCK_NEW,
        )
        // 2. dsh-fs-local — write-tool createIfAbsent via linkFile().
        patchJs(File(pkgDir, "dsh-fs-local/lib/index.js"), appliedMarker, null, null, FS_BLOCK_OLD, FS_BLOCK_NEW)
        // 3. dsh-attachment-local — attachment publish via link().
        patchJs(File(pkgDir, "dsh-attachment-local/lib/index.js"), appliedMarker, null, null, ATT_BLOCK_OLD, ATT_BLOCK_NEW)
    }

    private fun patchJs(
        file: File,
        marker: String,
        importOld: String?,
        importNew: String?,
        blockOld: String,
        blockNew: String,
    ) {
        if (!file.isFile) return
        runCatching {
            var src = file.readText()
            if (src.contains(marker)) {
                Log.i(TAG, "android dsh patch already applied: ${file.name}")
                return
            }
            if (importOld != null) {
                if (!src.contains(importOld) || importNew == null) {
                    Log.w(TAG, "android dsh patch skip (import shape): ${file.name}")
                    return
                }
                src = src.replace(importOld, importNew)
            }
            if (!src.contains(blockOld)) {
                Log.w(TAG, "android dsh patch skip (block shape): ${file.name}")
                return
            }
            file.writeText(src.replace(blockOld, blockNew))
            Log.i(TAG, "android dsh patch applied: ${file.name}")
        }.onFailure { Log.w(TAG, "android dsh patch failed: ${file.name}: ${it.message}") }
    }

    private fun versionFromPackage(dshDirRoot: File): String? {
        // 1.1.0 (M3): prefer the REAL product version from
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
