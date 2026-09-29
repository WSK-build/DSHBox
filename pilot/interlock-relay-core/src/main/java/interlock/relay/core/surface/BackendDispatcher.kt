package interlock.relay.core.surface

import interlock.relay.core.protocol.BackendId
import interlock.relay.core.protocol.CapabilityCategory
import interlock.relay.core.protocol.CapabilityDescriptor
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.SurfaceKind
import interlock.relay.core.exec.BackendCall
import interlock.relay.core.exec.BackendResult
import interlock.relay.core.exec.RelayBackend
import interlock.relay.core.log.LogEvent
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.log.RunLog
import interlock.relay.core.runtime.monotonicNow

/**
 * 按注册表声明的后端偏好顺序选择后端。后端只执行，不改变执行模式；
 * 模式在调用前已由 [SurfacePolicy] 裁决完毕。
 */
class BackendDispatcher(private val backends: Map<BackendId, RelayBackend>) {

    fun pick(descriptor: CapabilityDescriptor, surface: SurfaceKind): RelayBackend? =
        descriptor.backends.firstNotNullOfOrNull { id ->
            backends[id]?.takeIf { it.available() && it.supports(descriptor.id, surface) }
        }

    /**
     * 与 [pick] 同一条选择规则，唯一区别是：轮到某个后端回答时，先让它恢复自己那条连接。
     *
     * 只在真的是一次调用时走（状态上报走 [pick]），且只恢复被选中的那一家 ——
     * 对整条偏好列表统一 reconnect 会让一条本来由 direct 执行的 `app.launch`
     * 也触发一次 Shizuku 的解绑重绑，而那是有副作用的动作。
     *
     * 顺带把选中的 [BackendId] 一起返回：调用日志要写「这次落到哪个后端」，
     * 而后端实现本身不携带这个身份（[RelayBackend] 上没有它），从注册表反查等于
     * 又开一个真值来源 —— 身份本来就是这次遍历里选中的那一个。
     */
    private fun pickAndHeal(descriptor: CapabilityDescriptor, surface: SurfaceKind): Pair<BackendId, RelayBackend>? =
        descriptor.backends.firstNotNullOfOrNull { id ->
            val backend = backends[id] ?: return@firstNotNullOfOrNull null
            if (!backend.available()) backend.reconnect()
            backend.takeIf { it.available() && it.supports(descriptor.id, surface) }?.let { id to it }
        }

    /**
     * 「这条能力在这个模式下到底有没有实现」——只看 `supports()`，不看 `available()`。
     *
     * 模式裁决要的是实现事实：后端此刻连不上、无障碍服务没开、应用不在前台，都属于
     * 「现在跑不了」，由闸门与选择后端那一步如实报错；把它们混进裁决就会让一次瞬时
     * 状态改变能力的路由结论，还会把 `E_SURFACE_UNAVAILABLE`（要用户去处理）用在
     * 一个用户什么都做不了的场合。
     */
    fun canServe(descriptor: CapabilityDescriptor, surface: SurfaceKind): Boolean =
        descriptor.backends.any { id -> backends[id]?.supports(descriptor.id, surface) == true }

    /**
     * 这条能力在它的任一允许模式下有没有实现。不看后端此刻可不可达，也不看系统权限。
     *
     * 判据只留这一处：能力清单、面板统计与调用主链路都要问同一句「值不值得打扰用户」。
     * 主链路如果先弹框再回答「没实现」，用户就是为一次注定不执行的调用做了决定。
     */
    fun isImplemented(descriptor: CapabilityDescriptor): Boolean =
        descriptor.surfaces.ifEmpty { setOf(SurfaceKind.FOREGROUND) }
            .any { canServe(descriptor, it) }

    suspend fun dispatch(
        descriptor: CapabilityDescriptor,
        decision: SurfaceDecision,
        call: BackendCall,
    ): Dispatched {
        val cap = descriptor.id.wire
        val startedAt = monotonicNow()
        val picked = pickAndHeal(descriptor, decision.surface)
        if (picked == null) {
            // 「没有实现」与「有实现但此刻不可达」是两件事：无障碍没开、Shizuku 用户服务
            // 掉线、剪贴板不在前台，若一律回 E_NOT_IMPLEMENTED，就被说成「这条能力没写」，
            // 而它带的退出码 1（用法错）会反过来让助手去改脚本。
            val implementedSomewhere = canServe(descriptor, decision.surface) ||
                descriptor.surfaces.any { canServe(descriptor, it) }
            val refused = if (implementedSomewhere) {
                // reason 里再填一次能力名等于没说：回包本来就带着 `capability`。这一格要回答
                // "为什么挑不到"与"接下来看哪里"，所以列出哪几个执行面上确实写了后端，
                // 并指回能力清单里那两个状态字段。
                val servedOn = (SurfaceKind.entries.filter { canServe(descriptor, it) }
                    .map { it.wire } + descriptor.surfaces.map { it.wire })
                    .distinct().sorted().joinToString(", ")
                BackendResult.Failed(
                    RelayError.BACKEND_UNAVAILABLE,
                    "no backend reachable for the ${decision.surface.wire} surface; " +
                        "a backend is written for: [$servedOn] — read backend.shizuku and " +
                        "the accessibility state in the capability list",
                )
            } else {
                BackendResult.Failed(RelayError.CAPABILITY_NOT_IMPLEMENTED, descriptor.id.wire)
            }
            // 挑不中后端时没有一家可以被指认，`backend` 记 NONE：这与「落在某一家但失败了」
            // 是两种不同的排障方向，不能让两者在日志里长得一样。
            logCall(
                cap = cap,
                backend = BACKEND_NONE,
                result = if (implementedSomewhere) RESULT_UNAVAILABLE else RESULT_NOT_IMPLEMENTED,
                startedAt = startedAt,
            )
            return Dispatched(refused, decision)
        }
        val wire = picked.first.wire()
        val backend = picked.second
        val outcome = try {
            backend.execute(call.copy(surface = decision.surface))
        } catch (t: Throwable) {
            // 后端抛异常时派发处不会得到结果，但派发恰恰是唯一知道「它本该落到哪一家」的地方：
            // 这一条先记 failed 再原样抛出，让上层照旧按异常处理，也不让这次调用在日志里消失。
            logCall(cap, wire, RESULT_FAILED, startedAt)
            throw t
        }
        val result = if (outcome is BackendResult.Ok) RESULT_OK else RESULT_FAILED
        logCall(cap, wire, result, startedAt)
        return Dispatched(outcome, decision)
    }

    /**
     * BACKEND_CALL 的字段名，与 [LogEvent.BACKEND_CALL] 的占位符逐个相等。
     * 留成具名字段（而不是就地写字符串键）是为了让「阶段事件字典契约」那组测试能断言
     * 模板与调用点不会各自漂移 —— 少填一个键，落盘就会多出一段 `unresolved=`。
     */
    companion object {
        internal val CALL_FIELDS = setOf("cap", "backend", "result", "ms")

        /** 没有后端被选中时的 `backend` 取值。 */
        internal const val BACKEND_NONE = "none"

        internal const val RESULT_OK = "ok"
        internal const val RESULT_FAILED = "failed"
        internal const val RESULT_UNAVAILABLE = "unavailable"
        internal const val RESULT_NOT_IMPLEMENTED = "not_implemented"

        /** 枚举名只用于代码内部，日志用与 [BackendId] 语义一致的短字面量。 */
        private fun BackendId.wire(): String = when (this) {
            BackendId.DIRECT -> "direct"
            BackendId.A11Y -> "a11y"
            BackendId.SHIZUKU -> "shizuku"
        }
    }

    private fun logCall(cap: String, backend: String, result: String, startedAt: Long) {
        // 日志实例由装配根建出，本类在它之前被构造，故在派发这一刻取，不在构造时。
        RunLog.currentOrNull()?.info(
            LogSubsystem.BACKEND, LogEvent.BACKEND_CALL,
            "cap" to cap,
            "backend" to backend,
            "result" to result,
            "ms" to (monotonicNow() - startedAt).coerceAtLeast(0L).toString(),
        )
    }

    /** 结果与最终生效的模式一并返回，供响应与授权记录使用。 */
    data class Dispatched(val result: BackendResult, val decision: SurfaceDecision)
}
