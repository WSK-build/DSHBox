package com.dshbox.app.sandbox

import android.util.Log
import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
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
        val pkg = File(dshDir(), PROFILE_VERSION_FILE)
        if (pkg.isFile) {
            return try {
                org.json.JSONObject(pkg.readText()).optString("version").takeIf { it.isNotEmpty() }
            } catch (t: Throwable) {
                Log.w(TAG, "cannot read ${pkg.name} version: ${t.message}")
                null
            }
        }
        return null
    }

    /**
     * Installs (or replaces) the DSH layer from [bundle] (a .tar.gz of the DSH
     * runtime content) with version arbitration:
     *   - if [newVersion] is provided and the currently installed version is
     *     NEWER, keep the installed copy (user-newer wins) -> changed=false.
     *   - otherwise move the installed copy to previous/dsh (one copy only),
     *     extract [bundle] into dsh/, and record [newVersion].
     *
     * Only [dshDir] and [previousDshDir] are touched. Returns whether a change
     * was applied and the resulting version.
     */
    suspend fun installFromBundle(
        bundle: File,
        expectedSha256: String?,
        newVersion: String?,
    ): AppResult<DshUpdateOutcome> {
        if (!bundle.isFile) {
            return AppResult.Failure(AppError("DSH_BUNDLE_NOT_FOUND", "dsh bundle not found: ${bundle.absolutePath}"))
        }
        val current = installedVersion()
        if (current != null && newVersion != null && compareVersions(current, newVersion) >= 0) {
            // Installed is the same or newer; keep it (APK/newer must not downgrade).
            Log.i(TAG, "dsh update skipped: installed $current >= incoming $newVersion")
            return AppResult.Success(DshUpdateOutcome(version = current, changed = false))
        }
        if (expectedSha256 != null && !bundleManager.verifySha256(bundle, expectedSha256)) {
            return AppResult.Failure(AppError("DSH_SHA256_MISMATCH", "dsh bundle failed SHA-256 verification"))
        }

        val previous = previousDshDir()
        val dsh = dshDir()
        try {
            if (previous.exists()) previous.deleteRecursively()
            if (dsh.exists()) {
                // Old object -> previous (guaranteed single previous copy).
                if (!dsh.renameTo(previous)) {
                    Log.w(TAG, "could not move dsh to previous; deleting old instead")
                    dsh.deleteRecursively()
                }
            }
            dsh.mkdirs()
            val extract = when (val r = bundleManager.extractTarGz(bundle, dsh)) {
                is AppResult.Success -> true
                is AppResult.Failure -> {
                    // roll back the current slot so a failed update leaves the
                    // previous copy in place for the next boot.
                    if (previous.exists() && !dsh.exists()) previous.renameTo(dsh)
                    return AppResult.Failure(r.error)
                }
            }
            if (!extract) {
                return AppResult.Failure(AppError("DSH_EXTRACT_FAILED", "dsh bundle extraction failed"))
            }
            // Android compat (defense in depth): DSH publishes files via fs.link() which
            // Android app-data filesystems deny (EACCES). Apply the link()->rename() fallback
            // to this layer too (idempotent; covers online-updated layers, not just the embedded
            // baseline that is pre-patched at build time).
            applyAndroidDshPatch(dsh)
            if (!newVersion.isNullOrBlank()) {
                val vf = File(dsh, VERSION_FILE)
                vf.parentFile?.mkdirs()
                vf.writeText(newVersion)
            } else {
                // no explicit version: fall back to the discovered package.json version
                val disc = versionFromPackage(dsh) ?: "unknown"
                val vf = File(dsh, VERSION_FILE)
                vf.parentFile?.mkdirs()
                vf.writeText(disc)
            }
            Log.i(TAG, "dsh layer updated to ${newVersion ?: installedVersion()}")
            return AppResult.Success(DshUpdateOutcome(version = installedVersion(), changed = true))
        } catch (t: Throwable) {
            Log.e(TAG, "dsh install failed: ${t.message}", t)
            return AppResult.Failure(AppError("DSH_INSTALL_FAILED", "dsh install failed: ${t.message}"))
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

    private fun versionFromPackage(dshDirRoot: File): String? = try {
        val pkg = File(dshDirRoot, PROFILE_VERSION_FILE)
        if (pkg.isFile) org.json.JSONObject(pkg.readText()).optString("version").takeIf { it.isNotEmpty() } else null
    } catch (t: Throwable) {
        null
    }

    /**
     * Best-effort semantic-ish comparison (handles "0.1.0-rc.6" style). Returns
     * >0 when [a] is newer than [b], <0 when older, 0 when equal.
     */
    fun compareVersions(a: String, b: String): Int {
        val clean = { s: String -> s.trim().trimStart('v').split('-').first() }
        val pa = clean(a).split('.').mapNotNull { it.toIntOrNull() }
        val pb = clean(b).split('.').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        // Pre-release marker: a "-rc.N" is newer than the plain release if the
        // numeric parts are equal, but treat equal-numbered differently releases
        // as equal to avoid churn in arbitration.
        val ra = a.split('-').drop(1).joinToString("-")
        val rb = b.split('-').drop(1).joinToString("-")
        return if (ra == rb) 0 else ra.compareTo(rb)
    }
}

/** Outcome of a DSH update attempt. */
data class DshUpdateOutcome(
    val version: String?,
    val changed: Boolean,
)
