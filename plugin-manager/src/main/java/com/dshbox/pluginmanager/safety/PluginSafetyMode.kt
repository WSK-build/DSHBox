package com.dshbox.pluginmanager.safety

import android.util.Log
import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.app.sandbox.DshState
import com.dshbox.app.sandbox.SandboxManager
import com.dshbox.pluginmanager.core.PluginPaths
import com.dshbox.pluginmanager.layer.HostInfrastructure
import com.dshbox.pluginmanager.layer.PluginLayer
import com.dshbox.pluginmanager.safemode.AbsoluteSafeMode
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 一次守护动作的结果。 */
data class GuardOutcome(
    val ok: Boolean,
    val isolated: List<IsolationRecord>,
    val rounds: Int,
    /** 给用户看的一句话（成功或失败原因都走这里）。 */
    val message: String,
)

/**
 * 安全模式：DSH 启动失败时定位出错插件、把它跳过，**把整个 DSH 加载起来**。
 *
 * 三级降级：
 * 1. **归因**：从 DSH 进程日志里认出出错的插件（[FailureAttribution]）。
 * 2. **收敛**：写我们的层把认出来的条目停用，重启 DSH 再判定；每轮必须至少新隔离
 *    一个条目（无进展就不再空转），轮次上限见 [GuardState.MAX_ROUNDS]。
 * 3. **兜底**：归因不出来、或"跳过"用尽预算仍起不来时，直接切绝对安全模式
 *    （不加载任何第三方插件），保证 DSH 能起来。
 *
 * 触发方式有两种：界面显式调用 [runStartupGuard]，以及 [startLifecycleObserver]
 * 观察到 DSH 进入错误态后自动触发（这是"自动跳过出错插件"这句承诺的落点）。
 *
 * 与上游的差异：上游要自带一个"脱离终端的重启助手"才能让恢复页在宿主死后
 * 还能打开；我们的 app 本来就在宿主进程之外，重启直接走沙箱管理器，
 * 恢复页就是 app 自己的界面，因此这里不需要那套机制。
 *
 * 线程约定：所有 suspend 方法都由界面或观察协程调用；[state] 是唯一对外状态。
 *
 * **语言限制**：事件文案（onEvent 的参数）目前只有中文。它们由领域层直接产生，
 * 面向非中文用户时会回退中文原文；要完整本地化需要把领域层文案改成资源 id + 参数
 * （界面已经按"逐行覆盖显示"处理，不依赖具体措辞）。
 */
class PluginSafetyMode(
    private val paths: PluginPaths,
    private val layer: PluginLayer,
    private val store: GuardStore,
    private val sandbox: SandboxManager,
    private val absolute: AbsoluteSafeMode,
    /** 安装层实际自带的包名（[com.dshbox.pluginmanager.core.HostPackages] 推导）。 */
    private val installedPackages: Set<String> = emptySet(),
    /**
     * 安全模式启动过程的提示（app 统一的 Toast，见 [GuardToast]）。
     *
     * 只在这四个节点发：每轮跳过插件、本轮跳过且成功、改开启绝对安全模式、停止重试。
     * 其余情况一律不弹。
     */
    private val toast: GuardToast? = null,
) : com.dshbox.pluginmanager.core.IsolationLedger {

    private fun log(message: String) {
        Log.i(GUARD_TAG, message)
    }

    private val _state = MutableStateFlow(store.load())
    val state: StateFlow<GuardState> = _state.asStateFlow()

    private val guardMutex = Mutex()
    private var observerJob: Job? = null

    /** 同一时刻只允许一个守卫在跑：重叠的守卫会把"一轮"变成好几轮，用户等得更久。 */
    private val guardRunning = AtomicBoolean(false)

    /** 本次启动尝试的失败探针（每次进入 STARTING 重新起一个）。 */
    private var probeJob: Job? = null

    /** 观察者所在的 scope：开关打开时要借它立刻做一次核对。 */
    private var observerScope: CoroutineScope? = null

    /** 本次尝试的日志起点指纹（探针在启动时记下），用于切出"这次新写进去的内容"。 */
    @Volatile
    private var attemptBaseline: String = ""

    // ---------------------------------------------------------------- 开关

    /** 切换安全模式。只改状态，不写层；**打开时重置降级轮次**（这是唯一的显式重置入口）。 */
    fun setSafetyMode(enabled: Boolean): GuardState {
        val before = store.load().safetyMode
        val mutation = store.mutate { it.copy(safetyMode = enabled, roundsUsed = if (enabled) 0 else it.roundsUsed) }
        _state.value = mutation.state
        log("safety mode ${if (enabled) "on" else "off"}: rounds=${mutation.state.roundsUsed}")
        // 打开开关不该"等下一次启动才生效"：DSH 可能正处在"服务在监听、插件树坏掉、
        // 界面是报错页"的状态，用户按下开关的这一刻就应该有人去看一眼。
        if (enabled && !before) checkCurrentBootSoon()
        return mutation.state
    }

    /**
     * 开关打开后立刻核对"当前这次启动"：有新出现的失败点名就马上走守卫。
     *
     * 用探针记下的**本次尝试起点指纹**切分新内容（与探针同一套判据），
     * 没有指纹（还没观察到一次启动）就什么也不做——宁可不动，也不拿旧日志猜。
     */
    private fun checkCurrentBootSoon() {
        val scope = observerScope ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                val baseline = attemptBaseline
                if (baseline.isEmpty()) return@withContext
                val hits = failureHitsSince(baseline, readCurrentLog())
                if (hits.isEmpty()) return@withContext
                log(
                    "safety-mode-on: 当前这次启动就有失败点名 " +
                        hits.joinToString(", ") { it.id ?: it.name ?: "?" } + "，立即处理",
                )
                runStartupGuard()
            }
        }
    }

    /**
     * 切换绝对安全模式。
     *
     * 打开 = 把合成配置里的第三方条目逐条停用（写我们的层），并记下改动前状态；
     * 关闭 = 只按记录回滚我们改的东西，用户手动关掉的插件保持关闭。
     */
    suspend fun setAbsoluteMode(
        enabled: Boolean,
        onEvent: (String) -> Unit = {},
        /**
         * 写完层是否立刻重启 DSH。
         *
         * 默认 true：这个模式的语义是"**现在**不要加载任何第三方插件"，而层要到
         * 下次启动才参与组合——只写不重启，用户会看到"开了但插件照样在跑"（真机上就是这样）。
         * 守卫内部调用时传 false：它自己会重启。
         */
        restart: Boolean = true,
    ): AppResult<GuardState> {
        val current = store.load()
        if (enabled) {
            // profile 未变时直接用上次的条目集合，省掉一次 10~30 秒的 `--dump-config`。
            val cached = absolute.cachedTargets()
            val targets: List<String>
            if (cached != null) {
                targets = cached
                onEvent("插件清单未变，直接使用上次的停用集合…")
            } else {
                onEvent("正在读取合成后的插件清单…")
                val parsed = when (val result = absolute.composedEntries()) {
                    is AppResult.Success -> result.value
                    is AppResult.Failure -> return AppResult.Failure(result.error)
                }
                targets = absolute.thirdPartyIds(parsed.entries)
                absolute.rememberTargets(targets)
                if (parsed.unnamedIds.isNotEmpty()) {
                    onEvent("另有 ${parsed.unnamedIds.size} 个条目没有包名行，未能判断是否为第三方")
                }
            }
            onEvent("第三方插件 ${targets.size} 个，正在写入停用行…")
            when (val result = absolute.apply(targets)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> return AppResult.Failure(result.error)
            }
            val mutation = store.mutate { it.copy(absoluteMode = true) }
            _state.value = mutation.state
            log("absolute ON: disabled=${targets.size} ids=${targets.joinToString(",")}")
            if (restart) {
                onEvent("正在重启 DSH 让停用生效…")
                val outcome = restartAndJudge(
                    onEvent = onEvent,
                    isolated = emptyList(),
                    rounds = mutation.state.roundsUsed,
                    successMessage = "已停用 ${targets.size} 个第三方插件并重启完成",
                )
                if (!outcome.ok) {
                    return AppResult.Failure(
                        AppError(code = "ABSOLUTE_RESTART_FAILED", message = outcome.message),
                    )
                }
            }
            return AppResult.Success(store.load())
        }

        // 关闭：删掉本模式独占的那一层。守卫的隔离行在**另一份文件**里，不受影响。
        when (val cleared = absolute.clear()) {
            is AppResult.Failure -> return cleared
            is AppResult.Success -> Unit
        }
        val mutation = store.mutate { it.copy(absoluteMode = false) }
        _state.value = mutation.state
        log("absolute OFF: 已删除独占层")
        if (restart) {
            onEvent("正在重启 DSH 让恢复生效…")
            val outcome = restartAndJudge(
                onEvent = onEvent,
                isolated = emptyList(),
                rounds = mutation.state.roundsUsed,
                successMessage = "已恢复第三方插件并重启完成",
            )
            if (!outcome.ok) {
                return AppResult.Failure(
                    AppError(code = "ABSOLUTE_RESTART_FAILED", message = outcome.message),
                )
            }
        }
        return AppResult.Success(store.load())
    }

    /**
     * 市场里启停过这些条目 → 不再算"被安全模式隔离"。
     *
     * 见 [com.dshbox.pluginmanager.core.IsolationLedger]：用户亲手改过状态的条目，
     * 我们的记录必须让位，否则清单与市场会显示两个相反的状态，而且下一步的
     * "清单↔层对齐"会把用户刚启用的插件又翻回停用。
     */
    override fun forget(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val records = store.load().isolated
        val stale = records.filter { it.id in ids }
        if (stale.isEmpty()) return
        val mutation = store.mutate { state ->
            state.copy(isolated = state.isolated.filterNot { it.id in ids })
        }
        _state.value = mutation.state
        log("isolation forgot: 市场里已手动改过这些条目 → ${stale.map { it.id }}")
    }

    // ------------------------------------------------------------ 隔离清单

    /**
     * 恢复启用一个被隔离的插件。
     *
     * 做法是**删掉我们写的那条停用行**，而不是写 `disabled: false`：
     * 后者会在层里留下一条"强制启用"，一旦这个插件其实是确定性崩溃的，
     * 每次启动都会重新应用它——那就变成了崩溃循环。删行则回到"默认启用"，
     * 若它仍然崩溃，下一次启动守卫会重新把它隔离。
     *
     * 与启停一样，本轮不尝试热挂载：下次启动生效。
     */
    fun restorePlugin(id: String): AppResult<Unit> {
        if (!layer.removePatchEntry(id)) {
            return AppResult.Failure(
                AppError(code = "LAYER_WRITE_FAILED", message = "层文件写入失败，未解除隔离"),
            )
        }
        val mutation = store.mutate { it.copy(isolated = it.isolated.filterNot { r -> r.id == id }) }
        _state.value = mutation.state
        return AppResult.Success(Unit)
    }

    // ---------------------------------------------------------- 启动守护

    /**
     * 执行一次守护：归因 → 隔离 → 重启 → 判定。**只做一轮**。
     *
     * 为什么只做一轮：下一次"启动尝试又失败了"本来就由观察者的探针重新触发
     * （见 [startFailureProbe]），守卫自己再循环反而会和探针、和 app 自己的重试
     * 叠在一起。更关键的是**重启与等待必须放在互斥锁之外**——早先的实现把
     * `重启 + 等就绪（上限 150 秒）` 整段锁住，实测第二次触发被第一次堵了 77 秒，
     * 用户看到的"两分半才起来"里有一半是这把锁。
     */
    suspend fun runStartupGuard(onEvent: (String) -> Unit = {}): GuardOutcome {
        if (!guardRunning.compareAndSet(false, true)) {
            val rounds = store.load().roundsUsed
            log("guard skip: 已有守卫在跑，忽略这次触发")
            return GuardOutcome(false, emptyList(), rounds, "守卫正在运行，已忽略重复触发")
        }
        return try {
            when (val step = guardMutex.withLock { planNextLocked(onEvent) }) {
                is GuardStep.Finish -> {
                    // 只有"停止重试"这类收敛才提示；"安全模式未开启"之类的空转不发。
                    if (!step.outcome.ok && step.outcome.rounds >= GuardState.MAX_ROUNDS) {
                        toast?.gaveUp()
                    }
                    step.outcome
                }
                is GuardStep.Restart -> {
                    onEvent(step.pendingMessage)
                    // 绝对安全模式那条路已经在 Escalate 时提示过，这里不重复弹。
                    if (step.isolated.isNotEmpty()) {
                        toast?.attempting(step.rounds, step.isolated.map { it.id })
                    }
                    val outcome = restartAndJudge(
                        onEvent,
                        step.isolated,
                        step.rounds,
                        step.successMessage,
                    )
                    if (outcome.ok && step.isolated.isNotEmpty()) {
                        toast?.recovered(step.isolated.map { it.id })
                    }
                    outcome
                }
                is GuardStep.Escalate -> {
                    toast?.escalatedToAbsolute()
                    escalateToAbsolute(step, onEvent)
                }
            }
        } finally {
            guardRunning.set(false)
        }
    }

    /**
     * 兜底：归因不出来时，切到绝对安全模式（不加载任何第三方插件）再重启。
     *
     * 只在安全模式开着、且绝对安全模式没开时才会走到这里（[planNextLocked] 的分支顺序保证）。
     * 这一步**不消耗降级轮次**：它不猜是哪个插件，只是把"能启动"这件事拿回来；
     * 起不来时用户看到的是一句明确的结论 + 界面上的绝对安全模式已是开启状态。
     */
    private suspend fun escalateToAbsolute(step: GuardStep.Escalate, onEvent: (String) -> Unit): GuardOutcome {
        log("guard escalate: ${step.reason}")
        onEvent(step.reason)
        when (val result = setAbsoluteMode(true, onEvent, restart = false)) {
            is AppResult.Failure ->
                return GuardOutcome(false, emptyList(), step.rounds, result.error.message)
            is AppResult.Success -> Unit
        }
        onEvent("正在重启 DSH…")
        val outcome = restartAndJudge(
            onEvent = onEvent,
            isolated = emptyList(),
            rounds = step.rounds,
            successMessage = "已改为不加载任何第三方插件启动（绝对安全模式）",
        )
        return if (outcome.ok) {
            outcome
        } else {
            GuardOutcome(
                ok = false,
                isolated = emptyList(),
                rounds = step.rounds,
                message = "第三方插件已全部停用后仍未能启动，问题不在插件上（${outcome.message}）",
            )
        }
    }

    /** 守卫一轮的归宿：要么直接给结论，要么"层已改好，去重启"。 */
    private sealed interface GuardStep {
        data class Finish(val outcome: GuardOutcome) : GuardStep

        data class Restart(
            val isolated: List<IsolationRecord>,
            val rounds: Int,
            /** 重启前给用户看的话。 */
            val pendingMessage: String,
            /** 重启后就绪时的结论。 */
            val successMessage: String,
        ) : GuardStep

        /**
         * 归因不出来（日志为空 / 认得的名字对不上层里的条目）：改用绝对安全模式兜底。
         *
         * 这是"先把 DSH 拉起来"的最后一条路——不加载任何第三方插件，一定能起。
         */
        data class Escalate(val reason: String, val rounds: Int) : GuardStep
    }

    /**
     * 守卫的决策与落盘（读日志 → 归因 → 写层）。
     *
     * **只在 [guardMutex] 里跑**：这类"读-改-写"是本模块唯一的共享状态，
     * 而重启与等待是慢操作，放在锁里会把别的触发者全堵住。
     */
    private suspend fun planNextLocked(onEvent: (String) -> Unit): GuardStep {
        val snapshot = store.load()
        val rounds = snapshot.roundsUsed
        log(
            "guard start: safety=${snapshot.safetyMode} absolute=${snapshot.absoluteMode} " +
                "rounds=$rounds/${GuardState.MAX_ROUNDS} isolated=${snapshot.isolated.map { it.id }} " +
                "hostPackages=${installedPackages.size} layer=[${layerSummary()}]",
        )

        // 先对齐"清单"与"层"：界面说已隔离、层里却启用着，是必须当场修掉的不一致。
        // 补写的条数要留着：它算"这一轮的进展"——清单里记着、层里丢了，补回来本身就
        // 可能让这次启动成功，不该被判成"无进展"而直接转兜底。
        val repaired = if (snapshot.safetyMode) reconcileIsolation() else 0

        // 轮次跨进程单调：只有用户重新打开安全模式才会清零。若在"启动成功"时清零，
        // "就绪→随后崩溃"的循环每轮都能重新拿到配额，上限形同虚设。
        if (rounds >= GuardState.MAX_ROUNDS) {
            // 预算用完了还是没起来：继续"跳过"已经不是好策略，改为保证能起来的那条路。
            return GuardStep.Escalate(
                reason = "自动跳过已达上限（${GuardState.MAX_ROUNDS} 轮）仍未就绪，改用不加载任何第三方插件的方式启动",
                rounds = rounds,
            )
        }

        if (snapshot.absoluteMode) {
            // 绝对安全模式 = "现在不加载任何第三方插件"，它的停用行在**独占层**里。
            // 层已经在位时**不再重跑 `--dump-config`**：那是一次 10~30 秒的沙箱命令，
            // 而且在 DSH 卡死时还可能失败，会把"重启一次就好"变成一次报错。
            // 只有层真的不见了（被清理/写坏）才重建。
            if (!absolute.isApplied()) {
                onEvent("绝对安全模式的停用层缺失，正在重建…")
                when (val result = setAbsoluteMode(true, onEvent, restart = false)) {
                    is AppResult.Failure ->
                        return GuardStep.Finish(GuardOutcome(false, emptyList(), rounds, result.error.message))
                    is AppResult.Success -> Unit
                }
            }
            // **不占用"跳过坏插件"的轮次预算**：绝对安全模式是"保证能起来"的那条路，
            // 它不猜是哪个插件，只把"不加载任何第三方插件"落实并重启。
            //
            // 历史：此处曾加入"计入轮次、到上限就停"的上界，结果是——
            // 隔离已经用掉几轮之后再切到绝对安全模式，守卫会**直接不再重启**，
            // 真机表现为"绝对安全模式无法启动 DSH"。那是底层机制，不该为提示去动它。
            return GuardStep.Restart(
                isolated = emptyList(),
                rounds = rounds,
                pendingMessage = "正在重启 DSH…",
                successMessage = "已按绝对安全模式启动完成",
            )
        }

        if (!snapshot.safetyMode) {
            return GuardStep.Finish(GuardOutcome(false, emptyList(), rounds, "安全模式未开启，未做任何改动"))
        }

        // 层文件本身坏掉时，DSH 会静默丢弃整层——此时任何停用都不生效，
        // 必须先把它拉回可用状态（备份原文件后重置为空层）。
        if (!layer.read().parseOk) {
            onEvent("层文件无法解析，已备份并重置为空层")
            if (!layer.backupAndReset()) {
                return GuardStep.Finish(
                    GuardOutcome(false, emptyList(), rounds, "层文件既读不出也写不进（备份失败），未做改动"),
                )
            }
            // 层被重置后，之前写进去的停用行都不在了：隔离清单必须一起清空，
            // 否则界面继续显示"已跳过"，而磁盘上早已不再停用它们。
            val cleared = store.mutate { it.copy(isolated = emptyList()) }
            _state.value = cleared.state
            return GuardStep.Restart(
                isolated = emptyList(),
                rounds = rounds,
                pendingMessage = "正在重启 DSH…",
                successMessage = "层文件已重置为可用状态，隔离清单已清空",
            )
        }

        val text = readCurrentLog()
        val hits = FailureAttribution.parse(text)
        if (hits.isEmpty()) {
            // 日志里一个字都没有的失败是真实存在的一类：插件在**同步代码里死循环**时，
            // node 的 stdout 永远不刷新，进程占着一整个核、端口不监听、也不退出。
            // 这类故障归因不出来是谁，硬猜一个插件去停用等于乱改用户配置，
            // 因此交给绝对安全模式兜底：不加载任何第三方插件，先把 DSH 拉起来。
            return GuardStep.Escalate(
                reason = "日志里没有可归因的失败信息（可能是插件把进程卡死），改用不加载任何第三方插件的方式启动",
                rounds = rounds,
            )
        }
        // 归因原文进 logcat：真机上出问题时只有这一行能证明"到底是哪个条目坏了"。
        log(
            "guard 归因: " + hits.joinToString(", ") { "${it.id ?: "-"}/${it.name ?: "-"}" } +
                " 原文: " + FailureAttribution.failureSnippet(text),
        )

        // "等待服务"型失败 = 服务提供者不在。**不能靠猜去改 profile**：
        // 这类故障的处置（补装包 / 恢复 bundle 清单）会让用户看到结论与命令后自己决定，
        // 我们只写自己层里的停用行。历史上这里自动改过 profile，代价是把官方 bundle 删掉了。
        val resolved = resolveTargets(hits, onEvent)
        // 二层保险：resolveTargets 已按包名过滤过，这里再按同一条规则复核一次。
        val candidates = resolved.records
            .filterNot { HostInfrastructure.isProtected(it.id, it.name, installedPackages) }
        if (candidates.isEmpty()) {
            // 认得出名字、但对不上层里的条目：同样是"归因不出来"，交给绝对安全模式兜底。
            if (resolved.unmapped > 0) {
                return GuardStep.Escalate(
                    reason = "出错条目没能对应到层里的条目（可能是复合条目名），改用不加载任何第三方插件的方式启动",
                    rounds = rounds,
                )
            }
            // 出错的确实是官方/合成的基础设施：停用任何插件都救不了它，
            // 这时候的结论只能是"这不是插件的问题"。
            return GuardStep.Finish(
                GuardOutcome(
                    ok = false,
                    isolated = emptyList(),
                    rounds = rounds,
                    message = "出错的是宿主基础设施（官方条目），不是第三方插件，自动隔离无法解决",
                ),
            )
        }

        // 进度检查：这一轮要停用的条目里**必须至少有一个是新的**。全是老面孔说明
        // "跳过它"这条策略已经不奏效（例如坏的不是这个插件），再重启只是白等一轮，
        // 直接交给绝对安全模式兜底。
        val alreadyIsolated = snapshot.isolated.map { it.id }.toSet()
        val newly = candidates.filterNot { it.id in alreadyIsolated }
        if (newly.isEmpty()) {
            // 清单里已经有这些条目：层若刚刚被补回来（repaired），值得再重启试一次；
            // 否则说明"跳过它"这条路走不通（已经跳过还是同一个失败），转绝对安全模式。
            if (repaired > 0) {
                return GuardStep.Restart(
                    isolated = emptyList(),
                    rounds = rounds,
                    pendingMessage = "已补回 ${repaired} 条被改掉的隔离行，正在重启 DSH…",
                    successMessage = "已补回隔离行并启动成功",
                )
            }
            return GuardStep.Escalate(
                reason = "出错的条目（${candidates.joinToString(", ") { it.id }}）此前已隔离但仍然起不来，改用不加载任何第三方插件的方式启动",
                rounds = rounds,
            )
        }

        val written = newly.filter { layer.setDisabled(it.id, true) }
        if (written.isEmpty()) {
            return GuardStep.Finish(
                GuardOutcome(false, emptyList(), rounds, "层文件写入失败，未隔离任何插件"),
            )
        }
        // 写完之后立刻回读一遍层：`isolate` 这行日志必须能证明"停用行真的落盘了"。
        log(
            "guard isolate: " + written.joinToString(", ") { "${it.id}(pkg=${it.name})" } +
                " → layer=[${layerSummary()}]",
        )
        val mutation = store.mutate {
            it.copy(
                isolated = it.isolated.filterNot { old -> written.any { w -> w.id == old.id } } + written,
                roundsUsed = it.roundsUsed + 1,
            )
        }
        _state.value = mutation.state

        return GuardStep.Restart(
            isolated = written,
            rounds = mutation.state.roundsUsed,
            pendingMessage = "已跳过 ${written.size} 个插件，正在重启 DSH…",
            successMessage = "已跳过 ${written.size} 个插件并启动成功",
        )
    }

    /**
     * 层文件现状（诊断用）：`id:on/off` 列表。
     *
     * 真机上"界面说已跳过、实际没停用"这类事故，只有这一行能证伪——它读的是磁盘。
     */
    private fun layerSummary(max: Int = 8): String {
        val doc = layer.read()
        if (!doc.parseOk) return "解析失败" + (doc.readError?.let { "($it)" } ?: "")
        val rows = doc.patches.take(max).joinToString(", ") {
            "${it.id}:${if (it.disabled == true) "off" else "on"}"
        }
        val more = if (doc.patches.size > max) ", +${doc.patches.size - max}" else ""
        return if (rows.isEmpty()) "空" else rows + more
    }

    /**
     * 重启并判定结果。
     *
     * 只在**守卫成功完成一轮**时清零轮次——放在观察者里清零会让
     * "启动成功 → 随后崩溃"无限循环，上限形同虚设。
     */
    private suspend fun restartAndJudge(
        onEvent: (String) -> Unit,
        isolated: List<IsolationRecord>,
        rounds: Int,
        successMessage: String,
    ): GuardOutcome {
        val restarted = sandbox.restartDsh()
        if (restarted is AppResult.Failure) {
            return GuardOutcome(
                ok = false,
                isolated = isolated,
                rounds = rounds,
                message = RESTART_FAILED_PREFIX + restarted.error.message,
            )
        }
        val ready = awaitSettled()
        return if (ready) {
            // 刻意不清零轮次：清零等于让"就绪 → 随后崩溃"的循环每轮都拿到新配额。
            // 轮次只在用户重新打开安全模式（显式动作）时归零。
            GuardOutcome(true, isolated, rounds, successMessage)
        } else {
            GuardOutcome(false, isolated, rounds, "重启后 DSH 仍未就绪")
        }
    }

    // ------------------------------------------------------------ 日志读取

    /**
     * 读取 DSH 启动日志全文（按启动分段保留，见 [BootSegmentedLog]）。
     *
     * 保留策略在**写入侧**：只留最近 N 段、单段有上限；这里只加一道读取上限兜底 ——
     * 历史遗留的超大文件不该被整个读进内存。段与段的边界是写入侧写的标记行，
     * 界面据它把每段开头渲染成红色时间戳。
     */
    fun readBootLog(limit: Int = LOG_READ_LIMIT): String = tail(paths.dshProcessLog, limit)

    /**
     * 守卫内部用的读取器：仍是"当前日志的尾部 8KB"，与改动前**逐字一致**。
     *
     * 归因逻辑靠"日志长度是否增长"判断有没有新进展（见下方 baseline 比较），
     * 所以这里刻意保留原来的 8KB 口径，不跟着面板改成读全文。
     */
    fun readCurrentLog(limit: Int = LOG_TAIL_CHARS): String = tail(paths.dshProcessLog, limit)

    /*
     * 封存：「上次启动」的读取已停用（与下面 snapshotBootLog 一并封存）。
     * 那一栏依赖"启动前抓的快照"，而抓快照挂在被封存的生命周期观察者里；面板也已去掉这一栏。
     * 恢复步骤：取消本段注释 + 恢复观察者里的调用 + 恢复 PluginPaths.previousBootLogFile + 恢复面板那一栏。
     *
     * fun readLogText(current: Boolean, limit: Int = LOG_TAIL_CHARS): String {
     *     val file = if (current) paths.dshProcessLog else paths.previousBootLogFile
     *     return tail(file, limit)
     * }
     */

    /** DSH 日志文件是否已经有内容（用于界面提示"还没有记录"）。 */
    fun hasAnyLog(): Boolean = paths.dshProcessLog.isFile && paths.dshProcessLog.length() > 0L

    /**
     * 观察 DSH 生命周期：启动前抓日志快照；进入错误态并稳定后自动执行守护。
     *
     * 刻意加一个短暂延迟再动手：DSH 的错误态可能是瞬时的（沙箱自身的健康检查
     * 会先尝试重启），过早介入会和它抢着重启。
     */
    fun startLifecycleObserver(scope: CoroutineScope) {
        observerScope = scope
        if (observerJob?.isActive == true) return
        observerJob = scope.launch {
            // 观测者是否活着，只看这一行：真机上排查过"守卫一行日志都没有"，
            // 分不清是没观察、还是观察了没动作。有了它就有定论。
            log(
                "observer: 开始观察 DSH 生命周期（safety=${store.load().safetyMode} " +
                    "absolute=${store.load().absoluteMode}）",
            )
            // 观察者一起来就把"清单 ↔ 层"对齐一次。这是应用中最早能修掉不一致的时机，
            // 而一次启动里最值钱的就是"下一个 DSH 进程起来之前"。
            withContext(Dispatchers.IO) {
                if (store.load().safetyMode) reconcileIsolation()
            }
            var last: DshState? = null
            sandbox.dshState.collect { current ->
                if (current == DshState.STARTING) {
                    // 封存：「上次启动」快照已停（见 snapshotBootLog）。启动分段由写入侧负责。
                    // if (last != DshState.STARTING) snapshotBootLog()
                    // 每次进入 STARTING（含守卫自己发起的重启）都重新起一个探针：
                    // 只认"上一次是 STARTING 吗"会在连续两次 STARTING 之间漏掉一次尝试。
                    startFailureProbe(scope)
                }
                if (current == DshState.ERROR && last != DshState.ERROR) {
                    launch {
                        delay(ERROR_SETTLE_MS)
                        if (sandbox.dshState.value != DshState.ERROR) return@launch
                        val snapshot = store.load()
                        if (!snapshot.safetyMode && !snapshot.absoluteMode) return@launch
                        // 这里**不**按轮次提前退出：轮次用完时守卫会转向绝对安全模式兜底，
                        // 那正是"起不来"这件事该有的收敛方向。
                        // 守卫全程读写文件（层、状态、日志）：观察者跑在主线程的
                        // 作用域上，必须显式切到 IO，否则界面会被磁盘 IO 卡住。
                        withContext(Dispatchers.IO) { runStartupGuard() }
                    }
                }
                last = current
            }
        }
    }

    /**
     * 启动过程中盯着日志：**本次尝试一结束就立刻动手**，不等 app 的错误态。
     *
     * app 的启动失败每次要阻塞满超时（约 120 秒）并重试 3 次，走完五六分钟；
     * 而 DSH 的失败报告几秒内就写进日志了，所以这里每 [FAILURE_PROBE_INTERVAL_MS] 看一眼。
     *
     * 判据是**收尾行计数**（[FailureAttribution.trailerCount]），不是"日志里有没有失败文本"：
     * 旧尝试的失败文本会一直留在文件里，按文本判定会在起步几秒就误判——
     * 真机上表现为"刚开跑就被判失败"和"已经起来了还白隔离一次"。
     * 只有比本次启动那一刻多出来的收尾行，才说明这一次尝试真的结束了。
     */
    private fun startFailureProbe(scope: CoroutineScope) {
        probeJob?.cancel()
        probeJob = scope.launch {
            withContext(Dispatchers.IO) {
                val baselineText = readCurrentLog()
                val baseline = FailureAttribution.trailerCount(baselineText)
                // 启动那一刻的尾部指纹：用它把"这次尝试新写进去的内容"切出来。
                // 日志尾巴有 8KB 上限，光比长度在滑窗下会失效（真机上尾巴基本总是满的）。
                val baselineSuffix = baselineText.takeLast(SUFFIX_PROBE_CHARS)
                attemptBaseline = baselineSuffix
                val deadline = System.currentTimeMillis() + FAILURE_PROBE_WINDOW_MS
                while (System.currentTimeMillis() < deadline) {
                    delay(FAILURE_PROBE_INTERVAL_MS)
                    val state = sandbox.dshState.value
                    if (state == DshState.READY) {
                        // **就绪 ≠ 加载成功**。实测：DSH 的 web 服务在监听、进程占满一个核，
                        // 界面却是它自己的报错页（插件树加载失败）；app 因此认为"就绪"，
                        // 于是所有基于错误态/收尾行的触发都不再命中，没人去救。
                        // 所以在"就绪"这一刻核一遍这次尝试新写进日志的内容。
                        val hits = failureHitsSince(baselineSuffix, readCurrentLog())
                        if (hits.isNotEmpty()) {
                            val snapshot = store.load()
                            if (snapshot.safetyMode || snapshot.absoluteMode) {
                                log(
                                    "ready-but-broken: 已就绪但本次启动报出插件失败 " +
                                        hits.joinToString(", ") { it.id ?: it.name ?: "?" },
                                )
                                runStartupGuard()
                            }
                        }
                        return@withContext
                    }
                    if (state != DshState.STARTING) return@withContext
                    // 守卫正在处理（它自己会重启并再判定）：让开，别抢着重启。
                    if (guardRunning.get()) continue
                    val snapshot = store.load()
                    if (!snapshot.safetyMode && !snapshot.absoluteMode) return@withContext
                    // 轮次用完也照样叫守卫：它会把这次失败收敛到绝对安全模式。
                    val text = readCurrentLog()
                    val ended = FailureAttribution.trailerCount(text) - baseline
                    if (ended <= 0) continue
                    log("early trigger: 本次启动尝试已结束（失败收尾行 +$ended）")
                    runStartupGuard()
                    return@withContext
                }
                // 窗口到了还没有收尾行。**卡死**（插件在同步代码里死循环）就永远不会
                // 有收尾行：日志一个字都不增加、进程占满一个核、端口不监听。
                // 这种时候只剩 app 自己的启动超时能判出来，而它要几分钟——
                // 所以只要"整段时间日志没有任何新增"，就按卡死处理，交给守卫走绝对安全模式兜底。
                // 代价说明：真正极慢的冷启动也会命中这条，处置是"不加载第三方插件先起来"，
                // 用户可以在界面上关掉这个模式重试，比卡六分钟强。
                if (sandbox.dshState.value == DshState.STARTING &&
                    readCurrentLog().length <= baselineText.length
                ) {
                    log("early trigger: $FAILURE_PROBE_WINDOW_MS ms 内日志毫无新增，判定为卡死（无收尾行）")
                    runStartupGuard()
                    return@withContext
                }
                log("early trigger: 窗口内没有收尾行，交给 app 的错误态处理")
            }
        }
    }

    /**
     * 在"这次尝试新写进日志的内容"里找失败点名。
     *
     * 用启动时的尾部指纹定位切分点，而不是比长度：日志尾巴只有 8KB，一旦写满就滑动，
     * 长度差恒定为零。指纹找不到（新内容太多把指纹挤出去了）时返回空——
     * **宁可不动，也不拿旧日志去猜**：误判会去隔离一个没坏的插件。
     */
    private fun failureHitsSince(baselineSuffix: String, text: String): List<FailureHit> {
        val at = if (baselineSuffix.isEmpty()) 0 else text.lastIndexOf(baselineSuffix)
        if (at < 0) return emptyList()
        val fresh = text.substring(at + baselineSuffix.length)
        if (fresh.isBlank()) return emptyList()
        return FailureAttribution.parse(fresh)
    }

    /** 停止观察（界面销毁时调用）。 */
    fun stopLifecycleObserver() {
        observerJob?.cancel()
        observerJob = null
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 把归因结果变成可写层的条目。
     *
     * 失败行里常常只有包名（没有 loader 条目 id）。**不能把包名直接当 id 写层**——
     * 那样写出的行打不中任何条目（DSH 会报 `patch: entry X not found`），
     * 隔离等于没做，还可能在我们自己的层里留下永久无效的行。
     * 所以这里先拿一次合成配置，用"包名 ↔ 条目名"的前缀规则映射；
     * **映射不到的条目一律不写**，只统计数量交给调用方提示用户（宁可少隔离一个，
     * 也不要在启动路径上写入一条打不中的行）。
     */
    private suspend fun resolveTargets(
        hits: List<FailureHit>,
        onEvent: (String) -> Unit,
    ): ResolvedTargets {
        val parsed = absolute.composedEntries()
        val entries = (parsed as? AppResult.Success)?.value?.entries.orEmpty()
        // 两个方向都要：失败行只给包名时要查 id，只给 id 时要查包名（保护判定基于包名）。
        val byName = entries.associate { it.name to it.id }
        val byId = entries.associate { it.id to it.name }

        var unmapped = 0
        var protected = 0
        val records = hits.mapNotNull { hit ->
            val id = hit.id ?: lookupEntryId(hit.name, byName)
            if (id.isNullOrBlank() || !PluginLayer.isValidId(id)) {
                unmapped++
                return@mapNotNull null
            }
            val packageName = hit.name ?: byId[id]
            if (HostInfrastructure.isProtected(id, packageName, installedPackages)) {
                // 官方/合成条目，或包名判不出来：不写层。误关官方条目会让整机起不来。
                protected++
                return@mapNotNull null
            }
            IsolationRecord(
                id = id,
                name = packageName,
                reason = hit.reason.ifBlank { hit.kind.name },
                isolatedAtMs = System.currentTimeMillis(),
            )
        }
        if (unmapped > 0) onEvent("有 $unmapped 个出错项没能对应到层里的条目，未自动隔离")
        if (protected > 0) onEvent("有 $protected 个出错项属于宿主自带或无法判定，未自动隔离")
        return ResolvedTargets(records.distinctBy { it.id }, unmapped + protected)
    }

    /** 归因到条目 id 的映射结果。 */
    private data class ResolvedTargets(
        val records: List<IsolationRecord>,
        /** 没能对应到条目、或判定为受保护而未写层的命中数（只为提示用户）。 */
        val unmapped: Int,
    )

    /** 包名 → 条目 id：按包名或其子路径前缀匹配。 */
    private fun lookupEntryId(name: String?, mapping: Map<String, String>): String? {
        if (name.isNullOrBlank()) return null
        mapping[name]?.let { return it }
        return mapping.entries
            .firstOrNull { (entryName, _) -> entryName.startsWith("$name/") }
            ?.value
    }

    /**
     * 把「隔离清单」与层文件对齐：清单里记着的条目必须真的被停用。
     *
     * 以**层**为准（DSH 只认它），清单只是凭据。不一致的两种来路都见过：
     * 别的写者把我们的行改掉，
     * 或者层文件被外部工具重写。任其存在，界面会一直显示"已跳过"，而坏插件每次启动都照常加载。
     *
     * @return 补写的条数（0 = 本来就一致）
     */
    private fun reconcileIsolation(): Int {
        val records = store.load().isolated
        if (records.isEmpty()) return 0
        val disabled = layer.disabledIds()
        val missing = records.filterNot { it.id in disabled }
        if (missing.isEmpty()) return 0
        val written = missing.filter { layer.setDisabled(it.id, true) }
        if (written.isNotEmpty()) {
            log(
                "guard reconcile: 清单里已隔离但层里没停用，已补写 " +
                    written.joinToString(", ") { it.id } + " → layer=[${layerSummary()}]",
            )
        }
        return written.size
    }

    /*
     * 封存：在 DSH 启动前把当前日志尾（8KB）存成「上次启动」的快照。
     *
     * 它按固定字节取尾，与"按启动分段保留"的新策略口径不同；而面板已不再显示「上次启动」栏，
     * 故连同调用点一并封存。恢复步骤见上方 readBootLog 处。
     *
     * private fun snapshotBootLog() {
     *     val text = tail(paths.dshProcessLog, LOG_TAIL_CHARS)
     *     if (text.isBlank()) return
     *     runCatching {
     *         val file = paths.previousBootLogFile
     *         file.parentFile?.mkdirs()
     *         file.writeText(text, Charsets.UTF_8)
     *     }
     * }
     */

    private fun tail(file: File, limit: Int): String {
        if (!file.isFile || file.length() == 0L) return ""
        return runCatching {
            val length = file.length()
            val skip = (length - limit).coerceAtLeast(0L)
            file.inputStream().use { stream ->
                stream.skip(skip)
                stream.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    /**
     * 等待 DSH 落到终态（就绪或已结束）。
     *
     * 两条终止条件缺一不可：
     *
     * 1. **状态落终态**——READY 是成功；ERROR/STOPPED 是失败。
     * 2. **日志出现新的收尾行**——本次尝试已经结束。app 的状态机在启动失败时可能
     *    一直停在 STARTING（它要阻塞满超时），只看状态会把一轮白等到超时：
     *    实测就是这样把一轮 20 秒拖成 77 秒，用户感觉"卡着不动"。
     */
    private suspend fun awaitSettled(timeoutMs: Long = READY_TIMEOUT_MS): Boolean {
        val trailersBefore = FailureAttribution.trailerCount(readCurrentLog())
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (sandbox.dshState.value) {
                DshState.READY -> return true
                DshState.ERROR, DshState.STOPPED -> return false
                else -> Unit
            }
            if (FailureAttribution.trailerCount(readCurrentLog()) > trailersBefore) {
                log("awaitSettled: 日志出现新的收尾行，本次尝试已结束")
                return false
            }
            delay(SETTLE_POLL_MS)
        }
        return sandbox.dshState.value == DshState.READY
    }

    private companion object {
        /**
         * logcat 标记：守护每一步都写它。
         *
         * 有这个才可能核对"守护到底做了什么"——之前我只有界面文案，出问题时只能靠
         * 猜（`adb logcat -s PluginGuard` 即可）。
         */
        const val GUARD_TAG = "PluginGuard"

        /** 日志只看尾部这么多字符：失败信息总在最后，全量读没有意义还占内存。 */
        /** 守卫内部读取当前日志时取尾部多少字节（与改动前一致，见 [readCurrentLog]）。 */
        const val LOG_TAIL_CHARS = 8_000

        /**
         * 面板读取启动日志的**读取上限**。
         *
         * 写入侧已把这份日志限制在「最近 10 段 × 单段 50KB」以内；这里是历史遗留超大文件的兜底。
         * （原 `LOG_TAIL_CHARS = 8000` 是"取尾部 8KB"，一个窗口里会混进多次启动，已废止。）
         */
        const val LOG_READ_LIMIT = 1024 * 1024

        /** 与沙箱管理器的就绪超时保持一致（DSH 冷启动含 node 启动，留足余量）。 */
        const val READY_TIMEOUT_MS = 150_000L

        /** 错误态稳定多久才认为"真的起不来"，避免与健康检查的重试抢跑。 */
        const val ERROR_SETTLE_MS = 6_000L

        /**
         * 启动过程中看日志的间隔。
         *
         * 5 秒：DSH 失败通常在启动后几秒就打完收尾行，这个间隔让"发现失败"的延迟
         * 只有几秒（此前是固定等 25 秒才开始看，白等的时间比干活的时间还长）。
         */
        const val FAILURE_PROBE_INTERVAL_MS = 5_000L

        /** 探针最多盯这么久；之后交给 app 自己的错误态（它还有重试与超时兜底）。 */
        const val FAILURE_PROBE_WINDOW_MS = 120_000L

        /** 等待就绪时的轮询间隔（读日志尾 + 看状态，都很轻）。 */
        const val SETTLE_POLL_MS = 2_000L

        /**
         * 尾部指纹取多少字符。
         *
         * 用来在"日志尾巴写满 8KB 后滑动"的情况下仍然能定位"这次尝试新写的内容"：
         * 200 字符足够唯一，也不怕被一次启动的输出挤出去（失败报告约 3KB）。
         */
        const val SUFFIX_PROBE_CHARS = 200

        /** 重启失败时的消息前缀：调用方据此区分"重启都没发出"与"重启了但没起来"。 */
        const val RESTART_FAILED_PREFIX = "无法重启 DSH："
    }
}
