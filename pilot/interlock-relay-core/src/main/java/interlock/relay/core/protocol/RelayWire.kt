package interlock.relay.core.protocol

import interlock.relay.core.R
import org.json.JSONObject

/** 通道协议版本。宿主与助手端入口程序均按此版本校验，不匹配即拒绝处理。 */
const val RELAY_PROTOCOL_VERSION = 1

/**
 * 「宿主拒绝」码的**线上取值**。它是协议面：沙盒侧回包、入口脚本与手册都认这个值，
 * 与 [RelayError.GATE_HOST_DENIED] 的枚举名对齐；退出码取 [RelayExitCode.DENIED]，
 * retryable 语义由此定义。
 */
private const val HOST_DENIED_CODE = "E_GATE_HOST_DENIED"

/**
 * 控制通道协议版本。控制请求（提交/查状态/取消/探活）与能力请求分目录、分版本：
 * 过渡期两类信封共存，各自按自己的常量校验，互不借用——v1 的读取方忽略不认识的
 * 附加键，因此两版升级互不阻塞。
 */
const val RELAY_PROTOCOL_VERSION_V2 = 2

/**
 * 请求信封。由助手侧写入请求目录，宿主原子改名为处理中后消费。
 * 通道不承担鉴权：结构里没有任何凭证，调用者身份只到「本沙盒」为止。
 *
 * 本类只承载数据，不做解析。形状校验唯一发生在通道解码处，
 * 不允许出现第二份规则不一致的解析器。
 *
 * 信封里没有执行模式：前台还是后台由用户在助手界面选定，再经表面层裁决，
 * 助手不得指定表面——那会让「用户选的后台优先」变成可绕过的建议。
 */
data class RelayRequest(
    val id: String,
    val sentAtMs: Long,
    val capability: String,
    val args: JSONObject,
    val timeoutMs: Long,
) {
    companion object {
        /** 缺省有效期必须大于确认框等待时长，否则用户还在读确认框，请求已被回收。 */
        const val DEFAULT_TIMEOUT_MS = 90_000L
    }
}

/**
 * 响应信封。[error] 非空时 [ok] 必为 false。
 *
 * 执行模式用枚举而非字符串：字面量只在编码处出现一次，
 * 成功与失败分支不可能各写一套拼写。
 */
data class RelayResponse(
    val id: String,
    val ok: Boolean,
    val data: JSONObject,
    val artifacts: List<String>,
    val surface: SurfaceKind?,
    val degradedFrom: SurfaceKind?,
    val reason: String?,
    /**
     * 本次执行没有落在请求所期望的执行面上时，那条执行面故障码。
     *
     * 它与 [reason] 各位一处：`reason` 说这一趟本身怎么样，`degradeReason` 说为什么换了执行面。
     * 合成一个键会让成功回包里的降级说明与失败回包里的失败原因共用同一个名字，
     * 也会把后端那条具体诊断（此刻哪个包停在最前面）挤掉。
     */
    val degradeReason: String? = null,
    val error: RelayError?,
    val elapsedMs: Long,
    /**
     * 同参数重发的处置建议（见 [RetryPolicy]）。仅在能给出明确建议的调用上有值；
     * 空值表示这一趟没有可依据的判断，调用方不得自行默认为可重试。
     */
    val retryPolicy: RetryPolicy? = null,
    /**
     * 副作用是否已经落地的保守标记（见 [EffectState]）。
     *
     * `retryable` 只描述错误码本身，不描述动作是否已提交：断线前的安装与点击
     * 可能已经发生，按 `retryable:true` 原样重投会重复执行。判断能否重放只认
     * [retryPolicy] 与 [effectState]；`retryable` 不得当作非幂等动作的自动重放凭据。
     */
    val effectState: EffectState? = null,
    /**
     * 这次卡住的是哪一种许可。两种许可互不代替，混在一个错误文案里说，
     * 调用方就分不清该提示「助手规则不许」还是「系统采集弹框还没点」：
     *
     * - [CONSENT_KIND_RELAY_POLICY]：本模块自己的审批（档位与确认框）——
     *   「是否允许助手做这件事」由用户对本模块的授权回答；
     * - [CONSENT_KIND_ANDROID_PROJECTION]：Android 系统的屏幕采集同意
     *   （MediaProjection 弹框）——即使本模块放行，系统还要再问一次，
     *   助手通知上的「允许」点多少次都代替不了它。
     *
     * 只在能明确归类时给出；空表示这一趟不涉及许可问题或尚未细分。
     * 字面量是协议的一部分：写第二个来源就会有一处先拼错。
     */
    val consentKind: String? = null,
) {
    companion object {
        /** 本模块自己的审批（档位与确认框）涉及的许可。 */
        const val CONSENT_KIND_RELAY_POLICY = "relay_policy"

        /** Android 系统的屏幕采集同意（MediaProjection 弹框）涉及的许可。 */
        const val CONSENT_KIND_ANDROID_PROJECTION = "android_projection"
    }
}

/**
 * 通道错误码。错误表只留 code / retryable / exitCode：码与退出码是协议面，
 * 界面文案不绑在表里，而是经 [interlock.relay.core.spi.RelayText] 按
 * [defaultMessageRes] 的默认词条（或宿主自己的词条）取出。
 *
 * [exitCode] 与助手侧入口程序的退出码一一对应：「我发错了」与「用户拒绝」必须
 * 落在不同值上，否则脚本会把用法错误当拒绝无限重试。
 */
enum class RelayError(
    val code: String,
    val retryable: Boolean,
    val exitCode: Int,
) {
    GATE_HOST_DENIED(HOST_DENIED_CODE, false, RelayExitCode.DENIED),
    GATE_SYSTEM_MISSING("E_GATE_SYSTEM_MISSING", false, RelayExitCode.NEEDS_USER_ACTION),
    GATE_APPROVAL_TIMEOUT("E_GATE_APPROVAL_TIMEOUT", false, RelayExitCode.TIMEOUT),
    /**
     * 正在等用户当场确认（本模块确认窗或系统授权框）。
     *
     * 单独一档是因为它容易与两件事混：等满客户端 TTL 的 `E_TRANSPORT_TIMEOUT`
     * （读起来像宿主没应答）与选不到后端的 `E_NOT_IMPLEMENTED`（读起来像能力没实现）。
     * 三者的后续动作完全不同：这一档是「去手机上答复后重试」，
     * 因此宿主必须在客户端 TTL 到点之前先回这一条。
     */
    GATE_AWAITING_CONSENT("E_AWAITING_CONSENT", true, RelayExitCode.RETRY_LATER),
    /**
     * 确认队列已满：前面还有没答完的问题占着位置，这一问没有排上队。
     *
     * 与 [GATE_AWAITING_CONSENT] 分开：那条是「问题已经挂上、去手机上答复后重试」，
     * 这一条是「重试也接不上任何一张框，先退避一阵」——把两者合成一句，助手就会对着
     * 一张根本不存在的确认框等用户点头。
     */
    GATE_WAITING_TURN("E_GATE_WAITING_TURN", true, RelayExitCode.RETRY_LATER),
    /**
     * 请求在提交任何外部动作之前被取消：等用户答复期间收到了控制通道的取消。
     *
     * 与执行中收到取消必须分开：这里可证尚未提交任何动作、没有副作用，可以明确说
     * 「已取消、未执行」；也不可按原样重试——原请求已经终结，重发等于发起一件新的事。
     */
    GATE_CANCELLED("E_GATE_CANCELLED", false, RelayExitCode.RETRY_LATER),
    /**
     * 助手自己的窗口挡在路上（前台是宿主，无障碍按规则不操控宿主）。
     *
     * 这不是终态拒绝：用户切走之后同一条调用就能过。与 [GATE_HOST_DENIED]
     * 分开，脚本才不会把"请离开这一屏"当成"用户拒绝"而直接放弃整条任务链。
     */
    GATE_SUSPENDED_BY_HOST("E_TASK_SUSPENDED_BY_HOST", true, RelayExitCode.RETRY_LATER),
    /** 当前没有聚焦的可编辑控件：多半是上一次点击没落进输入框，而不是系统权限缺失。
     * 退出码取 USAGE（改脚本）且不可重试：原样重发答案不变，要改的是调用顺序。
     */
    NO_EDITABLE_TARGET("E_NO_EDITABLE_TARGET", false, RelayExitCode.USAGE),
    /** 选择器在当前控件树里没命中。处置是重读一次界面再定目标，不是换控件。 */
    NODE_NOT_FOUND("E_NODE_NOT_FOUND", false, RelayExitCode.USAGE),
    /**
     * 选择器命中多颗节点，宿主不替调用方挑。
     *
     * 与 [NODE_NOT_FOUND] 分开：那条要改的是"目标写错了"，这条要改的是"目标写得不够具体"
     * （加 index 或补一个能区分的字段）。都报成找不到，助手就会去重读界面，
     * 而重读多少次都不会让一个歧义选择器变具体。
     */
    NODE_AMBIGUOUS("E_NODE_AMBIGUOUS", false, RelayExitCode.USAGE),
    /** 节点命中了，但它不接受这个动作：不可点、不可滚、不是编辑框。换目标才有用，重发没有。 */
    NODE_NOT_ACTIONABLE("E_NODE_NOT_ACTIONABLE", false, RelayExitCode.USAGE),
    /** 动作已派发到节点，是应用侧没认。与上一条分开：这条值得重发一次。 */
    NODE_ACTION_REJECTED("E_ACTION_REJECTED", true, RelayExitCode.RETRY_LATER),
    /** ui.waitFor 到点条件仍不成立。与"通道没回执"的超时是两件事：这里是界面没变成那样。 */
    WAIT_TIMEOUT("E_WAIT_TIMEOUT", true, RelayExitCode.TIMEOUT),

    GATE_NO_FOREGROUND("E_GATE_NO_FOREGROUND", true, RelayExitCode.NEEDS_USER_ACTION),
    /**
     * 剪贴板要本应用持有焦点才读得到、写得起。与 [GATE_NO_FOREGROUND] 分开给：那条说的是
     * 「确认框无处呈现」，处置是开悬浮窗或等确认；这一条说的是剪贴板，处置是把助手切到
     * 前台。同一个码承载两种处置，向用户转述的动作就会错。
     */
    CLIPBOARD_NO_FOCUS("E_CLIPBOARD_NO_FOCUS", true, RelayExitCode.NEEDS_USER_ACTION),
    CAPABILITY_UNAVAILABLE_ON_DEVICE("E_CAPABILITY_UNAVAILABLE_ON_DEVICE", false, RelayExitCode.NEEDS_USER_ACTION),
    CAPABILITY_NOT_IMPLEMENTED("E_NOT_IMPLEMENTED", false, RelayExitCode.USAGE),
    /**
     * 这颗节点根本没有输入法动作键可触发。与 [NODE_NOT_ACTIONABLE]（节点没有这次要的那个动作）
     * 分开给：前者要说的是"这个控件不是输入框，换目标"，后者才是"这颗节点此刻不认这个动作"。
     */
    NODE_NO_IME_ACTION("E_NODE_NO_IME_ACTION", false, RelayExitCode.USAGE),

    /**
     * 解析出来的那颗节点属于系统授权界面：这一格只能由用户本人点。
     *
     * 不复用 [SURFACE_SYSTEM_CONSENT_REQUIRED]：那一句的六语原文说的都是"这次采集会话需要
     * 你在系统弹窗里确认"，指的是投屏同意框，与"你想点的是别人家的授权按钮"不是同一件事。
     * 一个码装两种处置，向用户转述的那一句就一定会错。
     */
    NODE_SYSTEM_CONSENT_TARGET(
        "E_NODE_SYSTEM_CONSENT_TARGET", false,
        RelayExitCode.NEEDS_USER_ACTION,
    ),

    /**
     * 启动请求被系统接下、但目标没有成为所在那块屏的前台窗口：前台屏那一路由
     * [interlock.relay.core.exec.direct.DirectBackend] 在核验前台归属后给出，虚拟屏那一路由
     * [interlock.relay.core.exec.shizuku.ShizukuBackend] 给出，两边都在 reason 里点名是谁占着最前面。
     *
     * 一条意思只给这一个码：拆成两支会让调用方必须同时认两支，而它的处置完全相同。
     * 不再用 `ok:true` 配 `verified:false` 交回：调用方拿到成功就会往下走，而那一步
     * 之后所有界面操作都落在别的窗口上。
     */
    LAUNCH_NOT_LANDED("E_LAUNCH_NOT_LANDED", true, RelayExitCode.RETRY_LATER),

    SURFACE_API_LEVEL("E_SURFACE_API_LEVEL", false, RelayExitCode.NEEDS_USER_ACTION),
    SURFACE_NO_SHELL("E_SURFACE_NO_SHELL", true, RelayExitCode.NEEDS_USER_ACTION),
    SURFACE_TRUSTED_DENIED("E_SURFACE_TRUSTED_DENIED", false, RelayExitCode.NEEDS_USER_ACTION),
    SURFACE_UNAVAILABLE("E_SURFACE_UNAVAILABLE", false, RelayExitCode.NEEDS_USER_ACTION),
    /**
     * 这条能力只声明了一个执行面（如 `surface.virtual` 只有可信屏），而用户当前的偏好链
     * 走不到那个面。与 [SURFACE_UNAVAILABLE] 分开：那条说的是「有实现但此刻够不着」，
     * 这一条说的是「请求落点本身不成立」——伪造一个前台落点只会让调用先过门禁、
     * 再在执行期换成一条泛化的 `E_BACKEND_UNAVAILABLE`，而不是「切偏好/建屏」这个真正的修法。
     */
    SURFACE_MODE_REQUIRED("E_SURFACE_MODE_REQUIRED", false, RelayExitCode.NEEDS_USER_ACTION),
    SURFACE_SYSTEM_CONSENT_REQUIRED("E_SURFACE_SYSTEM_CONSENT_REQUIRED", true, RelayExitCode.NEEDS_USER_ACTION),
    SURFACE_SECURE_WINDOW("E_SURFACE_SECURE_WINDOW", false, RelayExitCode.DENIED),
    BACKEND_SHIZUKU_DEAD("E_BACKEND_SHIZUKU_DEAD", true, RelayExitCode.RETRY_LATER),
    /**
     * 有实现，但此刻够不着：无障碍没绑上、Shizuku 用户服务掉线、剪贴板需要应用持有焦点。
     *
     * 与 `E_NOT_IMPLEMENTED` 必须分开：后者是"这个版本里没有这段代码"，助手收到它应当
     * 放弃这条路径；前者是"稍后或让用户动一下就能用"，收到它就应当重试或提示用户。
     */
    BACKEND_UNAVAILABLE("E_BACKEND_UNAVAILABLE", true, RelayExitCode.RETRY_LATER),
    STORAGE_FULL("E_STORAGE_FULL", true, RelayExitCode.RETRY_LATER),
    RATE_LIMITED("E_RATE_LIMITED", true, RelayExitCode.RETRY_LATER),
    /**
     * 队列容量确实满了（本地待发队列或宿主待执行队列任一）。
     *
     * 与 `E_RATE_LIMITED` 分开：限流说的是「频率太高，减速」，满队说的是「存放位置
     * 没了，稍后再来」——前者重试也可能立刻再被挡，后者退避一段时间后位置必然腾出。
     * 两码合一会让调用方在满队时按限流节奏无意义地快速重试。
     */
    QUEUE_FULL("E_QUEUE_FULL", true, RelayExitCode.RETRY_LATER),
    REQUEST_TOO_LARGE("E_REQUEST_TOO_LARGE", false, RelayExitCode.USAGE),
    TRANSPORT_MALFORMED("E_TRANSPORT_MALFORMED", false, RelayExitCode.USAGE),
    TRANSPORT_TIMEOUT("E_TRANSPORT_TIMEOUT", true, RelayExitCode.TIMEOUT),
    INTERNAL("E_INTERNAL", false, RelayExitCode.INTERNAL),
    ;

    companion object {
        fun fromCode(value: String): RelayError? = entries.firstOrNull { it.code == value }
    }
}

/**
 * 错误码的默认文案词条。文案本身一律经 [interlock.relay.core.spi.RelayText] 取出；
 * 宿主换文案时以此处为对照，或整体跳过这张表用自己的词条。
 */
fun RelayError.defaultMessageRes(): Int = when (this) {
    RelayError.GATE_HOST_DENIED -> R.string.relay_err_gate_denied
    RelayError.GATE_SYSTEM_MISSING -> R.string.relay_err_gate_system_missing
    RelayError.GATE_APPROVAL_TIMEOUT -> R.string.relay_err_gate_timeout
    RelayError.GATE_AWAITING_CONSENT -> R.string.relay_err_awaiting_consent
    RelayError.GATE_WAITING_TURN -> R.string.relay_err_gate_waiting_turn
    RelayError.GATE_CANCELLED -> R.string.relay_err_gate_cancelled
    RelayError.GATE_SUSPENDED_BY_HOST -> R.string.relay_err_suspended_by_host
    RelayError.NO_EDITABLE_TARGET -> R.string.relay_err_no_editable_target
    RelayError.NODE_NOT_FOUND -> R.string.relay_err_node_not_found
    RelayError.NODE_AMBIGUOUS -> R.string.relay_err_node_ambiguous
    RelayError.NODE_NOT_ACTIONABLE -> R.string.relay_err_node_not_actionable
    RelayError.NODE_ACTION_REJECTED -> R.string.relay_err_action_rejected
    RelayError.WAIT_TIMEOUT -> R.string.relay_err_wait_timeout
    RelayError.GATE_NO_FOREGROUND -> R.string.relay_err_gate_no_foreground
    RelayError.CLIPBOARD_NO_FOCUS -> R.string.relay_err_clipboard_no_focus
    RelayError.CAPABILITY_UNAVAILABLE_ON_DEVICE -> R.string.relay_err_unavailable_device
    RelayError.CAPABILITY_NOT_IMPLEMENTED -> R.string.relay_err_not_implemented
    RelayError.NODE_NO_IME_ACTION -> R.string.relay_err_node_no_ime_action
    RelayError.NODE_SYSTEM_CONSENT_TARGET -> R.string.relay_err_node_consent_target
    RelayError.LAUNCH_NOT_LANDED -> R.string.relay_err_launch_not_landed
    RelayError.SURFACE_API_LEVEL -> R.string.relay_err_surface_api_level
    RelayError.SURFACE_NO_SHELL -> R.string.relay_err_surface_no_shell
    RelayError.SURFACE_TRUSTED_DENIED -> R.string.relay_err_surface_trusted_denied
    RelayError.SURFACE_UNAVAILABLE -> R.string.relay_err_surface_unavailable
    RelayError.SURFACE_MODE_REQUIRED -> R.string.relay_err_surface_mode_required
    RelayError.SURFACE_SYSTEM_CONSENT_REQUIRED -> R.string.relay_err_surface_consent
    RelayError.SURFACE_SECURE_WINDOW -> R.string.relay_err_surface_secure
    RelayError.BACKEND_SHIZUKU_DEAD -> R.string.relay_err_backend_dead
    RelayError.BACKEND_UNAVAILABLE -> R.string.relay_err_backend_unavailable
    RelayError.STORAGE_FULL -> R.string.relay_err_storage_full
    RelayError.RATE_LIMITED -> R.string.relay_err_rate_limited
    RelayError.QUEUE_FULL -> R.string.relay_err_queue_full
    RelayError.REQUEST_TOO_LARGE -> R.string.relay_err_request_large
    RelayError.TRANSPORT_MALFORMED -> R.string.relay_err_malformed
    RelayError.TRANSPORT_TIMEOUT -> R.string.relay_err_timeout
    RelayError.INTERNAL -> R.string.relay_err_internal
}

/**
 * 助手侧入口程序的退出码约定。非零值必须可被脚本区分处理：
 * 拒绝、等待、用法错误是三套完全不同的后续动作。
 */
object RelayExitCode {
    const val SUCCESS = 0

    /** 参数不合形状或能力名未知——改脚本来修，重试没有意义。 */
    const val USAGE = 1

    /** 用户或规则挡下，重试会被再次挡下。 */
    const val DENIED = 2

    const val TIMEOUT = 3

    /** 需要用户去界面上做动作（授权、开权限、选表面）。 */
    const val NEEDS_USER_ACTION = 4

    const val RETRY_LATER = 5

    const val INTERNAL = 6
}
