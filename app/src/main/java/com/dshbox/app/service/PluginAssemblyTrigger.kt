package com.dshbox.app.service

import com.dshbox.app.sandbox.DshState

/**
 * 根据相邻 DSH 状态判断是否触发自有插件装配。
 * 首次收到 READY，或从其他状态进入 READY 时触发；连续 READY 不重复装配。
 * 失败后再次进入 READY 允许重新探测，以补齐先前未就位的插件。
 */
internal object PluginAssemblyTrigger {

    /**
     * @param previous 上一次发射的状态；尚未收到任何发射时为 null
     * @param current 本次发射的状态
     */
    fun shouldAssemble(previous: DshState?, current: DshState): Boolean =
        current == DshState.READY && previous != DshState.READY
}
