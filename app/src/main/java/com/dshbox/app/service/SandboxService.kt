package com.dshbox.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dshbox.app.DshApp
import com.dshbox.app.R
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.Constants
import com.dshbox.app.sandbox.BundledRuntimeInstaller
import com.dshbox.app.sandbox.DshState
import com.dshbox.app.sandbox.SandboxState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the sandbox and DSH lifecycles.
 *
 * The sandbox and DSH are now decoupled:
 * - Sandbox (Debian/PRoot) can run independently.
 * - DSH requires the sandbox to be running; if not, the user is told to wake it.
 * - First launch auto-starts both; afterwards the user controls each separately.
 */
class SandboxService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var restartInProgress = false

    private val sandboxManager
        get() = (application as DshApp).container.sandboxManager

    private val terminalManager
        get() = (application as DshApp).container.dshTerminalManager

    private val prefs: SharedPreferences
        get() = applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground()
        serviceScope.launch {
            sandboxManager.sandboxState.collectLatest { state ->
                updateNotification()
            }
        }
        serviceScope.launch {
            sandboxManager.dshState.collectLatest { state ->
                updateNotification()
            }
        }
        serviceScope.launch { bootstrap() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SANDBOX -> serviceScope.launch { startSandbox() }
            ACTION_STOP_SANDBOX -> serviceScope.launch { stopSandbox() }
            ACTION_RESTART_SANDBOX -> serviceScope.launch { restartSandbox() }
            ACTION_START_DSH -> serviceScope.launch { startDsh() }
            ACTION_RESTART_DSH -> serviceScope.launch { restartDsh() }
            ACTION_STOP_DSH -> serviceScope.launch { stopDsh() }
            ACTION_STOP_ALL -> {
                serviceScope.launch {
                    terminalManager.stopAll()
                    sandboxManager.forceStop()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        terminalManager.stopAll()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * One-time bootstrap: initialize directories, install bundled runtime if needed,
     * then on first launch start both sandbox and DSH. Subsequent launches leave
     * control to the user. The first-run marker is persisted only when BOTH
     * started successfully, so a failed first boot is retried on the next launch.
     */
    private suspend fun bootstrap() {
        sandboxManager.initialize()
        provisionMobileAdaptPlugin()
        val container = (application as DshApp).container
        when (val bundled = BundledRuntimeInstaller(applicationContext, container.sandboxConfig).installIfAbsent()) {
            is AppResult.Success -> if (bundled.value) Log.i(TAG, "bootstrap: bundled runtime installed")
            is AppResult.Failure -> Log.w(TAG, "bootstrap: bundled runtime install failed: ${bundled.error.message}")
        }
        if (!sandboxManager.isRuntimeInstalled()) {
            val installed = sandboxManager.installFirstAvailableBundle()
            if (installed is AppResult.Success) {
                sandboxManager.promoteRuntimeBundle()
            }
        }
        if (sandboxManager.isRuntimeInstalled()) {
            provisionBundledDsh()
            val firstRun = !prefs.getBoolean(Constants.PREF_FIRST_RUN_COMPLETED, false)
            if (firstRun) {
                Log.i(TAG, "bootstrap: first run, starting sandbox and dsh")
                sandboxManager.startSandbox()
                if (sandboxManager.sandboxState.value == SandboxState.RUNNING) {
                    val dshResult = startDsh()
                    if (dshResult is AppResult.Success) {
                        prefs.edit().putBoolean(Constants.PREF_FIRST_RUN_COMPLETED, true).apply()
                        Log.i(TAG, "bootstrap: first run complete")
                    } else {
                        Log.w(TAG, "bootstrap: first run dsh not ready, will retry next launch")
                    }
                } else {
                    Log.w(TAG, "bootstrap: first run sandbox failed, will retry next launch")
                }
            }
        }
    }

    /**
     * Copy the APK-bundled mobile-adapt cordis plugin into the DSH-HOME staging
     * area (user-data/.dsh/mobile-adapt), fully overwritten each install, so the
     * user-triggered 装配 step can assemble it into the DSH profile via guest
     * command injection (method B).
     */
    private suspend fun provisionMobileAdaptPlugin() = withContext(Dispatchers.IO) {
        val assetDir = "plugins/dsh-mobile-adapt"
        val stageDir = File(File(File(applicationContext.filesDir, "user-data"), ".dsh"), "mobile-adapt")
        try {
            if (stageDir.exists()) stageDir.deleteRecursively()
            stageDir.mkdirs()
            copyAssetTree(assetDir, stageDir)
            Log.i(TAG, "bootstrap: mobile-adapt plugin staged to ${stageDir.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "bootstrap: provision mobile-adapt plugin failed: ${t.message}")
        }
    }

    private fun copyAssetTree(assetPath: String, dest: File) {
        val assets = applicationContext.assets
        val children = assets.list(assetPath) ?: return
        for (name in children) {
            val full = "$assetPath/$name"
            val out = File(dest, name)
            // A directory is a path whose AssetManager::list returns a NON-EMPTY array;
            // a FILE returns null or an empty array — treat only non-empty as dir, else
            // copy as a regular file (nested dirs like plugin/ have many entries).
            if (assets.list(full)?.isNotEmpty() == true) {
                out.mkdirs()
                copyAssetTree(full, out)
            } else {
                assets.open(full).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private suspend fun provisionBundledDsh() {
        // DshBundler: provision the APK-bundled DSH baseline (assets/dsh/
        // <version>.tar.gz [+ .sha256]) into runtime-current/dsh with version
        // arbitration (installed-newer wins). No-op when the APK carries no DSH
        // baseline (e.g. a dev build that ships DSH separately).
        val assetManager = applicationContext.assets
        val entries = runCatching { assetManager.list("dsh") }.getOrNull() ?: return
        // zstd baseline (preferred) or a gzip fallback; DshLayer/extractTarGz sniffs
        // the actual compression from the stream magic, not the extension.
        val dshTarball = entries.firstOrNull { it.endsWith(".tar.zst") }
            ?: entries.firstOrNull { it.endsWith(".tar.gz") } ?: return
        val version = dshTarball.removeSuffix(".tar.zst").removeSuffix(".tar.gz")
        val out = File(cacheDir, "dsh-bundled-$version.tar")
        try {
            assetManager.open("dsh/$dshTarball").use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            val sha = if (entries.contains("$dshTarball.sha256")) {
                // Sidecars are "<sha256>  <file>"; take the hash token only, matching
                // the layer verification (BundledRuntimeInstaller / DefaultSandboxManager).
                assetManager.open("dsh/$dshTarball.sha256").use { it.readBytes().decodeToString().trim().split(Regex("\\s+")).firstOrNull() }
            } else {
                null
            }
            when (val r = sandboxManager.updateDsh(out, sha, version)) {
                is AppResult.Success ->
                    if (r.value.changed) Log.i(TAG, "bootstrap: bundled DSH ${r.value.version} installed")
                    else Log.i(TAG, "bootstrap: bundled DSH $version kept (already at ${r.value.version})")
                is AppResult.Failure ->
                    Log.w(TAG, "bootstrap: bundled DSH provision failed: ${r.error.message}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bootstrap: bundled DSH read failed: ${t.message}")
        } finally {
            out.delete()
        }
    }

    private suspend fun startSandbox() {
        sandboxManager.startSandbox()
    }

    private suspend fun stopSandbox() {
        // The terminal login shell lives inside the sandbox rootfs; kill it
        // first so it never outlives the PRoot tree it depends on.
        terminalManager.stopSandboxSession()
        sandboxManager.stopSandbox()
    }

    private suspend fun restartSandbox() {
        if (restartInProgress) return
        restartInProgress = true
        try {
            sandboxManager.restartSandbox()
        } finally {
            restartInProgress = false
        }
    }

    private suspend fun startDsh(): AppResult<com.dshbox.app.sandbox.DshRuntimeStatus> {
        val result = sandboxManager.startDsh()
        if (result is AppResult.Failure) {
            Log.w(TAG, "startDsh: ${result.error.message}")
            showToast(result.error.message)
        }
        return result
    }

    private suspend fun restartDsh(): AppResult<com.dshbox.app.sandbox.DshRuntimeStatus> {
        val result = sandboxManager.restartDsh()
        if (result is AppResult.Failure) {
            Log.w(TAG, "restartDsh: ${result.error.message}")
            showToast(result.error.message)
        }
        return result
    }

    private suspend fun stopDsh() {
        sandboxManager.stopDsh()
    }

    private fun showToast(message: String) {
        serviceScope.launch(Dispatchers.Main) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "startForeground failed due to missing notification permission", e)
        }
    }

    private fun updateNotification() {
        val notification = buildNotification()
        try {
            if (hasNotificationPermission()) {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            } else {
                Log.w(TAG, "updateNotification: POST_NOTIFICATIONS not granted - skipping notify")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "updateNotification: notify threw SecurityException", e)
        }
    }

    /** Android 13+ requires the POST_NOTIFICATIONS runtime permission before notify(). */
    private fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun buildNotification(): Notification {
        val sandboxState = sandboxManager.sandboxState.value
        val dshState = sandboxManager.dshState.value

        val sandboxText = when (sandboxState) {
            SandboxState.RUNNING -> "沙箱在线"
            SandboxState.STARTING, SandboxState.INITIALIZING -> "沙箱启动中"
            SandboxState.STOPPED -> "沙箱已停止"
            SandboxState.ERROR -> "沙箱异常"
            else -> "沙箱…"
        }
        val dshText = when (dshState) {
            DshState.READY -> "DSH 就绪"
            DshState.RUNNING, DshState.STARTING -> "DSH 启动中"
            DshState.STOPPED -> "DSH 已停止"
            DshState.ERROR -> "DSH 异常"
            else -> "DSH…"
        }
        val contentTitle = "$sandboxText · $dshText"
        val contentText = Constants.DSH_BASE_URL

        val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(Constants.DSH_BASE_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val startDshIntent = Intent(this, SandboxService::class.java).setAction(ACTION_START_DSH)
        val startDshPending = PendingIntent.getService(
            this,
            1,
            startDshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val restartDshIntent = Intent(this, SandboxService::class.java).setAction(ACTION_RESTART_DSH)
        val restartDshPending = PendingIntent.getService(
            this,
            2,
            restartDshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopSandboxIntent = Intent(this, SandboxService::class.java).setAction(ACTION_STOP_SANDBOX)
        val stopSandboxPending = PendingIntent.getService(
            this,
            3,
            stopSandboxIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF10A37F.toInt())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "打开 DSH", openPending)
            .addAction(0, "启动 DSH", startDshPending)
            .addAction(0, "重启 DSH", restartDshPending)
            .addAction(0, "停止沙箱", stopSandboxPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DSH Sandbox",
                NotificationManager.IMPORTANCE_LOW,
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "SandboxService"
        private const val CHANNEL_ID = "dsh_sandbox"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START_SANDBOX = "com.dshbox.app.action.START_SANDBOX"
        const val ACTION_STOP_SANDBOX = "com.dshbox.app.action.STOP_SANDBOX"
        const val ACTION_RESTART_SANDBOX = "com.dshbox.app.action.RESTART_SANDBOX"
        const val ACTION_START_DSH = "com.dshbox.app.action.START_DSH"
        const val ACTION_RESTART_DSH = "com.dshbox.app.action.RESTART_DSH"
        const val ACTION_STOP_DSH = "com.dshbox.app.action.STOP_DSH"
        const val ACTION_STOP_ALL = "com.dshbox.app.action.STOP_ALL"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SandboxService::class.java),
            )
        }

        fun startSandbox(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_START_SANDBOX),
            )
        }

        fun stopSandbox(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_STOP_SANDBOX),
            )
        }

        fun restartSandbox(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_RESTART_SANDBOX),
            )
        }

        fun startDsh(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_START_DSH),
            )
        }

        fun restartDsh(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_RESTART_DSH),
            )
        }

        fun stopDsh(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_STOP_DSH),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_STOP_ALL),
            )
        }
    }
}
