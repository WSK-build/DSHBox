package com.dshbox.app.common

import java.io.File

/**
 * DSH 实例槽位（单槽切换模型）：同一时刻仅一个活跃实例；
 * 主实例与应急实例共享运行层，仅 home 目录不同。
 */
enum class DshInstance(val homeLeaf: String) {
    /** 主实例：用户日常数据所在 home。 */
    MAIN(".dsh"),

    /** 应急实例：崩溃恢复用的独立 home，初始无第三方插件与凭证。 */
    EMERGENCY(".dsh-emergency");

    /** guest 侧 home 绝对路径（user-data 绑定为 [Constants.SANDBOX_WORKSPACE]）。 */
    val guestHome: String get() = "${Constants.SANDBOX_WORKSPACE}/$homeLeaf"

    /** 宿主侧 home 目录。 */
    fun hostHome(userDataDir: File): File = File(userDataDir, homeLeaf)

    companion object {
        /** 持久化值解析；未知/缺省一律回落主实例（安全默认）。 */
        fun fromHomeLeaf(leaf: String?): DshInstance =
            entries.firstOrNull { it.homeLeaf == leaf } ?: MAIN
    }
}