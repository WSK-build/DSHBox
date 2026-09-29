package interlock.relay.core.exec.a11y

import interlock.relay.core.protocol.ArgErrors
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.exec.BackendResult
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 把节点矩形归一到屏幕内：左右、上下各自排序后与屏幕求交。
 *
 * 部分厂商页面（启动器与自带浏览器结果页）会给出反向或飞出屏幕的矩形，原样交给助手
 * 就是一个指向屏外的假目标。`ui.snapshot` 与这里共用这一份实现：两处各写一套，迟早会
 * 出现快照里的坐标合法、按坐标回读却落到另一颗节点。
 */
internal fun normalizeBounds(raw: Rect, screen: Rect): Rect? {
    val left = maxOf(minOf(raw.left, raw.right), screen.left)
    val top = maxOf(minOf(raw.top, raw.bottom), screen.top)
    val right = minOf(maxOf(raw.left, raw.right), screen.right)
    val bottom = minOf(maxOf(raw.top, raw.bottom), screen.bottom)
    return Rect(left, top, right, bottom).takeIf { it.width() > 0 && it.height() > 0 }
}

/**
 * 按前序下标取回节点。下标规则必须与 [NodeSelector] 分配 `nodeId` 的规则一致，
 * 否则快照给出的编号在这里会指到另一颗节点上 —— 那是一次"看起来成功"的错误点击，
 * 比报错更难发现。
 */
internal fun findByIdPreorder(root: AccessibilityNodeInfo?, nodeId: Int): AccessibilityNodeInfo? {
    if (root == null || nodeId < 0) return null
    var counter = 0
    var found: AccessibilityNodeInfo? = null
    fun walk(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > NodeSelector.MAX_DEPTH || counter >= NodeSelector.MAX_NODES) return false
        if (counter == nodeId) {
            found = node
            return true
        }
        counter++
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (walk(child, depth + 1)) return true
        }
        return false
    }
    walk(root, 0)
    return found
}

/**
 * 节点级操作。与坐标通路（`ui.tap` / `ui.swipe` 走 `dispatchGesture`）的分别在于：
 * `performAction` 不产生触摸事件，也不会出现"坐标没对上、回执却是成功"这种静默失败。
 *
 * 落在哪块屏上由 [tree] 说：前台取活动窗口，可信虚拟屏取那块屏上此刻该用的那颗窗口
 * （`rootInActiveWindow` 在后者上没有意义，见 [DisplayTree]）。同一棵树里的编号才是
 * 同一个编号，所以快照与这里的取树规则必须同源。
 *
 * 滑杆与进度条走 [setProgress]：平台确实提供了 `ACTION_SET_PROGRESS`，只是它声明在
 * 嵌套类 `AccessibilityNodeInfo.AccessibilityAction` 上。闹钟的时、分就是这一类节点。
 * [readNode] 同时把 `getRangeInfo()` 的量程与当前值交回去，坐标通路要精确落点时靠它。
 */
internal class NodeActions(
    /** 这一棵树的来源：前台是活动窗口，可信虚拟屏上是那块屏上此刻该用的那颗窗口。 */
    private val tree: DisplayTree,
    private val budgetMs: Long,
    private val selfPackage: String,
    /**
     * 判「这一颗是不是系统授权界面上的控件」。
     *
     * 必须由调用方在**解析出目标之后**再问一次：闸门那一条判据读的是参数里自报的包名，
     * 而 `selector` 不写 `package` 时它读不到任何东西 —— 也就是说只靠参数，
     * 想点掉授权框上的「允许」可以靠省掉一个键绕过。这里补的是执行时的那一道。
     */
    private val consentTarget: (AccessibilityNodeInfo) -> Boolean = { false },
) {

    /** 节点矩形的裁剪面，与 [tree] 同一块屏 —— 换一棵树就换一套坐标。 */
    private val screen: Rect = tree.screen

    private sealed interface Target {
        /** [error] 跟着失败原因走：「规格写错了」与「树上没有这颗节点」是两条相反的后续动作。 */
        data class Bad(val error: RelayError, val reason: String) : Target
        data class Many(val matches: List<NodeMatch>) : Target
        data class One(val node: AccessibilityNodeInfo, val nodeId: Int, val mode: MatchMode) : Target
    }

    private fun root(): AccessibilityNodeInfo? = tree.root()

    /**
     * 解析出来的那颗节点落在系统授权界面上时拒掉这次派发。
     *
     * 闸门那道同名的判据只能读参数：`selector` 不写 `package` 时它什么也读不到，
     * 于是"点掉授权框的允许"可以用省掉一个键的方式绕过去。这一问是在树上拿到真身之后
     * 做的，绕不开。
     */
    private fun consentRefusal(target: Target.One): BackendResult? {
        if (!consentTarget(target.node)) return null
        return BackendResult.Failed(
            RelayError.NODE_SYSTEM_CONSENT_TARGET,
            "the resolved target is a system consent control: the user has to answer that dialog. " +
                "Refused at execution time - naming the target by nodeId, by view id, or by leaving " +
                "the package out of the selector does not bypass this check.",
        )
    }

    /**
     * 按编号回查归属（包名 + view 资源 id），只读。
     *
     * 给闸门判「这是不是系统授权界面」用：助手递 `nodeId` 时参数里没有任何归属信息，
     * 只有回到当前这棵树才看得出要点的是谁家的按钮。
     */
    fun ownerOf(nodeId: Int): Pair<String?, String?>? {
        val node = findByIdPreorder(root(), nodeId) ?: return null
        return node.packageName?.toString() to node.viewIdResourceName
    }

    /**
     * 写操作重新取树时用的那一版。
     *
     * 入口那道宿主窗口检查（`A11yBackend.execute`）挡的是"开始这一刻宿主在前台"，
     * 挡不住用户在十几轮滚动之间把宿主带到前台 —— 而每一次重新取树都可能落到宿主自己的
     * 确认框上。助手点掉自己的确认框等于自己给自己授权，所以每一轮都要再问一次归属。
     */
    private fun rootForWrite(): AccessibilityNodeInfo? =
        root()?.takeUnless { it.packageName?.toString() == selfPackage }

    /** 节点的稳定指纹：编号会随树形变而换人，指纹不会。 */
    /**
     * 以 [anchor] 为参照，取它某一侧上的第 N 个节点。
     *
     * 候选的编号与绝对定位同一套规则（前序下标），所以歧义里列出的 `nodeId` 能被 `ui.node`
     * 或下一次调用直接指回同一颗节点 —— 助手不必重新读一遍界面。
     */
    private fun relativeTarget(anchor: Target.One, rel: JSONObject?): Target {
        if (rel == null) {
            return Target.Bad(
                RelayError.TRANSPORT_MALFORMED,
                "$KEY_RELATIVE must be an object with $KEY_SIDE and optional $KEY_NTH, $KEY_SAME_CLASS",
            )
        }
        rel.keys().forEach { key ->
            // 不在表内的键一律算脚本写错：静默忽略一个拼错的键，等于让 sameClass 与
            // sameclass 给出两种结果而回包都说顺。
            if (key !in setOf(KEY_SIDE, KEY_NTH, KEY_SAME_CLASS)) {
                return Target.Bad(RelayError.TRANSPORT_MALFORMED, "unknown key in $KEY_RELATIVE: $key")
            }
        }
        val sideName = rel.optString(KEY_SIDE).lowercase()
        val side = RelativeGeometry.sideOf(sideName)
            ?: return Target.Bad(
                RelayError.TRANSPORT_MALFORMED,
                "relative.$KEY_SIDE must be right|left|above|below, got '$sideName'",
            )
        val nth = if (rel.has(KEY_NTH)) rel.optInt(KEY_NTH, 0) else 1
        if (nth < 1 || nth > MAX_RELATIVE_NTH) {
            return Target.Bad(RelayError.TRANSPORT_MALFORMED, "relative.$KEY_NTH must be within 1..$MAX_RELATIVE_NTH")
        }
        val sameClass = rel.optBoolean(KEY_SAME_CLASS)
        val top = root()
            ?: return Target.Bad(RelayError.BACKEND_UNAVAILABLE, "no window tree to read on display ${tree.displayId} after a second read: no window holds " +
                "focus there - retry once one does")
        val anchorNode = findByIdPreorder(top, anchor.nodeId)
            ?: return Target.Bad(
                RelayError.NODE_NOT_FOUND,
                "anchor nodeId=${anchor.nodeId} is gone from the current tree; node ids shift when the screen changes",
            )
        val candidates = boxesPreorder(top).map { (ref, node) -> boxOf(ref, node) }
        val scope = if (sameClass) " with the anchor's class" else ""
        val ordinal = if (nth > 1) " no $nth-th candidate there" else " read ui.snapshot or try another side"
        return when (val outcome = RelativeGeometry.pick(boxOf(anchor.nodeId, anchorNode), candidates, side, nth, sameClass)) {
            is RelativeGeometry.Outcome.Pick -> {
                val node = findByIdPreorder(top, outcome.box.ref)
                    ?: return Target.Bad(RelayError.NODE_NOT_FOUND, "the node on the $sideName side is gone from the current tree")
                // 选位与取节点是两次走树，中间 `getChild` 可以返回 null 让编号整体前移。
                // 不复核就派发的话，动作落在邻居身上而回包只说"派到了 nodeId=N"——
                // 自洽到看不出来。这里比对的是当初量到的那只盒子与类名。
                val rechecked = boxOf(outcome.box.ref, node)
                if (rechecked != outcome.box) {
                    return Target.Bad(
                        RelayError.NODE_NOT_FOUND,
                        "the node at position ${outcome.box.ref} changed while it was being located: " +
                            "re-read with ui.snapshot before targeting it again",
                    )
                }
                Target.One(node, outcome.box.ref, MatchMode.EXACT)
            }
            RelativeGeometry.Outcome.None -> Target.Bad(
                RelayError.NODE_NOT_FOUND,
                "no node on the $sideName of nodeId=${anchor.nodeId}$scope:$ordinal",
            )
            is RelativeGeometry.Outcome.Ambiguous -> Target.Many(
                // 并列最近，且调用方没有用 nth 指定第几个。按既有的歧义出口交回候选列表，
                // 而不是自己挑一个：这里挑中谁，谁的按钮就会被按下去。
                outcome.boxes.mapNotNull { box ->
                    findByIdPreorder(top, box.ref)?.let { NodeMatch(AccessibilityNodeView(it), box.ref, 0, MatchMode.EXACT) }
                },
            )
        }
    }

    /**
     * 按前序下标把整棵树收成（编号, 节点）表。
     *
     * 下标规则与 [findByIdPreorder] 逐字对齐（同样的跳过 null 子节点、同样的深度与节点数上限），
     * 否则歧义里报出的候选编号会指到另一颗节点上 —— 那是一次看起来成功的错误操作。
     */
    private fun boxesPreorder(root: AccessibilityNodeInfo): List<Pair<Int, AccessibilityNodeInfo>> {
        val out = ArrayList<Pair<Int, AccessibilityNodeInfo>>()
        var counter = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > NodeSelector.MAX_DEPTH || counter >= NodeSelector.MAX_NODES) return
            val id = counter
            counter++
            out += id to node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    /**
     * 节点的自报边界，换成 [RelativeGeometry] 吃的整数盒子。
     *
     * 与快照、`ui.node` 同一份归一规则（反向矩形先排序、再裁到屏内）：三处不同源的话，
     * 同一颗节点在快照里说"在锚点右侧"，在相对定位这里会被算到左下去。
     * 整颗都在屏外时按原矩形参与计算——它仍在树里，判成"没有这颗节点"是另一回事。
     */
    private fun boxOf(ref: Int, node: AccessibilityNodeInfo): RelativeGeometry.Box {
        val raw = Rect()
        node.getBoundsInScreen(raw)
        val box = normalizeBounds(raw, screen) ?: raw
        return RelativeGeometry.Box(ref, box.left, box.top, box.right, box.bottom, node.className?.toString())
    }

    private fun fingerprint(node: AccessibilityNodeInfo): String =
        listOf(
            node.className?.toString().orEmpty(),
            node.viewIdResourceName.orEmpty(),
            Rect().let { rect -> node.getBoundsInScreen(rect); "$rect" },
        ).joinToString("|")

    private fun resolve(args: JSONObject): Target {
        // 不带 `relative` 时走原来的绝对定位；带它时，args 里那套 selector/nodeId 描述的是
        // **参照物**，真正要操作的是参照物某一侧上的第 N 个节点。
        if (!args.has(KEY_RELATIVE)) return absoluteTarget(args)
        val anchor = absoluteTarget(args)
        if (anchor !is Target.One) return anchor
        return relativeTarget(anchor, args.optJSONObject(KEY_RELATIVE))
    }

    private fun absoluteTarget(args: JSONObject): Target {
        // 只把目标那两个键交给 NodeSelector：本能力自己的参数（direction、times、text…）
        // 与选择器无关，一起送过去会被它的严格键表判成拼错。
        // 顶层键是否合法由各能力的 ArgSpec 把关，两处不重复也不遗漏。
        val target = JSONObject()
        for (key in listOf(NodeSelector.KEY_SELECTOR, NodeSelector.KEY_NODE_ID)) {
            if (args.has(key)) target.put(key, args.opt(key))
        }
        val spec = NodeSelector.parse(target)
        return when (spec) {
            // 规格不合形状是脚本问题（退出码 1，改脚本来修）。
            is SelectorSpec.Invalid -> Target.Bad(RelayError.TRANSPORT_MALFORMED, spec.reason)

            is SelectorSpec.ByNodeId -> {
                // 整棵树都读不到时不能按"这颗节点不在树里"报：`ui.waitFor {"absent":true}`
                // 正是把 NODE_NOT_FOUND 读成"它消失了"并回成立的，那样一块够不着的屏会被写成
                // "目标确实没了"——一次假的成功，比报错难发现。
                val currentRoot = root() ?: return Target.Bad(
                    RelayError.BACKEND_UNAVAILABLE,
                    "no window tree to read on display ${tree.displayId} after a second read: no window holds " +
                "focus there - retry once one does",
                )
                val node = findByIdPreorder(currentRoot, spec.nodeId)
                    ?: return Target.Bad(
                        RelayError.NODE_NOT_FOUND,
                        "no node with nodeId=${spec.nodeId} in the current tree: " +
                            "node ids come from ui.snapshot and shift when the screen changes",
                    )
                Target.One(node, spec.nodeId, MatchMode.EXACT)
            }

            is SelectorSpec.Parsed -> {
                val top = root()
                    ?: return Target.Bad(
                        RelayError.BACKEND_UNAVAILABLE,
                        "no window tree to read on display ${tree.displayId} after a second read: no window holds " +
                "focus there - retry once one does",
                    )
                val matches = NodeSelector.findAll(AccessibilityNodeView(top), spec.selector)
                when {
                    matches.isEmpty() -> Target.Bad(RelayError.NODE_NOT_FOUND, "selector matched no node")
                    matches.size > 1 -> Target.Many(matches)
                    else -> {
                        val first = matches.single()
                        val node = first.node.accessibilityNode()
                            ?: return Target.Bad(RelayError.NODE_NOT_FOUND, "matched node is no longer in the tree")
                        Target.One(node, first.nodeId, first.mode)
                    }
                }
            }
        }
    }

    /**
     * 派一个动作并如实回报。先问 `actionList`：节点根本不提供这个动作时
     * `performAction` 也只是返回 false，那会被误报成"应用没认"而诱导助手重发。
     *
     * 成功分支刻意不写「applied/已生效」：`true` 只表示节点接下了这次派发，不表示界面真的
     * 变了（容器没有可滚内容时 `ui.scroll` 连派两次都返回 true 而画面零变化）。
     * 走这里的动作与 scroll 必须用同一套词，否则助手会以为它们拿到了更强的保证。
     */
    private fun act(
        target: Target.One,
        action: Int,
        extras: Bundle? = null,
        /**
         * 放过"节点没自报这个动作"那一问。只由 [imeAction] 在"确属输入框一族"时置位：
         * 平台常常不把 `ACTION_IME_ENTER` 写进可编辑节点的自报动作里，那一问会把可用的节点
         * 挡在派发之前 —— 放宽必须同时放宽这道门，否则改动只是换了个错误码。
         */
        allowUnadvertised: Boolean = false,
    ): BackendResult {
        val name = actionName(action)
        consentRefusal(target)?.let { return it }
        if (!allowUnadvertised && target.node.actionList.orEmpty().none { it.id == action }) {
            return BackendResult.Failed(RelayError.NODE_NOT_ACTIONABLE, "node does not support $name")
        }
        val applied = runCatching {
            if (extras == null) target.node.performAction(action)
            else target.node.performAction(action, extras)
        }.getOrDefault(false)
        return if (applied) {
            // 交回的是**动作之后**重读的那颗节点：`AccessibilityNodeInfo` 是取回那一刻的
            // 字段副本，拿派发前那颗去 describe，成功响应里就是一张过期的状态照。
            val fresh = findByIdPreorder(root(), target.nodeId)
            BackendResult.Ok(
                data = JSONObject()
                    .put("dispatched", true)
                    .put("effectVerified", false)
                    .put("nodeId", target.nodeId)
                    .put("node", describe(fresh ?: target.node))
                    .put(
                        "note",
                        "dispatched means the node accepted the action, not that the UI changed; " +
                            "re-read with ui.node or ui.waitFor to confirm the effect",
                    ),
            )
        } else {
            BackendResult.Failed(RelayError.NODE_ACTION_REJECTED, "$name was refused by the app")
        }
    }

    private fun dispatch(args: JSONObject, action: Int, extras: Bundle? = null): BackendResult =
        when (val target = resolve(args)) {
            is Target.Bad -> bad(target)
            is Target.Many -> ambiguous(target.matches)
            is Target.One -> act(target, action, extras)
        }

    suspend fun click(args: JSONObject): BackendResult = dispatch(args, AccessibilityNodeInfo.ACTION_CLICK)

    fun longClick(args: JSONObject): BackendResult = dispatch(args, AccessibilityNodeInfo.ACTION_LONG_CLICK)

    fun select(args: JSONObject): BackendResult = dispatch(args, AccessibilityNodeInfo.ACTION_SELECT)

    fun dismiss(args: JSONObject): BackendResult = dispatch(args, AccessibilityNodeInfo.ACTION_DISMISS)

    /**
     * 滚动容器，也用于滑杆（`ACTION_SCROLL_FORWARD/BACKWARD` 在滑杆上就是加减一档进度）。
     * 不接受这个动作的节点明确回「节点不支持滚动」并指回 `ui.node` ——
     * 让助手知道该换通路（`ui.setProgress` 或坐标），而不是重发。
     *
     * 带量程的节点（滚轮、滑杆）**每一步都等量程真的变了再派下一次**，回包把
     * `dispatched`（节点接受了几次派发）与 `advanced`（观察到几次位移）分列：只按
     * `performAction` 的返回值连发，会把动画途中被吞掉的派发也数进去，助手据此以为
     * 滚了 20 格而屏上只走了 2 格，只能整条重发。
     */
    suspend fun scroll(args: JSONObject): BackendResult {
        val direction = args.optString(KEY_DIRECTION).lowercase()
        if (direction != "forward" && direction != "backward") {
            return malformed("$KEY_DIRECTION must be forward or backward")
        }
        val action = if (direction == "forward") {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val timesGiven = args.has(KEY_TIMES)
        val times = if (timesGiven) {
            numberArg(args, KEY_TIMES)?.toInt() ?: return malformed(ArgErrors.notNumber(KEY_TIMES, args.opt(KEY_TIMES)))
        } else {
            1
        }
        if (times < 1 || times > MAX_SCROLL_TIMES) {
            return malformed("$KEY_TIMES must be within 1..$MAX_SCROLL_TIMES")
        }
        val until = if (args.has(KEY_UNTIL)) {
            numberArg(args, KEY_UNTIL)?.toFloat() ?: return malformed(ArgErrors.notNumber(KEY_UNTIL, args.opt(KEY_UNTIL)))
        } else {
            null
        }
        // 步数上限：`times` 是**上限**不是下限 —— 助手给了 times:2 就只许走两格，哪怕 until 还没到。
        // 没给 times 而给了 until 时，才用 MAX_UNTIL_STEPS 这一档更宽的默认（闹钟小时 0..23
        // 一次调用要允许二十几拍）。回包里 stepLimit 与 requested 各说各的，别互相冒充。
        val stepLimit = when {
            until == null -> times
            timesGiven -> times
            else -> MAX_UNTIL_STEPS
        }
        val picked = when (val target = resolve(args)) {
            is Target.Bad -> return bad(target)
            is Target.Many -> return ambiguous(target.matches)
            is Target.One -> target
        }
        // 滚动不走 [act]（它自己按轮派发），所以这道判据在这里也要问一次。
        consentRefusal(picked)?.let { return it }
        val startValue = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            picked.node.rangeInfo?.current
        } else {
            null
        }
        if (until != null) {
            val range = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) picked.node.rangeInfo else null
            if (range == null) {
                return BackendResult.Failed(
                    RelayError.NODE_NOT_ACTIONABLE,
                    "$KEY_UNTIL needs a node that reports a range; read ui.node {range}",
                )
            }
            if (until < range.min || until > range.max) {
                return malformed(
                    "$KEY_UNTIL=$until is outside this node's range [${range.min}..${range.max}] " +
                        "(read by ui.node)",
                )
            }
        }
        // 判据用 actionList，不用 isScrollable：滑杆（AbsSeekBar 一族）自己处理
        // ACTION_SCROLL_FORWARD/BACKWARD 来加减进度，却把 isScrollable 报成 false。
        // 拿 isScrollable 当门，恰好把闹钟时/分那一类节点全挡在外面 ——
        // 而那正是这批能力要解决的场景。
        if (picked.node.actionList.orEmpty().none { it.id == action }) {
            return BackendResult.Failed(
                RelayError.NODE_NOT_ACTIONABLE,
                "node does not accept scroll; read ui.node {range} to see what it is",
            )
        }
        var dispatched = 0
        var advanced = 0
        var measured = false
        var reached = false
        var landed = startValue
        var stoppedReason: String? = null
        val wanted = fingerprint(picked.node)
        // 每一步的让拍都算在这一次调用的预算里：越过它，信箱会把一次其实成功的滚动判成
        // E_TRANSPORT_TIMEOUT，而助手只能重发——那正是这批能力要避免的循环。
        val stepDeadline = interlock.relay.core.runtime.monotonicNow() +
            minOf(SCROLL_BUDGET_MS, budgetMs.coerceAtLeast(0L))
        // 进场就已经在目标值上：那就一格都别派。NumberPicker 在 23 上再滚一格会绕回 0，
        // "把小时滚到 23"因此主动破坏了它自己要的后置条件。
        if (until != null && startValue != null && valueReached(startValue, until)) {
            reached = true
            landed = startValue
        }
        for (round in 0 until (if (reached) 0 else stepLimit)) {
            // 每轮按编号重新取节点，并重新问一次动作表：滚动会把目标换掉甚至回收，
            // 而滚到底之后节点会撤下这个动作 —— 继续派只拿到 false，
            // 那是"到底了"不是"应用没认"，报成后者会被 retryable 引向无限重发。
            val fresh = findByIdPreorder(rootForWrite(), picked.nodeId)
            if (fresh == null) {
                stoppedReason = if (dispatched == 0) "node is gone from the tree" else "node is gone after $advanced observed step(s) of $dispatched accepted"
                break
            }
            // 编号只是"前序第几颗"这个位置，滚动恰恰最容易改变位置之上挂着几颗节点
            // （折叠标题、下拉头、RecyclerView 回收）。不比对指纹，后续几轮就会去滚
            // 另一颗容器，而响应只报"派了几次"——那是一次看起来成功的错误操作。
            if (fingerprint(fresh) != wanted) {
                stoppedReason = "the node at this position changed after $advanced observed step(s) of $dispatched accepted"
                break
            }
            if (fresh.actionList.orEmpty().none { it.id == action }) {
                stoppedReason = "node stopped accepting scroll after $advanced observed step(s) of $dispatched accepted"
                break
            }
            // 量程是当前这一格的值。有它才量得出位移；列表类容器没有量程，只能等一拍再派下一次。
            val before = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                fresh.rangeInfo?.current
            } else {
                null
            }
            if (!runCatching { fresh.performAction(action) }.getOrDefault(false)) {
                stoppedReason = "app stopped accepting scroll after $advanced observed step(s) of $dispatched accepted"
                break
            }
            dispatched++
            if (before == null) {
                delay(SCROLL_STEP_SETTLE_MS)
                continue
            }
            measured = true
            // 派发被接受不等于滚轮走了一格：滚轮与滑杆在动画途中会把紧接着的派发吞掉或
            // 合并，连发 20 次实际只走 1-2 格，而助手拿到的 dispatched 是 20。所以每一步
            // 都等这一格走过去再派下一次，并且只把**观察到变化**的那几次算进 advanced。
            val movedTo = settleUntilMoved(picked.nodeId, wanted, before, stepDeadline)
            if (movedTo == null) {
                stoppedReason = if (interlock.relay.core.runtime.monotonicNow() >= stepDeadline) {
                    "call budget exhausted after $advanced step(s)"
                } else {
                    "value stopped changing after $advanced step(s); likely at either end"
                }
                break
            }
            advanced++
            landed = movedTo
            if (until != null && valueReached(movedTo, until)) {
                reached = true
                break
            }
        }
        return if (dispatched == 0 && !reached) {
            // 一格都滚不动是"这里滚不动"，不是"应用拒了这次调用"：码要能分开这两件事。
            // 节点已经不在树上又是第三种：那是编号作废（该重读界面），不是这里滚不动。
            val gone = stoppedReason?.startsWith("node is gone") == true ||
                findByIdPreorder(root(), picked.nodeId) == null
            BackendResult.Failed(
                if (gone) RelayError.NODE_NOT_FOUND else RelayError.NODE_NOT_ACTIONABLE,
                stoppedReason ?: "node would not scroll from here",
            )
        } else {
            BackendResult.Ok(
                data = JSONObject()
                    .put("dispatched", dispatched)
                    .put("requested", times)
                    .put("stepLimit", stepLimit)
                    .put("direction", direction)
                    .apply {
                        // 只有量得出位移的那一类节点才交 advanced：没有量程时写 0 会被读成
                        // "一次都没动"，而真实情况是"这里量不出"。
                        if (measured) put("advanced", advanced)
                        // 目标值是问一次就够的判据：助手据此知道要不要再来一次，而不是靠
                        // 重读界面猜。没给 until 时不写 reached——"没要求到位"不等于"没到位"。
                        if (until != null) put("reached", reached).put("target", until)
                        landed?.let { put("value", it) }
                    }
                    .apply { stoppedReason?.let { put("stoppedEarly", true).put("stoppedReason", it) } }
                    .put(
                        "movementVerified", measured && advanced == dispatched,
                    )
                    .put(
                        "note",
                        if (measured) {
                            "advanced counts observed value changes, one settle per step; " +
                                "read ui.node {range} for the value it landed on"
                        } else {
                            "this node reports no range, so movement cannot be measured here; " +
                                "re-read with ui.snapshot or ui.waitFor to confirm the list moved"
                        },
                    ),
            )
        }
    }

    /**
     * 等这一格真的走过去：读到量程变了才回 true。
     *
     * 每拍都重新按编号取节点并核身份（同 `ui.setProgress` 的回读），否则换人之后读到的是
     * 另一颗滚杆的值 —— 那会把没动的一次说成动了。
     */
    private suspend fun settleUntilMoved(nodeId: Int, identity: String, before: Float, deadline: Long): Float? {
        var rounds = 0
        while (rounds < MAX_SCROLL_SETTLE_ROUNDS) {
            delay(SCROLL_SETTLE_POLL_MS)
            rounds += 1
            val current = readBackRange(nodeId, identity) ?: return null
            if (current != before) return current
            if (interlock.relay.core.runtime.monotonicNow() >= deadline) return null
        }
        return null
    }

    /**
     * 走到目标值没有。按**半格**容差判等：控件按自己的步进取整，滚轮交回来的常常是
     * `23.0` 与请求 `23` 这种同一档的两种写法；只按严格相等判，一次已经到位的滚动会被
     * 说成没到位，助手于是再滚一格 —— 那比报错了更坏，因为它把值滚过了。
     */
    private fun valueReached(current: Float, target: Float): Boolean =
        kotlin.math.abs(current - target) < 0.5f

    /** 直接写文本，不需要先聚焦。`ui.text` 那条要焦点的通路保留，两者失败原因不同。 */
    fun setValue(args: JSONObject): BackendResult {
        val text = args.optString(KEY_TEXT)
        if (text.isBlank()) return malformed("missing arg: $KEY_TEXT")
        val picked = when (val target = resolve(args)) {
            is Target.Bad -> return bad(target)
            is Target.Many -> return ambiguous(target.matches)
            is Target.One -> target
        }
        val extras = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = act(picked, AccessibilityNodeInfo.ACTION_SET_TEXT, extras)
        if (result is BackendResult.Ok) {
            // 回执只说明系统接了这次 setText，不说明界面上真有了这些字。
            // 回读按**节点编号**再取一次，不能按选择器再解析一次：选择器里带的常是写入前
            // 的旧文本，写完之后再按它去找必然落空，那会把一次成功的写入报成失败。
            // 这里不给 `matches` 那个硬布尔：控件自己会改写入内容（号码与日期掩码、
            // 联想替换、maxLength 截断），不相等不等于失败——判等要交给看得见现场的助手，
            // 口径与 ui.setProgress 一致。
            result.data.put("requested", text)
            val fresh = findByIdPreorder(root(), picked.nodeId)
            if (fresh == null) {
                result.data.put("readBackUnavailable", "node is no longer in the tree")
            } else {
                result.data.put("readBack", fresh.text?.toString().orEmpty())
            }
        }
        return result
    }

    /** 单节点状态读取。校验不再需要"再截一次图"。 */
    fun readNode(args: JSONObject): BackendResult = when (val target = resolve(args)) {
        is Target.Bad -> bad(target)
        is Target.Many -> ambiguous(target.matches)
        is Target.One -> BackendResult.Ok(
            data = JSONObject()
                // 命中口径要交回去：按精确文本命中与按包含命中是对助手两句不同的话。
                .put("matchMode", target.mode.name.lowercase())
                .put("nodeId", target.nodeId)
                .put("node", describe(target.node))
                .put("treeDisplay", tree.displayId)
                .also { body ->
                    staleNodeIdWarning(args, target)?.let { body.put("treeWarning", it) }
                },
        )
    }

    /**
     * 按编号取到一颗看不见、或在屏内没有面积的东西时说出来。
     *
     * 编号是"某一棵树里的前序位次"，换一块屏或换一次快照就从 0 重数；真机上量到过
     * "同一个编号在虚拟屏是一行设置项、在前台是助手自己会话里的一个按钮"，而回包当时
     * 仍报 `ok:true` + `matchMode:exact`。不说出来，助手会拿这个结果继续点。
     * 只按编号取的目标才判：按选择器取的就是眼前这棵树。
     */
    private fun staleNodeIdWarning(args: JSONObject, target: Target.One): String? =
        staleNodeIdReason(
            askedByNodeId = args.has(NodeSelector.KEY_NODE_ID),
            visibleToUser = target.node.isVisibleToUser(),
            hasOnScreenArea = normalizeBounds(
                Rect().also { target.node.getBoundsInScreen(it) },
                tree.screen,
            ) != null,
            displayId = tree.displayId,
        )


    /**
     * 等到条件成立。存在的理由是把"操作完立刻读"这种必然偶发失败的写法收进一次调用：
     * 界面切换有延迟，坐标通路下助手只能反复快照自己猜。
     */
    suspend fun waitFor(args: JSONObject): BackendResult {
        val requested = if (args.has(KEY_TIMEOUT_MS)) {
            numberArg(args, KEY_TIMEOUT_MS)?.toLong() ?: return malformed(ArgErrors.notNumber(KEY_TIMEOUT_MS, args.opt(KEY_TIMEOUT_MS)))
        } else {
            DEFAULT_WAIT_MS
        }
        if (requested < 0 || requested > MAX_WAIT_MS) {
            return malformed("$KEY_TIMEOUT_MS must be within 0..$MAX_WAIT_MS")
        }
        // 通道预算排在能力自身上限之前：客户端的 ttlMs 最短可以只给一秒，而信箱那笔预算
        // 一到就把整条请求判成 E_TRANSPORT_TIMEOUT —— 助手读到的是"宿主没应答"，
        // 真相是"界面还没变成那样"。宁可自己按预算收口，并把收口这件事说出去。
        // 传进来的 [budgetMs] 已经扣过回写响应要留的余量，见 RelayCoordinator。
        val timeoutMs = requested.coerceAtMost(budgetMs.coerceAtLeast(0L))
        // 单调钟：墙钟不参与任何差值计算是全模块的纪律（用户把系统时间往回调一次，
        // 用墙钟算的 deadline 就到不了，这条等待会一直跑，最后由信箱掐成
        // E_TRANSPORT_TIMEOUT —— 正是这次收口要消灭的那个假象）。
        val startedAt = interlock.relay.core.runtime.monotonicNow()
        val deadline = startedAt + timeoutMs
        var polls = 0
        var lastReason = "not evaluated"
        while (true) {
            polls++
            when (val outcome = evaluate(args)) {
                is Outcome.Satisfied -> return BackendResult.Ok(
                    data = JSONObject()
                        .put("satisfied", true)
                        .put("polls", polls)
                        .put("waitedMs", interlock.relay.core.runtime.monotonicNow() - startedAt)
                        .put("requestedMs", requested)
                        .put("cappedToBudget", timeoutMs < requested)
                        .put("node", outcome.node),
                )

                // 规格本身不合形状永远等不成：立刻回那条用法错误，而不是让助手在
                // 一个写错的选择器上把整笔预算等干、最后收到一个"超时"。
                is Outcome.Rejected -> return BackendResult.Failed(outcome.error, outcome.reason)

                is Outcome.Unsatisfied -> lastReason = outcome.reason
            }
            if (interlock.relay.core.runtime.monotonicNow() >= deadline) {
                // 失败分支带不了数据字段，所以把"其实只被给了这么多时间"写进原因里：
                // 最需要知道这一点的恰恰是超时那一条，否则 0 毫秒的等待与
                // 等满 15 秒的超时在助手眼里长成同一个答案。
                val capped = if (timeoutMs < requested) " (capped to the remaining channel budget)" else ""
                return BackendResult.Failed(
                    RelayError.WAIT_TIMEOUT,
                    "condition still false after ${timeoutMs}ms of the requested ${requested}ms$capped " +
                        "and $polls polls: $lastReason",
                )
            }
            delay(WAIT_POLL_MS)
        }
    }

    private sealed interface Outcome {
        data class Satisfied(val node: JSONObject) : Outcome

        /** 这次判定不该继续等：[error] 就是这条调用该回给助手的失败码。 */
        data class Rejected(val error: RelayError, val reason: String) : Outcome

        data class Unsatisfied(val reason: String) : Outcome
    }

    private fun evaluate(args: JSONObject): Outcome {
        val absent = args.opt(KEY_ABSENT) == true
        val target = resolve(args)
        // 「等它消失」最常见的实现方式是整颗节点从树里没了（弹窗关掉就是这样），
        // 所以找不到节点在 absent 这一档就是**成立**，不是"还没满足"。
        // 不这么分，这条最自然的用法必然等满预算回一个超时。
        //
        // 但「树上没有它」与「这一趟根本没读到树」是两件事，`NODE_NOT_FOUND` 一个码装着三种来路：
        // 整棵树读不到（换屏、后台通路刚断）、带 `relative` 时参照物没了或那一侧一个候选都没有、
        // 命中的那颗在两次走树之间消失。前两种成立的是"读不到"，不是"它没了"——
        // 一次屏幕切换会把 `absent:true` 直接兑现成"目标确实消失了"，那是断言被一个从没发生
        // 的事实判过。所以只承认"树读得到、且这一次是按绝对特征找过没有"。
        val absenceJudged = target is Target.Bad && target.error == RelayError.NODE_NOT_FOUND &&
            !args.has(KEY_RELATIVE) && root() != null
        if (target is Target.Bad && absent && absenceJudged) {
            return Outcome.Satisfied(JSONObject().put("gone", true))
        }
        if (target is Target.Bad && absent && target.error == RelayError.NODE_NOT_FOUND) {
            // 读不到树或参照物没了：不判成立，也不让它在剩下的预算里空等——
            // 等着也不会变成"读得到"，那是另一趟调用的事。
            return Outcome.Rejected(target.error, "${target.reason} (absence cannot be judged from here)")
        }
        val picked = when (target) {
            is Target.Bad -> return if (target.error == RelayError.NODE_NOT_FOUND) {
                Outcome.Unsatisfied(target.reason)
            } else {
                Outcome.Rejected(target.error, target.reason)
            }

            is Target.Many -> return Outcome.Rejected(RelayError.NODE_AMBIGUOUS, "selector is ambiguous")
            is Target.One -> target
        }
        val node = picked.node
        if (absent) {
            return if (node.isVisibleToUser()) {
                Outcome.Unsatisfied("node still visible")
            } else {
                Outcome.Satisfied(describe(node))
            }
        }
        if (args.has(KEY_CHECKED)) {
            val want = args.opt(KEY_CHECKED)
            if (node.isChecked != want) {
                return Outcome.Unsatisfied("checked is ${node.isChecked}, wanted $want")
            }
        }
        if (args.has(KEY_TEXT)) {
            val want = args.optString(KEY_TEXT)
            val actual = node.text?.toString()
            if (actual != want) {
                // 只报长度不报原文：这条能力每 150ms 就被问一次，把节点当前的文本原样
                // 交回沙盒，一次确认就变成了对一个正在打字的输入框的连续读取。
                return Outcome.Unsatisfied("text differs (length ${actual?.length ?: 0}, wanted ${want.length})")
            }
        }
        return Outcome.Satisfied(describe(node))
    }

    /**
     * 命中多颗节点时不回"找不到"，也不擅自挑第一颗：把候选带回去，
     * 让调用方加 `index` 或补一个能区分的字段。挑错一颗等于替用户点了别的按钮。
     */
    private fun ambiguous(matches: List<NodeMatch>): BackendResult {
        val preview = matches.take(MAX_CANDIDATES).joinToString("; ") { match ->
            val node = match.node.accessibilityNode()
            val label = node?.let {
                it.text?.toString()?.takeIf { t -> t.isNotEmpty() }
                    ?: it.contentDescription?.toString()?.takeIf { d -> d.isNotEmpty() }
                    ?: it.className?.toString().orEmpty()
            }.orEmpty()
            "#${match.nodeId} $label (siblingIndex ${match.node.siblingIndex})"
        }
        return BackendResult.Failed(
            RelayError.NODE_AMBIGUOUS,
            "selector matched ${matches.size} nodes: re-send with one of the candidate nodeIds " +
                "(every node capability accepts {\"nodeId\":N}), or add a distinguishing selector field. " +
                "\"${NodeSelector.KEY_INDEX}\" only separates candidates that share a parent. " +
                "candidates: $preview",
        )
    }

    /**
     * 可操作性一律按节点自报的**动作表**说，不按框架的那个布尔位说。
     *
     * `act()` 与 `scroll()` 的门就是 `actionList`，回包若改用 `isClickable` 那一套，
     * 同一个对象里就会出现「`clickable:true` 但一点就回 `E_NODE_NOT_ACTIONABLE`」的自相矛盾
     * （实测：`com.android.settings:id/vigour_search_content` 就是这样一颗）。判据只留一处，
     * 助手读到 `actions` 里没有 `click` 时就不必再去撞一次。
     */
    @Suppress("DEPRECATION")
    private fun describe(node: AccessibilityNodeInfo): JSONObject = JSONObject().apply {
        // 这颗节点属于哪块屏要跟着节点一起交：`nodeId` 是同一棵树里的前序下标，
        // 换一棵树就从 0 重数。回包里带上屏号，助手才能看出「我上一次读的是虚拟屏，
        // 这一次落到了主屏」，而不是拿着旧编号点到另一颗节点上还以为是同一颗。
        put("display", tree.displayId)
        put("class", node.className?.toString().orEmpty())
        node.text?.toString()?.takeIf { it.isNotEmpty() }?.let { put("text", it) }
        node.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { put("desc", it) }
        node.viewIdResourceName?.takeIf { it.isNotEmpty() }?.let { put("id", it) }
        node.packageName?.toString()?.let { put("package", it) }
        val actions = node.actionList.orEmpty().map { it.id }
        put("enabled", node.isEnabled)
        put("checkable", node.isCheckable)
        put("checked", node.isChecked)
        put("clickable", AccessibilityNodeInfo.ACTION_CLICK in actions)
        put("longClickable", AccessibilityNodeInfo.ACTION_LONG_CLICK in actions)
        put(
            "scrollable",
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actions ||
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in actions,
        )
        put("selected", node.isSelected)
        put("visible", node.isVisibleToUser())
        put("focused", node.isFocused)
        put("editable", node.isEditable)
        // 动作能力清单要交出去，否则 `E_NODE_NOT_ACTIONABLE` 是一句既防不了也验不了的判决：
        // 助手只能盲试。能力清单里给的是这批动作的名字，认不出的回 action(编号)。
        put(
            "actions",
            org.json.JSONArray(actions.map { actionName(it) }),
        )
        val raw = Rect().also { node.getBoundsInScreen(it) }
        val visible = normalizeBounds(raw, screen)
        if (visible == null) {
            put("offscreen", true)
        } else {
            put("left", visible.left)
            put("top", visible.top)
            put("right", visible.right)
            put("bottom", visible.bottom)
        }
        // 量程只读，但它是滑杆唯一能被程序读到的信息：没有它，坐标就只能靠试。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            node.rangeInfo?.let { put("range", rangeData(it)) }
        }
    }

    /**
     * 把滑杆/进度条设到某个值。动作常量是
     * `AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS`（API 24）——
     * 它声明在嵌套类上，只 dump 外层类会看不到（据此会误判成「平台没有这个动作」）。
     * 闹钟的时、分就是这一类节点，有了它才不必按坐标试错。
     *
     * 目标值两种写法：`percent`（量程百分比）或 `value`（量程本身的数值）。
     * 只给百分比会逼调用方先读量程再做乘法，而"7 时"这种目标天生就是数值；
     * 换算一次还可能在浮点上偏出半格。两者都给就以 `value` 为准，判据只在一处。
     */
    suspend fun setProgress(args: JSONObject): BackendResult {
        val hasPercent = args.has(KEY_PERCENT)
        val hasValue = args.has(KEY_VALUE)
        if (!hasPercent && !hasValue) return malformed("give $KEY_PERCENT or $KEY_VALUE")
        val picked = when (val target = resolve(args)) {
            is Target.Bad -> return bad(target)
            is Target.Many -> return ambiguous(target.matches)
            is Target.One -> target
        }
        val range = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) picked.node.rangeInfo else null
        if (range == null) {
            return BackendResult.Failed(
                RelayError.NODE_NOT_ACTIONABLE,
                "node reports no range info: not a slider or progress control",
            )
        }
        val value = if (hasValue) {
            numberArg(args, KEY_VALUE)?.toFloat()
                ?: return malformed(ArgErrors.notNumber(KEY_VALUE, args.opt(KEY_VALUE)))
        } else {
            val percent = numberArg(args, KEY_PERCENT)
                ?: return malformed(ArgErrors.notNumber(KEY_PERCENT, args.opt(KEY_PERCENT)))
            if (percent < 0.0 || percent > 100.0) {
                return malformed("$KEY_PERCENT must be within 0..100, got $percent")
            }
            (range.min + (range.max - range.min) * (percent / 100.0)).toFloat()
        }
        if (value < range.min || value > range.max) {
            return malformed(
                "$KEY_VALUE=$value is outside this node's range [${range.min}..${range.max}] " +
                    "(read by ui.node)",
            )
        }
        val extras = Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
        }
        val identity = fingerprint(picked.node)
        val result = act(picked, SET_PROGRESS_ID, extras)
        if (result is BackendResult.Ok) {
            result.data.put("requestedValue", value).put("range", rangeData(range))
            // 节点接下这次派发不等于值真的变了。回读把真实值交回去；不相等也不一定是不成功
            // ——控件按自己的步进取整同样不相等，所以这里只交数字、不替助手下结论。
            val readBack = readBackUntilSettled(picked.nodeId, value, identity)
            if (readBack.value == null) {
                // 与 setValue 同一形状：回读不成时给的是**原因**，不是一个裸布尔。
                result.data.put(
                    "readBackUnavailable",
                    "node is gone from the tree, took another node's place, or reports no range",
                )
            } else {
                result.data.put("readBack", readBack.value)
                if (readBack.rounds > 1) result.data.put("readBackRounds", readBack.rounds)
            }
        }
        return result
    }

    /**
     * 回读量程，最多让出几拍。
     *
     * 只读一次会把成功的写入报成失败：系统设置里的音量条把进度交给音频服务之后，
     * 又按服务回调把进度写回控件，中间那一瞬读回来的是**旧值**（派发 9 之后立刻读回 6，
     * 半秒后再读才是 9）。助手拿到「没变」就会重发或换通路，反而把一次成功的写入搅乱。
     *
     * 每拍都要重新按编号取节点，所以**每拍都要再核一次身份**：编号是前序下标，树一重排
     * 就换人，而音量页上 media/ring/alarm 是连着排的同类滑杆。不核身份就会把另一颗滑杆的
     * 当前值当回读交回去 —— 那比读不到更坏，因为那是一个看起来合理的假数字。
     */
    private suspend fun readBackUntilSettled(nodeId: Int, wanted: Float, identity: String): SettledRead {
        var rounds = 0
        var last: Float? = null
        // 让拍也不能越过这一次调用的预算：预算是闸门扣掉审批与回信余量之后剩下的那一段，
        // 超了它，信箱会把这条**已经成功**的写入判成 E_TRANSPORT_TIMEOUT。
        val deadline = interlock.relay.core.runtime.monotonicNow() +
            minOf(READBACK_BUDGET_MS, budgetMs.coerceAtLeast(0L))
        while (rounds < MAX_READBACK_ROUNDS) {
            last = readBackRange(nodeId, identity) ?: return SettledRead(null, rounds + 1)
            rounds += 1
            if (last == wanted || interlock.relay.core.runtime.monotonicNow() >= deadline) break
            delay(READBACK_POLL_MS)
        }
        return SettledRead(last, rounds)
    }

    /** 回读结果：最后一次读到的量程值，以及读了几拍。 */
    private data class SettledRead(val value: Float?, val rounds: Int)

    /**
     * 重新按编号取节点、读它现在的量程值。节点已被换掉、**换成了别的节点**、
     * 或没有量程则回 null。
     */
    private fun readBackRange(nodeId: Int, identity: String): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        val node = findByIdPreorder(root(), nodeId) ?: return null
        if (fingerprint(node) != identity) return null
        return node.rangeInfo?.current
    }

    /**
     * 量程。类型是 `AccessibilityNodeInfo` 的**嵌套**类 `RangeInfo`——
     * 与 `AccessibilityAction` 一样：按外层包名猜 FQN 会写成 `android.webkit.RangeInfo`，
     * 那个类在平台上不存在。
     */
    private fun rangeData(range: AccessibilityNodeInfo.RangeInfo): JSONObject = JSONObject().apply {
        put("min", range.min)
        put("max", range.max)
        put("current", range.current)
        // 交名字而不是 RANGE_TYPE_* 的那个整数：整数值在沙盒侧没有任何文档可对，
        // 而助手要判的是"这是离散档位还是连续量"。
        put("type", rangeTypeName(range.type))
    }

    private fun rangeTypeName(type: Int): String = when (type) {
        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT -> "int"
        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT -> "float"
        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT -> "percent"
        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INDETERMINATE -> "indeterminate"
        else -> "unknown($type)"
    }

    /**
     * 触发输入法的动作键。动作常量是 `AccessibilityAction.ACTION_IME_ENTER`（API 30），
     * 它不区分 DONE/SEARCH/SEND 具体是哪一种，所以这一条只覆盖"回车类"；
     * 要精确指定那几种，仍然只能去点界面上对应的按钮。
     *
     * 资格判据是**两问而不是一问**：节点自报带这个动作，或它本就是输入框一族。
     * 只按 `actionList` 判会把能用的节点拒掉 —— 平台常常不把 `ACTION_IME_ENTER`
     * 写进可编辑节点的自报动作里，而那类节点按回车是可行的。反过来，非输入框的节点
     * 回的是专用码（不是"节点没有这个动作"），助手据此换目标，而不是重发或去查权限。
     */
    fun imeAction(args: JSONObject): BackendResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return BackendResult.Failed(RelayError.CAPABILITY_UNAVAILABLE_ON_DEVICE, "ui.imeAction needs Android 11")
        }
        if (IME_ENTER_ID < 0) {
            return BackendResult.Failed(RelayError.CAPABILITY_UNAVAILABLE_ON_DEVICE, "no IME action on this device")
        }
        val picked = when (val target = resolve(args)) {
            is Target.Bad -> return bad(target)
            is Target.Many -> return ambiguous(target.matches)
            is Target.One -> target
        }
        val selfReports = picked.node.actionList.orEmpty().any { it.id == IME_ENTER_ID }
        if (!selfReports && !isEditableField(picked.node)) {
            return BackendResult.Failed(
                RelayError.NODE_NO_IME_ACTION,
                "node is not an editable field, so it has no IME action to trigger",
            )
        }
        return act(picked, IME_ENTER_ID, allowUnadvertised = !selfReports)
    }

    /** 输入框一族：按类名认，不看 `isText`（那个字段说的是节点自己有没有文字标签）。 */
    private fun isEditableField(node: AccessibilityNodeInfo): Boolean {
        val name = node.className?.toString().orEmpty()
        return name.endsWith("EditText") || name.endsWith("AutoCompleteTextView") ||
            name == "android.widget.EditText" || name.contains("TextInputEditText")
    }

    private fun numberArg(args: JSONObject, key: String): Double? =
        (args.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }

    /** 失败码原样带出：把码写进 reason 前缀等于让所有目标类失败都回成同一个码。 */
    private fun bad(target: Target.Bad): BackendResult.Failed =
        BackendResult.Failed(target.error, target.reason)

    private fun malformed(reason: String): BackendResult.Failed =
        BackendResult.Failed(RelayError.TRANSPORT_MALFORMED, reason)

    private fun actionName(action: Int): String = when (action) {
        AccessibilityNodeInfo.ACTION_CLICK -> "click"
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> "longClick"
        AccessibilityNodeInfo.ACTION_SELECT -> "select"
        AccessibilityNodeInfo.ACTION_DISMISS -> "dismiss"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scrollForward"
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scrollBackward"
        AccessibilityNodeInfo.ACTION_SET_TEXT -> "setValue"
        SET_PROGRESS_ID -> "setProgress"
        IME_ENTER_ID -> "imeAction"
        else -> "action($action)"
    }

    companion object {
        const val KEY_DIRECTION = "direction"
        const val KEY_TIMES = "times"
        const val KEY_UNTIL = "until"
        const val KEY_RELATIVE = "relative"
        const val KEY_SIDE = "side"
        const val KEY_NTH = "nth"
        const val KEY_SAME_CLASS = "sameClass"

        /** 相对定位最多往后数几个候选：超过它就是"这方向上没有第 N 个"，不是无限找。 */
        private const val MAX_RELATIVE_NTH = 20
        const val KEY_TEXT = "text"
        const val KEY_CHECKED = "checked"
        const val KEY_ABSENT = "absent"
        const val KEY_TIMEOUT_MS = "timeoutMs"
        const val KEY_PERCENT = "percent"
        const val KEY_VALUE = "value"

        private const val MAX_SCROLL_TIMES = 20
        private const val MAX_CANDIDATES = 8
        private const val WAIT_POLL_MS = 150L
        private const val DEFAULT_WAIT_MS = 3_000L
        private const val MAX_WAIT_MS = 15_000L

        /** 回读量程的让拍：控件把值交给别处再写回自己，要半拍才看得到。 */
        private const val READBACK_POLL_MS = 150L
        private const val MAX_READBACK_ROUNDS = 4
        private const val READBACK_BUDGET_MS = 600L

        /** 每一步派发后等量程变化的节拍与上限（约 640ms/步）。 */
        private const val SCROLL_SETTLE_POLL_MS = 80L
        private const val MAX_SCROLL_SETTLE_ROUNDS = 8

        /** 没有量程的容器不逐步核对，只按这一拍隔开派发，避免把动画吞掉的次数报成位移。 */
        private const val SCROLL_STEP_SETTLE_MS = 120L

        /** 整次滚动的让拍上限，与本次调用的预算取小者。 */
        private const val SCROLL_BUDGET_MS = 8_000L

        /** 给了 `until` 时的步数上限：从这里到目标值最多滚这么多格。 */
        private const val MAX_UNTIL_STEPS = 40

        /**
         * 两个动作常量声明在嵌套类 `AccessibilityNodeInfo.AccessibilityAction` 上，
         * 且 `ACTION_IME_ENTER` 要 API 30 —— 取值处各带一次版本判定，取不到就是 -1，
         * 而动作号都是正数，永远撞不上。
         */
        private val SET_PROGRESS_ID = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id

        private val IME_ENTER_ID = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
        } else {
            -1
        }
    }
}

/**
 * 「按编号取到的东西看不见」这条判据的本体。
 *
 * 做成文件顶层的纯函数而不是成员：`NodeActions` 的伴生对象里有依赖框架类的常量，
 * 在 JVM 单测里连类初始化都过不去，判据就钉不住。三种输入都是布尔，两种现场——位次在
 * 当前树里漂到了滚动区外的节点、编号取自另一块屏的那棵树——落下来是同一个可见性判断，
 * 所以按可见性给警告、把成因留给调用方核对，两种都覆盖得到。
 */
internal fun staleNodeIdReason(
    askedByNodeId: Boolean,
    visibleToUser: Boolean,
    hasOnScreenArea: Boolean,
    displayId: Int,
): String? {
    if (!askedByNodeId) return null
    if (visibleToUser && hasOnScreenArea) return null
    return "resolved by nodeId, but this node is not visible on display $displayId: this id is a " +
        "position in the tree this call just read, so if it came from an earlier snapshot the page " +
        "may have scrolled or refreshed and this position now holds a different node (an id taken " +
        "from another display always means a different tree). Take a fresh ui.snapshot on the display " +
        "you mean to act on and use the nodeId from it - do not act on this result as if it were the " +
        "intended node."
}
