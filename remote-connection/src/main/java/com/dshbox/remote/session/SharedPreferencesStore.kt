package com.dshbox.remote.session

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * [KeyValueStore] 的生产实现：模块私有 SharedPreferences。
 *
 * 值仅含设备档案与槽位/端口分配表等非敏感簿记数据；凭据与 cookie jar
 * 的加密存档走 [CredentialVault]，不经此通道落盘。
 *
 * 并发约定：同一进程内同一 prefs 文件只存在一个实例（经 [of] 取用）——
 * 槽位分配表的读-改-写以存储对象为锁（见 [SlotAllocator]），实例级互斥
 * 必须跨会话工厂生效。
 */
class SharedPreferencesStore private constructor(context: Context, fileName: String) : KeyValueStore {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun keys(): Set<String> = prefs.all.keys

    companion object {

        /** 进程级 prefs 文件名 → 实例注册表。 */
        private val instances = ConcurrentHashMap<String, SharedPreferencesStore>()

        /** 取 [fileName] 对应的进程级单例：同一 prefs 文件复用同一实例，跨工厂互斥。 */
        fun of(context: Context, fileName: String): SharedPreferencesStore =
            instances.computeIfAbsent(fileName) { SharedPreferencesStore(context.applicationContext, it) }
    }
}
