package interlock.relay.core.surface

import android.os.Build
import interlock.relay.core.protocol.CapabilityDescriptor
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.SurfaceKind

enum class SurfacePreference { FOREGROUND, BACKGROUND_PREFERRED }

/**
 * 裁决结果。[degradedFrom] 与 [reason] 一并回传给助手，降级不静默。
 *
 * [reason] 存的是 [interlock.relay.core.protocol.RelayError.code]，不复造一套降级原因词汇：
 * 界面按同一张错误码表取文案，助手按同一张表取退出码。
 *
 * [requiresMode] 非空表示这条能力只声明了一个执行面，而用户的偏好链走不到它：
 * [surface] 此时就是那个唯一的面，调用在进闸门前就会被打回（调用方据此直接回
 * 「需要该模式」），不再伪造一个前台落点让执行期去换一条泛化的错误。
 */
data class SurfaceDecision(
    val surface: SurfaceKind,
    val degradedFrom: SurfaceKind? = null,
    val reason: String? = null,
    val requiresMode: SurfaceKind? = null,
)

/**
 * 执行模式裁决。用户只有两个意图选项，**没有「仅后台」**：
 * 后台条件缺失时必须能回到前台，否则任务无路可走。
 *
 * 优先级：后台 → 前台 → 仅观测。仅观测不给注入能力，
 * 因此只在能力本身不需要输入操作时才会落到该模式。
 *
 * [backendCanServe] 是「这个模式下有没有后端真能跑这条能力」。少了这一问，
 * 裁决只看系统条件（版本、shell 在不在）：Android 13+ 且 Shizuku 在跑、用户又选了
 * 「优先后台」时，可信屏会被选中，而 `ui.*` 与 `screen.capture` 在后端侧对该模式
 * 一律 `supports() = false` —— 五项能力当场变成"未实现"，且没有任何降级提示。
 * 界面承诺的正是"后台跑不了就退回前台"，所以这一问必须问在裁决里而不是留给调用方。
 */
class SurfacePolicy(
    private val preference: () -> SurfacePreference,
    private val shellAvailable: () -> Boolean,
    private val backendCanServe: (CapabilityDescriptor, SurfaceKind) -> Boolean = { _, _ -> true },
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {

    fun decide(descriptor: CapabilityDescriptor): SurfaceDecision {
        val allowed = descriptor.surfaces
        // 与执行模式无关的能力（数据读取等）不涉及降级语义。
        if (allowed.isEmpty()) return SurfaceDecision(SurfaceKind.FOREGROUND)

        val preferred = when (preference()) {
            SurfacePreference.FOREGROUND -> listOf(SurfaceKind.FOREGROUND, SurfaceKind.OBSERVE_ONLY)
            SurfacePreference.BACKGROUND_PREFERRED -> listOf(
                SurfaceKind.BACKEND_TRUSTED,
                SurfaceKind.FOREGROUND,
                SurfaceKind.OBSERVE_ONLY,
            )
        }

        var blockedFrom: SurfaceKind? = null
        var reason: String? = null
        for (candidate in preferred) {
            if (candidate !in allowed) {
                // 用户要后台而这条能力没有声明任何后台面，这本身就是降级：只声明 FOREGROUND
                // 的能力（节点级那一批全在内）在「优先后台」下会刚刚在用户真屏上动了界面，
                // 却回 `degraded:false`。缺这个字段比回一个 false 更诚实。
                if (blockedFrom == null && candidate == SurfaceKind.BACKEND_TRUSTED) {
                    blockedFrom = candidate
                    reason = RelayError.SURFACE_UNAVAILABLE.code
                }
                continue
            }
            val rejection = rejectReason(candidate)
                ?: if (backendCanServe(descriptor, candidate)) null else RelayError.SURFACE_UNAVAILABLE.code
            if (rejection == null) {
                // 「降级」说的是一件具体的事：本应后台跑的活落到了用户眼前这块屏上。
                // 所以只有落点是 FOREGROUND 才算降级 —— 仅观测那块面不注入输入也不碰用户界面，
                // 把「它没声明后台面」说成「动了别处」，会让一条按定义无害的能力每次成功
                // 都被判成一次故障：调用方正是拿 degraded 判断后台计划有没有落空。
                val fellBack = candidate == SurfaceKind.FOREGROUND
                return SurfaceDecision(candidate, blockedFrom.takeIf { fellBack }, reason.takeIf { fellBack })
            }
            if (blockedFrom == null) {
                blockedFrom = candidate
                reason = rejection
            }
        }
        // 允许集合内没有任何可用模式时先分一类：**单一执行面**的能力（允许集非空且
        // 不含前台）在任何偏好链下都到不了自己唯一的那个面。落点不再伪造 FOREGROUND——
        // 后端对这类模式只认它声明的那个面，伪造的前台落点只会先过门禁、再在执行期
        // 换成一条泛化的「够不着」，而「切偏好/把那块屏建起来」才是真正能做的下一步。
        // 显式回「需要哪个模式」，调用方与界面按同一张错误码表给出准确的处置。
        if (allowed.isNotEmpty() && SurfaceKind.FOREGROUND !in allowed) {
            val required = allowed.first()
            return SurfaceDecision(
                required,
                requiresMode = required,
                reason = RelayError.SURFACE_MODE_REQUIRED.code,
            )
        }
        // 其余（允许集含前台）如实回绝，不擅自挑一个能跑但语义不同的模式。
        // 降级来源不能等于落点本身：「从 foreground 降级到 foreground」是自相矛盾的结论，
        // 直接取 preferred.first() 在纯前台偏好下就会产出它。
        val fallbackFrom = (blockedFrom ?: preferred.first())
            .takeIf { it != SurfaceKind.FOREGROUND }
        return SurfaceDecision(
            SurfaceKind.FOREGROUND,
            fallbackFrom,
            (reason ?: RelayError.SURFACE_UNAVAILABLE.code).takeIf { fallbackFrom != null },
        )
    }

    private fun rejectReason(surface: SurfaceKind): String? = when (surface) {
        SurfaceKind.BACKEND_TRUSTED -> when {
            sdkInt < MIN_BACKEND_SDK -> RelayError.SURFACE_API_LEVEL.code
            !shellAvailable() -> RelayError.SURFACE_NO_SHELL.code
            else -> null
        }

        else -> null
    }

    companion object {
        /** 可信虚拟屏依赖 shell 身份持有 ADD_TRUSTED_DISPLAY，该授予自 Android 13 起存在。 */
        const val MIN_BACKEND_SDK = Build.VERSION_CODES.TIRAMISU
    }
}
