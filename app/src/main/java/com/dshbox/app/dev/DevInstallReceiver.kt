package com.dshbox.app.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dshbox.app.BuildConfig
import com.dshbox.app.DshApp
import com.dshbox.app.common.AppResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug-only receiver used by CI/emulator testing to install a Runtime Bundle
 * from a device path. Production builds should disable it.
 *
 * adb shell am broadcast -n com.dshbox.app/.dev.DevInstallReceiver \
 *   -a com.dshbox.app.dev.action.INSTALL_RUNTIME \
 *   --es bundle /data/local/tmp/dshapp-runtime-debian-amd64-rootfs.tar.gz \
 *   --es sha256 <sha256> [--ez autostart true]
 *
 * The extraction of a multi-hundred-MB bundle takes minutes, far beyond the
 * broadcast delivery timeout, so the pending result is best-effort only: after
 * it expires the install keeps running in this process (kept alive by the
 * foreground SandboxService) and completion is observed via logcat/slots, not
 * via the broadcast result. All result/finish calls tolerate an expired result.
 */
class DevInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.action != ACTION_INSTALL_RUNTIME) return

        val bundlePath = intent.getStringExtra(EXTRA_BUNDLE) ?: return
        val sha256 = intent.getStringExtra(EXTRA_SHA256) ?: return
        val autoStart = intent.getBooleanExtra(EXTRA_AUTOSTART, false)

        val pendingResult = goAsync()
        val app = context.applicationContext as DshApp
        val manager = app.container.sandboxManager

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                manager.initialize()
                // The sandbox must be stopped before install/promote; adb
                // cannot reach SandboxService (not exported), so the dev
                // install path stops it itself (idempotent when already
                // stopped; waits out an in-flight start via the lifecycle lock).
                manager.stopSandbox()
                when (val installed = manager.installRuntimeBundle(File(bundlePath), sha256)) {
                    is AppResult.Success -> {
                        // Extraction took minutes; SandboxService may have
                        // auto-started the sandbox again in the meantime.
                        manager.stopSandbox()
                        manager.promoteRuntimeBundle()
                        if (autoStart) {
                            manager.startSandbox()
                        }
                        android.util.Log.i(TAG, "runtime bundle installed and promoted")
                    }
                    is AppResult.Failure -> {
                        android.util.Log.e(TAG, "install failed: ${installed.error.message}")
                        setErrorResult(pendingResult)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "install exception: ${t.message}", t)
                setErrorResult(pendingResult)
            } finally {
                try {
                    pendingResult.finish()
                } catch (_: IllegalStateException) {
                    // Broadcast already timed out; the install itself is unaffected.
                }
            }
        }
    }

    private fun setErrorResult(pendingResult: PendingResult) {
        try {
            resultCode = RESULT_CODE_ERROR
        } catch (_: IllegalStateException) {
            // Broadcast already timed out; log output is the error channel.
        }
    }

    companion object {
        private const val TAG = "DevInstallReceiver"
        const val ACTION_INSTALL_RUNTIME = "com.dshbox.app.dev.action.INSTALL_RUNTIME"
        const val EXTRA_BUNDLE = "bundle"
        const val EXTRA_SHA256 = "sha256"
        const val EXTRA_AUTOSTART = "autostart"
        const val RESULT_CODE_ERROR = 1
    }
}
