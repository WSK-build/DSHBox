package interlock.relay.core.exec.direct

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板表的判据在 JVM 侧钉住：这张表决定"助手能让系统做哪几件事"，
 * 越界与未知模板必须在落到 Intent 之前就被拒掉。
 */
class IntentTemplatesTest {

    private fun outcome(args: JSONObject) = IntentTemplates.validate(args)

    private fun ok(args: JSONObject): IntentTemplates.Params {
        val result = outcome(args)
        assertTrue("expected Ok, got $result", result is IntentTemplates.Outcome.Ok)
        return (result as IntentTemplates.Outcome.Ok).params
    }

    private fun reason(args: JSONObject): String {
        val result = outcome(args)
        assertTrue("expected Bad, got $result", result is IntentTemplates.Outcome.Bad)
        return (result as IntentTemplates.Outcome.Bad).reason
    }

    @Test
    fun `alarm takes hour and minute as given`() {
        val params = ok(JSONObject().put("template", "alarm.set").put("hour", 23).put("minute", 40))
        assertEquals(IntentTemplates.ALARM_SET, params.template)
        assertEquals(23, params.hour)
        assertEquals(40, params.minute)
    }

    @Test
    fun `alarm bounds are refused rather than wrapped`() {
        // 25 点被折成 1 点之后，用户批的是卡片上那个 25:00，落进系统的是另一天的 1 点。
        assertTrue(reason(JSONObject().put("template", "alarm.set").put("hour", 25).put("minute", 0)).contains("0..23"))
        assertTrue(reason(JSONObject().put("template", "alarm.set").put("hour", 7).put("minute", 60)).contains("0..59"))
        assertTrue(reason(JSONObject().put("template", "alarm.set").put("hour", -1).put("minute", 0)).isNotBlank())
    }

    @Test
    fun `alarm needs both hour and minute`() {
        assertTrue(reason(JSONObject().put("template", "alarm.set").put("minute", 5)).contains("hour"))
        assertTrue(reason(JSONObject().put("template", "alarm.set").put("hour", 5)).contains("minute"))
    }

    @Test
    fun `timer length is bounded`() {
        assertEquals(90, ok(JSONObject().put("template", "timer.set").put("length", 90)).lengthSeconds)
        assertTrue(reason(JSONObject().put("template", "timer.set").put("length", 0)).contains("seconds"))
        assertTrue(reason(JSONObject().put("template", "timer.set").put("length", 90000)).contains("seconds"))
        assertTrue(reason(JSONObject().put("template", "timer.set")).contains("length"))
    }

    @Test
    fun `unknown template lists what the table accepts`() {
        val reason = reason(JSONObject().put("template", "alarm.delete").put("hour", 1).put("minute", 1))
        assertTrue(reason.startsWith("unknown template: alarm.delete"))
        assertTrue(reason.contains("alarm.set"))
        // 表里没有"删除"这类入口，也不给任何路径把它加进来。
        assertTrue(!IntentTemplates.names.any { it.contains("delete") || it.contains("remove") })
    }

    @Test
    fun `missing template is a shape error`() {
        assertTrue(reason(JSONObject().put("hour", 7)).contains("template"))
    }

    @Test
    fun `message length is capped`() {
        val long = "a".repeat(101)
        assertTrue(reason(JSONObject().put("template", "alarm.set").put("hour", 7).put("minute", 0).put("message", long))
            .contains("100"))
        assertEquals("起床", ok(JSONObject().put("template", "alarm.set").put("hour", 7).put("minute", 0).put("message", "起床")).message)
    }

    @Test
    fun `the two show templates need no arguments`() {
        assertEquals(IntentTemplates.ALARM_SHOW, ok(JSONObject().put("template", "alarm.show")).template)
        assertEquals(IntentTemplates.TIMER_SHOW, ok(JSONObject().put("template", "timer.show")).template)
    }

    @Test
    fun `settings page must be one of the named panels`() {
        assertEquals("wifi", ok(JSONObject().put("template", "settings.open").put("page", "wifi")).page)
        assertEquals("nfc", ok(JSONObject().put("template", "settings.open").put("page", "NFC")).page)
        assertTrue(reason(JSONObject().put("template", "settings.open")).contains("page"))
        // 表外的页面名不能靠"猜一个 action 常量"兜底：那等于把自由填写放回来。
        assertTrue(reason(JSONObject().put("template", "settings.open").put("page", "airplane"))
            .contains("airplane"))
    }

    @Test
    fun `app info needs a well formed package`() {
        assertEquals("com.x.y", ok(JSONObject().put("template", "app.info").put("package", "com.x.y")).packageName)
        assertTrue(reason(JSONObject().put("template", "app.info")).contains("package"))
        assertTrue(reason(JSONObject().put("template", "app.info").put("package", "has spaces")).isNotBlank())
        assertTrue(reason(JSONObject().put("template", "app.info").put("package", "com.x/y")).isNotBlank())
    }

    @Test
    fun `dial accepts dial-pad characters only`() {
        assertEquals("+86 138-0000", ok(JSONObject().put("template", "dial").put("number", "+86 138-0000")).number)
        // 其余字符会改 `tel:` 之外的东西：问号开查询串、with 分号开别的 scheme 参数。
        assertTrue(reason(JSONObject().put("template", "dial").put("number", "138;rm -rf")).isNotBlank())
        assertTrue(reason(JSONObject().put("template", "dial").put("number", "http://x")).isNotBlank())
        assertTrue(reason(JSONObject().put("template", "dial").put("number", "1".repeat(33))).isNotBlank())
        assertTrue(reason(JSONObject().put("template", "dial")).contains("number"))
    }

    @Test
    fun `web open takes only http and https`() {
        assertEquals(
            "https://example.com/a?b=1",
            ok(JSONObject().put("template", "web.open").put("url", "https://example.com/a?b=1")).url,
        )
        // 这四个 scheme 能把"打开网页"变成读私有文件或调起任意组件。
        listOf("file:///sdcard/x", "content://mms", "intent:#Intent", "javascript:alert(1)").forEach { bad ->
            assertTrue("$bad must be refused", reason(JSONObject().put("template", "web.open").put("url", bad)).isNotBlank())
        }
        assertTrue(reason(JSONObject().put("template", "web.open").put("url", "https://a b")).isNotBlank())
        assertTrue(reason(JSONObject().put("template", "web.open").put("url", "https://" + "a".repeat(512))).contains("512"))
    }

    @Test
    fun `the table holds no destructive or alarm-cancelling entry`() {
        // 删除类与"停掉正在响的闹钟"都不进表：后者对单次闹钟等于永久关闭。
        val banned = listOf("delete", "remove", "dismiss", "snooze", "clear", "uninstall", "stop")
        assertTrue(IntentTemplates.names.none { name -> banned.any { name.contains(it) } })
    }
}
