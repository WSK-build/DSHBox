package interlock.relay.core.exec.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.ArgErrors
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.SurfaceKind
import interlock.relay.core.exec.BackendCall
import interlock.relay.core.exec.BackendResult
import interlock.relay.core.exec.RelayBackend
import interlock.relay.core.runtime.monotonicNow
import interlock.relay.core.storage.RelayPaths
import interlock.relay.core.storage.StorageReaper
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 无障碍通路。能力边界：手势只能作用于默认屏，`dispatchGesture` 不提供屏幕号参数；
 * 免系统确认框的截图需要 Android 11 以上。
 *
 * 节点级那十一条（含 `ui.snapshot`）的树从 [DisplayTree] 取，因此能落在可信虚拟屏上；
 * 坐标那四条仍只能作用于默认屏，要落后台屏时由 Shizuku 后端执行。两条通路的分工写在这里，
 * 注册表与 `supports` 都按它走。
 *
 * 指向本应用自身窗口的操作一律拒绝，避免助手通过操作确认框界面自行取得授权。
 * 该拒绝只表示「此刻宿主占着屏」，不是用户对能力的终态否决，见 [suspendedByHost]。
 */
class A11yBackend(
    context: Context,
    private val reaper: StorageReaper,
    /**
     * 可信虚拟屏此刻的编号，没有屏时为负数。由装配根从 Shizuku 后端取来：
     * 后端之间互不可见是这里的既有约束，跨后端的一个数只能这样递进来。
     */
    private val trustedDisplayId: () -> Int = { -1 },
) : RelayBackend {

    private val appContext = context.applicationContext
    private val selfPackage = appContext.packageName

    /** 截图与录屏共享同一采集会话，同时只允许一个在途。 */
    private val captureBusy = AtomicBoolean(false)

    /** 可信屏节点树的可得性：只记真的去取过的那几次结果，不按版本或机型推断。 */
    private val trustedNodeTree = TrustedNodeTree()

    override fun available(): Boolean = RelayAccessibilityService.current() != null

    override fun supports(capability: CapabilityId, surface: SurfaceKind): Boolean = when {
        // 节点级与快照：读的是哪棵树由 DisplayTree 说，两块屏都读得到，两块都服务。
        capability in TREE_CAPABILITIES -> surface == SurfaceKind.FOREGROUND ||
            (surface == SurfaceKind.BACKEND_TRUSTED && trustedDisplayReachable())

        // 坐标四条：`dispatchGesture` 没有屏幕号参数，落后台屏时只能由 Shizuku 后端做。
        capability in COORDINATE_CAPABILITIES -> surface == SurfaceKind.FOREGROUND

        capability == CapabilityId.SCREEN_CAPTURE ->
            surface == SurfaceKind.FOREGROUND && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        else -> false
    }

    /**
     * 那块屏的节点树取不取得到。
     *
     * 光有屏不够：跨屏取窗口只有 `getWindowsOnAllDisplays()` 按屏号分组的那一个签名，
     * 而它是 Android 13 起（见 [DisplayTree]）。低版本上屏在、树取不到，声明服务就会让助手
     * 在一条必然失败的通路上等，而不是如实降级到前台。
     */
    private fun trustedDisplayReachable(): Boolean =
        trustedDisplayId() >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    override suspend fun execute(call: BackendCall): BackendResult {
        // 键表排在取服务实例与查窗口之前：一次写错键的调用不该先付系统查询的成本。
        argsError(call)?.let { return it }
        // 服务实例可在「选好后端」之后才被系统解绑：这一刻够不着属于稍后再试，
        // 报成系统权限缺失会把助手引去重开无障碍。
        val service = RelayAccessibilityService.current()
            ?: return BackendResult.Failed(RelayError.BACKEND_UNAVAILABLE, "service not connected")
        // 只有会改动别的界面那一侧才算"驱动"。ui.node 与 ui.waitFor 是读，
        // 把它们一起挡下会让助手在用户切回宿主时彻底看不见界面。
        val controlsInput = call.descriptor.id in WRITING_CAPABILITIES
        // 宿主窗口占屏这一问的是"助手会不会点到自己的确认框"，那只可能发生在用户眼前这块屏上。
        // 落在可信虚拟屏的那一趟取的是那块屏的树，本应用在上面没有窗口；把两条一样处理，
        // 等于要求用户为了用后台通路而永远不许打开助手页。
        val onHostDisplay = call.surface != SurfaceKind.BACKEND_TRUSTED
        if (controlsInput && onHostDisplay && selfWindowVisible(service)) {
            return suspendedByHost()
        }
        // 节点级那十一条与快照共用同一棵树：编号是前序下标，两处不同源就会指错节点。
        // 坐标那四条走另一条分支，它们仍按默认屏的窗口自己取。
        if (call.descriptor.id in TREE_CAPABILITIES) {
            val outcome = treeFor(service, call)
            val tree = outcome.tree ?: return trustedTreeMissing(outcome)
            return when (call.descriptor.id) {
                CapabilityId.UI_SNAPSHOT -> snapshot(tree)
                CapabilityId.UI_CLICK -> nodeActions(tree, call).click(call.args)
                CapabilityId.UI_LONG_CLICK -> nodeActions(tree, call).longClick(call.args)
                CapabilityId.UI_SELECT -> nodeActions(tree, call).select(call.args)
                CapabilityId.UI_DISMISS -> nodeActions(tree, call).dismiss(call.args)
                CapabilityId.UI_SCROLL -> nodeActions(tree, call).scroll(call.args)
                CapabilityId.UI_SET_VALUE -> nodeActions(tree, call).setValue(call.args)
                CapabilityId.UI_NODE -> nodeActions(tree, call).readNode(call.args)
                CapabilityId.UI_WAIT_FOR -> nodeActions(tree, call).waitFor(call.args)
                CapabilityId.UI_SET_PROGRESS -> nodeActions(tree, call).setProgress(call.args)
                else -> nodeActions(tree, call).imeAction(call.args)
            }
        }
        return when (call.descriptor.id) {
            CapabilityId.UI_TAP -> click(service, call.args)
            CapabilityId.UI_SWIPE -> swipe(service, call.args)
            CapabilityId.UI_TEXT -> setText(service, call.args.optString(KEY_TEXT))
            CapabilityId.UI_KEY -> globalAction(service, call.args)
            // 版本判定就地再做一次：`supports()` 已经拦过，但"这条路径在 29 上不可达"
            // 是跨三个文件的推理，不写在这里就只有 lint 能看见。
            CapabilityId.SCREEN_CAPTURE ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) capture(service, call)
                else BackendResult.Failed(RelayError.CAPABILITY_UNAVAILABLE_ON_DEVICE, call.descriptor.id.wire)
            else -> BackendResult.Failed(RelayError.CAPABILITY_NOT_IMPLEMENTED, call.descriptor.id.wire)
        }
    }

    /** 一条能力接受的键集，以及其中不可缺、不可为空的键。 */
    internal data class ArgSpec(val allowed: Set<String> = emptySet(), val required: Set<String> = emptySet())

    /**
     * 本后端每条能力的键表，逐条见 [ARG_SPECS]。null 表示不由本后端实现，
     * 交给分发处回 `E_NOT_IMPLEMENTED`。
     */
    internal fun argSpec(id: CapabilityId): ArgSpec? = ARG_SPECS[id]

    /**
     * 参数来自沙盒，是不可信边界：未知键与空值一律拒，错误码用通道约定的
     * `E_TRANSPORT_MALFORMED`（退出码 1 = 改脚本，不是重试）。
     */
    private fun argsError(call: BackendCall): BackendResult? {
        val spec = argSpec(call.descriptor.id) ?: return null
        val args = call.args
        for (key in args.keys()) {
            if (key !in spec.allowed) return malformed(ArgErrors.unknown(key, spec.allowed))
        }
        for (key in spec.required) {
            if (args.optString(key).isBlank()) return malformed("missing arg: $key")
        }
        return null
    }

    private fun snapshot(tree: DisplayTree): BackendResult {
        val root = tree.root() ?: return noActiveWindow(tree.displayId)
        // 读不设宿主例外：要挡的是「助手驱动宿主自己的界面」，那是写操作，
        // 已在 execute() 里按 controlsInput 拦下。把读一起禁掉的后果是用户切回宿主那一刻
        // 助手读不到眼前界面，只能猜或干等；厂商助手都能读自己的界面。
        // 响应里的 package 会如实写着宿主，
        // 助手据此知道读到的是本模块的界面，不需要靠拒绝来表达这件事。
        val nodes = JSONArray()
        collect(root, nodes, 0, tree.screen)
        return BackendResult.Ok(
            data = JSONObject()
                .put("package", root.packageName?.toString())
                // 节点矩形与手势坐标同属这块屏的真实像素；不给这套基准，
                // 助手就只能从节点分布反推屏幕尺寸，反推出的是一个比真屏小的假尺寸。
                .put("screen", screenData(tree))
                .put("nodes", nodes)
                .put("truncated", nodes.length() >= MAX_NODES),
        )
    }

    /**
     * 节点级操作的入口。树与预算都在这里一次取好：十条能力各写一遍取树，
     * 改天就会有一条漏了预算、另一条用了别的屏的尺寸。
     */
    private fun nodeActions(tree: DisplayTree, call: BackendCall): NodeActions =
        NodeActions(tree, call.budgetMs, selfPackage) { node ->
            // 同一个判据、同一个包名表，只是问的时机不同：闸门问参数，这里问树上解析出来的那颗。
            interlock.relay.core.interlock.ConsentSurfaces.looksLikeConsentControl(
                node.packageName?.toString(), node.viewIdResourceName,
            )
        }

    /**
     * 只读回查节点归属，给闸门判「是不是系统授权界面」用。
     *
     * 预算传 0：这一问不发起任何操作，也不该占掉调用预算；服务没连上时回 null，
     * 由调用方按「认不出来」处理 —— 认不出来不等于放行，只是少一道判据。
     *
     * 两块屏都问一遍：系统授权页既可能挂在用户眼前这块屏上，也可能挂在助手自己开在
     * 后台屏上的那颗窗口里，只查前者会漏掉后一种，而漏掉的代价正是这道判据要防的事。
     */
    override fun targetOwner(nodeId: Int): RelayBackend.TargetOwner? {
        val service = RelayAccessibilityService.current() ?: return null
        val trees = listOf(hostTree(service)) +
            (if (trustedDisplayReachable()) listOfNotNull(trustedTree(service)) else emptyList())
        for (tree in trees) {
            val owner = NodeActions(tree, 0L, selfPackage).ownerOf(nodeId) ?: continue
            return RelayBackend.TargetOwner(owner.first, owner.second)
        }
        return null
    }

    /**
     * 这一次调用要读哪块屏的树，取不到时带上为什么取不到。
     *
     * 只有"问成了而那块屏没有窗口"才记进能力清单那句 `nodeTree`：**这一问本身没成**不能记，
     * 记了就等于把一次调用失败说成平台的边界，助手会照着它放弃一条其实只是暂时不通的路。
     * 闸门那句"这颗节点是谁家的"也不记 —— 它碰那块屏是为了认系统授权页，与"节点级能力在
     * 这台机器上落不落得地"不是一回事。
     */
    private fun treeFor(service: RelayAccessibilityService, call: BackendCall): TreeOutcome {
        if (call.surface != SurfaceKind.BACKEND_TRUSTED) return TreeOutcome(hostTree(service))
        val displayId = trustedDisplayId()
        return when (val look = DisplayTree.ofDisplay(appContext, service, displayId)) {
            is TreeLook.Has -> {
                if (displayId >= 0) trustedNodeTree.observe(displayId, true)
                TreeOutcome(look.tree, displayId = displayId)
            }
            TreeLook.NoWindowOnDisplay -> {
                if (displayId >= 0) trustedNodeTree.observe(displayId, false)
                TreeOutcome(null, MISSING_NO_WINDOW, displayId)
            }
            // 这一问本身失败：不记，能力清单上那句说的只能是"问到过的话，答案是什么"。
            is TreeLook.QueryFailed -> TreeOutcome(null, look.cause, displayId)
        }
    }

    /**
     * 取不到树的一次结果。[displayId] 是**这次真的去问的那块屏** —— 报错文案里的屏号必须
     * 跟着这一次走，不能在回答时再问一遍全局状态：那中间屏可能已经被回收或换号，于是回包
     * 报的是一个从没查过的屏号。
     */
    private class TreeOutcome(
        val tree: DisplayTree?,
        val missing: String? = null,
        val displayId: Int = -1,
    )

    /** 去那块屏取一次树。只读回查用，不记进清单。 */
    private fun trustedTree(service: RelayAccessibilityService): DisplayTree? =
        (DisplayTree.ofDisplay(appContext, service, trustedDisplayId()) as? TreeLook.Has)?.tree

    /**
     * 清单上那句 `trustedDisplay.nodeTree`：上一次真去取那块屏的树时，取到没有。
     *
     * [displayId] 由调用方给出，且必须是同一份清单里 `displayId` 那一项用的同一个数：
     * 这句要经由另一个判据（额外的"就绪"条件）换算，就会出现「alive 与 displayId 说屏在、
     * nodeTree 说没测过」这种自相矛盾的组合，而那个换算还会把取树记录清掉。
     */
    fun trustedNodeTreeState(displayId: Int): String = trustedNodeTree.state(displayId)

    /**
     * 落在可信屏上却没拿到树。三种说法对应三件不同的下一步，合成一句就会被照着做错的事。
     *
     * 三种说法各对应一件不同的下一步，合成一句就会被照着做错事：
     * 屏已建好、应用也确实落在上面（`app.launch` 回 `landedOn` 与 `topOnTarget`）时，
     * 「先去建屏、再开应用」这句话只会让同一条失败原地重跑一次。
     *
     * **这一问本身失败**不能说成「平台不给树」：那是把一次调用失败写成设备边界，
     * 一条只是暂时不通的路会被就此放弃。
     *
     * 措辞上不说「那块屏还活着」：`displayId` 来自装配根缓存的编号，屏可能被服务端五分钟
     * 空闲收掉而这里不对账，支撑得起的说法是「上一次记下的那块屏」。
     */
    private fun trustedTreeMissing(outcome: TreeOutcome): BackendResult {
        val displayId = outcome.displayId
        val reason = when {
            displayId < 0 ->
                "no trusted display to read: create one with surface.virtual and launch an app " +
                    "on it, or run on the foreground surface"
            outcome.missing == MISSING_NO_WINDOW ->
                "the accessibility service was given no window for trusted display $displayId " +
                    "(the id we recorded the last time it answered): node-level calls cannot read " +
                    "that screen right now - use ui.tap / ui.swipe for coordinates or " +
                    "screen.capture / screen.record for pixels"
            else ->
                "the cross-display window query for trusted display $displayId did not answer " +
                    "(${outcome.missing}): that is not the same as the display having no windows " +
                    "- retry, or run on the foreground surface"
        }
        return BackendResult.Failed(RelayError.BACKEND_UNAVAILABLE, reason)
    }

    /** 用户眼前这块屏：活动窗口，与这条通路一直以来的取法一致。 */
    private fun hostTree(service: RelayAccessibilityService): DisplayTree = DisplayTree(
        displayId = Display.DEFAULT_DISPLAY,
        screen = displayBounds(),
        densityDpi = appContext.resources.displayMetrics.densityDpi,
        rootProvider = DisplayTree.retryOnce { service.rootInActiveWindow },
    )

    /**
     * 默认屏的真实像素边框，即手势坐标系与节点 bounds 的共同基准。
     * 不用 `resources.displayMetrics`：它在 30 以上会跟随应用窗口收缩（分屏、
     * 尺寸兼容模式都会变小），拿它当屏幕尺寸会把屏内合法坐标判成越界。
     */
    @Suppress("DEPRECATION") // 30 以下没有窗口度量接口，真实尺寸只能从 Display 取
    private fun displayBounds(): Rect {
        val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.maximumWindowMetrics.bounds
        }
        val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    /** 坐标基准随数据一起给出：单位是像素还是 dp、属于哪一块屏，助手不必猜。 */
    private fun screenData(tree: DisplayTree): JSONObject = JSONObject().apply {
        put("width", tree.screen.width())
        put("height", tree.screen.height())
        // 本进程看不见那块屏（跨 UID 创建且未公开）时密度报不出来。宁可缺这个键，
        // 也不拿默认屏的密度去冒充另一块屏的 —— 助手按它换算 dp 会换算出一个假尺寸。
        if (tree.densityDpi > 0) put("densityDpi", tree.densityDpi)
        put("display", tree.displayId)
    }

    /**
     * 把节点矩形归一到屏幕内：左右、上下各自排序后与屏幕求交。
     * 部分厂商页面（启动器与自带浏览器结果页）会给出反向或飞出屏幕的矩形，
     * 原样交给助手就是一个指向屏外的假目标。
     */
    private fun visibleBounds(raw: Rect, screen: Rect): Rect? = normalizeBounds(raw, screen)

    // isChecked 自 Android 13 起被整型状态取代；替换方法的可用版本下限无法从离线平台存根确认，
    // 而本模块的最低支持版本低于它，故仍用这个虽标记废弃但各版本都存在取值器。
    @Suppress("DEPRECATION")
    private fun collect(node: AccessibilityNodeInfo?, out: JSONArray, depth: Int, screen: Rect) {
        if (node == null || out.length() >= MAX_NODES || depth > MAX_DEPTH) return
        val visible = visibleBounds(Rect().also { node.getBoundsInScreen(it) }, screen)
        // 编号即"这颗节点在这棵树里的前序位次"，必须在 put 之前取：它就是本条要落的下标。
        // 与 NodeSelector 的分配规则同源，助手才能拿快照里的编号直接喂给 ui.click；
        // 两处规则一旦分叉，编号会安静地指到另一颗节点上，那是一次看起来成功的错误点击。
        val nodeId = out.length()
        out.put(
            JSONObject().apply {
                put("nodeId", nodeId)
                put("class", node.className?.toString().orEmpty())
                // 只补这一个状态位：`ui.scroll` 要求节点 isScrollable，而快照不报它
                // 就等于让助手去滚一个它无法找到的目标。其余状态位交给 ui.node 单读，
                // 全量节点树里每个都带六个布尔会把快照撑爆。
                if (node.isScrollable) put("scrollable", true)
                node.text?.toString()?.takeIf { it.isNotEmpty() }?.let { put("text", it) }
                node.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { put("desc", it) }
                // 归一后没有可见面积的节点只给标记不给矩形：矩形是助手的点击目标，
                // 空框与负尺寸都会被当成一个屏内可点位置。
                if (visible == null) put("offscreen", true)
                else {
                    put("left", visible.left)
                    put("top", visible.top)
                    put("right", visible.right)
                    put("bottom", visible.bottom)
                }
                if (node.isClickable) put("clickable", true)
                if (node.isEditable) put("editable", true)
                if (node.isChecked) put("checked", true)
            },
        )
        for (index in 0 until node.childCount) collect(node.getChild(index), out, depth + 1, screen)
    }

    /**
     * 手势只给坐标不给目标，因此「当前屏幕上有没有本应用自己的窗口」必须在下发前查。
     * 确认框就叠在屏幕最上层，点中它等于助手自己批了自己；节点树与输入路径已各自
     * 检查过归属，这条路径不能漏。
     *
     * 窗口本身不带包名公开访问器，归属只能从窗口根节点取。
     */
    private fun selfWindowVisible(service: RelayAccessibilityService): Boolean =
        service.windows.orEmpty().any { it.root?.packageName?.toString() == selfPackage }

    /**
     * 宿主窗口占屏时的失败。规则本身不松：助手不得驱动宿主自己的界面；
     * 但这是用户切走一下就消失的瞬时状态，报成终态拒绝会让助手把整条任务链
     * 当成「用户已否」而放弃，只能干等到客户端超时。
     */
    private fun suspendedByHost(): BackendResult.Failed = BackendResult.Failed(
        RelayError.GATE_SUSPENDED_BY_HOST,
        "host window is in front: retry after the user leaves this screen",
    )

    /**
     * `rootInActiveWindow` 为空。没有焦点窗口多半是过渡态（刚切页、输入法还没起来），
     * 而 `E_GATE_SYSTEM_MISSING`（退出码 4）读起来是「用户先去开权限」——那条路径走不通，
     * 正确答案是原样重发一次。真缺权限由 `available()` 与闸门各自判。
     */
    /**
     * 「这次没读到树」。屏号必须带上：节点级那几条同一件事的说法是
     * `no window tree to read on display N`，快照这句不带屏号就没法比对两次调用读的是不是一块屏。
     */
    private fun noActiveWindow(displayId: Int): BackendResult.Failed = BackendResult.Failed(
        RelayError.BACKEND_UNAVAILABLE,
        "no window tree to read on display $displayId after a second read: no window holds focus there - retry once one does",
    )

    /** `ui.tap` 的点击形状：`{"x","y"}`，不接受任何其它键（手势键在键表那一层就被拒）。 */
    private suspend fun click(service: RelayAccessibilityService, args: JSONObject): BackendResult {
        badArgShape(args, POINT_KEYS, POINT_KEYS)?.let { return malformed(it) }
        val screen = displayBounds()
        badCoordinate(args, mapOf(KEY_X to screen.width(), KEY_Y to screen.height()))
            ?.let { return malformed(it) }
        val x = numberArg(args, KEY_X) ?: return malformed(ArgErrors.notNumber(KEY_X, args.opt(KEY_X)))
        val y = numberArg(args, KEY_Y) ?: return malformed(ArgErrors.notNumber(KEY_Y, args.opt(KEY_Y)))
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat(), y.toFloat())
        }
        return dispatchStroke(service, path, TAP_DURATION_MS)
    }

    /**
     * 滑动形状：`{"fromX","fromY","toX","toY"}`，可选 `{"durationMs"}`。
     * 只有 `ui.swipe` 走这里；`ui.tap` 在键表那一层就拒掉手势键，不再共用这份解析。
     */
    internal suspend fun swipe(service: RelayAccessibilityService, args: JSONObject): BackendResult {
        badArgShape(args, STROKE_KEYS, SWIPE_KEYS)?.let { return malformed(it) }
        val screen = displayBounds()
        badCoordinate(
            args,
            mapOf(
                KEY_FROM_X to screen.width(),
                KEY_TO_X to screen.width(),
                KEY_FROM_Y to screen.height(),
                KEY_TO_Y to screen.height(),
            ),
        )?.let { return malformed(it) }
        val durationMs = if (args.has(KEY_DURATION_MS)) {
            numberArg(args, KEY_DURATION_MS)?.toLong()?.coerceIn(MIN_GESTURE_MS, MAX_GESTURE_MS)
                ?: return malformed(ArgErrors.notNumber(KEY_DURATION_MS, args.opt(KEY_DURATION_MS)))
        } else {
            SWIPE_DURATION_MS
        }
        val fromX = numberArg(args, KEY_FROM_X) ?: return malformed(ArgErrors.notNumber(KEY_FROM_X, args.opt(KEY_FROM_X)))
        val fromY = numberArg(args, KEY_FROM_Y) ?: return malformed(ArgErrors.notNumber(KEY_FROM_Y, args.opt(KEY_FROM_Y)))
        val toX = numberArg(args, KEY_TO_X) ?: return malformed(ArgErrors.notNumber(KEY_TO_X, args.opt(KEY_TO_X)))
        val toY = numberArg(args, KEY_TO_Y) ?: return malformed(ArgErrors.notNumber(KEY_TO_Y, args.opt(KEY_TO_Y)))
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        return dispatchStroke(service, path, durationMs)
    }

    /**
     * 一种形状内的键严格校验：先报多余键再报缺失键。两形状在 [argSpec] 里合并放行，
     * 混用的那一格就在这里被分开。缺键会让 org.json 抛异常，被分发层记成 E_INTERNAL
     * （退出码 6，读起来像宿主坏了，真相是脚本写错了）。
     */
    private fun badArgShape(args: JSONObject, required: Collection<String>, allowed: Set<String>): String? {
        args.keys().asSequence().firstOrNull { it !in allowed }?.let { return ArgErrors.unknown(it, allowed) }
        return required.firstOrNull { !args.has(it) }?.let { "missing arg: $it" }
    }

    /**
     * 坐标必须是默认屏内的有限数字。越界不拦的后果最坏：`dispatchGesture` 对
     * 屏外坐标不回错，系统只是什么都没做，一次落在另一套坐标空间里的点击就报成成功。
     */
    private fun badCoordinate(args: JSONObject, limits: Map<String, Int>): String? {
        for ((key, limit) in limits) {
            val value = numberArg(args, key) ?: return ArgErrors.notNumber(key, args.opt(key))
            if (value < 0 || value >= limit) return "$key out of screen [0,$limit): $value"
        }
        return null
    }

    /**
     * 数值参数的唯一取法：只认 JSON 数字。
     *
     * 两件事必须在这里挡住。一是 `getDouble` 对非数字值抛 JSONException，会被分发层记成
     * E_INTERNAL（退出码 6，读起来像宿主自己坏了，真相是脚本写错）；二是 `optString`
     * 会把布尔悄悄变成 "1"，于是 `{"x":true}` 一路通过校验、又在取值处炸掉。
     * 数字字符串同样拒：坐标写不成数字就是脚本错了，替它猜一个值等于把错用一次。
     */
    private fun numberArg(args: JSONObject, key: String): Double? =
        (args.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun malformed(reason: String): BackendResult.Failed =
        BackendResult.Failed(RelayError.TRANSPORT_MALFORMED, reason)

    /** 下发一笔手势，并按系统真正回执的结果作答。 */
    private suspend fun dispatchStroke(
        service: RelayAccessibilityService,
        path: Path,
        requestedMs: Long,
    ): BackendResult {
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, requestedMs))
            .build()
        val startedAt = monotonicNow()
        val failure = service.dispatchAwaited(description)
        // 回实际耗时而不是请求值：系统会合并、缩短甚至掐掉一笔手势，只有回执时刻真实发生过。
        val observedMs = monotonicNow() - startedAt
        if (failure != null) {
            return BackendResult.Failed(RelayError.BACKEND_UNAVAILABLE, "gesture $failure")
        }
        return BackendResult.Ok(data = JSONObject().put("durationMs", observedMs))
    }

    /**
     * 等系统回执。返回 null 表示手势确实执行完；非空是失败原因。
     * 「发出去了」不等于「执行了」：只有 onCompleted 才算成功，onCancelled 与被
     * `dispatchGesture` 当场拒绝都必须落成失败，否则助手会按成功继续往下推。
     */
    private suspend fun RelayAccessibilityService.dispatchAwaited(
        description: GestureDescription,
    ): String? = suspendCancellableCoroutine { continuation ->
        val settled = AtomicBoolean(false)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (settled.compareAndSet(false, true)) continuation.resume(null)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (settled.compareAndSet(false, true)) continuation.resume("cancelled")
            }
        }
        if (!dispatchGesture(description, callback, null) && settled.compareAndSet(false, true)) {
            continuation.resume("rejected")
        }
    }

    private fun setText(service: RelayAccessibilityService, text: String): BackendResult {
        // 这一条只服务前台那块屏：`rootInActiveWindow` 只在默认屏上判定得出来。
        val root = service.rootInActiveWindow ?: return noActiveWindow(Display.DEFAULT_DISPLAY)
        if (root.packageName?.toString() == selfPackage) {
            return suspendedByHost()
        }
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: firstEditable(root)
            ?: return BackendResult.Failed(RelayError.NO_EDITABLE_TARGET, "no editable target")
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val applied = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        return if (applied) {
            BackendResult.Ok(data = JSONObject().put("length", text.length))
        } else {
            BackendResult.Failed(RelayError.BACKEND_UNAVAILABLE, "target rejected text")
        }
    }

    /**
     * 第一个可编辑节点，只作为"当前没有聚焦输入框"时的兜底。
     * 遍历上界与快照同源：通道同一时刻只跑一条请求，而这里找的只是"这一屏上有没有输入框"，
     * 越过界还没有就该回"没有"，而不是一棵深树走到天荒地老。
     */
    private fun firstEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var visited = 0
        var found: AccessibilityNodeInfo? = null
        fun descend(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > MAX_DEPTH || visited >= MAX_NODES) return false
            visited++
            if (node.isEditable) {
                found = node
                return true
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                if (descend(child, depth + 1)) return true
            }
            return false
        }
        root?.let { descend(it, 0) }
        return found
    }

    /** 接受的全局动作名只有这一份：取值清单与错误文案都从这里读，不允许出现第二套列表。 */
    private fun globalAction(service: RelayAccessibilityService, args: JSONObject): BackendResult {
        val requested = args.optString(KEY_KEY).lowercase()
        val action = GLOBAL_ACTIONS[requested]
            ?: return malformed("unknown value for $KEY_KEY: $requested, accepted: ${GLOBAL_ACTIONS.keys}")
        return if (service.performGlobalAction(action)) {
            BackendResult.Ok(data = JSONObject().put(KEY_KEY, requested))
        } else {
            BackendResult.Failed(RelayError.BACKEND_UNAVAILABLE, "action unavailable: $requested")
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun capture(service: RelayAccessibilityService, call: BackendCall): BackendResult {
        if (!captureBusy.compareAndSet(false, true)) {
            return BackendResult.Failed(RelayError.RATE_LIMITED, "capture already in flight")
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val shot = service.screenshot(executor)
            val bitmap = when (shot) {
                is Shot.Ok -> shot.bitmap
                is Shot.Rejected -> return BackendResult.Failed(shot.error, shot.detail)
            }
            val estimate = estimatePngBytes(bitmap.width, bitmap.height)
            val staged = reaper.newArtifactFile(call.requestId, "png", estimate)
                ?: return BackendResult.Failed(RelayError.STORAGE_FULL, "quota exhausted")
            // 硬件位图不可直接压缩，先转为软件位图再写盘，随后两者一并回收。
            val software = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            bitmap.recycle()
            if (software == null) {
                staged.delete()
                return BackendResult.Failed(RelayError.SURFACE_SECURE_WINDOW, "convert rejected")
            }
            val bytes = writePng(software, staged)
            software.recycle()
            if (bytes <= 0L) {
                staged.delete()
                return BackendResult.Failed(RelayError.STORAGE_FULL, "artifact write failed")
            }
            val artifact = reaper.publishArtifact(staged, call.requestId)
                ?: return BackendResult.Failed(RelayError.STORAGE_FULL, "artifact publish failed")
            // 换不到沙盒侧地址就不能回宿主绝对路径：助手拿到的地址必须能自己打开。
            val guestPath = RelayPaths.toGuestPath(artifact, call.paths)
                ?: return BackendResult.Failed(RelayError.INTERNAL, "artifact path unmappable").also {
                    reaper.discard(artifact)
                }
            return BackendResult.Ok(
                data = JSONObject().put("bytes", bytes),
                artifacts = listOf(guestPath),
                artifactBytes = bytes,
            )
        } finally {
            executor.shutdown()
            captureBusy.set(false)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun RelayAccessibilityService.screenshot(
        executor: java.util.concurrent.Executor,
    ): Shot = suspendCancellableCoroutine { continuation ->
        val settled = AtomicBoolean(false)
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val bitmap = runCatching {
                        Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    }.getOrNull()
                    // 这块 gralloc 缓冲归调用方所有：`wrapHardwareBuffer` 只是让 Bitmap 引用
                    // 它，不关就要等 finalizer；wrap 失败时更是只有这一份引用。
                    runCatching { result.hardwareBuffer.close() }
                    if (settled.compareAndSet(false, true)) {
                        if (bitmap != null) {
                            continuation.resume(Shot.Ok(bitmap))
                        } else {
                            continuation.resume(Shot.Rejected(RelayError.SURFACE_SECURE_WINDOW, "wrap failed"))
                        }
                    } else if (bitmap != null) {
                        runCatching { bitmap.recycle() }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (settled.compareAndSet(false, true)) {
                        continuation.resume(Shot.Rejected(mapScreenshotError(errorCode), "code=$errorCode"))
                    }
                }
            },
        )
    }

    /** 系统给出的失败码逐个映射，助手才能区分「稍后重试」与「永远做不到」。 */
    private fun mapScreenshotError(code: Int): RelayError = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> RelayError.SURFACE_SECURE_WINDOW
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> RelayError.RATE_LIMITED
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY,
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW,
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS,
        -> RelayError.GATE_SYSTEM_MISSING

        else -> RelayError.GATE_SYSTEM_MISSING
    }

    private sealed class Shot {
        class Ok(val bitmap: Bitmap) : Shot()
        class Rejected(val error: RelayError, val detail: String) : Shot()
    }

    /** 写盘前的配额估算：按未压缩 RGBA 上界计，宁可高估也不允许越过配额后全量清空。 */
    private fun estimatePngBytes(width: Int, height: Int): Long =
        (width.toLong() * height.toLong() * BYTES_PER_PIXEL).coerceAtMost(MAX_ESTIMATE_BYTES)

    private fun writePng(bitmap: Bitmap, target: File): Long = runCatching {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)
        }
        target.length()
    }.getOrDefault(0L)

    internal companion object {
        const val KEY_TEXT = "text"
        const val KEY_KEY = "key"
        const val KEY_X = "x"
        const val KEY_Y = "y"
        const val KEY_FROM_X = "fromX"
        const val KEY_FROM_Y = "fromY"
        const val KEY_TO_X = "toX"
        const val KEY_TO_Y = "toY"
        const val KEY_DURATION_MS = "durationMs"

        /** 点击形状的键：既是必填项，也不允许再多出任何键。 */
        val POINT_KEYS = setOf(KEY_X, KEY_Y)

        /** 节点级目标的两种顶层形状。 */
        val TARGET_KEYS = setOf(NodeSelector.KEY_SELECTOR, NodeSelector.KEY_NODE_ID, NodeActions.KEY_RELATIVE)

        /**
         * 坐标通路：只认默认屏，落后台屏时由 Shizuku 后端执行。
         * 分组的依据是"要不要一棵跨屏取得到的树"，不是"是不是写" —— 两条通路的落点规则不同。
         */
        val COORDINATE_CAPABILITIES = setOf(
            CapabilityId.UI_TAP,
            CapabilityId.UI_SWIPE,
            CapabilityId.UI_TEXT,
            CapabilityId.UI_KEY,
        )

        /** 只读的节点级能力：不改动别的界面，因此不受宿主占屏那条限制。 */
        val NODE_READ_CAPABILITIES = setOf(
            CapabilityId.UI_SNAPSHOT,
            CapabilityId.UI_NODE,
            CapabilityId.UI_WAIT_FOR,
        )

        /** 会改动别的界面那一侧的节点级能力。 */
        val NODE_WRITE_CAPABILITIES = setOf(
            CapabilityId.UI_CLICK,
            CapabilityId.UI_LONG_CLICK,
            CapabilityId.UI_SELECT,
            CapabilityId.UI_DISMISS,
            CapabilityId.UI_SCROLL,
            CapabilityId.UI_SET_VALUE,
            CapabilityId.UI_SET_PROGRESS,
            CapabilityId.UI_IME_ACTION,
        )

        /**
         * 靠一棵树干活的十一条能力。快照必须在内：`nodeId` 是前序下标，
         * 它取树的规则与按编号回读的规则不同源，编号就会指到另一颗节点上。
         */
        val TREE_CAPABILITIES = NODE_READ_CAPABILITIES + NODE_WRITE_CAPABILITIES

        /**
         * "问成了，那块屏上此刻没有一颗有树的窗口"。只有这一种结果会写进能力清单那句
         * `trustedDisplay.nodeTree` —— 跨屏查询本身失败时不能替平台表态（见 [trustedTreeMissing]）。
         */
        const val MISSING_NO_WINDOW = "no-window-on-display"

        /**
         * 宿主窗口占屏时要挡的能力。读的那三条不在内：挡掉它们只会让助手在用户切回宿主时
         * 读不到眼前界面。
         */
        val WRITING_CAPABILITIES = COORDINATE_CAPABILITIES + NODE_WRITE_CAPABILITIES

        /** 滑动的四个必填键。 */
        val STROKE_KEYS = setOf(KEY_FROM_X, KEY_FROM_Y, KEY_TO_X, KEY_TO_Y)

        /** `ui.swipe` 的键：四必填的手势键加可选时长。 */
        val SWIPE_KEYS = STROKE_KEYS + KEY_DURATION_MS

        const val TAP_DURATION_MS = 60L
        const val SWIPE_DURATION_MS = 260L
        const val MIN_GESTURE_MS = 50L
        const val MAX_GESTURE_MS = 3_000L
        const val MAX_NODES = 600
        const val MAX_DEPTH = 40
        const val PNG_QUALITY = 100
        const val BYTES_PER_PIXEL = 4L
        const val MAX_ESTIMATE_BYTES = 32L * 1024 * 1024
        const val GLOBAL_BACK = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
        const val GLOBAL_HOME = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
        const val GLOBAL_RECENTS = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS

        /** 全局键与动作常量的对应关系只在此处一份，接受清单与解析共用它。 */
        val GLOBAL_ACTIONS = mapOf(
            "back" to GLOBAL_BACK,
            "home" to GLOBAL_HOME,
            "recents" to GLOBAL_RECENTS,
        )

        /**
         * 本后端每条能力的键表。null 表示不由本后端实现，交给分发处回 `E_NOT_IMPLEMENTED`。
         *
         * 一张表加一条规则，而不是各能力各写判据：没进表的键会被静默丢掉，助手却以为它生效了，
         * 于是同一处参数名写错在一条通路上报「unknown arg」、在另一条通路上跑成别的事。
         * 正因如此 `ui.tap` 只留一种形状：滑动手势归 `ui.swipe`，这里不接受任何手势键。
         * 表以 internal 暴露：分发前后的两张键表必须逐格对齐，对齐判据要能跨层引用同一份。
         * 声明放在上面那些键组之后：伴生对象按书写顺序初始化，表在前面就取不到键组。
         */
        internal val ARG_SPECS: Map<CapabilityId, ArgSpec> = mapOf(
            CapabilityId.UI_SNAPSHOT to ArgSpec(),
            CapabilityId.SCREEN_CAPTURE to ArgSpec(),

            CapabilityId.UI_TAP to ArgSpec(POINT_KEYS, POINT_KEYS),
            CapabilityId.UI_SWIPE to ArgSpec(SWIPE_KEYS, STROKE_KEYS),
            CapabilityId.UI_TEXT to ArgSpec(setOf(KEY_TEXT), setOf(KEY_TEXT)),
            // 节点级：目标两形状（selector / nodeId）由 NodeSelector 自己判，这里只放行顶层键。
            CapabilityId.UI_CLICK to ArgSpec(TARGET_KEYS),
            CapabilityId.UI_LONG_CLICK to ArgSpec(TARGET_KEYS),
            CapabilityId.UI_SELECT to ArgSpec(TARGET_KEYS),
            CapabilityId.UI_DISMISS to ArgSpec(TARGET_KEYS),
            CapabilityId.UI_NODE to ArgSpec(TARGET_KEYS),
            CapabilityId.UI_IME_ACTION to ArgSpec(TARGET_KEYS),

            // `direction` 进了必填集，与手册上的星号对齐：只放 allowed 时省略它要走到
            // NodeActions 才被发现，回的是「must be forward or backward」——
            // 少一个键被说成一个键值写错，助手会去换拼写而不是补上那个键。
            CapabilityId.UI_SCROLL to ArgSpec(
                TARGET_KEYS + NodeActions.KEY_DIRECTION + NodeActions.KEY_TIMES + NodeActions.KEY_UNTIL,
                setOf(NodeActions.KEY_DIRECTION),
            ),
            CapabilityId.UI_SET_VALUE to ArgSpec(TARGET_KEYS + NodeActions.KEY_TEXT, setOf(NodeActions.KEY_TEXT)),
            CapabilityId.UI_SET_PROGRESS to ArgSpec(TARGET_KEYS + NodeActions.KEY_PERCENT + NodeActions.KEY_VALUE),
            CapabilityId.UI_WAIT_FOR to ArgSpec(
                TARGET_KEYS + NodeActions.KEY_TEXT + NodeActions.KEY_CHECKED +
                    NodeActions.KEY_ABSENT + NodeActions.KEY_TIMEOUT_MS,
            ),
            CapabilityId.UI_KEY to ArgSpec(setOf(KEY_KEY), setOf(KEY_KEY)),
        )
    }
}
