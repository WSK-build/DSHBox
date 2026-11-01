package com.dshbox.dsh.renderer.gecko

import android.content.Context
import android.os.Build
import android.os.Looper
import com.dshbox.dsh.renderer.api.DshRenderer
import com.dshbox.dsh.renderer.api.DshRendererAdapter
import com.dshbox.dsh.renderer.api.DshRendererCapabilities
import com.dshbox.dsh.renderer.api.DshRendererConfig
import com.dshbox.dsh.renderer.api.DshRendererFeature
import com.dshbox.dsh.renderer.api.DshRendererHost
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class GeckoDshRendererAdapter : DshRendererAdapter {
    override val id: String = "gecko"
    override val displayName: String = "GeckoView"

    override val capabilities: DshRendererCapabilities = DshRendererCapabilities(
        features = setOf(
            DshRendererFeature.FILE_CHOOSER,
            DshRendererFeature.WEB_PERMISSIONS,
            DshRendererFeature.CUSTOM_SCHEMES,
            DshRendererFeature.FINAL_MAIN_DOCUMENT_STATUS,
            DshRendererFeature.RENDERER_PROCESS_GONE,
            DshRendererFeature.HISTORY_BACK,
            DshRendererFeature.LOCAL_FILE_ACCESS,
            DshRendererFeature.CONTENT_URI_ACCESS,
        ),
    )

    override fun isAvailable(context: Context): Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    override fun create(
        context: Context,
        config: DshRendererConfig,
        host: DshRendererHost,
    ): DshRenderer {
        requireMainThread("GeckoDshRendererAdapter.create")
        return GeckoDshRenderer(
            hostContext = context,
            config = config,
            host = host,
            runtime = GeckoDshRuntime.get(context, config),
            capabilities = capabilities,
        )
    }
}

internal object GeckoDshRuntime {
    private var runtime: GeckoRuntime? = null

    fun get(context: Context, config: DshRendererConfig): GeckoRuntime {
        requireMainThread("GeckoDshRuntime.get")
        runtime?.let { return it }

        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .consoleOutput(config.debuggingEnabled)
            .remoteDebuggingEnabled(config.debuggingEnabled)
            .debugLogging(config.debuggingEnabled)
            .build()
        return GeckoRuntime.create(context.applicationContext, settings).also {
            runtime = it
        }
    }
}

internal fun requireMainThread(operation: String) {
    check(Looper.myLooper() == Looper.getMainLooper()) {
        "$operation must run on the main thread"
    }
}
