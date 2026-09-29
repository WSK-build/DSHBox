package interlock.relay.core.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「参数里不许出现宿主自身包名」这条安全判据的覆盖面。
 *
 * 它按形状扫整棵参数树（扁平、嵌套、数组、数组套数组都要拒）：
 * 只测能过的那一侧，等于给这条守卫留一个静默缩小的口子。
 */
class SelfTargetGuardTest {

    private val self = "com.example.app"

    private fun arg(vararg pairs: Pair<String, Any?>): JSONObject =
        JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }

    @Test
    fun `扁平参数命中时报出键名`() {
        assertEquals("package", selfTargetArg(arg("package" to self), self))
    }

    @Test
    fun `嵌套对象报出完整键路径`() {
        val args = arg("selector" to arg("text" to "确定", "package" to self))
        assertEquals("selector.package", selfTargetArg(args, self))
    }

    @Test
    fun `数组元素带下标`() {
        val args = arg("args" to JSONArray().put("dumpsys").put(self))
        assertEquals("args[1]", selfTargetArg(args, self))
    }

    @Test
    fun `数组套数组也要扫得到`() {
        // JSONArray 分支必须对内层数组递归下扫：只认字符串与对象时，
        // 内层数组里的宿主包名扫不到，等于多套一层就绕过守卫。
        val inner = JSONArray().put(self)
        val args = arg("args" to JSONArray().put(inner))
        assertEquals("args[0][0]", selfTargetArg(args, self))
    }

    @Test
    fun `对象数组里的对象字段也能报出路径`() {
        val args = arg(
            "targets" to JSONArray().put(arg("name" to "a", "package" to arg("owner" to self))),
        )
        assertEquals("targets[0].package.owner", selfTargetArg(args, self))
    }

    @Test
    fun `空键名不能交回一个空路径`() {
        // 回包写 arg "" 时，调用方看不出撞的是哪一个参数，而这条路径是那个回包唯一的信息量。
        val args = JSONObject().put("", self)
        assertEquals("<no-name>", selfTargetArg(args, self))
    }

    @Test
    fun `不相干的值一律放行`() {
        assertNull(selfTargetArg(arg("package" to "com.other.app"), self))
        // 前缀相同不是同一个包名：按包含匹配会把 com.example.app.extra 一起拒掉。
        assertNull(selfTargetArg(arg("package" to "$self.extra"), self))
        assertNull(selfTargetArg(arg("count" to 3, "flag" to true, "missing" to JSONObject.NULL), self))
        assertNull(selfTargetArg(arg("package" to arg("nested" to "com.other.app")), self))
    }

    @Test
    fun `命中多处时只报第一处且不抛异常`() {
        val args = arg("a" to self, "b" to self)
        assertEquals("a", selfTargetArg(args, self))
    }
}
