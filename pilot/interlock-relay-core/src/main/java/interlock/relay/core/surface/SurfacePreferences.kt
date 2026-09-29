package interlock.relay.core.surface

import interlock.relay.core.spi.RelayPrefs

/** 执行模式偏好存储。与授权档位共用同一私有偏好文件，卸载即净。 */
class SurfacePreferences(private val prefs: RelayPrefs) {

    private val file = RelayPrefs.FILE_MAIN

    fun preference(): SurfacePreference =
        prefs.getString(file, KEY, null)?.let { raw ->
            runCatching { SurfacePreference.valueOf(raw) }.getOrNull()
        } ?: SurfacePreference.FOREGROUND

    fun setPreference(value: SurfacePreference) {
        prefs.putString(file, KEY, value.name)
    }

    private companion object {
        const val KEY = "surface_preference"
    }
}
