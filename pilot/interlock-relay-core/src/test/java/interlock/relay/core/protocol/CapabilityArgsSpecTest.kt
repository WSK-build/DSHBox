package interlock.relay.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公共预校验表的用例。这张表在闸门之前运行，被打回的调用不占用户一次确认也不占
 * 执行配额；表里的键集必须与三张后端键表的并集/交集逐格对齐——预校验拒掉后端本会
 * 接受的调用，与放过一条注定失败的调用，是同一类错误的两面。
 */
class CapabilityArgsSpecTest {

    private fun args(vararg pairs: Pair<String, Any?>): org.json.JSONObject {
        val json = org.json.JSONObject()
        pairs.forEach { (key, value) -> json.put(key, value) }
        return json
    }

    @Test
    fun missingCoordinateKeyIsRejectedBeforeTheGate() {
        assertEquals("missing arg: y", CapabilityArgsSpec.validate(CapabilityId.UI_TAP, args("x" to 12)))
        assertNull(CapabilityArgsSpec.validate(CapabilityId.UI_TAP, args("x" to 12, "y" to 34)))
    }

    /** `ui.tap` 只认点击形状：拿滑动的键名点这条能力，必须在预校验就被点名为未知键。 */
    @Test
    fun tapShapeIsNotASwipeShape() {
        val reason = CapabilityArgsSpec.validate(
            CapabilityId.UI_SWIPE,
            args("x" to 1, "y" to 2),
        )
        assertTrue(reason.orEmpty().startsWith("unknown arg: x"))
        assertTrue(reason.orEmpty().contains("fromX"))
    }

    @Test
    fun keywordQueryPassesTheShape() {
        assertNull(CapabilityArgsSpec.validate(CapabilityId.PKG_QUERY, args("keyword" to "relay")))
        assertNull(CapabilityArgsSpec.validate(CapabilityId.PKG_QUERY, args()))
    }

    @Test
    fun shellVerbIsRequired() {
        assertEquals(
            "missing arg: verb",
            CapabilityArgsSpec.validate(CapabilityId.SYS_SHELL, args("args" to org.json.JSONArray())),
        )
    }

    /** args 是数组：非数组会被后端当成空参处理，`screencap` 那类动词会静默成功。 */
    @Test
    fun shellArgsMustBeAnArray() {
        val reason = CapabilityArgsSpec.validate(
            CapabilityId.SYS_SHELL,
            args("verb" to "dumpsys", "args" to "battery"),
        )
        assertTrue(reason.orEmpty().startsWith("args must be an array"))
        val valid = org.json.JSONArray().put("battery")
        assertNull(
            CapabilityArgsSpec.validate(
                CapabilityId.SYS_SHELL,
                args("verb" to "dumpsys", "args" to valid),
            ),
        )
    }

    @Test
    fun waitForTimeoutMustBeNumeric() {
        assertNull(CapabilityArgsSpec.validate(CapabilityId.UI_WAIT_FOR, args("timeoutMs" to 1500)))
        val reason = CapabilityArgsSpec.validate(CapabilityId.UI_WAIT_FOR, args("timeoutMs" to "1500"))
        assertTrue(reason.orEmpty().startsWith("timeoutMs must be a number"))
    }

    @Test
    fun unknownKeysAreRejectedWithTheAcceptedList() {
        val reason = CapabilityArgsSpec.validate(
            CapabilityId.UI_TAP,
            args("x" to 1, "y" to 2, "z" to 3),
        )
        assertEquals("unknown arg: z — this call takes: x, y", reason)
    }

    /** 落点类键不是可以改对的拼写：措辞要把「执行面由用户偏好决定」这件事讲完。 */
    @Test
    fun displayTargetingKeyGetsThePolicyWording() {
        val reason = CapabilityArgsSpec.validate(
            CapabilityId.UI_TAP,
            args("x" to 1, "y" to 2, "display" to 1),
        )
        assertTrue(reason.orEmpty().contains("which display a call runs on is decided by the user's surface preference"))
    }

    /** 节点级目标的类型判据与 NodeSelector 同口径：对象、数字，缺一即拒。 */
    @Test
    fun nodeTargetTypesFollowTheSelectorRules() {
        assertNull(
            CapabilityArgsSpec.validate(
                CapabilityId.UI_CLICK,
                args("selector" to args("text" to "OK")),
            ),
        )
        assertNull(CapabilityArgsSpec.validate(CapabilityId.UI_CLICK, args("nodeId" to 4)))
        assertTrue(
            CapabilityArgsSpec.validate(CapabilityId.UI_CLICK, args("nodeId" to "4"))
                .orEmpty()
                .startsWith("nodeId must be a number"),
        )
        assertTrue(
            CapabilityArgsSpec.validate(CapabilityId.UI_CLICK, args("selector" to "OK"))
                .orEmpty()
                .startsWith("selector must be an object"),
        )
    }

    @Test
    fun scrollDirectionIsRequired() {
        assertEquals(
            "missing arg: direction",
            CapabilityArgsSpec.validate(CapabilityId.UI_SCROLL, args("nodeId" to 1)),
        )
        assertNull(
            CapabilityArgsSpec.validate(
                CapabilityId.UI_SCROLL,
                args("nodeId" to 1, "direction" to "forward", "times" to 2),
            ),
        )
    }

    /**
     * 注册表里每条能力都必须有预校验：漏一条的话，那条能力的拼错参数会重新退回
     * 「先过闸门、后被打回」的旧时序。判法是间接的——有表的能力对未知键必拒，
     * 没表的能力会放行，一个全键名外的探针就能把漏网的钓出来。
     */
    @Test
    fun everyRegistryCapabilityHasAPrevalidationSpec() {
        CapabilityId.entries.forEach { id ->
            val reason = CapabilityArgsSpec.validate(
                id,
                args("no_capability_takes_this_key" to true),
            )
            assertTrue("${id.wire} 缺少预校验条目", reason != null)
        }
    }
}
