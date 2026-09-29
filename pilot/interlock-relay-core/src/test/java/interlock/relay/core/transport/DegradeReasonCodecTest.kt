package interlock.relay.core.transport

import interlock.relay.core.protocol.RelayResponse
import interlock.relay.core.protocol.SurfaceKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行面降级与后端诊断各占一个键。
 *
 * 「优先后台」落到前台、且停在最前面的是分身容器时，两条信息要在同一次回包里同时可见：
 * 只留降级码，调用方就拿不到那个包名，也就给不出下一步动作。
 */
class DegradeReasonCodecTest {

    private fun response(ok: Boolean, reason: String?, degradeReason: String?) = RelayResponse(
        id = "r1",
        ok = ok,
        data = JSONObject(),
        artifacts = emptyList(),
        surface = SurfaceKind.FOREGROUND,
        degradedFrom = SurfaceKind.BACKEND_TRUSTED,
        reason = reason,
        degradeReason = degradeReason,
        error = null,
        elapsedMs = 12L,
    )

    @Test
    fun successKeepsSceneNoteAndDegradeCodeApart() {
        val json = JSONObject(
            EnvelopeCodec.encode(
                response(
                    ok = true,
                    reason = "foreground is clone container com.vivo.doubleinstance",
                    degradeReason = "E_SURFACE_UNAVAILABLE",
                ),
            ),
        )
        assertEquals("foreground is clone container com.vivo.doubleinstance", json.getString("note"))
        assertEquals("E_SURFACE_UNAVAILABLE", json.getString("degradeReason"))
        assertFalse("成功回包的补充说明不占用 reason 这个键", json.has("reason"))
        assertTrue(json.getBoolean("degraded"))
    }

    @Test
    fun failureKeepsReasonAndStillReportsDegradeCode() {
        val json = JSONObject(
            EnvelopeCodec.encode(
                response(ok = false, reason = "accepted but not landed", degradeReason = "E_SURFACE_NO_SHELL"),
            ),
        )
        assertEquals("accepted but not landed", json.getString("reason"))
        assertEquals("E_SURFACE_NO_SHELL", json.getString("degradeReason"))
        assertFalse(json.has("note"))
    }

    @Test
    fun noDegradeWritesNoKey() {
        val json = JSONObject(EnvelopeCodec.encode(response(ok = true, reason = null, degradeReason = null)))
        assertFalse(json.has("degradeReason"))
    }
}
