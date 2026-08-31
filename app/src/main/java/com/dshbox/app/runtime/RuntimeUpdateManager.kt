package com.dshbox.app.runtime

import android.content.Context
import android.util.Log
import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.DshNpmSource
import com.dshbox.app.common.DshSources
import com.dshbox.app.common.Versions
import com.dshbox.app.util.BackgroundOps
import com.dshbox.app.sandbox.DshUpdateOutcome
import com.dshbox.app.sandbox.SandboxManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Result of probing one npm source for @deepseek-ai/dsh registry metadata. */
data class DshSourceProbe(
    val source: DshNpmSource,
    val reachable: Boolean,
    /** Wall-clock ms of the full metadata fetch+parse (download + JSON). */
    val latencyMs: Long,
    /** dist-tags.latest, or null when unreachable / malformed. */
    val latestVersion: String?,
    /** All published versions, newest first. */
    val versions: List<String>,
    val error: String? = null,
)

/** UI-facing state of the online DSH install started from the update screen. */
data class DshOnlineInstallState(
    val running: Boolean = false,
    val stage: String = "",
    /** Recent npm/tar output lines (bounded, oldest first). */
    val logs: List<String> = emptyList(),
    /** Set once the install settles (success or failure). */
    val result: AppResult<DshUpdateOutcome>? = null,
    val cancelled: Boolean = false,
)

/**
 * 1.1.0 (M6/M7) — online DSH update, redesigned:
 *  - probe every source in [DshSources.ALL] IN PARALLEL: latency + dist-tags.latest
 *    + the full published-version list. (1.0.0 probed mirrors one by one and could
 *    then only fail at the never-configured prebuilt-layer download — the feature
 *    could never install anything.)
 *  - install a chosen version from a chosen source by BUILDING the layer inside
 *    the guest via npm ([SandboxManager.installDshFromNpm]) — replicating
 *    runtime-bundle/scripts/install_dsh.sh, the exact way the bundled layer is
 *    produced — then hand it to the normal updateDsh pipeline.
 *
 * The install runs in this manager's own SupervisorJob scope (the manager lives
 * in AppContainer for the whole process), so closing the update screen never
 * aborts a running install; progress is published via [installState]. The guest
 * proot process of the current step is captured so [cancelDshInstall] can tear
 * it down.
 */
class RuntimeUpdateManager(
    private val appContext: Context,
    private val sandboxManager: SandboxManager,
) {
    private val tag = "RuntimeUpdate"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var activeGuestProcess: java.lang.Process? = null
    @Volatile private var installCancelled = false

    private val _installState = MutableStateFlow(DshOnlineInstallState())
    val installState: StateFlow<DshOnlineInstallState> = _installState.asStateFlow()

    // --------------------------------------------------------------- probing

    /**
     * Probes all sources in parallel, reporting each result to [onEach] as soon
     * as it completes (marshalled to the main thread for UI convenience).
     */
    suspend fun probeSources(onEach: (DshSourceProbe) -> Unit) = coroutineScope {
        for (source in DshSources.ALL) {
            launch {
                val probe = probeOne(source)
                withContext(Dispatchers.Main) { onEach(probe) }
            }
        }
    }

    private suspend fun probeOne(source: DshNpmSource): DshSourceProbe = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            val text = downloadText(source.metadataUrl(), connectTimeoutMs = 8_000, readTimeoutMs = 8_000)
            val latency = System.currentTimeMillis() - startedAt
            val json = JSONObject(text)
            val latest = json.optJSONObject("dist-tags")?.optString("latest")?.takeIf { it.isNotBlank() }
            val versions = json.optJSONObject("versions")?.keys()?.asSequence()?.toList().orEmpty()
                .sortedWith { a, b -> Versions.compare(b, a) }
            DshSourceProbe(
                source = source,
                reachable = true,
                latencyMs = latency,
                latestVersion = latest,
                versions = versions,
            )
        } catch (t: Throwable) {
            Log.w(tag, "probe ${source.url} failed: ${t.message}")
            DshSourceProbe(
                source = source,
                reachable = false,
                latencyMs = System.currentTimeMillis() - startedAt,
                latestVersion = null,
                versions = emptyList(),
                error = t.message ?: "未知错误",
            )
        }
    }

    // -------------------------------------------------------------- installing

    /**
     * Starts installing [version] of @deepseek-ai/dsh from [source] in the
     * background. Progress is published on [installState]. No-op while another
     * install is running. [allowDowngrade] must be set for versions not newer
     * than the installed one (the UI double-confirms such a choice first).
     */
    fun startDshInstall(source: DshNpmSource, version: String, allowDowngrade: Boolean) {
        if (_installState.value.running) return
        installCancelled = false
        activeGuestProcess = null
        _installState.value = DshOnlineInstallState(running = true, stage = "准备安装…")
        scope.launch {
            // 1.1.0 (M12.1 P1③)：登记后台操作，阻止设置页清理与其并发——
            // npm 安装写 base/tmp（GUEST_TMP）与 dsh-staging（CACHE），均为清理目标。
            BackgroundOps.runTracked {
                appendLog("· 源：${source.name}（${source.url}）")
                appendLog("· 包：@deepseek-ai/dsh@$version")
                val result = sandboxManager.installDshFromNpm(
                    registryUrl = source.url,
                    version = version,
                    allowDowngrade = allowDowngrade,
                    onStage = { stage -> update { it.copy(stage = stage) } },
                    onLog = ::appendLog,
                    onProcess = { process -> activeGuestProcess = process },
                )
                if (result is AppResult.Failure) {
                    Log.w(tag, "dsh npm install failed: ${result.error.code}: ${result.error.message}")
                }
                _installState.value = _installState.value.copy(
                    running = false,
                    stage = if (result is AppResult.Success) "安装完成" else "安装失败",
                    result = result,
                    cancelled = installCancelled,
                )
                activeGuestProcess = null
            }
        }
    }

    /**
     * Cancels the running install by destroying the current guest proot process;
     * --kill-on-exit cleans the guest-side tree. The pipeline then settles into
     * a failure with [DshOnlineInstallState.cancelled] set.
     */
    fun cancelDshInstall() {
        if (!_installState.value.running) return
        installCancelled = true
        appendLog("· 用户取消了安装")
        runCatching { activeGuestProcess?.destroy() }
    }

    /** Clears a finished install result (e.g. when the user returns to the list). */
    fun clearInstallResult() {
        if (!_installState.value.running) {
            _installState.value = DshOnlineInstallState()
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun update(block: (DshOnlineInstallState) -> DshOnlineInstallState) {
        _installState.value = block(_installState.value)
    }

    private fun appendLog(line: String) {
        // Filter proot/guest linker warnings (meaningless noise), keep real output.
        if (line.contains("WARNING: linker", ignoreCase = true) || line.contains("linkerconfig")) return
        update { state -> state.copy(logs = (state.logs + line).takeLast(MAX_LOG_LINES)) }
    }

    private fun downloadText(
        url: String,
        connectTimeoutMs: Long = 10_000,
        readTimeoutMs: Long = 15_000,
    ): String {
        val conn = open(url, connectTimeoutMs, readTimeoutMs)
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, connectTimeoutMs: Long, readTimeoutMs: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs.toInt()
            readTimeout = readTimeoutMs.toInt()
            setRequestProperty("User-Agent", "DSHBox/1.1")
            setRequestProperty("Accept", "application/json")
            // Do NOT call connect() here: responseCode/getInputStream triggers it lazily
            // (calling it eagerly for HTTPS can surface TLS-only shutdowns on some
            // carrier networks; we let the read path surface the real error instead).
        }

    private companion object {
        const val MAX_LOG_LINES = 400
    }
}
