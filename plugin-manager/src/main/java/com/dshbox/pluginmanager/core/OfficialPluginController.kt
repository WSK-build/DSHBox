package com.dshbox.pluginmanager.core

import android.content.Context
import com.dshbox.app.common.Constants
import com.dshbox.app.sandbox.OfficialPluginOverlay
import com.dshbox.app.sandbox.DshOfficialPlugins
import com.dshbox.pluginmanager.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「DSH 官方插件」各开关的状态与动作。
 *
 * 开关状态只落在本地偏好（默认全开）；每次拨动**立即**按新状态重写 `--patch` 覆盖层
 * （[OfficialPluginOverlay]），因此"重启 DSH 后生效"这句话在用户拨动的那一刻就已经成立 ——
 * 不需要等应用重启才把新状态落盘。
 *
 * 本板块**不受绝对安全模式影响**：该模式的目的是不加载第三方插件，而这里是上游官方
 * 内置能力，开关它们不违背其语义。界面侧因此只按"忙"置灰（见 `PluginPanelScreen`）。
 *
 * 文件读写走 IO 线程：本模块的每个动作都会碰磁盘，在组合或点击回调里同步做会卡界面。
 *
 * @param scope 与模块同寿的动作 scope（不随面板关闭而取消），由装配点注入。
 */
class OfficialPluginController(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(DshOfficialPlugins.enabledIds(prefs))

    /** 已开启的条目 id 集合。 */
    val enabled: StateFlow<Set<String>> = _enabled.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** 是否有一次拨动正在落盘。 */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * 拨动一个开关：写偏好 → 立即重写覆盖层 → 提示用户重启 DSH。
     *
     * 覆盖层写入失败**不回滚偏好**：偏好是用户的意图，覆盖层下次启动还会再写一次；
     * 此时给出长提示说明失败，而不是假装成功。
     */
    fun setEnabled(id: String, enabled: Boolean) {
        if (_busy.value) return
        _busy.value = true
        scope.launch {
            // apply() 先更新内存再异步落盘，故紧随其后的 refresh 一定能读到新值。
            prefs.edit().putBoolean(DshOfficialPlugins.prefKey(id), enabled).apply()
            val written = withContext(Dispatchers.IO) { OfficialPluginOverlay.refresh(context) }
            _enabled.value = DshOfficialPlugins.enabledIds(prefs)
            if (written) {
                toast(
                    context.getString(
                        if (enabled) R.string.pm_official_on else R.string.pm_official_off,
                        id,
                    ),
                    android.widget.Toast.LENGTH_SHORT,
                )
            } else {
                toast(
                    context.getString(R.string.pm_official_fail, id),
                    android.widget.Toast.LENGTH_LONG,
                )
            }
            _busy.value = false
        }
    }

    /** 主线程显示；失败留痕（Toast 必须在带 Looper 的线程上创建）。 */
    private fun toast(text: String, duration: Int) {
        mainHandler.post {
            runCatching {
                android.widget.Toast.makeText(context.applicationContext, text, duration).show()
            }.onFailure { android.util.Log.w(TAG, "toast failed: ${it.message}") }
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private companion object {
        const val TAG = "OfficialPlugin"
    }
}
