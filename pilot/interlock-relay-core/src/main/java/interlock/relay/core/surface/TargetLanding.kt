package interlock.relay.core.surface

import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.SurfaceKind
import org.json.JSONObject

/**
 * 一条目标落点记录。
 *
 * 记的是"这个目标此刻在哪个执行面上、凭什么证据"：只在当前会话内生效，不写回用户
 * 偏好，也不是全局有效执行面 —— 同一时刻两个目标可以各在各的面上。
 *
 * [verified] 为真的记录必须带实际证据（启动后的落地核验、抓到用户真屏、前台核验），
 * 不做乐观写入；[evidence] 区分证据种类，回包与排障按它读。
 *
 * [reason] 沿用错误码表（同 [SurfaceDecision.reason] 的口径）：记录是降级产生时，
 * 它就是那次降级的错误码；后台原生落点为 null。
 *
 * [preferenceAtCreation] 参与有效性判定：用户改了执行偏好，旧记录按失效处理，
 * 下一次调用重新裁决 —— 偏好是用户的当下决定，记录不得替它延续。
 */
data class TargetLanding(
    val targetPackage: String,
    val displayId: Int,
    val surface: SurfaceKind,
    val reason: String?,
    val verified: Boolean,
    val evidence: String,
    val evidenceAtMs: Long,
    val expiresAtMs: Long,
    val preferenceAtCreation: SurfacePreference,
) {
    /** 有效 = 没过 TTL 且创建时的偏好仍是当前偏好。 */
    fun usable(nowMs: Long, preference: SurfacePreference): Boolean =
        nowMs < expiresAtMs && preference == preferenceAtCreation
}

/** 证据种类。 */
object LandingEvidence {
    const val LANDED_ON_TARGET = "landed_on_target"
    const val GRABBED_USER_SCREEN = "grabbed_user_screen"
    const val FOREGROUND_VERIFIED = "foreground_verified"
}

/**
 * 一次后台启动失败的性质。前台兜底只给确定性失败一次；暂时性失败保持后台意图；
 * 结果未知不重放也不兜底。
 */
internal enum class LaunchFailureClass {
    /** 启动被确认落在了用户真屏上：目标已在眼前，不再重发前台启动。 */
    LANDED_ON_USER_SCREEN,

    /** 确定性失败：允许一次目标级前台兜底。 */
    DETERMINISTIC,

    /** 暂时性失败：返回可复查错误，保持后台意图。 */
    TRANSIENT,

    /** 结果未知：不自动重放，也不兜底，并以幂等标记挡住同参重发。 */
    UNKNOWN,

    /**
     * 启动尚未发生就失败了（参数错、能力未实现、档位拒绝、限流等）：
     * 原样返回，修复后即可重试 —— 不兜底，也不记幂等标记挡重发。
     */
    PRE_LAUNCH,
}

/**
 * 后台启动失败的性质判定（纯函数）。
 *
 * 分类的依据是后端失败出口自己标注的结构化事实与错误码，不猜 reason 文本：
 * - `grabbedUserScreen` 为真是后端亲自看到的"App 起在了用户真屏"；
 * - [RelayError.LAUNCH_NOT_LANDED] 的其余场景（目标屏停着别的应用、中介选择框）都是
 * "后台面确认给不了这个落点"的确定性结论；
 * - 建屏被 ROM 拒绝、API 不够、无安全尺寸、单一执行面卡死，都是重试也改不了的结论；
 * - Shizuku 断连、屏被回收、冷启动还没起完是暂时性的，退避后重试才有意义；
 * - 观察失败（整屏顶层窗口都读不到，后端以 `landingCheck:"failed"` 标注）说明结局
 * 未知 —— 启动可能已经发生，重发可能造成双开，兜底会把它钉到前台。
 */
internal fun classifyLaunchFailure(
    error: RelayError,
    data: JSONObject,
    dispatched: Boolean,
): LaunchFailureClass = when {
    error == RelayError.LAUNCH_NOT_LANDED && data.optBoolean("grabbedUserScreen") ->
        LaunchFailureClass.LANDED_ON_USER_SCREEN

    error == RelayError.LAUNCH_NOT_LANDED -> LaunchFailureClass.DETERMINISTIC
    error == RelayError.SURFACE_API_LEVEL ||
    error == RelayError.SURFACE_TRUSTED_DENIED ||
    error == RelayError.SURFACE_MODE_REQUIRED ||
    error == RelayError.SURFACE_UNAVAILABLE ->
        LaunchFailureClass.DETERMINISTIC

    error == RelayError.BACKEND_SHIZUKU_DEAD -> LaunchFailureClass.TRANSIENT
    error == RelayError.BACKEND_UNAVAILABLE ->
        if (data.optString("landingCheck") == "failed") LaunchFailureClass.UNKNOWN
        else LaunchFailureClass.TRANSIENT

    // 启动入口自己的两条失败码（am start 被服务侧权限拒绝 / 未落地的命令失败）：
    // 启动动作发生在**本请求之内**，重发是修复后的新尝试而不是未知结果的重放
    // —— 原样交回，不写幂等标记挡同参重发，也不兜底。先于此处显式分派，
    // 免得它们随 run() 的"已收到回执"事实被推成 UNKNOWN、把可修复重发挡 60 秒。
    error == RelayError.COMMAND_FAILED || error == RelayError.COMMAND_NOT_PERMITTED ->
        LaunchFailureClass.PRE_LAUNCH

    // 启动还没发生就失败（参数错、未实现、档位拒绝、限流、内部错误）：
    // 原样交回，修复后即可重试，不该被幂等标记挡住，也没有前台可兜底。
    !dispatched -> LaunchFailureClass.PRE_LAUNCH

    // 已递交之后才冒出来的未编类错误：结局未知，按未知处理。
    else -> LaunchFailureClass.UNKNOWN
}

/**
 * 后续界面/取帧调用按目标沿用落点的路由结论（纯函数）。
 *
 * [record] 为 null（没有记录、已过期、偏好已变）交回 [TargetRoute.None]，由调用方
 * 退回执行偏好裁决；记录是后台面而那块屏已经不在时，交回 [TargetRoute.Reject] 的
 * 结构化错误 —— 静默改走前台会把"后台优先"变成"悄悄上真屏"，宁可让调用方复查。
 */
internal sealed interface TargetRoute {
    /** 沿用记录里的执行面。[target] 是记录指向的目标，供派发前重校验。 */
    data class Follow(val decision: SurfaceDecision, val target: String? = null) : TargetRoute

    /** 记录已不可信：以结构化错误收场，不做静默改道。 */
    data class Reject(val error: RelayError, val reason: String) : TargetRoute

    /** 没有可沿用的落点：退回执行偏好裁决。 */
    object None : TargetRoute
}

internal fun routeForTarget(
    descriptorSurfaces: Set<SurfaceKind>,
    target: String,
    record: TargetLanding?,
    trustedDisplayAlive: Boolean,
    currentTop: String?,
    explicitTarget: Boolean,
    nowMs: Long,
    preference: SurfacePreference,
): TargetRoute {
    if (SurfaceKind.BACKEND_TRUSTED !in descriptorSurfaces) return TargetRoute.None
    if (record == null || !record.usable(nowMs, preference)) return TargetRoute.None
    // 沿用前校验目标仍是记录那块面上的当前顶层窗口：用户手动切走后旧落点不作数。
    // 目标已不在记录的位置上时分两路：调用**点名了目标**（args 的 package / selector.package）
    // → 结构化错误，把"去哪核实"说清，绝不代为改道；目标来自**最近启动的猜测**
    // （调用没点名）→ 退回执行偏好裁决，等于这次调用没有可依据的目标信息。
    // 当前顶层读不到（观察失败）时两路都不沿用 —— 宁可重裁，不猜。
    fun stale(): TargetRoute =
        if (explicitTarget) {
            TargetRoute.Reject(
                RelayError.SURFACE_UNAVAILABLE,
                "the recorded landing for " + target + " no longer matches what is on top of its " +
                    "surface: re-launch the target or re-check where it is before acting on it",
            )
        } else {
            TargetRoute.None
        }

    return when (record.surface) {
        SurfaceKind.BACKEND_TRUSTED -> when {
            !trustedDisplayAlive -> TargetRoute.Reject(
                RelayError.SURFACE_UNAVAILABLE,
                "the trusted display carrying the landing for this target is gone: recreate it " +
                    "with surface.virtual {\"action\":\"create\"} or launch the target again; " +
                    "the recorded landing was not reused",
            )

            currentTop == target -> TargetRoute.Follow(SurfaceDecision(SurfaceKind.BACKEND_TRUSTED), target)
            else -> stale()
        }

        else -> when {
            currentTop == target -> TargetRoute.Follow(
                SurfaceDecision(
                    SurfaceKind.FOREGROUND,
                    degradedFrom = SurfaceKind.BACKEND_TRUSTED.takeIf { record.reason != null },
                    reason = record.reason,
                ),
                target,
            )

            else -> stale()
        }
    }
}

/**
 * 界面调用的目标落定（纯函数）：参数里点名的包 → 选择器里的包 →
 * 本会话最近一次成功启动的目标。三处都没有目标时交回 null，由调用方退回执行偏好裁决。
 */
internal fun resolveLandingTarget(
    explicitPackage: String?,
    selectorPackage: String?,
    lastTarget: String?,
): String? = explicitPackage ?: selectorPackage ?: lastTarget

/** 一次启动结果落定后的处置。 */
internal data class LaunchSettling(
    val record: TargetLanding?,
    val fallbackToForeground: Boolean,
    val rewriteToOk: Boolean,
    val failureClass: LaunchFailureClass?,
    /** Ok 但未核验时的合成失败：命令被接受、落点未确认，按结果未知回可复查错误。 */
    val syntheticError: RelayError? = null,
)

/**
 * 启动结果落定（纯函数）：记什么记录、要不要前台兜底、要不要按"已在用户真屏"如实改写。
 *
 * [surface] 与 [degradedFrom] 是这次派发实际用的裁决；Ok 的 [data] 必须带后端给的
 * 落地核验字段（verified/display/landedOn），没有核验就不记。
 */
internal fun settleLaunchResult(
    surface: SurfaceKind,
    degradedFrom: SurfaceKind?,
    error: RelayError?,
    data: JSONObject,
    targetPackage: String,
    nowMs: Long,
    ttlMs: Long,
    preference: SurfacePreference,
    dispatched: Boolean = false,
): LaunchSettling {
    if (error == null) {
        // 没有核验证据就不是成功：`verified:false` 只说明命令被接下、落点还没看到。
        // 成因分两路：回包带着 observed（中介选择框、分身容器停在最前面 —— 成因明确、
        // 用户答完框就能再发）按确定性错误回，不写幂等标记；回包什么都不带
        // （冷启动没起完、无障碍没连上，观察缺席）才是真正的结果未知，
        // 按未知回可复查错误并记幂等标记。两路都不记落点、不兜底、不自动重放。
        if (!data.optBoolean("verified")) {
            val causeKnown = data.optString("observed").isNotEmpty()
            return LaunchSettling(
                null,
                fallbackToForeground = false,
                rewriteToOk = false,
                failureClass = if (causeKnown) LaunchFailureClass.DETERMINISTIC else LaunchFailureClass.UNKNOWN,
                syntheticError = RelayError.LAUNCH_NOT_LANDED,
            )
        }
        val landing = if (surface == SurfaceKind.BACKEND_TRUSTED) {
            TargetLanding(
                targetPackage = targetPackage,
                displayId = data.optInt("display", -1),
                surface = SurfaceKind.BACKEND_TRUSTED,
                reason = null,
                verified = true,
                evidence = LandingEvidence.LANDED_ON_TARGET,
                evidenceAtMs = nowMs,
                expiresAtMs = nowMs + ttlMs,
                preferenceAtCreation = preference,
            )
        } else {
            TargetLanding(
                targetPackage = targetPackage,
                displayId = 0,
                surface = SurfaceKind.FOREGROUND,
                reason = degradedFrom?.let { RelayError.SURFACE_UNAVAILABLE.code },
                verified = true,
                evidence = LandingEvidence.FOREGROUND_VERIFIED,
                evidenceAtMs = nowMs,
                expiresAtMs = nowMs + ttlMs,
                preferenceAtCreation = preference,
            )
        }
        return LaunchSettling(landing, fallbackToForeground = false, rewriteToOk = false, failureClass = null)
    }

    return when (val clazz = classifyLaunchFailure(error, data, dispatched)) {
        LaunchFailureClass.LANDED_ON_USER_SCREEN -> LaunchSettling(
            TargetLanding(
                targetPackage = targetPackage,
                displayId = 0,
                surface = SurfaceKind.FOREGROUND,
                reason = error.code,
                verified = true,
                evidence = LandingEvidence.GRABBED_USER_SCREEN,
                evidenceAtMs = nowMs,
                expiresAtMs = nowMs + ttlMs,
                preferenceAtCreation = preference,
            ),
            fallbackToForeground = false,
            rewriteToOk = true,
            failureClass = clazz,
        )

        LaunchFailureClass.DETERMINISTIC -> LaunchSettling(
            null,
            fallbackToForeground = true,
            rewriteToOk = false,
            failureClass = clazz,
        )

        else -> LaunchSettling(null, fallbackToForeground = false, rewriteToOk = false, failureClass = clazz)
    }
}
