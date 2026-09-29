package com.dshbox.app.sandbox

/**
 * DSH 健康循环的判定策略。
 *
 * 把「探测结果 + 窗口状态 → 下一步动作」抽成无副作用函数：时间由调用方以单调毫秒传入，
 * 函数只做状态迁移，因此不必起进程、不必等真实时间即可用 JVM 单测覆盖各种时序组合。
 *
 * 判定分档：
 *  - 探测正常即就绪；但**连续成功**达到 [DshHealthPolicy.HEALTHY_STREAK_TO_CLOSE] 次才关闭
 *    不应答窗口与清零重试预算，避免「半死」实例靠零星的单次成功反复清零窗口而永不自愈。
 *  - 进程句柄消失属确定性宕机，立即进入有界重启（不等窗口）。
 *  - 句柄仍存活却持续不应答时，只有连续失败超过配置窗口才判定不可用。
 *  - 设备未唤醒（休眠）期间不推进任何计时：把窗口与启动计时起点前移，等效于挂起判定。
 *  - 与上一轮间隔超过 [DshHealthPolicy.PROBE_GAP_REARM_MS] 视为采样中断（循环被冻结或长时间
 *    不被调度），同样重新起算，避免用跨越中断的陈旧差值直接判定。
 */
internal object DshHealthPolicy {
    /** 连续成功达到该次数才关闭不应答窗口并清零重试预算（约 3 轮 ≈ 6 秒）。 */
    const val HEALTHY_STREAK_TO_CLOSE = 3

    /**
     * 与上一轮的实际间隔超过该值即认为采样中断。
     *
     * 正常间隔只有轮询间隔与调度开销（约 2 秒），该阈值留出充足余量，只会在循环真的被
     * 冻结或长时间未获调度时命中。
     */
    const val PROBE_GAP_REARM_MS = 15_000L

    /**
     * 连续不应答超过该时长即对外提示「无响应」。
     *
     * 只用于界面提示与其它展示层信号，**不参与**是否重启的判定：判定仍按
     * [SandboxConfig.dshUnresponsiveGraceMs] 走，避免把「提前告诉用户」与「动手重启」
     * 两件事耦合在一起。
     */
    const val UNRESPONSIVE_HINT_MS = 15_000L
}

/** 窗口状态：健康循环持有的全部可变判定状态。 */
internal data class DshWindowState(
    /** 本实例是否曾经就绪过；未就绪阶段适用首次启动超时规则。 */
    val wasReady: Boolean,
    /** 已消耗的自动重启次数。 */
    val restartAttempts: Int,
    /** 不应答窗口的起点（单调毫秒）；null 表示当前没有未闭合的窗口。 */
    val unresponsiveSinceMs: Long?,
    /** 本轮启动计时的起点（单调毫秒）；重启或挂起后前移。 */
    val startedAtMs: Long,
    /** 连续成功次数，达到上限才关闭窗口。 */
    val healthyStreak: Int,
) {
    companion object {
        fun fresh(nowMs: Long) = DshWindowState(
            wasReady = false,
            restartAttempts = 0,
            unresponsiveSinceMs = null,
            startedAtMs = nowMs,
            healthyStreak = 0,
        )
    }
}

/** 单轮判定的输入。 */
internal data class DshHealthProbe(
    val nowMs: Long,
    /** 端口可连且 HTTP 有响应（任何状态码）。 */
    val webUiReady: Boolean,
    /** 进程句柄是否存活。 */
    val processAlive: Boolean,
    /** 设备是否唤醒且亮屏；未唤醒期间不推进判定。 */
    val deviceInteractive: Boolean,
    /** 与上一轮的实际间隔（不含本次探测耗时）；首轮传 0。 */
    val probeGapMs: Long,
)

/** 判定结果；每种结果都带出迁移后的窗口状态。 */
internal sealed interface DshHealthDecision {
    val next: DshWindowState

    /** 探测正常：状态置就绪。 */
    data class Ready(override val next: DshWindowState) : DshHealthDecision

    /** 继续观察，不动进程。 */
    data class Observe(override val next: DshWindowState) : DshHealthDecision

    /** 有界重启：调用方就地重启 DSH 进程。 */
    data class Restart(override val next: DshWindowState) : DshHealthDecision

    /** 首次启动超时：调用方清理进程并置 ERROR。 */
    data class InitialTimeout(override val next: DshWindowState) : DshHealthDecision

    /**
     * 重试预算用尽。
     *
     * 调用方需先复查实例是否已恢复应答：仍应答则保留实例并重新观察，确属不可用才清理置 ERROR。
     */
    data class Exhausted(override val next: DshWindowState) : DshHealthDecision
}

internal fun decideDshHealth(
    state: DshWindowState,
    probe: DshHealthProbe,
    readyTimeoutMs: Long,
    unresponsiveGraceMs: Long,
    maxRestartAttempts: Int,
): DshHealthDecision {
    var current = state
    // 设备休眠（guest 会被整体冻结）或采样中断（循环被冻结/长时间未获调度）：
    // 两者都重新起算窗口与启动计时，不使用跨越中断的差值做判定。
    if (!probe.deviceInteractive || probe.probeGapMs > DshHealthPolicy.PROBE_GAP_REARM_MS) {
        current = current.copy(unresponsiveSinceMs = null, startedAtMs = probe.nowMs)
    }

    if (probe.webUiReady) {
        val streak = current.healthyStreak + 1
        val closed = streak >= DshHealthPolicy.HEALTHY_STREAK_TO_CLOSE
        return DshHealthDecision.Ready(
            current.copy(
                wasReady = true,
                healthyStreak = streak,
                unresponsiveSinceMs = if (closed) null else current.unresponsiveSinceMs,
                restartAttempts = if (closed) 0 else current.restartAttempts,
            ),
        )
    }

    // 探测失败：连续成功计数归零。
    current = current.copy(healthyStreak = 0)

    if (!probe.processAlive) {
        // 确定性宕机：即使设备在休眠也立即重建。
        return restartOrExhausted(current, probe.nowMs, maxRestartAttempts)
    }

    if (!probe.deviceInteractive) {
        // 休眠期间不开启也不推进窗口（上方重新起算已把窗口置空），唤醒后从头观察。
        return DshHealthDecision.Observe(current)
    }

    if (!current.wasReady) {
        // 首次启动给满配置超时；进程仍存活却始终不就绪才判失败。
        return if (probe.nowMs - current.startedAtMs > readyTimeoutMs) {
            DshHealthDecision.InitialTimeout(current)
        } else {
            DshHealthDecision.Observe(current)
        }
    }

    val since = current.unresponsiveSinceMs ?: probe.nowMs
    current = current.copy(unresponsiveSinceMs = since)
    return if (probe.nowMs - since >= unresponsiveGraceMs) {
        restartOrExhausted(current, probe.nowMs, maxRestartAttempts)
    } else {
        DshHealthDecision.Observe(current)
    }
}

private fun restartOrExhausted(
    state: DshWindowState,
    nowMs: Long,
    maxRestartAttempts: Int,
): DshHealthDecision {
    val attempts = state.restartAttempts + 1
    return if (attempts < maxRestartAttempts) {
        // 重启后按新实例重新计：启动计时前移、窗口清空，且回到「尚未就绪」——
        // 新实例此后若始终不就绪，走的是首次启动超时（判失败），而不是再吃一次窗口。
        DshHealthDecision.Restart(
            state.copy(
                restartAttempts = attempts,
                startedAtMs = nowMs,
                unresponsiveSinceMs = null,
                wasReady = false,
            ),
        )
    } else {
        DshHealthDecision.Exhausted(state.copy(restartAttempts = attempts))
    }
}
