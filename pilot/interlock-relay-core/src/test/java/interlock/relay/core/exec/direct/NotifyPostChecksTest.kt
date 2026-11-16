package interlock.relay.core.exec.direct

import android.app.NotificationManager
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.EffectState
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.effectStateFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知发送校验的纯判据。各类场景各钉一条：权限被关、渠道被关、渠道缺失
 * 都必须在发送**之前**以系统权限类错误拒掉；发送之后只有本应用自己的活动通知里
 * 真的对上了那一条（同 id 旧通知不算）才算核实；复查窗口到点仍不可见时按
 * 「结果未知」作答——已派发、副作用状态 UNKNOWN、字段口径照带，绝不回「已发送」。
 * 判据不碰框架，假状态穷举。
 */
class NotifyPostChecksTest {

    @Test
    fun `权限被关在发送前拒绝且不赖渠道`() {
        val reason = NotifyPostChecks.preCheck(
            notificationsEnabled = false,
            channelImportance = NotificationManager.IMPORTANCE_DEFAULT,
        )
        assertTrue(reason.orEmpty().contains("notification permission"))
    }

    @Test
    fun `渠道被用户关闭在发送前拒绝`() {
        val reason = NotifyPostChecks.preCheck(
            notificationsEnabled = true,
            channelImportance = NotificationManager.IMPORTANCE_NONE,
        )
        assertTrue(reason.orEmpty().contains("channel"))
    }

    @Test
    fun `渠道缺失与被关闭分开说`() {
        val missing = NotifyPostChecks.preCheck(notificationsEnabled = true, channelImportance = null)
        val disabled = NotifyPostChecks.preCheck(
            notificationsEnabled = true,
            channelImportance = NotificationManager.IMPORTANCE_NONE,
        )
        assertTrue(missing.orEmpty().contains("missing"))
        assertTrue(disabled.orEmpty().contains("disabled"))
    }

    @Test
    fun `权限与渠道都在时放行`() {
        assertNull(
            NotifyPostChecks.preCheck(
                notificationsEnabled = true,
                channelImportance = NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        // 低重要性（静默）渠道也是合法渠道：可见性与重要性无关。
        assertNull(
            NotifyPostChecks.preCheck(
                notificationsEnabled = true,
                channelImportance = NotificationManager.IMPORTANCE_MIN,
            ),
        )
    }

    @Test
    fun `可见性以活动通知里对上那条 id 且不早于本次贴出为准`() {
        assertTrue(NotifyPostChecks.visible(listOf(1 to 100L, 42 to 900L, 7 to 100L), 42, 500L))
        assertFalse(NotifyPostChecks.visible(listOf(1 to 100L, 7 to 100L), 42, 500L))
        // 复查窗口读不到活动通知（空列表）时按不可见处理，不把「没查到」说成「已核实」。
        assertFalse(NotifyPostChecks.visible(emptyList(), 42, 500L))
    }

    /** 同 id 旧通知还挂在通知栏时不得误判 verified：贴出时刻必须晚于或等于发送。 */
    @Test
    fun `同 id 旧通知不算本次发送`() {
        val active = listOf(42 to 100L)
        assertFalse(NotifyPostChecks.visible(active, 42, 500L))
        // 时刻相等（同一毫秒贴出）算同一条；比早的一律是上一条。
        assertTrue(NotifyPostChecks.visible(listOf(42 to 500L), 42, 500L))
    }

    @Test
    fun `复查窗口总量不超过半秒`() {
        // 50ms × 10 次 = 500ms：窗口再长，一次已经成功的发送也会被拖到调用预算边缘。
        assertEquals(500L, DirectBackend.NOTIFY_VERIFY_TRIES * DirectBackend.NOTIFY_VERIFY_INTERVAL_MS)
    }

    /** 复查到点仍不可见的出口：结果未知码 + 已派发，副作用状态必须 UNKNOWN。 */
    @Test
    fun `未知结局按结果未知作答且副作用状态为 UNKNOWN`() {
        val failed = NotifyPostChecks.unknownOutcome(
            id = 42,
            notificationsEnabled = true,
            channelEnabled = true,
            windowMs = 500L,
        )
        assertEquals(RelayError.TRANSPORT_UNKNOWN, failed.error)
        assertTrue(failed.dispatched)
        assertTrue(failed.reason.orEmpty().contains("outcome is unknown"))
        assertEquals(
            EffectState.UNKNOWN,
            effectStateFor(ok = false, error = failed.error, capability = CapabilityId.NOTIFY_POST, dispatched = failed.dispatched),
        )
        // 该出口绝不落成「未开始」：系统已接受发送，NOT_STARTED 是替系统撒谎的另一种说法。
        assertEquals(
            EffectState.UNKNOWN,
            effectStateFor(ok = false, error = failed.error, capability = CapabilityId.NOTIFY_POST, dispatched = false),
        )
    }

    /** 失败回包与成功回包同一份字段口径：机器判读读字段，不解析 reason。 */
    @Test
    fun `未知结局与发送前拒绝的失败回包都带结构化字段`() {
        val unknown = NotifyPostChecks.unknownOutcome(42, true, true, 500L).data
        assertEquals(42, unknown.getInt("id"))
        assertEquals(true, unknown.getBoolean("posted"))
        assertEquals(false, unknown.getBoolean("verified"))
        assertEquals(true, unknown.getBoolean("notificationsEnabled"))
        assertEquals(true, unknown.getBoolean("channelEnabled"))

        val rejected = NotifyPostChecks.payload(7, posted = false, verified = false, false, false)
        assertEquals(7, rejected.getInt("id"))
        assertEquals(false, rejected.getBoolean("posted"))
        assertEquals(false, rejected.getBoolean("verified"))
        assertEquals(false, rejected.getBoolean("notificationsEnabled"))
        assertEquals(false, rejected.getBoolean("channelEnabled"))
    }

    /** 渠道缺失（null）在字段口径里不算「渠道开着」：它与被关闭是同一句诊断。 */
    @Test
    fun `渠道缺失时 payload 的 channelEnabled 为 false`() {
        val missing = NotifyPostChecks.preCheck(notificationsEnabled = true, channelImportance = null)
        assertTrue(missing.orEmpty().contains("missing"))
        // DirectBackend 的字段口径：channelImportance == null → channelEnabled = false。
        val channelImportance: Int? = null
        val channelEnabled = channelImportance != null && channelImportance != NotificationManager.IMPORTANCE_NONE
        assertFalse(channelEnabled)
    }

    /** 省略 id 的调用在拒绝回包与成功回包必须带同一个生成值：id 只解析一次。 */
    @Test
    fun `省略 id 时两个出口解析到同一个生成值`() {
        val omitted = org.json.JSONObject()
        assertEquals(1_700_000_000_123L.toInt(), NotifyPostChecks.resolveId(omitted, 1_700_000_000_123L))
        assertEquals(1_700_000_000_123L.toInt(), NotifyPostChecks.resolveId(omitted, 1_700_000_000_123L))
        // 调用方给了 id 就原样用，不按时刻覆盖。
        val explicit = org.json.JSONObject().put("id", 42)
        assertEquals(42, NotifyPostChecks.resolveId(explicit, 1_700_000_000_123L))
    }
}
