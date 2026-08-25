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
            code in 200..299
        } catch (_: Exception) {
            false
        }
}

fun SandboxHealth.toAppResult(): AppResult<SandboxHealth> =
    if (webUiReady) AppResult.Success(this)
    else AppResult.Failure(AppError("DSH_NOT_READY", "DSH WebUI is not ready", recoverable = true))
