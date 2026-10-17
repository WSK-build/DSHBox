package com.dshbox.browser.runtime

import android.content.Context
import android.content.res.Configuration

/**
 * Single construction entry for the background session, shared by the command service and the
 * activity (cold AI open and user-initiated windows alike). It allocates the persistent session
 * generation, builds an application-context page factory from local settings (never capturing a
 * Service or Activity), and installs the process registries. Reuses any existing session.
 */
internal object BrowserSessionFactory {
    /** Returns the existing session, or creates one; null when generation cannot be reserved. */
    fun createOrReuse(context: Context): BrowserSessionGateway? {
        BrowserSessionGatewayRegistry.current()?.let { return it }
        val appContext = context.applicationContext ?: context
        val generation = reserveGeneration(appContext) ?: return null
        val factory = BrowserPageFactory(
            context = appContext,
            renderingSettings = {
                BrowserRenderingPreferences.read(BrowserRuntimeSettings.preferences(appContext))
            },
            themePolicy = {
                val configuration = appContext.resources.configuration
                val systemDark = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
                BrowserWebThemePolicy.resolve(
                    BrowserRuntimeSettings.preferences(appContext)
                        .getString(BrowserRuntimeSettings.KEY_THEME_MODE, null),
                    systemDark,
                )
            },
        )
        val host = BrowserSessionHost(
            context = appContext,
            idleTimeoutMillis = BrowserSessionHost.idleTimeoutFromPreferences(appContext),
            pageFactory = { factory.create() },
            initialSessionGeneration = generation,
        )
        BrowserSessionHostRegistry.install(host)
        return BrowserSessionGateway(appContext, host, factory).also {
            BrowserSessionGatewayRegistry.install(it)
        }
    }

    /** Mirrors the persistent generation reservation so recreated sessions never reuse a value. */
    private fun reserveGeneration(context: Context): Long? {
        val preferences = BrowserRuntimeSettings.preferences(context)
        val start = preferences.getLong(BrowserRuntimeSettings.KEY_NEXT_SESSION_GENERATION, 0L)
        val next = BrowserSessionGeneration.reserve(start) ?: return null
        val committed = preferences.edit().putLong(BrowserRuntimeSettings.KEY_NEXT_SESSION_GENERATION, next).commit()
        return start.takeIf { committed }
    }
}

/** Pure, testable generation reservation shared by every session construction path. */
internal object BrowserSessionGeneration {
    /** Returns the next reserved counter for [start], or null when the counter would overflow. */
    fun reserve(start: Long): Long? {
        if (start < 0L ||
            start > BrowserRuntimeSettings.MAX_SAFE_GENERATION - BrowserRuntimeSettings.SESSION_GENERATION_RESERVATION
        ) return null
        return start + BrowserRuntimeSettings.SESSION_GENERATION_RESERVATION
    }
}
