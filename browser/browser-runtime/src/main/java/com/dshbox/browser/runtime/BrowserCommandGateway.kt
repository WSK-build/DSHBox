package com.dshbox.browser.runtime

/** Main-thread serialized entry point for the active Browser session. */
interface BrowserCommandGateway {
    fun execute(requestJson: String, callback: (String) -> Unit)
    fun cancel(requestId: String, callback: (String) -> Unit)
    fun cancelWait(requestId: String, code: String): Boolean
}

internal object BrowserCommandGatewayRegistry {
    @Volatile
    private var active: BrowserCommandGateway? = null

    fun install(gateway: BrowserCommandGateway) {
        active = gateway
    }

    fun remove(gateway: BrowserCommandGateway) {
        if (active === gateway) active = null
    }

    fun current(): BrowserCommandGateway? = active
}
