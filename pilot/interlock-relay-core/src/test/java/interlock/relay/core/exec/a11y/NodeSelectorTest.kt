package interlock.relay.core.exec.a11y

import interlock.relay.core.exec.a11y.SelectorSpec.ByNodeId
import interlock.relay.core.exec.a11y.SelectorSpec.Invalid
import interlock.relay.core.exec.a11y.SelectorSpec.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节点规格的解析与匹配。
 *
 * 只跑 JVM：解析侧由 [JsonView] 的 Map 实现驱动，因为单测类路径上的 org.json 是平台桩
 * （方法体被换成默认返回值），真拿 `JSONObject` 来驱动只会读到「一个键都没有」；
 * 匹配侧由 [NodeView] 的手搓小树驱动，因为无障碍节点在这个运行时里取不出任何字段。
 * 两条替身都只喂数据、不含规则，所以被测的确实是生产要跑的那一份。
 */
class NodeSelectorTest {

    private class FakeNode(
        override val text: String? = null,
        override val contentDescription: String? = null,
        override val viewId: String? = null,
        override val className: String? = null,
        override val packageName: String? = null,
        override val siblingIndex: Int = 0,
        private val kids: List<FakeNode> = emptyList(),
    ) : NodeView {
        override val children: List<NodeView> get() = kids
    }

    private class FakeArgs(private val values: Map<String, Any?>) : JsonView {
        override val keys: Set<String> get() = values.keys
        override fun has(key: String): Boolean = values.containsKey(key)
        override fun string(key: String): String? = values[key] as? String
        override fun number(key: String): Number? = values[key] as? Number

        @Suppress("UNCHECKED_CAST")
        override fun objectValue(key: String): JsonView? =
            (values[key] as? Map<String, Any?>)?.let { FakeArgs(it) }
    }

    private fun args(vararg pairs: Pair<String, Any?>) = FakeArgs(mapOf(*pairs))

    /** 坏输入的拒绝文案：测试断言的就是助手实际看到的这句话。 */
    private fun rejection(args: JsonView): String {
        val result = NodeSelector.parse(args)
        assertTrue("应回 Invalid，实际 $result", result is Invalid)
        return (result as Invalid).reason
    }

    private fun selectorRejection(vararg conditions: Pair<String, Any?>): String =
        rejection(args(NodeSelector.KEY_SELECTOR to mapOf(*conditions)))

    /** 键名沿用 JSON 侧那一套，测试里不出现第二份写法。 */
    private fun spec(vararg conditions: Pair<String, Any?>): Selector {
        val given = conditions.toMap()
        return Selector(
            text = given[NodeSelector.KEY_TEXT] as? String,
            contentDescription = given[NodeSelector.KEY_DESC] as? String,
            viewId = given[NodeSelector.KEY_ID] as? String,
            className = given[NodeSelector.KEY_CLASS] as? String,
            index = given[NodeSelector.KEY_INDEX] as? Int,
            packageHint = given[NodeSelector.KEY_PACKAGE] as? String,
        )
    }

    @Test
    fun bareNodeIdResolvesToAnIdLookup() {
        val result = NodeSelector.parse(args(NodeSelector.KEY_NODE_ID to 7))
        assertTrue("应回 ByNodeId，实际 $result", result is ByNodeId)
        assertEquals(7, (result as ByNodeId).nodeId)
    }

    /** 每个坏形状都点名 `nodeId`：助手只看得到回包文案，「改脚本」这条路才走得到。 */
    @Test
    fun malformedNodeIdIsRejectedByName() {
        listOf(
            NodeSelector.KEY_NODE_ID to "7",
            NodeSelector.KEY_NODE_ID to 1.5,
            NodeSelector.KEY_NODE_ID to -1,
            NodeSelector.KEY_NODE_ID to NodeSelector.MAX_NODES,
        ).forEach { bad ->
            val reason = rejection(args(bad))
            assertTrue("$bad -> $reason", reason.contains(NodeSelector.KEY_NODE_ID))
        }
        val mixed = rejection(
            args(
                NodeSelector.KEY_NODE_ID to 1,
                NodeSelector.KEY_SELECTOR to mapOf(NodeSelector.KEY_TEXT to "Save"),
            ),
        )
        assertTrue(mixed, mixed.contains(NodeSelector.KEY_NODE_ID))
        assertTrue(mixed, mixed.contains(NodeSelector.KEY_SELECTOR))
    }

    @Test
    fun selectorCarriesEveryAcceptedKey() {
        val result = NodeSelector.parse(
            args(
                NodeSelector.KEY_SELECTOR to mapOf(
                    NodeSelector.KEY_TEXT to "Save",
                    NodeSelector.KEY_DESC to "保存按钮",
                    NodeSelector.KEY_ID to "com.shop:id/save",
                    NodeSelector.KEY_CLASS to "android.widget.Button",
                    NodeSelector.KEY_INDEX to 2,
                    NodeSelector.KEY_PACKAGE to "com.shop",
                ),
            ),
        )
        assertEquals(
            Selector(
                text = "Save",
                contentDescription = "保存按钮",
                viewId = "com.shop:id/save",
                className = "android.widget.Button",
                index = 2,
                packageHint = "com.shop",
            ),
            (result as Parsed).selector,
        )
    }

    /** 全空规格解析处被拒、匹配处也不命中：两条路都不能退化成「树上任意节点」。 */
    @Test
    fun emptyOrUnconstrainedSelectorNeverMatches() {
        val reason = selectorRejection()
        SELECTOR_KEY_NAMES.forEach { assertTrue("$it 应进接受清单: $reason", reason.contains(it)) }
        val tree = FakeNode(kids = listOf(FakeNode(text = "Delete")))
        assertFalse(NodeSelector.matches(tree, Selector()))
        assertTrue(NodeSelector.findAll(tree, Selector(), allowContains = true).isEmpty())
    }

    /** 规格里的键只有一份白名单，内层与外层都要点名拒。 */
    @Test
    fun unknownKeysAreRejectedAndNamed() {
        val inside = selectorRejection("tex" to "Save")
        assertTrue(inside, inside.contains("tex"))
        SELECTOR_KEY_NAMES.forEach { assertTrue("$it 应进接受清单: $inside", inside.contains(it)) }
        val outside = rejection(args("node" to mapOf(NodeSelector.KEY_TEXT to "Save")))
        assertTrue(outside, outside.contains("node"))
        assertTrue(outside, outside.contains(NodeSelector.KEY_SELECTOR))
        assertTrue(outside, outside.contains(NodeSelector.KEY_NODE_ID))
    }

    /** 空串、数字、布尔、null 都不放行：悄悄替脚本转换一次，就是把误点留到最后那一步。 */
    @Test
    fun blankOrNonStringTextConditionIsRejected() {
        listOf(
            NodeSelector.KEY_TEXT to "   ",
            NodeSelector.KEY_TEXT to 5,
            NodeSelector.KEY_DESC to true,
            NodeSelector.KEY_ID to null,
            NodeSelector.KEY_CLASS to 1.5,
        ).forEach { (key, value) ->
            val reason = selectorRejection(key to value)
            assertTrue("$key=$value -> $reason", reason.contains("${NodeSelector.KEY_SELECTOR}.$key"))
            assertTrue(reason, reason.contains("non-empty string"))
        }
    }

    @Test
    fun indexMustBeAWholeNonNegativeNumber() {
        listOf("2", 1.5, true, -1, NodeSelector.MAX_NODES).forEach { bad ->
            val reason = selectorRejection(NodeSelector.KEY_INDEX to bad)
            assertTrue("$bad -> $reason", reason.contains("${NodeSelector.KEY_SELECTOR}.${NodeSelector.KEY_INDEX}"))
        }
        val zero = NodeSelector.parse(args(NodeSelector.KEY_SELECTOR to mapOf(NodeSelector.KEY_INDEX to 0)))
        assertTrue(zero.toString(), zero is Parsed)
    }

    @Test
    fun missingSpecIsRejectedWithBothAcceptedShapes() {
        val reason = rejection(args())
        assertTrue(reason, reason.contains(NodeSelector.KEY_SELECTOR))
        assertTrue(reason, reason.contains(NodeSelector.KEY_NODE_ID))
    }

    /** 精确目标排在模糊候选之后也要赢：兜底只在整棵树都没有精确命中时才轮到。 */
    @Test
    fun exactTextMatchWinsOverAnEarlierFuzzyCandidate() {
        val tree = FakeNode(
            kids = listOf(
                FakeNode(text = "Save note to Drive", siblingIndex = 0),
                FakeNode(text = "Save", siblingIndex = 1),
            ),
        )
        val hits = NodeSelector.findAll(tree, spec(NodeSelector.KEY_TEXT to "Save"), allowContains = true)
        assertEquals(1, hits.size)
        assertEquals(2, hits.first().nodeId)
        assertEquals(MatchMode.EXACT, hits.first().mode)
    }

    /** 没点名允许兜底就一个字都不松；兜底命中要带着口径回给调用方。 */
    @Test
    fun containsFallbackOnlyRunsOnAnExplicitOptIn() {
        val tree = FakeNode(kids = listOf(FakeNode(text = "Delete the backup copy")))
        val only = spec(NodeSelector.KEY_TEXT to "delete")
        assertTrue(NodeSelector.findAll(tree, only).isEmpty())
        val fuzzy = NodeSelector.findAll(tree, only, allowContains = true)
        assertEquals(1, fuzzy.size)
        assertEquals(MatchMode.CONTAINS, fuzzy.first().mode)
    }

    /** 「第二个删除按钮」靠同级序号定位，而不是靠助手再猜一次坐标。 */
    @Test
    fun indexConditionPicksTheSiblingByPosition() {
        val tree = FakeNode(
            kids = listOf(
                FakeNode(text = "Delete", siblingIndex = 0),
                FakeNode(text = "Delete", siblingIndex = 1),
            ),
        )
        val second = NodeSelector.findAll(tree, spec(NodeSelector.KEY_TEXT to "Delete", NodeSelector.KEY_INDEX to 1))
        assertEquals(listOf(2), second.map { it.nodeId })
        val none = NodeSelector.findAll(tree, spec(NodeSelector.KEY_TEXT to "Delete", NodeSelector.KEY_INDEX to 5))
        assertTrue(none.isEmpty())
    }

    /** 非文本条件只在分隔符处整体相等，也不吃兜底：短名撞进长名就是又一次误命中。 */
    @Test
    fun idClassAndPackageConditionsMatchWholeSegmentsOnly() {
        val node = FakeNode(viewId = "com.shop:id/save_note", className = "android.widget.Button", packageName = "com.shop")
        listOf("com.shop:id/save_note", "id/save_note", "save_note").forEach {
            assertTrue(it, NodeSelector.matches(node, spec(NodeSelector.KEY_ID to it)))
        }
        listOf("save", "note", "com.shop").forEach {
            assertFalse(it, NodeSelector.matches(node, spec(NodeSelector.KEY_ID to it)))
        }
        assertTrue(NodeSelector.matches(node, spec(NodeSelector.KEY_CLASS to "Button")))
        assertFalse(NodeSelector.matches(node, spec(NodeSelector.KEY_CLASS to "utton")))
        assertTrue(NodeSelector.matches(node, spec(NodeSelector.KEY_PACKAGE to "com.shop")))
        assertFalse(NodeSelector.matches(node, spec(NodeSelector.KEY_PACKAGE to "shop")))
        // 兜底开给文本也不该波及包名。
        assertFalse(NodeSelector.matches(node, spec(NodeSelector.KEY_PACKAGE to "shop"), allowContains = true))
    }

    /**
     * 同一棵树两次遍历给出同一批号，且号是节点在树里的位置而不是命中列表里的次序，
     * 每个号都能原样找回那颗节点——否则 `ui.snapshot` 之后按号点就成了一次随机点击。
     */
    @Test
    fun nodeIdsAreStableAcrossTraversalsAndResolveBackById() {
        val tree = FakeNode(
            className = "android.widget.FrameLayout",
            kids = listOf(
                FakeNode(text = "Save", kids = listOf(FakeNode(text = "OK"))),
                FakeNode(text = "Cancel"),
            ),
        )
        val save = NodeSelector.findAll(tree, spec(NodeSelector.KEY_TEXT to "Save"))
        assertEquals(save, NodeSelector.findAll(tree, spec(NodeSelector.KEY_TEXT to "Save")))
        assertEquals(listOf(1), save.map { it.nodeId })
        assertEquals(1, save.first().depth)
        val root = NodeSelector.findAll(tree, spec(NodeSelector.KEY_CLASS to "FrameLayout"))
        assertEquals(listOf(0), root.map { it.nodeId })
        assertEquals(listOf(3), NodeSelector.findAll(tree, spec(NodeSelector.KEY_TEXT to "Cancel")).map { it.nodeId })
        save.forEach { assertEquals(it.node, NodeSelector.findById(tree, it.nodeId)) }
        assertNull(NodeSelector.findById(tree, NodeSelector.MAX_NODES))
        assertNull(NodeSelector.findById(tree, -1))
    }

    /** 上限之外不再走：节点号必须与快照的截断位置一致，否则号会指到另一颗节点上。 */
    @Test
    fun traversalStopsAtTheDepthAndNodeCaps() {
        fun wide(target: Int) = FakeNode(
            kids = (0..NodeSelector.MAX_NODES).map {
                FakeNode(text = if (it == target) "target" else "other", siblingIndex = it)
            },
        )
        // 根占 0 号，所以位置 598 那颗正好落在最后一个被访问的号上。
        assertEquals(
            listOf(NodeSelector.MAX_NODES - 1),
            NodeSelector.findAll(wide(NodeSelector.MAX_NODES - 2), spec(NodeSelector.KEY_TEXT to "target")).map { it.nodeId },
        )
        assertTrue(
            NodeSelector.findAll(wide(NodeSelector.MAX_NODES), spec(NodeSelector.KEY_TEXT to "target")).isEmpty(),
        )

        fun chain(depth: Int): FakeNode =
            if (depth == 0) FakeNode(text = "target") else FakeNode(kids = listOf(chain(depth - 1)))

        assertEquals(
            1,
            NodeSelector.findAll(chain(NodeSelector.MAX_DEPTH), spec(NodeSelector.KEY_TEXT to "target")).size,
        )
        assertTrue(
            NodeSelector.findAll(chain(NodeSelector.MAX_DEPTH + 2), spec(NodeSelector.KEY_TEXT to "target")).isEmpty(),
        )
    }

    private companion object {
        val SELECTOR_KEY_NAMES = listOf("text", "desc", "id", "class", "index", "package")
    }
}
