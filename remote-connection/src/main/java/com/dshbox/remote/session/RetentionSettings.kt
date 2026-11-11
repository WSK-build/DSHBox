package com.dshbox.remote.session

import com.dshbox.remote.model.RetentionPolicy
import com.dshbox.remote.model.retentionPolicyFromArchive
import com.dshbox.remote.model.toArchiveValue

/**
 * 全局默认保留档的最小设置存储：单键（[KEY]）读写，值取策略的档案编码。
 * 生产落在模块私有偏好（[SharedPreferencesStore.of]，独立 prefs 文件）；
 * 无持久化出口时以 [VolatileKeyValueStore] 兜底（进程内存续，重启回默认档）。
 */
class RetentionSettings(private val store: KeyValueStore) {

    /** 读取全局默认档；无记录或值损坏时回落 IMMEDIATE。 */
    fun default(): RetentionPolicy =
        store.getString(KEY)?.let(::retentionPolicyFromArchive) ?: RetentionPolicy.IMMEDIATE

    /** 写入全局默认档。 */
    fun setDefault(policy: RetentionPolicy) {
        store.putString(KEY, policy.toArchiveValue())
    }

    companion object {
        /** 设置存储键：全局默认保留档。 */
        const val KEY = "retention.default"

        /** 生产 prefs 文件名（模块私有，独立于设备存档文件）。 */
        const val PREFERENCES_NAME = "dshbox_remote_settings"
    }
}

/** [KeyValueStore] 的内存兜底实现：无持久化出口（JVM 单测/骨架宿主）时使用。 */
internal class VolatileKeyValueStore : KeyValueStore {

    private val map = HashMap<String, String>()

    override fun getString(key: String): String? = map[key]

    override fun putString(key: String, value: String) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }

    override fun keys(): Set<String> = map.keys.toSet()
}
