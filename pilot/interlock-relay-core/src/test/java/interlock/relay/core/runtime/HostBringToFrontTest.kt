package interlock.relay.core.runtime

import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.RelayRequest
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「把宿主带到前台」这一自身目标豁免的形状边界。
 *
 * 豁免只覆盖 `app.launch` 且参数恰好只有宿主包名这一种形状；形状之外的任何组合
 * 都回到自身目标守卫按普通拒绝处理。形状判据放宽一格，就多一条触碰宿主的路。
 */
class HostBringToFrontTest {

    private val self = "com.example.host"

    private fun request(capability: CapabilityId, vararg args: Pair<String, Any?>) =
        RelayRequest(
            id = "req-1",
            sentAtMs = 0L,
            capability = capability.wire,
            args = JSONObject().apply { args.forEach { (k, v) -> put(k, v) } },
            timeoutMs = RelayRequest.DEFAULT_TIMEOUT_MS,
        )

    @Test
    fun `app launch 且参数只有宿主包名时命中`() {
        assertTrue(isHostBringToFront(request(CapabilityId.APP_LAUNCH, "package" to self), self))
    }

    @Test
    fun `多带一个参数就不算带回前台`() {
        assertFalse(
            isHostBringToFront(
                request(CapabilityId.APP_LAUNCH, "package" to self, "extra" to "1"),
                self,
            ),
        )
    }

    @Test
    fun `目标不是宿主时不命中`() {
        assertFalse(
            isHostBringToFront(request(CapabilityId.APP_LAUNCH, "package" to "com.other.app"), self),
        )
    }

    @Test
    fun `其他能力即使带宿主包名也不命中`() {
        assertFalse(
            isHostBringToFront(
                request(CapabilityId.UI_CLICK, "package" to self),
                self,
            ),
        )
    }

    @Test
    fun `宿主包名换成别的字符串时也不命中`() {
        assertFalse(
            isHostBringToFront(request(CapabilityId.APP_LAUNCH, "package" to self), "com.other.host"),
        )
    }
}
