package interlock.relay.core.surface

/**
 * 会话内的目标落点登记簿。
 *
 * 每个目标包名一条记录，另记"最近一次成功启动的目标"给不带目标参数的界面调用沿用。
 * 记录只在当前会话内生效：装配根随通道启停创建/丢弃本表，会话重启即清空；
 * 不写回用户偏好，也不是全局有效执行面。
 *
 * 失效（读时即删）：过 TTL、用户改了执行偏好、目标被停止、后台屏被动过（建/收）。
 * TTL 封顶记录的寿命，一次降级不会把目标永久钉在前台。
 *
 * 结果未知的启动以参数摘要记一枚标记：摘要相同的重发在标记有效期内被挡下，
 * 让调用方先核实再动 —— 这是"未知结果不自动重放"的幂等缝，不是全局锁。
 */
class TargetLandingTable(
    private val now: () -> Long,
    private val preference: () -> SurfacePreference,
    private val ttlMs: Long = DEFAULT_LANDING_TTL_MS,
    private val unknownTtlMs: Long = DEFAULT_UNKNOWN_LAUNCH_TTL_MS,
) {

    private val entries = HashMap<String, TargetLanding>()
    private var lastKey: String? = null

    /** "结果未知"的启动标记：参数摘要 → (目标包名，过期时刻)。 */
    private val unknownLaunches = HashMap<String, Pair<String, Long>>()

    /** 记入一条有证据的落点，并把它标为最近目标。 */
    @Synchronized
    fun record(landing: TargetLanding) {
        entries[landing.targetPackage] = landing
        lastKey = landing.targetPackage
    }

    /** 目标此刻的落点；过期或偏好已变的记录当场摘除。 */
    @Synchronized
    fun get(target: String): TargetLanding? {
        val landing = entries[target] ?: return null
        if (!landing.usable(now(), preference())) {
            entries.remove(target)
            if (lastKey == target) lastKey = null
            return null
        }
        return landing
    }

    /** 最近一次成功启动的目标（记录仍有效时）。 */
    @Synchronized
    fun lastTarget(): String? {
        val key = lastKey ?: return null
        return if (get(key) != null) key else null
    }

    /** 目标被停止（或等价事件）时摘除它的记录。 */
    @Synchronized
    fun invalidate(target: String) {
        entries.remove(target)
        if (lastKey == target) lastKey = null
    }

    /** 后台屏被动过（建/收）或会话收尾：全部记录作废，下一次调用重新裁决。 */
    @Synchronized
    fun clear() {
        entries.clear()
        lastKey = null
    }

    /** 一次启动的结果未知：按参数摘要记标记（带目标包名），让相同的重发先去核实。 */
    @Synchronized
    fun rememberUnknownLaunch(argsDigest: String, targetPackage: String) {
        unknownLaunches[argsDigest] = targetPackage to (now() + unknownTtlMs)
    }

    /** 摘要相同的启动是否还压着一枚未过期的"结果未知"标记。 */
    @Synchronized
    fun hasUnknownLaunch(argsDigest: String): Boolean {
        val marker = unknownLaunches[argsDigest] ?: return false
        if (now() >= marker.second) {
            unknownLaunches.remove(argsDigest)
            return false
        }
        return true
    }

    /**
     * 同一目标被成功核实（pkg.query / ui.snapshot 等读回场景，或一次核验过的启动落账）
     * 之后摘掉它的未知标记：核实动作本身就是标记要等的那一步，摘掉后同参重发照常走。
     */
    @Synchronized
    fun clearUnknownLaunch(targetPackage: String) {
        unknownLaunches.values.removeAll { it.first == targetPackage }
    }

    /** 一次全场景读回（ui.snapshot / pkg.query）等于把所有未知标记一并核实掉。 */
    @Synchronized
    fun clearAllUnknownLaunches() {
        unknownLaunches.clear()
    }

    companion object {
        /** 落点记录的默认寿命：足够覆盖一轮任务，短到不会把一次降级钉成常态。 */
        const val DEFAULT_LANDING_TTL_MS = 5L * 60L * 1000L

        /** "结果未知"标记的寿命：只挡紧随其后的盲目重发，核实动作本身不受限。 */
        const val DEFAULT_UNKNOWN_LAUNCH_TTL_MS = 60L * 1000L
    }
}
