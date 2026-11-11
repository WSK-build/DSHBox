package com.dshbox.remote.render

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebStorage
import com.dshbox.remote.session.CookieNameParser
import com.dshbox.remote.session.SessionCleanupTargets
import com.dshbox.remote.session.SessionDataCleaner

/**
 * [SessionDataCleaner] 的 systemWebView 变体实现：
 * - cookie：按**完整 cookie URL** 走进程级 [CookieManager]——对每个
 *   `scheme://host/` 目标先读现存 cookie（https 站点的 `Secure` cookie 只在
 *   https URL 下被读出；`HttpOnly` 不影响——原生 CookieManager 不受 JS 的
 *   httpOnly 限制，可清），再对该 URL 逐名按属性正确地过期置回（变体枚举
 *   收口在 [SessionCleanupTargets.cookieExpireValuesFor]：`__Host-` 仅
 *   `Secure; Path=/`、`__Secure-` 带 `Secure` 加两种 Domain 变体、普通名
 *   带/不带 Secure 各一套加两种 Domain 变体；分区（Partitioned）cookie 变体
 *   的口径由 `SessionCleanupTargets` 统一产出——涵盖了带分区属性的上游
 *   会话 cookie 的置回形态，见该函数的 KDoc 与用例）。全部主线程执行、逐条
 *   [runCatching]——任一置回失败不阻断同 URL 其余 cookie 与后续 URL 的清
 *   理。进程级 CookieManager 没有「按 host 清空」的 API，`removeAllCookies`
 *   会连带本机 DSH 页签的会话，禁止使用。
 * - 站点数据：按 origin 走 [WebStorage.deleteOrigin]（localStorage/
 *   IndexedDB 等按 origin 存储的明文数据）。
 *
 * Path 清理边界（known limitation）：`Path=/` 的过期只命中根路径 cookie，
 * 上游以非根 Path 下发的 cookie 可能遗留（CookieManager 不暴露各 path 的
 * 清单，逐 path 穷举不可实现）；注册域之外的上游 `Domain=.` 下发域 cookie
 * 同理（同族站点已作为独立精确目标产出，其余记录为已知限制）。回环风险由
 * 「槽位回收后新设备终身换用随机端口、旧 origin 永不可达」兜底。
 *
 * 与 gecko 变体的分工：本类只覆盖 systemWebView 渲染路径的进程级清理面
 * （CookieManager + WebStorage）；gecko 路径由 Gecko 会话自己的数据收口
 * 负责，两者共享 [SessionCleanupTargets] 产出的目标清单，不各写一份。
 */
class WebViewSessionDataCleaner(context: Context) : SessionDataCleaner {

    private val appContext = context.applicationContext
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun clearOrigins(origins: List<String>, cookieUrls: List<String>) {
        // 在主线程排队执行（CookieManager.getInstance 初始化与 setCookie 都
        // 须在主线程）；调用线程立即返回——清理是尽力收口，不阻塞删除/建
        // 立流程。两个收口（cookie 与站点数据）彼此独立、逐条容错。
        mainHandler.post {
            runCatching { clearCookies(cookieUrls) }
                .onFailure { Log.w(TAG, "cookie clearing failed (${it.javaClass.simpleName})") }
            runCatching { clearWebStorage(origins) }
                .onFailure { Log.w(TAG, "web storage clearing failed (${it.javaClass.simpleName})") }
        }
    }

    private fun clearCookies(cookieUrls: List<String>) {
        val manager = CookieManager.getInstance()
        for (cookieUrl in cookieUrls) {
            val header = runCatching { manager.getCookie(cookieUrl) }.getOrNull() ?: continue
            for (name in CookieNameParser.names(header)) {
                for (value in SessionCleanupTargets.cookieExpireValuesFor(name, cookieUrl)) {
                    runCatching { manager.setCookie(cookieUrl, value, ValueCallback { }) }
                }
            }
        }
    }

    private fun clearWebStorage(origins: List<String>) {
        val storage = WebStorage.getInstance()
        for (origin in origins) {
            runCatching { storage.deleteOrigin(origin) }
        }
    }

    private companion object {
        const val TAG = "WebViewSessionClean"
    }
}
