package com.dshbox.pluginmanager.core

import android.content.Context
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.Constants
import com.dshbox.app.sandbox.SandboxManager
import com.dshbox.pluginmanager.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「DSHBox 专属适配插件」两个开关的状态与动作。
 *
 * 与设置页里那套装配逻辑同源（同样的 guest 命令、同样的偏好键），只是入口搬到了
 * 插件面板：开关状态用本地偏好做瞬时响应，仅当本地键**从未写过**时才查一次 guest 校准。
 *
 * 两个开关**默认开启**（键**从未写过**时才置 true；写过之后一律不再改动，
 * 因此升级 / 重装 APK 不会覆盖用户的选择）：
 * - 移动端适配包（mobile-adapt）：装配/移除都执行随包资产里的安装脚本，重启 DSH 后生效。
 * - DSH连接手机（mobile-pilot）：与上一条**同形** —— 同一套 guest 命令、同一个脚本目录结构、
 *   同样的失败回滚，只是包名与 staging 目录不同。两条链路都只动 profile，不涉及 `--patch` 覆盖层。
 *
 * @param scope 界面侧生命周期；动作命令本身在 IO 上跑。
 */
class AdapterPluginController(
    private val context: Context,
    private val sandbox: SandboxManager,
    private val profile: String,
    private val scope: CoroutineScope,
) {

    private val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    // 必须排在下面两个 StateFlow 之前：预置的默认值要能被它们的初值读到
    // （类体内 init 块与属性初始化按声明顺序执行）。
    init {
        seedDefaultsOnce()
    }

    /**
     * 预置「默认开启」——只在**键从未写过**时写 true，写过就再也不碰。
     *
     * 为什么需要一个独立的标记键：只靠取值键的缺失无法区分"用户从没动过"与
     * "用户动过（关掉了）"，那样每次升级都会把用户的关闭动作翻回开启。
     * 标记键记下"默认值已经预置过"，此后两个开关的状态完全由用户与运行期状态决定。
     */
    private fun seedDefaultsOnce() {
        if (prefs.contains(PREF_DEFAULTS_SEEDED)) return
        val editor = prefs.edit().putBoolean(PREF_DEFAULTS_SEEDED, true)
        if (!prefs.contains(PREF_MOBILE_ADAPT_INSTALLED)) {
            editor.putBoolean(PREF_MOBILE_ADAPT_INSTALLED, true)
        }
        if (!prefs.contains(PREF_MOBILE_PILOT_ENABLED)) {
            // 这个开关改名过（旧键名字里有 pilot）。拨过旧开关的老用户，其选择要跟着搬过来，
            // 不能被"默认开启"顶掉。
            val carried = prefs.getBoolean(PREF_LEGACY_PILOT_ENABLED, true)
            editor.putBoolean(PREF_MOBILE_PILOT_ENABLED, carried)
        }
        editor.apply()
    }

    private val _mobileInstalled = MutableStateFlow(prefs.getBoolean(PREF_MOBILE_ADAPT_INSTALLED, true))

    /** 移动端适配包是否已装配（本地标记，装配/移除成功时翻转）。 */
    val mobileInstalled: StateFlow<Boolean> = _mobileInstalled.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** 命令执行中：开关禁用，防止连点。 */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _mobilePilotEnabled = MutableStateFlow(prefs.getBoolean(PREF_MOBILE_PILOT_ENABLED, true))

    /** 「DSH连接手机」是否已装配（本地标记，装配/卸载成功时翻转）。 */
    val mobilePilotEnabled: StateFlow<Boolean> = _mobilePilotEnabled.asStateFlow()

    private val _mobilePilotBusy = MutableStateFlow(false)

    /** mobile-pilot 命令执行中：开关禁用，防止连点（与 [busy] 同义，两条链路各自独立）。 */
    val mobilePilotBusy: StateFlow<Boolean> = _mobilePilotBusy.asStateFlow()

    /** 最近一次自动刷新失败的原因（由 bootstrap 写入偏好，可为空）。 */
    fun lastError(): String? = prefs.getString(Constants.PREF_MOBILE_ADAPT_LAST_ERROR, null)

    /**
     * 首次使用本地键未写过时，向 guest 查一次真实状态做校准；写过之后只信本地标记。
     */
    fun calibrateOnce() {
        if (prefs.contains(PREF_MOBILE_ADAPT_INSTALLED)) return
        scope.launch {
            _busy.value = true
            val installed = withContext(Dispatchers.IO) {
                val res = sandbox.runGuestCommand(
                    "grep -q mobile-adapt /root/projects/.dsh/profiles/$profile",
                    onLine = {},
                )
                res is AppResult.Success
            }
            _mobileInstalled.value = installed
            prefs.edit().putBoolean(PREF_MOBILE_ADAPT_INSTALLED, installed).apply()
            _busy.value = false
        }
    }

    /** 装配与移除共用一个入口：按当前状态决定执行哪个脚本。 */
    fun toggleMobileAdapt() {
        if (_busy.value) return
        val target = !_mobileInstalled.value
        _busy.value = true
        scope.launch {
            val script = if (target) "install.sh" else "uninstall.sh"
            val command = "chmod -R u+w $PROFILE_DIR/node_modules/@local/dsh-mobile-adapt 2>/dev/null || true; " +
                "bash $STAGE_DIR/$script $PROFILE_DIR"
            val result = withContext(Dispatchers.IO) { sandbox.runGuestCommand(command, onLine = {}) }
            if (result is AppResult.Success) {
                _mobileInstalled.value = target
                prefs.edit().putBoolean(PREF_MOBILE_ADAPT_INSTALLED, target).apply()
                toast(
                    context.getString(R.string.pm_adapt_ok) +
                        context.getString(R.string.pm_adapt_restart_hint),
                    android.widget.Toast.LENGTH_SHORT,
                )
            } else if (!target) {
                // 装配失败时把 guest 侧回滚一遍，避免留下半装配状态。
                withContext(Dispatchers.IO) {
                    sandbox.runGuestCommand(
                        "chmod -R u+w $PROFILE_DIR/node_modules/@local/dsh-mobile-adapt 2>/dev/null || true; " +
                            "bash $STAGE_DIR/uninstall.sh $PROFILE_DIR",
                        onLine = {},
                    )
                }
            }
            if (result is AppResult.Failure) {
                toast(
                    context.getString(R.string.pm_adapt_fail) + " " + result.error.message,
                    android.widget.Toast.LENGTH_LONG,
                )
            }
            _busy.value = false
        }
    }

    /**
     * mobile-pilot（DSH连接手机）的装配与卸载共用一个入口 —— 与 [toggleMobileAdapt] **同形**：
     * 同一套 guest 命令、同一个脚本目录结构、同样的失败回滚，只是包名与 staging 目录不同。
     */
    fun toggleMobilePilot() {
        if (_mobilePilotBusy.value) return
        val target = !_mobilePilotEnabled.value
        _mobilePilotBusy.value = true
        scope.launch {
            val script = if (target) "install.sh" else "uninstall.sh"
            val command = "chmod -R u+w $MOBILE_PILOT_PROFILE_PKG 2>/dev/null || true; " +
                "bash $MOBILE_PILOT_STAGE_DIR/$script $PROFILE_DIR"
            val result = withContext(Dispatchers.IO) { sandbox.runGuestCommand(command, onLine = {}) }
            if (result is AppResult.Success) {
                _mobilePilotEnabled.value = target
                prefs.edit().putBoolean(PREF_MOBILE_PILOT_ENABLED, target).apply()
                toast(
                    context.getString(R.string.pm_adapt_ok) +
                        context.getString(R.string.pm_adapt_restart_hint),
                    android.widget.Toast.LENGTH_SHORT,
                )
            } else if (!target) {
                // 装配失败时把 guest 侧回滚一遍，避免留下半装配状态。
                withContext(Dispatchers.IO) {
                    sandbox.runGuestCommand(
                        "chmod -R u+w $MOBILE_PILOT_PROFILE_PKG 2>/dev/null || true; " +
                            "bash $MOBILE_PILOT_STAGE_DIR/uninstall.sh $PROFILE_DIR",
                        onLine = {},
                    )
                }
            }
            if (result is AppResult.Failure) {
                toast(
                    context.getString(R.string.pm_adapt_fail) + " " + result.error.message,
                    android.widget.Toast.LENGTH_LONG,
                )
            }
            _mobilePilotBusy.value = false
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
        const val TAG = "PluginAdapt"

        /** 与设置页同源的偏好键（bootstrap 也读它）。 */
        const val PREF_MOBILE_ADAPT_INSTALLED = Constants.PREF_MOBILE_ADAPT_INSTALLED

        /** 「DSH连接手机」开关的键（bootstrap 也读它，决定启动期要不要把包装配到位）。 */
        const val PREF_MOBILE_PILOT_ENABLED = Constants.PREF_MOBILE_PILOT_ENABLED

        /** 该开关改名前的旧键，只用于一次性把老用户的选择搬过来。 */
        const val PREF_LEGACY_PILOT_ENABLED = Constants.PREF_LEGACY_PILOT_ENABLED

        /**
         * 「默认开启」是否已预置过（一次性标记）。
         *
         * 与取值键分开是必须的：只看取值键的缺失无法区分"用户从没动过"与"用户关掉了"，
         * 那样每次升级都会把用户的关闭动作翻回开启。
         */
        const val PREF_DEFAULTS_SEEDED = "adapter_defaults_seeded"

        const val STAGE_DIR = "${Constants.DSHBOX_PLUGINS_GUEST_DIR}/mobile-adapt"

        const val PROFILE_DIR = Constants.DSH_WEB_PROFILE_GUEST_DIR

        /** mobile-pilot 包的 staging 目录（随包资产复制进 guest 的落点）。 */
        const val MOBILE_PILOT_STAGE_DIR = "${Constants.DSHBOX_PLUGINS_GUEST_DIR}/mobile-pilot"

        /** mobile-pilot 包装配后在 profile 内的落点（由 install.sh 建立）。 */
        const val MOBILE_PILOT_PROFILE_PKG = "$PROFILE_DIR/node_modules/@local/mobile-pilot"
    }
}
