package com.dshbox.pluginmanager.core

import com.dshbox.pluginmanager.contract.HostCapabilities
import com.dshbox.pluginmanager.contract.HostContract
import java.io.File

/**
 * 宿主能力的生产探测：把 [HostCapabilities.detect] 接到真实文件系统。
 *
 * 全部依据都是本地痕迹：`compatibility.json` 存在说明豁免体系已启用，安装层有
 * `dsh-plugin-manager` 说明官方插件命令可用。不读版本号。
 *
 * 探测是本地事实的快照，不做缓存；调用方在装配点探一次即可。
 */
class HostCapabilityProbe(
    private val runtimeCurrentDir: File,
    private val profileDir: File,
) {

    /** 生产构造：路径取自 [PluginPaths]。 */
    constructor(paths: PluginPaths) : this(
        runtimeCurrentDir = paths.runtimeCurrentDir,
        profileDir = paths.profileDir,
    )

    /** 探测一次。 */
    fun detect(): HostCapabilities.Capabilities = HostCapabilities.detect(
        hasCompatibilityFile = File(profileDir, HostContract.COMPATIBILITY).isFile,
        hasPluginManagerPackage = pluginManagerPackageExists(),
    )

    /** 安装层里是否有 `@deepseek-ai/dsh-plugin-manager`；DSH 层与整棵依赖树两个根都查。 */
    private fun pluginManagerPackageExists(): Boolean {
        val packageName = "dsh-plugin-manager"
        val scope = HostContract.OFFICIAL_NAMESPACE.trimEnd('/')
        return listOf(
            File(runtimeCurrentDir, "${HostContract.DSH_LAYER}/${HostContract.NODE_MODULES}/$scope/$packageName"),
            File(runtimeCurrentDir, "${HostContract.NODE_MODULES}/$scope/$packageName"),
        ).any { it.isDirectory }
    }
}
