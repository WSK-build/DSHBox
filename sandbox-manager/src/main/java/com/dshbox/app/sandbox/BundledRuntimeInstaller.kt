package com.dshbox.app.sandbox

import android.content.Context
import android.util.Log
import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import java.io.File

/**
 * First-boot bootstrap: when no runtime is installed yet, extract the layered
 * runtime (base/node/android-side from assets/runtime) directly into
 * runtime-current. This makes "install the APK, open the app" a complete
 * deployment on a fresh device - no adb, no separate bundle import.
 *
 * The embedded runtime is now LAYERED: each layer ships as its own compressed
 * tarball with a "<name>.sha256" sidecar, plus a runtime-profile.json. DSH is
 * NOT embedded here (it is a separate product managed by the app's DSH update
 * flow, installed at runtime-current/dsh). The single-monolithic ".dshb" layout
 * is still supported for backward compatibility with older assets.
 */
class BundledRuntimeInstaller(
    private val context: Context,
    private val config: SandboxConfig,
    private val bundleManager: BundleManager = BundleManager(config),
) {
    companion object {
        private const val TAG = "BundledRuntime"
        const val ASSETS_DIR = "runtime"
        val LAYER_NAMES = listOf("base", "node", "android-side")
    }

    /**
     * Install the bundled layered runtime when no complete runtime is present.
     * A half-extracted slot is cleared and reinstalled.
     * @return Success(true) when a bundled runtime was installed (first boot),
     *   Success(false) when nothing was needed/done, Failure on error.
     */
    suspend fun installIfAbsent(): AppResult<Boolean> {
        val slot = bundleManager.currentSlotDir()
        // Complete layered runtime marker: profile + base layer.
        if (File(slot, "runtime-profile.json").isFile && File(slot, "base").isDirectory) {
            return AppResult.Success(false)
        }
        // Legacy single-bundle marker (old .dshb assets).
        if (File(slot, "debian/opt/dshapp/start_dsh.sh").isFile) return AppResult.Success(false)

        val layered = hasLayeredAssets()
        return if (layered) installLayered(slot) else installLegacySingleBundle(slot)
    }

    /** True when the APK embeds the layered runtime assets (base/node/android-side). */
    fun hasBundledBundle(): Boolean = hasLayeredAssets() || bundledLegacyBundleName() != null

    private fun hasLayeredAssets(): Boolean = try {
        val names = context.assets.list(ASSETS_DIR) ?: return false
        names.any { it == "runtime-profile.json" } &&
            names.any { it.startsWith("base.tar.") }
    } catch (t: Throwable) {
        Log.w(TAG, "cannot list layered runtime assets: ${t.message}")
        false
    }

    private suspend fun installLayered(slot: File): AppResult<Boolean> {
        val stagingDir = File(config.appFilesDir, "bundled-runtime-staging").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        try {
            if (slot.exists()) {
                Log.w(TAG, "incomplete runtime slot detected; clearing before layered reinstall")
                bundleManager.clearSlot(slot)
            }
            slot.mkdirs()

            for (layer in LAYER_NAMES) {
                val assetName = findLayerAsset(layer) ?: continue
                val staged = File(stagingDir, assetName)
                context.assets.open("$ASSETS_DIR/$assetName").use { input ->
                    staged.outputStream().use { output -> input.copyTo(output) }
                }
                // Verify the per-layer checksum sidecar if present.
                val expectedSha = context.assets
                    .open("$ASSETS_DIR/$assetName.sha256")
                    .bufferedReader()
                    .use { it.readText().trim().split(Regex("\\s+")).firstOrNull() }
                    ?.takeIf { it.isNotBlank() }
                if (expectedSha != null && !bundleManager.verifySha256(staged, expectedSha)) {
                    return AppResult.Failure(AppError("BUNDLED_SHA256_MISMATCH", "embedded layer $assetName failed SHA-256 verification"))
                }
                val dest = File(slot, layer)
                when (val result = bundleManager.extractTarGz(staged, dest)) {
                    is AppResult.Success -> Log.i(TAG, "embedded layer $assetName extracted to ${dest.absolutePath}")
                    is AppResult.Failure -> return result
                }
                // Phase C: record the layer's declared checksum as a sentinel so
                // isRuntimeInstalled()/ensureRuntimeComponents() can cheaply verify
                // integrity on later launches.
                if (expectedSha != null) {
                    val sentinel = File(dest, ".dshbox/layer-$layer.sha256")
                    runCatching {
                        sentinel.parentFile?.mkdirs()
                        sentinel.writeText(expectedSha)
                    }.onFailure { Log.w(TAG, "write sentinel layer-$layer failed: ${it.message}") }
                }
            }

            // Ship the profile (when present) into the runtime slot.
            copyAssetToFile("$ASSETS_DIR/runtime-profile.json", File(slot, "runtime-profile.json"))

            return if (File(slot, "runtime-profile.json").isFile && File(slot, "base").isDirectory) {
                AppResult.Success(true)
            } else {
                AppResult.Failure(AppError("BUNDLED_INCOMPLETE", "embedded runtime did not produce a complete base layer"))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "layered runtime install failed: ${t.message}", t)
            return AppResult.Failure(AppError("BUNDLED_INSTALL_FAILED", "failed to extract embedded runtime: ${t.message}"))
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /** Legacy single-bundle install path (old ".dshb" assets). */
    private suspend fun installLegacySingleBundle(slot: File): AppResult<Boolean> {
        val bundleName = bundledLegacyBundleName() ?: return AppResult.Success(false)
        val expectedSha = bundledLegacySha256(bundleName)
        val staging = File(config.appFilesDir, "bundled-runtime-staging.tar.gz")
        try {
            if (slot.exists()) {
                Log.w(TAG, "incomplete runtime slot detected; clearing before legacy reinstall")
                bundleManager.clearSlot(slot)
            }
            context.assets.open("$ASSETS_DIR/$bundleName").use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            }
            if (expectedSha != null && !bundleManager.verifySha256(staging, expectedSha)) {
                return AppResult.Failure(AppError("BUNDLED_SHA256_MISMATCH", "embedded runtime bundle failed SHA-256 verification"))
            }
            slot.mkdirs()
            val result = bundleManager.extractTarGz(staging, File(slot, "debian"))
            if (result is AppResult.Success) {
                Log.i(TAG, "legacy bundled runtime extracted to ${slot.absolutePath}")
            }
            return when (result) {
                is AppResult.Success -> AppResult.Success(true)
                is AppResult.Failure -> result
            }
        } catch (t: Throwable) {
            Log.e(TAG, "legacy bundled runtime install failed: ${t.message}", t)
            return AppResult.Failure(AppError("BUNDLED_INSTALL_FAILED", "failed to extract embedded runtime: ${t.message}"))
        } finally {
            staging.delete()
        }
    }

    private fun findLayerAsset(layer: String): String? = try {
        context.assets.list(ASSETS_DIR)?.firstOrNull { it.startsWith("$layer.tar.") }
    } catch (t: Throwable) {
        Log.w(TAG, "cannot find layer asset $layer: ${t.message}")
        null
    }

    private fun bundledLegacyBundleName(): String? = try {
        context.assets.list(ASSETS_DIR)?.filter { it.endsWith(".dshb") }?.firstOrNull()
    } catch (t: Throwable) {
        Log.w(TAG, "cannot list legacy bundle assets: ${t.message}")
        null
    }

    private fun bundledLegacySha256(bundleName: String): String? = try {
        context.assets.open("$ASSETS_DIR/$bundleName.sha256")
            .bufferedReader()
            .use { it.readText().trim() }
            .takeIf { it.isNotEmpty() }
    } catch (t: Throwable) {
        Log.w(TAG, "no sha256 sidecar for legacy bundled runtime: ${t.message}")
        null
    }

    private fun copyAssetToFile(assetPath: String, dest: File): Boolean = try {
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        true
    } catch (t: Throwable) {
        Log.w(TAG, "copy asset $assetPath failed: ${t.message}")
        false
    }
}
