package com.dshbox.pluginmanager.repair

import com.dshbox.app.common.AppResult
import com.dshbox.app.sandbox.SandboxManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「插件崩溃修复辅助」页的状态与动作：安装 / 更新 / 删除 opencode 终端 Agent。
 *
 * 三条动作都在 guest 内执行（命令由 [OpenCodeTool] 构造），输出逐行回吐给页面，因此用户能看到
 * 下载进度而不是一个转圈；命令本体只随 APK 携带，**工具一个字节都不打包**。
 *
 * registry 的兜底在本类内部完成（镜像优先、官方其次），前台只呈现"进行中 / 完成 / 失败"，
 * 不暴露源的选择。
 *
 * @param scope 界面侧生命周期；命令本身在 IO 上跑。
 */
class OpenCodeToolController(
    private val sandbox: SandboxManager,
    private val scope: CoroutineScope,
) {

    /** 页面的一次性结果，用来决定结果面板长什么样。 */
    enum class Outcome { NONE, INSTALLED, REMOVED, FAILED }

    /** 本次动作（失败后"重试"要重放的就是它，不能猜）。 */
    enum class Action { INSTALL, REMOVE }

    /** 页面状态。`lines` 是命令输出的尾部若干行；`outcome` 非 NONE 时结果面板可见。 */
    data class State(
        val installed: Boolean = false,
        val version: String? = null,
        val busy: Boolean = false,
        val lines: List<String> = emptyList(),
        val outcome: Outcome = Outcome.NONE,
        val error: String? = null,
        /** 本次动作已尝试的次数：> 1 表示正在换下一个源重试（界面据此提示"正在重试"）。 */
        val attempt: Int = 1,
        /** 最近一次动作；失败后"重试"按它重放。 */
        val action: Action? = null,
    )

    private val _state = MutableStateFlow(State())

    /** 供界面订阅。 */
    val state: StateFlow<State> = _state.asStateFlow()

    /** 进页面时探一次真实安装态（只看安装目录，不看常驻的命令包装脚本）。 */
    fun refresh() {
        scope.launch {
            val output = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                sandbox.runGuestCommand(OpenCodeTool.probeScript(), onLine = { output += it })
            }
            val probed = OpenCodeTool.parseProbe(output)
            _state.update { it.copy(installed = probed.installed, version = probed.version) }
        }
    }

    /**
     * 安装与更新共用一个入口：两者都是"幂等拉取最新版"。
     *
     * 失败不会破坏已装好的版本 —— 命令在 npm 失败时即中止（见 [OpenCodeTool.installScript]）。
     */
    fun installOrUpdate() {
        if (_state.value.busy) return
        _state.update {
            it.copy(
                busy = true,
                lines = emptyList(),
                outcome = Outcome.NONE,
                error = null,
                attempt = 1,
                action = Action.INSTALL,
            )
        }
        scope.launch {
            var lastError: String? = null
            var succeeded = false
            for ((index, registry) in OpenCodeTool.registries.withIndex()) {
                if (index > 0) _state.update { it.copy(attempt = index + 1) }
                val result = withContext(Dispatchers.IO) {
                    sandbox.runGuestCommand(
                        OpenCodeTool.installScript(registry),
                        onLine = { appendLine(it) },
                    )
                }
                if (result is AppResult.Success) {
                    succeeded = true
                    break
                }
                lastError = (result as? AppResult.Failure)?.error?.message
            }
            if (succeeded) {
                val probed = probeNow()
                _state.update {
                    it.copy(
                        busy = false,
                        installed = true,
                        version = probed.version,
                        outcome = Outcome.INSTALLED,
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        busy = false,
                        outcome = Outcome.FAILED,
                        error = lastError,
                    )
                }
            }
        }
    }

    /** 删除：装体、配置与用户数据同处一个目录，删这一个目录即全清（界面侧已二次确认）。 */
    fun remove() {
        if (_state.value.busy) return
        _state.update {
            it.copy(
                busy = true,
                lines = emptyList(),
                outcome = Outcome.NONE,
                error = null,
                action = Action.REMOVE,
            )
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                sandbox.runGuestCommand(OpenCodeTool.removeScript(), onLine = { appendLine(it) })
            }
            if (result is AppResult.Success) {
                _state.update {
                    it.copy(busy = false, installed = false, version = null, outcome = Outcome.REMOVED)
                }
            } else {
                _state.update {
                    it.copy(
                        busy = false,
                        outcome = Outcome.FAILED,
                        error = (result as? AppResult.Failure)?.error?.message,
                    )
                }
            }
        }
    }

    /** 关掉结果面板（用户点"关闭"）。 */
    fun dismiss() {
        _state.update { it.copy(outcome = Outcome.NONE, error = null, lines = emptyList()) }
    }

    private suspend fun probeNow(): OpenCodeTool.InstalledState {
        val output = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            sandbox.runGuestCommand(OpenCodeTool.probeScript(), onLine = { output += it })
        }
        return OpenCodeTool.parseProbe(output)
    }

    /** 输出只保留尾部若干行：一次安装会打很多行，页面不需要全量。 */
    private fun appendLine(line: String) {
        _state.update { it.copy(lines = (it.lines + line).takeLast(MAX_LINES)) }
    }

    private companion object {
        const val MAX_LINES = 120
    }
}
