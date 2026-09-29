package interlock.relay.core.exec.a11y

import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * 一条节点规格：六个条件全可选，至少要有一个。
 *
 * 不给「全空即任意节点」这一档：全空规格命中的是遍历遇到的第一颗节点，而下一步就是
 * `performAction`，等于随机破坏。规格全空在解析处就被拒（见 [NodeSelector.parse]），
 * 直接构造出来的全空规格也一律不命中（见 [NodeSelector.matches]），两条路都不留缺口。
 */
data class Selector(
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val index: Int? = null,
    val packageHint: String? = null,
) {
    /** 一个条件都没有；[NodeSelector.matches] 对这种规格永不命中。 */
    val unconstrained: Boolean
        get() = text == null && contentDescription == null && viewId == null &&
            className == null && index == null && packageHint == null
}

/** 命中口径，随结果一起交回调用方：「按精确文本命中」与「按包含命中」要对助手说两句不同的话。 */
enum class MatchMode { EXACT, CONTAINS }

/**
 * 一次命中。
 *
 * [nodeId] 是节点在整棵树前序遍历里的序号，不是命中列表里的序号——`ui.snapshot` 输出的
 * nodes 数组下标用的是同一套规则（同样跳过空节点、同样的深度与节点数上限），助手才可能
 * 先读快照、再按 `nodeId` 指回同一颗节点。按命中次序编号会让两次查询给出的同一个节点
 * 拿到两个号，那一步就没法接上。
 */
data class NodeMatch(
    val node: NodeView,
    val nodeId: Int,
    val depth: Int,
    val mode: MatchMode,
)

/** 规格解析结果：三条互斥出口，没有 null——调用方拿到 null 只能猜是「没给」还是「给坏了」。 */
sealed class SelectorSpec {
    data class Parsed(val selector: Selector) : SelectorSpec()
    data class ByNodeId(val nodeId: Int) : SelectorSpec()

    /** [reason] 点名出问题的那个键，并列出该键接受的写法。 */
    data class Invalid(val reason: String) : SelectorSpec()
}

/** 节点在规格里可见的那几项，见 [AccessibilityNodeView]。 */
interface NodeView {
    val text: String?
    val contentDescription: String?
    val viewId: String?
    val className: String?
    val packageName: String?

    /** 在同级里的序号，`index` 条件比的是它。根节点的父在本树之外，序号无从判定，固定 0。 */
    val siblingIndex: Int

    /** 子节点，顺序即遍历顺序；没有子节点回空表。 */
    val children: List<NodeView>
}

/**
 * 无障碍节点的 [NodeView] 实现。
 *
 * 不 recycle：这些节点是从调用方手里那棵树上读来的，快照之后同一批引用可能还在用；
 * 自 Android 11 起 recycle 已是空操作，回收别人给的引用只会让上层拿到失效节点。
 * [siblingIndex] 由父视图构造子视图时填——节点自己问不出「我是第几个子」，只能从下它的父那里知道。
 */
class AccessibilityNodeView(
    val info: AccessibilityNodeInfo,
    override val siblingIndex: Int = 0,
) : NodeView {

    override val text: String? get() = info.text?.toString()
    override val contentDescription: String? get() = info.contentDescription?.toString()
    override val viewId: String? get() = info.viewIdResourceName
    override val className: String? get() = info.className?.toString()
    override val packageName: String? get() = info.packageName?.toString()

    // 系统可以在遍历中途给出 null 子节点（窗口正在拆），跳过它才对得上快照的下标。
    override val children: List<NodeView>
        get() = (0 until info.childCount).mapNotNull { index ->
            info.getChild(index)?.let { AccessibilityNodeView(it, index) }
        }
}

/** 命中节点回落到无障碍节点本身，调用方拿它去 `performAction`；不是无障碍树来的节点回 null。 */
fun NodeView.accessibilityNode(): AccessibilityNodeInfo? = (this as? AccessibilityNodeView)?.info

/**
 * 一层只读的对象视图，[NodeSelector.parse] 的全部输入面。
 *
 * 解析规则刻意不直接吃 org.json：单测跑在 JVM 上，那里的 org.json 是平台桩（方法体被换成
 * 返回默认值），拿 `JSONObject` 驱动解析只会得到「一个键都没有」，规则等于没测。真机侧走
 * [asJsonView]，测试侧走 Map 实现，同一份规则两条入口都跑得到。
 */
interface JsonView {
    val keys: Set<String>
    fun has(key: String): Boolean

    /** 键的字符串值：不是字符串回 null，不代它转换——`optString` 会把 true 变成 "1"。 */
    fun string(key: String): String?

    /** 键的数值：不是数字回 null。整数性由解析处判，这里不替脚本圆场。 */
    fun number(key: String): Number?

    /** 键的下层对象：不是对象回 null。 */
    fun objectValue(key: String): JsonView?
}

/** org.json 侧的 [JsonView]。 */
fun JSONObject.asJsonView(): JsonView = JSONObjectView(this)

private class JSONObjectView(private val source: JSONObject) : JsonView {
    override val keys: Set<String> get() = source.keys()?.asSequence()?.toSet() ?: emptySet()
    override fun has(key: String): Boolean = source.has(key)
    override fun string(key: String): String? = source.opt(key) as? String
    override fun number(key: String): Number? = source.opt(key) as? Number
    override fun objectValue(key: String): JsonView? = source.optJSONObject(key)?.asJsonView()
}

/**
 * 节点级操作的入口：把助手发来的那段 JSON 变成树上的某一颗节点。
 *
 * 坐标点击「回执成功而界面没动」是查不出来的，节点动作能查——前提是每次都选中正确的节点，
 * 因此选择这一步只做规格解析与一次有界遍历，不做任何手势、不读任何屏幕尺寸。
 * 规格来自沙盒，是不可信边界：未知键、空值、类型不对的值一律拒。
 */
object NodeSelector {

    const val KEY_SELECTOR = "selector"
    const val KEY_NODE_ID = "nodeId"
    const val KEY_TEXT = "text"
    const val KEY_DESC = "desc"
    const val KEY_ID = "id"
    const val KEY_CLASS = "class"
    const val KEY_INDEX = "index"
    const val KEY_PACKAGE = "package"

    /** 清单顺序即错误文案里的顺序，助手看到的写法与代码接受的写法必须是同一份。 */
    val SELECTOR_KEYS = setOf(KEY_TEXT, KEY_DESC, KEY_ID, KEY_CLASS, KEY_INDEX, KEY_PACKAGE)

    private val TOP_LEVEL_KEYS = setOf(KEY_SELECTOR, KEY_NODE_ID)

    /**
     * 遍历上限，与 `ui.snapshot` 采集侧同值。
     *
     * 同值不是巧合而是节点号能对上号的前提：快照排到第 599 个就停、查找却走到第 600 个命中，
     * 助手照快照发来的 nodeId 就会指到另一颗节点上。采集侧那两个常量是它私有伴生对象里的，
     * 取不到同一份，所以改一处必须同时改另一处。
     */
    const val MAX_NODES = 600
    const val MAX_DEPTH = 40

    /** 一次查询最多回几条：命中多条本身就是要报给助手的信息，不该被一整棵树的量淹没。 */
    const val DEFAULT_MATCH_LIMIT = 20

    private val CLASS_TAILS = arrayOf(".")

    private val ID_TAILS = arrayOf(":", "/")

    /** 真机入口：args 就是请求里那个 `args` 对象。 */
    fun parse(args: JSONObject): SelectorSpec = parse(args.asJsonView())

    /**
     * 规格解析，两条互斥形状：`{"selector":{...}}` 与裸 `{"nodeId":n}`。
     *
     * 每条拒绝都点名出问题的那个键并列出接受能力清单：错误文案是助手唯一看得见的说明书，
     * 「你把键名写错了」与「用户不同意」是两条相反的动作（改脚本 / 就此放弃），
     * 文案含糊一次，它就在那条错路上重试一整轮。
     */
    fun parse(args: JsonView): SelectorSpec {
        args.keys.firstOrNull { it !in TOP_LEVEL_KEYS }?.let {
            return SelectorSpec.Invalid("unknown key: $it, accepted keys: ${TOP_LEVEL_KEYS.joinToString()}")
        }
        if (args.has(KEY_NODE_ID)) {
            if (args.has(KEY_SELECTOR)) {
                return SelectorSpec.Invalid("$KEY_NODE_ID and $KEY_SELECTOR are mutually exclusive: send one shape")
            }
            val nodeId = integer(args, KEY_NODE_ID, MAX_NODES)
                ?: return SelectorSpec.Invalid(
                    "$KEY_NODE_ID must be a whole number in [0,$MAX_NODES) taken from ui.snapshot",
                )
            return SelectorSpec.ByNodeId(nodeId)
        }
        if (!args.has(KEY_SELECTOR)) {
            return SelectorSpec.Invalid(
                "no selector given: expected a \"$KEY_SELECTOR\" object or a \"$KEY_NODE_ID\" number, " +
                    "accepted keys: ${TOP_LEVEL_KEYS.joinToString()}",
            )
        }
        val spec = args.objectValue(KEY_SELECTOR)
            ?: return SelectorSpec.Invalid("$KEY_SELECTOR must be an object holding any of ${SELECTOR_KEYS.joinToString()}")
        return parseSelector(spec)
    }

    private fun parseSelector(spec: JsonView): SelectorSpec {
        spec.keys.firstOrNull { it !in SELECTOR_KEYS }?.let {
            return SelectorSpec.Invalid(
                "unknown key in $KEY_SELECTOR: $it, accepted keys: ${SELECTOR_KEYS.joinToString()}",
            )
        }
        if (spec.keys.isEmpty()) {
            return SelectorSpec.Invalid("empty $KEY_SELECTOR: give at least one of ${SELECTOR_KEYS.joinToString()}")
        }
        var text: String? = null
        var desc: String? = null
        var viewId: String? = null
        var className: String? = null
        var packageHint: String? = null
        var index: Int? = null
        for (key in spec.keys) {
            when (key) {
                KEY_TEXT -> text = spec.string(key)?.takeIf { it.isNotBlank() }
                    ?: return SelectorSpec.Invalid("$KEY_SELECTOR.$KEY_TEXT must be a non-empty string")
                KEY_DESC -> desc = spec.string(key)?.takeIf { it.isNotBlank() }
                    ?: return SelectorSpec.Invalid("$KEY_SELECTOR.$KEY_DESC must be a non-empty string")
                KEY_ID -> viewId = spec.string(key)?.takeIf { it.isNotBlank() }
                    ?: return SelectorSpec.Invalid("$KEY_SELECTOR.$KEY_ID must be a non-empty string")
                KEY_CLASS -> className = spec.string(key)?.takeIf { it.isNotBlank() }
                    ?: return SelectorSpec.Invalid("$KEY_SELECTOR.$KEY_CLASS must be a non-empty string")
                KEY_PACKAGE -> packageHint = spec.string(key)?.takeIf { it.isNotBlank() }
                    ?: return SelectorSpec.Invalid("$KEY_SELECTOR.$KEY_PACKAGE must be a non-empty string")
                KEY_INDEX -> index = integer(spec, key, MAX_NODES)
                    ?: return SelectorSpec.Invalid("$KEY_SELECTOR.$KEY_INDEX must be a whole number in [0,$MAX_NODES)")
            }
        }
        return SelectorSpec.Parsed(
            Selector(
                text = text,
                contentDescription = desc,
                viewId = viewId,
                className = className,
                index = index,
                packageHint = packageHint,
            ),
        )
    }

    /**
     * 节点是否命中规格。默认只认严格匹配，模糊兜底要调用方点名要。
     *
     * 默认值不敢给宽：兜底命中的是「长得像」的那个节点，而下一步可能是删除。
     * 「第二个删除按钮」这类指令必须由调用方明确决定允许兜底，并把口径原样报回助手。
     */
    fun matches(node: NodeView, selector: Selector, allowContains: Boolean = false): Boolean =
        matchMode(node, selector, allowContains) != null

    /**
     * 按规格取节点，至多 [limit] 条。
     *
     * 先只按严格口径扫一遍，整棵树一条都没有、且调用方允许兜底，才再扫一遍：
     * 「精确优先」因此与遍历次序无关，不会因为一个精确目标排在后面就被前面的相似目标抢走。
     */
    fun findAll(
        root: NodeView?,
        selector: Selector,
        limit: Int = DEFAULT_MATCH_LIMIT,
        allowContains: Boolean = false,
    ): List<NodeMatch> {
        val strict = collect(root, selector, limit, allowContains = false)
        if (strict.isNotEmpty() || !allowContains) return strict
        return collect(root, selector, limit, allowContains = true)
    }

    /** 按节点号取回那一颗，越出上限或超出实际树大小都回 null。 */
    fun findById(root: NodeView?, nodeId: Int): NodeView? {
        var found: NodeView? = null
        traverse(root) { node, id, _ ->
            if (id != nodeId) return@traverse false
            found = node
            true
        }
        return found
    }

    private fun collect(
        root: NodeView?,
        selector: Selector,
        limit: Int,
        allowContains: Boolean,
    ): List<NodeMatch> {
        val hits = ArrayList<NodeMatch>()
        traverse(root) { node, nodeId, depth ->
            matchMode(node, selector, allowContains)?.let { mode -> hits += NodeMatch(node, nodeId, depth, mode) }
            hits.size >= limit
        }
        return hits
    }

    /**
     * 前序遍历，把（节点, 节点号, 深度）交给 [visit]，[visit] 回 true 即收工。
     *
     * 按规格找与按号找共用这一条遍历：两套查询各自写一遍最容易写歪的就是节点号的给法，
     * 而这里歪一点，助手照快照发来的号就会点到另一个东西。
     */
    private fun traverse(root: NodeView?, visit: (node: NodeView, nodeId: Int, depth: Int) -> Boolean) {
        var visited = 0
        fun descend(node: NodeView, depth: Int): Boolean {
            if (depth > MAX_DEPTH || visited >= MAX_NODES) return false
            if (visit(node, visited++, depth)) return true
            node.children.forEach { child -> if (descend(child, depth + 1)) return true }
            return false
        }
        root?.let { descend(it, 0) }
    }

    /** 命中口径；null 表示不命中。全空规格一律不命中，见 [Selector.unconstrained]。 */
    private fun matchMode(node: NodeView, selector: Selector, allowContains: Boolean): MatchMode? {
        if (selector.unconstrained) return null
        if (selector.index != null && node.siblingIndex != selector.index) return null
        if (selector.packageHint != null && node.packageName != selector.packageHint) return null
        if (selector.className != null && !qualifiedNameMatches(node.className, selector.className, CLASS_TAILS)) {
            return null
        }
        if (selector.viewId != null && !qualifiedNameMatches(node.viewId, selector.viewId, ID_TAILS)) return null
        val strict = textEquals(node.text, selector.text) &&
            textEquals(node.contentDescription, selector.contentDescription)
        if (strict) return MatchMode.EXACT
        val loose = containsText(node.text, selector.text) &&
            containsText(node.contentDescription, selector.contentDescription)
        return MatchMode.CONTAINS.takeIf { loose && allowContains }
    }

    /**
     * 类名与资源 id 的规范写法带包前缀（`android.widget.Button`、`com.x:id/save`），
     * 助手抄来的常是末段。只允许在分隔符处整体相等，不做包含匹配：短名撞进长名就是又一次误命中。
     */
    private fun qualifiedNameMatches(actual: String?, wanted: String, tails: Array<String>): Boolean {
        actual ?: return false
        if (actual == wanted) return true
        return tails.any { separator -> actual.contains(separator) && actual.substringAfterLast(separator) == wanted }
    }

    private fun textEquals(actual: String?, wanted: String?): Boolean = wanted == null || actual == wanted

    private fun containsText(actual: String?, wanted: String?): Boolean =
        wanted == null || actual?.contains(wanted, ignoreCase = true) == true

    /** 整数键的唯一读法：只接受 [0, bound) 内的整数，其它一律 null。 */
    private fun integer(source: JsonView, key: String, bound: Int): Int? {
        val raw = source.number(key)?.toDouble()?.takeIf { it.isFinite() && it % 1.0 == 0.0 } ?: return null
        val value = raw.toInt()
        return value.takeIf { raw == value.toDouble() && value >= 0 && value < bound }
    }
}
