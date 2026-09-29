package interlock.relay.core.spi

import android.content.Context
import android.content.SharedPreferences

/**
 * 键值偏好的取用口。core 内四处（装配根、授权档位、shell 动词逐条授权、执行模式偏好）
 * 都经由它读写，不再直接持有 SharedPreferences；宿主可用同一实现，也可换成自己的存储。
 */
interface RelayPrefs {

    fun getString(file: String, key: String, defValue: String?): String?

    fun putString(file: String, key: String, value: String)

    fun getBoolean(file: String, key: String, defValue: Boolean): Boolean

    fun putBoolean(file: String, key: String, value: Boolean)

    companion object {
        /** 装配根、授权档位与执行模式偏好共用的文件名。 */
        const val FILE_MAIN = "relay"

        /** `sys.shell` 会改系统动词的逐条授权文件名。 */
        const val FILE_SHELL_VERBS = "relay_shell_verbs"
    }

    /** 缺省实现：applicationContext 的 SharedPreferences。 */
    class Default(context: Context) : RelayPrefs {
        private val appContext = context.applicationContext

        private fun prefs(file: String): SharedPreferences =
            appContext.getSharedPreferences(file, Context.MODE_PRIVATE)

        override fun getString(file: String, key: String, defValue: String?): String? =
            runCatching { prefs(file).getString(key, defValue) }.getOrNull() ?: defValue

        override fun putString(file: String, key: String, value: String) {
            runCatching { prefs(file).edit().putString(key, value).apply() }
        }

        override fun getBoolean(file: String, key: String, defValue: Boolean): Boolean =
            runCatching { prefs(file).getBoolean(key, defValue) }.getOrDefault(defValue)

        override fun putBoolean(file: String, key: String, value: Boolean) {
            runCatching { prefs(file).edit().putBoolean(key, value).apply() }
        }
    }
}
