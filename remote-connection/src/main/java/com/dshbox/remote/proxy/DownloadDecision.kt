package com.dshbox.remote.proxy

/**
 * 下载判定（纯函数，JVM 可测）：主框架响应是否按「下载」处置。
 *
 * 远程页面经回环代理取数，代理层面无法直接感知请求是否为主框架导航，因此
 * 下载分支由两个纯判定共同把守：
 *
 *  1. [isNavigationRequest] —— 请求侧「主框架导航」近似：导航请求由引擎发出
 *     时携带 `Sec-Fetch-Dest: document`，或 Accept 里含 `text/html`；图片、
 *     字体、脚本等子资源请求两者皆无。缺这层把守时，图片类 Content-Type 的
 *     放行规则会把页面内嵌图片也吞进下载分支，页面渲染直接损坏。
 *  2. [isDownload] —— 响应侧「这是文件而非文档」：`Content-Disposition:
 *     attachment` 优先；否则按 Content-Type 放行文件类（octet-stream、
 *     zip、pdf、图片类、csv 等），拦下 HTML/JSON/JS/CSS/SSE 等页面与数据
 *     格式（它们必须照旧透传给渲染器）。
 */
internal object DownloadDecision {

    /** 明确「页面 / 数据」语义的 Content-Type：命中即不下载（attachment 除外）。 */
    private val NON_DOWNLOAD_TYPES = setOf(
        "text/html",
        "application/xhtml+xml",
        "application/json",
        "application/ld+json",
        "text/css",
        "text/javascript",
        "application/javascript",
        "application/x-javascript",
        "application/ecmascript",
        "application/xml",
        "text/xml",
        "text/event-stream",
        "text/plain",
    )

    /**
     * 响应头判定：是否应作为下载落盘。
     *
     * 判定顺序：`Content-Disposition: attachment` 一票通过（部分服务对二进制
     * 附件不给出规范的 Content-Type）；否则按 Content-Type 主值（去参数、
     * 小写）放行文件类、拦下页面类。无 Content-Type 且无 attachment 时无信号
     * ——照旧透传。
     */
    fun isDownload(responseHeaders: List<ProxyHeader>): Boolean {
        val disposition = responseHeaders.firstOrNull {
            it.name.equals("Content-Disposition", ignoreCase = true)
        }?.value
        if (disposition != null && disposition.substringBefore(';').trim()
                .equals("attachment", ignoreCase = true)
        ) {
            return true
        }
        val contentType = responseHeaders.firstOrNull {
            it.name.equals("Content-Type", ignoreCase = true)
        }?.value?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (contentType.isEmpty()) return false
        if (contentType.endsWith("+json")) return false
        return contentType !in NON_DOWNLOAD_TYPES
    }

    /**
     * 请求头判定：是否为主框架导航请求。`Sec-Fetch-Dest: document` 是现代
     * 引擎的主框架导航签名；引擎不发 Sec-Fetch 时以「Accept 含 text/html」
     * 兜底（导航请求的 Accept 由引擎统一发出，子资源请求不含）。
     */
    fun isNavigationRequest(requestHeaders: List<ProxyHeader>): Boolean {
        val fetchDest = requestHeaders.firstOrNull {
            it.name.equals("Sec-Fetch-Dest", ignoreCase = true)
        }?.value?.substringBefore(';')?.trim()?.lowercase()
        if (fetchDest == "document") return true
        val accept = requestHeaders.firstOrNull {
            it.name.equals("Accept", ignoreCase = true)
        }?.value.orEmpty()
        return accept.lowercase().contains("text/html")
    }
}
