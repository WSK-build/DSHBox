package interlock.relay.core.interlock

import interlock.relay.core.protocol.CapabilityId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判据矩阵（纯函数穷举）：哪些调用参数算「明确点名了目标包名」、
 * 执行场景三档判定（命中/未命中/读不到）怎么落。
 */
class ProtectedTargetMatrixTest {

    // ── protectedTarget：入口判据 ──

    @Test
    fun `带 package 的三条按顶层包名点名`() {
        assertEquals(
            "com.bank",
            protectedTarget(CapabilityId.APP_LAUNCH, JSONObject().put("package", "com.bank")),
        )
        assertEquals(
            "com.bank",
            protectedTarget(CapabilityId.APP_STOP, JSONObject().put("package", "com.bank")),
        )
        assertEquals(
            "com.bank",
            protectedTarget(CapabilityId.APPOPS_SET, JSONObject().put("package", "com.bank")),
        )
    }

    @Test
    fun `节点级十条按 selector package 点名`() {
        val args = JSONObject().put(
            "selector", JSONObject().put("package", "com.bank").put("text", "pay"),
        )
        for (id in listOf(
            CapabilityId.UI_NODE, CapabilityId.UI_WAIT_FOR,
            CapabilityId.UI_CLICK, CapabilityId.UI_LONG_CLICK, CapabilityId.UI_SELECT,
            CapabilityId.UI_DISMISS, CapabilityId.UI_SCROLL, CapabilityId.UI_SET_VALUE,
            CapabilityId.UI_SET_PROGRESS, CapabilityId.UI_IME_ACTION,
        )) {
            assertEquals("capability ${id.wire} 应按 selector.package 点名", "com.bank", protectedTarget(id, args))
        }
    }

    @Test
    fun `ui snapshot 不按 selector package 点名`() {
        // ui.snapshot 的键表（两侧 ARG_SPECS 均为空集）不接受任何参数，selector 永远
        // 不可达，因此不在 NODE_TARGET_CAPABILITIES 里；快照的名单判定在执行场景按
        // 树根包名做（见 A11yBackend）。
        val args = JSONObject().put(
            "selector", JSONObject().put("package", "com.bank"),
        )
        assertNull(protectedTarget(CapabilityId.UI_SNAPSHOT, args))
        assertNull(protectedTarget(CapabilityId.UI_SNAPSHOT, JSONObject()))
    }

    @Test
    fun `selector 没有 package 时不算点名`() {
        val args = JSONObject().put("selector", JSONObject().put("text", "pay"))
        assertNull(protectedTarget(CapabilityId.UI_CLICK, args))
        assertNull(protectedTarget(CapabilityId.UI_CLICK, JSONObject()))
    }

    @Test
    fun `空包名不算点名`() {
        assertNull(protectedTarget(CapabilityId.APP_LAUNCH, JSONObject().put("package", "")))
        assertNull(protectedTarget(CapabilityId.APP_LAUNCH, JSONObject()))
    }

    @Test
    fun `坐标与采集类不带包名参数不算点名`() {
        assertNull(protectedTarget(CapabilityId.UI_TAP, JSONObject().put("x", 1).put("y", 2)))
        assertNull(protectedTarget(CapabilityId.UI_SWIPE, JSONObject().put("fromX", 1)))
        assertNull(protectedTarget(CapabilityId.UI_TEXT, JSONObject().put("text", "hi")))
        assertNull(protectedTarget(CapabilityId.UI_KEY, JSONObject().put("key", "back")))
        assertNull(protectedTarget(CapabilityId.SCREEN_CAPTURE, JSONObject()))
        assertNull(protectedTarget(CapabilityId.SCREEN_RECORD, JSONObject().put("seconds", 3)))
    }

    @Test
    fun `安装的包名在入口读不到（解析在前端之后）`() {
        assertNull(protectedTarget(CapabilityId.PKG_INSTALL, JSONObject().put("upload", "app.apk")))
    }

    @Test
    fun `完全 shell 不解析命令`() {
        // 任意命令通道不受名单限制：命令文本里写什么包名都不算点名。
        assertNull(
            protectedTarget(
                CapabilityId.SYS_SHELL,
                JSONObject().put("verb", "am").put("args", org.json.JSONArray().put("start").put("com.bank")),
            ),
        )
        assertNull(
            protectedTarget(
                CapabilityId.SYS_SHELL_FULL,
                JSONObject().put("command", "am start -n com.bank/.MainActivity"),
            ),
        )
    }

    @Test
    fun `读数据类能力不按 package 点名`() {
        // pkg.query（keyword 键）/ notify.post / sys.intent 的 package 键是过滤条件或
        // 非操作对象，不是"要读的目标"。
        assertNull(protectedTarget(CapabilityId.PKG_QUERY, JSONObject().put("package", "com.bank")))
        assertNull(protectedTarget(CapabilityId.NOTIFY_POST, JSONObject().put("package", "com.bank")))
        assertNull(protectedTarget(CapabilityId.SYS_INTENT, JSONObject().put("template", "app.info").put("package", "com.bank")))
    }

    @Test
    fun `notify read 带 package 按顶层包名点名`() {
        // notify.read 的 package 是"只看这个包的通知"：点名的就是读取对象本身，
        // 受保护包的通知一条都不能交出去，入口先拒。
        assertEquals(
            "com.bank",
            protectedTarget(CapabilityId.NOTIFY_READ, JSONObject().put("package", "com.bank")),
        )
    }

    @Test
    fun `notify read 不带 package 的读全部不算点名`() {
        // 整条拒绝会把用户允许读的其它应用一并挡掉，改由 Direct 后端按名单过滤
        // （filterProtectedEntries）。
        assertNull(protectedTarget(CapabilityId.NOTIFY_READ, JSONObject()))
        assertNull(protectedTarget(CapabilityId.NOTIFY_READ, JSONObject().put("package", "")))
        assertNull(protectedTarget(CapabilityId.NOTIFY_READ, JSONObject().put("limit", 3)))
    }

    // ── protectedVerdict：执行场景三档 ──

    @Test
    fun `名单为空一律放行`() {
        assertEquals(ProtectedVerdict.Pass, protectedVerdict(emptySet(), "com.bank"))
        assertEquals(ProtectedVerdict.Pass, protectedVerdict(emptySet(), null))
    }

    @Test
    fun `名单非空而读不到按 fail-closed 处理`() {
        assertEquals(
            ProtectedVerdict.Unconfirmable,
            protectedVerdict(setOf("com.bank"), null),
        )
        assertEquals(
            ProtectedVerdict.Unconfirmable,
            protectedVerdict(setOf("com.bank"), ""),
        )
    }

    @Test
    fun `命中与未命中`() {
        assertEquals(
            ProtectedVerdict.Hit("com.bank"),
            protectedVerdict(setOf("com.bank", "com.wallet"), "com.bank"),
        )
        assertEquals(
            ProtectedVerdict.Pass,
            protectedVerdict(setOf("com.bank"), "com.other"),
        )
    }

    @Test
    fun `精确相等不认前缀`() {
        assertTrue(
            protectedVerdict(setOf("com.bank"), "com.bank.extra") is ProtectedVerdict.Pass,
        )
    }
}
