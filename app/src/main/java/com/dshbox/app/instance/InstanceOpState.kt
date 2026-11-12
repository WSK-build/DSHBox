package com.dshbox.app.instance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 实例操作（切换/创建/删除/层修复）的可观察状态。
 * 服务层执行操作时更新；管理界面读取以呈现进度与结果。
 */
object InstanceOpState {

    /** 操作类别标签（用于界面文案与日志）。 */
    enum class Kind { SWITCH, CREATE_EMERGENCY, DELETE_EMERGENCY, REPAIR_LAYER, RESET_MAIN, RESTORE_BACKUP, DELETE_BACKUP }

    data class State(
        val running: Boolean = false,
        val kind: Kind? = null,
        /** 失败原因（成功或空闲时为 null）。 */
        val error: String? = null,
        /** 最近一次操作是否成功（空闲时为 null）。 */
        val lastSuccess: Boolean? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 原子地尝试进入运行态：已有操作进行中返回 false（不改动状态）。 */
    fun tryBegin(kind: Kind): Boolean {
        while (true) {
            val current = _state.value
            if (current.running) return false
            if (_state.compareAndSet(current, State(running = true, kind = kind))) return true
        }
    }

    fun finish(success: Boolean, error: String? = null) {
        _state.value = _state.value.copy(running = false, error = error, lastSuccess = success)
    }
}