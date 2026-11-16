package interlock.relay.core.exec.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 面板输入的纯判据：动作白名单、归一化坐标、duration 夹取/校验、限速。
 * 面板输入**不走 gate/审批**（鉴权在控制 token + tab token + 会话存活），这里钉的是
 * 进入设备注入前的最后一道形状校验。
 */
class PanelInputPolicyTest {

    /** validate 回非空错误且包含片段。 */
    private fun assertRejects(error: String?, fragment: String) {
        assertTrue("应当拒绝：$error", error != null && error.contains(fragment))
    }

    private fun req(
        action: String,
        x: Double? = null, y: Double? = null,
        x1: Double? = null, y1: Double? = null, x2: Double? = null, y2: Double? = null,
        durationMs: Int? = null,
    ) = PanelInputPolicy.Request(action, x = x, y = y, x1 = x1, y1 = y1, x2 = x2, y2 = y2, durationMs = durationMs)

    @Test
    fun `动作白名单只认 tap swipe back home`() {
        assertNull(PanelInputPolicy.validate(req("tap", x = 0.5, y = 0.5)))
        assertNull(PanelInputPolicy.validate(req("swipe", x1 = 0.0, y1 = 0.0, x2 = 1.0, y2 = 1.0, durationMs = 300)))
        assertNull(PanelInputPolicy.validate(req("back")))
        assertNull(PanelInputPolicy.validate(req("home")))
        assertRejects(PanelInputPolicy.validate(req("text")), "unknown action")
        assertRejects(PanelInputPolicy.validate(req("longpress")), "unknown action")
        // AI 侧的能力动作不因面板通路而放行
        assertRejects(PanelInputPolicy.validate(req("key")), "unknown action")
    }

    @Test
    fun `坐标必须在区间内且按动作配齐`() {
        // tap 缺 y
        assertRejects(PanelInputPolicy.validate(req("tap", x = 0.5)), "y")
        // tap 越界（含 NaN/无穷）
        assertRejects(PanelInputPolicy.validate(req("tap", x = 1.5, y = 0.5)), "x")
        assertRejects(PanelInputPolicy.validate(req("tap", x = -0.1, y = 0.5)), "x")
        assertRejects(PanelInputPolicy.validate(req("tap", x = Double.NaN, y = 0.5)), "x")
        assertRejects(PanelInputPolicy.validate(req("tap", x = 0.5, y = Double.POSITIVE_INFINITY)), "y")
        // swipe 四点都要在 0..1
        assertRejects(PanelInputPolicy.validate(req("swipe", x1 = 0.0, y1 = 0.0, x2 = 1.2, y2 = 0.0, durationMs = 300)), "x2")
        // swipe 缺 duration / duration 越界
        assertRejects(PanelInputPolicy.validate(req("swipe", x1 = 0.0, y1 = 0.0, x2 = 1.0, y2 = 1.0)), "durationMs")
        assertRejects(PanelInputPolicy.validate(req("swipe", x1 = 0.0, y1 = 0.0, x2 = 1.0, y2 = 1.0, durationMs = 49)), "durationMs")
        assertRejects(PanelInputPolicy.validate(req("swipe", x1 = 0.0, y1 = 0.0, x2 = 1.0, y2 = 1.0, durationMs = 1001)), "durationMs")
        // 边界值合法
        assertNull(PanelInputPolicy.validate(req("swipe", x1 = 0.0, y1 = 0.0, x2 = 1.0, y2 = 1.0, durationMs = 50)))
        assertNull(PanelInputPolicy.validate(req("swipe", x1 = 0.0, y1 = 0.0, x2 = 1.0, y2 = 1.0, durationMs = 1000)))
    }

    @Test
    fun `像素映射按源尺寸夹取，尺寸未知拒映射`() {
        assertEquals(0, PanelInputPolicy.pixel(0.0, 2560))
        assertEquals(2559, PanelInputPolicy.pixel(1.0, 2560))
        assertEquals(640, PanelInputPolicy.pixel(0.5, 1280))
        // 0.999 映射到最后一列之内
        assertEquals(2558, PanelInputPolicy.pixel(0.9995, 2560))
        assertNull("源尺寸未知（0）必须拒映射", PanelInputPolicy.pixel(0.5, 0))
        assertNull(PanelInputPolicy.pixel(1.5, 100))
    }

    @Test
    fun `map 把归一化请求映成像素命令`() {
        val tap = PanelInputPolicy.map(req("tap", x = 0.5, y = 0.25), 2560, 2064)!!
        assertEquals("tap", tap.action)
        assertEquals(1280, tap.x)
        assertEquals(516, tap.y)
        val swipe = PanelInputPolicy.map(
            req("swipe", x1 = 0.0, y1 = 0.0, x2 = 1.0, y2 = 1.0, durationMs = 300),
            2560, 2064,
        )!!
        assertEquals(0, swipe.x1)
        assertEquals(0, swipe.y1)
        assertEquals(2559, swipe.x2)
        assertEquals(2063, swipe.y2)
        assertEquals(300, swipe.durationMs)
        // back/home 无坐标
        assertNull(PanelInputPolicy.map(req("back"), 0, 0)?.x)
        // 尺寸未知 → null
        assertNull(PanelInputPolicy.map(req("tap", x = 0.5, y = 0.5), 0, 0))
    }

    @Test
    fun `parse 只认自己写出的扁平 JSON，解析不出 action 回 null`() {
        val parsed = PanelInputPolicy.parse("""{"tab":"t","action":"tap","x":0.5,"y":0.25}""")!!
        assertEquals("tap", parsed.action)
        assertEquals(0.5, parsed.x!!, 0.0)
        assertEquals(0.25, parsed.y!!, 0.0)
        // 指数记数：1e-7 这种合法 JSON 数字也要能读
        val tiny = PanelInputPolicy.parse("""{"action":"tap","x":1e-7,"y":2.5E-3}""")!!
        assertEquals(1e-7, tiny.x!!, 1e-15)
        assertEquals(2.5e-3, tiny.y!!, 1e-15)
        // 会话 id 随 body 进来（端点对账用）
        val withSession = PanelInputPolicy.parse("""{"action":"tap","sessionId":"s-1","x":0.5,"y":0.5}""")!!
        assertEquals("s-1", withSession.sessionId)
        val swipe = PanelInputPolicy.parse(
            """{"action":"swipe","x1":0,"y1":0,"x2":1,"y2":1,"durationMs":300}""",
        )!!
        assertEquals(300, swipe.durationMs)
        assertNull(PanelInputPolicy.parse("{}"))
        assertNull(PanelInputPolicy.parse("not json"))
    }

    @Test
    fun `限速每秒 20 次，滑动窗口与新会话清账`() {
        val limiter = PanelInputPolicy.RateLimiter()
        repeat(20) { i -> assertTrue("第 ${i + 1} 次应放行", limiter.tryAcquire(1000L)) }
        assertFalse("同窗口第 21 次拒绝", limiter.tryAcquire(1099L))
        assertTrue("窗口滑过即恢复", limiter.tryAcquire(2000L))
        // 新会话：清账从零计数
        limiter.reset()
        repeat(20) { assertTrue(limiter.tryAcquire(2100L)) }
    }
}
