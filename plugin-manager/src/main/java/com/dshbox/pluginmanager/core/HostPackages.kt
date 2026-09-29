package com.dshbox.pluginmanager.core

import android.util.Log
import java.io.File

/**
 * 「官方包」的**运行时推导**，取代写死的名单。
 *
 * 为什么不用一份硬编码清单：DSH 官方会持续加/改包，写死的名单一定会陈旧，而误把官方
 * 条目当成第三方去停用，后果是**整机起不来**（真机上已经发生过一次）。所以这里按
 * "安装层实际有什么"来推：
 *
 * - `runtime-current/dsh/node_modules/@deepseek-ai/` 下的全部包（DSH 层自带）
 * - `runtime-current/node_modules/` 下的同名依赖（例如 `cordis` 这类裸名包）
 *
 * 推导之外还叠两条**永不陈旧**的规则，宁可多保护不可漏保护：
 * 1. 命名空间前缀 `@deepseek-ai/`；
 * 2. loader 自己的合成条目命名空间 `cordis:`。
 *
 * 推导拿不到时（目录缺失、读取失败）只保留上面两条规则；判不清楚的条目一律**拒绝**
 * 停用——见 [isHostPackage] 与调用方的保守处置。
 */
object HostPackages {

    private const val TAG = "PluginGuard"

    /** 命名空间前缀：官方包永远属于官方。 */
    private const val OFFICIAL_NAMESPACE = "@deepseek-ai/"

    /** loader 合成的条目（include / loader / group / base…）所在的命名空间。 */
    private const val LOADER_NAMESPACE = "cordis:"

    /**
     * 扫描安装层，得到"这台机器上安装层实际自带的包名"。
     *
     * 结果只用于**加宽**保护范围，不用于缩小；扫描失败返回空集合，调用方仍有前缀规则兜底。
     */
    fun installedPackages(paths: PluginPaths): Set<String> {
        val roots = listOf(
            File(paths.runtimeCurrentDir, "dsh/node_modules"),
            File(paths.runtimeCurrentDir, "node_modules"),
        )
        val names = mutableSetOf<String>()
        roots.forEach { root ->
            if (!root.isDirectory) return@forEach
            // 作用域包（@scope/name）与裸包名都要收：安装层里两者都有
            // （例如 `@deepseek-ai/dsh-tools` 与 `cordis`）。
            root.listFiles()?.forEach { entry ->
                val entryName = entry.name
                if (entryName.startsWith("@")) {
                    entry.listFiles()?.forEach { scoped ->
                        if (scoped.isDirectory) names.add("$entryName/${scoped.name}")
                    }
                } else if (entry.isDirectory) {
                    names.add(entryName)
                }
            }
        }
        if (names.isEmpty()) Log.w(TAG, "host package scan found nothing; falling back to namespace rules")
        return names
    }

    /**
     * 是否是宿主自带（官方）的包。
     *
     * @param installed [installedPackages] 的结果；空集合时只靠命名空间规则
     */
    fun isHostPackage(name: String?, installed: Set<String>): Boolean {
        if (name.isNullOrBlank()) return false
        if (name.startsWith(LOADER_NAMESPACE)) return true
        if (name.startsWith(OFFICIAL_NAMESPACE)) return true
        return name in installed
    }

    /**
     * 是否属于「第三方插件」= 明确不是宿主自带。
     *
     * 我方随包资产 `@local/` 不算第三方（它由 app 自己装配、随版本刷新）。
     * 判定拿不准时**返回 false**（保守）：调用方据此不写停用行。
     */
    fun isThirdParty(name: String?, installed: Set<String>): Boolean {
        if (name.isNullOrBlank()) return false
        if (name.startsWith("@local/")) return false
        return !isHostPackage(name, installed)
    }
}
