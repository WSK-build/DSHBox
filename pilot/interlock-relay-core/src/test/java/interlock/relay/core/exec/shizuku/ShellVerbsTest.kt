package interlock.relay.core.exec.shizuku

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动词表是这条能力唯一的把关处，所以在 JVM 侧逐条钉住：表外动词、元字符、
 * 删除一类与"要改系统但还没开关"必须各回各的话。
 */
class ShellVerbsTest {

    private fun plan(verb: String, vararg args: String) =
        ShellVerbs.plan(JSONObject().put("verb", verb).put("args", JSONArray().apply { args.forEach { put(it) } }))

    private fun command(verb: String, vararg args: String): String =
        (plan(verb, *args) as ShellVerbs.Plan.Run).command

    @Test
    fun `read-only verbs build quoted commands`() {
        assertEquals("dumpsys 'battery'", command("dumpsys", "battery"))
        assertEquals("pm 'list' 'packages'", command("pm", "list", "packages"))
        assertEquals("settings 'get' 'system' 'volume_music'", command("settings", "get", "system", "volume_music"))
        assertEquals("getprop", command("getprop"))
        assertEquals("appops 'get' 'com.example.app'", command("appops", "get", "com.example.app"))
        assertEquals("wm 'size'", command("wm", "size"))
    }

    @Test
    fun `screencap is read-only but takes no args`() {
        assertEquals("screencap", command("screencap"))
        assertTrue((plan("screencap", "-d", "0") as ShellVerbs.Plan.Bad).reason.contains("option flags"))
        assertTrue((plan("screencap", "png") as ShellVerbs.Plan.Bad).reason.contains("screencap takes no args"))
    }

    @Test
    fun `raw command strings and metacharacters never become a command`() {
        // 一条自由填写的命令串等于让一次批准替所有闸门背书。
        assertTrue(plan("dumpsys; rm", "-rf", "/") is ShellVerbs.Plan.Bad)
        assertTrue(plan("dumpsys", "battery|wc") is ShellVerbs.Plan.Bad)
        assertTrue(plan("dumpsys", "battery\$(id)") is ShellVerbs.Plan.Bad)
        assertTrue(plan("settings", "get", "system", "-x; reboot") is ShellVerbs.Plan.Bad)
    }

    @Test
    fun `delete-class verbs are refused and not configurable`() {
        listOf("rm", "rmdir", "dd", "su", "sh", "pm_clear", "settings_delete").forEach {
            val reason = (ShellVerbs.plan(JSONObject().put("verb", it)) as ShellVerbs.Plan.Bad).reason
            assertTrue("$it: $reason", reason.contains("refused by policy"))
        }
        // 真正的把关是"表里没有"：只读集合与禁用集合不能有任何交集。
        assertTrue(ShellVerbs.readOnlyNames.none { it in ShellVerbs.FORBIDDEN })
    }

    @Test
    fun `system-changing verbs ask for their own switch`() {
        listOf("input", "svc", "media").forEach { verb ->
            val plan = plan(verb, "1")
            assertTrue("$verb -> $plan", plan is ShellVerbs.Plan.NeedsOptIn)
            assertEquals(verb, (plan as ShellVerbs.Plan.NeedsOptIn).verb)
        }
    }

    @Test
    fun `unknown verb lists the read-only set it accepts`() {
        val reason = (plan("nope") as ShellVerbs.Plan.Bad).reason
        assertTrue(reason.startsWith("unknown verb: nope"))
        assertTrue(reason.contains("dumpsys"))
    }

    @Test
    fun `per-verb argument shapes are enforced`() {
        // dumpsys 不带服务名会把整机状态倒出来，pm 只认 list 一族。
        assertTrue((plan("dumpsys") as ShellVerbs.Plan.Bad).reason.contains("service name"))
        // 只读组不收选项旗标，也不收"顺手多一个参数"：那两条都能把查询变成写。
        assertTrue((plan("dumpsys", "batterystats", "--reset") as ShellVerbs.Plan.Bad).reason.contains("option flags"))
        assertTrue((plan("dumpsys", "deviceidle", "force-idle") as ShellVerbs.Plan.Bad).reason.contains("exactly one"))
        assertTrue((plan("wm", "size", "reset") as ShellVerbs.Plan.Bad).reason.contains("query"))
        assertTrue((plan("pm", "install", "/data/local/tmp/x.apk") as ShellVerbs.Plan.Bad).reason.contains("list"))
        assertTrue((plan("settings", "put", "system", "x") as ShellVerbs.Plan.Bad).reason.contains("get"))
        assertTrue((plan("appops", "set", "com.example.app") as ShellVerbs.Plan.Bad).reason.contains("get"))
        assertTrue((plan("wm", "size", "1080x1920", "extra", "a", "b", "c", "d") as ShellVerbs.Plan.Bad).reason.contains("at most"))
    }
}
