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
import java.io.File
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
    /** 1.1.1 (T1)：安装开始时刻（System.currentTimeMillis，用于展示已用时）。 */
    val startedAtMs: Long = 0L,
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
        _installState.value = DshOnlineInstallState(running = true, stage = "准备安装…", startedAtMs = System.currentTimeMillis())
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
                    // 1.1.1 (M7)：取消标志透传给 guest 命令等待循环——点「取消」后
                    // waitFor 语义被轮询取代，~300ms 内整套安装即收敛。
                    shouldAbort = { installCancelled },
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
     * Cancels the running install. 1.1.1 (M4): the original implementation only
     * called [Process.destroy] (SIGTERM), which is INERT for PRoot — proot
     * relays SIGTERM into the guest and itself stays alive, so `--kill-on-exit`
     * never fires and `waitFor()` never returns; the install appears stuck and
     * cancel looks dead (verified on device: proot/sh/npm all survived the tap).
     * Now the whole guest tree under the proot pid is SIGKILLed leaf-first
     * (same /proc-tree approach as SandboxProcessRunner.stop), with
     * [Process.destroyForcibly] as a backstop. The pipeline then settles into a
     * failure with [DshOnlineInstallState.cancelled] set.
     *
     * 1.1.1 (M6): the tree kill (full /proc scan + one `/system/bin/kill -KILL`
     * spawn per pid, each waited) used to run synchronously on the UI thread,
     * freezing the screen for ~1-3s so cancel felt dead/slow; it now runs on
     * this manager's own background scope and the button responds instantly.
     */
    fun cancelDshInstall() {
        if (!_installState.value.running) return
        installCancelled = true
        appendLog("· 用户取消了安装")
        scope.launch {
            // M6.2：真机诊断（logcat cancel: procRegistered=true pid=null ...
            // destroyForcibly executed=true 但 proot 存活）证实两条原路都不可靠：
            //   1) 反射 Process.pid() 在 e.g. 该机返回 null → SIGKILL 树杀从未执行；
            //   2) destroyForcibly() 实际只发 SIGTERM，PRoot 会转发它而自己不退出。
            // 改为按 cmdline 特征定位安装 proot（--kill-on-exit 树里唯一含
            // "dsh-stage" 的 libproot，覆盖 npm 安装与 tar 打包两阶段），直接 SIGKILL。
            val tree = findInstallProotTree()
            Log.i(tag, "cancel: cmdlineTree=${tree}")
            if (tree.isEmpty()) {
                // 位置找不到时的兜底：句柄直接 destroyForcibly（通常无效，但无害）。
                runCatching { activeGuestProcess?.destroyForcibly() }
                return@launch
            }
            // Children first, proot root last（与 SandboxProcessRunner.stop 同序）。
            tree.asReversed().forEach { p ->
                runCatching {
                    ProcessBuilder("/system/bin/kill", "-KILL", p.toString()).start().waitFor()
                }
            }
        }
    }

    /**
     * 定位在线安装的 proot 及其整棵 guest 进程树（M6.2）。特征：cmdline 含
     * "dsh-stage"（npm 阶段 `--prefix /tmp/dsh-stage` 与打包阶段 `tar -C
     * /tmp/dsh-stage` 都带）且以 libproot 二进制为 argv0 的进程即安装 proot；
     * 返回 [proot + 全部后代]；找不到返回空列表。
     * 注：cmdline 的 argv0 是 proot 的**完整路径**（如 /data/app/.../libproot.so），
     * 不能 startsWith("libproot")（M6.2 首版此判断恒为 false，树杀从未生效）。
     */
    private fun findInstallProotTree(): List<Int> {
        val table = readProcTable() ?: return emptyList()
        val root = table.keys.firstOrNull { pid ->
            val cmdline = readCmdline(pid)
            cmdline != null &&
                cmdline.contains("libproot.so") &&
                cmdline.contains("dsh-stage")
        } ?: return emptyList()
        return listOf(root) + descendantPids(table, root)
    }

    private fun readCmdline(pid: Int): String? =
        runCatching { File("/proc/$pid/cmdline").readText().replace('\u0000', ' ') }.getOrNull()

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

    /** pid -> ppid snapshot of the whole /proc table, or null when unreadable. */
    private fun readProcTable(): Map<Int, Int>? {
        val dir = File("/proc")
        val entries = dir.listFiles { f -> f.name.all { it.isDigit() } } ?: return null
        val map = HashMap<Int, Int>()
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            val stat = runCatching { entry.resolve("stat").readText() }.getOrNull() ?: continue
            val ppid = stat.substringAfter(") ").trim().split(' ').getOrNull(1)?.toIntOrNull() ?: continue
            map[pid] = ppid
        }
        return map
    }

    /** All descendants of [root], BFS order (parents before children). */
    private fun descendantPids(all: Map<Int, Int>, root: Int): List<Int> {
        val result = mutableListOf<Int>()
        val queue = ArrayDeque(all.filterValues { it == root }.keys)
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            result.add(pid)
            queue.addAll(all.filterValues { it == pid }.keys)
        }
        return result
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
