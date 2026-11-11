package com.dshbox.remote.session

/**
 * 会话内核的键值持久化抽象：设备存档、槽位/端口分配表共用的最底层存储接口。
 *
 * 生产实现落在模块私有偏好（[SharedPreferencesStore]）；JVM 单测以内存实现替换，
 * 保证分配与存档逻辑可在纯 JVM 环境覆盖。
 */
interface KeyValueStore {
    fun getString(key: String): String?

    fun putString(key: String, value: String)

    fun remove(key: String)

    /** 当前全部键：供按前缀扫描分配表条目使用。 */
    fun keys(): Set<String>
}
