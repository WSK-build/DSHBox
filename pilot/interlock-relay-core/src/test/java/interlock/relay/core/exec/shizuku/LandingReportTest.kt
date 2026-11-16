package interlock.relay.core.exec.shizuku

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动落点观察的回包写法（纯函数）：观察结果分开说，抓屏事实无论"启动造成"还是
 * "用户本来就在"都如实标真 —— 后者若被读成"后台面给不了落点"，调用侧就会再发
 * 一次前台启动，把已经在前面的目标重新启动一遍。
 */
class LandingReportTest {

    private val target = "com.target"

    private fun report(tops: Map<Int, String>?, before: Map<Int, String>? = null, display: Int = 7): JSONObject {
        val data = JSONObject()
        writeLandingReport(data, tops, display, target, before)
        return data
    }

    @Test
    fun `落在目标屏如实报核验成功`() {
        val data = report(mapOf(7 to target, 0 to "com.launcher"))
        assertTrue(data.getBoolean("verified"))
        assertEquals(7, data.getInt("landedOn"))
        assertEquals(target, data.getString("topOnTarget"))
        assertFalse(data.has("grabbedUserScreen"))
    }

    @Test
    fun `目标上了用户真屏标 grabbedUserScreen`() {
        val data = report(mapOf(0 to target, 7 to "com.other"), before = mapOf(0 to "com.launcher"))
        assertTrue(data.getBoolean("grabbedUserScreen"))
        assertFalse(data.getBoolean("verified"))
    }

    @Test
    fun `用户本来就在该 App 上也如实标真`() {
        val data = report(mapOf(0 to target, 7 to "com.other"), before = mapOf(0 to target))
        assertTrue("用户已在该 App：不能漏标，否则会被误判成确定性失败再启一次", data.getBoolean("grabbedUserScreen"))
        assertTrue(data.getString("note").contains("already"))
    }

    @Test
    fun `观察不到不冒充任何结论`() {
        val data = report(null)
        assertFalse(data.getBoolean("verified"))
        assertEquals("failed", data.getString("landingCheck"))
        assertFalse(data.has("grabbedUserScreen"))
    }

    @Test
    fun `目标屏停着别的活动只报所见不编结论`() {
        val data = report(mapOf(7 to "com.other", 0 to "com.launcher"), before = mapOf(7 to "com.other", 0 to "com.launcher"))
        assertFalse(data.getBoolean("verified"))
        assertEquals("com.other", data.getString("topOnTarget"))
        assertFalse(data.has("grabbedUserScreen"))
        assertNull(data.optJSONObject("landedOn"))
    }
}
