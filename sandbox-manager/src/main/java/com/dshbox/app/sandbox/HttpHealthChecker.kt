package com.dshbox.app.sandbox

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * HTTP health checker for the local DSH WebUI. A Ready decision requires
 * the port to be open and an HTTP probe to return any HTTP response (not
 * necessarily 200; DSH is an SPA, so 200/302/404 on a local route may still
 * mean the webserver is alive).
 */
class HttpHealthChecker(
    private val host: String = Constants.DSH_DEFAULT_HOST,
    private val port: Int = Constants.DSH_DEFAULT_PORT,
    private val path: String = "/",
    private val connectTimeoutMs: Int = 2_000,
    private val readTimeoutMs: Int = 3_000,
) : SandboxHealthChecker {

    override suspend fun check(): SandboxHealth = withContext(Dispatchers.IO) {
        val portOpen = isPortOpen(host, port, connectTimeoutMs)
        val httpAlive = if (portOpen) httpProbe() else false
        SandboxHealth(
            dshProcessRunning = portOpen,
            portOpen = portOpen,
            webUiReady = httpAlive,
        )
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }

    private fun httpProbe(): Boolean =
        try {
            val connection = URL("http://$host:$port$path").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            connection.disconnect()
            // 1.1.1 (M9)：DSH 0.1.2-rc.1 起 webserver 启用 token 认证（dsh-host-webserver），
            // 无 token 时 `/` 返回 401——此前只认 200..299 导致 DSH 被误判「未就绪」：
            // 健康循环 120s 超时置 ERROR 后退场，但真实 DSH 进程仍占着 3080，后续一切
            // 启动动作全部 EADDRINUSE（真机实证 401 + EADDRINUSE 链）。任何 HTTP 响应
            // （含 401/302/404/5xx）都说明 webserver 存活，只把「连不上/超时」判为不健康。
            code in 200..599
        } catch (_: Exception) {
            false
        }
}

fun SandboxHealth.toAppResult(): AppResult<SandboxHealth> =
    if (webUiReady) AppResult.Success(this)
    else AppResult.Failure(AppError("DSH_NOT_READY", "DSH WebUI is not ready", recoverable = true))
