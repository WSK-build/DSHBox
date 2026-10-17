package com.dshbox.app.browser

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.dshbox.app.MainActivity
import com.dshbox.app.ui.theme.AppThemeState
import com.dshbox.app.ui.theme.AppLocaleState
import com.dshbox.browser.api.BrowserUiSettings
import com.dshbox.browser.api.BrowserContract
import com.dshbox.browser.api.BrowserHomeStatus
import com.dshbox.browser.runtime.BrowserActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BrowserAppController(context: Context) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val mutableStatus = MutableStateFlow<BrowserHomeStatus?>(null)
    val status: StateFlow<BrowserHomeStatus?> = mutableStatus.asStateFlow()
    private var started = false
    private val permission = BrowserContract.internalPermission(this.context.packageName)
    private val appSettings = this.context.getSharedPreferences(
        BrowserUiSettings.APP_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == BrowserUiSettings.APP_THEME_KEY || key == BrowserUiSettings.APP_LOCALE_KEY) {
            broadcastUiSettings()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BrowserContract.ACTION_STATUS) return
            mutableStatus.value = when (intent.getStringExtra(BrowserContract.EXTRA_STATUS)) {
                BrowserContract.STATUS_RUNNING -> BrowserHomeStatus.RUNNING
                BrowserContract.STATUS_IDLE -> BrowserHomeStatus.IDLE
                else -> null
            }
        }
    }
    private val query = object : Runnable {
        override fun run() {
            if (!started) return
            val manager = context.getSystemService(ActivityManager::class.java)
            val alive = manager.runningAppProcesses.orEmpty().any {
                it.processName == "${context.packageName}:browser"
            }
            if (!alive) mutableStatus.value = null
            context.sendBroadcast(
                Intent(BrowserContract.ACTION_QUERY_STATUS)
                    .setPackage(context.packageName)
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY),
                permission,
            )
            handler.postDelayed(this, 5_000L)
        }
    }

    fun start() {
        if (started) return
        val filter = IntentFilter(BrowserContract.ACTION_STATUS)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            permission,
            handler,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        started = true
        appSettings.registerOnSharedPreferenceChangeListener(settingsListener)
        broadcastUiSettings()
        handler.post(query)
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(query)
        appSettings.unregisterOnSharedPreferenceChangeListener(settingsListener)
        context.unregisterReceiver(receiver)
    }

    fun open() {
        val settings = currentUiSettings()
        context.startActivity(
            Intent(context, BrowserActivity::class.java)
                .putExtra(BrowserContract.EXTRA_LOCALE_TAG, settings.localeTag)
                .putExtra(BrowserContract.EXTRA_THEME_MODE, settings.themeMode)
                .putExtra(
                    BrowserContract.EXTRA_RETURN_COMPONENT,
                    ComponentName(context, MainActivity::class.java),
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
    }

    private fun currentUiSettings(): BrowserUiSettings {
        val storedMode = appSettings.getString(BrowserUiSettings.APP_THEME_KEY, null)
            ?: AppThemeState.mode.name
        return BrowserUiSettings(
            localeTag = BrowserUiSettings.normalizeLocaleTag(AppLocaleState.currentTag(context)),
            themeMode = BrowserUiSettings.normalizeThemeMode(storedMode),
        )
    }

    private fun broadcastUiSettings() {
        val settings = currentUiSettings()
        context.sendBroadcast(
            Intent(BrowserContract.ACTION_UI_SETTINGS)
                .setPackage(context.packageName)
                .putExtra(BrowserContract.EXTRA_LOCALE_TAG, settings.localeTag)
                .putExtra(BrowserContract.EXTRA_THEME_MODE, settings.themeMode)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY),
            permission,
        )
    }
}
