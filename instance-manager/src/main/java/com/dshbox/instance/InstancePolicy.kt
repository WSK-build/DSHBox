package com.dshbox.instance

import com.dshbox.app.common.DshInstance

/** 实例级装配与删除策略（纯函数，JVM 单测锁定）。 */
object InstancePolicy {

    /** 应急槽固定装配清单：仅移动端适配包；不读主实例的全局开关。 */
    val EMERGENCY_ASSEMBLY: List<String> = listOf("mobile-adapt")

    /** 应急槽是否允许装配该自有插件（清单外一律拒绝）。 */
    fun allowsEmergencyAssembly(label: String): Boolean = label in EMERGENCY_ASSEMBLY

    /** 应急 home 仅在非活跃时可删除；活跃时需先切回主实例。 */
    fun canDeleteEmergency(active: DshInstance): Boolean = active != DshInstance.EMERGENCY
}