package interlock.relay.core.protocol

/**
 * 同参数重发的处置建议。它与错误码自带的 `retryable` 各答一个问题：
 * `retryable` 说「这个错误值得再试吗」，本表说「同一条请求再发一遍安全吗」。
 * 断回包之后的重试可能重复执行，因此重放凭据只认本表，不认 `retryable`。
 *
 * 通道上的字面量取 [name] 的小写形式（`safe_retry`、`user_decide`），
 * 与枚举名一一对应，不另设一套拼写。
 */
enum class RetryPolicy {
    /** 可直接重发：重放至多重复一次只读查询，不产生新的对外痕迹。 */
    SAFE_RETRY,

    /** 先查再动：先取原请求状态或读回现状核对，确认没成才重发。 */
    QUERY_FIRST,

    /** 交用户判断：可能已执行一次且无法从回包证实，是否重投由用户决定。 */
    USER_DECIDE,

    /** 绝不重发：保留一档给「重放本身就有害」的未来条目，当前表内暂无归属。 */
    NEVER,
}

/**
 * 副作用是否已经落地的保守标记。断线、超时之后调用方最需要知道的不是「成功没有」，
 * 而是「世界有没有被改动」：[UNKNOWN] 就是为答不了这一问的时刻准备的。
 * 字面量同 [RetryPolicy]：[name] 的小写形式。
 */
enum class EffectState {
    /** 可证尚未开始：动作未派发，或失败发生在派发之前。 */
    NOT_STARTED,

    /** 已提交给后端但回执未到（由执行链路接入后标注，本层暂不产出）。 */
    DISPATCHED,

    /** 结果已核实（成功回包）。 */
    VERIFIED,

    /** 无法证实：超时类失败时动作可能已发生。 */
    UNKNOWN,
}

/**
 * 重放分类表（设计来源：重放矩阵——按能力建立「无最终回执时怎么办」）。
 * 每条能力一行依据；表外一律 [RetryPolicy.USER_DECIDE]：
 * 未归类意味着没论证过它的重放安全，宁可多问一次人。
 */
fun retryPolicyOf(id: CapabilityId): RetryPolicy = when (id) {
    // 纯诊断/查询：重查只耗少量计算，不产生任何持久痕迹。
    CapabilityId.PKG_QUERY -> RetryPolicy.SAFE_RETRY
    CapabilityId.UI_SNAPSHOT -> RetryPolicy.SAFE_RETRY
    CapabilityId.UI_NODE -> RetryPolicy.SAFE_RETRY

    // 无副作用的等待：条件没等到就再等一轮，界面不会因此改变。
    CapabilityId.UI_WAIT_FOR -> RetryPolicy.SAFE_RETRY

    // 读取但耗资源：重复采集会重复弹系统许可、重复占用采集会话，先查原状态。
    CapabilityId.SCREEN_CAPTURE -> RetryPolicy.QUERY_FIRST
    CapabilityId.SCREEN_RECORD -> RetryPolicy.QUERY_FIRST
    CapabilityId.SCREEN_OBSERVE -> RetryPolicy.QUERY_FIRST
    CapabilityId.AUDIO_CAPTURE -> RetryPolicy.QUERY_FIRST
    CapabilityId.NOTIFY_READ -> RetryPolicy.QUERY_FIRST
    CapabilityId.LOCATION_READ -> RetryPolicy.QUERY_FIRST
    CapabilityId.MEDIA_READ -> RetryPolicy.QUERY_FIRST

    // 可检查的状态设置：先读回当前值核对目标，写没写成功可以从状态上分辨。
    CapabilityId.UI_SET_VALUE -> RetryPolicy.QUERY_FIRST
    CapabilityId.SECURE_SETTINGS -> RetryPolicy.QUERY_FIRST
    CapabilityId.APPOPS_SET -> RetryPolicy.QUERY_FIRST
    CapabilityId.CLIPBOARD_WRITE -> RetryPolicy.QUERY_FIRST

    // 非幂等创建/交接：重复执行会多一条联系人/日历/媒体/安装/进程，无法从回包分辨。
    CapabilityId.CONTACT_WRITE -> RetryPolicy.USER_DECIDE
    CapabilityId.CALENDAR_WRITE -> RetryPolicy.USER_DECIDE
    CapabilityId.MEDIA_WRITE -> RetryPolicy.USER_DECIDE
    CapabilityId.PKG_INSTALL -> RetryPolicy.USER_DECIDE
    CapabilityId.SYS_INTENT -> RetryPolicy.USER_DECIDE
    CapabilityId.APP_LAUNCH -> RetryPolicy.USER_DECIDE
    CapabilityId.APP_STOP -> RetryPolicy.USER_DECIDE
    CapabilityId.SYS_SHELL -> RetryPolicy.USER_DECIDE
    CapabilityId.NOTIFY_POST -> RetryPolicy.USER_DECIDE

    // UI 动作是一次性操控：重发就是再点一次、再滚一遍，无法证实第一次没生效。
    CapabilityId.UI_TAP -> RetryPolicy.USER_DECIDE
    CapabilityId.UI_CLICK -> RetryPolicy.USER_DECIDE
    CapabilityId.UI_LONG_CLICK -> RetryPolicy.USER_DECIDE
    CapabilityId.UI_SELECT -> RetryPolicy.USER_DECIDE
    CapabilityId.UI_DISMISS -> RetryPolicy.USER_DECIDE
    CapabilityId.UI_SCROLL -> RetryPolicy.USER_DECIDE
    CapabilityId.UI_SWIPE -> RetryPolicy.USER_DECIDE
    CapabilityId.UI_TEXT -> RetryPolicy.USER_DECIDE
    CapabilityId.UI_KEY -> RetryPolicy.USER_DECIDE
    CapabilityId.UI_IME_ACTION -> RetryPolicy.USER_DECIDE
    CapabilityId.UI_SET_PROGRESS -> RetryPolicy.USER_DECIDE

    // 表外能力（clip.read、contact.read、cal.read、surface.virtual 等）：
    // 没有逐条论证过重放安全，一律按最保守处置交用户决定。
    else -> RetryPolicy.USER_DECIDE
}

/**
 * 按执行结论标注副作用状态。超时三兄弟（通道、等待、审批框）意味着动作可能
 * 已经派发出去，只能答 [EffectState.UNKNOWN]；其余失败都发生在派发之前。
 * 更细的 [EffectState.DISPATCHED] 由执行链路在派发边界接入，本层不产出。
 */
fun effectStateFor(ok: Boolean, error: RelayError?): EffectState = when {
    ok -> EffectState.VERIFIED
    error == RelayError.TRANSPORT_TIMEOUT -> EffectState.UNKNOWN
    error == RelayError.WAIT_TIMEOUT -> EffectState.UNKNOWN
    error == RelayError.GATE_APPROVAL_TIMEOUT -> EffectState.UNKNOWN
    else -> EffectState.NOT_STARTED
}
