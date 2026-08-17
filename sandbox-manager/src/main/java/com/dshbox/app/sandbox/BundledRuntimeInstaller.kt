package com.dshbox.app.sandbox

import android.content.Context
import android.util.Log
import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import java.io.File

/**
 * First-boot bootstrap: when no runtime is installed yet, extract the runtime
 * bundle shipped inside the APK (assets/runtime/"*.tar.gz") directly into
 * runtime-current. This makes "install the APK, open the app" a complete
 * deployment on a fresh device - no adb, no separate bundle import.
 *
 * The embedded bundle must contain the DSHapp fixes (see tools/build_apk.sh);
 * a "<bundle>.sha256" sidecar asset is verified before extraction.
 */
class BundledRuntimeInstaller(
    private val context: Context,
    private val config: SandboxConfig,
    private val bundleManager: BundleManager = BundleManager(config),
) {
    companion object {
        private const val TAG = "BundledRuntime"
        const val ASSETS_DIR = "runtime"
    }

    /**
     * Install the bundled runtime when no complete runtime is present. A
     * half-extracted slot (e.g. the app process was killed mid-extraction) is
     * cleared and reinstalled - existence alone must not block retries.
     * @return Success(true) when a bundled runtime was installed (first boot),
     *   Success(false) when nothing was needed/done, Failure on error.
     */
    suspend fun installIfAbsent(): AppResult<Boolean> {
        val slot = bundleManager.currentSlotDir()
        // A complete runtime carries the DSH launcher inside the extracted
        // rootfs; anything less (empty dir, interrupted extraction) is retried.
        if (File(slot, "debian/opt/dshapp/start_dsh.sh").isFile) return AppResult.Success(false)

        val bundleName = bundledBundleName() ?: return AppResult.Success(false)
        val expectedSha = bundledSha256(bundleName)

        val staging = File(config.appFilesDir, "bundled-runtime-staging.tar.gz")
        try {
            if (slot.exists()) {
                Log.w(TAG, "incomplete runtime slot detected; clearing before reinstall")
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
                Log.i(TAG, "bundled runtime extracted to ${slot.absolutePath}")
            }
            return when (result) {
                is AppResult.Success -> AppResult.Success(true)
                is AppResult.Failure -> result
            }
        } catch (t: Throwable) {
            Log.e(TAG, "bundled runtime install failed: ${t.message}", t)
            return AppResult.Failure(AppError("BUNDLED_INSTALL_FAILED", "failed to extract embedded runtime: ${t.message}"))
        } finally {
            staging.delete()
        }
    }

    /** True when the APK embeds a runtime bundle (first boot auto-installs). */
    fun hasBundledBundle(): Boolean = bundledBundleName() != null

    private fun bundledBundleName(): String? = try {
        context.assets.list(ASSETS_DIR)
            ?.filter { it.endsWith(".dshb") }
            ?.firstOrNull()
    } catch (t: Throwable) {
        Log.w(TAG, "cannot list bundled runtime assets: ${t.message}")
        null
    }

    private fun bundledSha256(bundleName: String): String? = try {
        context.assets.open("$ASSETS_DIR/$bundleName.sha256")
            .bufferedReader()
            .use { it.readText().trim() }
            .takeIf { it.isNotEmpty() }
    } catch (t: Throwable) {
        Log.w(TAG, "no sha256 sidecar for bundled runtime: ${t.message}")
        null
    }
}
