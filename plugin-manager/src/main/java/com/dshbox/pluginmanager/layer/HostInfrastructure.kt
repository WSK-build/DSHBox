package com.dshbox.pluginmanager.layer

import com.dshbox.pluginmanager.core.HostPackages

/**
 * 「这个条目能不能停用」的唯一判定入口。
 *
 * 判定**基于包名**（合成树里 `- id:` 旁边的 `name:`），不是 id：id 是 loader 的条目名
 * （`drop-caret`），包名才是"谁提供的"（`dsh-drop-caret`）；官方保护只能落在包名上。
 *
 * 官方集合由 [HostPackages] 在运行时推导（安装层实际有什么），配合两条永不陈旧的
 * 命名空间规则。**判不清楚的条目一律拒绝停用**——误关官方条目的后果是整机起不来。
 */
object HostInfrastructure {

    /**
     * 是否受保护（禁止停用）。
     *
     * @param id loader 条目 id，仅用于展示与日志
     * @param name 条目对应的包名；为空时**判为受保护**（信息不足就不动手）
     * @param installed [HostPackages.installedPackages] 的推导结果
     */
    fun isProtected(id: String, name: String?, installed: Set<String>): Boolean {
        if (name.isNullOrBlank()) return true
        if (id.isBlank()) return true
        return HostPackages.isHostPackage(name, installed)
    }

    /** 是否属于第三方（= 明确不是宿主自带，且不是我方 `@local/` 资产）。 */
    fun isThirdParty(name: String?, installed: Set<String>): Boolean =
        HostPackages.isThirdParty(name, installed)
}
