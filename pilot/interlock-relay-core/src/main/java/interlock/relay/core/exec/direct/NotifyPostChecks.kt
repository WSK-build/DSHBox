package interlock.relay.core.exec.direct

import android.app.NotificationManager
import interlock.relay.core.exec.BackendResult
import interlock.relay.core.protocol.RelayError
import org.json.JSONObject

/**
 * 通知发送校验的**纯判据**。发送前后的两个决定都从这里出：
 *
 * - 发送前：通知权限、渠道开关两个条件任一不满足就拒绝发送——用户关掉的渠道或
 * 收回的权限会让 `notify()` 静默无效果，把那一次说成「已发送」是替系统撒谎；
 * 属于「系统权限一侧还缺一环」，回系统权限类错误。
 * - 发送后：`notify()` 接受只代表系统收下了请求，可见性是异步落定的。以本应用自己的
 * 活动通知里**有没有那一 条**为判据（读自己不需要通知监听权限）；短窗口复查到点
 * 仍不可见时，「系统接受了却看不见」答不出真实结局——按结果未知（`E_TRANSPORT_UNKNOWN`、
 * 已派发）处理，不回「已发送」。
 *
 * 不用通知监听验证自己发的通知：监听是全局的，读别人的通知既杂又不准。
 * 判据收成纯函数，JVM 单测按假状态穷举场景（权限拒绝、渠道关闭、实际可见、
 * 不可见、同 id 旧通知）。
 */
internal object NotifyPostChecks {

    /**
     * 发送的通知 id：调用方给了 `id` 就原样用，省略时按当前时刻生成。
     * 发送前拒绝与成功路径必须走**同一处**解析——两个出口各生成一次的话，
     * 省略 id 的调用在拒绝回包里会带一个从未用过的时刻 id，字段口径就散了。
     */
    internal fun resolveId(args: JSONObject, nowMs: Long): Int = args.optInt("id", nowMs.toInt())

    /** 发送前检查的结论。[reason] 为 null 表示可以发送；否则给出可直接回给助手的一句诊断。 */
    internal fun preCheck(notificationsEnabled: Boolean, channelImportance: Int?): String? = when {
        !notificationsEnabled ->
            "notification permission is not granted: the system would silently drop the post; " +
                "grant notifications for this app and send again"

        channelImportance == null ->
            "notification channel is missing: the channel was deleted or never created; " +
                "the post would be dropped, re-send after the channel is restored"

        channelImportance == NotificationManager.IMPORTANCE_NONE ->
            "notification channel is disabled by the user: the post would be dropped; " +
                "re-enable the channel (or use another channel) before sending"

        else -> null
    }

    /**
     * 发送后的可见性判据：活动通知里**同一条**才算核实。匹配按 id 之外还要看贴出时刻
     * ([postTimeMs])——同 id 的旧通知可能还挂在通知栏，只比 id 会把上一条误当成这次发送。
     * [entries] 是此刻活动通知的 (id, postTime) 对：postTime 是系统记录的**写入**时刻，
     * 比 `when` 更可信——后者是展示时刻，部分 ROM 会改写它。由调用方在复查窗口内逐轮取。
     */
    internal fun visible(entries: List<Pair<Int, Long>>, id: Int, postTimeMs: Long): Boolean =
        entries.any { it.first == id && it.second >= postTimeMs }

    /** 成功回包的字段口径：核实与否、两侧开关都按结构化字段给，不藏在 reason 里。 */
    internal fun payload(
        id: Int,
        posted: Boolean,
        verified: Boolean,
        notificationsEnabled: Boolean,
        channelEnabled: Boolean,
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("posted", posted)
        .put("verified", verified)
        .put("notificationsEnabled", notificationsEnabled)
        .put("channelEnabled", channelEnabled)

    /**
     * 复查窗口到点仍不可见的失败出口：系统**接受了**这次发送（动作已提交，结局无法证实），
     * 因此按「结果未知」码作答并标已派发——副作用状态必须 UNKNOWN，不得落成「未开始」。
     * 字段口径照带：posted 仍为 true（系统收下了），verified 为 false（没核实到可见）。
     */
    internal fun unknownOutcome(
        id: Int,
        notificationsEnabled: Boolean,
        channelEnabled: Boolean,
        windowMs: Long,
    ): BackendResult.Failed = BackendResult.Failed(
        RelayError.TRANSPORT_UNKNOWN,
        "the system accepted the post but no active notification of this app carries id $id " +
            "within the verify window (${windowMs}ms); the outcome is unknown - it may surface " +
            "later or never, do not treat it as sent and do not repost with the same id",
        dispatched = true,
        data = payload(id, posted = true, verified = false, notificationsEnabled, channelEnabled),
    )
}
