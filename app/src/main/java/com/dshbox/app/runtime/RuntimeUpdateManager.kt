package com.dshbox.app.runtime

import android.content.Context
import android.util.Log
import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.Constants
import com.dshbox.app.sandbox.DshUpdateOutcome
import com.dshbox.app.sandbox.SandboxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * §7.6 — online DSH update via mirror source.
 *
 * Flow (per plan §7.6):
 *   1. Probe the npm mirror(s) for `@deepseek-ai/dsh` latest version.
 *   2. Compare with the installed DSH layer (arbitration: installed >= latest -> keep).
 *   3. If newer, download the prebuilt `dsh_layer.tar.zst` (+ .sha256) from
 *      [Constants.DSH_LAYER_BASE_URL].
 *   4. Verify SHA-256, then hand off to [SandboxManager.updateDsh] which does a
 *      single-live-copy replace (old -> previous/dsh) + rollback. Never touches
 *      user-data/.dsh.
 *
 * The mirror probe is real; the prebuilt-layer download requires a configured
 * [Constants.DSH_LAYER_BASE_URL] host (deployment concern) and fails gracefully
 * when absent.
 */
class RuntimeUpdateManager(
    private val appContext: Context,
    private val sandboxManager: SandboxManager,
) {
    private val tag = "RuntimeUpdate"

    suspend fun updateDshLatest(onProgress: (String) -> Unit): AppResult<DshUpdateOutcome> =
        withContext(Dispatchers.IO) {
            onProgress("正在探测 DSH 最新版本…")
            val latest = runCatching { queryLatestVersion() }.getOrElse { t ->
                Log.w(tag, "mirror probe failed: ${t.message}")
                return@withContext AppResult.Failure(AppError("NETWORK_ERROR", "无法连接镜像源：${t.message}"))
            }
            if (latest.isNullOrBlank()) {
                onProgress("")
                return@withContext AppResult.Failure(AppError("NO_LATEST", "未获取到 DSH 最新版本"))
            }
            val installed = sandboxManager.dshVersion.value
            if (installed != null && compareVersions(installed, latest) >= 0) {
                onProgress("")
                return@withContext AppResult.Success(DshUpdateOutcome(version = installed, changed = false))
            }

            val base = Constants.DSH_LAYER_BASE_URL.trim().trimEnd('/')
            if (base.isEmpty()) {
                onProgress("")
                return@withContext AppResult.Failure(AppError("NO_UPDATE_SOURCE", "在线更新源未配置（DSH_LAYER_BASE_URL）"))
            }
            val layerUrl = "$base/$latest/dsh_layer.tar.zst"
            val shaUrl = "$layerUrl.sha256"
            onProgress("正在下载 DSH $latest …")
            val layer = File(appContext.cacheDir, "dsh-online-$latest.tar.zst")
            try {
                downloadToFile(layerUrl, layer)
            } catch (t: Throwable) {
                Log.e(tag, "download failed: ${t.message}", t)
                onProgress("")
                return@withContext AppResult.Failure(AppError("DOWNLOAD_FAILED", "下载失败：${t.message}"))
            }
            val sha = runCatching { downloadText(shaUrl).trim().split(Regex("\\s+")).firstOrNull() }.getOrNull()
            onProgress("正在安装 DSH $latest …")
            val result = sandboxManager.updateDsh(layer, sha, latest)
            onProgress("")
            result
        }

    /** Query the npm mirrors for @deepseek-ai/dsh dist-tags.latest; try each until one succeeds. */
    private fun queryLatestVersion(): String {
        val errors = mutableListOf<String>()
        for (mirror in Constants.DSH_MIRRORS) {
            try {
                return downloadText("$mirror/@deepseek-ai/dsh")
                    .let { JSONObject(it).optJSONObject("dist-tags")?.optString("latest")?.takeIf { v -> v.isNotBlank() } }
                    ?: throw IllegalStateException("no dist-tags.latest")
            } catch (t: Throwable) {
                errors.add("$mirror: ${t.message}")
            }
        }
        throw IllegalStateException("所有镜像源均不可达：" + errors.joinToString("；"))
    }

    private fun downloadText(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode} for $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadToFile(url: String, target: File) {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode} for $url")
            conn.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "DSHapp/0.1")
            setRequestProperty("Accept", "application/json")
            // Do NOT call connect() here: responseCode/getInputStream triggers it lazily
            // (calling it eagerly for HTTPS can surface TLS-only shutdowns on some
            // carrier networks; we let the read path surface the real error instead).
        }

    /** Best-effort semantic-ish compare; mirrors DshLayer.compareVersions. */
    internal fun compareVersions(a: String, b: String): Int {
        val clean = { s: String -> s.trim().trimStart('v').split('-').first() }
        val pa = clean(a).split('.').mapNotNull { it.toIntOrNull() }
        val pb = clean(b).split('.').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        val ra = a.split('-').drop(1).joinToString("-")
        val rb = b.split('-').drop(1).joinToString("-")
        return if (ra == rb) 0 else ra.compareTo(rb)
    }
}
