package com.dshbox.pluginmanager.market.data

import com.dshbox.pluginmanager.contract.HostContract
import java.io.File

/**
 * 镜像源覆盖文件的取数点（判定面唯一消费者）。
 *
 * 文件由 App 侧探测写入，落在 guest 与宿主共享的缓存挂载点；本模块装配时读一次
 * 快照。缺失、读不出、校验不过一律返回 null，装配点据此回退
 * [HostContract.DEFAULT_REGISTRY_BASE]——不得把"读不出"说成已配置。
 */
object PnpmRegistry {

    /** 读覆盖文件并解析基址；文件缺失、读失败、内容校验不过 → null。 */
    fun readBase(file: File): String? =
        runCatching {
            file.takeIf { it.isFile }?.readText()
        }.getOrNull()?.let { HostContract.resolveRegistryBase(it) }
}
