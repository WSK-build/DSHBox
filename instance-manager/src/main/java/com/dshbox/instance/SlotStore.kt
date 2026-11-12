package com.dshbox.instance

import com.dshbox.app.common.DshInstance

/** 活跃槽位的持久化抽象；JVM 单测用内存实现，设备侧用 SharedPreferences 实现。 */
interface SlotStore {
    fun read(): DshInstance
    fun write(instance: DshInstance)
}