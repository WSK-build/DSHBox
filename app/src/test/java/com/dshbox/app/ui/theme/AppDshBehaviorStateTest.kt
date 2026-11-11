package com.dshbox.app.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppDshBehaviorState] 两项通用偏好的读写与默认值。
 *
 * 纯 JVM 口径：Context 以内存桩（`getSharedPreferences` 返回进程内
 * [MutableSharedPreferences]）替换持久层，语义与真实 SharedPreferences
 * 等价——`load` 读、`setXxx` 写，同一份 prefs 读写往返；「重启后读回」
 * 用「换一个新 Context 指向同一 prefs 存储 + 重新 load」表达，等价于
 * 进程重启后的 DshApp.onCreate。
 *
 * 默认口径：状态栏**默认关**（全屏沉浸，保持既有行为），回车键**默认
 * 「换行」档**（true）。
 */
class AppDshBehaviorStateTest {

    /** 进程内 SharedPreferences 桩：真实读写语义（getBoolean/putBoolean/apply）。 */
    private class MutableSharedPreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as Boolean? ?: defValue

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                values[key] = value
                return this
            }

            override fun apply() { /* 内存桩：即时落盘 */ }
            override fun commit(): Boolean = true
            override fun remove(key: String): SharedPreferences.Editor {
                values.remove(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                values.clear()
                return this
            }

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                values[key] = value
                return this
            }

            override fun putStringSet(key: String, value: Set<String>?): SharedPreferences.Editor {
                values[key] = value
                return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                values[key] = value
                return this
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                values[key] = value
                return this
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                values[key] = value
                return this
            }
        }

        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue
        override fun getStringSet(key: String, defValue: Set<String>?): Set<String>? = values[key] as Set<String>? ?: defValue
        override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.remove(listener)
        }
    }

    /**
     * 只覆写 `getSharedPreferences` 的最小 Context 桩（[AppDshBehaviorStateStub]
     * 把其余成员全拒绝）：整个 prefs 存储在同进程内共享，等价真实
     * SharedPreferences 的持久语义。
     */
    private class MemoryPrefsContext(
        private val storage: MutableMap<String, MutableSharedPreferences>,
    ) {
        val context: Context = StubContext().apply {
            prefsProvider = { name, _ -> storage.getOrPut(name) { MutableSharedPreferences() } }
        }
    }

    private val storage = mutableMapOf<String, MutableSharedPreferences>()

    private fun freshContext(): Context = MemoryPrefsContext(storage).context

    /** 默认值口径：状态栏关（全屏沉浸）、回车键换行档。 */
    @Test
    fun defaultsAreFullScreenAndNewline() {
        AppDshBehaviorState.load(freshContext())
        assertFalse(AppDshBehaviorState.localStatusBarVisible)
        assertTrue(AppDshBehaviorState.enterKeyNewline)
    }

    /** 写入后并存的两项都写入同一 prefs（同一 app_settings 文件）。 */
    @Test
    fun writesRoundTripThroughTheSamePrefs() {
        AppDshBehaviorState.load(freshContext())
        AppDshBehaviorState.setLocalStatusBarVisible(freshContext(), true)
        AppDshBehaviorState.setEnterKeyNewline(freshContext(), false)

        // 重新 load（等价重启）：持久层读回的是写入值，由 load 把 object 单例
        // 的现值刷回持久层（object 的 private set 只允许 load/setter 改写）。
        AppDshBehaviorState.load(freshContext())
        assertTrue(AppDshBehaviorState.localStatusBarVisible)
        assertFalse(AppDshBehaviorState.enterKeyNewline)
    }

    /** 没有任何写入时 load 保持默认值（不抛异常、不翻转语义）。 */
    @Test
    fun loadWithEmptyStorageKeepsDefaults() {
        AppDshBehaviorState.load(freshContext())
        assertFalse(AppDshBehaviorState.localStatusBarVisible)
        assertTrue(AppDshBehaviorState.enterKeyNewline)
    }

    /** 写回写入值与持久层一致：键名固定不漂移。 */
    @Test
    fun persistedKeysAreStable() {
        AppDshBehaviorState.load(freshContext())
        AppDshBehaviorState.setLocalStatusBarVisible(freshContext(), true)
        AppDshBehaviorState.setEnterKeyNewline(freshContext(), false)
        val prefs = storage.getValue("app_settings")
        assertEquals(true, prefs.getBoolean("dsh_local_status_bar", false))
        assertEquals(false, prefs.getBoolean("dsh_enter_key_newline", true))
    }

    /** 单独设置一项不影响另一项。 */
    @Test
    fun settersAreIndependent() {
        AppDshBehaviorState.load(freshContext())
        AppDshBehaviorState.setLocalStatusBarVisible(freshContext(), true)
        assertTrue(AppDshBehaviorState.localStatusBarVisible)
        assertTrue(AppDshBehaviorState.enterKeyNewline)
        AppDshBehaviorState.setEnterKeyNewline(freshContext(), false)
        assertTrue(AppDshBehaviorState.localStatusBarVisible)
        assertFalse(AppDshBehaviorState.enterKeyNewline)
    }
}
