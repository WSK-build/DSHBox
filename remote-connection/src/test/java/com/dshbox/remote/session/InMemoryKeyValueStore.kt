package com.dshbox.remote.session

/**
 * [KeyValueStore] 的 JVM 单测内存实现：替代模块私有偏好，隔离文件系统。
 */
class InMemoryKeyValueStore : KeyValueStore {

    private val map = LinkedHashMap<String, String>()

    override fun getString(key: String): String? = map[key]

    override fun putString(key: String, value: String) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }

    override fun keys(): Set<String> = map.keys.toSet()
}
