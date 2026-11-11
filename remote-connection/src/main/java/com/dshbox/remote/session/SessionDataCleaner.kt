package com.dshbox.remote.session

/**
 * 设备删除/槽位复用时的渲染器侧站点数据清理口。生产恒用 WebView 清理实
 * 现（进程级 CookieManager 与 WebStorage）；gecko 变体的会话存储不在系统
 * WebView 中，此实现抹除不到，属已知限制（会话数据随 gecko 会话存续）。
 *
 * 清理口径与浏览器存储模型一一对应：
 * - [cookieUrls] 按**完整 cookie URL**（scheme://host[:port]/）清 cookie
 *   （cookie 不区分端口但区分 scheme 的可见性：https 站点的 `Secure`
 *   cookie 只在 https URL 下枚举/写回，PF host 层级覆盖该 host 的会话）；
 * - [origins] 按 **origin**（scheme://host:port）清站点数据
 *   （localStorage/IndexedDB 等按 origin 存储）。
 *
 * 已知限制（cookie 清理边界）：过期置回只带 `Path=/`——上游曾以非根 Path
 * 下发的 cookie（如 `Path=/zone`）不在标准清除语义内可能遗留（CookieManager
 * 不暴露各 path 的清单，逐 path 穷举不可实现）；`Domain=` 级候选见实现层
 * （对每名字逐一尝试根 Path 与域级写法）。记录为已知限制而非缺陷。
 */
interface SessionDataCleaner {

    /**
     * 清理给定 cookie URL 的 cookie 与 origins 的站点数据。两个清单各自
     * 独立，可为空；实现必须幂等且不抛异常（清理失败不阻断设备删除/会
     * 话建立）。
     */
    fun clearOrigins(origins: List<String>, cookieUrls: List<String>)
}

/**
 * cookie 头文本的名称解析（纯函数，JVM 单测锁定）：`getCookie(url)` 返回
 * 的 `name=value; name2=value2` 串拆出 cookie 名清单。空/空白串返回空清
 * 单，无 `=` 的段丢弃（异常串容忍），值两侧空白条目丢弃。
 */
object CookieNameParser {

    fun names(cookieHeader: String?): List<String> {
        if (cookieHeader.isNullOrBlank()) return emptyList()
        return cookieHeader.split(';')
            .map { it.trim() }
            .mapNotNull { entry ->
                val eq = entry.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                entry.take(eq).trim().takeIf { it.isNotEmpty() }
            }
    }
}
