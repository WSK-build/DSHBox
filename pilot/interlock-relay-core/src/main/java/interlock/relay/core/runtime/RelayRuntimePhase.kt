package interlock.relay.core.runtime

/**
 * agent 通道运行时的四个阶段。
 *
 * [STOPPED] 未启动；[STARTING] 装配在途（重活在 IO 协程上跑，此刻尚不可服务）；
 * [RUNNING] 装配全部完成、通道在消费；[DEGRADED] 装配中途失败、已按步清理，等待重试。
 *
 * 单独成为一个纯 JVM 文件：状态机与它的单测都不能沾 Android 类型，
 * 而消费它的容器、界面与宿主转发层共用同一份裁决。
 */
enum class RuntimePhase { STOPPED, STARTING, RUNNING, DEGRADED }

/**
 * 启动状态机：阶段迁移的唯一裁决点。
 *
 * 之所以要一台显式状态机，是因为一个布尔标记回答不了三件事：启动到一半算不算
 * 已启动、失败之后能不能重试、对外如何区分「正在起」与「没跑起来」。布尔在置位
 * 那一刻就无法回头，而这里的每次迁移都校验前置阶段，非法迁移被拒绝而不是被覆盖。
 *
 * 迁移矩阵：
 * - [beginStart]：STOPPED/DEGRADED → STARTING；RUNNING/STARTING 拒绝（重复启动）。
 * - [commitRunning]：STARTING → RUNNING；其余阶段为空操作（序列被叫停后迟到的提交）。
 * - [fail]：STARTING → DEGRADED 并记录原因；其余阶段拒绝。
 * - [beginStop]：STARTING/RUNNING → STOPPED；STOPPED/DEGRADED 拒绝（前者无事可停，
 *   后者的清理已由失败收尾做完，重试走 [beginStart] 而不是 stop）。
 *
 * 线程安全：迁移在内部锁内校验并生效；[onTransition] 也在该锁内回调，保证观察者
 * 按迁移顺序看到每个阶段 —— 因此回调必须轻量（至多一次易失写），且不得再进入
 * 本状态机（会在锁内重入死锁）。
 */
class RuntimeStateMachine(
    private val onTransition: ((RuntimePhase) -> Unit)? = null,
) {

    private val lock = Any()

    @Volatile
    private var current: RuntimePhase = RuntimePhase.STOPPED

    /**
     * 最近一次失败的原因。启动成功即清空；STARTING 期间保留上一次的原因供诊断
     * （界面在 STARTING 不展示原因，只展示「正在启动」）。
     */
    @Volatile
    private var failReason: String? = null

    /** 当前阶段。读不加锁：迁移只在锁内发生，易失读至多看到迁移前的一瞬。 */
    val phase: RuntimePhase get() = current

    /** [RuntimePhase.DEGRADED] 阶段失败原因的只读访问。 */
    val degradedReason: String? get() = failReason

    /** 请求启动。STOPPED 与 DEGRADED 可以进入 STARTING；已在跑或在途中返回 false。 */
    fun beginStart(): Boolean = moveTo(
        from = setOf(RuntimePhase.STOPPED, RuntimePhase.DEGRADED),
        to = RuntimePhase.STARTING,
    )

    /** 装配全部完成，提交运行。只在 STARTING 下生效；迟到的提交是空操作。 */
    fun commitRunning() {
        moveTo(from = setOf(RuntimePhase.STARTING), to = RuntimePhase.RUNNING)
    }

    /**
     * 装配在 [reason] 所指处失败。只在 STARTING 下生效，返回是否落入 DEGRADED。
     *
     * 先记原因再改阶段：观察到 DEGRADED 的那一方紧接着读原因时必须已经读得到。
     */
    fun fail(reason: String): Boolean {
        synchronized(lock) {
            if (current != RuntimePhase.STARTING) return false
            failReason = reason
            current = RuntimePhase.DEGRADED
            onTransition?.invoke(current)
        }
        return true
    }

    /** 请求停止。STARTING 与 RUNNING 可以回到 STOPPED；其余阶段无事可停。 */
    fun beginStop(): Boolean = moveTo(
        from = setOf(RuntimePhase.STARTING, RuntimePhase.RUNNING),
        to = RuntimePhase.STOPPED,
    )

    private fun moveTo(from: Set<RuntimePhase>, to: RuntimePhase): Boolean {
        synchronized(lock) {
            if (current !in from) return false
            // 跑起来就清掉上一场失败的账：RUNNING 不该还背着旧原因。
            if (to == RuntimePhase.RUNNING) failReason = null
            current = to
            onTransition?.invoke(to)
        }
        return true
    }
}
