package com.dshbox.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * `dsh web: http://host:port/?token=…` 行解析（以输出为准口径）的
 * 单测：正常行、缺端口、缺 token、redact 后、多余参数、非法端口、纯随机 token、
 * CRLF、变形行。
 */
class DshWebLaunchLineTest {

    private fun token(): String = UUID.randomUUID().toString().replace("-", "")

    @Test
    fun parse_readsPortAndToken() {
        val t = token()
        val parsed = DshWebLaunchLine.parse("dsh web: http://127.0.0.1:3083/?token=$t")
        assertEquals(3083, parsed!!.port)
        assertEquals(t, parsed.token)
    }

    /** 与请求值不一致以输出为准：输出 3097 就取 3097。 */
    @Test
    fun parse_prefersOutputPort() {
        val parsed = DshWebLaunchLine.parse("dsh web: http://127.0.0.1:3097/?token=abc")
        assertEquals(3097, parsed!!.port)
    }

    /** query 里带其它参数也解析得出 token。 */
    @Test
    fun parse_handlesExtraQueryParams() {
        val t = token()
        val parsed = DshWebLaunchLine.parse("dsh web: http://127.0.0.1:4099/?foo=1&token=$t&bar=2")
        assertEquals(4099, parsed!!.port)
        assertEquals(t, parsed.token)
    }

    /** 权威段没有显式端口（http 默认端口语义）→ null。 */
    @Test
    fun parse_rejectsMissingPort() {
        assertNull(DshWebLaunchLine.parse("dsh web: http://127.0.0.1/?token=abc"))
    }

    /** token 缺失 → null（端口与 token 必须同时确证）。 */
    @Test
    fun parse_rejectsMissingToken() {
        assertNull(DshWebLaunchLine.parse("dsh web: http://127.0.0.1:3080/"))
        assertNull(DshWebLaunchLine.parse("dsh web: http://127.0.0.1:3080/?token="))
    }

    /** 标记缺失 → 不是启动 URL 行。 */
    @Test
    fun parse_ignoresOtherLines() {
        assertNull(DshWebLaunchLine.parse("listening on http://localhost:3080"))
        assertNull(DshWebLaunchLine.parse(""))
    }

    /** redact 后的行（token 被替换为 ***）→ 端口仍可解析、token 失效即为 null。 */
    @Test
    fun parse_toleratesRedactedLines() {
        // token=*** 是弱形态：解析要求非空 token，`***` 非空所以可解析（端口仍取得）。
        val parsed = DshWebLaunchLine.parse("dsh web: http://127.0.0.1:3092/?token=***")
        assertEquals(3092, parsed!!.port)
    }

    /** 非法端口（超界/非数字）→ null。 */
    @Test
    fun parse_rejectsInvalidPort() {
        assertNull(DshWebLaunchLine.parse("dsh web: http://127.0.0.1:70000/?token=x"))
        assertNull(DshWebLaunchLine.parse("dsh web: http://127.0.0.1:0/?token=x"))
        assertNull(DshWebLaunchLine.parse("dsh web: http://127.0.0.1:port/?token=x"))
    }

    /** CRLF 结尾与行尾空白不干扰解析。 */
    @Test
    fun parse_toleratesCrlfAndTrailingSpace() {
        val t = token()
        assertEquals(t, DshWebLaunchLine.parse("dsh web: http://127.0.0.1:3081/?token=$t\r\n")!!.token)
        assertEquals(t, DshWebLaunchLine.parse("dsh web: http://127.0.0.1:3081/?token=$t   ")!!.token)
    }

    /** 多行输出里的目标行前后干扰不截断。 */
    @Test
    fun parse_findsMarkerMidLine() {
        val t = token()
        assertEquals(3084, DshWebLaunchLine.parse(
            "boot ok; dsh web: http://127.0.0.1:3084/?token=$t (ready)",
        )!!.port)
        assertEquals(t, DshWebLaunchLine.parse(
            "boot ok; dsh web: http://127.0.0.1:3084/?token=$t (ready)",
        )!!.token)
    }
}
