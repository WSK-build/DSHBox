package com.dshbox.remote.model

/**
 * 设备与回环槽位 IP 的终身绑定记录。
 *
 * 设备首次建立会话时绑定一个空闲槽位，此后终身不变；只有删除设备才回收。
 */
data class SlotBinding(
    val deviceId: DeviceId,
    /** 回环槽位 IP（127.0.0.2 ~ 127.0.0.11）。 */
    val loopbackIp: String,
)
