package com.dshbox.app.dsh

import android.os.Looper
import android.webkit.WebView
import com.dshbox.app.BuildConfig
import com.dshbox.dsh.renderer.api.DshRenderIdentity
import com.dshbox.dsh.renderer.api.DshWebCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which renderer implementation this APK actually uses. */
enum class DshRendererEngine {
    GECKO,
    SYSTEM_WEBVIEW,
    UNKNOWN,
}

/** Observable load stage. It never means that DSH's UI or conversations are ready. */
enum class DshRendererPhase {
    NOT_COLLECTED,
    STARTING,
    PAGE_STARTED,
    PAGE_STOPPED,
    FAILED,
    RELEASED,
}

/**
 * A deliberately small interpretation of [DshRendererPhase].
 *
 * No report can establish "ready" from CSS/UA/capability data. A real business
 * workflow must remain outside this store and be verified separately.
 */
enum class DshRendererPageMeaning {
    NOT_COLLECTED,
    LOADING,
    STOPPED_BUT_NOT_READY,
    FAILED,
    RELEASED,
}

data class DshSystemWebViewProvider(
    val packageName: String,
    val versionName: String?,
)

/**
 * Public report data. Identity, navigation ids, URLs, tokens and page text are
 * intentionally absent.
 */
data class DshRendererReport(
    val engine: DshRendererEngine,
    val engineBuild: String?,
    val phase: DshRendererPhase,
    val provider: DshSystemWebViewProvider?,
    val userAgent: String?,
    val nativeCapabilities: Map<DshWebCapability, Boolean>?,
    val effectiveCapabilities: Map<DshWebCapability, Boolean>,
    val capabilitiesObserved: Boolean,
    val updatedAtMillis: Long? = null,
    val workerCapabilities: Map<DshWebCapability, Boolean>? = null,
    val workerDetails: Map<String, Boolean> = emptyMap(),
)

internal val DshRendererReport.pageMeaning: DshRendererPageMeaning
    get() = when (phase) {
        DshRendererPhase.NOT_COLLECTED -> DshRendererPageMeaning.NOT_COLLECTED
        DshRendererPhase.STARTING,
        DshRendererPhase.PAGE_STARTED -> DshRendererPageMeaning.LOADING
        DshRendererPhase.PAGE_STOPPED -> DshRendererPageMeaning.STOPPED_BUT_NOT_READY
        DshRendererPhase.FAILED -> DshRendererPageMeaning.FAILED
        DshRendererPhase.RELEASED -> DshRendererPageMeaning.RELEASED
    }

/**
 * Main-thread report sink shared by onboarding and diagnostics.
 *
 * Only events carrying the current [DshRenderIdentity] are accepted. A released
 * instance is removed immediately, so a late callback from a rebuilt container
 * cannot overwrite the new report.
 */
object DshRendererReportStore {
    private var currentIdentity: DshRenderIdentity? = null
    private val _report = MutableStateFlow(initialReport())
    val report: StateFlow<DshRendererReport> = _report.asStateFlow()

    /** Engine id selected from the build flavor; this does not create a runtime. */
    val environmentEngineId: String
        get() = when (BuildConfig.FLAVOR) {
            "gecko" -> "gecko"
            else -> "system-webview"
        }

    fun begin(engineId: String, identity: DshRenderIdentity) {
        requireMainThread()
        currentIdentity = identity
        val engine = engineOf(engineId)
        _report.value = DshRendererReport(
            engine = engine,
            engineBuild = engineBuildOf(engine),
            phase = DshRendererPhase.STARTING,
            provider = if (engine == DshRendererEngine.SYSTEM_WEBVIEW) readSystemWebViewProvider() else null,
            userAgent = null,
            nativeCapabilities = null,
            effectiveCapabilities = emptyMap(),
            capabilitiesObserved = false,
            workerCapabilities = null,
            workerDetails = emptyMap(),
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    fun pageStarted(identity: DshRenderIdentity) {
        updateCurrent(identity) {
            it.copy(
                phase = DshRendererPhase.PAGE_STARTED,
                userAgent = null,
                nativeCapabilities = null,
                effectiveCapabilities = emptyMap(),
                capabilitiesObserved = false,
                workerCapabilities = null,
                workerDetails = emptyMap(),
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    fun pageStopped(identity: DshRenderIdentity) {
        updateCurrent(identity) {
            it.copy(
                phase = DshRendererPhase.PAGE_STOPPED,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Records an instance failure without retaining error text.
     *
     * A null identity is the initialization-failure path: it clears the current
     * identity so no later callback can attach itself to the failed report.
     */
    fun failed(identity: DshRenderIdentity? = null) {
        requireMainThread()
        if (identity == null) {
            currentIdentity = null
            _report.value = _report.value.copy(
                phase = DshRendererPhase.FAILED,
                userAgent = null,
                nativeCapabilities = null,
                effectiveCapabilities = emptyMap(),
                capabilitiesObserved = false,
                workerCapabilities = null,
                workerDetails = emptyMap(),
                updatedAtMillis = System.currentTimeMillis(),
            )
            return
        }
        updateCurrent(identity) {
            it.copy(
                phase = DshRendererPhase.FAILED,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    fun released(identity: DshRenderIdentity) {
        requireMainThread()
        if (currentIdentity != identity) return
        currentIdentity = null
        _report.value = _report.value.copy(
            phase = DshRendererPhase.RELEASED,
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    fun observeCapabilities(
        identity: DshRenderIdentity,
        native: Map<DshWebCapability, Boolean>?,
        effective: Map<DshWebCapability, Boolean>,
        userAgent: String?,
    ) {
        requireMainThread()
        if (currentIdentity != identity) return
        val safeNative = native?.let(::safeCapabilities)
        val safeEffective = safeCapabilities(effective)
        _report.value = _report.value.copy(
            userAgent = safeUserAgent(userAgent),
            nativeCapabilities = safeNative,
            effectiveCapabilities = safeEffective,
            capabilitiesObserved = safeNative?.isNotEmpty() == true || safeEffective.isNotEmpty(),
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    fun observeWorkerCapabilities(
        identity: DshRenderIdentity,
        capabilities: Map<DshWebCapability, Boolean>,
        details: Map<String, Boolean>,
    ) {
        requireMainThread()
        if (currentIdentity != identity) return
        val safeCapabilities = safeCapabilities(capabilities)
        if (safeCapabilities.isEmpty()) return
        _report.value = _report.value.copy(
            workerCapabilities = safeCapabilities,
            workerDetails = safeWorkerDetails(details),
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    private inline fun updateCurrent(
        identity: DshRenderIdentity,
        transform: (DshRendererReport) -> DshRendererReport,
    ) {
        requireMainThread()
        if (currentIdentity != identity) return
        _report.value = transform(_report.value)
    }

    private fun initialReport(): DshRendererReport {
        val engine = engineOf(environmentEngineId)
        return DshRendererReport(
            engine = engine,
            engineBuild = engineBuildOf(engine),
            phase = DshRendererPhase.NOT_COLLECTED,
            provider = if (engine == DshRendererEngine.SYSTEM_WEBVIEW) readSystemWebViewProvider() else null,
            userAgent = null,
            nativeCapabilities = null,
            effectiveCapabilities = emptyMap(),
            capabilitiesObserved = false,
            workerCapabilities = null,
            workerDetails = emptyMap(),
            updatedAtMillis = null,
        )
    }

    private fun engineOf(engineId: String): DshRendererEngine = when (engineId) {
        "gecko" -> DshRendererEngine.GECKO
        "system-webview", "systemWebView" -> DshRendererEngine.SYSTEM_WEBVIEW
        else -> DshRendererEngine.UNKNOWN
    }

    private fun engineBuildOf(engine: DshRendererEngine): String? =
        if (engine == DshRendererEngine.GECKO) BuildConfig.DSH_GECKOVIEW_VERSION else null

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "DshRendererReportStore must be updated on the main thread"
        }
    }
}

internal fun readSystemWebViewProvider(): DshSystemWebViewProvider? = runCatching {
    val current = WebView.getCurrentWebViewPackage()
    systemWebViewProvider(current?.packageName, current?.versionName)
}.getOrNull()

internal fun systemWebViewProvider(
    packageName: String?,
    versionName: String?,
): DshSystemWebViewProvider? {
    val safePackage = packageName?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return DshSystemWebViewProvider(
        packageName = safePackage,
        versionName = versionName?.trim()?.takeIf(String::isNotEmpty),
    )
}

internal fun safeUserAgent(value: String?): String? {
    if (value == null) return null
    val safe = buildString {
        for (char in value) {
            append(if (char in ' '..'~') char else ' ')
            if (length >= MAX_USER_AGENT_LENGTH) break
        }
    }.replace(Regex("\\s+"), " ").trim()
    return safe.ifEmpty { null }
}

private fun safeCapabilities(
    source: Map<DshWebCapability, Boolean>,
): Map<DshWebCapability, Boolean> = buildMap {
    for (capability in DshWebCapability.entries) {
        source[capability]?.let { put(capability, it) }
    }
}

private fun safeWorkerDetails(
    source: Map<String, Boolean>,
): Map<String, Boolean> = buildMap {
    for ((name, value) in source) {
        val safeName = name.trim()
        if (!WORKER_DETAIL_NAME.matches(safeName)) continue
        put(safeName, value)
        if (size >= MAX_WORKER_DETAILS) break
    }
}

private val WORKER_DETAIL_NAME = Regex("""[A-Z][A-Z0-9_]{0,63}""")
private const val MAX_USER_AGENT_LENGTH = 512
private const val MAX_WORKER_DETAILS = 32
