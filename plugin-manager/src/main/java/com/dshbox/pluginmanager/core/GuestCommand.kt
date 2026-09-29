package com.dshbox.pluginmanager.core

import com.dshbox.app.common.AppResult
import com.dshbox.app.sandbox.SandboxManager
import java.util.Collections

/** 一次性 guest 命令的执行结果。 */
data class GuestCommandResult(
    val ok: Boolean,
    val output: List<String>,
) {
    /** 合并后的完整输出，便于正则匹配与展示。 */
    val text: String get() = output.joinToString("\n")

    companion object {
        fun failure(message: String) = GuestCommandResult(false, listOf(message))
    }
}

/**
 * 在沙箱 guest 里跑一次性命令的入口。
 *
 * 抽成接口是为了让上层（安装链、绝对安全模式的 compose 探测）可测：
 * 单测里给一个假实现即可，不需要真机。
 */
interface GuestCommandRunner {
    suspend fun run(
        command: String,
        onLine: (String) -> Unit = {},
    ): GuestCommandResult
}

/**
 * 生产实现：转交 [SandboxManager.runGuestCommand]。
 *
 * 注意它**不要求**沙箱 keepalive 处于运行态（宿主会另起一个 PRoot 进程），
 * 但 DSH 的插件安装需要 guest 的 node/pnpm，所以调用方在长任务前应确保沙箱可用。
 */
class SandboxGuestCommandRunner(
    private val sandbox: SandboxManager,
) : GuestCommandRunner {

    override suspend fun run(
        command: String,
        onLine: (String) -> Unit,
    ): GuestCommandResult {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val sink: (String) -> Unit = { line ->
            lines.add(line)
            onLine(line)
        }
        return when (val result = sandbox.runGuestCommand(command, sink)) {
            is AppResult.Success -> GuestCommandResult(true, lines.toList())
            is AppResult.Failure -> {
                // 命令本身失败时宿主回的是 Failure；把已收到的行一并带出去做归因。
                val merged = lines.toMutableList().apply { add(result.error.message) }
                GuestCommandResult(false, merged)
            }
        }
    }
}
